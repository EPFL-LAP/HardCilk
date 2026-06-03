package chext.elasticnew

import chisel3._
import chisel3.util.DecoupledIO
import chisel3.util.ReadyValidIO
import chisel3.experimental.SourceInfo

import chisel3.hacks.deferred

import chext.elasticnew.{tracking => enTracking}
import enTracking.Container
import enTracking.withContainer

/** `Repeat` replicates each input token multiple times at the output.
  */
abstract class Repeat[Tin <: Data, Tout <: Data](
    source: ReadyValidIO[Tin],
    sink: ReadyValidIO[Tout],
    wIndex: Int
)(implicit si_ : SourceInfo)
    extends Container
    with Fire[Tout] {
  protected def fireSink: ReadyValidIO[Tout] = sink

  val sourceInfo: SourceInfo = si_
  def tpe: String = "Repeat"
  def namePrefix: String = "repeat"

  type LenFn = (Tin) => UInt
  type OutFn = (Tin, UInt, Bool, Bool) => Tout
  type OutExplicitFn = (Tin, UInt, Bool, Bool, Tout) => Unit

  private var lenFn_ = Option.empty[LenFn]
  private var outFn_ = Option.empty[OutFn]

  private def throw_(msg: String) =
    throw new IllegalArgumentException(sourceInfo.makeMessage((x) => f"Repeat: $msg $x"))

  protected final def in: Tin = throw_("in field shall not be used!")

  private def require_(cond: Boolean, msg: String): Unit = {
    require(cond, sourceInfo.makeMessage(x => s"Repeat: $msg $x"))
  }

  /** Sets the pure functional length function. */
  protected final def len(fn: => LenFn): Unit = {
    require_(lenFn_.isEmpty, "'len { (in) => ... }' must be called at most once!")
    lenFn_ = Some(fn)
  }

  /** Sets the pure functional output generation function. */
  protected final def out(fn: => OutFn): Unit = {
    require_(
      outFn_.isEmpty,
      "'out { (in, index, first, last) => ... }' must be called at most once!"
    )
    outFn_ = Some(fn)
  }

  /** Sets the imperative output generation function. */
  protected final def outExplicit(fn: => OutExplicitFn): Unit = {
    out { (in, index, first, last) =>
      {
        val outResult = Wire(chiselTypeOf(sink.bits))
        fn(in, index, first, last, outResult)
        outResult
      }
    }
  }

  deferred {
    require_(lenFn_.nonEmpty, "'len { (in) => ... }' must be called at least once!")
    require_(
      outFn_.nonEmpty,
      "'out { (in, index, first, last) => ... }' must be called at least once!"
    )

    val lenFn = lenFn_.get
    val outFn = outFn_.get

    withContainer(this) {
      val count = new Count(source, sink, UInt(wIndex.W)) { count =>
        count.init { (_) => 0.U }

        count.cond { (in, state) => state =/= lenFn(in) }

        count.next { (_, state) => state + 1.U }

        count.out { (in, state, first, last) => outFn(in, state, first, last) }
      }
    }
  }
}
