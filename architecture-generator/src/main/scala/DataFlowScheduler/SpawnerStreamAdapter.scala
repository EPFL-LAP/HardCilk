package DataFlowScheduler

import chisel3._
import chisel3.util._

import Util.SchedulerNetworkClientIO

/** Presents a [[Scheduler.SpawnerServer]] as a plain elastic FIFO.
  *
  * The spawner server was written for the classic scheduler, where it hangs off two
  * [[SchedulerNetworkClientIO]] ports: it absorbs tasks from the outside-spawn network on one side
  * and pushes them back into the local steal network on the other. The DataFlowScheduler has
  * neither network, so this adapter plays the part of both, turning the spawner into what it
  * really is underneath: a queue that spills to DRAM.
  *
  * Both `SchedulerNetworkClientIO`s here are unflipped, i.e. the network's view, because
  * `SpawnerServer` declares its two ports `Flipped`.
  *
  * @param taskWidth
  *   Width of a task closure.
  * @param outDepth
  *   Depth of the buffer that catches tasks coming back from DRAM.
  */
class SpawnerStreamAdapterIO(taskWidth: Int) extends Bundle {

  /** Tasks going into the spawner, i.e. spilling out of the ring. */
  val enq = Flipped(Decoupled(UInt(taskWidth.W)))

  /** Tasks coming back out of the spawner, to be re-injected into the ring. */
  val deq = Decoupled(UInt(taskWidth.W))

  /** Connect to `SpawnerServer.io.connNetwork_slave`. */
  val toSlave = new SchedulerNetworkClientIO(taskWidth)

  /** Connect to `SpawnerServer.io.connNetwork_master`. */
  val toMaster = new SchedulerNetworkClientIO(taskWidth)
}

class SpawnerStreamAdapter(taskWidth: Int, outDepth: Int = 4) extends Module {
  override def desiredName: String = "spawnerStreamAdapter"

  val io = IO(new SpawnerStreamAdapterIO(taskWidth))

  // ---- into the spawner ----------------------------------------------------
  // SpawnerServer consumes availableTask, holds serveStealReq.valid high forever to say it will
  // always absorb, and never drives qOutTask or stealReq on this side.
  io.toSlave.data.availableTask.valid := io.enq.valid
  io.toSlave.data.availableTask.bits := io.enq.bits
  io.enq.ready := io.toSlave.data.availableTask.ready

  io.toSlave.data.qOutTask.ready := true.B
  io.toSlave.ctrl.serveStealReq.ready := true.B
  io.toSlave.ctrl.stealReq.ready := true.B

  // ---- out of the spawner --------------------------------------------------
  // On this side the spawner drives WriteTaskToNetwork, which earns one credit per task by
  // handshaking serveStealReq before forwarding that task on qOutTask. Granting the credit
  // unconditionally is safe because re-injection is self-throttling: the spawner only reads DRAM
  // when its read queue has drained, and that queue drains solely into the buffer below.
  io.toMaster.ctrl.serveStealReq.ready := true.B
  io.toMaster.ctrl.stealReq.ready := false.B

  io.toMaster.data.availableTask.valid := false.B
  io.toMaster.data.availableTask.bits := DontCare

  private val outBuffer = Module(new Queue(UInt(taskWidth.W), outDepth))

  outBuffer.io.enq.valid := io.toMaster.data.qOutTask.valid
  outBuffer.io.enq.bits := io.toMaster.data.qOutTask.bits
  io.toMaster.data.qOutTask.ready := outBuffer.io.enq.ready

  io.deq <> outBuffer.io.deq
}
