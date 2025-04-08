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
import chext.elastic


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
    vssAxiFullCfg: axi4.Config
) extends Bundle {
  val nAxiPorts = vssCount

  val vss_axi_full = Vec(nAxiPorts, axi4.full.Master(vssAxiFullCfg))
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
    debug: Boolean,
    fpgaCount: Int,
    taskIndex: Int,
    spawnerServerNumber: Int = 1,
    collectStats: Boolean = true,
) extends Module {

  val taskIndexV = taskIndex
  val outsideSpawn = (peCountGlobalTaskIn + argRouteServersNumber) > 1

  val vssAxiFullCfg = axi4.Config(
    wAddr = addrWidth,
    wData = taskWidth,
    lite = false,
    wId = 1
  )

  val spawnerServer = if(outsideSpawn) Some(Seq.fill(spawnerServerNumber)(Module(new SpawnerServer(taskWidth)))) else None
  
  val spawnerServerMgmt = if(outsideSpawn) Some(Seq.fill(spawnerServerNumber)(IO(axi4.lite.Slave(spawnerServer.get(0).asInstanceOf[SpawnerServer].regBlock.cfgAxi))) ) else None
  val spawnerServerAXI = if(outsideSpawn) Some(Seq.fill(spawnerServerNumber)(IO(axi4.full.Master(spawnerServer.get(0).asInstanceOf[SpawnerServer].axiCfg))) ) else None
  // val spawnerServerMgmt = if(outsideSpawn) Some(Vec(spawnerServerNumber, IO(axi4.lite.Slave(spawnerServer.get(0).asInstanceOf[SpawnerServer].regBlock.cfgAxi)))) else None
  // val spawnerServerAXI = if(outsideSpawn) Some(Vec(spawnerServerNumber, IO(axi4.full.Master(spawnerServer.get(0).asInstanceOf[SpawnerServer].axiCfg)))) else None

  // Create Array of indicies 0, 1, 2, until spawnerServerNumber
  
  val step = if(outsideSpawn) (peCountGlobalTaskIn + argRouteServersNumber) / spawnerServerNumber else 0
  var spawnerIndicies = Array.tabulate(spawnerServerNumber)(n => (n + n * step))
  var outTaskSpawnIndicies = Array.tabulate(peCountGlobalTaskIn + argRouteServersNumber + spawnerServerNumber)(n => (n))
  // remove spawnServerIndicies from outTaskSpawnIndicies
  outTaskSpawnIndicies = outTaskSpawnIndicies.filterNot(spawnerIndicies.contains(_))

  val getOutsideSpawnNetwork = if(outsideSpawn) Some( Module(new SchedulerNetwork(taskWidth, (peCountGlobalTaskIn + argRouteServersNumber) + spawnerServerNumber, spawnerIndicies))) else None




  if(outsideSpawn){
    // spawnerServer.get.io.axi_mgmt <> spawnerServerMgmt.get
    // spawnerServer.get.io.m_axi.asFull :=> spawnerServerAXI.get
    // spawnerServer.get.io.connNetwork_slave <> getOutsideSpawnNetwork.get.io.connSS(0)

    for(i <- 0 until spawnerServerNumber){
      spawnerServer.get(i).asInstanceOf[SpawnerServer].io.axi_mgmt <> spawnerServerMgmt.get(i)
      spawnerServer.get(i).asInstanceOf[SpawnerServer].io.m_axi.asFull :=> spawnerServerAXI.get(i)
      spawnerServer.get(i).asInstanceOf[SpawnerServer].io.connNetwork_slave <> getOutsideSpawnNetwork.get.io.connSS(spawnerIndicies(i))
    }
  }


  val stealNW_TQ = Module(
    new SchedulerLocalNetwork(
      peCount = peCount,
      vssCount = if (fpgaCount > 1) (virtualAddressServersNumber + 2) else virtualAddressServersNumber,
      vasCount = if(outsideSpawn) spawnerServerNumber else 0,
      taskWidth = taskWidth,
      queueMaxLength = queueDepth,
      qRamReadLatency = 1,
      qRamWriteLatency = 1,
      spawnsItself = spawnsItself,
      successiveNetworkConfig = false
    )
  )
  if(outsideSpawn) {

    for(i <- 0 until spawnerServerNumber){
      spawnerServer.get(i).asInstanceOf[SpawnerServer].io.connNetwork_master <> stealNW_TQ.io.connVAS(i)
    }
  }

  val contentionThreshold_ = (max((peCount + (if (outsideSpawn) 1 else 0)) / 1.2, 1)).toInt
  val contentionDelta_ = 0 // if (contentionThreshold_ > 4) 1 else 0

  val virtualStealServers = Seq.fill(virtualAddressServersNumber)(
    Module(
      new SchedulerServer(
        taskWidth = taskWidth,
        contentionThreshold = contentionThreshold_,
        peCount = peCount,
        contentionDelta = contentionDelta_,
        vasCount = if(outsideSpawn) spawnerServerNumber else 0,
        sysAddressWidth = addrWidth,
        ignoreRequestSignals = false,
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
      vssAxiFullCfg = vssAxiFullCfg
    )
  )

  val io_paused = IO(Output(Bool()))
  io_paused := virtualStealServers.map(_.io.paused).reduce(_ || _)

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
    virtualStealServers(i).io.ntwDataUnitOccupancy <> stealNW_TQ.io.ntwDataUnitOccupancyVSS(i)
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

  val numberOftasksToStealOrServe = 128

  val remoteTaskServer =
    if (fpgaCount > 1) Some(Module(new RemoteTaskServer(taskWidth, numberOftasksToStealOrServe, peCount, taskIndex))) else None

  val fpgaCountInputReg =
    if (fpgaCount > 1) Some(IO(chiselTypeOf(remoteTaskServer.get.io.fpgaCountInputReg)).suggestName("fpgaCountInputReg"))
    else None
  val fpgaIndexInputReg =
    if (fpgaCount > 1) Some(IO(chiselTypeOf(remoteTaskServer.get.io.fpgaIndexInputReg)).suggestName("fpgaIndexInputReg"))
    else None

  val m_axis_remote =
    if (fpgaCount > 1) Some(IO(chiselTypeOf(remoteTaskServer.get.io.m_axis_taskAndReq)).suggestName("m_axis_remote")) else None
  val s_axis_remote =
    if (fpgaCount > 1) Some(IO(chiselTypeOf(remoteTaskServer.get.io.s_axis_taskAndReq)).suggestName("s_axis_remote")) else None

  if (fpgaCount > 1) {
    remoteTaskServer.get.io.serveRemote := virtualStealServers(0).io.serveRemote
    remoteTaskServer.get.io.getTasksFromRemote := virtualStealServers(0).io.getTasksFromRemote
    remoteTaskServer.get.io.connNetwork_0 <> stealNW_TQ.io.connVSS(virtualAddressServersNumber)
    remoteTaskServer.get.io.connNetwork_1 <> stealNW_TQ.io.connVSS(virtualAddressServersNumber + 1)
    elastic.SinkBuffer(m_axis_remote.get.asFull) <> remoteTaskServer.get.io.m_axis_taskAndReq.asFull
    s_axis_remote.get <> remoteTaskServer.get.io.s_axis_taskAndReq
    remoteTaskServer.get.io.fpgaCountInputReg := fpgaCountInputReg.get
    remoteTaskServer.get.io.fpgaIndexInputReg := fpgaIndexInputReg.get
  }

  axiFullPorts.zip(io_internal.vss_axi_full).foreach { case (vss, s_axi) => AxiWriteBuffer(vss) :=> s_axi }

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
    axis_stream_converters_out(i).io.dataIn.asLite <> stealNW_TQ.io.connPE(i).pop
    io_export.taskOut(i).asLite <> axis_stream_converters_out(i).io.dataOut.asLite
    if (spawnsItself) {
      axis_stream_converters_in.get(i).io.dataIn.asLite <> io_export.taskIn
        .get(i)
        .asLite
      stealNW_TQ.io
        .connPE(i)
        .push <> axis_stream_converters_in.get(i).io.dataOut.asLite
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
        spawnTaskCounter.io.signals(i) := (io_export.taskIn.get(i).asLite.fire)
        when(io_export.taskIn.get(i).asLite.fire) {
          logTask("TaskIn", i, io_export.taskIn.get(i).asLite.bits.asUInt)
        }
      }
    }

    val getExecuteTaskCounter = Module(new Counter64(peCount));
    for (i <- 0 until peCount) {
      getExecuteTaskCounter.io.signals(i) := (io_export.taskOut(i).asLite.fire)
      when(io_export.taskOut(i).asLite.fire) {
        logTask("TaskOut", i, io_export.taskOut(i).asLite.bits.asUInt)
      }
    }

    dontTouch(getExecuteTaskCounter.io.counter)
  }
  // DEBUG

  if (argRouteServersNumber > 0) {
    for (i <- 0 until argRouteServersNumber) {
      getOutsideSpawnNetwork.get.io.connSS(outTaskSpawnIndicies(i)) <> connArgumentNotifier(i)
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
      ).io.dataIn.asLite <> io_export.taskInGlobal
        .get(i - argRouteServersNumber)
        .asLite
      globalsTaskBuffers(
        i - argRouteServersNumber
      ).io.in <> axis_stream_converters_in_global(
        i - argRouteServersNumber
      ).io.dataOut.asLite

      //stealNW_TQ.io.connVAS(i) 
      getOutsideSpawnNetwork.get.io.connSS(outTaskSpawnIndicies(i)) <> globalsTaskBuffers(i - argRouteServersNumber).io.connStealNtw

    }

    // DEBUG
    if (debug) {
      val globalTaskInCounter = Module(new Counter64(peCountGlobalTaskIn))
      for (i <- 0 until peCountGlobalTaskIn) {
        globalTaskInCounter.io.signals(i) :=
          io_export.taskInGlobal.get(i).asLite.fire
        when(io_export.taskInGlobal.get(i).asLite.fire) {
          logTask("GlobalTaskIn", i, io_export.taskInGlobal.get(i).asLite.bits.asUInt)
        }
      }
      dontTouch(globalTaskInCounter.io.counter)
    }
    // DEBUG

    // If collectStats is true, create two counters, one for the number of tasks taken in and one for the number of tasks given out
    // Log the two counters to the output each 1000 cycles

  }


  if (collectStats) {
    val taskInCounter = if (spawnsItself) Some(Module(new Counter64(peCount))) else None
    val taskOutCounter = Module(new Counter64(peCount))

    for (i <- 0 until peCount) {
      if (spawnsItself) {
        taskInCounter.get.io.signals(i) := io_export.taskIn.get(i).asLite.fire
      }
      taskOutCounter.io.signals(i) := io_export.taskOut(i).asLite.fire
    }

    val cycleCounter = RegInit(0.U(128.W))
    val previousTaskInCounter = if(spawnsItself) Some(RegInit(0.U(128.W))) else None
    val previousTaskOutCounter = RegInit(0.U(128.W))
    cycleCounter := cycleCounter + 1.U

    // when(cycleCounter % 1000.U === 0.U && (previousTaskOutCounter =/= taskOutCounter.io.counter || (if(spawnsItself) taskInCounter.get.io.counter =/= previousTaskInCounter.get else 0.B))) {
    //   // Also log fpga Index
    //   // print only if one of the values changes
    //   previousTaskOutCounter := taskOutCounter.io.counter
    //   if(spawnsItself) previousTaskInCounter.get := taskInCounter.get.io.counter


    //   if (fpgaCount > 1) printf("fpgaIndex: %d\n", fpgaIndexInputReg.get)
    //   if (spawnsItself) printf("  TaskIn: %d\n", taskInCounter.get.io.counter)
    //   printf("  TaskOut: %d\n", taskOutCounter.io.counter)
    //   // print the value of the cycle counter * 2 in nano seconds
    //   printf("  Time ns: %d\n", cycleCounter * 2.U)

    // }
    
    if (spawnsItself) dontTouch(taskInCounter.get.io.counter)
    dontTouch(taskOutCounter.io.counter)
    dontTouch(cycleCounter)
  }


  // expose the task counters to the MFPGA module to be used for logging

}

object SchedulerEmitter extends App {
  emitVerilog(new Scheduler(64, 256, 16, 16, 1, true, 1, 1, 256, "PE", false, 2, 0, 4), Array("--target-dir", "output"))
}

//

// class Scheduler(
//     addrWidth: Int,
//     taskWidth: Int,
//     queueDepth: Int,
//     peCount: Int,
//     virtualAddressServersNumber: Int,
//     spawnsItself: Boolean,
//     peCountGlobalTaskIn: Int,
//     argRouteServersNumber: Int,
//     pePortWidth: Int,
//     peType: String,
//     debug: Boolean,
//     fpgaCount: Int
// )
