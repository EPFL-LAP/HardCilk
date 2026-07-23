package Scheduler

import chisel3._
import chisel3.util._
import Util._

class BufferServerInputIO(taskWidth: Int) extends Bundle {
  val connNetwork_slave = Flipped(new SchedulerNetworkClientIO(taskWidth))
  val connTaskSource = new SchedulerNetworkClientIO(taskWidth)
  val connSpawnerServer = new SchedulerNetworkClientIO(taskWidth)
}

class BufferServerInput(taskWidth: Int) extends Module {

  val io = IO(new BufferServerInputIO(taskWidth))

  val tinyQueueFromBuffer = Module(new Queue(UInt(taskWidth.W), 1, pipe = true))

  // Connect the buffer queue to the buffer so it thinks it looks like the network

  io.connTaskSource.data.availableTask.bits := DontCare
  io.connTaskSource.data.availableTask.valid := false.B
  tinyQueueFromBuffer.io.enq.bits := io.connTaskSource.data.qOutTask.bits
  io.connTaskSource.data.qOutTask.ready := tinyQueueFromBuffer.io.enq.ready
  tinyQueueFromBuffer.io.enq.valid := io.connTaskSource.data.qOutTask.valid

  // To make this work, we prioritize the buffer for local consumption. If there is something in the buffer, let the network pass right through and present the buffer to the server
  // We cannot prioritize the network for local consumption, because the network must always flow, so the network will not accept the buffer task at the same time it presents a task to the PE

  val bufferHasTask = tinyQueueFromBuffer.io.deq.valid

  // qOutTask is always a straight pass-through: the spawner is the only injector at this node (its
  // canServePeer hand-off), and it targets the ring slot directly regardless of the buffer state.
  io.connNetwork_slave.data.qOutTask.valid := io.connSpawnerServer.data.qOutTask.valid
  io.connNetwork_slave.data.qOutTask.bits := io.connSpawnerServer.data.qOutTask.bits
  io.connSpawnerServer.data.qOutTask.ready := io.connNetwork_slave.data.qOutTask.ready

  // availableTask routing depends on whether the co-located buffer has a task to present. Defaults;
  // the selected branch below overrides the handshakes it uses.
  io.connSpawnerServer.data.availableTask.valid := false.B
  io.connSpawnerServer.data.availableTask.bits := 0.U
  io.connNetwork_slave.data.availableTask.ready := false.B
  tinyQueueFromBuffer.io.deq.ready := false.B

  when(bufferHasTask) {
    // Present the buffered (co-located source) task to the spawner, and decline the incoming ring
    // task so it keeps circulating to a node that wants it. The spawner accepts the buffer task
    // whenever it has room (swallow-and-share); until then it simply waits in the buffer.
    io.connSpawnerServer.data.availableTask.valid := tinyQueueFromBuffer.io.deq.valid
    io.connSpawnerServer.data.availableTask.bits := tinyQueueFromBuffer.io.deq.bits
    tinyQueueFromBuffer.io.deq.ready := io.connSpawnerServer.data.availableTask.ready
  }.otherwise {
    // No buffered task, so plug the ring straight into the spawner.
    io.connSpawnerServer.data.availableTask.valid :=
      io.connNetwork_slave.data.availableTask.valid
    io.connSpawnerServer.data.availableTask.bits :=
      io.connNetwork_slave.data.availableTask.bits
    io.connNetwork_slave.data.availableTask.ready :=
      io.connSpawnerServer.data.availableTask.ready
  }

  /*
  How to handle the steal requests?
    Incoming: only the spawner may answer one. It is the only participant here that can inject a
      task into the ring, so it is the only one whose "serve" actually reaches the requester.
      Answering on behalf of the task source destroys the requester's demand for nothing: the
      source's task goes into tinyQueueFromBuffer and on to the co-located spawner, never onto the
      ring. (The source's ctrl channel is bookkeeping only -- GlobalTaskBuffer drives qOutTask
      straight off its queue and never gates data on that handshake.)
    Outgoing: Only comes from the spawner so can just connect
   */
  val incomingRingRequest = io.connNetwork_slave.ctrl.serveStealReq.ready
  val taskSourceCanServe = io.connTaskSource.ctrl.serveStealReq.valid
  val spawnerWantsTask = io.connSpawnerServer.ctrl.stealReq.valid

  // The co-located source exists to feed this spawner, so hand its task over whenever the spawner
  // is asking. Ring traffic is irrelevant to that decision.
  io.connTaskSource.ctrl.serveStealReq.ready := spawnerWantsTask
  io.connSpawnerServer.ctrl.serveStealReq.ready := incomingRingRequest
  // The spawner asserts this only when the task actually entered the ring, so a steal request is
  // never consumed without a matching delivery -- including when the ring is blocked.
  io.connNetwork_slave.ctrl.serveStealReq.valid :=
    io.connSpawnerServer.ctrl.serveStealReq.valid

  val locallyServedRequest = spawnerWantsTask && taskSourceCanServe
  io.connNetwork_slave.ctrl.stealReq.valid :=
    spawnerWantsTask && !locallyServedRequest
  io.connSpawnerServer.ctrl.stealReq.ready :=
    locallyServedRequest || io.connNetwork_slave.ctrl.stealReq.ready

  // Outside task sources never issue steal requests of their own.
  io.connTaskSource.ctrl.stealReq.ready := false.B

}
