package Descriptors


import chisel3.util.isPow2
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
    var allocationServersBaseAddresses: Seq[Int] = Seq.empty,
    var memoryAllocatorServersBaseAddresses: Seq[Int] = Seq.empty,
    // A subset of schedulerServersBaseAddresses: the entries that are spawner servers rather
    // than scheduler servers. Same register layout, so the driver treats them alike; this vector
    // only exists so the host can tell them apart.
    var spawnerServersBaseAddresses: Seq[Int] = Seq.empty
)

case class PortDescriptor(
    parentName: String,
    parentType: String,
    parentIndex: Int = 0, // Defaulted
    portType: String,
    portIndex: Int = 0  // Defaulted
) {
  def validate(): Unit = {
    require(parentType == "HardCilk" || parentType == "PE" || parentType == "mem", s"Invalid parentType: $parentType")
    require(parentIndex >= 0, "parentIndex must be >= 0")
    require(
      Set("taskIn", "taskOut", "taskInGlobal", "taskOutGlobal", "argIn", "argOut",
          "closureIn", "closureOut", "mallocIn", "mallocOut").contains(portType),
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

// --- SideConfig with default handling ---
case class SideConfig(
    sideType: String,
    numVirtualServers: Int = 0,
    capacityVirtualQueue: Int = 0,
    capacityPhysicalQueue: Int = 0,
    portWidth: Int = 32,
    virtualEntrtyWidth: Int = 0,
    numSpawnerServer: Int = 0
) {
  def validate(): Unit = {
    require(Set("scheduler", "allocator", "argumentNotifier", "memoryAllocator").contains(sideType),
      s"Invalid sideType: $sideType")

    if (portWidth == 32) { // '32' is the default
      DescriptorLogger.logger.warn(
        s"Task side '$sideType' is using default portWidth=32. " +
        "Ensure this is intended or specify 'portWidth' in the JSON."
      )
    }
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
    widthTask: Int,
    widthMalloc: Int = 0, // Defaulted
    variableSpawn: Boolean = false, // Defaulted
    sidesConfigs: List[SideConfig],
    var mgmtBaseAddresses: MemSystemDescriptor = MemSystemDescriptor(),
    spawnServersCount: Int = 0, // Defaulted
    hasAXI: Boolean = true,
    // Put a Xilinx RAMA (Random Access Memory Attachment) IP in front of the HBM
    // ports this task's PEs reach memory through. RAMA reorders/fragments random
    // accesses so the HBM controller sees a friendlier pattern, but it is only
    // worth its area when the port carries this task's traffic alone, so setting
    // this also makes the HBM interconnect give those PE masters their own
    // dedicated (un-muxed) HBM ports -- see HasHBMInterconnect.buildAndConnectHBM.
    // Applies to the `m_axi_gmem*` data masters only: the spawnNext/argOut
    // closure writes stay in the shared muxing pool, since RAMA buys nothing on
    // their short sequential bursts.
    generateRAMA: Boolean = false,
    isAIE: Boolean = false,
    generateSpawnNextWriteBuffer: Boolean = false,
    generateArgOutWriteBuffer: Boolean = false,
    // Per-argDataOut payload width (bits), keyed by the exact argData stream port
    // name written by the PE ("argDataOut" for a single destination, or
    // "argDataOut_<continuation>" for each destination of a multi-target send).
    argumentSizeList: Map[String, Int] = Map(),
    // Number of ordered write-buffer beats this continuation's closure is written
    // in (1 = fits in one beat; >1 = closure wider than the buffer's per-beat
    // payload, so the PE emits that many sequential spawn_next writes).
    closureWriteBeats: Int = 1,
    taskId: Int = 0, // Defaulted
    tag: Int = 0 // Continuation ID; defaulted for non-continuation tasks
) {
  // Helper methods are fine to keep here
  def getNumServers(sideType: String): Int = { //
    sidesConfigs.find(_.sideType == sideType).map(_.numVirtualServers).getOrElse(0)
  }
  def getCapacityVirtualQueue(sideType: String): Int = { //
    sidesConfigs.find(_.sideType == sideType).map(_.capacityVirtualQueue).getOrElse(0)
  }
  def getCapacityPhysicalQueue(sideType: String): Int = { //
    sidesConfigs.find(_.sideType == sideType).map(_.capacityPhysicalQueue).getOrElse(0)
  }
  // ... (other get... methods) ...
  
  def validate(): Unit = {
    sidesConfigs.foreach(_.validate())
    
    require(numProcessingElements > 0, s"Task '$name': numProcessingElements must be > 0")
    require(isPow2(widthTask) && widthTask <= 1024, s"Task '$name': widthTask must be power of 2 and <= 1024")
    
    if (peHDLPath.nonEmpty) {
      require(new java.io.File(peHDLPath).exists, s"Task '$name': peHDLPath not found at '$peHDLPath'")
    } else {
      DescriptorLogger.logger.warn(s"Task '$name' has no 'peHDLPath'. Ports will be exported.")
    }

    require(getNumServers("scheduler") > 0, s"Task '$name': must have > 0 scheduler servers")
    // ... (all other 'asserts' converted to 'require') ...
    
    require(dynamicMemAlloc && widthMalloc > 0 || !dynamicMemAlloc && widthMalloc == 0,
      s"Task '$name': dynamicMemAlloc requires widthMalloc > 0")

    if (isCont) {
      //require(getNumServers("allocator") > 0, s"Task '$name' (Cont): must have > 0 allocator servers")
      require(getNumServers("argumentNotifier") > 0, s"Task '$name' (Cont): must have > 0 argumentNotifier servers")
    }
    
    if (dynamicMemAlloc) {
       require(getNumServers("memoryAllocator") > 0, s"Task '$name' (DynMem): must have > 0 memoryAllocator servers")
    }
    
    if(generateArgOutWriteBuffer) {
      require(argumentSizeList.nonEmpty, s"Task '$name': argumentSizeList must not be empty!")
      require(argumentSizeList.values.forall(_ > 0), s"Task '$name': every argumentWidth must be > 0 to have a write buffer!")
    }

    require(closureWriteBeats >= 1, s"Task '$name': closureWriteBeats must be >= 1")

    if (generateRAMA) {
      require(peHDLPath.nonEmpty,
        s"Task '$name': generateRAMA needs PEs in the design, but no 'peHDLPath' is set")
      require(hasAXI,
        s"Task '$name': generateRAMA applies to the PEs' m_axi_gmem masters, but hasAXI is false")
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
    maximumAXIPorts: Int = 32,
    hasAXIDMAInput: Boolean = false,
    transformAXI: Boolean = false,
    transformPattern: List[Int] = List(),
    widthAXIAddress: Int = 34,
    fpgaCountSim: Int = 1,
    // Optional absolute (or explicit) path to the driver software project to copy
    // into the generated software project. When None, the emitter falls back to
    // the relative `../software/${jsonName}` (or mfpga variant) convention.
    driverSoftwarePath: Option[String] = None,
    // Kill switch for the streaming scheduler. Set to false to build the classic
    // Scheduler for every task, as before.
    useDataFlowScheduler: Boolean = true,
    // How many outstanding reads/writes the exported `m_axi_XX` masters advertise
    // to the block design. Vivado infers the AXI interfaces of the HardCilk top by
    // port name and, with nothing to go on, assumes 2 -- which makes every
    // SmartConnect on the HBM path build a 2-deep transaction tracker and throttle
    // the design to two reads in flight. The emitter turns this into an
    // X_INTERFACE_PARAMETER attribute on the generated Verilog, which propagates
    // through the SmartConnects (and RAMA IPs) during block-design validation.
    axiNumOutstanding: Int = 256
) {

  // --- Scheduler kind -------------------------------------------------------
  // A task that never spawns itself has no work to steal back from its own PEs, so the queue,
  // the steal network and the scheduler servers of the classic Scheduler are dead weight and it
  // gets the streaming DataFlowScheduler instead. The root task keeps the classic scheduler: it
  // is the one the host seeds with the initial closure. mFPGA is excluded because the remote
  // steal path (RemoteTaskServer, m_axis_remote) has no DataFlowScheduler equivalent yet.
  def usesDataFlowScheduler(task: TaskDescriptor): Boolean =
    useDataFlowScheduler &&
      !mFPGASynth && !mFPGASimulation &&
      !task.isRoot &&
      selfSpawnedCount(task.name) == 0

  /** `n`, the number of task inputs a DataFlowScheduler is built with. Must mirror the arguments
    * HardCilkBuilder passes, otherwise the hardware and the address map disagree.
    */
  def schedulerInputCount(task: TaskDescriptor): Int =
    getPortCount("spawn", task.name) + task.getNumServers("argumentNotifier")

  /** Reproduces the `outsideSpawn` gate of Scheduler.scala, which is what decides whether a
    * classic scheduler actually instantiates its spawner servers.
    */
  def classicOutsideSpawn(task: TaskDescriptor): Boolean = {
    val globalIn = getPortCount("spawn", task.name)
    val argRoute = task.getNumServers("argumentNotifier")

    ((globalIn + argRoute) > 0) && (argRoute > 0 || globalIn > 0)
  }

  /** Number of SchedulerServers actually built for a task. A DataFlowScheduler has none, even
    * though the descriptor still carries a "scheduler" SideConfig (which is what sizes the
    * spawner's virtual FIFO on the host side).
    */
  def schedulerServerCount(task: TaskDescriptor): Int =
    if (usesDataFlowScheduler(task)) 0 else task.getNumServers("scheduler")

  /** Number of SpawnerServers actually built for a task.
    *
    * For a dataflow task this comes from the cycle analysis alone, `spawnServersCount` in the JSON
    * is ignored. For a classic task it is `spawnServersCount`, but only when the scheduler really
    * builds them: Scheduler.scala guards them with `outsideSpawn`, and without this guard the
    * management port index would run ahead of the hardware and shift every later task's registers.
    */
  def spawnerCount(task: TaskDescriptor): Int =
    if (usesDataFlowScheduler(task)) { if (dataFlowSpawnerTasks.contains(task.name)) 1 else 0 }
    else if (classicOutsideSpawn(task)) task.spawnServersCount
    else 0

  // --- Task dependency graph and deadlock avoidance -------------------------
  // A DataFlowScheduler has no virtualized queue, so a cycle of dataflow tasks backpressures onto
  // itself and deadlocks. Every cycle therefore needs at least one task that can spill to DRAM.

  /** Directed graph over task names, from the union of the three task relations.
    *
    * `spawnList` and `sendArgumentList` deliver work; `spawnNextList` wires a continuation's
    * closure port and does not deliver a task, but it does carry backpressure (a task stalls when
    * its continuation's allocator runs dry), so including it is the conservative choice.
    */
  lazy val taskGraph: Map[String, Set[String]] = {
    val names = taskDescriptors.map(_.name).toSet
    val relations = Seq(spawnList, spawnNextList, sendArgumentList)

    names.map { from =>
      from -> relations.flatMap(_.getOrElse(from, Nil)).filter(names.contains).toSet
    }.toMap
  }

  /** Whether a task can already absorb an unbounded backlog, and so cannot be the link that closes
    * a deadlock.
    *
    * The classic scheduler always can: `validate()` requires at least one scheduler server, and a
    * scheduler server is a virtual queue that spills to DRAM. This is why cycles were never a
    * problem before the DataFlowScheduler existed, and it means the analysis below only has to
    * worry about cycles made up entirely of dataflow tasks.
    */
  private def hasVirtualizedQueue(task: TaskDescriptor): Boolean =
    !usesDataFlowScheduler(task)

  /** Tasks that must be given a SpawnerServer to keep the system deadlock free.
    *
    * A greedy feedback vertex set: repeatedly take a cyclic strongly connected component and
    * remove its cheapest member, until the graph is acyclic. Because the residual graph is
    * provably acyclic when the loop ends, every cycle has had one of its nodes chosen. This is
    * why elementary cycles are not enumerated: an SCC can hold exponentially many of them, and
    * one well-placed spawner usually covers them all (in triangleDAE, one node covers three).
    *
    * "Cheapest" is the task with the fewest scheduler inputs, so the host has to serve as few
    * spawners as possible and preferably small ones. Ties break on descriptor order, never on Map
    * iteration order, so the generated hardware is reproducible.
    */
  lazy val dataFlowSpawnerTasks: Set[String] = {
    val byName = taskDescriptors.map(t => t.name -> t).toMap
    val order = taskDescriptors.map(_.name).zipWithIndex.toMap
    val dataFlowTasks = taskDescriptors.filter(usesDataFlowScheduler).map(_.name).toSet

    // Tasks that already break cycles on their own play no further part.
    var alive = taskDescriptors.filterNot(hasVirtualizedQueue).map(_.name).toSet
    val chosen = mutable.LinkedHashSet.empty[String]

    var searching = true
    while (searching) {
      searching = false

      stronglyConnectedComponents(alive).foreach { scc =>
        val isCyclic = scc.size > 1 || scc.headOption.exists(n => taskGraph(n).contains(n))

        if (isCyclic && !scc.exists(chosen.contains)) {
          val candidates = scc.toSeq.sortBy(n => (schedulerInputCount(byName(n)), order(n)))

          val pick = candidates.find(dataFlowTasks.contains).getOrElse {
            throw new IllegalStateException(
              s"Task cycle [${scc.toSeq.sortBy(order).mkString(" -> ")}] has no task that can hold " +
                "a spawner server: none of them uses the DataFlowScheduler and none has a " +
                "virtualized queue. Set spawnServersCount >= 1 on one of them."
            )
          }

          chosen += pick
          alive -= pick
          searching = true
        }
      }
    }

    chosen.toSet
  }

  /** Strongly connected components of `taskGraph` restricted to `nodes`.
    *
    * Straight from the definition, via reachability: `u` and `v` share a component when each
    * reaches the other. Task graphs have a handful of nodes, so the quadratic closure costs
    * nothing and is far easier to check by eye than a lowlink algorithm.
    */
  private def stronglyConnectedComponents(nodes: Set[String]): Seq[Set[String]] = {
    def reachableFrom(start: String): Set[String] = {
      val seen = mutable.Set.empty[String]
      val pending = mutable.Stack(start)

      while (pending.nonEmpty) {
        taskGraph.getOrElse(pending.pop(), Set.empty).filter(nodes.contains).foreach { next =>
          if (seen.add(next)) pending.push(next)
        }
      }
      seen.toSet
    }

    val reaches = nodes.map(n => n -> reachableFrom(n)).toMap
    val ordered = nodes.toSeq.sorted

    ordered
      .map(n => (ordered.filter(m => reaches(n).contains(m) && reaches(m).contains(n)).toSet + n))
      .distinct
  }

  // --- All helper logic is kept here ---
  // Assign base addresses
  var j = 0
  val base = if (isVitisProject) 0x10 else 0x0
  

  taskDescriptors.foreach(task => {    
    task.mgmtBaseAddresses = MemSystemDescriptor()
    // Only servers that are really instantiated get an address, otherwise the driver would
    // initialise registers that no hardware answers. A DataFlowScheduler has no SchedulerServer.
    val numSchedulerServers = schedulerServerCount(task)
    for (i <- j until j + numSchedulerServers) {
      task.mgmtBaseAddresses.schedulerServersBaseAddresses = task.mgmtBaseAddresses.schedulerServersBaseAddresses :+ ((i << 6) + base)
    }
    j += numSchedulerServers
    println("J value after scheduler: " + j)

    // Spawner servers share the scheduler vector on purpose: their register block is laid out
    // exactly like a SchedulerServer's, so the host driver initialises and grows them with the
    // same code path. They are also published separately for observability, see the C++ header.
    val numSpawnerServers = spawnerCount(task)
    for (i <- j until j + numSpawnerServers) {
      task.mgmtBaseAddresses.schedulerServersBaseAddresses = task.mgmtBaseAddresses.schedulerServersBaseAddresses :+ ((i << 6) + base)
      task.mgmtBaseAddresses.spawnerServersBaseAddresses = task.mgmtBaseAddresses.spawnerServersBaseAddresses :+ ((i << 6) + base)
    }
    j += numSpawnerServers
    println("J value after spawner servers: " + j)


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
  taskDescriptors.foreach(
    task =>
      println(f"Task: ${task.name}:  task.mgmtBaseAddresses: ${task.mgmtBaseAddresses}")
    )

  // Log the scheduler choice and the deadlock analysis once, it drives everything above.
  taskDescriptors.foreach { task =>
    val kind = if (usesDataFlowScheduler(task)) "DataFlowScheduler" else "Scheduler"
    println(
      f"[HardCilk:Scheduler] ${task.name}%-30s ${kind}%-18s inputs=${schedulerInputCount(task)}%-3d " +
        f"schedulerServers=${schedulerServerCount(task)} spawnerServers=${spawnerCount(task)}"
    )

    if (usesDataFlowScheduler(task) && task.spawnServersCount > 0) {
      println(
        f"[HardCilk:Scheduler] WARNING: '${task.name}' sets spawnServersCount=${task.spawnServersCount} " +
          "but uses the DataFlowScheduler, where spawner servers come from the cycle analysis. " +
          "The descriptor value is ignored."
      )
    }
  }

  if (dataFlowSpawnerTasks.nonEmpty) {
    println(
      f"[HardCilk:Scheduler] spawner servers inserted to break task cycles: " +
        dataFlowSpawnerTasks.toSeq.sorted.mkString(", ")
    )
  }

  def getMfpgaBaseAddress(): Int = {
    (j << 6) + base
  }

  // Helper functions
  def selfSpawnedCount(task_name: String): Int = {
    spawnList.get(task_name) match {
      case Some(spawnedTasks) =>
        if (spawnedTasks.contains(task_name))
          taskDescriptors.find(_.name == task_name).map(_.numProcessingElements).getOrElse(0)
        else 0
      case None => 0
    }
  }

  def getPortCount(port_type: String, task_name: String): Int = {
    // Get the correct map based on the port_type
    val map = port_type match {
      case "spawn"        => spawnList
      case "spawnNext"    => spawnNextList
      case "sendArgument" => sendArgumentList
      case "mallocIn"     => mallocList
      case _              => throw new IllegalArgumentException(s"Invalid port type: $port_type")
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

    // if the port_type is spawn, decrement the return value by the value returned by selfSpawnCount
    val finalCount = if (port_type == "spawn") sum - selfSpawnedCount(task_name) else sum

    finalCount
  }

  def getSystemConnectionsDescriptor(): SystemConnections = {
    // mutable map of aggregators from string to int initialized to zero
    val aggregatorMapSendArg = mutable.Map[String, Int]().withDefaultValue(0)
    val aggregatorMapSpawnNext = mutable.Map[String, Int]().withDefaultValue(0)
    val aggregatorMapMalloc = mutable.Map[String, Int]().withDefaultValue(0)
    val aggregatorMapTaskInGlobal = mutable.Map[String, Int]().withDefaultValue(0)

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

      val selfSpawnedConnections = (0 until selfSpawnedCount(task.name)).map { i =>
        ConnectionDescriptor(
          PortDescriptor(task.name, "PE", i, "taskOut", 0),
          PortDescriptor(f"${task.name}", "HardCilk", 0, "taskIn", i),
          task.widthTask,
          "AXIS"
        )
      }

      val spawnedConnections = spawnedTasks.filterNot(_ == task.name).zipWithIndex.flatMap { case (spawnedTask, j) =>
        val spawnedTaskDescriptor = taskDescriptors.find(_.name == spawnedTask).get
        (0 until task.numProcessingElements).map { i =>
          // The destination taskInGlobal index must be a per-target running
          // counter (like argIn/spawnNext below), NOT the source PE index `i`.
          // Using `i` made every distinct spawner of the same target collide on
          // taskInGlobal[0] (when numProcessingElements == 1), leaving the other
          // taskInGlobal slots undriven ("sink not fully initialized").
          aggregatorMapTaskInGlobal(spawnedTask) += 1
          ConnectionDescriptor(
            PortDescriptor(task.name, "PE", i, "taskOutGlobal", j),
            PortDescriptor(f"${spawnedTask}", "HardCilk", 0, "taskInGlobal",
              aggregatorMapTaskInGlobal(spawnedTask) - 1),
            spawnedTaskDescriptor.widthTask,
            "AXIS"
          )
        }
      }

      val argumentConnections = argumentTasks.zipWithIndex.flatMap { case (argumentTask, j) =>
        taskDescriptors.find(_.name == argumentTask).get
        (0 until task.numProcessingElements).map { i =>
          aggregatorMapSendArg(argumentTask) += 1
          ConnectionDescriptor(
            PortDescriptor(task.name, "PE", i, "argOut", j),
            PortDescriptor(f"${argumentTask}", "HardCilk", 0, "argIn", aggregatorMapSendArg(argumentTask) - 1),
            widthAddress,
            "AXIS"
          )
        }
      }

      val spawnNextConnections = spawnNextTasks.zipWithIndex.flatMap { case (spawnNextTask, j) =>
        taskDescriptors.find(_.name == spawnNextTask).get
        (0 until task.numProcessingElements).map { i =>
          aggregatorMapSpawnNext(spawnNextTask) += 1
          ConnectionDescriptor(
            PortDescriptor(f"${spawnNextTask}", "HardCilk", 0, "closureOut", aggregatorMapSpawnNext(spawnNextTask) - 1),
            PortDescriptor(task.name, "PE", i, "closureIn", 0),
            widthAddress, // This is only an address disbrutor for now...
            "AXIS"
          )
        }
      }

      val mallocConnections = mallocTasks.zipWithIndex.flatMap { case (mallocTask, j) =>
        taskDescriptors.find(_.name == mallocTask).get
        (0 until task.numProcessingElements).map { i =>
          aggregatorMapMalloc(mallocTask) += 1
          ConnectionDescriptor(
            PortDescriptor(f"${mallocTask}", "HardCilk", 0, "mallocOut", aggregatorMapMalloc(mallocTask) - 1),
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
    // Must count exactly what the address loop above allocates and what connectManagement wires.
    taskDescriptors.map(schedulerServerCount).sum + taskDescriptors
      .map(_.getNumServers("memoryAllocator"))
      .sum + taskDescriptors.map(_.getNumServers("allocator")).sum +
      taskDescriptors.map(spawnerCount).sum +
      {
        if(mFPGASynth || mFPGASimulation) 1 else 0
      } +
      {
        var count_info_ports = 0
        if(mFPGASynth || mFPGASimulation){
          // Add an extra one for each task type
          count_info_ports += taskDescriptors.length

          // Add an extra one for each task with task.generateArgOutWriteBuffer set
          taskDescriptors.foreach(task => {
            if(task.generateArgOutWriteBuffer) {
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
  
  def validate(): Unit = {
    taskDescriptors.foreach(_.validate()) // Validate all sub-tasks
    
    require(isPow2(widthAddress) && widthAddress <= 64, "widthAddress must be power of 2 and <= 64")
    require(isPow2(widthContCounter) && widthContCounter <= 64, "widthContCounter must be power of 2 and <= 64")
    require(taskDescriptors.nonEmpty, "must have at least one taskDescriptor")
    
    val taskNames = taskDescriptors.map(_.name).toSet
    require(spawnList.keys.forall(taskNames.contains), s"spawnList contains unknown task names: ${spawnList.keys.filterNot(taskNames.contains)}")
    // ... (rest of list checks) ...
    require(spawnNextList.keys.forall(taskNames.contains), "spawnNextList contains unknown task names")
    require(sendArgumentList.keys.forall(taskNames.contains), "sendArgumentList contains unknown task names")
    require(mallocList.keys.forall(taskNames.contains), "mallocList contains unknown task names")
    
    require(fpgaModel == "ALVEO_U55C", s"Unsupported fpgaModel: $fpgaModel")

    require(axiNumOutstanding >= 1 && axiNumOutstanding <= 256,
      s"axiNumOutstanding must be between 1 and 256, got $axiNumOutstanding")
    if (axiNumOutstanding <= 2) {
      DescriptorLogger.logger.warn(
        s"axiNumOutstanding=$axiNumOutstanding will make every SmartConnect on the HBM path " +
          "throttle the design to that many transactions in flight."
      )
    }

    // Check if the system is supposed to support MFPGA, and has argument notification is that
    // tasks with argument notifiers must have contigous ids startting from ID zero
    if(mFPGASynth || mFPGASimulation) {
      // Create a list of the tasks with argument notifiers
      val id_list = taskDescriptors.filter(_.getNumServers("argumentNotifier") > 0).map(_.taskId)

      // Require that id_list is contigous starting with ID 0
      var decesion = true
      for(i <- 0 until id_list.length - 1) {
        if(id_list(i) + 1 != id_list(i + 1)) {
          decesion = false
        }
      }
      require(decesion,"To support mfpga the IDs of tasks with argument notifiers must be contigous and starting from zero.\n")

    }

    // The DataFlowScheduler has no remote steal path (no RemoteTaskServer, no m_axis_remote), so
    // usesDataFlowScheduler already excludes mFPGA. Assert it, because every mFPGA-only call site
    // narrows the scheduler map back to the classic type and would drop tasks silently otherwise.
    require(
      !(mFPGASynth || mFPGASimulation) || taskDescriptors.forall(!usesDataFlowScheduler(_)),
      "The DataFlowScheduler does not support mFPGA yet."
    )

    // A dataflow task with no task input would build PEs that can never be fed.
    taskDescriptors.filter(usesDataFlowScheduler).foreach { task =>
      require(
        schedulerInputCount(task) > 0,
        s"Task '${task.name}' uses the DataFlowScheduler but has no task inputs " +
          "(no spawn ports and no argument notifier servers), so its PEs would never receive a task."
      )
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
  def fromFullSysGenDescriptor(fullSysGenDescriptor: FullSysGenDescriptor): FullSysGenDescriptorExtended = {
    val systemConnections = fullSysGenDescriptor.getSystemConnectionsDescriptor()
    val memStats = fullSysGenDescriptor.getMemoryConnectionsStats(32) // Note: 32 is hardcoded here
    FullSysGenDescriptorExtended(fullSysGenDescriptor, systemConnections, memStats)
  }
}

// --- ALL JSON OBJECTS AND HELPERS ARE MOVED TO DescriptorJSON.scala ---