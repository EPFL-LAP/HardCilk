package fullSysGen

import chisel3._
import chisel3.util._

import stealSide._
import continuationSide._
import argRouting._
import commonInterfaces._
import play.api.libs.json._
import scala.collection.mutable.ArrayBuffer

import play.api.libs.json._
import play.api.libs.functional.syntax._
import chisel3.util.isPow2


import chext.amba.axi4
import chext.amba.axi4s  

import chext.amba.axi4.Ops._
import chext.amba.axi4.lite.components._
//import chext.amba.axi4.Casts._

import axi4s.Casts._

import hardcilk.util.readyValidMem

import descriptors._

import _root_.circt.stage.ChiselStage
import java.time.format.DateTimeFormatter

import tclResources._
import softwareResources._

import hlsHelpers._
import java.nio.channels.NonReadableChannelException

import io.circe.syntax._
import io.circe.generic.auto._

class fullSysGen(val fullSysGenDescriptor: fullSysGenDescriptor, val outputDirPathRTL:String) extends Module {
  
  override def desiredName: String =
    if (fullSysGenDescriptor.name.isEmpty) "fullSysGen"
    else fullSysGenDescriptor.name


  private def initialize() = {
    val conn_array = new ArrayBuffer[String]()
    def conn_writeln(s: String) = {
      conn_array.addOne(s)
    }

    def log(s: String) = {
      println(s)
    }

 
    // create a modifiable seq to carry the stealSide, continuationAllocationSide, and syncSide
    val stealSideMap = scala.collection.mutable.Map[String, stealSide]()
    val continuationAllocationSideMap = scala.collection.mutable.Map[String, continuationAllocationSide]()
    val syncSideMap = scala.collection.mutable.Map[String, syncSide]()
    val peMap = scala.collection.mutable.Map[String, Seq[hlsHelpers.VitisModule]]()

    val interfaceBuffer = new ArrayBuffer[hdlinfo.Interface]()

    val RegisterBlockSize = 6
    val numMasters = fullSysGenDescriptor.getNumConfigPorts()
    val axiCfgCtrl = axi4.Config(wAddr = numMasters+RegisterBlockSize, wData = 64, lite = true) 
    
    val demux = Module(new Demux(axiCfgCtrl, numMasters, (x: UInt) => (x >> RegisterBlockSize.U)))
    
    // connect the demux input to the input of the module
    val s_axil_mgmt = IO(axi4.Slave(axiCfgCtrl)).suggestName("s_axil_mgmt_hardcilk")
    s_axil_mgmt :=> demux.s_axil

    interfaceBuffer.addOne(hdlinfo.Interface("s_axil_mgmt_hardcilk", hdlinfo.InterfaceRole.slave, hdlinfo.InterfaceKind("axi4"), "clock", "reset", Map("config" -> hdlinfo.TypedObject(axiCfgCtrl))))


    var j = 0
    fullSysGenDescriptor.taskDescriptors.foreach { task =>

      println(task.mgmtBaseAddresses)

      // Create the black boxes for the task PEs.
      val peArray = VitisModuleFactory(task)
      peMap += (task.name -> peArray)

      // Export the PEs m_axi_gmem and s_axi_control interfaces
      
      for (i <- 0 until task.numProcessingElements) {
        val pe = peArray(i)
        val peName = f"${task.name}_${i}"
        val pem_axi_gmem = IO(chiselTypeOf(pe.getPort("m_axi_gmem").asInstanceOf[axi4.RawInterface])).suggestName(f"${peName}_m_axi_gmem")
        val pes_axi_control = IO(chiselTypeOf(pe.getPort("s_axi_control").asInstanceOf[axi4.RawInterface])).suggestName(f"${peName}_s_axi_control")
        
        interfaceBuffer.addOne(hdlinfo.Interface(f"${peName}_m_axi_gmem", hdlinfo.InterfaceRole.master, hdlinfo.InterfaceKind("axi4"), "clock", "reset", Map("config" -> hdlinfo.TypedObject(pem_axi_gmem.cfg))))
        interfaceBuffer.addOne(hdlinfo.Interface(f"${peName}_s_axi_control", hdlinfo.InterfaceRole.slave, hdlinfo.InterfaceKind("axi4"), "clock", "reset", Map("config" -> hdlinfo.TypedObject(pes_axi_control.cfg))))
  

        pe.getPort("m_axi_gmem").asInstanceOf[axi4.RawInterface] :=> pem_axi_gmem
        pes_axi_control :=> pe.getPort("s_axi_control").asInstanceOf[axi4.RawInterface] 
        pe.getPort("ap_clk").asInstanceOf[Clock] := clock
        pe.getPort("ap_rst_n").asInstanceOf[Bool] := ~reset.asBool
        try {
          pe.getPort("ap_start").asInstanceOf[Bool] := true.B
        } catch {
          case e: Exception => {
            println(f"This module has no ap_start. ${e}")
          }
        }
        

      }

      stealSideMap += (task.name -> Module(
        new stealSide(
          addrWidth = fullSysGenDescriptor.widthAddress,
          taskWidth = task.widthTask,
          queueDepth = task.getCapacityPhysicalQueue("scheduler"),
          peCount = task.numProcessingElements,
          spawnsItself = fullSysGenDescriptor.selfSpawnedCount(task.name)>0,
          peCountGlobalTaskIn = fullSysGenDescriptor.getPortCount("spawn", task.name),
          argRouteServersNumber = task.getNumServers("argumentNotifier"),
          virtualAddressServersNumber = task.getNumServers("scheduler"),
          pePortWidth = task.getPortWidth("scheduler")
        )
      ))
      

      // val stealSideExport = IO(chiselTypeOf(stealSideSeq.last.io_export)).suggestName(f"${task.name}_scheduler")
      // stealSideExport <> stealSideSeq.last.io_export

      

      // Export the AXI interface of the stealSide
      val stealSideAXI = IO(axi4.Master(stealSideMap(task.name).vssAxiFullCfg)).suggestName(f"${task.name}_schedulerAXI")
      stealSideMap(task.name).io_internal.vss_axi_full :=> stealSideAXI.asFull
      interfaceBuffer.addOne(hdlinfo.Interface(f"${task.name}_schedulerAXI", hdlinfo.InterfaceRole.master, hdlinfo.InterfaceKind("axi4"), "clock", "reset", Map("config" -> hdlinfo.TypedObject(stealSideAXI.cfg))))

      // Connect the stealSide Management to the management demux
      for (i <- j until j + task.getNumServers("scheduler")) {
        demux.m_axil(i) :=> stealSideMap(task.name).io_internal.axi_mgmt_vss(i - j)
      }
      j += task.getNumServers("scheduler")

      if (task.isCont) {

        continuationAllocationSideMap += (task.name -> Module(
          new continuationAllocationSide(
            addrWidth = fullSysGenDescriptor.widthAddress,
            peCount = fullSysGenDescriptor.getPortCount("spawnNext", task.name),
            vcasCount = task.getNumServers("allocator"),
            queueDepth = task.getCapacityPhysicalQueue("allocator"),
            pePortWidth = task.getPortWidth("allocator")
          )
        ))

        // val continuationAllocationSideExport = IO(chiselTypeOf(continuationAllocationSideSeq.last.io_export)).suggestName(f"${task.name}_closureAllocator")
        // continuationAllocationSideExport <> continuationAllocationSideSeq.last.io_export

        // Export the AXI interface of the continuationAllocationSide
        val continuationAllocationSideAXI = IO(axi4.Master(continuationAllocationSideMap(task.name).vcasAxiFullCfg)).suggestName(f"${task.name}_closureAllocatorAXI")
        continuationAllocationSideMap(task.name).io_internal.vcas_axi_full :=> continuationAllocationSideAXI.asFull

        interfaceBuffer.addOne(hdlinfo.Interface(f"${task.name}_closureAllocatorAXI", hdlinfo.InterfaceRole.master, hdlinfo.InterfaceKind("axi4"), "clock", "reset", Map("config" -> hdlinfo.TypedObject(continuationAllocationSideAXI.cfg))))

        // Connect the continuationAllocationSide Management to the management demux
        for (i <- j until j + task.getNumServers("allocator")) {
          demux.m_axil(i) :=> continuationAllocationSideMap(task.name).io_internal.axi_mgmt_vcas(i - j)
        }
        j += task.getNumServers("allocator")


        syncSideMap += (task.name -> Module(
          new syncSide(
            addrWidth = fullSysGenDescriptor.widthAddress,
            taskWidth = task.widthTask,
            queueDepth = task.getCapacityPhysicalQueue("argumentNotifier"),
            peCount = fullSysGenDescriptor.getPortCount("sendArgument", task.name),
            argRouteServersNumber = task.getNumServers("argumentNotifier"),
            contCounterWidth = fullSysGenDescriptor.widthContCounter,
            pePortWidth = task.getPortWidth("argumentNotifier")
          )
        ))
        println(f"sync side port width ${task.getPortWidth("argumentNotifier")}")

        // val syncSideExport = IO(chiselTypeOf(syncSideSeq.last.io)).suggestName(f"${task.name}_argumentNotifier")
        // syncSideExport <> syncSideSeq.last.io

        // Export the AXI interface of the syncSide
        val syncSideAXI = IO(axi4.Master(syncSideMap(task.name).argRouteAxiFullCfg)).suggestName(f"${task.name}_argumentNotifierAXI")
        syncSideMap(task.name).axi_full_argRoute :=> syncSideAXI.asFull

        interfaceBuffer.addOne(hdlinfo.Interface(f"${task.name}_argumentNotifierAXI", hdlinfo.InterfaceRole.master, hdlinfo.InterfaceKind("axi4"), "clock", "reset", Map("config" -> hdlinfo.TypedObject(syncSideAXI.cfg))))

        stealSideMap(task.name).connSyncSide <> syncSideMap(task.name).connStealNtw
      }
    }

    // Connet the PEs to the system based on the connection descriptor
    val systemConnectionsDescriptor = fullSysGenDescriptor.getSystemConnectionsDescriptor()
    
    for (connection <- systemConnectionsDescriptor.connections){
      try {
        println(connection)

        val physicalSourcePort =  connection.srcPort.parentType match {
          case "HardCilk" => {
            connection.srcPort.portType match {
              case "taskIn" | "taskOut" => stealSideMap(connection.srcPort.parentName).io_export.getPort(connection.srcPort.portType, connection.srcPort.portIndex)
              case "closureOut" => continuationAllocationSideMap(connection.srcPort.parentName).io_export.getPort(connection.srcPort.portType, connection.srcPort.portIndex)
              case "argIn" => syncSideMap(connection.srcPort.parentName).io_export.getPort(connection.srcPort.portType, connection.srcPort.portIndex)
            } 
          }
          case "PE" => {
            peMap(connection.srcPort.parentName)(connection.srcPort.parentIndex).getPort(connection.srcPort.portType)
          }
        }

        val physicalDestinationPort = connection.dstPort.parentType match {
          case "HardCilk" => {
            connection.dstPort.portType match {
              case "taskIn" | "taskOut" => stealSideMap(connection.dstPort.parentName).io_export.getPort(connection.dstPort.portType, connection.dstPort.portIndex)
              case "closureOut" => continuationAllocationSideMap(connection.dstPort.parentName).io_export.getPort(connection.dstPort.portType, connection.dstPort.portIndex)
              case "argIn" => syncSideMap(connection.dstPort.parentName).io_export.getPort(connection.dstPort.portType, connection.dstPort.portIndex)
            } 
          }
          case "PE" => {
            peMap(connection.dstPort.parentName)(connection.dstPort.parentIndex).getPort(connection.dstPort.portType)
          }
        }


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
            )
          ),
          interfaceBuffer.toSeq
        )
    }
    val write = new java.io.PrintWriter(f"${outputDirPathRTL}/${fullSysGenDescriptor.name}.hdlinfo.json")
    write.write(hdlinfoModule.asJson.toString())
    write.close()





    conn_array.mkString("\n")
  }

  

  val connectionsTxt = initialize()

}

object commandLineEmiiterV2 {
  def main(args: Array[String]) = {
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

    if (args.length < 2) {
      println(
        "Please enter the path to the task description file as the first argument and the path to the output directory as the second argument"
      )
    } else {

      // Create a directory under the output directory with the name of the json file, date, and timestamp (nearest second)
      val pathOutputDir = args(1)
      val pathInputJsonFile = args(0)
      val jsonName = basename(pathInputJsonFile)
      val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
      val timeFormatter = DateTimeFormatter.ofPattern("HH-mm-ss")
      val outputDirName = s"${jsonName}_${java.time.LocalDate.now.format(dateFormatter)}_${java.time.LocalTime.now.format(timeFormatter)}"
      val outputDirPathRTL = s"$pathOutputDir/$outputDirName/rtl"
      val outputDirPathSoftwareHeaders = s"$pathOutputDir/$outputDirName/softwareHeaders"
      val outputDirPathTCL = s"$pathOutputDir/$outputDirName/tcl"
     
      // Create the directories
      new java.io.File(outputDirPathRTL).mkdirs()
      new java.io.File(outputDirPathSoftwareHeaders).mkdirs()
      new java.io.File(outputDirPathTCL).mkdirs()

      val systemDescriptor = parseJsonFile[fullSysGenDescriptor](pathInputJsonFile)

      // for task in system descriptor copy all the files in the peHDLPath to the outputDirRTL
      systemDescriptor.taskDescriptors.foreach { task =>
        val peHDLPath = task.peHDLPath
        val peHDLPathFiles = new java.io.File(peHDLPath).listFiles()
        peHDLPathFiles.foreach { file =>
          val fileName = file.getName()
          val fileExtension = fileName.split("\\.").last
          val fileContent = readFile(file.getAbsolutePath())
          writeFile(s"$outputDirPathRTL/$fileName", fileContent)
        }
      }


      val temp = ChiselStage.emitSystemVerilogFile(
        {
          val module = new fullSysGen(systemDescriptor, outputDirPathRTL)
          module
        },
        Array(f"--target-dir=${outputDirPathRTL}"),
        Array("--disable-all-randomization")
      )

      ///val tclGen = new tclGeneratorCompute(systemDescriptor, outputDirPathTCL)
      ///val tclGenMem = new tclGeneratorMem(systemDescriptor, outputDirPathTCL)
      val tclGenMemPEs = new tclGeneratorMemPEs(systemDescriptor, outputDirPathTCL)
      val cppHeaderGen = CppHeaderTemplate.generateCppHeader(systemDescriptor, outputDirPathSoftwareHeaders)
      val testBenchHeaderGen = testBenchHeaderTemplate.generateCppHeader(systemDescriptor, outputDirPathSoftwareHeaders)
      

      dumpJsonFile[fullSysGenDescriptorExtended](s"$outputDirPathRTL/${systemDescriptor.name}_descriptor_2.json", fullSysGenDescriptorExtended.fromFullSysGenDescriptor(systemDescriptor))
    }
  }
}

