// From Canberk Universe
package Util

import chisel3._

import chext.elastic
import elastic.ConnectOp._

import chext.amba.axi4
import axi4.Ops._

import chext.util.BitOps._

case class AddressTransformConfig(
    val axiCfg: axi4.Config,
    val transform: Seq[Int]
) {
  require(
    transform.sorted == (0 until axiCfg.wAddr),
    "Invalid transformation!"
  )
}

class AddressTransform(val cfg: AddressTransformConfig) extends Module {
  import cfg._

  val s_axi = IO(axi4.full.Slave(axiCfg))
  val m_axi = IO(axi4.full.Master(axiCfg))

  if (axiCfg.read) {
    val transform0 = new elastic.Transform(s_axi.ar, m_axi.ar) {
      protected def onTransform: Unit = {
        out := in
        out.addr := in.addr.extract(transform)
      }
    }

    m_axi.r :=> s_axi.r
  }

  if (axiCfg.write) {
    val transform0 = new elastic.Transform(s_axi.aw, m_axi.aw) {
      protected def onTransform: Unit = {
        out := in
        out.addr := in.addr.extract(transform)
      }
    }

    s_axi.w :=> m_axi.w
    m_axi.b :=> s_axi.b
  }
}

object TestAddressTransformConfig extends App {
  AddressTransformConfig(
    axi4.Config(wAddr = 34),
    Seq(37, 36, 35, 34, 33, 17, 16, 15, 14, 28, 27, 26, 25, 24, 23, 22, 21, 20, 19, 18, 32, 31, 30, 29, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0).reverse
  )
}
