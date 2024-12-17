// /**
//  * A module with 32 axi stream inputs, and 32 axi stream outputs.
//  * The module has an internal 32 element register file that can be read and written to.
//  * The register file consists of 32 addrWidth registers + a locked bit.
//  * The register is used only when the locked bit is set.
//  * The module receives requests on the axi stream input ports and sends the result on the axi stream output ports.
//  * The module arbitrates between the input ports and send the response on the correct output port.
//  * A request is a lock or unlock request with the memoryAddress to lock or unlock.
// */

// package AXIHelpers

// import chisel3._
// import chisel3.util._

// import chext.amba.axi4s
// import chext.elastic._
// import chisel3.ChiselEnum


// class Negotiator(){

//     object state extends ChiselEnum {
//         val readArbiter = Valu(0.U)
//         val checkLock = Value(1.U)
//     }


//     val s_axis_config = axi4s.Config(wData = 1 + addrWidth, onlyRV = true)
//     val m_axis_config = axi4s.Config(wData = 1, onlyRV = true)

//     val m_axis = IO(VecInit(Seq.fill(32)(axi4s.Master(m_axis_config))))
//     val s_axis = IO(VecInit(Seq.fill(32)(axi4s.Slave(s_axis_config))))

//     val registerFile = RegInit(VecInit(Seq.fill(32)(0.U(addrWidth.W))))
//     val locked = RegInit(VecInit(Seq.fill(32)(false.B)))

//     val arbiter = Module(new elastic.Arbiter(chiselTypeOf(UInt), 32, chooserFn = elastic.Chooser.rr))
//     val selectedPort = arbiter.io.select.bits

//     val stateReg = RegInit(state.readArbiter)
    

//     val addressReg = RegInit(0.U(addrWidth.W))
//     val requestReg = RegInit(false.B)

//     // A queue of indicies that are empty
    

//     // Connect all the arbiter sources to the input ports using functional programming
//     arbiter.io.sources.zip(m_axis).map(x => x._1 :=> x._2)

//     // Drive all the output ports with default zeros on the valid and the data
//     s_axis.map(x => {
//         x.TVALID := false.B
//         x.TDATA := 0.U
//     })

//     when(stateReg == state.readArbiter){
//         when(arbiter.io.sink.valid){
//             state := state.checkLock
//         }
//     }.elsewhen(stateRef == state.checkLock){
        
//     }

//     // For the output of the arbiter (addrWidth bit + 1 bit for the lock) save it in a local register and acknowledge the valid
//     when(stateReg == state.readArbiter){
//         addressReg := arbiter.io.sink.bits(addrWidth - 1, 0)
//         requestReg := arbiter.io.sink.bits(addrWidth)   
//         arbiter.io.sink.ready := true.B
//     }

//     // TODO
    
// }