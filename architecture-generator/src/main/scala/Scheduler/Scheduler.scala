package Scheduler

import chisel3._

import scala.math._
import AXIHelpers._
import Util._

import chext.amba.axi4
import chext.amba.axi4s

import axi4.Ops._
import axi4s.Casts._

import axi4.full.components._

class SchedulerPEIO(
    val pePortWidth: Int,
    val peCount: Int,
    val spawnsItself: Boolean,
    val peCountGlobalTaskIn: Int
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
    val vssCount: Int,
    val axiMgmtCfg: axi4.Config,
    val addrWidth: Int,
    val taskWidth: Int,
    val vssAxiFullCfg: axi4.Config
) extends Bundle {
  val vss_axi_full = axi4.full.Master(vssAxiFullCfg)
  val axi_mgmt_vss = Vec(vssCount, axi4.lite.Slave(axiMgmtCfg))
}

class Scheduler(
    val addrWidth: Int,
    val taskWidth: Int,
    val queueDepth: Int,
    val peCount: Int,
    val virtualAddressServersNumber: Int,
    val spawnsItself: Boolean,
    val peCountGlobalTaskIn: Int,
    val argRouteServersNumber: Int,
    val pePortWidth: Int,
    val peType: String
) extends Module {

  val successiveNetworkConfig =
    if ((peCountGlobalTaskIn + argRouteServersNumber) > 0) true else false

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
        ignoreRequestSignals = successiveNetworkConfig
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
      vssAxiFullCfg = vssAxiFullCfg
    )
  )

  val mux = Module(
    new Mux(
      axiCfgSlave = vssAxiFullCfg,
      numSlaves = virtualAddressServersNumber,
      muxCfg = MuxConfig(slaveBuffers = axi4.BufferConfig.all(1))
    )
  )
  mux.m_axi :=> io_internal.vss_axi_full

  // DEBUG

  private val rCycleCounter = RegInit(0.U(128.W))
  rCycleCounter := rCycleCounter + 1.U
  private val timeStamp = rCycleCounter

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

  for (i <- 0 until virtualAddressServersNumber) {
    vssRvm(i).io.read.get.address <> virtualStealServers(i).io.read_address
    vssRvm(i).io.read.get.data <> virtualStealServers(i).io.read_data
    vssRvm(i).io.write.get.address <> virtualStealServers(i).io.write_address
    vssRvm(i).io.write.get.data <> virtualStealServers(i).io.write_data
    vssRvm(i).io.readBurst.get.len := virtualStealServers(i).io.read_burst_len
    vssRvm(i).io.writeBurst.get.len := virtualStealServers(i).io.write_burst_len
    vssRvm(i).io.writeBurst.get.last := virtualStealServers(i).io.write_last
    AxiWriteBuffer(vssRvm(i).axi) :=> mux.s_axi(i)
    virtualStealServers(i).io.connNetwork <> stealNW_TQ.io.connVSS(i)

    // DEBUG
    when(vssRvm(i).axi.w.fire) {
      printf(
        f"[TASK] %%d $peType VssAxiFull_w $i: %%x\n",
        timeStamp,
        vssRvm(i).axi.w.bits.data.asUInt
      )
    }
    when(vssRvm(i).axi.r.fire) {
      printf(
        f"[TASK] %%d $peType VssAxiFull_r $i: %%x\n",
        timeStamp,
        vssRvm(i).axi.r.bits.data.asUInt
      )
    }
    // DEBUG

  }

  // DEBUG
  val virtualStealServerTakeInCounter = Module(
    new Counter64(virtualAddressServersNumber)
  );
  for (i <- 0 until virtualAddressServersNumber) {
    virtualStealServerTakeInCounter.io.signals(i) := (virtualStealServers(
      i
    ).io.connNetwork.data.availableTask.fire)
  }
  dontTouch(virtualStealServerTakeInCounter.io.counter)

  val virtualStealServerGiveOutCounter = Module(
    new Counter64(virtualAddressServersNumber)
  );
  for (i <- 0 until virtualAddressServersNumber) {
    virtualStealServerGiveOutCounter.io.signals(i) := (virtualStealServers(
      i
    ).io.connNetwork.data.qOutTask.fire)
  }
  dontTouch(virtualStealServerGiveOutCounter.io.counter)

  for (i <- 0 until virtualAddressServersNumber) {
    when(virtualStealServers(i).io.connNetwork.data.qOutTask.fire) {
      printf(
        f"[TASK] %%d $peType VssTaskIn $i: %%x\n",
        timeStamp,
        virtualStealServers(i).io.connNetwork.data.qOutTask.bits.asUInt
      )
    }
    when(virtualStealServers(i).io.connNetwork.data.availableTask.fire) {
      printf(
        f"[TASK] %%d $peType VssTaskOut $i: %%x\n",
        timeStamp,
        virtualStealServers(i).io.connNetwork.data.availableTask.bits.asUInt
      )
    }
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
  if (spawnsItself) {
    val spawnTaskCounter = Module(new Counter64(peCount));
    for (i <- 0 until peCount) {
      spawnTaskCounter.io.signals(i) := (io_export.taskIn.get(i).lite.fire)
      when(io_export.taskIn.get(i).lite.fire) {
        printf(
          f"[TASK] %%d $peType TaskIn $i: %%x\n",
          timeStamp,
          io_export.taskIn.get(i).lite.bits.asUInt
        )
      }
    }
  }

  val getExecuteTaskCounter = Module(new Counter64(peCount));
  for (i <- 0 until peCount) {
    getExecuteTaskCounter.io.signals(i) := (io_export.taskOut(i).lite.fire)
    when(io_export.taskOut(i).lite.fire) {
      printf(
        f"[TASK] %%d $peType TaskOut $i: %%x\n",
        timeStamp,
        io_export.taskOut(i).lite.bits.asUInt
      )
    }
  }
  // DEBUG

  dontTouch(getExecuteTaskCounter.io.counter)
  dontTouch(virtualStealServerTakeInCounter.io.counter)

  if (argRouteServersNumber > 0) {
    for (i <- 0 until argRouteServersNumber) {
      stealNW_TQ.io.connVAS(i) <> connArgumentNotifier(i)
    }
    // DEBUG
    val argRouteTaskInCounter = Module(new Counter64(argRouteServersNumber))
    for (i <- 0 until argRouteServersNumber) {
      argRouteTaskInCounter.io.signals(i) := (connArgumentNotifier(
        i
      ).data.qOutTask.fire)
    }

    for (i <- 0 until argRouteServersNumber) {
      when(connArgumentNotifier(i).data.qOutTask.fire) {
        printf(
          f"[TASK] %%d $peType ArgRouteTaskIn $i: %%x\n",
          timeStamp,
          connArgumentNotifier(i).data.qOutTask.bits.asUInt
        )
      }
    }

    dontTouch(argRouteTaskInCounter.io.counter)
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
    val globalTaskInCounter = Module(new Counter64(peCountGlobalTaskIn))
    for (i <- 0 until peCountGlobalTaskIn) {
      globalTaskInCounter.io
        .signals(i) := (io_export.taskInGlobal.get(i).lite.fire)
    }

    for (i <- 0 until peCountGlobalTaskIn) {
      when(io_export.taskInGlobal.get(i).lite.fire) {
        printf(
          f"[TASK] %%d $peType GlobalTaskIn $i: %%x\n",
          timeStamp,
          io_export.taskInGlobal.get(i).lite.bits.asUInt
        )
      }
    }

    dontTouch(globalTaskInCounter.io.counter)
    // DEBUG

  }
}
