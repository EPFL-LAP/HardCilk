package HardCilk

import _root_.circt.stage.ChiselStage
import java.time.format.DateTimeFormatter
import java.time.{LocalDate, LocalTime}
import java.nio.file.{Files, Paths}
import Util.ArgParser
import Descriptors._
import Descriptors.DescriptorJSON._
import Util.HardCilkEmitterUtil._
import SoftwareUtil._
import TclResources._
import Util.VitisFlowGenerator

object HardCilkEmitter extends App {
  ArgParser.parseArgs(args) match {
    case None =>
      // parser printed usage; exit quietly
    case Some(cfg) =>
      val jsonName = basename(cfg.json_path)
      val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
      val timeFmt = DateTimeFormatter.ofPattern("HH-mm-ss")
      val outputDirName =
          if (cfg.timestamped)
            s"${jsonName}_${LocalDate.now.format(dateFmt)}_${LocalTime.now.format(timeFmt)}"
          else
            s"${jsonName}_hardcilk_output"
   
      val parsedDescriptor = parseJsonFile[FullSysGenDescriptor](cfg.json_path)
      // -x/--vitis-xclbin requires the Vitis management convention: a 32-bit
      // AXI-Lite control port and the 0x10 register-map offset (the first 0x10
      // bytes are reserved for the kernel's own ap_ctrl block). Force it on so
      // the RTL wrapper and the software header can never silently disagree with
      // the flag. `copy` re-runs the descriptor body, which recomputes every
      // mgmtBaseAddresses with the 0x10 base (Descriptors.scala).
      val systemDescriptor =
        if (cfg.vitis_generation && !parsedDescriptor.isVitisProject) {
          println(
            "[HardCilkEmitter] WARNING: -x/--vitis-xclbin was given but the JSON has " +
              "isVitisProject=false; forcing isVitisProject=true so the 0x10 management " +
              "offset and the 32-bit AXI-Lite control port are generated."
          )
          parsedDescriptor.copy(isVitisProject = true)
        } else parsedDescriptor


      if (!cfg.rtl_generation) {
        println("RTL generation not requested.")
        if (cfg.tcl_generation || cfg.questa_generation || cfg.cpp_header_generation)
          System.err.println(
            "ERROR: -b/--tcl-scripts, -q/--questa-sim and -c/--cpp-headers need the RTL to be " +
              "generated in the same run (they use the number of exported HBM ports); add " +
              "-g/--rtl-generation."
          )
      } else {
        val outputDirPathRTL = s"${cfg.output_dir}/$outputDirName/rtl"
        Files.createDirectories(Paths.get(outputDirPathRTL))

        // Read system descriptor from JSON
        try {
          systemDescriptor.validate() // <-- EXPLICITLY VALIDATE HERE
        } catch {
          case e: IllegalArgumentException =>
            System.err.println(s"JSON Validation Failed: ${e.getMessage}")
            System.exit(1)
        }

        // Call the generate RTL function
        val rtlResult = generateRTL(
          systemDescriptor = systemDescriptor,
          pathInputJsonFile = cfg.json_path,
          outputDirPathRTL = outputDirPathRTL,
          flags = cfg,
          isSimulation = false
        )
        val numHbmPortExports = rtlResult.numHbmPortExports
        println(s"Emitted RTL to: $outputDirPathRTL")
        if (cfg.tcl_generation || cfg.questa_generation) {
          val outputDirPathTCL = s"${cfg.output_dir}/$outputDirName/tcl"
          new java.io.File(outputDirPathTCL).mkdirs()
          // With --raw-hbm-ports the design exports one master per memory unit,
          // so the block designs reduce them to the requested -r ports with
          // SmartConnects instead of taking the exported count as the port count.
          val rawLabels = if (cfg.rawHbmPorts) rtlResult.hbmPortLabels else Seq.empty
          // Clamped: asking for more HBM ports than there are masters would leave
          // SmartConnects with no slave, which is not a legal cell. A design with
          // fewer masters than -r simply maps them 1:1.
          val numTclHbmPorts =
            if (cfg.rawHbmPorts) math.min(cfg.reduce_axi, numHbmPortExports) else numHbmPortExports
          if (cfg.tcl_generation) {
            TclGeneratorMemPEs.generate(
              systemDescriptor,
              outputDirPathTCL,
              numTclHbmPorts,
              rtlResult.hbmPortWidths,
              rawLabels
            )
            println(s"Emitted Vivado Block Design TCL to: $outputDirPathTCL")
          }
          if (cfg.questa_generation) {
            TclQuestaSim.generate(
              systemDescriptor,
              outputDirPathTCL,
              numTclHbmPorts,
              rtlResult.ramaPortIndices.toSet,
              rtlResult.hbmPortWidths,
              cfg.questaRamaStriping,
              rawLabels
            )
            println(s"Emitted QuestaSim project (run ./simulate.sh) to: $outputDirPathTCL")
          }
        }
        // Standalone driver header. Lands in the same place the -p project would
        // put it, so a run that already produced the software project can refresh
        // just the header. numHbmPortExports feeds getNumberAxiMasters().
        if (cfg.cpp_header_generation) {
          val includeDir =
            s"${cfg.output_dir}/$outputDirName/software/projects/$jsonName/include"
          Files.createDirectories(Paths.get(includeDir))
          CppHeaderTemplate.generateCppHeader(systemDescriptor, includeDir, numHbmPortExports)
          println(s"Emitted FullSysGenDescriptor.h to: $includeDir")
        }
      }


      

      if (cfg.vitis_generation) {
        val outputDirPathVitis = s"${cfg.output_dir}/$outputDirName/vitis"
        val outputDirPathRTLForVitis = s"${cfg.output_dir}/$outputDirName/rtl"
        Files.createDirectories(Paths.get(outputDirPathVitis))
        // The Vitis flow has no aggregation stage of its own: v++ builds the
        // interconnect from one `sp=` line per exported master, and the generator
        // takes that count from hbm_port_widths.txt rather than from -r, so raw
        // mode passes through untouched -- but with every raw master exposed,
        // which is untested at scale.
        if (cfg.rawHbmPorts) {
          println(
            "[HardCilkEmitter] WARNING: --raw-hbm-ports with -x is untested: conn_u55c.cfg will " +
              "carry one sp= line per raw master and let v++ build the interconnect for all of " +
              s"them, rather than the ${cfg.reduce_axi} SmartConnect-reduced port(s) the " +
              "Vivado/QuestaSim flows build."
          )
        }
        VitisFlowGenerator.generate(systemDescriptor, outputDirPathVitis, cfg.reduce_axi, outputDirPathRTLForVitis)
        println(s"Emitted Vitis xclbin project to: $outputDirPathVitis")
      }

      if (cfg.project_sc_generation) {
        // Using java.nio copy a folder with all its content (files and subfolders) to another folder, source is "pwd/software_template" and destination is "outputDirPathSC"
        val source = new java.io.File("software_template")
        val outputDirPathSC = s"${cfg.output_dir}/$outputDirName/software"
        val destination = new java.io.File(outputDirPathSC)
        java.nio.file.Files
          .walk(source.toPath)
          .forEach(sourcePath => {
            val destinationPath =
              destination.toPath.resolve(source.toPath.relativize(sourcePath))
            if (sourcePath.toFile.isDirectory) {
              java.nio.file.Files.createDirectories(destinationPath)
            } else {
              java.nio.file.Files.copy(
                sourcePath,
                destinationPath,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
              )
            }
          })

        // Rename `outputDirPathSC/projects/project_template` to `outputDirPathSC/projects/${jsonName}`
        val projectTemplate = new java.io.File(s"$outputDirPathSC/projects/project_template")
        val projectDestination =  new java.io.File(s"$outputDirPathSC/projects/${jsonName}")
        if (projectDestination.exists()) {
          java.nio.file.Files.walk(projectDestination.toPath)
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(java.nio.file.Files.delete(_))
        }
        java.nio.file.Files.move(projectTemplate.toPath, projectDestination.toPath)

        // Generate the HDL in the `outputDirPathSC/projects/${jsonName}/hdl`
        new java.io.File(s"$outputDirPathSC/projects/${jsonName}/hdl").mkdirs()
        val numHbmPortExports = generateRTL(
          systemDescriptor,
          cfg.json_path,
          s"$outputDirPathSC/projects/${jsonName}/hdl",
          cfg,
          true
        ).numHbmPortExports

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

        // Copy the driver software project into `outputDirPathSC/projects/${jsonName}`.
        // If `driverSoftwarePath` is specified in the JSON, use it directly;
        // otherwise fall back to the relative `../software/${jsonName}` convention
        // (or the mfpga variant when simulating/synthesizing for multiple FPGAs).
        var source_project_path = systemDescriptor.driverSoftwarePath match {
          case Some(path) => path
          case None =>
            if (systemDescriptor.mFPGASimulation || systemDescriptor.mFPGASynth)
              s"../software/mfpga/${jsonName}"
            else
              s"../software/${jsonName}"
        }

        val sourceProject = new java.io.File(source_project_path)

        java.nio.file.Files
          .walk(sourceProject.toPath)
          .forEach(sourcePath => {
            val destinationPath = projectDestination.toPath
              .resolve(sourceProject.toPath.relativize(sourcePath))
            if (sourcePath.toFile.isDirectory) {
              java.nio.file.Files.createDirectories(destinationPath)
            } else {
              java.nio.file.Files.copy(sourcePath, destinationPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
          })
      }


  }
}
