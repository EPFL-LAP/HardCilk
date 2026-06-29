
package HardCilk

import chisel3._
import chisel3.util.log2Ceil
import Descriptors._
import Scheduler._
import Allocator._
import ArgumentNotifier._
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
  val cfgAxi4HBM: axi4.Config
  val cfgXDMA: axi4.Config
  val interfaceBuffer: ArrayBuffer[hdlinfo.Interface]
  val axiOuts: ArrayBuffer[axi4.RawInterface]
  val axiXDMA: ArrayBuffer[axi4.RawInterface]

  // This is an "output" var that this trait will update
  var numHbmPortExports: Int

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
      schedulerMap: Map[String, Scheduler],
      closureAllocatorMap: Map[String, Allocator],
      argumentNotifierMap: Map[String, ArgumentNotifier],
      memoryAllocatorMap: Map[String, Allocator],
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
      pe.io.elements
        .get("m_axi_spawnNext")
        .foreach(p => ports.addOne(("spawnNext", p.asInstanceOf[axi4.RawInterface].asFull)))
      pe.io.elements
        .get("m_axi_argOut")
        .foreach(p => ports.addOne(("argOut", p.asInstanceOf[axi4.RawInterface].asFull)))
      if (task.hasAXI) {
        ports.addOne(("main", pe.getPort("m_axi_gmem").asInstanceOf[axi4.RawInterface].asFull))
      }
      ports.toSeq
    }

    val peInterfaceGroups = new ArrayBuffer[HbmInterfaceGroup]()

    fullSysGenDescriptor.taskDescriptors.foreach { task =>
      peMap.get(task.name).foreach { peArray =>
        peArray.zipWithIndex.foreach { case (pe, peIndex) =>
          val rolePorts = peOwnedPorts(pe, task)
          if (rolePorts.nonEmpty) {
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
        scheduler.spawnerServerAXI.foreach { ports =>
          ports.zipWithIndex.foreach { case (port, portIndex) =>
            schedulerInterfaceGroups.addOne(
              HbmInterfaceGroup(s"scheduler:${task.name}:spawner:$portIndex", Seq(port), Seq("spawner"))
            )
          }
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
          val ports = Seq(
            notifier.axi_full_argRoute(serverIndex),
            notifier.axi_full_argRoute(serverIndex + serverCount)
          )
          argumentNotifierGroups.addOne(
            HbmInterfaceGroup(s"argumentNotifier:${task.name}:$serverIndex", ports)
          )
          interfacesArgumentNotifier.addAll(ports)
        }
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

    if (totalPorts > 0) {
      val serverGroups =
        memoryAllocatorGroups ++ schedulerInterfaceGroups ++ closureAllocatorGroups ++
          argumentNotifierGroups ++ remoteMemAccessGroups

      val numPortsPerMux = totalPorts.toDouble / numHBMPorts.toDouble
      val requestedPeMux =
        if (interfacesPE.nonEmpty)
          math.ceil(interfacesPE.length.toDouble / numPortsPerMux).toInt
        else
          0
      val peMux =
        math.min(
          numHBMPorts,
          math.max(0, requestedPeMux)
        ) match {
          case mux if serverGroups.nonEmpty && mux == numHBMPorts && numHBMPorts > 1 =>
            numHBMPorts - 1
          case mux => mux
        }
      val serverMux = numHBMPorts - peMux

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
          assignGroupsToHbmPorts(groups, portCursor, share)
          portCursor += share
        }
      } else {
        // More distinct shapes than available PE ports: cannot isolate them all.
        // Keep the original per-PE packing; the remaining mixed ports fall back to
        // PATH 3 (and warn) rather than silently corrupting ids.
        assignGroupsToHbmPorts(peInterfaceGroups.toSeq, 0, peMux)
      }
      // Shape-aware server allocation: group by wData (not (wData, wId))
      // since different wId values can be harmonized by zero-extension (PATH 2b).
      // This clusters same-data-width servers onto the same HBM ports.
      val serverByDataWidth: Seq[(Int, Seq[HbmInterfaceGroup])] =
        serverGroups.toSeq
          .groupBy(g => g.interfaces.head.cfg.wData)
          .toSeq
          .sortBy { case (dw, gs) => (-gs.map(_.size).sum, dw) }
      val totalServerIfaces = math.max(1, serverGroups.map(_.size).sum)

      if (serverByDataWidth.length <= serverMux && serverGroups.nonEmpty) {
        // Enough server ports to give every data-width class its own
        // contiguous, proportional block.
        var portCursor = peMux
        serverByDataWidth.zipWithIndex.foreach { case ((_, groups), idx) =>
          val classesLeft = serverByDataWidth.length - idx
          val portsLeft   = serverMux - (portCursor - peMux)
          val share =
            if (idx == serverByDataWidth.length - 1) portsLeft
            else math.max(
              1,
              math.min(
                portsLeft - (classesLeft - 1),
                math.round(serverMux.toDouble * groups.map(_.size).sum / totalServerIfaces).toInt
              )
            )
          assignGroupsToHbmPorts(groups, portCursor, share)
          portCursor += share
        }
      } else {
        assignGroupsToHbmPorts(serverGroups.toSeq, peMux, serverMux)
      }

      // ---- Port allocation summary ------------------------------------
      println(s"[HBM:Interconnect] Port budget: $peMux PE ports (0..${peMux - 1}), $serverMux server ports ($peMux..${peMux + serverMux - 1})")
      peByShape.foreach { case ((dw, id), gs) =>
        println(s"[HBM:Interconnect]   PE shape (wData=$dw, wId=$id): ${gs.map(_.size).sum} interfaces")
      }
      serverByDataWidth.foreach { case (dw, gs) =>
        val idWidths = gs.flatMap(_.interfaces.map(_.cfg.wId)).distinct.sorted
        println(s"[HBM:Interconnect]   Server wData=$dw: ${gs.map(_.size).sum} interfaces (wId=${idWidths.mkString(",")})")
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
    val hbmIdCap         = 6
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
      regOwners(peInterfaceGroups.toSeq)
      regOwners(schedulerInterfaceGroups.toSeq)
      regOwners(memoryAllocatorGroups.toSeq)
      regOwners(closureAllocatorGroups.toSeq)
      regOwners(argumentNotifierGroups.toSeq)
      regOwners(remoteMemAccessGroups.toSeq)

      // ---- STATUS PE# table -------------------------------------------------
      // The watcher's 48-bit STATUS word packs the monitored groups CONTIGUOUSLY in
      // monitored order: group g starts right after the previous group's PEs, so with
      // one PE each the layout is adder:0, memReader:1, initiator:2 (no gaps). This
      // MUST match the watcher HLS, which advances its pack offset by the running
      // sum of the per-group N_ defines (memAccess.cpp: base = N_g0, then N_g0+N_g1),
      // and the host StatusConservation, which numbers PEs 0..NPE-1 the same way.
      // Emitting exactly `peCount` entries per group (the real instantiated count)
      // makes this table an accurate map of the live STATUS slots and the true number
      // of monitored PEs present.
      // 48-bit STATUS word, 4 bits/PE -> at most 12 monitored PE slots. Must match
      // MAX_STATUS_PES in the watcher HLS (memAccess.cpp).
      val MaxStatusPes = 12
      val monitored: Seq[(String, String)] =
        fullSysGenDescriptor.watcherConfig.toSeq
          .flatMap(_.monitored.map(m => (m.taskName, m.statusPrefix)))
      val peBase = scala.collection.mutable.LinkedHashMap[String, Int]()
      val pesEntries = scala.collection.mutable.ArrayBuffer[String]()
      var peCursor = 0
      monitored.foreach { case (taskName, statusPrefix) =>
        val peCount = peMap.get(taskName).map(_.length).getOrElse(0)
        peBase(taskName) = peCursor
        for (i <- 0 until peCount)
          pesEntries += s"""    {"peNumber": ${peCursor + i}, "task": "$taskName", "statusPrefix": "$statusPrefix", "indexInTask": $i}"""
        peCursor += peCount
      }
      if (peCursor > MaxStatusPes)
        throw new RuntimeException(
          s"watcher monitors $peCursor PEs total (" +
            monitored
              .map { case (t, _) => s"$t=${peMap.get(t).map(_.length).getOrElse(0)}" }
              .mkString(", ") +
            s") but the 48-bit STATUS word holds at most $MaxStatusPes (4 bits/PE). " +
            "Reduce numProcessingElements on the monitored tasks (and the matching N_ " +
            "defines in memAccess.cpp), or widen the STATUS word in the watcher HLS."
        )
      val pesJson = pesEntries.mkString(",\n")

      // Map a port master's owner string to the STATUS PE# it belongs to, or None
      // for shared servers (scheduler/allocator/argumentNotifier) that are not a
      // single monitored PE. Per-PE kinds carry the PE instance index as the 3rd
      // colon field (before any '#interface' suffix).
      val perPeKinds = Set("pe", "spawnNextWB", "sendArgumentWB")
      def peNumberOf(owner: String): Option[Int] = {
        val parts = owner.split(":")
        if (parts.length >= 3 && perPeKinds.contains(parts(0))) {
          val task   = parts(1)
          val idxStr = parts(2).takeWhile(_ != '#')
          (peBase.get(task), scala.util.Try(idxStr.toInt).toOption) match {
            case (Some(base), Some(idx))
                if idx < peMap.get(task).map(_.length).getOrElse(0) =>
              Some(base + idx)
            case _ => None
          }
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

      hbmPortMappingJson =
        s"""{
  "design": "${fullSysGenDescriptor.name}",
  "numComputePorts": ${hbmSlaves.count(_.nonEmpty)},
  "note": "'pes' is the STATUS PE# table (watcher monitored order; the STATUS bundle numbers PEs the same way). Each port master carries 'role' (main = the m_axi_gmem compute port; argOut/argDataOut/spawnNext = argument/continuation write-buffer ports; ring/spawner = scheduler ports) and 'peNumber' = the STATUS PE# that owns it, or null for shared servers (scheduler/allocator/argumentNotifier). port index == watcher BW_READ/BW_WRITE tap index. owner = '<kind>:<task>:<index>[#<role>]'.",
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
        val uniformShape   = dataWidths.length == 1 && idWidths.length == 1
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

        } else if (interfaceCount > 1 && dataWidths.length == 1 && nativeMuxId <= hbmIdCap && vitisCanConvert) {
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
          val outputCfg = cfgAxi4HBM.copy(axi3Compat = axi3CompatFlag, wId = collapsedIdWidth)

          // Per-slave: yank user bits, collapse/convert to `sinkCfg`, widen if needed.
          def collapseConvert(slavePort: axi4.full.Interface, sinkCfg: axi4.Config): axi4.full.Interface = {
            val protocolConverter = Module(
              new axi4.full.components.ProtocolConverter(
                new axi4.full.components.ProtocolConverterConfig(
                  axiSlaveCfg  = slavePort.cfg.copy(wUserAR = 0, wUserR = 0, wUserAW = 0, wUserW = 0, wUserB = 0),
                  axiMasterCfg = sinkCfg
                )
              )
            )
            axi4.full.SlaveBuffer(AxiUserYanker(slavePort), axi4.BufferConfig.all(2)) :=> protocolConverter.s_axi
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
