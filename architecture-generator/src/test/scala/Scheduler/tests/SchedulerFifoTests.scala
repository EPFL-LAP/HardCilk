package Scheduler.tests

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import scala.Predef.{assert => sAssert, _}
import scala.collection.mutable

import Scheduler.SchedulerServer

// Exercises the current SchedulerServer, which exposes DECOUPLED HBM ports
// (read_address/read_data/write_address/write_data + write_idle) that the system
// wraps with RVtoAXIBridge + AxiWriteBuffer. We attach a coherent memory model
// directly to those decoupled ports and stress the ring with many wraps + split
// bursts under fill/drain churn. Every pushed task must come out of qOutTask
// exactly once.
//
// Primary purpose here: validate the AXI 4KB-boundary burst cap (capBurstAtFifoEnd
// + push split-continuation) added for the large-size HBM corruption bug. The
// coherent memory model can't reproduce the below-RTL HBM mis-burst, so these
// tests prove the split LOGIC is correct (no loss / dup / deadlock across wraps
// and page boundaries) before trusting it on hardware.
class SchedulerFifoTests extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "SchedulerServer ring FIFO + 4KB burst split"

  private val taskWidth = 64
  private val addrWidth = 64
  private val nBeats = 16
  private val peCount = 4
  private val contentionThreshold = 3
  private val contentionDelta = 0
  private val vasCount = 0
  private val stride = taskWidth / 8 // bytes per beat / FIFO entry
  private val rAddrBase = BigInt("1000", 16)

  // Coherent memory model wired straight to the SchedulerServer decoupled ports.
  // Writes commit to `mem` on the W beat; write_idle drops on the last write beat
  // and rises again `bLatency` cycles later (models the B response). Reads return
  // committed data `rLatency` cycles after the read address fires. The server
  // serializes accesses, so a single in-flight read/write is sufficient.
  private class DecMem(dut: SchedulerServer, rLatency: Int, bLatency: Int) {
    val mem = mutable.Map.empty[BigInt, BigInt].withDefaultValue(BigInt(0))
    var cyc = 0
    // write
    var wAddr = BigInt(0); var wBeat = 0
    var bActive = false; var bDue = 0
    // read
    var rActive = false; var rAddr = BigInt(0); var rBeat = 0; var rLen = 0; var rDue = 0
    // captured fires
    var cWaddr = false; var cWaddrV = BigInt(0)
    var cWdata = false; var cWdataV = BigInt(0); var cWlast = false
    var cRaddr = false; var cRaddrV = BigInt(0); var cRlen = 0
    var cRdata = false

    def beforeStep(): Unit = {
      dut.io.write_address.ready.poke(true.B)
      dut.io.write_data.ready.poke(true.B)
      dut.io.read_address.ready.poke((!rActive).B)
      if (bActive && cyc >= bDue) bActive = false
      dut.io.write_idle.poke((!bActive).B)
      val rv = rActive && cyc >= rDue
      dut.io.read_data.valid.poke(rv.B)
      dut.io.read_data.bits.poke((if (rv) mem(rAddr + rBeat * stride) else BigInt(0)).U)

      cWaddr = dut.io.write_address.valid.peek().litToBoolean
      cWaddrV = if (cWaddr) dut.io.write_address.bits.peek().litValue else 0
      cWdata = dut.io.write_data.valid.peek().litToBoolean
      cWdataV = if (cWdata) dut.io.write_data.bits.peek().litValue else 0
      cWlast = dut.io.write_last.peek().litValue == 1
      cRaddr = (!rActive) && dut.io.read_address.valid.peek().litToBoolean
      cRaddrV = if (cRaddr) dut.io.read_address.bits.peek().litValue else 0
      cRlen = if (cRaddr) dut.io.read_burst_len.peek().litValue.toInt + 1 else 0
      cRdata = rv && dut.io.read_data.ready.peek().litToBoolean
    }

    def afterStep(): Unit = {
      if (cWaddr) { wAddr = cWaddrV; wBeat = 0 }
      if (cWdata) {
        mem(wAddr + wBeat * stride) = cWdataV
        if (cWlast) { bActive = true; bDue = cyc + bLatency } else wBeat += 1
      }
      if (cRaddr) { rActive = true; rAddr = cRaddrV; rLen = cRlen; rBeat = 0; rDue = cyc + rLatency }
      if (cRdata) { rBeat += 1; if (rBeat >= rLen) rActive = false }
      cyc += 1
    }
  }

  // AXI4-lite register write to axi_mgmt (RegisterBlock: 64-bit regs at 8-byte
  // offsets). Holds aw/w valid until both handshake, then waits for b.
  private def liteWrite(dut: SchedulerServer, off: Int, data: BigInt): Unit = {
    val m = dut.io.axi_mgmt
    var awDone = false; var wDone = false
    m.aw.bits.addr.poke(off.U); m.aw.bits.prot.poke(0.U)
    m.w.bits.data.poke(data.U); m.w.bits.strb.poke(((BigInt(1) << (taskWidth / 8)) - 1).U)
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

  private def liteRead(dut: SchedulerServer, off: Int): BigInt = {
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

  private def runRingTest(maxLength: Int, rounds: Int, fillBatch: Int,
                          rLatency: Int, bLatency: Int,
                          ringBase: BigInt = rAddrBase): Unit = {
    test(new SchedulerServer(taskWidth, contentionThreshold, peCount, contentionDelta,
                             vasCount, addrWidth, false, nBeats)) { dut =>
      dut.clock.setTimeout(0)
      val mem = new DecMem(dut, rLatency, bLatency)

      def driveNet(occ: Boolean, serveReady: Boolean, avValid: Boolean, avBits: BigInt): Unit = {
        dut.io.ntwDataUnitOccupancy.poke(occ.B)
        dut.io.connNetwork.ctrl.serveStealReq.ready.poke(serveReady.B)
        dut.io.connNetwork.ctrl.stealReq.ready.poke(true.B)
        dut.io.connNetwork.data.qOutTask.ready.poke(true.B)
        dut.io.connNetwork.data.availableTask.valid.poke(avValid.B)
        dut.io.connNetwork.data.availableTask.bits.poke(avBits.U)
      }
      for (i <- 0 until peCount) dut.io.lengths_of_hardware_queues(i).poke(0.U)
      // park ports before config
      dut.io.write_address.ready.poke(false.B); dut.io.write_data.ready.poke(false.B)
      dut.io.read_address.ready.poke(false.B); dut.io.read_data.valid.poke(false.B)
      dut.io.write_idle.poke(true.B)
      driveNet(false, true, false, 0)

      // configure FIFO base + length, then release rPause.
      liteWrite(dut, 0x08, ringBase)                // rAddr
      liteWrite(dut, 0x10, BigInt(maxLength))        // maxLength
      liteWrite(dut, 0x00, BigInt(0))                // rPause = 0
      driveNet(false, true, false, 0)
      dut.clock.step(5)
      println(s"[FIFO-cfg] rAddr=0x${liteRead(dut, 0x08).toString(16)} maxLength=${liteRead(dut, 0x10)} " +
        s"paused=${dut.io.paused.peek().litToBoolean}")

      val pushed = mutable.ArrayBuffer.empty[BigInt]
      val popped = mutable.ArrayBuffer.empty[BigInt]
      var nextId = BigInt(1)

      def stepWith(occ: Boolean, serveReady: Boolean, avValid: Boolean, avBits: BigInt): Boolean = {
        driveNet(occ, serveReady, avValid, avBits)
        mem.beforeStep()
        val avFire = avValid && dut.io.connNetwork.data.availableTask.ready.peek().litToBoolean
        val qFire = dut.io.connNetwork.data.qOutTask.valid.peek().litToBoolean
        val qBits = if (qFire) dut.io.connNetwork.data.qOutTask.bits.peek().litValue else BigInt(0)
        dut.clock.step()
        mem.afterStep()
        if (qFire) popped += qBits
        avFire
      }

      for (r <- 0 until rounds) {
        var fed = 0; var g = 0
        while (fed < fillBatch && g < 1200) {
          if (stepWith(occ = true, serveReady = false, avValid = true, avBits = nextId)) {
            pushed += nextId; nextId += 1; fed += 1
          }
          g += 1
        }
        g = 0
        while (popped.size < pushed.size && g < 6000) {
          stepWith(occ = false, serveReady = true, avValid = false, avBits = 0); g += 1
        }
        if (r < 3 || r % 20 == 0)
          println(s"[FIFO-round $r] pushed=${pushed.size} popped=${popped.size}")
      }
      var g = 0
      while (popped.size < pushed.size && g < 10000) {
        stepWith(occ = false, serveReady = true, avValid = false, avBits = 0); g += 1
      }

      val pushedSet = pushed.toSet
      val poppedSet = popped.toSet
      val dups = popped.groupBy(identity).filter(_._2.size > 1).keys.toSeq.sorted
      val lost = (pushedSet -- poppedSet).toSeq.sorted
      val spurious = (poppedSet -- pushedSet).toSeq.sorted
      val clean = (popped.size == pushed.size) && dups.isEmpty && lost.isEmpty && spurious.isEmpty
      println(s"[FIFO] maxLength=$maxLength pushed=${pushed.size} popped=${popped.size} " +
        s"dups=${dups.size} lost=${lost.size} spurious=${spurious.size} clean=$clean")
      if (dups.nonEmpty) println(s"[FIFO]   dup examples: ${dups.take(8)}")
      if (lost.nonEmpty) println(s"[FIFO]   lost examples: ${lost.take(8)}")
      sAssert(clean, s"ring mismatch: dups=${dups.size} lost=${lost.size} spurious=${spurious.size}")
    }
  }

  it should "deliver every task once with a non-wrapping ring (harness self-check)" in {
    runRingTest(maxLength = 100000, rounds = 12, fillBatch = nBeats, rLatency = 2, bLatency = 6)
  }

  it should "deliver every task exactly once across many wraps + split bursts" in {
    runRingTest(maxLength = 48, rounds = 60, fillBatch = nBeats, rLatency = 2, bLatency = 6)
  }

  it should "stay correct across wraps with a long B latency" in {
    runRingTest(maxLength = 48, rounds = 40, fillBatch = nBeats, rLatency = 1, bLatency = 20)
  }

  // --- AXI 4KB boundary split. A page-MISaligned base (0xFC0, with 8 B slots the
  // first 4KB line at 0x1000 lands on slot 8) forces full bursts to straddle the
  // boundary; maxLength=280 is not a multiple of nBeats so the alignment drifts
  // each wrap, mixing 4KB splits with fifo-end splits. The coherent memory model
  // can't reproduce the HW mis-burst, but it proves the capBurstAtFifoEnd 4KB cap
  // + push split-continuation deliver every task exactly once (no loss/dup/dead-
  // lock). With a page-aligned base this path is a bit-identical no-op. ---
  it should "split bursts at 4KB boundaries with a page-misaligned ring" in {
    runRingTest(maxLength = 280, rounds = 80, fillBatch = nBeats, rLatency = 2,
                bLatency = 6, ringBase = BigInt("FC0", 16))
  }
}
