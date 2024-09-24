package ArgumentNotifier

import chisel3._
import chisel3.util._
import chisel3.ChiselEnum

class ArgumentNotifierNetworkUnitIO(addrWidth: Int) extends Bundle {
  val addressIn = Flipped(DecoupledIO(UInt(addrWidth.W))) // Input from the previous unit
  val peAddress = Flipped(DecoupledIO(UInt(addrWidth.W))) // Input from the PE Queue
  val addressOut = DecoupledIO(UInt(addrWidth.W)) // Output to the next unit
}

class ArgumentNotifierNetworkUnit(addrWidth: Int, priority: Int) extends Module {
  val io = IO(new ArgumentNotifierNetworkUnitIO(addrWidth))

  object state extends ChiselEnum {
    val takeInAddress = Value(0.U)
    val giveAddr = Value(1.U)
  }

  val stateReg = RegInit(state.takeInAddress)
  val addressReg = RegInit(0.U(addrWidth.W))
  val priorityReg = RegInit(priority.U)

  io.addressIn.ready := false.B
  io.peAddress.ready := false.B
  io.addressOut.valid := false.B
  io.addressOut.bits := addressReg

  when(stateReg === state.takeInAddress) {
    when(io.addressIn.valid && io.peAddress.valid) {
      when(priorityReg === 0.U) {
        addressReg := io.peAddress.bits
        priorityReg := priority.U
      }.otherwise {
        addressReg := io.addressIn.bits
      }
      priorityReg := priorityReg - 1.U
    }.otherwise {
      when(io.peAddress.valid) {
        addressReg := io.peAddress.bits
      }.elsewhen(io.addressIn.valid) {
        addressReg := io.addressIn.bits
        when(priorityReg =/= 0.U) {
          priorityReg := priorityReg - 1.U
        }
      }
    }

    when(io.addressIn.valid || io.peAddress.valid) {
      stateReg := state.giveAddr
    }

  }.elsewhen(stateReg === state.giveAddr) {
    when(io.addressOut.ready) {
      stateReg := state.takeInAddress
    }
  }.otherwise {
    stateReg := state.takeInAddress
  }

  when(stateReg === state.takeInAddress) {
    when(io.addressIn.valid && io.peAddress.valid) {
      when(priorityReg === 0.U) {
        io.peAddress.ready := true.B
      }.otherwise {
        io.addressIn.ready := true.B
      }
    }.otherwise {
      when(io.peAddress.valid) {
        io.peAddress.ready := true.B
      }.elsewhen(io.addressIn.valid) {
        io.addressIn.ready := true.B
      }
    }
  }.elsewhen(stateReg === state.giveAddr) {
    io.addressOut.valid := true.B
  }

}
