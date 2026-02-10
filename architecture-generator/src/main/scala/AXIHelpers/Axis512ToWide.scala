package AXIHelpers
import chisel3._
import chisel3.util._
import chext.amba.axi4s
import chext.amba.axi4s.Casts._
import chext.amba.axi4s.FullChannel

class Axis512ToWide(
  val inCfg: axi4s.Config = axi4s.Config(wData = 512, wDest = 4),
  val outCfg: axi4s.Config = axi4s.Config(wData = 528, wDest = 4),
  val maxBeats: Int = 2  // Maximum number of input beats to accumulate
) extends Module {
  require(inCfg.wData == 512)
  require(outCfg.wData > 512)
  require(outCfg.wData % 8 == 0)
  require(!inCfg.onlyRV && !outCfg.onlyRV)
  require(maxBeats >= 1)
  require(maxBeats * 512 >= outCfg.wData, s"maxBeats ($maxBeats) too small for output width ${outCfg.wData}")
  
  val io = IO(new Bundle {
    val in  = axi4s.Slave(inCfg)
    val out = axi4s.Master(outCfg)
  })
  
  val inS  = io.in.asFull
  val outS = io.out.asFull
  
  val sliceW = log2Ceil(maxBeats + 1)
  
  val sliceIdx  = RegInit(0.U(sliceW.W))
  val haveBuf   = RegInit(false.B)
  
  // Accumulation buffer for wide output
  val buf = Reg(new FullChannel(outCfg))
  
  val outBytes = outCfg.wData / 8
  val inBytes = 64
  
  // -------------------------
  // Input - accept when we don't have a buffered output ready
  // -------------------------
  inS.ready := !haveBuf
  
  // Calculate byte/bit offset for current slice
  // Use fixed-width computation based on maxBeats
  // Limit shift amount BEFORE shifting to prevent oversized intermediate values
  val maxBitShift = log2Ceil(outCfg.wData)
  val maxByteShift = log2Ceil(outBytes)
  
  val currentBitOffset = WireDefault(0.U(maxBitShift.W))
  val currentByteOffset = WireDefault(0.U(maxByteShift.W))
  
  when(sliceIdx === 0.U) {
    currentBitOffset := 0.U
    currentByteOffset := 0.U
  }.elsewhen(sliceIdx === 1.U) {
    currentBitOffset := 512.U
    currentByteOffset := 64.U
  }.otherwise {
    // For maxBeats > 2, add more cases
    currentBitOffset := (sliceIdx * 512.U)(maxBitShift-1, 0)
    currentByteOffset := (sliceIdx * 64.U)(maxByteShift-1, 0)
  }
  
  when(inS.fire) {
    when(sliceIdx === 0.U) {
      // First beat - initialize buffer
      buf.data := inS.bits.data
      buf.keep := inS.bits.keep
      buf.strobe := inS.bits.strobe
      buf.last := inS.bits.last
      buf.dest.foreach(_ := inS.bits.dest.get)
      buf.id.foreach(_ := inS.bits.id.get)
      buf.user.foreach(_ := inS.bits.user.get)
    }.otherwise {
      // Subsequent beats - accumulate into buffer
      // Use fixed-width shift amounts to generate efficient hardware
      val shiftedData = (inS.bits.data.asUInt << currentBitOffset).asUInt
      val shiftedKeep = (inS.bits.keep.asUInt << currentByteOffset).asUInt
      val shiftedStrobe = (inS.bits.strobe.asUInt << currentByteOffset).asUInt
      
      buf.data := buf.data | shiftedData
      buf.keep := buf.keep | shiftedKeep
      buf.strobe := buf.strobe | shiftedStrobe
      
      // Update TLAST - packet ends if input has TLAST
      buf.last := inS.bits.last
    }
    
    // Advance slice index
    val isLastSlice = sliceIdx === (maxBeats - 1).U
    when(inS.bits.last || isLastSlice) {
      // Packet complete or buffer full - output next cycle
      haveBuf := true.B
      sliceIdx := 0.U
    }.otherwise {
      sliceIdx := sliceIdx + 1.U
    }
  }
  
  // -------------------------
  // Output
  // -------------------------
  outS.valid := haveBuf
  outS.bits := buf
  
  when(outS.fire) {
    haveBuf := false.B
  }
}

object Axis512ToWideEmitter extends App {
  emitVerilog(
    new Axis512ToWide(
      inCfg = axi4s.Config(wData = 512, wDest = 4),
      outCfg = axi4s.Config(wData = 528, wDest = 4),
      maxBeats = 2
    ),
    Array(
      "--target-dir=output/Axis512ToWide/"
    )
  )
}