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

  val SeenNoRequestOrTask = RegInit(0.U(32.W))
  val allowSpecialRequestsCount = RegInit(0.U(32.W))
  val tasksRead = RegInit(0.U(32.W))

  // to take a task u need to write a network request first  
  val allowCount = RegInit(0.U(32.W))
  when(startTokenReceived && allowCount < readTasksCountReg && io.m_axis_task.ready) {
    io.connNetwork.ctrl.stealReq.valid := true.B
    when(io.connNetwork.ctrl.stealReq.ready){
      allowCount := allowCount + 1.U
    }
  }.otherwise{
    // Special requests when no requests or tasks are seen for a long time
    when(SeenNoRequestOrTask >= 32.U && allowSpecialRequestsCount === 0.U){
      allowSpecialRequestsCount := allowCount - tasksRead
    }

    when(allowSpecialRequestsCount > 0.U){
      io.connNetwork.ctrl.stealReq.valid := true.B
      when(io.connNetwork.ctrl.stealReq.ready){
        allowSpecialRequestsCount := allowSpecialRequestsCount - 1.U
      }
    }
  } 


 
  when(startTokenReceived && tasksRead < allowCount){
    io.connNetwork.data.availableTask.ready := io.m_axis_task.ready
    io.m_axis_task.valid := io.connNetwork.data.availableTask.valid
    io.m_axis_task.bits := io.connNetwork.data.availableTask.bits

    when(io.connNetwork.data.availableTask.fire){
      tasksRead := tasksRead + 1.U
    }

    // SeenNoRequestOrTask is used to detect if the requests were killed in the network
    val networkDataValid = io.connNetwork.data.availableTask.fire
    val networkRequestValid = io.connNetwork.ctrl.stealReq.valid
    
    when(!networkDataValid && !networkRequestValid){
      SeenNoRequestOrTask := SeenNoRequestOrTask + 1.U
    }.otherwise{
      SeenNoRequestOrTask := 0.U
    }
  }

  when(startTokenReceived && tasksRead === readTasksCountReg){
    allowCount := 0.U
    tasksRead := 0.U
    startTokenReceived := false.B
    readTasksCountReg := 0.U
  }
  

  val readTaskCounter = RegInit(0.U(32.W))
  when(io.connNetwork.data.availableTask.fire){
    readTaskCounter := readTaskCounter + 1.U
  }

  val cyclesCounter = RegInit(0.U(32.W))
  cyclesCounter := cyclesCounter + 1.U
  when(cyclesCounter === 100000.U){
    printf("_______\n")
    printf("FPGA ID: %d, Number of tasks read from the local network: %d\n", io.fpgaId, readTaskCounter)
    printf("_______\n")
    cyclesCounter := 0.U
  }
  dontTouch(readTaskCounter)
  dontTouch(cyclesCounter)
  
}

