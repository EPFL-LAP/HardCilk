package Scheduler

import chisel3._

import chext.amba.axi4

import Util.SchedulerNetworkClientIO

/** What HardCilk needs from a scheduler, whichever kind it is.
  *
  * There are two: the classic [[Scheduler]], with its task queue, steal network and virtualized
  * scheduler servers, and the streaming [[DataFlowScheduler.DataFlowScheduler]] used by tasks that
  * never spawn themselves. The builder picks between them per task, so everything that wires
  * schedulers up works through this trait rather than a concrete type.
  *
  * The server accessors are the ones the management address map and the HBM interconnect walk. They
  * must return exactly the servers that were instantiated, in the order the address map assigns
  * them, or the host would end up writing to registers that no hardware answers. `Descriptors`
  * computes the same counts (`schedulerServerCount`, `spawnerCount`) and `connectManagement`
  * asserts the two agree.
  *
  * mFPGA-only ports (`s_axi_remote_task_server`, `m_axis_remote`, ...) are deliberately absent: the
  * DataFlowScheduler has no remote steal path, and `FullSysGenDescriptor.validate()` rejects the
  * combination, so those call sites narrow back to [[Scheduler]].
  */
trait HardCilkSchedulerLike { this: Module =>

  /** PE facing task ports. */
  def io_export: SchedulerPEIO

  /** High when the scheduler is waiting for the host to grow one of its virtual queues. */
  def io_paused: Bool

  /** Tasks created by the argument route servers, one port per server. */
  def connArgumentNotifier: Vec[SchedulerNetworkClientIO]

  /** Management slaves of the scheduler servers. Empty for a DataFlowScheduler, which has none. */
  def vssAxiMgmt: Seq[axi4.lite.Interface]

  /** Memory masters of the scheduler servers. Empty for a DataFlowScheduler. */
  def vssAxiFull: Seq[axi4.full.Interface]

  /** Management slaves of the spawner servers, in address-map order. */
  def spawnerAxiMgmt: Seq[axi4.lite.Interface]

  /** Memory masters of the spawner servers. */
  def spawnerAxiFull: Seq[axi4.full.Interface]
}

