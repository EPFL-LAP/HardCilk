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
    enableGlobalStart: Boolean = false
) extends Bundle {
  val connNetwork = Flipped(new SchedulerNetworkClientIO(taskWidth))
  val axi_mgmt = axi4.lite.Slave(regBlock.cfgAxi)
  val read_address = DecoupledIO(UInt(sysAddressWidth.W))
  val read_data = Flipped(DecoupledIO(UInt(taskWidth.W)))
  val read_burst_len = Output(UInt(4.W))
  val write_address = DecoupledIO(UInt(sysAddressWidth.W))
  val write_data = DecoupledIO(UInt(taskWidth.W))
  val write_burst_len = Output(UInt(4.W))
  val write_last = Output(UInt(1.W))
  val write_idle = Input(Bool())
  val ntwDataUnitOccupancy = Input(Bool())
  // Kernel-global start broadcast (opt-in via enableGlobalStart). Driven by a single
  // host-writable register that fans out to EVERY scheduler server, so all servers
  // leave their initial pause on the SAME cycle instead of one-at-a-time as the host
  // clears each rPause over AXI-lite (which, under hw_emu's slow register path,
  // staggers PE start by ~800 cycles per server). Held 0 during init, pulsed to 1
  // once to release everyone. Absent (== always-run) when the feature is off, so the
  // generated RTL is byte-identical to the pre-feature design.
  val globalRun = if (enableGlobalStart) Some(Input(Bool())) else None
  val paused = Output(Bool())
  // Telemetry tap: mirrors the internal networkCongested register so the watcher
  // can show when this server has flipped into HBM-ring absorb/spill mode (see
  // the "sched_congested" status group wired in HardCilk.connectWatcher).
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
    enableGlobalStart: Boolean = false
) extends Module {

  require(contentionThreshold + contentionDelta <= (peCount + vasCount))
  require(contentionThreshold - contentionDelta >= 0)
  require(nBeats <= 16)
  private val contentionRingWindowSize =
    if (ringWindowSize > 0) ringWindowSize else peCount + vasCount + 1
  require(contentionRingWindowSize > 0)
  private val localQueueDepth = nBeats * 8
  private val readAheadLowWatermark = nBeats * 7
  private val maxOutstandingReadBursts = 8
  private val readCountWidth = log2Ceil(maxOutstandingReadBursts + 1) + 1
  private val readBeatCountWidth = log2Ceil(localQueueDepth + nBeats + 1) + 1

  val regBlock = new RegisterBlock(wAddr = 6, wData = 64, wMask = 6)
  val io = IO(
    new SchedulerServerIO(taskWidth, regBlock, sysAddressWidth, peCount, enableGlobalStart)
  )

  io.axi_mgmt.suggestName("S_AXI_MGMT")

  io.axi_mgmt :=> regBlock.s_axil

  private val rAddr = RegInit(0.U(64.W))
  private val rPause = RegInit(0.U(64.W))
  private val maxLength = RegInit(0.U(64.W))
  private val currLen = RegInit(0.U(64.W))
  private val contentionCounterWidth = log2Ceil(contentionRingWindowSize + 1) + 2
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
  private val nBeatsUInt = nBeats.U(5.W)
  private val localQueueCapacity = localQueueDepth.U(readBeatCountWidth.W)
  private val ringTaskStealDebt = RegInit(0.U(64.W))
  // Read bursts may be issued ahead until the local scheduler buffer reaches
  // the low watermark. inflightReadBeats = beats issued but not yet returned;
  // fifoHeadReg/currLen only advance as beats RETURN, so the issue pointer and
  // available-to-issue count are adjusted by inflightReadBeats.
  private val outstandingReads = RegInit(0.U(readCountWidth.W))
  private val inflightReadBeats = RegInit(0.U(readBeatCountWidth.W))
  private val readBurstLens = Module(new Queue(UInt(5.W), maxOutstandingReadBursts))
  private val returnBeatsLeft = RegInit(0.U(5.W))
  private val stealReqInjectedThisCycle = WireDefault(false.B)

  private def capBurstAtFifoEnd(requestedBeats: UInt, ptr: UInt): UInt = {
    val slotsToEnd = maxLength - ptr
    val afterFifoCap =
      Mux(slotsToEnd < requestedBeats, slotsToEnd(4, 0), requestedBeats)
    // AXI4 forbids an INCR burst from crossing a 4KB address boundary. Ring slots
    // are (taskWidth/8) B and a burst is up to nBeats long, so a burst that starts
    // within (nBeats-1) slots of a 4KB line would straddle it -> illegal burst ->
    // the HBM/smartconnect mishandles the post-boundary beats and reads/writes the
    // WRONG ring slots (stale/lost tasks; only shows up once bursts are long, i.e.
    // at large sizes). Cap at the next 4KB line too. byteAddr is the ABSOLUTE
    // device address (rAddr + ptr<<addrShift) so the boundary is in device space;
    // slotsToPageEnd is always >= 1 (== nBeats/page when ptr is page-aligned), so
    // the burst never collapses to length 0. The push split-continuation
    // (splitPushPending = burst < requested) carries the remainder; a capped pop
    // simply reads fewer this round and resumes from the now page-aligned head.
    val byteAddr = (ptr << addrShift) + rAddr
    val slotsToPageEnd = (4096.U(13.W) - byteAddr(11, 0)) >> addrShift
    Mux(slotsToPageEnd < afterFifoCap, slotsToPageEnd(4, 0), afterFifoCap)
  }

  private val pushRequestedBeats = Wire(UInt(6.W))
  pushRequestedBeats := nBeatsUInt
  when(splitPushPending) {
    pushRequestedBeats := taskQueueBuffer.io.count
    when(taskQueueBuffer.io.count >= nBeatsUInt) {
      pushRequestedBeats := nBeatsUInt
    }
  }

  private val pushBurstBeats =
    capBurstAtFifoEnd(pushRequestedBeats, fifoTailReg)

  // Reads are sized/placed against what is NOT already claimed by an in-flight
  // burst, so a second read issues from where the first left off.
  private val availToIssue = currLen - inflightReadBeats
  private val readIssuePtr = {
    val p = fifoHeadReg + inflightReadBeats
    Mux(p < maxLength, p, p - maxLength)
  }

  private val popRequestedBeats =
    Mux(availToIssue < nBeats.U, availToIssue(4, 0), nBeatsUInt)

  private val popBurstBeats = capBurstAtFifoEnd(popRequestedBeats, readIssuePtr)

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
    when(
      io.ntwDataUnitOccupancy
    ) {
      contentionSample := 1.S
    }.elsewhen(
      !io.ntwDataUnitOccupancy
    ) {
      contentionSample := -1.S
    }
  } else {
    val stealReqPressure =
      io.connNetwork.ctrl.serveStealReq.ready || stealReqInjectedThisCycle
    when(
      !stealReqPressure &&
        io.ntwDataUnitOccupancy
    ) {
      contentionSample := 1.S
    }.elsewhen(
      stealReqPressure &&
        !io.ntwDataUnitOccupancy
    ) {
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

  // ---------------------------------------------------------------------------
  // HBM RING PREFETCH: KEEP THE LOCAL TASK BUFFER WARM WHILE THE NETWORK IS
  // UNCONGESTED.  THIS IS A SMALL BURST ENGINE, NOT THE OLD GLOBAL FSM: IT ONLY
  // TRACKS WHETHER A READ BURST IS OUTSTANDING AND HOW MANY RETURN BEATS REMAIN.
  // ---------------------------------------------------------------------------
  val writingToHBM = RegInit(false.B)
  val writeBeatsLeft = RegInit(0.U(5.W))
  val datapathEnabled = Wire(Bool())
  // Issue-ahead: keep roughly seven bursts claimed, with an eight-burst local
  // buffer to absorb the next read burst.
  val claimedReadBeats = taskQueueBuffer.io.count +& inflightReadBeats
  val readRoomForBurst = claimedReadBeats +& popBurstBeats <= localQueueCapacity
  val belowReadAheadWatermark = claimedReadBeats < readAheadLowWatermark.U
  val readCanIssue =
    outstandingReads < maxOutstandingReadBursts.U &&
      !writingToHBM &&
      !networkCongested &&
      datapathEnabled &&
      io.write_idle &&
      maxLength =/= 0.U &&
      availToIssue =/= 0.U &&
      popBurstBeats =/= 0.U &&
      belowReadAheadWatermark &&
      readRoomForBurst &&
      readBurstLens.io.enq.ready

  io.read_address.valid := readCanIssue
  io.read_address.bits := (readIssuePtr << addrShift) + rAddr
  io.read_burst_len := (popBurstBeats - 1.U)(3, 0)

  val readArFire = io.read_address.fire
  readBurstLens.io.enq.valid := readArFire
  readBurstLens.io.enq.bits := popBurstBeats

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

  // inflightReadBeats: += the issued burst, -= each returned beat.
  inflightReadBeats := inflightReadBeats +
    Mux(readArFire, popBurstBeats, 0.U) - Mux(io.read_data.fire, 1.U, 0.U)

  when(io.read_data.fire) {
    when(readLastBeat) {
      returnBeatsLeft := 0.U
    }.otherwise {
      returnBeatsLeft := currentReturnBeats - 1.U
    }
  }

  // ---------------------------------------------------------------------------
  // LOCAL BUFFER ENQUEUE ARBITRATION: READ DATA HAS PRIORITY BECAUSE IT IS AN
  // IN-FLIGHT AXI CHANNEL.  STOLEN TASKS FROM THE NETWORK ARE ACCEPTED WHEN
  // CONGESTED AND THE SINGLE ENQUEUE PORT IS NOT BEING USED BY READ DATA.
  // ---------------------------------------------------------------------------
  val canTrackReturnedBeat = returnBeatsLeft =/= 0.U || readBurstLens.io.deq.valid
  val readDataEnq = canTrackReturnedBeat && io.read_data.valid
  val availableTaskEnq =
    datapathEnabled && networkCongested && !readDataEnq && io.connNetwork.data.availableTask.valid

  taskQueueBuffer.io.enq.valid := readDataEnq || availableTaskEnq
  taskQueueBuffer.io.enq.bits := Mux(
    readDataEnq,
    io.read_data.bits,
    io.connNetwork.data.availableTask.bits
  )
  io.read_data.ready := canTrackReturnedBeat && taskQueueBuffer.io.enq.ready
  io.connNetwork.data.availableTask.ready :=
    datapathEnabled && networkCongested && !readDataEnq && taskQueueBuffer.io.enq.ready
  val availableTaskFire =
    io.connNetwork.data.availableTask.valid && io.connNetwork.data.availableTask.ready

  when(io.read_data.fire) {
    currLen := currLen - 1.U
    when(fifoHeadReg < maxLength - 1.U) {
      fifoHeadReg := fifoHeadReg + 1.U
    }.otherwise {
      fifoHeadReg := 0.U
    }
  }

  // ---------------------------------------------------------------------------
  // STEAL-CREDIT ACCOUNTING AND TASK OUTPUT: CONTROL CREDITS ARE COUNTED
  // INDEPENDENTLY FROM DATA-NETWORK BACKPRESSURE.  TASKS ONLY LEAVE THE LOCAL
  // BUFFER WHEN BOTH A STORED CREDIT AND qOutTask.ready ARE PRESENT.
  // ---------------------------------------------------------------------------
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

  // ---------------------------------------------------------------------------
  // HBM RING SPILL: WHEN THE NETWORK IS CONGESTED, DRAIN FULL LOCAL BURSTS BACK
  // TO THE RING.  FIFO-END AND 4KB CAPS ARE PRESERVED BY LATCHING THE CAPPED
  // BURST LENGTH AT AW FIRE AND USING IT FOR THE W CHANNEL.
  // ---------------------------------------------------------------------------
  val writeCanIssue =
    datapathEnabled &&
      networkCongested &&
      outstandingReads === 0.U &&
      !writingToHBM &&
      io.write_idle &&
      maxLength =/= 0.U &&
      pushBurstBeats =/= 0.U &&
      (taskQueueBuffer.io.count >= nBeatsUInt ||
        (splitPushPending && taskQueueBuffer.io.count =/= 0.U))

  io.write_address.valid := writeCanIssue
  io.write_address.bits := (fifoTailReg << addrShift) + rAddr
  io.write_burst_len := (pushBurstBeats - 1.U)(3, 0)

  when(io.write_address.fire) {
    writingToHBM := true.B
    writeBeatsLeft := pushBurstBeats
    splitPushPending := pushBurstBeats < pushRequestedBeats
  }

  io.write_data.valid := writingToHBM && taskQueueBuffer.io.deq.valid
  io.write_data.bits := taskQueueBuffer.io.deq.bits
  io.write_last := writingToHBM && writeBeatsLeft === 1.U

  val writeDataFire = io.write_data.fire

  taskQueueBuffer.io.deq.ready := Mux(
    writingToHBM,
    io.write_data.ready,
    qOutFire
  )

  when(writeDataFire) {
    currLen := currLen + 1.U
    when(fifoTailReg < maxLength - 1.U) {
      fifoTailReg := fifoTailReg + 1.U
    }.otherwise {
      fifoTailReg := 0.U
    }
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

  // ---------------------------------------------------------------------------
  // SOFTWARE PAUSE / RESIZE QUIESCE: REQUEST A PAUSE WHEN THE RING IS TOO SMALL
  // FOR ANOTHER LOCAL BURST, THEN STOP STARTING NEW WORK AND WAIT FOR IN-FLIGHT
  // HBM TRAFFIC TO DRAIN BEFORE RAISING rPause.  THIS PRESERVES THE OLD EXTERNAL
  // CONTRACT: WHILE rPause IS NONZERO THE SERVER IS OBSERVABLY PAUSED, SOFTWARE
  // MAY UPDATE THE RING REGISTERS, AND WRITING rPause BACK TO ZERO RESUMES THE
  // STREAMING DATAPATHS WITHOUT A GLOBAL FSM.
  // ---------------------------------------------------------------------------
  val resizeNeeded =
    maxLength =/= 0.U &&
      currLen + taskQueueBuffer.io.count + nBeatsUInt > maxLength
  val quiesceForPause = RegInit(false.B)
  val pauseRequested = quiesceForPause || interruptCondition
  val pauseDrained = outstandingReads === 0.U && !writingToHBM && io.write_idle

  // When the global-start feature is off, the gate is EXACTLY the original
  // expression (no extra term) so the RTL is byte-identical to before.
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

  // ---------------------------------------------------------------------------
  // AXI-LITE MANAGEMENT REPLIES: KEEP REGISTER ACCESS RESPONSIVE IN PARALLEL
  // WITH THE STREAMING DATAPATHS ABOVE.
  // ---------------------------------------------------------------------------
  when(regBlock.rdReq) {
    regBlock.rdOk()
  }

  when(regBlock.wrReq) {
    regBlock.wrOk()
  }

}
