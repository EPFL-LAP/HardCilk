package Atomics.tests

import chisel3._
import chiseltest._
import Atomics.Helpers.AvailableSlotTracker
import org.scalatest.flatspec.AnyFlatSpec
import scala.Predef.{assert => scalaAssert, _}
import scala.collection.mutable

class AvailableSlotTrackerTests
    extends AnyFlatSpec
    with ChiselScalatestTester
    with org.scalatest.ParallelTestExecution {
  behavior of "AvailableSlotTracker"

  private case class Params(p: Int, tagStoreSize: Int) {
    require(tagStoreSize % p == 0)
    val bucketSize: Int = tagStoreSize / p
  }

  private val params = Seq(
    Params(p = 1, tagStoreSize = 8), // single bucket spanning the store
    Params(p = 2, tagStoreSize = 8), // bucketSize 4
    Params(p = 2, tagStoreSize = 32), // wide buckets (16)
    Params(p = 4, tagStoreSize = 16), // bucketSize 4
    Params(p = 4, tagStoreSize = 32), // bucketSize 8
    Params(p = 8, tagStoreSize = 32), // bucketSize 4
    Params(p = 8, tagStoreSize = 8), // bucketSize 1 (pure free list)
    Params(p = 8, tagStoreSize = 64) // bucketSize 8
  )

  // Reference model: the lowest free slot in each bucket, mirroring the DUT's
  // per-bucket PriorityEncoderOH (None when the bucket is fully taken).
  private def expectedSelected(
      avail: Array[Boolean],
      p: Int,
      bucketSize: Int
  ): Array[Option[Int]] =
    Array.tabulate(p)(b => (b * bucketSize until (b + 1) * bucketSize).find(avail))

  private def pokeFreed(dut: AvailableSlotTracker, freed: Seq[Int]): Unit = {
    for (k <- 0 until 2 * dut.p) {
      if (k < freed.size) {
        dut.io.freed_entries(k).valid.poke(true.B)
        dut.io.freed_entries(k).index.poke(freed(k).U)
      } else {
        dut.io.freed_entries(k).valid.poke(false.B)
        dut.io.freed_entries(k).index.poke(0.U)
      }
    }
  }

  private def pokeConsumed(dut: AvailableSlotTracker, consumed: Seq[Boolean]): Unit = {
    for (b <- 0 until dut.p)
      dut.io.consumed(b).poke(consumed.lift(b).getOrElse(false).B)
  }

  private def consumeAll(dut: AvailableSlotTracker): Unit =
    pokeConsumed(dut, Seq.fill(dut.p)(true))

  private def consumeNone(dut: AvailableSlotTracker): Unit =
    pokeConsumed(dut, Seq.fill(dut.p)(false))

  private def checkSelected(
      dut: AvailableSlotTracker,
      params: Params,
      avail: Array[Boolean]
  ): Unit = {
    val exp = expectedSelected(avail, params.p, params.bucketSize)
    for (b <- 0 until params.p) {
      val v = dut.io.selected_slots(b).valid.peek().litToBoolean
      exp(b) match {
        case Some(idx) =>
          scalaAssert(v, s"bucket $b should be valid (expected index $idx)")
          val got = dut.io.selected_slots(b).index.peek().litValue.toInt
          scalaAssert(got == idx, s"bucket $b expected index $idx, got $got")
        case None =>
          scalaAssert(!v, s"bucket $b should be invalid (bucket is full)")
      }
    }
    val expCount = exp.count(_.isDefined)
    val gotCount = dut.io.outputCount.peek().litValue.toInt
    scalaAssert(gotCount == expCount, s"outputCount expected $expCount got $gotCount")
  }

  // From reset the whole store is free, so each bucket offers its base index.
  private def runFreshAllFree(dut: AvailableSlotTracker, params: Params): Unit = {
    pokeFreed(dut, Seq.empty)
    consumeNone(dut)
    scalaAssert(
      dut.io.outputCount.peek().litValue.toInt == params.p,
      s"a fresh tracker should offer p=${params.p} slots"
    )
    for (b <- 0 until params.p) {
      scalaAssert(
        dut.io.selected_slots(b).valid.peek().litToBoolean,
        s"bucket $b should be valid at reset"
      )
      val got = dut.io.selected_slots(b).index.peek().litValue.toInt
      scalaAssert(
        got == b * params.bucketSize,
        s"bucket $b should offer its base index ${b * params.bucketSize}, got $got"
      )
    }
  }

  // Consume the offered slots every cycle (no frees) and confirm we get every
  // slot exactly once, then the store reports exhausted.
  private def runDrainExhaust(dut: AvailableSlotTracker, params: Params): Unit = {
    pokeFreed(dut, Seq.empty)
    consumeAll(dut)
    val seen = mutable.Set.empty[Int]
    for (_ <- 0 until params.bucketSize + 3) {
      for (b <- 0 until params.p) {
        if (dut.io.selected_slots(b).valid.peek().litToBoolean) {
          val idx = dut.io.selected_slots(b).index.peek().litValue.toInt
          scalaAssert(idx >= 0 && idx < params.tagStoreSize, s"index $idx out of range")
          scalaAssert(!seen.contains(idx), s"index $idx handed out twice")
          seen += idx
        }
      }
      dut.clock.step()
    }
    consumeNone(dut)
    scalaAssert(
      seen.size == params.tagStoreSize,
      s"expected to hand out all ${params.tagStoreSize} slots, got ${seen.size}"
    )
    scalaAssert(
      dut.io.outputCount.peek().litValue.toInt == 0,
      "store should be exhausted after draining"
    )
    for (b <- 0 until params.p)
      scalaAssert(
        !dut.io.selected_slots(b).valid.peek().litToBoolean,
        s"bucket $b should be invalid once exhausted"
      )
  }

  // The "if there is 1 available, always find it" guarantee, probed at bucket
  // boundaries and the ends of the store.
  private def runSingleAvailableFound(
      dut: AvailableSlotTracker,
      params: Params
  ): Unit = {
    pokeFreed(dut, Seq.empty)
    consumeAll(dut)
    dut.clock.step(params.bucketSize) // drain the whole store
    consumeNone(dut)
    scalaAssert(
      dut.io.outputCount.peek().litValue.toInt == 0,
      "store should be exhausted before probing"
    )

    val probes = Seq(
      0,
      1,
      params.bucketSize - 1,
      params.bucketSize,
      params.tagStoreSize / 2,
      params.tagStoreSize - 1
    ).distinct.filter(i => i >= 0 && i < params.tagStoreSize)

    for (idx <- probes) {
      pokeFreed(dut, Seq(idx))
      dut.clock.step() // idx becomes available next cycle
      pokeFreed(dut, Seq.empty)
      consumeNone(dut)

      scalaAssert(
        dut.io.outputCount.peek().litValue.toInt == 1,
        s"freeing the single index $idx should yield outputCount 1"
      )
      val b = idx / params.bucketSize
      scalaAssert(
        dut.io.selected_slots(b).valid.peek().litToBoolean,
        s"bucket $b should present the freed index $idx"
      )
      scalaAssert(
        dut.io.selected_slots(b).index.peek().litValue.toInt == idx,
        s"expected freed index $idx in bucket $b"
      )

      consumeAll(dut)
      dut.clock.step() // consume it -> exhausted again
      consumeNone(dut)
      scalaAssert(
        dut.io.outputCount.peek().litValue.toInt == 0,
        s"after consuming $idx the store should be exhausted again"
      )
    }
  }

  // Bucketing trade-off: many free slots clustered in one bucket still drain only
  // one-per-cycle. Valid bits stay correct (exactly the one bucket).
  private def runClusteredBucketLimit(
      dut: AvailableSlotTracker,
      params: Params
  ): Unit = {
    pokeFreed(dut, Seq.empty)
    consumeAll(dut)
    dut.clock.step(params.bucketSize)
    consumeNone(dut)
    scalaAssert(dut.io.outputCount.peek().litValue.toInt == 0, "should start exhausted")

    val m = math.min(params.bucketSize, 2 * params.p) // freed has only 2p ports
    pokeFreed(dut, (0 until m)) // all within bucket 0 since m <= bucketSize
    dut.clock.step()
    pokeFreed(dut, Seq.empty)
    consumeNone(dut)

    for (j <- 0 until m) {
      scalaAssert(
        dut.io.outputCount.peek().litValue.toInt == 1,
        s"only bucket 0 has free slots, expected outputCount 1 (iter $j)"
      )
      scalaAssert(
        dut.io.selected_slots(0).valid.peek().litToBoolean,
        "bucket 0 should be the sole valid bucket"
      )
      scalaAssert(
        dut.io.selected_slots(0).index.peek().litValue.toInt == j,
        s"bucket 0 should drain in order, expected index $j"
      )
      for (b <- 1 until params.p)
        scalaAssert(
          !dut.io.selected_slots(b).valid.peek().litToBoolean,
          s"bucket $b should be empty during the clustered drain"
        )
      consumeAll(dut)
      dut.clock.step()
      consumeNone(dut)
    }
    scalaAssert(dut.io.outputCount.peek().litValue.toInt == 0, "bucket 0 should be drained")
  }

  // Random legal traffic (only ever free slots that are currently taken) checked
  // against the cycle-accurate reference model on every cycle.
  private def runModelRandom(
      dut: AvailableSlotTracker,
      params: Params,
      cycles: Int,
      seed: Int
  ): Unit = {
    val rng = new scala.util.Random(seed)
    val avail = Array.fill(params.tagStoreSize)(true)
    pokeFreed(dut, Seq.empty)
    consumeAll(dut)

    for (_ <- 0 until cycles) {
      checkSelected(dut, params, avail)
      val exp = expectedSelected(avail, params.p, params.bucketSize)

      val taken = (0 until params.tagStoreSize).filter(i => !avail(i))
      val maxFree = math.min(2 * params.p, taken.size)
      val k = if (maxFree == 0) 0 else rng.nextInt(maxFree + 1)
      val toFree = rng.shuffle(taken.toList).take(k)
      pokeFreed(dut, toFree)

      // Advance the model exactly as the DUT will at this edge:
      // consume the offered slots, then restore the freed ones.
      for (b <- 0 until params.p) exp(b).foreach(i => avail(i) = false)
      for (i <- toFree) avail(i) = true

      dut.clock.step()
    }
  }

  for (pr <- params) {
    val tag = s"p=${pr.p} tagStoreSize=${pr.tagStoreSize}"

    it should s"offer one free slot per bucket from reset ($tag)" in {
      test(new AvailableSlotTracker(p = pr.p, tagStoreSize = pr.tagStoreSize)) { dut =>
        runFreshAllFree(dut, pr)
      }
    }

    it should s"hand out every slot exactly once until exhausted ($tag)" in {
      test(new AvailableSlotTracker(p = pr.p, tagStoreSize = pr.tagStoreSize)) { dut =>
        runDrainExhaust(dut, pr)
      }
    }

    it should s"always find a lone freed slot at any position ($tag)" in {
      test(new AvailableSlotTracker(p = pr.p, tagStoreSize = pr.tagStoreSize)) { dut =>
        runSingleAvailableFound(dut, pr)
      }
    }

    it should s"match the reference model under random frees ($tag)" in {
      test(new AvailableSlotTracker(p = pr.p, tagStoreSize = pr.tagStoreSize)) { dut =>
        runModelRandom(dut, pr, cycles = 300, seed = 1234 + pr.tagStoreSize * 7 + pr.p)
      }
    }

    if (pr.p >= 2 && pr.bucketSize >= 2) {
      it should s"only drain one-per-cycle when free slots cluster in one bucket ($tag)" in {
        test(new AvailableSlotTracker(p = pr.p, tagStoreSize = pr.tagStoreSize)) { dut =>
          runClusteredBucketLimit(dut, pr)
        }
      }
    }
  }
}
