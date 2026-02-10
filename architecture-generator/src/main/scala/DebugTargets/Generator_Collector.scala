/***
 * This file for creating a generator collector module for testing mfpga AlveoLink example with scala generated code.
 * ***/

package DebugTargets

import chisel3._
import chext.amba.axi4
import axi4.lite.components.RegisterBlock
import axi4.Ops._
import chext.amba.axi4s
import chisel3.util.Cat
import axi4s.Casts._


class Generator_Collector extends Module {

  val axi4sCfg = axi4s.Config(wData = 512, wDest = 4)
  val regBlock = new RegisterBlock(wAddr = 8, wData = 64, wMask = 8)



  val fpgaCountInputReg = RegInit(0.U(64.W))
  val fpgaIndexInputReg = RegInit(0.U(64.W))
  val numberOfTasksToMove = RegInit(0.U(64.W))

  val stream_value_0 = RegInit(0.U(64.W))
  val stream_value_1 = RegInit(0.U(64.W))
  val stream_value_2 = RegInit(0.U(64.W))
  val stream_value_3 = RegInit(0.U(64.W))
  val stream_value_4 = RegInit(0.U(64.W))
  val stream_value_5 = RegInit(0.U(64.W))
  val stream_value_6 = RegInit(0.U(64.W))
  val stream_value_7 = RegInit(0.U(64.W))

  val writeStream = RegInit(0.U(64.W))

  regBlock.base(0x10)
  regBlock.reg(fpgaCountInputReg, read = true, write = true, desc = "Register To Identify The Number of FPGAs in The System")
  regBlock.reg(fpgaIndexInputReg, read = true, write = true, desc = "Register To Identify The Index of The FPGA in The System")
  regBlock.reg(numberOfTasksToMove, read = true, write = true, desc = "Number of tasks to move per request")
  regBlock.reg(stream_value_0, read = true, write = true, desc = "Stream Value 0")
  regBlock.reg(stream_value_1, read = true, write = true, desc = "Stream Value 1")
  regBlock.reg(stream_value_2, read = true, write = true, desc = "Stream Value 2")
  regBlock.reg(stream_value_3, read = true, write = true, desc = "Stream Value 3")
  regBlock.reg(stream_value_4, read = true, write = true, desc = "Stream Value 4")
  regBlock.reg(stream_value_5, read = true, write = true, desc = "Stream Value 5")
  regBlock.reg(stream_value_6, read = true, write = true, desc = "Stream Value 6")
  regBlock.reg(stream_value_7, read = true, write = true, desc = "Stream Value 7")
  regBlock.reg(writeStream, read = true, write = true, desc = "Write Stream Command")


  when(regBlock.rdReq) {
    regBlock.rdOk()
  }
  when(regBlock.wrReq) {
    regBlock.wrOk()
  }

  val index_4bit = fpgaIndexInputReg(3, 0)
  val count_4bit = fpgaCountInputReg(3, 0)

  val s_axi_cfg =  IO(axi4.Slave(regBlock.cfgAxi)).suggestName("s_axi_cfg") 
    

  s_axi_cfg :=> axi4.lite.MasterBuffer(regBlock.s_axil, axi4.BufferConfig.all(2))


  val master_stream_IO = IO(axi4s.Master(axi4sCfg)).suggestName(f"m_axis_mFPGA")

  val slave_stream_IO = IO(axi4s.Slave(axi4sCfg)).suggestName(f"s_axis_mFPGA")

  val master_stream_IO_buffered = chext.elastic.SinkBuffer(master_stream_IO.asFull, 4)
  val slave_stream_IO_buffered = chext.elastic.SourceBuffer(slave_stream_IO.asFull, 4)



  //master_stream_IO_buffered.bits.data := Cat(stream_value_0, stream_value_1, stream_value_2, stream_value_3, stream_value_4, stream_value_5, stream_value_6, stream_value_7)
  master_stream_IO_buffered.bits.data := Cat(stream_value_7, stream_value_6, stream_value_5, stream_value_4, stream_value_3, stream_value_2, stream_value_1, stream_value_0) // Compile this fix later
  


  slave_stream_IO_buffered.ready := (writeStream === 0.U) // Only accept data when writeStream is 0


  // An FSM to send a single packet if writeStream is set to 1 and then clear writeStream to 0
  when(writeStream === 1.U) {
    when(master_stream_IO_buffered.ready) {
      writeStream := 0.U
    }
    master_stream_IO_buffered.valid := true.B
    master_stream_IO_buffered.bits.keep := "hFFFFFFFFFFFFFFFF".U
    master_stream_IO_buffered.bits.strobe := "hFFFFFFFFFFFFFFFF".U
    master_stream_IO_buffered.bits.last := true.B
    // Destination is set to the FPGA index + 1 modulo the number of FPGAs
    val next_val = index_4bit + 1.U
    master_stream_IO_buffered.bits.dest.get := Mux(next_val >= count_4bit, 0.U, next_val)
  } .otherwise {
    master_stream_IO_buffered.valid := false.B
    master_stream_IO_buffered.bits.keep := 0.U
    master_stream_IO_buffered.bits.strobe := 0.U
    master_stream_IO_buffered.bits.last := false.B
    master_stream_IO_buffered.bits.dest.get := 0.U
  }

  // Incoming data on the slave stream shall be written to the stream_value registers
  when(slave_stream_IO_buffered.valid && slave_stream_IO_buffered.ready) {
    stream_value_0 := slave_stream_IO_buffered.bits.data(63, 0)
    stream_value_1 := slave_stream_IO_buffered.bits.data(127, 64)
    stream_value_2 := slave_stream_IO_buffered.bits.data(191, 128)
    stream_value_3 := slave_stream_IO_buffered.bits.data(255, 192)
    stream_value_4 := slave_stream_IO_buffered.bits.data(319, 256)
    stream_value_5 := slave_stream_IO_buffered.bits.data(383, 320)
    stream_value_6 := slave_stream_IO_buffered.bits.data(447, 384)
    stream_value_7 := slave_stream_IO_buffered.bits.data(511, 448)
  }

}

// Create an object to generate the Verilog
object Generator_Collector_Emitter extends App {
  import _root_.circt.stage.ChiselStage

  ChiselStage.emitSystemVerilogFile(
    new Generator_Collector(),
    Array(
      "--target-dir=output/Generator_Collector/"
    ),
    Array("--disable-all-randomization")
  )
}
