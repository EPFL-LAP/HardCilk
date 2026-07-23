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
    val saverWidth: Int
) extends Bundle {
  val update = new SlowUpdate(lineAddressWidth, continuationSize)
  val requiredDone = UInt(counterWidth.W)
  val saver = UInt(saverWidth.W)
}

/** Orders cache evictions against slow updates before the two paths separate.
  *
  * Evictions are address-demuxed across `nEvictionSaverLanes` independent
  * CacheEvictionSavers, each its own ordered AXI write stream. This gater
  * keeps one pendingCount/doneCount pair PER SAVER LANE (all the same
  * width/depth -- there is deliberately no per-lane JSON sizing, `saverOf`
  * alone selects which lane's pair an eviction or update uses): pendingCount
  * (k) counts evictions accepted for lane k so far; doneCount(k) counts lane
  * k's ordered AXI B responses. A slow update depends only on the eviction of
  * ITS OWN line, which `saverOf` always routes to the same lane as the
  * update itself, so the update fences on that one lane's pair.
  *
  * This is deliberately a per-lane fence, not a per-address one: an update
  * may wait for unrelated earlier evictions on its lane, but no associative
  * key table or finite outstanding-write reservation is required. Correctness
  * requires `saverOf` to be the SAME function the eviction network uses to
  * route the physical write, so a lane's completion order always matches its
  * acceptance order (constant per-lane AXI IDs guarantee the B stream is that
  * lane's ordered prefix).
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
    counterWidth: Int = 64
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
    laneWidth
  )
  private def sequencedUpdateType =
    new SequencedSlowUpdate(
      lineAddressWidth,
      continuationSize,
      counterWidth,
      saverWidth
    )

  val io = IO(new Bundle {
    val coupledIn = Flipped(Decoupled(coupledType))
    val evictionOut = Decoupled(taggedEvictionType)
    val slowUpdateOut =
      Decoupled(new SlowUpdate(lineAddressWidth, continuationSize))
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
    new Queue(sequencedUpdateType, slowRequestDepth)
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
  // it is that update's OWN line (the resolution-collision pairing built in
  // ArgumentServer) -- which `saverOf`, being a pure function of address,
  // always sends to the same lane as the update itself. An eviction sharing
  // this entry by pure cycle coincidence (an unrelated line, possibly a
  // different lane) must not gate the update's lane count; if it happens to
  // land on the same lane anyway, counting it is still safe -- that lane's
  // own completion stream will retire it regardless, exactly as the prior
  // single-lane fence could always wait on unrelated evictions.
  private val evictionIsOwnLine = needsEviction && (evictionSaver === updateSaver)

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
