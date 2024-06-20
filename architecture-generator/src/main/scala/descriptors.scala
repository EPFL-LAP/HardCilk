package descriptors

import play.api.libs.json._
import play.api.libs.functional.syntax._
import chisel3.util.isPow2
import scala.collection.mutable.ListBuffer
import scala.collection.mutable

case class memSystemDescriptor(
    var schedulerServersBaseAddresses: Seq[Int] = Seq.empty,
    var allocationServersBaseAddresses: Seq[Int] = Seq.empty,
    var memoryAllocatorServersBaseAddresses: Seq[Int] = Seq.empty
)

case class portDescriptor(
    val parentName: String,
    val parentType: String,
    val parentIndex: Int,
    val portType: String,
    val portIndex: Int
) {
  assert(parentType == "HardCilk" || parentType == "PE" || parentType == "mem")
  assert(parentIndex >= 0)
  assert(portType == "taskIn" || portType == "taskOut" || portType == "taskInGlobal" || portType == "taskOutGlobal"
    || portType == "argIn" || portType == "argOut" || portType == "closureIn" 
    || portType == "closureOut" || portType == "memIn" || portType == "memOut")
  assert(portIndex >= 0)

  def getFormatedPortName(descriptor:fullSysGenDescriptor): String = {
    if (parentType == "PE") {
      // TODO: Take care of multiple port indicies
      // TODO: Unify Port types
      // map port types argOut -> addrOut, closureIne -> contIn
      val portTypeMap = Map("argOut" -> "addrOut", "closureIn" -> "contIn")
      //f"${parentName}_${parentIndex}/${portType}"
        //_${portIndex}"
      f"${parentName}_${parentIndex}/${portTypeMap.getOrElse(portType, portType)}"
    } else if (parentType == "HardCilk") {

      // if port type is taskIn or taskOut then side = stealSide, if port type = closureOut then side = closureAllocator
      // if port type = argIn then side = argumentNotifier, else side = memoryAllocator   
      val side = if (portType == "taskIn" || portType == "taskOut") "scheduler" else if (portType == "closureOut") "closureAllocator" else if (portType == "argIn") "argumentNotifier" else "memoryAllocator"
      f"${descriptor.name}_0/${parentName}_${side}_${portType}_${portIndex}"

    } else if (parentType == "mem") {
      "err"
    } else {
      "err"
    }
  } 
}


case class connectionDescriptor(
    val srcPort: portDescriptor,
    val dstPort: portDescriptor,
    val bitWidth: Int = 0,
    val connectionType: String
)

case class systemConnections(
    val connections: List[connectionDescriptor]
)

case class interconnectDescriptor(
    val count: Int,
    val ratio: Int
)

case class memStats(
    val numSysAXI: Int,
    val numPEs: Int,
    val totalAXIPorts: Int,
    val interconnectDescriptors: List[interconnectDescriptor]
)


case class sideConfig(
    val sideType: String, // scheduler, allocator, argumentNotifier, memoryAllocator
    val numVirtualServers: Int = 0,  
    val capacityVirtualQueue: Int = 0,
    val capacityPhysicalQueue: Int = 0,
    val portWidth: Int = 32,
    val virtualEntrtyWidth: Int = 0,
) {
  assert(sideType == "scheduler" || sideType == "allocator" || sideType == "argumentNotifier" || sideType == "memoryAllocator")
}

case class taskDescriptor(
    name: String,
    peVersion: String = "1.0",

    isRoot: Boolean,
    isCont: Boolean,
    dynamicMemAlloc: Boolean,
    numProcessingElements: Int,
    widthTask: Int,
    
    sidesConfigs: List[sideConfig],

    mgmtBaseAddresses: memSystemDescriptor = memSystemDescriptor()
) {

  assert(numProcessingElements > 0)
  assert(isPow2(widthTask) && widthTask <= 1024 && widthTask >= 0)

  assert(getNumServers("scheduler") > 0)
  assert(getCapacityVirtualQueue("scheduler") > 0)
  assert(getCapacityPhysicalQueue("scheduler") > 0)

  if (isCont) {

    assert(getNumServers("allocator") > 0)
    assert(getCapacityVirtualQueue("allocator") > 0)
    assert(getCapacityPhysicalQueue("allocator") > 0)

    assert(getNumServers("argumentNotifier") > 0)
    assert(getCapacityVirtualQueue("argumentNotifier") > 0)
    assert(getCapacityPhysicalQueue("argumentNotifier") > 0)
  }

  if(dynamicMemAlloc) {

    assert(getNumServers("memoryAllocator") > 0)
    assert(getCapacityVirtualQueue("memoryAllocator") > 0)
    assert(getCapacityPhysicalQueue("memoryAllocator") > 0)
  }

  def getNumServers(sideType: String): Int = {
    assert(sideType == "scheduler" || sideType == "allocator" || sideType == "argumentNotifier" || sideType == "memoryAllocator")
    sidesConfigs.find(_.sideType == sideType).map(_.numVirtualServers).getOrElse(0)
  }
  def getCapacityVirtualQueue(sideType: String): Int = {
    assert(sideType == "scheduler" || sideType == "allocator" || sideType == "argumentNotifier" || sideType == "memoryAllocator")
    sidesConfigs.find(_.sideType == sideType).map(_.capacityVirtualQueue).getOrElse(0)
  }
  def getCapacityPhysicalQueue(sideType: String): Int = {
    assert(sideType == "scheduler" || sideType == "allocator" || sideType == "argumentNotifier" || sideType == "memoryAllocator")
    sidesConfigs.find(_.sideType == sideType).map(_.capacityPhysicalQueue).getOrElse(0)
  }
  def getPortWidth(sideType: String): Int = {
    assert(sideType == "scheduler" || sideType == "allocator" || sideType == "argumentNotifier" || sideType == "memoryAllocator")
    sidesConfigs.find(_.sideType == sideType).map(_.portWidth).getOrElse(0)
  }
  def getVirtualEntrtyWidth(sideType: String): Int = {
    assert(sideType == "scheduler" || sideType == "allocator" || sideType == "argumentNotifier" || sideType == "memoryAllocator")
    sidesConfigs.find(_.sideType == sideType).map(_.virtualEntrtyWidth).getOrElse(0)
  }
}

case class fullSysGenDescriptor(
    val name: String,
    val widthAddress: Int,
    val widthContCounter: Int,
    val taskDescriptors: List[taskDescriptor],
    val spawnList: Map[String, List[String]],
    val spawnNextList: Map[String, List[String]],
    val sendArgumentList: Map[String, List[String]],
    val cfgAxiHardCilk: chext.axi4.Config = chext.axi4.Config(),
    val targetFrequency: Int = 250 
) {
  assert(isPow2(widthAddress) && widthAddress <= 64)
  assert(isPow2(widthContCounter) && widthContCounter <= 64)

  assert(taskDescriptors.nonEmpty)

  assert(spawnList.keys.forall(taskDescriptors.map(_.name).contains(_)))
  assert(spawnNextList.keys.forall(taskDescriptors.map(_.name).contains(_)))
  assert(sendArgumentList.keys.forall(taskDescriptors.map(_.name).contains(_)))

  // assert that none of the HardCilk components require a wider buswidth than cfgAxiHardCilk
  // assert(taskDescriptors.map(_.widthTask).max <= cfgAxiHardCilk.wData)

  // Assign base addresses to the management servers
  var j = 0
  taskDescriptors.foreach(task => {
    val numSchedulerServers = task.getNumServers("scheduler")
    for (i <- j until j + numSchedulerServers) {
      task.mgmtBaseAddresses.schedulerServersBaseAddresses = task.mgmtBaseAddresses.schedulerServersBaseAddresses :+ (i<<6)
    }
    j += numSchedulerServers
    if(task.isCont) {
      val numAllocationServers = task.getNumServers("allocator")
      for (i <- j until j + numAllocationServers) {
        task.mgmtBaseAddresses.allocationServersBaseAddresses = task.mgmtBaseAddresses.allocationServersBaseAddresses :+ (i<<6)
      }
      j += numAllocationServers
    }
    if(task.dynamicMemAlloc) {
      val numMemoryAllocatorServers = task.getNumServers("memoryAllocator")
      for (i <- j until j + numMemoryAllocatorServers) {
        task.mgmtBaseAddresses.memoryAllocatorServersBaseAddresses = task.mgmtBaseAddresses.memoryAllocatorServersBaseAddresses :+ (i<<6)
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
      case "spawn" => spawnList
      case "spawnNext" => spawnNextList
      case "sendArgument" => sendArgumentList
      case _ => throw new IllegalArgumentException(s"Invalid port type: $port_type")
    }

    // Get the total number of processing elements that needs that type of port
    val totalProcessingElements = map.filter { case (_, mapped_list) => mapped_list.contains(task_name) }
      .keys
      .flatMap(taskName => taskDescriptors.find(_.name == taskName))
      .map(_.numProcessingElements)
      .sum

    // if the port_type is spawn, decrement the return value by the value returned by selfSpawnCount 
    val finalCount = if (port_type == "spawn") totalProcessingElements - selfSpawnedCount(task_name) else totalProcessingElements

    finalCount
  }

  def getSystemConnectionsDescriptor(): systemConnections = {
    // mutable map of aggregators from string to int initialized to zero
    val aggregatorMapSendArg = mutable.Map[String, Int]().withDefaultValue(0)
    val aggregatorMapSpawnNext = mutable.Map[String, Int]().withDefaultValue(0)

    val connections = taskDescriptors.flatMap { task =>
    val spawnedTasks = spawnList.getOrElse(task.name, List())
    val argumentTasks = sendArgumentList.getOrElse(task.name, List())
    val spawnNextTasks = spawnNextList.getOrElse(task.name, List())

    val taskConnections = (0 until task.numProcessingElements).map { i =>
      connectionDescriptor(portDescriptor(f"${task.name}", "HardCilk", 0, "taskOut", i), portDescriptor(task.name, "PE", i, "taskIn", 0), task.widthTask, "AXIS")
    }

    val selfSpawnedConnections = (0 until selfSpawnedCount(task.name)).map { i =>
      connectionDescriptor(portDescriptor(task.name, "PE", i, "taskOut", 0), portDescriptor(f"${task.name}", "HardCilk", 0, "taskIn", i), task.widthTask, "AXIS")
    }

    val spawnedConnections = spawnedTasks.filterNot(_ == task.name).zipWithIndex.flatMap { case (spawnedTask, j) =>
      val spawnedTaskDescriptor = taskDescriptors.find(_.name == spawnedTask).get
      (0 until task.numProcessingElements).map { i =>
        connectionDescriptor(portDescriptor(task.name, "PE", i, "taskOutGlobal", j), portDescriptor(f"${spawnedTask}", "HardCilk", 0, "taskInGlobal", i), spawnedTaskDescriptor.widthTask, "AXIS")
      }
    }

    
    val argumentConnections = argumentTasks.zipWithIndex.flatMap { case (argumentTask, j) =>
      val argumentTaskDescriptor = taskDescriptors.find(_.name == argumentTask).get
      (0 until task.numProcessingElements).map { i =>
        aggregatorMapSendArg(argumentTask) += 1
        connectionDescriptor(portDescriptor(task.name, "PE", i, "argOut", j), portDescriptor(f"${argumentTask}", "HardCilk", 0, "argIn", aggregatorMapSendArg(argumentTask)-1), widthAddress, "AXIS")
      }
    }

    val spawnNextConnections = spawnNextTasks.zipWithIndex.flatMap { case (spawnNextTask, j) =>
      val spawnNextTaskDescriptor = taskDescriptors.find(_.name == spawnNextTask).get
      (0 until task.numProcessingElements).map { i =>
        aggregatorMapSpawnNext(spawnNextTask) += 1
        connectionDescriptor(portDescriptor(f"${spawnNextTask}", "HardCilk", 0, "closureOut", aggregatorMapSpawnNext(spawnNextTask)-1), portDescriptor(task.name, "PE", i, "closureIn", 0), spawnNextTaskDescriptor.widthTask, "AXIS")
      }
    }

    taskConnections ++ selfSpawnedConnections ++ spawnedConnections ++ argumentConnections ++ spawnNextConnections
    }

    systemConnections(connections)
  }
  
  def getNumConfigPorts(): Int = {
    taskDescriptors.map(_.getNumServers("scheduler")).sum + taskDescriptors.map(_.getNumServers("memoryAllocator")).sum + taskDescriptors.map(_.getNumServers("allocator")).sum
  }

  def getSystemAXIPortsNames(): List[String] = {
    val schedulerPorts = taskDescriptors.flatMap { task =>
      (0 until task.getNumServers("scheduler")).map { i =>
        if(task.getNumServers("scheduler") == 1) f"${task.name}_schedulerAXI"
        else f"${task.name}_schedulerAXI_${i}"
      }
    }

    val allocatorPorts = taskDescriptors.flatMap { task =>
      (0 until task.getNumServers("allocator")).map { i =>
        if(task.getNumServers("allocator") == 1) f"${task.name}_closureAllocatorAXI"
        else f"${task.name}_closureAllocatorAXI_${i}"
      }
    }

    val argumentNotifierPorts = taskDescriptors.flatMap { task =>
      (0 until task.getNumServers("argumentNotifier")).map { i =>
        if(task.getNumServers("argumentNotifier") == 1) f"${task.name}_argumentNotifierAXI"
        else f"${task.name}_argumentNotifierAXI_${i}"
      }
    }

    val memoryAllocatorPorts = taskDescriptors.flatMap { task =>
      (0 until task.getNumServers("memoryAllocator")).map { i =>
        if(task.getNumServers("memoryAllocator") == 1) f"${task.name}_memoryAllocatorAXI"
        else f"${task.name}_memoryAllocatorAXI_${i}"
      }
    }


    schedulerPorts ++ allocatorPorts ++ argumentNotifierPorts ++ memoryAllocatorPorts
  }

  def getMemoryConnectionsStats(): memStats = {
    var interconnectDescriptors = ListBuffer[interconnectDescriptor]()

    val numSysAXI = taskDescriptors.map(_.sidesConfigs.length).sum

    val numPEs = taskDescriptors.map(_.numProcessingElements).sum

    val totalAXIPorts = numSysAXI + numPEs + 1

    var optimizer = totalAXIPorts

    var iteration = 0
    do {
      val ratio = (optimizer / (32.0-iteration)).ceil.toInt
      interconnectDescriptors += interconnectDescriptor(1, ratio)
      optimizer = optimizer - ratio
      iteration += 1
    } while (optimizer > 0)
    assert(optimizer == 0) 

    // In the interconnectDescriptors list aggregate the entries with the same ratios into one entry
    val interconnectDescriptorsAggregated = interconnectDescriptors.groupBy(_.ratio).map { case (ratio, descriptors) =>
      interconnectDescriptor(descriptors.map(_.count).sum, ratio)
    }.toList

    assert(interconnectDescriptorsAggregated.map(_.count).sum == 32)
       


    memStats(numSysAXI, numPEs, totalAXIPorts, interconnectDescriptorsAggregated)
  }

}

object fullSysGenDescriptor {
    implicit val memSystemDescriptorReads: Reads[memSystemDescriptor] = Json.using[Json.WithDefaultValues].reads[memSystemDescriptor]
    implicit val sideConfigReads: Reads[sideConfig] = Json.using[Json.WithDefaultValues].reads[sideConfig]
    implicit val taskDescriptorReads: Reads[taskDescriptor] = Json.using[Json.WithDefaultValues].reads[taskDescriptor]
    implicit val cfgAxiHardCilkReads: Reads[chext.axi4.Config] = Json.using[Json.WithDefaultValues].reads[chext.axi4.Config]
    implicit val fullSysGenDescriptorReads: Reads[fullSysGenDescriptor] = Json.using[Json.WithDefaultValues].reads[fullSysGenDescriptor]
}


object parseJsonFile {
  def apply[T](fpath: String)(implicit reads: Reads[T]): T = {
    val rawStringJson = scala.io.Source.fromFile(fpath).mkString
    val json = Json.parse(rawStringJson)
    json.validate[T] match {
      case JsSuccess(value, _) => value
      case JsError(errors) => throw new Exception(s"Error parsing JSON file: $errors")
    }
  }
}




object DescriptorsTest extends App {

  val parsedJson = parseJsonFile[fullSysGenDescriptor]("taskDescriptors/fibonacci.json")
  print(parsedJson.getPortCount("spawnNext", "sum"))

}