package AXIHelpers

// Not sure we really need this module

import chisel3._
import chext.amba.axi4s

class AxisReducer(
  axis_slave_cfg: axi4s.Config,
  axis_master_cfg: axi4s.Config
) extends Module {

  val io = IO(new Bundle {
    val s_axis = axi4s.Slave(axis_slave_cfg)
    val m_axis = axi4s.Master(axis_master_cfg)
  })

  // Forward TVALID, TREADY, TSTRB, TKEEP, TDEST, TLAST
  io.m_axis.TVALID := io.s_axis.TVALID
  io.s_axis.TREADY := io.m_axis.TREADY
  io.m_axis.TSTRB.get := io.s_axis.TSTRB.get
  io.m_axis.TKEEP.get := io.s_axis.TKEEP.get
  io.m_axis.TDEST.get := io.s_axis.TDEST.get

  // Reduce the data
  io.m_axis.TDATA := io.s_axis.TDATA

}