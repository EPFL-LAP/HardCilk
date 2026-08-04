package Atomics.Helpers
import chisel3._
import chisel3.util._
import Atomics.WriteIndexEntry

/* AvailableSlotTracker
 *
 * Tracks which of `tagStoreSize` slots are free as a bitmask (bit i set => slot i
 * is free). Each cycle it hands out up to `p` free slots on `selected_slots` and
 * reports how many it found on `outputCount`.
 *
 * Contract:
 *  - Every valid slot presented on `selected_slots` is an offer. It is marked
 *    taken the SAME cycle only when the corresponding `consumed` bit is set, so
 *    unconsumed offers remain available and may be offered again next cycle.
 *  - A consumed slot that is later not actually used must be handed back through
 *    `freed_entries` (together with slots freed by unlocks).
 *  - A `freed_entries` element with `valid` set returns its slot to the free pool.
 *
 * Bucketing (for cycle time + area): the slots are split into `p` equal,
 * contiguous buckets, and one free slot is priority-encoded per bucket in
 * parallel. Because each bucket's encoder scans all of its bits, a lone free slot
 * is always found (it lives in exactly one bucket). We deliberately do NOT
 * guarantee finding all `p` when more than `p` are free and they happen to
 * cluster into fewer than `p` buckets -- that is the accepted trade that keeps
 * every encoder tiny (tagStoreSize/p bits) and the design free of any wide
 * sequential reduction.
 */
class AvailableSlotTracker(
    val p: Int = 32,
    val tagStoreSize: Int = 128
) extends Module {
  val entrySize = log2Ceil(tagStoreSize)
  val io = IO(new Bundle {
    // Should contain 2*p entries
    val freed_entries = Input(Vec(2 * p, new WriteIndexEntry(tagStoreSize)))
    val consumed = Input(Vec(p, Bool()))
    val selected_slots = Output(Vec(p, new WriteIndexEntry(tagStoreSize)))
    val outputCount = Output(UInt((entrySize + 1).W))
  })

  require(p >= 1)
  require(
    tagStoreSize % p == 0,
    s"tagStoreSize ($tagStoreSize) must be divisible by p ($p) for even bucketing"
  )

  val bucketSize = tagStoreSize / p

  // Free-list: bit i set => slot i is free. Every slot starts free.
  val available = RegInit(((BigInt(1) << tagStoreSize) - 1).U(tagStoreSize.W))

  val localSel = Wire(Vec(p, UInt(bucketSize.W)))
  for (b <- 0 until p) {
    val base = b * bucketSize
    val slice = available(base + bucketSize - 1, base)
    val hasFree = slice.orR
    val localOneHot = PriorityEncoderOH(slice)

    localSel(b) := Mux(hasFree, localOneHot, 0.U(bucketSize.W))
    io.selected_slots(b).valid := hasFree
    io.selected_slots(b).index := base.U(entrySize.W) + OHToUInt(localOneHot)
  }

  io.outputCount := PopCount(io.selected_slots.map(_.valid))

  val selectedMask = Cat(
    (0 until p).reverse.map(b =>
      Mux(io.consumed(b), localSel(b), 0.U(bucketSize.W))
    )
  )

  // OR every valid freed slot back into the free pool
  val freedMask = io.freed_entries
    .map(e =>
      Mux(
        e.valid,
        UIntToOH(e.index(entrySize - 1, 0), tagStoreSize),
        0.U(tagStoreSize.W)
      )
    )
    .reduce(_ | _)

  // Take what was accepted this cycle; restore what was freed.
  available := (available & ~selectedMask) | freedMask
}
