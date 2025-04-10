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
import Util.WriteBuffer
import Util.WriteBufferConfig
import chisel3.util.Irrevocable
import chisel3.util.IrrevocableIO

class PE(){
  val peElements = scala.collection.mutable.Map[String, (axi4s.Interface, chext.amba.axi4s.Config, String)]()
}

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
  val interfacesAxiManagement = scala.collection.mutable.ArrayBuffer[(axi4.RawInterface, chext.amba.axi4.Config)]()

  val m_axis_buffer = scala.collection.mutable.ArrayBuffer[(axi4s.Interface, chext.amba.axi4s.Config)]()
  val s_axis_buffer = scala.collection.mutable.ArrayBuffer[(axi4s.Interface, chext.amba.axi4s.Config)]()

  // Create a buffer that represents a PE
  
  // Create a buffer that maps a string (task type) to an array of PEs
  val peMap = scala.collection.mutable.Map[String, ArrayBuffer[PE]]()

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

    var memory_interface_index = 0
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


    val interfacesScheduler = new ArrayBuffer[axi4.full.Interface]()
    val interfacesClosureAllocator = new ArrayBuffer[axi4.full.Interface]()
    val interfacesArgumentNotifier = new ArrayBuffer[axi4.full.Interface]()
    val interfacesMemoryAllocator = new ArrayBuffer[axi4.full.Interface]()
    
    var j = 0
    fullSysGenDescriptor.taskDescriptors.foreach { task =>

      // Create an entry in the PE map for the task
      if (!peMap.contains(task.name)) {
        peMap += (task.name -> ArrayBuffer[PE]())
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
          collectStats = true,
          spawnerServerNumber = task.spawnServersCount,
          tasksMoveCount = fullSysGenDescriptor.tasksMoveCount
        )
      ))

      // for each index of the processing elements, add an entry to the PE map
      for (i <- 0 until task.numProcessingElements) {
        peMap(task.name) += new PE()
      }

      // Expose the taskOut port of the scheduler to the outside
      for (i <- 0 until task.numProcessingElements) {
        val cfg = schedulerMap(task.name).io_export.getPort("taskOut", i).cfg
        val expose_port = IO(axi4s.Master(cfg)).suggestName(f"taskOut_${i}_${task.name}_${fpgaIndex}")
        expose_port.asLite <> elastic.SourceBuffer(schedulerMap(task.name).io_export.getPort("taskOut", i).asLite)
        peMap(task.name)(i).peElements += (f"taskOut_${i}_${task.name}_${fpgaIndex}" -> (expose_port, cfg, "Master"))
      }

      // Expose the taskIn port if the task spawnsItself and the task type has no spawnNext port
      // Create an array of arbiters equal to the number of the processing elements
      val arbiterArray = ArrayBuffer[elastic.BasicArbiter[Bits]]()

      if (fullSysGenDescriptor.selfSpawnedCount(task.name) > 0 && fullSysGenDescriptor.getPortCount("spawnNext", task.name) > 0) {
        for (i <- 0 until task.numProcessingElements) {
          
          arbiterArray.addOne(
            Module(
              new elastic.BasicArbiter(chiselTypeOf(schedulerMap(task.name).io_export.getPort("taskIn", i).asLite.bits), 2, elastic.Chooser.rr)
            )
          )


          arbiterArray(i).io.sink <> schedulerMap(task.name).io_export.getPort("taskIn", i).asLite

          arbiterArray(i).io.select.nodeq()
          when(arbiterArray(i).io.select.valid) {
            arbiterArray(i).io.select.deq()
          }
        }
      }

      if (fullSysGenDescriptor.selfSpawnedCount(task.name) > 0 && fullSysGenDescriptor.getPortCount("spawnNext", task.name) == 0) {
        for (i <- 0 until task.numProcessingElements) {
          val cfg = schedulerMap(task.name).io_export.getPort("taskIn", i).cfg
          val expose_port = IO(axi4s.Slave(cfg)).suggestName(f"taskIn_${i}_${task.name}_${fpgaIndex}")
          elastic.SinkBuffer(schedulerMap(task.name).io_export.getPort("taskIn", i).asLite) <> expose_port.asLite 
          peMap(task.name)(i).peElements += (f"taskIn_${i}_${task.name}_${fpgaIndex}" -> (expose_port, cfg, "Slave"))
        } 
      } else if(fullSysGenDescriptor.selfSpawnedCount(task.name) > 0 ){
        for (i <- 0 until task.numProcessingElements) {
          val cfg = schedulerMap(task.name).io_export.getPort("taskIn", i).cfg

          val expose_port = IO(axi4s.Slave(cfg)).suggestName(f"taskIn_${i}_${task.name}_${fpgaIndex}")
          arbiterArray(i).io.sources(0) <> expose_port.asLite
          peMap(task.name)(i).peElements += (f"taskIn_${i}_${task.name}_${fpgaIndex}" -> (expose_port, cfg, "Slave"))
        }
      }

      
      // check the fullSysGenDescriptor spawnList if the task exists in the spawn list of any other task
      val taskInGlobalExist = fullSysGenDescriptor.spawnList.exists(spawn => (spawn._1 != task.name && spawn._2.contains(task.name)))
      val taskNameSpawning = fullSysGenDescriptor.spawnList.filter(spawn => spawn._2.contains(task.name)).map(_._1)
      if(taskInGlobalExist){
        val numberOfPEsOfSpawningTask = fullSysGenDescriptor.taskDescriptors.filter(task => task.name == taskNameSpawning.head).head.numProcessingElements
        for(i <- 0 until numberOfPEsOfSpawningTask){
          val cfg = schedulerMap(task.name).io_export.getPort("taskInGlobal", i).cfg
          val expose_port = IO(axi4s.Slave(cfg)).suggestName(f"taskInGlobal_${i}_${task.name}_${fpgaIndex}")
          elastic.SinkBuffer(schedulerMap(task.name).io_export.getPort("taskInGlobal", i).asLite) <> expose_port.asLite 
          peMap(task.name)(i).peElements += (f"taskInGlobal_${i}_${task.name}_${fpgaIndex}" -> (expose_port, cfg, "Slave"))
        }
      }


      // Export the AXI interface of the Scheduler
      interfacesScheduler.addAll(schedulerMap(task.name).io_internal.vss_axi_full)

      if(schedulerMap(task.name).spawnerServerAXI != None){
        interfacesScheduler.addAll(schedulerMap(task.name).spawnerServerAXI.get)
      }

      // Connect the Scheduler Management to the management demux
      for (i <- j until j + task.getNumServers("scheduler")) {
        demux.m_axil(i) :=> schedulerMap(task.name).io_internal.axi_mgmt_vss(i - j)
      }
      j += task.getNumServers("scheduler")

      // Connect the taskSpawner from the Schedueler to the management demux
      if(schedulerMap(task.name).spawnerServerMgmt != None){

        for(i <- j until j + task.spawnServersCount){
          demux.m_axil(i) :=> schedulerMap(task.name).spawnerServerMgmt.get(i - j)
        }

        j += task.spawnServersCount
      }

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

        // Expose the closureOut port of the ClosureAllocator to the outside
        for (i <- 0 until task.numProcessingElements) {
          val cfg = closureAllocatorMap(task.name).io_export.getPort("closureOut", i).cfg
          val expose_port = IO(axi4s.Master(cfg)).suggestName(f"closureOut_${i}_${task.name}_${fpgaIndex}")
          expose_port.asLite <> elastic.SourceBuffer(closureAllocatorMap(task.name).io_export.getPort("closureOut", i).asLite)
          peMap(task.name)(i).peElements += (f"closureOut_${i}_${task.name}_${fpgaIndex}" -> (expose_port, cfg, "Master"))
        }


        // Export the AXI interface of the ClosureAllocator
        interfacesClosureAllocator.addAll(closureAllocatorMap(task.name).io_internal.vcas_axi_full)

        // Connect the ClosureAllocator Management to the management demux
        for (i <- j until j + task.getNumServers("allocator")) {
          demux.m_axil(i) :=> closureAllocatorMap(task.name).io_internal.axi_mgmt_vcas(i - j)
        }
        j += task.getNumServers("allocator")

        // Create a writeBuffer for the ClosureAllocator for each PE
        for (i <- 0 until task.numProcessingElements) {
          val mWriteBuffer = Module(
            new WriteBuffer(
              new WriteBufferConfig(
                wAddr = fullSysGenDescriptor.widthAddress,
                wData = task.widthTask,
                wAllow = 32,
                wAllowData = Seq(task.widthTask)
              )
            )
          )

          // Connect the write buffer to the spawnNext port of the PE (export the port to the outside)
          val cfg_s_pkg = mWriteBuffer.s_pkg.cfg
          val expose_port_s_pkg = IO(axi4s.Slave(cfg_s_pkg)).suggestName(f"spawnNext_${i}_${task.name}_${fpgaIndex}")
          expose_port_s_pkg.asLite <> elastic.SinkBuffer(mWriteBuffer.s_pkg.asLite)
          
          // Add the expose port to the peMap
          peMap(task.name)(i).peElements += (f"spawnNext_${i}_${task.name}_${fpgaIndex}" -> (expose_port_s_pkg, cfg_s_pkg, "Slave"))

          // pass through the write buffer to the spawn port of the scheduler
          mWriteBuffer.m_allows.head.asLite <> arbiterArray(i).io.sources(1)
          //mWriteBuffer.m_allows.head  <> schedulerMap(task.name).io_export.getPort("taskIn", i)

          // export the s_allows of the write buffer as taskIn port of the PE
          val cfg = mWriteBuffer.s_allows.head.cfg
          val expose_port = IO(axi4s.Slave(cfg)).suggestName(f"taskInSynced_${i}_${task.name}_${fpgaIndex}")
          expose_port.asLite <> elastic.SinkBuffer(mWriteBuffer.s_allows.head.asLite)
          peMap(task.name)(i).peElements += (f"taskInSynced_${i}_${task.name}_${fpgaIndex}" -> (expose_port, cfg, "Slave"))

          
          // export the axi interface of the write buffer

          val port = IO(axi4.Master(mWriteBuffer.m_axi.cfg)).suggestName(f"axi4_master_${memory_interface_index}_${fpgaIndex}")
          mWriteBuffer.m_axi :=> port.asFull
          axiOuts.addOne((port, port.cfg))


          // add it to the hdlinfo interface buffer
          interfaceBuffer.addOne(
            hdlinfo.Interface(
              f"m_axi_${memory_interface_index}%02d_${fpgaIndex}",
              hdlinfo.InterfaceRole("master"), // "sink" for slave
              hdlinfo.InterfaceKind("axi4"), 
              "clock",
              "reset",
              Map("config" -> hdlinfo.TypedObject(mWriteBuffer.m_axi.cfg))
            )
          )
          memory_interface_index += 1

        }
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

        // Expose the argIn port of the ArgumentNotifier to the outside
        for (i <- 0 until task.numProcessingElements) {
          val cfg = argumentNotifierMap(task.name).io_export.getPort("argIn", i).cfg
          val expose_port = IO(axi4s.Slave(cfg)).suggestName(f"argIn_${i}_${task.name}_${fpgaIndex}")
          elastic.SinkBuffer(argumentNotifierMap(task.name).io_export.getPort("argIn", i).asLite) <> expose_port.asLite
          peMap(task.name)(i).peElements += (f"argIn_${i}_${task.name}_${fpgaIndex}" -> (expose_port, cfg, "Slave"))
        }

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

      //val s_axis_mFPGA_argServers = if(mfpgaFlag && argumentNotifierMap.size > 0) Some(IO(axi4s.Master(axi4sCfg))) else None
      //val s_axis_mFPGA_schedulers = if(mfpgaFlag && argumentNotifierMap.size > 0) Some(IO(axi4s.Master(axi4sCfg))) else None

      // slave --> receives data, TVALID, TDATA are driven outside. TREADY is driven inside.
      // DEMUX --> tries to drive TVALID and TDATA. That is not possible.


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

          val s_axis_mFPGA_argServers = if(mfpgaFlag) Some(Wire(Irrevocable(chiselTypeOf(s_axis_mFPGA.get.asFull.bits)))) else None
          val s_axis_mFPGA_schedulers = if(mfpgaFlag) Some(Wire(Irrevocable(chiselTypeOf(s_axis_mFPGA.get.asFull.bits)))) else None

          // A demux to separate messages for the scheduler vs the argument servers
          new elastic.Fork(s_axis_mFPGA.get.asFull) {
            override def onFork(): Unit = {
              elastic.Demux(
                source = fork(in),
                sinks =  // Seq(s_axis_mFPGA_schedulers.get.asFull, s_axis_mFPGA_argServers.get.asFull),
                  Seq(s_axis_mFPGA_schedulers.get, s_axis_mFPGA_argServers.get),
                // select checks the 14th bit from the top, if zaero is scheduler else is argument server
                select = fork(in.data(498, 498))
              )
            }
          }

          // A demux to connect the m_axis_mFPGA_argServers to the correct ArgumentNotifier of the correct task ID
          new elastic.Fork(s_axis_mFPGA_argServers.get) {
            override def onFork(): Unit = {
              elastic.Demux(
                source = fork(in),
                sinks =  argumentNotifierMap.map(_._2.s_axis_remote.get.asFull).toSeq,
                // checks the upper 4 bits of the address (63:60) to select the correct ArgumentNotifier (taskId based)
                select = fork(in.data(63, 60))
              )
            }
          }

          // A demux to connect the m_axis_mFPGA_schedulers to the correct Scheduler of the correct task ID
          val schedulers = schedulerMap.toSeq.sortBy(_._2.taskIndexV).map(_._2.s_axis_remote.get.asFull)

          new elastic.Fork(elastic.SourceBuffer(s_axis_mFPGA_schedulers.get)) {
            override def onFork(): Unit = {
              elastic.Demux(
                source = fork(in),
                sinks =  schedulers,
                // checks the upper 4 bits of the address (511: 508) to select the correct Scheduler
                select = fork(in.data(511, 508)) // NOTE WE NEED TO EDIT th
              )
            }
          }
        } else {
          // There are only schedulers!
          // Create a sequence of the schedulers' s_axis_remote interfaces ordered by task ID
          val schedulers = schedulerMap.toSeq.sortBy(_._2.taskIndexV).map(_._2.s_axis_remote.get.asFull)
          new elastic.Fork(elastic.SourceBuffer(s_axis_mFPGA.get.asFull)) {
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
     

    // Directly export the memory of the servers to TLM
    
    interfacesScheduler.forall{
      case (interface) => {
        val port = IO(axi4.Master(interface.cfg)).suggestName(f"axi4_master_${memory_interface_index}_${fpgaIndex}")
        interface :=> port.asFull 
        axiOuts.addOne((port, interface.cfg))
        
        interfaceBuffer.addOne(
          hdlinfo.Interface(
            f"m_axi_${memory_interface_index}%02d_${fpgaIndex}",
            hdlinfo.InterfaceRole("master"), // "sink" for slave
            hdlinfo.InterfaceKind("axi4"), 
            "clock",
            "reset",
            Map("config" -> hdlinfo.TypedObject(interface.cfg))
          )
        )
        memory_interface_index += 1
        true
      }
    }

    interfacesClosureAllocator.forall{
      case (interface) => {
        val port = IO(axi4.Master(interface.cfg)).suggestName(f"axi4_master_${memory_interface_index}_${fpgaIndex}")
        interface :=> port.asFull
        axiOuts.addOne((port, interface.cfg))
        
        interfaceBuffer.addOne(
          hdlinfo.Interface(
            f"m_axi_${memory_interface_index}%02d_${fpgaIndex}",
            hdlinfo.InterfaceRole("master"), // "sink" for slave
            hdlinfo.InterfaceKind("axi4"), 
            "clock",
            "reset",
            Map("config" -> hdlinfo.TypedObject(interface.cfg))
          )
        )
        memory_interface_index += 1
        true
      }
    }

    interfacesArgumentNotifier.forall{
      case (interface) => {
        val port = IO(axi4.Master(interface.cfg)).suggestName(f"axi4_master_${memory_interface_index}_${fpgaIndex}")
        interface :=> port.asFull
        axiOuts.addOne((port, interface.cfg))
        interfaceBuffer.addOne(
          hdlinfo.Interface(
            f"m_axi_${memory_interface_index}%02d_${fpgaIndex}",
            hdlinfo.InterfaceRole("master"), // "sink" for slave
            hdlinfo.InterfaceKind("axi4"), 
            "clock",
            "reset",
            Map("config" -> hdlinfo.TypedObject(interface.cfg))
          )
        )
        memory_interface_index += 1
        true
      }
    }
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

//   // Helpers
//   def readFile(path: String): String = {
//     import java.nio.charset.StandardCharsets
//     import java.nio.file.{Files, Path}
//     Files.readString(Path.of(path), StandardCharsets.UTF_8)
//   }

//   def writeFile(path: String, data: String): Unit = {
//     import java.nio.charset.StandardCharsets
//     import java.nio.file.{Files, Path}
//     Files.writeString(Path.of(path), data, StandardCharsets.UTF_8)
//   }

//   def basename(path: String): String = {
//     path.split("/").last.split("\\.").head
//   }

//   def generateRTL(
//       systemDescriptor: FullSysGenDescriptor,
//       pathInputJsonFile: String,
//       outputDirPathRTL: String,
//       flags: BuilderConfig,
//       isSimulation: Boolean
//   ): Int = {
//     // for task in system descriptor copy all the files in the peHDLPath to the outputDirRTL
//     systemDescriptor.taskDescriptors.foreach { task =>
//       val peHDLPath = task.peHDLPath
//       val peHDLPathFiles = new java.io.File(peHDLPath).listFiles()
//       peHDLPathFiles.foreach { file =>
//         val fileName = file.getName()
//         val fileContent = readFile(file.getAbsolutePath())
//         writeFile(s"$outputDirPathRTL/$fileName", fileContent)
//       }
//     }

//     // Copy all the files in the src/main/resources/ to the outputDirRTL except the DualPortBRAM_sim.v
//     val resourcesPath = "src/main/resources/"
//     val synthDirectory = f"${outputDirPathRTL}/synth"
//     val questaDirectory = f"${outputDirPathRTL}/questa"
//     new java.io.File(synthDirectory).mkdirs()
//     new java.io.File(questaDirectory).mkdirs()

//     val resourcesFiles = new java.io.File(resourcesPath).listFiles()
//     val listOfFilesForRTL = List("DualPortBRAM_sim.v", "DualPortBRAM_xpm.v", "top.v", "u55c.xdc")
//     val listOfFilesForQuesta = List("top_sim.sv", "main_sim.sv")
//     writeFile(s"$outputDirPathRTL/empty.vh", "")
//     writeFile(s"$outputDirPathRTL/empty.sv", "")
//     resourcesFiles.foreach { file =>
//       val fileName = file.getName()
//       val fileContent = readFile(file.getAbsolutePath())

//       if (fileName.startsWith("DualPortBRAM")) {
//         if ((isSimulation && fileName == "DualPortBRAM_sim.v") || (!isSimulation && fileName == "DualPortBRAM_xpm.v")) {
//           writeFile(s"$outputDirPathRTL/DualPortBRAM.v", fileContent)
//         }
//       } else if (listOfFilesForQuesta.contains(fileName)) {
//         writeFile(s"$questaDirectory/$fileName", fileContent)
//       } else {
//         writeFile(s"$synthDirectory/$fileName", fileContent)
//       }
//     }

//     var numHbmPortExports = 0
//     ChiselStage.emitSystemVerilogFile(
//       {
//         val module = new HardCilk(
//           fullSysGenDescriptor = systemDescriptor,
//           outputDirPathRTL = outputDirPathRTL,
//           debug = flags.debug,
//           reduceAxi = flags.reduce_axi,
//           unitedHbm = true,
//           isSimulation = isSimulation,
//           argumentNotifierCutCount = 1
//         )
//         numHbmPortExports = 33
//         module
//       },
//       Array(f"--target-dir=${outputDirPathRTL}"),
//       Array("--disable-all-randomization")
//     )

//     // For the file in the outputDirRTL with the name of the systemDescriptor.name run sv2v on it using os.system, then remove the original file
//     import sys.process._
//     val svFilePath = s"$outputDirPathRTL/${systemDescriptor.name}.sv"
//     val vFilePath = s"$outputDirPathRTL/${systemDescriptor.name}.v"

//     // Check if the SystemVerilog file exists
//     val svFile = new java.io.File(svFilePath)
//     if (svFile.exists()) {
//       val sv2vCommand = s"sv2v $svFilePath"
//       // Get the ouput of the command instead of stdout
//       val sv2vOutput = sv2vCommand.!!
//       val rmCommand = s"rm $svFilePath"
//       rmCommand.!

//       // Write the output of sv2v to the verilog file
//       writeFile(vFilePath, sv2vOutput)

//     } else {
//       println(s"Error: File $svFilePath does not exist.")
//     }

//     numHbmPortExports
//   }

//   // Main body
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