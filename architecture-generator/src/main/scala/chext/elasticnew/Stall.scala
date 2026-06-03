package chext.elasticnew

import chisel3._
import chisel3.util.ReadyValidIO
import chisel3.experimental.SourceInfo

import chisel3.hacks.deferred

import chext.elasticnew.{tracking => enTracking}
import enTracking.Component

/** `Stall` conditionally stalls tokens.
  *
  * It connects a source and sink interface, and forwards tokens from source to sink only when a
  * user-defined condition is **true**. If the condition is **false**, the token is stalled.
  *
  * Once the stall condition is false (i.e., there is a transfer downstream), it should not turn
  * true until the transfer is complete. This is due to the irrevocable nature of the elastic
  * components. If this condition cannot be satisfied, place a sink buffer.
  *
  * @param source
  *   The upstream interface providing tokens that might be stalled.
  * @param sink
  *   The downstream interface accepting tokens.
  * @tparam Tin
  *   Type of the input token.
  * @tparam Tout
  *   Type of the output token.
  */
abstract class Stall[Tin <: Data, Tout <: Data](
    source: ReadyValidIO[Tin],
    sink: ReadyValidIO[Tout]
)(implicit si_ : SourceInfo)
    extends Component
    with Fire[Tout] {
  protected def fireSink: ReadyValidIO[Tout] = sink

  addSourcePort("source", source)
  addSinkPort("sink", sink)

  val sourceInfo: SourceInfo = si_
  def tpe: String = "Stall"
  def namePrefix: String = "stall"

  protected final val in = source.bits
  protected final val out = sink.bits

  private var condFn_ = Option.empty[() => Bool]

  private def require_(cond: Boolean, msg: String): Unit = {
    require(cond, sourceInfo.makeMessage((x) => f"Stall: $msg $x"))
  }

  /** Sets the condition under which an input token should be stalled.
    *
    * This must be called exactly once. The condition is evaluated every cycle. If it returns
    * `true`, the current input token is stalled. If it returns `false`, the token is passed to the
    * output normally.
    *
    * @example
    *   cond { in.shouldBeStalled }
    */
  protected final def cond(fn: => Bool): Unit = {
    require_(condFn_.isEmpty, "'cond { ... }' must be called at most once!")
    condFn_ = Some(() => { fn })
  }

  deferred {
    require_(condFn_.nonEmpty, "'cond { ... }' must be called at least once!")

    val cond = condFn_.get()

    source.ready := !cond && sink.ready
    sink.valid := !cond && source.valid
  }
}
