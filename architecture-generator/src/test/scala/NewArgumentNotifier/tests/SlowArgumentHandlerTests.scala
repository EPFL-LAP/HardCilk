package NewArgumentNotifier.tests

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import NewArgumentNotifier.SlowArgumentHandler

class SlowArgumentHandlerTests extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "SlowArgumentHandler"

  private val counterWidth = 8
  private val sysAddressWidth = 32
  private val continuationSize = 128
  private val lineShift = 4
  private val lineAddressWidth = sysAddressWidth - lineShift

  private val mkLine = NanTestUtil.line(counterWidth, continuationSize) _

  private def dutGen =
    new SlowArgumentHandler(
      counterWidth,
      sysAddressWidth,
      lineAddressWidth,
      lineShift,
      continuationSize,
      axiIdWidth = 2
    )

  private def init(dut: SlowArgumentHandler): Unit = {
    dut.io.slowUpdateIn.valid.poke(false.B)
    dut.io.spawnOut.ready.poke(true.B)
    dut.m_axi.ar.ready.poke(true.B)
    dut.m_axi.r.valid.poke(false.B)
    dut.m_axi.r.bits.id.poke(0.U)
    dut.m_axi.aw.ready.poke(true.B)
    dut.m_axi.w.ready.poke(true.B)
    dut.m_axi.b.valid.poke(false.B)
    dut.m_axi.b.bits.id.poke(0.U)
  }

  private def push(
      dut: SlowArgumentHandler,
      addr: BigInt,
      data: BigInt,
      strobe: BigInt
  ): Unit = {
    dut.io.slowUpdateIn.bits.address.poke((addr >> lineShift).U)
    // `data` and `strobe` describe the logical remainder; physically it begins
    // immediately above the low counter field.
    dut.io.slowUpdateIn.bits.dataWrite.poke((data << counterWidth).U)
    dut.io.slowUpdateIn.bits.dataWriteStrobe.poke((strobe << counterWidth).U)
    dut.io.slowUpdateIn.valid.poke(true.B)
    var guard = 0
    while (!dut.io.slowUpdateIn.ready.peek().litToBoolean) {
      dut.clock.step(); guard += 1; assert(guard < 50)
    }
    dut.clock.step()
    dut.io.slowUpdateIn.valid.poke(false.B)
  }

  // Wait for AR, check its address, and return how many cycles it took.
  private def expectAr(dut: SlowArgumentHandler, addr: BigInt, max: Int): Int = {
    var cycles = 0
    while (!dut.m_axi.ar.valid.peek().litToBoolean) {
      dut.clock.step(); cycles += 1
      assert(cycles < max, "AR never issued")
    }
    dut.m_axi.ar.bits.addr.expect(addr.U)
    dut.clock.step() // ar.ready is high -> consumed
    cycles
  }

  private def respondR(dut: SlowArgumentHandler, data: BigInt): Unit = {
    dut.m_axi.r.bits.data.poke(data.U)
    dut.m_axi.r.bits.id.poke(0.U)
    dut.m_axi.r.bits.last.poke(true.B)
    dut.m_axi.r.valid.poke(true.B)
    var guard = 0
    while (!dut.m_axi.r.ready.peek().litToBoolean) {
      dut.clock.step(); guard += 1; assert(guard < 20)
    }
    dut.clock.step()
    dut.m_axi.r.valid.poke(false.B)
  }

  private def expectWriteBack(dut: SlowArgumentHandler, addr: BigInt, data: BigInt): Unit = {
    var sawAw = false
    var sawW = false
    var guard = 0
    while (!(sawAw && sawW)) {
      if (dut.m_axi.aw.valid.peek().litToBoolean) {
        dut.m_axi.aw.bits.addr.expect(addr.U)
        sawAw = true
      }
      if (dut.m_axi.w.valid.peek().litToBoolean) {
        dut.m_axi.w.bits.data.expect(data.U)
        sawW = true
      }
      dut.clock.step(); guard += 1
      assert(guard < 20, "write-back never issued")
    }
    dut.m_axi.b.valid.poke(true.B)
    var bGuard = 0
    while (!dut.m_axi.b.ready.peek().litToBoolean) {
      dut.clock.step(); bGuard += 1; assert(bGuard < 20)
    }
    dut.clock.step()
    dut.m_axi.b.valid.poke(false.B)
  }

  it should "RMW a line and write it back while the counter stays above zero" in {
    test(dutGen) { dut =>
      init(dut)
      val addr = BigInt(0x7000)
      push(dut, addr, data = 0x30, strobe = 0xff)

      expectAr(dut, addr, max = 50)
      respondR(dut, mkLine(2, 0x1)) // counter 2, remainder 0x1

      // remainder |= 0x30, counter 2 -> 1: written back, not spawned.
      expectWriteBack(dut, addr, mkLine(1, 0x31))
      for (_ <- 0 until 10) {
        assert(!dut.io.spawnOut.valid.peek().litToBoolean, "spawned too early")
        dut.clock.step()
      }
    }
  }

  it should "spawn the merged line when the counter reaches zero (and not write back)" in {
    test(dutGen) { dut =>
      init(dut)
      val addr = BigInt(0x8000)
      push(dut, addr, data = BigInt(0x40) << 32, strobe = BigInt(0xff) << 32)

      expectAr(dut, addr, max = 50)
      respondR(dut, mkLine(1, 0x5))

      var guard = 0
      while (!dut.io.spawnOut.valid.peek().litToBoolean) {
        assert(
          !dut.m_axi.aw.valid.peek().litToBoolean,
          "wrote back a completed line instead of spawning it"
        )
        dut.clock.step(); guard += 1; assert(guard < 20, "never spawned")
      }
      dut.io.spawnOut.bits.expect(mkLine(0, (BigInt(0x40) << 32) | 0x5).U)
      dut.clock.step()
    }
  }

  it should "apply the bit strobe when merging" in {
    test(dutGen) { dut =>
      init(dut)
      val addr = BigInt(0x9000)
      // Data has bits outside the strobe window; they must be masked away.
      push(dut, addr, data = BigInt("ffff", 16), strobe = BigInt("0f00", 16))

      expectAr(dut, addr, max = 50)
      respondR(dut, mkLine(3, 0))
      expectWriteBack(dut, addr, mkLine(2, BigInt("0f00", 16)))
    }
  }

  it should "issue independent reads at one per cycle without a fixed delay" in {
    test(
      new SlowArgumentHandler(
        counterWidth,
        sysAddressWidth,
        lineAddressWidth,
        lineShift,
        continuationSize,
        axiIdWidth = 4
      )
    ) { dut =>
      init(dut)
      val count = 8
      val fireCycles = scala.collection.mutable.ArrayBuffer.empty[Int]
      val fireAddresses = scala.collection.mutable.ArrayBuffer.empty[BigInt]

      for (cycle <- 0 until (count + 10)) {
        if (cycle < count) {
          dut.io.slowUpdateIn.valid.poke(true.B)
          dut.io.slowUpdateIn.bits.address.poke((0x100 + cycle).U)
          dut.io.slowUpdateIn.bits.dataWrite.poke((cycle + 1).U)
          dut.io.slowUpdateIn.bits.dataWriteStrobe.poke(0xf.U)
          assert(
            dut.io.slowUpdateIn.ready.peek().litToBoolean,
            s"input pipeline rejected update $cycle"
          )
        } else {
          dut.io.slowUpdateIn.valid.poke(false.B)
        }

        if (
          dut.m_axi.ar.valid.peek().litToBoolean &&
          dut.m_axi.ar.ready.peek().litToBoolean
        ) {
          fireCycles += cycle
          fireAddresses += dut.m_axi.ar.bits.addr.peek().litValue
        }
        dut.clock.step()
      }

      assert(fireCycles.size == count, s"issued only ${fireCycles.size} reads")
      assert(
        fireCycles.sliding(2).forall(window => window(1) == window(0) + 1),
        s"reads were not pipelined: $fireCycles"
      )
      assert(
        fireAddresses.toSeq == (0 until count).map(i => BigInt(0x100 + i) << lineShift),
        s"wrong compact-address expansion: $fireAddresses"
      )
    }
  }

  it should "process back-to-back updates to the same line sequentially" in {
    test(dutGen) { dut =>
      init(dut)
      val addr = BigInt(0xb000)
      push(dut, addr, data = 0x1, strobe = 0xf)
      push(dut, addr, data = 0x2, strobe = 0xf)

      // First RMW: 3 -> 2.
      expectAr(dut, addr, max = 50)
      respondR(dut, mkLine(3, 0))
      expectWriteBack(dut, addr, mkLine(2, 0x1))

      // Second RMW sees the written-back line: 2 -> 1. One at a time, so the
      // second AR comes only after the first B.
      expectAr(dut, addr, max = 50)
      respondR(dut, mkLine(2, 0x1))
      expectWriteBack(dut, addr, mkLine(1, 0x3))
    }
  }
}
