package chext.elasticnew

import chisel3._
import chisel3.util.DecoupledIO
import chisel3.util.ReadyValidIO
import chisel3.experimental.SourceInfo

import chisel3.hacks.deferred

import scala.collection.mutable.ListBuffer

import chext.elasticnew.{tracking => enTracking}
import enTracking.Component

abstract class Join[T <: Data](
    val sink: ReadyValidIO[T]
)(implicit si_ : SourceInfo)
    extends Component {

  addSinkPort("sink", sink)

  val sourceInfo: SourceInfo = si_
  def tpe: String = "Join"
  def namePrefix: String = "join"

  private def require_(cond: Boolean, msg: String): Unit = {
    require(cond, sourceInfo.makeMessage(x => s"Join: $msg $x"))
  }

  private val sourceList = ListBuffer.empty[ReadyValidIO[Data]]

  protected val out: T = sink.bits

  protected final def onJoin: Unit = throw new NotImplementedError("Shall not be used!")

  /** Adds a new elastic interface to join.
    */
  def join[TT <: Data](source: ReadyValidIO[TT]): TT = {
    addSourcePort(f"source_${sourceList.length}", source)
    sourceList.addOne(source)
    source.bits
  }

  deferred {
    require_(sourceList.nonEmpty, "no sources are specified for the join!")

    joinImpl.join(sourceList.toSeq, sink, false)
  }
}

private[elasticnew] object joinImpl {
  /** Implements a join.
    */
  def join[T <: Data](
      sources: Seq[ReadyValidIO[Data]],
      sink: ReadyValidIO[Data],
      mark: Boolean = true
  )(implicit si: SourceInfo): Unit = {
    val allValid =
      VecInit(sources.map { _.valid }).reduceTree(_ && _)
    val fire = sink.ready && allValid
    sources.foreach { _.ready := fire }
    sink.valid := allValid
  }
}
