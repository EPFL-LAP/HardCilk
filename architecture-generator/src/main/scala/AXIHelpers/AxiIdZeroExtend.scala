package AXIHelpers

import chisel3._

import chext.elastic.ConnectOp._

import chext.amba.axi4
import chext.amba.axi4.Ops._

// (Bradley) This file written by AI
/** Zero-extends the AXI id width from ``cfgSlave.wId`` to ``targetWId``.
  *
  * This is effectively free in hardware: the ``:=>`` operator pads the narrower
  * id with zeros on the AR/AW channels and truncates the response id on the R/B
  * channels (safe because the upper bits were zero to begin with).
  *
  * Follows the same pattern as [[AxiUserYanker]]: a thin config-adapter that
  * lets two AXI interfaces with slightly different configs share a port/mux
  * without instantiating the expensive ProtocolConverter.
  */
class AxiIdZeroExtend(
    val cfgSlave: axi4.Config,
    val targetWId: Int
) extends Module {
  suggestName("AxiIdZeroExtend")
  require(
    targetWId >= cfgSlave.wId,
    s"AxiIdZeroExtend: targetWId ($targetWId) must be >= source wId (${cfgSlave.wId})"
  )

  val cfgMaster = cfgSlave.copy(wId = targetWId)

  val s_axi = IO(axi4.full.Slave(cfgSlave))
  val m_axi = IO(axi4.full.Master(cfgMaster))

  if (cfgSlave.read) {
    s_axi.ar :=> m_axi.ar
    m_axi.r :=> s_axi.r
  }

  if (cfgSlave.write) {
    s_axi.aw :=> m_axi.aw
    s_axi.w :=> m_axi.w
    m_axi.b :=> s_axi.b
  }
}

object AxiIdZeroExtend {

  /** Returns ``s_axi`` unchanged if its id width already matches ``targetWId``,
    * otherwise instantiates a zero-extend adapter.
    */
  def apply(s_axi: axi4.full.Interface, targetWId: Int): axi4.full.Interface = {
    if (s_axi.cfg.wId >= targetWId) return s_axi
    val ext = Module(new AxiIdZeroExtend(s_axi.cfg, targetWId))
    s_axi :=> ext.s_axi
    ext.m_axi
  }
}
