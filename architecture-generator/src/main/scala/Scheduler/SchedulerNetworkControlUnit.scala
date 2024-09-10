package Scheduler

import chisel3._
import Util._

// The interface connection for the stealing network ctrl unit.
class SchedulerNetworkControlUnitIO extends Bundle {
  // Connections to other network ctrl units
  val reqTaskIn = Input((Bool()))
  val reqTaskOut = Output(Bool())

  // Connections to steal server
  val connSS = new SchedulerNetworkClientRequest
}

class SchedulerNetworkControlUnit extends Module {
  val io = IO(new SchedulerNetworkControlUnitIO)

  val stealReqReg = RegInit(false.B)

  when(io.connSS.stealReq.valid) {
    io.reqTaskOut := 1.U
  }.elsewhen(io.connSS.serveStealReq.valid) {
    io.reqTaskOut := 0.U
  }.otherwise {
    io.reqTaskOut := stealReqReg
  }

  io.connSS.stealReq.ready := ~stealReqReg
  io.connSS.serveStealReq.ready := stealReqReg
  stealReqReg := io.reqTaskIn
}
