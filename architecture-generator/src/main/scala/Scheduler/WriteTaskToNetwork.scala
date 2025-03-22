package Scheduler

import chisel3._
import chisel3.util._
import Util._
import chisel3.ChiselEnum

class WriteTaskToNetworkIO(taskWidth: Int) extends Bundle {
  val connNetwork = Flipped(new SchedulerNetworkClientIO(taskWidth))
  val s_axis_task = Flipped(DecoupledIO(UInt(taskWidth.W)))
  val fpgaId = Input(UInt(64.W))
  val startToken = Flipped(DecoupledIO(UInt(1.W)))
}

class WriteTaskToNetwork(
    taskWidth: Int,
    numTasksToStealOrServe: Int,
    peCount: Int
) extends Module {

  object state extends ChiselEnum {
    val readTask = Value(0.U)
    val writeTaskNtw = Value(1.U)
  }

  val io = IO(new WriteTaskToNetworkIO(taskWidth))

  // val stateReg = RegInit(state.readTask)
  // val tasksGivenAwayCount = RegInit(0.U(32.W))

  io.connNetwork.data.qOutTask.bits := 0.U
  io.connNetwork.data.availableTask.ready := false.B
  io.connNetwork.data.qOutTask.valid := false.B
  io.s_axis_task.ready := false.B
  io.connNetwork.ctrl.serveStealReq.valid := false.B
  io.connNetwork.ctrl.stealReq.valid := false.B

  io.startToken.ready := false.B


  val startTokenReceived = RegInit(false.B)
  when(!startTokenReceived){
    io.startToken.ready := true.B
    when(io.startToken.valid){
      startTokenReceived := true.B
    }
  }

  // to serve a task u need to read a network request first  
  val allowCount = RegInit(0.U(32.W))

  when(startTokenReceived && allowCount < numTasksToStealOrServe.U) {
    io.connNetwork.ctrl.serveStealReq.valid := true.B
    when(io.connNetwork.ctrl.serveStealReq.ready){
      allowCount := allowCount + 1.U
    }
  }

  val tasksWritten = RegInit(0.U(32.W))

  when(startTokenReceived && tasksWritten < allowCount && io.s_axis_task.valid){
    // connect the valid output of the s_axis_task to the valid input of the network
    io.s_axis_task.ready := io.connNetwork.data.qOutTask.ready
    io.connNetwork.data.qOutTask.valid := io.s_axis_task.valid
    io.connNetwork.data.qOutTask.bits := io.s_axis_task.bits
    when(io.connNetwork.data.qOutTask.fire){
      tasksWritten := tasksWritten + 1.U
    }
  }

  when(startTokenReceived && tasksWritten === numTasksToStealOrServe.U){
    allowCount := 0.U
    tasksWritten := 0.U
    startTokenReceived := false.B
  }


  // Create a counter here to keep track of the number of tasks that have been given to the network and print it 
  // to the console with fpga ID
  // val writeTaskCount = RegInit(0.U(32.W))
  // when(io.connNetwork.data.qOutTask.fire) {
  //   writeTaskCount := writeTaskCount + 1.U
  // }

  // val cyclesCounter = RegInit(0.U(32.W))
  // cyclesCounter := cyclesCounter + 1.U
  // when(cyclesCounter === 10000.U){
  //   printf("FPGA ID: %d, Tasks given to network: %d\n", io.fpgaId, writeTaskCount)
  //   cyclesCounter := 0.U
  // }

  // dontTouch(writeTaskCount)
  // dontTouch(cyclesCounter)

  // when(tasksGivenAwayCount > 0.U && (stateReg =/= state.readTask || ~io.s_axis_task.valid)) {
  //   io.connNetwork.ctrl.serveStealReq.valid := true.B
  //   when(io.connNetwork.ctrl.serveStealReq.ready) {
  //     tasksGivenAwayCount := tasksGivenAwayCount - 1.U
  //   }
  // }

}

