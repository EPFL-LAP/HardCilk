package NewArgumentNotifier

import chisel3._
import chisel3.util._

/** A slow update plus the eviction-completion count it must observe and the
  * saver lane that count is tracked on.
  */
class SequencedSlowUpdate(
    val lineAddressWidth: Int,
    val continuationSize: Int,
    val counterWidth: Int,
    val saverWidth: Int,
    val payloadWidth: Int = 0,
    val offsetWidth: Int = 0
) extends Bundle {
  val update =
    new SlowUpdate(
      lineAddressWidth,
      continuationSize,
      payloadWidth,
      offsetWidth
    )
  val requiredDone = UInt(counterWidth.W)
  val saver = UInt(saverWidth.W)
}

/** Orders cache evictions against slow updates before the two paths separate.
  */
class EvictionGater(
    lineAddressWidth: Int,
    continuationSize: Int,
    serverTagWidth: Int,
    serverIDWidth: Int,
    laneWidth: Int,
    nEvictionSaverLanes: Int,
    saverOf: UInt => UInt,
    slowRequestDepth: Int = 64,
    counterWidth: Int = 64,
    // Slow updates stay compact through the gater; it never reads their data.
    payloadWidth: Int = 0,
    offsetWidth: Int = 0
) extends Module {
  require(slowRequestDepth >= 1)
  require(counterWidth >= 1)
  require(nEvictionSaverLanes >= 1)

  private val saverWidth = math.max(1, log2Ceil(nEvictionSaverLanes))

  private def metadataType =
    new ContinuationMetadata(serverTagWidth, serverIDWidth, laneWidth)
  private def taggedEvictionType = new TaggedEvictedContinuation(
    lineAddressWidth,
    continuationSize,
    serverTagWidth,
    serverIDWidth,
    laneWidth
  )
  private def coupledType = new CoupledSlowPathEntry(
    lineAddressWidth,
    continuationSize,
    serverTagWidth,
    serverIDWidth,
    laneWidth,
    payloadWidth,
    offsetWidth
  )
  private def sequencedUpdateType =
    new SequencedSlowUpdate(
      lineAddressWidth,
      continuationSize,
      counterWidth,
      saverWidth,
      payloadWidth,
      offsetWidth
    )

  val io = IO(new Bundle {
    val coupledIn = Flipped(Decoupled(coupledType))
    val evictionOut = Decoupled(taggedEvictionType)
    val slowUpdateOut =
      Decoupled(
        new SlowUpdate(
          lineAddressWidth,
          continuationSize,
          payloadWidth,
          offsetWidth
        )
      )
    // One per eviction-saver lane. Constant per-lane AXI IDs guarantee each
    // is its own ordered prefix, so completions are always immediately
    // consumed -- no ready/backpressure is needed or offered.
    val writeCompleted = Vec(nEvictionSaverLanes, Flipped(Valid(metadataType)))
  })

  private val pendingCounts =
    RegInit(VecInit(Seq.fill(nEvictionSaverLanes)(0.U(counterWidth.W))))
  private val doneCounts =
    RegInit(VecInit(Seq.fill(nEvictionSaverLanes)(0.U(counterWidth.W))))
  private val slowRequestQ = Module(
    new BankedQueue(sequencedUpdateType, slowRequestDepth)
  )

  private val needsEviction = io.coupledIn.bits.evictionValid
  private val needsUpdate = io.coupledIn.bits.updateValid
  private val evictionAccepted = !needsEviction || io.evictionOut.ready
  private val updateAccepted = !needsUpdate || slowRequestQ.io.enq.ready

  // A coupled entry remains atomic: if it contains both an eviction and its
  // colliding update, both outputs reserve their downstream space together.
  io.coupledIn.ready := evictionAccepted && updateAccepted
  io.evictionOut.valid :=
    io.coupledIn.valid && needsEviction && updateAccepted
  io.evictionOut.bits := io.coupledIn.bits.eviction

  private val evictionSaver =
    saverOf(io.coupledIn.bits.eviction.eviction.address)
  private val updateSaver = saverOf(io.coupledIn.bits.update.update.address)
  // A coupled eviction is only a same-cycle prerequisite for the update when
  // it is that update's OWN line.
  private val evictionIsOwnLine =
    needsEviction && (evictionSaver === updateSaver)

  slowRequestQ.io.enq.valid :=
    io.coupledIn.valid && needsUpdate && evictionAccepted
  slowRequestQ.io.enq.bits.update := io.coupledIn.bits.update.update
  slowRequestQ.io.enq.bits.saver := updateSaver
  // `pendingCounts(updateSaver)` is the number of lane-updateSaver evictions
  // accepted before this entry. A same-line eviction coupled into this same
  // atomic entry is also a prerequisite and hasn't incremented the register
  // yet (that happens on the next edge), hence the +1.
  slowRequestQ.io.enq.bits.requiredDone :=
    pendingCounts(updateSaver) + evictionIsOwnLine.asUInt

  private val headSaver = slowRequestQ.io.deq.bits.saver
  private val releaseEligible =
    slowRequestQ.io.deq.bits.requiredDone <= doneCounts(headSaver)
  io.slowUpdateOut.valid := slowRequestQ.io.deq.valid && releaseEligible
  io.slowUpdateOut.bits := slowRequestQ.io.deq.bits.update
  slowRequestQ.io.deq.ready := io.slowUpdateOut.ready && releaseEligible

  when(io.coupledIn.fire && needsEviction) {
    pendingCounts(evictionSaver) := pendingCounts(evictionSaver) + 1.U
  }
  for (k <- 0 until nEvictionSaverLanes) {
    when(io.writeCompleted(k).valid) {
      doneCounts(k) := doneCounts(k) + 1.U
    }
  }
}
