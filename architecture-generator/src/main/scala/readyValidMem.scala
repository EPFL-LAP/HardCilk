package hardcilk.util

import chisel3._
import chisel3.util._
import os.truncate

import chext.axi4

import axi4._
import axi4.Ops._
import axi4.lite.components.RegisterBlock

class readValidMemIO(taskWidth: Int, addrWidth: Int, readFlag: Boolean, writeFlag: Boolean, varBurst: Boolean) extends Bundle {
  
  val read = if (readFlag) Some(new Bundle {
    val address  = Flipped(Decoupled(UInt(addrWidth.W))) 
    val data     = Decoupled(UInt(taskWidth.W)) 
  }) else None

  val write = if(writeFlag) Some(new Bundle {
    val address  = Flipped(Decoupled(UInt(addrWidth.W))) 
    val data     = Flipped(Decoupled(UInt(taskWidth.W)))
  }) else None

  val writeBurst = if(varBurst) Some(new Bundle {
      val len = Input(UInt(4.W))
      val last = Input(UInt(1.W))
  }) else None

  val readBurst = if(varBurst) Some(new Bundle {
      val len = Input(UInt(4.W))
  }) else None


}

class readyValidMem(taskWidth: Int, addrWidth: Int, read:Boolean=true, write:Boolean=true, strbCntrl:Boolean=false, burstLength: Int=0, varBurst: Boolean=false) extends Module {
  

  // Require at least one of read or write must be true
  require(read || write)
  require(taskWidth > 0 && taskWidth <= 1024)
  assert(burstLength <= 15)

  val axiFullCfg = axi4.Config(
    wAddr = addrWidth,
    wData = taskWidth,
    lite = false
  )

  val io = IO(new readValidMemIO(taskWidth, addrWidth, read, write, varBurst))
  val writeDataDone = RegInit(0.U(2.W))

  val writeHandshakeDetector = RegInit(false.B)


  val axi = IO(axi4.Master(axiFullCfg))
  private val axiData = axi.asFull

  private def connectZeros[T <: Data](bits: T) = {
    bits := 0.U(bits.getWidth.W).asTypeOf(bits)
  }


  // Connects the ignored signals in the axi protocol to zero
  connectZeros(axiData.aw.bits) 
  connectZeros(axiData.ar.bits)
  connectZeros(axiData.w.bits)

  


  /*
  when(axiData.w.valid && axiData.w.ready){
    writeHandshakeDetector := true.B
  }.elsewhen(axiData.b.valid && writeHandshakeDetector){
    writeHandshakeDetector := false.B
  }*/


  axiData.w.valid := false.B
  if(write){

    if(varBurst) {

      when(io.write.get.data.valid && axiData.w.ready && ~writeHandshakeDetector){
        
        axiData.w.valid := true.B
        when(io.writeBurst.get.last === 1.U){
          writeHandshakeDetector := true.B
        }

      }.elsewhen(axiData.b.valid){

        writeHandshakeDetector := false.B

      }

      io.write.get.data.ready    := axiData.w.ready && ~writeHandshakeDetector

    } else {

      when(writeDataDone === 0.U && axiData.w.ready){
        writeDataDone := 1.U
      }.elsewhen(writeDataDone === 1.U && axiData.b.valid){
        writeDataDone := 0.U
      }

      when(io.write.get.data.valid && axiData.w.ready && ~writeHandshakeDetector){
          axiData.w.valid := true.B
          writeHandshakeDetector := true.B
      }.elsewhen(axiData.b.valid){
          writeHandshakeDetector := false.B
      }

      io.write.get.data.ready    := ((writeDataDone === 1.U) && axiData.b.valid) // axiData.w.ready //

    }


    axiData.aw.valid       := io.write.get.address.valid
    axiData.aw.bits.addr   := io.write.get.address.bits

    if(varBurst){
      axiData.aw.bits.len    := io.writeBurst.get.len
    } else {
      axiData.aw.bits.len    := burstLength.U // 0.U                      // Only single burst
    }
    

    axiData.aw.bits.size   := log2Ceil(taskWidth/8).U  // Encoding for the number of bytes in the burst from the specification
    axiData.aw.bits.burst  := 1.U                      // Encoding for the type of burst from the specification

    //axiData.w.valid        := io.write.get.data.valid //(io.write.get.data.valid && ~writeHandshakeDetector)
    axiData.w.bits.data    := io.write.get.data.bits
    axiData.w.bits.strb    := (-1).S(axiFullCfg.wStrobe.W).asUInt 
    
    if(varBurst){
      axiData.w.bits.last    := io.writeBurst.get.last
    } else {
      axiData.w.bits.last    := true.B
    }
    
      
    axiData.b.ready        := true.B

    
    io.write.get.address.ready := axiData.aw.ready
    
  } else {
    axiData.aw.valid       := false.B
    axiData.w.valid        := false.B
    axiData.b.ready        := false.B
  }

  
  if(read){
    axiData.ar.valid          := io.read.get.address.valid
    axiData.ar.bits.addr      := io.read.get.address.bits
    io.read.get.address.ready := axiData.ar.ready

    if(varBurst){
      axiData.ar.bits.len       := io.readBurst.get.len
    } else {
      axiData.ar.bits.len       := burstLength.U // 0.U                      // Only single burst
    }
    

    axiData.ar.bits.size      := log2Ceil(taskWidth/8).U  // Encoding for the number of bytes in the burst from the specification
    axiData.ar.bits.id        := 0.U
    axiData.ar.bits.burst     := 1.U                      // Encoding for the type of burst from the specification
    
    axiData.ar.bits.prot      := 0.U
    axiData.r.ready           := io.read.get.data.ready
    
    io.read.get.data.valid    := axiData.r.valid
    io.read.get.data.bits     := axiData.r.bits.data
  } else {
    axiData.ar.valid      := false.B
    axiData.r.ready       := false.B
  } 
}