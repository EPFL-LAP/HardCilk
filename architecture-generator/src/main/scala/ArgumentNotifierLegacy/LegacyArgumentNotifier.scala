package ArgumentNotifierLegacy

import chisel3._
import chisel3.util._
import Util._
import ArgumentNotifier.{ArgumentNotifierIO, ArgumentNotifierModule}
import chext.amba.axi4
import chext.amba.axi4s
import axi4.Ops._
import axi4s.Casts._
import AXIHelpers.AxisDataWidthConverter

/** Historical no-cache notifier from commit 2469686. */
class LegacyArgumentNotifier(
    addrWidth: Int,
    override val taskWidth: Int,
    queueDepth: Int,
    peCount: Int,
    override val argRouteServersNumber: Int,
    contCounterWidth: Int,
    pePortWidth: Int,
    cutCount: Int,
    multiDecrease: Boolean,
    taskID: Int,
    override val mfpgaSupport: Boolean,
    debug: Boolean = false,
    override val axisCfgTaskAndReq: axi4s.Config =
      axi4s.Config(wData = 512, wDest = 4)
) extends Module with ArgumentNotifierModule {
  require(!mfpgaSupport,
    "legacy architecture from 2469686 does not support the current mFPGA adapter")
  val m_axis_remote: Option[axi4s.Interface] = None
  val s_axis_remote: Option[axi4s.Interface] = None
  val fpgaIndexInputReg: Option[UInt] = None
  val s_axis_mfgpa_argument_notifier: Option[Seq[axi4.lite.Interface]] = None

  val io_export = IO(new ArgumentNotifierIO(pePortWidth, peCount))
  val connStealNtw = IO(Vec(
    argRouteServersNumber,
    Flipped(new SchedulerNetworkClientIO(taskWidth))
  ))
  require(argRouteServersNumber > 0)

  val argSide = Module(new ArgumentNotifierNetwork(
    addrWidth, taskWidth, peCount, argRouteServersNumber, queueDepth,
    cutCount, multiDecrease
  ))
  val argRouteServers = Seq.fill(argRouteServersNumber)(Module(
    new ArgumentServer(
      taskWidth, contCounterWidth, addrWidth, log2Ceil(taskWidth / 8),
      wId = 2, multiDecrease = multiDecrease
    )
  ))
  io_export.done := argRouteServers.map(_.io.done).reduce(_ || _)

  val axi_full_argRoute = IO(Vec(
    2 * argRouteServersNumber,
    axi4.full.Master(argRouteServers.head.io.m_axi_counter.cfg)
  ))
  for (i <- 0 until argRouteServersNumber) {
    argRouteServers(i).io.connNetwork <> argSide.io.connVAS(i)
    argRouteServers(i).io.connStealNtw <> connStealNtw(i)
    argRouteServers(i).io.m_axi_counter :=> axi_full_argRoute(i)
    argRouteServers(i).io.m_axi_task :=>
      axi_full_argRoute(i + argRouteServersNumber)
  }

  val converters = Seq.fill(peCount)(Module(
    new AxisDataWidthConverter(pePortWidth, addrWidth)
  ))
  for (i <- 0 until peCount) {
    converters(i).io.dataOut.asLite <> argSide.io.connPE(i)
    io_export.argIn(i).asLite <> converters(i).io.dataIn.asLite
  }

  if (debug) {
    val counter = Module(new Counter64(peCount))
    for (i <- 0 until peCount)
      counter.io.signals(i) := io_export.argIn(i).asLite.fire
    dontTouch(counter.io.counter)
  }
}
