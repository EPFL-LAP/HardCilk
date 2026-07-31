
package HardCilk

import chisel3._
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
  /**
   * Skip the in-design bus-group muxing and export every collected memory master
   * as its own `m_axi_XX`. The reduction down to the HBM's port count is then
   * done by multi-SI SmartConnects in the generated block designs, which handle
   * the mixed data widths a single mux cannot. See `--raw-hbm-ports`.
   */
  val rawHbmPorts: Boolean
  val addressTransformFlag: Boolean
  val cfgAxi4HBM: axi4.Config
  val cfgXDMA: axi4.Config
  val interfaceBuffer: ArrayBuffer[hdlinfo.Interface]
  val axiOuts: ArrayBuffer[axi4.RawInterface]
  val axiXDMA: ArrayBuffer[axi4.RawInterface]

  // These are "output" vars that this trait will update
  var numHbmPortExports: Int

  /**
   * Indices of the exported `m_axi_XX` masters that carry an `m_axi_gmem*` port
   * of a task marked `generateRAMA` in the descriptor, and therefore want a RAMA
   * IP in front of the HBM slave they end up on. The block-design generators
   * (TclQuestaSim / TclGeneratorMemPEs) read this back to place the IP.
   */
  var ramaPortIndices: Seq[Int]

  /**
   * One `<kind>/<task>` label per exported `m_axi_XX`, naming the unit whose
   * master it carries (`pe.gmem/pageRank`, `sched.vss/update`, ...). Only
   * meaningful in `rawHbmPorts` mode, where each exported port is exactly one
   * master; the block-design generators use it to deal masters of different
   * kinds onto the same SmartConnect. Empty otherwise.
   */
  var hbmPortLabels: Seq[String]

  /**
   * The part of an AXI config that a `ProtocolConverter` cannot adapt, i.e. the
   * bus flavour an interface has to share with everything muxed onto the same
   * HBM master. ID width, address width and AXI3 compatibility are converted,
   * and the user signals are removed by the `AxiUserYanker`, so all of them are
   * normalised away here.
   */
  private def busSignature(cfg: axi4.Config): axi4.Config =
    cfg.copy(
      wId = 0, wAddr = 0, axi3Compat = false,
      wUserAR = 0, wUserR = 0, wUserAW = 0, wUserW = 0, wUserB = 0
    )

  /**
   * This method is now part of the trait. It contains the exact logic
   * moved from CleanHardCilk.scala.
   */
  def buildAndConnectHBM(
      peMap: Map[String, Seq[VitisWriteBufferModule]],
      schedulerMap: Map[String, SchedulerLike],
      closureAllocatorMap: Map[String, Allocator],
      argumentNotifierMap: Map[String, ArgumentNotifier],
      memoryAllocatorMap: Map[String, Allocator],
      spawnNextWBMap: Map[String, Seq[WriteBuffer]],
      sendArgumentWBMap: Map[String, Seq[WriteBuffer]],
      remoteMemAccessMap: Map[String, RemoteStreamToMem]
  ): Unit = {

    // [This is the code block from CleanHardCilk.scala, line 316 to 512]

    val interfacesPE = new ArrayBuffer[axi4.full.Interface]()

    // `<kind>/<task>` label per collected master, appended in lock-step with the
    // interface buffers so index i of a label buffer describes index i of the
    // matching interface buffer. Only consumed in rawHbmPorts mode, but kept
    // filled unconditionally so the two never drift apart.
    val labelsPE = new ArrayBuffer[String]()

    // The `m_axi_gmem*` masters of a `generateRAMA` task -- only the PEs' own
    // memory ports, not the spawnNext/argOut closure writes, which are short
    // sequential bursts that a RAMA IP would not help. Compared by reference
    // below, so this stays correct even if two PEs expose identical configs.
    val interfacesRama = new ArrayBuffer[axi4.full.Interface]()

    peMap.foreach { case (taskName, peArray) =>
      val task = fullSysGenDescriptor.taskDescriptors.find(_.name == taskName).get
      peArray.foreach { pe =>
        // Legacy single spawnNext AXI-MM port.
        pe.io.elements.get("m_axi_spawnNext").foreach { p =>
          interfacesPE.addOne(p.asInstanceOf[axi4.RawInterface].asFull)
          labelsPE.addOne(s"pe.spawnNext/$taskName")
        }
        // New-style: one m_axi_spawnNext_<contName> port per named continuation.
        pe.io.elements
          .filter { case (name, _) => name.startsWith("m_axi_spawnNext_") }
          .foreach { case (_, p) =>
            interfacesPE.addOne(p.asInstanceOf[axi4.RawInterface].asFull)
            labelsPE.addOne(s"pe.spawnNext/$taskName")
          }
        pe.io.elements.get("m_axi_argOut").foreach { p =>
          interfacesPE.addOne(p.asInstanceOf[axi4.RawInterface].asFull)
          labelsPE.addOne(s"pe.argOut/$taskName")
        }
        // New-style: one m_axi_argOut_<target> port per named argument continuation.
        pe.io.elements
          .filter { case (name, _) => name.startsWith("m_axi_argOut_") }
          .foreach { case (_, p) =>
            interfacesPE.addOne(p.asInstanceOf[axi4.RawInterface].asFull)
            labelsPE.addOne(s"pe.argOut/$taskName")
          }
        if (task.hasAXI) {
          // A normal PE exposes exactly one memory master named `m_axi_gmem`; an
          // OVERLAP wrapper exposes one per collapsed sub-PE (`m_axi_gmem_<sub>`).
          // Add them all to the pool — the reducer below buckets them by width and
          // muxes each bucket, so the wrapper's (width-homogeneous) masters are
          // handled like any other PE ports.
          pe.io.elements.keys
            .filter(_.startsWith("m_axi_gmem"))
            .toSeq
            .sorted
            .foreach { n =>
              val iface = pe.getPort(n).asInstanceOf[axi4.RawInterface].asFull
              interfacesPE.addOne(iface)
              labelsPE.addOne(s"pe.gmem/$taskName")
              if (task.generateRAMA) interfacesRama.addOne(iface)
            }
        }
      }
    }

    spawnNextWBMap.foreach {
      case (taskName, wbArray) =>
        wbArray.foreach { wb =>
          interfacesPE.addOne(wb.m_axi.asInstanceOf[axi4.RawInterface].asFull)
          labelsPE.addOne(s"wb.spawnNext/$taskName")
        }
    }

    sendArgumentWBMap.foreach {
      case (taskName, wbArray) =>
        wbArray.foreach { wb =>
          interfacesPE.addOne(wb.m_axi.asInstanceOf[axi4.RawInterface].asFull)
          labelsPE.addOne(s"wb.argOut/$taskName")
        }
    }

    // Scheduler servers and spawner servers both reach memory here. A DataFlowScheduler
    // contributes no scheduler servers and at most one spawner. Iterated by key
    // rather than over `.values` so the task name survives into the label.
    val interfacesScheduler = new ArrayBuffer[axi4.full.Interface]()
    val labelsScheduler = new ArrayBuffer[String]()
    schedulerMap.foreach { case (taskName, s) =>
      s.vssAxiFull.foreach { i =>
        interfacesScheduler.addOne(i); labelsScheduler.addOne(s"sched.vss/$taskName")
      }
      s.spawnerAxiFull.foreach { i =>
        interfacesScheduler.addOne(i); labelsScheduler.addOne(s"sched.spawner/$taskName")
      }
    }

    /** Flatten a per-task map of interfaces, keeping a `<kind>/<task>` label per entry. */
    def collectLabelled[T](
        m: Map[String, T],
        kind: String
    )(ifacesOf: T => Iterable[axi4.full.Interface]): (ArrayBuffer[axi4.full.Interface], ArrayBuffer[String]) = {
      val ifaces = new ArrayBuffer[axi4.full.Interface]()
      val labels = new ArrayBuffer[String]()
      m.foreach { case (taskName, unit) =>
        ifacesOf(unit).foreach { i => ifaces.addOne(i); labels.addOne(s"$kind/$taskName") }
      }
      (ifaces, labels)
    }

    val (interfacesClosureAllocator, labelsClosureAllocator) =
      collectLabelled(closureAllocatorMap, "closureAlloc")(_.io_internal.vcas_axi_full)
    val (interfacesArgumentNotifier, labelsArgumentNotifier) =
      collectLabelled(argumentNotifierMap, "argNotifier")(_.axi_full_argRoute)
    val (interfacesMemoryAllocator, labelsMemoryAllocator) =
      collectLabelled(memoryAllocatorMap, "memAlloc")(_.io_internal.vcas_axi_full)
    val (interfacesRemoteMemAccess, labelsRemoteMemAccess) =
      collectLabelled(remoteMemAccessMap, "remoteMem")(v => Seq(v.io.m_axi_mem))

    val numHBMPorts = reduceAxi
    val hbmSlaves =
      scala.collection.mutable.Map[Int, ArrayBuffer[axi4.full.Interface]]()
    // Bucket indices (keys of `hbmSlaves`) that hold a single master of a
    // `generateRAMA` task; translated to exported m_axi_XX indices further down.
    var ramaBuckets: Set[Int] = Set.empty
    for (i <- 0 until numHBMPorts) {
      hbmSlaves += (i -> new ArrayBuffer[axi4.full.Interface]())
    }

    val totalPorts =
      interfacesPE.length + interfacesMemoryAllocator.length + interfacesScheduler.length + interfacesClosureAllocator.length + interfacesArgumentNotifier.length + interfacesRemoteMemAccess.length

    // log the number of total ports
    println(s"[HBM:Interconnect:92] Total ports: $totalPorts")

    // log the interfaces from each module
    println(s"[HBM:Interconnect:95] PE interfaces: ${interfacesPE.length}")

    // Log the widths of the PE interfaces
    val peWidths = interfacesPE.map(_.cfg.wData).distinct.sorted
    println(s"[HBM:Interconnect:95.1] PE interface widths: ${peWidths.mkString(", ")}")

    println(s"[HBM:Interconnect:96] Scheduler interfaces: ${interfacesScheduler.length}")

    // Log the widths of the Scheduler interfaces
    val schedulerWidths = interfacesScheduler.map(_.cfg.wData).distinct.sorted
    println(s"[HBM:Interconnect:96.1] Scheduler interface widths: ${schedulerWidths.mkString(", ")}")

    // Log the widths of the Closure Allocator interfaces
    val closureAllocatorWidths = interfacesClosureAllocator.map(_.cfg.wData).distinct.sorted
    println(s"[HBM:Interconnect:97.1] Closure Allocator interface widths: ${closureAllocatorWidths.mkString(", ")}")

    // Log the widths of the Argument Notifier interfaces
    val argumentNotifierWidths = interfacesArgumentNotifier.map(_.cfg.wData).distinct.sorted
    println(s"[HBM:Interconnect:98.1] Argument Notifier interface widths: ${argumentNotifierWidths.mkString(", ")}")

    // Log the widths of the Memory Allocator interfaces
    val memoryAllocatorWidths = interfacesMemoryAllocator.map(_.cfg.wData).distinct.sorted
    println(s"[HBM:Interconnect:99.1] Memory Allocator interface widths: ${memoryAllocatorWidths.mkString(", ")}")


    // The full master pool, and the matching `<kind>/<task>` label per entry.
    // Concatenated in the same order so index i of one describes index i of the
    // other; `allLabels` is only read back in rawHbmPorts mode, where a bucket
    // is exactly one master.
    val allInterfaces =
      interfacesPE ++ interfacesMemoryAllocator ++ interfacesScheduler ++
        interfacesClosureAllocator ++ interfacesArgumentNotifier ++ interfacesRemoteMemAccess
    val allLabels =
      labelsPE ++ labelsMemoryAllocator ++ labelsScheduler ++
        labelsClosureAllocator ++ labelsArgumentNotifier ++ labelsRemoteMemAccess
    require(
      allLabels.length == allInterfaces.length,
      s"[HBM:Interconnect] internal error: ${allInterfaces.length} master(s) but " +
        s"${allLabels.length} label(s); a collection site added an interface without its label."
    )

    // Bucket index -> label of the single master it holds. Populated in
    // rawHbmPorts mode only; the grouped path muxes several kinds per bucket.
    var bucketLabels: Map[Int, String] = Map.empty

    if (totalPorts > 0 && rawHbmPorts) {
      // --- Raw export: one m_axi_XX per master, no muxing ---
      // Every bucket holds a single interface, so the export loop below takes its
      // single-interface branch and each master reaches the top level with its own
      // native bus. The reduction down to the HBM's 32 slave ports is done by the
      // multi-SI SmartConnects the block-design generators emit, which convert
      // mixed data widths and AXI4 -> AXI3 on the way -- so the bus-group
      // constraint the muxed path lives under does not apply here, and
      // `maximumAXIPorts` is not a limit on the exported count.
      hbmSlaves.clear()
      allInterfaces.zipWithIndex.foreach { case (iface, i) =>
        hbmSlaves += (i -> ArrayBuffer(iface))
      }
      bucketLabels = allLabels.zipWithIndex.map { case (l, i) => i -> l }.toMap
      // A RAMA IP reorders everything on its port, so it only pays off on a
      // dedicated one -- which raw mode cannot promise, since the TCL shares each
      // HBM port between several masters.
      ramaBuckets = Set.empty
      if (interfacesRama.nonEmpty) {
        println(
          s"[HBM:Interconnect] WARNING: ${interfacesRama.length} master(s) of a generateRAMA task " +
            s"are exported raw and will share an HBM port with unrelated traffic; no dedicated " +
            s"RAMA port is reserved for them."
        )
      }
      println(
        s"[HBM:Interconnect] Raw export: ${allInterfaces.length} memory master(s) exported " +
          s"un-muxed (${allLabels.distinct.length} distinct unit kind(s))" +
          (if (fullSysGenDescriptor.hasAXIDMAInput) ", plus the s_axi_xdma port" else "") +
          s"; the reduction to at most $numHBMPorts HBM port(s) is deferred to the generated TCL."
      )
    } else if (totalPorts > 0) {
      // --- Reduce ports WITHOUT using Widen ---
      // Only interfaces that can be carried by the same AXI4 bus are muxed
      // together, so every resulting HBM master has a single, uniform flavour
      // and no Widen module is ever required. Width and AXI4 -> AXI3 conversion
      // down to the 256-bit HBM slaves happens outside the design (v++ in the
      // Vitis flow, the per-port SmartConnect in the block designs generated by
      // TclGeneratorMemPEs / TclQuestaSim).
      //
      // The grouping key is the interface config stripped of everything the
      // ProtocolConverter in front of each mux can adapt (ID width, address
      // width, AXI3 compatibility) and of the user signals (removed by the
      // AxiUserYanker below). Everything else -- data width, read/write
      // direction and the optional AxLOCK/CACHE/PROT/QOS/REGION signals, which
      // HLS omits on short-burst ports (HLSHelpers) -- must match across a
      // bucket, otherwise the converter cannot connect the slave to the mux.
      // The requested port count (numHBMPorts) is therefore a target: we can
      // never go below the number of distinct groups.
      val maxPorts = fullSysGenDescriptor.maximumAXIPorts

      // --- Priority pass: dedicated ports for the RAMA masters ---
      // A RAMA IP reorders the accesses of everything that reaches the HBM port
      // it sits on, so it only pays off when the port carries the traffic of the
      // task that asked for it and nothing else. The `m_axi_gmem*` masters of a
      // `generateRAMA` task are therefore taken out of the muxing pool first and
      // each given a port of their own.
      def isRama(iface: axi4.full.Interface): Boolean = interfacesRama.exists(_ eq iface)
      val ramaCandidates = allInterfaces.filter(isRama)
      val nonRama = allInterfaces.filterNot(isRama)

      // Dedicating a port is a best-effort promise: every bus group left in the
      // shared pool still needs at least one mux, and the total cannot exceed
      // the device's port count. Demote the tail of the RAMA list back into the
      // shared pool until the budget adds up.
      def numBusGroups(ifaces: ArrayBuffer[axi4.full.Interface]): Int =
        ifaces.map(i => busSignature(i.cfg)).distinct.length
      var numDedicated = ramaCandidates.length
      while (
        numDedicated > 0 &&
        numDedicated + numBusGroups(nonRama ++ ramaCandidates.drop(numDedicated)) > maxPorts
      ) {
        numDedicated -= 1
      }
      if (numDedicated < ramaCandidates.length) {
        println(
          s"[HBM:Interconnect] WARNING: ${ramaCandidates.length} master(s) requested a dedicated " +
            s"RAMA port but only $numDedicated fit within the $maxPorts-port limit; the remaining " +
            s"${ramaCandidates.length - numDedicated} are muxed with the rest of the design and " +
            s"get no RAMA IP."
        )
      }
      val dedicated = ramaCandidates.take(numDedicated)
      val shared    = nonRama ++ ramaCandidates.drop(numDedicated)

      val byGroup = shared.groupBy(iface => busSignature(iface.cfg))
      val groups = byGroup.keys.toSeq.sortBy(c =>
        (c.wData, c.read, c.write, c.hasLock, c.hasCache, c.hasProt, c.hasQos, c.hasRegion)
      )
      val numGroups = groups.length

      // Clamp the requested reduction over what is left after the dedicated
      // ports: at least one mux per group, and never more muxes than there are
      // interfaces in the shared pool.
      val targetPorts =
        math.max(numGroups, math.min(numHBMPorts - numDedicated, shared.length))

      // Start with one mux per group, then hand out the remaining muxes to the
      // currently most-loaded group (highest interfaces-per-mux ratio), never
      // exceeding a group's interface count.
      val muxByGroup = scala.collection.mutable.LinkedHashMap(groups.map(g => g -> 1): _*)
      var remaining = targetPorts - numGroups
      while (remaining > 0 && groups.exists(g => muxByGroup(g) < byGroup(g).length)) {
        val g = groups
          .filter(x => muxByGroup(x) < byGroup(x).length)
          .maxBy(x => byGroup(x).length.toDouble / muxByGroup(x))
        muxByGroup(g) += 1
        remaining -= 1
      }

      val achievedPorts = numDedicated + muxByGroup.values.sum

      // Rebuild the slave buckets: the dedicated RAMA masters take the lowest
      // bucket indices (one interface each), then each remaining bucket holds
      // interfaces of exactly one bus group, spread round-robin over that
      // group's muxes.
      hbmSlaves.clear()
      var idx = 0
      dedicated.foreach { iface =>
        hbmSlaves += (idx -> ArrayBuffer(iface))
        idx += 1
      }
      ramaBuckets = (0 until numDedicated).toSet
      for (g <- groups) {
        val ifaces = byGroup(g)
        val nMux   = muxByGroup(g)
        for (b <- 0 until nMux) {
          hbmSlaves += ((idx + b) -> new ArrayBuffer[axi4.full.Interface]())
        }
        ifaces.zipWithIndex.foreach { case (iface, k) =>
          hbmSlaves(idx + (k % nMux)).addOne(iface)
        }
        idx += nMux
      }

      val groupNames = groups.map(g =>
        s"${g.wData}b${if (g.read && g.write) "" else if (g.read) "/ro" else "/wo"}" +
          s"${if (g.hasQos) "" else "/noSideband"}"
      )
      println(
        s"[HBM:Interconnect] Widen-free reduction: requested=$numHBMPorts, " +
          s"reduced to $achievedPorts HBM port(s) = $numDedicated dedicated RAMA port(s) + " +
          s"${muxByGroup.values.sum} shared port(s) across $numGroups bus group(s) " +
          s"[${groupNames.mkString(", ")}] (limit $maxPorts)"
      )
      require(
        achievedPorts <= maxPorts,
        s"[HBM:Interconnect] FAILED to reduce HBM ports: $achievedPorts required (> $maxPorts). " +
          s"Bus groups [${groupNames.mkString(", ")}] cannot be muxed further without using Widen."
      )
    }


    if (fullSysGenDescriptor.hasAXIDMAInput){//!isSimulation) {
      val xdma_axi = IO(axi4.Slave(cfgXDMA)).suggestName("s_axi_xdma")
      // The XDMA slave has its own bus width, so it gets a dedicated bucket to
      // preserve the same-width-per-mux invariant.
      val xdmaBucket = hbmSlaves.keys.reduceOption(_ max _).map(_ + 1).getOrElse(0)
      hbmSlaves.getOrElseUpdate(xdmaBucket, new ArrayBuffer[axi4.full.Interface]()).addOne(
        axi4.full.SlaveBuffer(xdma_axi.asFull, axi4.BufferConfig.all(8))
      )
      // It is muxed like any other slave, so it also becomes an exported m_axi_XX
      // and needs a label of its own in raw mode.
      bucketLabels += (xdmaBucket -> "xdma/host")
      interfaceBuffer.addOne(
        hdlinfo.Interface(
          "s_axi_xdma", hdlinfo.InterfaceRole.slave, hdlinfo.InterfaceKind("axi4"),
          "clock", "reset", Map("config" -> hdlinfo.TypedObject(cfgXDMA))
        )
      )
      axiXDMA.addOne(xdma_axi)
    }

    numHbmPortExports = hbmSlaves.count(_._2.length > 0)
    // `hbmSlaves` is a mutable HashMap, so sort by bucket index to keep the
    // m_axi_XX numbering (and the hbm_port_widths.txt ordering) deterministic.
    // Empty buckets are dropped, so the exported index is not the bucket index:
    // translate the RAMA buckets through the same ordering.
    ramaPortIndices = hbmSlaves
      .filter(_._2.length > 0)
      .toSeq
      .sortBy(_._1)
      .zipWithIndex
      .collect { case ((bucket, _), i) if ramaBuckets.contains(bucket) => i }
    // Same translation for the per-master labels: bucket index -> exported index.
    // The XDMA bucket added above has no label and falls out here, which is
    // correct -- s_axi_xdma is a slave, not one of the exported m_axi_XX.
    hbmPortLabels =
      if (!rawHbmPorts) Seq.empty
      else
        hbmSlaves
          .filter(_._2.length > 0)
          .toSeq
          .sortBy(_._1)
          .map { case (bucket, _) => bucketLabels.getOrElse(bucket, "unknown") }
    hbmSlaves.filter(_._2.length > 0).toSeq.sortBy(_._1).zipWithIndex.map {
      case (hbmSlaves_i, i) => {
        val interfaceCount = hbmSlaves_i._2.length
        val hbmSlave = hbmSlaves_i._2

        // Every bucket holds interfaces of one bus group, so the exported master
        // is that group's bus: native data width (no Widen) and plain AXI4 (the
        // AXI4 -> AXI3 conversion the 256-bit HBM slaves need happens outside
        // the design). Only the ID and address widths are normalised.
        val exportCfg =
          busSignature(hbmSlave.head.cfg).copy(wId = 2, wAddr = cfgAxi4HBM.wAddr)

        if (interfaceCount > 1) {
          val mux = Module(
            new axi4.full.components.Mux(
              new axi4.full.components.MuxConfig(
                axiSlaveCfg = exportCfg,
                numSlaves = hbmSlave.length
              )
            )
          )

          mux.s_axi.zip(hbmSlave).foreach { case (muxPort, slavePort) =>
            val protocolConverter = Module(
              new axi4.full.components.ProtocolConverter(
                new axi4.full.components.ProtocolConverterConfig(
                  axiSlaveCfg = slavePort.cfg.copy(wUserAR = 0, wUserR = 0, wUserAW = 0, wUserW = 0, wUserB = 0),
                  axiMasterCfg = muxPort.cfg
                )
              )
            )
            axi4.full.SlaveBuffer(AxiUserYanker(slavePort), axi4.BufferConfig.all(8)) :=> protocolConverter.s_axi

            // if the slave cfg has data width smaller than the axi master config instantiate a Widen
            if(slavePort.cfg.wData < muxPort.cfg.wData){
              val widen_mod = Module(new chext.amba.axi4.full.components.Widen(chext.amba.axi4.full.components.WidenConfig(muxPort.cfg)))
              protocolConverter.m_axi :=> widen_mod.s_axi
              widen_mod.m_axi :=> muxPort
            } else{
              protocolConverter.m_axi :=> muxPort
            }
          }

          val axiOut = IO(axi4.Master(mux.m_axi.cfg)).suggestName(f"m_axi_${i}%02d")

          if (addressTransformFlag) {
            val addressTransform = Module(new Util.AddressTransform(
              AddressTransformConfig(
                axiCfg = axiOut.cfg,
                transform = Seq(33, 23, 22, 21, 20, 28, 27, 26, 25, 24, 32, 31, 30, 29, 19, 18, 17, 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0).reverse
              )
            ))
            mux.m_axi :=> addressTransform.s_axi
            addressTransform.m_axi :=> axiOut.asFull
          } else {
            mux.m_axi :=> axiOut.asFull
          }

          interfaceBuffer.addOne(
            hdlinfo.Interface(
              f"m_axi_${i}%02d", hdlinfo.InterfaceRole.master, hdlinfo.InterfaceKind("axi4"),
              "clock", "reset", Map("config" -> hdlinfo.TypedObject(axiOut.cfg))
            )
          )
          axiOuts.addOne(axiOut)
        } else {
          val outputCfg = exportCfg
          val axiOut =
            IO(axi4.Master(outputCfg)).suggestName(f"m_axi_${i}%02d")
          val protocolConverter = Module(
            new axi4.full.components.ProtocolConverter(
              new axi4.full.components.ProtocolConverterConfig(
                axiSlaveCfg = hbmSlave.head.cfg.copy(wUserAR = 0, wUserR = 0, wUserAW = 0, wUserW = 0, wUserB = 0),
                axiMasterCfg = outputCfg
              )
            )
          )
          axi4.full.SlaveBuffer(AxiUserYanker(hbmSlave.head), axi4.BufferConfig.all(2)) :=> protocolConverter.s_axi

          if (addressTransformFlag) {
             val addressTransform = Module(new Util.AddressTransform(
              AddressTransformConfig(
                axiCfg = axiOut.cfg,
                transform = Seq(33, 23, 22, 21, 20, 28, 27, 26, 25, 24, 32, 31, 30, 29, 19, 18, 17, 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0).reverse
              )
            ))
            // #TODO add the widen here as well.
            protocolConverter.m_axi :=> addressTransform.s_axi
            addressTransform.m_axi :=> axiOut.asFull
          } else {
            // Add the Widen for V80
            if(protocolConverter.s_axi.cfg.wData < axiOut.cfg.wData){
              val widen_mod = Module(new chext.amba.axi4.full.components.Widen(chext.amba.axi4.full.components.WidenConfig(axiOut.cfg)))
              protocolConverter.m_axi :=> widen_mod.s_axi
              widen_mod.m_axi :=> axiOut.asFull
            } else{
              protocolConverter.m_axi :=> axiOut.asFull
            }

          }

          interfaceBuffer.addOne(
            hdlinfo.Interface(
              f"m_axi_${i}%02d", hdlinfo.InterfaceRole.master, hdlinfo.InterfaceKind("axi4"),
              "clock", "reset", Map("config" -> hdlinfo.TypedObject(axiOut.cfg))
            )
          )
          axiOuts.addOne(axiOut)
        }
      }
    }
  }
}