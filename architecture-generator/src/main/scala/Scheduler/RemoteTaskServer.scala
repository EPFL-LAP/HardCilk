// package Scheduler

// import chisel3._
// import chisel3.util._
// import Util._

// import chext.amba.axi4s
// import chext.amba.axi4
// import chisel3.ChiselEnum
// import axi4.lite.components.RegisterBlock
// import axi4.Ops._




// /**
//  * RemoteTaskServer
//  * What does this moudle shall do? (No operations can be reverted, it shall continue to the end to stop deadlocks)
//  * 1. When serveRemote and getTasksFromRemote are low, the module shall forward the tasks from s_axis to m_axis
//  * 2. When serveRemote is high, the module shall start reading the network and fetch the tasks from the network
//  *    -- The module shall have a state machine to read the network and fetch the tasks into its local queue
//  *    -- The module shall check the s_axis for a request (If none was found and getTasksFromRemote is high, the module shall rewrite the data to the network)
//  *    -- If there is a request, the module shall supress the request and then send numTasksToStealOrServe tasks to the m_axis
//  * 3. When getTasksFromRemote is high, the module shall add a request to the m_axis 
//  *    -- After adding the request, the module shall read numTasksToStealOrServe from the s_axis and add them to the local queue
//  *    -- The module shall then send the tasks to the network
//  */



// class RemoteTaskServerIO(taskWidth: Int) extends Bundle {
//   assert((taskWidth + 8) < 512, "Task width is too large for the streaming interface in the RemoteTaskServer")
//   val axisCfgTaskAndReq = axi4s.Config(wData = (taskWidth + 8), wDest = 4) // task plus 8 bits for task valid and request value (8 bits for AXIS compat)
//   val s_axis_taskAndReq = axi4s.Slave(axisCfgTaskAndReq)
//   val m_axis_taskAndReq = axi4s.Master(axisCfgTaskAndReq)
//   val connNetwork_0 = Flipped(new SchedulerNetworkClientIO(taskWidth)) // Connection to the stealing Network
//   val connNetwork_1 = Flipped(new SchedulerNetworkClientIO(taskWidth)) // Connection to the stealing Network
//   val serveRemote = Input(Bool())         // A signal from the VSS
//   val getTasksFromRemote = Input(Bool())  // A signal from the VSS

//   val fpgaIndexInputReg = Input(UInt(8.W))
//   val fpgaCountInputReg = Input(UInt(8.W))
// }

// class RemoteTaskServer(
//     taskWidth: Int,
//     numTasksToStealOrServe: Int,
//     peCount: Int,
//     taskIndex: Int = 0
// ) extends Module {

//   val io = IO(new RemoteTaskServerIO(taskWidth))


//   // States
//   object state extends ChiselEnum {
//     val init = Value(0.U)
//     val serveRemote = Value(1.U)
//     val fetchRemote = Value(2.U)
//   }

//   val stateReg = RegInit(state.init)


//   // Task queue of size numTasksToStealOrServe
//   val taskQueue = Module(new Queue(UInt(taskWidth.W), numTasksToStealOrServe)).io
//   taskQueue.enq.valid := false.B
//   taskQueue.deq.ready := false.B
//   taskQueue.enq.bits := 0.U

//   io.m_axis_taskAndReq.TDATA := io.s_axis_taskAndReq.TDATA
//   io.m_axis_taskAndReq.TVALID := io.s_axis_taskAndReq.TVALID
//   io.s_axis_taskAndReq.TREADY := io.m_axis_taskAndReq.TREADY

//   io.m_axis_taskAndReq.TDEST.get := ((io.fpgaIndexInputReg + 1.U) % io.fpgaCountInputReg) // Set the destination as the next FPGA on a ring.

//   io.m_axis_taskAndReq.TKEEP.get := 0.U // We ignore TKEEP
//   io.m_axis_taskAndReq.TLAST.get := 0.U // We ignore TLAST
//   io.m_axis_taskAndReq.TSTRB.get := 0.U // We ignore TSTRB

//   // Instantiate a network reader
//   val readTaskFromNetwork = Module(new ReadTaskFromNetwork(taskWidth, numTasksToStealOrServe)).io
//   val writeTaskToNetwork = Module(new WriteTaskToNetwork(taskWidth, numTasksToStealOrServe, peCount)).io
//   readTaskFromNetwork.connNetwork <> io.connNetwork_0
//   writeTaskToNetwork.connNetwork <> io.connNetwork_1

//   readTaskFromNetwork.readTasksCount.valid := false.B
//   readTaskFromNetwork.readTasksCount.bits := 0.U
//   readTaskFromNetwork.m_axis_task.ready := false.B

//   writeTaskToNetwork.s_axis_task.valid := false.B
//   writeTaskToNetwork.s_axis_task.bits := 0.U


//   // When Serve Remote 3 things shall be done in ``parallel'', state is reverted to init when 3 is done
//   // 1. Read the network and fetch the tasks
//   // 2. Check the s_axis for a request
//   // 3. If there is a request, supress the request and send numTasksToStealOrServe tasks to the m_axis


//   val reqFound = RegInit(false.B)  
//   val readFromNetworkCounter = RegInit(0.U(log2Ceil(numTasksToStealOrServe).W))
//   val writeToRemoteCounter = RegInit(0.U(log2Ceil(numTasksToStealOrServe).W))
//   val revertServing = RegInit(false.B)
//   val revertServingDone = RegInit(false.B)
//   val revertServingCounter = RegInit(0.U(log2Ceil(numTasksToStealOrServe).W))



//   val reqAdded = RegInit(false.B)
//   val writtenToNetworkCounter = RegInit(0.U(log2Ceil(numTasksToStealOrServe).W))
//   val readFromRemoteCounter = RegInit(0.U(log2Ceil(numTasksToStealOrServe).W))




//   when(stateReg === state.init){

//     when(io.serveRemote){
    
//       stateReg := state.serveRemote

//       // Prepare for serving remote
//       reqFound := false.B
//       readFromNetworkCounter := numTasksToStealOrServe.U
//       writeToRemoteCounter := numTasksToStealOrServe.U
//       revertServing := false.B
//       revertServingDone := false.B
//       revertServingCounter := numTasksToStealOrServe.U

//       // Reset fetching remote
//       reqAdded := false.B
//       writtenToNetworkCounter := 0.U
//       readFromRemoteCounter := 0.U
      
    
//     }.elsewhen(io.getTasksFromRemote){
      
//       stateReg := state.fetchRemote
      
//       // Reset serving remote
//       reqFound := false.B
//       readFromNetworkCounter := 0.U
//       writeToRemoteCounter := 0.U
//       revertServing := false.B
//       revertServingDone := false.B
//       revertServingCounter := 0.U

//       // Prepare for fetching remote
//       reqAdded := false.B
//       writtenToNetworkCounter := numTasksToStealOrServe.U
//       readFromRemoteCounter := numTasksToStealOrServe.U
//     }

//   }.elsewhen(stateReg === state.serveRemote){

//     when((writeToRemoteCounter === 0.U && reqFound) || (revertServingDone && revertServingCounter === 0.U)){
//       stateReg := state.init
//     } 

//   }.elsewhen(stateReg === state.fetchRemote){

//     when(reqAdded && readFromRemoteCounter === 0.U && writtenToNetworkCounter === 0.U){
//       stateReg := state.init
//     }

//   }
  

//   // This section is responsible for reading the tasks and sending them to remote
//   // serveRemote can be reverted as long as there was no request found
//   when(stateReg === state.serveRemote){
    
//     // Here we need to check if there was a request from a remote FPGA
//     when(!reqFound && !revertServing){
//       val req = io.s_axis_taskAndReq.TDATA(taskWidth + 1, taskWidth + 1)
//       when(req > 0.U & io.s_axis_taskAndReq.TVALID & io.m_axis_taskAndReq.TREADY & readTaskFromNetwork.readTasksCount.ready){
//         reqFound := true.B

//         // Supress the request in the m_axis by changing only the bit at taskWidth to 0
//         io.m_axis_taskAndReq.TDATA := Cat(io.s_axis_taskAndReq.TDATA(taskWidth - 1, 0), false.B)
//         io.m_axis_taskAndReq.TVALID := true.B
//         io.s_axis_taskAndReq.TREADY := true.B  
//         readTaskFromNetwork.readTasksCount.valid := true.B
//         readTaskFromNetwork.readTasksCount.bits := numTasksToStealOrServe.U

//       }.elsewhen(!req && io.getTasksFromRemote){
//         revertServing := true.B
//       }
//     }

//     // Read tasks from the network
//     when(readFromNetworkCounter =/= 0.U){
//       when(readTaskFromNetwork.m_axis_task.valid){
//         taskQueue.enq.bits := readTaskFromNetwork.m_axis_task.bits
//         taskQueue.enq.valid := true.B
//         readFromNetworkCounter := readFromNetworkCounter - 1.U
//       }
//     }

//     // If a request was found, we send the tasks to remote
//     when(writeToRemoteCounter =/= 0.U && reqFound){
//       // We need to check if the incoming task from the network is valid or not 
//       // the upper byte from the s_axis second to lower bit represents if there is a valid task 
//       val valid = io.s_axis_taskAndReq.TDATA(taskWidth + 2, taskWidth + 2) // Re Check this
      
//       when(!valid && io.s_axis_taskAndReq.TVALID && taskQueue.deq.valid && io.m_axis_taskAndReq.TREADY){
        
//         // What we write to the m_axis is the task in the lower bytes
//         // we copy the request bit (lower bit of the upper byte) to the m_axis
//         // We set the valid bit to true
//         io.m_axis_taskAndReq.TDATA := Cat(taskQueue.deq.bits, io.s_axis_taskAndReq.TDATA(taskWidth, taskWidth), true.B) // Re Check this
        
//         io.m_axis_taskAndReq.TVALID := true.B
//         taskQueue.deq.ready := true.B
//         io.s_axis_taskAndReq.TREADY := true.B

//         writeToRemoteCounter := writeToRemoteCounter - 1.U
//       }
//     }

//     when(revertServing && revertServingCounter =/= 0.U){
//       // Drive the writer to write all the tasks in the task queue
//       when(taskQueue.deq.valid && writeTaskToNetwork.s_axis_task.ready){
//         writeTaskToNetwork.s_axis_task.bits := taskQueue.deq.bits
//         writeTaskToNetwork.s_axis_task.valid := true.B
//         taskQueue.deq.ready := true.B
//         revertServingCounter := revertServingCounter - 1.U
//       }
//     }

//   }




//   // Fetch remote is never reverted to avoid deadlocks
//   when(stateReg === state.fetchRemote){
//     when(!reqAdded){
//       // Here we try to add a request to the inter-FPGA connection
//       // the upper byte from the s_axis lower bit represents if there is a request
//       val req = io.s_axis_taskAndReq.TDATA(taskWidth, taskWidth) // Re Check this
//       when(!req && io.s_axis_taskAndReq.TVALID){
//         reqAdded := true.B
//       } 
//     }

//     when(readFromRemoteCounter =/= 0.U){
//       // Here we try to take a task from the inter-FPGA connection
//       // the upper byte from the s_axis second to lower bit represents if there is a valid task
//       val valid = io.s_axis_taskAndReq.TDATA(taskWidth + 1, taskWidth + 1) // Re Check this
//       when(valid === 1.U & io.s_axis_taskAndReq.TVALID & taskQueue.enq.ready){
//         taskQueue.enq.bits := io.s_axis_taskAndReq.TDATA(taskWidth - 1, 0)
//         taskQueue.enq.valid := true.B
//         readFromRemoteCounter := readFromRemoteCounter - 1.U
//       }
//     }

//     when(writtenToNetworkCounter =/= 0.U){
//       // Here we try to write a task to the inter-FPGA connection
//       when(io.connNetwork_1.data.availableTask.ready && taskQueue.deq.valid){
//         io.connNetwork_1.data.qOutTask.bits := taskQueue.deq.bits
//         io.connNetwork_1.data.qOutTask.valid := true.B
//         writtenToNetworkCounter := writtenToNetworkCounter - 1.U
//       }
//     }
//   }


// }


// object RemoteTaskServerEmitter extends App {
//   emitVerilog(new RemoteTaskServer(32, 4, 4, 2), Array("--target-dir", "output"))
// }
