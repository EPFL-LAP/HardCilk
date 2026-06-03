package chext.stream

import chisel3._

import chext.amba.axi4

class Task[T <: Data](genUser: T, wAddress: Int, wLength: Int) extends Bundle {
  val address = UInt(wAddress.W)
  val length = UInt(wLength.W)
  val user = genUser.cloneType
}
