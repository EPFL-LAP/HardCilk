package Scheduler.tests

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec

import Scheduler.{SchedulerLocalNetwork, SchedulerServer, SpawnerServer}

import chext.amba.axi4
import axi4.Ops._

/** A real SchedulerServer sharing the elastic PE-local ring with real spawners.
  *
  * The situation under test is the one the whole elastic-ring design leans on for liveness: the
  * scheduler and a spawner are both injecting flat out while the PEs refuse to take anything. The
  * ring fills, and the scheduler is supposed to notice and flip from producing into consuming --
  * absorbing the ring's surplus back into HBM. That flip is what releases the backpressure holding
  * the spawner off, so it is the only thing that ends the standoff.
  *
  * `spawnerLane` chooses which spawner is loaded, which is how the distance between the two
  * injectors is varied: the layout is vas0 -> vss0 -> pe0 -> vas1 -> pe1 -> ..., so the scheduler
  * sits at node 1 and spawner k at node 2k (k >= 1), i.e. 2k-1 hops upstream of it going forward,
  * with k-1 PEs in between.
  */
class CongestionRingHarness(
    taskWidth: Int,
    addrWidth: Int,
    peCount: Int,
    queueDepth: Int,
    spawnerQueueDepth: Int,
    nBeats: Int
) extends Module {
  // Same shape SchedulerServer's RegisterBlock(wAddr = 6, wData = 64, wMask = 6) produces.
  // Built directly rather than by instantiating a RegisterBlock, which would also create an
  // undriven s_axil wire in this module.
  private val cfgAxi = axi4.Config(wAddr = 6, wData = 64, lite = true)

  val io = IO(new Bundle {
    val axi_mgmt = axi4.lite.Slave(cfgAxi)

    // Per-spawner co-located source.
    val srcValid = Input(Vec(peCount, Bool()))
    val srcBits = Input(Vec(peCount, UInt(taskWidth.W)))

    // PE drains.
    val peReady = Input(Vec(peCount, Bool()))
    val peValid = Output(Vec(peCount, Bool()))
    val peBits = Output(Vec(peCount, UInt(taskWidth.W)))

    // Scheduler observation.
    val congested = Output(Bool())
    val paused = Output(Bool())
    val schedInject = Output(Bool()) // scheduler put a task on the ring
    val schedAbsorb = Output(Bool()) // scheduler took a task off the ring
    val ntwOccupancy = Output(Bool())

    // Scheduler HBM ports, driven by the testbench.
    val read_address = DecoupledIO(UInt(addrWidth.W))
    val read_data = Flipped(DecoupledIO(UInt(taskWidth.W)))
    val read_burst_len = Output(UInt(4.W))
    val write_address = DecoupledIO(UInt(addrWidth.W))
    val write_data = DecoupledIO(UInt(taskWidth.W))
    val write_burst_len = Output(UInt(4.W))
    val write_last = Output(UInt(1.W))
    val write_idle = Input(Bool())
  })

  val net = Module(
    new SchedulerLocalNetwork(
      peCount = peCount,
      vssCount = 1,
      vasCount = peCount,
      taskWidth = taskWidth,
      queueDepth = queueDepth,
      qRamReadLatency = 1,
      qRamWriteLatency = 1,
      spawnsItself = false,
      successiveNetworkConfig = false
    )
  )

  val spawners = Seq.fill(peCount)(Module(new SpawnerServer(taskWidth, spawnerQueueDepth)))

  for (i <- 0 until peCount) {
    spawners(i).io.connNetwork_master <> net.io.connVAS(i)
    net.io.vasForceInject(i) := spawners(i).io.forceInject

    val s = spawners(i).io.connNetwork_slave
    s.data.availableTask.valid := io.srcValid(i)
    s.data.availableTask.bits := io.srcBits(i)
    s.data.qOutTask.ready := true.B
    s.ctrl.serveStealReq.ready := false.B
    s.ctrl.stealReq.ready := true.B

    net.io.connPE(i).pop.ready := io.peReady(i)
    net.io.connPE(i).push.valid := false.B
    net.io.connPE(i).push.bits := 0.U
    io.peValid(i) := net.io.connPE(i).pop.valid
    io.peBits(i) := net.io.connPE(i).pop.bits
  }

  // Same derivation Scheduler.scala uses, so the test exercises the shipped thresholds rather than
  // a private set of its own: assert at ~82% of the window, clear at ~59%.
  private val contentionWindow = peCount + peCount + 1
  private val contentionAssertAt =
    math.max(math.min(math.ceil(contentionWindow * 0.82).toInt, peCount + peCount), 1)
  private val contentionClearAt = math.max(math.floor(contentionWindow * 0.59).toInt, 0)
  private val contentionDelta =
    math.min(math.max((contentionAssertAt - contentionClearAt) / 2, 2), contentionAssertAt / 2)
  private val contentionThreshold = contentionAssertAt - contentionDelta

  val sched = Module(
    new SchedulerServer(
      taskWidth = taskWidth,
      contentionThreshold = contentionThreshold,
      peCount = peCount,
      contentionDelta = contentionDelta,
      vasCount = peCount,
      sysAddressWidth = addrWidth,
      ignoreRequestSignals = false,
      nBeats = nBeats
    )
  )

  sched.io.connNetwork <> net.io.connVSS(0)
  sched.io.ntwDataUnitOccupancy := net.io.ntwDataUnitOccupancyVSS(0)
  sched.io.ntwReqArriving := net.io.ntwReqArrivingVSS(0)
  for (i <- 0 until peCount)
    sched.io.lengths_of_hardware_queues(i) := net.io.lengths_of_hardware_queues(i)

  io.axi_mgmt <> sched.io.axi_mgmt
  io.congested := sched.io.congested
  io.paused := sched.io.paused
  io.ntwOccupancy := net.io.ntwDataUnitOccupancyVSS(0)
  io.schedInject :=
    sched.io.connNetwork.data.qOutTask.valid && sched.io.connNetwork.data.qOutTask.ready
  io.schedAbsorb :=
    sched.io.connNetwork.data.availableTask.valid && sched.io.connNetwork.data.availableTask.ready

  io.read_address <> sched.io.read_address
  sched.io.read_data <> io.read_data
  io.read_burst_len := sched.io.read_burst_len
  io.write_address <> sched.io.write_address
  io.write_data <> sched.io.write_data
  io.write_burst_len := sched.io.write_burst_len
  io.write_last := sched.io.write_last
  sched.io.write_idle := io.write_idle
}

class SchedulerCongestionTests extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "scheduler congestion relief on the elastic ring"

  private val taskWidth = 64
  private val addrWidth = 64
  private val nBeats = 16
  private val schedTag = BigInt(1) << 40

  private def anns = Seq(VerilatorBackendAnnotation)

  private def liteWrite(dut: CongestionRingHarness, hbm: Hbm, off: Int, data: BigInt): Unit = {
    val m = dut.io.axi_mgmt
    var awDone = false; var wDone = false
    m.aw.bits.addr.poke(off.U); m.aw.bits.prot.poke(0.U)
    m.w.bits.data.poke(data.U); m.w.bits.strb.poke(((BigInt(1) << 8) - 1).U)
    m.aw.valid.poke(true.B); m.w.valid.poke(true.B); m.b.ready.poke(true.B)
    var g = 0
    while ((!awDone || !wDone) && g < 200) {
      if (!awDone && m.aw.ready.peek().litToBoolean) awDone = true
      if (!wDone && m.w.ready.peek().litToBoolean) wDone = true
      hbm.tick(); dut.clock.step(); g += 1
      if (awDone) m.aw.valid.poke(false.B)
      if (wDone) m.w.valid.poke(false.B)
    }
    m.aw.valid.poke(false.B); m.w.valid.poke(false.B)
    g = 0
    while (!m.b.valid.peek().litToBoolean && g < 200) { hbm.tick(); dut.clock.step(); g += 1 }
    hbm.tick(); dut.clock.step(); m.b.ready.poke(false.B)
  }

  /** Point the scheduler at an HBM ring holding `curr` tasks so it has something to inject. */
  private def configure(dut: CongestionRingHarness, hbm: Hbm, maxLen: Int, curr: Int): Unit = {
    // Headroom matters: the server quiesces itself if
    // currLen + localQueue + nBeats > maxLength ("resizeNeeded"), which silently disables the whole
    // datapath. Same shape the driver programs: tail = head = 0, currLen = tasks resident.
    liteWrite(dut, hbm, 0x08, BigInt("1000", 16)) // rAddr
    liteWrite(dut, hbm, 0x10, BigInt(maxLen)) // maxLength
    liteWrite(dut, hbm, 0x18, BigInt(0)) // fifoTail
    liteWrite(dut, hbm, 0x20, BigInt(0)) // fifoHead
    liteWrite(dut, hbm, 0x28, BigInt(0)) // enableSteal
    liteWrite(dut, hbm, 0x30, BigInt(curr)) // currLen
    liteWrite(dut, hbm, 0x00, BigInt(0)) // rPause = 0, run
  }

  /** Trivially-satisfying HBM: reads always return a tagged task, writes always accepted. */
  private class Hbm(dut: CongestionRingHarness, beatGap: Int = 0) {
    // Beats OWED, not beats-of-the-current-burst. The server keeps up to
    // maxOutstandingReadBursts (8) in flight, so a model that tracks one burst at a time silently
    // drops the rest: outstandingReads then never returns to zero and writeCanIssue -- which is
    // gated on exactly that -- is blocked for the whole run by the testbench rather than the RTL.
    private var beatsOwed = 0
    private var gapCnt = 0
    private var nextVal = BigInt(1)
    var writesAccepted = 0
    var readsIssued = 0
    var awFires = 0

    def init(): Unit = {
      dut.io.read_address.ready.poke(true.B)
      dut.io.write_address.ready.poke(true.B)
      dut.io.write_data.ready.poke(true.B)
      dut.io.write_idle.poke(true.B)
      dut.io.read_data.valid.poke(false.B)
      dut.io.read_data.bits.poke(0.U)
    }

    /** Call once per cycle, before stepping. */
    def tick(): Unit = {
      if (
        dut.io.read_address.valid.peek().litToBoolean &&
        dut.io.read_address.ready.peek().litToBoolean
      ) {
        beatsOwed += dut.io.read_burst_len.peek().litValue.toInt + 1
        readsIssued += 1
      }
      gapCnt += 1
      val supply = beatsOwed > 0 && (beatGap == 0 || gapCnt % (beatGap + 1) == 0)
      dut.io.read_data.valid.poke(supply.B)
      if (supply) dut.io.read_data.bits.poke((schedTag + nextVal).U)
      if (supply && dut.io.read_data.ready.peek().litToBoolean) {
        beatsOwed -= 1; nextVal += 1
      }
      if (
        dut.io.write_address.valid.peek().litToBoolean &&
        dut.io.write_address.ready.peek().litToBoolean
      ) awFires += 1
      if (
        dut.io.write_data.valid.peek().litToBoolean &&
        dut.io.write_data.ready.peek().litToBoolean
      ) writesAccepted += 1
    }
  }

  private case class Result(
      congestedCycles: Int,
      firstCongested: Int,
      injects: Int,
      absorbs: Int,
      writes: Int,
      congestedEdges: Int,
      spawnerPops: Int
  )

  /** Both injectors flat out, PEs refusing for `blockedCycles`, then released. */
  private def run(
      peCount: Int,
      spawnerLane: Int,
      blockedCycles: Int,
      totalCycles: Int,
      beatGap: Int = 0
  ): Result = {
    var out: Result = null
    test(new CongestionRingHarness(taskWidth, addrWidth, peCount, 8, 8, nBeats))
      .withAnnotations(anns) { dut =>
        dut.clock.setTimeout(0)
        val hbm = new Hbm(dut, beatGap)
        hbm.init()
        for (i <- 0 until peCount) {
          dut.io.srcValid(i).poke(false.B)
          dut.io.srcBits(i).poke((i + 1).U)
          dut.io.peReady(i).poke(false.B)
        }
        configure(dut, hbm, maxLen = 4096, curr = 2048)

        // One spawner spams from a bottomless source; the PEs take nothing.
        dut.io.srcValid(spawnerLane).poke(true.B)

        var congestedCycles = 0
        var firstCongested = -1
        var injects = 0
        var absorbs = 0
        var edges = 0
        var prevCongested = false
        var spawnerPops = 0

        for (c <- 0 until totalCycles) {
          if (c == blockedCycles) for (i <- 0 until peCount) dut.io.peReady(i).poke(true.B)
          hbm.tick()

          val cong = dut.io.congested.peek().litToBoolean
          if (cong) {
            congestedCycles += 1
            if (firstCongested < 0) firstCongested = c
          }
          if (cong != prevCongested) edges += 1
          prevCongested = cong

          if (dut.io.schedInject.peek().litToBoolean) injects += 1
          if (dut.io.schedAbsorb.peek().litToBoolean) absorbs += 1
          for (i <- 0 until peCount)
            if (
              dut.io.peReady(i).peek().litToBoolean &&
              dut.io.peValid(i).peek().litToBoolean &&
              dut.io.peBits(i).peek().litValue < schedTag
            ) spawnerPops += 1

          dut.clock.step()
        }
        println(
          s"[cfg] paused=${dut.io.paused.peek().litToBoolean} readsIssued=${hbm.readsIssued} " +
            s"awFires=${hbm.awFires} burstLen=${dut.io.write_burst_len.peek().litValue} " +
            s"awValid=${dut.io.write_address.valid.peek().litToBoolean}"
        )
        out = Result(congestedCycles, firstCongested, injects, absorbs, hbm.writesAccepted, edges, spawnerPops)
      }
    out
  }

  it should "detect congestion and absorb ring tasks when the PEs refuse" in {
    val r = run(peCount = 4, spawnerLane = 1, blockedCycles = 1200, totalCycles = 1600)
    println(
      s"[congestion] pe=4 lane=1 firstCongested=${r.firstCongested} congestedCycles=${r.congestedCycles} " +
        s"injects=${r.injects} absorbs=${r.absorbs} hbmWrites=${r.writes} edges=${r.congestedEdges}"
    )
    assert(
      r.firstCongested >= 0,
      "the scheduler never flagged congestion even though both injectors were flat out and no PE " +
        "took anything -- the ring has no way back from a standoff"
    )
    assert(
      r.absorbs > 0,
      s"congestion was flagged (${r.congestedCycles} cycles) but the scheduler never absorbed a " +
        "single task off the ring, so nothing was relieved"
    )
    assert(r.writes > 0, s"the scheduler absorbed ${r.absorbs} tasks but wrote none back to HBM")
  }

  // The scheduler sits at ring node 1 and spawner k at node 2k, so loading a distant spawner puts
  // several PEs between the two injectors. Detection must not depend on adjacency.
  it should "detect congestion regardless of the gap between scheduler and spawner" in {
    for ((peCount, lane) <- Seq((4, 1), (4, 3), (8, 1), (8, 7))) {
      val r = run(peCount, lane, blockedCycles = 1200, totalCycles = 1600)
      println(
        s"[congestion] pe=$peCount lane=$lane firstCongested=${r.firstCongested} " +
          s"congested=${r.congestedCycles} absorbs=${r.absorbs} writes=${r.writes} edges=${r.congestedEdges}"
      )
      assert(
        r.firstCongested >= 0 && r.absorbs > 0,
        s"pe=$peCount lane=$lane: no congestion relief (congested=${r.congestedCycles}, " +
          s"absorbs=${r.absorbs}) -- detection depends on how close the two injectors are"
      )
    }
  }

  // Stickiness. Absorbing frees ring slots, which drops occupancy, which can clear the flag before
  // anything has really drained -- and then it re-congests. If the flag flaps, the scheduler
  // oscillates between producing and consuming and never makes progress either way.
  // Read beats returning with gaps, which is what real HBM does. Absorb must not fill the buffer
  // in those gaps: a beat that then cannot land keeps outstandingReads nonzero, and writeCanIssue
  // waits on exactly that, so the spill can never start.
  it should "make writeback progress when read beats return with gaps" in {
    val r = run(peCount = 4, spawnerLane = 1, blockedCycles = 2000, totalCycles = 2000, beatGap = 3)
    println(s"[congestion] gapped reads: congested=${r.congestedCycles} absorbs=${r.absorbs} writes=${r.writes}")
    assert(
      r.writes > 0,
      s"no HBM writeback with gapped read returns (absorbs=${r.absorbs}): absorbed tasks filled the " +
        "buffer between beats, the in-flight read cannot retire, and writeCanIssue is gated on it"
    )
  }

  // The flag has to STAY asserted long enough to be useful, because writeCanIssue is gated on it --
  // every drop aborts the spill in progress. What matters is that relief keeps happening, not the
  // raw toggle count, so assert on coverage and on work done rather than on edges. (An earlier
  // version of this test asserted edges <= 4; that was a proxy, and it stopped tracking harm once
  // the spill got far enough between toggles to make real progress.)
  it should "hold congestion long enough to keep spilling while the PEs refuse" in {
    for (peCount <- Seq(4, 8)) {
      val cycles = 2000
      val r = run(peCount = peCount, spawnerLane = 1, blockedCycles = cycles, totalCycles = cycles)
      println(
        s"[congestion] sustained pe=$peCount: congested=${r.congestedCycles}/$cycles " +
          s"edges=${r.congestedEdges} absorbs=${r.absorbs} writes=${r.writes}"
      )
      assert(r.firstCongested >= 0, s"pe=$peCount: never congested with the PEs refusing throughout")
      assert(
        r.congestedCycles * 2 >= cycles,
        s"pe=$peCount: congested only ${r.congestedCycles}/$cycles with the PEs refusing throughout"
      )
      assert(
        r.writes > cycles / 4,
        s"pe=$peCount: only ${r.writes} HBM writes for ${r.absorbs} absorbed -- the spill is being " +
          s"aborted by the flag dropping (${r.congestedEdges} toggles)"
      )
    }
  }

  // Once the PEs start draining again the scheduler must go back to feeding them, or the flag is a
  // one-way trap and the ring stays starved for the rest of the run.
  it should "resume injecting once the PEs drain again" in {
    val r = run(peCount = 4, spawnerLane = 1, blockedCycles = 800, totalCycles = 2400)
    println(
      s"[congestion] recovery: congested=${r.congestedCycles}/2400 injects=${r.injects} " +
        s"absorbs=${r.absorbs} spawnerPops=${r.spawnerPops}"
    )
    assert(r.injects > 0, "the scheduler never injected at all")
    assert(
      r.spawnerPops > 0,
      "no spawner task ever reached a PE after the drain reopened -- the standoff never cleared"
    )
  }
}
