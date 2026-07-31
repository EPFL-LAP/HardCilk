package AXIHelpers

import chisel3._
import chisel3.util._

import chext.amba.axi4s

class AxisDataWidthConverterIO(dataWidthIn: Int, dataWidthOut: Int) extends Bundle {

  implicit val axisCfgSlave: axi4s.Config = axi4s.Config(wData = dataWidthIn, onlyRV = true)
  implicit val axisCfgMaster: axi4s.Config = axi4s.Config(wData = dataWidthOut, onlyRV = true)

  val dataIn = axi4s.Slave(axisCfgSlave)
  val dataOut = axi4s.Master(axisCfgMaster)

}

class AxisUpscaler(dataWidthIn: Int, dataWidthOut: Int) extends Module {

  // Make sure the data widths is power of two
  assert((isPow2(dataWidthIn) && isPow2(dataWidthOut)) || dataWidthIn == dataWidthOut)
  val upScaleFactor = dataWidthOut / dataWidthIn

  // Define the IO
  val io = IO(new AxisDataWidthConverterIO(dataWidthIn, dataWidthOut))

  // Gather `upScaleFactor` beats, first beat in the least significant bits, then hand the whole
  // word over. The buffer is a Vec rather than an accumulated UInt so that a beat overwrites its
  // slot instead of being OR-ed into whatever the previous word left there.
  val buffer = Reg(Vec(upScaleFactor, UInt(dataWidthIn.W)))
  val readCounter = RegInit(0.U(log2Ceil(upScaleFactor + 1).W))
  val full = RegInit(false.B)

  io.dataIn.TREADY := !full
  io.dataOut.TVALID := full
  io.dataOut.TDATA := buffer.asUInt

  when(!full && io.dataIn.TVALID) {
    buffer(readCounter) := io.dataIn.TDATA
    readCounter := readCounter + 1.U

    when(readCounter === (upScaleFactor - 1).U) {
      readCounter := 0.U
      full := true.B
    }
  }

  when(full && io.dataOut.TREADY) {
    full := false.B
  }
}

class AxisDownscaler(dataWidthIn: Int, dataWidthOut: Int) extends Module {

  // Make sure the data widths is power of two
  assert(isPow2(dataWidthIn) && isPow2(dataWidthOut))
  val downScaleFactor = dataWidthIn / dataWidthOut

  // Define the IO
  val io = IO(new AxisDataWidthConverterIO(dataWidthIn, dataWidthOut))

  // Take a word from the input and push it out one beat at a time, least significant beat first,
  // which is the order AxisUpscaler gathers them in.
  val buffer = Reg(Vec(downScaleFactor, UInt(dataWidthOut.W)))
  val writeCounter = RegInit(0.U(log2Ceil(downScaleFactor + 1).W))
  val busy = RegInit(false.B)

  io.dataIn.TREADY := !busy
  io.dataOut.TVALID := busy
  io.dataOut.TDATA := buffer(writeCounter)

  when(!busy && io.dataIn.TVALID) {
    buffer := io.dataIn.TDATA.asTypeOf(buffer)
    writeCounter := 0.U
    busy := true.B
  }

  when(busy && io.dataOut.TREADY) {
    writeCounter := writeCounter + 1.U

    when(writeCounter === (downScaleFactor - 1).U) {
      writeCounter := 0.U
      busy := false.B
    }
  }
}

class AxisDataWidthConverter(dataWidthIn: Int, dataWidthOut: Int) extends Module {

  //assert((isPow2(dataWidthIn) && isPow2(dataWidthOut)) || dataWidthIn == dataWidthOut)
  
  val io = IO(new AxisDataWidthConverterIO(dataWidthIn, dataWidthOut))

  if((isPow2(dataWidthIn) && isPow2(dataWidthOut)) || dataWidthIn == dataWidthOut) {
    //println("Axis convertsion can be done");
    if (dataWidthIn < dataWidthOut) {
      val upScaler = Module(new AxisUpscaler(dataWidthIn, dataWidthOut))
      upScaler.io.dataIn <> io.dataIn
      io.dataOut <> upScaler.io.dataOut
    } else if (dataWidthIn > dataWidthOut) {
      val downScaler = Module(new AxisDownscaler(dataWidthIn, dataWidthOut))
      downScaler.io.dataIn <> io.dataIn
      io.dataOut <> downScaler.io.dataOut
    } else {
      io.dataOut.TDATA := io.dataIn.TDATA
      io.dataOut.TVALID := io.dataIn.TVALID
      io.dataIn.TREADY := io.dataOut.TREADY
    }
  } else if(dataWidthOut < dataWidthIn){
    println("Warning: Axis convertsion can be done with cutting data");
    io.dataOut.TDATA := io.dataIn.TDATA
    io.dataOut.TVALID := io.dataIn.TVALID
    io.dataIn.TREADY := io.dataOut.TREADY
  } else{
    println("Axis convertsion can't be done")
    throw new Exception("Data width conversion not supported")
  }



}
