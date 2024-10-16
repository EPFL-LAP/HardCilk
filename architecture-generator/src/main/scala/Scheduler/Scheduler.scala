package Scheduler

import chisel3._
import chisel3.util._
import scala.math._

import AXIHelpers._
import Util._

import chext.amba.axi4
import chext.amba.axi4s

import axi4.Ops._
import axi4s.Casts._

import axi4.full.components._

class SchedulerPEIO(
    pePortWidth: Int,
    peCount: Int,
    spawnsItself: Boolean,
    peCountGlobalTaskIn: Int
) extends Bundle {

  implicit val axisCfgTask: axi4s.Config =
    axi4s.Config(wData = pePortWidth, onlyRV = true)

  val taskOut = Vec(peCount, axi4s.Master(axisCfgTask))

  val taskIn =
    if (spawnsItself) Some(Vec(peCount, axi4s.Slave(axisCfgTask))) else None
  val taskInGlobal =
    if (peCountGlobalTaskIn > 0)
      Some(Vec(peCountGlobalTaskIn, axi4s.Slave(axisCfgTask)))
    else None

  // a getter function for the port with name and index
  def getPort(name: String, index: Int): axi4s.Interface = {
    name match {
      case "taskOut"      => taskOut(index)
      case "taskIn"       => taskIn.get(index)
      case "taskInGlobal" => taskInGlobal.get(index)
    }
  }
}

class SchedulerAxiIO(
    vssCount: Int,
    axiMgmtCfg: axi4.Config,
    addrWidth: Int,
    taskWidth: Int,
    vssAxiFullCfg: axi4.Config,
    reduceAxi: Boolean
) extends Bundle {
  val nAxiPorts = if (reduceAxi) 1 else vssCount
  private val wId = if (reduceAxi) log2Ceil(vssCount) else 0

  val vss_axi_full = Vec(nAxiPorts, axi4.full.Master(vssAxiFullCfg.copy(wId = vssAxiFullCfg.wId + wId)))
  val axi_mgmt_vss = Vec(vssCount, axi4.lite.Slave(axiMgmtCfg))
}

class Scheduler(
    addrWidth: Int,
    taskWidth: Int,
    queueDepth: Int,
    peCount: Int,
    virtualAddressServersNumber: Int,
    spawnsItself: Boolean,
    peCountGlobalTaskIn: Int,
    argRouteServersNumber: Int,
    pePortWidth: Int,
    peType: String,
    reduceAxi: Boolean,
    debug: Boolean
) extends Module {

  val successiveNetworkConfig = (peCountGlobalTaskIn + argRouteServersNumber) > 0

  val vssAxiFullCfg = axi4.Config(
    wAddr = addrWidth,
    wData = taskWidth,
    lite = false,
    wId = 1
  )

  val stealNW_TQ = Module(
    new SchedulerLocalNetwork(
      peCount = peCount,
      vssCount = virtualAddressServersNumber,
      vasCount = argRouteServersNumber + peCountGlobalTaskIn,
      taskWidth = taskWidth,
      queueMaxLength = queueDepth,
      qRamReadLatency = 1,
      qRamWriteLatency = 1,
      spawnsItself = spawnsItself,
      successiveNetworkConfig = successiveNetworkConfig
    )
  )

  val contentionThreshold_ = (max((peCount + argRouteServersNumber + peCountGlobalTaskIn) / 1.2, 1)).toInt
  val contentionDelta_ = if (contentionThreshold_ > 4) 1 else 0

  val virtualStealServers = Seq.fill(virtualAddressServersNumber)(
    Module(
      new SchedulerServer(
        taskWidth = taskWidth,
        contentionThreshold = contentionThreshold_,
        peCount = peCount,
        contentionDelta = contentionDelta_,
        vasCount = argRouteServersNumber + peCountGlobalTaskIn,
        sysAddressWidth = addrWidth,
        ignoreRequestSignals = successiveNetworkConfig,
        nBeats = 16
      )
    )
  )

  val io_export = IO(
    new SchedulerPEIO(
      pePortWidth = pePortWidth,
      peCount = peCount,
      spawnsItself = spawnsItself,
      peCountGlobalTaskIn = peCountGlobalTaskIn
    )
  )

  val io_internal = IO(
    new SchedulerAxiIO(
      addrWidth = addrWidth,
      taskWidth = taskWidth,
      vssCount = virtualAddressServersNumber,
      axiMgmtCfg = virtualStealServers(0).regBlock.cfgAxi,
      vssAxiFullCfg = vssAxiFullCfg,
      reduceAxi = reduceAxi
    )
  )

  // DEBUG
  private val rCycleCounter = RegInit(0.U(128.W))
  rCycleCounter := rCycleCounter + 1.U

  private def logTask(name: String, idx: Int, data: UInt): Unit = {
    printf(
      f"[TASK] %%d $peType $name $idx: %%x\n",
      rCycleCounter,
      data
    )
  }
  // DEBUG

  val connArgumentNotifier = IO(
    Vec(argRouteServersNumber, new SchedulerNetworkClientIO(taskWidth))
  )

  for (i <- 0 until virtualAddressServersNumber) {
    io_internal.axi_mgmt_vss(i) :=> virtualStealServers(i).io.axi_mgmt
    virtualStealServers(i).io.ntwDataUnitOccupancy <> stealNW_TQ.io
      .ntwDataUnitOccupancyVSS(i)
  }

  val vssRvm = Seq.fill(virtualAddressServersNumber)(
    Module(new RVtoAXIBridge(taskWidth, addrWidth, varBurst = true))
  )

  val axiFullPorts = vssRvm.map(_.axi)

  for (i <- 0 until virtualAddressServersNumber) {
    vssRvm(i).io.read.get.address <> virtualStealServers(i).io.read_address
    vssRvm(i).io.read.get.data <> virtualStealServers(i).io.read_data
    vssRvm(i).io.write.get.address <> virtualStealServers(i).io.write_address
    vssRvm(i).io.write.get.data <> virtualStealServers(i).io.write_data
    vssRvm(i).io.readBurst.get.len := virtualStealServers(i).io.read_burst_len
    vssRvm(i).io.writeBurst.get.len := virtualStealServers(i).io.write_burst_len
    vssRvm(i).io.writeBurst.get.last := virtualStealServers(i).io.write_last
    virtualStealServers(i).io.connNetwork <> stealNW_TQ.io.connVSS(i)

    // DEBUG
    if (debug) {
      when(vssRvm(i).axi.w.fire) {
        logTask("VssAxiFull_w", i, vssRvm(i).axi.w.bits.data.asUInt)
      }
      when(vssRvm(i).axi.r.fire) {
        logTask("VssAxiFull_r", i, vssRvm(i).axi.r.bits.data.asUInt)
      }
    }
    // DEBUG
  }

  if (reduceAxi) {
    val mux = Module(
      new Mux( new MuxConfig(
        axiSlaveCfg = vssAxiFullCfg,
        numSlaves = virtualAddressServersNumber,
        slaveBuffers = axi4.BufferConfig.all(1))
      )
    )
    mux.m_axi :=> io_internal.vss_axi_full(0)
    axiFullPorts.zip(mux.s_axi).foreach { case (vss, s_axi) => AxiWriteBuffer(vss) :=> s_axi }
  } else {
    axiFullPorts.zip(io_internal.vss_axi_full).foreach { case (vss, s_axi) => AxiWriteBuffer(vss) :=> s_axi }
  }

  // DEBUG
  if (debug) {
    val virtualStealServerTakeInCounter = Module(
      new Counter64(virtualAddressServersNumber)
    )

    for (i <- 0 until virtualAddressServersNumber) {
      virtualStealServerTakeInCounter.io.signals(i) :=
        virtualStealServers(i).io.connNetwork.data.availableTask.fire
      when(virtualStealServers(i).io.connNetwork.data.availableTask.fire) {
        logTask(
          "VssTaskOut",
          i,
          virtualStealServers(i).io.connNetwork.data.availableTask.bits.asUInt
        )
      }
    }
    dontTouch(virtualStealServerTakeInCounter.io.counter)

    val virtualStealServerGiveOutCounter = Module(
      new Counter64(virtualAddressServersNumber)
    )

    for (i <- 0 until virtualAddressServersNumber) {
      virtualStealServerGiveOutCounter.io.signals(i) :=
        virtualStealServers(i).io.connNetwork.data.qOutTask.fire
      when(virtualStealServers(i).io.connNetwork.data.qOutTask.fire) {
        logTask(
          "VssTaskIn",
          i,
          virtualStealServers(i).io.connNetwork.data.qOutTask.bits.asUInt
        )
      }
    }
    dontTouch(virtualStealServerGiveOutCounter.io.counter)
  }
  // DEBUG

  // If taskWidth == pePortWidth, the converter is created as just a wire.
  val axis_stream_converters_out =
    Seq.fill(peCount)(Module(new AxisDataWidthConverter(taskWidth, pePortWidth)))
  val axis_stream_converters_in =
    if (spawnsItself)
      Some(
        Seq.fill(peCount)(Module(new AxisDataWidthConverter(pePortWidth, taskWidth)))
      )
    else None
  for (i <- 0 until peCount) {
    axis_stream_converters_out(i).io.dataIn.lite <> stealNW_TQ.io.connPE(i).pop
    io_export.taskOut(i).lite <> axis_stream_converters_out(i).io.dataOut.lite
    if (spawnsItself) {
      axis_stream_converters_in.get(i).io.dataIn.lite <> io_export.taskIn
        .get(i)
        .lite
      stealNW_TQ.io
        .connPE(i)
        .push <> axis_stream_converters_in.get(i).io.dataOut.lite
    } else {
      stealNW_TQ.io.connPE(i).push.valid := false.B
      stealNW_TQ.io.connPE(i).push.bits := DontCare
    }
  }

  // DEBUG
  if (debug) {
    if (spawnsItself) {
      val spawnTaskCounter = Module(new Counter64(peCount));
      for (i <- 0 until peCount) {
        spawnTaskCounter.io.signals(i) := (io_export.taskIn.get(i).lite.fire)
        when(io_export.taskIn.get(i).lite.fire) {
          logTask("TaskIn", i, io_export.taskIn.get(i).lite.bits.asUInt)
        }
      }
    }

    val getExecuteTaskCounter = Module(new Counter64(peCount));
    for (i <- 0 until peCount) {
      getExecuteTaskCounter.io.signals(i) := (io_export.taskOut(i).lite.fire)
      when(io_export.taskOut(i).lite.fire) {
        logTask("TaskOut", i, io_export.taskOut(i).lite.bits.asUInt)
      }
    }

    dontTouch(getExecuteTaskCounter.io.counter)
  }
  // DEBUG

  if (argRouteServersNumber > 0) {
    for (i <- 0 until argRouteServersNumber) {
      stealNW_TQ.io.connVAS(i) <> connArgumentNotifier(i)
    }

    // DEBUG
    if (debug) {
      val argRouteTaskInCounter = Module(new Counter64(argRouteServersNumber))
      for (i <- 0 until argRouteServersNumber) {
        argRouteTaskInCounter.io.signals(i) :=
          connArgumentNotifier(i).data.qOutTask.fire
        when(connArgumentNotifier(i).data.qOutTask.fire) {
          logTask("ArgRouteTaskIn", i, connArgumentNotifier(i).data.qOutTask.bits.asUInt)
        }
      }
      dontTouch(argRouteTaskInCounter.io.counter)
    }
    // DEBUG
  }

  if (peCountGlobalTaskIn > 0) {
    val axis_stream_converters_in_global = Seq.fill(peCountGlobalTaskIn)(
      Module(new AxisDataWidthConverter(pePortWidth, taskWidth))
    )
    val globalsTaskBuffers = Seq.fill(peCountGlobalTaskIn)(
      Module(new GlobalTaskBuffer(taskWidth, peCount))
    )

    for (i <- argRouteServersNumber until (argRouteServersNumber + peCountGlobalTaskIn)) {
      axis_stream_converters_in_global(
        i - argRouteServersNumber
      ).io.dataIn.lite <> io_export.taskInGlobal
        .get(i - argRouteServersNumber)
        .lite
      globalsTaskBuffers(
        i - argRouteServersNumber
      ).io.in <> axis_stream_converters_in_global(
        i - argRouteServersNumber
      ).io.dataOut.lite
      stealNW_TQ.io.connVAS(i) <> globalsTaskBuffers(
        i - argRouteServersNumber
      ).io.connStealNtw
    }

    // DEBUG
    if (debug) {
      val globalTaskInCounter = Module(new Counter64(peCountGlobalTaskIn))
      for (i <- 0 until peCountGlobalTaskIn) {
        globalTaskInCounter.io.signals(i) :=
          io_export.taskInGlobal.get(i).lite.fire
        when(io_export.taskInGlobal.get(i).lite.fire) {
          logTask("GlobalTaskIn", i, io_export.taskInGlobal.get(i).lite.bits.asUInt)
        }
      }
      dontTouch(globalTaskInCounter.io.counter)
    }
    // DEBUG

  }
}
