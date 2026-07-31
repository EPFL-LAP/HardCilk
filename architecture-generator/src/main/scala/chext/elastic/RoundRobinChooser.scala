package chext.elastic

import chisel3._
import chisel3.util._

/** A round-robin `Chooser` that is fair for any number of contenders.
  *
  * It grants the first contender after the last granted one, wrapping around to the first one when
  * there is none, so a contender waits at most `n - 1` grants. `lastChoice` starts at `n - 1` so
  * that the very first grant goes to contender 0.
  *
  * This exists because [[RRChooser]] is only fair when `n` is a power of two. It wraps on
  * `lastChoice === (2^^log2Up(n)) - 1`, which for a non power of two `n` is an index that can never
  * be granted, so the rotation stops at `n - 1` and that contender keeps the grant forever. Use
  * this chooser instead whenever the contender count is not known to be a power of two.
  *
  * @param v
  *   Contention vector (one bit per contender), as required by [[Chooser]].
  */
class RoundRobinChooser(v: Vec[Bool]) extends Chooser(v) {
  require(v.length >= 1, "there must be at least one contender")

  private val lastChoice = RegInit((v.length - 1).U(wChoice.W))

  private val after = VecInit(v.zipWithIndex.map { case (x, i) => x && i.U > lastChoice })
  private val anyAfter = after.reduceTree(_ || _)

  override def choice: UInt =
    if (v.length == 1) zeroChoice
    else Mux(anyAfter, PriorityEncoder(after), PriorityEncoder(v))

  override def updateState: Unit = { lastChoice := choice }
}

object RoundRobinChooser {

  /** Builds a fair round-robin [[Chooser.ChooserFn]]. */
  def apply(): Chooser.ChooserFn = (v: Vec[Bool]) => new RoundRobinChooser(v)
}
