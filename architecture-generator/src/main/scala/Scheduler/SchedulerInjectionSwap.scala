package Scheduler

import chisel3._
import chisel3.util._

class SchedulerInjectionSwapIO(taskWidth: Int) extends Bundle {

  val upstreamIn = Flipped(Decoupled(UInt(taskWidth.W)))
  val downstreamIn = Flipped(Decoupled(UInt(taskWidth.W)))

  val upstreamOut = Decoupled(UInt(taskWidth.W))
  val downstreamOut = Decoupled(UInt(taskWidth.W))
}

class SchedulerInjectionSwap(taskWidth: Int) extends Module {
  val io = IO(new SchedulerInjectionSwapIO(taskWidth))

  val swap =
    io.upstreamIn.valid && io.downstreamIn.valid &&
      io.upstreamOut.ready && io.downstreamOut.ready

  io.downstreamOut.valid := Mux(
    swap,
    io.upstreamIn.valid,
    io.downstreamIn.valid
  )
  io.downstreamOut.bits := Mux(swap, io.upstreamIn.bits, io.downstreamIn.bits)
  io.upstreamOut.valid := Mux(swap, io.downstreamIn.valid, io.upstreamIn.valid)
  io.upstreamOut.bits := Mux(swap, io.downstreamIn.bits, io.upstreamIn.bits)

  io.upstreamIn.ready := io.upstreamOut.ready
  io.downstreamIn.ready := io.downstreamOut.ready
}

object SchedulerInjectionSwapEmitter extends App {
  emitVerilog(new SchedulerInjectionSwap(256), Array("--target-dir", "output"))
}
