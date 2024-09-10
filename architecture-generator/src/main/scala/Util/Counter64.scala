package Util

import chisel3._
import chisel3.util._

// Create a hardware class for a 64-bit counter that takes as input an n number of signals, increments an internal counter by 1 for each signal that is high per cycle.
class Counter64(n: Int) extends Module {
  val io = IO(new Bundle {
    val signals = Input(Vec(n, Bool()))
    val counter = Output(UInt(64.W))
  })

  val counter = RegInit(0.U(64.W))

  // Increment the counter by 1 for each signal that is high
  counter := counter + PopCount(io.signals)

  io.counter := counter
}
