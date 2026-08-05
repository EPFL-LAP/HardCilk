package Scheduler

import chisel3._
import chext.amba.axi4
import chext.amba.axi4s
import Util.SchedulerNetworkClientIO

/** Stable top-level scheduler surface shared by the updated and commit-2469686
  * implementations. The legacy implementation deliberately supports the
  * single-FPGA datapath only; generator validation rejects legacy global-start
  * and legacy mFPGA before elaboration.
  */
trait SchedulerModule extends Module {
  val taskWidth: Int
  val peCount: Int
  val schedulerServersNumber: Int
  val spawnerServerNumber: Int
  val taskId: Int
  val mfpgaSupport: Boolean
  val axisCfgTaskAndReq: axi4s.Config

  val io_export: SchedulerPEIO
  val io_internal: SchedulerAxiIO
  val io_paused: Bool
  val io_congested: Vec[Bool]
  val connArgumentNotifier: Vec[SchedulerNetworkClientIO]
  val io_globalRun: Option[Bool]

  val fpgaCountInputReg: Option[UInt]
  val fpgaIndexInputReg: Option[UInt]
  val numberOfTasksToMove: Option[UInt]
  val m_axis_remote: Option[axi4s.Interface]
  val s_axis_remote: Option[axi4s.Interface]
  val s_axi_remote_task_server: Option[axi4.lite.Interface]

  /** Historical schedulers expose HBM-backed spawners; updated schedulers keep
    * these empty because their spawner queues are on chip.
    */
  def legacySpawnerMgmt: Seq[axi4.lite.Interface] = Seq.empty
  def legacySpawnerAxi: Seq[axi4.full.Interface] = Seq.empty
}
