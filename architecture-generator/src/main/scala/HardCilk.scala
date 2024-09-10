import chisel3._

import Scheduler._
import ClosureAllocator._
import ArgumentNotifier._
import Descriptors._
import TclResources._
import HLSHelpers._

import chext.amba.axi4

import axi4.Ops._
import axi4.lite.components._

import scala.collection.mutable.ArrayBuffer

import _root_.circt.stage.ChiselStage
import java.time.format.DateTimeFormatter

import io.circe.syntax._
import io.circe.generic.auto._
import SoftwareUtil._

class HardCilk(
    fullSysGenDescriptor: fullSysGenDescriptor,
    outputDirPathRTL: String
) extends Module {

  override def desiredName: String =
    if (fullSysGenDescriptor.name.isEmpty) "fullSysGen"
    else fullSysGenDescriptor.name

  private def initialize() = {
    val conn_array = new ArrayBuffer[String]()

    // create a modifiable seq to carry the Scheduler, ClosureAllocator, and ArgumentNotifier
    val SchedulerMap = scala.collection.mutable.Map[String, Scheduler]()
    val ClosureAllocatorMap = scala.collection.mutable.Map[String, ClosureAllocator]()
    val ArgumentNotifierMap = scala.collection.mutable.Map[String, ArgumentNotifier]()
    val peMap = scala.collection.mutable.Map[String, Seq[HLSHelpers.VitisModule]]()

    val interfaceBuffer = new ArrayBuffer[hdlinfo.Interface]()

    val RegisterBlockSize = 6
    val numMasters = fullSysGenDescriptor.getNumConfigPorts()
    val axiCfgCtrl = axi4.Config(wAddr = numMasters + RegisterBlockSize, wData = 64, lite = true)

    val demux = Module(new Demux(axiCfgCtrl, numMasters, (x: UInt) => (x >> RegisterBlockSize.U)))

    // connect the demux input to the input of the module
    val s_axil_mgmt = IO(axi4.Slave(axiCfgCtrl)).suggestName("s_axil_mgmt_hardcilk")
    s_axil_mgmt :=> demux.s_axil

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
        val pem_axi_gmem =
          IO(chiselTypeOf(pe.getPort("m_axi_gmem").asInstanceOf[axi4.RawInterface])).suggestName(f"${peName}_m_axi_gmem")
        val pes_axi_control =
          IO(chiselTypeOf(pe.getPort("s_axi_control").asInstanceOf[axi4.RawInterface])).suggestName(f"${peName}_s_axi_control")

        interfaceBuffer.addOne(
          hdlinfo.Interface(
            f"${peName}_m_axi_gmem",
            hdlinfo.InterfaceRole.master,
            hdlinfo.InterfaceKind("axi4"),
            "clock",
            "reset",
            Map("config" -> hdlinfo.TypedObject(pem_axi_gmem.cfg))
          )
        )
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

      SchedulerMap += (task.name -> Module(
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
          peType = task.name
        )
      ))

      // val SchedulerExport = IO(chiselTypeOf(SchedulerSeq.last.io_export)).suggestName(f"${task.name}_scheduler")
      // SchedulerExport <> SchedulerSeq.last.io_export

      // Export the AXI interface of the Scheduler
      val SchedulerAXI = IO(axi4.Master(SchedulerMap(task.name).vssAxiFullCfg)).suggestName(f"${task.name}_schedulerAXI")
      SchedulerMap(task.name).io_internal.vss_axi_full :=> SchedulerAXI.asFull
      interfaceBuffer.addOne(
        hdlinfo.Interface(
          f"${task.name}_schedulerAXI",
          hdlinfo.InterfaceRole.master,
          hdlinfo.InterfaceKind("axi4"),
          "clock",
          "reset",
          Map("config" -> hdlinfo.TypedObject(SchedulerAXI.cfg))
        )
      )

      // Connect the Scheduler Management to the management demux
      for (i <- j until j + task.getNumServers("scheduler")) {
        demux.m_axil(i) :=> SchedulerMap(task.name).io_internal.axi_mgmt_vss(i - j)
      }
      j += task.getNumServers("scheduler")

      if (task.isCont) {

        ClosureAllocatorMap += (task.name -> Module(
          new ClosureAllocator(
            addrWidth = fullSysGenDescriptor.widthAddress,
            peCount = fullSysGenDescriptor.getPortCount("spawnNext", task.name),
            vcasCount = task.getNumServers("allocator"),
            queueDepth = task.getCapacityPhysicalQueue("allocator"),
            pePortWidth = task.getPortWidth("allocator")
          )
        ))

        // val ClosureAllocatorExport = IO(chiselTypeOf(ClosureAllocatorSeq.last.io_export)).suggestName(f"${task.name}_closureAllocator")
        // ClosureAllocatorExport <> ClosureAllocatorSeq.last.io_export

        // Export the AXI interface of the ClosureAllocator
        val ClosureAllocatorAXI =
          IO(axi4.Master(ClosureAllocatorMap(task.name).vcasAxiFullCfgSlave)).suggestName(f"${task.name}_closureAllocatorAXI")
        ClosureAllocatorMap(task.name).io_internal.vcas_axi_full :=> ClosureAllocatorAXI.asFull

        interfaceBuffer.addOne(
          hdlinfo.Interface(
            f"${task.name}_closureAllocatorAXI",
            hdlinfo.InterfaceRole.master,
            hdlinfo.InterfaceKind("axi4"),
            "clock",
            "reset",
            Map("config" -> hdlinfo.TypedObject(ClosureAllocatorAXI.cfg))
          )
        )

        // Connect the ClosureAllocator Management to the management demux
        for (i <- j until j + task.getNumServers("allocator")) {
          demux.m_axil(i) :=> ClosureAllocatorMap(task.name).io_internal.axi_mgmt_vcas(i - j)
        }
        j += task.getNumServers("allocator")

        ArgumentNotifierMap += (task.name -> Module(
          new ArgumentNotifier(
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

        // val ArgumentNotifierExport = IO(chiselTypeOf(ArgumentNotifierSeq.last.io)).suggestName(f"${task.name}_argumentNotifier")
        // ArgumentNotifierExport <> ArgumentNotifierSeq.last.io

        // Export the AXI interface of the ArgumentNotifier
        val ArgumentNotifierAXI =
          IO(axi4.Master(ArgumentNotifierMap(task.name).argRouteAxiFullCfg)).suggestName(f"${task.name}_argumentNotifierAXI")
        ArgumentNotifierMap(task.name).axi_full_argRoute :=> ArgumentNotifierAXI.asFull

        interfaceBuffer.addOne(
          hdlinfo.Interface(
            f"${task.name}_argumentNotifierAXI",
            hdlinfo.InterfaceRole.master,
            hdlinfo.InterfaceKind("axi4"),
            "clock",
            "reset",
            Map("config" -> hdlinfo.TypedObject(ArgumentNotifierAXI.cfg))
          )
        )

        SchedulerMap(task.name).connArgumentNotifier <> ArgumentNotifierMap(task.name).connStealNtw
      }
    }

    // Connet the PEs to the system based on the connection descriptor
    val systemConnectionsDescriptor = fullSysGenDescriptor.getSystemConnectionsDescriptor()

    for (connection <- systemConnectionsDescriptor.connections) {
      try {
        println(connection)

        val physicalSourcePort = connection.srcPort.parentType match {
          case "HardCilk" => {
            connection.srcPort.portType match {
              case "taskIn" | "taskOut" =>
                SchedulerMap(connection.srcPort.parentName).io_export
                  .getPort(connection.srcPort.portType, connection.srcPort.portIndex)
              case "closureOut" =>
                ClosureAllocatorMap(connection.srcPort.parentName).io_export
                  .getPort(connection.srcPort.portType, connection.srcPort.portIndex)
              case "argIn" =>
                ArgumentNotifierMap(connection.srcPort.parentName).io_export
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
              case "taskIn" | "taskOut" =>
                SchedulerMap(connection.dstPort.parentName).io_export
                  .getPort(connection.dstPort.portType, connection.dstPort.portIndex)
              case "closureOut" =>
                ClosureAllocatorMap(connection.dstPort.parentName).io_export
                  .getPort(connection.dstPort.portType, connection.dstPort.portIndex)
              case "argIn" =>
                ArgumentNotifierMap(connection.dstPort.parentName).io_export
                  .getPort(connection.dstPort.portType, connection.dstPort.portIndex)
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

object HardCilkEmitter extends App {
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
    val outputDirName =
      s"${jsonName}_${java.time.LocalDate.now.format(dateFormatter)}_${java.time.LocalTime.now.format(timeFormatter)}"
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
        val fileContent = readFile(file.getAbsolutePath())
        writeFile(s"$outputDirPathRTL/$fileName", fileContent)
      }
    }

    ChiselStage.emitSystemVerilogFile(
      {
        val module = new HardCilk(systemDescriptor, outputDirPathRTL)
        module
      },
      Array(f"--target-dir=${outputDirPathRTL}"),
      Array("--disable-all-randomization")
    )

    /// val tclGen = new tclGeneratorCompute(systemDescriptor, outputDirPathTCL)
    /// val tclGenMem = new tclGeneratorMem(systemDescriptor, outputDirPathTCL)
    TclGeneratorMemPEs.generate(systemDescriptor, outputDirPathTCL)
    CppHeaderTemplate.generateCppHeader(systemDescriptor, outputDirPathSoftwareHeaders)
    TestBenchHeaderTemplate.generateCppHeader(systemDescriptor, outputDirPathSoftwareHeaders)

    dumpJsonFile[fullSysGenDescriptorExtended](
      s"$outputDirPathRTL/${systemDescriptor.name}_descriptor_2.json",
      fullSysGenDescriptorExtended.fromFullSysGenDescriptor(systemDescriptor)
    )
  }
}
