package Allocator

import chisel3._
import chisel3.util._
import chisel3.ChiselEnum

class AllocatorNetworkUnitIO(addrWidth: Int) extends Bundle {
  val addressIn = Flipped(DecoupledIO(UInt(addrWidth.W)))
  val addressOut = DecoupledIO(UInt(addrWidth.W))
  val casAddressOut = DecoupledIO(UInt(addrWidth.W))
}

class AllocatorNetworkUnit(addrWidth: Int) extends Module {
  val io = IO(new AllocatorNetworkUnitIO(addrWidth))

  object state extends ChiselEnum {
    val takeInAddress = Value(0.U)
    val giveAddr = Value(1.U)
  }

  val stateReg = RegInit(state.takeInAddress)
  val addressReg = RegInit(0.U(addrWidth.W))
  val priorityReg = RegInit(true.B)

  io.addressIn.ready := false.B
  io.addressOut.valid := false.B
  io.addressOut.bits := addressReg
  io.casAddressOut.valid := false.B
  io.casAddressOut.bits := addressReg

  when(stateReg === state.takeInAddress) {
    when(io.addressIn.valid) {
      addressReg := io.addressIn.bits
      stateReg := state.giveAddr
    }
  }.elsewhen(stateReg === state.giveAddr) {
    when(io.addressOut.ready || io.casAddressOut.ready) {
      stateReg := state.takeInAddress
      priorityReg := ~priorityReg
    }
  }.otherwise {
    stateReg := state.takeInAddress
  }

  when(stateReg === state.takeInAddress) {
    io.addressIn.ready := true.B
  }.elsewhen(stateReg === state.giveAddr) {
    when(io.addressOut.ready && io.casAddressOut.ready) {
      when(priorityReg) {
        when(io.casAddressOut.ready) {
          io.casAddressOut.valid := true.B
        }
      }.otherwise {
        when(io.addressOut.ready) {
          io.addressOut.valid := true.B
        }
      }
    }.otherwise {
      when(io.casAddressOut.ready) {
        io.casAddressOut.valid := true.B
      }.elsewhen(io.addressOut.ready) {
        io.addressOut.valid := true.B
      }
    }
  }
}
