package chext.elasticnew

import chisel3._
import chisel3.util.DecoupledIO
import chisel3.util.ReadyValidIO

import chisel3.experimental.SourceInfo

import chisel3.hacks.deferred

import scala.collection.mutable.ArrayBuffer

import chext.elasticnew.{tracking => enTracking}
import enTracking.Component

/** `Transducer` creates a finite-state transducer between a source and a sink.
  *
  * It defines four actions: `stall`, `accept`, `consume`, and `produce`, only one action must be
  * taken at a time. Each action can be associated with a state transition, which is executed only
  * if the action is taken.
  */
abstract class Transducer[Tin <: Data, Tout <: Data](
    source: ReadyValidIO[Tin],
    sink: ReadyValidIO[Tout]
)(implicit si_ : SourceInfo)
    extends Component
    with Fire[Tout] {
  protected def fireSink: ReadyValidIO[Tout] = sink

  addSourcePort("source", source)
  addSinkPort("sink", sink)

  val sourceInfo: SourceInfo = si_
  def tpe: String = "Transducer"
  def namePrefix: String = "transducer"

  protected final val in = source.bits
  protected final val out = sink.bits

  type PacketFn = () => Unit
  type ActionFn = () => Unit

  private var packetFn_ = Option.empty[PacketFn]

  private var dryRun_ = true

  private var actions_ = ArrayBuffer.empty[(String, SourceInfo)]
  private var actionIndex_ = 0
  private var actionConds_ = Seq.empty[Bool]

  private var deferredContext_ = false

  private def require_(cond: Boolean, msg: String): Unit = {
    require(cond, sourceInfo.makeMessage((x) => f"Transducer: $msg $x"))
  }

  private def requireDeferredContext_(funcName: String)(implicit sourceInfo: SourceInfo): Unit = {
    require(
      deferredContext_,
      sourceInfo.makeMessage((x) =>
        f"Transducer: $funcName must be called within 'packet { ... }' $x"
      )
    )
  }

  private def throw_(msg: String) =
    throw new IllegalArgumentException(sourceInfo.makeMessage((x) => f"Transducer: $msg $x"))

  /** Defines the transducer's per-packet behavior. Must be called exactly once.
    */
  protected final def packet(fn: => Unit): Unit = {
    if (!deferredContext_)
      require_(packetFn_.isEmpty, "'packet { ... }' must be defined at most once!")

    packetFn_ = Some(() => fn)
  }

  /** Neither consumes input nor produces output; holds state.
    */
  protected final def stall(action: => Unit)(implicit si_ : SourceInfo): Unit = {
    requireDeferredContext_("stall")

    if (dryRun_) {
      actions_.addOne(("stall", sourceInfo))
      return
    }

    actionConds_(actionIndex_) := true.B
    actionIndex_ += 1

    source.ready := false.B
    sink.valid := false.B

    action
  }

  /** Accepts a source packet and produces a sink packet in the same cycle.
    */
  protected final def accept(action: => Unit)(implicit si_ : SourceInfo): Unit = {
    requireDeferredContext_("accept")

    if (dryRun_) {
      actions_.addOne(("accept", sourceInfo))
      return
    }

    actionConds_(actionIndex_) := true.B
    actionIndex_ += 1

    source.ready := sink.ready
    sink.valid := source.valid

    when(sink.ready) {
      action
    }
  }

  /** Consumes a source packet without producing an output.
    */
  protected final def consume(action: => Unit)(implicit si_ : SourceInfo): Unit = {
    requireDeferredContext_("consume")

    if (dryRun_) {
      actions_.addOne(("consume", sourceInfo))
      return
    }

    actionConds_(actionIndex_) := true.B
    actionIndex_ += 1

    source.ready := true.B
    sink.valid := false.B

    action
  }

  /** Produces a sink packet without consuming the source packet.
    */
  protected final def produce(action: => Unit)(implicit si_ : SourceInfo): Unit = {
    requireDeferredContext_("produce")

    if (dryRun_) {
      actions_.addOne(("produce", sourceInfo))
      return
    }

    actionConds_(actionIndex_) := true.B
    actionIndex_ += 1

    source.ready := false.B
    sink.valid := source.valid

    when(sink.ready) {
      action
    }
  }

  deferred {
    require_(packetFn_.nonEmpty, "'packet { ... }' must be called at least once!")

    deferredContext_ = true

    dryRun_ = true
    when(source.valid) {
      packetFn_.get()
    }

    dryRun_ = false

    actionConds_ = Seq.fill(actions_.length) { WireInit(false.B) }

    source.ready := false.B
    sink.valid := false.B

    when(source.valid) {
      packetFn_.get()
    }.otherwise {
      out := DontCare
    }

    require_(
      actionConds_.nonEmpty,
      "elasticnew.Transducer: There must be at least one action taken in the transducer. Maybe call accept{} in it?"
    )

    val cond = VecInit(actionConds_).asUInt

    val errorAtLeastTwoActions = WireInit(source.valid && ((cond & (cond -% 1.U)) =/= 0.U))
    val errorNoAction = WireInit(source.valid && (cond === 0.U))

    dontTouch(errorAtLeastTwoActions)
    dontTouch(errorNoAction)

    when(errorAtLeastTwoActions) {
      printf("elasticnew.Transducer: at least two actions are taken in the same clock cycle!\n")
      actionConds_.zip(actions_).foreach {
        case (cond, (funcName, sourceInfo)) => {
          when(cond) {
            printf(sourceInfo.makeMessage { (x) => f"elasticnew.Transducer: action '$funcName' $x\n" })
          }
        }
      }
    }

    when(errorNoAction) {
      printf(sourceInfo.makeMessage { (x) => f"elasticnew.Transducer: no action was taken! $x\n" })
    }

    assert(actionIndex_ == actions_.length)
  }
}
