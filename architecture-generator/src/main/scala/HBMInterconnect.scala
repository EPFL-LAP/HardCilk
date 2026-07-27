
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
  val addressTransformFlag: Boolean
  val cfgAxi4HBM: axi4.Config
  val cfgXDMA: axi4.Config
  val interfaceBuffer: ArrayBuffer[hdlinfo.Interface]
  val axiOuts: ArrayBuffer[axi4.RawInterface]
  val axiXDMA: ArrayBuffer[axi4.RawInterface]

  // This is an "output" var that this trait will update
  var numHbmPortExports: Int

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
      schedulerMap: Map[String, Scheduler],
      closureAllocatorMap: Map[String, Allocator],
      argumentNotifierMap: Map[String, ArgumentNotifier],
      memoryAllocatorMap: Map[String, Allocator],
      spawnNextWBMap: Map[String, Seq[WriteBuffer]],
      sendArgumentWBMap: Map[String, Seq[WriteBuffer]],
      remoteMemAccessMap: Map[String, RemoteStreamToMem]
  ): Unit = {

    // [This is the code block from CleanHardCilk.scala, line 316 to 512]

    val interfacesPE = new ArrayBuffer[axi4.full.Interface]()


    peMap.foreach { case (taskName, peArray) =>
      val task = fullSysGenDescriptor.taskDescriptors.find(_.name == taskName).get
      peArray.foreach { pe =>
        // Legacy single spawnNext AXI-MM port.
        pe.io.elements.get("m_axi_spawnNext").foreach(p => interfacesPE.addOne(p.asInstanceOf[axi4.RawInterface].asFull))
        // New-style: one m_axi_spawnNext_<contName> port per named continuation.
        pe.io.elements
          .filter { case (name, _) => name.startsWith("m_axi_spawnNext_") }
          .foreach { case (_, p) => interfacesPE.addOne(p.asInstanceOf[axi4.RawInterface].asFull) }
        pe.io.elements.get("m_axi_argOut").foreach(p => interfacesPE.addOne(p.asInstanceOf[axi4.RawInterface].asFull))
        // New-style: one m_axi_argOut_<target> port per named argument continuation.
        pe.io.elements
          .filter { case (name, _) => name.startsWith("m_axi_argOut_") }
          .foreach { case (_, p) => interfacesPE.addOne(p.asInstanceOf[axi4.RawInterface].asFull) }
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
            .foreach(n =>
              interfacesPE.addOne(pe.getPort(n).asInstanceOf[axi4.RawInterface].asFull))
        }
      }
    }

    spawnNextWBMap.foreach {
      case (taskName, wbArray) =>
        wbArray.foreach { wb =>
          interfacesPE.addOne(wb.m_axi.asInstanceOf[axi4.RawInterface].asFull)
        }
    }

    sendArgumentWBMap.foreach {
      case (taskName, wbArray) =>
        wbArray.foreach { wb =>
          interfacesPE.addOne(wb.m_axi.asInstanceOf[axi4.RawInterface].asFull)
        }
    }

    val interfacesScheduler = schedulerMap.values.flatMap(_.io_internal.vss_axi_full).to(ArrayBuffer)
    schedulerMap.values.foreach { s =>
      if (s.spawnerServerAXI.isDefined) {
        interfacesScheduler.addAll(s.spawnerServerAXI.get)
      }
    }

    val interfacesClosureAllocator = closureAllocatorMap.values.flatMap(_.io_internal.vcas_axi_full).to(ArrayBuffer)
    val interfacesArgumentNotifier = argumentNotifierMap.values.flatMap(_.axi_full_argRoute).to(ArrayBuffer)
    val interfacesMemoryAllocator = memoryAllocatorMap.values.flatMap(_.io_internal.vcas_axi_full).to(ArrayBuffer)

    val interfacesRemoteMemAccess = remoteMemAccessMap.values.flatMap(v => Seq(v.io.m_axi_mem)).to(ArrayBuffer)

    val numHBMPorts = reduceAxi
    val hbmSlaves =
      scala.collection.mutable.Map[Int, ArrayBuffer[axi4.full.Interface]]()
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


    if (totalPorts > 0) {
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
      val allInterfaces =
        interfacesPE ++ interfacesMemoryAllocator ++ interfacesScheduler ++
          interfacesClosureAllocator ++ interfacesArgumentNotifier ++ interfacesRemoteMemAccess

      val byGroup = allInterfaces.groupBy(iface => busSignature(iface.cfg))
      val groups = byGroup.keys.toSeq.sortBy(c =>
        (c.wData, c.read, c.write, c.hasLock, c.hasCache, c.hasProt, c.hasQos, c.hasRegion)
      )
      val numGroups = groups.length

      // Clamp the requested reduction: at least one mux per group, and never
      // more muxes than there are interfaces (== totalPorts).
      val targetPorts = math.max(numGroups, math.min(numHBMPorts, totalPorts))

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

      val achievedPorts = muxByGroup.values.sum

      // Rebuild the slave buckets so that each bucket holds only interfaces of
      // one group, spread round-robin across that group's muxes.
      hbmSlaves.clear()
      var idx = 0
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

      val maxPorts = fullSysGenDescriptor.maximumAXIPorts
      val groupNames = groups.map(g =>
        s"${g.wData}b${if (g.read && g.write) "" else if (g.read) "/ro" else "/wo"}" +
          s"${if (g.hasQos) "" else "/noSideband"}"
      )
      println(
        s"[HBM:Interconnect] Widen-free reduction: requested=$numHBMPorts, " +
          s"reduced to $achievedPorts HBM port(s) across $numGroups bus group(s) " +
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