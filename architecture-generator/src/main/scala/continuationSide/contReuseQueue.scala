// package continuationSide

// import chisel3._
// import chisel3.util._
// import chisel3.ChiselEnum
// import dataclass.data


// class contReuseQueueIO(sysAddressWidth: Int,vasCount: Int) extends Bundle {
//     val dataOut       = DecoupledIO(UInt(sysAddressWidth.W))
//     val dataIn        = Vec(vasCount,Flipped(DecoupledIO(UInt(sysAddressWidth.W))))
// }

// class contReuseQueue(sysAddressWidth: Int, queueDepth: Int, vasCount: Int) extends Module {


//     val contQ = Module(new contQueue(sysAddressWidth, queueDepth))
//     val io = IO(new contReuseQueueIO(sysAddressWidth, vasCount))
    
    
    



//     // VAS can drive the queue
//     io.dataIn.ready := contQ.io.addressIn.ready
//     io.dataIn.valid := contQ.io.addressIn.valid
//     io.dataIn.bits  := contQ.io.addressIn.bits


//     io.dataOut.valid          := false.B
//     contQ.io.addressOut.ready := false.B
//     io.dataOut.bits           := contQ.io.addressOut.bits


//     object state extends ChiselEnum 
//     {<
//         val readQueue         = Value(0.U)
//         val writeToContServer = Value(1.U)
//     }

//     val stateReg0  = RegInit(state.readQueue)
//     val addrOutReg = RegInit(0.U(sysAddressWidth.W))


//     when(stateReg0 === state.readQueue) {
//         contQ.io.addressOut.ready := true.B
//         when(contQ.io.addressOut.valid) {
//             stateReg0 := state.writeToContServer
//             addrOutReg := contQ.io.addressOut.bits
//         }
//     }.elsewhen(stateReg0 === state.writeToContServer) {
//         when(io.dataOut.ready) {
//             stateReg0 := state.readQueue
//         }
//     }
//     io.dataOut.ready := 
//     io.dataOut.valid := contQ.io.addressOut.valid
//     io.dataOut.bits  := addrOutReg
// }
