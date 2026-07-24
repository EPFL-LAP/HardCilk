
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
      if (fullSysGenDescriptor.isVitisProject) {
        // --- Vitis flow: reduce ports WITHOUT using Widen ---
        // Only interfaces of the same data width are muxed together, so every
        // resulting HBM master carries a single, uniform bus width and no Widen
        // module is ever required. The requested port count (numHBMPorts) is a
        // target: we can never go below the number of distinct widths, since
        // each width needs at least one dedicated mux.
        val allInterfaces =
          interfacesPE ++ interfacesMemoryAllocator ++ interfacesScheduler ++
            interfacesClosureAllocator ++ interfacesArgumentNotifier ++ interfacesRemoteMemAccess

        val byWidth  = allInterfaces.groupBy(_.cfg.wData)
        val widths   = byWidth.keys.toSeq.sorted
        val numWidthGroups = widths.length

        // Clamp the requested reduction: at least one mux per width group, and
        // never more muxes than there are interfaces (== totalPorts).
        val targetPorts = math.max(numWidthGroups, math.min(numHBMPorts, totalPorts))

        // Start with one mux per width group, then hand out the remaining muxes
        // to the currently most-loaded group (highest interfaces-per-mux ratio),
        // never exceeding a group's interface count.
        val muxByWidth = scala.collection.mutable.LinkedHashMap(widths.map(w => w -> 1): _*)
        var remaining = targetPorts - numWidthGroups
        while (remaining > 0 && widths.exists(w => muxByWidth(w) < byWidth(w).length)) {
          val w = widths
            .filter(x => muxByWidth(x) < byWidth(x).length)
            .maxBy(x => byWidth(x).length.toDouble / muxByWidth(x))
          muxByWidth(w) += 1
          remaining -= 1
        }

        val achievedPorts = muxByWidth.values.sum

        // Rebuild the slave buckets so that each bucket holds only same-width
        // interfaces, spread round-robin across that group's muxes.
        hbmSlaves.clear()
        var idx = 0
        for (w <- widths) {
          val ifaces = byWidth(w)
          val nMux   = muxByWidth(w)
          for (b <- 0 until nMux) {
            hbmSlaves += ((idx + b) -> new ArrayBuffer[axi4.full.Interface]())
          }
          ifaces.zipWithIndex.foreach { case (iface, k) =>
            hbmSlaves(idx + (k % nMux)).addOne(iface)
          }
          idx += nMux
        }

        val maxPorts = fullSysGenDescriptor.maximumAXIPorts
        println(
          s"[HBM:Interconnect] Vitis Widen-free reduction: requested=$numHBMPorts, " +
            s"reduced to $achievedPorts HBM port(s) across $numWidthGroups width group(s) " +
            s"[${widths.mkString(", ")}] (limit $maxPorts)"
        )
        require(
          achievedPorts <= maxPorts,
          s"[HBM:Interconnect] FAILED to reduce HBM ports: $achievedPorts required (> $maxPorts). " +
            s"Widths [${widths.mkString(", ")}] cannot be muxed further without using Widen."
        )
      } else {
        val numPortsPerMux = totalPorts.toDouble / numHBMPorts.toDouble
        val peMux = math.max(1, math.ceil(1.0 * interfacesPE.length / numPortsPerMux).toInt)
        val serverMux = math.max(0, numHBMPorts - peMux)

        val pePortsPerMux = if (peMux > 0 && interfacesPE.length > 0) 1.0 * interfacesPE.length / peMux else 1.0

        interfacesPE.zipWithIndex
          .groupBy(x => (x._2.toDouble / pePortsPerMux).toInt)
          .foreach(x => {
            if (hbmSlaves.contains(x._1)) hbmSlaves(x._1).addAll(x._2.map(_._1))
          })

        val serverInterfaces = interfacesMemoryAllocator ++ interfacesScheduler ++ interfacesClosureAllocator ++ interfacesArgumentNotifier ++ interfacesRemoteMemAccess

        val serverPortsPerMuxClamped = if (serverInterfaces.length > 0 && serverMux > 0) (1.0 * serverInterfaces.length / serverMux) else 1.0

        serverInterfaces.zipWithIndex
          .groupBy(x => peMux + (x._2.toDouble / serverPortsPerMuxClamped).toInt)
          .foreach(x => {
             if (hbmSlaves.contains(x._1)) hbmSlaves(x._1).addAll(x._2.map(_._1))
          })
      }
    }


    if (fullSysGenDescriptor.hasAXIDMAInput){//!isSimulation) {
      val xdma_axi = IO(axi4.Slave(cfgXDMA)).suggestName("s_axi_xdma")
      // In the Vitis (Widen-free) flow the XDMA slave has its own bus width, so
      // it gets a dedicated bucket to preserve the same-width-per-mux invariant.
      // Otherwise keep the legacy behaviour of sharing the last requested port.
      val xdmaBucket =
        if (fullSysGenDescriptor.isVitisProject) hbmSlaves.keys.reduceOption(_ max _).map(_ + 1).getOrElse(0)
        else numHBMPorts - 1
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

    var axi3CompatFlag = true
    if(fullSysGenDescriptor.isVitisProject){
      // Vitis flow is AXI4
      axi3CompatFlag = false
    }

    numHbmPortExports = hbmSlaves.filter(_._2.length > 0).size
    hbmSlaves.filter(_._2.length > 0).zipWithIndex.map {
      case (hbmSlaves_i, i) => {
        val interfaceCount = hbmSlaves_i._2.length
        val hbmSlave = hbmSlaves_i._2

        // In the Vitis flow every bucket is width-homogeneous, so the mux/output
        // bus is sized to the bucket's native width and no Widen is needed.
        // Elsewhere keep the fixed 256-bit HBM bus (Widen handles narrow slaves).
        val busWidth =
          if (fullSysGenDescriptor.isVitisProject) hbmSlave.head.cfg.wData else cfgAxi4HBM.wData

        if (
          interfaceCount == 1 && hbmSlave.head.cfg.axi3Compat && hbmSlave.head.cfg.wData == 256
        ) {
          val axiOut =
            IO(axi4.Master(hbmSlave.head.cfg)).suggestName(f"m_axi_${i}%02d")
          hbmSlave.head :=> axiOut.asFull
          interfaceBuffer.addOne(
            hdlinfo.Interface(
              f"m_axi_${i}%02d", hdlinfo.InterfaceRole.master, hdlinfo.InterfaceKind("axi4"),
              "clock", "reset", Map("config" -> hdlinfo.TypedObject(axiOut.cfg))
            )
          )
          axiOuts.addOne(axiOut)
        } else if (interfaceCount > 1) {
          val mux = Module(
            new axi4.full.components.Mux(
              new axi4.full.components.MuxConfig(
                axiSlaveCfg = cfgAxi4HBM.copy(axi3Compat = axi3CompatFlag, wId = 2, wData = busWidth),
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
          val outputCfg = cfgAxi4HBM.copy(axi3Compat = axi3CompatFlag, wId = 2, wData = busWidth)
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