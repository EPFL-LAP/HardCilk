package Allocator

import chisel3._
import chisel3.util._

// VCAS injection unit of the allocator address ring.
//
// This is a registered shift-register stage (like SchedulerNetworkDataUnit): it
// carries an (address, valid) pair one hop around the closed ring each cycle.
// When an empty slot passes and the local VCAS has a free address to hand out,
// the address is injected into the ring; otherwise a valid slot is passed along.
class AllocatorServerNetworkUnitIO(addrWidth: Int) extends Bundle {
  // Ring shift-register ports (non-elastic, like the scheduler network).
  val addressIn = Input(UInt(addrWidth.W))
  val validIn = Input(Bool())
  val addressOut = Output(UInt(addrWidth.W))
  val validOut = Output(Bool())

  // Free addresses supplied by the local VCAS server.
  val addressIn1 = Flipped(DecoupledIO(UInt(addrWidth.W)))
}

class AllocatorServerNetworkUnit(addrWidth: Int) extends Module {
  val io = IO(new AllocatorServerNetworkUnitIO(addrWidth))

  val addrReg = RegInit(0.U(addrWidth.W))
  val validReg = RegInit(false.B)

  io.addressIn1.ready := false.B

  when(io.validIn) {
    // Pass the passing address along the ring (occupied slots have priority so
    // in-flight addresses are never dropped).
    validReg := true.B
    addrReg := io.addressIn
  }.elsewhen(io.addressIn1.valid) {
    // Empty slot: inject a fresh free address from the local VCAS.
    validReg := true.B
    addrReg := io.addressIn1.bits
    io.addressIn1.ready := true.B
  }.otherwise {
    validReg := false.B
    addrReg := 0.U
  }

  io.addressOut := addrReg
  io.validOut := validReg
}
