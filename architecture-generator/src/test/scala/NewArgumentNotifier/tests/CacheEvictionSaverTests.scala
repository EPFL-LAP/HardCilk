package NewArgumentNotifier.tests

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import NewArgumentNotifier.CacheEvictionSaver
import chext.amba.axi4

class CacheEvictionSaverTests extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "CacheEvictionSaver"

  private val sysAddressWidth = 32
  private val continuationSize = 128
  private val lineShift = 4
  private val lineAddressWidth = sysAddressWidth - lineShift

  private def dutGen = new CacheEvictionSaver(
    sysAddressWidth,
    lineAddressWidth,
    lineShift,
    continuationSize
  )

  private def init(dut: CacheEvictionSaver): Unit = {
    dut.io.evictionIn.valid.poke(false.B)
    dut.io.writeCompleted.ready.poke(true.B)
    dut.m_axi.aw.ready.poke(true.B)
    dut.m_axi.w.ready.poke(true.B)
    dut.m_axi.b.valid.poke(false.B)
  }

  private def push(
      dut: CacheEvictionSaver,
      addr: BigInt,
      data: BigInt,
      id: BigInt = 0
  ): Unit = {
    dut.io.evictionIn.bits.eviction.address.poke(addr.U)
    dut.io.evictionIn.bits.eviction.taskData.poke(data.U)
    dut.io.evictionIn.bits.metadata.server.poke(0.U)
    dut.io.evictionIn.bits.metadata.id.poke(id.U)
    dut.io.evictionIn.bits.metadata.lane.poke(0.U)
    dut.io.evictionIn.valid.poke(true.B)
    var guard = 0
    while (!dut.io.evictionIn.ready.peek().litToBoolean) {
      dut.clock.step(); guard += 1
      assert(guard < 50, "eviction never accepted")
    }
    dut.clock.step()
    dut.io.evictionIn.valid.poke(false.B)
  }

  it should "issue a single-beat full-strobe write for an eviction" in {
    test(dutGen) { dut =>
      init(dut)
      val data = BigInt("0102030405060708090a0b0c0d0e0f10", 16)
      push(dut, 0x300, data)

      var sawAw = false
      var sawW = false
      var guard = 0
      while (!(sawAw && sawW)) {
        if (dut.m_axi.aw.valid.peek().litToBoolean) {
          dut.m_axi.aw.bits.addr.expect(BigInt(0x3000).U)
          dut.m_axi.aw.bits.len.expect(0.U)
          sawAw = true
        }
        if (dut.m_axi.w.valid.peek().litToBoolean) {
          dut.m_axi.w.bits.data.expect(data.U)
          dut.m_axi.w.bits.strb
            .expect(((BigInt(1) << (continuationSize / 8)) - 1).U)
          dut.m_axi.w.bits.last.expect(true.B)
          sawW = true
        }
        dut.clock.step(); guard += 1
        assert(guard < 20, s"write never issued (aw=$sawAw w=$sawW)")
      }

      // B responses produce the matching completion notification.
      dut.m_axi.b.valid.poke(true.B)
      assert(dut.m_axi.b.ready.peek().litToBoolean)
      dut.clock.step()
      dut.m_axi.b.valid.poke(false.B)
    }
  }

  it should "split a 2048-bit eviction into one two-beat 1024-bit burst" in {
    val wideContinuationSize = 2048
    val wideLineShift = 8
    val wideLineAddressWidth = sysAddressWidth - wideLineShift
    test(
      new CacheEvictionSaver(
        sysAddressWidth,
        wideLineAddressWidth,
        wideLineShift,
        wideContinuationSize
      )
    ) { dut =>
      init(dut)
      assert(dut.m_axi.w.bits.data.getWidth == 1024)

      // Hold both channels so the complete burst can be inspected without
      // racing the saver's shallow input queue.
      dut.m_axi.aw.ready.poke(false.B)
      dut.m_axi.w.ready.poke(false.B)
      val low = (BigInt(1) << 1024) - 1
      val high = BigInt("0123456789abcdef", 16) << 900
      val data = (high << 1024) | low
      push(dut, 0x321, data, id = 1)

      var guard = 0
      while (!dut.m_axi.aw.valid.peek().litToBoolean) {
        dut.clock.step(); guard += 1; assert(guard < 20)
      }
      dut.m_axi.aw.bits.addr.expect(BigInt(0x32100).U)
      dut.m_axi.aw.bits.len.expect(1.U)
      dut.m_axi.aw.bits.size.expect(7.U)
      dut.m_axi.aw.bits.burst.expect(axi4.BurstType.INCR)

      while (!dut.m_axi.w.valid.peek().litToBoolean) dut.clock.step()
      dut.m_axi.w.bits.data.expect(low.U)
      dut.m_axi.w.bits.strb.expect(((BigInt(1) << 128) - 1).U)
      dut.m_axi.w.bits.last.expect(false.B)
      // Backpressure must leave the first beat stable.
      dut.clock.step(2)
      dut.m_axi.w.bits.data.expect(low.U)
      dut.m_axi.w.bits.last.expect(false.B)

      dut.m_axi.aw.ready.poke(true.B)
      dut.m_axi.w.ready.poke(true.B)
      dut.clock.step()
      dut.m_axi.aw.ready.poke(false.B)
      dut.m_axi.w.bits.data.expect(high.U)
      dut.m_axi.w.bits.last.expect(true.B)
      dut.clock.step()

      // One burst still produces exactly one completion, after its single B.
      assert(!dut.io.writeCompleted.valid.peek().litToBoolean)
      dut.m_axi.b.bits.id.poke(0.U)
      dut.m_axi.b.valid.poke(true.B)
      while (!dut.m_axi.b.ready.peek().litToBoolean) dut.clock.step()
      dut.clock.step()
      dut.m_axi.b.valid.poke(false.B)
      while (!dut.io.writeCompleted.valid.peek().litToBoolean) dut.clock.step()
      dut.io.writeCompleted.bits.id.expect(1.U)
    }
  }

  it should "issue the write within a handful of cycles of grabbing an eviction" in {
    test(dutGen) { dut =>
      init(dut)
      dut.io.evictionIn.bits.eviction.address.poke(BigInt(0x400).U)
      dut.io.evictionIn.bits.eviction.taskData.poke(42.U)
      dut.io.evictionIn.bits.metadata.server.poke(0.U)
      dut.io.evictionIn.bits.metadata.id.poke(0.U)
      dut.io.evictionIn.bits.metadata.lane.poke(0.U)
      dut.io.evictionIn.valid.poke(true.B)
      var guard = 0
      while (!dut.io.evictionIn.ready.peek().litToBoolean) {
        dut.clock.step(); guard += 1; assert(guard < 20)
      }
      dut.clock.step() // eviction grabbed here
      dut.io.evictionIn.valid.poke(false.B)

      // The gater reserved this key before the ring, so the saver can issue AW
      // immediately after accepting the eviction.
      var lat = 0
      while (!dut.m_axi.aw.valid.peek().litToBoolean) {
        dut.clock.step(); lat += 1
        assert(lat <= 4, s"AW took $lat cycles after the grab")
      }
    }
  }

  it should "return the matching metadata only after B" in {
    test(dutGen) { dut =>
      init(dut)
      push(dut, 0x345, 0x55, id = 1)
      assert(!dut.io.writeCompleted.valid.peek().litToBoolean)
      dut.m_axi.b.valid.poke(true.B)
      while (!dut.m_axi.b.ready.peek().litToBoolean) dut.clock.step()
      dut.clock.step()
      dut.m_axi.b.valid.poke(false.B)
      var completionGuard = 0
      while (!dut.io.writeCompleted.valid.peek().litToBoolean) {
        dut.clock.step(); completionGuard += 1; assert(completionGuard < 10)
      }
      dut.io.writeCompleted.bits.server.expect(0.U)
      dut.io.writeCompleted.bits.id.expect(1.U)
      dut.io.writeCompleted.bits.lane.expect(0.U)
    }
  }

  it should "stop grabbing evictions when the memory stalls writes" in {
    test(dutGen) { dut =>
      init(dut)
      dut.m_axi.aw.ready.poke(false.B)
      dut.m_axi.w.ready.poke(false.B)

      // With AW/W blocked, only the shallow internal buffering can absorb
      // evictions; after that, ready must drop (so the ring keeps the rest).
      var accepted = 0
      dut.io.evictionIn.valid.poke(true.B)
      for (i <- 0 until 12) {
        dut.io.evictionIn.bits.eviction.address.poke((0x500 + i).U)
        dut.io.evictionIn.bits.eviction.taskData.poke((100 + i).U)
        dut.io.evictionIn.bits.metadata.server.poke(0.U)
        dut.io.evictionIn.bits.metadata.id.poke((i & 1).U)
        dut.io.evictionIn.bits.metadata.lane.poke(0.U)
        if (dut.io.evictionIn.ready.peek().litToBoolean) accepted += 1
        dut.clock.step()
      }
      dut.io.evictionIn.valid.poke(false.B)
      assert(accepted <= 4, s"saver hoarded $accepted evictions while stalled")
      assert(accepted >= 1, "saver accepted nothing at all")

      // Release the memory: everything accepted must drain as writes.
      dut.m_axi.aw.ready.poke(true.B)
      dut.m_axi.w.ready.poke(true.B)
      var seenAw = 0
      var guard = 0
      while (seenAw < accepted) {
        if (
          dut.m_axi.aw.valid.peek().litToBoolean &&
          dut.m_axi.aw.ready.peek().litToBoolean
        ) seenAw += 1
        dut.clock.step(); guard += 1
        assert(guard < 100, s"only $seenAw of $accepted writes drained")
      }
    }
  }
}
