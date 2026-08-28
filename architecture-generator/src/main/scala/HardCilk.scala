package HardCilk

import chisel3._
import Descriptors._
import Scheduler._
import Allocator._
import ArgumentNotifier._
import NewArgumentNotifier._
import HLSHelpers._
import Util.HardCilkUtil._
import Util.RemoteStreamToMem

import chext.amba.axi4
import axi4.Ops._
import AXIHelpers._
import Atomics.LockServer
import axi4.lite.components._

import io.circe.syntax._
import io.circe.generic.auto._
import scala.collection.mutable.ArrayBuffer
import chext.elastic.ConnectOp._
import chext.amba.axi4.lite.components.{Upscale, UpscaleConfig}

import HardCilkBuilder.PortToExport
import Util.WriteBuffer
import Util.GeneratorProfile

class HardCilk(
    override val fullSysGenDescriptor: FullSysGenDescriptor, // Made public for trait
    outputDirPathRTL: String,
    debug: Boolean,
    override val reduceAxi: Int, // Made public for trait
    unitedHbm: Boolean,
    isSimulation: Boolean,
    argumentNotifierCutCount: Int,
    override val addressTransformFlag: Boolean = false,
    override val enableRamaByDefault: Boolean = false,
    override val ramaStripingEnabled: Boolean = false,
    override val generatorProfile: GeneratorProfile = GeneratorProfile(
      Util.ArchitectureMode.Updated,
      Util.ArgumentServerMode.Cached
    ),
    override val enableGlobalStart: Boolean = false
) extends Module
    with HasHBMInterconnect
    with HardCilkHasMfpgaSupport { // <-- MIXIN THE TRAIT HERE

  override def desiredName: String =
    if (fullSysGenDescriptor.name.isEmpty) "fullSysGen"
    else fullSysGenDescriptor.name

  val paused = IO(Output(Bool())).suggestName("paused")
  val done = IO(Output(Bool())).suggestName("done")

  // These are now concrete implementations for the trait's abstract members
  val axiOuts = scala.collection.mutable.ArrayBuffer[axi4.RawInterface]()
  val axiXDMA = scala.collection.mutable.ArrayBuffer[axi4.RawInterface]()
  val interfacesAxiControl =
    scala.collection.mutable.ArrayBuffer[axi4.RawInterface]()
  val interfacesAxiManagement =
    scala.collection.mutable.ArrayBuffer[axi4.RawInterface]()
  var numHbmPortExports = reduceAxi
  // Exported m_axi_NN indices whose PE task requested selective RAMA.
  var ramaPortIndices: Seq[Int] = Seq.empty
  val interfaceBuffer = new ArrayBuffer[hdlinfo.Interface]()
  val exportedPeHdlinfoPorts = new ArrayBuffer[hdlinfo.Port]()

  // These are also concrete implementations for the trait
  val cfgAxi4HBM = axi4.Config(
    wId = 5,
    wAddr = fullSysGenDescriptor.widthAXIAddress,
    wData = 256,
    wUserAR = 0,
    wUserR = 0,
    wUserAW = 0,
    wUserW = 0,
    wUserB = 0
  )
  val cfgXDMA = axi4.Config(wId = 4, wAddr = 64, wData = 512)

  val builder =
    new HardCilkBuilder(
      fullSysGenDescriptor,
      debug,
      argumentNotifierCutCount,
      enableGlobalStart,
      generatorProfile
    )

  val blueprint = builder.defineBlueprint()

  val peMap = blueprint.peFactories.map { case (name, factory) =>
    name -> factory()
  }
  val schedulerMap = blueprint.schedulerFactories.map { case (name, factory) =>
    name -> Module(factory())
  }
  val allocatorMap = blueprint.allocatorFactories.map { case (name, factory) =>
    name -> Module(factory())
  }
  val notifierMap = blueprint.argNotifierFactories.map { case (name, factory) =>
    name -> Module(factory())
  }
  val newNotifierMap = blueprint.newArgNotifierFactories.map {
    case (name, factory) =>
      name -> Module(factory())
  }
  val memAllocatorMap = blueprint.memAllocatorFactories.map {
    case (name, factory) => name -> Module(factory())
  }

  for ((name, allocator) <- allocatorMap) {
    allocator.io_recycle.foreach { recycleIn =>
      val resolved = newNotifierMap(name).resolvedAddresses.getOrElse(
        throw new IllegalStateException(
          s"$name: allocator expects recycle sources but its argument " +
            "notifier does not export resolutions"
        )
      )
      require(
        resolved.length == recycleIn.length,
        s"$name: ${resolved.length} resolution branches but " +
          s"${recycleIn.length} recycle inputs"
      )
      recycleIn.zip(resolved).foreach { case (sink, source) => sink := source }
    }
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
  connectManagement(
    demux,
    schedulerMap,
    allocatorMap,
    memAllocatorMap,
    notifierMap,
    remoteStreamToMemMap
  )

  val globalRunGate: Option[Bool] =
    if (!enableGlobalStart) None
    else
      Some {
        val globalRunReg = RegInit(0.U(64.W))
        val globalRunBlock =
          new axi4.lite.components.RegisterBlock(
            wAddr = 6,
            wData = 64,
            wMask = 6
          )
        demux.m_axil(
          fullSysGenDescriptor.getNumConfigPorts()
        ) :=> globalRunBlock.s_axil
        globalRunBlock.base(0x00)
        globalRunBlock.reg(
          globalRunReg,
          read = true,
          write = true,
          desc =
            "Kernel-global start broadcast: bit0 releases all scheduler servers + watcher"
        )
        when(globalRunBlock.rdReq) { globalRunBlock.rdOk() }
        when(globalRunBlock.wrReq) { globalRunBlock.wrOk() }
        val gr = globalRunReg(0)
        schedulerMap.values.foreach { sched => sched.io_globalRun.get := gr }
        gr
      }

  connectPEs(peMap)
  connectNewArgumentNotifiers(newNotifierMap, peMap)

  val portsToExport = builder.connectSubsystems(
    schedulerMap,
    allocatorMap,
    notifierMap,
    newNotifierMap,
    memAllocatorMap,
    peMap,
    spawnNextWBMap,
    sendArgumentWBMap
  )

  exportMissingPEPorts(
    portsToExport,
    schedulerMap,
    allocatorMap,
    notifierMap,
    memAllocatorMap,
    peMap,
    spawnNextWBMap,
    sendArgumentWBMap
  )

  connectGlobalSignals(
    schedulerMap,
    allocatorMap,
    memAllocatorMap,
    notifierMap,
    newNotifierMap
  )

  // This call now invokes the method from the HasHBMInterconnect trait
  buildAndConnectHBM(
    peMap,
    schedulerMap,
    allocatorMap,
    notifierMap,
    newNotifierMap,
    memAllocatorMap,
    spawnNextWBMap,
    sendArgumentWBMap,
    remoteStreamToMemMap
  )

  fullSysGenDescriptor.lockConfig.foreach { lc => connectLockServer(lc, peMap) }

  // Append the watcher LAST so its dedicated HBM masters are the highest-index
  // (topmost) m_axi ports. Gated on watcherConfig => no effect on other benchmarks.
  fullSysGenDescriptor.watcherConfig.foreach { wc =>
    connectWatcher(wc, peMap, schedulerMap, newNotifierMap)
  }

  if (fullSysGenDescriptor.mFPGASimulation || fullSysGenDescriptor.mFPGASynth) {
    buildMfpgaConnections()
  }

  exportPEControl(peMap)
  generateHdlInfo()

  // --- Private Helper Methods for Initialization ---

  private def exportMissingPEPorts(
      portsToExport: Seq[PortToExport],
      scheds: Map[String, SchedulerModule],
      allocs: Map[String, AllocatorModule],
      notifiers: Map[String, ArgumentNotifierModule],
      memAllocs: Map[String, AllocatorModule],
      pes: Map[String, Seq[VitisWriteBufferModule]],
      spawnNextWBs: Map[String, Seq[WriteBuffer]],
      sendArgumentWBs: Map[String, Seq[WriteBuffer]]
  ): Unit = {

    if (portsToExport.nonEmpty) {
      println(
        s"[CleanHardCilk] Exporting ${portsToExport.length} ports for missing PEs..."
      )
    }

    for (port <- portsToExport) {
      val subPortDesc = port.subsystemPortDescriptor
      val pePortDesc = port.pePortDescriptor

      val subsystemPort = getPhysicalPort(
        subPortDesc,
        scheds,
        allocs,
        notifiers,
        memAllocs,
        pes,
        spawnNextWBs,
        sendArgumentWBs
      )

      /** First, handle if directly the port is exported
        */

      val newIO = IO(chiselTypeOf(subsystemPort))
      val ioName =
        f"BindTo_PE_${pePortDesc.parentName}_${pePortDesc.parentIndex}_${pePortDesc.portType}"
      newIO.suggestName(ioName)
      println(s"  ... exporting ${ioName}")

      if (port.isSource) {
        newIO <> subsystemPort
        exportedPeHdlinfoPorts += hdlinfo.Port(
          ioName,
          hdlinfo.PortDirection.input,
          hdlinfo.PortKind.data,
          associatedClock = "clock"
        )
      } else {
        subsystemPort <> newIO
        exportedPeHdlinfoPorts += hdlinfo.Port(
          ioName,
          hdlinfo.PortDirection.output,
          hdlinfo.PortKind.data,
          associatedClock = "clock"
        )
      }
    }
  }

  private def instantiateManagementDemux(): axi4.lite.components.Demux = {
    val registerBlockSize = 6
    val numMasters =
      fullSysGenDescriptor.getNumConfigPorts() + (if (enableGlobalStart) 1
                                                  else 0)
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
      IO(axi4.Slave(axiCfgCtrl.copy(wData = 32)))
        .suggestName("s_axil_mgmt_hardcilk")
    } else {
      IO(axi4.Slave(axiCfgCtrl)).suggestName("s_axil_mgmt_hardcilk")
    }

    if (fullSysGenDescriptor.isVitisProject) {

      val s_axil_mgmt_upscale = Module(
        new Upscale(new UpscaleConfig(axiCfgCtrl.copy(wData = 32), 64))
      )
      axi4.lite.SlaveBuffer(
        s_axil_mgmt.asLite,
        axi4.BufferConfig.all(8)
      ) :=> s_axil_mgmt_upscale.s_axi

      val offset = 0x10
      new chext.elastic.Transform(
        s_axil_mgmt_upscale.m_axi.ar,
        demux.s_axil.ar
      ) {
        protected override def onTransform: Unit = {
          out := in
          out.addr := in.addr - Mux(
            in.addr > 0.U,
            offset.U,
            0.U
          ) // This was done to have addr 0 (mapped for HLS registers to not hang the axi transaction)
        }
      }
      demux.s_axil.r :=> s_axil_mgmt_upscale.m_axi.r
      new chext.elastic.Transform(
        s_axil_mgmt_upscale.m_axi.aw,
        demux.s_axil.aw
      ) {
        protected override def onTransform: Unit = {
          out := in
          out.addr := in.addr - Mux(
            in.addr > 0.U,
            offset.U,
            0.U
          ) // This was done to have addr 0 (mapped for HLS registers to not hang the axi transaction)
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
        "clock",
        "reset",
        Map("config" -> hdlinfo.TypedObject(axiCfgCtrl))
      )
    )
    interfacesAxiManagement.addOne(s_axil_mgmt)
    demux
  }

  private def connectManagement(
      demux: axi4.lite.components.Demux,
      schedulerMap: Map[String, SchedulerModule],
      closureAllocatorMap: Map[String, AllocatorModule],
      memoryAllocatorMap: Map[String, AllocatorModule],
      argumentNotifierMap: Map[String, ArgumentNotifierModule],
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

      taskSched.legacySpawnerMgmt.zipWithIndex.foreach { case (port, index) =>
        demux.m_axil(j + index) :=> port
      }
      j += taskSched.legacySpawnerMgmt.size

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
    if (
      fullSysGenDescriptor.mFPGASynth || fullSysGenDescriptor.mFPGASimulation
    ) {
      // each scheduler has an extra port
      fullSysGenDescriptor.taskDescriptors.foreach { task =>
        val taskSched = schedulerMap(task.name)
        demux.m_axil(j) :=> taskSched.s_axi_remote_task_server.get
        j += 1
      }
      // each remote stream has a port
      fullSysGenDescriptor.taskDescriptors.foreach { task =>
        if (remoteStreamToMemMap.contains(task.name)) {
          demux.m_axil(j) :=> remoteStreamToMemMap(task.name).io.axi_mgmt
          j += 1
        }
      }
      // each argument notifier has a sequence of extra ports
      fullSysGenDescriptor.taskDescriptors.foreach { task =>
        if (notifierMap.contains(task.name)) {
          val s_axi_seq =
            notifierMap(task.name).s_axis_mfgpa_argument_notifier.get
          for (i <- 0 until task.getNumServers("argumentNotifier")) {
            demux.m_axil(j) :=> s_axi_seq(i)
            j += 1
          }
        }
      }
    }
  }

  private def connectPEs(
      peMap: Map[String, Seq[VitisWriteBufferModule]]
  ): Unit = {
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
      schedulerMap: Map[String, SchedulerModule],
      closureAllocatorMap: Map[String, AllocatorModule],
      memoryAllocatorMap: Map[String, AllocatorModule],
      argumentNotifierMap: Map[String, ArgumentNotifierModule],
      newArgumentNotifierMap: Map[String, ArgumentNetworks]
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

    val oldDone = argumentNotifierMap.values.map(_.io_export.done).toSeq
    val newDone = newArgumentNotifierMap.values.map(_.done).toSeq
    done := (oldDone ++ newDone).reduceOption(_ || _).getOrElse(false.B)
  }

  private def connectNewArgumentNotifiers(
      networks: Map[String, ArgumentNetworks],
      pes: Map[String, Seq[VitisWriteBufferModule]]
  ): Unit = {
    networks.foreach { case (targetName, network) =>
      val newSources = fullSysGenDescriptor.taskDescriptors.flatMap { source =>
        if (
          fullSysGenDescriptor.spawnNextList
            .getOrElse(source.name, Nil)
            .contains(targetName)
        )
          pes.getOrElse(source.name, Nil)
        else Nil
      }
      require(newSources.size == network.cfg.nSourcePEs)
      newSources.zipWithIndex.foreach { case (pe, index) =>
        val spawnWrite = pe
          .getPort("m_axi_spawnNext")
          .asInstanceOf[axi4.RawInterface]
          .asFull
        val newCont = network.s_axi_newCont(index).asFull
        // The wrapper's historical AXI declaration includes unused read
        // channels; NewArgumentNotifier is deliberately write-only.
        spawnWrite.aw :=> newCont.aw
        spawnWrite.w :=> newCont.w
        newCont.b :=> spawnWrite.b

        val meta = pe
          .getPort("continuationMetaIn")
          .asInstanceOf[chext.amba.axi4s.Interface]
        val returned = network.m_continuation(index)
        meta.TVALID := returned.valid
        meta.TDATA := returned.bits.metadata.asUInt.pad(32)
        returned.ready := meta.TREADY
      }

      val updateSources = fullSysGenDescriptor.taskDescriptors.flatMap {
        source =>
          if (
            fullSysGenDescriptor.sendArgumentList
              .getOrElse(source.name, Nil)
              .contains(targetName)
          )
            pes.getOrElse(source.name, Nil)
          else Nil
      }
      require(updateSources.size == network.cfg.nUpdatePEs)
      updateSources.zipWithIndex.foreach { case (pe, index) =>
        val packet =
          pe.getPort("argOut").asInstanceOf[chext.amba.axi4s.Interface]
        val payloadLo = 96
        val offsetLo = payloadLo + network.cfg.updatePayloadWidth
        val semanticWidth = offsetLo + network.cfg.updateOffsetWidth
        // Vitis HLS exposes AXIS TDATA in whole bytes. A compact packet whose
        // offset ends mid-byte therefore has zero padding above its semantic
        // fields (for example, 132 bits is emitted as a 136-bit port).
        val physicalWidth = ((semanticWidth + 7) / 8) * 8
        require(
          packet.cfg.wData == physicalWidth,
          s"$targetName argOut must be {address[64], metadata[32], " +
            s"payload[${network.cfg.updatePayloadWidth}], " +
            s"offset[${network.cfg.updateOffsetWidth}]} padded to " +
            s"$physicalWidth AXIS bits; got ${packet.cfg.wData} bits"
        )
        val sink = network.s_update(index)
        sink.valid := packet.TVALID
        sink.bits.address := network.cfg.lineAddressOf(packet.TDATA(63, 0))
        val metaWidth = sink.bits.metadata.getWidth
        sink.bits.metadata := packet
          .TDATA(64 + metaWidth - 1, 64)
          .asTypeOf(network.cfg.metadataType)
        sink.bits.payload := packet.TDATA(offsetLo - 1, payloadLo)
        sink.bits.offset.foreach { offset =>
          offset := packet.TDATA(semanticWidth - 1, offsetLo)
        }
        packet.TREADY := sink.ready
      }
    }
  }

  /** Instantiate one shared LockServer, wire each participating PE's
    * toLock/fromLock to a lane, and export its HBM master as a dedicated m_axi
    * port.
    */
  private def connectLockServer(
      lc: LockConfig,
      peMap: Map[String, Seq[VitisWriteBufferModule]]
  ): Unit = {

    val lockPEs: Seq[(VitisWriteBufferModule, TaskDescriptor)] =
      fullSysGenDescriptor.taskDescriptors
        .filter(_.participatesInLock)
        .flatMap(t => (peMap(t.name).map(x => (x, t))))
    val number_of_needed_lanes = (for {
      lp <- lockPEs
      c <- 0 until lp._2.lockPorts
    } yield (0)).length
    require(
      number_of_needed_lanes
        == lc.N,
      s"lock lanes ${number_of_needed_lanes} must equal lockConfig.N ${lc.N}"
    )

    val lockServer = Module(
      new LockServer(
        n = lc.N,
        p = lc.P,
        tagStoreSize = lc.tagStoreSize,
        addrW = fullSysGenDescriptor.widthAXIAddress,
        lockTraceCsv = false,
        inflightDepth = lc.inflightDepth
      )
    )
    for (i <- 0 until lc.N) {
      lockServer.io.req(i).valid := false.B
      lockServer.io.req(i).bits := DontCare
      lockServer.io.resp(i).ready := false.B
    }

    for (
      (pe, hasMultiplePorts, index, lane) <-
        (for {
          (pe, desc) <- lockPEs
          index <- 0 until desc.lockPorts
        } yield (pe, desc.lockPorts > 1, index)).zipWithIndex.map {
          case ((a, b, c), d) => (a, b, c, d)
        }
    ) {
      val lockStringAddition = if (hasMultiplePorts) s"$index" else ""
      val toLock = pe
        .getPort(s"toLock$lockStringAddition")
        .asInstanceOf[chext.amba.axi4s.Interface]
      val fromLock =
        pe.getPort(s"fromLock$lockStringAddition")
          .asInstanceOf[chext.amba.axi4s.Interface]
      val req = lockServer.io.req(lane)
      val resp = lockServer.io.resp(lane)

      // PE -> server
      req.valid := toLock.TVALID
      toLock.TREADY := req.ready
      req.bits.tdata := toLock.TDATA
      req.bits.tlast := true.B // single-beat; PE iface has no TLAST under onlyRV

      // server -> PE
      fromLock.TVALID := resp.valid
      resp.ready := fromLock.TREADY
      fromLock.TDATA := resp.bits.tdata
    }

    val gmemYanked = AxiUserYanker(lockServer.io.gmem.asFull)
    val outputCfg = gmemYanked.cfg
    val portName = f"m_axi_${numHbmPortExports}%02d"
    val axiOut = IO(axi4.Master(outputCfg)).suggestName(portName)

    axi4.full.SlaveBuffer(
      gmemYanked,
      axi4.BufferConfig.all(2)
    ) :=> axiOut.asFull

    interfaceBuffer.addOne(
      hdlinfo.Interface(
        portName,
        hdlinfo.InterfaceRole.master,
        hdlinfo.InterfaceKind("axi4"),
        "clock",
        "reset",
        Map("config" -> hdlinfo.TypedObject(axiOut.cfg))
      )
    )

    axiOuts.addOne(axiOut)
    // LockServer has no task-level override, so it inherits the CLI RAMA mode.
    if (enableRamaByDefault)
      ramaPortIndices = ramaPortIndices :+ numHbmPortExports
    numHbmPortExports += 1
  }

  private def connectWatcher(
      wc: WatcherConfig,
      peMap: Map[String, Seq[VitisWriteBufferModule]],
      schedulerMap: Map[String, SchedulerModule],
      newArgumentNotifierMap: Map[String, ArgumentNetworks]
  ): Unit = {
    val maxStatusSlots = 22
    require(wc.statusSlots.size <= maxStatusSlots)

    val maxHbmPorts = 31

    val gmemCfg = axi4.Config(
      wId = 3,
      wAddr = 64,
      wData = 256,
      wUserAR = 1,
      wUserR = 1,
      wUserAW = 1,
      wUserW = 1,
      wUserB = 1,
      axi3Compat = false,
      hasQos = true,
      hasProt = true,
      hasCache = true,
      hasRegion = true,
      hasLock = true
    )

    // Exactly one AXI ID on each physical telemetry master.
    val watcherMemBaseChannels: Seq[Int] = Seq(0, 8)

    val watcher = Module(
      new HLSHelpers.WatcherBlackBox(
        moduleName = wc.moduleName,
        gmemCfg = gmemCfg,
        addrWidth = 64,
        statusSlots = maxStatusSlots,
        maxHbmPorts = maxHbmPorts,
        memBaseChannels = watcherMemBaseChannels
      )
    )

    watcher.io.elements("ap_clk").asInstanceOf[Clock] := clock
    watcher.io.elements("ap_rst_n").asInstanceOf[Bool] := ~reset.asBool
    // mem_0 drives gmem (port A), and mem_8 drives gmem1 (port B).
    // start_addr is the byte offset added in the
    // kernel. The watcher is mapped exclusively to HBM[16:31], whose device address
    // starts at 0x200000000. Vitis does not subtract that base for AXI masters, so
    // both pointers receive 0x200000000. The kernel itself adds 4 GiB for port B
    // (FOUR_GB_BEATS in memAccess.cpp): port A lands in HBM[16:23] and port B in
    // HBM[24:31].
    for (ch <- watcherMemBaseChannels) {
      watcher.getPort(watcher.memBasePin(ch)).asInstanceOf[UInt] :=
        BigInt("200000000", 16).U(64.W)
    }
    watcher.io.elements("start_addr").asInstanceOf[UInt] := BigInt(wc.startAddr)
      .U(64.W)

    def encodingWidth(encoding: String): Int = encoding match {
      case "boolean1"    => 1
      case "readyValid2" => 2
      case other         =>
        throw new RuntimeException(s"unknown watcher encoding '$other'")
    }

    def resolveField(field: WatcherStatusField): UInt = {
      val target = field.target
      target.kind match {
        case "pe" =>
          val pes = peMap.getOrElse(
            target.taskName,
            throw new RuntimeException(
              s"watcher references missing PE task '${target.taskName}'"
            )
          )
          require(target.index >= 0 && target.index < pes.size)
          val (valid, ready) =
            pes(target.index).getWatcherStatusHandshake(target.port)
          chisel3.util.Cat(ready, valid)

        case "schedulerServer" =>
          schedulerMap(target.taskName).io_congested(target.index).asUInt

        case "slowUpdateHandler" | "evictionSaver" | "argumentServer" =>
          newArgumentNotifierMap.get(target.taskName) match {
            case None          => 0.U(2.W)
            case Some(network) =>
              target.kind match {
                case "slowUpdateHandler" =>
                  network.watcherSlowUpdates(target.index)
                case "evictionSaver"  => network.watcherEvictions(target.index)
                case "argumentServer" =>
                  val flatIndex =
                    target.index * network.cfg.newLanesPerServer + target.lane
                  network.watcherFastSpawns(flatIndex)
              }
          }

        case other =>
          throw new RuntimeException(s"unknown watcher target kind '$other'")
      }
    }

    // Each JSON element owns one physical four-bit tap. Fields pack low-to-high
    // in array order; their self-documenting encoding names define their widths.
    wc.statusSlots.zipWithIndex.foreach { case (slot, slotIndex) =>
      val fieldsLowToHigh = slot.fields.map(resolveField)
      val usedWidth = slot.fields.map(f => encodingWidth(f.encoding)).sum
      val highPadding = 4 - usedWidth
      val piecesHighToLow =
        (if (highPadding > 0) Seq(0.U(highPadding.W)) else Seq.empty) ++
          fieldsLowToHigh.reverse
      val raw = chisel3.util.Cat(piecesHighToLow)

      // Uniform registered boundary: prevents X propagation and keeps every bit
      // in a selected nibble aligned to the same sampled cycle.
      watcher.getPort(watcher.statusPinName(slotIndex)) :=
        RegNext(raw, 0.U(4.W))
    }
    for (slotIndex <- wc.statusSlots.size until maxStatusSlots) {
      watcher.getPort(watcher.statusPinName(slotIndex)) := 0.U(4.W)
    }

    val startGate: Bool = globalRunGate.getOrElse {
      val firstDispatch =
        schedulerMap.values
          .flatMap(s =>
            s.io_export.taskOut.map(t => t.TVALID.asBool && t.TREADY.asBool)
          )
          .toSeq
          .reduceOption(_ || _)
          .getOrElse(false.B)
      val startedLatch = RegInit(false.B)
      when(firstDispatch) { startedLatch := true.B }
      startedLatch || firstDispatch
    }
    watcher.getPort("start_gate") := startGate.asUInt

    val nCompute = numHbmPortExports
    for (p <- 0 until maxHbmPorts) {
      if (p < nCompute) {
        val m = axiOuts(p).asFull

        if (m.cfg.write) {
          val wb = Wire(UInt(8.W))
          wb := Mux(m.w.fire, chisel3.util.PopCount(m.w.bits.strb), 0.U)
          watcher.getPort(watcher.wbytesPin(p)) := RegNext(wb, 0.U(8.W))
          val addrHi = math.min(
            fullSysGenDescriptor.widthAXIAddress - 1,
            m.aw.bits.addr.getWidth - 1
          )
          val addrLo = addrHi - 19
          watcher.getPort(watcher.awaddrPin(p)) :=
            chisel3.util.RegEnable(
              m.aw.bits.addr(addrHi, addrLo),
              0.U(20.W),
              m.aw.fire
            )
        } else {
          watcher.getPort(watcher.wbytesPin(p)) := 0.U
          watcher.getPort(watcher.awaddrPin(p)) := 0.U
        }
        if (m.cfg.read) {
          val rb = Wire(UInt(16.W))
          rb := Mux(m.ar.fire, (m.ar.bits.len +& 1.U) << m.ar.bits.size, 0.U)
          watcher.getPort(watcher.rbytesPin(p)) := RegNext(rb, 0.U(16.W))
          val addrHi = math.min(
            fullSysGenDescriptor.widthAXIAddress - 1,
            m.ar.bits.addr.getWidth - 1
          )
          val addrLo = addrHi - 19
          watcher.getPort(watcher.araddrPin(p)) :=
            chisel3.util.RegEnable(
              m.ar.bits.addr(addrHi, addrLo),
              0.U(20.W),
              m.ar.fire
            )
        } else {
          watcher.getPort(watcher.rbytesPin(p)) := 0.U
          watcher.getPort(watcher.araddrPin(p)) := 0.U
        }
      } else {
        watcher.getPort(watcher.wbytesPin(p)) := 0.U
        watcher.getPort(watcher.rbytesPin(p)) := 0.U
        watcher.getPort(watcher.awaddrPin(p)) := 0.U
        watcher.getPort(watcher.araddrPin(p)) := 0.U
      }
    }

    def exportGmemMaster(pinName: String): Unit = {
      val gmem =
        watcher.getPort(pinName).asInstanceOf[axi4.RawInterface].asFull
      val gmemYanked = AxiUserYanker(gmem)
      val outputCfg = gmemYanked.cfg
      val portName = f"m_axi_${numHbmPortExports}%02d"
      val axiOut = IO(axi4.Master(outputCfg)).suggestName(portName)

      axi4.full.SlaveBuffer(
        gmemYanked,
        axi4.BufferConfig.all(2)
      ) :=> axiOut.asFull

      interfaceBuffer.addOne(
        hdlinfo.Interface(
          portName,
          hdlinfo.InterfaceRole.master,
          hdlinfo.InterfaceKind("axi4"),
          "clock",
          "reset",
          Map("config" -> hdlinfo.TypedObject(axiOut.cfg))
        )
      )

      axiOuts.addOne(axiOut)
      numHbmPortExports += 1
    }

    exportGmemMaster("m_axi_gmem")
    exportGmemMaster("m_axi_gmem1")
  }

  // --- buildAndConnectHBM IS NOW GONE ---
  // (It lives in the HasHBMInterconnect trait)

  private def exportPEControl(
      peMap: Map[String, Seq[VitisWriteBufferModule]]
  ): Unit = {
    fullSysGenDescriptor.taskDescriptors.foreach { task =>
      try {
        if (task.hasAXI && peMap.contains(task.name)) {
          val peArray = peMap(task.name)
          for (i <- 0 until task.numProcessingElements) {
            val pe = peArray(i)
            val peName = f"${task.name}_${i}"
            val pes_axi_control = IO(
              chiselTypeOf(
                pe.getPort("s_axi_control").asInstanceOf[axi4.RawInterface]
              )
            ).suggestName(f"${peName}_s_axi_control")

            interfaceBuffer.addOne(
              hdlinfo.Interface(
                f"${peName}_s_axi_control",
                hdlinfo.InterfaceRole.slave,
                hdlinfo.InterfaceKind("axi4"),
                "clock",
                "reset",
                Map("config" -> hdlinfo.TypedObject(pes_axi_control.cfg))
              )
            )
            pes_axi_control :=> pe
              .getPort("s_axi_control")
              .asInstanceOf[axi4.RawInterface]
            interfacesAxiControl.addOne(pes_axi_control)
          }
        }
      } catch {
        case _: Exception =>
          print(
            s"Module has no s_axi_control port, skip"
          ) // Module has no s_axi_control port, skip
      }
    }
  }

  private def generateHdlInfo(): Unit = {
    lazy val hdlinfoModule: hdlinfo.Module = {
      import hdlinfo._
      val basicPorts = Seq(
        Port("clock", PortDirection.input, PortKind.clock),
        Port(
          "reset",
          PortDirection.input,
          PortKind.reset,
          PortSensitivity.resetActiveHigh,
          associatedClock = "clock"
        ),
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

    // HBM port -> module descriptor (built by HasHBMInterconnect). Lets the
    // telemetry viewer label each bandwidth port by the PE/scheduler/etc. attached
    // to it. Copied next to the xclbin and embedded in the trace .bin by the host.
    val wports = new java.io.PrintWriter(
      f"${outputDirPathRTL}/${fullSysGenDescriptor.name}.hbmports.json"
    )
    wports.write(hbmPortMappingJson)
    wports.close()
  }
}
