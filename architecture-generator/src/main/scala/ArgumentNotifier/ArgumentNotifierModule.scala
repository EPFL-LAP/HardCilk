package ArgumentNotifier

import chisel3._
import chext.amba.axi4
import chext.amba.axi4s
import Util.SchedulerNetworkClientIO

trait ArgumentNotifierModule extends Module {
  val taskWidth: Int
  val argRouteServersNumber: Int
  val mfpgaSupport: Boolean
  val axisCfgTaskAndReq: axi4s.Config
  val io_export: ArgumentNotifierIO
  val connStealNtw: Vec[SchedulerNetworkClientIO]
  val axi_full_argRoute: Vec[axi4.full.Interface]
  val m_axis_remote: Option[axi4s.Interface]
  val s_axis_remote: Option[axi4s.Interface]
  val fpgaIndexInputReg: Option[UInt]
  val s_axis_mfgpa_argument_notifier: Option[Seq[axi4.lite.Interface]]
}
