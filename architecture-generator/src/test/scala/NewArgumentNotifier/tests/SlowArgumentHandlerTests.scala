package NewArgumentNotifier.tests

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import NewArgumentNotifier.SlowArgumentHandler
import chext.amba.axi4

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
    dut.io.slowUpdateIn.bits.payload.poke(((data & strobe) << counterWidth).U)
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

  it should "OR only the payload bits supplied by the upstream expander" in {
    test(dutGen) { dut =>
      init(dut)
      val addr = BigInt(0x9000)
      // The test helper mirrors the compact expander by zeroing bits outside
      // the selected payload before the slow handler receives the update.
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
          dut.io.slowUpdateIn.bits.payload.poke((cycle + 1).U)
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

  it should "use two 1024-bit beats for a 2048-bit RMW while preserving its ID" in {
    val wideContinuationSize = 2048
    val wideLineShift = 8
    val wideLineAddressWidth = sysAddressWidth - wideLineShift
    val wideLine = NanTestUtil.line(counterWidth, wideContinuationSize) _
    test(
      new SlowArgumentHandler(
        counterWidth,
        sysAddressWidth,
        wideLineAddressWidth,
        wideLineShift,
        wideContinuationSize,
        axiIdWidth = 2
      )
    ) { dut =>
      init(dut)
      assert(dut.m_axi.r.bits.data.getWidth == 1024)

      dut.m_axi.ar.ready.poke(false.B)
      dut.m_axi.aw.ready.poke(false.B)
      dut.m_axi.w.ready.poke(false.B)

      def pushWide(byteAddress: BigInt, dataWrite: BigInt): Unit = {
        dut.io.slowUpdateIn.bits.address.poke((byteAddress >> wideLineShift).U)
        dut.io.slowUpdateIn.bits.payload.poke(dataWrite.U)
        dut.io.slowUpdateIn.valid.poke(true.B)
        var guard = 0
        while (!dut.io.slowUpdateIn.ready.peek().litToBoolean) {
          dut.clock.step(); guard += 1; assert(guard < 50)
        }
        dut.clock.step()
        dut.io.slowUpdateIn.valid.poke(false.B)
      }

      def consumeAr(byteAddress: BigInt): BigInt = {
        var guard = 0
        while (!dut.m_axi.ar.valid.peek().litToBoolean) {
          dut.clock.step(); guard += 1; assert(guard < 50)
        }
        dut.m_axi.ar.bits.addr.expect(byteAddress.U)
        dut.m_axi.ar.bits.len.expect(1.U)
        dut.m_axi.ar.bits.size.expect(7.U)
        dut.m_axi.ar.bits.burst.expect(axi4.BurstType.INCR)
        val id = dut.m_axi.ar.bits.id.peek().litValue
        dut.m_axi.ar.ready.poke(true.B)
        dut.clock.step()
        dut.m_axi.ar.ready.poke(false.B)
        id
      }

      // Leave ID 0 outstanding, then return ID 1 first. This verifies that the
      // local beat assembly does not collapse the handler's OOO RID tracking.
      val addr0 = BigInt(0x12000)
      val addr1 = BigInt(0x13000)
      pushWide(addr0, BigInt(1) << 1200)
      pushWide(addr1, BigInt(1) << 1500)
      val id0 = consumeAr(addr0)
      val id1 = consumeAr(addr1)
      assert(id0 == 0 && id1 == 1, s"unexpected allocated IDs: $id0, $id1")

      val base = wideLine(2, (BigInt(1) << 1300) | 0x55)
      val merged = wideLine(
        1,
        (BigInt(1) << 1492) | (BigInt(1) << 1300) | 0x55
      )
      val mask1024 = (BigInt(1) << 1024) - 1
      val baseLow = base & mask1024
      val baseHigh = base >> 1024

      dut.m_axi.r.bits.id.poke(id1.U)
      dut.m_axi.r.bits.data.poke(baseLow.U)
      dut.m_axi.r.bits.last.poke(false.B)
      dut.m_axi.r.valid.poke(true.B)
      assert(dut.m_axi.r.ready.peek().litToBoolean)
      dut.clock.step()
      // A partial logical line must not trigger a write or spawn.
      assert(!dut.m_axi.aw.valid.peek().litToBoolean)
      assert(!dut.io.spawnOut.valid.peek().litToBoolean)

      dut.m_axi.r.bits.data.poke(baseHigh.U)
      dut.m_axi.r.bits.last.poke(true.B)
      while (!dut.m_axi.r.ready.peek().litToBoolean) dut.clock.step()
      dut.clock.step()
      dut.m_axi.r.valid.poke(false.B)

      var guard = 0
      while (!dut.m_axi.aw.valid.peek().litToBoolean) {
        dut.clock.step(); guard += 1; assert(guard < 30)
      }
      dut.m_axi.aw.bits.addr.expect(addr1.U)
      dut.m_axi.aw.bits.id.expect(id1.U)
      dut.m_axi.aw.bits.len.expect(1.U)
      dut.m_axi.aw.bits.size.expect(7.U)

      while (!dut.m_axi.w.valid.peek().litToBoolean) dut.clock.step()
      dut.m_axi.w.bits.data.expect((merged & mask1024).U)
      dut.m_axi.w.bits.last.expect(false.B)
      dut.clock.step(2)
      dut.m_axi.w.bits.data.expect((merged & mask1024).U)

      dut.m_axi.aw.ready.poke(true.B)
      dut.m_axi.w.ready.poke(true.B)
      dut.clock.step()
      dut.m_axi.aw.ready.poke(false.B)
      dut.m_axi.w.bits.data.expect((merged >> 1024).U)
      dut.m_axi.w.bits.last.expect(true.B)
      dut.clock.step()

      dut.m_axi.b.bits.id.poke(id1.U)
      dut.m_axi.b.valid.poke(true.B)
      assert(dut.m_axi.b.ready.peek().litToBoolean)
      dut.clock.step()
      dut.m_axi.b.valid.poke(false.B)
    }
  }
}
