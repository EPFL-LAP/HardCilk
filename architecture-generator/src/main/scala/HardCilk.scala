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

import io.circe.syntax._
import io.circe.generic.auto._
import scala.collection.mutable.ArrayBuffer

class HardCilk(
    fullSysGenDescriptor: FullSysGenDescriptor,
    outputDirPathRTL: String,
    debug: Boolean,
    reduceAxi: Boolean
) extends Module {

  override def desiredName: String =
    if (fullSysGenDescriptor.name.isEmpty) "fullSysGen"
    else fullSysGenDescriptor.name

  private def initialize(): Unit = {
    // create a modifiable seq to carry the Scheduler, ClosureAllocator, and ArgumentNotifier
    val schedulerMap = scala.collection.mutable.Map[String, Scheduler]()
    val closureAllocatorMap = scala.collection.mutable.Map[String, Allocator]()
    val memoryAllocatorMap = scala.collection.mutable.Map[String, Allocator]()
    val argumentNotifierMap = scala.collection.mutable.Map[String, ArgumentNotifier]()
    val peMap = scala.collection.mutable.Map[String, Seq[HLSHelpers.VitisModule]]()

    val interfaceBuffer = new ArrayBuffer[hdlinfo.Interface]()

    val registerBlockSize = 6
    val numMasters = fullSysGenDescriptor.getNumConfigPorts()
    val axiCfgCtrl = axi4.Config(wAddr = numMasters + registerBlockSize, wData = 64, lite = true)

    val demux = Module(new Demux(axiCfgCtrl, numMasters, (x: UInt) => (x >> registerBlockSize.U)))

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

        if (task.hasAXI) {
          val pem_axi_gmem =
            IO(chiselTypeOf(pe.getPort("m_axi_gmem").asInstanceOf[axi4.RawInterface])).suggestName(f"${peName}_m_axi_gmem")
          val pes_axi_control =
            IO(chiselTypeOf(pe.getPort("s_axi_control").asInstanceOf[axi4.RawInterface]))
              .suggestName(f"${peName}_s_axi_control")

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
        }

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
          reduceAxi = reduceAxi,
          debug = debug
        )
      ))

      // Export the AXI interface of the Scheduler
      schedulerMap(task.name).io_internal.vss_axi_full.zipWithIndex.foreach {
        case (port, idx) => {
          val name = f"${task.name}_schedulerAXI_${idx}"
          val schedulerAXI = IO(axi4.Master(port.cfg)).suggestName(name)
          port :=> schedulerAXI.asFull
          interfaceBuffer.addOne(
            hdlinfo.Interface(
              name,
              hdlinfo.InterfaceRole.master,
              hdlinfo.InterfaceKind("axi4"),
              "clock",
              "reset",
              Map("config" -> hdlinfo.TypedObject(schedulerAXI.cfg))
            )
          )
        }
      }

      // Connect the Scheduler Management to the management demux
      for (i <- j until j + task.getNumServers("scheduler")) {
        demux.m_axil(i) :=> schedulerMap(task.name).io_internal.axi_mgmt_vss(i - j)
      }
      j += task.getNumServers("scheduler")

      if (task.isCont) {
        closureAllocatorMap += (task.name -> Module(
          new Allocator(
            addrWidth = fullSysGenDescriptor.widthAddress,
            peCount = fullSysGenDescriptor.getPortCount("spawnNext", task.name),
            vcasCount = task.getNumServers("allocator"),
            queueDepth = task.getCapacityPhysicalQueue("allocator"),
            pePortWidth = task.getPortWidth("allocator"),
            reduceAxi = reduceAxi
          )
        ))

        // Export the AXI interface of the ClosureAllocator
        closureAllocatorMap(task.name).io_internal.vcas_axi_full.zipWithIndex.foreach {
          case (port, idx) => {
            val name = f"${task.name}_closureAllocatorAXI_${idx}"
            val closureAllocatorAXI = IO(axi4.Master(port.cfg)).suggestName(name)
            port :=> closureAllocatorAXI.asFull
            interfaceBuffer.addOne(
              hdlinfo.Interface(
                name,
                hdlinfo.InterfaceRole.master,
                hdlinfo.InterfaceKind("axi4"),
                "clock",
                "reset",
                Map("config" -> hdlinfo.TypedObject(closureAllocatorAXI.cfg))
              )
            )
          }
        }

        // Connect the ClosureAllocator Management to the management demux
        for (i <- j until j + task.getNumServers("allocator")) {
          demux.m_axil(i) :=> closureAllocatorMap(task.name).io_internal.axi_mgmt_vcas(i - j)
        }
        j += task.getNumServers("allocator")

        argumentNotifierMap += (task.name -> Module(
          new ArgumentNotifier(
            addrWidth = fullSysGenDescriptor.widthAddress,
            taskWidth = task.widthTask,
            queueDepth = task.getCapacityPhysicalQueue("argumentNotifier"),
            peCount = fullSysGenDescriptor.getPortCount("sendArgument", task.name),
            argRouteServersNumber = task.getNumServers("argumentNotifier"),
            contCounterWidth = fullSysGenDescriptor.widthContCounter,
            pePortWidth = task.getPortWidth("argumentNotifier"),
            reduceAxi = reduceAxi
          )
        ))

        // Export the AXI interface of the ArgumentNotifier
        argumentNotifierMap(task.name).axi_full_argRoute.zipWithIndex.foreach {
          case (port, idx) => {
            val name = f"${task.name}_argumentNotifierAXI_${idx}"
            val argumentNotifierAXI = IO(axi4.Master(port.cfg)).suggestName(name)
            port :=> argumentNotifierAXI.asFull
            interfaceBuffer.addOne(
              hdlinfo.Interface(
                name,
                hdlinfo.InterfaceRole.master,
                hdlinfo.InterfaceKind("axi4"),
                "clock",
                "reset",
                Map("config" -> hdlinfo.TypedObject(argumentNotifierAXI.cfg))
              )
            )
          }
        }

        schedulerMap(task.name).connArgumentNotifier <> argumentNotifierMap(task.name).connStealNtw
      }

      if (task.dynamicMemAlloc) {
        memoryAllocatorMap += (task.name -> Module(
          new Allocator(
            addrWidth = fullSysGenDescriptor.widthAddress,
            peCount = fullSysGenDescriptor.getPortCount("mallocIn", task.name),
            vcasCount = task.getNumServers("memoryAllocator"),
            queueDepth = task.getCapacityPhysicalQueue("memoryAllocator"),
            pePortWidth = task.getPortWidth("memoryAllocator"),
            reduceAxi = reduceAxi
          )
        ))

        // Export the AXI interface of the MemoryAllocator
        memoryAllocatorMap(task.name).io_internal.vcas_axi_full.zipWithIndex.foreach {
          case (port, idx) => {
            val name = f"${task.name}_memoryAllocatorAXI_${idx}"
            val memoryAllocatorAXI = IO(axi4.Master(port.cfg)).suggestName(name)
            port :=> memoryAllocatorAXI.asFull
            interfaceBuffer.addOne(
              hdlinfo.Interface(
                name,
                hdlinfo.InterfaceRole.master,
                hdlinfo.InterfaceKind("axi4"),
                "clock",
                "reset",
                Map("config" -> hdlinfo.TypedObject(memoryAllocatorAXI.cfg))
              )
            )
          }
        }

        for (i <- j until j + task.getNumServers("memoryAllocator")) {
          demux.m_axil(i) :=> memoryAllocatorMap(task.name).io_internal.axi_mgmt_vcas(i - j)
        }
        j += task.getNumServers("memoryAllocator")
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
  }

  initialize()
}

object HardCilkEmitter extends App {
  import _root_.circt.stage.ChiselStage
  import java.time.format.DateTimeFormatter
  import scopt.OParser

  // Handling argument parsing
  case class BuilderConfig(
      val debug: Boolean = false,
      val reduce_axi: Boolean = false,
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
      opt[Unit]('r', "reduce-axi")
        .action((_, c) => c.copy(reduce_axi = true))
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
        .text("Generates the TCL output of the HardCilk for Vivado Block Design"),
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
        .text("Generates all outputs for HardCilk, equivilant to using `-g -c -b -s` flags"),
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
      useBramSim: Boolean
  ): Unit = {
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
    val resourcesFiles = new java.io.File(resourcesPath).listFiles()
    resourcesFiles.foreach { file =>
      val fileName = file.getName()
      val fileContent = readFile(file.getAbsolutePath())
      if (fileName.startsWith("DualPortBRAM")) {
        if ((useBramSim && fileName == "DualPortBRAM_sim.v") || (!useBramSim && fileName == "DualPortBRAM_xpm.v")) {
          writeFile(s"$outputDirPathRTL/DualPortBRAM.v", fileContent)
        }
      } else {
        writeFile(s"$outputDirPathRTL/$fileName", fileContent)
      }
    }

    ChiselStage.emitSystemVerilogFile(
      {
        val module = new HardCilk(systemDescriptor, outputDirPathRTL, flags.debug, flags.reduce_axi)
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
  }

  // Main body
  OParser.parse(parser, args, BuilderConfig()) match {
    case None =>
      println(f"Incorrect usage, please run with `--help` option to get the usage help")
    case Some(flags) => {
      // Create a directory under the output directory with the name of the json file, date, and timestamp (nearest second)
      val pathOutputDir = flags.output_dir
      val pathInputJsonFile = flags.json_path
      val jsonName = basename(pathInputJsonFile)
      val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
      val timeFormatter = DateTimeFormatter.ofPattern("HH-mm-ss")

      val outputDirName =
        if (flags.timestamped)
          s"${jsonName}_${java.time.LocalDate.now.format(dateFormatter)}_${java.time.LocalTime.now.format(timeFormatter)}"
        else
          s"${jsonName}_hardcilk_output"

      val outputDirPathRTL = s"$pathOutputDir/$outputDirName/rtl"
      val outputDirPathSoftwareHeaders = s"$pathOutputDir/$outputDirName/softwareHeaders"
      val outputDirPathTCL = s"$pathOutputDir/$outputDirName/tcl"
      val outputDirPathSC = s"$pathOutputDir/$outputDirName/software"

      // Create the sytem descriptors
      val systemDescriptor = parseJsonFile[FullSysGenDescriptor](pathInputJsonFile)

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
      if (flags.rtl_generation) {
        new java.io.File(outputDirPathRTL).mkdirs()
        generateRTL(systemDescriptor, pathInputJsonFile, outputDirPathRTL, flags, false)
        dumpJsonFile[FullSysGenDescriptorExtended](
          s"$outputDirPathRTL/${systemDescriptor.name}_descriptor_2.json",
          FullSysGenDescriptorExtended.fromFullSysGenDescriptor(systemDescriptor)
        )
      }
      if (flags.cpp_header_generation || flags.sc_header_generation) {
        new java.io.File(outputDirPathSoftwareHeaders).mkdirs()
        if (flags.cpp_header_generation)
          CppHeaderTemplate.generateCppHeader(systemDescriptor, outputDirPathSoftwareHeaders, flags.reduce_axi)
        if (flags.sc_header_generation)
          TestBenchHeaderTemplate.generateCppHeader(systemDescriptor, outputDirPathSoftwareHeaders, flags.reduce_axi)
      }
      if (flags.tcl_generation) {
        new java.io.File(outputDirPathTCL).mkdirs()
        TclGeneratorMemPEs.generate(systemDescriptor, outputDirPathTCL, flags.reduce_axi)
      }
      if (flags.project_sc_generation) {
        // Using java.nio copy a folder with all its content (files and subfolders) to another folder, source is "pwd/software_template" and destination is "outputDirPathSC"
        val source = new java.io.File("software_template")
        val destination = new java.io.File(outputDirPathSC)
        java.nio.file.Files
          .walk(source.toPath)
          .forEach(sourcePath => {
            val destinationPath = destination.toPath.resolve(source.toPath.relativize(sourcePath))
            if (sourcePath.toFile.isDirectory) {
              java.nio.file.Files.createDirectories(destinationPath)
            } else {
              java.nio.file.Files.copy(sourcePath, destinationPath)
            }
          })

        // Rename `outputDirPathSC/projects/project_template` to `outputDirPathSC/projects/${jsonName}`
        val projectTemplate = new java.io.File(s"$outputDirPathSC/projects/project_template")
        val projectDestination = new java.io.File(s"$outputDirPathSC/projects/${jsonName}")
        projectTemplate.renameTo(projectDestination)

        // Generate the SystemC project in the `outputDirPathSC/project/${jsonName}/include`
        new java.io.File(s"$outputDirPathSC/projects/${jsonName}/include").mkdirs()
        CppHeaderTemplate.generateCppHeader(
          systemDescriptor,
          s"$outputDirPathSC/projects/${jsonName}/include",
          flags.reduce_axi
        )

        // Generate the SystemC testbench in the `outputDirPathSC/projects/${jsonName}/include`
        TestBenchHeaderTemplate.generateCppHeader(
          systemDescriptor,
          s"$outputDirPathSC/projects/${jsonName}/include",
          flags.reduce_axi
        )

        // Generate the HDL in the `outputDirPathSC/projects/${jsonName}/hdl`
        new java.io.File(s"$outputDirPathSC/projects/${jsonName}/hdl").mkdirs()
        generateRTL(systemDescriptor, pathInputJsonFile, s"$outputDirPathSC/projects/${jsonName}/hdl", flags, true)

        // Read the `outputDirPathSC/projects/${jsonName}/CMakeLists.txt` and replace the `${project_template}` with the `${jsonName}`
        val cmakeListsPath = s"$outputDirPathSC/projects/${jsonName}/CMakeLists.txt"
        val cmakeListsContent = readFile(cmakeListsPath)
        val newCmakeListsContent = cmakeListsContent.replace("${project_template}", jsonName)
        writeFile(cmakeListsPath, newCmakeListsContent)

        // Also copy `../software/${jsonName}` to `outputDirPathSC/projects/${jsonName}`
        val sourceProject = new java.io.File(s"../software/${jsonName}")
        java.nio.file.Files
          .walk(sourceProject.toPath)
          .forEach(sourcePath => {
            val destinationPath = projectDestination.toPath.resolve(sourceProject.toPath.relativize(sourcePath))
            if (sourcePath.toFile.isDirectory) {
              java.nio.file.Files.createDirectories(destinationPath)
            } else {
              java.nio.file.Files.copy(sourcePath, destinationPath)
            }
          })
      }
    }
  }
}
