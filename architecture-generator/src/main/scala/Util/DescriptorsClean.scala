package Descriptors

import chisel3.util.{isPow2, log2Ceil}
import scala.collection.mutable
import org.slf4j.{LoggerFactory, Logger} // For logging warnings
import scala.collection.mutable.ListBuffer

// --- Helper Objects ---

// Use a common logger for all descriptor warnings
object DescriptorLogger {
  val logger: Logger = LoggerFactory.getLogger(this.getClass)
}

case class MemSystemDescriptor(
    var schedulerServersBaseAddresses: Seq[Int] = Seq.empty,
    var spawnerServersBaseAddresses: Seq[Int] = Seq.empty,
    var allocationServersBaseAddresses: Seq[Int] = Seq.empty,
    var memoryAllocatorServersBaseAddresses: Seq[Int] = Seq.empty
)

case class PortDescriptor(
    parentName: String,
    parentType: String,
    parentIndex: Int = 0, // Defaulted
    portType: String,
    portIndex: Int = 0 // Defaulted
) {
  def validate(): Unit = {
    require(
      parentType == "HardCilk" || parentType == "PE" || parentType == "mem",
      s"Invalid parentType: $parentType"
    )
    require(parentIndex >= 0, "parentIndex must be >= 0")
    require(
      Set(
        "taskIn",
        "taskOut",
        "taskInGlobal",
        "taskOutGlobal",
        "argIn",
        "argOut",
        "closureIn",
        "closureOut",
        "mallocIn",
        "mallocOut"
      ).contains(portType),
      s"Invalid portType: $portType"
    )
    require(portIndex >= 0, "portIndex must be >= 0")
  }

  def getFormatedPortName(descriptor: FullSysGenDescriptor): String = {
    // ... (this logic remains the same)
    if (parentType == "PE") {
      val portTypeMap = Map("argOut" -> "addrOut", "closureIn" -> "contIn")
      f"${parentName}_${parentIndex}/${portTypeMap.getOrElse(portType, portType)}"
    } else if (parentType == "HardCilk") {
      val side =
        if (portType == "taskIn" || portType == "taskOut") "scheduler"
        else if (portType == "closureOut") "closureAllocator"
        else if (portType == "argIn") "argumentNotifier"
        else "memoryAllocator"
      f"${descriptor.name}_0/${parentName}_${side}_${portType}_${portIndex}"
    } else {
      "err"
    }
  }
}

case class ConnectionDescriptor(
    srcPort: PortDescriptor,
    dstPort: PortDescriptor,
    bitWidth: Int = 0, // Defaulted
    connectionType: String = "AXIS" // Defaulted
) {
  def validate(): Unit = {
    srcPort.validate()
    dstPort.validate()
  }
}

case class SystemConnections(
    connections: List[ConnectionDescriptor]
)

// ... (MemStats, InterconnectDescriptor remain the same) ...
case class InterconnectDescriptor(
    count: Int,
    ratio: Int
)

case class MemStats(
    totalAXIPorts: Int,
    interconnectDescriptors: List[InterconnectDescriptor]
)

// inflightDepth = per-PE in-flight credit budget: how many lock requests a PE
// may have unresolved inside the server at once. 1 mimics the original
// one-request-at-a-time behaviour; raise it to let a PE pipeline locks.
case class LockConfig(N: Int, P: Int, tagStoreSize: Int, inflightDepth: Int = 1)

// --- Watcher (telemetry) configuration ---
// Optional and default-off: when present, the generator builds and wires the
// free-running shared `watcher` HLS kernel. Each source is self-describing:
// `kind` names the hardware object, while the
// remaining fields identify one instance and, where needed, one port/signal/lane.
case class WatcherStatusTarget(
    kind: String,
    taskName: String,
    index: Int = 0,
    port: String = "",
    signal: String = "",
    lane: Int = 0
)

// Fields pack into their physical four-bit slot in array order, low-to-high.
// The encoding name carries its width: boolean1 is one bit; readyValid2 is two
// bits with bit0=valid and bit1=ready. Unused upper slot bits are tied to zero.
case class WatcherStatusField(
    encoding: String,
    target: WatcherStatusTarget
)

case class WatcherStatusSlot(
    label: String = "",
    fields: List[WatcherStatusField] = Nil
)
case class WatcherConfig(
    hdlPath: String = "../hls-kernel-output/watcher/watcher",
    moduleName: String = "watcher",
    startAddr: Long = 0L,                  // kernel-relative base tie-off (HBM[16:31] window => 0)
    statusSlots: List[WatcherStatusSlot] = Nil
)

case class LegacySideOverrides(
    numVirtualServers: Option[Int] = None,
    capacityVirtualQueue: Option[Int] = None,
    capacityPhysicalQueue: Option[Int] = None,
    portWidth: Option[Int] = None
)

case class LegacyTaskOverrides(
    spawnServersCount: Option[Int] = None,
    spawnerQueueDepth: Option[Int] = None
)

// --- SideConfig with default handling ---
case class SideConfig(
    sideType: String,
    numVirtualServers: Int = 0,
    capacityVirtualQueue: Int = 0,
    capacityPhysicalQueue: Int = 0,
    useAffinity: Boolean = false,
    affinityQueueDepth: Int = 0,
    affinityTagBits: Int = 0,
    portWidth: Int = 32,
    virtualEntrtyWidth: Int = 0,
    numSpawnerServer: Int = 0,
    // Internal resolved-mode bit. GeneratorProfile overwrites any decoded value;
    // descriptors select the implementation through --argument-server instead.
    useNewArgumentNotifier: Boolean = false,
    slowArgumentHandlerCount: Int = 1,
    // Number of independent CacheEvictionSaver / eviction AXI ports. Each also
    // becomes one lane of the EvictionGater's completion-ordering fence (one
    // pendingCount/doneCount pair per lane, all the same width -- there is no
    // separate per-lane JSON knob, this single count sizes every lane
    // uniformly). Independent of slowArgumentHandlerCount: evictions and slow
    // updates are demuxed by address separately, so the two counts need not
    // match.
    cacheEvictionSaverCount: Int = 1,
    newContinuationLanesPerServer: Int = 1,
    // Number of adjacent, private cache lanes used round-robin by each
    // continuation-source PE. A value of 1 preserves the historical static
    // source-to-lane mapping. The physical lane count must be divisible by this
    // factor so every source owns one disjoint lane group within a server.
    newContinuationLaneStripingFactor: Int = 1,
    directUpdateLanesPerServer: Int = 1,
    // Cut count for the slow-update collection network (servers -> slow
    // handlers).
    argumentNotifierCutCount: Int = 1,
    // Cut count for the eviction collection network (servers -> eviction-saver
    // lanes). Same trade-off as argumentNotifierCutCount.
    evictionCutCount: Int = 1,
    argumentServerIdWidth: Int = 6,
    cacheDelayCycles: Int = 0,
    // Extra ArgumentServer coupledQ slots for missed updates with no co-cycle
    // eviction. Throughput knob for the non-backpressuring front porch; too small
    // only throttles (never incorrect). See ArgumentServer.scala.
    missedUpdateExtra: Int = 64,
    slowRequestQueueDepth: Int = 64,
    legacyOverrides: Option[LegacySideOverrides] = None
) {
  def validate(): Unit = {
    require(
      Set("scheduler", "allocator", "argumentNotifier", "memoryAllocator")
        .contains(sideType),
      s"Invalid sideType: $sideType"
    )

    if (portWidth == 32) { // '32' is the default
      DescriptorLogger.logger.warn(
        s"Task side '$sideType' is using default portWidth=32. " +
          "Ensure this is intended or specify 'portWidth' in the JSON."
      )
    }
    require(affinityQueueDepth >= 0)
    require(affinityTagBits >= 0)
    if (useAffinity) {
      require(sideType == "scheduler", "Affinity is only valid on scheduler sides")
      require(affinityQueueDepth > 0)
      require(affinityTagBits > 0)
    } else if (affinityQueueDepth != 0 || affinityTagBits != 0) {
      // Tolerate affinity fields left in the JSON with useAffinity=false so the
      // knobs can be toggled by flipping a single flag. They carry no meaning
      // here -- `normalized` strips them from the version passed forward.
      DescriptorLogger.logger.warn(
        s"Task side '$sideType' has affinityQueueDepth/affinityTagBits set but " +
          "useAffinity=false; these fields are ignored and stripped."
      )
    }
    if (sideType == "argumentNotifier" && useNewArgumentNotifier) {
      require(slowArgumentHandlerCount > 0)
      require(cacheEvictionSaverCount > 0)
      require(newContinuationLanesPerServer > 0)
      require(newContinuationLaneStripingFactor > 0)
      require(
        newContinuationLanesPerServer % newContinuationLaneStripingFactor == 0,
        "newContinuationLanesPerServer must be divisible by " +
          "newContinuationLaneStripingFactor"
      )
      require(directUpdateLanesPerServer > 0)
      require(
        newContinuationLanesPerServer <= directUpdateLanesPerServer + 1,
        "newContinuationLanesPerServer exceeds the direct update lanes plus " +
          "the redirect-ring input"
      )
      require(argumentNotifierCutCount > 0)
      require(evictionCutCount > 0)
      require(argumentServerIdWidth > 0)
      require(cacheDelayCycles >= 0)
      require(missedUpdateExtra >= 1)
      require(slowRequestQueueDepth > 0)
    }
  }

  // Strip affinity sizing knobs when the feature is disabled so downstream
  // consumers never see stale queue-depth/tag-bit values. Lets the JSON keep
  // those fields around for easy toggling via a single `useAffinity` flag.
  def normalized: SideConfig =
    if (useAffinity) this
    else copy(affinityQueueDepth = 0, affinityTagBits = 0)

  def resolved(profile: Util.GeneratorProfile): SideConfig = {
    val selected = if (profile.isLegacy) {
      legacyOverrides.fold(this) { legacy =>
        copy(
          numVirtualServers = legacy.numVirtualServers.getOrElse(numVirtualServers),
          capacityVirtualQueue = legacy.capacityVirtualQueue.getOrElse(capacityVirtualQueue),
          capacityPhysicalQueue = legacy.capacityPhysicalQueue.getOrElse(capacityPhysicalQueue),
          portWidth = legacy.portWidth.getOrElse(portWidth)
        )
      }
    } else this
    selected.copy(
      useNewArgumentNotifier = profile.usesCachedArgumentServer,
      legacyOverrides = None
    ).normalized
  }
}

// --- TaskDescriptor with validation ---
case class TaskDescriptor(
    name: String,
    peVersion: String = "1.0",
    peHDLPath: String = "",
    isRoot: Boolean,
    isCont: Boolean,
    dynamicMemAlloc: Boolean,
    numProcessingElements: Int,
    // Drive the HLS PE's ap_none `peIndex` input with its physical PE-array index.
    injectPeIndex: Boolean = false,
    peIndexBits: Int = 0,
    widthTask: Int,
    widthMalloc: Int = 0, // Defaulted
    variableSpawn: Boolean = false, // Defaulted
    sidesConfigs: List[SideConfig],
    var mgmtBaseAddresses: MemSystemDescriptor = MemSystemDescriptor(),
    spawnServersCount: Int = 0, // Defaulted
    // Per-SpawnerServer on-chip taskQueue depth. Sizes the outside-spawn injection
    // buffer on this task's local ring. Smaller depths backpressure the upstream
    // spawner (and, through it, the injecting PE) sooner -> fewer tasks outstanding
    // -> tighter cache working set. Default preserves the historical hardcoded 16.
    spawnerQueueDepth: Int = 16,
    // Depth of this task's co-located spawnNext write buffer -- the staging queue
    // that holds each released child task pending its closure write + argument-
    // notifier metadata assignment. VCD-measured as the dominant reservoir of
    // outstanding child (e.g. memReader) tasks; shrinking it backpressures the
    // spawning PE sooner and caps how many children (and downstream continuations)
    // are in flight. Default 128 preserves the historical WriteBufferCounter depth.
    spawnNextWriteBufferDepth: Int = 128,
    hasAXI: Boolean = true,
    // When true, every PE instance of this task gets its OWN reserved HBM port
    // for its main compute master (m_axi_gmem) -- never muxed with any other
    // master. One port per PE instance. Use for bandwidth-critical PEs (e.g.
    // countDecoupled's memReader) so they are never throttled by port sharing.
    dedicatedAxiPort: Boolean = false,
    // Tri-state RAMA override for every PE instance's main m_axi_gmem master:
    // omitted/None -> inherit the command-line RAMA mode
    // false        -> never attach RAMA
    // true         -> always attach RAMA (striped only with --rama-striping)
    generateRAMA: Option[Boolean] = None,
    // When > 0, the main compute masters (m_axi_gmem) of ALL this task's PE
    // instances are CONSOLIDATED onto exactly this many reserved HBM ports,
    // regardless of the PE count. The reserved port(s) form a flat mux carrying
    // only these masters (each keeps a fair 1/k share) and are never re-muxed
    // with unrelated masters. Use for PEs whose main port fires rarely (e.g.
    // countDecoupled's taskInitiator reentry, which only writes on "done").
    // Mutually exclusive with dedicatedAxiPort.
    totalAxiPorts: Int = 0,
    participatesInLock: Boolean =
      false, // Whether this task's PEs get lock req/resp lanes
    lockPorts: Int = 1,
    isAIE: Boolean = false,
    generateSpawnNextWriteBuffer: Boolean = false,
    generateArgOutWriteBuffer: Boolean = false,
    argumentSizeList: List[Int] = List(),
    // Width of the aligned payload-slot selector carried by argOut when its
    // target uses NewArgumentNotifier.  It is intentionally explicit in JSON,
    // but FullSysGenDescriptor.validate derives the required value and rejects
    // any mismatch.  A full-continuation payload has zero offset bits and omits
    // the field from the physical packet.
    argumentOffsetWidth: Option[Int] = None,
    // Payload width stored by the no-cache argDataOut write buffer. This applies
    // to both updated-no-cache and legacy-2469686 builds. It is independent from
    // argumentSizeList, which describes cached update payloads.
    argumentWriteDataWidth_NoCache: Int = 0,
    taskId: Int = 0, // Defaulted
    peHDLVariants: Map[String, String] = Map.empty,
    legacyOverrides: Option[LegacyTaskOverrides] = None
) {
  // Helper methods are fine to keep here
  def getNumServers(sideType: String): Int = { //
    sidesConfigs
      .find(_.sideType == sideType)
      .map(_.numVirtualServers)
      .getOrElse(0)
  }
  def getSideConfig(sideType: String): Option[SideConfig] =
    sidesConfigs.find(_.sideType == sideType)

  def normalized: TaskDescriptor =
    copy(sidesConfigs = sidesConfigs.map(_.normalized))

  def resolved(profile: Util.GeneratorProfile): TaskDescriptor = {
    val argumentKey = profile.argumentServer.cliName
    val selectedPath =
      if (peHDLVariants.isEmpty) peHDLPath
      else peHDLVariants.getOrElse(
        argumentKey,
        throw new IllegalArgumentException(
          s"Task '$name': peHDLVariants is mode-specific but has no '$argumentKey' path"
        )
      )
    val selected = if (profile.isLegacy) {
      legacyOverrides.fold(this) { legacy =>
        copy(
          spawnServersCount = legacy.spawnServersCount.getOrElse(spawnServersCount),
          spawnerQueueDepth = legacy.spawnerQueueDepth.getOrElse(spawnerQueueDepth)
        )
      }
    } else this
    selected.copy(
      peHDLPath = selectedPath,
      generateArgOutWriteBuffer = selected.generateArgOutWriteBuffer ||
        (!profile.usesCachedArgumentServer && selected.argumentWriteDataWidth_NoCache > 0),
      sidesConfigs = selected.sidesConfigs.map(_.resolved(profile)),
      // Address assignment mutates this legacy container during elaboration.
      // Give the resolved build its own instance so the parsed source descriptor
      // remains reusable for another profile in the same JVM.
      mgmtBaseAddresses = MemSystemDescriptor(),
      peHDLVariants = Map.empty,
      legacyOverrides = None
    )
  }

  def usesNewArgumentNotifier: Boolean =
    getSideConfig("argumentNotifier").exists(_.useNewArgumentNotifier)
  def getCapacityVirtualQueue(sideType: String): Int = { //
    sidesConfigs
      .find(_.sideType == sideType)
      .map(_.capacityVirtualQueue)
      .getOrElse(0)
  }
  def getCapacityPhysicalQueue(sideType: String): Int = { //
    sidesConfigs
      .find(_.sideType == sideType)
      .map(_.capacityPhysicalQueue)
      .getOrElse(0)
  }
  // ... (other get... methods) ...

  def validate(): Unit = {
    sidesConfigs.foreach(_.validate())

    getSideConfig("scheduler").foreach { scheduler =>
      require(
        scheduler.portWidth == widthTask,
        s"Task '$name': scheduler portWidth=${scheduler.portWidth} must equal " +
          s"widthTask=$widthTask; differing scheduler port widths are unsupported"
      )
    }

    require(
      numProcessingElements > 0,
      s"Task '$name': numProcessingElements must be > 0"
    )
    require(peIndexBits >= 0, s"Task '$name': peIndexBits must be >= 0")
    require(
      argumentOffsetWidth.forall(_ >= 0),
      s"Task '$name': argumentOffsetWidth must be >= 0"
    )
    require(
      argumentSizeList.size <= 1,
      s"Task '$name': multiple argumentSizeList entries are not supported yet; " +
        "the generator currently supports only one argument payload width per task"
    )
    require(
      peHDLVariants.keySet.subsetOf(Set("cached", "no-cache")),
      s"Task '$name': peHDLVariants keys must be cached or no-cache"
    )
    if (injectPeIndex) {
      require(
        peIndexBits > 0,
        s"Task '$name': injectPeIndex requires peIndexBits > 0"
      )
      require(
        BigInt(numProcessingElements - 1) < (BigInt(1) << peIndexBits),
        s"Task '$name': peIndexBits=$peIndexBits cannot represent " +
          s"$numProcessingElements PE indices"
      )
    } else {
      require(
        peIndexBits == 0,
        s"Task '$name': peIndexBits requires injectPeIndex=true"
      )
    }
    require(
      isPow2(widthTask) && widthTask <= 1024,
      s"Task '$name': widthTask must be power of 2 and <= 1024"
    )
    require(
      !(dedicatedAxiPort && totalAxiPorts > 0),
      s"Task '$name': dedicatedAxiPort and totalAxiPorts are mutually exclusive"
    )
    require(
      !(generateRAMA.contains(true) && totalAxiPorts > 0),
      s"Task '$name': generateRAMA and totalAxiPorts are mutually exclusive because each RAMA PE master needs an unshared HBM port"
    )
    if (generateRAMA.contains(true)) {
      require(
        hasAXI,
        s"Task '$name': generateRAMA applies to the PEs' m_axi_gmem masters, but hasAXI is false"
      )
      require(
        peHDLPath.nonEmpty,
        s"Task '$name': generateRAMA needs PEs in the design, but no 'peHDLPath' is set"
      )
    }

    if (peHDLPath.nonEmpty) {
      require(
        new java.io.File(peHDLPath).exists,
        s"Task '$name': peHDLPath not found at '$peHDLPath'"
      )
    } else {
      DescriptorLogger.logger.warn(
        s"Task '$name' has no 'peHDLPath'. Ports will be exported."
      )
    }

    // A scheduler server is the HBM-backed virtual queue that seeds/persists a
    // task's tasks. Root tasks are seeded by the host into that backing store, so
    // they REQUIRE at least one. A non-root task is fed purely by spawn/outside-
    // spawn and can run with zero scheduler servers: its local ring + spawner hold
    // and backpressure all in-flight work (no HBM spill), which is exactly what we
    // want on hot rings like countDecoupled's memReader.
    require(
      getNumServers("scheduler") > 0 || !isRoot,
      s"Task '$name': only root tasks may omit scheduler servers (isRoot=true needs > 0)"
    )
    // ... (all other 'asserts' converted to 'require') ...

    require(
      dynamicMemAlloc && widthMalloc > 0 || !dynamicMemAlloc && widthMalloc == 0,
      s"Task '$name': dynamicMemAlloc requires widthMalloc > 0"
    )

    if (isCont) {
      // require(getNumServers("allocator") > 0, s"Task '$name' (Cont): must have > 0 allocator servers")
      require(
        getNumServers("argumentNotifier") > 0,
        s"Task '$name' (Cont): must have > 0 argumentNotifier servers"
      )
    }

    if (dynamicMemAlloc) {
      require(
        getNumServers("memoryAllocator") > 0,
        s"Task '$name' (DynMem): must have > 0 memoryAllocator servers"
      )
    }

    if (generateArgOutWriteBuffer) {
      require(
        !argumentSizeList.isEmpty,
        s"Task '$name': argumentSizeList must not be empty!"
      )
      require(
        argumentSizeList.head > 0,
        s"Task '$name': argumentWidth must be > 0 to has a write buffer!"
      )
    }

  }
}

// --- FullSysGenDescriptor with validation ---
case class FullSysGenDescriptor(
    name: String,
    widthAddress: Int,
    widthContCounter: Int,
    taskDescriptors: List[TaskDescriptor],
    spawnList: Map[String, List[String]],
    spawnNextList: Map[String, List[String]],
    sendArgumentList: Map[String, List[String]],
    mallocList: Map[String, List[String]] = Map.empty,
    // cfgAxiHardCilk: chext.amba.axi4.Config = chext.amba.axi4.Config(), // This class is not defined, commenting out
    targetFrequency: Int = 250,
    memorySizeSim: Int = 1,
    fpgaModel: String = "ALVEO_U55C",
    isVitisProject: Boolean = false,
    keepAXI4Interfaces: Boolean = false,
    mFPGASynth: Boolean = false,
    mFPGASimulation: Boolean = false,
    lockConfig: Option[LockConfig] = None,
    watcherConfig: Option[WatcherConfig] = None,
    maximumAXIPorts: Int = 32,
    hasAXIDMAInput: Boolean = false,
    transformAXI: Boolean = false,
    transformPattern: List[Int] = List(),
    widthAXIAddress: Int = 34,
    fpgaCountSim: Int = 1,
    resolvedArchitecture: String = "updated"
) {
  // --- All helper logic is kept here ---
  // Assign base addresses
  var j = 0
  val base = if (isVitisProject) 0x10 else 0x0

  taskDescriptors.foreach(task => {
    task.mgmtBaseAddresses = MemSystemDescriptor()
    val numSchedulerServers = task.getNumServers("scheduler")
    for (i <- j until j + numSchedulerServers) {
      task.mgmtBaseAddresses.schedulerServersBaseAddresses =
        task.mgmtBaseAddresses.schedulerServersBaseAddresses :+ ((i << 6) + base)
    }
    j += numSchedulerServers
    println("J value after scheduler: " + j)

    if (resolvedArchitecture == "legacy" && task.spawnServersCount > 0) {
      for (i <- j until j + task.spawnServersCount) {
        task.mgmtBaseAddresses.spawnerServersBaseAddresses =
          task.mgmtBaseAddresses.spawnerServersBaseAddresses :+ ((i << 6) + base)
      }
      j += task.spawnServersCount
    }
    println("J value after spawner: " + j)

    if (task.isCont) {
      val numAllocationServers = task.getNumServers("allocator")
      for (i <- j until j + numAllocationServers) {
        task.mgmtBaseAddresses.allocationServersBaseAddresses =
          task.mgmtBaseAddresses.allocationServersBaseAddresses :+ ((i << 6) + base)
      }
      j += numAllocationServers
    }
    println("J value after allocator: " + j)

    if (task.dynamicMemAlloc) {
      val numMemoryAllocatorServers = task.getNumServers("memoryAllocator")
      for (i <- j until j + numMemoryAllocatorServers) {
        task.mgmtBaseAddresses.memoryAllocatorServersBaseAddresses =
          task.mgmtBaseAddresses.memoryAllocatorServersBaseAddresses :+ ((i << 6) + base)
      }
      j += numMemoryAllocatorServers
    }
    println("J value after memory allocator: " + j)
  })

  // For each task log base addresses
  taskDescriptors.foreach(task =>
    println(
      f"Task: ${task.name}:  task.mgmtBaseAddresses: ${task.mgmtBaseAddresses}"
    )
  )

  def getMfpgaBaseAddress(): Int = {
    (j << 6) + base
  }

  // Helper functions
  def selfSpawnedCount(task_name: String): Int = {
    spawnList.get(task_name) match {
      case Some(spawnedTasks) =>
        if (spawnedTasks.contains(task_name))
          taskDescriptors
            .find(_.name == task_name)
            .map(_.numProcessingElements)
            .getOrElse(0)
        else 0
      case None => 0
    }
  }

  def getPortCount(port_type: String, task_name: String): Int = {
    if (port_type == "spawn") {
      return spawnList.iterator.map { case (srcTaskName, spawnedTasks) =>
        if (srcTaskName == task_name) {
          0
        } else {
          val srcTask = taskDescriptors.find(_.name == srcTaskName).get
          spawnedTasks.count(_ == task_name) * srcTask.numProcessingElements
        }
      }.sum
    }

    // Get the correct map based on the port_type
    val map = port_type match {
      case "spawnNext"    => spawnNextList
      case "sendArgument" => sendArgumentList
      case "mallocIn"     => mallocList
      case _              =>
        throw new IllegalArgumentException(s"Invalid port type: $port_type")
    }

    // Get the total number of processing elements that needs that type of port
    val totalProcessingElements = map
      .filter { case (_, mapped_list) => mapped_list.contains(task_name) }
      .keys
      .flatMap(taskName => taskDescriptors.find(_.name == taskName))

    var sum = 0
    totalProcessingElements.foreach { task =>
      sum += task.numProcessingElements
    }

    sum
  }

  def getSystemConnectionsDescriptor(): SystemConnections = {
    // mutable map of aggregators from string to int initialized to zero
    val aggregatorMapSpawn = mutable.Map[String, Int]().withDefaultValue(0)
    val aggregatorMapSendArg = mutable.Map[String, Int]().withDefaultValue(0)
    val aggregatorMapSpawnNext = mutable.Map[String, Int]().withDefaultValue(0)
    val aggregatorMapMalloc = mutable.Map[String, Int]().withDefaultValue(0)

    val connections = taskDescriptors.flatMap { task =>
      val spawnedTasks = spawnList.getOrElse(task.name, List())
      val argumentTasks = sendArgumentList.getOrElse(task.name, List())
      val mallocTasks = mallocList.getOrElse(task.name, List())
      val spawnNextTasks = spawnNextList.getOrElse(task.name, List())

      val taskConnections = (0 until task.numProcessingElements).map { i =>
        ConnectionDescriptor(
          PortDescriptor(f"${task.name}", "HardCilk", 0, "taskOut", i),
          PortDescriptor(task.name, "PE", i, "taskIn", 0),
          task.widthTask,
          "AXIS"
        )
      }

      val selfSpawnedConnections = (0 until selfSpawnedCount(task.name)).map {
        i =>
          ConnectionDescriptor(
            PortDescriptor(task.name, "PE", i, "taskOut", 0),
            PortDescriptor(f"${task.name}", "HardCilk", 0, "taskIn", i),
            task.widthTask,
            "AXIS"
          )
      }

      // Number global spawn inputs PE-major: all output ports from PE 0, then
      // all output ports from PE 1, and so on. This keeps duplicated targets
      // (for example two memReader launches per reentry PE) adjacent at the
      // destination scheduler.
      val globalSpawnedTasks = spawnedTasks.filterNot(_ == task.name)
      val spawnedConnections =
        (0 until task.numProcessingElements).flatMap { i =>
          globalSpawnedTasks.zipWithIndex.map { case (spawnedTask, j) =>
            val spawnedTaskDescriptor =
              taskDescriptors.find(_.name == spawnedTask).get
            aggregatorMapSpawn(spawnedTask) += 1
            ConnectionDescriptor(
              PortDescriptor(task.name, "PE", i, "taskOutGlobal", j),
              PortDescriptor(
                f"${spawnedTask}",
                "HardCilk",
                0,
                "taskInGlobal",
                aggregatorMapSpawn(spawnedTask) - 1
              ),
              spawnedTaskDescriptor.widthTask,
              "AXIS"
            )
          }
        }

      val argumentConnections = argumentTasks.zipWithIndex.flatMap {
        case (argumentTask, j) =>
          taskDescriptors.find(_.name == argumentTask).get
          (0 until task.numProcessingElements).map { i =>
            aggregatorMapSendArg(argumentTask) += 1
            ConnectionDescriptor(
              PortDescriptor(task.name, "PE", i, "argOut", j),
              PortDescriptor(
                f"${argumentTask}",
                "HardCilk",
                0,
                "argIn",
                aggregatorMapSendArg(argumentTask) - 1
              ),
              widthAddress,
              "AXIS"
            )
          }
      }

      val spawnNextConnections = spawnNextTasks.zipWithIndex.flatMap {
        case (spawnNextTask, j) =>
          taskDescriptors.find(_.name == spawnNextTask).get
          (0 until task.numProcessingElements).map { i =>
            aggregatorMapSpawnNext(spawnNextTask) += 1
            ConnectionDescriptor(
              PortDescriptor(
                f"${spawnNextTask}",
                "HardCilk",
                0,
                "closureOut",
                aggregatorMapSpawnNext(spawnNextTask) - 1
              ),
              PortDescriptor(task.name, "PE", i, "closureIn", 0),
              widthAddress, // This is only an address disbrutor for now...
              "AXIS"
            )
          }
      }

      val mallocConnections = mallocTasks.zipWithIndex.flatMap {
        case (mallocTask, j) =>
          taskDescriptors.find(_.name == mallocTask).get
          (0 until task.numProcessingElements).map { i =>
            aggregatorMapMalloc(mallocTask) += 1
            ConnectionDescriptor(
              PortDescriptor(
                f"${mallocTask}",
                "HardCilk",
                0,
                "mallocOut",
                aggregatorMapMalloc(mallocTask) - 1
              ),
              PortDescriptor(task.name, "PE", i, "mallocIn", 0),
              widthAddress, // This is only an address distrbutor for now
              "AXIS"
            )
          }
      }

      taskConnections ++ selfSpawnedConnections ++ spawnedConnections ++ argumentConnections ++ spawnNextConnections ++ mallocConnections
    }

    SystemConnections(connections)
  }

  def getNumConfigPorts(): Int = {
    taskDescriptors.map(_.getNumServers("scheduler")).sum + taskDescriptors
      .map(_.getNumServers("memoryAllocator"))
      .sum + taskDescriptors.map(_.getNumServers("allocator")).sum +
      (if (resolvedArchitecture == "legacy") taskDescriptors.map(_.spawnServersCount).sum else 0) +
      {
        if (mFPGASynth || mFPGASimulation) 1 else 0
      } +
      {
        var count_info_ports = 0
        if (mFPGASynth || mFPGASimulation) {
          // Add an extra one for each task type
          count_info_ports += taskDescriptors.length

          // Add an extra one for each task with task.generateArgOutWriteBuffer set
          taskDescriptors.foreach(task => {
            if (task.generateArgOutWriteBuffer) {
              count_info_ports += 1
            }
          })

          // Add an extra one for all the arg notifiers existing in each task
          taskDescriptors.foreach(task => {
            count_info_ports += task.getNumServers("argumentNotifier")
          })
        }
        count_info_ports
      }
  }

  def getSystemAXIPortsNames(reduce_axi: Int): List[String] = {
    Seq.tabulate(reduce_axi)(i => f"m_axi_${i}%02d").toList
  }

  def getMemoryConnectionsStats(reduce_axi: Int): MemStats = {
    val interconnectDescriptors = ListBuffer[InterconnectDescriptor]()

    val totalAXIPorts = reduce_axi

    var optimizer = totalAXIPorts

    var iteration = 0
    do {
      val ratio = (optimizer / (32.0 - iteration)).ceil.toInt
      interconnectDescriptors += InterconnectDescriptor(1, ratio)
      optimizer = optimizer - ratio
      iteration += 1
    } while (optimizer > 0)
    assert(optimizer == 0)

    // In the interconnectDescriptors list aggregate the entries with the same ratios into one entry
    val interconnectDescriptorsAggregated = interconnectDescriptors
      .groupBy(_.ratio)
      .map { case (ratio, descriptors) =>
        InterconnectDescriptor(descriptors.map(_.count).sum, ratio)
      }
      .toList

    assert(interconnectDescriptorsAggregated.map(_.count).sum <= 32)

    MemStats(totalAXIPorts, interconnectDescriptorsAggregated)
  }

  def normalized: FullSysGenDescriptor =
    copy(taskDescriptors = taskDescriptors.map(_.normalized))

  def resolved(profile: Util.GeneratorProfile): FullSysGenDescriptor =
    copy(
      taskDescriptors = taskDescriptors.map(_.resolved(profile)),
      resolvedArchitecture = profile.architecture.cliName
    ).normalized

  def validate(): Unit = {
    taskDescriptors.foreach(_.validate()) // Validate all sub-tasks

    require(
      isPow2(widthAddress) && widthAddress <= 64,
      "widthAddress must be power of 2 and <= 64"
    )
    require(
      isPow2(widthContCounter) && widthContCounter <= 64,
      "widthContCounter must be power of 2 and <= 64"
    )
    require(taskDescriptors.nonEmpty, "must have at least one taskDescriptor")

    val taskNames = taskDescriptors.map(_.name).toSet
    require(
      spawnList.keys.forall(taskNames.contains),
      s"spawnList contains unknown task names: ${spawnList.keys.filterNot(taskNames.contains)}"
    )
    // ... (rest of list checks) ...
    require(
      spawnNextList.keys.forall(taskNames.contains),
      "spawnNextList contains unknown task names"
    )
    require(
      sendArgumentList.keys.forall(taskNames.contains),
      "sendArgumentList contains unknown task names"
    )
    require(
      mallocList.keys.forall(taskNames.contains),
      "mallocList contains unknown task names"
    )

    require(fpgaModel == "ALVEO_U55C", s"Unsupported fpgaModel: $fpgaModel")

    // A NewArgumentNotifier update is a compact OR payload plus an aligned
    // payload-slot selector.  Every source feeding one target shares the same
    // physical packet type, so both widths must agree.  Keeping the selector
    // width explicit in JSON makes the ABI visible while these checks prevent
    // it from drifting away from the continuation/payload geometry.
    taskDescriptors.filter(_.usesNewArgumentNotifier).foreach { target =>
      val sources = taskDescriptors.filter(source =>
        sendArgumentList.getOrElse(source.name, Nil).contains(target.name)
      )
      require(
        sources.nonEmpty,
        s"Task '${target.name}' uses NewArgumentNotifier but has no argument-update sources"
      )

      val packetShapes = sources.map { source =>
        require(
          source.argumentSizeList.nonEmpty,
          s"Task '${source.name}' must specify argumentSizeList when updating NewArgumentNotifier target '${target.name}'"
        )
        val payloadWidth = source.argumentSizeList.max
        require(
          isPow2(payloadWidth),
          s"Task '${source.name}': NewArgumentNotifier payload width $payloadWidth must be a power of two"
        )
        require(
          payloadWidth <= target.widthTask && target.widthTask % payloadWidth == 0,
          s"Task '${source.name}': payload width $payloadWidth must divide continuation width ${target.widthTask} for '${target.name}'"
        )
        val slotCount = target.widthTask / payloadWidth
        require(
          isPow2(slotCount),
          s"Task '${source.name}': continuation/payload slot count $slotCount must be a power of two"
        )
        val requiredOffsetWidth = log2Ceil(slotCount)
        require(
          source.argumentOffsetWidth.nonEmpty,
          s"Task '${source.name}' must explicitly specify argumentOffsetWidth for NewArgumentNotifier target '${target.name}'"
        )
        val offsetWidth = source.argumentOffsetWidth.get
        require(
          offsetWidth == requiredOffsetWidth,
          s"Task '${source.name}': argumentOffsetWidth=$offsetWidth, expected $requiredOffsetWidth for ${target.widthTask}-bit continuation / $payloadWidth-bit payload"
        )
        (payloadWidth, offsetWidth)
      }.distinct

      require(
        packetShapes.size == 1,
        s"All argument-update sources for '${target.name}' must use one payload/offset shape; found ${packetShapes.mkString(", ")}"
      )
    }

    // Check if the system is supposed to support MFPGA, and has argument notification is that
    // tasks with argument notifiers must have contigous ids startting from ID zero
    if (mFPGASynth || mFPGASimulation) {
      // Create a list of the tasks with argument notifiers
      val id_list = taskDescriptors
        .filter(_.getNumServers("argumentNotifier") > 0)
        .map(_.taskId)

      // Require that id_list is contigous starting with ID 0
      var decesion = true
      for (i <- 0 until id_list.length - 1) {
        if (id_list(i) + 1 != id_list(i + 1)) {
          decesion = false
        }
      }
      require(
        decesion,
        "To support mfpga the IDs of tasks with argument notifiers must be contigous and starting from zero.\n"
      )

    }

    // No task may opt into locking unless a lockConfig is present to serve it.
    if (lockConfig.isEmpty) {
      val orphans = taskDescriptors.filter(_.participatesInLock).map(_.name)
      require(
        orphans.isEmpty,
        s"Tasks have participatesInLock=true but no top-level lockConfig is set: ${orphans.mkString(", ")}"
      )
    }

    lockConfig.foreach { lc =>
      require(isPow2(lc.P), "lockConfig.P must be a power of two")
      require(lc.P <= lc.N, "lockConfig.P must be <= N")
      require(
        lc.tagStoreSize % lc.P == 0,
        "lockConfig.tagStoreSize must be a multiple of P"
      )
      require(
        lc.N % (2 * lc.P) == 0,
        "lockConfig.N must be a multiple of 2*P (AMU bucketing)"
      )
      // N must equal the total number of lock-participating PE lanes. A task opts in
      // via participatesInLock=true; each of its PEs gets one lane. For BFS only
      // the helper participates: 16 helper PEs = 16 lanes.
      val lockLanes = taskDescriptors
        .filter(_.participatesInLock)
        .map(x => x.numProcessingElements * x.lockPorts)
        .sum
      require(
        lockLanes == lc.N,
        s"lockConfig.N (${lc.N}) must equal the total PEs of tasks with participatesInLock=true ($lockLanes)"
      )
      require(
        lockLanes > 0,
        "lockConfig is set but no task has participatesInLock=true"
      )
    }

    // Watcher: validate the fixed physical slot budget and all descriptor-level
    // references. Port existence and NewArgumentNotifier vector bounds are
    // re-checked at elaboration when the corresponding modules exist.
    watcherConfig.foreach { wc =>
      require(wc.statusSlots.nonEmpty, "watcherConfig.statusSlots must not be empty")
      require(
        wc.statusSlots.size <= 22,
        s"watcherConfig.statusSlots has ${wc.statusSlots.size} entries; the 88-bit STATUS field holds at most 22"
      )
      wc.statusSlots.zipWithIndex.foreach { case (slot, slotIndex) =>
        require(slot.fields.nonEmpty,
          s"watcher status slot $slotIndex must contain at least one field")
        def encodingWidth(encoding: String): Int = encoding match {
          case "boolean1" => 1
          case "readyValid2" => 2
          case other => throw new IllegalArgumentException(
            s"watcher status slot $slotIndex has unknown encoding '$other' (expected boolean1 or readyValid2)")
        }
        val packedWidth = slot.fields.map(f => encodingWidth(f.encoding)).sum
        require(packedWidth <= 4,
          s"watcher status slot $slotIndex packs $packedWidth bits; a physical slot holds 4")

        slot.fields.zipWithIndex.foreach { case (field, fieldIndex) =>
          val target = field.target
          val task = taskDescriptors.find(_.name == target.taskName)
          target.kind match {
            case "pe" =>
              require(field.encoding == "readyValid2",
                s"watcher slot $slotIndex field $fieldIndex PE targets require readyValid2")
              require(task.nonEmpty,
                s"watcher slot $slotIndex field $fieldIndex references unknown PE task '${target.taskName}'")
              require(target.index >= 0 && target.index < task.get.numProcessingElements,
                s"watcher slot $slotIndex field $fieldIndex PE index ${target.index} is outside ${target.taskName}[0,${task.get.numProcessingElements})")
              require(target.port.nonEmpty,
                s"watcher slot $slotIndex field $fieldIndex PE target requires port")

            case "schedulerServer" =>
              require(field.encoding == "boolean1",
                s"watcher slot $slotIndex field $fieldIndex schedulerServer targets require boolean1")
              require(target.signal == "congested",
                s"watcher slot $slotIndex field $fieldIndex schedulerServer signal must be congested")
              require(task.nonEmpty,
                s"watcher slot $slotIndex field $fieldIndex references unknown scheduler task '${target.taskName}'")
              val n = task.get.getNumServers("scheduler")
              require(target.index >= 0 && target.index < n,
                s"watcher slot $slotIndex field $fieldIndex scheduler index ${target.index} is outside ${target.taskName}[0,$n)")

            case "slowUpdateHandler" | "evictionSaver" | "argumentServer" =>
              require(field.encoding == "readyValid2",
                s"watcher slot $slotIndex field $fieldIndex ${target.kind} targets require readyValid2")
              require(task.nonEmpty,
                s"watcher slot $slotIndex field $fieldIndex references unknown argument task '${target.taskName}'")
              val side = task.get.getSideConfig("argumentNotifier")
              require(side.nonEmpty,
                s"watcher slot $slotIndex field $fieldIndex references task '${target.taskName}' without an argumentNotifier side")
              // Cached-only observer fields retain their physical slot in
              // no-cache/legacy builds and are tied to zero at elaboration.
              // Bounds that describe cached hardware are therefore meaningful
              // only when that hardware is selected.
              if (task.get.usesNewArgumentNotifier) target.kind match {
                case "slowUpdateHandler" =>
                  require(target.port == "input",
                    s"watcher slowUpdateHandler port must be input")
                  require(target.index >= 0 && target.index < side.get.slowArgumentHandlerCount,
                    s"watcher slowUpdateHandler index ${target.index} is outside ${target.taskName}[0,${side.get.slowArgumentHandlerCount})")
                case "evictionSaver" =>
                  require(target.port == "input",
                    s"watcher evictionSaver port must be input")
                  require(target.index >= 0 && target.index < side.get.cacheEvictionSaverCount,
                    s"watcher evictionSaver index ${target.index} is outside ${target.taskName}[0,${side.get.cacheEvictionSaverCount})")
                case "argumentServer" =>
                  require(target.port == "fastSpawn",
                    s"watcher argumentServer port must be fastSpawn")
                  require(target.index >= 0 && target.index < side.get.numVirtualServers,
                    s"watcher argumentServer index ${target.index} is outside ${target.taskName}[0,${side.get.numVirtualServers})")
                  require(target.lane >= 0 && target.lane < side.get.newContinuationLanesPerServer,
                    s"watcher argumentServer lane ${target.lane} is outside [0,${side.get.newContinuationLanesPerServer})")
              }

            case other => throw new IllegalArgumentException(
              s"watcher slot $slotIndex field $fieldIndex has unknown target kind '$other'")
          }
        }
      }
    }
  }
}

// ... (FullSysGenDescriptorExtended remains the same) ...
case class FullSysGenDescriptorExtended(
    fullSysGenDescriptor: FullSysGenDescriptor,
    systemConnections: SystemConnections,
    val memStats: MemStats
)
object FullSysGenDescriptorExtended {
  def fromFullSysGenDescriptor(
      fullSysGenDescriptor: FullSysGenDescriptor
  ): FullSysGenDescriptorExtended = {
    val systemConnections =
      fullSysGenDescriptor.getSystemConnectionsDescriptor()
    val memStats = fullSysGenDescriptor.getMemoryConnectionsStats(
      32
    ) // Note: 32 is hardcoded here
    FullSysGenDescriptorExtended(
      fullSysGenDescriptor,
      systemConnections,
      memStats
    )
  }
}

// --- ALL JSON OBJECTS AND HELPERS ARE MOVED TO DescriptorJSON.scala ---
