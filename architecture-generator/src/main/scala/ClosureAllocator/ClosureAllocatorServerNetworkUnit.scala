package ClosureAllocator

import chisel3._
import chisel3.util._
import chisel3.ChiselEnum

class ClosureAllocatorServerNetworkUnitIO(addrWidth: Int) extends Bundle {
  val addressIn0 = Flipped(DecoupledIO(UInt(addrWidth.W)))
  val addressIn1 = Flipped(DecoupledIO(UInt(addrWidth.W)))
  val addressOut = DecoupledIO(UInt(addrWidth.W))
}

class ClosureAllocatorServerNetworkUnit(addrWidth: Int) extends Module {
  val io = IO(new ClosureAllocatorServerNetworkUnitIO(addrWidth))

  object state extends ChiselEnum {
    val takeInAddress0 = Value(0.U)
    val takeInAddress1 = Value(1.U)
    val giveAddr = Value(2.U)
  }

  val stateReg = RegInit(state.takeInAddress0)
  val addressReg = RegInit(0.U(addrWidth.W))
  val priorityReg = RegInit(true.B)

  io.addressIn0.ready := false.B
  io.addressIn1.ready := false.B
  io.addressOut.valid := false.B
  io.addressOut.bits := addressReg

  when(stateReg === state.takeInAddress0) {

    when(io.addressIn0.valid) {

      priorityReg := ~priorityReg
      stateReg := state.giveAddr
      addressReg := io.addressIn0.bits

    }.elsewhen(io.addressIn1.valid) {

      priorityReg := ~priorityReg
      stateReg := state.takeInAddress1

    }

  }.elsewhen(stateReg === state.takeInAddress1) {

    when(io.addressIn1.valid) {

      priorityReg := ~priorityReg
      stateReg := state.giveAddr
      addressReg := io.addressIn1.bits

    }.elsewhen(io.addressIn0.valid) {

      priorityReg := ~priorityReg
      stateReg := state.takeInAddress0

    }

  }.elsewhen(stateReg === state.giveAddr) {

    when(io.addressOut.ready) {

      when(priorityReg) {

        stateReg := state.takeInAddress0

      }.otherwise {

        stateReg := state.takeInAddress1

      }

    }

  }

  when(stateReg === state.takeInAddress0) {

    io.addressIn0.ready := true.B

  }.elsewhen(stateReg === state.takeInAddress1) {

    io.addressIn1.ready := true.B

  }.elsewhen(stateReg === state.giveAddr) {

    io.addressOut.valid := true.B

  }

}
