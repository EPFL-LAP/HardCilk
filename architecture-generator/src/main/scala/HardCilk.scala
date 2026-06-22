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
    override val addressTransformFlag: Boolean = false // Made public for trait
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
    new HardCilkBuilder(fullSysGenDescriptor, debug, argumentNotifierCutCount)

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
  connectPEs(peMap)

  val portsToExport = builder.connectSubsystems(
    schedulerMap,
    allocatorMap,
    notifierMap,
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

  connectGlobalSignals(schedulerMap, allocatorMap, memAllocatorMap, notifierMap)

  // This call now invokes the method from the HasHBMInterconnect trait
  buildAndConnectHBM(
    peMap,
    schedulerMap,
    allocatorMap,
    notifierMap,
    memAllocatorMap,
    spawnNextWBMap,
    sendArgumentWBMap,
    remoteStreamToMemMap
  )

  fullSysGenDescriptor.lockConfig.foreach { lc => connectLockServer(lc, peMap) }

  // Append the watcher LAST so its dedicated HBM master is the highest-index
  // (topmost) m_axi port. Gated on watcherConfig => no effect on other benchmarks.
  fullSysGenDescriptor.watcherConfig.foreach { wc => connectWatcher(wc, peMap) }

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

  /** Instantiate the free-running telemetry watcher, tap each monitored PE's
    * in/out queue handshakes, tie its start_addr to the configured constant, and
    * export its HBM master as the dedicated topmost m_axi port.
    *
    * The watcher is purely observational: it only READS the PEs' AXIS valid/ready
    * (no `<>`), so PE<->scheduler connectivity is untouched. The PE count per task
    * is taken from peMap, so the wiring is dynamic in numProcessingElements.
    */
  private def connectWatcher(
      wc: WatcherConfig,
      peMap: Map[String, Seq[VitisWriteBufferModule]]
  ): Unit = {

    // (statusPrefix, peCount) in the configured order (must match the HLS arrays).
    val monitoredCounts: Seq[(String, Int)] = wc.monitored.map { mon =>
      val pes = peMap.getOrElse(
        mon.taskName,
        throw new RuntimeException(
          s"watcherConfig monitors unknown/instantiated task '${mon.taskName}'"
        )
      )
      (mon.statusPrefix, pes.length)
    }

    // Fixed AXI config matching the synthesized watcher.v gmem master: 512b data
    // (HLS widened the 128b bundle bus), 1-bit id, 64b address, 1-bit user on every
    // channel, full AXI4 (ARLEN=8 => axi3Compat off, qos/prot/cache/region/lock on).
    // The platform HBM adapter does the 512->256 width step.
    val gmemCfg = axi4.Config(
      wId = 1,
      wAddr = 64,
      wData = 512,
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

    val watcher = Module(
      new HLSHelpers.WatcherBlackBox(
        moduleName = wc.moduleName,
        gmemCfg = gmemCfg,
        addrWidth = 64,
        monitored = monitoredCounts
      )
    )

    watcher.io.elements("ap_clk").asInstanceOf[Clock] := clock
    watcher.io.elements("ap_rst_n").asInstanceOf[Bool] := ~reset.asBool
    // mem = m_axi base pointer (offset=direct), start_addr = byte offset added in
    // the kernel. The watcher is mapped exclusively to HBM[16:31], which starts
    // at physical address 0x200000000 in the device address space. Vitis does NOT
    // automatically subtract this base for AXI masters, so the kernel must issue
    // the full physical address. `wc.startAddr` acts as an offset into this window.
    watcher.io.elements("mem").asInstanceOf[UInt] := BigInt("200000000", 16).U(64.W)
    watcher.io.elements("start_addr").asInstanceOf[UInt] := BigInt(wc.startAddr).U(64.W)

    // --- Tap each monitored PE's in/out queue handshakes ---
    // Each status pin is 2 bits carrying the RAW AXIS handshake: bit0 = valid,
    // bit1 = ready (matches the HLS QueueStatus{valid,ready} struct packing).
    // From these the viewer derives empty(=!valid), full(=valid&&!ready), and the
    // transfer events consumed(in_valid&&in_ready) / pushed(out_valid&&out_ready).
    // in_* is the consumer side (PE's input), out_* is the producer side (PE's
    // output) -- so valid/ready mean opposite things on the two queues.
    //
    // Every status bit is passed through a SINGLE uniform RegNext stage so (a)
    // the long PE->watcher path is broken for timing and (b) all bits share the
    // exact same 1-cycle delay -> the watcher samples a coherent snapshot (no
    // cross-bit cycle skew). RegNext on each tap uses the same clock edge, so the
    // delay is identical across every PE and every bit.
    wc.monitored.foreach { mon =>
      val pes = peMap(mon.taskName)
      pes.zipWithIndex.foreach { case (pe, i) =>
        val inIf =
          pe.getPort(mon.inPort).asInstanceOf[chext.amba.axi4s.Interface]
        val outIf =
          pe.getPort(mon.outPort).asInstanceOf[chext.amba.axi4s.Interface]

        val inValid = inIf.TVALID
        val inReady = inIf.TREADY
        val outValid = outIf.TVALID
        val outReady = outIf.TREADY

        // Reset value 0 (NOT a bare RegNext): an uninitialized register starts as
        // X in simulation, and that X propagates through the watcher into its AXI
        // write path -> a malformed transaction that stalls the shared HBM
        // crossbar and hangs compute. Initializing to 0 keeps the uniform 1-cycle
        // delay while guaranteeing defined startup.
        watcher.getPort(watcher.inPinName(mon.statusPrefix, i)) :=
          RegNext(chisel3.util.Cat(inReady, inValid), 0.U(2.W))
        watcher.getPort(watcher.outPinName(mon.statusPrefix, i)) :=
          RegNext(chisel3.util.Cat(outReady, outValid), 0.U(2.W))
      }
    }

    // --- Export gmem as the dedicated topmost m_axi_NN (mirrors connectLockServer) ---
    val gmem =
      watcher.getPort("m_axi_gmem").asInstanceOf[axi4.RawInterface].asFull
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
  }
}
