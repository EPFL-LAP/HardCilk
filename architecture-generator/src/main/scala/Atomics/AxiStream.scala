package Atomics

import chisel3._

class AxiStream(val w: Int) extends Bundle {
  val tdata = UInt(w.W)
  val tlast = Bool()
}
