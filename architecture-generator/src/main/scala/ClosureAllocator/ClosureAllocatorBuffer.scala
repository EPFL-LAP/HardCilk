package ClosureAllocator

import chisel3._
import chisel3.util._

class ClosureAllocatorBufferIO(addrWidth: Int) extends Bundle {
  val addressIn = Flipped(DecoupledIO(UInt(addrWidth.W)))
  val addressOut = DecoupledIO(UInt(addrWidth.W))
}

class ClosureAllocatorBuffer(addrWidth: Int, queueDepth: Int) extends Module {
  val io = IO(new ClosureAllocatorBufferIO(addrWidth))
  val q = Module(new Queue(UInt(), queueDepth))

  q.io.enq <> io.addressIn
  io.addressOut <> q.io.deq
}
