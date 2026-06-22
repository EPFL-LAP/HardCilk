package Atomics.Helpers
import chisel3._
import Atomics.{AtomicMode, RequestType, WriteIndexEntry}
import chisel3.util._
import Atomics.Operation
/* Module InputArbiter

// Takes n inputs (queues where we view the front element but don't pop) and selects p locks and p unlocks (or less depending on how many there are)
// Then fills in p outputs, giving unlocks priority. Locks to left, unlocks to right (this part should be a simple mux)
// Each cycle shoulddo the following:
    Take the input and double it so we have 2n wide
    Calculate a mask n wide that shifts to the right 1 each cycle
    AND the mask on the input validity
    Pick the first p of each type (and return a list of viewed ns to output so we can mark them)
    Return list of picked p contents

 */

class InputArbiter(
    val n: Int = 128,
    val p: Int = 4,
    val tagStoreSize: Int = 128,
    val addrW: Int = 64,
    val singleSelect: Boolean = false
) extends Module {
  require(addrW > 0, "addrW must be positive")

  val io = IO(new Bundle {
    val requests = Vec(n, Flipped(Decoupled(new RequestType(n, addrW))))
    val sameCycleSelectedMask = Output(Vec(n, Bool()))

    val availableSlots = Input(Vec(p, new WriteIndexEntry(tagStoreSize)))
    val laneBlocked = Input(Vec(p, Bool()))
    val consumedSlots = Output(Vec(p, Bool()))
    val selectedWriteIndices = Output(Vec(p, new WriteIndexEntry(tagStoreSize)))
    val selectedRequests = Output(Vec(p, new RequestType(n, addrW)))
  })

  // This arbiter only peeks at queue heads; popping is driven by LockServer. Tie off
  // the (unused) ready outputs so the Decoupled interface is fully driven.
  io.requests.foreach(_.ready := false.B)

  val bucketCount = if (singleSelect) p else 2 * p
  val bucketSize = n / bucketCount
  val bucketOffsetWidth = math.max(1, log2Ceil(bucketSize))

  require(
    n % bucketCount == 0,
    s"n ($n) must be divisible by ${if (singleSelect) "p" else "2*p"} ($bucketCount)"
  )

  val selectCycleReg = RegInit(true.B)
  selectCycleReg := !selectCycleReg
  val acceptsSelectionThisCycle = if (singleSelect) true.B else selectCycleReg

  val bucketStart = RegInit(
    VecInit(Seq.fill(bucketCount)(0.U(bucketOffsetWidth.W)))
  )

  def selectFromBucket(bucket: Int, wantLock: Boolean): UInt = {
    val bucketBase = bucket * bucketSize
    val candidates = VecInit((0 until bucketSize).map { localIndex =>
      val request = io.requests(bucketBase + localIndex)
      request.valid && request.bits.isValid &&
      ((request.bits.operation.isLock) === wantLock.B)
    })

    val candidateBits = candidates.asUInt
    val lowerMask =
      ((1.U(bucketSize.W) << bucketStart(bucket)) - 1.U)(bucketSize - 1, 0)
    val upperHits = candidateBits & ~lowerMask
    val lowerHits = candidateBits & lowerMask
    val useUpper = upperHits.orR
    val selected = Mux(
      useUpper,
      PriorityEncoderOH(upperHits),
      PriorityEncoderOH(lowerHits)
    )

    Mux(candidateBits.orR, selected, 0.U(bucketSize.W))
  }

  def selectOnePerBucket(wantLock: Boolean): UInt = {
    val selected = Wire(Vec(bucketCount, UInt(bucketSize.W)))
    for (bucket <- 0 until bucketCount) {
      selected(bucket) := selectFromBucket(bucket, wantLock)
    }
    selected.asUInt
  }

  val selected_top_2p_locks = selectOnePerBucket(wantLock = true)
  val selected_top_2p_unlocks = selectOnePerBucket(wantLock = false)

  val selected_top_2p_lock_buckets =
    selected_top_2p_locks.asTypeOf(Vec(bucketCount, UInt(bucketSize.W)))
  val selected_top_2p_unlock_buckets =
    selected_top_2p_unlocks.asTypeOf(Vec(bucketCount, UInt(bucketSize.W)))

  val selected_top_2p_unlock_bucket_is_hot =
    Wire(Vec(bucketCount, Bool()))
  val selected_top_2p_buckets =
    Wire(Vec(bucketCount, UInt(bucketSize.W)))

  for (bucket <- 0 until bucketCount) {
    selected_top_2p_unlock_bucket_is_hot(bucket) :=
      selected_top_2p_unlock_buckets(bucket).orR

    selected_top_2p_buckets(bucket) := Mux(
      selected_top_2p_unlock_bucket_is_hot(bucket),
      selected_top_2p_unlock_buckets(bucket),
      selected_top_2p_lock_buckets(bucket)
    )
  }

  val selected_top_2p_reg =
    RegInit(VecInit(Seq.fill(bucketCount)(0.U(bucketSize.W))))
  val selected_top_2p_request_reg =
    RegInit(
      VecInit(Seq.fill(bucketCount)(0.U.asTypeOf(new RequestType(n, addrW))))
    )
  val heldForSlot = RegInit(VecInit(Seq.fill(bucketCount)(false.B)))
  val bucketWillHold = Wire(Vec(bucketCount, Bool()))
  for (bucket <- 0 until bucketCount) {
    bucketWillHold(bucket) := heldForSlot(bucket)
  }

  for (i <- 0 until n) {
    io.sameCycleSelectedMask(i) := false.B
  }

  def selectedOHFromBucket(bucket: Int): UInt = {
    val selected = Wire(Vec(bucketCount, UInt(bucketSize.W)))
    for (i <- 0 until bucketCount) {
      selected(i) := (if (bucket == i) {
                        selected_top_2p_reg(i)
                      } else {
                        0.U(bucketSize.W)
                      })
    }
    selected.asUInt
  }

  def requestFromBucket(bucket: Int, selected: UInt): RequestType = {
    val bucketBase = bucket * bucketSize
    val requestHits = (0 until bucketSize).map(i => selected(i))
    val selectedHeads =
      (0 until bucketSize).map(i => io.requests(bucketBase + i).bits)
    val selectedRequest = Wire(new RequestType(n, addrW))

    selectedRequest.tag := Mux1H(requestHits, selectedHeads.map(_.tag))
    selectedRequest.data := Mux1H(requestHits, selectedHeads.map(_.data))
    selectedRequest.isBlocking := Mux1H(
      requestHits,
      selectedHeads.map(_.isBlocking)
    )
    selectedRequest.requestingPE := Mux1H(
      requestHits,
      selectedHeads.map(_.requestingPE)
    )
    selectedRequest.operation :=
      Operation.safe(Mux1H(requestHits, selectedHeads.map(_.operation.asUInt)))._1
    selectedRequest.atomicMode :=
      AtomicMode.safe(Mux1H(requestHits, selectedHeads.map(_.atomicMode.asUInt)))._1
    selectedRequest.floatCompare :=
      Mux1H(requestHits, selectedHeads.map(_.floatCompare))
    selectedRequest.meta := Mux1H(requestHits, selectedHeads.map(_.meta))
    // Only ever set on the AMU return path, which bypasses the arbiter.
    selectedRequest.writeOccurred := false.B
    selectedRequest.isValid := selected.orR
    selectedRequest
  }

  val selected_top_2p_requests =
    Wire(Vec(bucketCount, new RequestType(n, addrW)))
  for (bucket <- 0 until bucketCount) {
    selected_top_2p_requests(bucket) :=
      requestFromBucket(bucket, selected_top_2p_buckets(bucket))
  }

  val selected_top_p_1 = Wire(Vec(p, UInt(n.W)))
  val selected_top_p_2 = Wire(Vec(p, UInt(n.W)))
  val selected_top_p = Wire(Vec(p, UInt(n.W)))
  val laneStalled = Wire(Vec(p, Bool()))

  for (i <- 0 until p) {
    selected_top_p_1(i) := selectedOHFromBucket(i)
    selected_top_p_2(i) :=
      (if (singleSelect) 0.U(n.W) else selectedOHFromBucket(p + i))
    selected_top_p(i) :=
      (if (singleSelect) selected_top_p_1(i)
       else Mux(selectCycleReg, selected_top_p_2(i), selected_top_p_1(i)))

    val rawSelectedRequest = Wire(new RequestType(n, addrW))
    val selectedRequest1 = selected_top_2p_request_reg(i)
    val selectedRequest2 =
      if (singleSelect) 0.U.asTypeOf(new RequestType(n, addrW))
      else selected_top_2p_request_reg(p + i)
    rawSelectedRequest.tag :=
      (if (singleSelect) selectedRequest1.tag
       else Mux(selectCycleReg, selectedRequest2.tag, selectedRequest1.tag))
    rawSelectedRequest.data :=
      (if (singleSelect) selectedRequest1.data
       else Mux(selectCycleReg, selectedRequest2.data, selectedRequest1.data))
    rawSelectedRequest.isBlocking :=
      (if (singleSelect) selectedRequest1.isBlocking
       else Mux(selectCycleReg, selectedRequest2.isBlocking, selectedRequest1.isBlocking))
    rawSelectedRequest.requestingPE :=
      (if (singleSelect) selectedRequest1.requestingPE
       else Mux(selectCycleReg, selectedRequest2.requestingPE, selectedRequest1.requestingPE))
    rawSelectedRequest.operation :=
      (if (singleSelect) selectedRequest1.operation
       else
         Operation
           .safe(
             Mux(
               selectCycleReg,
               selectedRequest2.operation.asUInt,
               selectedRequest1.operation.asUInt
             )
           )
           ._1)
    rawSelectedRequest.atomicMode :=
      (if (singleSelect) selectedRequest1.atomicMode
       else
         AtomicMode
           .safe(
             Mux(
               selectCycleReg,
               selectedRequest2.atomicMode.asUInt,
               selectedRequest1.atomicMode.asUInt
             )
           )
           ._1)
    rawSelectedRequest.floatCompare :=
      (if (singleSelect) selectedRequest1.floatCompare
       else
         Mux(
           selectCycleReg,
           selectedRequest2.floatCompare,
           selectedRequest1.floatCompare
         ))
    rawSelectedRequest.meta :=
      (if (singleSelect) selectedRequest1.meta
       else Mux(selectCycleReg, selectedRequest2.meta, selectedRequest1.meta))
    // Only ever set on the AMU return path, which bypasses the arbiter.
    rawSelectedRequest.writeOccurred := false.B
    rawSelectedRequest.isValid := selected_top_p(i).orR

    val needsSlot =
      rawSelectedRequest.isValid && rawSelectedRequest.operation.isLock
    val hasSlot = io.availableSlots(i).valid
    laneStalled(i) := io.laneBlocked(i) || (needsSlot && !hasSlot)

    io.selectedRequests(i) := 0.U.asTypeOf(new RequestType(n, addrW))
    when(!laneStalled(i) && rawSelectedRequest.isValid) {
      io.selectedRequests(i) := rawSelectedRequest
      io.selectedRequests(i).isValid := true.B
    }

    io.selectedWriteIndices(i) := 0.U.asTypeOf(
      new WriteIndexEntry(tagStoreSize)
    )
    io.selectedWriteIndices(i).valid := !laneStalled(i) && needsSlot && hasSlot
    io.selectedWriteIndices(i).index := Mux(
      hasSlot,
      io.availableSlots(i).index,
      0.U
    )
    io.consumedSlots(i) := !laneStalled(i) && needsSlot && hasSlot

    if (singleSelect) {
      bucketWillHold(i) := laneStalled(i)
    } else {
      when(selectCycleReg) {
        bucketWillHold(p + i) := laneStalled(i)
      }.otherwise {
        bucketWillHold(i) := laneStalled(i)
      }
    }
  }

  for (bucket <- 0 until bucketCount) {
    val acceptsNewSelection =
      acceptsSelectionThisCycle && !bucketWillHold(bucket) && selected_top_2p_buckets(
        bucket
      ).orR

    when(acceptsSelectionThisCycle && !bucketWillHold(bucket)) {
      selected_top_2p_reg(bucket) := selected_top_2p_buckets(bucket)
      selected_top_2p_request_reg(bucket) := selected_top_2p_requests(bucket)
    }

    when(acceptsNewSelection) {
      when(bucketStart(bucket) === (bucketSize - 1).U) {
        bucketStart(bucket) := 0.U
      }.otherwise {
        bucketStart(bucket) := bucketStart(bucket) + 1.U
      }

      for (localIndex <- 0 until bucketSize) {
        io.sameCycleSelectedMask(bucket * bucketSize + localIndex) :=
          selected_top_2p_buckets(bucket)(localIndex)
      }
    }

    heldForSlot(bucket) := bucketWillHold(bucket)
  }

}
