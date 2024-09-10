package ArgumentNotifier

import chisel3._
import chisel3.util._
import chisel3.ChiselEnum

class ArgumentNotifierServerNetworkUnitIO(addrWidth: Int) extends Bundle {
  val addressIn = Flipped(DecoupledIO(UInt(addrWidth.W))) // Input from the previous unit
  val vasAddressOut = DecoupledIO(UInt(addrWidth.W)) // Output to the VAS
  val addressOut = DecoupledIO(UInt(addrWidth.W)) // Output to the next unit
}

class ArgumentNotifierServerNetworkUnit(
    addrWidth: Int,
    tagBitsNumber: Int,
    tagBitsShift: Int,
    tagBitsValue: Array[Int],
    vasNumber: Int
) extends Module {
  val io = IO(new ArgumentNotifierServerNetworkUnitIO(addrWidth))

  object state extends ChiselEnum {
    val takeInAddress = Value(0.U)
    val giveAddrNext = Value(1.U)
    val giveAddrVAS = Value(2.U)
  }

  val stateReg = RegInit(state.takeInAddress)
  val addressReg = RegInit(0.U(addrWidth.W))
  val mask = ((1.U << tagBitsNumber) - 1.U)

  io.addressIn.ready := false.B
  io.vasAddressOut.valid := false.B
  io.addressOut.valid := false.B
  io.vasAddressOut.bits := addressReg
  io.addressOut.bits := addressReg

  when(stateReg === state.takeInAddress) {
    io.addressIn.ready := true.B
    when(io.addressIn.valid) {
      addressReg := io.addressIn.bits
      if (tagBitsValue.length == 2) {
        when(
          (io.addressIn.bits >> tagBitsShift & mask) === tagBitsValue(0).U(tagBitsNumber.W)
            || (io.addressIn.bits >> tagBitsShift & mask) === tagBitsValue(1).U(tagBitsNumber.W)
        ) {
          stateReg := state.giveAddrVAS
        }.otherwise {
          stateReg := state.giveAddrNext
        }
      } else {
        when((io.addressIn.bits >> tagBitsShift & mask) === tagBitsValue(0).U(tagBitsNumber.W)) {
          stateReg := state.giveAddrVAS
        }.otherwise {
          stateReg := state.giveAddrNext
        }
      }
    }
  }.elsewhen(stateReg === state.giveAddrNext) {
    io.addressOut.valid := true.B
    when(io.addressOut.ready) {
      stateReg := state.takeInAddress
    }
  }.elsewhen(stateReg === state.giveAddrVAS) {
    io.vasAddressOut.valid := true.B
    when(io.vasAddressOut.ready) {
      stateReg := state.takeInAddress
    }
  }

  when(stateReg === state.takeInAddress) {
    io.addressIn.ready := true.B
  }.elsewhen(stateReg === state.giveAddrNext) {
    io.addressOut.valid := true.B
  }.elsewhen(stateReg === state.giveAddrVAS) {
    io.vasAddressOut.valid := true.B
  }

}
