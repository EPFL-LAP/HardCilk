package HardCilk

import chext.amba.axi4
import chisel3._
import Descriptors._
import Scheduler._
import Allocator._
import ArgumentNotifier._
import NewArgumentNotifier._
import HLSHelpers._
import Util.HardCilkUtil._
import Util._
import SchedulerLegacy.LegacyScheduler
import AllocatorLegacy.LegacyAllocator
import ArgumentNotifierLegacy.LegacyArgumentNotifier


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
class HardCilkBuilder(desc: FullSysGenDescriptor, debug: Boolean, argCutCount: Int,
    enableGlobalStart: Boolean = false,
    generatorProfile: GeneratorProfile = GeneratorProfile(ArchitectureMode.Updated, ArgumentServerMode.Cached)) {

  import HardCilkBuilder.PortToExport

  case class SubsystemBlueprint(
      peFactories: Map[String, () => Seq[VitisWriteBufferModule]],
      schedulerFactories: Map[String, () => SchedulerModule],
      allocatorFactories: Map[String, () => AllocatorModule],
      argNotifierFactories: Map[String, () => ArgumentNotifierModule],
      newArgNotifierFactories: Map[String, () => ArgumentNetworks],
      memAllocatorFactories: Map[String, () => AllocatorModule],
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

    val schedulerFactories: Map[String, () => SchedulerModule] = desc.taskDescriptors.map { task =>
      task.name -> (() => if (generatorProfile.isLegacy) new LegacyScheduler(
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
        spawnerQueueDepth = task.spawnerQueueDepth,
        argRouteServersCreateTasks =
          task.sidesConfigs.length > 2 || (task.isCont && task.spawnServersCount > 0),
        taskId = task.taskId,
        mfpgaSupport = desc.mFPGASimulation || desc.mFPGASynth
      ) else new Scheduler(
        addrWidth = desc.widthAddress,
        taskWidth = task.widthTask,
        queueDepth = task.getCapacityPhysicalQueue("scheduler"),
        peCount = task.numProcessingElements,
        spawnsItself = desc.selfSpawnedCount(task.name) > 0,
        peCountGlobalTaskIn = desc.getPortCount("spawn", task.name),
        argRouteServersNumber = task.getSideConfig("argumentNotifier") match {
          case Some(c) if c.useNewArgumentNotifier =>
            c.numVirtualServers * c.newContinuationLanesPerServer +
              c.slowArgumentHandlerCount
          case _ => task.getNumServers("argumentNotifier")
        },
        schedulerServersNumber = task.getNumServers("scheduler"),
        pePortWidth = task.widthTask,
        peType = task.name,
        debug = debug,
        spawnerServerNumber = task.spawnServersCount,
        spawnerQueueDepth = task.spawnerQueueDepth,
        useAffinity = task.getSideConfig("scheduler").exists(_.useAffinity),
        affinityQueueDepth =
          task.getSideConfig("scheduler").map(_.affinityQueueDepth).getOrElse(0),
        affinityTagBits =
          task.getSideConfig("scheduler").map(_.affinityTagBits).getOrElse(0),
        fastArgumentRouteServersNumber = task.getSideConfig("argumentNotifier") match {
          case Some(c) if c.useNewArgumentNotifier =>
            c.numVirtualServers * c.newContinuationLanesPerServer
          case _ => 0
        },
        newContinuationLaneStripingFactor =
          task.getSideConfig("argumentNotifier") match {
            case Some(c) if c.useNewArgumentNotifier =>
              c.newContinuationLaneStripingFactor
            case _ => 1
          },
        // A continuation (isCont) re-injects its own task via the argument
        // notifier when the join counter hits 0. With mFPGA on, that loops back
        // through the network; single-FPGA needs the *local* outsideSpawn path,
        // which requires this flag and a spawner (spawnServersCount > 0). The
        // original `> 2` heuristic only fired for tasks with an extra (e.g.
        // allocator) sideConfig, so single-FPGA continuations dropped the
        // re-injection. The mFPGA benchmarks keep spawnServersCount=0 and are
        // unaffected.
        argRouteServersCreateTasks =
          task.sidesConfigs.length > 2 || (task.isCont && task.spawnServersCount > 0),
        taskId = task.taskId,
        mfpgaSupport = desc.mFPGASimulation || desc.mFPGASynth,
        enableGlobalStart = enableGlobalStart
      ))
    }.toMap

    val allocatorFactories = desc.taskDescriptors
      .filter(t => desc.getPortCount("spawnNext", t.name) > 0)
      .map { task =>
        task.name -> (() => (if (generatorProfile.isLegacy) new LegacyAllocator(
          addrWidth = desc.widthAddress,
          peCount = desc.getPortCount("spawnNext", task.name),
          vcasCount = task.getNumServers("allocator"),
          queueDepth = task.getCapacityPhysicalQueue("allocator"),
          pePortWidth = 64
        ) else new Allocator(
          addrWidth = desc.widthAXIAddress, // HBM address width (34): continuations pack/address natively at HBM width
          peCount = desc.getPortCount("spawnNext", task.name),
          vcasCount = task.getNumServers("allocator"),
          queueDepth = task.getCapacityPhysicalQueue("allocator"),
          pePortWidth = 64 // <-- HARDCODED
        )))
      }.toMap

    val argNotifierFactories = desc.taskDescriptors
      .filter(t => desc.getPortCount("sendArgument", t.name) > 0 && !t.usesNewArgumentNotifier)
      .map { task =>
        val argPeCount = desc.getPortCount("sendArgument", task.name)
        val argServerCount = task.getNumServers("argumentNotifier")
        // cutCount = number of parallel collector lanes in the notifier network.
        // Size it to min(peCount, servers): the servers are the absorption ceiling
        // (each drains ~1 continuation/cycle), and the network needs one lane per
        // server to keep them all fed -- more lanes than servers just back up at the
        // per-server arbiters, fewer funnels PEs through a shared lane and caps the
        // whole continuation-firing stage at 1/cycle (the old global default of 1).
        // Never exceed peCount (asserted in ArgumentNotifierNetwork).
        val argCut = math.max(1, math.min(argPeCount, argServerCount))
        task.name -> (() => (if (generatorProfile.isLegacy) new LegacyArgumentNotifier(
          addrWidth =
            if (task.variableSpawn) (34 + desc.widthContCounter + 6)
            else desc.widthAddress,
          taskWidth = task.widthTask,
          queueDepth = task.getCapacityPhysicalQueue("argumentNotifier"),
          peCount = argPeCount,
          argRouteServersNumber = argServerCount,
          contCounterWidth = desc.widthContCounter,
          pePortWidth = 64,
          cutCount = argCutCount,
          multiDecrease = task.variableSpawn,
          mfpgaSupport = desc.mFPGASynth || desc.mFPGASimulation,
          taskID = task.taskId
        ) else new ArgumentNotifier(
          addrWidth =
            if (task.variableSpawn)
              (34 + desc.widthContCounter + 6)
            else
              desc.widthAddress,
          taskWidth = task.widthTask,
          queueDepth = task.getCapacityPhysicalQueue("argumentNotifier"),
          peCount = argPeCount,
          argRouteServersNumber = argServerCount,
          contCounterWidth = desc.widthContCounter,
          pePortWidth = 64, // <-- HARDCODED
          cutCount = argCut,
          multiDecrease = task.variableSpawn,
          mfpgaSupport = desc.mFPGASynth || desc.mFPGASimulation,
          taskID = task.taskId
        )))
      }.toMap

    val newArgNotifierFactories = desc.taskDescriptors
      .filter(t => desc.getPortCount("sendArgument", t.name) > 0 && t.usesNewArgumentNotifier)
      .map { task =>
        val c = task.getSideConfig("argumentNotifier").get
        val updateSources = desc.taskDescriptors.filter(source =>
          desc.sendArgumentList.getOrElse(source.name, Nil).contains(task.name)
        )
        val updatePayloadWidth = updateSources.map(_.argumentSizeList.max).distinct
        val updateOffsetWidth = updateSources.map(_.argumentOffsetWidth.get).distinct
        require(
          updatePayloadWidth.size == 1 && updateOffsetWidth.size == 1,
          s"${task.name}: all NewArgumentNotifier update sources must share one payload/offset shape"
        )
        val expectedNew = desc.getPortCount("spawnNext", task.name)
        val expectedUpdates = desc.getPortCount("sendArgument", task.name)
        require(
          c.newContinuationLaneStripingFactor > 0 &&
            c.newContinuationLanesPerServer %
              c.newContinuationLaneStripingFactor == 0,
          s"${task.name}: new continuation lanes must divide evenly into " +
            "private striped groups"
        )
        val newSourcesPerServer =
          c.newContinuationLanesPerServer /
            c.newContinuationLaneStripingFactor
        require(
          c.numVirtualServers * newSourcesPerServer == expectedNew,
          s"${task.name}: argument servers * (new lanes / striping factor) " +
            s"must equal $expectedNew spawnNext sources"
        )
        require(
          c.numVirtualServers * c.directUpdateLanesPerServer == expectedUpdates,
          s"${task.name}: argument servers * direct update lanes must equal $expectedUpdates sendArgument sources"
        )
        task.name -> (() => new ArgumentNetworks(
          ArgumentNetworksConfig(
            nServers = c.numVirtualServers,
            newLanesPerServer = c.newContinuationLanesPerServer,
            newLaneStripingFactor =
              c.newContinuationLaneStripingFactor,
            updateLanesPerServer = c.directUpdateLanesPerServer,
            nSlowHandlers = c.slowArgumentHandlerCount,
            nEvictionSavers = c.cacheEvictionSaverCount,
            counterWidth = desc.widthContCounter,
            sysAddressWidth = desc.widthAddress,
            realAddressWidth = desc.widthAXIAddress,
            serverIDWidth = c.argumentServerIdWidth,
            cacheDelayCycles = c.cacheDelayCycles,
            missedUpdateExtra = c.missedUpdateExtra,
            continuationSize = task.widthTask,
            updatePayloadWidth = updatePayloadWidth.head,
            updateOffsetWidth = updateOffsetWidth.head,
            slowCutCount = c.argumentNotifierCutCount,
            evictCutCount = c.evictionCutCount,
            slowRequestQueueDepth = c.slowRequestQueueDepth
          )
        ))
      }.toMap

    val memAllocatorFactories = desc.taskDescriptors
      .filter(t => desc.getPortCount("mallocIn", t.name) > 0)
      .map { task =>
        task.name -> (() => (if (generatorProfile.isLegacy) new LegacyAllocator(
          addrWidth = desc.widthAddress,
          peCount = desc.getPortCount("mallocIn", task.name),
          vcasCount = task.getNumServers("memoryAllocator"),
          queueDepth = task.getCapacityPhysicalQueue("memoryAllocator"),
          pePortWidth = 64
        ) else new Allocator(
          addrWidth = desc.widthAXIAddress, // HBM address width (34): continuations pack/address natively at HBM width
          peCount = desc.getPortCount("mallocIn", task.name),
          vcasCount = task.getNumServers("memoryAllocator"),
          queueDepth = task.getCapacityPhysicalQueue("memoryAllocator"),
          pePortWidth = 64 // <-- HARDCODED
        )))
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
        task.generateSpawnNextWriteBuffer
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
        task.generateArgOutWriteBuffer
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
      newArgNotifierFactories,
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
      scheds: Map[String, SchedulerModule],
      allocs: Map[String, AllocatorModule],
      notifiers: Map[String, ArgumentNotifierModule],
      newNotifiers: Map[String, ArgumentNetworks],
      memAllocs: Map[String, AllocatorModule],
      pes: Map[String, Seq[VitisWriteBufferModule]],
      spawnNextWBs: Map[String, Seq[WriteBuffer]],
      sendArgumentWBs: Map[String, Seq[WriteBuffer]]
  ): Seq[PortToExport] = {

    println(s"[HardCilk:Builder:197] Connecting ${scheds.size} schedulers, ${allocs.size} allocators, ${notifiers.size} notifiers, ${memAllocs.size} memAllocs")

    val portsToExport = new scala.collection.mutable.ArrayBuffer[PortToExport]()

    for (taskName <- scheds.keys) {
      if (notifiers.contains(taskName)) {
        scheds(taskName).connArgumentNotifier <> notifiers(taskName).connStealNtw
      } else if (newNotifiers.contains(taskName)) {
        scheds(taskName).connArgumentNotifier <> newNotifiers(taskName).connStealNtw
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
      val isNewArgumentConnection =
        connection.dstPort.parentType == "HardCilk" &&
          connection.dstPort.portType == "argIn" &&
          newNotifiers.contains(connection.dstPort.parentName)
      val srcIsPE = connection.srcPort.parentType == "PE"
      val dstIsPE = connection.dstPort.parentType == "PE"
      val peName = if (srcIsPE) connection.srcPort.parentName else if (dstIsPE) connection.dstPort.parentName else ""
      val peExists = pes.contains(peName)
      val peIdx = if (srcIsPE) connection.srcPort.parentIndex else if (dstIsPE) connection.dstPort.parentIndex else 0
      val spawnNextWB = if (spawnNextWBs.get(peName).isDefined) spawnNextWBs(peName)(peIdx) else null
      val sendArgumentWB = if (sendArgumentWBs.get(peName).isDefined) sendArgumentWBs(peName)(peIdx) else null

      println(s"[HardCilkBuilder] Connecting ${connection.srcPort} to ${connection.dstPort} (PE exists: ${peExists})")

      if (isNewArgumentConnection) {
        // Address+metadata is connected explicitly by HardCilk alongside the
        // update write-buffer AXI port.
      } else if (srcIsPE && !peExists) {
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
