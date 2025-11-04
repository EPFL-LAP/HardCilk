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
    override val taskWidth: Int,
    queueDepth: Int,
    peCount: Int,
    override val argRouteServersNumber: Int,
    contCounterWidth: Int,
    pePortWidth: Int,
    cutCount: Int,
    multiDecrease: Boolean,
    override val mfpgaSupport: Boolean,
    debug: Boolean = false,
    override val axisCfgTaskAndReq: axi4s.Config = axi4s.Config(wData = 512, wDest = 4) 
) extends Module with  NotifierHasMfpgaSupport {

  print(f"ArgumentNotifier: addrWidth: ${addrWidth} \n")
  print(f"ArgumentNotifier: pePortWidth: ${pePortWidth} \n")

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
      cutCount = cutCount,
      multiDecrease = multiDecrease
    )
  )

  val serversList = List.tabulate(argRouteServersNumber)(n => n)
  val argRouteServers = serversList.zipWithIndex.map { case (tag, index) =>
    Module(
      new ArgumentServerMfpgaWrapper(
        taskWidth = taskWidth,
        counterWidth = contCounterWidth,
        sysAddressWidth = addrWidth,
        tagBitsShift = log2Ceil(taskWidth / 8),
        wId = 2,
        multiDecrease = multiDecrease,
        mfpgaSupport = mfpgaSupport
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
    axis_stream_converters_in(i).io.dataOut.asLite <> argSide.io.connPE(i)
    io_export.argIn(i).asLite <> axis_stream_converters_in(i).io.dataIn.asLite
  }

  // DEBUG
  if(debug) {
    val argInCounter = Module(new Counter64(peCount))
    for (i <- 0 until peCount) {
      argInCounter.io.signals(i) := (io_export.argIn(i).asLite.valid & io_export.argIn(i).asLite.ready)
    }
    dontTouch(argInCounter.io.counter)
  }
  // DEBUG

  if(mfpgaSupport){
    buildMfpgaConnections()
  }
}

// Create an emitter for the ArgumentNotifier module
object ArgumentNotifierEmitter extends App {
  import _root_.circt.stage.ChiselStage

  ChiselStage.emitSystemVerilogFile(
    new ArgumentNotifier(
      addrWidth = 64,
      taskWidth = 256,
      queueDepth = 32,
      peCount = 2,
      argRouteServersNumber = 1,
      contCounterWidth = 32,
      pePortWidth = 64,
      cutCount = 2,
      multiDecrease = false,
      mfpgaSupport = true
    ),
    Array(
      "--target-dir=output/mfPGA-scheduler/"
    ),
    Array("--disable-all-randomization")
  )
}

