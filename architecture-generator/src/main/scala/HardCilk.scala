package HardCilk

import chisel3._
import Descriptors._
import Scheduler._
import Allocator._
import ArgumentNotifier._
import HLSHelpers._
import Util.HardCilkUtil._
import Util.RemoteStreamToMem

import chext.amba.axi4
import axi4.Ops._
import axi4.lite.components._

import io.circe.syntax._
import io.circe.generic.auto._
import scala.collection.mutable.ArrayBuffer
import chext.elastic.ConnectOp._
import chext.amba.axi4.lite.components.{Upscale, UpscaleConfig}



import HardCilkBuilder.PortToExport
import Util.WriteBuffer

class HardCilk(
    override val fullSysGenDescriptor: FullSysGenDescriptor, // Made public for trait
    outputDirPathRTL: String,
    debug: Boolean,
    override val reduceAxi: Int, // Made public for trait
    unitedHbm: Boolean,
    isSimulation: Boolean,
    argumentNotifierCutCount: Int,
    override val addressTransformFlag: Boolean = false // Made public for trait
) extends Module with HasHBMInterconnect with HardCilkHasMfpgaSupport { // <-- MIXIN THE TRAIT HERE


  override def desiredName: String =
    if (fullSysGenDescriptor.name.isEmpty) "fullSysGen"
    else fullSysGenDescriptor.name

  val paused = IO(Output(Bool())).suggestName("paused")
  val done   = IO(Output(Bool())).suggestName("done")

  // These are now concrete implementations for the trait's abstract members
  val axiOuts = scala.collection.mutable.ArrayBuffer[axi4.RawInterface]()
  val axiXDMA = scala.collection.mutable.ArrayBuffer[axi4.RawInterface]()
  val interfacesAxiControl =
    scala.collection.mutable.ArrayBuffer[axi4.RawInterface]()
  val interfacesAxiManagement =
    scala.collection.mutable.ArrayBuffer[axi4.RawInterface]()
  var numHbmPortExports = reduceAxi
  val interfaceBuffer = new ArrayBuffer[hdlinfo.Interface]()
  val exportedPeHdlinfoPorts = new ArrayBuffer[hdlinfo.Port]()

  // These are also concrete implementations for the trait
  val cfgAxi4HBM = axi4.Config(
    wId = 5, wAddr = fullSysGenDescriptor.widthAXIAddress, wData = 256,
    wUserAR = 0, wUserR = 0, wUserAW = 0, wUserW = 0, wUserB = 0
  )
  val cfgXDMA = axi4.Config(wId = 4, wAddr = 64, wData = 512)

  val builder = new HardCilkBuilder(fullSysGenDescriptor, debug, argumentNotifierCutCount)

  val blueprint = builder.defineBlueprint()

  val peMap = blueprint.peFactories.map {
    case (name, factory) => name -> factory()
  }
  val schedulerMap = blueprint.schedulerFactories.map {
    case (name, factory) => name -> Module(factory())
  }
  val allocatorMap = blueprint.allocatorFactories.map {
    case (name, factory) => name -> Module(factory())
  }
  val notifierMap = blueprint.argNotifierFactories.map {
    case (name, factory) => name -> Module(factory())
  }
  val memAllocatorMap = blueprint.memAllocatorFactories.map {
    case (name, factory) => name -> Module(factory())
  }

  val spawnNextWBMap = blueprint.spawnNextWBFactories.map {
    case (name, factory) => name -> factory()
  }
  val sendArgumentWBMap = blueprint.sendArgumentWBFactories.map {
    case (name, factory) => name -> factory()
  }

  val remoteStreamToMemMap = blueprint.remoteStreamToMemFactories.map {
    case (name, factory) => name -> Module(factory())
  }


  val demux = instantiateManagementDemux()
  connectManagement(demux, schedulerMap, allocatorMap, memAllocatorMap, notifierMap, remoteStreamToMemMap)
  connectPEs(peMap)

  val portsToExport = builder.connectSubsystems(
    schedulerMap, allocatorMap, notifierMap, memAllocatorMap, peMap, spawnNextWBMap, sendArgumentWBMap
  )



  exportMissingPEPorts(
    portsToExport, schedulerMap, allocatorMap, notifierMap, memAllocatorMap, peMap, spawnNextWBMap, sendArgumentWBMap
  )

  connectGlobalSignals(schedulerMap, allocatorMap, memAllocatorMap, notifierMap)

  // This call now invokes the method from the HasHBMInterconnect trait
  buildAndConnectHBM(peMap, schedulerMap, allocatorMap, notifierMap, memAllocatorMap, spawnNextWBMap, sendArgumentWBMap, remoteStreamToMemMap)

  exportPEControl(peMap)
  generateHdlInfo()

  if(fullSysGenDescriptor.mFPGASimulation || fullSysGenDescriptor.mFPGASynth){
    buildMfpgaConnections()
  }


  // --- Private Helper Methods for Initialization ---

  private def exportMissingPEPorts(
      portsToExport: Seq[PortToExport],
      scheds: Map[String, Scheduler],
      allocs: Map[String, Allocator],
      notifiers: Map[String, ArgumentNotifier],
      memAllocs: Map[String, Allocator],
      pes: Map[String, Seq[VitisWriteBufferModule]],
      spawnNextWBs: Map[String, Seq[WriteBuffer]],
      sendArgumentWBs: Map[String, Seq[WriteBuffer]]
  ): Unit = {

    if (portsToExport.nonEmpty) {
      println(s"[CleanHardCilk] Exporting ${portsToExport.length} ports for missing PEs...")
    }

    for (port <- portsToExport) {
      val subPortDesc = port.subsystemPortDescriptor
      val pePortDesc = port.pePortDescriptor

      val subsystemPort = getPhysicalPort(
        subPortDesc, scheds, allocs, notifiers, memAllocs, pes, spawnNextWBs, sendArgumentWBs
      )

      /**
       * First, handle if directly the port is exported
      */

      val newIO = IO(chiselTypeOf(subsystemPort))
      val ioName = f"BindTo_PE_${pePortDesc.parentName}_${pePortDesc.parentIndex}_${pePortDesc.portType}"
      newIO.suggestName(ioName)
      println(s"  ... exporting ${ioName}")

      if (port.isSource) {
        newIO <> subsystemPort
        exportedPeHdlinfoPorts += hdlinfo.Port(
          ioName, hdlinfo.PortDirection.input, hdlinfo.PortKind.data, associatedClock = "clock"
        )
      } else {
        subsystemPort <> newIO
        exportedPeHdlinfoPorts += hdlinfo.Port(
          ioName, hdlinfo.PortDirection.output, hdlinfo.PortKind.data, associatedClock = "clock"
        )
      }
    }
  }

  private def instantiateManagementDemux(): axi4.lite.components.Demux = {
    val registerBlockSize = 6
    val numMasters = fullSysGenDescriptor.getNumConfigPorts()
    val axiCfgCtrl = axi4.Config(
      wAddr = numMasters + registerBlockSize,
      wData = 64,
      lite = true
    )

    val demux = Module(
      new axi4.lite.components.Demux(
        new DemuxConfig(
          axiCfgCtrl,
          numMasters,
          (x: UInt) => (x >> registerBlockSize.U)
        )
      )
    )

    val s_axil_mgmt = if (fullSysGenDescriptor.isVitisProject) {
      IO(axi4.Slave(axiCfgCtrl.copy(wData = 32))).suggestName("s_axil_mgmt_hardcilk")
    } else {
      IO(axi4.Slave(axiCfgCtrl)).suggestName("s_axil_mgmt_hardcilk")
    }

    if (fullSysGenDescriptor.isVitisProject) {

      val s_axil_mgmt_upscale = Module(
        new Upscale(new UpscaleConfig(axiCfgCtrl.copy(wData = 32), 64))
      )
      axi4.lite.SlaveBuffer(s_axil_mgmt.asLite, axi4.BufferConfig.all(8)) :=> s_axil_mgmt_upscale.s_axi

      val offset = 0x10
      new chext.elastic.Transform(s_axil_mgmt_upscale.m_axi.ar, demux.s_axil.ar) {
        protected override def onTransform: Unit = {
          out := in
          out.addr := in.addr - Mux(in.addr > 0.U, offset.U, 0.U) // This was done to have addr 0 (mapped for HLS registers to not hang the axi transaction)
        }
      }
      demux.s_axil.r :=> s_axil_mgmt_upscale.m_axi.r
      new chext.elastic.Transform(s_axil_mgmt_upscale.m_axi.aw, demux.s_axil.aw) {
        protected override def onTransform: Unit = {
          out := in
          out.addr := in.addr - Mux(in.addr > 0.U, offset.U, 0.U) // This was done to have addr 0 (mapped for HLS registers to not hang the axi transaction)
        }
      }
      s_axil_mgmt_upscale.m_axi.w :=> demux.s_axil.w
      demux.s_axil.b :=> s_axil_mgmt_upscale.m_axi.b
    } else {
      s_axil_mgmt :=> demux.s_axil
    }

    interfaceBuffer.addOne(
      hdlinfo.Interface(
        "s_axil_mgmt_hardcilk",
        hdlinfo.InterfaceRole.slave,
        hdlinfo.InterfaceKind("axi4"),
        "clock", "reset",
        Map("config" -> hdlinfo.TypedObject(axiCfgCtrl))
      )
    )
    interfacesAxiManagement.addOne(s_axil_mgmt)
    demux
  }

  private def connectManagement(
      demux: axi4.lite.components.Demux,
      schedulerMap: Map[String, Scheduler],
      closureAllocatorMap: Map[String, Allocator],
      memoryAllocatorMap: Map[String, Allocator],
      argumentNotifierMap: Map[String, ArgumentNotifier],
      remoteStreamToMemMap: Map[String, RemoteStreamToMem]
  ): Unit = {
    var j = 0 // Management port index
    fullSysGenDescriptor.taskDescriptors.foreach { task =>
      val taskSched = schedulerMap(task.name)
      // Connect Scheduler Management
      for (i <- j until j + task.getNumServers("scheduler")) {
        demux.m_axil(i) :=> taskSched.io_internal.axi_mgmt_vss(i - j)
      }
      j += task.getNumServers("scheduler")

      // Connect Scheduler Spawner Management (if any)
      if (taskSched.spawnerServerMgmt.isDefined) {
        for (i <- j until j + task.spawnServersCount) {
          demux.m_axil(i) :=> taskSched.spawnerServerMgmt.get(i - j)
        }
        j += task.spawnServersCount
      }

      // Connect Closure Allocator Management (if any)
      if (closureAllocatorMap.contains(task.name)) {
        val taskAlloc = closureAllocatorMap(task.name)
        for (i <- j until j + task.getNumServers("allocator")) {
          demux.m_axil(i) :=> taskAlloc.io_internal.axi_mgmt_vcas(i - j)
        }
        j += task.getNumServers("allocator")
      }

      // Connect Memory Allocator Management (if any)
      if (memoryAllocatorMap.contains(task.name)) {
        val taskMemAlloc = memoryAllocatorMap(task.name)
        for (i <- j until j + task.getNumServers("memoryAllocator")) {
          demux.m_axil(i) :=> taskMemAlloc.io_internal.axi_mgmt_vcas(i - j)
        }
        j += task.getNumServers("memoryAllocator")
      }
    }

    // if mfpga support connect the info ports
    if(fullSysGenDescriptor.mFPGASynth || fullSysGenDescriptor.mFPGASimulation){
      // each scheduler has an extra port
      fullSysGenDescriptor.taskDescriptors.foreach { task =>
        val taskSched = schedulerMap(task.name)
        demux.m_axil(j) :=> taskSched.s_axi_remote_task_server.get
        j += 1
      }
      // each remote stream has a port
      fullSysGenDescriptor.taskDescriptors.foreach { task =>
        if(remoteStreamToMemMap.contains(task.name)){
          demux.m_axil(j) :=> remoteStreamToMemMap(task.name).io.axi_mgmt
          j += 1
        }
      }
      // each argument notifier has a sequence of extra ports
      fullSysGenDescriptor.taskDescriptors.foreach { task =>
        if(notifierMap.contains(task.name)){
          val s_axi_seq = notifierMap(task.name).s_axis_mfgpa_argument_notifier.get
          for(i <- 0 until task.getNumServers("argumentNotifier")){
            demux.m_axil(j) :=> s_axi_seq(i)
            j += 1
          }
        }
      }
    }
  }

  private def connectPEs(peMap: Map[String, Seq[VitisWriteBufferModule]]): Unit = {
    for {
      (taskName, peArray) <- peMap
      pe <- peArray
    } {
      pe.getPort("ap_clk").asInstanceOf[Clock] := clock
      pe.getPort("ap_rst_n").asInstanceOf[Bool] := ~reset.asBool
      try {
        pe.getPort("ap_start").asInstanceOf[Bool] := true.B
      } catch {
        case _: Exception => // Module has no ap_start
      }
    }
  }

  private def connectGlobalSignals(
      schedulerMap: Map[String, Scheduler],
      closureAllocatorMap: Map[String, Allocator],
      memoryAllocatorMap: Map[String, Allocator],
      argumentNotifierMap: Map[String, ArgumentNotifier]
  ): Unit = {
    val schedulerPaused =
      if (schedulerMap.isEmpty) false.B
      else schedulerMap.map(_._2.io_paused).reduce(_ || _)
    val closureAllocatorPaused =
      if (closureAllocatorMap.isEmpty) false.B
      else closureAllocatorMap.map(_._2.io_paused).reduce(_ || _)
    val memoryAllocatorPaused =
      if (memoryAllocatorMap.isEmpty) false.B
      else memoryAllocatorMap.map(_._2.io_paused).reduce(_ || _)

    paused := schedulerPaused || closureAllocatorPaused || memoryAllocatorPaused

    if (argumentNotifierMap.nonEmpty) {
      done := argumentNotifierMap.map(_._2.io_export.done).reduce(_ || _)
    } else {
      done := false.B
    }
  }

  // --- buildAndConnectHBM IS NOW GONE ---
  // (It lives in the HasHBMInterconnect trait)

  private def exportPEControl(peMap: Map[String, Seq[VitisWriteBufferModule]]): Unit = {
    fullSysGenDescriptor.taskDescriptors.foreach { task =>
      if (task.hasAXI && peMap.contains(task.name)) {
        val peArray = peMap(task.name)
        for (i <- 0 until task.numProcessingElements) {
          val pe = peArray(i)
          val peName = f"${task.name}_${i}"
          val pes_axi_control = IO(
            chiselTypeOf(pe.getPort("s_axi_control").asInstanceOf[axi4.RawInterface])
          ).suggestName(f"${peName}_s_axi_control")

          interfaceBuffer.addOne(
            hdlinfo.Interface(
              f"${peName}_s_axi_control",
              hdlinfo.InterfaceRole.slave,
              hdlinfo.InterfaceKind("axi4"),
              "clock", "reset",
              Map("config" -> hdlinfo.TypedObject(pes_axi_control.cfg))
            )
          )
          pes_axi_control :=> pe.getPort("s_axi_control").asInstanceOf[axi4.RawInterface]
          interfacesAxiControl.addOne(pes_axi_control)
        }
      }
    }
  }

  private def generateHdlInfo(): Unit = {
    lazy val hdlinfoModule: hdlinfo.Module = {
      import hdlinfo._
      val basicPorts = Seq(
        Port("clock", PortDirection.input, PortKind.clock),
        Port("reset", PortDirection.input, PortKind.reset, PortSensitivity.resetActiveHigh, associatedClock = "clock"),
        Port("paused", PortDirection.output, PortKind.data),
        Port("done", PortDirection.output, PortKind.data)
      )

      Module(
        fullSysGenDescriptor.name,
        basicPorts ++ exportedPeHdlinfoPorts.toSeq, // Use our buffer
        interfaceBuffer.toSeq
      )
    }

    val write = new java.io.PrintWriter(
      f"${outputDirPathRTL}/${fullSysGenDescriptor.name}.hdlinfo.json"
    )
    write.write(hdlinfoModule.asJson.toString())
    write.close()
  }
}