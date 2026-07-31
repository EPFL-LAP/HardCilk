package chext.elastic

import chisel3._
import chisel3.util._

/** A `Chooser` that is biased towards one of the contenders.
  *
  * It is meant for arbitration/distribution points where one of the streams is known to be much
  * more likely than the others: the preferred stream is granted back-to-back (so it never pays an
  * arbitration penalty in the common case), while a credit counter bounds the starvation of the
  * remaining streams.
  *
  * The preferred contender wins as long as it has credits left. Every grant given to the preferred
  * contender *while another one is waiting* burns one credit; any other grant (or a cycle without
  * contention) refills them. Hence `patience` is, in the worst case, the number of consecutive
  * grants the preferred stream may take before a waiting stream is served, and should be picked to
  * match the expected arrival ratio between the streams.
  *
  * @param v
  *   Contention vector (one bit per contender), as required by [[Chooser]].
  * @param preferred
  *   Index of the biased (high probability) contender.
  * @param patience
  *   Number of back-to-back grants the preferred contender may take under contention.
  */
class BiasedChooser(v: Vec[Bool], val preferred: Int, val patience: Int) extends Chooser(v) {
  require(preferred >= 0 && preferred < v.length, "preferred must be a valid contender index")
  require(patience >= 1, "patience must be at least 1, otherwise the bias is meaningless")

  private val credits = RegInit(patience.U(log2Up(patience + 1).W))

  private val others = VecInit(v.zipWithIndex.map { case (x, i) =>
    if (i == preferred) false.B else x
  })

  private val othersContend = others.reduceTree(_ || _)
  private val otherChoice = PriorityEncoder(others)

  // Fall back to the others only when the bias is exhausted, and never hand the choice over to a
  // contender that is not there (that would stall the whole arbitration point).
  private val takePreferred = v(preferred) && (credits =/= 0.U || !othersContend)

  override def choice: UInt = Mux(takePreferred, preferred.U(wChoice.W), otherChoice)

  override def updateState: Unit = {
    when(takePreferred && othersContend) {
      credits := credits - 1.U
    }.otherwise {
      credits := patience.U
    }
  }
}

object BiasedChooser {

  /** Builds a [[Chooser.ChooserFn]] biased towards `preferred`. */
  def apply(preferred: Int, patience: Int): Chooser.ChooserFn =
    (v: Vec[Bool]) => new BiasedChooser(v, preferred, patience)
}
