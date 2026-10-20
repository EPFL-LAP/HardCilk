package Util

import chisel3._
import chisel3.util._

import chext.elastic
import chext.elastic.ConnectOp._

/**
  * HC_SCHED_SLR_LOCAL (see [[NocPePolicy.schedSlrLocal]]): building blocks of the
  * SLR-local (hierarchical) task scheduler and argument notifier.
  *
  * Every die-local block is a module whose INSTANCE name is `hcslr_<what>_<role>`,
  * `<role>` being one of the skid roles of [[NocPePolicy.skidRoles]] (`slr0`,
  * `slr1p`, `slr1h`, `slr1`, `slr2`). The emitter puts `keep_hierarchy` on
  * every such instance and the V80 floorplan pins it to the die its role names
  * ([[NocPePolicy.slrOfRole]]), wherever it sits in the hierarchy.
  *
  * Each of these blocks re-times the reset it receives through two local flops
  * ([[relayedReset]]): the parent's reset leaf lives on the hub die, and a block
  * pinned two dice away would otherwise be reset over a 0-logic-level net that
  * crosses both SLR boundaries and fans out to every flop of the block. Two
  * flops add two cycles of reset latency on that block only; nothing is in
  * flight at that time (the host starts the accelerator long after reset).
  */
object SlrLocal {

  /** Instance-name prefix the emitter and the floorplan key on. */
  val prefix = "hcslr_"

  /** Two reset-retiming flops inside the calling module. */
  def relayedReset(r: Reset): Bool = {
    val r1 = RegNext(r.asBool, true.B)
    val r2 = RegNext(r1, true.B)
    dontTouch(r1)
    dontTouch(r2)
    r2
  }

  /**
    * Per-die reset relay chain for one link: one flop per die/boundary role, in
    * `outward` order (hub first), each an `hcslr_<link>rst_<role>` instance the
    * floorplan pins to its die, so the reset crosses one SLR boundary per cycle
    * instead of two dice in one (that net was -1.0 ns post-place). The hub role
    * keeps `base`. Returns the reset to use on each role's die.
    */
  def resetChain(base: Reset, link: String, outward: Seq[String]): Map[String, Bool] =
    chisel3.experimental.noPrefix {
      var cur: Bool = base.asBool
      outward.map { role =>
        if (role != NocPePolicy.hubSlr.toLowerCase) {
          val m = Module(new SlrResetRelay)
          m.suggestName(s"${prefix}${link}rst_$role")
          m.io.in := cur
          cur = m.io.out
        }
        role -> cur
      }.toMap
    }

  /** Splits `total` items into `groups` contiguous shares of as equal a size as possible. */
  def shares(total: Int, groups: Int): Seq[Range] = {
    require(groups > 0 && total >= groups)
    val size = total / groups
    val larger = total % groups
    Seq
      .tabulate(groups) { g => size + (if (g < larger) 1 else 0) }
      .scanLeft(0) { _ + _ }
      .sliding(2)
      .map { b => b(0) until b(1) }
      .toSeq
  }

  /**
    * Round-robin distribution tree from `source` to `sinks`, at most `fanout`
    * sinks per node. Every node is a [[chext.elastic.Distributor]], which
    * registers its sinks, so each level is one pipeline stage and no node
    * chooses among more than `fanout` ready flags. A task goes to exactly one
    * sink, as with the single flat Distributor it replaces.
    */
  def distTree(
      gen: UInt,
      source: DecoupledIO[UInt],
      sinks: Seq[DecoupledIO[UInt]],
      fanout: Int,
      name: String
  ): Unit = {
    require(sinks.nonEmpty && fanout >= 2)
    if (sinks.length == 1) {
      source :=> sinks.head
    } else {
      val k =
        if (sinks.length <= fanout) sinks.length
        else math.min(fanout, (sinks.length + fanout - 1) / fanout)
      val node = Module(new elastic.Distributor(gen, k, elastic.RoundRobinChooser()))
      node.suggestName(name)
      source :=> node.io.source
      elastic.Disposed(node.io.select)
      if (k == sinks.length) {
        sinks.zipWithIndex.foreach { case (s, i) => node.io.sinks(i) :=> s }
      } else {
        shares(sinks.length, k).zipWithIndex.foreach { case (share, i) =>
          val mid = Wire(Decoupled(gen))
          mid.suggestName(s"${name}_c$i")
          node.io.sinks(i) :=> mid
          distTree(gen, mid, share.map(sinks(_)), fanout, s"${name}_$i")
        }
      }
    }
  }
}

/** One reset-relay flop (no reset of its own), see [[SlrLocal.resetChain]]. */
class SlrResetRelay extends Module {
  override def desiredName: String = "hcSlrResetRelay"
  val io = IO(new Bundle {
    val in = Input(Bool())
    val out = Output(Bool())
  })
  private val r = RegNext(io.in)
  dontTouch(r)
  io.out := r
}

/** A [[SkidBuffer]] with its own retimed reset, for a stage pinned to a die (see [[SlrLocal]]). */
class SlrSkid[T <: Data](gen: T) extends Module {
  override def desiredName: String = "hcSlrSkid"
  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(gen))
    val deq = Decoupled(gen)
  })
  private val rr = SlrLocal.relayedReset(reset)
  withReset(rr) {
    val stage = Module(new SkidBuffer(gen))
    stage.io.enq <> io.enq
    io.deq <> stage.io.deq
  }
}

object SlrSkid {

  /**
    * A chain of [[SlrSkid]] stages, one per role, named `hcslr_<link>_<role>`.
    * Every SLR crossing on the way is then flop-to-flop, both valid/data and
    * ready (a SkidBuffer drives all three from registers).
    */
  def chain[T <: Data](
      source: DecoupledIO[T],
      link: String,
      roles: Seq[String],
      resets: Map[String, Bool] = Map.empty
  ): DecoupledIO[T] =
    // noPrefix: the instance names must be exactly `hcslr_<link>_<role>`.
    chisel3.experimental.noPrefix {
      roles.foldLeft(source) { case (src, role) =>
        val s = resets.get(role) match {
          case Some(r) => withReset(r)(Module(new SlrSkid(chiselTypeOf(src.bits))))
          case None    => Module(new SlrSkid(chiselTypeOf(src.bits)))
        }
        s.suggestName(s"${SlrLocal.prefix}${link}_$role")
        s.io.enq <> src
        s.io.deq
      }
    }
}
