package Allocator

import chisel3._
import chisel3.util._

class AllocatorNetworkUnitIO(addrWidth: Int) extends Bundle {
  val addressIn = Input(UInt(addrWidth.W))
  val validIn = Input(Bool())
  val addressOut = Output(UInt(addrWidth.W))
  val validOut = Output(Bool())

  // Tap to the local PE's CAS server / queue.
  val casAddressOut = DecoupledIO(UInt(addrWidth.W))
}

class AllocatorNetworkUnit(addrWidth: Int) extends Module {
  val io = IO(new AllocatorNetworkUnitIO(addrWidth))

  val addrReg = RegInit(0.U(addrWidth.W))
  val validReg = RegInit(false.B)

  io.casAddressOut.valid := false.B
  io.casAddressOut.bits := io.addressIn

  when(io.casAddressOut.ready && io.validIn) {
    // Local PE takes the passing address off the ring.
    validReg := false.B
    addrReg := 0.U
    io.casAddressOut.valid := true.B
    io.casAddressOut.bits := io.addressIn
  }.elsewhen(io.validIn) {
    // Pass the address along the ring so it can reach another PE.
    validReg := true.B
    addrReg := io.addressIn
  }.otherwise {
    validReg := false.B
    addrReg := 0.U
  }

  io.addressOut := addrReg
  io.validOut := validReg
}
