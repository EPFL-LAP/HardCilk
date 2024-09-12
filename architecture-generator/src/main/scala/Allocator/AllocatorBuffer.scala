package Allocator

import chisel3._
import chisel3.util._

class AllocatorBufferIO(addrWidth: Int) extends Bundle {
  val addressIn = Flipped(DecoupledIO(UInt(addrWidth.W)))
  val addressOut = DecoupledIO(UInt(addrWidth.W))
}

class AllocatorBuffer(addrWidth: Int, queueDepth: Int) extends Module {
  val io = IO(new AllocatorBufferIO(addrWidth))
  val q = Module(new Queue(UInt(), queueDepth))

  q.io.enq <> io.addressIn
  io.addressOut <> q.io.deq
}
