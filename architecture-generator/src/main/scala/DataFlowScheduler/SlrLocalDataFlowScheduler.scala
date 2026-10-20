package DataFlowScheduler

import chisel3._
import chisel3.util._

import chext.elastic
import chext.elastic.ConnectOp._

import chext.amba.axi4
import chext.amba.axi4s
import axi4s.Casts._
import axi4.Ops._

import AXIHelpers.AxisDataWidthConverter
import Scheduler.{HardCilkSchedulerLike, SchedulerPEIO}
import Util.{NocPePolicy, SchedulerNetworkClientIO, SlrLocal, SlrSkid}

/**
  * The part of an SLR-local scheduler that lives on one die: a round-robin
  * distribution tree (at most `fanout` ways per node) feeding a closed ring of
  * [[DataFlowTaskUnit]]s, one per PE of that die. Same unit, same ring and same
  * per-unit policy as [[DataFlowNetwork]]; the ring just never leaves the die.
  */
class SlrLocalSchedulerBlock(taskWidth: Int, nPes: Int, fanout: Int, inputBias: Int, overflow: Boolean)
    extends Module {
  override def desiredName: String = "hcSlrLocalScheduler"

  val io = IO(new Bundle {
    val in = Flipped(Decoupled(UInt(taskWidth.W)))
    val out = Vec(nPes, Decoupled(UInt(taskWidth.W)))
    /** With a spawner server: tokens the ring could not keep (to the hub's spawner). */
    val overflow = if (SlrLocalSchedulerBlock.this.overflow) Some(Decoupled(UInt(taskWidth.W))) else None
  })

  private val rr = SlrLocal.relayedReset(reset)
  withReset(rr) {
    val network = Module(
      new DataFlowNetwork(
        DataFlowNetworkConfig(nUnits = nPes, wData = taskWidth, inputBias = inputBias, openRing = overflow)
      )
    )
    network.suggestName("ring")
    // The last skid stage of the incoming link is the arrival register.
    SlrLocal.distTree(UInt(taskWidth.W), io.in, network.io.s_primary.toSeq, fanout, "dist")
    network.io.m_primary.zip(io.out).foreach { case (s, o) => s :=> o }
    if (overflow) {
      // As the flat scheduler's spill path: keep the token in the ring while the
      // wrap hop can take it, divert it (here: to the hub's spawner) only once that
      // path is full.
      val spill = Module(new elastic.Distributor(UInt(taskWidth.W), 2, elastic.Chooser.priority(_)))
      spill.suggestName("spillSplit")
      network.io.m_spill.get :=> spill.io.source
      elastic.Disposed(spill.io.select)
      spill.io.sinks(0) :=> network.io.s_refill.get
      spill.io.sinks(1) :=> io.overflow.get
    }
  }
}

/**
  * HC_SCHED_SLR_LOCAL: a [[DataFlowScheduler]] split per die.
  *
  * {{{
  *   task inputs --(RR merge)--> top distributor (one way per die, on the hub die)
  *       way d --> registered skid chain, one stage per die/boundary (hcslr_tlink<d>_<role>)
  *             --> hcslr_sched_<die>: distribution tree + ring of that die's PEs
  *             --> taskOut of exactly those PEs
  * }}}
  *
  * Only the few die links cross an SLR boundary (one task-wide register path per
  * far die and boundary), instead of one per far PE; the wide fan-out is local.
  * Every task still goes to exactly one PE of this task: delivery semantics are
  * those of the flat scheduler, only the choice of PE differs.
  *
  * Same ports as [[DataFlowScheduler]], so the builder wires it the same way.
  * Only built for `nTaskIn < peCount` (the builder falls back to the flat
  * scheduler otherwise). With a spawner server (task-cycle breaker) each die's
  * ring is opened like the flat one: a token the wrap hop cannot take goes back
  * to the hub (`hcslr_olink<d>_<role>`) and into the spawner, whose output
  * re-enters through the top distributor.
  *
  * @param peSlr die of every PE index (`SLR2` = hub, see [[NocPePolicy]])
  */
class SlrLocalDataFlowScheduler(
    val taskWidth: Int,
    val peCount: Int,
    val peCountGlobalTaskIn: Int,
    val argRouteServersNumber: Int,
    val pePortWidth: Int,
    val argRouteServersCreateTasks: Boolean,
    val peType: String,
    val peSlr: Seq[String],
    val hasSpawnerServer: Boolean = false,
    val fanout: Int = 8,
    val inputBias: Int = 4
) extends Module with HardCilkSchedulerLike {
  require(peSlr.length == peCount)

  override def desiredName: String =
    if (peType.isEmpty) "dataFlowScheduler" else s"dataFlowScheduler_${peType}"

  val nArgRouteTaskIn = if (argRouteServersCreateTasks) argRouteServersNumber else 0
  val nTaskIn = peCountGlobalTaskIn + nArgRouteTaskIn
  require(nTaskIn > 0 && nTaskIn < peCount)

  val io_export = IO(
    new SchedulerPEIO(
      pePortWidth = pePortWidth,
      peCount = peCount,
      spawnsItself = false,
      peCountGlobalTaskIn = peCountGlobalTaskIn
    )
  )
  val connArgumentNotifier = IO(Vec(argRouteServersNumber, new SchedulerNetworkClientIO(taskWidth)))

  private val gen = UInt(taskWidth.W)

  private def fromArgumentNotifier(port: SchedulerNetworkClientIO): DecoupledIO[UInt] = {
    val result = Wire(Decoupled(gen))
    result.valid := port.data.qOutTask.valid
    result.bits := port.data.qOutTask.bits
    port.data.qOutTask.ready := result.ready
    port.ctrl.stealReq.ready := true.B
    port.ctrl.serveStealReq.ready := false.B
    port.data.availableTask.valid := false.B
    port.data.availableTask.bits := 0.U
    result
  }

  private def fromAxis(port: axi4s.Interface): DecoupledIO[UInt] = {
    val source = port.asLite
    val result = Wire(Decoupled(gen))
    result.valid := source.valid
    result.bits := source.bits.asUInt
    source.ready := result.ready
    result
  }

  private val sources: Seq[DecoupledIO[UInt]] =
    (if (argRouteServersCreateTasks) connArgumentNotifier.toSeq.map(fromArgumentNotifier) else Seq.empty) ++
      io_export.taskInGlobal.map(_.toSeq).getOrElse(Seq.empty).zipWithIndex.map { case (port, i) =>
        val converter = Module(new AxisDataWidthConverter(pePortWidth, taskWidth))
        converter.suggestName(s"taskInGlobalConverter_$i")
        converter.io.dataIn.asLite <> port.asLite
        fromAxis(converter.io.dataOut)
      }

  if (!argRouteServersCreateTasks) {
    connArgumentNotifier.foreach { port =>
      port.ctrl.serveStealReq.ready := false.B
      port.ctrl.stealReq.ready := false.B
      port.data.availableTask.valid := false.B
      port.data.availableTask.bits := 0.U
      port.data.qOutTask.ready := false.B
    }
  }

  // ---- Spawner server (only when the task-cycle analysis asks for one) -------
  // The per-die rings overflow into it (see SlrLocalSchedulerBlock), and what it
  // gives back re-enters through the top distributor, like a new task.
  private val spawner =
    if (hasSpawnerServer) Some(Module(new Scheduler.SpawnerServer(taskWidth))) else None
  val spawnerServerMgmt = spawner.map(s => IO(axi4.lite.Slave(s.regBlock.cfgAxi)))
  val spawnerServerAXI = spawner.map(s => IO(axi4.full.Master(s.axiCfg)))
  private val adapter = spawner.map { server =>
    server.suggestName("spawnerServer")
    server.io.axi_mgmt <> spawnerServerMgmt.get
    server.io.m_axi.asFull :=> spawnerServerAXI.get
    val a = Module(new SpawnerStreamAdapter(taskWidth))
    a.suggestName("spawnerAdapter")
    a.io.toSlave <> server.io.connNetwork_slave
    a.io.toMaster <> server.io.connNetwork_master
    a
  }
  private val allSources = sources ++ adapter.map(_.io.deq).toSeq

  // All inputs into one stream (a no-op for the usual single spawner input).
  private val merged: DecoupledIO[UInt] =
    if (allSources.length == 1) allSources.head
    else {
      val arb = Module(new elastic.BasicArbiter(gen, allSources.length, elastic.RoundRobinChooser()))
      arb.suggestName("taskInMerge")
      allSources.zipWithIndex.foreach { case (s, k) => s :=> arb.io.sources(k) }
      elastic.Disposed(arb.io.select)
      val w = Wire(Decoupled(gen))
      arb.io.sink :=> w
      w
    }

  // Dies in hub-first order, each with the (sorted) PE indices it serves.
  private val dieOrder = Seq(NocPePolicy.hubSlr, "SLR1", "SLR0")
  val groups: Seq[(String, Seq[Int])] =
    dieOrder.map(d => d -> peSlr.indices.filter(peSlr(_) == d)).filter(_._2.nonEmpty)
  require(groups.map(_._2.length).sum == peCount, s"peSlr names a die outside ${dieOrder.mkString("/")}")

  println(
    s"[SlrLocalDataFlowScheduler] $peType: inputs=$nTaskIn, " +
      groups.map { case (d, l) => s"$d ${l.length} PE(s) [${l.head}..${l.last}]" }.mkString(", ") +
      s", local fan-out <= $fanout"
  )

  private val links: Seq[DecoupledIO[UInt]] =
    if (groups.length == 1) Seq(merged)
    else {
      val top = Module(new elastic.Distributor(gen, groups.length, elastic.RoundRobinChooser()))
      top.suggestName("topDistributor")
      merged :=> top.io.source
      elastic.Disposed(top.io.select)
      groups.indices.map { i =>
        val w = Wire(Decoupled(gen))
        top.io.sinks(i) :=> w
        w
      }
    }

  private val taskOutStreams = Array.fill[DecoupledIO[UInt]](peCount)(null)
  private val overflows = scala.collection.mutable.ArrayBuffer[DecoupledIO[UInt]]()
  groups.zip(links).zipWithIndex.foreach { case (((die, pes), link), i) =>
    val roles =
      if (die == NocPePolicy.hubSlr) Seq(NocPePolicy.hubSlr.toLowerCase)
      else NocPePolicy.skidRoles(die, peIsSource = false)
    val resets = SlrLocal.resetChain(reset, s"tlink$i", roles)
    val arrived = SlrSkid.chain(link, s"tlink$i", roles, resets)
    val block = withReset(resets(roles.last))(
      Module(new SlrLocalSchedulerBlock(taskWidth, pes.length, fanout, inputBias, hasSpawnerServer)))
    block.suggestName(s"${SlrLocal.prefix}sched_${die.toLowerCase}")
    block.io.in <> arrived
    block.io.overflow.foreach { o =>
      val back =
        if (die == NocPePolicy.hubSlr) Seq(NocPePolicy.hubSlr.toLowerCase)
        else NocPePolicy.skidRoles(die, peIsSource = true)
      overflows += SlrSkid.chain(o, s"olink$i", back, resets)
    }
    pes.zipWithIndex.foreach { case (pe, k) => taskOutStreams(pe) = block.io.out(k) }
  }

  taskOutStreams.zipWithIndex.foreach { case (source, i) =>
    val converter = Module(new AxisDataWidthConverter(taskWidth, pePortWidth))
    converter.suggestName(s"taskOutConverter_$i")
    val sink = converter.io.dataIn.asLite
    sink.valid := source.valid
    sink.bits := source.bits
    source.ready := sink.ready
    io_export.taskOut(i).asLite <> converter.io.dataOut.asLite
  }

  adapter.foreach { a =>
    if (overflows.length == 1) overflows.head :=> a.io.enq
    else {
      val ov = Module(new elastic.BasicArbiter(gen, overflows.length, elastic.RoundRobinChooser()))
      ov.suggestName("overflowMerge")
      overflows.zipWithIndex.foreach { case (o, k) => o :=> ov.io.sources(k) }
      elastic.Disposed(ov.io.select)
      ov.io.sink :=> a.io.enq
    }
  }

  val io_paused = IO(Output(Bool()))
  io_paused := spawner.map(_.io.paused).getOrElse(false.B)

  override def vssAxiMgmt: Seq[axi4.lite.Interface] = Seq.empty
  override def vssAxiFull: Seq[axi4.full.Interface] = Seq.empty
  override def spawnerAxiMgmt: Seq[axi4.lite.Interface] = spawnerServerMgmt.toSeq
  override def spawnerAxiFull: Seq[axi4.full.Interface] = spawnerServerAXI.toSeq
}
