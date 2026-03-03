package chext.elasticnew

import chisel3._
import chisel3.util.ReadyValidIO
import chisel3.experimental.SourceInfo

import chisel3.hacks.deferred

import chext.elasticnew.{tracking => enTracking}
import enTracking.Component

abstract class Count[Tstate <: Data, Tin <: Data, Tout <: Data](
    source: ReadyValidIO[Tin],
    sink: ReadyValidIO[Tout],
    genState: Tstate
)(implicit si_ : SourceInfo)
    extends Component
    with Fire[Tout] {
  protected def fireSink: ReadyValidIO[Tout] = sink

  addSourcePort("source", source)
  addSinkPort("sink", sink)

  val sourceInfo: SourceInfo = si_
  def tpe: String = "Count"
  def namePrefix: String = "count"

  type InitFn = (Tin) => Tstate
  type InitExplicitFn = (Tin, Tstate) => Unit
  type CondFn = (Tin, Tstate) => Bool
  type NextFn = (Tin, Tstate) => Tstate
  type NextExplicitFn = (Tin, Tstate, Tstate) => Unit
  type OutFn = (Tin, Tstate, Bool, Bool) => Tout
  type OutExplicitFn = (Tin, Tstate, Bool, Bool, Tout) => Unit

  private var initFn_ = Option.empty[InitFn]
  private var condFn_ = Option.empty[CondFn]
  private var nextFn_ = Option.empty[NextFn]
  private var outFn_ = Option.empty[OutFn]

  private def require_(cond: Boolean, msg: String): Unit = {
    require(cond, sourceInfo.makeMessage((x) => f"Count: $msg $x"))
  }

  private def throw_(msg: String) =
    throw new IllegalArgumentException(sourceInfo.makeMessage((x) => f"Count: $msg $x"))

  protected final def in: Tin = throw_("`in` field shall not be used!")

  /** Sets the pure functional "init" state initialization function. */
  protected final def init(fn: => InitFn): Unit = {
    require_(initFn_.isEmpty, "'init { (in) => ... }' must be called at most once!")
    initFn_ = Some(fn)
  }

  /** Sets the imperative "init" state initialization function. */
  protected final def initExplicit(fn: => InitExplicitFn): Unit = {
    init {
      (in) => {
        val initResult = Wire(genState)
        fn(in, initResult)
        initResult
      }
    }
  }

  /** Sets the condition function that determines whether the state is valid for generating an output token. */
  protected final def cond(fn: => CondFn): Unit = {
    require_(condFn_.isEmpty, "'cond { (in, state) => ... }' must be called at most once!")
    condFn_ = Some(fn)
  }

  /** Sets the pure functional "next" state transition function. */
  protected final def next(fn: => NextFn): Unit = {
    require_(nextFn_.isEmpty, "'next { (in, state) => ... }' must be called at most once!")
    nextFn_ = Some(fn)
  }

  /** Sets the imperative "next" state update function. */
  protected final def nextExplicit(fn: => NextExplicitFn): Unit = {
    next {
      (in, state) => {
        val nextResult = Wire(genState)
        fn(in, state, nextResult)
        nextResult
      }
    }
  }

  /** Sets the pure functional output generation function. */
  protected final def out(fn: => OutFn): Unit = {
    require_(outFn_.isEmpty, "'out { (in, state, first, last) => ... }' must be called at most once!")
    outFn_ = Some(fn)
  }

  /** Sets the imperative output generation function. */
  protected final def outExplicit(fn: => OutExplicitFn): Unit = {
    out {
      (in, state, first, last) => {
        val outResult = Wire(chiselTypeOf(sink.bits))
        fn(in, state, first, last, outResult)
        outResult
      }
    }
  }

  deferred {
    require_(initFn_.nonEmpty, "'init { (in) => ... }' must be called at least once!")
    require_(condFn_.nonEmpty, "'cond { (in, state) => ... }' must be called at least once!")
    require_(nextFn_.nonEmpty, "'next { (in, state) => ... }' must be called at least once!")
    require_(outFn_.nonEmpty, "'out { (in, state, first, last) => ... }' must be called at least once!")

    val initFn = initFn_.get
    val nextFn = nextFn_.get
    val condFn = condFn_.get
    val outFn = outFn_.get

    val state = Reg(genState)
    val valid = RegInit(false.B)

    val inBits = source.bits
    val outBits = sink.bits

    // Default: no dequeue, no enqueue
    source.ready := false.B
    sink.valid := false.B
    sink.bits := DontCare

    when(source.valid) {
      when(valid) {
        val nextState = nextFn(inBits, state)

        when(!condFn(inBits, nextState)) {
          sink.bits := outFn(inBits, state, false.B, true.B)
          sink.valid := true.B

          when(sink.ready) {
            valid := false.B
            state := 0.U.asTypeOf(state)
            source.ready := true.B
          }
        }.otherwise {
          sink.bits := outFn(inBits, state, false.B, false.B)
          sink.valid := true.B

          when(sink.ready) {
            state := nextState
          }
        }
      }.otherwise {
        val initState = initFn(inBits)
        val nextState = nextFn(inBits, initState)

        when(!condFn(inBits, initState)) {
          source.ready := true.B

        }.elsewhen(!condFn(inBits, nextState)) {
          sink.bits := outFn(inBits, initState, true.B, true.B)
          sink.valid := true.B

          when(sink.ready) {
            source.ready := true.B
          }
        }.otherwise {
          sink.bits := outFn(inBits, initState, true.B, false.B)
          sink.valid := true.B

          when(sink.ready) {
            state := nextState
            valid := true.B
          }
        }
      }
    }
  }
}
