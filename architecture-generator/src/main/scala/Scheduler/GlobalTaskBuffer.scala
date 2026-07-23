package Scheduler

import chisel3._
import chisel3.util._
import Util._

class GlobalTaskBufferIO(taskWidth: Int) extends Bundle {
  val in = Flipped(DecoupledIO(UInt(taskWidth.W)))
  val connStealNtw = Flipped(new SchedulerNetworkClientIO(taskWidth))
}

class GlobalTaskBuffer(taskWidth: Int, peCount: Int) extends Module {

  val io = IO(new GlobalTaskBufferIO(taskWidth))

  // Decouple request consumption from availability of an empty data-ring slot.
  // A buffered task may consume a parked steal request before that task can be
  // inserted into the ring. `pipe` preserves one-task-per-cycle throughput when
  // a full queue dequeues and enqueues in the same cycle.
  val taskQueue = Module(new Queue(UInt(taskWidth.W), 2, pipe = true))
  taskQueue.io.enq.valid := io.in.valid
  taskQueue.io.enq.bits := io.in.bits
  io.in.ready := taskQueue.io.enq.ready

  io.connStealNtw.data.qOutTask.valid := taskQueue.io.deq.valid
  io.connStealNtw.data.qOutTask.bits := taskQueue.io.deq.bits
  taskQueue.io.deq.ready := io.connStealNtw.data.qOutTask.ready
  io.connStealNtw.data.availableTask.ready := false.B
  io.connStealNtw.ctrl.stealReq.valid := false.B

  // requestBalance = consumed steal requests - tasks pushed into the ring.
  // A positive balance is backed by that many queued tasks. A negative balance
  // is the old servedRequestCount case: tasks were pushed before their matching
  // steal requests arrived. The conservative eligibility test intentionally
  // does not count a task accepted into the queue in the current cycle.
  val requestBalance = RegInit(0.S(33.W))
  val pushedTask = taskQueue.io.deq.valid && taskQueue.io.deq.ready
  val queuedTaskCount = taskQueue.io.count.zext

  io.connStealNtw.ctrl.serveStealReq.valid := requestBalance < queuedTaskCount
  val servedRequest =
    io.connStealNtw.ctrl.serveStealReq.valid && io.connStealNtw.ctrl.serveStealReq.ready

  when(servedRequest =/= pushedTask) {
    requestBalance := requestBalance + servedRequest.asUInt.zext - pushedTask.asUInt.zext
  }

  // Every early-consumed request must remain backed by a real, unpushed task.
  assert(requestBalance <= queuedTaskCount)
}
