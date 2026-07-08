package Scheduler

import chisel3._
import chisel3.util._
import Util._

import chext.amba.axi4s
import chext.amba.axi4
import chext.elastic
import axi4s.Casts._
import axi4.lite.components.RegisterBlock
import axi4.Ops._

class SpawnerServerIO(taskWidth: Int) extends Bundle {

  val connNetwork_slave = Flipped(
    new SchedulerNetworkClientIO(taskWidth)
  ) // Connection to the stealing Network
  val connNetwork_master = Flipped(
    new SchedulerNetworkClientIO(taskWidth)
  ) // Connection to the stealing Network
}

class SpawnerServer(
    taskWidth: Int,
    queueDepth: Int = 16
) extends Module {

  val io = IO(new SpawnerServerIO(taskWidth))

  // Create the local tasks queue to absorb bumps in the ring. We ALWAYS try to drain this if there is space
  val taskQueue = Module(
    new Queue(UInt(taskWidth.W), queueDepth)
  )

  // Try to drain the taskQueue
  val servedRequestCount = RegInit(0.U(32.W))
  io.connNetwork_master.data.qOutTask.valid := taskQueue.io.deq.valid
  io.connNetwork_master.data.qOutTask.bits := taskQueue.io.deq.bits
  taskQueue.io.deq.ready := io.connNetwork_master.data.qOutTask.ready
  io.connNetwork_master.data.availableTask.ready := false.B
  io.connNetwork_master.ctrl.stealReq.valid := false.B
  val pushedTask = taskQueue.io.deq.valid && taskQueue.io.deq.ready
  io.connNetwork_master.ctrl.serveStealReq.valid := pushedTask || servedRequestCount > 0.U
  val servedRequest =
    io.connNetwork_master.ctrl.serveStealReq.valid && io.connNetwork_master.ctrl.serveStealReq.ready
  when(servedRequest && !pushedTask) {
    servedRequestCount := servedRequestCount - 1.U
  }.elsewhen(pushedTask && !servedRequest) {
    servedRequestCount := servedRequestCount + 1.U
  }

  // Try to fill the taskQueue
  val acceptedRequestCount = RegInit(0.U(32.W))
  taskQueue.io.enq.valid := io.connNetwork_slave.data.availableTask.valid
  taskQueue.io.enq.bits := io.connNetwork_slave.data.availableTask.bits
  io.connNetwork_slave.data.availableTask.ready := taskQueue.io.enq.ready
  io.connNetwork_slave.data.qOutTask.valid := false.B
  io.connNetwork_slave.data.qOutTask.bits := 0.U
  io.connNetwork_slave.ctrl.serveStealReq.valid := false.B
  val pulledTask = taskQueue.io.enq.valid && taskQueue.io.enq.ready
  io.connNetwork_slave.ctrl.stealReq.valid := pulledTask || acceptedRequestCount > 0.U
  val acceptedRequest =
    io.connNetwork_slave.ctrl.stealReq.valid && io.connNetwork_slave.ctrl.stealReq.ready
  when(acceptedRequest && !pulledTask) {
    acceptedRequestCount := acceptedRequestCount - 1.U
  }.elsewhen(pulledTask && !acceptedRequest) {
    acceptedRequestCount := acceptedRequestCount + 1.U
  }
}

object SpawnerServerEmitter extends App {
  emitVerilog(new SpawnerServer(256), Array("--target-dir", "output"))
}
