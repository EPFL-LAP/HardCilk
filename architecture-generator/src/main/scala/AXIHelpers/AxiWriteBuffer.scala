package AXIHelpers

import chisel3._

import chext.elastic._
import chext.elastic.ConnectOp._

import chext.amba.axi4._
import chext.amba.axi4.Ops._

class AxiWriteBuffer(
    cfg: Config,
    maxInflightWrite: Int = 3
) extends Module {

  val s_axi = IO(full.Slave(cfg))
  val m_axi = IO(full.Master(cfg))

  private val counter = Module(new chext.util.Counter(maxInflightWrite + 1))

  counter.io.incEn := s_axi.aw.fire
  counter.io.decEn := s_axi.b.fire

  new Arrival(s_axi.aw, m_axi.aw) {
    protected def onArrival: Unit = {
      when(!counter.io.full) {
        out := in
        accept()
      }
    }
  }

  new Arrival(s_axi.ar, m_axi.ar) {
    protected def onArrival: Unit = {
      when(counter.io.empty && !s_axi.aw.fire) {
        out := in
        accept()
      }
    }
  }

  s_axi.w :=> m_axi.w
  m_axi.b :=> s_axi.b
  m_axi.r :=> s_axi.r
}

object AxiWriteBuffer {
  def apply(slave: full.Interface): full.Interface = {
    val module = Module(new AxiWriteBuffer(slave.cfg))
    slave :=> module.s_axi
    module.m_axi
  }
}
