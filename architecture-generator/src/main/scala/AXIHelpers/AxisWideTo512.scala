package AXIHelpers

import chisel3._
import chisel3.util._

import chext.amba.axi4s
import chext.amba.axi4s.Casts._
import chext.amba.axi4s.FullChannel

class AxisWideTo512(
  val inCfg: axi4s.Config = axi4s.Config(wData = 528, wDest = 4),
  val outCfg: axi4s.Config = axi4s.Config(wData = 512, wDest = 4)
) extends Module {
  require(outCfg.wData == 512)
  require(inCfg.wData >= 512)
  require(inCfg.wData % 8 == 0)
  
  val io = IO(new Bundle {
    val in  = axi4s.Slave(inCfg)
    val out = axi4s.Master(outCfg)
  })
  
  val inS  = io.in.asFull
  val outS = io.out.asFull
  
  // Calculate how many output beats per input
  val outputBeatsPerInput = (inCfg.wData + 511) / 512  // Ceiling division
  val sliceW = log2Ceil(outputBeatsPerInput + 1)
  
  val sliceIdx  = RegInit(0.U(sliceW.W))
  val haveBuf   = RegInit(false.B)
  val buf = Reg(new FullChannel(inCfg))
  
  // Input
  inS.ready := !haveBuf
  
  when(inS.fire) {
    buf      := inS.bits
    sliceIdx := 0.U
    haveBuf  := true.B
  }
  
  // Dynamic slice with bit offset
  def sliceBits(x: UInt, bitOffset: UInt, width: Int): UInt = {
    require(width > 0)
    val shifted = x >> bitOffset
    shifted(width-1, 0)
  }
  
  val outBytes = 64
  val bitOffset = sliceIdx * 512.U
  val byteOffset = sliceIdx * 64.U
  
  outS.valid := haveBuf
  outS.bits.data   := sliceBits(buf.data, bitOffset, 512)
  outS.bits.keep   := sliceBits(buf.keep, byteOffset, outBytes)
  outS.bits.strobe := sliceBits(buf.strobe, byteOffset, outBytes)
  
  val lastSlice = sliceIdx === (outputBeatsPerInput - 1).U
  val nextByteOffset = (sliceIdx + 1.U) << 6.U  // Multiply by 64 using shift
  val keepRemaining = buf.keep >> nextByteOffset
  val noMoreData = keepRemaining === 0.U
  
  outS.bits.last := buf.last && (lastSlice || noMoreData)
  outS.bits.dest.foreach(_ := buf.dest.get)
  outS.bits.id.foreach(_   := buf.id.get)
  outS.bits.user.foreach(_ := Mux(sliceIdx === 0.U, buf.user.get, 0.U))
  
  when(outS.fire) {
    when(lastSlice || (buf.last && noMoreData)) {
      haveBuf := false.B
    }.otherwise {
      sliceIdx := sliceIdx + 1.U
    }
  }
}

object AxisWideTo512Emitter extends App {
  emitVerilog(new AxisWideTo512(),
    Array(
      "--target-dir=output/AxisWideTo512/"
    )
  )
}