package Atomics

import chisel3._
import chisel3.util._
import chisel3.util.random.LFSR
import Atomics.Helpers.InputArbiter
import Atomics.Helpers.AvailableSlotTracker
import chext.amba.axi4
import chext.amba.axi4.Casts._
import chext.amba.axi4.full.ConnectOp._

// Request packet skeleton:
//   bits 63:0    = lock tag / byte address field from the PE; LockServer uses
//                  the lower addrW bits
//   bits 127:64  = operand/data
//   bits 131:128 = opcode
//   bit  132     = blocking lock request
//   bits 134:133 = atomic mode (00 preserves the old 64-bit behavior)
//   bit  135     = float-compare flag: when set, the conditional SET_IF_* ops
//                  order operand vs memory as IEEE-754 floats instead of ints
//   bits 143:136 = sender metadata, echoed back in the response so a PE with
//                  several requests in flight can correlate completions
// Response packet skeleton:
//   bits  7:0     = success status:
//                     bit 0 = request succeeded
//                     bit 1 = for conditional AMU ops (SetIfGreater/SetIfLess),
//                             whether the store actually happened; for every
//                             other op it just mirrors bit 0 on success
//   bits 71:8     = requested lock addr
//   bits 135:72   = previous memory value (HBM-backed atomic ops only)
//   bits 143:136  = metadata echoed from the request
//
// Requests from one PE may complete out of issue order (a retried request
// finishes after a younger one that did not retry). PEs that need ordering
// must wait for the response before issuing a dependent request; in
// particular, never issue an operation on a tag while an operation on the
// same tag from the same PE is still unresolved.

object Operation extends ChiselEnum {
  // UnlockAndRespond is internal-only (not reachable from decode): when a lane's
  // AMU finishes a forwarded op, the returned request is re-injected with this
  // opcode. It releases the held tag like an unlock and delivers the AMU's
  // result to the PE when it resolves, so the PE only sees the response once
  // the tag is actually free.
  val Unlock, Lock, LockSetUnlockAndReturnCurrent,
      LockSetIfGreaterUnlockAndReturnCurrent,
      LockSetIfSignedLessUnlockAndReturnCurrent, LockAddNReturnCurrent,
      UnlockNoResponse, UnlockAndRespond = Value

  def decode(bits: UInt): Operation.Type = {
    MuxLookup(bits, Unlock)(
      Seq(
        "b0000".U -> Unlock,
        "b0001".U -> Lock,
        "b0010".U -> LockSetUnlockAndReturnCurrent,
        "b0011".U -> LockSetIfGreaterUnlockAndReturnCurrent,
        "b0100".U -> LockSetIfSignedLessUnlockAndReturnCurrent,
        "b0101".U -> LockAddNReturnCurrent,
        "b0111".U -> UnlockNoResponse
      )
    )
  }

  // Extension methods on Operation values. Defined here in the companion object,
  // so they're in implicit scope for any Operation.Type with no extra import:
  //   myReq.operation.isLock
  implicit class OperationOps(val op: Operation.Type) {
    def isLock: Bool =
      op === Lock || op === LockSetUnlockAndReturnCurrent || op === LockSetIfGreaterUnlockAndReturnCurrent || op === LockSetIfSignedLessUnlockAndReturnCurrent || op === LockAddNReturnCurrent

    def isUnlock: Bool =
      op === Unlock || op === UnlockNoResponse || op === UnlockAndRespond

    def shouldForwardToMemoryUnit: Bool =
      op === LockSetUnlockAndReturnCurrent || op === LockSetIfGreaterUnlockAndReturnCurrent || op === LockSetIfSignedLessUnlockAndReturnCurrent || op === LockAddNReturnCurrent
  }
}

object AtomicMode extends ChiselEnum {
  val Byte, Word, DoubleWord = Value

  def decode(bits: UInt): AtomicMode.Type = {
    MuxLookup(bits, DoubleWord)(
      Seq(
        "b00".U -> DoubleWord,
        "b01".U -> Byte,
        "b10".U -> Word,
        "b11".U -> DoubleWord
      )
    )
  }
}

class RequestType(n: Int, addrW: Int = 64) extends Bundle {
  require(addrW > 0, "addrW must be positive")
  val tag = UInt(addrW.W)
  val data = UInt(64.W)
  val operation = Operation()
  val isValid = Bool()
  val requestingPE = UInt(log2Ceil(n).W)
  val isBlocking = Bool()
  val atomicMode = AtomicMode()
  // When set, the conditional SET_IF_* ops compare operand vs memory as IEEE-754
  // floats instead of integers (so negative values order correctly). Ignored by
  // every non-conditional op.
  val floatCompare = Bool()
  // Sender-defined correlation id, echoed back in the response.
  val meta = UInt(8.W)
  // Set by the AMU on its return: whether the read-modify-write actually
  // stored. Only meaningful on the AMU's UnlockAndRespond return path; ignored
  // for ordinary requests. Drives response status bit 1.
  val writeOccurred = Bool()
}

class WriteIndexEntry(tagStoreSize: Int) extends Bundle {
  val valid = Bool()
  val index = UInt(log2Ceil(tagStoreSize).W)
}

object LockServer {
  val ReqWidth = 144
  val RespWidth = 144
}

class LockServer(
    val n: Int,
    val p: Int = 32,
    val tagStoreSize: Int = 128,
    val addrW: Int = 64,
    val lockTraceCsv: Boolean = false,
    val singleSelect: Boolean = false,
    // Per-PE in-flight credit budget: how many requests a PE may have unresolved
    // inside the server (pipeline + replay queue + AMU + response queue). Bounds
    // every per-PE structure, so all of them are provably overflow-free.
    val inflightDepth: Int = 5
) extends Module {
  import LockServer._
  require(addrW > 0 && addrW <= 64, "addrW must be in the range [1, 64]")
  require(inflightDepth >= 1, "inflightDepth must be at least 1")

  // One atomic memory unit per pipeline lane (p). Each AMU is an AXI master to
  // HBM; they share the single HBM port through chext's N->1 AXI mux. The mux
  // appends log2Ceil(p) bits to the AXI id, so the HBM-facing config is wider.
  // AXI id = AMU table slot index; the mux appends the lane index.
  // Each lane serves n/p PEs with up to inflightDepth forwards outstanding each.
  val amuTableSize = ((n + p - 1) / p) * inflightDepth
  val amuAxiCfg =
    axi4.Config(
      wId = math.max(1, log2Ceil(amuTableSize)),
      wAddr = addrW,
      wData = 64,
      read = true,
      write = true
    )
  val gmemMuxCfg =
    axi4.full.components.MuxConfig(axiSlaveCfg = amuAxiCfg, numSlaves = p)

  val io = IO(new Bundle {
    val req = Vec(n, Flipped(Decoupled(new AxiStream(ReqWidth))))
    val resp = Vec(n, Decoupled(new AxiStream(RespWidth)))
    val gmem = axi4.Master(gmemMuxCfg.axiMasterCfg)
  })

  val gmemMux = Module(new axi4.full.components.Mux(gmemMuxCfg))
  val atomicMemoryUnits =
    Seq.tabulate(p)(_ =>
      Module(new AtomicMemoryUnit(n, amuTableSize, addrW, amuAxiCfg))
    )
  // Worst case a single lane's FIFO must hold every request routed to it:
  // n/p PEs times inflightDepth forwards each.
  val amuFifoDepth = amuTableSize
  val amuFifos =
    Seq.fill(p)(
      Module(new Queue(new RequestType(n, addrW), entries = amuFifoDepth))
    )
  // AMU returns are urgent unlock+response operations: if they sit behind a
  // replayed lock that is waiting for a free tag slot, the return that would
  // free the slot can be head-of-line blocked. Keep them in lane-local queues
  // and inject them before ordinary arbiter traffic.
  val amuReturnQueues =
    Seq.fill(p)(Module(new Queue(new RequestType(n, addrW), entries = 2)))

  for (i <- 0 until p) {
    // FIFO output -> AMU input. (FIFO inputs and AMU outputs left unwired.)
    atomicMemoryUnits(i).io.req <> amuFifos(i).io.deq
    // AMU AXI master -> mux slave port.
    atomicMemoryUnits(i).io.gmem :=> gmemMux.s_axi(i)
  }
  // Mux master -> top-level HBM port.
  gmemMux.m_axi <> io.gmem.asFull

  // When requests come in, put them in small Queues

  // Tag store lives in registers
  // Data doesn't need init; valid bits must start clear so empty slots don't spuriously match.
  val tagStore = Reg(Vec(tagStoreSize, UInt(addrW.W)))
  val tagStoreValid = RegInit(VecInit(Seq.fill(tagStoreSize)(false.B)))
  val unlockTable = Wire(Vec(tagStoreSize, Bool()))
  val lockTable = Wire(Vec(tagStoreSize, Bool()))

  // Forward declaration: cycle-3 writers are read by the cycle-1 and cycle-2 forwarding compares.
  val writingRequests = Seq.fill(p)(Wire(new RequestType(n, addrW)))

  val availableTagTracker = Module(
    new AvailableSlotTracker(p = p, tagStoreSize = tagStoreSize)
  )
  val returnedIndices =
    Wire(Vec(2 * p, new WriteIndexEntry(tagStoreSize)))
  val trackerFreedEntries =
    Wire(Vec(2 * p, new WriteIndexEntry(tagStoreSize)))
  availableTagTracker.io.freed_entries := trackerFreedEntries

  val inputQueues =
    Seq.fill(n)(Module(new Queue(new RequestType(n, addrW), entries = 2)))

  // Per-PE pipelining state. A request is consumed (popped) the cycle the
  // arbiter selects it; if it cannot complete at cycle 3 it is re-enqueued into
  // its PE's replay queue and tried again. The credit counter `inflight` tracks
  // requests from admission until their response leaves io.resp (or until
  // cycle 3 for ops with no response); admission stops at inflightDepth, which
  // bounds every per-PE structure:
  //  - replay queue: each credited request occupies at most one slot at a time
  //    (it is either in the pipe, in the replay queue, at the AMU, or in the
  //    urgent AMU return queue, or in the response queue), so depth
  //    inflightDepth can never overflow.
  //  - response queue: at most one response per credit, released on deq.
  // Known deadlock (deferred to PE authors by contract): a PE that fills all
  // its credits with blocking locks whose release depends on its own later
  // unlock wedges itself -- the unlock can never be admitted.
  val replayQueues =
    Seq.fill(n)(
      Module(new Queue(new RequestType(n, addrW), entries = inflightDepth))
    )
  val respQueues =
    Seq.fill(n)(Module(new Queue(UInt(RespWidth.W), entries = inflightDepth)))
  val inflight =
    RegInit(VecInit(Seq.fill(n)(0.U(log2Ceil(inflightDepth + 1).W))))

  // Static PE -> lane mapping (mirrors the arbiter's bucketing): a PE lives in
  // exactly one bucket, and buckets `l` and `p + l` both resolve on lane `l`.
  // Everything a PE produces or receives therefore flows through one lane --
  // its retries, its AMU forwards, and its responses -- so all per-PE fanout
  // below is lane-local (p independent 1-to-(n/p) fanouts, never p-to-n).
  val bucketCount = if (singleSelect) p else 2 * p
  def laneOfPe(pe: Int): Int = {
    val bucket = pe / (n / bucketCount)
    if (bucket < p) bucket else bucket - p
  }

  for (i <- 0 until n) {
    val req = io.req(i)
    val enq = inputQueues(i).io.enq

    req.ready := enq.ready

    enq.valid := req.valid
    enq.bits.tag := req.bits.tdata(addrW - 1, 0)
    enq.bits.data := req.bits.tdata(127, 64)
    enq.bits.operation := Operation.decode(req.bits.tdata(131, 128))
    enq.bits.isBlocking := req.bits.tdata(132, 132)
    enq.bits.requestingPE := i.U
    enq.bits.isValid := true.B
    enq.bits.atomicMode := AtomicMode.decode(req.bits.tdata(134, 133))
    enq.bits.floatCompare := req.bits.tdata(135, 135)
    enq.bits.meta := req.bits.tdata(143, 136)
    // Only ever set by the AMU on its return path; default for fresh requests.
    enq.bits.writeOccurred := false.B
  }

  val arbiter = Module(
    new InputArbiter(
      n = n,
      p = p,
      tagStoreSize = tagStoreSize,
      addrW = addrW,
      singleSelect = singleSelect
    )
  )
  arbiter.io.availableSlots := availableTagTracker.io.selected_slots
  availableTagTracker.io.consumed := arbiter.io.consumedSlots

  // Arbiter feed: the replay queue drains strictly before the main input queue
  // (a replayed request already holds a credit; fresh requests need a free
  // credit to be admitted). The arbiter captures the request bits into its own
  // registers on the sameCycleSelectedMask cycle, so popping at selection is
  // safe -- a held/stalled selection replays from the arbiter's copy.
  for (i <- 0 until n) {
    val replay = replayQueues(i).io.deq
    val main = inputQueues(i).io.deq
    val useReplay = replay.valid
    arbiter.io.requests(i).valid :=
      useReplay || (main.valid && inflight(i) < inflightDepth.U)
    arbiter.io.requests(i).bits := Mux(useReplay, replay.bits, main.bits)
    replay.ready := arbiter.io.sameCycleSelectedMask(i) && useReplay
    main.ready := arbiter.io.sameCycleSelectedMask(i) && !useReplay
  }

  // Take the results of the arbiter and latch them. Lane-local AMU returns have
  // priority; while one is present, the arbiter holds any ordinary selection for
  // that lane so no input/replay request is consumed and dropped.
  val arbiterOut = Wire(Vec(p, new RequestType(n, addrW)))
  for (i <- 0 until p) {
    val urgentReturn = amuReturnQueues(i).io.deq
    arbiter.io.laneBlocked(i) := urgentReturn.valid
    urgentReturn.ready := true.B
    arbiterOut(i) := Mux(urgentReturn.valid, urgentReturn.bits, arbiter.io.selectedRequests(i))
  }

  val emptyWriteIndex = 0.U.asTypeOf(new WriteIndexEntry(tagStoreSize))

  val selected_requests = Seq.tabulate(p)(i =>
    RegNext(arbiterOut(i), 0.U.asTypeOf(new RequestType(n, addrW)))
  )
  // Latched alongside `selected_requests` (same pipeline stage): the arbiter
  // forwards only consumed tracker slots. They are pipelined separately and
  // returned unless one actually commits.
  val selected_write_indices: Seq[WriteIndexEntry] =
    Seq.tabulate(p)(i =>
      RegNext(
        arbiter.io.selectedWriteIndices(i),
        emptyWriteIndex
      )
    )
  // In the next cycle, we a) do a very large fanout and compare and
  // b) we also compare against the current writers

  // Bit-exact wide equality mapped to a LUT reduction tree instead of a CARRY8
  // chain. Vivado infers a carry chain for a 64-bit `===`; carry chains are
  // column-locked and vertical, so the placer cannot spread them -- they are the
  // residual routing-congestion hotspot in this tag-match CAM (tagStoreSize * p
  // = many comparators packed into one pocket). Comparing in 6-bit chunks
  // (one LUT6 each) and AND-reducing keeps the result identical to `a === b`
  // while letting every comparator place freely. Used only for the high-
  // multiplicity store compare below; the small p*p compares stay as `===`.
  // The reductions are grouped in sixes so each level packs into one LUT6 and
  // the tree stays shallow (~3 LUT levels for a 64b tag); a flat reduce would
  // build a deep linear chain, and `.andR`/`===` would re-infer a carry chain.
  def tagEqLut(a: UInt, b: UInt): Bool = {
    def andTree(bits: Seq[Bool]): Bool =
      if (bits.length <= 6) bits.reduce(_ && _)
      else andTree(bits.grouped(6).map(_.reduce(_ && _)).toSeq)
    val chunkEq = (a ^ b).asBools.grouped(6).map(g => !VecInit(g).asUInt.orR).toSeq
    andTree(chunkEq)
  }

  val storageComparison = Seq.fill(tagStoreSize)(Seq.fill(p)(Reg(Bool())))
  // unlockMatch(k)(i): cycle-1 slot k is an unlock that matches tagStore entry i.
  // At most one i matches per k since tags are unique in the store.
  val unlockMatch = Wire(Vec(p, Vec(tagStoreSize, Bool())))
  for (i <- 0 until tagStoreSize) {
    for (k <- 0 until p) {
      val compared =
        tagEqLut(selected_requests(k).tag, tagStore(i)) && tagStoreValid(i)
      storageComparison(i)(k) := compared
      unlockMatch(k)(i) := compared && selected_requests(
        k
      ).operation.isUnlock &&
        selected_requests(k).isValid
    }
  }
  for (i <- 0 until tagStoreSize) {
    unlockTable(i) := (0 until p).map(k => unlockMatch(k)(i)).reduce(_ || _)
  }
  // Free-list return of unlocked slots. Register the match vector before encoding
  // so the tag compare and the one-hot index encode fall on separate
  // pipeline stages (their combined path failed timing). Costs one extra cycle
  // before a freed slot returns to the tracker; the tag release above
  // (unlockTable -> tagStoreValid) is unaffected.
  val delayedUnlockMatch = RegNext(unlockMatch, 0.U.asTypeOf(unlockMatch))
  for (k <- 0 until p) {
    returnedIndices(k).valid := delayedUnlockMatch(k).asUInt.orR
    returnedIndices(k).index := OHToUInt(delayedUnlockMatch(k))
  }
  val delayedUnlockReturnedIndices = RegNext(
    VecInit((0 until p).map(k => returnedIndices(k))),
    VecInit(Seq.fill(p)(0.U.asTypeOf(new WriteIndexEntry(tagStoreSize))))
  )
  for (k <- 0 until p) {
    trackerFreedEntries(k) := delayedUnlockReturnedIndices(k)
  }

  val cycle1Comparison = Seq.fill(p)(Seq.fill(p)(Wire(Bool())))
  for (i <- 0 until p) {
    for (k <- 0 until p) {
      cycle1Comparison(i)(k) := (selected_requests(
        k
      ).tag === writingRequests(i).tag && writingRequests(i).isValid)
    }
  }

  val cycle1ComparisonCompacted = Seq.fill(p)(Reg(Bool()))
  for (k <- 0 until p) {
    cycle1ComparisonCompacted(k) :=
      (0 until p).map(i => cycle1Comparison(i)(k)).reduce(_ || _)
  }

  // In the next cycle, we reduce the tag comparisons down to 1 per p, and do another
  // comparison with the in-flight requests

  val cycle2requests = Seq.tabulate(p)(i =>
    RegNext(selected_requests(i), 0.U.asTypeOf(new RequestType(n, addrW)))
  )
  val cycle2indices =
    Seq.tabulate(p)(i =>
      RegNext(
        selected_write_indices(i),
        0.U.asTypeOf(new WriteIndexEntry(tagStoreSize))
      )
    )
  val storageComparisonReduced = Seq.fill(p)(Reg(Bool()))
  for (k <- 0 until p) {
    storageComparisonReduced(k) :=
      (0 until tagStoreSize).map(i => storageComparison(i)(k)).reduce(_ || _)
  }

  val cycle2Comparison = Seq.fill(p)(Seq.fill(p)(Wire(Bool())))
  for (i <- 0 until p) {
    for (k <- 0 until p) {
      cycle2Comparison(i)(k) := (cycle2requests(
        k
      ).tag === writingRequests(i).tag && writingRequests(i).isValid)
    }
  }

  // Peer dedup: two slots can't both lock the same tag in the same cycle, so
  // exactly one lane in each same-tag locker group wins and the rest report a
  // conflict and retry. A fixed "lowest lane index wins" tiebreak can starve a
  // high-index peer, so priority is rotated. To keep the routing-heavy tag
  // fanout small, the tag compares stay triangular and mirrored (one
  // comparator per lane pair, reused for both directions), and all priority work
  // happens on a p-wide collision bitvector -- never on the tags. The rotation
  // comes from a free-running LFSR turned into a p-bit mask, which is *registered*
  // so the LFSR and the shifter float away from this region and only the small
  // mask crosses in. Winner = first set collision bit scanning upward from the
  // rotation with wraparound (the rotated priority-encoder idiom from
  // InputArbiter). Tag-equality is transitive among valid lockers, so every
  // member of a group sees the same collision vector and the same mask -- exactly
  // one lane is the unique winner.
  val cycle2PeerConflict = Wire(Vec(p, Bool()))
  if (p == 1) {
    cycle2PeerConflict(0) := false.B
  } else {
    val validLock = (0 until p).map(k =>
      cycle2requests(k).isValid && cycle2requests(k).operation.isLock
    )
    // Triangular tag-equality, computed once and mirrored; tagEq(j)(k) is only
    // ever read for j != k.
    val tagEq = Array.ofDim[Bool](p, p)
    for (k <- 0 until p) {
      for (j <- 0 until k) {
        val e = validLock(j) && validLock(k) &&
          (cycle2requests(j).tag === cycle2requests(k).tag)
        tagEq(j)(k) = e
        tagEq(k)(j) = e
      }
    }

    val rotWidth = log2Ceil(p)
    val peerRotation = LFSR(16)(rotWidth - 1, 0)
    // Register the rotation mask so its generation logic can be placed elsewhere;
    // only this p-bit signal enters the dedup. Bits below the rotation are the
    // wraparound (low-priority) region.
    val rotMask = RegInit(0.U(p.W))
    rotMask := ((1.U(p.W) << peerRotation) - 1.U)(p - 1, 0)

    for (k <- 0 until p) {
      // Collision group including self: bit k = lane k is a valid lock, bit j =
      // a same-tag valid-lock peer.
      val group = Wire(UInt(p.W))
      group := VecInit((0 until p).map { j =>
        if (j == k) validLock(k) else tagEq(j)(k)
      }).asUInt
      val upperHits = group & ~rotMask
      val lowerHits = group & rotMask
      val winnerOH = Mux(
        upperHits.orR,
        PriorityEncoderOH(upperHits),
        PriorityEncoderOH(lowerHits)
      )
      // Defer iff the group is non-empty and the rotated winner is another lane.
      cycle2PeerConflict(k) := group.orR && !winnerOH(k)
    }
  }

  val cycle2ComparisonCompacted = Seq.fill(p)(Reg(Bool()))
  for (k <- 0 until p) {
    cycle2ComparisonCompacted(k) :=
      (0 until p).map(i => cycle2Comparison(i)(k)).reduce(_ || _) ||
        cycle1ComparisonCompacted(k) ||
        cycle2PeerConflict(k)
  }

  // In the next cycle, we do the following:
  // a) respond to requests
  // b) write locks

  val cycle3failures = Seq.tabulate(p)(i =>
    cycle2ComparisonCompacted(i) || storageComparisonReduced(i)
  )
  val cycle3requests = Seq.tabulate(p)(i =>
    RegNext(cycle2requests(i), 0.U.asTypeOf(new RequestType(n, addrW)))
  )
  val cycle3indices =
    Seq.tabulate(p)(i =>
      RegNext(cycle2indices(i), 0.U.asTypeOf(new WriteIndexEntry(tagStoreSize)))
    )
  // A lock is a *candidate* writer if it carries a valid store slot; whether it
  // actually commits is gated below by the conflict checks (cycle3failures).
  // Kept separate from writingRequests.isValid so commitsWrite does not read
  // writingRequests -- writingRequests.isValid is itself driven by commitsWrite.
  val rawWriterValid = Seq.tabulate(p)(i =>
    cycle3requests(i).isValid && cycle3requests(i).operation.isLock &&
      cycle3indices(i).valid
  )
  // Lock commit is computed here (rather than below) because the response path
  // now depends on whether a forwardable lock actually committed.
  val commitsWrite =
    Seq.tabulate(p)(i => rawWriterValid(i) && !cycle3failures(i))
  // WriteIndex.id is a fixed 16b placeholder; narrow it to the tagStore width here.
  val cycle3indexBits = Seq.tabulate(p)(i =>
    cycle3indices(i).index(availableTagTracker.entrySize - 1, 0)
  )

  // Resolve selected requests. Each real lane request takes exactly one of
  // four exits:
  //  - retry: re-enqueue into its PE's replay queue (no response yet)
  //  - forward: hand to this lane's AMU (the response comes later, when the
  //    AMU's return flows back through as an UnlockAndRespond)
  //  - silent: complete with no response (external UnlockNoResponse)
  //  - respond: complete with a response into its PE's response queue
  val laneRespond = Wire(Vec(p, Bool()))
  val laneRetry = Wire(Vec(p, Bool()))
  val laneSilent = Wire(Vec(p, Bool()))
  val laneRespData = Wire(Vec(p, UInt(RespWidth.W)))
  for (i <- 0 until p) {
    val req3 = cycle3requests(i)
    val isReal = req3.isValid
    val op = req3.operation
    // Unlock always succeeds; lock succeeds iff no conflict was detected.
    val success = op.isUnlock || !cycle3failures(i)
    // Retry without a response when a lock has no store slot, or when a blocking
    // lock finds the tag occupied/contended.
    val blockingConflict = op.isLock && req3.isBlocking && cycle3failures(i)
    val lockNoSlot = op.isLock && !cycle3failures(i) && !cycle3indices(i).valid
    // An unlock that tag-matches an in-flight committing lock but misses the
    // store would otherwise "succeed" while releasing nothing: the lock only
    // writes the store at its own cycle 3, after this unlock's cycle-1 compare.
    // Send it around again so it finds the committed entry. (A lock and an
    // unlock for the same tag selected the same cycle still race; same-PE that
    // close is impossible, and cross-PE it is an inherently racy program --
    // unchanged from the unpipelined design.)
    val unlockMissedWriter = op.isUnlock && cycle2ComparisonCompacted(i) &&
      !storageComparisonReduced(i)
    // The three retry causes and forwardSuccess are pairwise disjoint:
    // blockingConflict needs a failure, lockNoSlot needs a missing slot, and
    // unlockMissedWriter is not a lock; commitsWrite needs the opposite of all.
    val try_again = lockNoSlot || blockingConflict || unlockMissedWriter

    // A forwardable lock that committed is handed to its AMU instead of being
    // answered now: keep the tag held, send no response.
    val forwardSuccess =
      commitsWrite(i) && op.shouldForwardToMemoryUnit

    // Forwarded ops go into this lane's AMU FIFO (lane i -> AMU i). The FIFO is
    // sized for the worst case, so it must always have room on a commit.
    amuFifos(i).io.enq.valid := forwardSuccess
    amuFifos(i).io.enq.bits := req3
    assert(
      !forwardSuccess || amuFifos(i).io.enq.ready,
      "AMU FIFO overflow: a forwardable lock committed but its FIFO was full"
    )

    laneRetry(i) := isReal && try_again
    laneSilent(i) := isReal && !try_again && !forwardSuccess &&
      op === Operation.UnlockNoResponse
    laneRespond(i) := isReal && !try_again && !forwardSuccess &&
      op =/= Operation.UnlockNoResponse
    // Status bit 1: for an AMU return (UnlockAndRespond), whether the
    // conditional read-modify-write actually stored, as reported by the AMU.
    // For every other op the store-happened notion is meaningless, so it just
    // mirrors success (1 when the op succeeded).
    val writeOccurred =
      Mux(op === Operation.UnlockAndRespond, req3.writeOccurred, success)
    // Response layout:
    //   bits  7:0     = success status (bit 0 = success, bit 1 = write occurred)
    //   bits 71:8     = requested lock addr
    //   bits 135:72   = previous memory value (HBM-backed atomic ops only)
    //   bits 143:136  = metadata echoed from the request
    laneRespData(i) := Cat(
      req3.meta,
      Mux(op === Operation.UnlockAndRespond, req3.data, 0.U(64.W)),
      // Zero-extend the addrW-bit tag to a fixed 64-bit field so the response
      // layout (and RespWidth) is independent of addrW: consumers decode the
      // tag at bits 71:8 and the previous value at 135:72 regardless of the
      // configured address width. Without this, addrW < 64 shifts every field.
      req3.tag.pad(64),
      0.U(6.W),
      writeOccurred,
      success
    )
  }

  // Registered boundary between the cycle-3 resolution logic and the per-PE
  // queue fanout: the conflict logic stays local to its stage, and only these
  // registered signals cross into the per-PE region.
  val laneRespondReg = RegNext(laneRespond, VecInit(Seq.fill(p)(false.B)))
  val laneRetryReg = RegNext(laneRetry, VecInit(Seq.fill(p)(false.B)))
  val laneSilentReg = RegNext(laneSilent, VecInit(Seq.fill(p)(false.B)))
  val laneRespDataReg =
    RegNext(laneRespData, VecInit(Seq.fill(p)(0.U(RespWidth.W))))
  val laneReqReg = Seq.tabulate(p)(i =>
    RegNext(cycle3requests(i), 0.U.asTypeOf(new RequestType(n, addrW)))
  )

  // AMU returns re-enter through the lane-local urgent return queue as
  // UnlockAndRespond ops that release the held tag and deliver the result at
  // their own cycle 3.
  val amuReturn = Seq.tabulate(p) { i =>
    val ret = Wire(new RequestType(n, addrW))
    ret := atomicMemoryUnits(i).io.resp.bits
    ret.operation := Operation.UnlockAndRespond
    ret.isValid := true.B
    ret
  }
  for (i <- 0 until p) {
    val r = atomicMemoryUnits(i).io.resp
    amuReturnQueues(i).io.enq.valid := r.valid
    amuReturnQueues(i).io.enq.bits := amuReturn(i)
    r.ready := amuReturnQueues(i).io.enq.ready
  }

  // Per-PE queue feeds. All signals here come from the PE's own lane, so this
  // is p independent 1-to-(n/p) fanouts rather than a p-to-n crossbar, and a
  // PE's replay/response queues each see at most one enqueue per cycle by
  // construction. AMU returns bypass these replay queues through the lane-local
  // urgent return path above.
  for (q <- 0 until n) {
    val l = laneOfPe(q)
    val peHere = laneReqReg(l).requestingPE === q.U
    val retryHere = laneRetryReg(l) && peHere
    val respondHere = laneRespondReg(l) && peHere
    val silentHere = laneSilentReg(l) && peHere

    replayQueues(q).io.enq.valid := retryHere
    replayQueues(q).io.enq.bits := laneReqReg(l)
    assert(
      !retryHere || replayQueues(q).io.enq.ready,
      "Replay queue overflow: more replayed requests than credits"
    )

    respQueues(q).io.enq.valid := respondHere
    respQueues(q).io.enq.bits := laneRespDataReg(l)
    assert(
      !respondHere || respQueues(q).io.enq.ready,
      "Response queue overflow: more responses than credits"
    )

    // Credit accounting: reserve on admission from the main input queue,
    // release when the response leaves io.resp (or at resolution for silent
    // completions, which produce no response).
    val inc = inputQueues(q).io.deq.fire
    val decResp = respQueues(q).io.deq.fire
    val decSilent = silentHere
    assert(
      inflight(q) +& inc.asUInt >= decResp.asUInt +& decSilent.asUInt,
      "In-flight credit underflow"
    )
    inflight(q) := inflight(q) +& inc.asUInt - decResp.asUInt - decSilent.asUInt
  }

  for (i <- 0 until n) {
    io.resp(i).valid := respQueues(i).io.deq.valid
    io.resp(i).bits.tdata := respQueues(i).io.deq.bits
    io.resp(i).bits.tlast := true.B
    respQueues(i).io.deq.ready := io.resp(i).ready
  }

  // writingRequests is the in-flight conflict source for the cycle-1/cycle-2 tag
  // compares. Only locks that ACTUALLY commit may block a same-tag peer: a lock
  // that fails writes nothing to the store and returns its slot, so treating it
  // as a writer lets a failed lock perpetually kill its same-tag successors --
  // a livelock when one tag is contended every cycle (no burst leader ever
  // escapes). Gating isValid by commitsWrite extends this path by the
  // cycle3failures term on purpose; correctness wins over the cycle it costs.
  for (i <- 0 until p) {
    writingRequests(i) := cycle3requests(i)
    writingRequests(i).isValid := commitsWrite(i)

    when(commitsWrite(i)) {
      tagStore(cycle3indexBits(i)) := writingRequests(i).tag
    }
  }

  val rawSlotReturned = Wire(Vec(p, Bool()))
  for (i <- 0 until p) {
    val rawSlot = cycle3indices(i)
    val rawSlotCommitted =
      (0 until p)
        .map(j =>
          rawSlot.valid && commitsWrite(j) && cycle3indices(j).valid &&
            (cycle3indices(j).index === rawSlot.index)
        )
        .reduce(_ || _)

    rawSlotReturned(i) := rawSlot.valid && !rawSlotCommitted
    returnedIndices(p + i).valid := rawSlotReturned(i)
    returnedIndices(p + i).index := rawSlot.index
    trackerFreedEntries(p + i) := returnedIndices(p + i)
  }

  for (j <- 0 until tagStoreSize) {
    lockTable(j) := (0 until p)
      .map(i => commitsWrite(i) && (cycle3indexBits(i) === j.U))
      .reduce(_ || _)
  }

  for (i <- 0 until tagStoreSize) {
    when(unlockTable(i)) {
      tagStoreValid(i) := false.B
    }.elsewhen(lockTable(i)) {
      tagStoreValid(i) := true.B
    }
  }

  if (lockTraceCsv) {
    val cycle = RegInit(0.U(64.W))
    cycle := cycle + 1.U

    for (k <- 0 until p) {
      when(unlockMatch(k).asUInt.orR) {
        printf(
          p"LOCKTRACE,${cycle},release,${selected_requests(k).requestingPE},${selected_requests(k).tag}\n"
        )
      }
    }

    for (i <- 0 until p) {
      when(commitsWrite(i)) {
        printf(
          p"LOCKTRACE,${cycle},acquire,${cycle3requests(i).requestingPE},${cycle3requests(i).tag}\n"
        )
      }
    }
  }

}
