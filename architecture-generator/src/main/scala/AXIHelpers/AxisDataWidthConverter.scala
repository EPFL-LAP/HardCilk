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

  // Define the states for the module state machine
  object state extends ChiselEnum {
    val bufferData = Value(0.U)
    val writeDataToOutput = Value(1.U)
  }

  // Make sure the data widths is power of two
  assert(isPow2(dataWidthIn) && isPow2(dataWidthOut))
  val upScaleFactor = dataWidthOut / dataWidthIn

  // Define the IO
  val io = IO(new AxisDataWidthConverterIO(dataWidthIn, dataWidthOut))

  // Take the data in LS bits and register each packet until the upScaleFactor is reached
  val buffer = RegInit(0.U(dataWidthOut.W))
  val readCounter = RegInit(0.U)
  val stateReg = RegInit(state.bufferData)

  when(stateReg === state.bufferData) {
    when(io.dataIn.TVALID && readCounter < (upScaleFactor - 1).U) {
      buffer := buffer | (io.dataIn.TDATA << (readCounter * dataWidthIn.U))
      readCounter := readCounter + 1.U
    }.elsewhen(io.dataIn.TVALID && readCounter === (upScaleFactor - 1).U) {
      buffer := buffer | (io.dataIn.TDATA << (readCounter * dataWidthIn.U))
      stateReg := state.writeDataToOutput
    }
  }.elsewhen(stateReg === state.writeDataToOutput) {
    when(io.dataOut.TREADY) {
      readCounter := 0.U
      stateReg := state.bufferData
    }
  }

  io.dataIn.TREADY := false.B
  io.dataOut.TVALID := false.B
  io.dataOut.TDATA := buffer

  when(stateReg === state.bufferData) {
    io.dataIn.TREADY := true.B
  }.elsewhen(stateReg === state.writeDataToOutput) {
    io.dataOut.TVALID := true.B
  }
}

class AxisDownscaler(dataWidthIn: Int, dataWidthOut: Int) extends Module {

  // Define the states for the module state machine
  object state extends ChiselEnum {
    val bufferData = Value(0.U)
    val writeDataToOutput = Value(1.U)
  }

  // Make sure the data widths is power of two
  assert(isPow2(dataWidthIn) && isPow2(dataWidthOut))
  val downScaleFactor = dataWidthIn / dataWidthOut

  // Define the IO
  val io = IO(new AxisDataWidthConverterIO(dataWidthIn, dataWidthOut))

  // Take a beat from the input and push each packet to the slave until the downScaleFactor is reached
  val buffer = RegInit(0.U(dataWidthIn.W))
  val writeCounter = RegInit(0.U)
  val stateReg = RegInit(state.bufferData)

  when(stateReg === state.bufferData) {
    when(io.dataIn.TVALID) {
      buffer := io.dataIn.TDATA
      writeCounter := 0.U
    }
  }.elsewhen(stateReg === state.writeDataToOutput) {
    when(io.dataOut.TREADY && writeCounter < (downScaleFactor - 1).U) {
      io.dataOut.TDATA := buffer >> (writeCounter * dataWidthOut.U)
      writeCounter := writeCounter + 1.U
    }.elsewhen(io.dataOut.TREADY && writeCounter === (downScaleFactor - 1).U) {
      stateReg := state.bufferData
    }
  }

  io.dataIn.TREADY := false.B
  io.dataOut.TVALID := false.B
  io.dataOut.TDATA := 0.U

  when(stateReg === state.bufferData) {
    io.dataIn.TREADY := true.B
  }.elsewhen(stateReg === state.writeDataToOutput) {
    io.dataOut.TVALID := true.B
  }
}

class AxisDataWidthConverter(dataWidthIn: Int, dataWidthOut: Int) extends Module {

  assert(isPow2(dataWidthIn) && isPow2(dataWidthOut))

  val io = IO(new AxisDataWidthConverterIO(dataWidthIn, dataWidthOut))
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

}
