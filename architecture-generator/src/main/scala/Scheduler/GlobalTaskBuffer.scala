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
  // No need for buffer. We always try to push the task, and keep track of how many credits we need to consume later
  io.connStealNtw.data.qOutTask.valid := io.in.valid
  io.connStealNtw.data.qOutTask.bits := io.in.bits
  io.in.ready := io.connStealNtw.data.qOutTask.ready
  io.connStealNtw.data.availableTask.ready := false.B
  io.connStealNtw.ctrl.stealReq.valid := false.B

  // TODO: This should be sized according to the ring size. If this ring is >16, this might not be sufficient and can overflow!
  val servedRequestCount = RegInit(0.U(32.W))
  val pushedTask = io.in.valid && io.in.ready

  io.connStealNtw.ctrl.serveStealReq.valid := pushedTask || servedRequestCount > 0.U
  val servedRequest =
    io.connStealNtw.ctrl.serveStealReq.valid && io.connStealNtw.ctrl.serveStealReq.ready

  when(servedRequest && !pushedTask) {
    servedRequestCount := servedRequestCount - 1.U
  }.elsewhen(pushedTask && !servedRequest) {
    servedRequestCount := servedRequestCount + 1.U
  }

}
