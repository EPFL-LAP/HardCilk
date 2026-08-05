package Allocator

import chisel3._

trait AllocatorModule extends Module {
  val io_export: ClosureAllocatorPEIO
  val io_internal: ClosureAllocatorAxiIO
  val io_paused: Bool
}
