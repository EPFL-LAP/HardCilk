package NewArgumentNotifier

import chisel3._
import chisel3.util._

/** A Queue whose payload storage is split into independently controlled banks.
  *
  * A normal very-wide Queue infers one LUTRAM per payload slice, but all of
  * those LUTRAMs share one address and write-enable cone.  At continuation
  * widths this creates thousand-sink control nets and forces the placer to
  * cluster the whole FIFO.  Each bank here is a complete Queue, so its address
  * and enable logic only drives `bankWidth` payload bits.  The banks receive
  * exactly the same enqueue/dequeue events and therefore remain in lockstep.
  * Externally this has the same depth, latency, flow, pipe, and Decoupled
  * behavior as a single Queue.
  */
class BankedQueue[T <: Data](
    gen: T,
    entries: Int,
    bankWidth: Int = 64,
    pipe: Boolean = false,
    flow: Boolean = false
) extends Module {
  require(entries >= 1)
  require(bankWidth >= 1)

  private val payloadWidth = gen.getWidth
  require(payloadWidth >= 1)
  private val countWidth = math.max(1, log2Ceil(entries + 1))
  private val bankCount = (payloadWidth + bankWidth - 1) / bankWidth

  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(gen))
    val deq = Decoupled(gen)
    val count = Output(UInt(countWidth.W))
  })

  private val banks = (0 until bankCount).map { bank =>
    val low = bank * bankWidth
    val width = math.min(bankWidth, payloadWidth - low)
    Module(new Queue(UInt(width.W), entries, pipe = pipe, flow = flow))
  }

  private val packedEnq = io.enq.bits.asUInt
  for ((q, bank) <- banks.zipWithIndex) {
    val low = bank * bankWidth
    val high = math.min(low + bankWidth, payloadWidth) - 1
    q.io.enq.valid := io.enq.valid
    q.io.enq.bits := packedEnq(high, low)
    q.io.deq.ready := io.deq.ready
  }

  // All banks start empty and see identical handshakes, so their control state
  // is identical by construction.  Bank zero is the sole external control
  // representative; no wide AND tree is placed on ready or valid.
  io.enq.ready := banks.head.io.enq.ready
  io.deq.valid := banks.head.io.deq.valid
  io.deq.bits := Cat(banks.reverse.map(_.io.deq.bits)).asTypeOf(gen)
  io.count := banks.head.io.count
}

/** The exact round-robin policy used by chext.elastic.BasicArbiter, with its
  * two-entry output Queue replaced by BankedQueue.  All call sites in the new
  * notifier discard BasicArbiter's select stream, so it is intentionally absent
  * here; an always-drained select Queue never backpressures the original.
  */
class BankedRRArbiter[T <: Data](
    gen: T,
    n: Int,
    bankWidth: Int = 64
) extends Module {
  require(n > 1)

  private val choiceWidth = log2Up(n)
  val io = IO(new Bundle {
    val sources = Vec(n, Flipped(Decoupled(gen)))
    val sink = Decoupled(gen)
  })

  private val sinkQ = Module(new BankedQueue(gen, 2, bankWidth = bankWidth))
  io.sink <> sinkQ.io.deq

  private val sourceValids = VecInit(io.sources.map(_.valid))
  private val lastChoice = RegInit(0.U(choiceWidth.W))
  private val choiceMax = (-1).S(choiceWidth.W).asUInt
  private val rrChoice = Mux(
    lastChoice === choiceMax,
    0.U(choiceWidth.W),
    PriorityEncoder(sourceValids.zipWithIndex.map { case (valid, i) =>
      i.U > lastChoice && valid
    })
  )
  private val priorityChoice = PriorityEncoder(sourceValids)
  private val choice = Mux(sourceValids(rrChoice), rrChoice, priorityChoice)
  private val fire = io.sources(choice).valid && sinkQ.io.enq.ready

  sinkQ.io.enq.valid := fire
  sinkQ.io.enq.bits := io.sources(choice).bits
  io.sources.zipWithIndex.foreach { case (source, i) =>
    source.ready := fire && choice === i.U
  }

  when(fire) {
    lastChoice := choice
  }
}

object BankedQueue {
  /** Width-banked equivalent of elastic.SourceBuffer. */
  def sourceBuffer[T <: Data](
      source: ReadyValidIO[T],
      entries: Int = 2,
      bankWidth: Int = 64,
      pipe: Boolean = false,
      flow: Boolean = false
  ): DecoupledIO[T] = {
    val q = Module(
      new BankedQueue(
        chiselTypeOf(source.bits),
        entries,
        bankWidth = bankWidth,
        pipe = pipe,
        flow = flow
      )
    )
    q.io.enq.valid := source.valid
    q.io.enq.bits := source.bits
    source.ready := q.io.enq.ready
    q.io.deq
  }
}
