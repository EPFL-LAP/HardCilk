package Scheduler

import chisel3._
import chisel3.util._
import scala.math._

import AXIHelpers._
import Util._

import chext.amba.axi4
import chext.amba.axi4s
import axi4s.Casts._
import axi4.Ops._


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

  val taskIn = if (spawnsItself) Some(Vec(peCount, axi4s.Slave(axisCfgTask))) else None
  val taskInGlobal = if (peCountGlobalTaskIn > 0) Some(Vec(peCountGlobalTaskIn, axi4s.Slave(axisCfgTask))) else None

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
) extends Bundle {
  val nAxiPorts = vssCount

  val vss_axi_full = Vec(nAxiPorts, axi4.full.Master(vssAxiFullCfg))
  val axi_mgmt_vss = Vec(vssCount, axi4.lite.Slave(axiMgmtCfg))
}

class Scheduler(
    addrWidth: Int,
    override val taskWidth: Int,
    queueDepth: Int,
    override val peCount: Int,
    override val schedulerServersNumber: Int,
    spawnsItself: Boolean,
    peCountGlobalTaskIn: Int,
    argRouteServersNumber: Int,
    pePortWidth: Int,
    peType: String,
    debug: Boolean,
    override val spawnerServerNumber: Int = 1,
    argRouteServersCreateTasks: Boolean =false,
    override val mfpgaSupport: Boolean = false,
    maxNumnberToStealOrServe: Int = 256,
    override val taskId: Int = 0,
    override val axisCfgTaskAndReq: axi4s.Config = axi4s.Config(wData = 512, wDest = 4) 
) extends Module with SchedulerHasMfpgaSupport {

  val vssAxiFullCfg = axi4.Config(
    wAddr = addrWidth,
    wData = taskWidth,
    lite = false,
    wId = 1
  )


  val outsideSpawn = ((peCountGlobalTaskIn + argRouteServersNumber) > 0) && (argRouteServersCreateTasks || peCountGlobalTaskIn > 0)

  println(f"Outside spawn ${outsideSpawn} of task ${peType}")

  val spawnerServer = if(outsideSpawn) Some(Seq.fill(spawnerServerNumber)(Module(new SpawnerServer(taskWidth)))) else None
  
  val spawnerServerMgmt = if(outsideSpawn) Some(Seq.fill(spawnerServerNumber)(IO(axi4.lite.Slave(spawnerServer.get(0).asInstanceOf[SpawnerServer].regBlock.cfgAxi))) ) else None
  val spawnerServerAXI = if(outsideSpawn) Some(Seq.fill(spawnerServerNumber)(IO(axi4.full.Master(spawnerServer.get(0).asInstanceOf[SpawnerServer].axiCfg))) ) else None  
  val step = if(outsideSpawn) (peCountGlobalTaskIn + argRouteServersNumber) / spawnerServerNumber else 0
  var spawnerIndicies = Array.tabulate(spawnerServerNumber)(n => (n + n * step))
  var outTaskSpawnIndicies = Array.tabulate(peCountGlobalTaskIn + argRouteServersNumber + spawnerServerNumber)(n => (n))
  outTaskSpawnIndicies = outTaskSpawnIndicies.filterNot(spawnerIndicies.contains(_))
  val getOutsideSpawnNetwork = if(outsideSpawn) Some( Module(new SchedulerNetwork(taskWidth, (peCountGlobalTaskIn + argRouteServersNumber) + spawnerServerNumber, spawnerIndicies))) else None


  // Log the size of the outside spawn network
  if(outsideSpawn) {
    println(f"Outside spawn network size: ${getOutsideSpawnNetwork.get.io.connSS.size} connections")
    // log the indices of the spawner servers
    println(f"Spawner server indices: ${spawnerIndicies.mkString(", ")}")
    // log the indices of the outTaskSpawnIndicies
    println(f"Out task spawn indices: ${outTaskSpawnIndicies.mkString(", ")}")
  }

  if(outsideSpawn){
    for(i <- 0 until spawnerServerNumber){
      spawnerServer.get(i).asInstanceOf[SpawnerServer].io.axi_mgmt <> spawnerServerMgmt.get(i)
      spawnerServer.get(i).asInstanceOf[SpawnerServer].io.m_axi.asFull :=> spawnerServerAXI.get(i)
      spawnerServer.get(i).asInstanceOf[SpawnerServer].io.connNetwork_slave <> getOutsideSpawnNetwork.get.io.connSS(spawnerIndicies(i))

      // Log the connetion of these indicies in the outside spawn network
      println(f"Spawner server ${i} connection to outside spawn network: ${getOutsideSpawnNetwork.get.io.connSS(spawnerIndicies(i)).toString()}")

    }
  }


  // Add two entries SchedulerLocalNetwork if mfpgaSupport is enabled, one for task reading and one for task writing from the network.
  val schedulerServersInputToSchedulerLocalNetwork = if (mfpgaSupport) (schedulerServersNumber + 2) else schedulerServersNumber


  val stealNW_TQ = Module(
    new SchedulerLocalNetwork(
      peCount = peCount,
      vssCount = schedulerServersInputToSchedulerLocalNetwork,
      vasCount = if(outsideSpawn) spawnerServerNumber else 0,
      taskWidth = taskWidth,
      queueDepth = queueDepth,
      qRamReadLatency = 1,
      qRamWriteLatency = 1,
      spawnsItself = spawnsItself,
      successiveNetworkConfig = false // HARDCODED, #TODO: if hardware generation fails with 1 PE, enable this when vsscount > peCount
    )
  )

  if(outsideSpawn) {
    for(i <- 0 until spawnerServerNumber){
      spawnerServer.get(i).asInstanceOf[SpawnerServer].io.connNetwork_master <> stealNW_TQ.io.connVAS(i)
    }
  }

  val contentionThreshold_ = (max((peCount + argRouteServersNumber + peCountGlobalTaskIn) / 1.2, 1)).toInt
  val contentionDelta_ = if (contentionThreshold_ > 4) 1 else 0

  val schedulerServers = Seq.fill(schedulerServersNumber)(
    Module(
      new SchedulerServer(
        taskWidth = taskWidth,
        contentionThreshold = contentionThreshold_,
        peCount = peCount,
        contentionDelta = contentionDelta_,
        vasCount = argRouteServersNumber + peCountGlobalTaskIn,
        sysAddressWidth = addrWidth,
        ignoreRequestSignals = false, // HARDCODED
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
      vssCount = schedulerServersNumber,
      axiMgmtCfg = schedulerServers(0).regBlock.cfgAxi,
      vssAxiFullCfg = vssAxiFullCfg,
    )
  )

  val io_paused = IO(Output(Bool()))
  io_paused := schedulerServers.map(_.io.paused).reduce(_ || _)

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

  val connArgumentNotifier = IO(Vec(argRouteServersNumber, new SchedulerNetworkClientIO(taskWidth)))

  for (i <- 0 until schedulerServersNumber) {
    io_internal.axi_mgmt_vss(i) :=> schedulerServers(i).io.axi_mgmt
    schedulerServers(i).io.ntwDataUnitOccupancy <> stealNW_TQ.io
      .ntwDataUnitOccupancyVSS(i)
  }

  val vssRvm = Seq.fill(schedulerServersNumber)(
    Module(new RVtoAXIBridge(taskWidth, addrWidth, varBurst = true))
  )

  val axiFullPorts = vssRvm.map(_.axi)

  for (i <- 0 until schedulerServersNumber) {
    vssRvm(i).io.read.get.address <> schedulerServers(i).io.read_address
    vssRvm(i).io.read.get.data <> schedulerServers(i).io.read_data
    vssRvm(i).io.write.get.address <> schedulerServers(i).io.write_address
    vssRvm(i).io.write.get.data <> schedulerServers(i).io.write_data
    vssRvm(i).io.readBurst.get.len := schedulerServers(i).io.read_burst_len
    vssRvm(i).io.writeBurst.get.len := schedulerServers(i).io.write_burst_len
    vssRvm(i).io.writeBurst.get.last := schedulerServers(i).io.write_last
    schedulerServers(i).io.connNetwork <> stealNW_TQ.io.connVSS(i)

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

  axiFullPorts.zip(io_internal.vss_axi_full).foreach { case (vss, s_axi) => AxiWriteBuffer(vss) :=> s_axi }

  // DEBUG
  if (debug) {
    val virtualStealServerTakeInCounter = Module(
      new Counter64(schedulerServersNumber)
    )

    for (i <- 0 until schedulerServersNumber) {
      virtualStealServerTakeInCounter.io.signals(i) :=
        schedulerServers(i).io.connNetwork.data.availableTask.fire
      when(schedulerServers(i).io.connNetwork.data.availableTask.fire) {
        logTask(
          "VssTaskOut",
          i,
          schedulerServers(i).io.connNetwork.data.availableTask.bits.asUInt
        )
      }
    }
    dontTouch(virtualStealServerTakeInCounter.io.counter)

    val virtualStealServerGiveOutCounter = Module(
      new Counter64(schedulerServersNumber)
    )

    for (i <- 0 until schedulerServersNumber) {
      virtualStealServerGiveOutCounter.io.signals(i) :=
        schedulerServers(i).io.connNetwork.data.qOutTask.fire
      when(schedulerServers(i).io.connNetwork.data.qOutTask.fire) {
        logTask(
          "VssTaskIn",
          i,
          schedulerServers(i).io.connNetwork.data.qOutTask.bits.asUInt
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

    // Write these values to all scheduler servers (avoids sink not connected errors)
    for (j <- 0 until schedulerServersNumber){
      schedulerServers(j).io.lengths_of_hardware_queues(i) := stealNW_TQ.io.lengths_of_hardware_queues(i)
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

  if (argRouteServersNumber > 0 && outsideSpawn){ //&& argRouteServersCreateTasks) { // 
    for (i <- 0 until argRouteServersNumber) {
      getOutsideSpawnNetwork.get.io.connSS(outTaskSpawnIndicies(i)) <> connArgumentNotifier(i)
    } 
  } else {
    for (i <- 0 until argRouteServersNumber) {
      connArgumentNotifier(i).ctrl.serveStealReq.ready := 0.U
      connArgumentNotifier(i).ctrl.stealReq.ready := 0.U
      connArgumentNotifier(i).data.availableTask.valid := 0.U
      connArgumentNotifier(i).data.availableTask.bits := 0.U
      connArgumentNotifier(i).data.qOutTask.ready := 0.U
    }
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

      getOutsideSpawnNetwork.get.io.connSS(outTaskSpawnIndicies(i)) <> globalsTaskBuffers(i - argRouteServersNumber).io.connStealNtw
    }
  }
  buildMfpgaConnections()
}


// Create an emmitter for the Scheduler module that includes the mfPGA connections enabled. Write the sys verilog files in output/mfPGA-scheduler/
object SchedulerMfpgaEmitter extends App {
  import _root_.circt.stage.ChiselStage

  ChiselStage.emitSystemVerilogFile(
    new Scheduler(
      addrWidth = 64,
      taskWidth = 256,
      queueDepth = 64,
      peCount = 4,
      schedulerServersNumber = 2,
      spawnsItself = true,
      peCountGlobalTaskIn = 2,
      argRouteServersNumber = 2,
      pePortWidth = 256,
      peType = "fib",
      debug = false,
      mfpgaSupport = true,
      maxNumnberToStealOrServe = 256
    ),
    Array(
      "--target-dir=output/mfPGA-scheduler/"
    ),
    Array("--disable-all-randomization")
  )
}
