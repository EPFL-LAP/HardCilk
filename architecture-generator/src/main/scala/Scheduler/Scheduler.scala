package Scheduler

import chisel3._
import chisel3.util._
import scala.math._

import AXIHelpers._
import Util._

import chext.amba.axi4
import chext.amba.axi4s
import axi4s.Casts._
import axi4.Ops._

import axi4.full.components._

case class SchedulerOutsideRingLayout(
    spawnerIndices: Vector[Int],
    sourceIndices: Vector[Int]
)

object SchedulerOutsideRingLayout {
  /** Place outside-ring sources and spawners.
    *
    * With grouped argument lanes, sources retain their natural order and each
    * spawner is co-located with the final lane in its striped group. Any
    * ungrouped sources (for example, the slow argument handler) remain after
    * the grouped fast lanes. With groupSize == 1, preserve the historical
    * priority/co-location policy.
    */
  def build(
      sourceCount: Int,
      spawnerCount: Int,
      groupedSourceStart: Int = 0,
      groupedSourceCount: Int = 0,
      groupSize: Int = 1
  ): SchedulerOutsideRingLayout = {
    require(sourceCount > 0)
    require(spawnerCount > 0)
    require(groupSize > 0)

    val networkSize = max(sourceCount, spawnerCount)
    def equallySpacedIndices(count: Int): Vector[Int] =
      Vector.tabulate(count)(i => i * networkSize / count)

    if (groupSize > 1) {
      require(groupedSourceStart >= 0)
      require(groupedSourceCount > 0)
      require(groupedSourceCount % groupSize == 0)
      require(
        groupedSourceCount / groupSize == spawnerCount,
        "striped argument-lane groups must match the spawn-server count"
      )
      require(groupedSourceStart + groupedSourceCount <= sourceCount)

      val spawners = Vector.tabulate(spawnerCount) { i =>
        groupedSourceStart + (i + 1) * groupSize - 1
      }
      require(spawners.distinct.size == spawners.size)
      require(spawners.forall(_ < networkSize))
      SchedulerOutsideRingLayout(
        spawnerIndices = spawners,
        sourceIndices = Vector.tabulate(sourceCount)(identity)
      )
    } else if (groupedSourceStart > 0 && groupedSourceCount > 0) {
      // Both kinds of source feed this ring: spawns arriving from another task
      // (sources [0, groupedSourceStart)) and continuation lanes released by the
      // argument server (the groupedSourceCount that follow them).
      //
      // The slots co-located with a spawner are the privileged ones, because
      // BufferServerInput serves its co-located source and DECLINES the ring task
      // while it has one, leaving that task to circulate. Those slots go to the
      // continuation lanes, so a continuation already in flight is drained before
      // new work is injected -- the opposite assignment lets an upstream task
      // flood the ring with fresh tasks while resident ones go round and round.
      //
      // With one lane and one incoming spawn per spawner this lays out as
      //
      //   taskOutGlobal_0 -> (continuation_0 + spawner_0) -> taskOutGlobal_1 -> ...
      //
      // (data flows node i -> node i+1), so each spawn source also sits exactly
      // one hop upstream of the spawner it feeds.
      val evenSplit =
        groupedSourceStart == spawnerCount && groupedSourceCount == spawnerCount
      val spawners =
        if (evenSplit) Vector.tabulate(spawnerCount)(i => 2 * i + 1)
        else equallySpacedIndices(spawnerCount)

      val laneSlots = spawners.take(groupedSourceCount)
      val leftoverSlots =
        (spawners.drop(groupedSourceCount) ++
          Vector.tabulate(networkSize)(identity).filterNot(spawners.contains))
          .iterator

      val sources = Array.fill(sourceCount)(-1)
      for (k <- laneSlots.indices) sources(groupedSourceStart + k) = laneSlots(k)
      for (j <- 0 until sourceCount if sources(j) < 0)
        sources(j) = leftoverSlots.next()

      SchedulerOutsideRingLayout(spawners, sources.toVector)
    } else {
      val spawners = equallySpacedIndices(spawnerCount)
      val sources =
        if (sourceCount <= spawnerCount) equallySpacedIndices(sourceCount)
        else
          spawners ++ Vector
            .tabulate(networkSize)(identity)
            .filterNot(spawners.contains)
      SchedulerOutsideRingLayout(spawners, sources)
    }
  }
}

class SchedulerPEIO(
    pePortWidth: Int,
    peCount: Int,
    spawnsItself: Boolean,
    peCountGlobalTaskIn: Int
) extends Bundle {

  implicit val axisCfgTask: axi4s.Config =
    axi4s.Config(wData = pePortWidth, onlyRV = true)

  val taskOut = Vec(peCount, axi4s.Master(axisCfgTask))

  val taskIn =
    if (spawnsItself) Some(Vec(peCount, axi4s.Slave(axisCfgTask))) else None
  val taskInGlobal =
    if (peCountGlobalTaskIn > 0)
      Some(Vec(peCountGlobalTaskIn, axi4s.Slave(axisCfgTask)))
    else None

  // a getter function for the port with name and index
  def getPort(name: String, index: Int): axi4s.Interface = {
    name match {
      case "taskOut"      => taskOut(index)
      case "taskIn"       => taskIn.get(index)
      case "taskInGlobal" => taskInGlobal.get(index)
    }
  }
}

class SchedulerAxiIO(
    vssCount: Int,
    axiMgmtCfg: axi4.Config,
    addrWidth: Int,
    taskWidth: Int,
    vssAxiFullCfg: axi4.Config
) extends Bundle {
  val nAxiPorts = vssCount

  val vss_axi_full = Vec(nAxiPorts, axi4.full.Master(vssAxiFullCfg))
  val axi_mgmt_vss = Vec(vssCount, axi4.lite.Slave(axiMgmtCfg))
}

class Scheduler(
    addrWidth: Int,
    override val taskWidth: Int,
    queueDepth: Int,
    override val peCount: Int,
    override val schedulerServersNumber: Int,
    spawnsItself: Boolean,
    peCountGlobalTaskIn: Int,
    argRouteServersNumber: Int,
    pePortWidth: Int,
    peType: String,
    debug: Boolean,
    override val spawnerServerNumber: Int = 1,
    spawnerQueueDepth: Int = 16,
    argRouteServersCreateTasks: Boolean = false,
    override val mfpgaSupport: Boolean = false,
    maxNumnberToStealOrServe: Int = 256,
    override val taskId: Int = 0,
    override val axisCfgTaskAndReq: axi4s.Config =
      axi4s.Config(wData = 512, wDest = 4),
    enableGlobalStart: Boolean = false,
    useAffinity: Boolean = false,
    affinityQueueDepth: Int = 0,
    affinityTagBits: Int = 0,
    fastArgumentRouteServersNumber: Int = 0,
    newContinuationLaneStripingFactor: Int = 1,
    // Width of each scheduler server's HBM ring port. 0 (the default) means "one
    // beat per task"; a narrower port spreads a task over several beats without
    // changing the ring layout, which is what lets a task be wider than any HBM
    // data channel the platform offers.
    ringPortWidth: Int = 0
) extends Module
    with SchedulerHasMfpgaSupport
    with SchedulerModule {

  private val vssPortWidth =
    if (ringPortWidth > 0) ringPortWidth else taskWidth

  val vssAxiFullCfg = axi4.Config(
    wAddr = addrWidth,
    wData = vssPortWidth,
    lite = false,
    wId = 1
  )

  val outsideSpawn =
    ((peCountGlobalTaskIn + argRouteServersNumber) > 0) && (argRouteServersCreateTasks || peCountGlobalTaskIn > 0)

  println(f"Outside spawn ${outsideSpawn} of task ${peType}")

  val spawnerServer =
    if (outsideSpawn)
      Some(Seq.fill(spawnerServerNumber)(Module(new SpawnerServer(taskWidth, queueDepth = spawnerQueueDepth))))
    else None

  val outsideSpawnSourceCount = peCountGlobalTaskIn + argRouteServersNumber
  val outsideSpawnNetworkSize =
    if (outsideSpawn) max(outsideSpawnSourceCount, spawnerServerNumber) else 0

  private val outsideRingLayout =
    if (outsideSpawn)
      Some(
        SchedulerOutsideRingLayout.build(
          sourceCount = outsideSpawnSourceCount,
          spawnerCount = spawnerServerNumber,
          groupedSourceStart = peCountGlobalTaskIn,
          groupedSourceCount = fastArgumentRouteServersNumber,
          groupSize = newContinuationLaneStripingFactor
        )
      )
    else None

  val spawnerIndices =
    outsideRingLayout.map(_.spawnerIndices.toArray).getOrElse(Array.empty[Int])
  val sourceIndices =
    outsideRingLayout.map(_.sourceIndices.toArray).getOrElse(Array.empty[Int])
  // The larger group occupies every slot; the smaller group is evenly spaced among it.
  val pairedIndices =
    if (outsideSpawnSourceCount <= spawnerServerNumber) sourceIndices.toSeq
    else spawnerIndices.toSeq
  val pairedInputIndexBySlot = pairedIndices.zipWithIndex.toMap
  val bufferServerInputs =
    Seq.fill(pairedIndices.size)(Module(new BufferServerInput(taskWidth)))

  // Elastic, not rigid. The premise for leaving this ring rigid was that it has
  // "one injector per node and no contention"; the first half is true and the
  // second is not. Several sources inject here -- every incoming-spawn buffer
  // and every fast/slow continuation lane that did not draw a co-located spawner
  // slot -- and on a rigid ring a hole can only be taken by whoever it drifts
  // past first, so an injector one hop downstream of a busy one never gets a
  // turn. See the injectWanted/forceForward policy at the bottom of this module.
  val getOutsideSpawnNetwork =
    if (outsideSpawn)
      Some(
        Module(
          new SchedulerNetwork(
            taskWidth,
            outsideSpawnNetworkSize,
            spawnerIndices,
            elasticData = true
          )
        )
      )
    else None

  // Log the size of the outside spawn network
  if (outsideSpawn) {
    println(
      f"Outside spawn network size: ${getOutsideSpawnNetwork.get.io.connSS.size} connections"
    )
    println(f"Spawner server indices: ${spawnerIndices.mkString(", ")}")
    println(f"Task source indices: ${sourceIndices.mkString(", ")}")
  }

  if (outsideSpawn) {
    for (i <- 0 until spawnerServerNumber) {
      val slot = spawnerIndices(i)
      pairedInputIndexBySlot.get(slot) match {
        case Some(inputIndex) =>
          bufferServerInputs(inputIndex).io.connNetwork_slave <>
            getOutsideSpawnNetwork.get.io.connSS(slot)
          bufferServerInputs(inputIndex).io.connSpawnerServer <>
            spawnerServer.get(i).io.connNetwork_slave
        case None =>
          spawnerServer.get(i).io.connNetwork_slave <>
            getOutsideSpawnNetwork.get.io.connSS(slot)
      }

      println(
        f"Spawner server ${i} connection to outside spawn network: ${getOutsideSpawnNetwork.get.io.connSS(slot).toString()}"
      )
    }
  }

  private def connectOutsideSpawnSource(
      sourceIndex: Int,
      source: SchedulerNetworkClientIO
  ): Unit = {
    val slot = sourceIndices(sourceIndex)
    pairedInputIndexBySlot.get(slot) match {
      case Some(inputIndex) =>
        bufferServerInputs(inputIndex).io.connTaskSource <> source
      case None =>
        getOutsideSpawnNetwork.get.io.connSS(slot) <> source
    }
  }

  // Add two entries SchedulerLocalNetwork if mfpgaSupport is enabled, one for task reading and one for task writing from the network.
  val schedulerServersInputToSchedulerLocalNetwork =
    if (mfpgaSupport) (schedulerServersNumber + 2) else schedulerServersNumber
  val schedulerLocalNetworkVasCount =
    if (outsideSpawn) spawnerServerNumber else 0
  val schedulerLocalNetworkLength =
    peCount + schedulerLocalNetworkVasCount + schedulerServersInputToSchedulerLocalNetwork

  val stealNW_TQ = Module(
    new SchedulerLocalNetwork(
      peCount = peCount,
      vssCount = schedulerServersInputToSchedulerLocalNetwork,
      vasCount = schedulerLocalNetworkVasCount,
      taskWidth = taskWidth,
      queueDepth = queueDepth,
      qRamReadLatency = 1,
      qRamWriteLatency = 1,
      spawnsItself = spawnsItself,
      successiveNetworkConfig =
        false, // HARDCODED, #TODO: if hardware generation fails with 1 PE, enable this when vsscount > peCount
      useAffinity = useAffinity,
      affinityQueueDepth = affinityQueueDepth,
      affinityTagBits = affinityTagBits
    )
  )

  if (outsideSpawn) {
    for (i <- 0 until spawnerServerNumber) {
      spawnerServer
        .get(i)
        .asInstanceOf[SpawnerServer]
        .io
        .connNetwork_master <> stealNW_TQ.io.connVAS(i)
      stealNW_TQ.io.vasForceInject(i) :=
        spawnerServer.get(i).asInstanceOf[SpawnerServer].io.forceInject
    }
  }

  // Congestion thresholds, expressed against the rolling window (= the ring length), so they keep
  // their meaning at any ring size. The window holds one -1/0/+1 sample per cycle per ring node.
  //
  //   assert when the sum reaches ~82% of the window
  //   clear  when it falls back to ~59%
  //
  // The gap between them is deliberately wide. A narrow one lets the flag drop on the strength of
  // relief the scheduler itself caused: it starts absorbing, the ring eases, the sum dips a little,
  // and it flips back to injecting before anything has reached HBM. Since writeCanIssue is gated on
  // networkCongested, every such flip aborts the spill. For the 17-node countDecoupled ring this is
  // assert at 14, clear at 10 (it was 14 and 12).
  //
  // SchedulerServer takes a midpoint and a delta rather than the two points, so convert -- and clamp
  // the assert point to peCount + vasCount, which its own require() bounds it by.
  val contentionWindow_ = schedulerLocalNetworkLength
  val contentionVasCount_ = argRouteServersNumber + peCountGlobalTaskIn
  val contentionAssertAt_ =
    max(min(math.ceil(contentionWindow_ * 0.82).toInt, peCount + contentionVasCount_), 1)
  val contentionClearAt_ =
    max(math.floor(contentionWindow_ * 0.59).toInt, 0)
  // Floor the half-gap at 2 (so assert and clear are at least 4 apart). The proportional gap is
  // 0.82 - 0.59 = 0.23 of the window, which integer-rounds to 2 on a 9-node ring and lets the flag
  // drop after a couple of quiet cycles -- measured 22 toggles per run there against 2 on a 17-node
  // ring. Clamped so the clear point stays non-negative, which SchedulerServer's require() needs.
  // Half-gap: at least 2 (so assert and clear sit >= 4 apart), but never more than half the assert
  // point, or the clear point would go negative -- SchedulerServer requires it non-negative.
  val contentionDelta_ =
    min(max((contentionAssertAt_ - contentionClearAt_) / 2, 2), contentionAssertAt_ / 2)
  val contentionThreshold_ = contentionAssertAt_ - contentionDelta_

  val schedulerServers = Seq.fill(schedulerServersNumber)(
    Module(
      new SchedulerServer(
        taskWidth = taskWidth,
        contentionThreshold = contentionThreshold_,
        peCount = peCount,
        contentionDelta = contentionDelta_,
        vasCount = argRouteServersNumber + peCountGlobalTaskIn,
        sysAddressWidth = addrWidth,
        ignoreRequestSignals = false, // HARDCODED
        nBeats = 16,
        ringWindowSize = schedulerLocalNetworkLength,
        enableGlobalStart = enableGlobalStart,
        ringPortWidth = vssPortWidth
      )
    )
  )

  val io_export = IO(
    new SchedulerPEIO(
      pePortWidth = pePortWidth,
      peCount = peCount,
      spawnsItself = spawnsItself,
      peCountGlobalTaskIn = peCountGlobalTaskIn
    )
  )

  // Management AXI-lite config for the per-server register blocks. Derived from
  // the fixed RegisterBlock geometry (see SchedulerServer.regBlock) rather than
  // indexing schedulerServers(0), so io_internal is well-defined even when this
  // task has zero scheduler servers (a non-root task fed purely by spawn).
  // Matches SchedulerServer.regBlock.cfgAxi (RegisterBlock(wAddr=6, wData=64).cfgAxi)
  // built directly so no RegisterBlock (and its dangling s_axil Wire) is created.
  private val schedulerMgmtCfg =
    axi4.Config(wAddr = 6, wData = 64, lite = true)

  val io_internal = IO(
    new SchedulerAxiIO(
      addrWidth = addrWidth,
      taskWidth = taskWidth,
      vssCount = schedulerServersNumber,
      axiMgmtCfg = schedulerMgmtCfg,
      vssAxiFullCfg = vssAxiFullCfg
    )
  )

  val io_paused = IO(Output(Bool()))
  // reduceOption: with zero scheduler servers there is nothing to pause.
  io_paused := schedulerServers.map(_.io.paused).reduceOption(_ || _).getOrElse(false.B)

  // Per-server networkCongested tap, exported in server order for the watcher's
  // "sched_congested" telemetry group (see HardCilk.connectWatcher).
  val io_congested = IO(Vec(schedulerServersNumber, Output(Bool())))
  for (i <- 0 until schedulerServersNumber) {
    io_congested(i) := schedulerServers(i).io.congested
  }

  // DEBUG
  private val rCycleCounter = RegInit(0.U(128.W))
  rCycleCounter := rCycleCounter + 1.U

  private def logTask(name: String, idx: Int, data: UInt): Unit = {
    printf(
      f"[TASK] %%d $peType $name $idx: %%x\n",
      rCycleCounter,
      data
    )
  }
  // DEBUG

  val connArgumentNotifier = IO(
    Vec(argRouteServersNumber, new SchedulerNetworkClientIO(taskWidth))
  )

  // Kernel-global start broadcast (opt-in; see SchedulerServer.globalRun). Driven
  // from the HardCilk top by a single host-writable register and fanned to every
  // server so they all un-pause on the same cycle. Absent when the feature is off.
  val io_globalRun = if (enableGlobalStart) Some(IO(Input(Bool()))) else None

  for (i <- 0 until schedulerServersNumber) {
    io_internal.axi_mgmt_vss(i) :=> schedulerServers(i).io.axi_mgmt
    schedulerServers(i).io.ntwReqArriving := stealNW_TQ.io.ntwReqArrivingVSS(i)
    schedulerServers(i).io.ntwDataUnitOccupancy <> stealNW_TQ.io
      .ntwDataUnitOccupancyVSS(i)
    if (enableGlobalStart)
      schedulerServers(i).io.globalRun.get := io_globalRun.get
  }

  // Plain in-order AXI adapter (no chext.elastic) replacing RVtoAXIBridge +
  // AxiWriteBuffer on the scheduler's HBM ring port. The elastic Arrival/
  // SinkBuffer write path was the suspected source of the memReader wrap
  // corruption (read-after-write settling margin had zero effect on HW, ruling
  // out a read-side cause).
  val vssAdapter = Seq.fill(schedulerServersNumber)(
    Module(new SchedulerAXIAdapter(taskWidth, addrWidth, vssPortWidth))
  )

  val axiFullPorts = vssAdapter.map(_.axi)

  for (i <- 0 until schedulerServersNumber) {
    vssAdapter(i).io.read_address <> schedulerServers(i).io.read_address
    vssAdapter(i).io.read_data <> schedulerServers(i).io.read_data
    vssAdapter(i).io.write_address <> schedulerServers(i).io.write_address
    vssAdapter(i).io.write_data <> schedulerServers(i).io.write_data
    vssAdapter(i).io.read_burst_len := schedulerServers(i).io.read_burst_len
    vssAdapter(i).io.write_burst_len := schedulerServers(i).io.write_burst_len
    vssAdapter(i).io.write_last := schedulerServers(i).io.write_last
    schedulerServers(i).io.write_idle := vssAdapter(i).io.write_idle
    schedulerServers(i).io.connNetwork <> stealNW_TQ.io.connVSS(i)

    // DEBUG
    if (debug) {
      when(vssAdapter(i).axi.w.fire) {
        logTask("VssAxiFull_w", i, vssAdapter(i).axi.w.bits.data.asUInt)
      }
      when(vssAdapter(i).axi.r.fire) {
        logTask("VssAxiFull_r", i, vssAdapter(i).axi.r.bits.data.asUInt)
      }
    }
    // DEBUG
  }

  axiFullPorts.zip(io_internal.vss_axi_full).foreach { case (a, s_axi) =>
    a :=> s_axi
  }

  // DEBUG
  if (debug) {
    val virtualStealServerTakeInCounter = Module(
      new Counter64(schedulerServersNumber)
    )

    for (i <- 0 until schedulerServersNumber) {
      virtualStealServerTakeInCounter.io.signals(i) :=
        schedulerServers(i).io.connNetwork.data.availableTask.fire
      when(schedulerServers(i).io.connNetwork.data.availableTask.fire) {
        logTask(
          "VssTaskOut",
          i,
          schedulerServers(i).io.connNetwork.data.availableTask.bits.asUInt
        )
      }
    }
    dontTouch(virtualStealServerTakeInCounter.io.counter)

    val virtualStealServerGiveOutCounter = Module(
      new Counter64(schedulerServersNumber)
    )

    for (i <- 0 until schedulerServersNumber) {
      virtualStealServerGiveOutCounter.io.signals(i) :=
        schedulerServers(i).io.connNetwork.data.qOutTask.fire
      when(schedulerServers(i).io.connNetwork.data.qOutTask.fire) {
        logTask(
          "VssTaskIn",
          i,
          schedulerServers(i).io.connNetwork.data.qOutTask.bits.asUInt
        )
      }
    }
    dontTouch(virtualStealServerGiveOutCounter.io.counter)
  }
  // DEBUG

  // If taskWidth == pePortWidth, the converter is created as just a wire.
  val axis_stream_converters_out =
    Seq.fill(peCount)(
      Module(new AxisDataWidthConverter(taskWidth, pePortWidth))
    )
  val axis_stream_converters_in =
    if (spawnsItself)
      Some(
        Seq.fill(peCount)(
          Module(new AxisDataWidthConverter(pePortWidth, taskWidth))
        )
      )
    else None
  for (i <- 0 until peCount) {
    axis_stream_converters_out(i).io.dataIn.lite <> stealNW_TQ.io.connPE(i).pop
    io_export.taskOut(i).lite <> axis_stream_converters_out(i).io.dataOut.lite
    if (spawnsItself) {
      axis_stream_converters_in.get(i).io.dataIn.lite <> io_export.taskIn
        .get(i)
        .lite
      stealNW_TQ.io
        .connPE(i)
        .push <> axis_stream_converters_in.get(i).io.dataOut.lite
    } else {
      stealNW_TQ.io.connPE(i).push.valid := false.B
      stealNW_TQ.io.connPE(i).push.bits := DontCare
    }

    // Write these values to all scheduler servers (avoids sink not connected errors)
    for (j <- 0 until schedulerServersNumber) {
      schedulerServers(j).io.lengths_of_hardware_queues(i) := stealNW_TQ.io
        .lengths_of_hardware_queues(i)
    }
  }

  // DEBUG
  if (debug) {
    if (spawnsItself) {
      val spawnTaskCounter = Module(new Counter64(peCount));
      for (i <- 0 until peCount) {
        spawnTaskCounter.io.signals(i) := (io_export.taskIn.get(i).lite.fire)
        when(io_export.taskIn.get(i).lite.fire) {
          logTask("TaskIn", i, io_export.taskIn.get(i).lite.bits.asUInt)
        }
      }
    }

    val getExecuteTaskCounter = Module(new Counter64(peCount));
    for (i <- 0 until peCount) {
      getExecuteTaskCounter.io.signals(i) := (io_export.taskOut(i).lite.fire)
      when(io_export.taskOut(i).lite.fire) {
        logTask("TaskOut", i, io_export.taskOut(i).lite.bits.asUInt)
      }
    }

    dontTouch(getExecuteTaskCounter.io.counter)
  }
  // DEBUG

  if (argRouteServersNumber > 0 && outsideSpawn) { // && argRouteServersCreateTasks) { //
    for (i <- 0 until argRouteServersNumber) {
      connectOutsideSpawnSource(
        peCountGlobalTaskIn + i,
        connArgumentNotifier(i)
      )
    }
  } else {
    for (i <- 0 until argRouteServersNumber) {
      connArgumentNotifier(i).ctrl.serveStealReq.ready := 0.U
      connArgumentNotifier(i).ctrl.stealReq.ready := 0.U
      connArgumentNotifier(i).data.availableTask.valid := 0.U
      connArgumentNotifier(i).data.availableTask.bits := 0.U
      connArgumentNotifier(i).data.qOutTask.ready := 0.U
    }
  }

  if (peCountGlobalTaskIn > 0) {
    val axis_stream_converters_in_global = Seq.fill(peCountGlobalTaskIn)(
      Module(new AxisDataWidthConverter(pePortWidth, taskWidth))
    )
    val globalsTaskBuffers = Seq.fill(peCountGlobalTaskIn)(
      Module(new GlobalTaskBuffer(taskWidth, peCount))
    )
    for (i <- 0 until peCountGlobalTaskIn) {

      axis_stream_converters_in_global(
        i
      ).io.dataIn.asLite <> io_export.taskInGlobal
        .get(i)
        .asLite
      globalsTaskBuffers(
        i
      ).io.in <> axis_stream_converters_in_global(
        i
      ).io.dataOut.asLite

      connectOutsideSpawnSource(
        i,
        globalsTaskBuffers(i).io.connStealNtw
      )
    }
  }

  // ---- Outside-spawn ring admission policy -----------------------------------
  // Must come after every connectOutsideSpawnSource call, since it reads the
  // valid each source drives onto its slot.
  //
  // The two classes on this ring are NEW work (spawns arriving from another
  // task) and CONTINUATIONS (the argument networks' fast and slow lanes, work
  // already in flight that retires when it lands). New work only adds; a
  // continuation is what frees a closure, a cache line and a scheduler entry.
  // So a continuation must never be made to wait behind new work.
  //
  // The rigid ring got this exactly backwards, by position rather than by
  // policy: holes travel with the data, so the node just behind a consumer sees
  // them first and the node just after a busy injector never does. Measured on
  // fullTriangleCountDecoupled/com-orkut, the fast lane sitting one hop
  // downstream of triangle's incoming-spawn buffer moved 2 tasks and was then
  // stuck valid/!ready from cycle 8339 to the end of the run, while the lane one
  // hop behind a spawner kept going.
  if (outsideSpawn) {
    val ntw = getOutsideSpawnNetwork.get
    val spawnerSlots = spawnerIndices.toSet
    val sourceSlots = sourceIndices.toSet
    // A paired source is wired into its BufferServerInput rather than onto the
    // ring (and already wins there, via bufferHasTask), so those slots count as
    // spawner nodes here and not as lanes.
    val contLaneSlots =
      (peCountGlobalTaskIn until outsideSpawnSourceCount)
        .map(sourceIndices(_))
        .toSet -- spawnerSlots

    for (slot <- 0 until outsideSpawnNetworkSize) {
      // "My producer has something for the ring." This reaches the hop UPSTREAM
      // and stops it refilling the slot we are about to vacate, which is how a
      // node makes its own hole instead of waiting for one. Only real sources
      // ask: a spawner slot is a consumer whose peer hand-off is opportunistic,
      // and one that asked continuously would pin whichever lane sits
      // immediately upstream of it -- the starvation this change removes.
      // Reading qOutTask.valid is safe here because no SchedulerInjectionSwap
      // sits on this ring, so nothing derives valid from ready.
      ntw.io.injectWanted.get(slot) :=
        (if (spawnerSlots.contains(slot)) false.B
         else if (sourceSlots.contains(slot))
           ntw.io.connSS(slot).data.qOutTask.valid
         else false.B)

      // "Forward past the downstream hop's want anyway." Set on every
      // continuation lane and on nothing else, so the override runs one way
      // only: a lane can push past new work, new work can never push past a
      // lane. Deliberately blanket rather than only where it is load-bearing
      // today -- elastic redistributes priority instead of preserving ring
      // order, so which lane needs it moves with the layout, and a missing force
      // is a permanent starvation where a redundant one costs nothing between
      // two sparse producers. Only the SOFT want is overridden; stopInFull stays
      // unconditional, so this can reorder who gets a slot but never drop a task.
      ntw.io.forceForward.get(slot) := contLaneSlots.contains(slot).B
    }
  }

  buildMfpgaConnections()
}

// Create an emmitter for the Scheduler module that includes the mfPGA connections enabled. Write the sys verilog files in output/mfPGA-scheduler/
object SchedulerMfpgaEmitter extends App {
  import _root_.circt.stage.ChiselStage

  ChiselStage.emitSystemVerilogFile(
    new Scheduler(
      addrWidth = 64,
      taskWidth = 256,
      queueDepth = 64,
      peCount = 4,
      schedulerServersNumber = 2,
      spawnsItself = true,
      peCountGlobalTaskIn = 2,
      argRouteServersNumber = 2,
      pePortWidth = 256,
      peType = "fib",
      debug = false,
      mfpgaSupport = true,
      maxNumnberToStealOrServe = 256
    ),
    Array(
      "--target-dir=output/mfPGA-scheduler/"
    ),
    Array("--disable-all-randomization")
  )
}
