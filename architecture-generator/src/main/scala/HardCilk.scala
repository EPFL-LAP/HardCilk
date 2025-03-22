package HardCilk

import chisel3._

import Scheduler._
import Allocator._
import ArgumentNotifier._
import Descriptors._
import TclResources._
import HLSHelpers._
import SoftwareUtil._

import chext.amba.axi4
import axi4.Ops._
import axi4.lite.components._
import chict.ict_segm._

import io.circe.syntax._
import io.circe.generic.auto._
import scala.collection.mutable.ArrayBuffer
import chisel3.util.log2Ceil
import AXIHelpers._

import chext.amba.axi4s
import axi4s.Casts._
import chext.elastic

import axi4.lite.components.RegisterBlock


class HardCilk(
    fullSysGenDescriptor: FullSysGenDescriptor,
    outputDirPathRTL: String,
    debug: Boolean,
    reduceAxi: Int,
    unitedHbm: Boolean,
    isSimulation: Boolean,
    argumentNotifierCutCount: Int,
    fpgaIndex: Int = 0
) extends Module {

  val axiOuts = scala.collection.mutable.ArrayBuffer[(axi4.RawInterface, chext.amba.axi4.Config)]()
  val axiXDMA = scala.collection.mutable.ArrayBuffer[(axi4.RawInterface, chext.amba.axi4.Config)]()
  val interfacesAxiControl = scala.collection.mutable.ArrayBuffer[(axi4.RawInterface, chext.amba.axi4.Config)]()
  val interfacesAxiManagement = scala.collection.mutable.ArrayBuffer[(axi4.RawInterface, chext.amba.axi4.Config)]()

  val m_axis_buffer = scala.collection.mutable.ArrayBuffer[(axi4s.Interface, chext.amba.axi4s.Config)]()
  val s_axis_buffer = scala.collection.mutable.ArrayBuffer[(axi4s.Interface, chext.amba.axi4s.Config)]()

  val paused = IO(Output(Bool())).suggestName("paused")
  val done = IO(Output(Bool())).suggestName("done")
  var numHbmPortExports = reduceAxi
  val interfaceBuffer = new ArrayBuffer[hdlinfo.Interface]()

  override def desiredName: String =
    if (fullSysGenDescriptor.name.isEmpty) f"fullSysGen_${fpgaIndex}"
    else fullSysGenDescriptor.name + f"_${fpgaIndex}"

  private def initialize(): Unit = {
    // create a modifiable seq to carry the Scheduler, ClosureAllocator, and ArgumentNotifier
    val schedulerMap = scala.collection.mutable.Map[String, Scheduler]()
    val closureAllocatorMap = scala.collection.mutable.Map[String, Allocator]()
    val memoryAllocatorMap = scala.collection.mutable.Map[String, Allocator]()
    val argumentNotifierMap = scala.collection.mutable.Map[String, ArgumentNotifier]()
    val peMap = scala.collection.mutable.Map[String, Seq[HLSHelpers.VitisWriteBufferModule]]()


    val registerBlockSize = 6
    val numMasters = fullSysGenDescriptor.getNumConfigPorts()
    val axiCfgCtrl = axi4.Config(wAddr = numMasters + registerBlockSize, wData = 64, lite = true)

    // This demux is for all the management interfaces
    val demux = Module(
      new axi4.lite.components.Demux(new DemuxConfig(axiCfgCtrl, numMasters, (x: UInt) => (x >> registerBlockSize.U)))
    )

    val mfpgaFlag = fullSysGenDescriptor.fpgaCount > 1
    

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Register File To identify the FPGA
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      val regBlock = new RegisterBlock(wAddr = 6, wData = 64, wMask = 6)
      val fpgaCountInputReg = RegInit(0.U(64.W))
      val fpgaIndexInputReg = RegInit(0.U(64.W))
      
      regBlock.base(0x00)
      regBlock.reg(fpgaCountInputReg, read = true, write = true, desc = "Register To Identify The Number of FPGAs in The System")
      regBlock.reg(fpgaIndexInputReg, read = true, write = true, desc = "Register To Identify The Index of The FPGA in The System")


      when(regBlock.rdReq) {
        regBlock.rdOk()
      }
      when(regBlock.wrReq) {
        regBlock.wrOk()
      }

      // Connect the register block to the the last index of the management demux
      demux.m_axil(numMasters - 1) :=> regBlock.s_axil
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // End Register File To identify the FPGA
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////



    // HBM Configuration
    val cfgAxi4HBM = axi4.Config(
      wId = 0,
      wAddr = 34,
      wData = 256,
      wUserAR = 0,
      wUserR = 0,
      wUserAW = 0,
      wUserW = 0,
      wUserB = 0
    )
    val cfgXDMA = axi4.Config(wId = 4, wAddr = 64, wData = 512)

    // connect the demux input to the input of the module
    val s_axil_mgmt = IO(axi4.Slave(axiCfgCtrl)).suggestName(f"s_axil_mgmt_hardcilk_${fpgaIndex}")
    s_axil_mgmt :=> demux.s_axil

    interfaceBuffer.addOne(
      hdlinfo.Interface(
       f"s_axil_mgmt_hardcilk_${fpgaIndex}",
        hdlinfo.InterfaceRole.slave,
        hdlinfo.InterfaceKind("axi4"),
        "clock",
        "reset",
        Map("config" -> hdlinfo.TypedObject(axiCfgCtrl))
      )
    )

    interfacesAxiManagement.addOne((s_axil_mgmt, axiCfgCtrl))

    val interfacesPE = new ArrayBuffer[axi4.full.Interface]()
    val interfacesScheduler = new ArrayBuffer[axi4.full.Interface]()
    val interfacesClosureAllocator = new ArrayBuffer[axi4.full.Interface]()
    val interfacesArgumentNotifier = new ArrayBuffer[axi4.full.Interface]()
    val interfacesMemoryAllocator = new ArrayBuffer[axi4.full.Interface]()
    var j = 0
    var indexing_for_pe_slave_axi = 0
    fullSysGenDescriptor.taskDescriptors.foreach { task =>
      //println(task.mgmtBaseAddresses)

      // Create the black boxes for the task PEs.
      val peArray = VitisModuleFactory(task, fullSysGenDescriptor)
      peMap += (task.name -> peArray)

      // Export the PEs m_axi_gmem and s_axi_control interfaces

      for (i <- 0 until task.numProcessingElements) {
        val pe = peArray(i)
        val peName = f"${task.name}_${i}_${fpgaIndex}"

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
          interfacesPE.addOne(pe.getPort("m_axi_gmem").asInstanceOf[axi4.RawInterface].asFull)
          val pes_axi_control = IO(chiselTypeOf(pe.getPort("s_axi_control").asInstanceOf[axi4.RawInterface]))
            .suggestName(f"task_s_axi_control_${indexing_for_pe_slave_axi}_${fpgaIndex}")
          
            //pes_axi_control.name

          interfaceBuffer.addOne(
            hdlinfo.Interface(
              f"task_s_axi_control_${indexing_for_pe_slave_axi}%02d_${fpgaIndex}",
              hdlinfo.InterfaceRole.slave,
              hdlinfo.InterfaceKind("axi4"),
              "clock",
              "reset",
              Map("config" -> hdlinfo.TypedObject(pes_axi_control.cfg))
            )
          )
          indexing_for_pe_slave_axi+=1

          pes_axi_control :=> pe.getPort("s_axi_control").asInstanceOf[axi4.RawInterface]
          interfacesAxiControl.addOne((pes_axi_control, pes_axi_control.cfg))
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
          peCountGlobalTaskIn = fullSysGenDescriptor.getPortCount("spawn", task.name),
          argRouteServersNumber = task.getNumServers("argumentNotifier"),
          virtualAddressServersNumber = task.getNumServers("scheduler"),
          pePortWidth = task.getPortWidth("scheduler"),
          peType = task.name,
          debug = debug,
          fpgaCount = fullSysGenDescriptor.fpgaCount,
          taskIndex = task.taskId,
          collectStats = true
        )
      ))

      // Export the AXI interface of the Scheduler
      interfacesScheduler.addAll(schedulerMap(task.name).io_internal.vss_axi_full)

      // Connect the Scheduler Management to the management demux
      for (i <- j until j + task.getNumServers("scheduler")) {
        demux.m_axil(i) :=> schedulerMap(task.name).io_internal.axi_mgmt_vss(i - j)
      }
      j += task.getNumServers("scheduler")

      if(mfpgaFlag){
        schedulerMap(task.name).fpgaCountInputReg.get := fpgaCountInputReg
        schedulerMap(task.name).fpgaIndexInputReg.get := fpgaIndexInputReg
      }

      if (fullSysGenDescriptor.getPortCount("spawnNext", task.name) > 0) {
        closureAllocatorMap += (task.name -> Module(
          new Allocator(
            addrWidth = fullSysGenDescriptor.widthAddress,
            peCount = fullSysGenDescriptor.getPortCount("spawnNext", task.name),
            vcasCount = task.getNumServers("allocator"),
            queueDepth = task.getCapacityPhysicalQueue("allocator"),
            pePortWidth = task.getPortWidth("allocator")
          )
        ))

        // Export the AXI interface of the ClosureAllocator
        interfacesClosureAllocator.addAll(closureAllocatorMap(task.name).io_internal.vcas_axi_full)

        // Connect the ClosureAllocator Management to the management demux
        for (i <- j until j + task.getNumServers("allocator")) {
          demux.m_axil(i) :=> closureAllocatorMap(task.name).io_internal.axi_mgmt_vcas(i - j)
        }
        j += task.getNumServers("allocator")
      }

      if(fullSysGenDescriptor.getPortCount("sendArgument", task.name) > 0) {
        argumentNotifierMap += (task.name -> Module(
          new ArgumentNotifier(
            addrWidth = fullSysGenDescriptor.widthAddress,
            taskWidth = task.widthTask,
            queueDepth = task.getCapacityPhysicalQueue("argumentNotifier"),
            peCount = fullSysGenDescriptor.getPortCount("sendArgument", task.name),
            argRouteServersNumber = task.getNumServers("argumentNotifier"),
            contCounterWidth = fullSysGenDescriptor.widthContCounter,
            pePortWidth = task.getPortWidth("argumentNotifier"),
            cutCount = argumentNotifierCutCount,
            fpgaCount = fullSysGenDescriptor.fpgaCount,
            taskIndex = task.taskId
          )
        ))

        // Export the AXI interface of the ArgumentNotifier
        interfacesArgumentNotifier.addAll(argumentNotifierMap(task.name).axi_full_argRoute)

        schedulerMap(task.name).connArgumentNotifier <> argumentNotifierMap(task.name).connStealNtw

        // Connect the ArgumentNotifier Management to the management demux
        if(mfpgaFlag){
          argumentNotifierMap(task.name).fpgaIndexInputReg.get := fpgaIndexInputReg
        }
      }

      if (fullSysGenDescriptor.getPortCount("mallocIn", task.name) > 0) {
        memoryAllocatorMap += (task.name -> Module(
          new Allocator(
            addrWidth = fullSysGenDescriptor.widthAddress,
            peCount = fullSysGenDescriptor.getPortCount("mallocIn", task.name),
            vcasCount = task.getNumServers("memoryAllocator"),
            queueDepth = task.getCapacityPhysicalQueue("memoryAllocator"),
            pePortWidth = task.getPortWidth("memoryAllocator")
          )
        ))

        // Export the AXI interface of the MemoryAllocator
        interfacesMemoryAllocator.addAll(memoryAllocatorMap(task.name).io_internal.vcas_axi_full)

        for (i <- j until j + task.getNumServers("memoryAllocator")) {
          demux.m_axil(i) :=> memoryAllocatorMap(task.name).io_internal.axi_mgmt_vcas(i - j)
        }
        j += task.getNumServers("memoryAllocator")
      }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // MULTIFPGA CONFIGURATION SENDING AXI4S FROME REMOTE
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      // Create an elastic arbiter for the multi FPGA configuration
      
      val axi4sCfg = axi4s.Config(wData = 512, wDest = 4)

      val m_axis_mFPGA = if(mfpgaFlag) Some(IO(axi4s.Master(axi4sCfg))) else None


      if(mfpgaFlag){
        m_axis_buffer.addOne((m_axis_mFPGA.get, axi4sCfg))
        interfaceBuffer.addOne(
            hdlinfo.Interface(
              f"m_axis_mFPGA_${fpgaIndex}",
              hdlinfo.InterfaceRole("sink"), // "sink" for slave
              hdlinfo.InterfaceKind("readyValid[axis4_mFPGA]"),  // TDEST
              "clock",
              "reset"
            )
          )
      }

      if(mfpgaFlag){
        // Slaves count is the number of tasks + the number of tasks that has argumentNotifier
        val slavesCount = fullSysGenDescriptor.taskDescriptors.length + fullSysGenDescriptor.taskDescriptors.count(task => task.getNumServers("argumentNotifier") > 0)

        val arbiterToMfpgaNetwork = Module(
          new elastic.BasicArbiter(chiselTypeOf(m_axis_mFPGA.get.asFull.bits), slavesCount, chooserFn = elastic.Chooser.rr)
        )

        arbiterToMfpgaNetwork.io.select.nodeq()
        when(arbiterToMfpgaNetwork.io.select.valid) {
          arbiterToMfpgaNetwork.io.select.deq()
        }

        // Connect the m_axis_mFPGA to the sink of the arbiter
        elastic.SourceBuffer(arbiterToMfpgaNetwork.io.sink) <> m_axis_mFPGA.get.asFull  
        
        // each source is coming from the schedulers of each task and the argumentNotifier of each task that has argumentNotifier
        val sources = schedulerMap.map(_._2.m_axis_remote.get.asFull).toSeq ++ argumentNotifierMap.map(_._2.m_axis_remote.get.asFull).toSeq

        // Connect the sources to the arbiter
        for (i <- 0 until slavesCount) {
          arbiterToMfpgaNetwork.io.sources(i) <> sources(i)
        }
      }


    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // END MULTIFPGA CONFIGURATION SENDING AXI4S FROME REMOTE
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // MULTIFPGA CONFIGURATION RECEIVING AXI4S FROME REMOTE
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      val s_axis_mFPGA = if(mfpgaFlag) Some(IO(axi4s.Slave(axi4sCfg)).suggestName(f"s_axis_mFPGA_${fpgaIndex}")) else None

      val m_axis_mFPGA_argServers = if(mfpgaFlag && argumentNotifierMap.size > 0) Some(IO(axi4s.Master(axi4sCfg))) else None
      val m_axis_mFPGA_schedulers = if(mfpgaFlag && argumentNotifierMap.size > 0) Some(IO(axi4s.Master(axi4sCfg))) else None


      if(mfpgaFlag){
        s_axis_buffer.addOne((s_axis_mFPGA.get, axi4sCfg))
        interfaceBuffer.addOne(
            hdlinfo.Interface(
              f"s_axis_mFPGA_${fpgaIndex}",
              hdlinfo.InterfaceRole("source"), // "sink" for slave
              hdlinfo.InterfaceKind("readyValid[axis4_mFPGA]"), 
              "clock",
              "reset"
            )
          )
      }

      if(mfpgaFlag){

        // If there are argument notifiers, connect them to remote
        if(argumentNotifierMap.size > 0){        
          // A demux to separate messages for the scheduler vs the argument servers
          new elastic.Fork(s_axis_mFPGA.get.asFull) {
            override def onFork(): Unit = {
              elastic.Demux(
                source = fork(in),
                sinks =  Seq(m_axis_mFPGA_schedulers.get.asFull, m_axis_mFPGA_argServers.get.asFull),
                // select checks if the upper width - 64 bits are all 1s
                select = fork(in.data(511, 64).andR)
              )
            }
          }

          // A demux to connect the m_axis_mFPGA_argServers to the correct ArgumentNotifier of the correct task ID
          new elastic.Fork(m_axis_mFPGA_argServers.get.asFull) {
            override def onFork(): Unit = {
              elastic.Demux(
                source = fork(in),
                sinks =  argumentNotifierMap.map(_._2.s_axis_remote.get.asFull).toSeq,
                // checks the upper 4 bits of the address (63:60) to select the correct ArgumentNotifier
                select = fork(in.data(63, 60))
              )
            }
          }

          // A demux to connect the m_axis_mFPGA_schedulers to the correct Scheduler of the correct task ID
          new elastic.Fork(m_axis_mFPGA_schedulers.get.asFull) {
            override def onFork(): Unit = {
              elastic.Demux(
                source = fork(in),
                sinks =  schedulerMap.map(_._2.s_axis_remote.get.asFull).toSeq,
                // checks the upper 4 bits of the address (511: 508) to select the correct Scheduler
                select = fork(in.data(511, 508)) // NOTE WE NEED TO EDIT th
              )
            }
          }
        } else {
          // There are only schedulers!
          // Create a sequence of the schedulers' s_axis_remote interfaces ordered by task ID
          val schedulers = schedulerMap.toSeq.sortBy(_._2.taskIndexV).map(_._2.s_axis_remote.get.asFull)
          new elastic.Fork(s_axis_mFPGA.get.asFull) {
            override def onFork(): Unit = {
              elastic.Demux(
                source = fork(in),
                sinks =  schedulers,
                // checks the upper 4 bits of the address (511: 508) to select the correct Scheduler
                select = fork(in.data(511, 508)) 
              )
            }
          }
        }
      }
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // END MULTIFPGA CONFIGURATION RECEIVING AXI4S FROME REMOTE
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
     

    // Connect paused and done signals: N.B: needs to be fixed to accomodate different notifcation approaches
    val schedulerPaused = if (schedulerMap.isEmpty) false.B else schedulerMap.map(_._2.io_paused).reduce(_ || _)
    val closureAllocatorPaused =
      if (closureAllocatorMap.isEmpty) false.B else closureAllocatorMap.map(_._2.io_paused).reduce(_ || _)
    val memoryAllocatorPaused =
      if (memoryAllocatorMap.isEmpty) false.B else memoryAllocatorMap.map(_._2.io_paused).reduce(_ || _)
    paused := schedulerPaused || closureAllocatorPaused || memoryAllocatorPaused
    // check if the argumentNotifier is empty, else done is false
    if(argumentNotifierMap.isEmpty) {
      done := false.B
    } else {
      done := argumentNotifierMap.map(_._2.io_export.done).reduce(_ || _)
    }
    
    //done := argumentNotifierMap.map(_._2.io_export.done).reduce(_ || _)

    // Connect the Interconnect
    val numHBMPorts = reduceAxi
    val log2k = 2
    val hbmSlaves = scala.collection.mutable.Map[Int, ArrayBuffer[axi4.full.Interface]]()
    for (i <- 0 until numHBMPorts) {
      hbmSlaves += (i -> new ArrayBuffer[axi4.full.Interface]())
    }

    val totalPorts =
      interfacesPE.length + interfacesMemoryAllocator.length + interfacesScheduler.length + interfacesClosureAllocator.length + interfacesArgumentNotifier.length

  
    val numPortsPerMux = math.ceil(totalPorts.toDouble / numHBMPorts.toDouble)
    val peMux = math.ceil(1.0 * interfacesPE.length / numPortsPerMux).toInt
    val serverMux = numHBMPorts - peMux

    interfacesPE.zipWithIndex
      .groupBy(x => (x._2.toDouble / (1.0 * interfacesPE.length / peMux)).toInt)
      .foreach(x => {
        hbmSlaves(x._1).addAll(x._2.map(_._1))
      })
    interfacesMemoryAllocator.zipWithIndex
      .groupBy(x => peMux + (x._2.toDouble / (1.0 * interfacesMemoryAllocator.length / serverMux)).toInt)
      .foreach(x => {
        hbmSlaves(x._1).addAll(x._2.map(_._1))
      })
    interfacesScheduler.zipWithIndex
      .groupBy(x => peMux + (x._2.toDouble / (1.0 * interfacesScheduler.length / serverMux)).toInt)
      .foreach(x => {
        hbmSlaves(x._1).addAll(x._2.map(_._1))
      })
    interfacesClosureAllocator.zipWithIndex
      .groupBy(x => peMux + (x._2.toDouble / (1.0 * interfacesClosureAllocator.length / serverMux)).toInt)
      .foreach(x => {
        hbmSlaves(x._1).addAll(x._2.map(_._1))
      })
    interfacesArgumentNotifier.zipWithIndex
      .groupBy(x => peMux + (x._2.toDouble / (1.0 * interfacesArgumentNotifier.length / serverMux)).toInt)
      .foreach(x => {
        hbmSlaves(x._1).addAll(x._2.map(_._1))
      })

    // if (!isSimulation) {
    val xdma_axi = IO(axi4.Slave(cfgXDMA)).suggestName(f"s_axi_xdma_${fpgaIndex}")
    hbmSlaves(numHBMPorts - 1).addOne(axi4.full.SlaveBuffer(xdma_axi.asFull, axi4.BufferConfig.all(8)))

    interfaceBuffer.addOne(
      hdlinfo.Interface(
        f"s_axi_xdma_${fpgaIndex}",
        hdlinfo.InterfaceRole.slave,
        hdlinfo.InterfaceKind("axi4"),
        "clock",
        "reset",
        Map("config" -> hdlinfo.TypedObject(cfgXDMA))
      )
    )

    axiXDMA.addOne((xdma_axi, cfgXDMA))
    // }

    def SlaveBuffer(axi: axi4.full.Interface) = axi4.full.SlaveBuffer(axi, axi4.BufferConfig.all(2))
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
                axiSlaveCfg = slavePort.cfg.copy(wUserAR = 0, wUserR = 0, wUserAW = 0, wUserW = 0, wUserB = 0),
                axiMasterCfg = muxPort.cfg
              )
            )
          )
          SlaveBuffer(SlaveBuffer(SlaveBuffer(AxiUserYanker(slavePort)))) :=> protocolConverter.s_axi
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
        val axiOut = IO(axi4.Master(protocolConverter.m_axi.cfg)).suggestName(f"m_axi_${i}%02d_${fpgaIndex}")

        SlaveBuffer(SlaveBuffer(SlaveBuffer(protocolConverter.m_axi))) :=> axiOut.asFull
        interfaceBuffer.addOne(
          hdlinfo.Interface(
            f"m_axi_${i}%02d_${fpgaIndex}",
            hdlinfo.InterfaceRole.master,
            hdlinfo.InterfaceKind("axi4"),
            "clock",
            "reset",
            Map("config" -> hdlinfo.TypedObject(axiOut.cfg))
          )
        )
        axiOuts.addOne((axiOut, axiOut.cfg))
      }
    } else {
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
                  axiSlaveCfg = slavePort.cfg.copy(wUserAR = 0, wUserR = 0, wUserAW = 0, wUserW = 0, wUserB = 0),
                  axiMasterCfg = muxPort.cfg
                )
              )
            )
            axi4.full.SlaveBuffer(AxiUserYanker(slavePort), axi4.BufferConfig.all(8)) :=> protocolConverter.s_axi
            protocolConverter.m_axi :=> muxPort
          }

          val axiOut = IO(axi4.Master(mux.m_axi.cfg)).suggestName(f"m_axi_${i}%02d_${fpgaIndex}")
          mux.m_axi :=> axiOut.asFull
          interfaceBuffer.addOne(
            hdlinfo.Interface(
              f"m_axi_${i}%02d_${fpgaIndex}",
              hdlinfo.InterfaceRole.master,
              hdlinfo.InterfaceKind("axi4"),
              "clock",
              "reset",
              Map("config" -> hdlinfo.TypedObject(axiOut.cfg))
            )
          )
          axiOuts.addOne((axiOut, axiOut.cfg))
        }
      }
    }

    // Connet the PEs to the system based on the connection descriptor
    val systemConnectionsDescriptor = fullSysGenDescriptor.getSystemConnectionsDescriptor()

    for (connection <- systemConnectionsDescriptor.connections) {
      try {
        val physicalSourcePort = connection.srcPort.parentType match {
          case "HardCilk" => {
            connection.srcPort.portType match {
              case "taskIn" | "taskOut" | "taskInGlobal" =>
                schedulerMap(connection.srcPort.parentName).io_export
                  .getPort(connection.srcPort.portType, connection.srcPort.portIndex)
              case "closureOut" =>
                closureAllocatorMap(connection.srcPort.parentName).io_export
                  .getPort(connection.srcPort.portType, connection.srcPort.portIndex)
              case "mallocOut" =>
                memoryAllocatorMap(connection.srcPort.parentName).io_export
                  .getPort(connection.srcPort.portType, connection.srcPort.portIndex)
              case "argIn" =>
                argumentNotifierMap(connection.srcPort.parentName).io_export
                  .getPort(connection.srcPort.portType, connection.srcPort.portIndex)
            }
          }
          case "PE" => {
            peMap(connection.srcPort.parentName)(connection.srcPort.parentIndex).getPort(connection.srcPort.portType)
          }
        }

        val physicalDestinationPort = connection.dstPort.parentType match {
          case "HardCilk" => {
            connection.dstPort.portType match {
              case "taskIn" | "taskOut" | "taskInGlobal" =>
                schedulerMap(connection.dstPort.parentName).io_export
                  .getPort(connection.dstPort.portType, connection.dstPort.portIndex)
              case "closureOut" =>
                closureAllocatorMap(connection.dstPort.parentName).io_export
                  .getPort(connection.dstPort.portType, connection.dstPort.portIndex)
              case "mallocOut" =>
                memoryAllocatorMap(connection.dstPort.parentName).io_export
                  .getPort(connection.dstPort.portType, connection.dstPort.portIndex)
              case "argIn" =>
                argumentNotifierMap(connection.dstPort.parentName).io_export
                  .getPort(connection.dstPort.portType, connection.dstPort.portIndex)
            }
          }
          case "PE" => {
            peMap(connection.dstPort.parentName)(connection.dstPort.parentIndex).getPort(connection.dstPort.portType)
          }
        }

        physicalSourcePort.asInstanceOf[axi4s.Interface].asLite <> elastic.SinkBuffer(physicalDestinationPort.asInstanceOf[axi4s.Interface].asLite)

      } catch {
        case e: Exception => {
          println(e)
        }
      }
    }

    // lazy val hdlinfoModule: hdlinfo.Module = {
    //   import hdlinfo._
    //   Module(
    //     fullSysGenDescriptor.name,
    //     Seq(
    //       Port(
    //         "clock",
    //         PortDirection.input,
    //         PortKind.clock
    //       ),
    //       Port(
    //         "reset",
    //         PortDirection.input,
    //         PortKind.reset,
    //         PortSensitivity.resetActiveHigh,
    //         associatedClock = "clock"
    //       ),
    //       Port(
    //         "paused",
    //         PortDirection.output,
    //         PortKind.data
    //       ),
    //       Port(
    //         "done",
    //         PortDirection.output,
    //         PortKind.data
    //       )
    //     ),
    //     interfaceBuffer.toSeq
    //   )
    // }
    // val write = new java.io.PrintWriter(f"${outputDirPathRTL}/${fullSysGenDescriptor.name}.hdlinfo.json")
    // write.write(hdlinfoModule.asJson.toString())
    // write.close()
  }

  initialize()
}

// object HardCilkEmitter extends App {
//   import _root_.circt.stage.ChiselStage
//   import java.time.format.DateTimeFormatter
//   import scopt.OParser

//   // Handling argument parsing
//   case class BuilderConfig(
//       val debug: Boolean = false,
//       val reduce_axi: Int = 32,
//       val timestamped: Boolean = false,
//       val cpp_header_generation: Boolean = false,
//       val tcl_generation: Boolean = false,
//       val rtl_generation: Boolean = false,
//       val sc_header_generation: Boolean = false,
//       val project_sc_generation: Boolean = false,
//       val output_dir: String = ".",
//       val json_path: String = ""
//   )

//   val builder = OParser.builder[BuilderConfig]
//   val parser = {
//     import builder._
//     OParser.sequence(
//       programName("HardCilk"),
//       head("HardCilk", "0.1"),
//       arg[String]("<json-path>")
//         .required()
//         .action((x, c) => c.copy(json_path = x))
//         .text("path of a JSON descriptor for the HardCilk system"),
//       opt[String]('o', "output-dir")
//         .action((x, c) => c.copy(output_dir = x))
//         .text("output directory"),
//       opt[Unit]('d', "debug")
//         .action((_, c) => c.copy(debug = true))
//         .text("enable debug hardware counters and simulation logging"),
//       opt[Int]('r', "reduce-axi")
//         .action((x, c) => c.copy(reduce_axi = x))
//         .text("enable AXI port reduction to HBM capacity"),
//       opt[Unit]('t', "timestamped")
//         .action((_, c) => c.copy(timestamped = true))
//         .text("generate output in a timestamped folder"),
//       opt[Unit]('g', "rtl-generation")
//         .action((_, c) => c.copy(rtl_generation = true))
//         .text("Generates the RTL output of the HardCilk"),
//       opt[Unit]('c', "cpp-headers")
//         .action((_, c) => c.copy(cpp_header_generation = true))
//         .text("Generates the C++ headers needed for the driver"),
//       opt[Unit]('b', "tcl-scripts")
//         .action((_, c) => c.copy(tcl_generation = true))
//         .text("Generates the TCL output of the HardCilk for Vivado Block Design"),
//       opt[Unit]('s', "sc-headers")
//         .action((_, c) => c.copy(sc_header_generation = true))
//         .text("Generates the C++ header for SystemC simulation"),
//       opt[Unit]('p', "project-sc")
//         .action((_, c) => c.copy(project_sc_generation = true))
//         .text("Generates the C++ project for SystemC simulation"),
//       opt[Unit]('a', "all")
//         .action((_, c) =>
//           c.copy(
//             cpp_header_generation = true,
//             rtl_generation = true,
//             tcl_generation = true,
//             sc_header_generation = true,
//             project_sc_generation = true
//           )
//         )
//         .text("Generates all outputs for HardCilk, equivilant to using `-g -c -b -s` flags"),
//       help("help").text("Prints this help text")
//     )
//   }

  // // Helpers
  // def readFile(path: String): String = {
  //   import java.nio.charset.StandardCharsets
  //   import java.nio.file.{Files, Path}
  //   Files.readString(Path.of(path), StandardCharsets.UTF_8)
  // }

  // def writeFile(path: String, data: String): Unit = {
  //   import java.nio.charset.StandardCharsets
  //   import java.nio.file.{Files, Path}
  //   Files.writeString(Path.of(path), data, StandardCharsets.UTF_8)
  // }

  // def basename(path: String): String = {
  //   path.split("/").last.split("\\.").head
  // }

  // def generateRTL(
  //     systemDescriptor: FullSysGenDescriptor,
  //     pathInputJsonFile: String,
  //     outputDirPathRTL: String,
  //     flags: BuilderConfig,
  //     isSimulation: Boolean
  // ): Int = {
  //   // for task in system descriptor copy all the files in the peHDLPath to the outputDirRTL
  //   systemDescriptor.taskDescriptors.foreach { task =>
  //     val peHDLPath = task.peHDLPath
  //     val peHDLPathFiles = new java.io.File(peHDLPath).listFiles()
  //     peHDLPathFiles.foreach { file =>
  //       val fileName = file.getName()
  //       val fileContent = readFile(file.getAbsolutePath())
  //       writeFile(s"$outputDirPathRTL/$fileName", fileContent)
  //     }
  //   }

  //   // Copy all the files in the src/main/resources/ to the outputDirRTL except the DualPortBRAM_sim.v
  //   val resourcesPath = "src/main/resources/"
  //   val synthDirectory = f"${outputDirPathRTL}/synth"
  //   val questaDirectory = f"${outputDirPathRTL}/questa"
  //   new java.io.File(synthDirectory).mkdirs()
  //   new java.io.File(questaDirectory).mkdirs()

  //   val resourcesFiles = new java.io.File(resourcesPath).listFiles()
  //   val listOfFilesForRTL = List("DualPortBRAM_sim.v", "DualPortBRAM_xpm.v", "top.v", "u55c.xdc")
  //   val listOfFilesForQuesta = List("top_sim.sv", "main_sim.sv")
  //   writeFile(s"$outputDirPathRTL/empty.vh", "")
  //   writeFile(s"$outputDirPathRTL/empty.sv", "")
  //   resourcesFiles.foreach { file =>
  //     val fileName = file.getName()
  //     val fileContent = readFile(file.getAbsolutePath())

  //     if (fileName.startsWith("DualPortBRAM")) {
  //       if ((isSimulation && fileName == "DualPortBRAM_sim.v") || (!isSimulation && fileName == "DualPortBRAM_xpm.v")) {
  //         writeFile(s"$outputDirPathRTL/DualPortBRAM.v", fileContent)
  //       }
  //     } else if (listOfFilesForQuesta.contains(fileName)) {
  //       writeFile(s"$questaDirectory/$fileName", fileContent)
  //     } else {
  //       writeFile(s"$synthDirectory/$fileName", fileContent)
  //     }
  //   }

  //   var numHbmPortExports = 0
  //   ChiselStage.emitSystemVerilogFile(
  //     {
  //       val module = new HardCilk(
  //         fullSysGenDescriptor = systemDescriptor,
  //         outputDirPathRTL = outputDirPathRTL,
  //         debug = flags.debug,
  //         reduceAxi = flags.reduce_axi,
  //         unitedHbm = true,
  //         isSimulation = isSimulation,
  //         argumentNotifierCutCount = 1
  //       )
  //       numHbmPortExports = module.numHbmPortExports
  //       module
  //     },
  //     Array(f"--target-dir=${outputDirPathRTL}"),
  //     Array("--disable-all-randomization")
  //   )

  //   // For the file in the outputDirRTL with the name of the systemDescriptor.name run sv2v on it using os.system, then remove the original file
  //   import sys.process._
  //   val svFilePath = s"$outputDirPathRTL/${systemDescriptor.name}.sv"
  //   val vFilePath = s"$outputDirPathRTL/${systemDescriptor.name}.v"

  //   // Check if the SystemVerilog file exists
  //   val svFile = new java.io.File(svFilePath)
  //   if (svFile.exists()) {
  //     val sv2vCommand = s"sv2v $svFilePath"
  //     // Get the ouput of the command instead of stdout
  //     val sv2vOutput = sv2vCommand.!!
  //     val rmCommand = s"rm $svFilePath"
  //     rmCommand.!

  //     // Write the output of sv2v to the verilog file
  //     writeFile(vFilePath, sv2vOutput)

  //   } else {
  //     println(s"Error: File $svFilePath does not exist.")
  //   }

  //   numHbmPortExports
  // }

  // Main body
//   OParser.parse(parser, args, BuilderConfig()) match {
//     case None =>
//       println(f"Incorrect usage, please run with `--help` option to get the usage help")
//     case Some(flags) => {
//       // Create a directory under the output directory with the name of the json file, date, and timestamp (nearest second)
//       val pathOutputDir = flags.output_dir
//       val pathInputJsonFile = flags.json_path
//       val jsonName = basename(pathInputJsonFile)
//       val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
//       val timeFormatter = DateTimeFormatter.ofPattern("HH-mm-ss")

//       val outputDirName =
//         if (flags.timestamped)
//           s"${jsonName}_${java.time.LocalDate.now.format(dateFormatter)}_${java.time.LocalTime.now.format(timeFormatter)}"
//         else
//           s"${jsonName}_hardcilk_output"

//       val outputDirPathRTL = s"$pathOutputDir/$outputDirName/rtl"
//       val outputDirPathSoftwareHeaders = s"$pathOutputDir/$outputDirName/softwareHeaders"
//       val outputDirPathTCL = s"$pathOutputDir/$outputDirName/tcl"
//       val outputDirPathSC = s"$pathOutputDir/$outputDirName/software"

//       // Create the sytem descriptors
//       val systemDescriptor = parseJsonFile[FullSysGenDescriptor](pathInputJsonFile)

//       // @TODO: Add a flag to delete the output directory
//       // if any of the flags is true, delete the output directory
//       import sys.process._
//       if (
//         flags.rtl_generation || flags.cpp_header_generation || flags.tcl_generation || flags.sc_header_generation || flags.project_sc_generation
//       ) {
//         val outputDir = new java.io.File(s"$pathOutputDir/$outputDirName")
//         if (outputDir.exists()) {
//           val deleteCommand = s"rm -r $outputDir"
//           deleteCommand.!
//         }
//       }

//       // Create the directories
//       var numHbmPortExports = flags.reduce_axi
//       if (flags.project_sc_generation) {
//         // Using java.nio copy a folder with all its content (files and subfolders) to another folder, source is "pwd/software_template" and destination is "outputDirPathSC"
//         val source = new java.io.File("software_template")
//         val destination = new java.io.File(outputDirPathSC)
//         java.nio.file.Files
//           .walk(source.toPath)
//           .forEach(sourcePath => {
//             val destinationPath = destination.toPath.resolve(source.toPath.relativize(sourcePath))
//             if (sourcePath.toFile.isDirectory) {
//               java.nio.file.Files.createDirectories(destinationPath)
//             } else {
//               java.nio.file.Files.copy(sourcePath, destinationPath)
//             }
//           })

//         // Rename `outputDirPathSC/projects/project_template` to `outputDirPathSC/projects/${jsonName}`
//         val projectTemplate = new java.io.File(s"$outputDirPathSC/projects/project_template")
//         val projectDestination = new java.io.File(s"$outputDirPathSC/projects/${jsonName}")
//         projectTemplate.renameTo(projectDestination)

//         // Generate the HDL in the `outputDirPathSC/projects/${jsonName}/hdl`
//         new java.io.File(s"$outputDirPathSC/projects/${jsonName}/hdl").mkdirs()
//         numHbmPortExports = generateRTL(systemDescriptor, pathInputJsonFile, s"$outputDirPathSC/projects/${jsonName}/hdl", flags, true)

//         // Generate the SystemC project in the `outputDirPathSC/project/${jsonName}/include`
//         new java.io.File(s"$outputDirPathSC/projects/${jsonName}/include").mkdirs()
//         CppHeaderTemplate.generateCppHeader(
//           systemDescriptor,
//           s"$outputDirPathSC/projects/${jsonName}/include",
//           numHbmPortExports
//         )

//         // Generate the SystemC testbench in the `outputDirPathSC/projects/${jsonName}/include`
//         TestBenchHeaderTemplate.generateCppHeader(
//           systemDescriptor,
//           s"$outputDirPathSC/projects/${jsonName}/include",
//           numHbmPortExports
//         )

//         // Read the `outputDirPathSC/projects/${jsonName}/CMakeLists.txt` and replace the `${project_template}` with the `${jsonName}`
//         val cmakeListsPath = s"$outputDirPathSC/projects/${jsonName}/CMakeLists.txt"
//         val cmakeListsContent = readFile(cmakeListsPath)
//         val newCmakeListsContent = cmakeListsContent.replace("${project_template}", jsonName)
//         writeFile(cmakeListsPath, newCmakeListsContent)

//         // Also copy `../software/${jsonName}` to `outputDirPathSC/projects/${jsonName}`
//         val sourceProject = new java.io.File(s"../software/${jsonName}")
//         java.nio.file.Files
//           .walk(sourceProject.toPath)
//           .forEach(sourcePath => {
//             val destinationPath = projectDestination.toPath.resolve(sourceProject.toPath.relativize(sourcePath))
//             if (sourcePath.toFile.isDirectory) {
//               java.nio.file.Files.createDirectories(destinationPath)
//             } else {
//               java.nio.file.Files.copy(sourcePath, destinationPath)
//             }
//           })
//       }
//       if (flags.rtl_generation) {
//         new java.io.File(outputDirPathRTL).mkdirs()
//         numHbmPortExports = generateRTL(systemDescriptor, pathInputJsonFile, outputDirPathRTL, flags, false)
//         dumpJsonFile[FullSysGenDescriptorExtended](
//           s"$outputDirPathRTL/${systemDescriptor.name}_descriptor_2.json",
//           FullSysGenDescriptorExtended.fromFullSysGenDescriptor(systemDescriptor)
//         )
//       }
//       if (flags.cpp_header_generation || flags.sc_header_generation) {
//         new java.io.File(outputDirPathSoftwareHeaders).mkdirs()
//         if (flags.cpp_header_generation)
//           CppHeaderTemplate.generateCppHeader(systemDescriptor, outputDirPathSoftwareHeaders, numHbmPortExports)
//         if (flags.sc_header_generation)
//           TestBenchHeaderTemplate.generateCppHeader(systemDescriptor, outputDirPathSoftwareHeaders, numHbmPortExports)
//       }
//       if (flags.tcl_generation) {
//         new java.io.File(outputDirPathTCL).mkdirs()
//         TclGeneratorMemPEs.generate(systemDescriptor, outputDirPathTCL, numHbmPortExports)
//         TclQuestaSim.generate(systemDescriptor, outputDirPathTCL, numHbmPortExports)
//       }
//     }
//   }
// }

// object MyAppEntry extends App {
//   HardCilkEmitter.main(Array[String]("taskDescriptors/paper_exp1.json", "-o", "output", "-r", "32", "-a"))
// }
