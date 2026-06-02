package Atomics.tests

import chisel3._
import chiseltest._
import Atomics.Helpers.InputArbiter
import Atomics.{AtomicMode, Operation}
import org.scalatest.ParallelTestExecution
import org.scalatest.flatspec.AnyFlatSpec
import scala.Predef.{assert => scalaAssert, _}
import scala.collection.mutable

class InputArbiterTests
    extends AnyFlatSpec
    with ChiselScalatestTester
    with ParallelTestExecution {
  behavior of "InputArbiter"

  private case class Params(n: Int, p: Int) {
    require(n % (2 * p) == 0)
    val bucketCount: Int = 2 * p
    val bucketSize: Int = n / bucketCount
  }

  private val params = Seq(
    Params(4, 2),
    Params(8, 2),
    Params(8, 4),
    Params(16, 4),
    Params(32, 4),
    Params(16, 8),
    Params(32, 8)
  )

  // reqs maps a port index -> (isLock, tag). requestingPE is always poked to the
  // port index so a selected output names its own source for cross-checking.
  private def driveAll(dut: InputArbiter, reqs: Map[Int, (Boolean, Int)]): Unit = {
    for (i <- 0 until dut.n) {
      val r = reqs.get(i)
      dut.io.requests(i).valid.poke(r.isDefined.B)
      dut.io.requests(i).bits.isValid.poke(r.isDefined.B)
      dut.io.requests(i).bits.operation
        .poke(if (r.map(_._1).getOrElse(false)) Operation.Lock else Operation.Unlock)
      dut.io.requests(i).bits.tag.poke(r.map(_._2).getOrElse(i + 1).U)
      dut.io.requests(i).bits.data.poke(0.U)
      dut.io.requests(i).bits.isBlocking.poke(false.B)
      dut.io.requests(i).bits.requestingPE.poke(i.U)
      dut.io.requests(i).bits.atomicMode.poke(AtomicMode.DoubleWord)
    }
  }

  private def driveSlots(dut: InputArbiter, valid: Boolean): Unit = {
    for (lane <- 0 until dut.p) {
      dut.io.availableSlots(lane).valid.poke(valid.B)
      dut.io.availableSlots(lane).index.poke(lane.U)
    }
  }

  private case class Sel(
      lane: Int,
      pe: Int,
      isLock: Boolean,
      tag: Int,
      atomicMode: BigInt
  )

  private def peekSelected(dut: InputArbiter): Seq[Sel] = {
    val out = mutable.ArrayBuffer.empty[Sel]
    for (lane <- 0 until dut.p) {
      if (dut.io.selectedRequests(lane).isValid.peek().litToBoolean) {
        out += Sel(
          lane,
          dut.io.selectedRequests(lane).requestingPE.peek().litValue.toInt,
          dut.io.selectedRequests(lane).operation.peek().litValue == Operation.Lock.litValue,
          dut.io.selectedRequests(lane).tag.peek().litValue.toInt,
          dut.io.selectedRequests(lane).atomicMode.peek().litValue
        )
      }
    }
    out.toSeq
  }

  private def bucketOf(idx: Int, bucketSize: Int): Int = idx / bucketSize

  // Steady random inputs: every selection must name a real, valid input with the
  // exact bits we drove, and within one cycle selections come from distinct
  // buckets (hence distinct ports).
  private def runValidityAndDistinct(
      dut: InputArbiter,
      params: Params,
      seed: Int
  ): Unit = {
    val rng = new scala.util.Random(seed)
    val driven = (0 until params.n).flatMap { i =>
      if (rng.nextBoolean()) Some(i -> (rng.nextBoolean(), 1000 + i)) else None
    }.toMap
    driveAll(dut, driven)
    driveSlots(dut, valid = true)

    for (_ <- 0 until 40) {
      val sels = peekSelected(dut)
      val pes = sels.map(_.pe)
      scalaAssert(pes.distinct.size == pes.size, s"duplicate port selected this cycle: $sels")
      val buckets = sels.map(s => bucketOf(s.pe, params.bucketSize))
      scalaAssert(buckets.distinct.size == buckets.size, s"two lanes from one bucket: $sels")
      for (s <- sels) {
        scalaAssert(driven.contains(s.pe), s"selected port ${s.pe} was not a valid input: $sels")
        val (il, tg) = driven(s.pe)
        scalaAssert(s.isLock == il, s"isLock mismatch on port ${s.pe}: got ${s.isLock} want $il")
        scalaAssert(s.tag == tg, s"tag mismatch on port ${s.pe}: got ${s.tag} want $tg")
      }
      dut.clock.step()
    }
  }

  // Each bucket holds a lock (local 0) and an unlock (local 1); the unlock must
  // always win, and every bucket's unlock must eventually be selected.
  private def runUnlockPriority(dut: InputArbiter, params: Params): Unit = {
    val driven = mutable.Map.empty[Int, (Boolean, Int)]
    for (b <- 0 until params.bucketCount) {
      val base = b * params.bucketSize
      driven(base + 0) = (true, 2000 + base) // lock
      driven(base + 1) = (false, 3000 + base) // unlock
    }
    driveAll(dut, driven.toMap)
    driveSlots(dut, valid = true)

    val seenUnlocks = mutable.Set.empty[Int]
    for (_ <- 0 until 4 * params.bucketCount + 8) {
      for (s <- peekSelected(dut)) {
        scalaAssert(!s.isLock, s"a lock was selected while its bucket held an unlock: $s")
        seenUnlocks += s.pe
      }
      dut.clock.step()
    }
    val expected = (0 until params.bucketCount).map(b => b * params.bucketSize + 1).toSet
    scalaAssert(
      seenUnlocks == expected,
      s"expected to select every bucket unlock $expected, saw $seenUnlocks"
    )
  }

  // One candidate per bucket: the rotation must emit every bucket, so each
  // candidate is selected at least once over a couple of windows.
  private def runCoverageOnePerBucket(dut: InputArbiter, params: Params): Unit = {
    val driven =
      (0 until params.bucketCount).map(b => (b * params.bucketSize) -> (true, 5000 + b)).toMap
    driveAll(dut, driven)
    driveSlots(dut, valid = true)

    val seen = mutable.Set.empty[Int]
    for (_ <- 0 until 4 * params.bucketCount + 12) {
      for (s <- peekSelected(dut)) {
        scalaAssert(driven.contains(s.pe), s"phantom selection of port ${s.pe}")
        seen += s.pe
      }
      dut.clock.step()
    }
    scalaAssert(
      seen == driven.keySet,
      s"expected every bucket candidate ${driven.keySet} to be selected, saw $seen"
    )
  }

  private def runEmpty(dut: InputArbiter, params: Params): Unit = {
    driveAll(dut, Map.empty)
    driveSlots(dut, valid = true)
    for (_ <- 0 until 12) {
      scalaAssert(peekSelected(dut).isEmpty, "no valid inputs should mean no selections")
      for (i <- 0 until dut.n)
        scalaAssert(
          !dut.io.sameCycleSelectedMask(i).peek().litToBoolean,
          s"mask($i) should be low with no inputs"
        )
      dut.clock.step()
    }
  }

  // A request must be both .valid and .bits.isValid to be eligible; neither alone
  // should ever be selected.
  private def runGating(dut: InputArbiter, params: Params): Unit = {
    // valid=true, isValid=false everywhere
    for (i <- 0 until dut.n) {
      dut.io.requests(i).valid.poke(true.B)
      dut.io.requests(i).bits.isValid.poke(false.B)
      dut.io.requests(i).bits.operation.poke(Operation.Lock)
      dut.io.requests(i).bits.tag.poke((i + 1).U)
      dut.io.requests(i).bits.requestingPE.poke(i.U)
    }
    driveSlots(dut, valid = true)
    for (_ <- 0 until 8) {
      scalaAssert(peekSelected(dut).isEmpty, "isValid=false must never be selected")
      dut.clock.step()
    }
    // valid=false, isValid=true everywhere
    for (i <- 0 until dut.n) {
      dut.io.requests(i).valid.poke(false.B)
      dut.io.requests(i).bits.isValid.poke(true.B)
    }
    for (_ <- 0 until 8) {
      scalaAssert(peekSelected(dut).isEmpty, "valid=false must never be selected")
      dut.clock.step()
    }
  }

  // sameCycleSelectedMask: only hot on the (every-other) select cycle, never two
  // cycles in a row, at most one bit per bucket, only on valid inputs, and obeys
  // unlock priority within a bucket.
  private def runMaskInvariants(dut: InputArbiter, params: Params, seed: Int): Unit = {
    val rng = new scala.util.Random(seed)
    val driven = (0 until params.n).flatMap { i =>
      if (rng.nextInt(3) != 0) Some(i -> (rng.nextBoolean(), 7000 + i)) else None
    }.toMap
    driveAll(dut, driven)
    driveSlots(dut, valid = true)

    var prevHot = false
    var anyHot = false
    for (_ <- 0 until 30) {
      val masked = (0 until dut.n).filter(i => dut.io.sameCycleSelectedMask(i).peek().litToBoolean)
      val hot = masked.nonEmpty
      scalaAssert(!(prevHot && hot), s"mask hot two cycles in a row: $masked")
      if (hot) {
        anyHot = true
        val buckets = masked.map(i => bucketOf(i, params.bucketSize))
        scalaAssert(buckets.distinct.size == buckets.size, s"mask has >1 per bucket: $masked")
        scalaAssert(masked.size <= params.bucketCount, s"mask wider than bucketCount: $masked")
        for (i <- masked) {
          scalaAssert(driven.contains(i), s"mask hot on a non-valid input $i")
          val b = bucketOf(i, params.bucketSize)
          val bucketHasUnlock = (b * params.bucketSize until (b + 1) * params.bucketSize)
            .exists(j => driven.get(j).exists(!_._1))
          if (bucketHasUnlock)
            scalaAssert(!driven(i)._1, s"bucket $b held an unlock but masked a lock ($i)")
        }
      }
      prevHot = hot
      dut.clock.step()
    }
    if (driven.nonEmpty)
      scalaAssert(anyHot, "with valid inputs the mask should be hot on select cycles")
  }

  it should "preserve atomicMode from input queue to selected request" in {
    val pr = Params(8, 4)
    test(new InputArbiter(pr.n, pr.p)) { dut =>
      driveAll(dut, Map(0 -> (true, 123)))
      dut.io.requests(0).bits.atomicMode.poke(AtomicMode.Byte)
      driveSlots(dut, valid = true)

      var seen = false
      for (_ <- 0 until 12) {
        for (s <- peekSelected(dut)) {
          if (s.pe == 0) {
            scalaAssert(
              s.atomicMode == AtomicMode.Byte.litValue,
              s"selected request lost atomic mode: $s"
            )
            seen = true
          }
        }
        dut.clock.step()
      }
      scalaAssert(seen, "test request was never selected")
    }
  }

  for (pr <- params) {
    val tag = s"n=${pr.n} p=${pr.p}"

    it should s"select only real inputs, from distinct buckets, with matching bits ($tag)" in {
      test(new InputArbiter(pr.n, pr.p)) { dut =>
        runValidityAndDistinct(dut, pr, seed = 7 + pr.n * 31 + pr.p)
      }
    }

    it should s"eventually select every bucket's candidate ($tag)" in {
      test(new InputArbiter(pr.n, pr.p)) { dut => runCoverageOnePerBucket(dut, pr) }
    }

    it should s"select nothing when no input is valid ($tag)" in {
      test(new InputArbiter(pr.n, pr.p)) { dut => runEmpty(dut, pr) }
    }

    it should s"require both valid and isValid to select a request ($tag)" in {
      test(new InputArbiter(pr.n, pr.p)) { dut => runGating(dut, pr) }
    }

    it should s"drive a well-formed sameCycleSelectedMask ($tag)" in {
      test(new InputArbiter(pr.n, pr.p)) { dut =>
        runMaskInvariants(dut, pr, seed = 99 + pr.n + pr.p * 13)
      }
    }

    if (pr.bucketSize >= 2) {
      it should s"prioritise unlocks over locks within a bucket ($tag)" in {
        test(new InputArbiter(pr.n, pr.p)) { dut => runUnlockPriority(dut, pr) }
      }
    }
  }
}
