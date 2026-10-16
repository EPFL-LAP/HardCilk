package HardCilk

import chext.amba.axi4
import chext.amba.axi4s
import chext.amba.axi4s.Casts._
import chisel3._
import Descriptors._
import Scheduler._
import Allocator._
import ArgumentNotifier._
import HLSHelpers._
import Util.HardCilkUtil._
import Util._
import chisel3.util.ReadyValidIO
import chext.elastic.{connect => elasticConnect}


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
      schedulerFactories: Map[String, () => SchedulerLike],
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

    // A task that never spawns itself has nothing to steal back from its own PEs, so it gets the
    // streaming DataFlowScheduler instead of the classic one. The root keeps the classic scheduler:
    // it is the one the host seeds. See Descriptors.usesDataFlowScheduler.
    val schedulerFactories: Map[String, () => SchedulerLike] = desc.taskDescriptors.map { task =>
      task.name -> (if (desc.usesDataFlowScheduler(task)) { () =>
        new DataFlowScheduler.DataFlowScheduler(
          taskWidth = task.widthTask,
          peCount = task.numProcessingElements,
          peCountGlobalTaskIn = desc.getPortCount("spawn", task.name),
          argRouteServersNumber = task.getNumServers("argumentNotifier"),
          pePortWidth = task.widthTask,
          // The classic scheduler infers this from the side-config count. Here it decides whether
          // the task has any input at all, so ask the question directly.
          argRouteServersCreateTasks = task.getNumServers("argumentNotifier") > 0,
          // Not task.spawnServersCount: for a dataflow task the spawner exists only to break a
          // dependency cycle, and which task hosts it comes from the graph analysis.
          hasSpawnerServer = desc.dataFlowSpawnerTasks.contains(task.name),
          peType = task.name
        )
      } else { () =>
        new Scheduler(
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
          // Through the descriptor helper, so the hardware cannot disagree with the number of
          // management blocks the address map reserved for this task.
          spawnerServerNumber = desc.spawnerCount(task),
          argRouteServersCreateTasks = task.sidesConfigs.length > 2,
          taskId = task.taskId,
          mfpgaSupport = desc.mFPGASimulation || desc.mFPGASynth
        )
      })
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
                wData = task.argumentSizeList.values.max, // We currently assume a single argument type per task
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
              axiDataWidth = task.argumentSizeList.values.max
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
   * Streams that get a skid buffer inserted on the driving side: every link
   * between the hub logic and a PE, in both directions.
   *
   * Once a task's PEs are floorplanned across two dice (see
   * `TclResources.Floorplan`) these nets cross an SLL, and HardCilk drove them
   * straight from one module's output register into the other module's input
   * logic. Hub <-> PE is exactly the set that fans between the middle die and
   * every PE, so it is exactly the set that can cross.
   *
   * The whole class is buffered rather than a hand-picked few links: a failing
   * crossing can have a single logic level, which makes it a distance problem
   * on whichever link happens to cross, not a depth problem on the busy ones.
   * Buffering every hub <-> PE link is uniform, needs no knowledge of the
   * floorplan, and costs a cycle only on links that would otherwise be a bare
   * wire across the die.
   *
   * Bombyx already instantiates a buffer on the *receiving* side of the PE
   * wrapper's `taskIn`/`argOut`. This adds the launch flop.
   */
  private def wantsSkid(c: ConnectionDescriptor): Boolean = {
    val src = c.srcPort.parentType
    val dst = c.dstPort.parentType
    (src == "HardCilk" && dst == "PE") || (src == "PE" && dst == "HardCilk")
  }

  /**
   * Wire `src` to `dst` through a two-entry skid buffer, if this connection is one
   * of [[wantsSkid]] and both ends are elastic.
   *
   * [[Util.SkidBuffer]] rather than a `Queue(2)`: a `Queue` drives its port
   * signals from a pointer comparator and a LUTRAM read, which makes an SLR
   * crossing ineligible for a Laguna flop (see the header of [[Util.SkidBuffer]]).
   *
   * One transfer per cycle sustained, one cycle of latency; the throughput cost
   * is negligible.
   *
   * @return true when the buffered connection was made, false to fall back to a
   *         plain bulk connect.
   */
  private def connectWithSkid(
      c: ConnectionDescriptor,
      src: chisel3.Data,
      dst: chisel3.Data
  ): Boolean = {
    if (!wantsSkid(c)) return false

    // Named after the link, so a Vivado timing report says which crossing a
    // path belongs to rather than just `stage_17`.
    val linkName =
      s"${c.srcPort.parentName}_${c.srcPort.portType}${c.srcPort.portIndex}" +
        s"__${c.dstPort.parentName}_${c.dstPort.portType}${c.dstPort.portIndex}"

    def buffered(source: ReadyValidIO[Data], sink: ReadyValidIO[Data]): Boolean = {
      elasticConnect(SkidBuffer(source, name = linkName), sink)
      println(
        s"[HardCilk:Builder] skid buffer on ${c.srcPort.parentName}.${c.srcPort.portType}" +
          s"[${c.srcPort.portIndex}] -> ${c.dstPort.parentName}.${c.dstPort.portType}" +
          s"[${c.dstPort.portIndex}]"
      )
      true
    }

    def notBuffered(why: String): Boolean = {
      println(
        s"[HardCilk:Builder] WARNING: wanted a skid buffer on ${c.srcPort} -> ${c.dstPort} " +
          s"but $why; connecting directly."
      )
      false
    }

    (src, dst) match {
      // These are AXI-Stream ports. `asLite` views one as ready/valid/bits, which
      // is lossless only when the stream carries nothing else -- otherwise TLAST,
      // TKEEP and friends would be silently dropped by the buffer.
      case (s: axi4s.Interface, d: axi4s.Interface) =>
        if (s.cfg.onlyRV && d.cfg.onlyRV)
          buffered(
            s.asLite.asInstanceOf[ReadyValidIO[Data]],
            d.asLite.asInstanceOf[ReadyValidIO[Data]]
          )
        else
          notBuffered("the stream carries sideband signals a ready/valid buffer would drop")
      case (s: ReadyValidIO[_], d: ReadyValidIO[_]) =>
        buffered(s.asInstanceOf[ReadyValidIO[Data]], d.asInstanceOf[ReadyValidIO[Data]])
      case _ =>
        notBuffered("the ports are not elastic")
    }
  }

  /**
   * Pure wiring logic — connects instantiated modules.
   */
  def connectSubsystems(
      scheds: Map[String, SchedulerLike],
      allocs: Map[String, Allocator],
      notifiers: Map[String, ArgumentNotifier],
      memAllocs: Map[String, Allocator],
      pes: Map[String, Seq[VitisWriteBufferModule]],
      spawnNextWBs: Map[String, Seq[WriteBuffer]],
      sendArgumentWBs: Map[String, Seq[WriteBuffer]]
  ): Seq[PortToExport] = {

    println(s"[HardCilk:Builder:197] Connecting ${scheds.size} schedulers, ${allocs.size} allocators, ${notifiers.size} notifiers, ${memAllocs.size} memAllocs")

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

    // Track every HardCilk-side (subsystem) port that actually gets wired by the
    // connection descriptor, so we can report scheduler ports that were left
    // unconnected — these are what firtool later flags as "sink not fully
    // initialized" (with an unhelpful empty name).
    val connectedHC = scala.collection.mutable.Set[(String, String, Int)]()
    def recordHC(p: PortDescriptor): Unit =
      if (p.parentType == "HardCilk")
        connectedHC += ((p.parentName, p.portType, p.portIndex))

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
        recordHC(connection.dstPort)
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
        recordHC(connection.srcPort)
        portsToExport += PortToExport(connection.srcPort, connection.dstPort, isSource = true)
      } else {
        def portLabel(p: PortDescriptor): String =
          s"${p.parentType}(${p.parentName}).${p.portType}[${p.portIndex}]"

        // Resolve each side independently so we can report *which* endpoint's
        // port lookup failed and which endpoint is consequently left dangling.
        // A dangling sink (input) is exactly what firtool later reports as
        // "sink not fully initialized" with an unhelpful empty name.
        val physicalSourcePort =
          try Some(getPhysicalPort(
            connection.srcPort, scheds, allocs, notifiers, memAllocs, pes, spawnNextWBs, sendArgumentWBs))
          catch {
            case e: Exception =>
              println(s"[HardCilk:Builder] ERROR resolving SOURCE port ${portLabel(connection.srcPort)}: ${e.getMessage}")
              None
          }

        val physicalDestinationPort =
          try Some(getPhysicalPort(
            connection.dstPort, scheds, allocs, notifiers, memAllocs, pes, spawnNextWBs, sendArgumentWBs))
          catch {
            case e: Exception =>
              println(s"[HardCilk:Builder] ERROR resolving DEST port ${portLabel(connection.dstPort)}: ${e.getMessage}")
              None
          }

        (physicalSourcePort, physicalDestinationPort) match {
          case (Some(src), Some(dst)) =>
            try {
              if (!connectWithSkid(connection, src, dst)) src <> dst
              recordHC(connection.srcPort)
              recordHC(connection.dstPort)
              println("[HardCilk:Builder:237] Connected " +
                s"${portLabel(connection.srcPort)} --> ${portLabel(connection.dstPort)}")
            } catch {
              case e: Exception =>
                println(s"[HardCilk:Builder] ERROR wiring ${portLabel(connection.srcPort)} <> ${portLabel(connection.dstPort)}: ${e.getMessage}")
                println(s"[HardCilk:Builder] Failed connection details: ${connection}")
            }
          case _ =>
            // At least one endpoint could not be resolved. Name the endpoint(s)
            // that WILL be left unconnected so the firtool error is traceable.
            val dangling =
              (if (physicalSourcePort.isEmpty) Seq(portLabel(connection.srcPort)) else Seq.empty) ++
              (if (physicalDestinationPort.isEmpty) Seq(portLabel(connection.dstPort)) else Seq.empty)
            println(s"[HardCilk:Builder] UNCONNECTED due to failed port resolution: ${dangling.mkString(" and ")}")
            println(s"[HardCilk:Builder] Failed connection details: ${connection}")
        }
      }
    }

    // ---- Scheduler port connection coverage report -------------------------
    // List every exported scheduler port and whether the connection descriptor
    // wired it. taskIn/taskInGlobal are sinks (must be driven); an unconnected
    // one is exactly what firtool reports as "sink not fully initialized".
    println("[HardCilk:Builder] ===== Scheduler port connection coverage =====")
    for ((name, sched) <- scheds) {
      val taskOutN = sched.io_export.taskOut.length
      val taskInN = sched.io_export.taskIn.map(_.length).getOrElse(0)
      val taskInGlobalN = sched.io_export.taskInGlobal.map(_.length).getOrElse(0)

      def report(portType: String, count: Int, isSink: Boolean): Unit =
        for (i <- 0 until count) {
          val connected = connectedHC.contains((name, portType, i))
          val flag =
            if (connected) "connected"
            else if (isSink) "UNCONNECTED (sink — undriven!)"
            else "unconnected (source — unused)"
          println(f"[HardCilk:Builder]   scheduler($name).$portType[$i]: $flag")
        }

      println(f"[HardCilk:Builder] scheduler '$name': " +
        f"taskOut=$taskOutN, taskIn=$taskInN, taskInGlobal=$taskInGlobalN")
      report("taskOut", taskOutN, isSink = false)
      report("taskIn", taskInN, isSink = true)
      report("taskInGlobal", taskInGlobalN, isSink = true)
    }
    println("[HardCilk:Builder] ===============================================")

    portsToExport.toSeq
  }
}