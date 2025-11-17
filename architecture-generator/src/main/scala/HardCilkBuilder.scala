package HardCilk

import chext.amba.axi4
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
      sendArgumentWBFactories: Map[String, () => Seq[WriteBuffer]]
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
          mfpgaSupport = desc.mFPGASynth || desc.mFPGASimulation
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
            val wb = Module(new WriteBuffer(
              new WriteBufferConfig(
                wAddr = desc.widthAddress,
                wData = desc.spawnNextList(task.name).map(tn => desc.taskDescriptors.find(_.name == tn).get.widthTask).max, // this assumes a single spawnNext type per task
                wAllow = (if (task.variableSpawn) 0 else 32), // <-- 32 is HARDCODED
                wAllowData = Seq(task.widthTask)
              )
            ))
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
            val wb = Module(new WriteBuffer(
              new WriteBufferConfig(
                wAddr = desc.widthAddress,
                wData = task.argumentSizeList.max, // We currently assume a single argument type per task 
                wAllow = 32,
                wAllowData = Seq(64) // Size of the argument notification address
              )
            ))
            wbSeq += wb
          }
          wbSeq.toSeq
        })
      }.toMap

    SubsystemBlueprint(
      peFactories, 
      schedulerFactories, 
      allocatorFactories, 
      argNotifierFactories, 
      memAllocatorFactories,
      spawnNextWBFactories,
      sendArgumentWBFactories
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
      pes: Map[String, Seq[VitisWriteBufferModule]],
      spawnNextWBs: Map[String, Seq[WriteBuffer]],
      sendArgumentWBs: Map[String, Seq[WriteBuffer]]
  ): Seq[PortToExport] = {
    
    println(s"[HardCilkBuilder] Connecting ${scheds.size} schedulers, ${allocs.size} allocators, ${notifiers.size} notifiers, ${memAllocs.size} memAllocs")
    
    val portsToExport = new scala.collection.mutable.ArrayBuffer[PortToExport]()

    for (taskName <- scheds.keys) {
      if (notifiers.contains(taskName)) {
        scheds(taskName).connArgumentNotifier <> notifiers(taskName).connStealNtw
      }
    }

    // Exporting s_pkg and m_axi ports of write buffers
    for (taskName <- spawnNextWBs.keys) {
      val peExists = pes.contains(taskName)
      if (!peExists) {
        for (idx <- 0 until spawnNextWBs(taskName).length) {
          // val wb = spawnNextWBs(taskName)(idx)
          portsToExport += PortToExport(
            PortDescriptor(taskName,"spawnNextWB",idx,"s_pkg",0),
            PortDescriptor(taskName,"pe",idx,"spawnNext",0),
            isSource = false
          )
        }
      }
    }

    for (taskName <- sendArgumentWBs.keys) {
      val peExists = pes.contains(taskName)
      if (!peExists) {
        for (idx <- 0 until sendArgumentWBs(taskName).length) {
          // val wb = sendArgumentWBs(taskName)(idx)
          portsToExport += PortToExport(
            PortDescriptor(taskName,"sendArgumentWB",idx,"s_pkg",0),
            PortDescriptor(taskName,"pe",idx,"argDataOut",0),
            isSource = false
          )
        }
      }
    }

    val systemConnectionsDescriptor = desc.getSystemConnectionsDescriptor()

    for (connection <- systemConnectionsDescriptor.connections) {
      val srcIsPE = connection.srcPort.parentType == "PE"
      val dstIsPE = connection.dstPort.parentType == "PE"
      val peName = if (srcIsPE) connection.srcPort.parentName else if (dstIsPE) connection.dstPort.parentName else ""
      val peExists = pes.contains(peName)
      val peIdx = if (srcIsPE) connection.srcPort.parentIndex else if (dstIsPE) connection.dstPort.parentIndex else 0
      val spawnNextWB = if (spawnNextWBs.get(peName).isDefined) spawnNextWBs(peName)(peIdx) else null
      val sendArgumentWB = if (sendArgumentWBs.get(peName).isDefined) sendArgumentWBs(peName)(peIdx) else null

      println(s"[HardCilkBuilder] Connecting ${connection.srcPort} to ${connection.dstPort} (PE exists: ${peExists})")

      if (srcIsPE && !peExists) {
        val hardcilkPort = getPhysicalPort(connection.dstPort, scheds, allocs, notifiers, memAllocs, pes, spawnNextWBs, sendArgumentWBs)
        // Connecting WB m_allows to HardCilk and exporting s_allows port
        // Todo: is s_allows and m_allows always index 0? If yes, why it supports multiple?
        connection.srcPort.portType match {
          case "taskOut" => {
            if (spawnNextWB != null) {
              spawnNextWB.m_allows(0) <> hardcilkPort
              portsToExport += PortToExport(PortDescriptor(peName,"spawnNextWB",peIdx,"s_allows",0), connection.srcPort, isSource = false)
            } else {
              portsToExport += PortToExport(connection.dstPort, connection.srcPort, isSource = false)
            }
          }
          case "argOut" => {
            if (sendArgumentWB != null) {
              sendArgumentWB.m_allows(0) <> hardcilkPort
              portsToExport += PortToExport(PortDescriptor(peName,"sendArgumentWB",peIdx,"s_allows",0), connection.srcPort, isSource = false)
            } else {
              portsToExport += PortToExport(connection.dstPort, connection.srcPort, isSource = false)
            }
          }
          case _: String => {
            portsToExport += PortToExport(connection.dstPort, connection.srcPort, isSource = false)
          }
        }
      } else if (dstIsPE && !peExists) {
        portsToExport += PortToExport(connection.srcPort, connection.dstPort, isSource = true)
      } else {
        try {
          // Calls the helper from HardCilkUtil
          val physicalSourcePort = getPhysicalPort(
            connection.srcPort, scheds, allocs, notifiers, memAllocs, pes, spawnNextWBs, sendArgumentWBs
          )

          // Calls the helper from HardCilkUtil
          val physicalDestinationPort = getPhysicalPort(
            connection.dstPort, scheds, allocs, notifiers, memAllocs, pes, spawnNextWBs, sendArgumentWBs
          )

          physicalSourcePort <> physicalDestinationPort

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