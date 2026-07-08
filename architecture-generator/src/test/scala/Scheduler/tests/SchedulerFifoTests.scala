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

  private case class WriteBurst(addr: BigInt, len: Int)
  private case class ReadBurst(addr: BigInt, len: Int)
  private case class CreditRun(out: Seq[BigInt], serveFires: Int)

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
    var cWaddr = false; var cWaddrV = BigInt(0); var cWlen = 0
    var cWdata = false; var cWdataV = BigInt(0); var cWlast = false
    var cRaddr = false; var cRaddrV = BigInt(0); var cRlen = 0
    var cRdata = false
    val writeBursts = mutable.ArrayBuffer.empty[WriteBurst]
    val readBursts = mutable.ArrayBuffer.empty[ReadBurst]
    val writeData = mutable.ArrayBuffer.empty[(BigInt, BigInt)]

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
      cWlen = if (cWaddr) dut.io.write_burst_len.peek().litValue.toInt + 1 else 0
      cWdata = dut.io.write_data.valid.peek().litToBoolean
      cWdataV = if (cWdata) dut.io.write_data.bits.peek().litValue else 0
      cWlast = dut.io.write_last.peek().litValue == 1
      cRaddr = (!rActive) && dut.io.read_address.valid.peek().litToBoolean
      cRaddrV = if (cRaddr) dut.io.read_address.bits.peek().litValue else 0
      cRlen = if (cRaddr) dut.io.read_burst_len.peek().litValue.toInt + 1 else 0
      cRdata = rv && dut.io.read_data.ready.peek().litToBoolean
    }

    def afterStep(): Unit = {
      if (cWaddr) {
        wAddr = cWaddrV; wBeat = 0
        writeBursts += WriteBurst(cWaddrV, cWlen)
      }
      if (cWdata) {
        val addr = wAddr + wBeat * stride
        mem(addr) = cWdataV
        writeData += ((addr, cWdataV))
        if (cWlast) { bActive = true; bDue = cyc + bLatency } else wBeat += 1
      }
      if (cRaddr) {
        rActive = true; rAddr = cRaddrV; rLen = cRlen; rBeat = 0; rDue = cyc + rLatency
        readBursts += ReadBurst(cRaddrV, cRlen)
      }
      if (cRdata) { rBeat += 1; if (rBeat >= rLen) rActive = false }
      cyc += 1
    }
  }

  private def parkMemory(dut: SchedulerServer): Unit = {
    dut.io.write_address.ready.poke(false.B)
    dut.io.write_data.ready.poke(false.B)
    dut.io.read_address.ready.poke(false.B)
    dut.io.read_data.valid.poke(false.B)
    dut.io.read_data.bits.poke(0.U)
    dut.io.write_idle.poke(true.B)
  }

  private def driveNet(
      dut: SchedulerServer,
      occ: Boolean,
      serveReady: Boolean,
      avValid: Boolean,
      avBits: BigInt,
      qReady: Boolean = true,
      stealReady: Boolean = true): Unit = {
    dut.io.ntwDataUnitOccupancy.poke(occ.B)
    dut.io.connNetwork.ctrl.serveStealReq.ready.poke(serveReady.B)
    dut.io.connNetwork.ctrl.stealReq.ready.poke(stealReady.B)
    dut.io.connNetwork.data.qOutTask.ready.poke(qReady.B)
    dut.io.connNetwork.data.availableTask.valid.poke(avValid.B)
    dut.io.connNetwork.data.availableTask.bits.poke(avBits.U)
  }

  private def initInputs(dut: SchedulerServer): Unit = {
    for (i <- 0 until peCount) dut.io.lengths_of_hardware_queues(i).poke(0.U)
    parkMemory(dut)
    driveNet(dut, occ = false, serveReady = true, avValid = false, avBits = 0)
  }

  private def configureRing(
      dut: SchedulerServer,
      ringBase: BigInt = rAddrBase,
      maxLen: Int,
      curr: Int = 0,
      head: Int = 0,
      tail: Int = 0,
      enableSteal: BigInt = 0): Unit = {
    liteWrite(dut, 0x08, ringBase)
    liteWrite(dut, 0x10, BigInt(maxLen))
    liteWrite(dut, 0x18, BigInt(tail))
    liteWrite(dut, 0x20, BigInt(head))
    liteWrite(dut, 0x28, enableSteal)
    liteWrite(dut, 0x30, BigInt(curr))
    liteWrite(dut, 0x00, BigInt(0))
  }

  private def stepWith(
      dut: SchedulerServer,
      mem: DecMem,
      occ: Boolean,
      serveReady: Boolean,
      avValid: Boolean,
      avBits: BigInt,
      qReady: Boolean = true,
      stealReady: Boolean = true): (Boolean, Boolean, BigInt, Boolean) = {
    val (avFire, qFire, qBits, serveFire, _) =
      stepWithSteal(dut, mem, occ, serveReady, avValid, avBits, qReady, stealReady)
    (avFire, qFire, qBits, serveFire)
  }

  private def stepWithSteal(
      dut: SchedulerServer,
      mem: DecMem,
      occ: Boolean,
      serveReady: Boolean,
      avValid: Boolean,
      avBits: BigInt,
      qReady: Boolean = true,
      stealReady: Boolean = true): (Boolean, Boolean, BigInt, Boolean, Boolean) = {
    driveNet(dut, occ, serveReady, avValid, avBits, qReady, stealReady)
    mem.beforeStep()
    val avFire = avValid && dut.io.connNetwork.data.availableTask.ready.peek().litToBoolean
    val qFire = qReady && dut.io.connNetwork.data.qOutTask.valid.peek().litToBoolean
    val qBits = if (qFire) dut.io.connNetwork.data.qOutTask.bits.peek().litValue else BigInt(0)
    val serveFire =
      serveReady && dut.io.connNetwork.ctrl.serveStealReq.valid.peek().litToBoolean
    val stealFire =
      stealReady && dut.io.connNetwork.ctrl.stealReq.valid.peek().litToBoolean
    dut.clock.step()
    mem.afterStep()
    (avFire, qFire, qBits, serveFire, stealFire)
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
      initInputs(dut)

      // configure FIFO base + length, then release rPause.
      configureRing(dut, ringBase = ringBase, maxLen = maxLength)
      driveNet(dut, false, true, false, 0)
      dut.clock.step(5)
      println(s"[FIFO-cfg] rAddr=0x${liteRead(dut, 0x08).toString(16)} maxLength=${liteRead(dut, 0x10)} " +
        s"paused=${dut.io.paused.peek().litToBoolean}")

      val pushed = mutable.ArrayBuffer.empty[BigInt]
      val popped = mutable.ArrayBuffer.empty[BigInt]
      var nextId = BigInt(1)

      def ringStep(occ: Boolean, serveReady: Boolean, avValid: Boolean, avBits: BigInt): Boolean = {
        val (avFire, qFire, qBits, _) = stepWith(dut, mem, occ, serveReady, avValid, avBits)
        if (qFire) popped += qBits
        avFire
      }

      for (r <- 0 until rounds) {
        var fed = 0; var g = 0
        while (fed < fillBatch && g < 1200) {
          if (ringStep(occ = true, serveReady = false, avValid = true, avBits = nextId)) {
            pushed += nextId; nextId += 1; fed += 1
          }
          g += 1
        }
        g = 0
        while (popped.size < pushed.size && g < 6000) {
          ringStep(occ = false, serveReady = true, avValid = false, avBits = 0); g += 1
        }
        if (r < 3 || r % 20 == 0)
          println(s"[FIFO-round $r] pushed=${pushed.size} popped=${popped.size}")
      }
      var g = 0
      while (popped.size < pushed.size && g < 10000) {
        ringStep(occ = false, serveReady = true, avValid = false, avBits = 0); g += 1
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

  private def runCreditScenario(
      dut: SchedulerServer,
      mem: DecMem,
      base: BigInt,
      tasks: Seq[BigInt],
      serveReadyAt: Int => Boolean,
      qReadyAt: Int => Boolean = (_: Int) => true,
      head: Int = 0,
      tail: Int = 0,
      maxCycles: Int = 700): CreditRun = {
    tasks.zipWithIndex.foreach { case (task, i) => mem.mem(base + (head + i) * stride) = task }

    initInputs(dut)
    configureRing(dut, ringBase = base, maxLen = 64, curr = tasks.size, head = head, tail = tail)

    val out = mutable.ArrayBuffer.empty[BigInt]
    var serveFires = 0
    var cycle = 0
    while (out.size < tasks.size && cycle < maxCycles) {
      val (_, qFire, qBits, serveFire) =
        stepWith(
          dut,
          mem,
          occ = false,
          serveReady = serveReadyAt(cycle),
          avValid = false,
          avBits = 0,
          qReady = qReadyAt(cycle))
      if (qFire) out += qBits
      if (serveFire) serveFires += 1
      cycle += 1
    }
    CreditRun(out.toSeq, serveFires)
  }

  it should "buffer a short congested burst, consume one credit per task, and give it away without touching HBM" in {
    test(new SchedulerServer(taskWidth, contentionThreshold, peCount, contentionDelta,
                             vasCount, addrWidth, false, nBeats)) { dut =>
      dut.clock.setTimeout(0)
      val mem = new DecMem(dut, rLatency = 1, bLatency = 4)
      initInputs(dut)
      configureRing(dut, maxLen = 64)

      for (_ <- 0 until contentionThreshold + 2)
        stepWith(dut, mem, occ = true, serveReady = false, avValid = false, avBits = 0)

      val in = Seq[BigInt](0x101, 0x102, 0x103, 0x104)
      val accepted = mutable.ArrayBuffer.empty[BigInt]
      var idx = 0; var guard = 0
      while (idx < in.size && guard < 200) {
        val (avFire, _, _, _) =
          stepWith(dut, mem, occ = true, serveReady = false, avValid = true, avBits = in(idx))
        if (avFire) { accepted += in(idx); idx += 1 }
        guard += 1
      }
      sAssert(accepted == in, s"accepted=$accepted expected=$in")

      val out = mutable.ArrayBuffer.empty[BigInt]
      var serveFires = 0
      guard = 0
      while (out.size < in.size && guard < 400) {
        val (_, qFire, qBits, serveFire) =
          stepWith(dut, mem, occ = false, serveReady = true, avValid = false, avBits = 0)
        if (qFire) out += qBits
        if (serveFire) serveFires += 1
        guard += 1
      }

      sAssert(out == in, s"out=$out expected=$in")
      sAssert(serveFires == in.size, s"direct buffer drain consumed $serveFires steal requests")
      sAssert(mem.writeBursts.isEmpty, s"unexpected writes: ${mem.writeBursts}")
      sAssert(mem.readBursts.isEmpty, s"unexpected reads: ${mem.readBursts}")
      sAssert(liteRead(dut, 0x30) == 0, "short direct drain should leave currLen at zero")
    }
  }

  it should "repay accepted ring tasks with later steal requests" in {
    test(new SchedulerServer(taskWidth, contentionThreshold, peCount, contentionDelta,
                             vasCount, addrWidth, false, nBeats)) { dut =>
      dut.clock.setTimeout(0)
      val mem = new DecMem(dut, rLatency = 1, bLatency = 4)
      initInputs(dut)
      configureRing(dut, maxLen = 64)

      for (_ <- 0 until contentionThreshold + 2)
        stepWithSteal(dut, mem, occ = true, serveReady = false, avValid = false, avBits = 0)

      val in = Seq[BigInt](0x121, 0x122, 0x123, 0x124)
      var accepted = 0
      var guard = 0
      while (accepted < in.size && guard < 200) {
        val (avFire, _, _, _, stealFire) =
          stepWithSteal(
            dut,
            mem,
            occ = true,
            serveReady = false,
            avValid = true,
            avBits = in(accepted),
            stealReady = false)
        sAssert(!stealFire, "steal request fired while stealReq.ready was held low")
        if (avFire) accepted += 1
        guard += 1
      }
      sAssert(accepted == in.size, s"accepted=$accepted expected=${in.size}")

      var repaid = 0
      guard = 0
      while (repaid < accepted && guard < 200) {
        val (_, _, _, _, stealFire) =
          stepWithSteal(
            dut,
            mem,
            occ = true,
            serveReady = false,
            avValid = false,
            avBits = 0,
            stealReady = true)
        if (stealFire) repaid += 1
        guard += 1
      }

      sAssert(repaid == accepted, s"repaid=$repaid accepted=$accepted")
    }
  }

  it should "repay an accepted ring task with a same-cycle steal request when ready" in {
    test(new SchedulerServer(taskWidth, contentionThreshold, peCount, contentionDelta,
                             vasCount, addrWidth, false, nBeats)) { dut =>
      dut.clock.setTimeout(0)
      val mem = new DecMem(dut, rLatency = 1, bLatency = 4)
      initInputs(dut)
      configureRing(dut, maxLen = 64)

      for (_ <- 0 until contentionThreshold + 2)
        stepWithSteal(dut, mem, occ = true, serveReady = false, avValid = false, avBits = 0)

      val (avFire, _, _, _, stealFire) =
        stepWithSteal(
          dut,
          mem,
          occ = true,
          serveReady = false,
          avValid = true,
          avBits = 0x131,
          stealReady = true)

      sAssert(avFire, "ring task was not accepted")
      sAssert(stealFire, "matching steal request did not fire in the same cycle")

      var extraSteals = 0
      for (_ <- 0 until 8) {
        val (_, _, _, _, extraFire) =
          stepWithSteal(
            dut,
            mem,
            occ = true,
            serveReady = false,
            avValid = false,
            avBits = 0,
            stealReady = true)
        if (extraFire) extraSteals += 1
      }

      sAssert(extraSteals == 0, s"same-cycle repayment left extra steal debt: $extraSteals")
    }
  }

  it should "count injected steal requests as congestion relief" in {
    test(new SchedulerServer(taskWidth, contentionThreshold, peCount, contentionDelta,
                             vasCount, addrWidth, false, nBeats)) { dut =>
      dut.clock.setTimeout(0)
      val mem = new DecMem(dut, rLatency = 1, bLatency = 4)
      initInputs(dut)
      configureRing(dut, maxLen = 64)

      for (_ <- 0 until contentionThreshold + 2)
        stepWithSteal(dut, mem, occ = true, serveReady = false, avValid = false, avBits = 0)

      val in = Seq[BigInt](0x141, 0x142, 0x143, 0x144)
      var accepted = 0
      var guard = 0
      while (accepted < in.size && guard < 200) {
        val (avFire, _, _, _, stealFire) =
          stepWithSteal(
            dut,
            mem,
            occ = true,
            serveReady = false,
            avValid = true,
            avBits = in(accepted),
            stealReady = false)
        sAssert(!stealFire, "steal request fired while stealReq.ready was held low")
        if (avFire) accepted += 1
        guard += 1
      }
      sAssert(accepted == in.size, s"accepted=$accepted expected=${in.size}")

      var repaid = 0
      guard = 0
      while (repaid < accepted && guard < 200) {
        val (_, _, _, _, stealFire) =
          stepWithSteal(
            dut,
            mem,
            occ = false,
            serveReady = false,
            avValid = false,
            avBits = 0,
            stealReady = true)
        if (stealFire) repaid += 1
        guard += 1
      }
      sAssert(repaid == accepted, s"repaid=$repaid accepted=$accepted")

      driveNet(dut, occ = false, serveReady = false, avValid = true, avBits = 0x145)
      mem.beforeStep()
      dut.io.connNetwork.data.availableTask.ready.expect(false.B)
    }
  }

  it should "spill a full congested buffer to the ring in order" in {
    test(new SchedulerServer(taskWidth, contentionThreshold, peCount, contentionDelta,
                             vasCount, addrWidth, false, nBeats)) { dut =>
      dut.clock.setTimeout(0)
      val mem = new DecMem(dut, rLatency = 1, bLatency = 5)
      val base = BigInt("2000", 16)
      initInputs(dut)
      configureRing(dut, ringBase = base, maxLen = 64)

      for (_ <- 0 until contentionThreshold + 2)
        stepWith(dut, mem, occ = true, serveReady = false, avValid = false, avBits = 0)

      val in = (0 until nBeats).map(i => BigInt(0x2200 + i))
      var idx = 0; var guard = 0
      while (idx < in.size && guard < 400) {
        val (avFire, _, _, _) =
          stepWith(dut, mem, occ = true, serveReady = false, avValid = true, avBits = in(idx))
        if (avFire) idx += 1
        guard += 1
      }
      while (mem.writeData.size < nBeats && guard < 1000) {
        stepWith(dut, mem, occ = true, serveReady = false, avValid = false, avBits = 0)
        guard += 1
      }

      sAssert(mem.writeBursts == Seq(WriteBurst(base, nBeats)),
        s"write bursts=${mem.writeBursts}")
      val expectedWrites = in.zipWithIndex.map { case (task, i) => (base + i * stride, task) }
      sAssert(mem.writeData.toSeq == expectedWrites,
        s"writeData=${mem.writeData.toSeq} expected=$expectedWrites")
      sAssert(liteRead(dut, 0x30) == nBeats, "currLen should count spilled tasks")
      sAssert(liteRead(dut, 0x18) == nBeats, "tail should advance by the spilled burst")
    }
  }

  it should "refill from the ring, consume one steal credit per task, and drain tasks in order" in {
    test(new SchedulerServer(taskWidth, contentionThreshold, peCount, contentionDelta,
                             vasCount, addrWidth, false, nBeats)) { dut =>
      dut.clock.setTimeout(0)
      val mem = new DecMem(dut, rLatency = 2, bLatency = 1)
      val base = BigInt("3000", 16)
      val head = 3
      val in = (0 until 5).map(i => BigInt(0x3300 + i))
      in.zipWithIndex.foreach { case (task, i) => mem.mem(base + (head + i) * stride) = task }

      initInputs(dut)
      configureRing(dut, ringBase = base, maxLen = 64, curr = in.size, head = head, tail = 8)

      val out = mutable.ArrayBuffer.empty[BigInt]
      var serveFires = 0
      var guard = 0
      while (out.size < in.size && guard < 500) {
        val serveReady = guard > 20
        val (_, qFire, qBits, serveFire) =
          stepWith(dut, mem, occ = false, serveReady = serveReady, avValid = false, avBits = 0)
        if (qFire) out += qBits
        if (serveFire) serveFires += 1
        guard += 1
      }

      sAssert(mem.readBursts.headOption.contains(ReadBurst(base + head * stride, in.size)),
        s"read bursts=${mem.readBursts}")
      sAssert(out == in, s"out=$out expected=$in")
      sAssert(serveFires == in.size, s"should consume one steal request per output task")
      sAssert(liteRead(dut, 0x30) == 0, "currLen should decrement after the pop burst")
      sAssert(liteRead(dut, 0x20) == head + in.size, "head should advance by popped tasks")
    }
  }

  it should "issue read bursts ahead until the fixed low watermark is reached" in {
    val readAheadWatermark = nBeats * 7
    test(new SchedulerServer(taskWidth, contentionThreshold, peCount, contentionDelta,
                             vasCount, addrWidth, false, nBeats)) { dut =>
      dut.clock.setTimeout(0)
      val base = BigInt("3800", 16)
      initInputs(dut)
      configureRing(dut, ringBase = base, maxLen = 256, curr = 128)

      val readAddrs = mutable.ArrayBuffer.empty[BigInt]
      for (_ <- 0 until 10) {
        driveNet(dut, occ = false, serveReady = false, avValid = false, avBits = 0)
        dut.io.write_address.ready.poke(false.B)
        dut.io.write_data.ready.poke(false.B)
        dut.io.write_idle.poke(true.B)
        dut.io.read_address.ready.poke(true.B)
        dut.io.read_data.valid.poke(false.B)
        dut.io.read_data.bits.poke(0.U)

        if (dut.io.read_address.valid.peek().litToBoolean)
          readAddrs += dut.io.read_address.bits.peek().litValue
        dut.clock.step()
      }

      val expected = (0 until readAheadWatermark by nBeats).map(i => base + i * stride)
      sAssert(readAddrs.toSeq == expected,
        s"read-ahead bursts=$readAddrs expected=$expected")
    }
  }

  it should "hold qOutTask stable under network backpressure" in {
    test(new SchedulerServer(taskWidth, contentionThreshold, peCount, contentionDelta,
                             vasCount, addrWidth, false, nBeats)) { dut =>
      dut.clock.setTimeout(0)
      val mem = new DecMem(dut, rLatency = 1, bLatency = 1)
      val base = BigInt("4000", 16)
      val in = Seq[BigInt](0x4401, 0x4402)
      in.zipWithIndex.foreach { case (task, i) => mem.mem(base + i * stride) = task }

      initInputs(dut)
      configureRing(dut, ringBase = base, maxLen = 64, curr = in.size)

      var sawHeldValid = false
      var guard = 0
      while (!sawHeldValid && guard < 200) {
        val (_, _, _, _) =
          stepWith(dut, mem, occ = false, serveReady = true, avValid = false, avBits = 0, qReady = false)
        if (dut.io.connNetwork.data.qOutTask.valid.peek().litToBoolean) {
          dut.io.connNetwork.data.qOutTask.bits.expect(in.head.U)
          sawHeldValid = true
        }
        guard += 1
      }
      sAssert(sawHeldValid, "qOutTask never became valid while backpressured")

      val out = mutable.ArrayBuffer.empty[BigInt]
      guard = 0
      while (out.size < in.size && guard < 100) {
        val (_, qFire, qBits, _) =
          stepWith(dut, mem, occ = false, serveReady = true, avValid = false, avBits = 0, qReady = true)
        if (qFire) out += qBits
        guard += 1
      }
      sAssert(out == in, s"out=$out expected=$in")
    }
  }

  it should "expose management registers, queue utilization, and mFPGA remote hints" in {
    test(new SchedulerServer(taskWidth, contentionThreshold, peCount, contentionDelta,
                             vasCount, addrWidth, false, nBeats)) { dut =>
      dut.clock.setTimeout(0)
      initInputs(dut)
      configureRing(dut, ringBase = BigInt("5000", 16), maxLen = 128, enableSteal = 1)

      dut.io.lengths_of_hardware_queues(0).poke(1.U)
      dut.io.lengths_of_hardware_queues(1).poke(2.U)
      dut.io.lengths_of_hardware_queues(2).poke(3.U)
      dut.io.lengths_of_hardware_queues(3).poke(4.U)
      dut.clock.step()

      sAssert(liteRead(dut, 0x08) == BigInt("5000", 16))
      sAssert(liteRead(dut, 0x10) == 128)
      sAssert(liteRead(dut, 0x38) == BigInt("01020304", 16),
        s"queuesUtil=0x${liteRead(dut, 0x38).toString(16)}")

      dut.io.getTasksFromRemote.expect(true.B)
      dut.io.serveRemote.expect(false.B)

      liteWrite(dut, 0x30, 17)
      dut.clock.step()
      dut.io.serveRemote.expect(true.B)
      dut.io.getTasksFromRemote.expect(false.B)

      liteWrite(dut, 0x00, BigInt("FFFFFFFFFFFFFFFF", 16))
      dut.clock.step()
      dut.io.serveRemote.expect(false.B)
      dut.io.getTasksFromRemote.expect(false.B)
    }
  }

  it should "pause for software resize when configured capacity is too small" in {
    test(new SchedulerServer(taskWidth, contentionThreshold, peCount, contentionDelta,
                             vasCount, addrWidth, false, nBeats)) { dut =>
      dut.clock.setTimeout(0)
      initInputs(dut)
      configureRing(dut, maxLen = 8)

      var guard = 0
      while (!dut.io.paused.peek().litToBoolean && guard < 100) {
        dut.clock.step()
        guard += 1
      }
      dut.io.paused.expect(true.B)

      driveNet(dut, occ = true, serveReady = true, avValid = true, avBits = 0xdead)
      dut.io.read_address.valid.expect(false.B)
      dut.io.write_address.valid.expect(false.B)
      dut.io.connNetwork.data.availableTask.ready.expect(false.B)
      dut.io.connNetwork.ctrl.serveStealReq.valid.expect(false.B)
      dut.io.connNetwork.data.qOutTask.valid.expect(false.B)

      liteWrite(dut, 0x10, 64)
      liteWrite(dut, 0x00, 0)
      dut.clock.step(4)
      dut.io.paused.expect(false.B)
    }
  }

  it should "consume one steal credit per output task when credits are immediately present" in {
    test(new SchedulerServer(taskWidth, contentionThreshold, peCount, contentionDelta,
                             vasCount, addrWidth, false, nBeats)) { dut =>
      dut.clock.setTimeout(0)
      val mem = new DecMem(dut, rLatency = 1, bLatency = 1)
      val base = BigInt("6000", 16)
      val in = (0 until 6).map(i => BigInt(0x6600 + i))

      val run = runCreditScenario(dut, mem, base, in, serveReadyAt = _ => true)
      sAssert(run.out == in, s"baseline refill failed: out=${run.out} expected=$in")
      sAssert(run.serveFires == run.out.size,
        s"pipelined server should consume ${run.out.size} credits, consumed ${run.serveFires}")
    }
  }

  it should "consume one steal credit per output task when the first credit arrives late" in {
    test(new SchedulerServer(taskWidth, contentionThreshold, peCount, contentionDelta,
                             vasCount, addrWidth, false, nBeats)) { dut =>
      dut.clock.setTimeout(0)
      val mem = new DecMem(dut, rLatency = 1, bLatency = 1)
      val base = BigInt("7000", 16)
      val in = (0 until 5).map(i => BigInt(0x7700 + i))

      val run = runCreditScenario(dut, mem, base, in, serveReadyAt = cycle => cycle >= 40)
      sAssert(run.out == in, s"baseline delayed-credit refill failed: out=${run.out} expected=$in")
      sAssert(run.serveFires == run.out.size,
        s"late-credit case should consume ${run.out.size} credits, consumed ${run.serveFires}")
    }
  }

  it should "consume one steal credit per output task when credits arrive intermittently" in {
    test(new SchedulerServer(taskWidth, contentionThreshold, peCount, contentionDelta,
                             vasCount, addrWidth, false, nBeats)) { dut =>
      dut.clock.setTimeout(0)
      val mem = new DecMem(dut, rLatency = 2, bLatency = 1)
      val base = BigInt("8000", 16)
      val in = (0 until 7).map(i => BigInt(0x8800 + i))

      val run = runCreditScenario(
        dut,
        mem,
        base,
        in,
        serveReadyAt = cycle => cycle >= 20 && cycle % 4 == 0)
      sAssert(run.out == in, s"baseline intermittent-credit refill failed: out=${run.out} expected=$in")
      sAssert(run.serveFires == run.out.size,
        s"intermittent-credit case should consume ${run.out.size} credits, consumed ${run.serveFires}")
    }
  }

  it should "consume one steal credit per output task across output backpressure" in {
    test(new SchedulerServer(taskWidth, contentionThreshold, peCount, contentionDelta,
                             vasCount, addrWidth, false, nBeats)) { dut =>
      dut.clock.setTimeout(0)
      val mem = new DecMem(dut, rLatency = 1, bLatency = 1)
      val base = BigInt("9000", 16)
      val in = (0 until 4).map(i => BigInt(0x9900 + i))

      val run = runCreditScenario(
        dut,
        mem,
        base,
        in,
        serveReadyAt = _ => true,
        qReadyAt = cycle => cycle >= 35 && cycle % 5 == 0)
      sAssert(run.out == in, s"baseline backpressured refill failed: out=${run.out} expected=$in")
      sAssert(run.serveFires == run.out.size,
        s"backpressured-output case should consume ${run.out.size} credits, consumed ${run.serveFires}")
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
