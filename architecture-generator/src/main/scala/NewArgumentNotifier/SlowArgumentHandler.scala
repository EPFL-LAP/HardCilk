package NewArgumentNotifier

import chisel3._
import chisel3.util._

import chext.amba.axi4
import chext.elastic
import chext.elastic.ConnectOp._

/** Pipelined slow path for updates whose continuation has left the cache. */
class SlowArgumentHandlerIO(
    lineAddressWidth: Int,
    continuationSize: Int,
    enableRecycling: Boolean,
    payloadWidth: Int,
    offsetWidth: Int
) extends Bundle {
  val slowUpdateIn = Flipped(
    Decoupled(
      new SlowUpdate(
        lineAddressWidth,
        continuationSize,
        payloadWidth,
        offsetWidth
      )
    )
  )
  val spawnOut = Decoupled(UInt(continuationSize.W))
  // Address of each continuation resolved in memory, for the recycler. Valid,
  // never Decoupled: the recycler observes the spawn, it must never gate it.
  val resolvedAddressOut =
    if (enableRecycling) Some(Valid(UInt(lineAddressWidth.W))) else None
}

class SlowArgumentHandler(
    counterWidth: Int,
    sysAddressWidth: Int,
    lineAddressWidth: Int,
    lineShift: Int,
    continuationSize: Int,
    inputQueueDepth: Int = 16,
    axiIdWidth: Int = 6,
    // Expose each resolution's line address so the recycler can return it to the
    // allocator's free list.
    enableRecycling: Boolean = false,
    // Updates arrive compact (payload + aligned slot offset) and are expanded to
    // a full line here.
    payloadWidth: Int = 0,
    offsetWidth: Int = 0
) extends Module {
  require(lineAddressWidth + lineShift == sysAddressWidth)
  require(inputQueueDepth >= 1)
  require(axiIdWidth >= 1)

  private val axiDataWidth =
    if (continuationSize == 2048) 1024 else continuationSize
  private val beatsPerAccess = continuationSize / axiDataWidth

  val cfgAxi = axi4.Config(
    wAddr = sysAddressWidth,
    wData = axiDataWidth,
    wId = axiIdWidth
  )

  val io = IO(
    new SlowArgumentHandlerIO(
      lineAddressWidth,
      continuationSize,
      enableRecycling,
      payloadWidth,
      offsetWidth
    )
  )
  val m_axi = IO(axi4.full.Master(cfgAxi))

  private def lineType = new ContinuationLine(counterWidth, continuationSize)
  private val nInflight = 1 << axiIdWidth

  private def slowUpdateType =
    new SlowUpdate(
      lineAddressWidth,
      continuationSize,
      payloadWidth,
      offsetWidth
    )
  private val inputQ = Module(
    new BankedQueue(slowUpdateType, inputQueueDepth)
  )
  inputQ.io.enq <> io.slowUpdateIn

  object InflightStage extends ChiselEnum {
    val readPending, writePending, spawnPending = Value
  }

  private class InflightUpdate extends Bundle {
    val address = UInt(lineAddressWidth.W)
    val stage = InflightStage()
    val decrement = UInt(counterWidth.W)
  }
  private val inflightValid = RegInit(VecInit.fill(nInflight)(false.B))
  private val inflight = Reg(Vec(nInflight, new InflightUpdate))
  private val inflightData = Mem(nInflight, UInt(continuationSize.W))

  // Requests that encounter the same address after its read has completed are
  // recycled, just as in the current ArgumentServer, so unrelated requests do
  // not suffer head-of-line blocking.
  private val feedbackQ = Module(new BankedQueue(slowUpdateType, 8))
  private val inputArb = Module(new BankedRRArbiter(slowUpdateType, 2))

  inputQ.io.deq :=> inputArb.io.sources(0)
  feedbackQ.io.deq :=> inputArb.io.sources(1)

  private val candidate = inputArb.io.sink
  private val matchVec = VecInit(
    inflightValid.zip(inflight).map { case (valid, entry) =>
      valid && entry.address === candidate.bits.address
    }
  )
  private val matchValid = matchVec.asUInt.orR
  private val matchId = PriorityEncoder(matchVec)
  private val emptyVec = ~inflightValid.asUInt
  private val hasEmpty = emptyVec.orR
  private val emptyId = PriorityEncoder(emptyVec)

  // The handler operates on one logical continuation per request. For a
  // 2048-bit continuation the HBM-facing AXI port is 1024 bits, so one logical
  // read is returned as a two-beat INCR burst. AXI4 keeps the beats of a read
  // burst contiguous; complete bursts may still return out of order, and the
  // unchanged RID selects the corresponding in-flight entry below.
  private val logicalRValid = Wire(Bool())
  private val logicalRReady = Wire(Bool())
  private val logicalRId = Wire(UInt(axiIdWidth.W))
  private val logicalRData = Wire(UInt(continuationSize.W))

  if (beatsPerAccess == 1) {
    logicalRValid := m_axi.r.valid
    logicalRId := m_axi.r.bits.id
    logicalRData := m_axi.r.bits.data
    m_axi.r.ready := logicalRReady
  } else {
    val firstReadBeat = RegInit(true.B)
    val firstReadData = Reg(UInt(axiDataWidth.W))
    val firstReadId = Reg(UInt(axiIdWidth.W))

    logicalRValid := m_axi.r.valid && !firstReadBeat
    logicalRId := m_axi.r.bits.id
    logicalRData := Cat(m_axi.r.bits.data, firstReadData)
    m_axi.r.ready := Mux(firstReadBeat, true.B, logicalRReady)

    when(m_axi.r.fire) {
      when(firstReadBeat) {
        assert(
          !m_axi.r.bits.last,
          "first 1024-bit read beat ended a 2048-bit access"
        )
        firstReadData := m_axi.r.bits.data
        firstReadId := m_axi.r.bits.id
        firstReadBeat := false.B
      }.otherwise {
        assert(
          m_axi.r.bits.last,
          "second 1024-bit read beat did not end a 2048-bit access"
        )
        assert(
          m_axi.r.bits.id === firstReadId,
          "RID changed within a 2048-bit read burst"
        )
        firstReadBeat := true.B
      }
    }
  }

  private val logicalRFire = logicalRValid && logicalRReady
  // A response and a coalescing update for that same ID must not modify the
  // entry on the same edge. Recycle the update in that case.
  private val responseCollision =
    logicalRValid && matchValid && logicalRId === matchId
  private val canCoalesce =
    matchValid && inflight(matchId).stage === InflightStage.readPending &&
      !responseCollision

  candidate.ready := false.B
  feedbackQ.io.enq.valid := false.B
  feedbackQ.io.enq.bits := candidate.bits

  m_axi.ar.valid := candidate.valid && !matchValid && hasEmpty
  m_axi.ar.bits := 0.U.asTypeOf(m_axi.ar.bits)
  m_axi.ar.bits.addr := candidate.bits.address ## 0.U(lineShift.W)
  m_axi.ar.bits.id := emptyId
  m_axi.ar.bits.len := (beatsPerAccess - 1).U
  m_axi.ar.bits.size := log2Ceil(axiDataWidth / 8).U
  m_axi.ar.bits.burst := axi4.BurstType.INCR

  when(candidate.valid) {
    when(canCoalesce) {
      candidate.ready := true.B
    }.elsewhen(matchValid) {
      feedbackQ.io.enq.valid := true.B
      candidate.ready := feedbackQ.io.enq.ready
    }.otherwise {
      candidate.ready := hasEmpty && m_axi.ar.ready
    }
  }

  // Both writers are funnelled into ONE write port. Two `Mem.write` call sites
  // make firtool emit a two-write-port memory, which Vivado cannot infer at all
  // ("RAM has multiple writes via different ports in same process") -- it fails
  // elaboration outright at this width. The writers are mutually exclusive
  // anyway: coalescing requires `matchValid`, issuing a new read requires
  // `!matchValid`, so a single port loses nothing.
  private val coalesceFire = candidate.fire && canCoalesce
  private val dataWriteEn = coalesceFire || m_axi.ar.fire
  private val dataWriteId = Mux(coalesceFire, matchId, emptyId)
  // The ONLY expansion site in the whole slow path: everything upstream carries
  // the compact payload + offset, one barrel shift places it here.
  private val candidateData = candidate.bits.expanded
  private val dataWriteData = Mux(
    coalesceFire,
    inflightData.read(matchId) | candidateData,
    candidateData
  )
  when(dataWriteEn) {
    inflightData.write(dataWriteId, dataWriteData)
  }
  assert(
    !(coalesceFire && m_axi.ar.fire),
    "in-flight data memory has one write port, but a coalescing update and a " +
      "new read issued on the same cycle"
  )

  when(coalesceFire) {
    inflight(matchId).decrement := inflight(matchId).decrement + 1.U
  }
  when(m_axi.ar.fire) {
    inflightValid(emptyId) := true.B
    inflight(emptyId).address := candidate.bits.address
    inflight(emptyId).stage := InflightStage.readPending
    inflight(emptyId).decrement := 1.U
  }

  private class CompletedUpdate extends Bundle {
    val id = UInt(axiIdWidth.W)
    val address = UInt(lineAddressWidth.W)
    val data = UInt(continuationSize.W)
  }
  private val writeQ = Module(new BankedQueue(new CompletedUpdate, nInflight))
  private val spawnQ = Module(new BankedQueue(new CompletedUpdate, nInflight))

  private val returned = inflight(logicalRId)
  private val base = logicalRData.asTypeOf(lineType)
  private val incoming = inflightData.read(logicalRId).asTypeOf(lineType)
  private val completes =
    base.counter =/= 0.U && base.counter <= returned.decrement
  private val merged = Wire(lineType)
  merged.remainder := base.remainder | incoming.remainder
  merged.counter := Mux(completes, 0.U, base.counter - returned.decrement)

  logicalRReady := Mux(completes, spawnQ.io.enq.ready, writeQ.io.enq.ready)
  writeQ.io.enq.valid := logicalRFire && !completes
  spawnQ.io.enq.valid := logicalRFire && completes
  for (q <- Seq(writeQ, spawnQ)) {
    q.io.enq.bits.id := logicalRId
    q.io.enq.bits.address := returned.address
    q.io.enq.bits.data := merged.asUInt
  }
  when(logicalRFire) {
    inflight(logicalRId).stage := Mux(
      completes,
      InflightStage.spawnPending,
      InflightStage.writePending
    )
  }

  // AW and W may backpressure independently; Fork holds the completed update
  // until both have accepted it and permits following writebacks to pipeline.
  new elastic.Fork(writeQ.io.deq) {
    protected def onFork: Unit = {
      new elastic.Transform(fork(), m_axi.aw) {
        protected def onTransform: Unit = {
          out := 0.U.asTypeOf(out)
          out.addr := in.address ## 0.U(lineShift.W)
          out.id := in.id
          out.len := (beatsPerAccess - 1).U
          out.size := log2Ceil(axiDataWidth / 8).U
          out.burst := axi4.BurstType.INCR
        }
      }
      if (beatsPerAccess == 1) {
        new elastic.Transform(fork(), m_axi.w) {
          protected def onTransform: Unit = {
            out := 0.U.asTypeOf(out)
            out.data := in.data
            out.strb := Fill(axiDataWidth / 8, 1.U(1.W))
            out.last := true.B
          }
        }
      } else {
        new elastic.Replicate(fork(), m_axi.w) {
          protected def onReplicate: Unit = {
            len := beatsPerAccess.U
            out := 0.U.asTypeOf(out)
            out.data := Mux(
              idx === 0.U,
              in.data(axiDataWidth - 1, 0),
              in.data(continuationSize - 1, axiDataWidth)
            )
            out.strb := Fill(axiDataWidth / 8, 1.U(1.W))
            out.last := last
          }
        }
      }
    }
  }

  m_axi.b.ready := true.B
  when(m_axi.b.fire) {
    inflightValid(m_axi.b.bits.id) := false.B
  }

  io.spawnOut.valid := spawnQ.io.deq.valid
  io.spawnOut.bits := spawnQ.io.deq.bits.data
  spawnQ.io.deq.ready := io.spawnOut.ready
  when(spawnQ.io.deq.fire) {
    inflightValid(spawnQ.io.deq.bits.id) := false.B
  }
  // The completed line has been handed off as a task, so its storage is dead.
  io.resolvedAddressOut.foreach { out =>
    out.valid := spawnQ.io.deq.fire
    out.bits := spawnQ.io.deq.bits.address
  }
}
