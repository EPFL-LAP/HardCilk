package Scheduler

import chisel3._
import Util._

// The interface connection for the stealing network data unit.
class SchedulerNetworkDataUnitIO(taskWidth: Int) extends Bundle {
  // Connections to other network data units (Shift register and not elastic)
  val taskIn = Input(UInt(taskWidth.W))
  val taskOut = Output(UInt(taskWidth.W))

  val validIn = Input(Bool())
  val validOut = Output(Bool())

  // Connections to steal server
  val connSS = new SchedulerNetworkClientData(taskWidth)

  val occupied = Output(Bool()) // Indicates that the data unit is occupied to the vss
}

class SchedulerNetworkDataUnit(taskWidth: Int) extends Module {
  val io = IO(new SchedulerNetworkDataUnitIO(taskWidth))

  // If the taskIn is valid using validIn and io.connSS.availableTask.ready, then the task
  // is put to io.connSS.availableTask.bits and the io.connSS.availableTask.valid is set to true.
  // while validOut is set to false.
  // If the taskIn is not valid, then io.connSS.qOutTask.bits is zero and io.connSS.availableTask.valid and
  // the validOut bit are set to false. In case connSS.qOutTask.valid is true, then the taskOut is set to
  // connSS.qOutTask.bits and validOut is set to true.

  val taskReg = RegInit(0.U(taskWidth.W))
  val validReg = RegInit(false.B)

  // Create the needed registers to carry the task and the valid bit.
  //   val taskReg          = RegInit(0.U(taskWidth.W))
  //   val validReg         = RegInit(false.B)
  //   val qOutTaskReadyReg = RegInit(false.B) // changed to true if the task insertion in the network succeeded in the previous cycle.

  // Create the propagations conditions
  io.connSS.qOutTask.ready := false.B
  io.connSS.availableTask.bits := 0.U
  io.connSS.availableTask.valid := false.B

  when(io.connSS.availableTask.ready && io.validIn) {
    validReg := false.B
    taskReg := 0.U
    io.connSS.availableTask.valid := true.B
    io.connSS.availableTask.bits := io.taskIn
  }.elsewhen(io.connSS.qOutTask.valid && ~io.validIn) {
    validReg := true.B
    taskReg := io.connSS.qOutTask.bits
    io.connSS.qOutTask.ready := true.B
  }.elsewhen(io.validIn) {
    validReg := io.validIn
    taskReg := io.taskIn
  }.otherwise {
    validReg := false.B
    taskReg := 0.U
  }

  // Shift the registers of the network.
  io.taskOut := taskReg
  io.validOut := validReg
  io.occupied := validReg
}
