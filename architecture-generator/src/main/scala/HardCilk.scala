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

class HardCilk(
    override val fullSysGenDescriptor: FullSysGenDescriptor, // Made public for trait
    outputDirPathRTL: String,
    debug: Boolean,
    override val reduceAxi: Int, // Made public for trait
    unitedHbm: Boolean,
    isSimulation: Boolean,
    argumentNotifierCutCount: Int,
    override val addressTransformFlag: Boolean = false, // Made public for trait
    // Opt-in kernel-global start broadcast (default OFF -> byte-identical to the
    // pre-feature design). When ON: one host-writable register releases all
    // scheduler servers on the same cycle and anchors the watcher start gate.
    enableGlobalStart: Boolean = false
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
    new HardCilkBuilder(fullSysGenDescriptor, debug, argumentNotifierCutCount, enableGlobalStart)

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
  val newNotifierMap = blueprint.newArgNotifierFactories.map { case (name, factory) =>
    name -> Module(factory())
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
  connectManagement(
    demux,
    schedulerMap,
    allocatorMap,
    memAllocatorMap,
    notifierMap,
    remoteStreamToMemMap
  )
  // ---- Kernel-global start broadcast register --------------------------------
  // One host-writable 64-bit register whose bit0 ("globalRun") fans out to every
  // scheduler server's io_globalRun AND to the telemetry watcher's start_gate. The
  // host clears each server's rPause while this is 0, then writes it 1 once so ALL
  // servers leave pause on the SAME cycle (instead of one-at-a-time as each rPause
  // write lands over the slow hw_emu AXI-lite path). Sits on the last demux port
  // (index == getNumConfigPorts), host address (getNumConfigPorts << 6) + base.
  //
  // Resets to 0: the system is HELD until the host's single release write, so
  // startSystem() MUST write this to 1 (it does). This 0->1 edge is also the
  // watcher's start gate (see connectWatcher) -- a deterministic "compute starts
  // now" anchor, so cycle_count 0 == release and the first accept can never be
  // dropped (the gate leads the first dispatch by many cycles).
  val globalRunGate: Option[Bool] =
    if (!enableGlobalStart) None
    else Some {
      val globalRunReg = RegInit(0.U(64.W))
      val globalRunBlock =
        new axi4.lite.components.RegisterBlock(wAddr = 6, wData = 64, wMask = 6)
      demux.m_axil(fullSysGenDescriptor.getNumConfigPorts()) :=> globalRunBlock.s_axil
      globalRunBlock.base(0x00)
      globalRunBlock.reg(
        globalRunReg,
        read = true,
        write = true,
        desc = "Kernel-global start broadcast: bit0 releases all scheduler servers + watcher"
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

  connectGlobalSignals(schedulerMap, allocatorMap, memAllocatorMap, notifierMap, newNotifierMap)

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
      scheds: Map[String, Scheduler],
      allocs: Map[String, Allocator],
      notifiers: Map[String, ArgumentNotifier],
      memAllocs: Map[String, Allocator],
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
    // +1 master for the kernel-global start-broadcast register (a RegisterBlock at
    // demux index == getNumConfigPorts(), connected in the body below) ONLY when the
    // feature is enabled. All existing server config ports keep their indices/
    // addresses; this one lands right after. Off -> unchanged master count.
    val numMasters =
      fullSysGenDescriptor.getNumConfigPorts() + (if (enableGlobalStart) 1 else 0)
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
      schedulerMap: Map[String, Scheduler],
      closureAllocatorMap: Map[String, Allocator],
      memoryAllocatorMap: Map[String, Allocator],
      argumentNotifierMap: Map[String, ArgumentNotifier],
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

  /** Wire the two PE-side write streams and the explicit metadata channels.
    * Source ordering is descriptor order followed by PE index, the same order
    * used by FullSysGenDescriptor.getPortCount.
    */
  private def connectNewArgumentNotifiers(
      networks: Map[String, ArgumentNetworks],
      pes: Map[String, Seq[VitisWriteBufferModule]]
  ): Unit = {
    networks.foreach { case (targetName, network) =>
      val newSources = fullSysGenDescriptor.taskDescriptors.flatMap { source =>
        if (fullSysGenDescriptor.spawnNextList.getOrElse(source.name, Nil).contains(targetName))
          pes.getOrElse(source.name, Nil)
        else Nil
      }
      require(newSources.size == network.cfg.nSourcePEs)
      newSources.zipWithIndex.foreach { case (pe, index) =>
        val spawnWrite = pe.getPort("m_axi_spawnNext")
          .asInstanceOf[axi4.RawInterface].asFull
        val newCont = network.s_axi_newCont(index).asFull
        // The wrapper's historical AXI declaration includes unused read
        // channels; NewArgumentNotifier is deliberately write-only.
        spawnWrite.aw :=> newCont.aw
        spawnWrite.w :=> newCont.w
        newCont.b :=> spawnWrite.b

        val meta = pe.getPort("continuationMetaIn").asInstanceOf[chext.amba.axi4s.Interface]
        val returned = network.m_continuation(index)
        meta.TVALID := returned.valid
        meta.TDATA := returned.bits.metadata.asUInt.pad(32)
        returned.ready := meta.TREADY
      }

      val updateSources = fullSysGenDescriptor.taskDescriptors.flatMap { source =>
        if (fullSysGenDescriptor.sendArgumentList.getOrElse(source.name, Nil).contains(targetName))
          pes.getOrElse(source.name, Nil)
        else Nil
      }
      require(updateSources.size == network.cfg.nUpdatePEs)
      updateSources.zipWithIndex.foreach { case (pe, index) =>
        val packet = pe.getPort("argOut").asInstanceOf[chext.amba.axi4s.Interface]
        val lineWidth = network.cfg.continuationSize
        val dataLo = 96
        val strobeLo = dataLo + lineWidth
        require(packet.cfg.wData == strobeLo + lineWidth,
          s"$targetName argOut must be {address[64], metadata[32], data[$lineWidth], strobe[$lineWidth]}")
        val sink = network.s_update(index)
        sink.valid := packet.TVALID
        sink.bits.address := network.cfg.lineAddressOf(packet.TDATA(63, 0))
        val metaWidth = sink.bits.metadata.getWidth
        sink.bits.metadata := packet.TDATA(64 + metaWidth - 1, 64)
          .asTypeOf(network.cfg.metadataType)
        sink.bits.dataWrite := packet.TDATA(strobeLo - 1, dataLo)
        sink.bits.dataWriteStrobe := packet.TDATA(strobeLo + lineWidth - 1, strobeLo)
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

    // --- A. Deterministic lane assignment ---
    // Walk taskDescriptors (stable order), not peMap, so lanes are reproducible.
    // BFS: the 16 sparse_edgemap_helper PEs become lanes 0..15.
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
    ) // tripwire; validate() guarantees it

    // --- B. Instantiate and tie off every lane (unconnected lanes stay safely idle) ---
    // addrW matches the HBM port address width (widthAXIAddress, 34) so the lock
    // tags, tag store, and AMU master are all native HBM-width -- no 64->34 address
    // transition, and the tag-store comparators are 34-bit instead of 64-bit.
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

    // --- C. Connect endpoints (last-connect semantics override the tie-off above) ---
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

    // --- D. Export io.gmem as its own dedicated m_axi_NN ---
    // STRATEGY #2 (direct wire): connect gmem straight to its own HBM port with
    // NO ProtocolConverter (so no IdSerialize id-collapse) and NO Widen. The
    // exported port matches gmem EXACTLY (64-bit data, full amuId+lane id width),
    // so every outstanding atomic keeps a UNIQUE HBM id => at most one in flight
    // per id => the per-id response-ordering assumption can never be violated.
    // The platform's AXI-compliant HBM adapter performs the 64->256 width step.
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
    numHbmPortExports += 1
  }

  /** Instantiate the free-running telemetry watcher, wire the twenty-two generic
    * status taps selected by the descriptor, tie start_addr to the configured
    * constant, and export its two HBM masters as the topmost m_axi ports.
    *
    * The watcher is purely observational: it only READS the PEs' AXIS valid/ready
    * (no `<>`), so PE<->scheduler connectivity is untouched.
    */
  private def connectWatcher(
      wc: WatcherConfig,
      peMap: Map[String, Seq[VitisWriteBufferModule]],
      schedulerMap: Map[String, Scheduler],
      newArgumentNotifierMap: Map[String, ArgumentNetworks]
  ): Unit = {
    val maxStatusSlots = 22
    require(wc.statusSlots.size <= maxStatusSlots)

    // Number of per-HBM-port bandwidth/address pin groups on the watcher (matches
    // the kernel MAX_HBM_PORTS). The actual exported compute masters are wired below;
    // any remaining pins are tied to 0.
    val maxHbmPorts = 31

    // Fixed AXI config matching the synthesized watcher.v gmem master: 256b data
    // (a 256-bit beat = two 128-bit telemetry bundles), 3-bit id, 64b address, 1-bit
    // user on every channel, full AXI4 (ARLEN=8 => axi3Compat off, qos/prot/cache/
    // region/lock on). Must match watcher.v C_M_AXI_GMEM_DATA_WIDTH.
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
    watcher.io.elements("start_addr").asInstanceOf[UInt] := BigInt(wc.startAddr).U(64.W)

    def encodingWidth(encoding: String): Int = encoding match {
      case "boolean1" => 1
      case "readyValid2" => 2
      case other => throw new RuntimeException(s"unknown watcher encoding '$other'")
    }

    def resolveField(field: WatcherStatusField): UInt = {
      val target = field.target
      target.kind match {
        case "pe" =>
          val pes = peMap.getOrElse(target.taskName,
            throw new RuntimeException(s"watcher references missing PE task '${target.taskName}'"))
          require(target.index >= 0 && target.index < pes.size)
          val (valid, ready) = pes(target.index).getWatcherStatusHandshake(target.port)
          chisel3.util.Cat(ready, valid)

        case "schedulerServer" =>
          schedulerMap(target.taskName).io_congested(target.index).asUInt

        case "slowUpdateHandler" | "evictionSaver" | "argumentServer" =>
          val network = newArgumentNotifierMap.getOrElse(
            target.taskName,
            throw new RuntimeException(
              s"watcher references missing new argument notifier '${target.taskName}'"
            )
          )
          target.kind match {
            case "slowUpdateHandler" => network.watcherSlowUpdates(target.index)
            case "evictionSaver" => network.watcherEvictions(target.index)
            case "argumentServer" =>
              val flatIndex = target.index * network.cfg.newLanesPerServer + target.lane
              network.watcherFastSpawns(flatIndex)
          }

        case other => throw new RuntimeException(
          s"unknown watcher target kind '$other'")
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

    // --- Start gate ---
    // With the global-start feature ON: drive the gate from the kernel-global
    // broadcast (globalRunGate). globalRun is the host's single "go" write in
    // startSystem(): it resets to 0 (watcher idle, all scheduler servers held) and
    // rises exactly once when the host releases the system. That 0->1 edge is a
    // DETERMINISTIC compute-start anchor -- cycle_count 0 == release -- and it leads
    // the first task dispatch by many cycles (servers must read HBM, fill buffers,
    // and serve a steal before any accept), so the first accept has a wide margin
    // and can never be dropped.
    //
    // With the feature OFF (default): fall back to the original heuristic -- the
    // gate opens on the first scheduler dispatch, driven a cycle EARLY by OR-ing the
    // registered latch with the combinational firstDispatch so the T+1 accept isn't
    // lost to the watcher HLS's internal sampling skew. This path is byte-identical
    // to the pre-feature design.
    val startGate: Bool = globalRunGate.getOrElse {
      val firstDispatch =
        schedulerMap.values
          .flatMap(s => s.io_export.taskOut.map(t => t.TVALID.asBool && t.TREADY.asBool))
          .toSeq
          .reduceOption(_ || _)
          .getOrElse(false.B)
      val startedLatch = RegInit(false.B)
      when(firstDispatch) { startedLatch := true.B }
      startedLatch || firstDispatch
    }
    watcher.getPort("start_gate") := startGate.asUInt

    // --- Per-HBM-port bandwidth + address taps ---
    // For each exported compute master we register, with reset-init 0 (same X-startup
    // hazard avoidance as the status taps): the per-cycle write bytes (popcount WSTRB),
    // the per-cycle read bytes ((ARLEN+1)<<ARSIZE of an issued burst), and the most-
    // recent AW/AR address bits [39:20] (1 MB granularity, tapped now, used later for
    // region stats). NB: HBM addresses live at ~0x1_0000_0000.. so the *top* 20 bits
    // [63:44] are always zero -- bits [39:20] are the ones that actually distinguish
    // regions (graph vs scheduler) while still covering the full 16 GB map. This
    // only READS axiOuts (the exported masters) and never drives them, so the compute
    // datapath is untouched. axiOuts already holds the compute masters here (the
    // watcher's own port is appended afterwards). Pins beyond the exported count are 0.
    val nCompute = numHbmPortExports
    for (p <- 0 until maxHbmPorts) {
      if (p < nCompute) {
        val m = axiOuts(p).asFull
        // Some internal masters (notably CacheEvictionSaver) are deliberately
        // write-only. Never ask chext for a channel disabled by the AXI config:
        // its accessor correctly throws `Not supported: read/write`.
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
              m.aw.bits.addr(addrHi, addrLo), 0.U(20.W), m.aw.fire)
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
              m.ar.bits.addr(addrHi, addrLo), 0.U(20.W), m.ar.fire)
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

    // --- Export both gmem masters as dedicated topmost m_axi_NN ports ---
    // Port A (m_axi_gmem) is exported first so it gets the lower index; port B
    // (m_axi_gmem1) is next. renderConnCfg maps the last two masters to the two
    // watcher HBM windows (port A -> HBM[16:23], port B -> HBM[24:31]).
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
