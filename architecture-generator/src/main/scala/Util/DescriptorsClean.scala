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
    var memoryAllocatorServersBaseAddresses: Seq[Int] = Seq.empty
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

case class RWRequestDescriptor(
    `type`: String,
    mode: String,
    portWidth: Int,
    nextsubPE: Option[String] = None
) {
  def validate(subPEName: String): Unit = {
    require(Set("read", "write").contains(`type`),
      s"subPE '$subPEName': rwRequest.type must be one of read|write")
    require(Set("single", "stream").contains(mode),
      s"subPE '$subPEName': rwRequest.mode must be one of single|stream")
    require(portWidth > 0,
      s"subPE '$subPEName': rwRequest.portWidth must be > 0")

    if (`type` == "read") {
      require(nextsubPE.nonEmpty,
        s"subPE '$subPEName': rwRequest.nextsubPE must exist when type is read")
    } else {
      require(nextsubPE.isEmpty,
        s"subPE '$subPEName': rwRequest.nextsubPE must not exist when type is write")
    }
  }
}

case class SubPEDescriptor(
    peName: String,
    rwRequest: RWRequestDescriptor
) {
  def validate(subPEName: String): Unit = {
    require(peName.nonEmpty, s"subPE '$subPEName': peName must not be empty")
    rwRequest.validate(subPEName)
  }
}

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
    isAIE: Boolean = false,
    generateSpawnNextWriteBuffer: Boolean = false,
    generateArgOutWriteBuffer: Boolean = false,
    argumentSizeList: List[Int] = List(),
    taskId: Int = 0 // Defaulted
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
      require(!argumentSizeList.isEmpty, s"Task '$name': argumentSizeList must not be empty!")
      require(argumentSizeList.head > 0, s"Task '$name': argumentWidth must be > 0 to has a write buffer!")
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
    subPEList: Map[String, SubPEDescriptor] = Map.empty,
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
    fpgaCountSim: Int = 1
) {
  // --- All helper logic is kept here ---
  // Assign base addresses
  var j = 0
  val base = if (isVitisProject) 0x10 else 0x0


  taskDescriptors.foreach(task => {
    task.mgmtBaseAddresses = MemSystemDescriptor()
    val numSchedulerServers = task.getNumServers("scheduler")
    for (i <- j until j + numSchedulerServers) {
      task.mgmtBaseAddresses.schedulerServersBaseAddresses = task.mgmtBaseAddresses.schedulerServersBaseAddresses :+ ((i << 6) + base)
    }
    j += numSchedulerServers
    println("J value after scheduler: " + j)

    if(task.spawnServersCount > 0) {
      for (i <- j until j + task.spawnServersCount ) {
        task.mgmtBaseAddresses.schedulerServersBaseAddresses = task.mgmtBaseAddresses.schedulerServersBaseAddresses :+ ((i << 6) + base)
      }
      j += task.spawnServersCount
    }
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
          ConnectionDescriptor(
            PortDescriptor(task.name, "PE", i, "taskOutGlobal", j),
            PortDescriptor(f"${spawnedTask}", "HardCilk", 0, "taskInGlobal", i),
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
    taskDescriptors.map(_.getNumServers("scheduler")).sum + taskDescriptors
      .map(_.getNumServers("memoryAllocator"))
      .sum + taskDescriptors.map(_.getNumServers("allocator")).sum +
      {
        var spawner_count = 0
        taskDescriptors.foreach(task => {
          if(task.spawnServersCount > 0) {
            spawner_count += task.spawnServersCount
          }
        })
        spawner_count
      } +
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

    subPEList.foreach { case (subPEName, subPEDescriptor) =>
      require(subPEName.nonEmpty, "subPEList contains an empty subPE name")
      subPEDescriptor.validate(subPEName)
      require(taskNames.contains(subPEDescriptor.peName),
        s"subPE '$subPEName': unknown peName '${subPEDescriptor.peName}'")
      subPEDescriptor.rwRequest.nextsubPE.foreach { nextSubPE =>
        require(subPEList.contains(nextSubPE),
          s"subPE '$subPEName': nextsubPE '$nextSubPE' is not defined in subPEList")
      }
    }

    require(fpgaModel == "ALVEO_U55C", s"Unsupported fpgaModel: $fpgaModel")

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