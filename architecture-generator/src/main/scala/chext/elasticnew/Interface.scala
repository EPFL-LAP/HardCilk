package chext

import chisel3._
import chisel3.util.DecoupledIO

import chisel3.experimental.SourceInfo
import chisel3.experimental.requireIsChiselType
import chisel3.experimental.requireIsHardware

import scala.annotation.unchecked.uncheckedVariance

package object elasticnew {
  // Use DecoupledIO as the Interface type for compatibility with AXI4 interfaces
  type Interface[+T <: Data] = DecoupledIO[T @uncheckedVariance]
}

package elasticnew {

object Interface {
  def apply[T <: Data](gen: T)(implicit sourceInfo: SourceInfo): Interface[T] = {
    requireIsChiselType(
      gen,
      "elasticnew.Interface: expected Chisel type for gen"
    )
    new DecoupledIO(gen)
  }
}

object Source {
  def apply[T <: Data](gen: T)(implicit sourceInfo: SourceInfo): Interface[T] = {
    requireIsChiselType(
      gen,
      "elasticnew.Source: expected Chisel type for gen"
    )
    Flipped(new DecoupledIO(gen))
  }

  def io[T <: Data](gen: T)(implicit sourceInfo: SourceInfo): Interface[T] = IO(apply(gen))

  def like[T <: Data](hw: Interface[T])(implicit sourceInfo: SourceInfo) = {
    requireIsHardware(
      hw,
      "elasticnew.Source: expected hardware for hw"
    )
    Source(chiselTypeOf(hw.bits))
  }

  def ioLike[T <: Data](hw: Interface[T])(implicit sourceInfo: SourceInfo) = IO(like(hw))
}

object Sink {
  def apply[T <: Data](gen: T)(implicit sourceInfo: SourceInfo): Interface[T] = {
    requireIsChiselType(
      gen,
      "elasticnew.Sink: expected Chisel type for gen"
    )
    new DecoupledIO(gen)
  }

  def like[T <: Data](hw: Interface[T])(implicit sourceInfo: SourceInfo) = {
    requireIsHardware(
      hw,
      "elasticnew.Sink: expected hardware for hw"
    )
    Sink(chiselTypeOf(hw.bits))
  }
}

object EWire {
  def apply[T <: Data](gen: T)(implicit sourceInfo: SourceInfo) = {
    requireIsChiselType(
      gen,
      "elasticnew.EWire: expected Chisel type for gen"
    )
    Wire(new DecoupledIO(gen))
  }

  def like[T <: Data](hw: Interface[T])(implicit sourceInfo: SourceInfo) = {
    requireIsHardware(
      hw,
      "elasticnew.EWire: expected hardware for hw"
    )
    Wire(new DecoupledIO(chiselTypeOf(hw.bits)))
  }
}

} // package elasticnew
