package Allocator

import chisel3._
import chisel3.util.Valid

trait AllocatorModule extends Module {
  val io_export: ClosureAllocatorPEIO
  val io_internal: ClosureAllocatorAxiIO
  val io_paused: Bool

  /** Freed continuation addresses coming back from the argument notifier's
    * resolution branches. Absent unless recycling is enabled, and never
    * available on the legacy allocator.
    */
  def io_recycle: Option[Vec[Valid[UInt]]] = None
}
