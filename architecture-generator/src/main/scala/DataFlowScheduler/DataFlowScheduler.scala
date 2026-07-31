package DataFlowScheduler

// Not Virtualized, can cause a deadlock in a circular dependency.
// Dependency is analyzed on task generation, and a spawnerServer can be created by the
// HardCilk Builder to avoid deadlocks.

import chisel3._
import chisel3.util._

import chext.elastic
import chext.elastic.ConnectOp._

import chext.amba.axi4
import chext.amba.axi4s
import axi4s.Casts._
import axi4.Ops._

import AXIHelpers.AxisDataWidthConverter
import Scheduler.{HardCilkSchedulerLike, SchedulerPEIO, SpawnerServer}
import Util.SchedulerNetworkClientIO

/** A scheduler for tasks that never spawn themselves.
  *
  * Such a task has no work to steal back from its own PEs, so none of the machinery of
  * [[Scheduler.Scheduler]] applies: there is no task queue, no steal network and no scheduler
  * server. What is left is pure dataflow, tasks arrive from the outside and have to be spread over
  * the PEs, which is what [[DataFlowNetwork]] does.
  *
  * The scheduler has `n` task inputs and `m = peCount` task outputs:
  *
  *   - `n` is `peCountGlobalTaskIn`, plus `argRouteServersNumber` when the argument route servers
  *     create tasks of their own.
  *   - `m` is one output per PE, driven by the `m`-unit ring of the [[DataFlowNetwork]].
  *
  * The three cases are reconciled before the ring:
  *
  *   - `n == m`: input `i` feeds unit `i` directly.
  *   - `n < m`: each input fans out over its share of the units through a round-robin
  *     [[chext.elastic.Distributor]], so a single producer keeps several PEs busy.
  *   - `n > m`: each unit is fed by its share of the inputs through a round-robin
  *     [[chext.elastic.BasicArbiter]], so no producer can monopolise a PE.
  *
  * The shares are as equal as the counts allow, and round-robin makes the choice among the *ready*
  * ports only, so a stalled PE is skipped rather than blocking the producer behind it. Whatever
  * imbalance is left is absorbed by the ring itself, which spills the tokens a unit cannot place
  * onto its neighbours.
  *
  * @param taskWidth
  *   Width of a task closure, and of every task port inside the scheduler.
  * @param peCount
  *   Number of PEs, hence the number of task outputs and the size of the ring.
  * @param peCountGlobalTaskIn
  *   Number of task inputs coming from other tasks' PEs.
  * @param argRouteServersNumber
  *   Number of argument route servers of this task.
  * @param argRouteServersCreateTasks
  *   Whether those servers produce tasks. When set, they add `argRouteServersNumber` task inputs.
  * @param pePortWidth
  *   Width of the PE facing task ports. When it differs from `taskWidth`, an
  *   [[AXIHelpers.AxisDataWidthConverter]] is inserted on every `taskOut` and `taskInGlobal` port,
  *   as in [[Scheduler.Scheduler]]. Equal widths make the converter a plain wire. The argument
  *   route task inputs are internal to the accelerator and stay `taskWidth` wide.
  * @param hasSpawnerServer
  *   Give this scheduler a [[Scheduler.SpawnerServer]] on the ring's spill path. Decided by the
  *   builder's task-cycle analysis, not by the descriptor: a ring of tasks that can only push back
  *   on each other deadlocks unless one of them can spill to DRAM.
  * @param peType
  *   Task name, used to name the generated module.
  * @param inputBias
  *   Per unit arbitration bias inside the ring, see [[DataFlowTaskUnitConfig]].
  */
class DataFlowScheduler(
    val taskWidth: Int,
    val peCount: Int,
    val peCountGlobalTaskIn: Int,
    val argRouteServersNumber: Int,
    val pePortWidth: Int,
    val argRouteServersCreateTasks: Boolean = false,
    val hasSpawnerServer: Boolean = false,
    val peType: String = "",
    val inputBias: Int = 4
) extends Module with HardCilkSchedulerLike {
  require(peCount >= 1, "a scheduler needs at least one PE to feed")
  require(peCountGlobalTaskIn >= 0 && argRouteServersNumber >= 0, "port counts cannot be negative")

  override def desiredName: String =
    if (peType.isEmpty) "dataFlowScheduler" else s"dataFlowScheduler_${peType}"

  /** Task inputs contributed by the argument route servers. */
  val nArgRouteTaskIn = if (argRouteServersCreateTasks) argRouteServersNumber else 0

  /** Total number of task inputs, `n`. */
  val nTaskIn = peCountGlobalTaskIn + nArgRouteTaskIn

  /** Total number of task outputs, `m`. */
  val nTaskOut = peCount

  // The PE facing ports keep the layout of the classic scheduler, so that the HardCilk builder can
  // wire this scheduler in the same way. `spawnsItself` is false by construction here.
  val io_export = IO(
    new SchedulerPEIO(
      pePortWidth = pePortWidth,
      peCount = peCount,
      spawnsItself = false,
      peCountGlobalTaskIn = peCountGlobalTaskIn
    )
  )

  /** Tasks created by the argument route servers. Same port as the classic scheduler, so that the
    * builder can wire `ArgumentNotifier.connStealNtw` to either kind with a plain `<>`.
    */
  val connArgumentNotifier = IO(Vec(argRouteServersNumber, new SchedulerNetworkClientIO(taskWidth)))

  println(
    f"[DataFlowScheduler] taskWidth=${taskWidth}, pePortWidth=${pePortWidth}, inputs=${nTaskIn} " +
      f"(global=${peCountGlobalTaskIn}, argRoute=${nArgRouteTaskIn}), outputs=${nTaskOut}, " +
      f"spawnerServer=${hasSpawnerServer}"
  )

  private val network = Module(
    new DataFlowNetwork(
      DataFlowNetworkConfig(
        nUnits = nTaskOut,
        wData = taskWidth,
        inputBias = inputBias,
        openRing = hasSpawnerServer
      )
    )
  )
  network.suggestName("network")

  // Same ordering as the classic scheduler: the argument route servers come first. They are
  // internal, so they need no width conversion, while the PE facing inputs do.
  private val sources: Seq[DecoupledIO[UInt]] =
    (if (argRouteServersCreateTasks) connArgumentNotifier.toSeq.map(fromArgumentNotifier)
     else Seq.empty) ++
      io_export.taskInGlobal.map(_.toSeq).getOrElse(Seq.empty).zipWithIndex.map {
        case (port, i) =>
          val converter = Module(new AxisDataWidthConverter(pePortWidth, taskWidth))
          converter.suggestName(s"taskInGlobalConverter_$i")

          converter.io.dataIn.asLite <> port.asLite
          fromAxis(converter.io.dataOut)
      }

  // Argument route servers that do not create tasks still need their ports driven, exactly as the
  // classic scheduler does when it has no outside-spawn network to attach them to.
  if (!argRouteServersCreateTasks) {
    connArgumentNotifier.foreach { port =>
      port.ctrl.serveStealReq.ready := false.B
      port.ctrl.stealReq.ready := false.B
      port.data.availableTask.valid := false.B
      port.data.availableTask.bits := 0.U
      port.data.qOutTask.ready := false.B
    }
  }

  private val sinks = network.io.s_primary

  require(
    nTaskIn > 0,
    "a DataFlowScheduler with no task inputs would build PEs that can never receive a task"
  )

  if (nTaskIn == nTaskOut) {
    sources.zip(sinks).foreach { case (source, sink) => source :=> sink }
  } else if (nTaskIn < nTaskOut) {
    // Fan out: every input owns a share of the units and rotates over the ready ones.
    shares(nTaskOut, nTaskIn).zip(sources).zipWithIndex.foreach { case ((share, source), i) =>
      if (share.length == 1) {
        source :=> sinks(share.head)
      } else {
        val distributor = Module(
          new elastic.Distributor(UInt(taskWidth.W), share.length, elastic.RoundRobinChooser())
        )
        distributor.suggestName(s"taskInDistributor_$i")

        source :=> distributor.io.source
        elastic.Disposed(distributor.io.select)

        share.zipWithIndex.foreach { case (unit, k) => distributor.io.sinks(k) :=> sinks(unit) }
      }
    }
  } else {
    // Fan in: every unit is fed by a share of the inputs, served in round robin.
    shares(nTaskIn, nTaskOut).zipWithIndex.foreach { case (share, unit) =>
      if (share.length == 1) {
        sources(share.head) :=> sinks(unit)
      } else {
        val arbiter = Module(
          new elastic.BasicArbiter(UInt(taskWidth.W), share.length, elastic.RoundRobinChooser())
        )
        arbiter.suggestName(s"taskInArbiter_$unit")

        share.zipWithIndex.foreach { case (in, k) => sources(in) :=> arbiter.io.sources(k) }
        elastic.Disposed(arbiter.io.select)

        arbiter.io.sink :=> sinks(unit)
      }
    }
  }

  network.io.m_primary.zipWithIndex.foreach { case (source, i) =>
    val converter = Module(new AxisDataWidthConverter(taskWidth, pePortWidth))
    converter.suggestName(s"taskOutConverter_$i")

    toAxis(converter.io.dataIn, source)
    io_export.taskOut(i).asLite <> converter.io.dataOut.asLite
  }

  // ---- Spawner server on the ring's spill path -----------------------------
  // A token only reaches the spawner after every PE has refused it and it cannot re-enter the
  // ring, so in a balanced system this path is never taken. When a cycle of tasks backpressures
  // onto itself, it is what lets the ring drain into DRAM and keep accepting, which is what
  // breaks the deadlock.
  private val spawner =
    if (hasSpawnerServer) Some(Module(new SpawnerServer(taskWidth))) else None

  val spawnerServerMgmt = spawner.map(s => IO(axi4.lite.Slave(s.regBlock.cfgAxi)))
  val spawnerServerAXI = spawner.map(s => IO(axi4.full.Master(s.axiCfg)))

  spawner.foreach { server =>
    server.suggestName("spawnerServer")
    server.io.axi_mgmt <> spawnerServerMgmt.get
    server.io.m_axi.asFull :=> spawnerServerAXI.get

    val adapter = Module(new SpawnerStreamAdapter(taskWidth))
    adapter.suggestName("spawnerAdapter")
    adapter.io.toSlave <> server.io.connNetwork_slave
    adapter.io.toMaster <> server.io.connNetwork_master

    // Prefer keeping the token in the ring; divert to DRAM only once that path is truly full.
    val spill = Module(new elastic.Distributor(UInt(taskWidth.W), 2, elastic.Chooser.priority(_)))
    spill.suggestName("spillSplit")

    // Round robin so a full spawner cannot starve the ring's own traffic on the way back in.
    val refill = Module(new elastic.BasicArbiter(UInt(taskWidth.W), 2, elastic.RoundRobinChooser()))
    refill.suggestName("refillMerge")

    network.io.m_spill.get :=> spill.io.source
    elastic.Disposed(spill.io.select)

    spill.io.sinks(0) :=> refill.io.sources(0)
    spill.io.sinks(1) :=> adapter.io.enq
    adapter.io.deq :=> refill.io.sources(1)

    elastic.Disposed(refill.io.select)
    refill.io.sink :=> network.io.s_refill.get
  }

  // ---- HardCilkSchedulerLike ----------------------------------------------
  val io_paused = IO(Output(Bool()))
  io_paused := spawner.map(_.io.paused).getOrElse(false.B)

  override def vssAxiMgmt: Seq[axi4.lite.Interface] = Seq.empty
  override def vssAxiFull: Seq[axi4.full.Interface] = Seq.empty
  override def spawnerAxiMgmt: Seq[axi4.lite.Interface] = spawnerServerMgmt.toSeq
  override def spawnerAxiFull: Seq[axi4.full.Interface] = spawnerServerAXI.toSeq

  /** Adapts an argument route server's network port to an elastic task stream.
    *
    * `ctrl.stealReq.ready` is not optional: ArgumentServer counts every task it hands over and
    * only decrements that counter when the request is acknowledged, so leaving it low wedges the
    * server once the counter saturates.
    */
  private def fromArgumentNotifier(port: SchedulerNetworkClientIO): DecoupledIO[UInt] = {
    val result = Wire(Decoupled(UInt(taskWidth.W)))

    result.valid := port.data.qOutTask.valid
    result.bits := port.data.qOutTask.bits
    port.data.qOutTask.ready := result.ready

    port.ctrl.stealReq.ready := true.B
    port.ctrl.serveStealReq.ready := false.B
    port.data.availableTask.valid := false.B
    port.data.availableTask.bits := 0.U

    result
  }

  /** Splits `total` ports into `groups` shares of as equal a size as possible. */
  private def shares(total: Int, groups: Int): Seq[Range] = {
    require(groups > 0 && total >= groups)

    val size = total / groups
    val larger = total % groups

    Seq
      .tabulate(groups) { g => size + (if (g < larger) 1 else 0) }
      .scanLeft(0) { _ + _ }
      .sliding(2)
      .map { bounds => bounds(0) until bounds(1) }
      .toSeq
  }

  private def fromAxis(port: axi4s.Interface): DecoupledIO[UInt] = {
    val source = port.asLite
    val result = Wire(Decoupled(UInt(taskWidth.W)))

    result.valid := source.valid
    result.bits := source.bits.asUInt
    source.ready := result.ready

    result
  }

  private def toAxis(port: axi4s.Interface, source: DecoupledIO[UInt]): Unit = {
    val sink = port.asLite

    sink.valid := source.valid
    sink.bits := source.bits
    source.ready := sink.ready
  }
}
