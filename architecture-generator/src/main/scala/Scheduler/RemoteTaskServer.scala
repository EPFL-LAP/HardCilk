package Scheduler

import chisel3._
import chisel3.util._
import Util._


import chext.amba.axi4s
import chext.elastic
import chext.util.BitOps._
import axi4s.Casts._


/** RemoteTaskServer What does this moudle shall do? (No operations can be reverted, it shall continue to the end to stop
  * deadlocks)
  *   1. When serveRemote and getTasksFromRemote are low, the module shall forward the tasks from s_axis to m_axis 2. When
  *      serveRemote is high, the module shall start reading the network and fetch the tasks from the network -- The module
  *      shall have a state machine to read the network and fetch the tasks into its local queue -- The module shall check the
  *      s_axis for a request (If none was found and getTasksFromRemote is high, the module shall rewrite the data to the
  *      network) -- If there is a request, the module shall supress the request and then send numTasksToStealOrServe tasks to
  *      the m_axis 3. When getTasksFromRemote is high, the module shall add a request to the m_axis -- After adding the
  *      request, the module shall read numTasksToStealOrServe from the s_axis and add them to the local queue -- The module
  *      shall then send the tasks to the network
  */

class RemoteTaskServerIO(taskWidth: Int, axisCfgTaskAndReq : axi4s.Config) extends Bundle {
  assert((taskWidth + 14) < 512, "Task width is too large for the streaming interface in the RemoteTaskServer")
  val s_axis_taskAndReq = axi4s.Slave(axisCfgTaskAndReq)
  val m_axis_taskAndReq = axi4s.Master(axisCfgTaskAndReq)
  val connNetwork_0 = Flipped(new SchedulerNetworkClientIO(taskWidth)) // Connection to the stealing Network
  val connNetwork_1 = Flipped(new SchedulerNetworkClientIO(taskWidth)) // Connection to the stealing Network
  val serveRemote = Input(Bool()) // A signal from the VSS
  val getTasksFromRemote = Input(Bool()) // A signal from the VSS

  val fpgaIndexInputReg = Input(UInt(8.W))
  val fpgaCountInputReg = Input(UInt(8.W))
  val numTasksToStealOrServe = Input(UInt(32.W))
}

import HardCilk.globalFunctionIds


class taskRequestReplyType(taskWidth: Int) extends Bundle {
  val fpgaId = UInt(4.W)
  val taskId = UInt(4.W)
  val globalFunctionId = globalFunctionIds()
  val taskData = UInt(taskWidth.W)
  val isRequest = Bool()
  val padding = UInt((512 - fpgaId.getWidth - taskId.getWidth - globalFunctionId.getWidth - taskData.getWidth - isRequest.getWidth).W)  
  assert(this.getWidth == 512, "taskRequestReplyType width must be 512 bits")
}

// Problem Request forwarding is not working correctly.
// Probable solution is to directly go from the demux to the arbiter if not serving remote

class RemoteTaskServer(
    taskWidth: Int,
    peCount: Int,
    axisCfgTaskAndReq : axi4s.Config,
    taskIndex: Int,
    coolDownTime: Int = 0,
    maxNumnberToStealOrServe: Int = 256    
) extends Module {

  val io = IO(new RemoteTaskServerIO(taskWidth, axisCfgTaskAndReq))


  val servingRemote = RegInit(false.B)


  // Instantiate a network reader and writer
  val readTaskFromNetwork = Module(new ReadTaskFromNetwork(taskWidth)).io
  val writeTaskToNetwork = Module(new WriteTaskToNetwork(taskWidth)).io
  writeTaskToNetwork.numTasksToStealOrServe := io.numTasksToStealOrServe
  readTaskFromNetwork.fpgaId := io.fpgaIndexInputReg
  writeTaskToNetwork.fpgaId := io.fpgaIndexInputReg
  readTaskFromNetwork.connNetwork <> io.connNetwork_0
  writeTaskToNetwork.connNetwork <> io.connNetwork_1

  // We need to arbitrate the m_axis_taskAndReq
  val arbiter = Module(
    new elastic.BasicArbiter(chiselTypeOf(io.m_axis_taskAndReq.asFull.bits), 3, chooserFn = elastic.Chooser.rr)
  )
  arbiter.io.select.nodeq()
  when(arbiter.io.select.valid) {
    arbiter.io.select.deq()
  }
  elastic.SourceBuffer(arbiter.io.sink) <> io.m_axis_taskAndReq.asFull

  val requestForwardSlave = Wire(axi4s.Slave(axisCfgTaskAndReq))

  val fetchingRemote = RegInit(false.B)
  val requestSent = RegInit(false.B)
  val dataReceievedCount = RegInit(0.U(32.W))
  val taskRequestingMaster = Wire(axi4s.Master(axisCfgTaskAndReq))
  val taskWaitingSlave = Wire(axi4s.Slave(axisCfgTaskAndReq))
  val flaggedByServingRemote = RegInit(false.B)
  taskRequestingMaster.TDATA := 0.asUInt(512.W)
  taskRequestingMaster.TSTRB.get := 0.U
  taskRequestingMaster.TKEEP.get := 0.U
  taskRequestingMaster.TLAST.get := false.B
  taskRequestingMaster.asFull.valid := false.B
  taskRequestingMaster.TDEST.get := ((io.fpgaIndexInputReg + 1.U) % io.fpgaCountInputReg) // Task request is always sent to the next FPGA
  taskWaitingSlave.asFull.ready := false.B

  // A queue to hold the tasks that are received from the remote FPGAs
  val queueForReceivingTasks = Module(new Queue(UInt(taskWidth.W), maxNumnberToStealOrServe))
  // Connect the queue slave to the taskWaitingSlave from the demux
  queueForReceivingTasks.io.enq.valid := taskWaitingSlave.TVALID
  queueForReceivingTasks.io.enq.bits := taskWaitingSlave.TDATA.asTypeOf(new taskRequestReplyType(taskWidth)).taskData
  taskWaitingSlave.TREADY := queueForReceivingTasks.io.enq.ready

  // Connect the queue master to the writeTaskToNetwork so that it reaches the local network
  queueForReceivingTasks.io.deq.ready := writeTaskToNetwork.s_axis_task.ready
  writeTaskToNetwork.s_axis_task.valid := queueForReceivingTasks.io.deq.valid
  writeTaskToNetwork.s_axis_task.bits := queueForReceivingTasks.io.deq.bits

  // Check if we need to fetch tasks from the network
  when(io.getTasksFromRemote && !fetchingRemote) {
    fetchingRemote := true.B
  }
  
  val networkWriterReady = RegInit(false.B)
  writeTaskToNetwork.startToken.valid := false.B
  writeTaskToNetwork.startToken.bits := 0.U
  when(fetchingRemote && !networkWriterReady) {
    writeTaskToNetwork.startToken.valid := true.B
    writeTaskToNetwork.startToken.bits := 1.U
    when(writeTaskToNetwork.startToken.ready) {
      networkWriterReady := true.B
    }
  }


  // Send a request to the m_axis_port
  when(fetchingRemote && networkWriterReady && !requestSent) {
    taskRequestingMaster.TVALID := true.B
    
    // Create a request packet using the new taskRequestReplyType bundle
    val taskRequestPacket = Wire(new taskRequestReplyType(taskWidth))
    taskRequestPacket.fpgaId := io.fpgaIndexInputReg(3,0)
    taskRequestPacket.taskId := taskIndex.U(4.W)
    taskRequestPacket.globalFunctionId := globalFunctionIds.scheduler
    taskRequestPacket.taskData := 0.U(taskWidth.W)
    taskRequestPacket.isRequest := true.B
    taskRequestPacket.padding := 0.U
    taskRequestingMaster.TDATA := taskRequestPacket.asUInt
   
    taskRequestingMaster.TDEST.get := ((io.fpgaIndexInputReg + 1.U) % io.fpgaCountInputReg) // Task request is always sent to the next FPGA
    when(taskRequestingMaster.TREADY) {
      requestSent := true.B
    }
  }



  // Now listen on the taskWaitingSlave
  when(requestSent && dataReceievedCount < io.numTasksToStealOrServe) {
    when(dataReceievedCount === io.numTasksToStealOrServe - 1.U && taskWaitingSlave.asFull.valid && queueForReceivingTasks.io.enq.ready) {
      requestSent := false.B
      fetchingRemote := false.B
      dataReceievedCount := 0.U
      networkWriterReady := false.B
    }.elsewhen(taskWaitingSlave.asFull.valid && queueForReceivingTasks.io.enq.ready) {
      dataReceievedCount := dataReceievedCount + 1.U
    }

    // Reset this state if flagged by serving remote and no data was received
    when(/*!taskWaitingSlave.asFull.valid && io.serveRemote && dataReceievedCount === 0.U &&*/ flaggedByServingRemote) {

      requestSent := false.B
      fetchingRemote := false.B
      dataReceievedCount := 0.U
      flaggedByServingRemote := false.B

    }

  }

  // Start Listening to the incoming requests

  val activelyServing = RegInit(false.B)
  val fetchLocalNetwork = RegInit(false.B)
  val servingCount = RegInit(0.U(32.W))
  val taskServingMaster = Wire(axi4s.Master(axisCfgTaskAndReq))
  taskServingMaster.TDATA := 0.asUInt(512.W)
  taskServingMaster.TSTRB.get := 0.U
  taskServingMaster.TKEEP.get := 0.U
  taskServingMaster.TLAST.get := false.B
  taskServingMaster.asFull.valid := false.B
  val taskRequestListener = Wire(axi4s.Slave(axisCfgTaskAndReq))
  taskRequestListener.asFull.ready := false.B

  val fpgaToServe = RegInit(0.U(8.W))

  val queueForSendingTasks = Module(new Queue(UInt(512.W), maxNumnberToStealOrServe))
  // Connect the enq of the queue to readTaskFromNetwork
  queueForSendingTasks.io.enq.valid := readTaskFromNetwork.m_axis_task.valid
  queueForSendingTasks.io.enq.bits := readTaskFromNetwork.m_axis_task.bits
  readTaskFromNetwork.m_axis_task.ready := queueForSendingTasks.io.enq.ready

  // Connect the deq of the queue to the taskServingMaster
  queueForSendingTasks.io.deq.ready := taskServingMaster.TREADY
  taskServingMaster.TVALID := queueForSendingTasks.io.deq.valid

  // pack the tak reply using the new taskRequestReplyType bundle
  val taskReplyPacket = Wire(new taskRequestReplyType(taskWidth))
  taskReplyPacket.fpgaId := io.fpgaIndexInputReg(3,0)
  taskReplyPacket.taskId := taskIndex.U(4.W)
  taskReplyPacket.globalFunctionId := globalFunctionIds.scheduler
  taskReplyPacket.taskData := queueForSendingTasks.io.deq.bits
  taskReplyPacket.isRequest := false.B
  taskReplyPacket.padding := 0.U

  // Now the data should be comproised of taskIndex, taskData, and the fact that this is a valid data
  taskServingMaster.TDATA := taskReplyPacket.asUInt
  taskServingMaster.TDEST.get := fpgaToServe


  // Count the fires of the taskServeingMaster
  val fires_counter = RegInit(0.U(32.W))
  when(taskServingMaster.asFull.fire) {
    fires_counter := fires_counter + 1.U
  }
  
  // Count the fires of the taskRequestListener
  val fires_counter_listener = RegInit(0.U(32.W))
  when(taskWaitingSlave.asFull.fire) {
    fires_counter_listener := fires_counter_listener + 1.U
  }

  // Print each 10000 cycles
  val cycles_counter = RegInit(0.U(32.W))
  cycles_counter := cycles_counter + 1.U
  when(cycles_counter === 1000000.U) {
    printf("_______\n")
    printf("FPGA ID %d, taskID %d: Tasks recieved from remote: %d\n", io.fpgaIndexInputReg, taskIndex.U, fires_counter_listener)
    printf("FPGA ID %d, taskID %d: Tasks sent to remote: %d\n", io.fpgaIndexInputReg, taskIndex.U, fires_counter)
    printf("_______\n")
    cycles_counter := 0.U
  }



  // Check if we need to serve the remote
  when(io.serveRemote && !servingRemote) {
    servingRemote := true.B
  }

  // Listen to see if there is a request
  when(servingRemote && !activelyServing) {
    taskRequestListener.asFull.ready := true.B
    when(taskRequestListener.asFull.valid) {
      // TDATA from 512 - 4 till 512-12 is the fpga index
      fpgaToServe := taskRequestListener.asTypeOf(new taskRequestReplyType(taskWidth)).fpgaId
      activelyServing := true.B

    }.elsewhen(!io.serveRemote) {
      servingRemote := false.B // fall back if there was no request and the serveRemote is low
    }
  }

  readTaskFromNetwork.readTasksCount.valid := false.B
  readTaskFromNetwork.readTasksCount.bits := 0.U
  // Get tasks from your local network
  when(servingRemote && activelyServing && !fetchLocalNetwork) {
    readTaskFromNetwork.readTasksCount.valid := true.B
    readTaskFromNetwork.readTasksCount.bits := io.numTasksToStealOrServe
    when(readTaskFromNetwork.readTasksCount.ready) {
      fetchLocalNetwork := true.B
    }
  }

  // When you informed the module to fetch local network, count the fires for the tasks served
  when(fetchLocalNetwork && servingCount < io.numTasksToStealOrServe) {
    when(servingCount === io.numTasksToStealOrServe - 1.U && taskServingMaster.TREADY && queueForSendingTasks.io.deq.valid) {
      fetchLocalNetwork := false.B
      activelyServing := false.B
      servingRemote := false.B
      servingCount := 0.U
    }.elsewhen(taskServingMaster.TREADY && queueForSendingTasks.io.deq.valid) {
      servingCount := servingCount + 1.U
    }
  }

  val requestForwardSlaveSupressSelfRequest = Wire(axi4s.Slave(axisCfgTaskAndReq))
  requestForwardSlaveSupressSelfRequest.asFull.ready := true.B
  
  //when(flaggedByServingRemote === false.B){
    flaggedByServingRemote := requestForwardSlaveSupressSelfRequest.asFull.valid
  //}

  //val s_axis_taskAndReqMarked = Wire(Irrevocable(new chext.amba.axi4s.FullChannel(io.s_axis_taskAndReq.cfg)))

  val s_axis_taskAndReqMarked = Wire(Irrevocable(new chext.amba.axi4s.FullChannel(io.s_axis_taskAndReq.cfg.copy(wData = 520))))

  new elastic.Arrival(io.s_axis_taskAndReq.asFull, s_axis_taskAndReqMarked) {
    protected def onArrival: Unit = {
      out := in
      out.data := Cat(servingRemote, activelyServing) ## in.data
      accept()
    }
  }

  // A demux to separate requests from tasks
  new elastic.Fork(s_axis_taskAndReqMarked) {
    override def onFork(): Unit = {
      // Create a wire to hold the parsed packet
      val taskRequest = Wire(new taskRequestReplyType(taskWidth))
      taskRequest := in.data(511, 0).asTypeOf(new taskRequestReplyType(taskWidth))

      val isRequest = taskRequest.isRequest 
      val isSelfRequest = (taskRequest.fpgaId === io.fpgaIndexInputReg) && isRequest

      val isServingRemote = in.data.dropMsbN(7).msbN(1).asBool
      val isActivelyServing = in.data.dropMsbN(6).msbN(1).asBool
      elastic.Demux(
        source = fork(in),
        sinks = Seq(elastic.SinkBuffer(taskWaitingSlave.asFull), elastic.SinkBuffer(taskRequestListener.asFull), elastic.SinkBuffer(requestForwardSlave.asFull), elastic.SinkBuffer(requestForwardSlaveSupressSelfRequest.asFull)),
        select = elastic.SourceBuffer(fork(
          Cat(
          
            //(isRequest && !(servingRemote && !activelyServing)) || (isRequest && isSelfRequest && servingRemote),
            
            (isRequest && !servingRemote) || (isRequest && activelyServing) || (isSelfRequest && servingRemote),
            
            //(servingRemote && !activelyServing && isRequest) || (servingRemote && isSelfRequest)
            
            servingRemote && ((!activelyServing && isRequest) ||  isSelfRequest)

          ))
        )
      )

    }
  }

  //// Connect the taskRequestingMaster to the arbiter
  taskRequestingMaster.asFull <> arbiter.io.sources(0)
  taskServingMaster.asFull <> arbiter.io.sources(1)

  

  new elastic.Transform(requestForwardSlave.asFull, arbiter.io.sources(2)) {
    protected def onTransform: Unit = {
      out.data := in.data
      out.dest.get := (in.dest.get + 1.U) % io.fpgaCountInputReg
      out.keep := in.keep
      out.last := in.last
      out.strobe := in.strobe
    }
  }


  // Log request generation and request subression
  when(taskRequestingMaster.asFull.valid) {
    printf("TIME %d, FPGA ID %d, packet FPGAID %d: Request generated\n", cycles_counter, io.fpgaIndexInputReg, taskRequestingMaster.TDATA(511 - 4, 511 - 11))
  }

  when(requestForwardSlaveSupressSelfRequest.asFull.valid) {
    printf("TIME %d, FPGA ID %d, packet FPGAID %d: Request subpressed\n", cycles_counter, io.fpgaIndexInputReg, requestForwardSlaveSupressSelfRequest.TDATA(511 - 4, 511 - 11))
  }

}
