package NewArgumentNotifier

import chisel3._
import chisel3.util._

import chext.amba.axi4
import chext.elastic

class CacheEvictionSaverIO(
    lineAddressWidth: Int,
    continuationSize: Int,
    serverTagWidth: Int,
    serverIDWidth: Int,
    laneWidth: Int
)
    extends Bundle {
  val evictionIn = Flipped(
    Decoupled(
      new TaggedEvictedContinuation(
        lineAddressWidth,
        continuationSize,
        serverTagWidth,
        serverIDWidth,
        laneWidth
      )
    )
  )

  /** Emitted only after the corresponding AXI B response. */
  val writeCompleted = Decoupled(
    new ContinuationMetadata(serverTagWidth, serverIDWidth, laneWidth)
  )
}

/** Address-agnostic eviction writer.
  *
  * The originating EvictionGater counts the line before it enters the ring;
  * the returned completion advances that server's ordered completion fence.
  * Constant-ID AXI B responses are ordered, so a metadata FIFO associates each
  * response with its completion notification.
  */
class CacheEvictionSaver(
    memoryAddressWidth: Int,
    lineAddressWidth: Int,
    lineShift: Int,
    continuationSize: Int,
    serverTagWidth: Int = 1,
    serverIDWidth: Int = 1,
    laneWidth: Int = 1,
    queueDepth: Int = 2,
    responseQueueDepth: Int = 64
) extends Module {
  require(lineAddressWidth + lineShift == memoryAddressWidth)
  require(queueDepth >= 1 && responseQueueDepth >= 1)

  val cfgAxi = axi4.Config(
    wAddr = memoryAddressWidth,
    wData = continuationSize,
    wId = 1,
    read = false
  )

  val io = IO(
    new CacheEvictionSaverIO(
      lineAddressWidth,
      continuationSize,
      serverTagWidth,
      serverIDWidth,
      laneWidth
    )
  )
  val m_axi = IO(axi4.full.Master(cfgAxi))

  private val inputQ = Module(
    new BankedQueue(chiselTypeOf(io.evictionIn.bits), queueDepth)
  )
  inputQ.io.enq <> io.evictionIn

  // Record each issued key alongside the AW/W transaction. The Fork holds
  // the source until all three consumers have accepted it.
  private val issuedMetadataQ = Module(
    new Queue(
      new ContinuationMetadata(serverTagWidth, serverIDWidth, laneWidth),
      responseQueueDepth
    )
  )
  new elastic.Fork(inputQ.io.deq) {
    protected def onFork: Unit = {
      new elastic.Transform(fork(), m_axi.aw) {
        protected def onTransform: Unit = {
          out := 0.U.asTypeOf(out)
          out.addr := in.eviction.address ## 0.U(lineShift.W)
          out.len := 0.U
          out.size := log2Ceil(continuationSize / 8).U
          out.burst := axi4.BurstType.INCR
        }
      }

      new elastic.Transform(fork(), m_axi.w) {
        protected def onTransform: Unit = {
          out := 0.U.asTypeOf(out)
          out.data := in.eviction.taskData
          out.strb := Fill(continuationSize / 8, 1.U(1.W))
          out.last := true.B
        }
      }

      new elastic.Transform(fork(), issuedMetadataQ.io.enq) {
        protected def onTransform: Unit = out := in.metadata
      }
    }
  }

  private val completedQ = Module(
    new Queue(
      new ContinuationMetadata(serverTagWidth, serverIDWidth, laneWidth),
      responseQueueDepth
    )
  )
  m_axi.b.ready := issuedMetadataQ.io.deq.valid && completedQ.io.enq.ready
  issuedMetadataQ.io.deq.ready := m_axi.b.fire
  completedQ.io.enq.valid := m_axi.b.fire
  completedQ.io.enq.bits := issuedMetadataQ.io.deq.bits
  io.writeCompleted <> completedQ.io.deq
}
