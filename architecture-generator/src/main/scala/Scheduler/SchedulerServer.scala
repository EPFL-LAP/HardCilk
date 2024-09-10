package Scheduler

import chisel3._
import chisel3.util._
import Util._
import chisel3.ChiselEnum

import chext.amba.axi4
import axi4.Ops._
import axi4.lite.components.RegisterBlock

class SchedulerServerIO(taskWidth: Int, regBlock: RegisterBlock, sysAddressWidth: Int) extends Bundle {
  val connNetwork = Flipped(new SchedulerNetworkClientIO(taskWidth))
  val axi_mgmt = axi4.lite.Slave(regBlock.cfgAxi)
  val read_address = DecoupledIO(UInt(sysAddressWidth.W))
  val read_data = Flipped(DecoupledIO(UInt(taskWidth.W)))
  val read_burst_len = Output(UInt(4.W))
  val write_address = DecoupledIO(UInt(sysAddressWidth.W))
  val write_data = DecoupledIO(UInt(taskWidth.W))
  val write_burst_len = Output(UInt(4.W))
  val write_last = Output(UInt(1.W))
  val ntwDataUnitOccupancy = Input(Bool())
}

// N.B: For correct execution
// contentionThreshold + contentionDelta <= peCount
// contentionThreshold - contentionDelta >= 0

class SchedulerServer(
    taskWidth: Int,
    contentionThreshold: Int,
    peCount: Int,
    contentionDelta: Int,
    vasCount: Int,
    sysAddressWidth: Int,
    ignoreRequestSignals: Boolean,
    nBeats: Int = 1
) extends Module {

  require(contentionThreshold + contentionDelta <= (peCount + vasCount))
  require(contentionThreshold - contentionDelta >= 0)

  // States
  object state extends ChiselEnum {
    val init = Value(0.U)
    val takeInTask = Value(2.U)
    val pushTaskMem = Value(3.U)
    val pushTaskMemAddress = Value(4.U)
    val popTaskMem = Value(5.U)
    val popTaskMemAddress = Value(6.U)
    val giveAwayTask = Value(7.U)
    val serveStealRequests = Value(8.U)
    val extendFIFO = Value(9.U)
    val processInterruptState = Value(10.U)
  }

  val regBlock = new RegisterBlock(wAddr = 6, wData = 64, wMask = 6)
  val io = IO(new SchedulerServerIO(taskWidth, regBlock, sysAddressWidth))

  io.axi_mgmt.suggestName("S_AXI_MGMT")

  io.axi_mgmt :=> regBlock.s_axil

  private val rAddr = RegInit(0.U(64.W))
  private val rPause = RegInit(0.U(64.W))
  private val procInterrupt = RegInit(0.U(64.W))
  private val maxLength = RegInit(0.U(64.W))
  private val stateReg = RegInit(state.init)
  private val currLen = Wire(UInt(64.W))
  private val contentionCounter = RegInit(0.U(64.W))
  private val contentionThresh = RegInit(contentionThreshold.U(64.W))
  private val networkCongested = RegInit(false.B)
  private val delta = RegInit((contentionDelta).U(32.W))
  private val fifoTailReg = RegInit(0.U(64.W)) // Push at tail
  private val fifoHeadReg = RegInit(0.U(64.W)) // Pop at head
  private val popOrPush = RegInit(true.B) // was pop is true, was push is false
  private val addrShift = RegInit((log2Ceil(taskWidth / 8)).U)
  private val taskQueueBuffer = Module(new Queue(UInt(), nBeats))
  private val memDataCounter = RegInit(0.U(5.W))

  regBlock.base(0x00)
  regBlock.reg(rPause, read = true, write = true, desc = "Register to indicate whether the FSM is paused or not.")
  regBlock.reg(rAddr, read = true, write = true, desc = "Base address of virtual FIFO")
  regBlock.reg(maxLength, read = true, write = true, desc = "Max length currently available for the FIFO")
  regBlock.reg(fifoTailReg, read = true, write = true, desc = "The tail register of the FIFO")
  regBlock.reg(fifoHeadReg, read = true, write = true, desc = "The head register of the FIFO")
  regBlock.reg(procInterrupt, read = true, write = true, desc = "A register that allows the processor to interrupt the FSM")

  // Contention Counter FSM
  if (ignoreRequestSignals) {
    when(
      io.ntwDataUnitOccupancy
        && contentionCounter =/= (peCount + vasCount).U
    ) {
      contentionCounter := contentionCounter + 1.U
    }.elsewhen(
      contentionCounter =/= 0.U
        && !io.ntwDataUnitOccupancy
    ) {
      contentionCounter := contentionCounter - 1.U
    }.otherwise {
      contentionCounter := contentionCounter
    }
  } else {
    when(
      !io.connNetwork.ctrl.serveStealReq.ready &&
        io.ntwDataUnitOccupancy
        && contentionCounter =/= (peCount + vasCount).U
    ) {
      contentionCounter := contentionCounter + 1.U
    }.elsewhen(
      io.connNetwork.ctrl.serveStealReq.ready &&
        contentionCounter =/= 0.U
        && !io.ntwDataUnitOccupancy
    ) {
      contentionCounter := contentionCounter - 1.U
    }.otherwise {
      contentionCounter := contentionCounter
    }
  }

  when(contentionCounter >= (contentionThresh + delta)) {
    networkCongested := true.B
  }.elsewhen(contentionCounter < (contentionThresh - delta)) {
    networkCongested := false.B
  }.otherwise {
    networkCongested := networkCongested
  }

  // transition of FSM
  when(stateReg === state.init) {

    when(procInterrupt =/= 0.U) {
      stateReg := state.processInterruptState
      rPause := "hFFFFFFFFFFFFFFFF".U
    }.elsewhen((currLen === maxLength && networkCongested) || maxLength < (nBeats.U + currLen)) {

      stateReg := state.extendFIFO
      rPause := "hFFFFFFFFFFFFFFFF".U

    }.elsewhen(networkCongested && taskQueueBuffer.io.count === nBeats.U) {

      stateReg := state.pushTaskMemAddress

    }.elsewhen(networkCongested) {

      stateReg := state.takeInTask

    }.elsewhen(!networkCongested && currLen =/= 0.U && taskQueueBuffer.io.count === 0.U) {

      stateReg := state.popTaskMemAddress

    }.elsewhen(!networkCongested && taskQueueBuffer.io.count =/= 0.U) {

      stateReg := state.giveAwayTask

    }

  }.elsewhen(stateReg === state.takeInTask) {

    when(taskQueueBuffer.io.count === (nBeats - 1).U && io.connNetwork.data.availableTask.valid) {

      stateReg := state.pushTaskMemAddress

    }.elsewhen(io.connNetwork.data.availableTask.valid && networkCongested) {

      stateReg := state.takeInTask

    }.elsewhen(!networkCongested) {

      stateReg := state.init

    }

  }.elsewhen(stateReg === state.pushTaskMemAddress) {

    when(io.write_address.ready) {
      stateReg := state.pushTaskMem
      memDataCounter := nBeats.U
    }

  }.elsewhen(stateReg === state.pushTaskMem) {

    when(io.write_data.ready && memDataCounter === 1.U) {
      stateReg := state.init
      popOrPush := false.B

      when(fifoTailReg < maxLength - 1.U) {
        fifoTailReg := fifoTailReg + 1.U
      }.otherwise {
        fifoTailReg := 0.U
      }

    }.elsewhen(io.write_data.ready) {
      memDataCounter := memDataCounter - 1.U
      when(fifoTailReg < maxLength - 1.U) {
        fifoTailReg := fifoTailReg + 1.U
      }.otherwise {
        fifoTailReg := 0.U
      }
    }

  }.elsewhen(stateReg === state.popTaskMemAddress) {

    when(io.read_address.ready) {
      stateReg := state.popTaskMem
      memDataCounter := Mux(currLen < nBeats.U, currLen, nBeats.U)
    }

  }.elsewhen(stateReg === state.popTaskMem) {

    when(io.read_data.valid && memDataCounter === 1.U) {
      stateReg := state.serveStealRequests
      popOrPush := true.B

      when(fifoHeadReg < maxLength - 1.U) {
        fifoHeadReg := fifoHeadReg + 1.U
      }.otherwise {
        fifoHeadReg := 0.U
      }
    }.elsewhen(io.read_data.valid) {
      memDataCounter := memDataCounter - 1.U
      when(fifoHeadReg < maxLength - 1.U) {
        fifoHeadReg := fifoHeadReg + 1.U
      }.otherwise {
        fifoHeadReg := 0.U
      }
    }

  }.elsewhen(stateReg === state.giveAwayTask) {

    when(io.connNetwork.data.qOutTask.ready) {
      stateReg := state.init
    }.elsewhen(networkCongested) {
      stateReg := state.init
    }.otherwise {
      stateReg := state.giveAwayTask
    }

  }.elsewhen(stateReg === state.serveStealRequests) {

    when(io.connNetwork.ctrl.serveStealReq.ready) {
      stateReg := state.giveAwayTask
    }.elsewhen(networkCongested) {
      stateReg := state.init
    }.elsewhen(procInterrupt =/= 0.U) {
      stateReg := state.init
    }

  }.elsewhen(stateReg === state.extendFIFO) {

    when(rPause === false.B) {

      stateReg := state.init

    }.otherwise {

      stateReg := state.extendFIFO

    }

  }.elsewhen(stateReg === state.processInterruptState) {

    when(rPause === false.B) {
      stateReg := state.init
    }.otherwise {
      stateReg := state.processInterruptState
    }

  }

  io.connNetwork.data.qOutTask.bits := taskQueueBuffer.io.deq.bits
  io.write_data.bits := taskQueueBuffer.io.deq.bits

  // Queue Outputs
  io.read_address.valid := false.B
  io.read_address.bits := 0.U
  io.read_data.ready := false.B
  io.write_address.valid := false.B
  io.write_address.bits := 0.U
  io.write_data.valid := false.B

  // Data Network Outputs
  io.connNetwork.data.availableTask.ready := false.B
  io.connNetwork.data.qOutTask.valid := false.B

  // Ctrl Network Outputs
  io.connNetwork.ctrl.stealReq.valid := false.B
  io.connNetwork.ctrl.serveStealReq.valid := false.B

  // Internal Queue Buffer IO
  taskQueueBuffer.io.enq.valid := false.B
  taskQueueBuffer.io.enq.bits := 0.U

  taskQueueBuffer.io.deq.ready := false.B

  io.write_burst_len := 0.U
  io.write_last := false.B
  io.read_burst_len := 0.U

  // Output of the FSM
  when(stateReg === state.takeInTask) {

    taskQueueBuffer.io.enq.bits := io.connNetwork.data.availableTask.bits // Update internal register value with taken task
    io.connNetwork.data.availableTask.ready := taskQueueBuffer.io.enq.ready
    taskQueueBuffer.io.enq.valid := io.connNetwork.data.availableTask.valid

  }.elsewhen(stateReg === state.pushTaskMemAddress) {

    io.write_address.valid := true.B
    io.write_address.bits := (fifoTailReg << addrShift) + rAddr
    io.write_burst_len := (nBeats - 1).U // equivalent to 16 values

  }.elsewhen(stateReg === state.pushTaskMem) {

    io.write_data.valid := true.B
    taskQueueBuffer.io.deq.ready := io.write_data.ready
    when(memDataCounter === 1.U) {
      io.write_last := true.B
    }

  }.elsewhen(stateReg === state.popTaskMemAddress) {

    io.read_address.valid := true.B
    io.read_address.bits := (fifoHeadReg << addrShift) + rAddr
    io.read_burst_len := Mux(currLen < nBeats.U, currLen - 1.U, (nBeats - 1).U)

  }.elsewhen(stateReg === state.popTaskMem) {

    io.read_data.ready := true.B
    taskQueueBuffer.io.enq.bits := io.read_data.bits
    taskQueueBuffer.io.enq.valid := io.read_data.valid

  }.elsewhen(stateReg === state.giveAwayTask) {

    io.connNetwork.data.qOutTask.valid := true.B
    taskQueueBuffer.io.deq.ready := io.connNetwork.data.qOutTask.ready

  }.elsewhen(stateReg === state.serveStealRequests) {

    io.connNetwork.ctrl.serveStealReq.valid := true.B // Digest a steal request

  }

  // currLen calculation
  val lengthHistroy = RegInit(0.U(64.W))

  when(fifoTailReg > fifoHeadReg) {

    currLen := fifoTailReg - fifoHeadReg
    lengthHistroy := currLen

  }.elsewhen(fifoTailReg < fifoHeadReg) {

    currLen := maxLength - fifoHeadReg + fifoTailReg
    lengthHistroy := currLen

  }.otherwise {

    lengthHistroy := lengthHistroy

    when(popOrPush) {

      currLen := 0.U

    }.otherwise {

      currLen := lengthHistroy + 1.U

    }

  }

  // Reply to axi management operations.
  when(regBlock.rdReq) {
    regBlock.rdOk()
  }

  when(regBlock.wrReq) {
    regBlock.wrOk()
  }
}

// object virtualStealServer extends App {
//     (new chisel3.stage.ChiselStage).emitVerilog(
//         {
//           val module = (new virtualStealServer(256, 4, 8, 2, 1, 64, false))
//           module

//         },
//         Array(
//           "--emission-options=disableMemRandomization,disableRegisterRandomization",
//           f"--target-dir=output"
//         )
//       )
// }
