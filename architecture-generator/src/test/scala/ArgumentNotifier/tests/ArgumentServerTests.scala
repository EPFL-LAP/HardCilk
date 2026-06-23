package ArgumentNotifier.tests

import chisel3._
import chisel3.util.log2Ceil
import chiseltest._
import ArgumentNotifier.ArgumentServer
import org.scalatest.flatspec.AnyFlatSpec
import scala.Predef.{assert => scalaAssert, _}
import scala.collection.mutable

// Stress test for ArgumentServer, the join-counter decrement engine. BFS drives
// the pathological case: ONE continuation counter is decremented by the entire
// frontier (one +1 per finished helper, up to ~900k/level). The server does an
// AXI read-modify-write per address and COLLAPSES same-address decrements into a
// single in-flight slot. This test reproduces that fan-in: counterStart = N
// decrement requests hit a single address back-to-back; the counter must reach
// exactly 0 (no lost or double-counted decrements) and the continuation task
// must be re-injected exactly once. A lost decrement leaves the counter stuck
// above 0 forever -- the BFS as-skitter hang.
class ArgumentServerTests
    extends AnyFlatSpec
    with ChiselScalatestTester {
  behavior of "ArgumentServer"

  // Match the BFS ArgumentServerMfpgaWrapper instantiation (wId=2, counter 32b,
  // 64-bit addresses, single-decrement). taskWidth shrunk from 1024 to 64 to
  // keep sim fast -- the decrement logic under test is independent of it.
  private val taskWidth = 64
  private val counterWidth = 32
  private val sysAddressWidth = 64
  private val wId = 2
  private val tagBitsShift = log2Ceil(taskWidth / 8)
  private val counterAddr = BigInt(0x1000)

  // Models the AXI counter memory: read returns the stored counter, write
  // commits it. `latency` read/write delay widens the collapse / feedback
  // windows so same-address races actually occur.
  // writeReady(cyc): when false the counter's AW/W channels are NOT ready, modelling
  // the HBM write port being starved by contending read traffic (the dist-4 case:
  // 182k helpers hammering graph reads while the counter writeback wants the same
  // port). This keeps RMW slots parked in cnt_wd, which is what pushes incoming
  // same-address decrements onto the qFeedback path.
  private class CounterAxi(dut: ArgumentServer, readLatency: Int, writeLatency: Int,
                           writeReady: Int => Boolean = _ => true) {
    val mem = mutable.Map.empty[BigInt, BigInt].withDefaultValue(BigInt(0))
    var cyc = 0
    val reads = mutable.ArrayBuffer.empty[(Int, BigInt)]   // (cycle, valueReturned)
    val writes = mutable.ArrayBuffer.empty[(Int, BigInt, BigInt)] // (cycle, old, new)
    private val c = dut.io.m_axi_counter
    private val rResp = mutable.ArrayBuffer.empty[(Int, BigInt, BigInt)] // cd,id,data
    private val bResp = mutable.ArrayBuffer.empty[(Int, BigInt)] // cd,id
    private val awQ = mutable.Queue.empty[(BigInt, BigInt)] // id,addr
    private val wQ = mutable.Queue.empty[(BigInt, BigInt)] // data,strb
    private var rIdx = -1
    private var bIdx = -1
    private var rConsumed = false
    private var bConsumed = false
    private var capAr = (false, BigInt(0), BigInt(0))
    private var capAw = (false, BigInt(0), BigInt(0))
    private var capW = (false, BigInt(0), BigInt(0))

    private def pick(cds: collection.Seq[Int]): Int =
      cds.indices.find(i => cds(i) <= 0).getOrElse(-1)

    def beforeStep(): Unit = {
      val wr = writeReady(cyc)
      c.ar.ready.poke(true.B)
      c.aw.ready.poke(wr.B)
      c.w.ready.poke(wr.B)

      rIdx = pick(rResp.map(_._1))
      if (rIdx >= 0) {
        val (_, id, data) = rResp(rIdx)
        c.r.valid.poke(true.B); c.r.bits.id.poke(id.U)
        c.r.bits.data.poke(data.U); c.r.bits.resp.poke(0.U); c.r.bits.last.poke(true.B)
      } else {
        c.r.valid.poke(false.B); c.r.bits.id.poke(0.U)
        c.r.bits.data.poke(0.U); c.r.bits.resp.poke(0.U); c.r.bits.last.poke(true.B)
      }

      bIdx = pick(bResp.map(_._1))
      if (bIdx >= 0) {
        c.b.valid.poke(true.B); c.b.bits.id.poke(bResp(bIdx)._2.U); c.b.bits.resp.poke(0.U)
      } else {
        c.b.valid.poke(false.B); c.b.bits.id.poke(0.U); c.b.bits.resp.poke(0.U)
      }

      val arF = c.ar.valid.peek().litToBoolean // ar.ready always true here
      capAr = (arF, if (arF) c.ar.bits.id.peek().litValue else 0,
        if (arF) c.ar.bits.addr.peek().litValue else 0)
      val awF = wr && c.aw.valid.peek().litToBoolean
      capAw = (awF, if (awF) c.aw.bits.id.peek().litValue else 0,
        if (awF) c.aw.bits.addr.peek().litValue else 0)
      val wF = wr && c.w.valid.peek().litToBoolean
      capW = (wF, if (wF) c.w.bits.data.peek().litValue else 0,
        if (wF) c.w.bits.strb.peek().litValue else 0)
      rConsumed = rIdx >= 0 && c.r.ready.peek().litToBoolean
      bConsumed = bIdx >= 0 && c.b.ready.peek().litToBoolean
    }

    def afterStep(): Unit = {
      if (rConsumed) rResp.remove(rIdx)
      if (bConsumed) bResp.remove(bIdx)
      for (i <- rResp.indices) rResp(i) = (math.max(0, rResp(i)._1 - 1), rResp(i)._2, rResp(i)._3)
      for (i <- bResp.indices) bResp(i) = (math.max(0, bResp(i)._1 - 1), bResp(i)._2)

      if (capAr._1) {
        val v = mem(capAr._3) & ((BigInt(1) << counterWidth) - 1)
        reads += ((cyc, v))
        rResp += ((readLatency, capAr._2, v))
      }
      if (capAw._1) awQ.enqueue((capAw._2, capAw._3))
      if (capW._1) wQ.enqueue((capW._2, capW._3))
      while (awQ.nonEmpty && wQ.nonEmpty) {
        val (id, addr) = awQ.dequeue()
        val (data, _) = wQ.dequeue()
        // counter write: strobe covers exactly the counterWidth low bytes.
        val old = mem(addr)
        mem(addr) = data & ((BigInt(1) << counterWidth) - 1)
        writes += ((cyc, old, mem(addr)))
        bResp += ((writeLatency, id))
      }
      cyc += 1
    }
  }

  // Models m_axi_task: a read-only port. When the counter hits 0 the server
  // reads the continuation task here and forwards it to qOutTask.
  private class TaskAxi(dut: ArgumentServer, latency: Int) {
    private val t = dut.io.m_axi_task
    private val rResp = mutable.ArrayBuffer.empty[(Int, BigInt, BigInt)]
    private var rIdx = -1
    private var rConsumed = false
    private var capAr = (false, BigInt(0), BigInt(0))
    var reads = 0
    val readAddresses = mutable.ArrayBuffer.empty[BigInt]

    def beforeStep(): Unit = {
      t.ar.ready.poke(true.B)
      rIdx = rResp.indices.find(i => rResp(i)._1 <= 0).getOrElse(-1)
      if (rIdx >= 0) {
        t.r.valid.poke(true.B); t.r.bits.id.poke(rResp(rIdx)._2.U)
        t.r.bits.data.poke(rResp(rIdx)._3.U); t.r.bits.resp.poke(0.U); t.r.bits.last.poke(true.B)
      } else {
        t.r.valid.poke(false.B); t.r.bits.id.poke(0.U)
        t.r.bits.data.poke(0.U); t.r.bits.resp.poke(0.U); t.r.bits.last.poke(true.B)
      }
      val arF = t.ar.valid.peek().litToBoolean
      capAr = (arF, if (arF) t.ar.bits.id.peek().litValue else 0,
        if (arF) t.ar.bits.addr.peek().litValue else 0)
      rConsumed = rIdx >= 0 && t.r.ready.peek().litToBoolean
    }
    def afterStep(): Unit = {
      if (rConsumed) rResp.remove(rIdx)
      for (i <- rResp.indices) rResp(i) = (math.max(0, rResp(i)._1 - 1), rResp(i)._2, rResp(i)._3)
      if (capAr._1) {
        reads += 1
        readAddresses += capAr._3
        rResp += ((latency, capAr._2, BigInt(0xABCD)))
      }
    }
  }

  // Drive the steal-network sink so re-injected tasks never backpressure, and
  // count how many continuation tasks were re-injected (qOutTask fires).
  private def driveStealNetwork(dut: ArgumentServer): Int = {
    val s = dut.io.connStealNtw
    driveStealNetwork(dut, qReady = true, stealReady = true)
  }

  // Returns 1 the cycle a continuation is actually handed off (qOutTask fires).
  // `qReady`/`stealReady` let the test backpressure the steal network, mimicking a
  // busy downstream scheduler.
  private def driveStealNetwork(dut: ArgumentServer, qReady: Boolean, stealReady: Boolean): Int = {
    val s = dut.io.connStealNtw
    s.data.availableTask.valid.poke(false.B)
    s.data.availableTask.bits.poke(0.U)
    s.data.qOutTask.ready.poke(qReady.B)
    s.ctrl.serveStealReq.ready.poke(true.B)
    s.ctrl.stealReq.ready.poke(stealReady.B)
    if (qReady && s.data.qOutTask.valid.peek().litToBoolean) 1 else 0
  }

  private def runFanIn(
      dut: ArgumentServer,
      counterStart: Int,
      readLatency: Int,
      writeLatency: Int,
      injectEveryCycle: Boolean,
      stallLimit: Int = 4000,
      writeReady: Int => Boolean = _ => true
  ): Unit = {
    dut.clock.setTimeout(0)
    val cmem = new CounterAxi(dut, readLatency, writeLatency, writeReady)
    val tmem = new TaskAxi(dut, readLatency)
    cmem.mem(counterAddr) = BigInt(counterStart)

    // Idle a few cycles to let resets settle.
    dut.io.connNetwork.valid.poke(false.B)
    for (_ <- 0 until 8) {
      cmem.beforeStep(); tmem.beforeStep(); driveStealNetwork(dut)
      dut.clock.step(); cmem.afterStep(); tmem.afterStep()
    }

    var injected = 0
    var reinjections = 0
    var cycles = 0
    var lastProgress = 0
    var prevRemaining = counterStart
    val maxCycles = 200000

    // Done when all decrements injected AND the continuation has been re-injected
    // (the server signals completion by re-injection, not by writing 0 back).
    while ((injected < counterStart || reinjections == 0) &&
      (cycles - lastProgress) < stallLimit && cycles < maxCycles) {

      val wantInject = injected < counterStart && (injectEveryCycle || (cycles % 2 == 0))
      dut.io.connNetwork.valid.poke(if (wantInject) true.B else false.B)
      dut.io.connNetwork.bits.poke(counterAddr.U)

      cmem.beforeStep(); tmem.beforeStep()
      reinjections += driveStealNetwork(dut)

      val accepted = wantInject && dut.io.connNetwork.ready.peek().litToBoolean

      dut.clock.step()
      cmem.afterStep(); tmem.afterStep()
      cycles += 1
      if (accepted) injected += 1

      val remaining = cmem.mem(counterAddr).toInt
      if (remaining != prevRemaining || injected != prevRemaining) {
        // progress = a decrement committed or a request was accepted
      }
      if (remaining != prevRemaining || accepted) { lastProgress = cycles; prevRemaining = remaining }
    }
    dut.io.connNetwork.valid.poke(false.B)

    // Let any tail re-injection drain.
    for (_ <- 0 until 200) {
      cmem.beforeStep(); tmem.beforeStep(); reinjections += driveStealNetwork(dut)
      dut.clock.step(); cmem.afterStep(); tmem.afterStep()
    }

    val totalWritten = cmem.writes.map { case (_, o, n) => o - n }.sum
    val lastRead = if (cmem.reads.nonEmpty) cmem.reads.last._2 else BigInt(-1)
    println(s"[ArgServer] writes(delta): ${cmem.writes.map { case (c, o, n) => s"$c:${o - n}" }.mkString(" ")}")
    println(s"[ArgServer] totalWritten=$totalWritten lastRead=$lastRead start=$counterStart " +
      s"reinjections=$reinjections injected=$injected cycles=$cycles lastProgress=$lastProgress")

    scalaAssert(
      injected == counterStart,
      s"only injected $injected/$counterStart decrement requests before stall @cycle $cycles " +
        s"(input wedged -- qFeedback deadlock?)"
    )
    scalaAssert(
      reinjections == 1,
      s"expected exactly one continuation re-injection (counter logically hits 0 exactly once), " +
        s"got $reinjections after $counterStart decrements " +
        s"[totalWritten=$totalWritten lastRead=$lastRead cycles=$cycles lastProgress=$lastProgress]"
    )
    // The re-injecting RMW must land on exactly 0: the decrements written back plus
    // the final batch (== the last value read) must equal counterStart. If it fired
    // early (over-count) or the counter never hit 0 (under-count), this breaks.
    scalaAssert(
      totalWritten + lastRead == counterStart,
      s"MISCOUNT: written($totalWritten) + finalBatch($lastRead) != start($counterStart)"
    )
  }

  // Long read latency: every same-address request collapses during the cnt_rd
  // window. This is the easy path and should pass.
  it should "fan-in start=64, long read latency (collapse path)" in {
    test(new ArgumentServer(taskWidth, counterWidth, sysAddressWidth, tagBitsShift, wId)) { dut =>
      runFanIn(dut, counterStart = 64, readLatency = 8, writeLatency = 8, injectEveryCycle = true)
    }
  }

  // Short read, long write-back: the slot sits in cnt_wd (read done, writeback
  // pending) while new same-address requests keep arriving -- forcing them onto
  // the qFeedback>=3 retry path that carries the in-source deadlock warning.
  it should "fan-in start=64, short read + long writeback (cnt_wd feedback path)" in {
    test(new ArgumentServer(taskWidth, counterWidth, sysAddressWidth, tagBitsShift, wId)) { dut =>
      runFanIn(dut, counterStart = 64, readLatency = 1, writeLatency = 16, injectEveryCycle = true)
    }
  }

  // A counter size that does not divide evenly into collapse batches, with the
  // feedback path active.
  it should "fan-in start=100, short read + long writeback" in {
    test(new ArgumentServer(taskWidth, counterWidth, sysAddressWidth, tagBitsShift, wId)) { dut =>
      runFanIn(dut, counterStart = 100, readLatency = 2, writeLatency = 20, injectEveryCycle = true)
    }
  }

  // triangleCountDecoupled creates many independent counter=2 joins. Two
  // memReader completions target each address while hundreds of other joins are
  // active, unlike BFS's one-address fan-in. Every address must re-inject once.
  it should "complete many interleaved two-return joins exactly once" in {
    test(new ArgumentServer(taskWidth, counterWidth, sysAddressWidth, tagBitsShift, wId)) { dut =>
      dut.clock.setTimeout(0)
      val cmem = new CounterAxi(dut, readLatency = 1, writeLatency = 12,
        writeReady = cyc => cyc % 5 != 0)
      val tmem = new TaskAxi(dut, latency = 3)
      val joinCount = 512
      val addrs = (0 until joinCount).map(i => counterAddr + i * 0x40)
      addrs.foreach(a => cmem.mem(a) = 2)

      // Interleave both returns within batches while keeping many unrelated
      // counter RMWs in flight.
      val requests = addrs.grouped(16).flatMap { batch =>
        batch ++ batch.reverse
      }.toVector

      dut.io.connNetwork.valid.poke(false.B)
      for (_ <- 0 until 8) {
        cmem.beforeStep(); tmem.beforeStep(); driveStealNetwork(dut)
        dut.clock.step(); cmem.afterStep(); tmem.afterStep()
      }

      var injected = 0
      var reinjected = 0
      var cycles = 0
      var lastProgress = 0
      while ((injected < requests.size || reinjected < joinCount) &&
        cycles - lastProgress < 5000 && cycles < 200000) {
        val valid = injected < requests.size
        dut.io.connNetwork.valid.poke(valid.B)
        dut.io.connNetwork.bits.poke(
          (if (valid) requests(injected) else BigInt(0)).U)
        cmem.beforeStep(); tmem.beforeStep()
        val fired = valid && dut.io.connNetwork.ready.peek().litToBoolean
        val r = driveStealNetwork(dut)
        dut.clock.step(); cmem.afterStep(); tmem.afterStep()
        cycles += 1
        if (fired) { injected += 1; lastProgress = cycles }
        if (r != 0) { reinjected += r; lastProgress = cycles }
      }

      scalaAssert(injected == requests.size,
        s"paired joins stalled after $injected/${requests.size} returns")
      scalaAssert(reinjected == joinCount,
        s"expected $joinCount reinjections, got $reinjected")
      val groupedReads = tmem.readAddresses.groupBy(identity).view.mapValues(_.size).toMap
      val bad = addrs.filter(a => groupedReads.getOrElse(a, 0) != 1)
      scalaAssert(bad.isEmpty,
        s"continuations not re-injected exactly once: ${bad.take(16).mkString(",")}")
    }
  }

  // THE as-skitter dist-4 case: a large counter hammered at full rate while the
  // counter WRITEBACK port is mostly starved (HBM busy with helper graph reads).
  // Reads return fast, but writes only commit on rare ready windows, so RMW slots
  // sit in cnt_wd and incoming same-address decrements pour onto the qFeedback
  // path. If the qFeedback>=3 gate stalls the shared input, the whole engine
  // wedges and the counter freezes short of 0 -- the hang.
  it should "WEDGE REPRO: large fan-in with starved counter-writeback port" in {
    test(new ArgumentServer(taskWidth, counterWidth, sysAddressWidth, tagBitsShift, wId)) { dut =>
      // write port ready only 1 cycle in 16 -> heavy write starvation.
      runFanIn(dut, counterStart = 400, readLatency = 1, writeLatency = 2,
        injectEveryCycle = true, stallLimit = 3000,
        writeReady = cyc => (cyc % 16 == 0))
    }
  }

  // The actual BFS loop: level L re-sets the SAME counter address (store_continuation)
  // to F_L, F_L helper completions decrement it, the last one drives it to 0 and the
  // server re-injects the continuation, which (in the real system) triggers the BFS
  // PE to re-set the counter for level L+1. Here we model exactly that cycle through
  // ONE reused ArgumentServer across many levels, with realistic re-injection
  // backpressure and jittery AXI latency. A miscount or wedge in level-to-level reuse
  // -- the as-skitter hang, which only shows up after several levels -- trips this.
  private def runMultiLevel(
      dut: ArgumentServer,
      levelSizes: Seq[Int],
      readLatency: Int,
      writeLatency: Int,
      stealBackpressure: Boolean,
      stallLimit: Int = 6000
  ): Unit = {
    dut.clock.setTimeout(0)
    val cmem = new CounterAxi(dut, readLatency, writeLatency)
    val tmem = new TaskAxi(dut, readLatency)

    var cycles = 0
    var totalReinjections = 0

    def stealReadyNow: Boolean = !stealBackpressure || (cycles % 3 != 0)

    // settle
    dut.io.connNetwork.valid.poke(false.B)
    for (_ <- 0 until 8) {
      cmem.beforeStep(); tmem.beforeStep()
      driveStealNetwork(dut, qReady = stealReadyNow, stealReady = stealReadyNow)
      dut.clock.step(); cmem.afterStep(); tmem.afterStep(); cycles += 1
    }

    for ((f, level) <- levelSizes.zipWithIndex) {
      // store_continuation re-sets the counter for this level.
      cmem.mem(counterAddr) = BigInt(f)
      var injected = 0
      var levelReinjected = 0
      var lastProgress = cycles
      val writesBefore = cmem.writes.size

      while (levelReinjected == 0 && (cycles - lastProgress) < stallLimit) {
        val wantInject = injected < f
        dut.io.connNetwork.valid.poke(if (wantInject) true.B else false.B)
        dut.io.connNetwork.bits.poke(counterAddr.U)

        cmem.beforeStep(); tmem.beforeStep()
        val r = driveStealNetwork(dut, qReady = stealReadyNow, stealReady = stealReadyNow)

        val accepted = wantInject && dut.io.connNetwork.ready.peek().litToBoolean

        dut.clock.step(); cmem.afterStep(); tmem.afterStep(); cycles += 1
        if (accepted) { injected += 1; lastProgress = cycles }
        if (cmem.writes.size != writesBefore) lastProgress = cycles
        if (r > 0) { levelReinjected += r; totalReinjections += r; lastProgress = cycles }
      }
      dut.io.connNetwork.valid.poke(false.B)

      scalaAssert(
        injected == f,
        s"level $level (F=$f): only injected $injected/$f before stall @cycle $cycles " +
          s"(input wedged); totalReinjections=$totalReinjections"
      )
      scalaAssert(
        levelReinjected == 1,
        s"level $level (F=$f): expected exactly one re-injection, got $levelReinjected @cycle $cycles " +
          s"-- counter never reached 0 (lost decrement) or fired twice. " +
          s"memNow=${cmem.mem(counterAddr)} totalReinjections=$totalReinjections"
      )
    }
    println(s"[ArgServer] multi-level OK: ${levelSizes.size} levels, totalReinjections=$totalReinjections, cycles=$cycles")
  }

  it should "MULTI-LEVEL BFS loop: many levels reuse one counter (uniform sizes)" in {
    test(new ArgumentServer(taskWidth, counterWidth, sysAddressWidth, tagBitsShift, wId)) { dut =>
      runMultiLevel(dut, levelSizes = Seq.fill(12)(40), readLatency = 4, writeLatency = 8,
        stealBackpressure = false)
    }
  }

  it should "MULTI-LEVEL BFS loop: varied level sizes + steal backpressure + jitter" in {
    test(new ArgumentServer(taskWidth, counterWidth, sysAddressWidth, tagBitsShift, wId)) { dut =>
      runMultiLevel(dut, levelSizes = Seq(1, 5, 17, 33, 64, 28, 9, 50, 3, 41),
        readLatency = 1, writeLatency = 13, stealBackpressure = true)
    }
  }
}
