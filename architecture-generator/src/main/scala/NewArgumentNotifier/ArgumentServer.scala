package NewArgumentNotifier

import chisel3._
import chisel3.util._

object ArgumentNotifierHelpers {

  /** Lane-select width; at least 1 bit so NParallelNew == 1 still elaborates.
    */
  def laneWidth(nParallelNew: Int): Int = math.max(1, log2Ceil(nParallelNew))
}

/** Location of a cached continuation. */
class ContinuationMetadata(
    val serverTagWidth: Int,
    val serverIDWidth: Int,
    val laneWidth: Int
) extends Bundle {
  val server = UInt(serverTagWidth.W)
  val id = UInt(serverIDWidth.W)
  val lane = UInt(laneWidth.W)
}

class ContinuationReference(
    val lineAddressWidth: Int,
    val serverTagWidth: Int,
    val serverIDWidth: Int,
    val laneWidth: Int
) extends Bundle {
  val address = UInt(lineAddressWidth.W)
  val metadata =
    new ContinuationMetadata(serverTagWidth, serverIDWidth, laneWidth)
}

/** Request to insert a freshly written continuation into a server's cache. */
class NewContinuationReq(val lineAddressWidth: Int, val continuationSize: Int)
    extends Bundle {
  val address = UInt(lineAddressWidth.W)
  val taskBaseData = UInt(continuationSize.W)
}

class DelayedNewContinuation(
    val lineAddressWidth: Int,
    val continuationSize: Int,
    val serverIDWidth: Int
) extends Bundle {
  val id = UInt(serverIDWidth.W)
  val address = UInt(lineAddressWidth.W)
  val taskBaseData = UInt(continuationSize.W)
}

class NewContinuationPort(
    val lineAddressWidth: Int,
    val continuationSize: Int,
    val serverIDWidth: Int,
    val laneWidth: Int
) extends Bundle {
  val req = Flipped(
    Decoupled(new NewContinuationReq(lineAddressWidth, continuationSize))
  )

  // We assign an ID (the cache slot) as the request is accepted
  val assignedId = Output(UInt(serverIDWidth.W))
  val assignedLane = Output(UInt(laneWidth.W))
}

class ContinuationUpdate(
    val lineAddressWidth: Int,
    val serverTagWidth: Int,
    val serverIDWidth: Int,
    val laneWidth: Int,
    val payloadWidth: Int,
    val offsetWidth: Int
) extends Bundle {
  val address = UInt(lineAddressWidth.W)
  val metadata =
    new ContinuationMetadata(serverTagWidth, serverIDWidth, laneWidth)
  val payload = UInt(payloadWidth.W)
  val offset = if (offsetWidth > 0) Some(UInt(offsetWidth.W)) else None
}

class SpawnedTask(val continuationSize: Int) extends Bundle {
  val taskData = UInt(continuationSize.W)
}

class EvictedContinuation(val lineAddressWidth: Int, val continuationSize: Int)
    extends Bundle {
  val address = UInt(lineAddressWidth.W)
  val taskData = UInt(continuationSize.W)
}

class SlowUpdate(
    val lineAddressWidth: Int,
    val continuationSize: Int,
    val payloadWidth: Int = 0,
    val offsetWidth: Int = 0
) extends Bundle {
  val effectivePayloadWidth =
    if (payloadWidth == 0) continuationSize else payloadWidth
  require(effectivePayloadWidth <= continuationSize)
  require(continuationSize % effectivePayloadWidth == 0)

  val address = UInt(lineAddressWidth.W)
  val payload = UInt(effectivePayloadWidth.W)
  val offset = if (offsetWidth > 0) Some(UInt(offsetWidth.W)) else None

  def expanded: UInt = {
    if (effectivePayloadWidth == continuationSize) {
      payload
    } else {
      val bitShift = offset.get << log2Ceil(effectivePayloadWidth)
      (payload.pad(continuationSize) << bitShift)(continuationSize - 1, 0)
    }
  }
}

/** An eviction plus the cache slot that produced it. The compact metadata is
  * carried through the eviction ring so the originating EvictionGater can be
  * notified when the HBM write completes.
  */
class TaggedEvictedContinuation(
    val lineAddressWidth: Int,
    val continuationSize: Int,
    val serverTagWidth: Int,
    val serverIDWidth: Int,
    val laneWidth: Int
) extends Bundle {
  val eviction = new EvictedContinuation(lineAddressWidth, continuationSize)
  val metadata =
    new ContinuationMetadata(serverTagWidth, serverIDWidth, laneWidth)
}

/** A cache-missed update plus the cache slot it references. */
class TaggedSlowUpdate(
    val lineAddressWidth: Int,
    val continuationSize: Int,
    val serverTagWidth: Int,
    val serverIDWidth: Int,
    val laneWidth: Int,
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
  val metadata =
    new ContinuationMetadata(serverTagWidth, serverIDWidth, laneWidth)
}

class CoupledSlowPathEntry(
    val lineAddressWidth: Int,
    val continuationSize: Int,
    val serverTagWidth: Int,
    val serverIDWidth: Int,
    val laneWidth: Int,
    val payloadWidth: Int = 0,
    val offsetWidth: Int = 0
) extends Bundle {
  val spawnValid = Bool()
  val evictionValid = Bool()

  val eviction = new TaggedEvictedContinuation(
    lineAddressWidth,
    continuationSize,
    serverTagWidth,
    serverIDWidth,
    laneWidth
  )
  val updateValid = Bool()
  val update = new TaggedSlowUpdate(
    lineAddressWidth,
    continuationSize,
    serverTagWidth,
    serverIDWidth,
    laneWidth,
    payloadWidth,
    offsetWidth
  )
}

class ContinuationLine(val counterWidth: Int, val continuationSize: Int)
    extends Bundle {

  val remainder = UInt((continuationSize - counterWidth).W)
  val counter = UInt(counterWidth.W)
}

//The new ArgumentServer. It has N parallel pipes, and handles the caching. If a counter goes to 0, it sends a notification out through the existing channel. When cached items or lookups hit the end of the line, they get sent to the SlowArgumentHandler.
class ArgumentServerIO(
    counterWidth: Int,
    lineAddressWidth: Int,
    serverTagWidth: Int,
    serverIDWidth: Int,
    continuationSize: Int,
    NParallelNew: Int,
    NParallelUpdate: Int,
    updatePayloadWidth: Int,
    updateOffsetWidth: Int,
    enableRecycling: Boolean
) extends Bundle {
  private val laneW = ArgumentNotifierHelpers.laneWidth(NParallelNew)

  val newContInput = Vec(
    NParallelNew,
    new NewContinuationPort(
      lineAddressWidth,
      continuationSize,
      serverIDWidth,
      laneW
    )
  )
  val contUpdateInput = Vec(
    NParallelUpdate,
    Flipped(
      Decoupled(
        new ContinuationUpdate(
          lineAddressWidth,
          serverTagWidth,
          serverIDWidth,
          laneW,
          updatePayloadWidth,
          updateOffsetWidth
        )
      )
    )
  )

  val spawnTaskOutputs =
    Vec(NParallelNew, Decoupled(new SpawnedTask(continuationSize)))

  // Address of each continuation as it resolves, for the recycler. A resolved
  // line's data has already been merged into the spawned task, so its storage is
  // dead from this moment and the address can go back on the free list. Valid,
  // never Decoupled: the recycler should never reject an address.
  val resolvedAddressOut =
    if (enableRecycling)
      Some(Vec(NParallelNew, Valid(UInt(lineAddressWidth.W))))
    else None

  val coupledSlowPath = Vec(
    NParallelNew,
    Decoupled(
      new CoupledSlowPathEntry(
        lineAddressWidth,
        continuationSize,
        serverTagWidth,
        serverIDWidth,
        laneW,
        updatePayloadWidth,
        updateOffsetWidth
      )
    )
  )
}

class ArgumentServer(
    counterWidth: Int,
    lineAddressWidth: Int,
    serverTagWidth: Int,
    serverIDWidth: Int,
    continuationSize: Int,
    NParallelNew: Int,
    NParallelUpdate: Int,
    serverIndex: Int = 0,
    cacheDelayCycles: Int = 0,
    // Extra coupledQ slots reserved for missed updates that have NO co-cycle
    // eviction (an update for a line evicted earlier). Sized so the slow-path
    // backlog never has to backpressure into the eviction pool.
    missedUpdateExtra: Int = 64,
    // Zero is an internal convenience default for direct ArgumentServer users:
    // it resolves to a full-continuation payload.  Descriptor/config paths pass
    // the validated physical width explicitly.
    updatePayloadWidth: Int = 0,
    updateOffsetWidth: Int = 0,
    // Expose each resolution's line address so the recycler can return it to the
    // allocator's free list.
    enableRecycling: Boolean = false,
    // Depth at which the front porch switches from a shift register to URAM.
    // Effectively off by default -- see Util.DelayLine.defaultUramThreshold for
    // the measurement that disabled it.
    porchUramThreshold: Int = Util.DelayLine.defaultUramThreshold
) extends Module {

  require(cacheDelayCycles >= 0)
  require(missedUpdateExtra >= 1)
  private val effectiveUpdatePayloadWidth =
    if (updatePayloadWidth == 0) continuationSize else updatePayloadWidth
  require(
    isPow2(effectiveUpdatePayloadWidth) &&
      effectiveUpdatePayloadWidth <= continuationSize
  )
  require(continuationSize % effectiveUpdatePayloadWidth == 0)
  private val updateSlotCount = continuationSize / effectiveUpdatePayloadWidth
  require(isPow2(updateSlotCount))
  require(updateOffsetWidth == log2Ceil(updateSlotCount))

  val io = IO(
    new ArgumentServerIO(
      counterWidth,
      lineAddressWidth,
      serverTagWidth,
      serverIDWidth,
      continuationSize,
      NParallelNew,
      NParallelUpdate,
      effectiveUpdatePayloadWidth,
      updateOffsetWidth,
      enableRecycling
    )
  )

  private def lineType = new ContinuationLine(counterWidth, continuationSize)
  private def expandPayload(update: ContinuationUpdate): UInt = {
    if (effectiveUpdatePayloadWidth == continuationSize) {
      update.payload
    } else {
      val bitShift = update.offset.get << log2Ceil(effectiveUpdatePayloadWidth)
      (update.payload.pad(continuationSize) << bitShift)(
        continuationSize - 1,
        0
      )
    }
  }
  private val cacheDepth = 1 << serverIDWidth
  private val idleFlushCycles = 10
  private val idleCountWidth = log2Ceil(idleFlushCycles + 1)

  // ---- Non-backpressuring front porch (reservation) ----
  // The porch must never stall internally: a continuation delayed past the fixed
  // arrival time of its memReader-timed update would miss a not-yet-inserted line
  // and read unsaved HBM. All backpressure lives at the porch ENTRANCE
  // (newContInput.req.ready), where the initiator dispatches the continuation and
  // its memReader task atomically.
  //
  // The resolution FIFO must always have room for the spawn or eviction an insert
  // produces. An insert only resolves a valid old line once the cache is full --
  // before that it fills an empty slot and produces nothing -- so the admission
  // cap is (residentCapacity + resolution pool): throttling starts only once
  // resolutions can actually be produced. Spawns and evictions share that
  // reservation. Missed updates for genuinely-evicted lines ride the same physical
  // FIFO for gater ordering but draw from a separate logical share, so a miss
  // backlog cannot wedge a resolution. With no porch, retain two slots so the
  // queue can sustain II=1.
  private val resolutionPoolDepth = math.max(2, cacheDelayCycles)
  private val missedPoolDepth =
    math.max(1, missedUpdateExtra) // pure missed-update pool
  // Normal insertion resolves id+1, so one ring slot is always the separation
  // point between the insertion head and the far-end resolution: at most
  // (cacheDepth-1) continuations are resident simultaneously.
  private val residentCapacity = cacheDepth - 1
  // A resolved cache line stays charged to inFlight for four cycles after its
  // slot is freed: issue the synchronous read, classify/enqueue the result, then
  // the two stages of the BRAM-backed coupledQ. Keeping those stages from stealing
  // capacity from a full cache + porch is what preserves II=1, and it keeps
  //   resolutionInCq <= admitCap - residentCapacity
  // exact.
  private val resolutionTailCredits = 4
  private val admitCap =
    residentCapacity + resolutionPoolDepth + resolutionTailCredits
  // Admission alone bounds the undrained resolution backlog, because the porch
  // runs ungated (see cacheInsertReadies) and a resolution cannot be
  // backpressured. inFlight = porch + cache-resident + resolution-pipeline +
  // resolutions-in-coupledQ. Idle flushes punch holes in the cache; flushHoles
  // below keeps those slots charged until a later insertion's clear step absorbs
  // each hole. Admission holds inFlight + flushHoles at the entrance, so
  //   resolutionInCq <= admitCap - residentCapacity
  // and the bound is exact rather than conservative: the cache is necessarily full
  // whenever resolutionInCq peaks, since a resolution is only produced when the
  // ring wraps onto a VALID slot. The peak is a burst to admitCap (porch full,
  // cache full), the entrance blocking, then the porch draining ungated with every
  // entry -- plus the resolutionTailCredits, which are admissions granted beyond
  // porch + cache -- converting into a parked resolution.
  private val resolutionQueueDepth = admitCap - residentCapacity
  private val coupledQueueDepth = resolutionQueueDepth + missedPoolDepth
  private val laneW = ArgumentNotifierHelpers.laneWidth(NParallelNew)

  // Per-lane reservation counters (declared here; driven in the resolution loop).
  //  inFlight   = continuations admitted but not yet resolved out of {porch,cache,
  //               coupledQ-resolution}. +1 on admit; -1 when its spawn/eviction entry
  //               drains from coupledQ. Gates admission together with flushHoles.
  //  resolutionInCq = spawn/eviction entries resident in coupledQ. Together with
  //               the issue-stage guard, ensures a resolution is never produced into
  //               a full reserved share.
  //  missedInCq = pure missed updates (no co-cycle resolution) resident in coupledQ.
  //               Guards their enqueue at missedPoolDepth so a miss backlog never wedges
  //               a resolution. A coupled update riding a spawn/eviction entry is free.
  //  flushHoles = valid slots cleared by idle flush and not yet absorbed by a normal
  //               insertion resolving an invalid slot. Keeps effective occupancy
  //               charged while the cache is below residentCapacity.
  private val inFlight =
    Seq.fill(NParallelNew)(RegInit(0.U((log2Ceil(admitCap + 2)).W)))
  private val resolutionInCq =
    Seq.fill(NParallelNew)(RegInit(0.U((log2Ceil(coupledQueueDepth + 2)).W)))
  private val missedInCq =
    Seq.fill(NParallelNew)(RegInit(0.U((log2Ceil(missedPoolDepth + 2)).W)))
  // A flush moves a valid line out of the cache without an accompanying insert.
  // Until a normal insertion resolves an already-invalid slot, that cache hole
  // must remain charged against admission; otherwise the freed slot invalidates
  // the resolutionQueueDepth sizing argument.
  val flushHoles =
    Seq.fill(NParallelNew)(RegInit(0.U(log2Ceil(cacheDepth + 1).W)))
  // The true cache deficit: residentCapacity minus the number of valid slots.
  // Derived from the same events that write cacheValid rather than reconstructed
  // from flush/insert events, which can drift; a count that reads too low
  // over-admits and breaks the
  //   resolutionInCq <= admitCap - residentCapacity
  // sizing argument, silently dropping a resolution when the coupledQ is exactly
  // full. Admission gates on this.
  //
  // Maintained as a deficit rather than a resident count so the admission gate
  // keeps the shape `inFlight + <register>` and no new arithmetic lands on that
  // path. Reset: the cache starts empty, so every usable slot is missing.
  val cacheDeficit =
    Seq.fill(NParallelNew)(
      RegInit(residentCapacity.U(log2Ceil(cacheDepth + 1).W))
    )

  private def coupledType = new CoupledSlowPathEntry(
    lineAddressWidth,
    continuationSize,
    serverTagWidth,
    serverIDWidth,
    laneW,
    effectiveUpdatePayloadWidth,
    updateOffsetWidth
  )
  private def taggedSlowType = new TaggedSlowUpdate(
    lineAddressWidth,
    continuationSize,
    serverTagWidth,
    serverIDWidth,
    laneW,
    effectiveUpdatePayloadWidth,
    updateOffsetWidth
  )

  // Both large per-lane payload stores are synchronous-read memories so they
  // infer BRAM rather than LUTRAM. Each has one read port (tail resolution) and
  // one write port (insertion / update), which is exactly a simple dual-port
  // block RAM.
  val cacheBaseStores = Seq.fill(NParallelNew)(
    SyncReadMem(cacheDepth, UInt(continuationSize.W))
  )
  val cacheIDStores =
    Seq.fill(NParallelNew)(Mem(cacheDepth, UInt(lineAddressWidth.W)))

  // The update store is a row of `updateSlotCount` payload-sized elements per
  // cache slot, written with a per-element mask. A masked element write is a BRAM
  // write-enable lane, so an update never has to read the row first. One update
  // payload is exactly one element, so distinct children never collide and
  // repeated writes to one element are last-write-wins.
  //
  // The row is split across several memories because Vivado's RAM inference gives
  // up past a certain number of mask lanes, dropping the whole array into
  // flip-flops instead of BRAM. Splitting keeps every store at or below a lane
  // count that infers; an update still writes exactly one element of exactly one
  // store, so the semantics are unchanged.
  private val maxSlotsPerStore = 16
  private val slotsPerStore = math.min(updateSlotCount, maxSlotsPerStore)
  private val updateStoreCount = updateSlotCount / slotsPerStore
  require(
    updateStoreCount * slotsPerStore == updateSlotCount,
    "update slots must divide evenly across the payload stores"
  )
  // Store s holds slots [s*slotsPerStore, (s+1)*slotsPerStore); concatenating
  // the stores in order reproduces the original flat row exactly.
  val updateStores = Seq.fill(NParallelNew)(
    Seq.fill(updateStoreCount)(
      SyncReadMem(
        cacheDepth,
        Vec(slotsPerStore, UInt(effectiveUpdatePayloadWidth.W))
      )
    )
  )

  // The update store has exactly ONE write port, and an insert and an update are
  // independently sourced (porch vs perLaneFIFO), routinely landing in the same
  // cycle on different slots. This per-slot bit stands in for the insert's write:
  // false means "the update row for this slot is stale, read it as zero". Insert
  // clears it; the first update to the slot sets it and, in the same access,
  // writes every element (its own payload plus zeros elsewhere), which is the
  // deferred clear. Both read sites substitute zero while it is false, so no stale
  // payload survives into the resolution merge.
  val deltaValid = Seq.fill(NParallelNew)(
    RegInit(VecInit(Seq.fill(cacheDepth)(false.B)))
  )
  // Same reasoning for the remaining-argument countdown, seeded by the insert and
  // decremented by the update: as a Mem those are two writers, as a register file
  // they are independent writes to different indices. It is only
  // cacheDepth x counterWidth bits. This register file is also the only
  // join-counter record; the update store holds payload slices only.
  val remainingCounterRegs = Seq.fill(NParallelNew)(
    RegInit(VecInit(Seq.fill(cacheDepth)(0.U(counterWidth.W))))
  )
  val cacheBaseStoresHead =
    Seq.fill(NParallelNew)(RegInit(0.U(serverIDWidth.W)))
  val cacheValid = Seq.fill(NParallelNew)(
    RegInit(VecInit(Seq.fill(cacheDepth)(false.B)))
  )
  val cacheDone = Seq.fill(NParallelNew)(
    RegInit(VecInit(Seq.fill(cacheDepth)(false.B)))
  )
  val doneCounts = Seq.fill(NParallelNew)(
    RegInit(0.U((serverIDWidth + 1).W))
  )
  val idleCounts = Seq.fill(NParallelNew)(
    RegInit(0.U(idleCountWidth.W))
  )
  val flushScanAddresses = Seq.fill(NParallelNew)(
    RegInit(0.U(serverIDWidth.W))
  )

  private def delayedNewType =
    new DelayedNewContinuation(
      lineAddressWidth,
      continuationSize,
      serverIDWidth
    )

  // The request-side pointer reserves metadata immediately.  Cache insertion
  // happens later, when the corresponding entry leaves the front porch.
  val cacheInsertValids = Wire(Vec(NParallelNew, Bool()))
  val cacheInsertReadies = Wire(Vec(NParallelNew, Bool()))
  val cacheInsertBits = Wire(Vec(NParallelNew, delayedNewType))
  val cacheInsertFires = Wire(Vec(NParallelNew, Bool()))
  for (i <- 0 until NParallelNew) {
    cacheInsertReadies(i) := false.B
    cacheInsertFires(i) := cacheInsertValids(i) && cacheInsertReadies(i)

    io.newContInput(i).assignedId := cacheBaseStoresHead(i)
    io.newContInput(i).assignedLane := i.U

    if (cacheDelayCycles == 0) {
      // Admission gate: keep accepting while total in-flight work is below cache
      // size + the resolution reservation. This is the only porch backpressure.
      // It must qualify the INSERT as well as the handshake -- gating req.ready
      // alone would let cacheInsertFires insert an uncharged continuation while
      // admission is refusing it.
      val admitOk = (inFlight(i) +& cacheDeficit(i)) < admitCap.U
      cacheInsertValids(i) := io.newContInput(i).req.valid && admitOk
      cacheInsertBits(i).id := cacheBaseStoresHead(i)
      cacheInsertBits(i).address := io.newContInput(i).req.bits.address
      cacheInsertBits(i).taskBaseData :=
        io.newContInput(i).req.bits.taskBaseData
      io.newContInput(i).req.ready := cacheInsertReadies(i) && admitOk
    } else {
      // The porch is a fixed-latency delay line, so it is spelled as one:
      // Util.DelayLine picks its implementation from the depth.
      val porch = Module(
        new Util.DelayLine(delayedNewType, cacheDelayCycles, porchUramThreshold)
      )

      val porchIn = Wire(delayedNewType)
      porchIn.id := cacheBaseStoresHead(i)
      porchIn.address := io.newContInput(i).req.bits.address
      porchIn.taskBaseData := io.newContInput(i).req.bits.taskBaseData
      porch.io.in := porchIn

      // MUST be req.fire, not req.valid. req.ready adds the admission gate on top
      // of porchCanAdvance, so shifting in on `valid` alone lets a continuation
      // enter the porch while admission is refusing it: inFlight and
      // cacheBaseStoresHead (both advanced on `fire`) never see it, and up to
      // cacheDelayCycles uncharged continuations become resolutions, breaking
      //   resolutionInCq <= admitCap - residentCapacity
      // and overflowing the coupledQ. It would also re-use assignedId, since the
      // source retries a request it never saw accepted.
      //
      // Only the VALID is qualified; the payload rides in unconditionally, which
      // is what lets the payload path carry neither an enable nor a reset and so
      // become an SRL or a URAM. The tail payload is read only through
      // cacheInsertBits, and every consumer of that qualifies on cacheInsertFires.
      porch.io.inValid := io.newContInput(i).req.fire

      // A bubble advances just like a valid entry; only a blocked valid tail
      // freezes the shift register and backpressures its source. The entrance is
      // the only backpressure, so a continuation never sits in the porch longer
      // than cacheDelayCycles and stays time-aligned with its memReader update.
      val porchCanAdvance = !porch.io.outValid || cacheInsertReadies(i)
      io.newContInput(i).req.ready := porchCanAdvance &&
        ((inFlight(i) +& cacheDeficit(i)) < admitCap.U)

      // A DelayLine has no enable and cannot be held, so porchCanAdvance must stay
      // true. The condition is structural today (cacheInsertReadies is tied high);
      // this fails loudly in simulation rather than silently dropping a porch
      // entry if that ever changes.
      assert(
        porchCanAdvance,
        "ArgumentServer front porch was asked to stall, but a DelayLine " +
          "cannot be held: a porch entry would be lost."
      )

      cacheInsertValids(i) := porch.io.outValid
      cacheInsertBits(i) := porch.io.out
    }

    when(io.newContInput(i).req.fire) {
      cacheBaseStoresHead(i) := cacheBaseStoresHead(i) + 1.U
    }
  }

  // These wires also protect the update write below from a same-cycle cache
  // insertion or resolution. Unlike the old head/head-1 exclusion, the
  // protection lasts only for the cycle in which there is a real collision.
  val resolutionIssued = Wire(Vec(NParallelNew, Bool()))
  val resolutionAddresses = Wire(Vec(NParallelNew, UInt(serverIDWidth.W)))
  val resolutionRemovesDone = Wire(Vec(NParallelNew, Bool()))
  val updateMakesDone = Wire(Vec(NParallelNew, Bool()))
  val updateTargetIds = Wire(Vec(NParallelNew, UInt(serverIDWidth.W)))
  // High on the cycle a hitting update commits its masked write. Named so that
  // the insert/update and resolution/update coincidences -- the cases that used
  // to contend for a single store write port -- are observable from a testbench.
  val updateApplied = Wire(Vec(NParallelNew, Bool()))
  // High while an update at the cache lookup targets the very slot being
  // inserted this cycle, so it is held rather than treated as a miss. Exposed
  // for the same reason as updateApplied.
  val insertCollisions = Wire(Vec(NParallelNew, Bool()))
  for (i <- 0 until NParallelNew) {
    updateApplied(i) := false.B
    insertCollisions(i) := false.B
    resolutionIssued(i) := false.B
    resolutionAddresses(i) := 0.U
    resolutionRemovesDone(i) := false.B
    updateMakesDone(i) := false.B
    updateTargetIds(i) := 0.U
  }

  // Commit the matured porch entry to the searchable cache.
  for (i <- 0 until NParallelNew) {
    when(cacheInsertFires(i)) {
      val insertedLine = cacheInsertBits(i).taskBaseData.asTypeOf(lineType)
      cacheBaseStores(i).write(
        cacheInsertBits(i).id,
        cacheInsertBits(i).taskBaseData
      )
      cacheIDStores(i).write(
        cacheInsertBits(i).id,
        cacheInsertBits(i).address
      )
      // Retire the recycled slot's update row instead of zeroing updateStores, and
      // seed the countdown. Both are registers, so neither contends with an update
      // landing on another slot this cycle. cacheValid for this slot is set in the
      // same cycle, so the slot becomes matchable exactly when its row is declared
      // stale; an update can never observe the previous tenant's payloads.
      deltaValid(i)(cacheInsertBits(i).id) := false.B
      remainingCounterRegs(i)(cacheInsertBits(i).id) := insertedLine.counter
    }
  }

  // One physical queue per cache lane couples resolutions and missed updates.
  // A one-cycle update staging queue is load-bearing: a resolution collision is
  // detected in the issue cycle, while SyncReadMem produces its eviction in the
  // following cycle. Staging aligns the two so both can occupy one atomic entry.
  //
  // This is the one deep, continuation-wide FIFO in the lane, so it is a normal
  // wide queue backed by SyncReadMem (BRAM).
  //
  // BramQueue rather than Queue(useSyncReadMem = true): the stock Chisel queue
  // reads the slot it writes in the same cycle and so needs cross-port write-first
  // read-under-write, which a Xilinx BRAM does not provide. It costs one extra
  // cycle of latency on an empty -> nonempty transition, covered by
  // resolutionTailCredits.
  val coupledQs = Seq.fill(NParallelNew)(
    Module(new BramQueue(coupledType, coupledQueueDepth))
  )
  val delayedMissQs = Seq.fill(NParallelNew)(
    Module(new BankedQueue(taggedSlowType, 2))
  )
  val spawnValids = Wire(Vec(NParallelNew, Bool()))
  val evictionValids = Wire(Vec(NParallelNew, Bool()))
  val evictionBits = Wire(
    Vec(
      NParallelNew,
      new TaggedEvictedContinuation(
        lineAddressWidth,
        continuationSize,
        serverTagWidth,
        serverIDWidth,
        laneW
      )
    )
  )
  for (i <- 0 until NParallelNew) {
    spawnValids(i) := false.B
    evictionValids(i) := false.B
    evictionBits(i) := 0.U.asTypeOf(evictionBits(i))

    // Charge-by-type enqueue. A resolution (spawn OR eviction) always enqueues using
    // its porch-reserved slot. A coupled update rides free in that entry. Only a pure
    // missed update (no co-cycle resolution) draws from the missed-update share.
    val hasSpawn = spawnValids(i)
    val hasEviction = evictionValids(i)
    val hasResolution = hasSpawn || hasEviction
    val hasUpdate = delayedMissQs(i).io.deq.valid
    val isPureUpdate = hasUpdate && !hasResolution
    val missedRoom = missedInCq(i) < missedPoolDepth.U
    coupledQs(i).io.enq.valid := hasResolution || (isPureUpdate && missedRoom)
    // A resolution is a one-cycle pulse off the RegNext pipeline, so a full queue
    // would silently drop it, and with it a release. admitCap +
    // resolutionQueueDepth are what cover the porch-empty worst case.
    assert(
      !(hasResolution && !coupledQs(i).io.enq.ready),
      "ArgumentServer: resolution met a full coupledQ; a release would be lost"
    )
    coupledQs(i).io.enq.bits.spawnValid := hasSpawn
    coupledQs(i).io.enq.bits.evictionValid := hasEviction
    coupledQs(i).io.enq.bits.eviction := evictionBits(i)
    coupledQs(
      i
    ).io.enq.bits.updateValid := hasUpdate && (hasResolution || missedRoom)
    coupledQs(i).io.enq.bits.update := delayedMissQs(i).io.deq.bits
    delayedMissQs(i).io.deq.ready :=
      coupledQs(
        i
      ).io.enq.ready && (hasResolution || (isPureUpdate && missedRoom))
  }

  // Read from back and fire/forward
  //
  // Resolution is tied to a committed cache insert: when a matured porch entry is
  // written at `id`, the line at `id + 1` (the far end of the cache ring) is
  // inspected one cycle later and either spawned (counter reached zero) or evicted
  // to the slow path. The result lands in a small skid queue so a busy consumer
  // can never drop or double-count a resolution.
  for (i <- 0 until NParallelNew) {
    val spawnQ = Module(new BankedQueue(new SpawnedTask(continuationSize), 4))
    // Reserve room for the one-cycle resolution pipeline as well as entries
    // already in coupledQ. At count <= depth-2, this cycle and the already-issued
    // prior cycle can both resolve without overflow. Pure missed updates use their
    // own share and cannot block this gate.
    val resolutionHasRoom =
      resolutionInCq(i) <= (resolutionPoolDepth - 2).U
    // The porch never stalls. Room for an insert's resolution is guaranteed by
    // sizing (admitCap + resolutionQueueDepth), not by gating the porch here:
    // gating on resolutionInCq can freeze a lone straggler at the porch tail, and
    // a frozen porch entry is inserted later than dispatch + cacheDelayCycles, so
    // its memReader-timed update arrives while the line is still in the porch,
    // misses, takes the slow path and reads unsaved HBM. Only the idle flush needs
    // the gate: it resolves without an insert and so is not bounded by admission.
    cacheInsertReadies(i) := true.B

    val normalResolution = cacheInsertFires(i)
    val idleFlushEnabled = idleCounts(i) === idleFlushCycles.U &&
      doneCounts(i) =/= 0.U
    val flushResolution =
      !normalResolution && idleFlushEnabled && resolutionHasRoom
    val readAddr = Mux(
      normalResolution,
      cacheInsertBits(i).id + 1.U,
      flushScanAddresses(i)
    )
    val issueResolution = normalResolution || flushResolution
    val resolvedSlotWasValid = cacheValid(i)(readAddr)

    resolutionIssued(i) := issueResolution
    resolutionAddresses(i) := readAddr
    resolutionRemovesDone(i) :=
      issueResolution && resolvedSlotWasValid && cacheDone(i)(readAddr)

    // A push restarts the idle interval and positions the fallback scanner at
    // the next entry in normal ring order. While flushing, invalid entries are
    // still stepped over so a completed entry can always be reached.
    when(normalResolution) {
      idleCounts(i) := 0.U
      flushScanAddresses(i) := cacheInsertBits(i).id + 2.U
    }.otherwise {
      when(idleCounts(i) =/= idleFlushCycles.U) {
        idleCounts(i) := idleCounts(i) + 1.U
      }
      when(flushResolution) {
        flushScanAddresses(i) := flushScanAddresses(i) + 1.U
      }
    }

    // We use RegNext because SyncReadMem has a 1-cycle read latency
    val resolveValid = RegNext(
      issueResolution && resolvedSlotWasValid,
      false.B
    )

    // Address of the line being pushed out, aligned with the RegNext'd valid
    val evictedAddress = RegNext(cacheIDStores(i).read(readAddr))

    // If it is new AND valid, we read the continuation
    val completedBase =
      cacheBaseStores(i).read(readAddr).asTypeOf(lineType)
    // Both payload memories are synchronous, so they are read with the same
    // address in the same cycle and their outputs are aligned one cycle later. A
    // slot whose row is stale contributes nothing to the merge below; deltaValid is
    // a register, so it is sampled through a RegNext to line it up. Every store's
    // output concatenates back into the flat row in store order.
    val completedUpdateRow = VecInit(
      updateStores(i).flatMap(store => store.read(readAddr))
    )
    val completedRowValid = RegNext(deltaValid(i)(readAddr), false.B)
    val completedOthers =
      Mux(completedRowValid, completedUpdateRow.asUInt, 0.U).asTypeOf(lineType)
    // The join counter is the register file's countdown, sampled alongside the two
    // synchronous reads. An update to this same slot in this same cycle is excluded
    // by the resolution-collision guard in `matches` below, so nothing can slip
    // past the sample.
    val completedRemaining = RegNext(remainingCounterRegs(i)(readAddr))

    // Need to OR all the arguments; the counter comes from the countdown.
    val completedFull = Wire(lineType)
    completedFull.remainder := completedBase.remainder | completedOthers.remainder
    completedFull.counter := completedRemaining

    // If the counter is 0, it goes to the spawn queue. If not, it is evicted
    // toward the CacheEvictionSaver ring.
    spawnValids(i) := resolveValid && completedFull.counter === 0.U
    evictionValids(i) := resolveValid && completedFull.counter =/= 0.U
    evictionBits(i).eviction.taskData := completedFull.asUInt
    evictionBits(i).eviction.address := evictedAddress
    evictionBits(i).metadata.server := serverIndex.U
    evictionBits(i).metadata.id := RegNext(readAddr)
    evictionBits(i).metadata.lane := i.U

    // ---- reservation counter maintenance ----
    // Atomically split the head. A spawn+update entry waits until both consumers
    // can accept, so neither half can duplicate or outrun the other. Head-of-line
    // spawn backpressure may delay later evictions, but cannot stall the porch
    // until the entire reserved resolution share fills.
    val coupledHead = coupledQs(i).io.deq
    val headNeedsSpawn = coupledHead.bits.spawnValid
    val headNeedsSlow =
      coupledHead.bits.evictionValid || coupledHead.bits.updateValid
    val spawnAccepted = !headNeedsSpawn || spawnQ.io.enq.ready
    val slowAccepted = !headNeedsSlow || io.coupledSlowPath(i).ready
    coupledHead.ready := spawnAccepted && slowAccepted

    spawnQ.io.enq.valid := coupledHead.valid && headNeedsSpawn && slowAccepted
    // The merged line IS the spawned task now, so the HBM storage behind this
    // address is dead and can go straight back to the allocator's free list.
    io.resolvedAddressOut.foreach { out =>
      out(i).valid := coupledHead.fire && coupledHead.bits.spawnValid
      out(i).bits := coupledHead.bits.eviction.eviction.address
    }
    spawnQ.io.enq.bits.taskData := coupledHead.bits.eviction.eviction.taskData
    io.coupledSlowPath(i).valid :=
      coupledHead.valid && headNeedsSlow && spawnAccepted
    io.coupledSlowPath(i).bits := coupledHead.bits
    // The spawn half terminates locally; downstream only sees eviction/update data.
    io.coupledSlowPath(i).bits.spawnValid := false.B

    val admitInc = io.newContInput(i).req.fire
    val resolutionEnq = coupledQs(i).io.enq.fire &&
      (coupledQs(i).io.enq.bits.spawnValid || coupledQs(
        i
      ).io.enq.bits.evictionValid)
    val resolutionDeq = coupledHead.fire &&
      (coupledHead.bits.spawnValid || coupledHead.bits.evictionValid)
    val evictionLeave = coupledHead.fire && coupledHead.bits.evictionValid
    // The coupledQ is the upstream reservation boundary for both outcomes.  A
    // spawn can leave it only when spawnQ accepts the transfer, so downstream
    // backpressure still prevents credit retirement without charging spawnQ
    // occupancy against the cache + porch admission budget.
    val spawnLeave = coupledHead.fire && coupledHead.bits.spawnValid
    val pureEnq = coupledQs(i).io.enq.fire &&
      !coupledQs(i).io.enq.bits.spawnValid &&
      !coupledQs(i).io.enq.bits.evictionValid && coupledQs(
        i
      ).io.enq.bits.updateValid
    val pureDeq = coupledHead.fire && !coupledHead.bits.spawnValid &&
      !coupledHead.bits.evictionValid && coupledHead.bits.updateValid
    val flushHoleInc = flushResolution && resolvedSlotWasValid
    // Initial cache fill also resolves invalid slots, but those are not holes
    // created by a flush. Retire a credit only when one is outstanding.
    val flushHoleDec = normalResolution && !resolvedSlotWasValid &&
      flushHoles(i) =/= 0.U
    inFlight(i) := (inFlight(i) +& admitInc.asUInt) -
      (evictionLeave.asUInt +& spawnLeave.asUInt)
    resolutionInCq(i) :=
      (resolutionInCq(i) +& resolutionEnq.asUInt) - resolutionDeq.asUInt
    missedInCq(i) := (missedInCq(i) +& pureEnq.asUInt) - pureDeq.asUInt
    flushHoles(i) :=
      (flushHoles(i) +& flushHoleInc.asUInt) - flushHoleDec.asUInt

    io.spawnTaskOutputs(i) <> spawnQ.io.deq
  }

  // Finally, we need to process updates as they come in. Since we know where their element should live (lane and ID), we just need to check there
  // Buffer incoming updates to help the router before we hit the crossbar
  val updatePipes = Seq.fill(NParallelUpdate)(
    Module(
      new BankedQueue(
        chiselTypeOf(io.contUpdateInput(0).bits),
        1,
        pipe = true
      )
    )
  )
  for (i <- 0 until NParallelUpdate) {
    updatePipes(i).io.enq <> io.contUpdateInput(i)
    updatePipes(i).io.deq.ready := false.B
  }

  // First, we turn into per-lane FIFOs
  val perLaneFIFOs = Seq.fill(NParallelNew)(
    Module(new BankedQueue(chiselTypeOf(io.contUpdateInput(0).bits), 3))
  )

  // Each destination lane alternates between its local update input and the other
  // inputs under contention; the other half is round-robin across all non-local
  // inputs. Preferences are work-conserving: if the preferred class is idle the
  // other class uses the cycle, and a sole requester never waits. Only the narrow
  // selector/control path is involved -- the sliced continuation-data mux below
  // remains the routing fabric.
  for (j <- 0 until NParallelNew) {
    val selWidth = math.max(1, log2Ceil(NParallelUpdate))
    val selectedInput = WireDefault(0.U(selWidth.W))
    val isValid = WireDefault(false.B)

    val requests = VecInit(updatePipes.map { pipe =>
      pipe.io.deq.valid && pipe.io.deq.bits.metadata.lane === j.U
    })
    val localInput = j
    require(
      localInput < NParallelUpdate,
      "ArgumentServer needs a local update input for every cache lane"
    )
    val localTurn = RegInit(true.B)
    val localValid = requests(localInput)
    val otherInputs = (1 until NParallelUpdate).map { offset =>
      (localInput + offset) % NParallelUpdate
    }

    if (otherInputs.nonEmpty) {
      val otherCount = otherInputs.size
      val otherPtrWidth = math.max(1, log2Ceil(otherCount))
      val otherPtr = RegInit(0.U(otherPtrWidth.W))
      val otherSelected = WireDefault(otherInputs.head.U(selWidth.W))
      val otherSelectedPos = WireDefault(0.U(otherPtrWidth.W))
      val otherValid = WireDefault(false.B)

      // A small control-only rotating priority encoder. Generate one static
      // priority order per pointer value; synthesis reduces the constant index
      // arithmetic, avoiding a modulo operator or any continuation-width logic.
      for (start <- 0 until otherCount) {
        when(otherPtr === start.U) {
          for (offset <- (0 until otherCount).reverse) {
            val pos = (start + offset) % otherCount
            when(requests(otherInputs(pos))) {
              otherSelected := otherInputs(pos).U
              otherSelectedPos := pos.U
              otherValid := true.B
            }
          }
        }
      }

      val chooseLocal = localValid && (localTurn || !otherValid)
      val chooseOther = otherValid && (!localTurn || !localValid)
      isValid := chooseLocal || chooseOther
      selectedInput := Mux(chooseLocal, localInput.U, otherSelected)

      when(perLaneFIFOs(j).io.enq.fire) {
        localTurn := !localTurn
      }
      when(chooseOther && perLaneFIFOs(j).io.enq.ready) {
        otherPtr := Mux(
          otherSelectedPos === (otherCount - 1).U,
          0.U,
          otherSelectedPos + 1.U
        )
      }
    } else {
      isValid := localValid
      selectedInput := localInput.U
    }

    perLaneFIFOs(j).io.enq.valid := isValid

    // Convert the binary arbitration result into static one-hot grants before
    // touching payload data. Dynamic Vec indexing pads non-power-of-two input
    // counts and creates needless mux legs.
    val grants = Wire(Vec(NParallelUpdate, Bool()))
    for (i <- 0 until NParallelUpdate) {
      grants(i) := isValid && selectedInput === i.U
    }

    perLaneFIFOs(j).io.enq.bits.payload := Mux1H(
      (0 until NParallelUpdate).map(i =>
        grants(i) -> updatePipes(i).io.deq.bits.payload
      )
    )
    perLaneFIFOs(j).io.enq.bits.offset.foreach { offset =>
      offset := Mux1H(
        (0 until NParallelUpdate).map(i =>
          grants(i) -> updatePipes(i).io.deq.bits.offset.get
        )
      )
    }
    perLaneFIFOs(j).io.enq.bits.metadata := Mux1H(
      (0 until NParallelUpdate).map(i =>
        grants(i) -> updatePipes(i).io.deq.bits.metadata
      )
    )
    perLaneFIFOs(j).io.enq.bits.address := Mux1H(
      (0 until NParallelUpdate).map(i =>
        grants(i) -> updatePipes(i).io.deq.bits.address
      )
    )

    for (i <- 0 until NParallelUpdate) {
      when(grants(i)) {
        updatePipes(i).io.deq.ready := perLaneFIFOs(j).io.enq.ready
      }
    }
  }

  // Updates stay compact through routing, buffering and the cache lookup: the
  // cache write is a masked element write, so the payload only has to reach one
  // `effectiveUpdatePayloadWidth`-wide element. A one-entry pipelined queue per
  // cache lane registers the arbitration result, keeping it off the cache lookup
  // and BRAM-write timing path. Expansion to a full continuation width happens
  // once, on the miss path below, where the slow-path entry needs a line-shaped
  // write mask.
  val stagedUpdatePipes = Seq.fill(NParallelNew)(
    Module(
      new BankedQueue(
        chiselTypeOf(io.contUpdateInput(0).bits),
        1,
        pipe = true
      )
    )
  )
  for (i <- 0 until NParallelNew) {
    stagedUpdatePipes(i).io.enq <> perLaneFIFOs(i).io.deq
  }

  val matches = Wire(Vec(NParallelNew, Bool()))
  val valids = Wire(Vec(NParallelNew, Bool()))

  // Check whether the address still matches its cache slot and whether that slot
  // is being inserted/resolved in this exact cycle. This closes the lost-update
  // races without permanently excluding the most recently inserted line, which
  // must remain updateable when producers go idle so it can become eligible for
  // the fallback flush.
  for (i <- 0 until NParallelNew) {
    val update = stagedUpdatePipes(i).io.deq
    val targetId = update.bits.metadata.id
    updateTargetIds(i) := targetId
    // An update that lands on the exact cycle its own line is inserted must not
    // be treated as a genuine miss: the line is being written to the cache right
    // now and has never been evicted, so it has no HBM backing and the slow path
    // would read unsaved memory. cacheValid is a Reg set on this cycle, so simply
    // holding the update one cycle turns it into a hit.
    val insertCollision =
      cacheInsertFires(i) && targetId === cacheInsertBits(i).id
    insertCollisions(i) := update.valid && insertCollision
    matches(i) := cacheValid(i)(targetId) &&
      cacheIDStores(i).read(targetId) === update.bits.address &&
      !insertCollision &&
      !(resolutionIssued(i) && targetId === resolutionAddresses(i))

    valids(i) := update.valid

    // Stage missed updates for one cycle before the coupled FIFO.  Besides
    // breaking the wide cache-lookup path, this aligns a same-cycle resolution
    // collision with the eviction produced by the synchronous base store.
    // An insert collision is deliberately NOT routed here (it is not a real
    // miss); the update stays at the head of the expansion pipe and hits next cycle.
    // A resolution collision IS routed here: that line is genuinely leaving the
    // cache, and the one-cycle staging couples the update to its own eviction so
    // the gater fences it behind that eviction's HBM write.
    delayedMissQs(i).io.enq.valid := valids(i) && !matches(
      i
    ) && !insertCollision
    // A missed update stays compact all the way to the SlowArgumentHandler,
    // which is the only thing that reads the data; it expands there.
    delayedMissQs(i).io.enq.bits.update.address := update.bits.address
    delayedMissQs(i).io.enq.bits.update.payload := update.bits.payload
    delayedMissQs(i).io.enq.bits.update.offset.zip(update.bits.offset).foreach {
      case (out, in) => out := in
    }
    delayedMissQs(i).io.enq.bits.metadata := update.bits.metadata

    update.ready :=
      matches(i) || (delayedMissQs(i).io.enq.ready && !insertCollision)

    val fireUpdate = matches(i) && valids(i)

    // Which element of the row this payload owns. A full-line payload has a
    // single element and therefore no offset field.
    val targetSlot =
      if (updateSlotCount == 1) 0.U
      else update.bits.offset.get

    // A stale row contributes zero, so the first update to a recycled slot must
    // rewrite the WHOLE row: its own element plus zeros everywhere else. That
    // full-row write IS the deferred clear the insert skipped. Afterwards only
    // the addressed element is enabled, so sibling payloads already merged into
    // the row survive untouched.
    val rowIsStale = !deltaValid(i)(targetId)
    // Per-store slices of the same logical row write. Only the store that owns
    // `targetSlot` sees a set mask bit, unless the row is stale, in which case
    // every store rewrites its whole slice with the payload-or-zero pattern.
    val updateWriteData = Seq.tabulate(updateStoreCount) { s =>
      val data = Wire(Vec(slotsPerStore, UInt(effectiveUpdatePayloadWidth.W)))
      val mask = Wire(Vec(slotsPerStore, Bool()))
      for (k <- 0 until slotsPerStore) {
        val selected = targetSlot === (s * slotsPerStore + k).U
        data(k) := Mux(selected, update.bits.payload, 0.U)
        mask(k) := selected || rowIsStale
      }
      (data, mask)
    }

    // If it matches, we apply the update
    when(fireUpdate) {
      val currentRemaining = remainingCounterRegs(i)(targetId)

      // Sole writer of updateStores: one masked write port, so each memory
      // stays a simple dual-port BRAM. No read, hence no read-modify-write.
      updateApplied(i) := true.B
      for (s <- 0 until updateStoreCount) {
        val (data, mask) = updateWriteData(s)
        updateStores(i)(s).write(targetId, data, mask)
      }
      deltaValid(i)(targetId) := true.B
      remainingCounterRegs(i)(targetId) := currentRemaining - 1.U
      updateMakesDone(i) := currentRemaining === 1.U
    }
  }

  // Maintain an explicit completed-entry count per lane. The count enables
  // ordered fallback draining after ten idle cycles; it does not select which
  // entries may leave. Therefore incomplete entries before the completed one
  // are evicted in normal ring order, and draining stops when no completed
  // entries remain.
  for (i <- 0 until NParallelNew) {
    val insertFire = cacheInsertFires(i)
    val insertId = cacheInsertBits(i).id
    val insertDone = insertFire &&
      cacheInsertBits(i).taskBaseData.asTypeOf(lineType).counter === 0.U

    // Derived DIRECTLY from the two conditions that actually change the number of
    // valid slots, in the same block that performs those writes, so the deficit
    // cannot drift away from the cache no matter which path (normal resolution,
    // idle flush, initial fill) caused the change.
    //   clearsSlot : a valid slot is invalidated  -> one more slot missing
    //   fillsHole  : an INVALID slot becomes valid -> one fewer slot missing
    // An insert onto an already-valid slot would not change the count, so guard
    // on the current valid bit rather than assuming ring order holds.
    val clearsSlot =
      resolutionIssued(i) && cacheValid(i)(resolutionAddresses(i))
    val fillsHole = insertFire && !cacheValid(i)(insertId)

    when(clearsSlot) {
      cacheValid(i)(resolutionAddresses(i)) := false.B
      cacheDone(i)(resolutionAddresses(i)) := false.B
    }
    when(insertFire) {
      cacheValid(i)(insertId) := true.B
      cacheDone(i)(insertId) := insertDone
    }
    when(updateMakesDone(i)) {
      cacheDone(i)(updateTargetIds(i)) := true.B
    }

    cacheDeficit(i) :=
      (cacheDeficit(i) +& clearsSlot.asUInt) - fillsHole.asUInt

    // Diagnostic only (simulation): report the first cycle on which the
    // event-reconstructed flushHoles disagrees with the true deficit. Non-fatal
    // and one-shot so a run still completes and stays readable.
    val deficitDivergenceSeen = RegInit(false.B)
    when(flushHoles(i) =/= cacheDeficit(i) && !deficitDivergenceSeen) {
      deficitDivergenceSeen := true.B
      printf(
        p"[ArgumentServer] lane $i: flushHoles=${flushHoles(i)} " +
          p"!= cacheDeficit=${cacheDeficit(i)} (event accounting drifted)\n"
      )
    }

    val increments = PopCount(Seq(insertDone, updateMakesDone(i)))
    val decrements = resolutionRemovesDone(i).asUInt
    doneCounts(i) := doneCounts(i) + increments - decrements
  }
}
