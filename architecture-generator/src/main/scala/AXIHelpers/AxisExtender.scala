package AXIHelpers

import chisel3._
import chisel3.util._
import chext.amba.axi4s



class AxisExtender(
  axis_slave_cfg: axi4s.Config,
  master_data_width: Int,
  extend_byte_value: Int
) extends Module {

  val axis_master_cfg = axi4s.Config(
    wData = master_data_width,
    wDest = axis_slave_cfg.wDest
  )

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

  // Extend the data with the value of extend_byte_value
  io.m_axis.TDATA := Cat(
    Fill(master_data_width - io.s_axis.TDATA.getWidth, extend_byte_value.U),
    io.s_axis.TDATA
  )
}

// object AxisExtender {
//   def apply(
//     source: axi4s.Slave,
//     axis_slave_cfg: axi4s.Config,
//     master_data_width: Int,
//     extend_byte_value: Int
//   ): axi4s.Master = {
//     val ext = new AxisExtender(axis_slave_cfg, master_data_width, extend_byte_value)
//     ext.io.s_axis <> source
//     ext.io.m_axis
//   }
// }

