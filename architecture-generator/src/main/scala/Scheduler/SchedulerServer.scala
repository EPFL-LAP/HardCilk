package Scheduler

import chisel3._
import chisel3.util._
import Util._

import chext.amba.axi4
import axi4.Ops._
import axi4.lite.components.RegisterBlock

class SchedulerServerIO(
    taskWidth: Int,
    regBlock: RegisterBlock,
    sysAddressWidth: Int,
    peCount: Int,
    enableGlobalStart: Boolean = false,
    // Width of the HBM ring's AXI data channels. Defaults to the task width, in
    // which case one beat is one task and this bundle is unchanged.
    portWidth: Int = 0
) extends Bundle {
  private val ringPortWidth = if (portWidth > 0) portWidth else taskWidth
  val connNetwork = Flipped(new SchedulerNetworkClientIO(taskWidth))
  val axi_mgmt = axi4.lite.Slave(regBlock.cfgAxi)
  val read_address = DecoupledIO(UInt(sysAddressWidth.W))
  val read_data = Flipped(DecoupledIO(UInt(ringPortWidth.W)))
  val read_burst_len = Output(UInt(4.W))
  val write_address = DecoupledIO(UInt(sysAddressWidth.W))
  val write_data = DecoupledIO(UInt(ringPortWidth.W))
  val write_burst_len = Output(UInt(4.W))
  val write_last = Output(UInt(1.W))
  val write_idle = Input(Bool())

  val ntwDataUnitOccupancy = Input(Bool())
  val ntwReqArriving = Input(Bool())

  val globalRun = if (enableGlobalStart) Some(Input(Bool())) else None
  val paused = Output(Bool())

  val congested = Output(Bool())
  val lengths_of_hardware_queues = Vec(peCount, Input(UInt(8.W)))
  val serveRemote = Output(
    Bool()
  ) // A signal from the VSS to the RemoteTaskServer
  val getTasksFromRemote = Output(
    Bool()
  ) // A signal from the VSS to the RemoteTaskServer
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
    nBeats: Int,
    ringWindowSize: Int = 0,
    enableGlobalStart: Boolean = false,
    // Width of the HBM ring's AXI data channels. 0 (the default) means "same as
    // the task width"
    ringPortWidth: Int = 0
) extends Module {

  require(contentionThreshold + contentionDelta <= (peCount + vasCount))
  require(contentionThreshold - contentionDelta >= 0)
  require(nBeats <= 16)

  private val portWidth = if (ringPortWidth > 0) ringPortWidth else taskWidth
  require(
    taskWidth % portWidth == 0,
    s"taskWidth $taskWidth must be a whole number of $portWidth-bit ring beats"
  )
  private val beatsPerTask = taskWidth / portWidth
  require(
    isPow2(beatsPerTask),
    s"beats per task ($beatsPerTask) must be a power of two"
  )
  require(
    nBeats % beatsPerTask == 0,
    s"a $nBeats-beat burst must hold a whole number of $beatsPerTask-beat tasks"
  )
  private val multiBeatTask = beatsPerTask > 1
  private val beatShift = log2Ceil(beatsPerTask)
  private val beatIdxWidth = if (multiBeatTask) beatShift else 1
  // Burst size in TASKS. With one beat per task this is nBeats, i.e. unchanged.
  private val tasksPerBurst = nBeats / beatsPerTask
  private val contentionRingWindowSize =
    if (ringWindowSize > 0) ringWindowSize else peCount + vasCount + 1
  require(contentionRingWindowSize > 0)
  private val localQueueDepth = tasksPerBurst * 8
  private val readAheadLowWatermark = tasksPerBurst * 7
  private val maxOutstandingReadBursts = 8
  private val readCountWidth = log2Ceil(maxOutstandingReadBursts + 1) + 1
  private val readTaskCountWidth =
    log2Ceil(localQueueDepth + tasksPerBurst + 1) + 1

  val regBlock = new RegisterBlock(wAddr = 6, wData = 64, wMask = 6)
  val io = IO(
    new SchedulerServerIO(
      taskWidth,
      regBlock,
      sysAddressWidth,
      peCount,
      enableGlobalStart,
      portWidth
    )
  )

  io.axi_mgmt.suggestName("S_AXI_MGMT")

  io.axi_mgmt :=> regBlock.s_axil

  private val rAddr = RegInit(0.U(64.W))
  private val rPause = RegInit(0.U(64.W))
  private val maxLength = RegInit(0.U(64.W))
  private val currLen = RegInit(0.U(64.W))
  private val contentionCounterWidth =
    log2Ceil(contentionRingWindowSize + 1) + 2
  private val contentionCounter = RegInit(0.S(contentionCounterWidth.W))
  private val contentionThresh =
    RegInit(contentionThreshold.S(contentionCounterWidth.W))
  private val networkCongested = RegInit(false.B)
  private val delta = RegInit(contentionDelta.S(contentionCounterWidth.W))
  private val contentionWindow =
    RegInit(VecInit(Seq.fill(contentionRingWindowSize)(0.S(2.W))))
  private val contentionWindowPtrWidth =
    if (contentionRingWindowSize <= 1) 1 else log2Ceil(contentionRingWindowSize)
  private val contentionWindowPtr =
    RegInit(0.U(contentionWindowPtrWidth.W))
  private val fifoTailReg = RegInit(0.U(64.W)) // Push at tail
  private val fifoHeadReg = RegInit(0.U(64.W)) // Pop at head
  private val addrShift = RegInit((log2Ceil(taskWidth / 8)).U)
  private val taskQueueBuffer = Module(
    new Queue(UInt(taskWidth.W), localQueueDepth)
  )
  private val splitPushPending = RegInit(false.B)
  private val queuesUtil = RegInit(0.U(64.W))
  private val enableMfpgaSteal = RegInit(0.U(64.W))
  private val burstTasksUInt = tasksPerBurst.U(5.W)
  private val localQueueCapacity = localQueueDepth.U(readTaskCountWidth.W)
  private val ringTaskStealDebt = RegInit(0.U(64.W))

  private val outstandingReads = RegInit(0.U(readCountWidth.W))
  private val inflightReadTasks = RegInit(0.U(readTaskCountWidth.W))
  private val readBurstLens = Module(
    new Queue(UInt(5.W), maxOutstandingReadBursts)
  )
  private val returnBeatsLeft = RegInit(0.U(5.W))
  private val stealReqInjectedThisCycle = WireDefault(false.B)

  private def capBurstAtFifoEnd(requestedBeats: UInt, ptr: UInt): UInt = {
    val slotsToEnd = maxLength - ptr
    val afterFifoCap =
      Mux(slotsToEnd < requestedBeats, slotsToEnd(4, 0), requestedBeats)

    val byteAddr = (ptr << addrShift) + rAddr
    val slotsToPageEnd = (4096.U(13.W) - byteAddr(11, 0)) >> addrShift
    Mux(slotsToPageEnd < afterFifoCap, slotsToPageEnd(4, 0), afterFifoCap)
  }

  private val pushRequestedTasks = Wire(UInt(6.W))
  pushRequestedTasks := burstTasksUInt
  when(splitPushPending) {
    pushRequestedTasks := taskQueueBuffer.io.count
    when(taskQueueBuffer.io.count >= burstTasksUInt) {
      pushRequestedTasks := burstTasksUInt
    }
  }

  private val pushBurstTasks =
    capBurstAtFifoEnd(pushRequestedTasks, fifoTailReg)

  // Reads are sized/placed against what is NOT already claimed by an in-flight
  // burst, so a second read issues from where the first left off.
  private val availToIssue = currLen - inflightReadTasks
  private val readIssuePtr = {
    val p = fifoHeadReg + inflightReadTasks
    Mux(p < maxLength, p, p - maxLength)
  }

  private val popRequestedTasks =
    Mux(availToIssue < tasksPerBurst.U, availToIssue(4, 0), burstTasksUInt)

  private val popBurstTasks = capBurstAtFifoEnd(popRequestedTasks, readIssuePtr)

  regBlock.base(0x00)
  regBlock.reg(
    rPause,
    read = true,
    write = true,
    desc = "Register to indicate whether the FSM is paused or not."
  )
  regBlock.reg(
    rAddr,
    read = true,
    write = true,
    desc = "Base address of virtual FIFO"
  )
  regBlock.reg(
    maxLength,
    read = true,
    write = true,
    desc = "Max length currently available for the FIFO"
  )
  regBlock.reg(
    fifoTailReg,
    read = true,
    write = true,
    desc = "The tail register of the FIFO"
  )
  regBlock.reg(
    fifoHeadReg,
    read = true,
    write = true,
    desc = "The head register of the FIFO"
  )
  // regBlock.reg(procInterrupt, read = true, write = true, desc = "A register that allows the processor to interrupt the FSM")
  regBlock.reg(
    enableMfpgaSteal,
    read = true,
    write = true,
    desc = "Enables mFPGA stealing"
  )
  regBlock.reg(
    currLen,
    read = true,
    write = true,
    desc = "A register that holds the current length of the FIFO"
  )
  regBlock.reg(
    queuesUtil,
    read = true,
    write = true,
    desc = "A register that holds the lengths of different hardware queues"
  )

  val interruptCondition = (enableMfpgaSteal(63) =/= 0.U)

  // queuesUtils register is only done for debugging small number of PEs to check the utilization of local BRAM queues per PE
  if (peCount <= 8) {
    val newQueuesUtil = Wire(UInt(64.W))
    newQueuesUtil := io.lengths_of_hardware_queues.reduceLeft(Cat(_, _))
    queuesUtil := newQueuesUtil
  }

  // Logic to decide whether to serve or get tasks from remote FPGAs
  when(networkCongested || currLen > 16.U) {
    io.serveRemote := true.B && maxLength =/= 0.U && !rPause && currLen > 16.U && enableMfpgaSteal(
      0
    ) =/= 0.U
    io.getTasksFromRemote := false.B
  }.otherwise {
    io.serveRemote := false.B
    io.getTasksFromRemote := true.B && maxLength =/= 0.U && !rPause && enableMfpgaSteal(
      0
    ) =/= 0.U
  }

  io.paused := rPause
  io.congested := networkCongested

  val contentionSample = WireDefault(0.S(2.W))

  if (ignoreRequestSignals) {
    when(io.ntwDataUnitOccupancy) {
      contentionSample := 1.S
    }.otherwise {
      contentionSample := -1.S
    }
  } else {
    when(!io.ntwReqArriving && io.ntwDataUnitOccupancy) {
      contentionSample := 1.S
    }.elsewhen(io.ntwReqArriving && !io.ntwDataUnitOccupancy) {
      contentionSample := -1.S
    }
  }

  val expiredContentionSample = contentionWindow(contentionWindowPtr)
  val nextContentionCounter =
    contentionCounter + contentionSample - expiredContentionSample

  contentionWindow(contentionWindowPtr) := contentionSample
  contentionWindowPtr := Mux(
    contentionWindowPtr === (contentionRingWindowSize - 1).U,
    0.U,
    contentionWindowPtr + 1.U
  )
  contentionCounter := nextContentionCounter

  when(nextContentionCounter >= (contentionThresh + delta)) {
    networkCongested := true.B
  }.elsewhen(nextContentionCounter < (contentionThresh - delta)) {
    networkCongested := false.B
  }.otherwise {
    networkCongested := networkCongested
  }

  val writingToHBM = RegInit(false.B)
  val writeBeatsLeft = RegInit(0.U(5.W))
  val datapathEnabled = Wire(Bool())
  // Issue-ahead: keep roughly seven bursts claimed, with an eight-burst local
  // buffer to absorb the next read burst.
  val claimedReadTasks = taskQueueBuffer.io.count +& inflightReadTasks
  val readRoomForBurst = claimedReadTasks +& popBurstTasks <= localQueueCapacity
  val belowReadAheadWatermark = claimedReadTasks < readAheadLowWatermark.U
  val readCanIssue =
    outstandingReads < maxOutstandingReadBursts.U &&
      !writingToHBM &&
      !networkCongested &&
      datapathEnabled &&
      io.write_idle &&
      maxLength =/= 0.U &&
      availToIssue =/= 0.U &&
      popBurstTasks =/= 0.U &&
      belowReadAheadWatermark &&
      readRoomForBurst &&
      readBurstLens.io.enq.ready

  io.read_address.valid := readCanIssue
  io.read_address.bits := (readIssuePtr << addrShift) + rAddr
  io.read_burst_len := ((popBurstTasks << beatShift) - 1.U)(3, 0)

  val readArFire = io.read_address.fire
  readBurstLens.io.enq.valid := readArFire
  // The return counter runs on beats, so record the burst in beats.
  readBurstLens.io.enq.bits := (popBurstTasks << beatShift)(4, 0)

  val startingReturnedBurst = returnBeatsLeft === 0.U
  val currentReturnBeats =
    Mux(startingReturnedBurst, readBurstLens.io.deq.bits, returnBeatsLeft)
  val readLastBeat = io.read_data.fire && currentReturnBeats === 1.U

  readBurstLens.io.deq.ready := io.read_data.fire && startingReturnedBurst

  // outstandingReads: +1 per issued AR, -1 per completed returned burst.
  when(readArFire && !readLastBeat) {
    outstandingReads := outstandingReads + 1.U
  }.elsewhen(!readArFire && readLastBeat) {
    outstandingReads := outstandingReads - 1.U
  }

  private val rxBeatIdx =
    if (multiBeatTask) Some(RegInit(0.U(beatIdxWidth.W))) else None
  private val rxHold =
    if (multiBeatTask) Some(Reg(UInt((taskWidth - portWidth).W))) else None
  private val rxLastBeat =
    rxBeatIdx.map(_ === (beatsPerTask - 1).U).getOrElse(true.B)
  private val rxTask =
    rxHold.map(h => Cat(io.read_data.bits, h)).getOrElse(io.read_data.bits)

  if (multiBeatTask) {
    when(io.read_data.fire) {
      rxHold.get := Cat(io.read_data.bits, rxHold.get)(taskWidth - 1, portWidth)
      rxBeatIdx.get := Mux(rxLastBeat, 0.U, rxBeatIdx.get + 1.U)
    }
  }

  // A task is retired from the ring only once its last beat has landed.
  val readTaskComplete = io.read_data.fire && rxLastBeat

  // inflightReadTasks: += the issued burst, -= each fully returned task.
  inflightReadTasks := inflightReadTasks +
    Mux(readArFire, popBurstTasks, 0.U) - Mux(readTaskComplete, 1.U, 0.U)

  when(io.read_data.fire) {
    when(readLastBeat) {
      returnBeatsLeft := 0.U
    }.otherwise {
      returnBeatsLeft := currentReturnBeats - 1.U
    }
  }

  val canTrackReturnedBeat =
    returnBeatsLeft =/= 0.U || readBurstLens.io.deq.valid
  // Only the last beat of a task claims the enqueue port; the earlier beats of a
  // multi-beat task pass straight into rxHold and leave the port free.
  val readDataEnq = canTrackReturnedBeat && io.read_data.valid && rxLastBeat
  // Absorbing may not eat the buffer space an in-flight read burst has already claimed.
  //
  // The read path reserves it (claimedReadTasks = count + inflightReadTasks gates readCanIssue) and
  // the absorb path must honour the same reservation, because the two run at different times: a
  // burst is issued while UNcongested, congestion hits before it returns, and absorbed ring tasks
  // would otherwise fill the buffer to the brim. The returning beats then have nowhere to land, so
  // outstandingReads never falls to zero -- and writeCanIssue waits on exactly that, so the spill
  // that would drain the buffer can never start.
  val roomBeyondInflightReads =
    taskQueueBuffer.io.count +& inflightReadTasks < localQueueCapacity
  val availableTaskEnq =
    datapathEnabled && networkCongested && !readDataEnq && roomBeyondInflightReads &&
      io.connNetwork.data.availableTask.valid

  taskQueueBuffer.io.enq.valid := readDataEnq || availableTaskEnq
  taskQueueBuffer.io.enq.bits := Mux(
    readDataEnq,
    rxTask,
    io.connNetwork.data.availableTask.bits
  )
  io.read_data.ready :=
    canTrackReturnedBeat && (!rxLastBeat || taskQueueBuffer.io.enq.ready)
  io.connNetwork.data.availableTask.ready :=
    datapathEnabled && networkCongested && !readDataEnq && roomBeyondInflightReads &&
      taskQueueBuffer.io.enq.ready
  val availableTaskFire =
    io.connNetwork.data.availableTask.valid && io.connNetwork.data.availableTask.ready

  when(readTaskComplete) {
    currLen := currLen - 1.U
    when(fifoHeadReg < maxLength - 1.U) {
      fifoHeadReg := fifoHeadReg + 1.U
    }.otherwise {
      fifoHeadReg := 0.U
    }
  }

  val stealCredits = RegInit(0.U(log2Ceil(localQueueDepth + 1).W))
  val canOutputTask =
    datapathEnabled && !networkCongested && !writingToHBM && taskQueueBuffer.io.deq.valid && stealCredits =/= 0.U
  val qOutFire = canOutputTask && io.connNetwork.data.qOutTask.ready
  val canConsumeStealCredit =
    datapathEnabled &&
      !networkCongested &&
      !writingToHBM &&
      taskQueueBuffer.io.count > stealCredits

  io.connNetwork.ctrl.serveStealReq.valid := canConsumeStealCredit
  val stealCreditFire =
    io.connNetwork.ctrl.serveStealReq.valid && io.connNetwork.ctrl.serveStealReq.ready

  io.connNetwork.data.qOutTask.valid := canOutputTask
  io.connNetwork.data.qOutTask.bits := taskQueueBuffer.io.deq.bits

  when(stealCreditFire && !qOutFire) {
    stealCredits := stealCredits + 1.U
  }.elsewhen(!stealCreditFire && qOutFire) {
    stealCredits := stealCredits - 1.U
  }

  val writeCanIssue =
    datapathEnabled &&
      networkCongested &&
      outstandingReads === 0.U &&
      !writingToHBM &&
      io.write_idle &&
      maxLength =/= 0.U &&
      pushBurstTasks =/= 0.U &&
      (taskQueueBuffer.io.count >= burstTasksUInt ||
        (splitPushPending && taskQueueBuffer.io.count =/= 0.U))

  io.write_address.valid := writeCanIssue
  io.write_address.bits := (fifoTailReg << addrShift) + rAddr
  io.write_burst_len := ((pushBurstTasks << beatShift) - 1.U)(3, 0)

  when(io.write_address.fire) {
    writingToHBM := true.B
    writeBeatsLeft := (pushBurstTasks << beatShift)(4, 0)
    splitPushPending := pushBurstTasks < pushRequestedTasks
  }

  private val txBeatIdx =
    if (multiBeatTask) Some(RegInit(0.U(beatIdxWidth.W))) else None
  private val txLastBeat =
    txBeatIdx.map(_ === (beatsPerTask - 1).U).getOrElse(true.B)
  private val txBeat = txBeatIdx
    .map(idx =>
      VecInit.tabulate(beatsPerTask)(i =>
        taskQueueBuffer.io.deq.bits(portWidth * (i + 1) - 1, portWidth * i)
      )(idx)
    )
    .getOrElse(taskQueueBuffer.io.deq.bits)

  io.write_data.valid := writingToHBM && taskQueueBuffer.io.deq.valid
  io.write_data.bits := txBeat
  io.write_last := writingToHBM && writeBeatsLeft === 1.U

  val writeDataFire = io.write_data.fire
  val writeTaskComplete = writeDataFire && txLastBeat

  if (multiBeatTask) {
    when(writeDataFire) {
      txBeatIdx.get := Mux(txLastBeat, 0.U, txBeatIdx.get + 1.U)
    }
  }

  taskQueueBuffer.io.deq.ready := Mux(
    writingToHBM,
    io.write_data.ready && txLastBeat,
    qOutFire
  )

  // The ring pointers move per TASK ...
  when(writeTaskComplete) {
    currLen := currLen + 1.U
    when(fifoTailReg < maxLength - 1.U) {
      fifoTailReg := fifoTailReg + 1.U
    }.otherwise {
      fifoTailReg := 0.U
    }
  }

  // ... and the burst counter per BEAT.
  when(writeDataFire) {
    when(writeBeatsLeft === 1.U) {
      writingToHBM := false.B
      writeBeatsLeft := 0.U
    }.otherwise {
      writeBeatsLeft := writeBeatsLeft - 1.U
    }
  }

  io.connNetwork.ctrl.stealReq.valid := datapathEnabled && (ringTaskStealDebt =/= 0.U || availableTaskFire)
  val stealReqFire =
    io.connNetwork.ctrl.stealReq.valid && io.connNetwork.ctrl.stealReq.ready
  stealReqInjectedThisCycle := stealReqFire

  when(availableTaskFire && !stealReqFire) {
    ringTaskStealDebt := ringTaskStealDebt + 1.U
  }.elsewhen(!availableTaskFire && stealReqFire) {
    ringTaskStealDebt := ringTaskStealDebt - 1.U
  }

  val resizeNeeded =
    maxLength =/= 0.U &&
      currLen + taskQueueBuffer.io.count + burstTasksUInt > maxLength
  val quiesceForPause = RegInit(false.B)
  val pauseRequested = quiesceForPause || interruptCondition
  val pauseDrained = outstandingReads === 0.U && !writingToHBM && io.write_idle

  datapathEnabled :=
    (if (enableGlobalStart)
       rPause === 0.U && !pauseRequested && io.globalRun.get
     else
       rPause === 0.U && !pauseRequested)

  when(resizeNeeded || interruptCondition) {
    quiesceForPause := true.B
  }

  when(rPause =/= 0.U) {
    quiesceForPause := false.B
  }

  when(pauseRequested && pauseDrained) {
    rPause := "hFFFFFFFFFFFFFFFF".U
    quiesceForPause := false.B
  }

  when(rPause === 0.U && !resizeNeeded && !interruptCondition) {
    quiesceForPause := false.B
  }

  when(regBlock.rdReq) {
    regBlock.rdOk()
  }

  when(regBlock.wrReq) {
    regBlock.wrOk()
  }

}
