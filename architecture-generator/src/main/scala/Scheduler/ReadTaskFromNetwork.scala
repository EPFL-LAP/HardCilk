package Scheduler

import chisel3._
import chisel3.util._
import Util._
import chisel3.ChiselEnum

class ReadTaskFromNetworkIO(taskWidth: Int) extends Bundle {
  val connNetwork = Flipped(new SchedulerNetworkClientIO(taskWidth))
  val m_axis_task = DecoupledIO(UInt(taskWidth.W))
  val readTasksCount = Flipped(DecoupledIO(UInt(32.W)))
  val fpgaId = Input(UInt(64.W))
}

class ReadTaskFromNetwork(
    taskWidth: Int,
    numTasksToStealOrServe: Int,
) extends Module {

  val io = IO(new ReadTaskFromNetworkIO(taskWidth))

  io.connNetwork.ctrl.stealReq.valid := false.B
  io.connNetwork.ctrl.serveStealReq.valid := false.B
  io.connNetwork.data.availableTask.ready := false.B
  io.connNetwork.data.qOutTask.valid := false.B
  io.connNetwork.data.qOutTask.bits := 0.U
  
  io.m_axis_task.valid := false.B
  io.m_axis_task.bits := 0.U

  io.readTasksCount.ready := false.B

  val startTokenReceived = RegInit(false.B) 
  val readTasksCountReg = RegInit(0.U(32.W))

  when(!startTokenReceived){
    io.readTasksCount.ready := true.B
    when(io.readTasksCount.valid){
      startTokenReceived := true.B
      readTasksCountReg := io.readTasksCount.bits
    }
  }

  // to take a task u need to write a network request first  
  val allowCount = RegInit(0.U(32.W))
  when(startTokenReceived && allowCount < readTasksCountReg && io.m_axis_task.ready) {
    io.connNetwork.ctrl.stealReq.valid := true.B
    when(io.connNetwork.ctrl.stealReq.ready){
      allowCount := allowCount + 1.U
    }
  } 

  val tasksRead = RegInit(0.U(32.W))
  when(startTokenReceived && tasksRead < readTasksCountReg){
    io.connNetwork.data.availableTask.ready := io.m_axis_task.ready
    io.m_axis_task.valid := io.connNetwork.data.availableTask.valid
    io.m_axis_task.bits := io.connNetwork.data.availableTask.bits

    when(io.m_axis_task.fire){
      tasksRead := tasksRead + 1.U
    }
  }

  when(startTokenReceived && tasksRead === readTasksCountReg){
    allowCount := 0.U
    tasksRead := 0.U
    startTokenReceived := false.B
  }

  // io.readTasksCount.ready := false.B

  // // Create an internal queue of size numTasksToStealOrServe to have space to store the tasks that are read from the network
  // val taskQueue = Module(new Queue(UInt(taskWidth.W), numTasksToStealOrServe))
  // taskQueue.io.enq.valid := false.B
  // taskQueue.io.enq.bits := 0.U
  
  
  // taskQueue.io.deq.ready := io.m_axis_task.ready
  // io.m_axis_task.valid := taskQueue.io.deq.valid
  // io.m_axis_task.bits := taskQueue.io.deq.bits


  // // States
  // object state extends ChiselEnum {
  //   val init = Value(0.U)
  //   val readTasks = Value(1.U)
  // }

  // val stateReg = RegInit(state.init)
  // val readTaskRegData = RegInit(0.U(32.W))
  // val readTaskRegReq = RegInit(0.U(32.W))

  // when(stateReg === state.init){
  //   when(io.readTasksCount.valid && taskQueue.io.count === 0.U){
  //     readTaskRegData := io.readTasksCount.bits
  //     readTaskRegReq := readTaskRegReq + io.readTasksCount.bits
  //     io.readTasksCount.ready := true.B
  //     stateReg := state.readTasks
  //   }
  //   // only ready when the task queue is empty to make sure there is always space to store the tasks    
  //   io.readTasksCount.ready := (taskQueue.io.count  === 0.U)
  // }.elsewhen(stateReg === state.readTasks){
  //   when(readTaskRegData > 0.U){
  //     stateReg := state.readTasks
  //   }.otherwise{
  //     stateReg := state.init
  //   }
  // }

  // io.connNetwork.ctrl.stealReq.valid := false.B
  // io.connNetwork.ctrl.serveStealReq.valid := false.B
  // io.connNetwork.data.availableTask.ready := false.B
  // io.connNetwork.data.qOutTask.valid := false.B
  // io.connNetwork.data.qOutTask.bits := 0.U
  



  // // This section reads the data from the network according to the number of tasks requested
  // when(stateReg === state.readTasks){
  //   when(readTaskRegData > 0.U){  
  //     io.connNetwork.data.availableTask.ready := true.B
  //   }

  //   when(io.connNetwork.data.availableTask.valid && readTaskRegData > 0.U){ 
  //       //io.m_axis_task.bits := io.connNetwork.data.availableTask.bits
  //       //io.m_axis_task.valid := true.B
  //       taskQueue.io.enq.valid := true.B
  //       taskQueue.io.enq.bits := io.connNetwork.data.availableTask.bits
  //       readTaskRegData := readTaskRegData - 1.U
  //   }
  // }

  // // This section adds requests to the network according to the number of tasks requested
  // when(readTaskRegReq > 0.U && (!io.readTasksCount.valid || taskQueue.io.count =/= 0.U)){
  //   when(readTaskRegReq > 0.U){
  //     io.connNetwork.ctrl.stealReq.valid := true.B // Request task
  //   }    
  //   when(io.connNetwork.ctrl.stealReq.ready && readTaskRegReq > 0.U){
  //     readTaskRegReq := readTaskRegReq - 1.U
  //   }
  // }

  // Create a counter here to keep track of the number of tasks that have been read from the network and print it 
  // to the console with fpga ID
  // val readTaskCounter = RegInit(0.U(32.W))
  // when(taskQueue.io.enq.fire){
  //   readTaskCounter := readTaskCounter + 1.U
  // }

  // val cyclesCounter = RegInit(0.U(32.W))
  // cyclesCounter := cyclesCounter + 1.U
  // when(cyclesCounter === 10000.U){
  //   printf("FPGA ID: %d, Number of tasks read from the network: %d\n", io.fpgaId, readTaskCounter)
  //   cyclesCounter := 0.U
  // }
  // dontTouch(readTaskCounter)
  // dontTouch(cyclesCounter)
  



}

