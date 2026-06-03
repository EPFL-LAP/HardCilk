package chext.util

import chisel3._
import chisel3.util._

class Counter(maxExclusive: Int) extends Module {
  val io = IO(new Bundle {
    val incEn = Input(Bool())
    val decEn = Input(Bool())

    val empty = Output(Bool())
    val full = Output(Bool())
  })

  val wCounter = log2Up(maxExclusive)
  private val rCounter = RegInit(0.U(wCounter.W))

  when(io.incEn && io.decEn) {}
    .elsewhen(io.incEn) {
      rCounter := rCounter + 1.U
    }
    .elsewhen(io.decEn) {
      rCounter := rCounter - 1.U
    }

  io.empty := rCounter === 0.U
  io.full := rCounter === (maxExclusive - 1).U

  def zero = io.empty
  def notZero = !io.empty

  def full = io.full
  def notFull = !io.full

  def noInc() = io.incEn := false.B
  def inc() = io.incEn := true.B

  def noDec() = io.decEn := false.B
  def dec() = io.decEn := true.B
}

private object Counter extends App {
  import chisel3.stage._

  emitVerilog(new Counter(64), Array("--target-dir", "output/"))
}
