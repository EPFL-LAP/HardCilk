package HardCilk

import chisel3._

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
import cats.instances.map
import chisel3.util.IrrevocableIO
import chisel3.util.Decoupled
import chisel3.util.DecoupledIO

// What I need to write for passing tasks around

class MfpgaHardCilk(
    fullSysGenDescriptor: FullSysGenDescriptor,
    outputDirPathRTL: String,
    debug: Boolean,
    reduceAxi: Int,
    unitedHbm: Boolean,
    isSimulation: Boolean,
    argumentNotifierCutCount: Int
) extends Module {

  override val desiredName = fullSysGenDescriptor.name
  // Create an array of modules of HardCilk of size fullSysGenDescriptor.fpgaCount

  val interfaceBuffer = new ArrayBuffer[hdlinfo.Interface]()
  val fpgaModules = ArrayBuffer[HardCilk]()
  for (i <- 0 until fullSysGenDescriptor.fpgaCount) {
    fpgaModules += Module(
      new HardCilk(
        fullSysGenDescriptor = fullSysGenDescriptor,
        outputDirPathRTL = outputDirPathRTL,
        debug = debug,
        reduceAxi = reduceAxi,
        unitedHbm = unitedHbm,
        isSimulation = isSimulation,
        argumentNotifierCutCount = argumentNotifierCutCount,
        fpgaIndex = i
      )
    )
  }
  // Merge all interfaceBuffer from all fpgaModules into one interfaceBuffer
  fpgaModules.foreach { fpgaModule =>
    interfaceBuffer ++= fpgaModule.interfaceBuffer
  }

  // loop over the fpgaModules
  for (i <- 0 until fullSysGenDescriptor.fpgaCount) {

    val fpgaModule = fpgaModules(i)

    // export all the axi streams and them to the interface buffer of HDL INFO
    fpgaModule.peMap.foreach { pe =>
      val peType = pe._1
      val peMap = pe._2
      peMap.foreach { pe =>
        pe.peElements.foreach { port =>
          // get the config of the port
          val portConfig = port._2._2
          // get the port
          val portIO = port._2._1
          // get the name of the port
          val portName = port._1

          // Create a export decoupled IO for the port with the same port width
          val portExport =
            if (port._2._3 == "Master") IO(DecoupledIO(UInt(portConfig.wData.W))).suggestName(portName)
            else IO(Flipped(DecoupledIO(UInt(portConfig.wData.W)))).suggestName(portName)

          // connect the port to the export
          portIO.asLite <> portExport

          // add the port to the interface buffer
          interfaceBuffer.addOne(
            hdlinfo.Interface(
              portName,
              hdlinfo.InterfaceRole(if (port._2._3 == "Master") "sink" else "source"),
              hdlinfo.InterfaceKind("readyValid[chext.elastic.Data]"),
              "clock",
              "reset",
              Map("width" -> hdlinfo.TypedObject(portConfig.wData))
            )
          )
        }
      }
    }

    // export all the axi masters (n per fpgaModule)
    var j = 0
    fpgaModule.axiOuts.foreach { axiOut =>
      // create an axi IO with the same configuration as the axiOut and connect it to the axiOut
      val axiOutIO =
        IO(axi4.Master(axiOut._2)).suggestName(f"m_axi_${j}%02d_${i}")
      axiOut._1 :=> axiOutIO
      j += 1
    }

    // export all the aximanagement slaves (1 per fpgaModule)
    fpgaModule.interfacesAxiManagement.foreach { interfaceAxiManagement =>
      val interfaceAxiManagementIO = IO(axi4.Slave(interfaceAxiManagement._2))
        .suggestName(f"s_axil_mgmt_hardcilk_${i}")
      interfaceAxiManagementIO :=> interfaceAxiManagement._1
    }

    if (fpgaModules.size > 1) {
      val m_axis_bufferIO = IO(axi4s.Master(fpgaModule.m_axis_buffer.head._2))
        .suggestName(f"m_axis_mFPGA_${i}")
      val s_axis_bufferIO = IO(axi4s.Slave(fpgaModule.s_axis_buffer.head._2))
        .suggestName(f"s_axis_mFPGA_${i}")
      fpgaModule.m_axis_buffer.head._1 <> m_axis_bufferIO
      fpgaModule.s_axis_buffer.head._1 <> s_axis_bufferIO
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
  val write = new java.io.PrintWriter(
    f"${outputDirPathRTL}/${fullSysGenDescriptor.name}.hdlinfo.json"
  )
  write.write(hdlinfoModule.asJson.toString())
  write.close()

}

object MfpgaHardCilkEmitter extends App {
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
      val json_path: String = "",
      val project_name: String = ""
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
      opt[String]('q', "source-project-name")
        .action((x, c) => c.copy(project_name = x))
        .text("source project name"),
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
  ): ArrayBuffer[HardCilk] = {
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
        if ((isSimulation && fileName == "DualPortBRAM_sim.v") || (!isSimulation && fileName == "DualPortBRAM_xpm.v")) {
          writeFile(s"$outputDirPathRTL/DualPortBRAM.v", fileContent)
        }
      } else if (listOfFilesForQuesta.contains(fileName)) {
        writeFile(s"$questaDirectory/$fileName", fileContent)
      } else {
        writeFile(s"$synthDirectory/$fileName", fileContent)
      }
    }

    var fpgaModules = ArrayBuffer[HardCilk]()

    ChiselStage.emitSystemVerilogFile(
      {
        val module = new MfpgaHardCilk(
          fullSysGenDescriptor = systemDescriptor,
          outputDirPathRTL = outputDirPathRTL,
          debug = flags.debug,
          reduceAxi = flags.reduce_axi,
          unitedHbm = true,
          isSimulation = isSimulation,
          argumentNotifierCutCount = 1
        )
        fpgaModules = module.fpgaModules
        module
      },
      Array(f"--target-dir=${outputDirPathRTL}"),
      Array("--disable-all-randomization")
    )

    // For the file in the outputDirRTL with the name of the systemDescriptor.name run sv2v on it using os.system, then remove the original file
    import sys.process._
    val svFilePath = s"${outputDirPathRTL}/${systemDescriptor.name}.sv"
    val vFilePath = s"${outputDirPathRTL}/${systemDescriptor.name}.v"

    // Check if the SystemVerilog file exists
    val svFile = new java.io.File(svFilePath)
    if (svFile.exists()) {
      val sv2vCommand = s"sv2v $svFilePath"
      // Get the ouput of the command instead of stdout
      val sv2vOutput = sv2vCommand.!!.replace(
        "reg [63:0] counter;",
        "reg [63:0] counter /*verilator public*/;"
      )
      val rmCommand = s"rm $svFilePath"
      rmCommand.!

      // read the generated verilog file and find the line "reg [63:0] counter;" and replace it with "reg [63:0] counter /*verilator public*/;" and rewrite the file back
      // val newVerilogFileContent = sv2vOutput.replace("reg [63:0] counter;", "reg [63:0] counter /*verilator public*/;")
      // writeFile(vFilePath, newVerilogFileContent)

      // Write the output of sv2v to the verilog file
      writeFile(vFilePath, sv2vOutput)
      fpgaModules
    } else {
      println(s"Error: File $svFilePath does not exist.")
      ArrayBuffer[HardCilk]()
    }
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
      val internalName = flags.project_name
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
          new java.io.File(s"$outputDirPathSC/projects/${internalName}")
        projectTemplate.renameTo(projectDestination)

        // Generate the HDL in the `outputDirPathSC/projects/${jsonName}/hdl`
        new java.io.File(s"$outputDirPathSC/projects/${internalName}/hdl").mkdirs()
        val fpgaModules = generateRTL(
          systemDescriptor,
          pathInputJsonFile,
          s"$outputDirPathSC/projects/${internalName}/hdl",
          flags,
          true
        )

        // Generate the SystemC project in the `outputDirPathSC/project/${jsonName}/include`
        new java.io.File(s"$outputDirPathSC/projects/${internalName}/include")
          .mkdirs()
        CppHeaderTemplate.generateCppHeader(
          systemDescriptor,
          s"$outputDirPathSC/projects/${internalName}/include"
        )

        systemDescriptor.taskDescriptors.foreach { task =>
          val peHDLPath = task.peHDLPath
          val peHDLPathFiles = new java.io.File(peHDLPath).listFiles()
          peHDLPathFiles.foreach { file =>
            val fileName = file.getName()
            val fileContent = readFile(file.getAbsolutePath())
            writeFile(s"$outputDirPathSC/projects/${internalName}/include/$fileName", fileContent)
          }
        }

        // softlink "ln -s repos/jnbrq/sysc-switch/include/sysc_netw/" to "outputDirPathSC/projects/${jsonName}/include/sysc_netw"
        val syscSwitchPath =
          "/repos/jnbrq/sysc-switch/include/sysc_netw"
        val syscSwitchDestination =
          s"$outputDirPathSC/projects/${internalName}/include/sysc_netw"
        val syscSwitchCommand = s"ln -s $syscSwitchPath $syscSwitchDestination"
        syscSwitchCommand.!

        // Generate the SystemC testbench in the `outputDirPathSC/projects/${jsonName}/include`
        TestBenchHeaderTemplate.generateCppHeader(
          systemDescriptor,
          s"$outputDirPathSC/projects/${internalName}/include",
          fpgaModules
        )

        // Read the `outputDirPathSC/projects/${jsonName}/CMakeLists.txt` and replace the `${project_template}` with the `${jsonName}`
        val cmakeListsPath =
          s"$outputDirPathSC/projects/${internalName}/CMakeLists.txt"
        val cmakeListsContent = readFile(cmakeListsPath)
        val newCmakeListsContent =
          cmakeListsContent.replace("${project_template}", internalName)
        writeFile(cmakeListsPath, newCmakeListsContent)

        // Also copy `../software/${jsonName}` to `outputDirPathSC/projects/${jsonName}`
        val sourceProject = new java.io.File(s"../software/${internalName}")

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

      // if (flags.rtl_generation) {
      //   new java.io.File(outputDirPathRTL).mkdirs()
      //   numHbmPortExports = generateRTL(
      //     systemDescriptor,
      //     pathInputJsonFile,
      //     outputDirPathRTL,
      //     flags,
      //     false
      //   )
      //   dumpJsonFile[FullSysGenDescriptorExtended](
      //     s"$outputDirPathRTL/${systemDescriptor.name}_descriptor_2.json",
      //     FullSysGenDescriptorExtended.fromFullSysGenDescriptor(
      //       systemDescriptor
      //     )
      //   )
      // }
      // if (flags.cpp_header_generation || flags.sc_header_generation) {
      //   new java.io.File(outputDirPathSoftwareHeaders).mkdirs()
      //   if (flags.cpp_header_generation)
      //     CppHeaderTemplate.generateCppHeader(
      //       systemDescriptor,
      //       outputDirPathSoftwareHeaders
      //     )
      //   if (flags.sc_header_generation)
      //     TestBenchHeaderTemplate.generateCppHeader(
      //       systemDescriptor,
      //       outputDirPathSoftwareHeaders,
      //       fpgaModules
      //     )
      // }
    }
  }
}

object mFPGAEntry extends App {
  MfpgaHardCilkEmitter.main(
    Array[String](
      "taskDescriptors/paper_exp1.json",
      "-o",
      "output",
      "-r",
      "32",
      "-a"
    )
  )
}

object mFpga_Sweep1 extends App {
  for (i <- (1 to 4)) {
    MfpgaHardCilkEmitter.main(
      Array[String](
        f"taskDescriptors/sweep1/sweep1_${i}.json",
        "-o",
        "output",
        "-r",
        "32",
        "-a",
        "-q", // // original project
        "paper_exp1"  // original project
      )
    )
  }
}
