package argRouting

import chisel3._
import chisel3.util._
import chisel3.ChiselEnum

class argRouteNtwUnitIO(addrWidth: Int) extends Bundle {
    val addressIn   = Flipped(DecoupledIO(UInt(addrWidth.W)))    // Input from the previous unit
    val peAddress   = Flipped(DecoupledIO(UInt(addrWidth.W)))    // Input from the PE Queue
    val addressOut  = DecoupledIO(UInt(addrWidth.W))             // Output to the next unit
}

class argRouteNtwUnit(addrWidth: Int) extends Module {
    val io = IO(new argRouteNtwUnitIO(addrWidth))

    object state extends ChiselEnum 
    {
        val takeInAddress         = Value(0.U)
        val giveAddr              = Value(1.U)
    }

    val stateReg    = RegInit(state.takeInAddress)
    val addressReg  = RegInit(0.U(addrWidth.W))
    val priorityReg = RegInit(true.B)

    io.addressIn.ready     := false.B
    io.peAddress.ready     := false.B
    io.addressOut.valid    := false.B
    io.addressOut.bits     := addressReg



    when(stateReg === state.takeInAddress){
        when(io.addressIn.valid && io.peAddress.valid){
            when(priorityReg){
                addressReg := io.peAddress.bits
            }.otherwise{
                addressReg := io.addressIn.bits
            }
            priorityReg := ~priorityReg
        }.otherwise{
            when(io.peAddress.valid){
                addressReg := io.peAddress.bits
            }.elsewhen(io.addressIn.valid){
                addressReg := io.addressIn.bits
            }
        }

        when(io.addressIn.valid || io.peAddress.valid){
            stateReg   := state.giveAddr
        }

    }.elsewhen(stateReg === state.giveAddr){
        when(io.addressOut.ready){
            stateReg := state.takeInAddress
        }
    }.otherwise{
        stateReg := state.takeInAddress
    }


    when(stateReg === state.takeInAddress){
        when(io.addressIn.valid && io.peAddress.valid){
            when(priorityReg){
                io.peAddress.ready := true.B
            }.otherwise{
                io.addressIn.ready := true.B
            }
        }.otherwise{
            when(io.peAddress.valid){
                io.peAddress.ready  := true.B
            }.elsewhen(io.addressIn.valid){
                io.addressIn.ready  := true.B
            }
        }
    }.elsewhen(stateReg === state.giveAddr){
        io.addressOut.valid := true.B
    }

}