package continuationSide

import chisel3._
import chisel3.util._
import chisel3.ChiselEnum

class contAddrServerIO(addrWidth: Int) extends Bundle {
    val addressIn  = Flipped(DecoupledIO(UInt(addrWidth.W)))
    val addressOut = DecoupledIO(UInt(addrWidth.W))
}

class contAddrServer(addrWidth: Int) extends Module {
    val io = IO(new contAddrServerIO(addrWidth))

    object state extends ChiselEnum 
    {
        val takeInAddress         = Value(0.U)
        val pushAddressToFIFO     = Value(1.U)
    }

    val stateReg = RegInit(state.takeInAddress)
    val addressReg = RegInit(0.U(addrWidth.W))
    
    io.addressOut.bits  := addressReg
    io.addressOut.valid := false.B
    io.addressIn.ready  := false.B

    when(stateReg === state.takeInAddress){
        when(io.addressIn.valid){
            stateReg   := state.pushAddressToFIFO
            addressReg := io.addressIn.bits
        }
    }.elsewhen(stateReg === state.pushAddressToFIFO){
        when(io.addressOut.ready){
            stateReg   := state.takeInAddress
        }
    }.otherwise{
        stateReg := state.takeInAddress
    }

    when(stateReg === state.takeInAddress){
        io.addressIn.ready := true.B
    }.elsewhen(stateReg === state.pushAddressToFIFO){
        io.addressOut.valid := true.B
    }   
}