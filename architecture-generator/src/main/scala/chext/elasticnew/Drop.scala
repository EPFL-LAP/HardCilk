package chext.elasticnew

import chisel3._
import chisel3.util.ReadyValidIO
import chisel3.experimental.SourceInfo

import chisel3.hacks.deferred

import chext.elasticnew.{tracking => enTracking}
import enTracking.Component

/** `Drop` conditionally drops tokens.
  */
abstract class Drop[Tin <: Data, Tout <: Data](
    source: ReadyValidIO[Tin],
    sink: ReadyValidIO[Tout]
)(implicit si_ : SourceInfo)
    extends Component
    with Fire[Tout] {
  protected def fireSink: ReadyValidIO[Tout] = sink

  addSourcePort("source", source)
  addSinkPort("sink", sink)

  val sourceInfo: SourceInfo = si_
  def tpe: String = "Drop"
  def namePrefix: String = "drop"

  protected final val in = source.bits
  protected final val out = sink.bits

  private var condFn_ = Option.empty[() => Bool]

  private def require_(cond: Boolean, msg: String): Unit = {
    require(cond, sourceInfo.makeMessage((x) => f"Drop: $msg $x"))
  }

  /** Sets the condition under which an input token should be dropped.
    */
  protected final def cond(fn: => Bool): Unit = {
    require_(condFn_.isEmpty, "'cond { ... }' must be called at most once!")
    condFn_ = Some(() => { fn })
  }

  deferred {
    require_(condFn_.nonEmpty, "'cond { ... }' must be called at least once!")

    val condVal = condFn_.get()

    source.ready := condVal || sink.ready
    sink.valid := !condVal && source.valid
  }
}
