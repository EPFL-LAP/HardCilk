package AXIHelpers

import chisel3._
import chisel3.util._

import chext.amba.axi4s
import chext.amba.axi4s.FullChannel
import chext.amba.axi4s.Casts._


class Axis1024To512(
  val inCfg: axi4s.Config  = axi4s.Config(wData = 1024),
  val outCfg: axi4s.Config = axi4s.Config(wData = 512)
) extends Module {

  require(inCfg.wData == 1024)
  require(outCfg.wData == 512)

  val io = IO(new Bundle {
    val in  = axi4s.Slave(inCfg)
    val out = axi4s.Master(outCfg)
  })

  val inS  = io.in.asFull
  val outS = io.out.asFull

  // --------------------------------------------------------------------------
  // Buffer the single input beat
  // --------------------------------------------------------------------------

  val buf      = Reg(new FullChannel(inCfg))
  val haveBuf  = RegInit(false.B)
  val beatSel  = RegInit(0.U(1.W)) // 0 = lower, 1 = upper

  inS.ready := !haveBuf

  when(inS.fire) {
    buf     := inS.bits
    haveBuf := true.B
    beatSel := 0.U
  }

  // --------------------------------------------------------------------------
  // Static slicing (no dynamic shifts)
  // --------------------------------------------------------------------------

  val dataLo = buf.data(511,   0)
  val dataHi = buf.data(1023, 512)

  val keepLo = buf.keep(63,   0)
  val keepHi = buf.keep(127, 64)

  val strbLo = buf.strobe(63,   0)
  val strbHi = buf.strobe(127, 64)

  // --------------------------------------------------------------------------
  // Output
  // --------------------------------------------------------------------------

  outS.valid := haveBuf

  outS.bits.data   := Mux(beatSel === 0.U, dataLo, dataHi)
  outS.bits.keep   := Mux(beatSel === 0.U, keepLo, keepHi)
  outS.bits.strobe := Mux(beatSel === 0.U, strbLo, strbHi)

  outS.bits.last := beatSel === 1.U

  outS.bits.dest.foreach(_ := buf.dest.get)
  outS.bits.id.foreach(_   := buf.id.get)
  outS.bits.user.foreach(_ := buf.user.get)

  // --------------------------------------------------------------------------
  // Control
  // --------------------------------------------------------------------------

  when(outS.fire) {
    when(beatSel === 1.U) {
      haveBuf := false.B
    }.otherwise {
      beatSel := 1.U
    }
  }
}