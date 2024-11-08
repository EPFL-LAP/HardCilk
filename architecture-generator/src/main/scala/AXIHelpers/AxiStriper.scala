package AXIHelpers

import chisel3._

import chext.amba.axi4
import axi4.Ops._

import chext.elastic
import chext.elastic.ConnectOp._

class AxiStriper(
    val cfg: axi4.Config,
    val stripe: UInt => UInt
) extends Module {
  suggestName("AxiStriper")
  val s_axi = IO(axi4.full.Slave(cfg))
  val m_axi = IO(axi4.full.Master(cfg))

  import chext.util.BitOps._

  if (cfg.read) {
    new elastic.Transform(s_axi.ar, m_axi.ar) {
      protected def onTransform: Unit = {
        out := in
        out.addr := stripe(in.addr)
      }
    }

    m_axi.r :=> s_axi.r
  }

  if (cfg.write) {
    new elastic.Transform(s_axi.aw, m_axi.aw) {
      protected def onTransform: Unit = {
        out := in
        out.addr := stripe(in.addr)
      }
    }

    s_axi.w :=> m_axi.w
    m_axi.b :=> s_axi.b
  }
}

object AxiStriper {
  def apply(
      s_axi: axi4.full.Interface,
      stripe: UInt => UInt = x => {
        // x(24, 20) ## x(33, 25) ## x(19, 0)
        x(16, 12) ## x(33, 17) ## x(11, 0)
      }
  ): axi4.full.Interface = {
    val module = Module(new AxiStriper(s_axi.cfg, stripe))
    s_axi :=> module.s_axi
    module.m_axi
  }
}
