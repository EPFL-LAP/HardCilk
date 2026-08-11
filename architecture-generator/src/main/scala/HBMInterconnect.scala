
package HardCilk

import chisel3._
import chisel3.util.log2Ceil
import Descriptors._
import Scheduler._
import Allocator._
import ArgumentNotifier._
import NewArgumentNotifier._
import HLSHelpers._
import scala.collection.mutable.ArrayBuffer

// All the AXI-related imports needed by the HBM logic
import chext.amba.axi4
import axi4.Ops._
import AXIHelpers._
import Util.AddressTransformConfig
import io.circe.generic.auto._
import Util.WriteBuffer
import Util.RemoteStreamToMem
import Util.GeneratorProfile

/**
 * A trait that encapsulates the HBM AXI interconnect generation logic.
 * It is intended to be mixed into the top-level CleanHardCilk module.
 * * It requires the class mixing it in to provide concrete implementations
 * for all the abstract 'val's and 'var's defined below.
 */
trait HasHBMInterconnect extends Module {

  // --- Abstract members to be provided by CleanHardCilk ---
  // These are "inputs" that the trait needs from the main class.
  val fullSysGenDescriptor: FullSysGenDescriptor
  val reduceAxi: Int
  val addressTransformFlag: Boolean
  val enableRamaByDefault: Boolean
  // True only for --rama-striping. --rama-no-striping also enables RAMA ports,
  // but leaves the host-visible address map linear.
  val ramaStripingEnabled: Boolean
  val generatorProfile: GeneratorProfile
  val enableGlobalStart: Boolean
  val cfgAxi4HBM: axi4.Config
  val cfgXDMA: axi4.Config
  val interfaceBuffer: ArrayBuffer[hdlinfo.Interface]
  val axiOuts: ArrayBuffer[axi4.RawInterface]
  val axiXDMA: ArrayBuffer[axi4.RawInterface]

  // This is an "output" var that this trait will update
  var numHbmPortExports: Int

  // Exact exported m_axi_NN indices that should receive RAMA after applying the
  // CLI default and every task's tri-state generateRAMA override.
  var ramaPortIndices: Seq[Int]

  // Output: JSON mapping each exported HBM port (m_axi_NN, compacted index ==
  // watcher bandwidth-tap index) to the module masters attached to it. Built in
  // buildAndConnectHBM and written to <name>.hbmports.json by CleanHardCilk.
  var hbmPortMappingJson: String = "{}"

  /**
   * This method is now part of the trait. It contains the exact logic
   * moved from CleanHardCilk.scala.
   */
  def buildAndConnectHBM(
      peMap: Map[String, Seq[VitisWriteBufferModule]],
      schedulerMap: Map[String, SchedulerModule],
      closureAllocatorMap: Map[String, AllocatorModule],
      argumentNotifierMap: Map[String, ArgumentNotifierModule],
      newArgumentNotifierMap: Map[String, ArgumentNetworks],
      memoryAllocatorMap: Map[String, AllocatorModule],
      spawnNextWBMap: Map[String, Seq[WriteBuffer]],
      sendArgumentWBMap: Map[String, Seq[WriteBuffer]],
      remoteMemAccessMap: Map[String, RemoteStreamToMem]
  ): Unit = {

    // [This is the code block from CleanHardCilk.scala, line 316 to 512]

    // A group bundles the HBM masters owned by one module (PE / write buffer /
    // server). `roles` (parallel to `interfaces`) names each master's function
    // ("main" = the kernel's m_axi_gmem compute port, "argOut"/"spawnNext" = the
    // argument / continuation write-buffer ports); when absent the role falls
    // back to the interface index so the descriptor is always populated.
    case class HbmInterfaceGroup(
        name: String,
        interfaces: Seq[axi4.full.Interface],
        roles: Seq[String] = Seq.empty
    ) {
      def size: Int = interfaces.length
      def roleAt(i: Int): String =
        if (roles.length == interfaces.length) roles(i) else i.toString
    }

    // Returns each of a PE's HBM masters tagged with its role name, in a stable
    // order (spawnNext, argOut, then the main m_axi_gmem compute port).
    def peOwnedPorts(
        pe: VitisWriteBufferModule,
        task: TaskDescriptor
    ): Seq[(String, axi4.full.Interface)] = {
      val ports = new ArrayBuffer[(String, axi4.full.Interface)]()
      val spawnTerminatesInNewNotifier = fullSysGenDescriptor.spawnNextList
        .getOrElse(task.name, Nil)
        .exists(name => newArgumentNotifierMap.contains(name))
      val updateTerminatesInNewNotifier = fullSysGenDescriptor.sendArgumentList
        .getOrElse(task.name, Nil)
        .exists(name => newArgumentNotifierMap.contains(name))
      if (!spawnTerminatesInNewNotifier)
        pe.io.elements.get("m_axi_spawnNext").foreach(p =>
          ports.addOne(("spawnNext", p.asInstanceOf[axi4.RawInterface].asFull)))
      if (!updateTerminatesInNewNotifier)
        pe.io.elements.get("m_axi_argOut").foreach(p =>
          ports.addOne(("argOut", p.asInstanceOf[axi4.RawInterface].asFull)))
      if (task.hasAXI) {
        ports.addOne(("main", pe.getPort("m_axi_gmem").asInstanceOf[axi4.RawInterface].asFull))
      }
      ports.toSeq
    }

    val peInterfaceGroups = new ArrayBuffer[HbmInterfaceGroup]()
    // PEs whose descriptor sets dedicatedAxiPort or generateRAMA get their MAIN compute master
    // (m_axi_gmem) pulled onto its OWN reserved HBM port -- one port per PE
    // instance, never muxed with any other master (PATH 1 direct passthrough).
    // Their write-buffer ports (spawnNext/argOut), if any, still ride the shared
    // pool. Everything else is unchanged.
    val dedicatedGroups = new ArrayBuffer[HbmInterfaceGroup]()
    // Identity-tracked subset of the dedicated main masters that requested
    // RAMA. Interface identity is stable through allocation into hbmSlaves.
    val interfacesRama = new ArrayBuffer[axi4.full.Interface]()
    // Explicit generateRAMA=false interfaces. Under a global CLI RAMA mode they
    // are isolated so their exported ingress can safely bypass RAMA.
    val interfacesNoRama = new ArrayBuffer[axi4.full.Interface]()
    // totalAxiPorts consolidation: (portCount, mainMasterGroups) per task whose
    // descriptor sets totalAxiPorts > 0. The main masters of ALL its PEs are
    // pulled out of the shared pool (like dedicated) and later packed onto
    // exactly `portCount` reserved front ports as a flat mux. Each main master
    // is its own single-interface group so the port-mapping JSON keeps the true
    // per-PE owner/role. portCount is capped at the master count.
    val consolidatedTaskGroups = new ArrayBuffer[(Int, Seq[HbmInterfaceGroup])]()
    // With RAMA disabled, legacy ignores updated-only placement knobs and feeds
    // every PE-owned master into the historical shared allocator. A selected
    // RAMA mode deliberately opts into the current isolation layer so each
    // transformed ingress remains unambiguous.
    val useHistoricalLegacyGrouping =
      generatorProfile.isLegacy &&
        !enableRamaByDefault &&
        !fullSysGenDescriptor.taskDescriptors.exists(_.generateRAMA.contains(true))

    fullSysGenDescriptor.taskDescriptors.foreach { task =>
      peMap.get(task.name).foreach { peArray =>
        val forceRama = task.generateRAMA.contains(true)
        val forceNoRama = task.generateRAMA.contains(false)
        val isolateNoRama = enableRamaByDefault && forceNoRama && task.totalAxiPorts == 0
        val reserveMain = !useHistoricalLegacyGrouping &&
          (task.dedicatedAxiPort || forceRama || isolateNoRama)
        val consolidateMain = !useHistoricalLegacyGrouping &&
          !reserveMain && task.totalAxiPorts > 0
        val taskMainGroups = new ArrayBuffer[HbmInterfaceGroup]()
        peArray.zipWithIndex.foreach { case (pe, peIndex) =>
          val rolePorts = peOwnedPorts(pe, task)
          if (rolePorts.nonEmpty) {
            if (reserveMain) {
              val (mainPorts, otherPorts) = rolePorts.partition(_._1 == "main")
              mainPorts.foreach { case (role, iface) =>
                dedicatedGroups.addOne(
                  HbmInterfaceGroup(s"pe:${task.name}:$peIndex", Seq(iface), Seq(role))
                )
                if (forceRama) interfacesRama.addOne(iface)
                if (forceNoRama) interfacesNoRama.addOne(iface)
              }
              if (otherPorts.nonEmpty) {
                peInterfaceGroups.addOne(
                  HbmInterfaceGroup(
                    s"pe:${task.name}:$peIndex",
                    otherPorts.map(_._2),
                    otherPorts.map(_._1)
                  )
                )
              }
            } else if (consolidateMain) {
              val (mainPorts, otherPorts) = rolePorts.partition(_._1 == "main")
              mainPorts.foreach { case (role, iface) =>
                taskMainGroups.addOne(
                  HbmInterfaceGroup(s"pe:${task.name}:$peIndex", Seq(iface), Seq(role))
                )
                if (forceNoRama) interfacesNoRama.addOne(iface)
              }
              if (otherPorts.nonEmpty) {
                peInterfaceGroups.addOne(
                  HbmInterfaceGroup(
                    s"pe:${task.name}:$peIndex",
                    otherPorts.map(_._2),
                    otherPorts.map(_._1)
                  )
                )
              }
            } else {
              peInterfaceGroups.addOne(
                HbmInterfaceGroup(
                  s"pe:${task.name}:$peIndex",
                  rolePorts.map(_._2),
                  rolePorts.map(_._1)
                )
              )
            }
          }
        }
        if (consolidateMain && taskMainGroups.nonEmpty) {
          val portCount = math.min(task.totalAxiPorts, taskMainGroups.size)
          consolidatedTaskGroups.addOne((portCount, taskMainGroups.toSeq))
        }
      }
    }
    fullSysGenDescriptor.taskDescriptors.foreach { task =>
      spawnNextWBMap.get(task.name).foreach { wbArray =>
        wbArray.zipWithIndex.foreach { case (wb, wbIndex) =>
          peInterfaceGroups.addOne(
            HbmInterfaceGroup(
              s"spawnNextWB:${task.name}:$wbIndex",
              Seq(wb.m_axi.asInstanceOf[axi4.RawInterface].asFull),
              Seq("spawnNext")
            )
          )
        }
      }
      sendArgumentWBMap.get(task.name).foreach { wbArray =>
        wbArray.zipWithIndex.foreach { case (wb, wbIndex) =>
          peInterfaceGroups.addOne(
            HbmInterfaceGroup(
              s"sendArgumentWB:${task.name}:$wbIndex",
              Seq(wb.m_axi.asInstanceOf[axi4.RawInterface].asFull),
              Seq("argDataOut")
            )
          )
        }
      }
    }

    val schedulerInterfaceGroups = new ArrayBuffer[HbmInterfaceGroup]()
    fullSysGenDescriptor.taskDescriptors.foreach { task =>
      schedulerMap.get(task.name).foreach { scheduler =>
        scheduler.io_internal.vss_axi_full.zipWithIndex.foreach { case (port, portIndex) =>
          schedulerInterfaceGroups.addOne(
            HbmInterfaceGroup(s"scheduler:${task.name}:vss:$portIndex", Seq(port), Seq("ring"))
          )
        }
        scheduler.legacySpawnerAxi.zipWithIndex.foreach { case (port, portIndex) =>
          schedulerInterfaceGroups.addOne(
            HbmInterfaceGroup(s"spawner:${task.name}:$portIndex", Seq(port), Seq("spawner"))
          )
        }
      }
    }

    val interfacesScheduler = schedulerInterfaceGroups.flatMap(_.interfaces).to(ArrayBuffer)

    val interfacesClosureAllocator = new ArrayBuffer[axi4.full.Interface]()
    val closureAllocatorGroups = new ArrayBuffer[HbmInterfaceGroup]()
    fullSysGenDescriptor.taskDescriptors.foreach { task =>
      closureAllocatorMap.get(task.name).foreach { allocator =>
        allocator.io_internal.vcas_axi_full.zipWithIndex.foreach { case (port, portIndex) =>
          closureAllocatorGroups.addOne(
            HbmInterfaceGroup(s"closureAllocator:${task.name}:$portIndex", Seq(port))
          )
          interfacesClosureAllocator.addOne(port)
        }
      }
    }

    val interfacesMemoryAllocator = new ArrayBuffer[axi4.full.Interface]()
    val memoryAllocatorGroups = new ArrayBuffer[HbmInterfaceGroup]()
    fullSysGenDescriptor.taskDescriptors.foreach { task =>
      memoryAllocatorMap.get(task.name).foreach { allocator =>
        allocator.io_internal.vcas_axi_full.zipWithIndex.foreach { case (port, portIndex) =>
          memoryAllocatorGroups.addOne(
            HbmInterfaceGroup(s"memoryAllocator:${task.name}:$portIndex", Seq(port))
          )
          interfacesMemoryAllocator.addOne(port)
        }
      }
    }

    val interfacesArgumentNotifier = new ArrayBuffer[axi4.full.Interface]()
    val argumentNotifierGroups = new ArrayBuffer[HbmInterfaceGroup]()
    fullSysGenDescriptor.taskDescriptors.foreach { task =>
      argumentNotifierMap.get(task.name).foreach { notifier =>
        val serverCount = task.getNumServers("argumentNotifier")
        for (serverIndex <- 0 until serverCount) {
          // Emit the counter (RMW) master and the task (read) master as SEPARATE
          // one-interface groups so assignGroupsToHbmPorts can place them on
          // different HBM ports. Grouped together they land on one port and share
          // its single read-data channel: every completing continuation needs a
          // counter read AND a task read, so at 1 beat/cycle the notifier releases
          // only one ready task per 2 cycles -> the whole decoupled loop is pinned
          // at II=2. On separate ports the two reads return in parallel (II=1).
          // (argRoute(i) = m_axi_counter, argRoute(i+serverCount) = m_axi_task; see
          //  ArgumentNotifier.scala.)
          val counterPort = notifier.axi_full_argRoute(serverIndex)
          val taskPort = notifier.axi_full_argRoute(serverIndex + serverCount)
          if (useHistoricalLegacyGrouping) {
            argumentNotifierGroups.addOne(
              HbmInterfaceGroup(
                s"argumentNotifier:${task.name}:$serverIndex",
                Seq(counterPort, taskPort),
                Seq("counter", "task")
              )
            )
          } else {
            argumentNotifierGroups.addOne(
              HbmInterfaceGroup(
                s"argumentNotifier:${task.name}:$serverIndex#counter",
                Seq(counterPort),
                Seq("counter")
              )
            )
            argumentNotifierGroups.addOne(
              HbmInterfaceGroup(
                s"argumentNotifier:${task.name}:$serverIndex#task",
                Seq(taskPort),
                Seq("task")
              )
            )
          }
          interfacesArgumentNotifier.addOne(counterPort)
          interfacesArgumentNotifier.addOne(taskPort)
        }
      }
    }

    newArgumentNotifierMap.foreach { case (taskName, notifier) =>
      notifier.m_axi_slow.zipWithIndex.foreach { case (port, index) =>
        argumentNotifierGroups.addOne(
          HbmInterfaceGroup(
            s"newArgumentNotifier:$taskName:slow:$index",
            Seq(port), Seq("slow")))
        interfacesArgumentNotifier.addOne(port)
      }
      notifier.m_axi_evict.zipWithIndex.foreach { case (port, index) =>
        argumentNotifierGroups.addOne(
          HbmInterfaceGroup(
            s"newArgumentNotifier:$taskName:evict:$index",
            Seq(port), Seq("evict")))
        interfacesArgumentNotifier.addOne(port)
      }
    }

    val interfacesRemoteMemAccess = new ArrayBuffer[axi4.full.Interface]()
    val remoteMemAccessGroups = new ArrayBuffer[HbmInterfaceGroup]()
    fullSysGenDescriptor.taskDescriptors.foreach { task =>
      remoteMemAccessMap.get(task.name).foreach { remote =>
        remoteMemAccessGroups.addOne(
          HbmInterfaceGroup(s"remoteMemAccess:${task.name}", Seq(remote.io.m_axi_mem))
        )
        interfacesRemoteMemAccess.addOne(remote.io.m_axi_mem)
      }
    }

    val interfacesPE = peInterfaceGroups.flatMap(_.interfaces).to(ArrayBuffer)

    val numHBMPorts = reduceAxi
    val hbmSlaves = Seq.fill(numHBMPorts)(new ArrayBuffer[axi4.full.Interface]())

    // Reserve one HBM port per dedicated-PE main master at the FRONT (slots
    // 0..numDedicated-1). These are removed from the shared pool above, so the
    // proportional PE/server allocation below only distributes the REMAINING
    // ports. Each dedicated port carries exactly one master -> PATH 1.
    val numDedicated = dedicatedGroups.size
    // Reserved front ports = dedicated (1 master each) + consolidated
    // (totalAxiPorts). Both are pulled out of the shared pool; the proportional
    // PE/server allocation below starts at `numReserved`.
    val numConsolidated = consolidatedTaskGroups.map(_._1).sum
    val numReserved = numDedicated + numConsolidated
    require(
      numReserved < numHBMPorts,
      s"dedicatedAxiPort/generateRAMA/totalAxiPorts requested $numReserved reserved port(s) but only " +
        s"$numHBMPorts HBM port(s) exist; leave at least one for schedulers/servers"
    )
    val remainingPorts = numHBMPorts - numReserved
    dedicatedGroups.zipWithIndex.foreach { case (g, i) =>
      hbmSlaves(i).addAll(g.interfaces)
    }
    if (numDedicated > 0)
      println(s"[HBM:Interconnect] Reserved $numDedicated dedicated PE port(s) (0..${numDedicated - 1})")
    if (numConsolidated > 0)
      println(s"[HBM:Interconnect] Reserved $numConsolidated consolidated PE port(s) (${numDedicated}..${numReserved - 1})")

    val totalPorts =
      interfacesPE.length + interfacesMemoryAllocator.length + interfacesScheduler.length + interfacesClosureAllocator.length + interfacesArgumentNotifier.length + interfacesRemoteMemAccess.length

    // log the number of total ports
    println(s"[HBM:Interconnect:92] Total ports: $totalPorts")

    // log the interfaces from each module
    println(s"[HBM:Interconnect:95] PE interfaces: ${interfacesPE.length}")
    println(s"[HBM:Interconnect:96] Scheduler interfaces: ${interfacesScheduler.length}")
    println(s"[HBM:Interconnect:97] Closure Allocator interfaces: ${interfacesClosureAllocator.length}")
    println(s"[HBM:Interconnect:98] Argument Notifier interfaces: ${interfacesArgumentNotifier.length}")
    println(s"[HBM:Interconnect:99] Memory Allocator interfaces: ${interfacesMemoryAllocator.length}")


    def assignGroupsToHbmPorts(
        groups: Seq[HbmInterfaceGroup],
        firstPort: Int,
        portCount: Int
    ): Unit = {
      if (groups.nonEmpty && portCount > 0) {
        val targetPortsPerMux =
          math.max(1.0, groups.map(_.size).sum.toDouble / portCount.toDouble)
        var portIndex = firstPort
        var portsInCurrentMux = 0

        groups.foreach { group =>
          val canAdvance =
            portIndex < firstPort + portCount - 1 &&
              portsInCurrentMux > 0 &&
              portsInCurrentMux + group.size > targetPortsPerMux

          if (canAdvance) {
            portIndex += 1
            portsInCurrentMux = 0
          }

          hbmSlaves(portIndex).addAll(group.interfaces)
          portsInCurrentMux += group.size
        }
      }
    }

    // The U55C HBM SAXI preserves at most six AXI ID bits. Server allocation
    // accounts for the mux-select bits up front so a legal native ID is not
    // needlessly collapsed later by PATH 3.
    val hbmIdCap = 6
    var unfairServerPackingDetails = Seq.empty[String]

    // Pack each consolidating task's main masters onto its reserved port block,
    // split as evenly as possible. Each reserved port becomes a flat, shape-
    // uniform mux -> PATH 2 (native ids, no ProtocolConverter), and is never
    // re-muxed with the shared pool below.
    var consolCursor = numDedicated
    consolidatedTaskGroups.foreach { case (portCount, groups) =>
      assignGroupsToHbmPorts(groups, consolCursor, portCount)
      consolCursor += portCount
    }

    if (totalPorts > 0) {
      val serverGroups =
        memoryAllocatorGroups ++ schedulerInterfaceGroups ++ closureAllocatorGroups ++
          argumentNotifierGroups ++ remoteMemAccessGroups

      val numPortsPerMux = totalPorts.toDouble / remainingPorts.toDouble
      val requestedPeMux =
        if (interfacesPE.nonEmpty)
          math.ceil(interfacesPE.length.toDouble / numPortsPerMux).toInt
        else
          0
      val peMux =
        math.min(
          remainingPorts,
          math.max(0, requestedPeMux)
        ) match {
          case mux if serverGroups.nonEmpty && mux == remainingPorts && remainingPorts > 1 =>
            remainingPorts - 1
          case mux => mux
        }
      val serverMux = remainingPorts - peMux

      // Decompose every PE-owned master into its OWN assignable unit, then group
      // by exact (wData, wId) shape. A single PE can own masters of different
      // shapes (e.g. m_axi_gmem 32b/id1, m_axi_spawnNext 512b/id0, m_axi_argOut
      // 32b/id0), so bundling them per-PE forces a mixed-shape port -> PATH 3
      // id-collapse. Grouping per interface lets every muxed port stay shape-
      // uniform, the precondition for PATH 1/2 (native ids, no ProtocolConverter).
      val peShapeGroups: Seq[HbmInterfaceGroup] =
        peInterfaceGroups.toSeq.flatMap { g =>
          g.interfaces.zipWithIndex.map { case (iface, ii) =>
            HbmInterfaceGroup(s"${g.name}#$ii", Seq(iface))
          }
        }

      // Distinct shape classes, biggest population first (then by width, id).
      val peByShape: Seq[((Int, Int), Seq[HbmInterfaceGroup])] =
        peShapeGroups
          .groupBy(g => (g.interfaces.head.cfg.wData, g.interfaces.head.cfg.wId))
          .toSeq
          .sortBy { case (shape, gs) => (-gs.map(_.size).sum, shape._1, shape._2) }
      val totalPeIfaces = math.max(1, peShapeGroups.map(_.size).sum)

      if (peByShape.length <= peMux) {
        // Enough PE ports to give every shape its own contiguous, proportional
        // block -> no port ever mixes shapes.
        var portCursor = 0
        peByShape.zipWithIndex.foreach { case ((_, groups), idx) =>
          val classesLeft = peByShape.length - idx
          val portsLeft   = peMux - portCursor
          val share =
            if (idx == peByShape.length - 1) portsLeft
            else math.max(
              1,
              math.min(
                portsLeft - (classesLeft - 1),
                math.round(peMux.toDouble * groups.map(_.size).sum / totalPeIfaces).toInt
              )
            )
          assignGroupsToHbmPorts(groups, numReserved + portCursor, share)
          portCursor += share
        }
      } else {
        // More distinct shapes than available PE ports: cannot isolate them all.
        // Keep the original per-PE packing; the remaining mixed ports fall back to
        // PATH 3 (and warn) rather than silently corrupting ids.
        assignGroupsToHbmPorts(peInterfaceGroups.toSeq, numReserved, peMux)
      }
      // Shape-aware server allocation. ID widths may differ because PATH 2b can
      // zero-extend them, and user fields are yanked before the mux. Everything
      // else must agree, especially read/write direction: ProtocolConverter
      // preserves those capabilities and cannot connect a write-only allocator
      // to a read/write mux port.
      def muxCompatibleServerShape(cfg: axi4.Config): axi4.Config =
        cfg.copy(
          wId = 0,
          wUserAR = 0,
          wUserR = 0,
          wUserAW = 0,
          wUserW = 0,
          wUserB = 0
        )
      val serverByShape: Seq[(axi4.Config, Seq[HbmInterfaceGroup])] =
        serverGroups.toSeq
          .groupBy(g => muxCompatibleServerShape(g.interfaces.head.cfg))
          .toSeq
          .sortBy { case (shape, gs) =>
            (-gs.map(_.size).sum, shape.wData, shape.wId)
          }
      if (serverByShape.length <= serverMux && serverGroups.nonEmpty) {
        // First give every shape enough ports to keep maxNativeId + muxSelect
        // within the HBM ID cap. Low-ID masters can therefore share aggressively,
        // while an ID-6 master must remain private. Spend any ports left over on
        // the currently busiest shape to avoid gratuitous traffic imbalance.
        val serverFirst = numReserved + peMux
        final case class ServerShapeAllocation(
            shape: axi4.Config,
            groups: Seq[HbmInterfaceGroup],
            maxNativeId: Int,
            var ports: Int
        ) {
          val interfaceCount: Int = groups.map(_.size).sum
          val maxPerPort: Int =
            if (maxNativeId >= hbmIdCap) 1 else 1 << (hbmIdCap - maxNativeId)
          def load: Int = (interfaceCount + ports - 1) / ports
          def canUseAnotherPort: Boolean = ports < interfaceCount
        }

        val allocations = serverByShape.map { case (shape, groups) =>
          val count = groups.map(_.size).sum
          val maxId = groups.flatMap(_.interfaces.map(_.cfg.wId)).max
          val maxPerPort = if (maxId >= hbmIdCap) 1 else 1 << (hbmIdCap - maxId)
          ServerShapeAllocation(
            shape,
            groups,
            maxId,
            ports = math.max(1, (count + maxPerPort - 1) / maxPerPort)
          )
        }

        val minimumPorts = allocations.map(_.ports).sum
        if (minimumPorts <= serverMux) {
          var portsLeft = serverMux - minimumPorts
          while (portsLeft > 0 && allocations.exists(_.canUseAnotherPort)) {
            val chosen = allocations.filter(_.canUseAnotherPort).maxBy { a =>
              // Highest current fan-in first. Ties favor more total traffic,
              // then lower IDs (which remain the preferred sharing class).
              (a.load, a.interfaceCount, -a.maxNativeId)
            }
            chosen.ports += 1
            portsLeft -= 1
          }

          var portCursor = serverFirst
          // Place the most shareable (smallest-ID) classes first. This ordering
          // is cosmetic for HBM, but makes the allocation summary intuitive.
          allocations.sortBy(a => (a.maxNativeId, -a.interfaceCount)).foreach { a =>
            assignGroupsToHbmPorts(a.groups, portCursor, a.ports)
            portCursor += a.ports
          }
        } else {
          // There are not enough physical ports for an ID-safe allocation.
          // Keep the generic balanced placement; PATH 3 will identify every
          // actual overflow with its existing red correctness/performance warning.
          assignGroupsToHbmPorts(serverGroups.toSeq, serverFirst, serverMux)
        }
      } else {
        assignGroupsToHbmPorts(serverGroups.toSeq, numReserved + peMux, serverMux)
      }

      val serverFirst = numReserved + peMux
      val occupiedServerPorts =
        (serverFirst until (serverFirst + serverMux)).filter(hbmSlaves(_).nonEmpty)
      val crowded = occupiedServerPorts.filter(hbmSlaves(_).size >= 3)
      val idForcedPrivate = occupiedServerPorts.filter { p =>
        hbmSlaves(p).size == 1 && hbmSlaves(p).head.cfg.wId >= hbmIdCap
      }
      unfairServerPackingDetails = crowded.flatMap { crowdedPort =>
        val crowdedMaxId = hbmSlaves(crowdedPort).map(_.cfg.wId).max
        idForcedPrivate.collect {
          case privatePort if hbmSlaves(privatePort).head.cfg.wId > crowdedMaxId =>
            val crowdedName = f"m_axi_$crowdedPort%02d"
            val privateName = f"m_axi_$privatePort%02d"
            s"$crowdedName has ${hbmSlaves(crowdedPort).size} masters (max id=$crowdedMaxId) " +
              s"while $privateName is private for id=${hbmSlaves(privatePort).head.cfg.wId}"
        }
      }.distinct

      // ---- Port allocation summary ------------------------------------
      if (numDedicated > 0)
        println(s"[HBM:Interconnect] Dedicated PE ports: $numDedicated (0..${numDedicated - 1})")
      println(s"[HBM:Interconnect] Port budget: $peMux PE ports (${numReserved}..${numReserved + peMux - 1}), $serverMux server ports (${numReserved + peMux}..${numReserved + peMux + serverMux - 1})")
      peByShape.foreach { case ((dw, id), gs) =>
        println(s"[HBM:Interconnect]   PE shape (wData=$dw, wId=$id): ${gs.map(_.size).sum} interfaces")
      }
      serverByShape.foreach { case (shape, gs) =>
        val idWidths = gs.flatMap(_.interfaces.map(_.cfg.wId)).distinct.sorted
        println(
          s"[HBM:Interconnect]   Server wData=${shape.wData}, " +
            s"read=${shape.read}, write=${shape.write}: ${gs.map(_.size).sum} " +
            s"interfaces (wId=${idWidths.mkString(",")})"
        )
      }
      println("[HBM:Interconnect]   Per-port mapping:")
      hbmSlaves.zipWithIndex.foreach { case (buf, idx) =>
        if (buf.nonEmpty) {
          val shapes = buf.map(i => s"(${i.cfg.wData},id${i.cfg.wId})").groupBy(identity).map { case (k, v) => s"${v.size}×$k" }.mkString(", ")
          val portName = f"m_axi_${idx}%02d"
          println(s"[HBM:Interconnect]     $portName: ${buf.size} masters — $shapes")
        }
      }
    }

    def hbmSkidBuffer(source: axi4.full.Interface): axi4.full.Interface =

      axi4.full.SlaveBuffer(source, axi4.BufferConfig.all(8))

    def connectThroughHbmSkidBuffer(
        source: axi4.full.Interface,
        sink: axi4.full.Interface
    ): Unit =
      hbmSkidBuffer(source) :=> sink


    if (fullSysGenDescriptor.hasAXIDMAInput) {
      val xdma_axi = IO(axi4.Slave(cfgXDMA)).suggestName("s_axi_xdma")
      hbmSlaves(numHBMPorts - 1).addOne(
        axi4.full.SlaveBuffer(xdma_axi.asFull, axi4.BufferConfig.all(8))
      )
      interfaceBuffer.addOne(
        hdlinfo.Interface(
          "s_axi_xdma", hdlinfo.InterfaceRole.slave, hdlinfo.InterfaceKind("axi4"),
          "clock", "reset", Map("config" -> hdlinfo.TypedObject(cfgXDMA))
        )
      )
      axiXDMA.addOne(xdma_axi)
    }

    // hbmSlaves can contain empty physical buckets, while exported m_axi_NN
    // indices are compact. Derive descriptor-forced indices in that exact order.
    val compactBuckets = hbmSlaves.filter(_.nonEmpty).zipWithIndex
    val forcedRamaPorts = compactBuckets.collect {
      case (bucket, exportedIndex)
          if bucket.length == 1 && interfacesRama.exists(_ eq bucket.head) => exportedIndex
    }
    require(
      forcedRamaPorts.length == interfacesRama.length,
      s"generateRAMA=true requested ${interfacesRama.length} PE port(s), but only ${forcedRamaPorts.length} ended up on unshared exported HBM ports"
    )

    val forcedNoRamaPorts = compactBuckets.collect {
      case (bucket, exportedIndex)
          if bucket.exists(iface => interfacesNoRama.exists(_ eq iface)) =>
        if (enableRamaByDefault) {
          require(
            bucket.forall(iface => interfacesNoRama.exists(_ eq iface)),
            s"generateRAMA=false traffic reached shared exported HBM port $exportedIndex while a global RAMA mode is enabled"
          )
        }
        exportedIndex
    }.toSet

    ramaPortIndices =
      if (enableRamaByDefault)
        compactBuckets.map(_._2).filterNot(forcedNoRamaPorts).toSeq
      else forcedRamaPorts.toSeq
    if (ramaPortIndices.nonEmpty) {
      println(
        s"[HBM:Interconnect] RAMA enabled on exported port(s): ${ramaPortIndices.map(i => f"m_axi_$i%02d").mkString(", ")}"
      )
    }

    val axi3CompatFlag = false
    numHbmPortExports = hbmSlaves.count(_.nonEmpty)
    // ------------------------------------------------------------------
    // Per-HBM-port export. Four paths, fastest first:
    //   PATH 1   direct passthrough  (1 master / port)            -> native id, no PC
    //   PATH 2   mux, no id collapse (N same-shape masters)       -> native id, no PC
    //   PATH 2b  mux, id zero-extend (N same-wData, mixed-wId)   -> widened id, no PC
    //   PATH 3   fallback id-collapse (mixed wData / overflow)    -> ProtocolConverter
    //
    // The HBM SAXI hard-caps the AXI id width at 6 bits. We may keep the full
    // native id (up to 6) ONLY on PATHs 1 & 2, which never instantiate the
    // ProtocolConverter (IdSerialize + Upscale) — that replicated machinery is
    // what wrecks timing. PATH 3 is forced to collapse the id back to 2 bits and
    // cannot close at a high Fmax, hence the loud warning so the user knows.
    // ------------------------------------------------------------------
    val collapsedIdWidth = 2

    def bigRedWarning(title: String, lines: Seq[String]): Unit = {
      val red   = "[1;37;41m"
      val reset = "[0m"
      val body  = title +: lines
      val w     = body.map(_.length).max
      val bar   = "#" * (w + 4)
      println(red + bar + reset)
      body.foreach(l => println(red + "# " + l.padTo(w, ' ') + " #" + reset))
      println(red + bar + reset)
    }

    if (unfairServerPackingDetails.nonEmpty) {
      bigRedWarning(
        "HBM server allocation is ID-safe but traffic-imbalanced",
        unfairServerPackingDetails ++ Seq(
          "effect : a crowded low-ID port may become a throughput bottleneck",
          "reason : the private high-ID master cannot share without exceeding the HBM ID cap",
          "fix    : add HBM ports, reduce the high native ID width, or accept PATH 3 ID collapse"
        )
      )
    }

    // ---- HBM port -> owner descriptor (for the telemetry viewer) ------------
    // Map every exported m_axi_NN (the COMPACTED index used by the export loop and
    // therefore by the watcher's bandwidth taps, axiOuts(p)) to the module masters
    // attached to it, using the same group names printed in the per-port summary.
    // Owner lookup is by reference identity (the same interface objects live in
    // both the groups and hbmSlaves).
    locally {
      val ownerOf = new java.util.IdentityHashMap[axi4.full.Interface, String]()
      val roleOf = new java.util.IdentityHashMap[axi4.full.Interface, String]()
      def regOwners(groups: Seq[HbmInterfaceGroup]): Unit = groups.foreach { g =>
        g.interfaces.zipWithIndex.foreach { case (iface, gi) =>
          val role = g.roleAt(gi)
          ownerOf.put(iface, if (g.interfaces.size > 1) s"${g.name}#$role" else g.name)
          roleOf.put(iface, role)
        }
      }
      regOwners(dedicatedGroups.toSeq)
      regOwners(consolidatedTaskGroups.flatMap(_._2).toSeq)
      regOwners(peInterfaceGroups.toSeq)
      regOwners(schedulerInterfaceGroups.toSeq)
      regOwners(memoryAllocatorGroups.toSeq)
      regOwners(closureAllocatorGroups.toSeq)
      regOwners(argumentNotifierGroups.toSeq)
      regOwners(remoteMemAccessGroups.toSeq)

      // ---- Explicit physical STATUS-slot table ------------------------------
      // Each descriptor element maps directly to one of the fixed HLS status_0..21
      // nibbles. The generated trace descriptor preserves the complete mapping so
      // hosts/viewers never infer connectivity from PE counts or HLS array names.
      val slots = fullSysGenDescriptor.watcherConfig.toSeq.flatMap(_.statusSlots)
      val peSlots = scala.collection.mutable.Map[(String, Int), Int]()
      val pesEntries = slots.zipWithIndex.map { case (slot, slotIndex) =>
        val label = if (slot.label.nonEmpty) slot.label else s"statusSlot:$slotIndex"
        val fieldsJson = slot.fields.map { field =>
          val t = field.target
          val cachedOnly = Set("slowUpdateHandler", "evictionSaver", "argumentServer")
            .contains(t.kind)
          val active = !cachedOnly || fullSysGenDescriptor.taskDescriptors
            .find(_.name == t.taskName).exists(_.usesNewArgumentNotifier)
          val activityJson =
            if (active) "\"active\":true"
            else "\"active\":false,\"inactiveReason\":\"argument-server-mode\""
          val selectorJson = t.kind match {
            case "pe" | "slowUpdateHandler" | "evictionSaver" =>
              s",\"port\":\"${t.port}\""
            case "schedulerServer" =>
              s",\"signal\":\"${t.signal}\""
            case "argumentServer" =>
              s""","port":"${t.port}","lane":${t.lane}"""
            case _ => "" // rejected by descriptor validation before elaboration
          }
          s"""{"encoding":"${field.encoding}",$activityJson,"target":{"kind":"${t.kind}","taskName":"${t.taskName}","index":${t.index}$selectorJson}}"""
        }.mkString(",")
        val peTargets = slot.fields.map(_.target).filter(_.kind == "pe")
        // The host's PE conservation view interprets a canonical PE nibble as
        // two ready/valid handshakes in descriptor order: input, then output.
        // Keep every other legal packing visible as a generic packed slot.
        val isPeSlot = slot.fields.size == 2 &&
          slot.fields.forall(_.encoding == "readyValid2") &&
          peTargets.size == slot.fields.size &&
          peTargets.forall(t => t.taskName == peTargets.head.taskName && t.index == peTargets.head.index)
        if (isPeSlot) {
          val pe = peTargets.head
          peSlots((pe.taskName, pe.index)) = slotIndex
          s"""    {"peNumber": $slotIndex, "kind": "pe", "label": "$label", "task": "${pe.taskName}", "statusPrefix": "$label", "indexInTask": ${pe.index}, "fields": [$fieldsJson]}"""
        } else {
          s"""    {"peNumber": $slotIndex, "kind": "packed", "label": "$label", "task": "watcher:packed", "statusPrefix": "$label", "indexInTask": 0, "fields": [$fieldsJson]}"""
        }
      }
      val pesJson = pesEntries.mkString(",\n")

      // Map a port master's owner string to the STATUS PE# it belongs to, or None
      // for shared servers (scheduler/allocator/argumentNotifier) that are not a
      // single selected PE. Per-PE kinds carry the PE instance index as the 3rd
      // colon field (before any '#interface' suffix).
      val perPeKinds = Set("pe", "spawnNextWB", "sendArgumentWB")
      def peNumberOf(owner: String): Option[Int] = {
        val parts = owner.split(":")
        if (parts.length >= 3 && perPeKinds.contains(parts(0))) {
          val task   = parts(1)
          val idxStr = parts(2).takeWhile(_ != '#')
          scala.util.Try(idxStr.toInt).toOption.flatMap(idx => peSlots.get((task, idx)))
        } else None
      }

      val portsJson = hbmSlaves.filter(_.nonEmpty).zipWithIndex.map { case (buf, i) =>
        val mastersJson = buf.toSeq.map { iface =>
          val owner = Option(ownerOf.get(iface)).getOrElse("xdma_or_external")
          val role = Option(roleOf.get(iface)).getOrElse("unknown")
          val peNum = peNumberOf(owner).map(_.toString).getOrElse("null")
          s"""        {"owner": "$owner", "role": "$role", "peNumber": $peNum, "wData": ${iface.cfg.wData}, "wId": ${iface.cfg.wId}}"""
        }.mkString(",\n")
        s"""    {
      "port": $i,
      "portName": "m_axi_${"%02d".format(i)}",
      "masters": [
$mastersJson
      ]
    }"""
      }.mkString(",\n")

      val compactHbmSlaves = hbmSlaves.filter(_.nonEmpty)
      def hbmPortForOwner(ownerPrefix: String): String =
        compactHbmSlaves.zipWithIndex.collectFirst {
          case (buf, port) if buf.exists(iface =>
              Option(ownerOf.get(iface)).exists(_.startsWith(ownerPrefix))) => port.toString
        }.getOrElse("null")

      val managementEntries = fullSysGenDescriptor.taskDescriptors.flatMap { task =>
        val schedulerEntries = task.mgmtBaseAddresses.schedulerServersBaseAddresses.zipWithIndex.map {
          case (baseAddress, index) =>
            val rootSeed = task.isRoot
            s"""    {"kind":"scheduler","task":"${task.name}","index":$index,"baseAddress":$baseAddress,"registerLayout":"scheduler-v1","queueCapacity":${task.getCapacityVirtualQueue("scheduler")},"entryBytes":${task.widthTask / 8},"hbmPort":${hbmPortForOwner(s"scheduler:${task.name}:vss:$index")},"rootSeed":$rootSeed}"""
        }
        val spawnerEntries = task.mgmtBaseAddresses.spawnerServersBaseAddresses.zipWithIndex.map {
          case (baseAddress, index) =>
            s"""    {"kind":"spawner","task":"${task.name}","index":$index,"baseAddress":$baseAddress,"registerLayout":"spawner-v1","queueCapacity":${task.getCapacityVirtualQueue("scheduler")},"entryBytes":${task.widthTask / 8},"hbmPort":${hbmPortForOwner(s"spawner:${task.name}:$index")},"rootSeed":false}"""
        }
        val allocatorEntries = task.mgmtBaseAddresses.allocationServersBaseAddresses.zipWithIndex.map {
          case (baseAddress, index) =>
            s"""    {"kind":"allocator","task":"${task.name}","index":$index,"baseAddress":$baseAddress,"registerLayout":"allocator-v1","queueCapacity":${task.getCapacityVirtualQueue("allocator")},"entryBytes":${fullSysGenDescriptor.widthAddress / 8},"hbmPort":${hbmPortForOwner(s"closureAllocator:${task.name}:$index")},"rootSeed":false}"""
        }
        val memoryAllocatorEntries = task.mgmtBaseAddresses.memoryAllocatorServersBaseAddresses.zipWithIndex.map {
          case (baseAddress, index) =>
            s"""    {"kind":"memoryAllocator","task":"${task.name}","index":$index,"baseAddress":$baseAddress,"registerLayout":"memory-allocator-v1","queueCapacity":${task.getCapacityVirtualQueue("memoryAllocator")},"entryBytes":${fullSysGenDescriptor.widthAddress / 8},"hbmPort":${hbmPortForOwner(s"memoryAllocator:${task.name}:$index")},"rootSeed":false}"""
        }
        schedulerEntries ++ spawnerEntries ++ allocatorEntries ++ memoryAllocatorEntries
      }.mkString(",\n")

      val managementBase = if (fullSysGenDescriptor.isVitisProject) 0x10 else 0
      val globalStartAddress =
        if (enableGlobalStart)
          ((fullSysGenDescriptor.getNumConfigPorts() << 6) + managementBase).toString
        else "null"

      val stripedHostMapping = ramaStripingEnabled && ramaPortIndices.nonEmpty
      val stripeMemoryCount =
        if (fullSysGenDescriptor.watcherConfig.isDefined) 16 else 32
      val ramaPortsJson = ramaPortIndices.sorted.mkString(", ")
      val hostMappingJson =
        if (stripedHostMapping)
          s"""{"mode":"per_memory","runtimeModes":["hw","questa"],"firstBank":0,"memoryCount":$stripeMemoryCount,"fragmentBytes":64,"bytesPerBank":536870912}"""
        else
          """{"mode":"none","runtimeModes":[]}"""

      val ramaMode =
        if (ramaStripingEnabled) "striped"
        else if (enableRamaByDefault) "non-striped"
        else "descriptor-only"

      hbmPortMappingJson =
        s"""{
  "schemaVersion": 2,
  "design": "${fullSysGenDescriptor.name}",
  "numComputePorts": ${hbmSlaves.count(_.nonEmpty)},
  "build": {"architecture":"${generatorProfile.architecture.cliName}","argumentServer":"${generatorProfile.argumentServer.cliName}","argumentServerImplementation":"${generatorProfile.argumentServerImplementation}","referenceCommit":${if (generatorProfile.isLegacy) "\"2469686\"" else "null"},"ramaMode":"$ramaMode","globalStart":{"available":${!generatorProfile.isLegacy},"enabled":$enableGlobalStart,"registerAddress":$globalStartAddress}},
  "management": {"registerStrideBytes":64,"servers":[
$managementEntries
  ]},
  "note": "'pes' is the configured physical STATUS-slot table. Each port master carries 'role' (main = the m_axi_gmem compute port; argOut/argDataOut/spawnNext = argument/continuation write-buffer ports; ring/spawner = scheduler ports) and 'peNumber' = the selected STATUS slot that owns it, or null for shared servers (scheduler/allocator/argumentNotifier). port index == watcher BW_READ/BW_WRITE tap index. owner = '<kind>:<task>:<index>[#<role>]'.",
  "rama": {"ports": [$ramaPortsJson], "hostMapping": $hostMappingJson},
  "statusSlotSchema": {"slotBits": 4, "fieldOrder": "lowToHigh", "encodings": {"boolean1": {"bits": 1}, "readyValid2": {"bits": 2, "bit0": "valid", "bit1": "ready"}}},
  "pes": [
$pesJson
  ],
  "ports": [
$portsJson
  ]
}
"""
    }

    hbmSlaves.filter(_.nonEmpty).zipWithIndex.foreach {
      case (hbmSlave, i) =>
        val portName       = f"m_axi_${i}%02d"
        val interfaceCount = hbmSlave.length
        val dataWidths     = hbmSlave.map(_.cfg.wData).distinct
        val idWidths       = hbmSlave.map(_.cfg.wId).distinct
        def muxCompatibleShape(cfg: axi4.Config): axi4.Config =
          cfg.copy(
            wId = 0,
            wUserAR = 0,
            wUserR = 0,
            wUserAW = 0,
            wUserW = 0,
            wUserB = 0
          )
        val compatibleShapes = hbmSlave.map(i => muxCompatibleShape(i.cfg)).distinct
        val uniformShape = compatibleShapes.length == 1 && idWidths.length == 1
        val uniformNonIdShape = compatibleShapes.length == 1
        val selBits        = if (interfaceCount > 1) log2Ceil(interfaceCount) else 0
        val nativeMuxId    = hbmSlave.map(_.cfg.wId).max + selBits
        // The HBM controller port is natively 256b, but Vitis inserts a width
        // converter for any kernel m_axi up to 1024b — both upsizing
        // (64/128->256) and downsizing (1024/512->256), preserving the id.
        // 1024b is the hard ceiling: AXI4's data bus maxes out at 1024 bits.
        // So anything <= 1024b rides the native paths and never needs in-fabric
        // narrowing / id-collapse.
        val vitisMaxWidth   = 1024
        val vitisCanConvert = hbmSlave.forall(_.cfg.wData <= vitisMaxWidth)

        // Create the exported HBM master IO + register its hdlinfo, then drive it
        // from `src` (optionally through the address-bit permutation).
        def exportFrom(src: axi4.full.Interface): Unit = {
          val axiOut = IO(axi4.Master(src.cfg)).suggestName(portName)
          if (addressTransformFlag) {
            val addressTransform = Module(new Util.AddressTransform(
              AddressTransformConfig(
                axiCfg = axiOut.cfg,
                transform = Seq(33, 23, 22, 21, 20, 28, 27, 26, 25, 24, 32, 31, 30, 29, 19, 18, 17, 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0).reverse
              )
            ))
            connectThroughHbmSkidBuffer(src, addressTransform.s_axi)
            connectThroughHbmSkidBuffer(addressTransform.m_axi, axiOut.asFull)
          } else {
            connectThroughHbmSkidBuffer(src, axiOut.asFull)
          }
          interfaceBuffer.addOne(
            hdlinfo.Interface(
              portName, hdlinfo.InterfaceRole.master, hdlinfo.InterfaceKind("axi4"),
              "clock", "reset", Map("config" -> hdlinfo.TypedObject(axiOut.cfg))
            )
          )
          axiOuts.addOne(axiOut)
        }

        if (interfaceCount == 1 && vitisCanConvert && nativeMuxId <= hbmIdCap) {
          // ===== PATH 1: DIRECT PASSTHROUGH ==============================
          // Single master on this port -> no arbitration. Strip user bits
          // (cheap) and let Vitis upsize native->256. Native id preserved
          // (<= 6 bits, so it survives the HBM SAXI id width untruncated).
          exportFrom(AxiUserYanker(hbmSlave.head))

        } else if (interfaceCount > 1 && uniformShape && nativeMuxId <= hbmIdCap && vitisCanConvert) {
          // ===== PATH 2: MUX, NO ID COLLAPSE =============================
          // Same-shape masters -> a plain Mux preserves native ids (output id
          // = native + select bits, still <= 6). Vitis upsizes the single mux
          // output to 256. No ProtocolConverter / Upscale / IdSerialize.
          val muxSlaveCfg = hbmSlave.head.cfg.copy(
            wUserAR = 0, wUserR = 0, wUserAW = 0, wUserW = 0, wUserB = 0
          )
          val mux = Module(
            new axi4.full.components.Mux(
              new axi4.full.components.MuxConfig(
                axiSlaveCfg = muxSlaveCfg,
                numSlaves   = interfaceCount
              )
            )
          )
          mux.s_axi.zip(hbmSlave).foreach { case (muxPort, slavePort) =>
            axi4.full.SlaveBuffer(AxiUserYanker(slavePort), axi4.BufferConfig.all(8)) :=> muxPort
          }
          exportFrom(mux.m_axi)

        } else if (interfaceCount > 1 && uniformNonIdShape && nativeMuxId <= hbmIdCap && vitisCanConvert) {
          // ===== PATH 2b: MUX WITH ID ZERO-EXTENSION ====================
          // Same data width but mixed id widths. Zero-extend all interfaces
          // to the widest id, then use a plain Mux.  This is free in
          // hardware (just wiring) and avoids the expensive
          // ProtocolConverter / IdSerialize path.
          val maxWId = idWidths.max
          println(s"[HBM:Interconnect] $portName: PATH 2b — zero-extending ids to $maxWId bits (from ${idWidths.mkString(",")})")
          val muxSlaveCfg = hbmSlave.head.cfg.copy(
            wId = maxWId,
            wUserAR = 0, wUserR = 0, wUserAW = 0, wUserW = 0, wUserB = 0
          )
          val mux = Module(
            new axi4.full.components.Mux(
              new axi4.full.components.MuxConfig(
                axiSlaveCfg = muxSlaveCfg,
                numSlaves   = interfaceCount
              )
            )
          )
          mux.s_axi.zip(hbmSlave).foreach { case (muxPort, slavePort) =>
            axi4.full.SlaveBuffer(
              AxiIdZeroExtend(AxiUserYanker(slavePort), maxWId),
              axi4.BufferConfig.all(8)
            ) :=> muxPort
          }
          exportFrom(mux.m_axi)

        } else {
          // ===== PATH 3: FALLBACK (id collapse via ProtocolConverter) ====
          // Mixed shapes, id-budget overflow, or a >256b server port. Must
          // instantiate the ProtocolConverter (IdSerialize + Upscale) and
          // collapse the id -> 2 bits. This path will NOT close timing high.
          // Each fallback cause has its OWN fix; print only the relevant one so
          // the message is actionable (grouping does NOT help a >256b port).
          val (reason, fix) =
            if (!vitisCanConvert)
              (s"a port wider than ${vitisMaxWidth}b exceeds the Vitis kernel-AXI max (data=${dataWidths.mkString(",")})",
               s"emit this master at <=${vitisMaxWidth}b (Vitis converts to the 256b HBM port for free); wider must be narrowed here")
            else if (!uniformShape)
              (s"mixed master shapes on one port (data=${dataWidths.mkString(",")}, id=${idWidths.mkString(",")})",
               "give this port a single PE shape (the tool auto-groups same-shape PEs when ports allow), or add HBM ports")
            else
              (s"native mux id $nativeMuxId exceeds the $hbmIdCap-bit HBM cap",
               "add HBM ports (fewer PEs per mux), or shrink the per-PE id width")
          bigRedWarning(
            s"HBM port $portName fell back to the SLOW id-collapse path",
            Seq(
              s"reason : $reason",
              s"effect : instantiates IdSerialize + Upscale, collapses id -> $collapsedIdWidth bits",
              s"fix    : $fix"
            )
          )
          require(
            uniformNonIdShape,
            s"$portName fallback cannot mux incompatible AXI capabilities: " +
              compatibleShapes.mkString(", ")
          )
          val sourceShape = hbmSlave.head.cfg
          val outputCfg = cfgAxi4HBM.copy(
            axi3Compat = axi3CompatFlag,
            wId = collapsedIdWidth,
            read = sourceShape.read,
            write = sourceShape.write
          )

          // Per-slave: yank user bits, collapse/convert to `sinkCfg`, widen if needed.
          def collapseConvert(slavePort: axi4.full.Interface, sinkCfg: axi4.Config): axi4.full.Interface = {
            val source = axi4.full.SlaveBuffer(
              AxiUserYanker(slavePort),
              axi4.BufferConfig.all(2)
            )

            // chext's ProtocolConverter unconditionally touches both its master
            // read and write channels while wiring its internal stages, so it
            // cannot elaborate a unidirectional AXI configuration. Closure
            // allocators are write-only; compose the same required operations
            // explicitly for them: serialize IDs, downscale the wide beat, then
            // zero-extend the collapsed ID to the shared mux width.
            if (!source.cfg.read && source.cfg.write) {
              val serialized =
                if (source.cfg.wId > 0) {
                  val idSerialize = Module(
                    new axi4.full.components.IdSerialize(
                      axi4.full.components.IdSerializeConfig(source.cfg)
                    )
                  )
                  source :=> idSerialize.s_axi
                  idSerialize.m_axi
                } else source

              val widthConverted =
                if (serialized.cfg.wData > sinkCfg.wData) {
                  val downscale = Module(
                    new axi4.full.components.Downscale(
                      axi4.full.components.DownscaleConfig(
                        serialized.cfg,
                        sinkCfg.wData
                      )
                    )
                  )
                  serialized :=> downscale.s_axi
                  downscale.m_axi
                } else serialized

              hbmSkidBuffer(AxiIdZeroExtend(widthConverted, sinkCfg.wId))
            } else {
              require(
                source.cfg.read && source.cfg.write,
                s"$portName fallback currently supports read/write or write-only masters"
              )
              val protocolConverter = Module(
                new axi4.full.components.ProtocolConverter(
                  new axi4.full.components.ProtocolConverterConfig(
                    axiSlaveCfg = source.cfg,
                    axiMasterCfg = sinkCfg
                  )
                )
              )
              source :=> protocolConverter.s_axi
              val protocolConverted = hbmSkidBuffer(protocolConverter.m_axi)
              if (slavePort.cfg.wData < sinkCfg.wData) {
                val widen_mod = Module(
                  new chext.amba.axi4.full.components.Widen(
                    chext.amba.axi4.full.components.WidenConfig(sinkCfg)
                  )
                )
                connectThroughHbmSkidBuffer(protocolConverted, widen_mod.s_axi)
                widen_mod.m_axi
              } else {
                protocolConverted
              }
            }
          }

          if (interfaceCount > 1) {
            val mux = Module(
              new axi4.full.components.Mux(
                new axi4.full.components.MuxConfig(
                  axiSlaveCfg = outputCfg,
                  numSlaves   = interfaceCount
                )
              )
            )
            mux.s_axi.zip(hbmSlave).foreach { case (muxPort, slavePort) =>
              connectThroughHbmSkidBuffer(collapseConvert(slavePort, muxPort.cfg), muxPort)
            }
            exportFrom(mux.m_axi)
          } else {
            exportFrom(collapseConvert(hbmSlave.head, outputCfg))
          }
        }
    }
  }
}
