package Allocator.tests

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import scala.Predef.{assert => sAssert, _}
import scala.collection.mutable

import Allocator.{AllocatorNetwork, AllocatorServer, BeatUnpacker}

// Tests for the beat-granularity allocator distribution ring:
//  - BeatUnpacker slices packed beats back into addresses in lane order,
//    gapless at 1 address/cycle across beat boundaries.
//  - AllocatorNetwork delivers every injected address exactly once and keeps
//    TWO flat-out PEs simultaneously fed at ~1 address/cycle each while other
//    PEs are idle/slow (the countDecoupled initiator-starvation scenario: the
//    old 1-address ring let the upstream PE swallow the whole stream).
//  - AllocatorServer keeps multiple read bursts outstanding and sustains
//    ~1 beat/cycle through an 80-cycle-latency memory model.
class AllocatorBeatRingTests extends AnyFlatSpec with ChiselScalatestTester {

  private val beatWidth = 256
  private val sysAddressWidth = 34
  private val pePortWidth = 64
  private val alignBits = 5 // log2(256/8)
  private val contBits = sysAddressWidth - alignBits // 29
  private val packedPerBeat = beatWidth / contBits // 8

  private def packBeat(lanes: Seq[BigInt]): BigInt = {
    sAssert(lanes.length == packedPerBeat)
    lanes.zipWithIndex.map { case (v, i) => v << (i * contBits) }.reduce(_ | _)
  }

  behavior of "BeatUnpacker"

  it should "unpack lanes in order, gapless at 1 address/cycle" in {
    test(new BeatUnpacker(beatWidth, sysAddressWidth, pePortWidth, beatQueueDepth = 2)) { dut =>
      val nBeats = 6
      val sent = mutable.ArrayBuffer[BigInt]()
      val received = mutable.ArrayBuffer[BigInt]()
      var beatIdx = 0
      var validStreak = 0
      var maxStreak = 0
      val rnd = new scala.util.Random(7)

      dut.io.beatIn.valid.poke(false.B)
      dut.io.addressOut.ready.poke(false.B)
      dut.clock.step()

      for (cyc <- 0 until 200) {
        val feeding = beatIdx < nBeats
        if (feeding) {
          val lanes = Seq.tabulate(packedPerBeat)(j => BigInt(beatIdx * packedPerBeat + j + 1))
          dut.io.beatIn.bits.poke(packBeat(lanes).U)
          dut.io.beatIn.valid.poke(true.B)
        } else {
          dut.io.beatIn.valid.poke(false.B)
        }
        // First half: always ready (measure gaplessness). Second half: random ready.
        val outReady = if (cyc < 60) true else rnd.nextBoolean()
        dut.io.addressOut.ready.poke(outReady.B)

        val inFire = feeding && dut.io.beatIn.ready.peek().litToBoolean
        val outValid = dut.io.addressOut.valid.peek().litToBoolean
        val outFire = outValid && outReady
        if (inFire) {
          sent ++= Seq.tabulate(packedPerBeat)(j => BigInt(beatIdx * packedPerBeat + j + 1))
          beatIdx += 1
        }
        if (outFire) received += dut.io.addressOut.bits.peek().litValue
        if (cyc < 60) {
          if (outValid) { validStreak += 1; maxStreak = maxStreak.max(validStreak) }
          else validStreak = 0
        }
        dut.clock.step()
      }

      sAssert(received.nonEmpty)
      // Addresses come back shifted by the alignment bits, in exact lane order.
      val expected = sent.map(_ << alignBits)
      sAssert(
        received == expected.take(received.length),
        s"unpacked sequence mismatch: got ${received.take(10)}, want ${expected.take(10)}"
      )
      sAssert(received.length == nBeats * packedPerBeat, s"only ${received.length} delivered")
      // Gapless across beat boundaries while always-ready: one solid valid run
      // covering at least two whole beats.
      sAssert(maxStreak >= 2 * packedPerBeat, s"valid streak $maxStreak < ${2 * packedPerBeat}")
    }
  }

  behavior of "AllocatorNetwork beat ring"

  it should "feed two flat-out PEs at ~1 addr/cycle each and conserve all addresses" in {
    test(
      new AllocatorNetwork(
        beatWidth = beatWidth,
        sysAddressWidth = sysAddressWidth,
        pePortWidth = pePortWidth,
        peCount = 8,
        queueDepth = 32,
        vcasCount = 1
      )
    ) { dut =>
      val rnd = new scala.util.Random(21)
      // countDecoupled scenario: PE0 + PE2 flat out, PE5 slow, the rest idle.
      val readyProb = Array(1.0, 0.0, 1.0, 0.0, 0.0, 0.3, 0.0, 0.0)
      val sent = mutable.ArrayBuffer[BigInt]()
      val receivedPer = Array.fill(8)(mutable.ArrayBuffer[BigInt]())
      val firesInWindow = Array.fill(8)(0)
      var laneCounter = BigInt(1)
      var nextBeat = Seq.tabulate(packedPerBeat)(j => laneCounter + j)

      val phase1 = 600
      val windowStart = 100
      dut.io.connVCAS(0).valid.poke(false.B)
      for (i <- 0 until 8) dut.io.connPE(i).ready.poke(false.B)
      dut.clock.step()

      for (cyc <- 0 until phase1) {
        dut.io.connVCAS(0).bits.poke(packBeat(nextBeat).U)
        dut.io.connVCAS(0).valid.poke(true.B)
        val readies = Array.tabulate(8)(i => rnd.nextDouble() < readyProb(i))
        for (i <- 0 until 8) dut.io.connPE(i).ready.poke(readies(i).B)

        if (dut.io.connVCAS(0).ready.peek().litToBoolean) {
          sent ++= nextBeat
          laneCounter += packedPerBeat
          nextBeat = Seq.tabulate(packedPerBeat)(j => laneCounter + j)
        }
        for (i <- 0 until 8) {
          if (readies(i) && dut.io.connPE(i).valid.peek().litToBoolean) {
            receivedPer(i) += (dut.io.connPE(i).bits.peek().litValue >> alignBits)
            if (cyc >= windowStart) firesInWindow(i) += 1
          }
        }
        dut.clock.step()
      }

      // Drain: stop the source, open every sink, let the ring + unpackers empty.
      dut.io.connVCAS(0).valid.poke(false.B)
      for (i <- 0 until 8) dut.io.connPE(i).ready.poke(true.B)
      var idleCycles = 0
      var guard = 0
      while (idleCycles < 30 && guard < 2000) {
        var fired = false
        for (i <- 0 until 8) {
          if (dut.io.connPE(i).valid.peek().litToBoolean) {
            receivedPer(i) += (dut.io.connPE(i).bits.peek().litValue >> alignBits)
            fired = true
          }
        }
        idleCycles = if (fired) 0 else idleCycles + 1
        guard += 1
        dut.clock.step()
      }

      val received = receivedPer.flatten.toSeq
      // Conservation: every injected address delivered exactly once, none invented.
      sAssert(received.length == received.distinct.length, "duplicate addresses delivered")
      sAssert(
        received.toSet == sent.toSet,
        s"lost/invented addresses: sent=${sent.length} received=${received.length}"
      )
      // Both flat-out PEs stay ~gapless through the measured window: this is the
      // property the old address-granularity ring failed (PE2 starved in 16-cycle
      // droughts while PE0 ate the stream).
      val window = phase1 - windowStart
      for (i <- Seq(0, 2)) {
        sAssert(
          firesInWindow(i) >= (window * 0.95).toInt,
          s"PE$i starved: ${firesInWindow(i)}/$window fires"
        )
      }
    }
  }

  behavior of "AllocatorServer read-ahead engine"

  // AXI4-lite register write (RegisterBlock: 64-bit regs at 8-byte offsets),
  // same pattern as SchedulerFifoTests.liteWrite.
  private def liteWrite(dut: AllocatorServer, off: Int, data: BigInt): Unit = {
    val m = dut.io.axi_mgmt
    var awDone = false; var wDone = false
    m.aw.bits.addr.poke(off.U); m.aw.bits.prot.poke(0.U)
    m.w.bits.data.poke(data.U); m.w.bits.strb.poke(0xff.U)
    m.aw.valid.poke(true.B); m.w.valid.poke(true.B); m.b.ready.poke(true.B)
    var g = 0
    while ((!awDone || !wDone) && g < 100) {
      if (!awDone && m.aw.ready.peek().litToBoolean) awDone = true
      if (!wDone && m.w.ready.peek().litToBoolean) wDone = true
      dut.clock.step(); g += 1
      if (awDone) m.aw.valid.poke(false.B)
      if (wDone) m.w.valid.poke(false.B)
    }
    m.aw.valid.poke(false.B); m.w.valid.poke(false.B)
    g = 0
    while (!m.b.valid.peek().litToBoolean && g < 100) { dut.clock.step(); g += 1 }
    dut.clock.step(); m.b.ready.poke(false.B)
  }

  private def liteRead(dut: AllocatorServer, off: Int): BigInt = {
    val m = dut.io.axi_mgmt
    m.ar.bits.addr.poke(off.U); m.ar.bits.prot.poke(0.U)
    m.ar.valid.poke(true.B); m.r.ready.poke(true.B)
    var g = 0
    while (!m.ar.ready.peek().litToBoolean && g < 100) { dut.clock.step(); g += 1 }
    dut.clock.step(); m.ar.valid.poke(false.B)
    g = 0
    while (!m.r.valid.peek().litToBoolean && g < 100) { dut.clock.step(); g += 1 }
    val v = m.r.bits.data.peek().litValue
    dut.clock.step(); m.r.ready.poke(false.B)
    v
  }

  it should "self-pause out of reset, then sustain ~1 beat/cycle with 8 outstanding bursts" in {
    test(new AllocatorServer(dataWidth = beatWidth, sysAddressWidth = sysAddressWidth, burstLength = 15)) {
      dut =>
        val LAT = 80
        val burstBeats = 16
        val contsPerBurst = burstBeats * packedPerBeat // 128
        val baseAddr = BigInt(0x10000)
        val availInit = BigInt(32768)

        dut.io.read_address.ready.poke(false.B)
        dut.io.read_data.valid.poke(false.B)
        dut.io.dataOut.ready.poke(false.B)
        dut.clock.step(5)

        // Engine wanted to read with avaialbleSize=0 -> must have self-paused.
        sAssert(liteRead(dut, 0x00) != BigInt(0), "server did not self-pause out of reset")
        sAssert(dut.io.paused.peek().litToBoolean)

        // Program like the driver does: pause stays set until everything else is ready.
        liteWrite(dut, 0x08, baseAddr) // rAddr
        liteWrite(dut, 0x10, availInit) // avaialbleSize
        liteWrite(dut, 0x00, 0) // rPause released LAST

        // In-order fixed-burst memory model with LAT-cycle latency.
        val pendingBeats = mutable.Queue[(Int, BigInt)]() // (availableAtCycle, beatValue)
        val arAddrs = mutable.ArrayBuffer[BigInt]()
        var beatValue = BigInt(0)
        val delivered = mutable.ArrayBuffer[BigInt]()
        var deliveredInWindow = 0
        val winLo = 200; val winHi = 700
        var arFiresBeforeData = 0

        dut.io.read_address.ready.poke(true.B)
        dut.io.dataOut.ready.poke(true.B)

        for (cyc <- 0 until winHi) {
          val beatReady = pendingBeats.nonEmpty && pendingBeats.head._1 <= cyc
          dut.io.read_data.valid.poke(beatReady.B)
          if (beatReady) dut.io.read_data.bits.poke(pendingBeats.head._2.U)

          if (dut.io.read_address.valid.peek().litToBoolean) {
            arAddrs += dut.io.read_address.bits.peek().litValue
            if (cyc < LAT) arFiresBeforeData += 1
            for (k <- 0 until burstBeats) {
              pendingBeats.enqueue((cyc + LAT + k, beatValue))
              beatValue += 1
            }
          }
          if (beatReady && dut.io.read_data.ready.peek().litToBoolean) {
            pendingBeats.dequeue()
          }
          if (dut.io.dataOut.valid.peek().litToBoolean) {
            delivered += dut.io.dataOut.bits.peek().litValue
            if (cyc >= winLo) deliveredInWindow += 1
          }
          dut.clock.step()
        }

        // Multiple bursts must be in flight long before the first data returns
        // (single/double-outstanding was the old ~0.16 beat/cycle bottleneck).
        sAssert(
          arFiresBeforeData >= 7,
          s"only $arFiresBeforeData read bursts issued before first data returned"
        )
        // Sustained ~1 beat/cycle through the latency pipe.
        val window = winHi - winLo
        sAssert(
          deliveredInWindow >= (window * 0.95).toInt,
          s"throughput ${deliveredInWindow}/$window beats/cycle"
        )
        // Free-address FIFO is consumed top-down in fixed 512-byte bursts.
        val expectedFirst = baseAddr + (((availInit - contsPerBurst) >> 3) << alignBits)
        sAssert(arAddrs.head == expectedFirst, s"first AR ${arAddrs.head} != $expectedFirst")
        for (i <- 0 until arAddrs.length - 1) {
          sAssert(arAddrs(i) - arAddrs(i + 1) == 512, s"AR stride broke at $i")
        }
        // Beats pass through in order, none dropped or reordered.
        sAssert(delivered == delivered.indices.map(BigInt(_)), "beat order broken")
    }
  }
}
