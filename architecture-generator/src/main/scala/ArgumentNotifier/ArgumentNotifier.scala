package ArgumentNotifier

import chisel3._
import chisel3.util._

import ArgumentNotifier._
import Util._

import chext.amba.axi4
import chext.amba.axi4s

import axi4.Ops._

import axi4s.Casts._
import AXIHelpers.AxisDataWidthConverter
import AXIHelpers.AxiWriteBuffer

class ArgumentNotifierIO(
    pePortWidth: Int,
    peCount: Int
) extends Bundle {

  implicit val axisCfgAddress: axi4s.Config = axi4s.Config(wData = pePortWidth, onlyRV = true)

  val argIn = Vec(peCount, axi4s.Slave(axisCfgAddress))
  val done = Output(Bool())

  // a getter function for the port with name and index
  def getPort(name: String, index: Int): axi4s.Interface = {
    name match {
      case "argIn" => argIn(index)
    }
  }
}

class ArgumentNotifier(
    addrWidth: Int,
    taskWidth: Int,
    queueDepth: Int,
    peCount: Int,
    argRouteServersNumber: Int,
    contCounterWidth: Int,
    pePortWidth: Int,
    cutCount: Int
) extends Module {

  val io_export = IO(new ArgumentNotifierIO(pePortWidth = pePortWidth, peCount = peCount))
  val connStealNtw = IO(Vec(argRouteServersNumber, Flipped(new SchedulerNetworkClientIO(taskWidth))))

  assert(argRouteServersNumber > 0)

  val argSide = Module(
    new ArgumentNotifierNetwork(
      addrWidth = addrWidth,
      taskWidth = taskWidth,
      peCount = peCount,
      vasNum = argRouteServersNumber,
      queueDepth = queueDepth,
      cutCount = cutCount
    )
  )

  val serversList = List.tabulate(argRouteServersNumber)(n => n)
  val argRouteServers = serversList.zipWithIndex.map { case (tag, index) =>
    Module(
      new ArgumentServer(
        taskWidth = taskWidth,
        counterWidth = contCounterWidth,
        sysAddressWidth = addrWidth,
        tagBitsShift = log2Ceil(taskWidth / 8),
        wId = 2
      )
    )
  }

  io_export.done := argRouteServers.map(_.io.done).reduce(_ || _)

  val nAxiPorts = 2 * argRouteServersNumber
  val axi_full_argRoute = IO(Vec(nAxiPorts, axi4.full.Master(argRouteServers.head.io.m_axi_counter.cfg)))

  for (i <- 0 until argRouteServersNumber) {
    argRouteServers(i).io.connNetwork <> argSide.io.connVAS(i)
    argRouteServers(i).io.connStealNtw <> connStealNtw(i)
  }

  for (i <- 0 until argRouteServersNumber) {
    argRouteServers(i).io.m_axi_counter :=> axi_full_argRoute(i)
    argRouteServers(i).io.m_axi_task :=> axi_full_argRoute(i + argRouteServersNumber)
  }

  val axis_stream_converters_in =
    Seq.fill(peCount)(Module(new AxisDataWidthConverter(dataWidthIn = pePortWidth, dataWidthOut = addrWidth)))
  for (i <- 0 until peCount) {
    axis_stream_converters_in(i).io.dataOut.lite <> argSide.io.connPE(i)
    io_export.argIn(i).lite <> axis_stream_converters_in(i).io.dataIn.lite
  }

  // DEBUG
  val argInCounter = Module(new Counter64(peCount))
  for (i <- 0 until peCount) {
    argInCounter.io.signals(i) := (io_export.argIn(i).lite.valid & io_export.argIn(i).lite.ready)
  }
  dontTouch(argInCounter.io.counter)
  // DEBUG

}
