package Scheduler

import chisel3._
import chisel3.util._
import Util._

import chext.amba.axi4s
import chext.elastic
import chext.util.BitOps._
import axi4s.Casts._
import scala.collection.immutable.SeqMap
import chext.amba.axi4
import chext.amba.axi4.lite.components.RegisterBlock
import axi4.Ops._

import HardCilk.globalFunctionIds


// ─────────────────────────────────────────────────────────────────────────────
//  taskRequestReplyType hierarchy
//
//  IMPORTANT: the base class carries ONLY abstract defs — no `val` hardware
//  fields.  In Chisel, parent-class `val`s are laid out at the MSB of the
//  final bundle; keeping them abstract lets each concrete class own the full
//  bit layout from top to bottom.
//
//  Width rule:
//    taskWidth <  256  →  512-bit single-word packet  (taskRequestReplyType512)
//    taskWidth >= 256  → 1024-bit dual-word packet    (taskRequestReplyType1024)
// ─────────────────────────────────────────────────────────────────────────────

sealed abstract class taskRequestReplyBase(val taskWidth: Int) extends Bundle {

  // ── Abstract hardware fields (concrete classes define the actual vals) ──────
  def fpgaId:           UInt
  def taskId:           UInt
  def globalFunctionId: globalFunctionIds.Type
  def isRequest:        Bool
  def taskData_lower:   UInt
  def taskData_upper:   UInt
  def padding_0:        UInt

  // ── Uniform getters ──────────────────────────────────────────────────────────
  def getFpgaId:           UInt              = fpgaId
  def getTaskId:           UInt              = taskId
  def getGlobalFunctionId: globalFunctionIds.Type = globalFunctionId
  def getIsRequest:        Bool              = isRequest
  def getTaskData:         UInt              = Cat(taskData_upper, taskData_lower)

  // ── Abstract setters (each concrete class handles field duplication) ─────────
  def setFpgaId(v: UInt):                        Unit
  def setTaskId(v: UInt):                        Unit
  def setGlobalFunctionId(v: globalFunctionIds.Type): Unit
  def setIsRequest(v: Bool):                     Unit
  def setTaskData(v: UInt):                      Unit
  def setPadding(v: UInt):                       Unit
}


// ─────────────────────────────────────────────────────────────────────────────
//  512-bit layout  (taskWidth < 256)
//
//  MSB ──────────────────────────────────────────────────────────────── LSB
//  [ fpgaId(4) | taskId(4) | gfid | isRequest(1) | upper(W/2) | lower(W/2) | pad ]
// ─────────────────────────────────────────────────────────────────────────────
class taskRequestReplyType512(taskWidth: Int) extends taskRequestReplyBase(taskWidth) {
  private val gfidWidth = globalFunctionIds().getWidth
  private val padWidth  = 512 - 4 - 4 - gfidWidth - 1 - taskWidth
  require(taskWidth < 256,  s"Use taskRequestReplyType1024 for taskWidth >= 256")
  require(padWidth  >= 0,   s"taskWidth=$taskWidth overflows 512-bit layout by ${-padWidth} bits")

  // Hardware fields — order here IS the bit layout (MSB first)
  val fpgaId           = UInt(4.W)
  val taskId           = UInt(4.W)
  val globalFunctionId = globalFunctionIds()
  val isRequest        = Bool()
  val taskData_upper   = UInt((taskWidth / 2).W)   // upper half first for Cat consistency
  val taskData_lower   = UInt((taskWidth / 2).W)
  val padding_0        = UInt(padWidth.W)

  // Setters — single word, no duplication
  def setFpgaId(v: UInt):                            Unit = fpgaId           := v
  def setTaskId(v: UInt):                            Unit = taskId           := v
  def setGlobalFunctionId(v: globalFunctionIds.Type): Unit = globalFunctionId := v
  def setIsRequest(v: Bool):                         Unit = isRequest        := v
  def setTaskData(v: UInt): Unit = {
    taskData_lower := v(taskWidth / 2 - 1, 0)
    taskData_upper := v(taskWidth - 1, taskWidth / 2)
  }
  def setPadding(v: UInt): Unit = padding_0 := 0.U

  assert(this.getWidth == 512, "taskRequestReplyType512 must be exactly 512 bits")
}


// ─────────────────────────────────────────────────────────────────────────────
//  1024-bit layout  (taskWidth >= 256)
//
//  Word 0 (MSB):
//    [ fpgaId(4) | taskId(4) | gfid | isRequest(1) | lower(W/2) | pad0 ]
//  Word 1 (LSB):
//    [ fpgaId_(4) | taskId_(4) | gfid_ | upper(W/2) | pad1 ]
//
//  fpgaId_ == fpgaId, taskId_ == taskId, gfid_ == gfid  (mirrors, hidden
//  from callers — the setters write both copies automatically)
// ─────────────────────────────────────────────────────────────────────────────
class taskRequestReplyType1024(taskWidth: Int) extends taskRequestReplyBase(taskWidth) {
  private val gfidWidth = globalFunctionIds().getWidth
  private val halfTask  = taskWidth / 2
  private val pad0Width = 512 - 4 - 4 - gfidWidth - 1 - halfTask
  private val pad1Width = 512 - 4 - 4 - gfidWidth     - halfTask
  require(taskWidth >= 256, s"Use taskRequestReplyType512 for taskWidth < 256")
  require(pad0Width  >= 0,  s"taskWidth=$taskWidth overflows word-0 by ${-pad0Width} bits")
  require(pad1Width  >= 0,  s"taskWidth=$taskWidth overflows word-1 by ${-pad1Width} bits")

  // Word 0
  val fpgaId           = UInt(4.W)
  val taskId           = UInt(4.W)
  val globalFunctionId = globalFunctionIds()
  val isRequest        = Bool()
  val taskData_lower   = UInt(halfTask.W)
  val padding_0        = UInt(pad0Width.W)

  // Word 1 — mirrored identity fields + upper half of task data
  val fpgaId_           = UInt(4.W)
  val taskId_           = UInt(4.W)
  val globalFunctionId_ = globalFunctionIds()
  val taskData_upper    = UInt(halfTask.W)
  val padding_1         = UInt(pad1Width.W)

  // Setters — write both word-0 and word-1 copies for identity fields
  def setFpgaId(v: UInt):                            Unit = { fpgaId           := v; fpgaId_           := v }
  def setTaskId(v: UInt):                            Unit = { taskId           := v; taskId_           := v }
  def setGlobalFunctionId(v: globalFunctionIds.Type): Unit = { globalFunctionId := v; globalFunctionId_ := v }
  def setIsRequest(v: Bool):                         Unit =   isRequest        := v
  def setTaskData(v: UInt): Unit = {
    taskData_lower := v(halfTask - 1, 0)
    taskData_upper := v(taskWidth - 1, halfTask)
  }
  def setPadding(v: UInt): Unit = { padding_0 := 0.U; padding_1 := 0.U }

  assert(this.getWidth == 1024, "taskRequestReplyType1024 must be exactly 1024 bits")
}


// ─────────────────────────────────────────────────────────────────────────────
//  Factory — picks the right concrete class at elaboration time
// ─────────────────────────────────────────────────────────────────────────────
object taskRequestReplyType {
  def apply(taskWidth: Int): taskRequestReplyBase =
    if (taskWidth < 256) new taskRequestReplyType512(taskWidth)
    else                 new taskRequestReplyType1024(taskWidth)
}


// ─────────────────────────────────────────────────────────────────────────────
//  RemoteTaskServer IO + Module
// ─────────────────────────────────────────────────────────────────────────────

class RemoteTaskServerIO(taskWidth: Int, axisCfgTaskAndReq: axi4s.Config, regBlock: RegisterBlock) extends Bundle {
  val s_axis_taskAndReq = axi4s.Slave(axisCfgTaskAndReq)
  val m_axis_taskAndReq = axi4s.Master(axisCfgTaskAndReq)
  val connNetwork_0     = Flipped(new SchedulerNetworkClientIO(taskWidth))
  val connNetwork_1     = Flipped(new SchedulerNetworkClientIO(taskWidth))
  val serveRemote           = Input(Bool())
  val getTasksFromRemote    = Input(Bool())
  val fpgaIndexInputReg     = Input(UInt(8.W))
  val fpgaCountInputReg     = Input(UInt(8.W))
  val numTasksToStealOrServe = Input(UInt(32.W))
  val axi_mgmt = axi4.lite.Slave(regBlock.cfgAxi)
}


class RemoteTaskServer(
    taskWidth:              Int,
    peCount:                Int,
    axisCfgTaskAndReq:      axi4s.Config,
    taskIndex:              Int,
    coolDownTime:           Int = 0,
    maxNumnberToStealOrServe: Int = 256
) extends Module {

  // ── AXI-Lite management registers ──────────────────────────────────────────
  val regBlock = new RegisterBlock(wAddr = 6, wData = 64, wMask = 6)

  val tasks_sent               = RegInit(0.U(64.W))
  val tasks_recevied           = RegInit(0.U(64.W))
  val requests_sent            = RegInit(0.U(64.W))
  val requests_accepted        = RegInit(0.U(64.W))
  val requests_forwarded       = RegInit(0.U(64.W))
  val self_requests_squished   = RegInit(0.U(64.W))
  val self_requests_reforwarded = RegInit(0.U(64.W))

  regBlock.base(0x00)
  regBlock.reg(tasks_sent,                read = true, write = true, desc = "Number of tasks sent")
  regBlock.reg(tasks_recevied,            read = true, write = true, desc = "Number of tasks received")
  regBlock.reg(requests_sent,             read = true, write = true, desc = "Number of requests sent")
  regBlock.reg(requests_accepted,         read = true, write = true, desc = "Number of requests accepted")
  regBlock.reg(requests_forwarded,        read = true, write = true, desc = "Number of requests forwarded")
  regBlock.reg(self_requests_squished,    read = true, write = true, desc = "Number of self requests squished")
  regBlock.reg(self_requests_reforwarded, read = true, write = true, desc = "Number of self requests reforwarded")

  when(regBlock.rdReq) { regBlock.rdOk() }
  when(regBlock.wrReq) { regBlock.wrOk() }

  val io = IO(new RemoteTaskServerIO(taskWidth, axisCfgTaskAndReq, regBlock))

  io.axi_mgmt.suggestName("S_AXI_MGMT")
  io.axi_mgmt :=> regBlock.s_axil

  // ── Convenience: packet width derived from factory at elaboration time ──────
  private val pktWidth = taskRequestReplyType(taskWidth).getWidth   // 512 or 1024

  val servingRemote           = RegInit(false.B)
  val shiftedFpgaIndexInputReg = RegNext(io.fpgaIndexInputReg)

  // ── Network reader / writer ─────────────────────────────────────────────────
  val readTaskFromNetwork  = Module(new ReadTaskFromNetwork(taskWidth)).io
  val writeTaskToNetwork   = Module(new WriteTaskToNetwork(taskWidth)).io
  writeTaskToNetwork.numTasksToStealOrServe := io.numTasksToStealOrServe
  readTaskFromNetwork.fpgaId  := shiftedFpgaIndexInputReg
  writeTaskToNetwork.fpgaId   := shiftedFpgaIndexInputReg
  readTaskFromNetwork.connNetwork  <> io.connNetwork_0
  writeTaskToNetwork.connNetwork   <> io.connNetwork_1

  // ── Arbiter for m_axis_taskAndReq (3 sources) ───────────────────────────────
  val arbiter = Module(
    new elastic.BasicArbiter(chiselTypeOf(io.m_axis_taskAndReq.asFull.bits), 3, chooserFn = elastic.Chooser.rr)
  )
  arbiter.io.select.nodeq()
  when(arbiter.io.select.valid) { arbiter.io.select.deq() }
  elastic.SourceBuffer(arbiter.io.sink) <> io.m_axis_taskAndReq.asFull

  val requestForwardSlave = Wire(axi4s.Slave(axisCfgTaskAndReq))

  // ── Fetching-remote state ───────────────────────────────────────────────────
  val fetchingRemote    = RegInit(false.B)
  val requestSent       = RegInit(false.B)
  val dataReceievedCount = RegInit(0.U(32.W))
  val flaggedByServingRemote = RegInit(false.B)

  val taskRequestingMaster = Wire(axi4s.Master(axisCfgTaskAndReq))
  val taskWaitingSlave     = Wire(axi4s.Slave(axisCfgTaskAndReq))

  // Default: drive taskRequestingMaster to safe idle
  taskRequestingMaster.TDATA         := 0.asUInt(axisCfgTaskAndReq.wData.W)
  taskRequestingMaster.TSTRB.get     := 0.U
  taskRequestingMaster.TKEEP.get     := 0.U
  taskRequestingMaster.TLAST.get     := false.B
  taskRequestingMaster.asFull.valid  := false.B
  taskRequestingMaster.TDEST.get     := ((shiftedFpgaIndexInputReg + 1.U) % io.fpgaCountInputReg)
  taskWaitingSlave.asFull.ready      := false.B

  // ── Queue for tasks received from remote FPGAs ──────────────────────────────
  val queueForReceivingTasks = Module(new Queue(UInt(taskWidth.W), maxNumnberToStealOrServe))

  queueForReceivingTasks.io.enq.valid := taskWaitingSlave.TVALID
  queueForReceivingTasks.io.enq.bits  :=
    taskWaitingSlave.TDATA.asTypeOf(taskRequestReplyType(taskWidth)).getTaskData

  taskWaitingSlave.TREADY := queueForReceivingTasks.io.enq.ready

  // Connect queue output to network writer
  queueForReceivingTasks.io.deq.ready    := writeTaskToNetwork.s_axis_task.ready
  writeTaskToNetwork.s_axis_task.valid   := queueForReceivingTasks.io.deq.valid
  writeTaskToNetwork.s_axis_task.bits    := queueForReceivingTasks.io.deq.bits

  // ── Trigger fetching ────────────────────────────────────────────────────────
  when(io.getTasksFromRemote && !fetchingRemote) {
    fetchingRemote := true.B
  }

  val networkWriterReady = RegInit(false.B)
  writeTaskToNetwork.startToken.valid := false.B
  writeTaskToNetwork.startToken.bits  := 0.U
  when(fetchingRemote && !networkWriterReady) {
    writeTaskToNetwork.startToken.valid := true.B
    writeTaskToNetwork.startToken.bits  := 1.U
    when(writeTaskToNetwork.startToken.ready) {
      networkWriterReady := true.B
    }
  }

  // ── Send request packet ─────────────────────────────────────────────────────
  when(fetchingRemote && networkWriterReady && !requestSent) {
    taskRequestingMaster.TVALID := true.B

    val taskRequestPacket = Wire(taskRequestReplyType(taskWidth))
    taskRequestPacket.setFpgaId(shiftedFpgaIndexInputReg(3, 0))
    taskRequestPacket.setTaskId(taskIndex.U(4.W))
    taskRequestPacket.setGlobalFunctionId(globalFunctionIds.scheduler)
    taskRequestPacket.setIsRequest(true.B)
    taskRequestPacket.setTaskData(0.U(taskWidth.W))
    taskRequestPacket.setPadding(0.U)

    taskRequestingMaster.TDATA     := taskRequestPacket.asUInt
    taskRequestingMaster.TLAST.get := true.B
    taskRequestingMaster.TSTRB.get := "hFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF".U
    taskRequestingMaster.TKEEP.get := "hFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF".U

    when(taskRequestingMaster.TREADY) {
      requestSent := true.B
    }
  }

  // ── Count received tasks; reset on flaggedByServingRemote ──────────────────
  when(requestSent && dataReceievedCount < io.numTasksToStealOrServe) {
    when(dataReceievedCount === io.numTasksToStealOrServe - 1.U &&
         taskWaitingSlave.asFull.valid && queueForReceivingTasks.io.enq.ready) {
      requestSent        := false.B
      fetchingRemote     := false.B
      dataReceievedCount := 0.U
      networkWriterReady := false.B
    }.elsewhen(taskWaitingSlave.asFull.valid && queueForReceivingTasks.io.enq.ready) {
      dataReceievedCount := dataReceievedCount + 1.U
    }

    when(flaggedByServingRemote) {
      requestSent        := false.B
      fetchingRemote     := false.B
      dataReceievedCount := 0.U
      flaggedByServingRemote := false.B
    }
  }

  // ── Serving-remote state ────────────────────────────────────────────────────
  val activelyServing  = RegInit(false.B)
  val fetchLocalNetwork = RegInit(false.B)
  val servingCount     = RegInit(0.U(32.W))
  val fpgaToServe      = RegInit(0.U(8.W))

  val taskServingMaster   = Wire(axi4s.Master(axisCfgTaskAndReq))
  val taskRequestListener = Wire(axi4s.Slave(axisCfgTaskAndReq))

  // Default: idle
  taskServingMaster.TDATA        := 0.asUInt(axisCfgTaskAndReq.wData)
  taskServingMaster.TSTRB.get    := 0.U
  taskServingMaster.TKEEP.get    := 0.U
  taskServingMaster.TLAST.get    := false.B
  taskServingMaster.asFull.valid := false.B
  taskRequestListener.asFull.ready := false.B

  // ── Queue for tasks to be sent to remote FPGA ───────────────────────────────
  val queueForSendingTasks = Module(new Queue(UInt(taskWidth.W), maxNumnberToStealOrServe))

  queueForSendingTasks.io.enq.valid    := readTaskFromNetwork.m_axis_task.valid
  queueForSendingTasks.io.enq.bits     := readTaskFromNetwork.m_axis_task.bits
  readTaskFromNetwork.m_axis_task.ready := queueForSendingTasks.io.enq.ready

  queueForSendingTasks.io.deq.ready    := taskServingMaster.TREADY
  taskServingMaster.TVALID             := queueForSendingTasks.io.deq.valid

  // ── Build reply packet using setters ────────────────────────────────────────
  val taskReplyPacket = Wire(taskRequestReplyType(taskWidth))
  taskReplyPacket.setFpgaId(shiftedFpgaIndexInputReg(3, 0))
  taskReplyPacket.setTaskId(taskIndex.U(4.W))
  taskReplyPacket.setGlobalFunctionId(globalFunctionIds.scheduler)
  taskReplyPacket.setIsRequest(false.B)
  taskReplyPacket.setTaskData(queueForSendingTasks.io.deq.bits)
  taskReplyPacket.setPadding(0.U)

  taskServingMaster.TDATA     := taskReplyPacket.asUInt
  taskServingMaster.TDEST.get := fpgaToServe
  taskServingMaster.TLAST.get := true.B
  taskServingMaster.TSTRB.get := "hFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF".U
  taskServingMaster.TKEEP.get := "hFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF".U

  // ── Debug counters ──────────────────────────────────────────────────────────
  val fires_counter          = RegInit(0.U(32.W))
  val fires_counter_listener = RegInit(0.U(32.W))
  val cycles_counter         = RegInit(0.U(32.W))

  when(taskServingMaster.asFull.fire) { fires_counter          := fires_counter + 1.U }
  when(taskWaitingSlave.asFull.fire)  { fires_counter_listener := fires_counter_listener + 1.U }

  cycles_counter := cycles_counter + 1.U
  when(cycles_counter === 1000000.U) {
    printf("_______\n")
    printf("FPGA ID %d, taskID %d: Tasks recieved from remote: %d\n",
      shiftedFpgaIndexInputReg, taskIndex.U, fires_counter_listener)
    printf("FPGA ID %d, taskID %d: Tasks sent to remote: %d\n",
      shiftedFpgaIndexInputReg, taskIndex.U, fires_counter)
    printf("_______\n")
    cycles_counter := 0.U
  }

  // ── Serve-remote entry / exit ───────────────────────────────────────────────
  when(io.serveRemote && !servingRemote) {
    servingRemote := true.B
  }

  when(servingRemote && !activelyServing) {
    taskRequestListener.asFull.ready := true.B
    when(taskRequestListener.asFull.valid) {
      fpgaToServe    := taskRequestListener.TDATA
                          .asTypeOf(taskRequestReplyType(taskWidth))
                          .getFpgaId
      activelyServing := true.B
    }.elsewhen(!io.serveRemote) {
      servingRemote := false.B
    }
  }

  readTaskFromNetwork.readTasksCount.valid := false.B
  readTaskFromNetwork.readTasksCount.bits  := 0.U
  when(servingRemote && activelyServing && !fetchLocalNetwork) {
    readTaskFromNetwork.readTasksCount.valid := true.B
    readTaskFromNetwork.readTasksCount.bits  := io.numTasksToStealOrServe
    when(readTaskFromNetwork.readTasksCount.ready) {
      fetchLocalNetwork := true.B
    }
  }

  when(fetchLocalNetwork && servingCount < io.numTasksToStealOrServe) {
    when(servingCount === io.numTasksToStealOrServe - 1.U &&
         taskServingMaster.TREADY && queueForSendingTasks.io.deq.valid) {
      fetchLocalNetwork := false.B
      activelyServing   := false.B
      servingRemote     := false.B
      servingCount      := 0.U
    }.elsewhen(taskServingMaster.TREADY && queueForSendingTasks.io.deq.valid) {
      servingCount := servingCount + 1.U
    }
  }

  // ── Self-request suppressor ─────────────────────────────────────────────────
  val requestForwardSlaveSupressSelfRequest = Wire(axi4s.Slave(axisCfgTaskAndReq))
  requestForwardSlaveSupressSelfRequest.asFull.ready := true.B
  flaggedByServingRemote := requestForwardSlaveSupressSelfRequest.asFull.valid

  // ── Mark s_axis with serving state (2 extra bits at MSB) ───────────────────
  val s_axis_taskAndReqMarked = Wire(Irrevocable(
    new chext.amba.axi4s.FullChannel(
      io.s_axis_taskAndReq.cfg.copy(wData = 8 + pktWidth)
    )
  ))

  new elastic.Arrival(io.s_axis_taskAndReq.asFull, s_axis_taskAndReqMarked) {
    protected def onArrival: Unit = {
      out      := in
      out.data := Cat(servingRemote, activelyServing) ## in.data
      accept()
    }
  }

  // ── Demux: route packets to the right downstream sink ──────────────────────
  new elastic.Fork(s_axis_taskAndReqMarked) {
    override def onFork(): Unit = {
      val taskRequest = Wire(taskRequestReplyType(taskWidth))
      taskRequest := in.data(pktWidth - 1, 0).asTypeOf(taskRequestReplyType(taskWidth))

      val isRequest      = taskRequest.getIsRequest
      val isSelfRequest  = (taskRequest.getFpgaId === shiftedFpgaIndexInputReg) && isRequest
      val isServingRemote  = in.data.dropMsbN(7).msbN(1).asBool
      val isActivelyServing = in.data.dropMsbN(6).msbN(1).asBool

      elastic.Demux(
        source = fork(in),
        sinks  = Seq(
          elastic.SinkBuffer(taskWaitingSlave.asFull),
          elastic.SinkBuffer(taskRequestListener.asFull),
          elastic.SinkBuffer(requestForwardSlave.asFull),
          elastic.SinkBuffer(requestForwardSlaveSupressSelfRequest.asFull)
        ),
        select = elastic.SourceBuffer(fork(
          Cat(
            (isRequest && !servingRemote) || (isRequest && activelyServing) || (isSelfRequest && servingRemote),
            servingRemote && ((!activelyServing && isRequest) || isSelfRequest)
          )
        ))
      )
    }
  }

  // ── Wire arbiter sources ────────────────────────────────────────────────────
  taskRequestingMaster.asFull <> arbiter.io.sources(0)
  taskServingMaster.asFull    <> arbiter.io.sources(1)

  new elastic.Transform(requestForwardSlave.asFull, arbiter.io.sources(2)) {
    protected def onTransform: Unit = {
      out.data  := in.data
      out.dest.get := (in.dest.get + 1.U) % io.fpgaCountInputReg
      out.keep  := Fill(out.keep.getWidth,   1.U(1.W))
      out.strobe := Fill(out.strobe.getWidth, 1.U(1.W))
      out.last  := true.B
    }
  }

  // ── Management counter updates ──────────────────────────────────────────────
  when(taskServingMaster.asFull.fire)                    { tasks_sent               := tasks_sent + 1.U }
  when(taskWaitingSlave.asFull.fire)                     { tasks_recevied           := tasks_recevied + 1.U }
  when(taskRequestingMaster.asFull.fire)                 { requests_sent            := requests_sent + 1.U }
  when(taskRequestListener.asFull.fire)                  { requests_accepted        := requests_accepted + 1.U }
  when(requestForwardSlave.asFull.fire)                  { requests_forwarded       := requests_forwarded + 1.U }
  when(requestForwardSlaveSupressSelfRequest.asFull.fire){ self_requests_squished   := self_requests_squished + 1.U }

  when(requestForwardSlave.asFull.fire &&
       requestForwardSlave.TDATA
         .asTypeOf(taskRequestReplyType(taskWidth))
         .getFpgaId === shiftedFpgaIndexInputReg) {
    self_requests_reforwarded := self_requests_reforwarded + 1.U
  }

  // ── Debug printf ────────────────────────────────────────────────────────────
  when(taskRequestingMaster.asFull.valid) {
    printf("TIME %d, FPGA ID %d, packet FPGAID %d: Request generated\n",
      cycles_counter,
      shiftedFpgaIndexInputReg,
      taskRequestingMaster.TDATA
        .asTypeOf(taskRequestReplyType(taskWidth))
        .getFpgaId
    )
  }

  when(requestForwardSlaveSupressSelfRequest.asFull.valid) {
    printf("TIME %d, FPGA ID %d, packet FPGAID %d: Request subpressed\n",
      cycles_counter,
      shiftedFpgaIndexInputReg,
      requestForwardSlaveSupressSelfRequest.TDATA
        .asTypeOf(taskRequestReplyType(taskWidth))
        .getFpgaId
    )
  }

  // ── Fix up m_axis output fields ─────────────────────────────────────────────
  io.m_axis_taskAndReq.asFull.bits.last   := true.B
  io.m_axis_taskAndReq.asFull.bits.keep   := Fill(io.m_axis_taskAndReq.asFull.bits.keep.getWidth,   1.U(1.W))
  io.m_axis_taskAndReq.asFull.bits.strobe := Fill(io.m_axis_taskAndReq.asFull.bits.strobe.getWidth, 1.U(1.W))
}