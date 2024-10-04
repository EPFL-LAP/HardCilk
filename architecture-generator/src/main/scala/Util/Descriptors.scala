package Descriptors

import play.api.libs.json._
import chisel3.util.isPow2
import scala.collection.mutable.ListBuffer
import scala.collection.mutable

case class MemSystemDescriptor(
    var schedulerServersBaseAddresses: Seq[Int] = Seq.empty,
    var allocationServersBaseAddresses: Seq[Int] = Seq.empty,
    var memoryAllocatorServersBaseAddresses: Seq[Int] = Seq.empty
)

case class PortDescriptor(
    val parentName: String,
    val parentType: String,
    val parentIndex: Int,
    val portType: String,
    val portIndex: Int
) {
  assert(parentType == "HardCilk" || parentType == "PE" || parentType == "mem")
  assert(parentIndex >= 0)
  assert(
    portType == "taskIn" || portType == "taskOut" || portType == "taskInGlobal" || portType == "taskOutGlobal"
      || portType == "argIn" || portType == "argOut" || portType == "closureIn"
      || portType == "closureOut" || portType == "mallocIn" || portType == "mallocOut"
  )
  assert(portIndex >= 0)

  def getFormatedPortName(descriptor: FullSysGenDescriptor): String = {
    if (parentType == "PE") {
      // TODO: Take care of multiple port indicies
      // TODO: Unify Port types
      // map port types argOut -> addrOut, closureIne -> contIn
      val portTypeMap = Map("argOut" -> "addrOut", "closureIn" -> "contIn")
      // f"${parentName}_${parentIndex}/${portType}"
      // _${portIndex}"
      f"${parentName}_${parentIndex}/${portTypeMap.getOrElse(portType, portType)}"
    } else if (parentType == "HardCilk") {

      // if port type is taskIn or taskOut then side = Scheduler, if port type = closureOut then side = closureAllocator
      // if port type = argIn then side = argumentNotifier, else side = memoryAllocator
      val side =
        if (portType == "taskIn" || portType == "taskOut") "scheduler"
        else if (portType == "closureOut") "closureAllocator"
        else if (portType == "argIn") "argumentNotifier"
        else "memoryAllocator"
      f"${descriptor.name}_0/${parentName}_${side}_${portType}_${portIndex}"

    } else if (parentType == "mem") {
      "err"
    } else {
      "err"
    }
  }

}

case class ConnectionDescriptor(
    val srcPort: PortDescriptor,
    val dstPort: PortDescriptor,
    val bitWidth: Int = 0,
    val connectionType: String
)

case class SystemConnections(
    val connections: List[ConnectionDescriptor]
)

case class InterconnectDescriptor(
    val count: Int,
    val ratio: Int
)

case class MemStats(
    val numSysAXI: Int,
    val numPEs: Int,
    val totalAXIPorts: Int,
    val interconnectDescriptors: List[InterconnectDescriptor]
)

case class SideConfig(
    val sideType: String, // scheduler, allocator, argumentNotifier, memoryAllocator
    val numVirtualServers: Int = 0,
    val capacityVirtualQueue: Int = 0,
    val capacityPhysicalQueue: Int = 0,
    val portWidth: Int = 32,
    val virtualEntrtyWidth: Int = 0
) {
  assert(sideType == "scheduler" || sideType == "allocator" || sideType == "argumentNotifier" || sideType == "memoryAllocator")
}

case class TaskDescriptor(
    name: String,
    peVersion: String = "1.0",
    peHDLPath: String = "",
    isRoot: Boolean,
    isCont: Boolean,
    dynamicMemAlloc: Boolean,
    numProcessingElements: Int,
    widthTask: Int,
    widthMalloc: Int,
    sidesConfigs: List[SideConfig],
    mgmtBaseAddresses: MemSystemDescriptor = MemSystemDescriptor(),
    hasAXI: Boolean = true
) {

  assert(numProcessingElements > 0)
  assert(isPow2(widthTask) && widthTask <= 1024 && widthTask >= 0)

  assert(getNumServers("scheduler") > 0)
  assert(getCapacityVirtualQueue("scheduler") > 0)
  assert(getCapacityPhysicalQueue("scheduler") > 0)

  assert(dynamicMemAlloc && widthMalloc > 0 || !dynamicMemAlloc && widthMalloc == 0)

  if (isCont) {

    assert(getNumServers("allocator") > 0)
    assert(getCapacityVirtualQueue("allocator") > 0)
    assert(getCapacityPhysicalQueue("allocator") > 0)

    assert(getNumServers("argumentNotifier") > 0)
    assert(getCapacityVirtualQueue("argumentNotifier") > 0)
    assert(getCapacityPhysicalQueue("argumentNotifier") > 0)
  }

  if (dynamicMemAlloc) {

    assert(getNumServers("memoryAllocator") > 0)
    assert(getCapacityVirtualQueue("memoryAllocator") > 0)
    assert(getCapacityPhysicalQueue("memoryAllocator") > 0)
  }

  def getNumServers(sideType: String): Int = {
    assert(
      sideType == "scheduler" || sideType == "allocator" || sideType == "argumentNotifier" || sideType == "memoryAllocator"
    )
    sidesConfigs.find(_.sideType == sideType).map(_.numVirtualServers).getOrElse(0)
  }
  def getCapacityVirtualQueue(sideType: String): Int = {
    assert(
      sideType == "scheduler" || sideType == "allocator" || sideType == "argumentNotifier" || sideType == "memoryAllocator"
    )
    sidesConfigs.find(_.sideType == sideType).map(_.capacityVirtualQueue).getOrElse(0)
  }
  def getCapacityPhysicalQueue(sideType: String): Int = {
    assert(
      sideType == "scheduler" || sideType == "allocator" || sideType == "argumentNotifier" || sideType == "memoryAllocator"
    )
    sidesConfigs.find(_.sideType == sideType).map(_.capacityPhysicalQueue).getOrElse(0)
  }
  def getPortWidth(sideType: String): Int = {
    assert(
      sideType == "scheduler" || sideType == "allocator" || sideType == "argumentNotifier" || sideType == "memoryAllocator"
    )
    val width = sidesConfigs.find(_.sideType == sideType).map(_.portWidth).getOrElse(0)
    assert(width != 0, "Please make sure to specify the width of each port in the json descriptor")
    width
  }
  def getVirtualEntrtyWidth(sideType: String): Int = {
    assert(
      sideType == "scheduler" || sideType == "allocator" || sideType == "argumentNotifier" || sideType == "memoryAllocator"
    )
    sidesConfigs.find(_.sideType == sideType).map(_.virtualEntrtyWidth).getOrElse(0)
  }
}

case class FullSysGenDescriptor(
    val name: String,
    val widthAddress: Int,
    val widthContCounter: Int,
    val taskDescriptors: List[TaskDescriptor],
    val spawnList: Map[String, List[String]],
    val spawnNextList: Map[String, List[String]],
    val sendArgumentList: Map[String, List[String]],
    val mallocList: Map[String, List[String]],
    val cfgAxiHardCilk: chext.amba.axi4.Config = chext.amba.axi4.Config(),
    val targetFrequency: Int = 250,
    val memorySizeSim: Int = 1 // in GB
) {
  assert(isPow2(widthAddress) && widthAddress <= 64)
  assert(isPow2(widthContCounter) && widthContCounter <= 64)

  assert(taskDescriptors.nonEmpty)

  assert(spawnList.keys.forall(taskDescriptors.map(_.name).contains(_)))
  assert(spawnNextList.keys.forall(taskDescriptors.map(_.name).contains(_)))
  assert(sendArgumentList.keys.forall(taskDescriptors.map(_.name).contains(_)))
  assert(mallocList.keys.forall(taskDescriptors.map(_.name).contains(_)))

  // assert that none of the HardCilk components require a wider buswidth than cfgAxiHardCilk
  // assert(taskDescriptors.map(_.widthTask).max <= cfgAxiHardCilk.wData)

  // Assign base addresses to the management servers
  var j = 0
  taskDescriptors.foreach(task => {
    val numSchedulerServers = task.getNumServers("scheduler")
    for (i <- j until j + numSchedulerServers) {
      task.mgmtBaseAddresses.schedulerServersBaseAddresses = task.mgmtBaseAddresses.schedulerServersBaseAddresses :+ (i << 6)
    }
    j += numSchedulerServers
    if (task.isCont) {
      val numAllocationServers = task.getNumServers("allocator")
      for (i <- j until j + numAllocationServers) {
        task.mgmtBaseAddresses.allocationServersBaseAddresses =
          task.mgmtBaseAddresses.allocationServersBaseAddresses :+ (i << 6)
      }
      j += numAllocationServers
    }
    if (task.dynamicMemAlloc) {
      val numMemoryAllocatorServers = task.getNumServers("memoryAllocator")
      for (i <- j until j + numMemoryAllocatorServers) {
        task.mgmtBaseAddresses.memoryAllocatorServersBaseAddresses =
          task.mgmtBaseAddresses.memoryAllocatorServersBaseAddresses :+ (i << 6)
      }
      j += numMemoryAllocatorServers
    }
  })

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
          aggregatorMapSpawnNext(mallocTask) += 1
          ConnectionDescriptor(
            PortDescriptor(f"${mallocTask}", "HardCilk", 0, "mallocOut", aggregatorMapSpawnNext(mallocTask) - 1),
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
      .sum + taskDescriptors.map(_.getNumServers("allocator")).sum
  }

  def getSystemAXIPortsNames(reduce_axi: Boolean): List[String] = {
    val schedulerPorts = taskDescriptors.flatMap { task =>
      if (task.getNumServers("scheduler") > 0)
        if (reduce_axi)
          List(f"${task.name}_schedulerAXI_0")
        else
          (0 until task.getNumServers("scheduler")).map { i =>
            f"${task.name}_schedulerAXI_${i}"
          }
      else List()
    }

    val allocatorPorts = taskDescriptors.flatMap { task =>
      if (task.getNumServers("allocator") > 0)
        if (reduce_axi)
          List(f"${task.name}_closureAllocatorAXI_0")
        else
          (0 until task.getNumServers("allocator")).map { i =>
            f"${task.name}_closureAllocatorAXI_${i}"
          }
      else List()
    }

    val argumentNotifierPorts = taskDescriptors.flatMap { task =>
      if (task.getNumServers("argumentNotifier") > 0)
        if (reduce_axi)
          List(f"${task.name}_argumentNotifierAXI_0")
        else
          (0 until task.getNumServers("argumentNotifier") * 2).map { i =>
            f"${task.name}_argumentNotifierAXI_${i}"
          }
      else List()
    }

    val memoryAllocatorPorts = taskDescriptors.flatMap { task =>
      if (task.getNumServers("memoryAllocator") > 0)
        if (reduce_axi)
          List(f"${task.name}_memoryAllocatorAXI_0")
        else
          (0 until task.getNumServers("memoryAllocator")).map { i =>
            f"${task.name}_memoryAllocatorAXI_${i}"
          }
      else List()
    }

    schedulerPorts ++ allocatorPorts ++ argumentNotifierPorts ++ memoryAllocatorPorts
  }

  def getMemoryConnectionsStats(reduce_axi: Boolean): MemStats = {
    val interconnectDescriptors = ListBuffer[InterconnectDescriptor]()

    val numSysAXI = taskDescriptors.map { descriptor =>
      if (reduce_axi) descriptor.sidesConfigs.length
      else
        descriptor.getNumServers("scheduler") + descriptor.getNumServers("memoryAllocator") + descriptor.getNumServers(
          "allocator"
        ) + descriptor.getNumServers("argumentNotifier") * 2
    }.sum

    val numPEs = taskDescriptors
      .filter(_.hasAXI) // Assuming `hasAXI` is a method or property that checks for AXI presence
      .map(_.numProcessingElements)
      .sum

    val totalAXIPorts = numSysAXI + numPEs + 1

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

    MemStats(numSysAXI, numPEs, totalAXIPorts, interconnectDescriptorsAggregated)
  }

}

case class FullSysGenDescriptorExtended(
    val fullSysGenDescriptor: FullSysGenDescriptor,
    val systemConnections: SystemConnections,
    val memStats: MemStats
)

object FullSysGenDescriptor {
  implicit val memSystemDescriptorReads: Reads[MemSystemDescriptor] =
    Json.using[Json.WithDefaultValues].reads[MemSystemDescriptor]
  implicit val memSystemDescriptorWrites: Writes[MemSystemDescriptor] =
    Json.using[Json.WithDefaultValues].writes[MemSystemDescriptor]

  implicit val sideConfigReads: Reads[SideConfig] = Json.using[Json.WithDefaultValues].reads[SideConfig]
  implicit val sideConfigWrites: Writes[SideConfig] = Json.using[Json.WithDefaultValues].writes[SideConfig]

  implicit val taskDescriptorReads: Reads[TaskDescriptor] = Json.using[Json.WithDefaultValues].reads[TaskDescriptor]
  implicit val taskDescriptorWrites: Writes[TaskDescriptor] = Json.using[Json.WithDefaultValues].writes[TaskDescriptor]

  implicit val cfgAxiHardCilkReads: Reads[chext.amba.axi4.Config] =
    Json.using[Json.WithDefaultValues].reads[chext.amba.axi4.Config]
  implicit val cfgAxiHardCilkWrites: Writes[chext.amba.axi4.Config] =
    Json.using[Json.WithDefaultValues].writes[chext.amba.axi4.Config]

  implicit val fullSysGenDescriptorReads: Reads[FullSysGenDescriptor] =
    Json.using[Json.WithDefaultValues].reads[FullSysGenDescriptor]
  implicit val fullSysGenDescriptorWrites: Writes[FullSysGenDescriptor] =
    Json.using[Json.WithDefaultValues].writes[FullSysGenDescriptor]
}

object FullSysGenDescriptorExtended {
  def fromFullSysGenDescriptor(fullSysGenDescriptor: FullSysGenDescriptor): FullSysGenDescriptorExtended = {
    val systemConnections = fullSysGenDescriptor.getSystemConnectionsDescriptor()
    val memStats = fullSysGenDescriptor.getMemoryConnectionsStats(true)
    FullSysGenDescriptorExtended(fullSysGenDescriptor, systemConnections, memStats)
  }

  implicit val portDescriptorReads: Reads[PortDescriptor] = Json.using[Json.WithDefaultValues].reads[PortDescriptor]
  implicit val portDescriptorWrites: Writes[PortDescriptor] = Json.using[Json.WithDefaultValues].writes[PortDescriptor]

  implicit val connectionDescriptorReads: Reads[ConnectionDescriptor] =
    Json.using[Json.WithDefaultValues].reads[ConnectionDescriptor]
  implicit val connectionDescriptorWrites: Writes[ConnectionDescriptor] =
    Json.using[Json.WithDefaultValues].writes[ConnectionDescriptor]

  implicit val systemConnectionsReads: Reads[SystemConnections] = Json.using[Json.WithDefaultValues].reads[SystemConnections]
  implicit val systemConnectionsWrites: Writes[SystemConnections] = Json.using[Json.WithDefaultValues].writes[SystemConnections]

  implicit val interconnectDescriptorReads: Reads[InterconnectDescriptor] =
    Json.using[Json.WithDefaultValues].reads[InterconnectDescriptor]
  implicit val interconnectDescriptorWrites: Writes[InterconnectDescriptor] =
    Json.using[Json.WithDefaultValues].writes[InterconnectDescriptor]

  implicit val memStatsReads: Reads[MemStats] = Json.using[Json.WithDefaultValues].reads[MemStats]
  implicit val memStatsWrites: Writes[MemStats] = Json.using[Json.WithDefaultValues].writes[MemStats]

  implicit val fullSysGenDescriptorExtendedReads: Reads[FullSysGenDescriptorExtended] =
    Json.using[Json.WithDefaultValues].reads[FullSysGenDescriptorExtended]
  implicit val fullSysGenDescriptorExtendedWrites: Writes[FullSysGenDescriptorExtended] =
    Json.using[Json.WithDefaultValues].writes[FullSysGenDescriptorExtended]
}

object parseJsonFile {
  def apply[T](fpath: String)(implicit reads: Reads[T]): T = {
    val rawStringJson = scala.io.Source.fromFile(fpath).mkString
    val json = Json.parse(rawStringJson)
    json.validate[T] match {
      case JsSuccess(value, _) => value
      case JsError(errors)     => throw new Exception(s"Error parsing JSON file: $errors")
    }
  }
}

object dumpJsonFile {
  def apply[T](fpath: String, data: T)(implicit writes: Writes[T]): Unit = {
    val json = Json.toJson(data)
    val jsonString = Json.prettyPrint(json)
    val writer = new java.io.PrintWriter(fpath)
    writer.write(jsonString)
    writer.close()
  }
}
