package Allocator

import chisel3._
import chisel3.util._
import chisel3.ChiselEnum

import chext.amba.axi4
import axi4.lite.components.RegisterBlock

class AllocatorServerIO(dataWidth: Int, regBlock: RegisterBlock, sysAddressWidth: Int) extends Bundle {
  val dataOut = DecoupledIO(UInt(dataWidth.W))
  val axi_mgmt = axi4.lite.Slave(regBlock.cfgAxi)
  val read_address = DecoupledIO(UInt(sysAddressWidth.W))
  val read_data = Flipped(DecoupledIO(UInt(dataWidth.W)))
}

class AllocatorServer(dataWidth: Int, sysAddressWidth: Int, burstLength: Int) extends Module {

  assert(burstLength <= 15) // 15 is equivalent to 16 beats

  object state extends ChiselEnum {
    val init = Value(0.U)
    val provideReadAddress = Value(1.U)
    val readCont = Value(2.U)
    val giveData = Value(3.U)
    val extendFIFO = Value(4.U)
  }

  val regBlock = new RegisterBlock(wAddr = 6, wData = 64, wMask = 6)

  val io = IO(new AllocatorServerIO(dataWidth, regBlock, sysAddressWidth))

  io.axi_mgmt.suggestName("0_S_AXI_MGMT")
  regBlock.s_axil <> io.axi_mgmt

  private val rAddr = RegInit(0.U(64.W))
  private val rPause = RegInit(0.U(64.W))
  private val avaialbleSize = RegInit(0.U(64.W))
  private val stateReg = RegInit(state.init)
  private val addrShift = RegInit((log2Ceil(dataWidth / 8)).U)
  private val index = avaialbleSize - (burstLength + 1).U
  private val continuationsRegisters = RegInit(VecInit(Seq.fill(burstLength + 1)(0.U(sysAddressWidth.W))))
  private val burstCounter = RegInit(burstLength.U(log2Ceil(burstLength).W))

  regBlock.base(0x00)
  regBlock.reg(rPause, read = true, write = true, desc = "Register to indicate whether the FSM is paused or not.")
  regBlock.reg(rAddr, read = true, write = true, desc = "Base address of virtual continuation FIFO")
  regBlock.reg(avaialbleSize, read = true, write = true, desc = "Availble address FIFO size")

  when(stateReg === state.init) {
    when(avaialbleSize >= (burstLength + 1).U) {
      stateReg := state.provideReadAddress
    }.otherwise {
      stateReg := state.extendFIFO
      rPause := "hFFFFFFFFFFFFFFFF".U
    }
  }.elsewhen(stateReg === state.provideReadAddress) {
    when(io.read_address.ready) {
      stateReg := state.readCont
      burstCounter := burstLength.U
    }
  }.elsewhen(stateReg === state.readCont) {
    when(io.read_data.valid) {
      continuationsRegisters(burstCounter) := io.read_data.bits
      burstCounter := burstCounter - 1.U
      avaialbleSize := avaialbleSize - 1.U
    }

    when(burstCounter === 0.U && io.read_data.valid) {
      stateReg := state.giveData
      avaialbleSize := avaialbleSize - 1.U
      burstCounter := burstLength.U
    }

  }.elsewhen(stateReg === state.giveData) {
    when(io.dataOut.ready) {
      burstCounter := burstCounter - 1.U
    }
    when(burstCounter === 0.U && io.dataOut.ready) {
      stateReg := state.init
    }

  }.elsewhen(stateReg === state.extendFIFO) {
    when(rPause === false.B) {
      stateReg := state.init
    }
  }

  io.read_data.ready := false.B
  io.read_address.valid := false.B
  io.dataOut.valid := false.B

  when(stateReg === state.provideReadAddress) {
    io.read_address.valid := true.B
  }.elsewhen(stateReg === state.readCont) {
    io.read_data.ready := true.B
  }.elsewhen(stateReg === state.giveData) {
    io.dataOut.valid := true.B
  }

  io.read_address.bits := rAddr + (index << addrShift)
  io.dataOut.bits := continuationsRegisters(burstCounter)

  // Reply to axi management operations.
  when(regBlock.rdReq) {
    regBlock.rdOk()
  }
  when(regBlock.wrReq) {
    regBlock.wrOk()
  }
}
