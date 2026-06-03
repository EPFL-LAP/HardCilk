package chext.elasticnew

import chisel3._
import chisel3.util.DecoupledIO
import chisel3.util.ReadyValidIO

import chisel3.experimental.SourceInfo

import chisel3.hacks.deferred

import scala.collection.mutable.ListBuffer

import chext.elasticnew.{tracking => enTracking}
import enTracking.Component

abstract class Fork[T <: Data](
    source: ReadyValidIO[T],
    eager: Boolean = true
)(implicit si_ : SourceInfo)
    extends Component {
  addSourcePort("source", source)

  val sourceInfo: SourceInfo = si_
  def tpe: String = "Fork"
  def namePrefix: String = "fork"

  private def require_(cond: Boolean, msg: String): Unit = {
    require(cond, sourceInfo.makeMessage(x => s"Fork: $msg $x"))
  }

  private val sinkList = ListBuffer.empty[DecoupledIO[Data]]

  protected val in = source.bits

  protected final def onFork: Unit = throw new NotImplementedError("Shall not be used!")

  /** Branches a new elastic interface from the fork.
    */
  protected final def fork[TT <: Data](tt: TT = in): DecoupledIO[TT] = {
    val result = Wire(new DecoupledIO(chiselTypeOf(tt)))
    result.bits := tt
    addSinkPort(f"sink_${sinkList.length}", result)
    sinkList.addOne(result)
    result
  }

  deferred {
    require_(sinkList.nonEmpty, "no sinks are specified for the fork!")

    if (eager)
      forkImpl.eagerFork(source, sinkList.toSeq, false)
    else
      forkImpl.lazyFork(source, sinkList.toSeq, false)
  }
}

private[elasticnew] object forkImpl {
  def eagerFork[T <: Data](
      source: ReadyValidIO[T],
      sinks: Seq[DecoupledIO[Data]],
      mark: Boolean = true
  )(implicit si: SourceInfo): Unit = {
    // registers to remember if transmission already took place
    val regs = RegInit(VecInit(Seq.fill(sinks.length) { false.B }))

    val ready = VecInit(sinks.zip(regs).map {
      case (sink, reg) => {
        sink.ready || reg
      }
    }).reduceTree(_ && _)
    source.ready := ready

    sinks.zip(regs).foreach {
      case (sink, reg) => {
        sink.valid := source.valid && !reg
      }
    }

    sinks.zip(regs).foreach {
      case (sink, reg) => {
        // the next value for the register
        reg := (sink.ready || reg) && source.valid && !source.ready
      }
    }
  }

  def lazyFork[T <: Data](
      source: ReadyValidIO[T],
      sinks: Seq[DecoupledIO[Data]],
      mark: Boolean = true
  )(implicit si: SourceInfo): Unit = {
    sinks.foreach { //
      case (sink) => sink.valid := source.valid && source.ready
    }

    val ready = VecInit(sinks.map { _.ready }).reduceTree(_ && _)
    source.ready := ready
  }
}
