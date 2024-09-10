package ArgumentNotifier

import chisel3._
import chisel3.util._

import ArgumentNotifier._
import Util._

import chext.amba.axi4
import chext.amba.axi4s

import axi4.Ops._
import chext.amba.axi4.full.components._

import axi4s.Casts._
import AXIHelpers.AxisDataWidthConverter

class ArgumentNotifierIO(
    val pePortWidth: Int,
    val peCount: Int
) extends Bundle {

  implicit val axisCfgAddress: axi4s.Config = axi4s.Config(wData = pePortWidth, onlyRV = true)

  val argIn = Vec(peCount, axi4s.Slave(axisCfgAddress))

  // a getter function for the port with name and index
  def getPort(name: String, index: Int): axi4s.Interface = {
    name match {
      case "argIn" => argIn(index)
    }
  }
}

class ArgumentNotifier(
    val addrWidth: Int,
    val taskWidth: Int,
    val queueDepth: Int,
    val peCount: Int,
    val argRouteServersNumber: Int,
    val contCounterWidth: Int,
    val pePortWidth: Int
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
      queueDepth = queueDepth
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
        noContinuations = false
      )
    )
  }

  val argRouteRvm = Seq.fill(argRouteServersNumber)(Module(new RVtoAXIBridge(contCounterWidth, addrWidth)))
  val argRouteRvmReadOnly = Seq.fill(argRouteServersNumber)(
    Module(new RVtoAXIBridge(contCounterWidth, addrWidth, write = false, burstLength = (taskWidth / contCounterWidth) - 2))
  )

  val argRouteAxiFullCfg =
    axi4.Config(wAddr = addrWidth, wData = contCounterWidth, lite = false, wId = log2Ceil(2 * argRouteServersNumber))

  val axi_full_argRoute = IO(axi4.full.Master(argRouteAxiFullCfg)).suggestName("axi_full_argRoute")

  val axiCfgSlave = axi4.Config(wAddr = addrWidth, wData = contCounterWidth, lite = false, wId = 0)

  val mux = Module(
    new Mux(
      axiCfgSlave = axiCfgSlave,
      numSlaves = 2 * argRouteServersNumber,
      muxCfg = MuxConfig(slaveBuffers = axi4.BufferConfig.all(2))
    )
  )
  mux.m_axi :=> axi_full_argRoute

  for (i <- 0 until argRouteServersNumber) {
    argRouteRvm(i).axi :=> mux.s_axi(i)
    argRouteRvm(i).io.read.get.address <> argRouteServers(i).io.read_address
    argRouteRvm(i).io.read.get.data <> argRouteServers(i).io.read_data
    argRouteRvm(i).io.write.get.address <> argRouteServers(i).io.write_address
    argRouteRvm(i).io.write.get.data <> argRouteServers(i).io.write_data
    argRouteServers(i).io.connNetwork <> argSide.io.connVAS(i)
    argRouteServers(i).io.connStealNtw <> connStealNtw(i)

    argRouteRvmReadOnly(i).axi :=> mux.s_axi(i + argRouteServersNumber)
    argRouteRvmReadOnly(i).io.read.get.address <> argRouteServers(i).io.read_address_task
    argRouteRvmReadOnly(i).io.read.get.data <> argRouteServers(i).io.read_data_task
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
