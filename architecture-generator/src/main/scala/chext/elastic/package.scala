package chext

import chisel3._
import chisel3.util.DecoupledIO

package object elastic {
  type Interface[T <: chisel3.Data] = DecoupledIO[T]

  object Interface {
    def apply[T <: Data](gen: T): Interface[T] =
      new Interface(gen)

  }

  object Source {
    def apply[T <: Data](gen: T): Interface[T] =
      Flipped(new Interface(gen))
  }


  object Sink {
    def apply[T <: Data](gen: T): Interface[T] =
      new Interface(gen)
  }
}
