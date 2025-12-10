package HardCilk

import chisel3._
import Descriptors._
import Scheduler._
import Allocator._
import ArgumentNotifier._
import HLSHelpers._
import Util.HardCilkUtil._
import Util._


/**
 * Companion object to hold helper classes
 */
object HardCilkBuilder {
  case class PortToExport(
      subsystemPortDescriptor: PortDescriptor,
      pePortDescriptor: PortDescriptor,
      isSource: Boolean
  )
}

/**
 * A pure Scala helper that describes how to assemble the HardCilk system.
 */
class HardCilkBuilder(desc: FullSysGenDescriptor, debug: Boolean, argCutCount: Int) {
  
  import HardCilkBuilder.PortToExport

  case class SubsystemBlueprint(
      peFactories: Map[String, () => Seq[VitisWriteBufferModule]],
      schedulerFactories: Map[String, () => Scheduler],
      allocatorFactories: Map[String, () => Allocator],
      argNotifierFactories: Map[String, () => ArgumentNotifier],
      memAllocatorFactories: Map[String, () => Allocator],
      spawnNextWBFactories: Map[String, () => Seq[WriteBuffer]],
      sendArgumentWBFactories: Map[String, () => Seq[WriteBuffer]],
      remoteStreamToMemFactories: Map[String, () => RemoteStreamToMem]
  )

  /** defineBlueprint() remains unchanged */
  def defineBlueprint(): SubsystemBlueprint = {

    val peFactories = desc.taskDescriptors
      .filter(task => task.peHDLPath.nonEmpty)
      .map { task =>
        task.name -> (() => VitisModuleFactory(task, desc))
      }.toMap

    val schedulerFactories = desc.taskDescriptors.map { task =>
      task.name -> (() => new Scheduler(
        addrWidth = desc.widthAddress,
        taskWidth = task.widthTask,
        queueDepth = task.getCapacityPhysicalQueue("scheduler"),
        peCount = task.numProcessingElements,
        spawnsItself = desc.selfSpawnedCount(task.name) > 0,
        peCountGlobalTaskIn = desc.getPortCount("spawn", task.name),
        argRouteServersNumber = task.getNumServers("argumentNotifier"),
        schedulerServersNumber = task.getNumServers("scheduler"),
        pePortWidth = task.widthTask,
        peType = task.name,
        debug = debug,
        spawnerServerNumber = task.spawnServersCount,
        argRouteServersCreateTasks = task.sidesConfigs.length > 2,
        taskId = task.taskId,
        mfpgaSupport = desc.mFPGASimulation || desc.mFPGASynth
      ))
    }.toMap

    val allocatorFactories = desc.taskDescriptors
      .filter(t => desc.getPortCount("spawnNext", t.name) > 0)
      .map { task =>
        task.name -> (() => new Allocator(
          addrWidth = desc.widthAddress,
          peCount = desc.getPortCount("spawnNext", task.name),
          vcasCount = task.getNumServers("allocator"),
          queueDepth = task.getCapacityPhysicalQueue("allocator"),
          pePortWidth = 64 // <-- HARDCODED
        ))
      }.toMap

    val argNotifierFactories = desc.taskDescriptors
      .filter(t => desc.getPortCount("sendArgument", t.name) > 0)
      .map { task =>
        task.name -> (() => new ArgumentNotifier(
          addrWidth =
            if (task.variableSpawn)
              (34 + desc.widthContCounter + 6)
            else
              desc.widthAddress,
          taskWidth = task.widthTask,
          queueDepth = task.getCapacityPhysicalQueue("argumentNotifier"),
          peCount = desc.getPortCount("sendArgument", task.name),
          argRouteServersNumber = task.getNumServers("argumentNotifier"),
          contCounterWidth = desc.widthContCounter,
          pePortWidth = 64, // <-- HARDCODED
          cutCount = argCutCount,
          multiDecrease = task.variableSpawn,
          mfpgaSupport = desc.mFPGASynth || desc.mFPGASimulation,
          taskID = task.taskId 
        ))
      }.toMap
    
    val memAllocatorFactories = desc.taskDescriptors
      .filter(t => desc.getPortCount("mallocIn", t.name) > 0)
      .map { task =>
        task.name -> (() => new Allocator(
          addrWidth = desc.widthAddress,
          peCount = desc.getPortCount("mallocIn", task.name),
          vcasCount = task.getNumServers("memoryAllocator"),
          queueDepth = task.getCapacityPhysicalQueue("memoryAllocator"),
          pePortWidth = 64 // <-- HARDCODED
        ))
      }.toMap


    /**
      * Create a factory of spawnNext write buffers
      * Conditions to create write buffers:
      * 1. Task has a PE HDL path which is empty
      * 2. task.generateSpawnNextWriteBuffer is true
      * 3. The task issues a spawnNext of another task type
      * We create one write buffer per PE of the task type
      */

    val spawnNextWBFactories = desc.taskDescriptors
      .filter { task =>
        task.peHDLPath.isEmpty &&
        task.generateSpawnNextWriteBuffer &&
        desc.getPortCount("spawn", task.name) > 0 // The task issues a spawn; therefore, they need to be guarded by a write buffer for continuations
      }
      .map { task =>
        task.name -> (() => {
          val wbSeq = scala.collection.mutable.ArrayBuffer[WriteBuffer]()
          for (_ <- 0 until task.numProcessingElements) {
            val wb = new WriteBuffer(
              new WriteBufferConfig(
                wAddr = desc.widthAddress,
                wData = desc.spawnNextList(task.name).map(tn => desc.taskDescriptors.find(_.name == tn).get.widthTask).max, // this assumes a single spawnNext type per task
                wAllow = (if (task.variableSpawn) 0 else 32), // <-- 32 is HARDCODED
                wAllowData = Seq(task.widthTask)
              )
            )
            wbSeq += wb
          }
          wbSeq.toSeq
        })
      }.toMap

    val sendArgumentWBFactories = desc.taskDescriptors
      .filter { task =>
        task.peHDLPath.isEmpty &&
        task.generateArgOutWriteBuffer &&
        desc.getPortCount("sendArgument", task.name) > 0 // The task issues a sendArgument; therefore, they need to be guarded by a write buffer for memory writes
      }
      .map { task =>
        task.name -> (() => {
          val wbSeq = scala.collection.mutable.ArrayBuffer[WriteBuffer]()
          for (_ <- 0 until task.numProcessingElements) {
            val wb = new WriteBuffer(
              new WriteBufferConfig(
                wAddr = desc.widthAddress,
                wData = task.argumentSizeList.max, // We currently assume a single argument type per task 
                wAllow = 32,
                wAllowData = Seq(64) // Size of the argument notification address
              )
            )
            wbSeq += wb
          }
          wbSeq.toSeq
        })
      }.toMap

    val remoteStreamToMemFactories = desc.taskDescriptors
      .filter { task =>
        task.generateArgOutWriteBuffer &&
        (desc.mFPGASimulation || desc.mFPGASynth)
      }
      .map { task =>
        task.name -> (() => {
          val remoteStreamToMem = new RemoteStreamToMem(
            new RemoteStreamToMemConfig(
              addressWidth = 64,
              localModulesCount = task.numProcessingElements,
              taskId = task.taskId,
              axiDataWidth = task.argumentSizeList.head
            ))
          remoteStreamToMem
        })
      }.toMap

    SubsystemBlueprint(
      peFactories, 
      schedulerFactories, 
      allocatorFactories, 
      argNotifierFactories, 
      memAllocatorFactories,
      spawnNextWBFactories,
      sendArgumentWBFactories,
      remoteStreamToMemFactories
    )
  }

  // <-- Removed the private getPhysicalPort helper function -->

  /**
   * Pure wiring logic — connects instantiated modules.
   */
  def connectSubsystems(
      scheds: Map[String, Scheduler],
      allocs: Map[String, Allocator],
      notifiers: Map[String, ArgumentNotifier],
      memAllocs: Map[String, Allocator],
      pes: Map[String, Seq[VitisWriteBufferModule]]
  ): Seq[PortToExport] = {
    
    println(s"[HardCilk:Builder:197] Connecting ${scheds.size} schedulers, ${allocs.size} allocators, ${notifiers.size} notifiers, ${memAllocs.size} memAllocs")
    
    val portsToExport = new scala.collection.mutable.ArrayBuffer[PortToExport]()

    for (taskName <- scheds.keys) {
      if (notifiers.contains(taskName)) {
        scheds(taskName).connArgumentNotifier <> notifiers(taskName).connStealNtw
      }
    }

    val systemConnectionsDescriptor = desc.getSystemConnectionsDescriptor()

    for (connection <- systemConnectionsDescriptor.connections) {
      val srcIsPE = connection.srcPort.parentType == "PE"
      val dstIsPE = connection.dstPort.parentType == "PE"
      val peName = if (srcIsPE) connection.srcPort.parentName else if (dstIsPE) connection.dstPort.parentName else ""
      val peExists = pes.contains(peName)

      if (srcIsPE && !peExists) {
        portsToExport += PortToExport(connection.dstPort, connection.srcPort, isSource = false)
      } else if (dstIsPE && !peExists) {
        portsToExport += PortToExport(connection.srcPort, connection.dstPort, isSource = true)
      } else {
        try {
          // Calls the helper from HardCilkUtil
          val physicalSourcePort = getPhysicalPort(
            connection.srcPort, scheds, allocs, notifiers, memAllocs, pes
          )

          // Calls the helper from HardCilkUtil
          val physicalDestinationPort = getPhysicalPort(
            connection.dstPort, scheds, allocs, notifiers, memAllocs, pes
          )

          physicalSourcePort <> physicalDestinationPort

          // Log the connection
          println("[HardCilk:Builder:237] Connected " +
            s"${connection.srcPort.parentType}(${connection.srcPort.parentName}).${connection.srcPort.portType}.${connection.srcPort.portIndex} " +
            s"--> ${connection.dstPort.parentType}(${connection.dstPort.parentName}).${connection.dstPort.portType}.${connection.dstPort.portIndex}")

        } catch {
          case e: Exception => {
            println(s"ERROR during connection: ${e.getMessage}")
            println(s"Failed connection details: ${connection}")
          }
        }
      }
    }
    
    portsToExport.toSeq
  }
}