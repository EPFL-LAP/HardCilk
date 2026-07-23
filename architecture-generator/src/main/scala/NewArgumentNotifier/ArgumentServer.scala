package NewArgumentNotifier

import chisel3._
import chisel3.util._

// ---------------------------------------------------------------------------
// Shared bundle definitions for the new argument-notifier subsystem.
//
// A continuation lives either in an ArgumentServer's cache (fast path) or in
// HBM (slow path, after eviction). Internally we carry only its compact,
// line-aligned HBM address. Cache-location metadata is a separate bundle; it
// is never hidden in otherwise-unused address bits.
// ---------------------------------------------------------------------------

object ArgumentNotifierHelpers {
  /** Lane-select width; at least 1 bit so NParallelNew == 1 still elaborates. */
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

/** The value carried with a child task to identify its continuation. The
  * address is the compact line index produced from an AllocatorServer address;
  * metadata remains structurally visible throughout the datapath.
  */
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

/** A continuation while it traverses the fixed-delay cache front porch.  Its
  * cache ID is reserved when the request is accepted, before the entry reaches
  * the searchable cache proper.
  */
class DelayedNewContinuation(
    val lineAddressWidth: Int,
    val continuationSize: Int,
    val serverIDWidth: Int
) extends Bundle {
  val id = UInt(serverIDWidth.W)
  val address = UInt(lineAddressWidth.W)
  val taskBaseData = UInt(continuationSize.W)
}

/** One "new continuation" lane of an ArgumentServer. The metadata outputs are
  * valid in the cycle `req` fires; the write-buffer bridge samples them right
  * then so the released child tasks can be tagged.
  */
class NewContinuationPort(
    val lineAddressWidth: Int,
    val continuationSize: Int,
    val serverIDWidth: Int,
    val laneWidth: Int
) extends Bundle {
  val req = Flipped(
    Decoupled(new NewContinuationReq(lineAddressWidth, continuationSize))
  )

  // We assign an ID inside the LUTRAM
  val assignedId = Output(UInt(serverIDWidth.W))
  val assignedLane = Output(UInt(laneWidth.W))
}

/** A continuation update: one child finished, ORs its argument payload into
  * the line (bit-granular strobe) and implicitly decrements the join counter
  * by one. Assumes exactly ONE update write per child.
  */
class ContinuationUpdate(
    val lineAddressWidth: Int,
    val serverTagWidth: Int,
    val serverIDWidth: Int,
    val laneWidth: Int,
    val continuationSize: Int
) extends Bundle {
  val address = UInt(lineAddressWidth.W)
  val metadata =
    new ContinuationMetadata(serverTagWidth, serverIDWidth, laneWidth)
  val dataWriteStrobe = UInt(continuationSize.W)
  val dataWrite = UInt(continuationSize.W)
}

/** A completed continuation, spawned as a task (the merged line IS the task
  * payload).
  */
class SpawnedTask(val continuationSize: Int) extends Bundle {
  val taskData = UInt(continuationSize.W)
}

/** A still-counting line pushed out of the cache; must be persisted to HBM by
  * a CacheEvictionSaver before any slow update for it is processed.
  */
class EvictedContinuation(val lineAddressWidth: Int, val continuationSize: Int)
    extends Bundle {
  val address = UInt(lineAddressWidth.W)
  val taskData = UInt(continuationSize.W)
}

/** An update that missed the cache (line already evicted); handled in memory
  * by a SlowArgumentHandler.
  */
class SlowUpdate(val lineAddressWidth: Int, val continuationSize: Int)
    extends Bundle {
  val address = UInt(lineAddressWidth.W)
  val dataWriteStrobe = UInt(continuationSize.W)
  val dataWrite = UInt(continuationSize.W)
}

/** An eviction plus the cache slot that produced it.  The compact metadata is
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
    val laneWidth: Int
) extends Bundle {
  val update = new SlowUpdate(lineAddressWidth, continuationSize)
  val metadata =
    new ContinuationMetadata(serverTagWidth, serverIDWidth, laneWidth)
}

/** One entry of the coupled resolution/slow-path FIFO. A cache resolution is
  * either a spawn or an eviction (never both) and may share the entry with a
  * missed update. A pure missed update has neither resolution-valid bit set.
  */
class CoupledSlowPathEntry(
    val lineAddressWidth: Int,
    val continuationSize: Int,
    val serverTagWidth: Int,
    val serverIDWidth: Int,
    val laneWidth: Int
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
    laneWidth
  )
}

/** Layout of a continuation line, both in the cache and in HBM. */
class ContinuationLine(val counterWidth: Int, val continuationSize: Int)
    extends Bundle {
  // Chisel packs the first Bundle field into the most-significant bits. The
  // continuation ABI is a packed C struct whose counter begins at byte zero,
  // so declare the payload first and the counter last to place counter at
  // [counterWidth-1:0].
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
    NParallelUpdate: Int
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
          continuationSize
        )
      )
    )
  )

  val spawnTaskOutputs =
    Vec(NParallelNew, Decoupled(new SpawnedTask(continuationSize)))

  val coupledSlowPath = Vec(
    NParallelNew,
    Decoupled(
      new CoupledSlowPathEntry(
        lineAddressWidth,
        continuationSize,
        serverTagWidth,
        serverIDWidth,
        laneW
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
    // backlog never has to backpressure into the eviction pool. Throughput knob:
    // too small only throttles (memReader stalls, self-limiting), never wrong.
    missedUpdateExtra: Int = 64
) extends Module {

  require(cacheDelayCycles >= 0)
  require(missedUpdateExtra >= 1)

  val io = IO(
    new ArgumentServerIO(
      counterWidth,
      lineAddressWidth,
      serverTagWidth,
      serverIDWidth,
      continuationSize,
      NParallelNew,
      NParallelUpdate
    )
  )

  private def lineType = new ContinuationLine(counterWidth, continuationSize)
  private val cacheDepth = 1 << serverIDWidth
  private val idleFlushCycles = 10
  private val idleCountWidth = log2Ceil(idleFlushCycles + 1)

  // ---- Non-backpressuring front porch (reservation) ----------------------------
  // Root cause of the countDecoupled release-loss: when the eviction path (coupledQ)
  // filled, resolutionHasRoom dropped, the porch stalled INSIDE its shift register,
  // and a continuation was delayed past the fixed arrival time of its (memReader-
  // timed) update -> the update missed a not-yet-inserted line and read UNSAVED HBM
  // (counter 0) -> completes=false -> writeback instead of spawn -> release lost.
  // Fix: the porch must NEVER stall internally. Move all backpressure to the porch
  // ENTRANCE (newContInput.req.ready), where the initiator dispatches the
  // continuation and its memReader task atomically, keeping them synchronized.
  //
  // We guarantee the resolution FIFO always has room for the spawn or eviction an
  // insert produces. KEY subtlety: an insert only resolves a valid old line once
  // the cache is full (before
  // that it fills an empty slot, producing nothing), so we must keep accepting
  // through fill-up. So the admission cap is (resident_capacity + resolution_pool):
  // we only
  // start throttling once the cache is full and resolutions can actually be produced.
  // Spawns and evictions use the SAME reserved resolution share. Missed updates for
  // genuinely-evicted (saved) lines ride the same physical FIFO for gater ordering
  // but draw from a separate logical share so a miss backlog cannot wedge a
  // resolution. The resolution share is exactly the front-porch reservation: one
  // slot for every continuation that can be in the porch. There is deliberately no
  // additional headroom. With no porch, retain two slots so the queue structure can
  // sustain II=1 without degenerating into a combinational/single-entry corner.
  // missedUpdateExtra sizes only the independent pure-miss share below.
  private val resolutionPoolDepth = math.max(2, cacheDelayCycles)
  private val missedPoolDepth = math.max(1, missedUpdateExtra) // pure missed-update pool
  // Normal insertion resolves id+1, so one ring slot is always the separation
  // point between the insertion head and the far-end resolution: at most
  // (cacheDepth-1) continuations are resident simultaneously.
  private val residentCapacity = cacheDepth - 1
  // A resolved cache line remains charged to inFlight for three cycles after
  // its slot is freed: issue the synchronous read, classify/enqueue its result,
  // then dequeue the registered coupledQ. Keep those three pipeline stages from
  // stealing capacity from an otherwise-full cache + porch, or steady-state
  // admission bubbles and II=1 is lost.
  private val resolutionTailCredits = 3
  private val admitCap =
    residentCapacity + resolutionPoolDepth + resolutionTailCredits
  // Admission alone must bound the undrained resolution backlog, because the porch
  // runs UNGATED (see cacheInsertReadies) and a resolution cannot be backpressured.
  // inFlight = porch + cache-resident + resolution-pipeline + resolutions-in-coupledQ.
  // Idle flushes temporarily punch holes in the cache; flushHoles below keeps
  // those slots charged until a later insertion's clear step absorbs each hole.
  // Admission holds inFlight + flushHoles at the entrance, so
  //   resolutionInCq <= admitCap - residentCapacity
  // and this bound is EXACT (not conservative): the cache is necessarily full
  // whenever resolutionInCq peaks. A resolution is only produced when the ring wraps
  // onto a VALID slot, so driving the cache below residentCapacity (only the idle
  // flush can) makes the following inserts land on invalid slots and produce
  // NOTHING, refilling the cache before resolutionInCq moves again. The peak is
  // always: burst to admitCap (porch full, cache full), entrance blocks, then the
  // porch drains ungated and every entry -- plus the resolutionTailCredits, which
  // are admissions granted BEYOND porch+cache -- converts into a parked resolution.
  // Those tail credits are exactly what the old reservation missed: it sized this
  // share against the porch alone (resolutionPoolDepth), leaving it 3 short, and the
  // porch gate (resolutionInCq <= resolutionPoolDepth-2) was load-bearing cover for
  // that shortfall -- it "prevented" the overflow only by FREEZING the porch, which
  // is the release-loss bug. Removing the gate therefore requires this sizing.
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

  private def coupledType = new CoupledSlowPathEntry(
    lineAddressWidth,
    continuationSize,
    serverTagWidth,
    serverIDWidth,
    laneW
  )

  // Base stores use SyncReadMem (BRAM) to save area, updates use Mem (LUTRAM) for the async RMW
  val cacheBaseStores = Seq.fill(NParallelNew)(
    SyncReadMem(cacheDepth, UInt(continuationSize.W))
  )
  val cacheIDStores =
    Seq.fill(NParallelNew)(Mem(cacheDepth, UInt(lineAddressWidth.W)))
  val updateLUTRAMs =
    Seq.fill(NParallelNew)(Mem(cacheDepth, UInt(continuationSize.W)))
  val remainingCounterStores =
    Seq.fill(NParallelNew)(Mem(cacheDepth, UInt(counterWidth.W)))
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
      cacheInsertValids(i) := io.newContInput(i).req.valid
      cacheInsertBits(i).id := cacheBaseStoresHead(i)
      cacheInsertBits(i).address := io.newContInput(i).req.bits.address
      cacheInsertBits(i).taskBaseData :=
        io.newContInput(i).req.bits.taskBaseData
      // Admission gate: keep accepting while total in-flight work is below cache
      // size + the resolution reservation. This is the only porch backpressure.
      io.newContInput(i).req.ready := cacheInsertReadies(i) &&
        ((inFlight(i) +& flushHoles(i)) < admitCap.U)
    } else {
      val porchValid = RegInit(
        VecInit(Seq.fill(cacheDelayCycles)(false.B))
      )
      val porchData = Reg(Vec(cacheDelayCycles, delayedNewType))
      val porchCanAdvance =
        !porchValid(cacheDelayCycles - 1) || cacheInsertReadies(i)

      // A bubble advances just like a valid entry.  Only a blocked valid tail
      // freezes the clock-enabled shift register and backpressures its source.
      // Admission gate (see admitCap): the ENTRANCE is the only backpressure, so a
      // continuation never sits in the porch longer than cacheDelayCycles and stays
      // time-aligned with its memReader update. porchCanAdvance must stay ~always
      // true (cacheInsertReadies no longer depends on coupledQ occupancy).
      io.newContInput(i).req.ready := porchCanAdvance &&
        ((inFlight(i) +& flushHoles(i)) < admitCap.U)
      when(porchCanAdvance) {
        for (stage <- (1 until cacheDelayCycles).reverse) {
          porchValid(stage) := porchValid(stage - 1)
          porchData(stage) := porchData(stage - 1)
        }
        porchValid(0) := io.newContInput(i).req.valid
        when(io.newContInput(i).req.valid) {
          porchData(0).id := cacheBaseStoresHead(i)
          porchData(0).address := io.newContInput(i).req.bits.address
          porchData(0).taskBaseData :=
            io.newContInput(i).req.bits.taskBaseData
        }
      }

      cacheInsertValids(i) := porchValid(cacheDelayCycles - 1)
      cacheInsertBits(i) := porchData(cacheDelayCycles - 1)
    }

    when(io.newContInput(i).req.fire) {
      cacheBaseStoresHead(i) := cacheBaseStoresHead(i) + 1.U
    }
  }

  // These wires also protect the update RMW below from a same-cycle cache
  // insertion or resolution. Unlike the old head/head-1 exclusion, the
  // protection lasts only for the cycle in which there is a real collision.
  val resolutionIssued = Wire(Vec(NParallelNew, Bool()))
  val resolutionAddresses = Wire(Vec(NParallelNew, UInt(serverIDWidth.W)))
  val resolutionRemovesDone = Wire(Vec(NParallelNew, Bool()))
  val updateMakesDone = Wire(Vec(NParallelNew, Bool()))
  val updateTargetIds = Wire(Vec(NParallelNew, UInt(serverIDWidth.W)))
  for (i <- 0 until NParallelNew) {
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
      updateLUTRAMs(i).write(cacheInsertBits(i).id, 0.U)
      remainingCounterStores(i).write(
        cacheInsertBits(i).id,
        insertedLine.counter
      )
    }
  }

  // One physical queue per cache lane couples resolutions and missed updates.
  // A one-cycle update staging queue is load-bearing: a resolution collision
  // is detected in the issue cycle, while SyncReadMem produces its eviction in
  // the following cycle. Staging aligns the two so the resolution and update can
  // occupy one atomic coupled entry.
  val coupledQs = Seq.fill(NParallelNew)(
    Module(new Queue(coupledType, coupledQueueDepth))
  )
  val delayedMissQs = Seq.fill(NParallelNew)(
    Module(
      new Queue(
        new TaggedSlowUpdate(
          lineAddressWidth,
          continuationSize,
          serverTagWidth,
          serverIDWidth,
          laneW
        ),
        2
      )
    )
  )
  val spawnValids = Wire(Vec(NParallelNew, Bool()))
  val evictionValids = Wire(Vec(NParallelNew, Bool()))
  val evictionBits = Wire(Vec(NParallelNew, new TaggedEvictedContinuation(
    lineAddressWidth,
    continuationSize,
    serverTagWidth,
    serverIDWidth,
    laneW
  )))
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
    // would SILENTLY DROP it -- and with it a release. Nothing backpressures it;
    // admitCap + resolutionQueueDepth are what must cover the porch-empty worst
    // case. Fire loudly in simulation if that sizing ever stops holding.
    assert(
      !(hasResolution && !coupledQs(i).io.enq.ready),
      "ArgumentServer: resolution met a full coupledQ; a release would be lost"
    )
    coupledQs(i).io.enq.bits.spawnValid := hasSpawn
    coupledQs(i).io.enq.bits.evictionValid := hasEviction
    coupledQs(i).io.enq.bits.eviction := evictionBits(i)
    coupledQs(i).io.enq.bits.updateValid := hasUpdate && (hasResolution || missedRoom)
    coupledQs(i).io.enq.bits.update := delayedMissQs(i).io.deq.bits
    delayedMissQs(i).io.deq.ready :=
      coupledQs(i).io.enq.ready && (hasResolution || (isPureUpdate && missedRoom))
  }

  // Read from back and fire/forward
  //
  // Resolution is tied to a COMMITTED cache insert: when a matured porch entry
  // is written at `id`, the line at `id + 1` (the far end of the cache ring)
  // is inspected one cycle later and either spawned (counter reached zero) or
  // evicted to the slow path. The result lands in a small skid queue so a
  // busy consumer can never drop (or double-count) a resolution; instead,
  // inserts stall via req.ready while a queue could overflow.
  for (i <- 0 until NParallelNew) {
    val spawnQ = Module(new Queue(new SpawnedTask(continuationSize), 4))
    // Reserve room for the one-cycle resolution pipeline as well as entries already
    // in coupledQ. At count <= depth-2, this cycle and the already-issued prior cycle
    // can both resolve without overflow. Pure missed updates use their own share and
    // therefore cannot block this gate.
    val resolutionHasRoom =
      resolutionInCq(i) <= (resolutionPoolDepth - 2).U
    // The porch NEVER stalls. Room for an insert's resolution is guaranteed by
    // sizing (admitCap + resolutionQueueDepth), not by gating the porch here.
    // Gating it on resolutionInCq is what still lost releases on hardware: when a
    // bursty entrance let the porch drain to a lone straggler at the tail while
    // real-HBM backpressure stacked resolutions, this gate went false and froze
    // that straggler. A frozen porch entry is inserted later than dispatch +
    // cacheDelayCycles, so its memReader-timed update arrives while the line is
    // still in the porch -- a line with NO HBM backing -- misses, takes the slow
    // path, reads unsaved (zeroed) HBM, and completes=false drops the release.
    // Only the idle flush still needs the gate: it resolves WITHOUT an insert and
    // so is not bounded by admission.
    cacheInsertReadies(i) := true.B

    val normalResolution = cacheInsertFires(i)
    val idleFlushEnabled = idleCounts(i) === idleFlushCycles.U &&
      doneCounts(i) =/= 0.U
    val flushResolution = !normalResolution && idleFlushEnabled && resolutionHasRoom
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
    val completedOthers = RegNext(
      updateLUTRAMs(i).read(readAddr).asTypeOf(lineType)
    )

    // Need to OR all the arguments, and ADD the counter decremenets
    val completedFull = Wire(lineType)
    completedFull.remainder := completedBase.remainder | completedOthers.remainder
    completedFull.counter := completedBase.counter - completedOthers.counter

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
    // Atomically split the head. A spawn+update entry waits until BOTH consumers can
    // accept, so neither half can duplicate or outrun the other. Head-of-line spawn
    // backpressure may delay later evictions, but it cannot stall the porch until the
    // entire reserved resolution share fills.
    val coupledHead = coupledQs(i).io.deq
    val headNeedsSpawn = coupledHead.bits.spawnValid
    val headNeedsSlow = coupledHead.bits.evictionValid || coupledHead.bits.updateValid
    val spawnAccepted = !headNeedsSpawn || spawnQ.io.enq.ready
    val slowAccepted = !headNeedsSlow || io.coupledSlowPath(i).ready
    coupledHead.ready := spawnAccepted && slowAccepted

    spawnQ.io.enq.valid := coupledHead.valid && headNeedsSpawn && slowAccepted
    spawnQ.io.enq.bits.taskData := coupledHead.bits.eviction.eviction.taskData
    io.coupledSlowPath(i).valid :=
      coupledHead.valid && headNeedsSlow && spawnAccepted
    io.coupledSlowPath(i).bits := coupledHead.bits
    // The spawn half terminates locally; downstream only sees eviction/update data.
    io.coupledSlowPath(i).bits.spawnValid := false.B

    val admitInc = io.newContInput(i).req.fire
    val resolutionEnq = coupledQs(i).io.enq.fire &&
      (coupledQs(i).io.enq.bits.spawnValid || coupledQs(i).io.enq.bits.evictionValid)
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
      !coupledQs(i).io.enq.bits.evictionValid && coupledQs(i).io.enq.bits.updateValid
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
    Module(new Queue(chiselTypeOf(io.contUpdateInput(0).bits), 1, pipe = true))
  )
  for (i <- 0 until NParallelUpdate) {
    updatePipes(i).io.enq <> io.contUpdateInput(i)
    updatePipes(i).io.deq.ready := false.B
  }

  // First, we turn into per-lane FIFOs
  val perLaneFIFOs = Seq.fill(NParallelNew)(
    Module(new Queue(chiselTypeOf(io.contUpdateInput(0).bits), 3))
  )

  // Each destination lane alternates between its local update input and the
  // other inputs under contention.  The other half is round-robin across all
  // non-local inputs.  Preferences are work-conserving: if the preferred class
  // is idle, the other class uses the cycle, and a sole requester never waits.
  //
  // This changes only the narrow selector/control path.  The existing sliced
  // continuation-data mux below remains the 5x4 routing fabric, so fairness
  // costs one phase bit plus a tiny RR pointer per destination lane without
  // adding another continuation-width routing stage.
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

    // Slice the crossbar into 32-bit chunks so Vivado doesn't clump everything together
    val sliceWidth = 32
    val numSlices = (continuationSize + sliceWidth - 1) / sliceWidth

    val dataSlicesOut = for (c <- 0 until numSlices) yield {
      val sliceSelect = dontTouch(WireInit(selectedInput))
      val low = c * sliceWidth
      val high = math.min((c + 1) * sliceWidth - 1, continuationSize - 1)
      val dataSlicesIn = VecInit(
        updatePipes.map(p => p.io.deq.bits.dataWrite(high, low))
      )
      dataSlicesIn(sliceSelect)
    }

    val strobeSlicesOut = for (c <- 0 until numSlices) yield {
      val sliceSelect = dontTouch(WireInit(selectedInput))
      val low = c * sliceWidth
      val high = math.min((c + 1) * sliceWidth - 1, continuationSize - 1)
      val strobeSlicesIn = VecInit(
        updatePipes.map(p => p.io.deq.bits.dataWriteStrobe(high, low))
      )
      strobeSlicesIn(sliceSelect)
    }

    val metadataArray = VecInit(updatePipes.map(_.io.deq.bits.metadata))
    val addrArray = VecInit(updatePipes.map(_.io.deq.bits.address))

    perLaneFIFOs(j).io.enq.bits.dataWrite := Cat(dataSlicesOut.reverse)
    perLaneFIFOs(j).io.enq.bits.dataWriteStrobe := Cat(strobeSlicesOut.reverse)
    perLaneFIFOs(j).io.enq.bits.metadata := metadataArray(selectedInput)
    perLaneFIFOs(j).io.enq.bits.address := addrArray(selectedInput)

    for (i <- 0 until NParallelUpdate) {
      when(isValid && selectedInput === i.U) {
        updatePipes(i).io.deq.ready := perLaneFIFOs(j).io.enq.ready
      }
    }
  }

  val matches = Wire(Vec(NParallelNew, Bool()))
  val valids = Wire(Vec(NParallelNew, Bool()))

  // We then need to check whether the address still matches its cache slot and
  // whether that slot is being inserted/resolved in this exact cycle.
  //
  // This closes the lost-update races without permanently excluding the most
  // recently inserted line. That line must remain updateable when producers
  // go idle, otherwise it could never become eligible for the fallback flush.
  for (i <- 0 until NParallelNew) {
    val targetId = perLaneFIFOs(i).io.deq.bits.metadata.id
    updateTargetIds(i) := targetId
    // An update that lands on the exact cycle its own line is inserted must not
    // be treated as a genuine miss: the line is being written to the cache right
    // now and has never been evicted, so it has no HBM backing and the slow path
    // would read unsaved memory. cacheValid is a Reg set on this cycle, so simply
    // holding the update one cycle turns it into a hit.
    val insertCollision =
      cacheInsertFires(i) && targetId === cacheInsertBits(i).id
    matches(i) := cacheValid(i)(targetId) &&
      cacheIDStores(i).read(targetId) === perLaneFIFOs(
      i
    ).io.deq.bits.address &&
      !insertCollision &&
      !(resolutionIssued(i) && targetId === resolutionAddresses(i))

    valids(i) := perLaneFIFOs(i).io.deq.valid

    // Stage missed updates for one cycle before the coupled FIFO.  Besides
    // breaking the wide cache-lookup path, this aligns a same-cycle resolution
    // collision with the eviction produced by the synchronous base store.
    // An insert collision is deliberately NOT routed here (it is not a real
    // miss); the update stays at the head of perLaneFIFO and hits next cycle.
    // A resolution collision IS routed here: that line is genuinely leaving the
    // cache, and the one-cycle staging couples the update to its own eviction so
    // the gater fences it behind that eviction's HBM write.
    delayedMissQs(i).io.enq.valid := valids(i) && !matches(i) && !insertCollision
    delayedMissQs(i).io.enq.bits.update.address := perLaneFIFOs(
      i
    ).io.deq.bits.address
    delayedMissQs(i).io.enq.bits.update.dataWriteStrobe := perLaneFIFOs(
      i
    ).io.deq.bits.dataWriteStrobe
    delayedMissQs(i).io.enq.bits.update.dataWrite := perLaneFIFOs(
      i
    ).io.deq.bits.dataWrite
    delayedMissQs(i).io.enq.bits.metadata := perLaneFIFOs(i).io.deq.bits.metadata

    perLaneFIFOs(i).io.deq.ready :=
      matches(i) || (delayedMissQs(i).io.enq.ready && !insertCollision)

    val fireUpdate = matches(i) && valids(i)

    // If it matches, we apply the update
    when(fireUpdate) {
      val currentVal =
        updateLUTRAMs(i).read(targetId).asTypeOf(lineType)
      val currentRemaining = remainingCounterStores(i).read(targetId)
      val incomingVal = (perLaneFIFOs(i).io.deq.bits.dataWrite & perLaneFIFOs(
        i
      ).io.deq.bits.dataWriteStrobe).asTypeOf(lineType)

      val newVal = Wire(lineType)
      // OR the payload, but ADD to the counter so it subtracts out later
      newVal.remainder := currentVal.remainder | incomingVal.remainder
      newVal.counter := currentVal.counter + 1.U

      updateLUTRAMs(i).write(targetId, newVal.asUInt)
      remainingCounterStores(i).write(targetId, currentRemaining - 1.U)
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

    when(resolutionIssued(i) && cacheValid(i)(resolutionAddresses(i))) {
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

    val increments = PopCount(Seq(insertDone, updateMakesDone(i)))
    val decrements = resolutionRemovesDone(i).asUInt
    doneCounts(i) := doneCounts(i) + increments - decrements
  }
}
