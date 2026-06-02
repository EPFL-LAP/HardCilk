
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

    case class HbmInterfaceGroup(name: String, interfaces: Seq[axi4.full.Interface]) {
      def size: Int = interfaces.length
    }

    def peOwnedPorts(pe: VitisWriteBufferModule, task: TaskDescriptor): Seq[axi4.full.Interface] = {
      val ports = new ArrayBuffer[axi4.full.Interface]()
      pe.io.elements
        .get("m_axi_spawnNext")
        .foreach(p => ports.addOne(p.asInstanceOf[axi4.RawInterface].asFull))
      pe.io.elements
        .get("m_axi_argOut")
        .foreach(p => ports.addOne(p.asInstanceOf[axi4.RawInterface].asFull))
      if (task.hasAXI) {
        ports.addOne(pe.getPort("m_axi_gmem").asInstanceOf[axi4.RawInterface].asFull)
      }
      ports.toSeq
    }

    val peInterfaceGroups = new ArrayBuffer[HbmInterfaceGroup]()

    fullSysGenDescriptor.taskDescriptors.foreach { task =>
      peMap.get(task.name).foreach { peArray =>
        peArray.zipWithIndex.foreach { case (pe, peIndex) =>
          val ports = peOwnedPorts(pe, task)
          if (ports.nonEmpty) {
            peInterfaceGroups.addOne(HbmInterfaceGroup(s"pe:${task.name}:$peIndex", ports))
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
              Seq(wb.m_axi.asInstanceOf[axi4.RawInterface].asFull)
            )
          )
        }
      }
      sendArgumentWBMap.get(task.name).foreach { wbArray =>
        wbArray.zipWithIndex.foreach { case (wb, wbIndex) =>
          peInterfaceGroups.addOne(
            HbmInterfaceGroup(
              s"sendArgumentWB:${task.name}:$wbIndex",
              Seq(wb.m_axi.asInstanceOf[axi4.RawInterface].asFull)
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
            HbmInterfaceGroup(s"scheduler:${task.name}:vss:$portIndex", Seq(port))
          )
        }
        scheduler.spawnerServerAXI.foreach { ports =>
          ports.zipWithIndex.foreach { case (port, portIndex) =>
            schedulerInterfaceGroups.addOne(
              HbmInterfaceGroup(s"scheduler:${task.name}:spawner:$portIndex", Seq(port))
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

      assignGroupsToHbmPorts(peInterfaceGroups.toSeq, 0, peMux)
      assignGroupsToHbmPorts(serverGroups.toSeq, peMux, serverMux)
    }

    def hbmSkidBuffer(source: axi4.full.Interface): axi4.full.Interface =
      axi4.full.SlaveBuffer(source, axi4.BufferConfig.all(2))

    def connectThroughHbmSkidBuffer(
        source: axi4.full.Interface,
        sink: axi4.full.Interface
    ): Unit =
      hbmSkidBuffer(source) :=> sink


    if (false){//!isSimulation) {
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
    hbmSlaves.filter(_.nonEmpty).zipWithIndex.map {
      case (hbmSlave, i) => {
        val interfaceCount = hbmSlave.length

        if (
          interfaceCount == 1 && hbmSlave.head.cfg.axi3Compat && hbmSlave.head.cfg.wData == 256
        ) {
          val axiOut =
            IO(axi4.Master(hbmSlave.head.cfg)).suggestName(f"m_axi_${i}%02d")
          connectThroughHbmSkidBuffer(hbmSlave.head, axiOut.asFull)
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
                axiSlaveCfg = cfgAxi4HBM.copy(axi3Compat = axi3CompatFlag, wId = 2),
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
            val protocolConverted = hbmSkidBuffer(protocolConverter.m_axi)

            // if the slave cfg has data width smaller than the axi master config instantiate a Widen
            if(slavePort.cfg.wData < muxPort.cfg.wData){
              val widen_mod = Module(new chext.amba.axi4.full.components.Widen(chext.amba.axi4.full.components.WidenConfig(muxPort.cfg)))
              protocolConverted :=> widen_mod.s_axi
              connectThroughHbmSkidBuffer(widen_mod.m_axi, muxPort)
            } else{
              protocolConverted :=> muxPort
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
            connectThroughHbmSkidBuffer(mux.m_axi, addressTransform.s_axi)
            connectThroughHbmSkidBuffer(addressTransform.m_axi, axiOut.asFull)
          } else {
            connectThroughHbmSkidBuffer(mux.m_axi, axiOut.asFull)
          }

          interfaceBuffer.addOne(
            hdlinfo.Interface(
              f"m_axi_${i}%02d", hdlinfo.InterfaceRole.master, hdlinfo.InterfaceKind("axi4"),
              "clock", "reset", Map("config" -> hdlinfo.TypedObject(axiOut.cfg))
            )
          )
          axiOuts.addOne(axiOut)
        } else {
          val outputCfg = cfgAxi4HBM.copy(axi3Compat = axi3CompatFlag, wId = 2)
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
          val protocolConverted = hbmSkidBuffer(protocolConverter.m_axi)

          if (addressTransformFlag) {
             val addressTransform = Module(new Util.AddressTransform(
              AddressTransformConfig(
                axiCfg = axiOut.cfg,
                transform = Seq(33, 23, 22, 21, 20, 28, 27, 26, 25, 24, 32, 31, 30, 29, 19, 18, 17, 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0).reverse
              )
            ))
            // #TODO add the widen here as well.
            protocolConverted :=> addressTransform.s_axi
            connectThroughHbmSkidBuffer(addressTransform.m_axi, axiOut.asFull)
          } else {
            // Add the Widen for V80
            if(protocolConverter.s_axi.cfg.wData < axiOut.cfg.wData){
              val widen_mod = Module(new chext.amba.axi4.full.components.Widen(chext.amba.axi4.full.components.WidenConfig(axiOut.cfg)))
              protocolConverted :=> widen_mod.s_axi
              connectThroughHbmSkidBuffer(widen_mod.m_axi, axiOut.asFull)
            } else{
              protocolConverted :=> axiOut.asFull
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
