
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
      remoteMemAccessMap: Map[String, RemoteStreamToMem]
  ): Unit = {
    
    // [This is the code block from CleanHardCilk.scala, line 316 to 512]
    
    val interfacesPE = new ArrayBuffer[axi4.full.Interface]()

    
    peMap.foreach { case (taskName, peArray) =>
      val task = fullSysGenDescriptor.taskDescriptors.find(_.name == taskName).get
      peArray.foreach { pe =>
        pe.io.elements.get("m_axi_spawnNext").foreach(p => interfacesPE.addOne(p.asInstanceOf[axi4.RawInterface].asFull))
        pe.io.elements.get("m_axi_argOut").foreach(p => interfacesPE.addOne(p.asInstanceOf[axi4.RawInterface].asFull))
        if (task.hasAXI) {
          interfacesPE.addOne(pe.getPort("m_axi_gmem").asInstanceOf[axi4.RawInterface].asFull)
        }
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
    println(s"[HBM:Interconnect:96] Scheduler interfaces: ${interfacesScheduler.length}")
    println(s"[HBM:Interconnect:97] Closure Allocator interfaces: ${interfacesClosureAllocator.length}")
    println(s"[HBM:Interconnect:98] Argument Notifier interfaces: ${interfacesArgumentNotifier.length}")
    println(s"[HBM:Interconnect:99] Memory Allocator interfaces: ${interfacesMemoryAllocator.length}")


    if (totalPorts > 0) {
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
    numHbmPortExports = hbmSlaves.filter(_._2.length > 0).size
    hbmSlaves.filter(_._2.length > 0).zipWithIndex.map {
      case (hbmSlaves_i, i) => {
        val interfaceCount = hbmSlaves_i._2.length
        val hbmSlave = hbmSlaves_i._2

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