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


import chext.axi4
import chext.axis4

import axi4.Ops._
import axi4.lite.components._

import axis4.Casts._

import hardcilk.util.readyValidMem

import descriptors._

import _root_.circt.stage.ChiselStage
import java.time.format.DateTimeFormatter

import tclResources._
import softwareResources._

class fullSysGen(val fullSysGenDescriptor: fullSysGenDescriptor) extends Module with chisel3.interface.Extra {
  
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
    val stealSideSeq = scala.collection.mutable.ArrayBuffer[stealSide]()
    val continuationAllocationSideSeq = scala.collection.mutable.ArrayBuffer[continuationAllocationSide]()
    val syncSideSeq = scala.collection.mutable.ArrayBuffer[syncSide]()

    val RegisterBlockSize = 6
    val numMasters = fullSysGenDescriptor.getNumConfigPorts()
    val axiCfgCtrl = axi4.Config(wAddr = numMasters+RegisterBlockSize, wData = 64, lite = true) 
    
    val demux = Module(new Demux(axiCfgCtrl, numMasters, (x: UInt) => (x >> RegisterBlockSize.U)))
    
    // connect the demux input to the input of the module
    val s_axil_mgmt = IO(axi4.Slave(axiCfgCtrl)).suggestName("s_axil_mgmt_hardcilk")
    s_axil_mgmt :=> demux.s_axil


    var j = 0
    fullSysGenDescriptor.taskDescriptors.foreach { task =>

      println(task.mgmtBaseAddresses)
      
      stealSideSeq += Module(
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
      )
      
      val stealSideExport = IO(chiselTypeOf(stealSideSeq.last.io_export)).suggestName(f"${task.name}_scheduler")
      stealSideExport <> stealSideSeq.last.io_export
      
      // Export the AXI interface of the stealSide
      val stealSideAXI = IO(axi4.Master(stealSideSeq.last.vssAxiFullCfg)).suggestName(f"${task.name}_schedulerAXI")
      stealSideSeq.last.io_internal.vss_axi_full :=> stealSideAXI.asFull 

      // Connect the stealSide Management to the management demux
      for (i <- j until j + task.getNumServers("scheduler")) {
        demux.m_axil(i) :=> stealSideSeq.last.io_internal.axi_mgmt_vss(i - j)
      }
      j += task.getNumServers("scheduler")

      if (task.isCont) {

        continuationAllocationSideSeq += Module(
          new continuationAllocationSide(
            addrWidth = fullSysGenDescriptor.widthAddress,
            peCount = fullSysGenDescriptor.getPortCount("spawnNext", task.name),
            vcasCount = task.getNumServers("allocator"),
            queueDepth = task.getCapacityPhysicalQueue("allocator"),
            pePortWidth = task.getPortWidth("allocator")
          )
        )
        val continuationAllocationSideExport = IO(chiselTypeOf(continuationAllocationSideSeq.last.io_export)).suggestName(f"${task.name}_closureAllocator")
        continuationAllocationSideExport <> continuationAllocationSideSeq.last.io_export

        // Export the AXI interface of the continuationAllocationSide
        val continuationAllocationSideAXI = IO(axi4.Master(continuationAllocationSideSeq.last.vcasAxiFullCfg)).suggestName(f"${task.name}_closureAllocatorAXI")
        continuationAllocationSideSeq.last.io_internal.vcas_axi_full :=> continuationAllocationSideAXI.asFull

        // Connect the continuationAllocationSide Management to the management demux
        for (i <- j until j + task.getNumServers("allocator")) {
          demux.m_axil(i) :=> continuationAllocationSideSeq.last.io_internal.axi_mgmt_vcas(i - j)
        }
        j += task.getNumServers("allocator")



        syncSideSeq += Module(
          new syncSide(
            addrWidth = fullSysGenDescriptor.widthAddress,
            taskWidth = task.widthTask,
            queueDepth = task.getCapacityPhysicalQueue("argumentNotifier"),
            peCount = fullSysGenDescriptor.getPortCount("sendArgument", task.name),
            argRouteServersNumber = task.getNumServers("argumentNotifier"),
            contCounterWidth = fullSysGenDescriptor.widthContCounter,
            pePortWidth = task.getPortWidth("argumentNotifier")
          )
        )
        val syncSideExport = IO(chiselTypeOf(syncSideSeq.last.io)).suggestName(f"${task.name}_argumentNotifier")
        syncSideExport <> syncSideSeq.last.io

        // Export the AXI interface of the syncSide
        val syncSideAXI = IO(axi4.Master(syncSideSeq.last.argRouteAxiFullCfg)).suggestName(f"${task.name}_argumentNotifierAXI")
        syncSideSeq.last.axi_full_argRoute :=> syncSideAXI.asFull 

        stealSideSeq.last.connSyncSide <> syncSideSeq.last.connStealNtw
      }
    }

    conn_array.mkString("\n")
  }

  val connectionsTxt = initialize()

  val getExtra: strenc.Typed = strenc.PrimitiveTyped(connectionsTxt)
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

      val temp = ChiselStage.emitSystemVerilogFile(
        {
          val module = new fullSysGen(systemDescriptor)
          module
        },
        Array(f"--target-dir=${outputDirPathRTL}"),
        Array("--disable-all-randomization")
      )

      val tclGen = new tclGeneratorCompute(systemDescriptor, outputDirPathTCL)
      val tclGenMem = new tclGeneratorMem(systemDescriptor, outputDirPathTCL)
      val cppHeaderGen = CppHeaderTemplate.generateCppHeader(systemDescriptor, outputDirPathSoftwareHeaders)
      

      dumpJsonFile[fullSysGenDescriptorExtended](s"$outputDirPathRTL/${systemDescriptor.name}_descriptor_2.json", fullSysGenDescriptorExtended.fromFullSysGenDescriptor(systemDescriptor))
    }
  }
}

