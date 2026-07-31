package DataFlowScheduler

import chisel3._
import chisel3.util._

import chext.elastic
import chext.elastic.ConnectOp._

/** Configuration of a [[DataFlowNetwork]].
  *
  * @param nUnits
  *   Number of [[DataFlowTaskUnit]]s in the ring.
  * @param wData
  *   Payload width of every port of every unit.
  * @param inputBias
  *   Per unit arbitration bias, see [[DataFlowTaskUnitConfig]].
  * @param inputDepth
  *   Per unit input register depth, see [[DataFlowTaskUnitConfig]].
  * @param computeDepth
  *   Per unit compute pipeline depth, see [[DataFlowTaskUnitConfig]].
  * @param openRing
  *   Cut the ring between the last and the first unit and bring the two ends out as `m_spill` and
  *   `s_refill`, so something can be spliced into the spill path. Used to hand overflow to a
  *   spawner server; leave it closed otherwise.
  */
case class DataFlowNetworkConfig(
    nUnits: Int,
    wData: Int,
    inputBias: Int = 4,
    inputDepth: Int = 2,
    computeDepth: Int = 0,
    openRing: Boolean = false
) {
  require(nUnits >= 1, "a network needs at least one unit")

  /** Configuration handed to every unit of the network. */
  def unit = DataFlowTaskUnitConfig(
    wData = wData,
    inputBias = inputBias,
    inputDepth = inputDepth,
    computeDepth = computeDepth
  )
}

class DataFlowNetworkIO(cfg: DataFlowNetworkConfig) extends Bundle {
  import cfg._

  /** High probability input of each unit. */
  val s_primary = Vec(nUnits, Flipped(Decoupled(UInt(wData.W))))

  /** High probability output of each unit. */
  val m_primary = Vec(nUnits, Decoupled(UInt(wData.W)))

  /** Tokens leaving the ring at the cut, present only when `openRing`. */
  val m_spill = if (openRing) Some(Decoupled(UInt(wData.W))) else None

  /** Tokens re-entering the ring at the cut, present only when `openRing`. */
  val s_refill = if (openRing) Some(Flipped(Decoupled(UInt(wData.W)))) else None
}

/** A ring of [[DataFlowTaskUnit]]s.
  *
  * Every unit exposes its high probability input and output to the outside, as the `s_primary` and
  * `m_primary` vectors. The low probability ports are not exposed: they are chained into a ring,
  * unit `i` spilling into unit `i + 1` and the last unit closing the loop onto unit `0`.
  *
  * The ring is what gives the network its load balancing. A unit hands its tokens to its own
  * `m_primary` for as long as that output keeps up; once it backpressures, the unit's routing stage
  * spills into the ring instead of stalling, and the token is offered to the next unit, which tries
  * its own `m_primary` first, and so on. A token therefore travels around the ring only as far as
  * it must to find an output that can take it.
  *
  * A ring hop costs no combinational path: the sending unit drives `m_secondary` from a register
  * and the receiving unit registers `s_secondary` on arrival, so the hop is register-to-register
  * and the ring can be spread across the die without becoming the critical path.
  *
  * When every `m_primary` backpressures at once the tokens simply circulate and the ring fills up,
  * after which the units stop accepting on `s_primary`. That is ordinary backpressure, not a
  * deadlock: nothing is dropped, and the network drains again as soon as any output accepts.
  *
  * With `nUnits == 1` the single unit's `m_secondary` loops back onto its own `s_secondary`, which
  * is legal and simply lets a token retry the same output.
  *
  * @param cfg
  *   Network configuration.
  * @param computeFn
  *   Payload transform applied by every unit, `(in, out)`. Defaults to the identity.
  * @param outputChooserFn
  *   Per unit output policy. Defaults to a strict priority, i.e. a unit only spills into the ring
  *   when its own output cannot take the token.
  */
class DataFlowNetwork(
    val cfg: DataFlowNetworkConfig,
    val computeFn: (UInt, UInt) => Unit = (in: UInt, out: UInt) => { out := in },
    val outputChooserFn: elastic.Chooser.ChooserFn = elastic.Chooser.priority(_)
) extends Module {
  import cfg._

  override def desiredName: String = "dataFlowNetwork"

  val io = IO(new DataFlowNetworkIO(cfg))

  private val units = Seq.tabulate(nUnits) { i =>
    val unit = Module(new DataFlowTaskUnit(cfg.unit, computeFn, outputChooserFn))
    unit.suggestName(s"unit_$i")
    unit
  }

  // The high probability ports are the network's ports.
  io.s_primary.zip(units).foreach { case (source, unit) => source :=> unit.io.s_primary }
  units.zip(io.m_primary).foreach { case (unit, sink) => unit.io.m_primary :=> sink }

  // The low probability ports form the ring. With `openRing` the wrap hop is left to the caller,
  // which splices the spill path through a spawner server before closing the loop.
  units.zipWithIndex.foreach { case (unit, i) =>
    val isWrapHop = i == nUnits - 1

    if (isWrapHop && openRing) {
      unit.io.m_secondary :=> io.m_spill.get
      io.s_refill.get :=> units(0).io.s_secondary
    } else {
      unit.io.m_secondary :=> units((i + 1) % nUnits).io.s_secondary
    }
  }
}
