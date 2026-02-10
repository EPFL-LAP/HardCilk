package AXIHelpers

import chisel3._
import chisel3.util._
import chext.amba.axi4s
import chext.amba.axi4s.Casts._
import chext.amba.axi4s.FullChannel

class Axis512To1024(
  val inCfg: axi4s.Config = axi4s.Config(wData = 512, wDest = 4),
  val outCfg: axi4s.Config = axi4s.Config(wData = 1024, wDest = 4)
) extends Module {

  require(inCfg.wData == 512)
  require(outCfg.wData == 1024)
  require(!inCfg.onlyRV && !outCfg.onlyRV)

  val io = IO(new Bundle {
    val in  = axi4s.Slave(inCfg)
    val out = axi4s.Master(outCfg)
  })

  val inS  = io.in.asFull
  val outS = io.out.asFull

  // ------------------------------------------------------------
  // State machine
  // ------------------------------------------------------------

  val sIdle :: sBeat1 :: sOut :: Nil = Enum(3)
  val state = RegInit(sIdle)

  // Output buffer
  val buf = Reg(new FullChannel(outCfg))

  // Default assignments
  inS.ready  := false.B
  outS.valid := false.B
  outS.bits  := buf

  // ------------------------------------------------------------
  // FSM
  // ------------------------------------------------------------

  switch(state) {

    // -------------------------
    // First 512-bit beat
    // -------------------------
    is(sIdle) {
      inS.ready := true.B

      when(inS.fire) {
        buf.data   := inS.bits.data
        buf.keep   := inS.bits.keep
        buf.strobe := inS.bits.strobe
        buf.last   := false.B

        buf.dest.foreach(_ := inS.bits.dest.get)
        buf.id.foreach(_   := inS.bits.id.get)
        buf.user.foreach(_ := inS.bits.user.get)

        state := sBeat1
      }
    }

    // -------------------------
    // Second 512-bit beat
    // -------------------------
    is(sBeat1) {
      inS.ready := true.B

      when(inS.fire) {
        buf.data   := buf.data | (inS.bits.data   << 512)
        buf.keep   := buf.keep | (inS.bits.keep   << 64)
        buf.strobe := buf.strobe | (inS.bits.strobe << 64)
        buf.last   := true.B   // output is always a single beat

        // Optional protocol checks
        assert(inS.bits.last, "Expected TLAST on second beat")

        state := sOut
      }
    }

    // -------------------------
    // Output
    // -------------------------
    is(sOut) {
      outS.valid := true.B

      when(outS.fire) {
        state := sIdle
      }
    }
  }
}