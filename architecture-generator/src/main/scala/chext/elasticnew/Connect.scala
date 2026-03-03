package chext.elasticnew

import chisel3._
import chisel3.util.ReadyValidIO
import chisel3.experimental.SourceInfo
import chisel3.experimental.skipPrefix

import chisel3.hacks.deferred

import chext.elasticnew.{tracking => enTracking}
import enTracking.Component
import enTracking.RigidComponent

/** `Connect` connects two elastic interfaces, with an optional combinational transformation.
  */
class Connect[Tin <: Data, Tout <: Data](
    source: ReadyValidIO[Tin],
    sink: ReadyValidIO[Tout]
)(implicit si_ : SourceInfo)
    extends Component
    with Fire[Tout] {
  protected def fireSink: ReadyValidIO[Tout] = sink

  addSourcePort("source", source)
  addSinkPort("sink", sink)

  val sourceInfo: SourceInfo = si_
  def tpe: String = "Connect"
  def namePrefix: String = "connect"

  protected final val in = source.bits
  protected final val out = sink.bits

  out := in

  sink.valid := source.valid
  source.ready := sink.ready
}

object ConnectOp {
  private def connect_[T <: Data](
      source: ReadyValidIO[T],
      sink: ReadyValidIO[T]
  )(implicit sourceInfo: SourceInfo) = {
    enTracking.uniquePrefix {
      source.ready := sink.ready
      sink.valid := source.valid
      sink.bits := source.bits

      val component = skipPrefix { new RigidComponent("Connect", "connect") }
      component.source("source", source)
      component.sink("sink", sink)
    }
  }

  implicit class elastic_connect_op[T <: Data](source: ReadyValidIO[T]) {
    def :=>(sink: ReadyValidIO[T])(implicit sourceInfo: SourceInfo): Unit = {
      connect_(source, sink)
    }
  }
}
