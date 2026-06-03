package chext.elasticnew

import chisel3._
import chisel3.util._
import chisel3.experimental.SourceInfo

import ConnectOp._

class Mux[T <: Data](
    val gen: T,
    val n: Int,
    val isLastFn: T => Bool = (_: T) => true.B
) extends Module {
  require(n > 0)
  override def desiredName: String = "elasticMux"

  val genSelect = UInt(chisel3.util.log2Up(n).W)

  val io = IO(new Bundle {
    val sources = Vec(n, Source(gen))
    val sink = Sink(gen)
    val select = Source(genSelect)
  })

  private val valid = io.select.valid && io.sources(io.select.bits).valid
  private val fire = valid && io.sink.ready
  private val isLast = isLastFn(io.sink.bits)

  io.sources.zipWithIndex.foreach { case (x, i) =>
    x.ready := fire && i.U === (io.select.bits)
  }

  // sink ready might wait for sink valid
  // so, make sure that they do not depend on each other
  io.sink.valid := valid

  io.select.ready := fire && isLast

  io.sink.bits := io.sources(io.select.bits.asUInt).bits
}

object Mux {
  def apply[T <: Data](
      sources: Seq[Interface[T]],
      sink: Interface[T],
      select: Interface[UInt],
      isLastFn: T => Bool = (_: T) => true.B
  )(implicit si: SourceInfo): Mux[T] = {
    val mux = Module(
      new Mux(chiselTypeOf(sources(0).bits), sources.length, isLastFn)
    )

    sources.zip(mux.io.sources).foreach { case (x, y) => x :=> y }
    mux.io.sink :=> sink
    select :=> mux.io.select

    mux
  }
}
