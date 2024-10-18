package AXIHelpers

import chisel3._
import chisel3.util._

import chext.elastic.ConnectOp._

import chext.amba.axi4
import chext.amba.axi4.Ops._

class AxiUserYanker(
    val cfgSlave: axi4.Config
) extends Module {
  val cfgMaster = cfgSlave.copy(
    wUserAR = 0,
    wUserR = 0,
    wUserAW = 0,
    wUserW = 0,
    wUserB = 0
  )

  val s_axi = IO(axi4.full.Slave(cfgSlave))
  val m_axi = IO(axi4.full.Master(cfgMaster))

  if (cfgSlave.wUserAR != 0)
    assert(!s_axi.ar.valid || s_axi.ar.bits.user.asUInt === 0.U)
  if (cfgSlave.wUserR != 0)
    assert(!s_axi.r.valid || s_axi.r.bits.user.asUInt === 0.U)
  if (cfgSlave.wUserAW != 0)
    assert(!s_axi.aw.valid || s_axi.aw.bits.user.asUInt === 0.U)
  if (cfgSlave.wUserW != 0)
    assert(!s_axi.w.valid || s_axi.w.bits.user.asUInt === 0.U)
  if (cfgSlave.wUserB != 0)
    assert(!s_axi.b.valid || s_axi.b.bits.user.asUInt === 0.U)

  if(cfgSlave.read) {
    s_axi.ar :=> m_axi.ar
    m_axi.r :=> s_axi.r
  }

  if(cfgSlave.write) {
    s_axi.aw :=> m_axi.aw
    s_axi.w :=> m_axi.w
    m_axi.b :=> s_axi.b
  }
}

object AxiUserYanker {
  def apply(s_axi: axi4.full.Interface): axi4.full.Interface = {
    val yanker = Module(new AxiUserYanker(s_axi.cfg))
    s_axi :=> yanker.s_axi
    yanker.m_axi
  }
}
