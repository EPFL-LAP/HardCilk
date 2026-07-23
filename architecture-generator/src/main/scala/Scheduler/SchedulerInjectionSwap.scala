package Scheduler

import chisel3._
import chisel3.util._

// Injection-priority swap for a co-located spawner/scheduler pair.
//
// On the PE-local ring a spawner (VAS) sits immediately upstream of its
// scheduler (VSS): VAS -> VSS -> PE (SchedulerLocalRingLayout).  When only one
// of the two injects, the passive neighbour lets the task flow through into the
// PE.  But when BOTH inject the same cycle, the VSS task -- being PE-adjacent --
// reaches the PE first and bumps the spawner's locally-generated task to PE i+1,
// breaking the cache locality of a task whose next access is closest to its
// previous one.
//
// This module carries only the two DATA injections and, on any cycle where both
// would fire (both producers valid AND both ring slots ready), swaps their
// destinations so the spawner's task takes the PE-adjacent slot and reaches the
// PE first.  Because it acts only when both sides fire, the swap is a pure
// relabel: every ready is already high, so nothing is blocked, delayed, or
// dropped, and the control ring with its steal accounting needs no involvement.
// Any other cycle is a straight pass-through.
class SchedulerInjectionSwapIO(taskWidth: Int) extends Bundle {
  // Producers: the two co-located injectors, named for their position in the
  // forward data flow.  upstream = spawner (VAS), downstream = scheduler (VSS).
  val upstreamIn = Flipped(Decoupled(UInt(taskWidth.W)))
  val downstreamIn = Flipped(Decoupled(UInt(taskWidth.W)))
  // Consumers: the two ring slots those injectors feed, in the same position
  // order.  downstream is the PE-adjacent slot.
  val upstreamOut = Decoupled(UInt(taskWidth.W))
  val downstreamOut = Decoupled(UInt(taskWidth.W))
}

class SchedulerInjectionSwap(taskWidth: Int) extends Module {
  val io = IO(new SchedulerInjectionSwapIO(taskWidth))

  // Both injectors would land this cycle: both hold a task and both slots are
  // free.  Only then do we swap.  The slot readys are position-based
  // (~validIn), independent of which producer drives the slot, so swapping the
  // drivers cannot disturb this condition -- it is combinationally stable.
  val swap =
    io.upstreamIn.valid && io.downstreamIn.valid &&
      io.upstreamOut.ready && io.downstreamOut.ready

  // On a swap the upstream (spawner) task takes the downstream, PE-adjacent slot
  // so it reaches the PE first; the downstream (scheduler) task takes the
  // upstream slot.  Otherwise everything passes straight through.
  io.downstreamOut.valid := Mux(swap, io.upstreamIn.valid, io.downstreamIn.valid)
  io.downstreamOut.bits := Mux(swap, io.upstreamIn.bits, io.downstreamIn.bits)
  io.upstreamOut.valid := Mux(swap, io.downstreamIn.valid, io.upstreamIn.valid)
  io.upstreamOut.bits := Mux(swap, io.downstreamIn.bits, io.upstreamIn.bits)

  // Each producer's ready is driven from its OWN slot, never crossed -- crossing
  // it through `swap` would make ready depend on valid and close a combinational
  // cycle back through the injecting clients (whose qOutTask.valid depends on
  // qOutTask.ready).  This is exact, not an approximation: a swap can only occur
  // when BOTH slots are ready, so on that cycle upstreamOut.ready ==
  // downstreamOut.ready == true and the crossed and own-slot readys coincide.
  io.upstreamIn.ready := io.upstreamOut.ready
  io.downstreamIn.ready := io.downstreamOut.ready
}

object SchedulerInjectionSwapEmitter extends App {
  emitVerilog(new SchedulerInjectionSwap(256), Array("--target-dir", "output"))
}
