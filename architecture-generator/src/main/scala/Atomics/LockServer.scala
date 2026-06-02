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
//   bit  135     = reserved flag
// Response packet skeleton:
//   bit 0 = current success status
// Remaining request/response bits are reserved for future HBM-backed atomics.

object Operation extends ChiselEnum {
  val Unlock, Lock, LockSetUnlockAndReturnCurrent,
      LockSetIfGreaterUnlockAndReturnCurrent,
      LockSetIfSignedLessUnlockAndReturnCurrent, LockAddOneReturnCurrent,
      UnlockNoResponse = Value

  def decode(bits: UInt): Operation.Type = {
    MuxLookup(bits, Unlock)(
      Seq(
        "b0000".U -> Unlock,
        "b0001".U -> Lock,
        "b0010".U -> LockSetUnlockAndReturnCurrent,
        "b0011".U -> LockSetIfGreaterUnlockAndReturnCurrent,
        "b0100".U -> LockSetIfSignedLessUnlockAndReturnCurrent,
        "b0101".U -> LockAddOneReturnCurrent,
        "b0111".U -> UnlockNoResponse
      )
    )
  }

  // Extension methods on Operation values. Defined here in the companion object,
  // so they're in implicit scope for any Operation.Type with no extra import:
  //   myReq.operation.isLock
  implicit class OperationOps(val op: Operation.Type) {
    def isLock: Bool =
      op === Lock || op === LockSetUnlockAndReturnCurrent || op === LockSetIfGreaterUnlockAndReturnCurrent || op === LockSetIfSignedLessUnlockAndReturnCurrent || op === LockAddOneReturnCurrent

    def isUnlock: Bool = op === Unlock || op === UnlockNoResponse

    def shouldForwardToMemoryUnit: Bool =
      op === LockSetUnlockAndReturnCurrent || op === LockSetIfGreaterUnlockAndReturnCurrent || op === LockSetIfSignedLessUnlockAndReturnCurrent || op === LockAddOneReturnCurrent
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
}

class WriteIndexEntry(tagStoreSize: Int) extends Bundle {
  val valid = Bool()
  val index = UInt(log2Ceil(tagStoreSize).W)
}

object LockServer {
  val ReqWidth = 136
  val RespWidth = 136
}

class LockServer(
    val n: Int,
    val p: Int = 32,
    val tagStoreSize: Int = 128,
    val addrW: Int = 64,
    val lockTraceCsv: Boolean = false
) extends Module {
  import LockServer._
  require(addrW > 0 && addrW <= 64, "addrW must be in the range [1, 64]")

  // One atomic memory unit per pipeline lane (p). Each AMU is an AXI master to
  // HBM; they share the single HBM port through chext's N->1 AXI mux. The mux
  // appends log2Ceil(p) bits to the AXI id, so the HBM-facing config is wider.
  // AXI id = AMU table slot index (log2Ceil(n/p)); the mux appends the lane index.
  val amuAxiCfg =
    axi4.Config(
      wId = log2Ceil(n / p),
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
    Seq.tabulate(p)(i =>
      Module(new AtomicMemoryUnit(n, p, i, addrW, amuAxiCfg))
    )
  // Worst case a single lane's FIFO must hold every request routed to it.
  val amuFifoDepth = (n + p - 1) / p
  val amuFifos =
    Seq.fill(p)(
      Module(new Queue(new RequestType(n, addrW), entries = amuFifoDepth))
    )

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
  // In-flight scoreboard, indexed by queue (PE). A queue is masked from the arbiter
  // from the cycle its head is selected until that request resolves at cycle 3.
  // Without it, the 2-cycle arbiter re-samples a still-unpopped head and double-
  // processes it. Set/clear are disjoint per queue (a masked queue can't be
  // re-selected), so there is no set-vs-clear race.
  val inputQueuesViewed = RegInit(VecInit(Seq.fill(n)(false.B)))
  // Per-PE override: set when an AMU returns a forwarded request, cleared when the
  // arbiter re-accepts that PE. While set, the arbiter sees the queue head as an
  // UnlockNoResponse, so the re-injected request flows through the unlock stages
  // (releasing the held tag) without producing a second PE response.
  val unrOverride = RegInit(VecInit(Seq.fill(n)(false.B)))
  // Ports [0, p) carry cycle-3 lane responses; ports [p, 2p) carry AMU responses.
  // Each PE has at most one in-flight request, so each response port only needs a
  // one-entry holding register rather than a general multi-port queue.
  val responsePortValid = Wire(Vec(2 * p, Bool()))
  val responsePortPe = Wire(Vec(2 * p, UInt(log2Ceil(n).W)))
  val responsePortData = Wire(Vec(2 * p, UInt(RespWidth.W)))
  for (i <- 0 until 2 * p) {
    responsePortValid(i) := false.B
    responsePortPe(i) := 0.U
    responsePortData(i) := 0.U
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
  }

  val arbiter = Module(
    new InputArbiter(n = n, p = p, tagStoreSize = tagStoreSize, addrW = addrW)
  )
  arbiter.io.availableSlots := availableTagTracker.io.selected_slots
  availableTagTracker.io.consumed := arbiter.io.consumedSlots

  for (i <- 0 until n) {
    val deq = inputQueues(i).io.deq
    arbiter.io.requests(i).valid := deq.valid && !inputQueuesViewed(i)
    arbiter.io.requests(i).bits := deq.bits
    when(unrOverride(i)) {
      arbiter.io.requests(i).bits.operation := Operation.UnlockNoResponse
    }
  }

  // Take the results of the arbiter and latch them.
  // Override isValid from the arbiter's grant so empty slots don't poison comparisons.
  val arbiterOut = Wire(Vec(p, new RequestType(n, addrW)))
  for (i <- 0 until p) {
    arbiterOut(i) := arbiter.io.selectedRequests(i)
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

  val storageComparison = Seq.fill(tagStoreSize)(Seq.fill(p)(Reg(Bool())))
  // unlockMatch(k)(i): cycle-1 slot k is an unlock that matches tagStore entry i.
  // At most one i matches per k since tags are unique in the store.
  val unlockMatch = Wire(Vec(p, Vec(tagStoreSize, Bool())))
  for (i <- 0 until tagStoreSize) {
    for (k <- 0 until p) {
      val compared =
        (selected_requests(k).tag === tagStore(i)) && tagStoreValid(i)
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

  // Resolve selected requests and present completed responses to the one-entry
  // per-PE response buffers below.
  val laneReal = Wire(Vec(p, Bool())) // lane carries a real request
  val lanePop = Wire(Vec(p, Bool())) // request is done (pop its queue)
  val lanePe = Wire(Vec(p, UInt(log2Ceil(n).W))) // source queue of the request
  for (i <- 0 until p) {
    val pe = cycle3requests(i).requestingPE
    val isReal = cycle3requests(i).isValid
    // Unlock always succeeds; lock succeeds iff no conflict was detected.
    val success =
      cycle3requests(i).operation.isUnlock || !cycle3failures(i)
    // Retry without a response when a lock has no store slot, or when a blocking
    // lock finds the tag occupied/contended.
    val blockingConflict =
      cycle3requests(i).operation.isLock && cycle3requests(i).isBlocking &&
        cycle3failures(i)
    val try_again =
      (cycle3requests(i).operation.isLock && !cycle3failures(
        i
      ) && !cycle3indices(i).valid) || blockingConflict

    // A forwardable lock that committed is handed to its AMU instead of being
    // answered now: keep the tag held, stay masked, don't pop, send no response.
    val forwardSuccess =
      commitsWrite(i) && cycle3requests(i).operation.shouldForwardToMemoryUnit
    // The self-injected unlock that releases a forwarded op produces no response
    // (the PE already got its answer when the AMU returned).
    val isNoRespUnlock =
      cycle3requests(i).operation === Operation.UnlockNoResponse

    // Forwarded ops go into this lane's AMU FIFO (lane i -> AMU i). The FIFO is
    // sized for the worst case, so it must always have room on a commit.
    amuFifos(i).io.enq.valid := forwardSuccess
    amuFifos(i).io.enq.bits := cycle3requests(i)
    assert(
      !forwardSuccess || amuFifos(i).io.enq.ready,
      "AMU FIFO overflow: a forwardable lock committed but its FIFO was full"
    )

    responsePortPe(i) := pe
    responsePortData(i) := success.asUInt
    // Empty lanes, retries, forwarded ops, and the release unlock send nothing.
    responsePortValid(i) := isReal && !try_again && !forwardSuccess &&
      !isNoRespUnlock

    laneReal(
      i
    ) := isReal && !forwardSuccess // forwards stay masked until the AMU returns
    lanePop(i) := isReal && !try_again && !forwardSuccess
    lanePe(i) := pe
  }

  // AMU responses use the upper response ports [p, 2p).
  // Format: status=1 in bits[63:0], returned value in bits[127:64], bits[135:128]
  // left as a placeholder. AMU i targets the PE carried in its returned request.
  for (i <- 0 until p) {
    val r = atomicMemoryUnits(i).io.resp
    responsePortValid(p + i) := r.valid
    responsePortPe(p + i) := r.bits.requestingPE
    responsePortData(p + i) := Cat(0.U(8.W), r.bits.data, 1.U(64.W))
  }

  // Pipeline producer ports before the PE response crossbar. This keeps the
  // conflict/AMU completion logic local to its producer cycle and gives Vivado a
  // registered boundary before the 2*p-to-n fanout.
  val responsePortValidReg = RegNext(
    responsePortValid,
    VecInit(Seq.fill(2 * p)(false.B))
  )
  val responsePortPeReg = RegNext(
    responsePortPe,
    VecInit(Seq.fill(2 * p)(0.U(log2Ceil(n).W)))
  )
  val responsePortDataReg = RegNext(
    responsePortData,
    VecInit(Seq.fill(2 * p)(0.U(RespWidth.W)))
  )

  // Per-PE: did an AMU return a forwarded request for this PE this cycle? At most
  // one AMU can (each PE has a single in-flight forward), so this is collision-free.
  val amuRespondedFor = Wire(Vec(n, Bool()))
  for (q <- 0 until n) {
    amuRespondedFor(q) := (0 until p)
      .map { i =>
        val r = atomicMemoryUnits(i).io.resp
        r.valid && (r.bits.requestingPE === q.U)
      }
      .reduce(_ || _)
  }

  val respValid = RegInit(VecInit(Seq.fill(n)(false.B)))
  val respData = Reg(Vec(n, UInt(RespWidth.W)))

  for (q <- 0 until n) {
    val hits = VecInit(
      (0 until 2 * p).map(i =>
        responsePortValidReg(i) && (responsePortPeReg(i) === q.U)
      )
    )
    val hasHit = hits.asUInt.orR
    val canAccept = !respValid(q) || io.resp(q).ready

    assert(
      PopCount(hits) <= 1.U,
      "Response crossbar collision: multiple producers targeted the same PE"
    )
    assert(
      !hasHit || canAccept,
      "Response buffer overflow: PE did not consume its previous response"
    )

    when(hasHit) {
      respValid(q) := true.B
      respData(q) := Mux1H(hits, responsePortDataReg)
    }.elsewhen(io.resp(q).ready) {
      respValid(q) := false.B
    }
  }

  // Queue management, all keyed by requestingPE (not lane index):
  //  - pop a queue iff a resolving lane came from it,
  //  - scoreboard: set when the arbiter selects a head, clear when it resolves
  //    (pop or retry) so retries can be re-selected. Set and clear are mutually
  //    exclusive per queue: a masked head is never re-offered to the arbiter.
  //  - an AMU response unmasks the PE and arms its UNR override so the re-selected
  //    request flows through as the releasing unlock.
  for (q <- 0 until n) {
    val resolvedHere =
      (0 until p).map(i => laneReal(i) && (lanePe(i) === q.U)).reduce(_ || _)
    val poppedHere =
      (0 until p).map(i => lanePop(i) && (lanePe(i) === q.U)).reduce(_ || _)

    inputQueues(q).io.deq.ready := poppedHere

    // sameCycleSelectedMask and amuRespondedFor cannot both fire for the same PE:
    // the PE stays masked from the forward until the AMU response unmasks it.
    when(arbiter.io.sameCycleSelectedMask(q)) {
      inputQueuesViewed(q) := true.B
      unrOverride(q) := false.B
    }.elsewhen(amuRespondedFor(q)) {
      inputQueuesViewed(q) := false.B
      unrOverride(q) := true.B
    }.elsewhen(resolvedHere) {
      inputQueuesViewed(q) := false.B
    }
  }

  for (i <- 0 until n) {
    io.resp(i).valid := respValid(i)
    io.resp(i).bits.tdata := respData(i)
    io.resp(i).bits.tlast := true.B
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
