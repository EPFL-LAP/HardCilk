package chext.elasticnew

import chisel3._
import chisel3.util.ReadyValidIO
import chisel3.experimental.SourceInfo

import chext.elasticnew.{tracking => enTracking}
import enTracking.Component

abstract class Transform[Tin <: Data, Tout <: Data](
    source: ReadyValidIO[Tin],
    sink: ReadyValidIO[Tout]
)(implicit si_ : SourceInfo)
    extends Component
    with Fire[Tout] {
  protected def fireSink: ReadyValidIO[Tout] = sink

  addSourcePort("source", source)
  addSinkPort("sink", sink)

  val sourceInfo: SourceInfo = si_
  def tpe: String = "Transform"
  def namePrefix: String = "transform"

  protected final val in = source.bits
  protected final val out = sink.bits

  sink.valid := source.valid
  source.ready := sink.ready
}
