/**
 * Please Clean this up later:
 * 
 * 1. Address transform flag shall be directly specified in the JSON descriptor, currently it can destroy a project.
 * 2. The configuration for the s_axi_slave between vivado flow and vitis flow must be checked also in the JSON
 * 3. Adding a slave for the PCIE axi shall be specified in the JSON descriptor
 * 4. Clean up the configuration of the HBM united vs not vs with address transform
 * 5. Specify AXI3 compat or not based on Vitis or Vivado flow
 * 6. Specify width of the axi mgmt based on Vitis or Vivado flow
 * 7. In the write buffer, argDataOut size is hardcoded and must be taken from the PE somehow (HLSHelpers.scala)
 * 8. The m_axi_argOut data width in HLSHelpers is hardcoded to 32 bits for the fib example, must be taken from the PE somehow
 * 
 * 
 * Adding support for Vitis Flow
 * 
 * Adding support for multi fpga
 * 
 * Adding support for no PE rtl generation
 * 
 * Cleaning up the code
 * 
*/



package HardCilk

import chisel3._

import Scheduler._
import Allocator._
import ArgumentNotifier._
import Descriptors._
import DescriptorJSON._
import TclResources._
import HLSHelpers._
import SoftwareUtil._

import chext.amba.axi4
import axi4.Ops._
import axi4.lite.components._
import chict.ict_segm._
import chext.amba.axi4s
import chext._
  
import io.circe.syntax._
import io.circe.generic.auto._
import scala.collection.mutable.ArrayBuffer
import chisel3.util.log2Ceil
import AXIHelpers._
import Util.AddressTransformConfig
import chext.elastic.TransformOp.transform

class HardCilk_old(
    fullSysGenDescriptor: FullSysGenDescriptor,
    outputDirPathRTL: String,
    debug: Boolean,
    reduceAxi: Int,
    unitedHbm: Boolean,
    isSimulation: Boolean,
    argumentNotifierCutCount: Int,
    addressTransformFlag: Boolean = false
) extends Module {

  val axiOuts = scala.collection.mutable.ArrayBuffer[axi4.RawInterface]()
  val axiXDMA = scala.collection.mutable.ArrayBuffer[axi4.RawInterface]()
  val interfacesAxiControl =
    scala.collection.mutable.ArrayBuffer[axi4.RawInterface]()
  val interfacesAxiManagement =
    scala.collection.mutable.ArrayBuffer[axi4.RawInterface]()
  val paused = IO(Output(Bool())).suggestName("paused")
  val done = IO(Output(Bool())).suggestName("done")
  var numHbmPortExports = reduceAxi

  override def desiredName: String =
    if (fullSysGenDescriptor.name.isEmpty) "fullSysGen"
    else fullSysGenDescriptor.name

  private def initialize(): Unit = {
    // create a modifiable seq to carry the Scheduler, ClosureAllocator, and ArgumentNotifier
    val schedulerMap = scala.collection.mutable.Map[String, Scheduler]()
    val closureAllocatorMap = scala.collection.mutable.Map[String, Allocator]()
    val memoryAllocatorMap = scala.collection.mutable.Map[String, Allocator]()
    val argumentNotifierMap =
      scala.collection.mutable.Map[String, ArgumentNotifier]()
    val peMap = scala.collection.mutable
      .Map[String, Seq[HLSHelpers.VitisWriteBufferModule]]()

    val interfaceBuffer = new ArrayBuffer[hdlinfo.Interface]()

    val registerBlockSize = 6
    val numMasters = fullSysGenDescriptor.getNumConfigPorts()
    println(f"numMasters: ${numMasters}")
    val axiCfgCtrl = axi4.Config(
      wAddr = numMasters + registerBlockSize,
      wData = 64,
      lite = true
    )

    val demux = Module(
      new axi4.lite.components.Demux(
        new DemuxConfig(
          axiCfgCtrl,
          numMasters,
          (x: UInt) => (x >> registerBlockSize.U)
        )
      )
    )

    // HBM Configuration
    val cfgAxi4HBM = axi4.Config(
      wId = 5,
      // wId = 0,
      wAddr = 64,
      wData = 256,
      wUserAR = 0,
      wUserR = 0,
      wUserAW = 0,
      wUserW = 0,
      wUserB = 0
    )
    val cfgXDMA = axi4.Config(wId = 4, wAddr = 64, wData = 512)

    // connect the demux input to the input of the module
    
    val isVitis = true

    
    val s_axil_mgmt = if (isVitis) {
      IO(axi4.Slave(axiCfgCtrl.copy(wData = 32)))
        .suggestName("s_axil_mgmt_hardcilk")
    } else {
      IO(axi4.Slave(axiCfgCtrl)).suggestName("s_axil_mgmt_hardcilk")
    }
    

    

    if (!isVitis)
      s_axil_mgmt :=> demux.s_axil
    else {
      // For Vitis Flow to move to the offset of 0x10
      import chext.elastic.ConnectOp._

      import chext.amba.axi4.lite.components.Upscale
      import chext.amba.axi4.lite.components.UpscaleConfig

      // Create an upscaler from 32 to 64 bits
      val s_axil_mgmt_upscale = Module(
        new Upscale(
          new UpscaleConfig(
            axiCfgCtrl.copy(wData = 32),
            64
          )
        )
      )

      s_axil_mgmt :=> s_axil_mgmt_upscale.s_axi


      val offset = 0x10

      new chext.elastic.Transform(s_axil_mgmt_upscale.m_axi.ar, demux.s_axil.ar) {
        protected override def onTransform: Unit = {
          out := in
          out.addr := in.addr - offset.U
        }
      }

      demux.s_axil.r :=> s_axil_mgmt_upscale.m_axi.r

      new chext.elastic.Transform(s_axil_mgmt_upscale.m_axi.aw, demux.s_axil.aw) {
        protected override def onTransform: Unit = {
          out := in
          out.addr := in.addr - offset.U
        }
      }

      s_axil_mgmt_upscale.m_axi.w :=> demux.s_axil.w
      demux.s_axil.b :=> s_axil_mgmt_upscale.m_axi.b
    }

    interfaceBuffer.addOne(
      hdlinfo.Interface(
        "s_axil_mgmt_hardcilk",
        hdlinfo.InterfaceRole.slave,
        hdlinfo.InterfaceKind("axi4"),
        "clock",
        "reset",
        Map("config" -> hdlinfo.TypedObject(axiCfgCtrl))
      )
    )

    interfacesAxiManagement.addOne(s_axil_mgmt)

    val interfacesPE = new ArrayBuffer[axi4.full.Interface]()
    val interfacesScheduler = new ArrayBuffer[axi4.full.Interface]()
    val interfacesClosureAllocator = new ArrayBuffer[axi4.full.Interface]()
    val interfacesArgumentNotifier = new ArrayBuffer[axi4.full.Interface]()
    val interfacesMemoryAllocator = new ArrayBuffer[axi4.full.Interface]()
    var j = 0
    fullSysGenDescriptor.taskDescriptors.foreach { task =>
      // println(task.mgmtBaseAddresses)

      // Create the black boxes for the task PEs.
      val peArray = VitisModuleFactory(task, fullSysGenDescriptor)
      peMap += (task.name -> peArray)

      // Export the PEs m_axi_gmem and s_axi_control interfaces

      for (i <- 0 until task.numProcessingElements) {
        val pe = peArray(i)
        val peName = f"${task.name}_${i}"

        pe.io.elements
          .get("m_axi_spawnNext")
          .map(port => {
            interfacesPE.addOne(port.asInstanceOf[axi4.RawInterface].asFull)
          })

        pe.io.elements
          .get("m_axi_argOut")
          .map(port => {
            interfacesPE.addOne(port.asInstanceOf[axi4.RawInterface].asFull)
          })

        if (task.hasAXI) {
          interfacesPE.addOne(
            pe.getPort("m_axi_gmem").asInstanceOf[axi4.RawInterface].asFull
          )
          val pes_axi_control = IO(
            chiselTypeOf(
              pe.getPort("s_axi_control").asInstanceOf[axi4.RawInterface]
            )
          )
            .suggestName(f"${peName}_s_axi_control")

          interfaceBuffer.addOne(
            hdlinfo.Interface(
              f"${peName}_s_axi_control",
              hdlinfo.InterfaceRole.slave,
              hdlinfo.InterfaceKind("axi4"),
              "clock",
              "reset",
              Map("config" -> hdlinfo.TypedObject(pes_axi_control.cfg))
            )
          )

          pes_axi_control :=> pe
            .getPort("s_axi_control")
            .asInstanceOf[axi4.RawInterface]
          interfacesAxiControl.addOne(pes_axi_control)
        }

        // Connect the ap signals
        pe.getPort("ap_clk").asInstanceOf[Clock] := clock
        pe.getPort("ap_rst_n").asInstanceOf[Bool] := ~reset.asBool
        try {
          pe.getPort("ap_start").asInstanceOf[Bool] := true.B
        } catch {
          case e: Exception => {
            // println(f"This module has no ap_start. ${e}")
          }
        }

      }

      schedulerMap += (task.name -> Module(
        new Scheduler(
          addrWidth = fullSysGenDescriptor.widthAddress,
          taskWidth = task.widthTask,
          queueDepth = task.getCapacityPhysicalQueue("scheduler"),
          peCount = task.numProcessingElements,
          spawnsItself = fullSysGenDescriptor.selfSpawnedCount(task.name) > 0,
          peCountGlobalTaskIn =
            fullSysGenDescriptor.getPortCount("spawn", task.name),
          argRouteServersNumber = task.getNumServers("argumentNotifier"),
          schedulerServersNumber = task.getNumServers("scheduler"),
          // get the port width from the PE from HLSHelpers (axisCfgAddress)
          pePortWidth = peMap(task.name).head
            .getPort("taskIn")
            .asInstanceOf[axi4s.Interface]
            .cfg
            .wData,
          peType = task.name,
          debug = debug,
          spawnerServerNumber = task.spawnServersCount,
          argRouteServersCreateTasks =
            task.sidesConfigs.length > 2 // HARDCODED, might be 3 if there is mem alloc
        )
      ))

      // Export the AXI interface of the Scheduler
      interfacesScheduler.addAll(
        schedulerMap(task.name).io_internal.vss_axi_full
      )

      // Connect the Scheduler Management to the management demux
      for (i <- j until j + task.getNumServers("scheduler")) {
        demux.m_axil(i) :=> schedulerMap(task.name).io_internal
          .axi_mgmt_vss(i - j)
      }
      j += task.getNumServers("scheduler")

      if (schedulerMap(task.name).spawnerServerAXI.isDefined) {
        interfacesScheduler.addAll(
          schedulerMap(task.name).spawnerServerAXI.get
        )
      }

      if (schedulerMap(task.name).spawnerServerMgmt.isDefined) {
        for (i <- j until j + task.spawnServersCount) {
          println(f"Index ${i - j}")
          println(f"I value ${i}")
          println(f"Demux axi length ${demux.m_axil.length}")
          demux.m_axil(i) :=> schedulerMap(task.name).spawnerServerMgmt
            .get(i - j)
        }
        j += task.spawnServersCount
      }

      if (fullSysGenDescriptor.getPortCount("spawnNext", task.name) > 0) {
        closureAllocatorMap += (task.name -> Module(
          new Allocator(
            addrWidth = fullSysGenDescriptor.widthAddress,
            peCount = fullSysGenDescriptor.getPortCount("spawnNext", task.name),
            vcasCount = task.getNumServers("allocator"),
            queueDepth = task.getCapacityPhysicalQueue("allocator"),
            // pePortWidth = task.getPortWidth("allocator")
            pePortWidth =
              64 // peMap(task.name).head.getPort("closureIn").asInstanceOf[axi4s.Interface].cfg.wData  // CHECK THIS
          )
        ))

        // Export the AXI interface of the ClosureAllocator
        interfacesClosureAllocator.addAll(
          closureAllocatorMap(task.name).io_internal.vcas_axi_full
        )

        // Connect the ClosureAllocator Management to the management demux
        for (i <- j until j + task.getNumServers("allocator")) {
          demux.m_axil(i) :=> closureAllocatorMap(task.name).io_internal
            .axi_mgmt_vcas(i - j)
        }
        j += task.getNumServers("allocator")
      }

      if (fullSysGenDescriptor.getPortCount("sendArgument", task.name) > 0) {
        println(task.name)
        argumentNotifierMap += (task.name -> Module(
          new ArgumentNotifier(
            addrWidth =
              if (task.variableSpawn)
                (34 + fullSysGenDescriptor.widthContCounter + 6)
              else
                fullSysGenDescriptor.widthAddress, // 34 is Alveo U55C FPGA specific
            taskWidth = task.widthTask,
            queueDepth = task.getCapacityPhysicalQueue("argumentNotifier"),
            peCount =
              fullSysGenDescriptor.getPortCount("sendArgument", task.name),
            argRouteServersNumber = task.getNumServers("argumentNotifier"),
            contCounterWidth = fullSysGenDescriptor.widthContCounter,
            // pePortWidth = task.getPortWidth("argumentNotifier"),
            pePortWidth =
              64, // peMap(task.name).head.getPort("argOut").asInstanceOf[axi4s.Interface].cfg.wData,

            cutCount = argumentNotifierCutCount,
            multiDecrease = task.variableSpawn,
            mfpgaSupport = false,
            taskID = 0
          )
        ))

        // Export the AXI interface of the ArgumentNotifier
        interfacesArgumentNotifier.addAll(
          argumentNotifierMap(task.name).axi_full_argRoute
        )

        schedulerMap(task.name).connArgumentNotifier <> argumentNotifierMap(
          task.name
        ).connStealNtw
      }

      if (fullSysGenDescriptor.getPortCount("mallocIn", task.name) > 0) {
        memoryAllocatorMap += (task.name -> Module(
          new Allocator(
            addrWidth = fullSysGenDescriptor.widthAddress,
            peCount = fullSysGenDescriptor.getPortCount("mallocIn", task.name),
            vcasCount = task.getNumServers("memoryAllocator"),
            queueDepth = task.getCapacityPhysicalQueue("memoryAllocator"),
            // pePortWidth = task.getPortWidth("memoryAllocator")
            pePortWidth = peMap(task.name).head
              .getPort("mallocIn")
              .asInstanceOf[axi4s.Interface]
              .cfg
              .wData
          )
        ))

        // Export the AXI interface of the MemoryAllocator
        interfacesMemoryAllocator.addAll(
          memoryAllocatorMap(task.name).io_internal.vcas_axi_full
        )

        for (i <- j until j + task.getNumServers("memoryAllocator")) {
          demux.m_axil(i) :=> memoryAllocatorMap(task.name).io_internal
            .axi_mgmt_vcas(i - j)
        }
        j += task.getNumServers("memoryAllocator")
      }
    }

    // Connect paused and done signals: N.B: needs to be fixed to accomodate different notifcation approaches
    val schedulerPaused =
      if (schedulerMap.isEmpty) false.B
      else schedulerMap.map(_._2.io_paused).reduce(_ || _)
    val closureAllocatorPaused =
      if (closureAllocatorMap.isEmpty) false.B
      else closureAllocatorMap.map(_._2.io_paused).reduce(_ || _)
    val memoryAllocatorPaused =
      if (memoryAllocatorMap.isEmpty) false.B
      else memoryAllocatorMap.map(_._2.io_paused).reduce(_ || _)
    paused := schedulerPaused || closureAllocatorPaused || memoryAllocatorPaused
    done := argumentNotifierMap.map(_._2.io_export.done).reduce(_ || _)

    // Connect the Interconnect
    val numHBMPorts = reduceAxi
    val log2k = 2
    val hbmSlaves =
      scala.collection.mutable.Map[Int, ArrayBuffer[axi4.full.Interface]]()
    for (i <- 0 until numHBMPorts) {
      hbmSlaves += (i -> new ArrayBuffer[axi4.full.Interface]())
    }

    val totalPorts =
      interfacesPE.length + interfacesMemoryAllocator.length + interfacesScheduler.length + interfacesClosureAllocator.length + interfacesArgumentNotifier.length
    val numPortsPerMux = totalPorts.toDouble / numHBMPorts.toDouble
    val peMux = math.ceil(1.0 * interfacesPE.length / numPortsPerMux).toInt
    val serverMux = numHBMPorts - peMux

    interfacesPE.zipWithIndex
      .groupBy(x => (x._2.toDouble / (1.0 * interfacesPE.length / peMux)).toInt)
      .foreach(x => {
        hbmSlaves(x._1).addAll(x._2.map(_._1))
      })
    interfacesMemoryAllocator.zipWithIndex
      .groupBy(x =>
        peMux + (x._2.toDouble / (1.0 * interfacesMemoryAllocator.length / serverMux)).toInt
      )
      .foreach(x => {
        hbmSlaves(x._1).addAll(x._2.map(_._1))
      })
    interfacesScheduler.zipWithIndex
      .groupBy(x =>
        peMux + (x._2.toDouble / (1.0 * interfacesScheduler.length / serverMux)).toInt
      )
      .foreach(x => {
        hbmSlaves(x._1).addAll(x._2.map(_._1))
      })
    interfacesClosureAllocator.zipWithIndex
      .groupBy(x =>
        peMux + (x._2.toDouble / (1.0 * interfacesClosureAllocator.length / serverMux)).toInt
      )
      .foreach(x => {
        hbmSlaves(x._1).addAll(x._2.map(_._1))
      })
    interfacesArgumentNotifier.zipWithIndex
      .groupBy(x =>
        peMux + (x._2.toDouble / (1.0 * interfacesArgumentNotifier.length / serverMux)).toInt
      )
      .foreach(x => {
        hbmSlaves(x._1).addAll(x._2.map(_._1))
      })

    if (false){//!isSimulation) { // skipping for vitis flow
    val xdma_axi = IO(axi4.Slave(cfgXDMA)).suggestName("s_axi_xdma")
    hbmSlaves(numHBMPorts - 1).addOne(
      axi4.full.SlaveBuffer(xdma_axi.asFull, axi4.BufferConfig.all(8))
    )

    interfaceBuffer.addOne(
      hdlinfo.Interface(
        "s_axi_xdma",
        hdlinfo.InterfaceRole.slave,
        hdlinfo.InterfaceKind("axi4"),
        "clock",
        "reset",
        Map("config" -> hdlinfo.TypedObject(cfgXDMA))
      )
    )

    axiXDMA.addOne(xdma_axi)
    }

    def SlaveBuffer(axi: axi4.full.Interface) =
      axi4.full.SlaveBuffer(axi, axi4.BufferConfig.all(2))
    if (!unitedHbm) {
      // Create the interconnect
      // TODO: Calculate the id or add ID serializer
      // TODO: Drive the empty signals
      val ict = Module(
        new IctSegm(
          new IctSegmConfig(
            cfgAxi4HBM.copy(wId = 4),
            log2Ceil(numHBMPorts),
            log2k
          )
        )
      )

      ict.s_axi.zipWithIndex.foreach { case (port, i) =>
        // Mux the interfaces
        val mux = Module(
          new axi4.full.components.Mux(
            new axi4.full.components.MuxConfig(
              axiSlaveCfg = cfgAxi4HBM.copy(axi3Compat = true),
              numSlaves = hbmSlaves(i).length
            )
          )
        )
        mux.s_axi.zip(hbmSlaves(i)).foreach { case (muxPort, slavePort) =>
          val protocolConverter = Module(
            new axi4.full.components.ProtocolConverter(
              new axi4.full.components.ProtocolConverterConfig(
                axiSlaveCfg = slavePort.cfg.copy(
                  wUserAR = 0,
                  wUserR = 0,
                  wUserAW = 0,
                  wUserW = 0,
                  wUserB = 0
                ),
                axiMasterCfg = muxPort.cfg
              )
            )
          )
          SlaveBuffer(
            SlaveBuffer(SlaveBuffer(AxiUserYanker(slavePort)))
          ) :=> protocolConverter.s_axi
          AxiStriper(protocolConverter.m_axi) :=> muxPort
        }
        axi4.full.SlaveBuffer(mux.m_axi, axi4.BufferConfig(2)) :=> port
      }

      // Connect the outputs of the interconnect to the IO through protocol converters to make it axi3 compatible
      ict.m_axi.zipWithIndex.foreach { case (port, i) =>
        val protocolConverter = Module(
          new axi4.full.components.ProtocolConverter(
            new axi4.full.components.ProtocolConverterConfig(
              axiSlaveCfg = port.cfg,
              axiMasterCfg = cfgAxi4HBM.copy(axi3Compat = true)
            )
          )
        )
        port :=> protocolConverter.s_axi
        val axiOut = IO(axi4.Master(protocolConverter.m_axi.cfg))
          .suggestName(f"m_axi_${i}%02d")

        SlaveBuffer(
          SlaveBuffer(SlaveBuffer(protocolConverter.m_axi))
        ) :=> axiOut.asFull
        interfaceBuffer.addOne(
          hdlinfo.Interface(
            f"m_axi_${i}%02d",
            hdlinfo.InterfaceRole.master,
            hdlinfo.InterfaceKind("axi4"),
            "clock",
            "reset",
            Map("config" -> hdlinfo.TypedObject(axiOut.cfg))
          )
        )
        axiOuts.addOne(axiOut)
      }
    } else if (false) {
      numHbmPortExports = hbmSlaves.filter(_._2.length > 0).size
      hbmSlaves.filter(_._2.length > 0).zipWithIndex.map {
        case (hbmSlaves_i, i) => {

          val hbmSlave = hbmSlaves_i._2
          // Mux the interfaces
          val mux = Module(
            new axi4.full.components.Mux(
              new axi4.full.components.MuxConfig(
                axiSlaveCfg = cfgAxi4HBM.copy(axi3Compat = true),
                numSlaves = hbmSlave.length
              )
            )
          )

          mux.s_axi.zip(hbmSlave).foreach { case (muxPort, slavePort) =>
            val protocolConverter = Module(
              new axi4.full.components.ProtocolConverter(
                new axi4.full.components.ProtocolConverterConfig(
                  axiSlaveCfg = slavePort.cfg.copy(
                    wUserAR = 0,
                    wUserR = 0,
                    wUserAW = 0,
                    wUserW = 0,
                    wUserB = 0
                  ),
                  axiMasterCfg = muxPort.cfg
                )
              )
            )

            println(
              s"Configuration for the protocol converter: ${protocolConverter.cfg}"
            )
            // println(s"Configuration for the mux port")

            // println("\n--- m_axi ID Widths ---")
            // println(s"m_axi.ar.bits.id.getWidth: ${protocolConverter.m_axi.ar.bits.id.getWidth}")
            // println(s"m_axi.aw.bits.id.getWidth: ${protocolConverter.m_axi.aw.bits.id.getWidth}")
            // println(s"m_axi.b.bits.id.getWidth:  ${protocolConverter.m_axi.b.bits.id.getWidth}")
            // println(s"m_axi.r.bits.id.getWidth:  ${protocolConverter.m_axi.r.bits.id.getWidth}")

            // println("\n--- s_axi ID Widths ---")
            // println(s"s_axi.ar.bits.id.getWidth: ${protocolConverter.s_axi.ar.bits.id.getWidth}")
            // println(s"s_axi.aw.bits.id.getWidth: ${protocolConverter.s_axi.aw.bits.id.getWidth}")
            // println(s"s_axi.b.bits.id.getWidth:  ${protocolConverter.s_axi.b.bits.id.getWidth}")
            // println(s"s_axi.r.bits.id.getWidth:  ${protocolConverter.s_axi.r.bits.id.getWidth}")

            axi4.full.SlaveBuffer(
              AxiUserYanker(slavePort),
              axi4.BufferConfig.all(8)
            ) :=> protocolConverter.s_axi
            protocolConverter.m_axi :=> muxPort
          }

          val axiOut =
            IO(axi4.Master(mux.m_axi.cfg)).suggestName(f"m_axi_${i}%02d")
          mux.m_axi :=> axiOut.asFull
          interfaceBuffer.addOne(
            hdlinfo.Interface(
              f"m_axi_${i}%02d",
              hdlinfo.InterfaceRole.master,
              hdlinfo.InterfaceKind("axi4"),
              "clock",
              "reset",
              Map("config" -> hdlinfo.TypedObject(axiOut.cfg))
            )
          )
          axiOuts.addOne(axiOut)
        }
      }
    } else {
      val axi3CompatFlag = false // only keeps axi4 ports as axi4 i ffalse
      numHbmPortExports = hbmSlaves.filter(_._2.length > 0).size
      hbmSlaves.filter(_._2.length > 0).zipWithIndex.map {
        case (hbmSlaves_i, i) => {

          val interfaceCount = hbmSlaves_i._2.length
          val hbmSlave = hbmSlaves_i._2

          println(
            s"Interfaces number ${interfaceCount}, widthData ${hbmSlave.head.cfg.wData}, AXI3 COMPAT ${hbmSlave.head.cfg.axi3Compat}"
          )

          if (
            interfaceCount == 1 && hbmSlave.head.cfg.axi3Compat && hbmSlave.head.cfg.wData == 256
          ) {
            println(f"AXI3 COMPAT single interface, index${i}")
            val axiOut =
              IO(axi4.Master(hbmSlave.head.cfg)).suggestName(f"m_axi_${i}%02d")
            hbmSlave.head :=> axiOut.asFull
            interfaceBuffer.addOne(
              hdlinfo.Interface(
                f"m_axi_${i}%02d",
                hdlinfo.InterfaceRole.master,
                hdlinfo.InterfaceKind("axi4"),
                "clock",
                "reset",
                Map("config" -> hdlinfo.TypedObject(axiOut.cfg))
              )
            )
            axiOuts.addOne(axiOut)
          } else if (interfaceCount > 1) {

            println("MUXed interface")

            // Mux the interfaces
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
                    axiSlaveCfg = slavePort.cfg.copy(
                      wUserAR = 0,
                      wUserR = 0,
                      wUserAW = 0,
                      wUserW = 0,
                      wUserB = 0
                    ),
                    axiMasterCfg = muxPort.cfg
                  )
                )
              )

              axi4.full.SlaveBuffer(
                AxiUserYanker(slavePort),
                axi4.BufferConfig.all(8)
              ) :=> protocolConverter.s_axi
              protocolConverter.m_axi :=> muxPort
            }

            val axiOut =
              IO(axi4.Master(mux.m_axi.cfg)).suggestName(f"m_axi_${i}%02d")

            if (addressTransformFlag) {
              val addressTransform = Module(
                new Util.AddressTransform(
                  // AddressTransformConfig(
                  //   axiCfg = axiOut.cfg,
                  //   transform = Seq( 33, 17, 16, 15, 14, 28, 27,
                  //     26, 25, 24, 23, 22, 21, 20, 19, 18, 32, 31, 30, 29, 13,
                  //     12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0).reverse
                  // )

                  AddressTransformConfig(
                    axiCfg = axiOut.cfg,
                    transform = Seq(33, 23, 22, 21, 20, 28, 27, 26, 25, 24, 32, 31, 30, 29, 19, 18, 17, 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0).reverse
                  )
                )
              )

              mux.m_axi :=> addressTransform.s_axi
              addressTransform.m_axi :=> axiOut.asFull
            } else {
              mux.m_axi :=> axiOut.asFull
            }


            
            interfaceBuffer.addOne(
              hdlinfo.Interface(
                f"m_axi_${i}%02d",
                hdlinfo.InterfaceRole.master,
                hdlinfo.InterfaceKind("axi4"),
                "clock",
                "reset",
                Map("config" -> hdlinfo.TypedObject(axiOut.cfg))
              )
            )
            axiOuts.addOne(axiOut)
          } else {
            println("Non AXI 3 COMPAT single interface")
            val outputCfg = cfgAxi4HBM.copy(axi3Compat = axi3CompatFlag, wId = 2)

            val axiOut =
              IO(axi4.Master(outputCfg)).suggestName(f"m_axi_${i}%02d")

            val protocolConverter = Module(
              new axi4.full.components.ProtocolConverter(
                new axi4.full.components.ProtocolConverterConfig(
                  axiSlaveCfg = hbmSlave.head.cfg.copy(
                    wUserAR = 0,
                    wUserR = 0,
                    wUserAW = 0,
                    wUserW = 0,
                    wUserB = 0
                  ),
                  axiMasterCfg = outputCfg
                )
              )
            )

            axi4.full.SlaveBuffer(
              AxiUserYanker(hbmSlave.head),
              axi4.BufferConfig.all(2)
            ) :=> protocolConverter.s_axi

            if (addressTransformFlag) {
              val addressTransform = Module(
                new Util.AddressTransform(
                  
                  /*AddressTransformConfig(
                    axiCfg = axiOut.cfg,
                    transform = Seq( 33, 17, 16, 15, 14, 28, 27,
                      26, 25, 24, 23, 22, 21, 20, 19, 18, 32, 31, 30, 29, 13,
                      12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0).reverse
                  )*/
                  AddressTransformConfig(
                    axiCfg = axiOut.cfg,
                    transform = Seq(33, 23, 22, 21, 20, 28, 27, 26, 25, 24, 32, 31, 30, 29, 19, 18, 17, 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0).reverse
                  )

                )
              )

              protocolConverter.m_axi :=> addressTransform.s_axi
              addressTransform.m_axi :=> axiOut.asFull
            } else {
              protocolConverter.m_axi :=> axiOut.asFull
            }

          }

        }
      }
    }

    // Connet the PEs to the system based on the connection descriptor
    val systemConnectionsDescriptor =
      fullSysGenDescriptor.getSystemConnectionsDescriptor()

    for (connection <- systemConnectionsDescriptor.connections) {

      // log the connection
      println(
        f"Connecting ${connection.srcPort.parentType} ${connection.srcPort.parentName} ${connection.srcPort.portType} ${connection.srcPort.portIndex} to ${connection.dstPort.parentType} ${connection.dstPort.parentName} ${connection.dstPort.portType} ${connection.dstPort.portIndex}"
      )

      try {
        val physicalSourcePort = connection.srcPort.parentType match {
          case "HardCilk" => {
            connection.srcPort.portType match {
              case "taskIn" | "taskOut" | "taskInGlobal" =>
                schedulerMap(connection.srcPort.parentName).io_export
                  .getPort(
                    connection.srcPort.portType,
                    connection.srcPort.portIndex
                  )
              case "closureOut" =>
                closureAllocatorMap(connection.srcPort.parentName).io_export
                  .getPort(
                    connection.srcPort.portType,
                    connection.srcPort.portIndex
                  )
              case "mallocOut" =>
                memoryAllocatorMap(connection.srcPort.parentName).io_export
                  .getPort(
                    connection.srcPort.portType,
                    connection.srcPort.portIndex
                  )
              case "argIn" =>
                argumentNotifierMap(connection.srcPort.parentName).io_export
                  .getPort(
                    connection.srcPort.portType,
                    connection.srcPort.portIndex
                  )
            }
          }
          case "PE" => {
            peMap(connection.srcPort.parentName)(connection.srcPort.parentIndex)
              .getPort(connection.srcPort.portType)
          }
        }

        val physicalDestinationPort = connection.dstPort.parentType match {
          case "HardCilk" => {
            connection.dstPort.portType match {
              case "taskIn" | "taskOut" | "taskInGlobal" =>
                schedulerMap(connection.dstPort.parentName).io_export
                  .getPort(
                    connection.dstPort.portType,
                    connection.dstPort.portIndex
                  )
              case "closureOut" =>
                closureAllocatorMap(connection.dstPort.parentName).io_export
                  .getPort(
                    connection.dstPort.portType,
                    connection.dstPort.portIndex
                  )
              case "mallocOut" =>
                memoryAllocatorMap(connection.dstPort.parentName).io_export
                  .getPort(
                    connection.dstPort.portType,
                    connection.dstPort.portIndex
                  )
              case "argIn" =>
                argumentNotifierMap(connection.dstPort.parentName).io_export
                  .getPort(
                    connection.dstPort.portType,
                    connection.dstPort.portIndex
                  )
            }
          }
          case "PE" => {
            peMap(connection.dstPort.parentName)(connection.dstPort.parentIndex)
              .getPort(connection.dstPort.portType)
          }
        }

        // Connect the ports
        physicalSourcePort <> physicalDestinationPort

      } catch {
        case e: Exception => {
          println(e)
        }
      }
    }

    lazy val hdlinfoModule: hdlinfo.Module = {
      import hdlinfo._
      Module(
        fullSysGenDescriptor.name,
        Seq(
          Port(
            "clock",
            PortDirection.input,
            PortKind.clock
          ),
          Port(
            "reset",
            PortDirection.input,
            PortKind.reset,
            PortSensitivity.resetActiveHigh,
            associatedClock = "clock"
          ),
          Port(
            "paused",
            PortDirection.output,
            PortKind.data
          ),
          Port(
            "done",
            PortDirection.output,
            PortKind.data
          )
        ),
        interfaceBuffer.toSeq
      )
    }
    val write = new java.io.PrintWriter(
      f"${outputDirPathRTL}/${fullSysGenDescriptor.name}.hdlinfo.json"
    )
    write.write(hdlinfoModule.asJson.toString())
    write.close()
  }

  initialize()
}

object HardCilkEmitter_old extends App {
  import _root_.circt.stage.ChiselStage
  import java.time.format.DateTimeFormatter
  import scopt.OParser

  // Handling argument parsing
  case class BuilderConfig(
      val debug: Boolean = false,
      val reduce_axi: Int = 32,
      val timestamped: Boolean = false,
      val cpp_header_generation: Boolean = false,
      val tcl_generation: Boolean = false,
      val rtl_generation: Boolean = false,
      val sc_header_generation: Boolean = false,
      val project_sc_generation: Boolean = false,
      val output_dir: String = ".",
      val json_path: String = ""
  )

  val builder = OParser.builder[BuilderConfig]
  val parser = {
    import builder._
    OParser.sequence(
      programName("HardCilk"),
      head("HardCilk", "0.1"),
      arg[String]("<json-path>")
        .required()
        .action((x, c) => c.copy(json_path = x))
        .text("path of a JSON descriptor for the HardCilk system"),
      opt[String]('o', "output-dir")
        .action((x, c) => c.copy(output_dir = x))
        .text("output directory"),
      opt[Unit]('d', "debug")
        .action((_, c) => c.copy(debug = true))
        .text("enable debug hardware counters and simulation logging"),
      opt[Int]('r', "reduce-axi")
        .action((x, c) => c.copy(reduce_axi = x))
        .text("enable AXI port reduction to HBM capacity"),
      opt[Unit]('t', "timestamped")
        .action((_, c) => c.copy(timestamped = true))
        .text("generate output in a timestamped folder"),
      opt[Unit]('g', "rtl-generation")
        .action((_, c) => c.copy(rtl_generation = true))
        .text("Generates the RTL output of the HardCilk"),
      opt[Unit]('c', "cpp-headers")
        .action((_, c) => c.copy(cpp_header_generation = true))
        .text("Generates the C++ headers needed for the driver"),
      opt[Unit]('b', "tcl-scripts")
        .action((_, c) => c.copy(tcl_generation = true))
        .text(
          "Generates the TCL output of the HardCilk for Vivado Block Design"
        ),
      opt[Unit]('s', "sc-headers")
        .action((_, c) => c.copy(sc_header_generation = true))
        .text("Generates the C++ header for SystemC simulation"),
      opt[Unit]('p', "project-sc")
        .action((_, c) => c.copy(project_sc_generation = true))
        .text("Generates the C++ project for SystemC simulation"),
      opt[Unit]('a', "all")
        .action((_, c) =>
          c.copy(
            cpp_header_generation = true,
            rtl_generation = true,
            tcl_generation = true,
            sc_header_generation = true,
            project_sc_generation = true
          )
        )
        .text(
          "Generates all outputs for HardCilk, equivilant to using `-g -c -b -s` flags"
        ),
      help("help").text("Prints this help text")
    )
  }

  // Helpers
  def readFile(path: String): String = {
    import java.nio.charset.StandardCharsets
    import java.nio.file.{Files, Path}
    Files.readString(Path.of(path), StandardCharsets.UTF_8)
  }

  def writeFile(path: String, data: String): Unit = {
    import java.nio.charset.StandardCharsets
    import java.nio.file.{Files, Path}
    Files.writeString(Path.of(path), data, StandardCharsets.UTF_8)
  }

  def basename(path: String): String = {
    path.split("/").last.split("\\.").head
  }

  def generateRTL(
      systemDescriptor: FullSysGenDescriptor,
      pathInputJsonFile: String,
      outputDirPathRTL: String,
      flags: BuilderConfig,
      isSimulation: Boolean
  ): Int = {
    // for task in system descriptor copy all the files in the peHDLPath to the outputDirRTL
    systemDescriptor.taskDescriptors.foreach { task =>
      val peHDLPath = task.peHDLPath
      val peHDLPathFiles = new java.io.File(peHDLPath).listFiles()
      peHDLPathFiles.foreach { file =>
        val fileName = file.getName()
        val fileContent = readFile(file.getAbsolutePath())
        writeFile(s"$outputDirPathRTL/$fileName", fileContent)
      }
    }

    // Copy all the files in the src/main/resources/ to the outputDirRTL except the DualPortBRAM_sim.v
    val resourcesPath = "src/main/resources/"
    val synthDirectory = f"${outputDirPathRTL}/synth"
    val questaDirectory = f"${outputDirPathRTL}/questa"
    new java.io.File(synthDirectory).mkdirs()
    new java.io.File(questaDirectory).mkdirs()

    val resourcesFiles = new java.io.File(resourcesPath).listFiles()
    val listOfFilesForRTL =
      List("DualPortBRAM_sim.v", "DualPortBRAM_xpm.v", "top.v", "u55c.xdc")
    val listOfFilesForQuesta = List("top_sim.sv", "main_sim.sv")
    writeFile(s"$outputDirPathRTL/empty.vh", "")
    writeFile(s"$outputDirPathRTL/empty.sv", "")
    resourcesFiles.foreach { file =>
      val fileName = file.getName()
      val fileContent = readFile(file.getAbsolutePath())

      if (fileName.startsWith("DualPortBRAM")) {
        if (
          (isSimulation && fileName == "DualPortBRAM_sim.v") || (!isSimulation && fileName == "DualPortBRAM_xpm.v")
        ) {
          writeFile(s"$outputDirPathRTL/DualPortBRAM.v", fileContent)
        }
      } else if (listOfFilesForQuesta.contains(fileName)) {
        writeFile(s"$questaDirectory/$fileName", fileContent)
      } else {
        writeFile(s"$synthDirectory/$fileName", fileContent)
      }
    }

    var numHbmPortExports = 0
    ChiselStage.emitSystemVerilogFile(
      {
        val module = new HardCilk_old(
          fullSysGenDescriptor = systemDescriptor,
          outputDirPathRTL = outputDirPathRTL,
          debug = flags.debug,
          reduceAxi = flags.reduce_axi,
          unitedHbm = true,
          isSimulation = isSimulation,
          argumentNotifierCutCount = 1
        )
        numHbmPortExports = module.numHbmPortExports
        module
      },
      Array(f"--target-dir=${outputDirPathRTL}"),
      Array("--disable-all-randomization")
    )

    // For the file in the outputDirRTL with the name of the systemDescriptor.name run sv2v on it using os.system, then remove the original file
    import sys.process._
    val svFilePath = s"$outputDirPathRTL/${systemDescriptor.name}.sv"
    val vFilePath = s"$outputDirPathRTL/${systemDescriptor.name}.v"

    // Check if the SystemVerilog file exists
    val svFile = new java.io.File(svFilePath)
    if (svFile.exists()) {
      val sv2vCommand = s"sv2v $svFilePath"
      // Get the ouput of the command instead of stdout
      val sv2vOutput = sv2vCommand.!!
      val rmCommand = s"rm $svFilePath"
      rmCommand.!

      // Write the output of sv2v to the verilog file
      writeFile(vFilePath, sv2vOutput)

    } else {
      println(s"Error: File $svFilePath does not exist.")
    }

    numHbmPortExports
  }

  // Main body
  OParser.parse(parser, args, BuilderConfig()) match {
    case None =>
      println(
        f"Incorrect usage, please run with `--help` option to get the usage help"
      )
    case Some(flags) => {
      // Create a directory under the output directory with the name of the json file, date, and timestamp (nearest second)
      val pathOutputDir = flags.output_dir
      val pathInputJsonFile = flags.json_path
      val jsonName = basename(pathInputJsonFile)
      val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
      val timeFormatter = DateTimeFormatter.ofPattern("HH-mm-ss")

      val outputDirName =
        if (flags.timestamped)
          s"${jsonName}_${java.time.LocalDate.now.format(dateFormatter)}_${java.time.LocalTime.now
              .format(timeFormatter)}"
        else
          s"${jsonName}_hardcilk_output"

      val outputDirPathRTL = s"$pathOutputDir/$outputDirName/rtl"
      val outputDirPathSoftwareHeaders =
        s"$pathOutputDir/$outputDirName/softwareHeaders"
      val outputDirPathTCL = s"$pathOutputDir/$outputDirName/tcl"
      val outputDirPathSC = s"$pathOutputDir/$outputDirName/software"

      // Create the sytem descriptors
      val systemDescriptor =
        parseJsonFile[FullSysGenDescriptor](pathInputJsonFile)

      // @TODO: Add a flag to delete the output directory
      // if any of the flags is true, delete the output directory
      import sys.process._
      if (
        flags.rtl_generation || flags.cpp_header_generation || flags.tcl_generation || flags.sc_header_generation || flags.project_sc_generation
      ) {
        val outputDir = new java.io.File(s"$pathOutputDir/$outputDirName")
        if (outputDir.exists()) {
          val deleteCommand = s"rm -r $outputDir"
          deleteCommand.!
        }
      }

      // Create the directories
      var numHbmPortExports = flags.reduce_axi
      if (flags.project_sc_generation) {
        // Using java.nio copy a folder with all its content (files and subfolders) to another folder, source is "pwd/software_template" and destination is "outputDirPathSC"
        val source = new java.io.File("software_template")
        val destination = new java.io.File(outputDirPathSC)
        java.nio.file.Files
          .walk(source.toPath)
          .forEach(sourcePath => {
            val destinationPath =
              destination.toPath.resolve(source.toPath.relativize(sourcePath))
            if (sourcePath.toFile.isDirectory) {
              java.nio.file.Files.createDirectories(destinationPath)
            } else {
              java.nio.file.Files.copy(sourcePath, destinationPath)
            }
          })

        // Rename `outputDirPathSC/projects/project_template` to `outputDirPathSC/projects/${jsonName}`
        val projectTemplate =
          new java.io.File(s"$outputDirPathSC/projects/project_template")
        val projectDestination =
          new java.io.File(s"$outputDirPathSC/projects/${jsonName}")
        projectTemplate.renameTo(projectDestination)

        // Generate the HDL in the `outputDirPathSC/projects/${jsonName}/hdl`
        new java.io.File(s"$outputDirPathSC/projects/${jsonName}/hdl").mkdirs()
        numHbmPortExports = generateRTL(
          systemDescriptor,
          pathInputJsonFile,
          s"$outputDirPathSC/projects/${jsonName}/hdl",
          flags,
          true
        )

        // Generate the SystemC project in the `outputDirPathSC/project/${jsonName}/include`
        new java.io.File(s"$outputDirPathSC/projects/${jsonName}/include")
          .mkdirs()
        CppHeaderTemplate.generateCppHeader(
          systemDescriptor,
          s"$outputDirPathSC/projects/${jsonName}/include",
          numHbmPortExports
        )

        // Generate the SystemC testbench in the `outputDirPathSC/projects/${jsonName}/include`
        TestBenchHeaderTemplate.generateCppHeader(
          systemDescriptor,
          s"$outputDirPathSC/projects/${jsonName}/include",
          numHbmPortExports
        )

        // Read the `outputDirPathSC/projects/${jsonName}/CMakeLists.txt` and replace the `${project_template}` with the `${jsonName}`
        val cmakeListsPath =
          s"$outputDirPathSC/projects/${jsonName}/CMakeLists.txt"
        val cmakeListsContent = readFile(cmakeListsPath)
        val newCmakeListsContent =
          cmakeListsContent.replace("${project_template}", jsonName)
        writeFile(cmakeListsPath, newCmakeListsContent)

        // Also copy `../software/${jsonName}` to `outputDirPathSC/projects/${jsonName}`
        val sourceProject = new java.io.File(s"../software/${jsonName}")
        java.nio.file.Files
          .walk(sourceProject.toPath)
          .forEach(sourcePath => {
            val destinationPath = projectDestination.toPath
              .resolve(sourceProject.toPath.relativize(sourcePath))
            if (sourcePath.toFile.isDirectory) {
              java.nio.file.Files.createDirectories(destinationPath)
            } else {
              java.nio.file.Files.copy(sourcePath, destinationPath)
            }
          })
      }
      if (flags.rtl_generation) {
        new java.io.File(outputDirPathRTL).mkdirs()
        numHbmPortExports = generateRTL(
          systemDescriptor,
          pathInputJsonFile,
          outputDirPathRTL,
          flags,
          false
        )
        dumpJsonFile[FullSysGenDescriptorExtended](
          s"$outputDirPathRTL/${systemDescriptor.name}_descriptor_2.json",
          FullSysGenDescriptorExtended.fromFullSysGenDescriptor(
            systemDescriptor
          )
        )
      }
      if (flags.cpp_header_generation || flags.sc_header_generation) {
        new java.io.File(outputDirPathSoftwareHeaders).mkdirs()
        if (flags.cpp_header_generation)
          CppHeaderTemplate.generateCppHeader(
            systemDescriptor,
            outputDirPathSoftwareHeaders,
            numHbmPortExports
          )
        if (flags.sc_header_generation)
          TestBenchHeaderTemplate.generateCppHeader(
            systemDescriptor,
            outputDirPathSoftwareHeaders,
            numHbmPortExports
          )
      }
      if (flags.tcl_generation) {
        new java.io.File(outputDirPathTCL).mkdirs()
        TclGeneratorMemPEs.generate(
          systemDescriptor,
          outputDirPathTCL,
          numHbmPortExports
        )
        TclQuestaSim.generate(
          systemDescriptor,
          outputDirPathTCL,
          numHbmPortExports
        )
      }
    }
  }
}

import chext.amba.axi4.full.components._

object EmitProtocolConverter extends App {
  /*
  Configuration for the protocol converter: ProtocolConverterConfig(Config(1,64,32,true,true,false,true,true,true,true,true,false,0,0,0,0,0),Config(5,34,256,true,true,false,true,true,true,true,true,true,0,0,0,0,0),false)
  Configuration for the protocol converter: ProtocolConverterConfig(Config(3,64,256,true,true,false,true,true,true,true,true,false,0,0,0,0,0),Config(5,34,256,true,true,false,true,true,true,true,true,true,0,0,0,0,0),false)
  Configuration for the protocol converter: ProtocolConverterConfig(Config(3,64,256,true,true,false,true,true,true,true,true,false,0,0,0,0,0),Config(5,34,256,true,true,false,true,true,true,true,true,true,0,0,0,0,0),false)
  Configuration for the protocol converter: ProtocolConverterConfig(Config(3,64,256,true,true,false,true,true,true,true,true,false,0,0,0,0,0),Config(5,34,256,true,true,false,true,true,true,true,true,true,0,0,0,0,0),false)
  Configuration for the protocol converter: ProtocolConverterConfig(Config(3,64,256,true,true,false,true,true,true,true,true,false,0,0,0,0,0),Config(5,34,256,true,true,false,true,true,true,true,true,true,0,0,0,0,0),false)
  Configuration for the protocol converter: ProtocolConverterConfig(Config(3,64,256,true,true,false,true,true,true,true,true,false,0,0,0,0,0),Config(5,34,256,true,true,false,true,true,true,true,true,true,0,0,0,0,0),false)
  Configuration for the protocol converter: ProtocolConverterConfig(Config(3,64,256,true,true,false,true,true,true,true,true,false,0,0,0,0,0),Config(5,34,256,true,true,false,true,true,true,true,true,true,0,0,0,0,0),false)
  Configuration for the protocol converter: ProtocolConverterConfig(Config(3,64,256,true,true,false,true,true,true,true,true,false,0,0,0,0,0),Config(5,34,256,true,true,false,true,true,true,true,true,true,0,0,0,0,0),false)
  Configuration for the protocol converter: ProtocolConverterConfig(Config(3,64,256,true,true,false,true,true,true,true,true,false,0,0,0,0,0),Config(5,34,256,true,true,false,true,true,true,true,true,true,0,0,0,0,0),false)
  Configuration for the protocol converter: ProtocolConverterConfig(Config(1,64,256,true,true,false,true,true,true,true,true,false,0,0,0,0,0),Config(5,34,256,true,true,false,true,true,true,true,true,true,0,0,0,0,0),false)
  Configuration for the protocol converter: ProtocolConverterConfig(Config(2,64,256,true,true,false,true,true,true,true,true,false,0,0,0,0,0),Config(5,34,256,true,true,false,true,true,true,true,true,true,0,0,0,0,0),false)
  Configuration for the protocol converter: ProtocolConverterConfig(Config(1,64,256,true,true,false,true,true,true,true,true,false,0,0,0,0,0),Config(5,34,256,true,true,false,true,true,true,true,true,true,0,0,0,0,0),false)
  Configuration for the protocol converter: ProtocolConverterConfig(Config(2,64,256,true,true,false,true,true,true,true,true,false,0,0,0,0,0),Config(5,34,256,true,true,false,true,true,true,true,true,true,0,0,0,0,0),false)
  Configuration for the protocol converter: ProtocolConverterConfig(Config(4,64,512,true,true,false,true,true,true,true,true,false,0,0,0,0,0),Config(5,34,256,true,true,false,true,true,true,true,true,true,0,0,0,0,0),false)
   */

  val cfg1 = ProtocolConverterConfig(
    axi4.Config(1, 32, 32),
    axi4.Config(3, 32, 64),
    false
  )
  emitVerilog(new ProtocolConverter(cfg1))
}
