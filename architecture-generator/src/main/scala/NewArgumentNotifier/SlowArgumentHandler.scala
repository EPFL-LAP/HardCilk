package NewArgumentNotifier

import chisel3._
import chisel3.util._

import chext.amba.axi4
import chext.elastic
import chext.elastic.ConnectOp._

/** Pipelined slow path for updates whose continuation has left the cache.
  *
  * This follows the existing ArgumentNotifier.ArgumentServer organization:
  * AXI IDs index an in-flight table, updates to an address whose read is still
  * outstanding are coalesced, and unrelated reads continue independently.
  * Eviction ordering is enforced before this module by EvictionGater.
  */
class SlowArgumentHandlerIO(
    lineAddressWidth: Int,
    continuationSize: Int
) extends Bundle {
  val slowUpdateIn = Flipped(
    Decoupled(new SlowUpdate(lineAddressWidth, continuationSize))
  )
  val spawnOut = Decoupled(UInt(continuationSize.W))
}

class SlowArgumentHandler(
    counterWidth: Int,
    sysAddressWidth: Int,
    lineAddressWidth: Int,
    lineShift: Int,
    continuationSize: Int,
    inputQueueDepth: Int = 16,
    axiIdWidth: Int = 6
) extends Module {
  require(lineAddressWidth + lineShift == sysAddressWidth)
  require(inputQueueDepth >= 1)
  require(axiIdWidth >= 1)

  val cfgAxi = axi4.Config(
    wAddr = sysAddressWidth,
    wData = continuationSize,
    wId = axiIdWidth
  )

  val io = IO(new SlowArgumentHandlerIO(lineAddressWidth, continuationSize))
  val m_axi = IO(axi4.full.Master(cfgAxi))

  private def lineType = new ContinuationLine(counterWidth, continuationSize)
  private val nInflight = 1 << axiIdWidth

  private val inputQ = Module(
    new BankedQueue(
      new SlowUpdate(lineAddressWidth, continuationSize),
      inputQueueDepth
    )
  )
  inputQ.io.enq <> io.slowUpdateIn

  object InflightStage extends ChiselEnum {
    val readPending, writePending, spawnPending = Value
  }
  private class InflightUpdate extends Bundle {
    val address = UInt(lineAddressWidth.W)
    val stage = InflightStage()
    val dataWrite = UInt(continuationSize.W)
    val decrement = UInt(counterWidth.W)
  }
  private val inflightValid = RegInit(VecInit.fill(nInflight)(false.B))
  private val inflight = Reg(Vec(nInflight, new InflightUpdate))

  // Requests that encounter the same address after its read has completed are
  // recycled, just as in the current ArgumentServer, so unrelated requests do
  // not suffer head-of-line blocking.
  private val feedbackQ = Module(
    new BankedQueue(new SlowUpdate(lineAddressWidth, continuationSize), 8)
  )
  private val inputArb = Module(
    new BankedRRArbiter(
      new SlowUpdate(lineAddressWidth, continuationSize),
      2
    )
  )

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
  // A response and a coalescing update for that same ID must not modify the
  // entry on the same edge. Recycle the update in that case.
  private val responseCollision =
    m_axi.r.valid && matchValid && m_axi.r.bits.id === matchId
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
  m_axi.ar.bits.len := 0.U
  m_axi.ar.bits.size := log2Ceil(continuationSize / 8).U
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

  when(candidate.fire && canCoalesce) {
    inflight(matchId).dataWrite :=
      inflight(matchId).dataWrite | candidate.bits.dataWrite
    inflight(matchId).decrement := inflight(matchId).decrement + 1.U
  }
  when(m_axi.ar.fire) {
    inflightValid(emptyId) := true.B
    inflight(emptyId).address := candidate.bits.address
    inflight(emptyId).stage := InflightStage.readPending
    inflight(emptyId).dataWrite := candidate.bits.dataWrite
    inflight(emptyId).decrement := 1.U
  }

  private class CompletedUpdate extends Bundle {
    val id = UInt(axiIdWidth.W)
    val address = UInt(lineAddressWidth.W)
    val data = UInt(continuationSize.W)
  }
  private val writeQ = Module(new BankedQueue(new CompletedUpdate, nInflight))
  private val spawnQ = Module(new BankedQueue(new CompletedUpdate, nInflight))

  private val returned = inflight(m_axi.r.bits.id)
  private val base = m_axi.r.bits.data.asTypeOf(lineType)
  private val incoming = returned.dataWrite.asTypeOf(lineType)
  private val completes =
    base.counter =/= 0.U && base.counter <= returned.decrement
  private val merged = Wire(lineType)
  merged.remainder := base.remainder | incoming.remainder
  merged.counter := Mux(completes, 0.U, base.counter - returned.decrement)

  m_axi.r.ready := Mux(completes, spawnQ.io.enq.ready, writeQ.io.enq.ready)
  writeQ.io.enq.valid := m_axi.r.fire && !completes
  spawnQ.io.enq.valid := m_axi.r.fire && completes
  for (q <- Seq(writeQ, spawnQ)) {
    q.io.enq.bits.id := m_axi.r.bits.id
    q.io.enq.bits.address := returned.address
    q.io.enq.bits.data := merged.asUInt
  }
  when(m_axi.r.fire) {
    inflight(m_axi.r.bits.id).stage := Mux(
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
          out.len := 0.U
          out.size := log2Ceil(continuationSize / 8).U
          out.burst := axi4.BurstType.INCR
        }
      }
      new elastic.Transform(fork(), m_axi.w) {
        protected def onTransform: Unit = {
          out := 0.U.asTypeOf(out)
          out.data := in.data
          out.strb := Fill(continuationSize / 8, 1.U(1.W))
          out.last := true.B
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
}
