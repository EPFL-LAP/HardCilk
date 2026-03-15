package HardCilk

import _root_.circt.stage.ChiselStage
import java.time.format.DateTimeFormatter
import java.time.{LocalDate, LocalTime}
import java.nio.file.{Files, Paths, StandardCopyOption}
import Util.ArgParser
import Descriptors._
import Descriptors.DescriptorJSON._
import Util.HardCilkEmitterUtil._
import SoftwareUtil._

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

      val systemDescriptor = parseJsonFile[FullSysGenDescriptor](cfg.json_path)

      if (!cfg.rtl_generation) {
        println("RTL generation not requested.")
      } else {
        val outputDirPathRTL = s"${cfg.output_dir}/$outputDirName/rtl"
        Files.createDirectories(Paths.get(outputDirPathRTL))

        // Read system descriptor from JSON
        try {
          systemDescriptor.validate()
        } catch {
          case e: IllegalArgumentException =>
            System.err.println(s"JSON Validation Failed: ${e.getMessage}")
            System.exit(1)
        }

        // Call the generate RTL function
        val numHbmPortExports = generateRTL(
          systemDescriptor = systemDescriptor,
          pathInputJsonFile = cfg.json_path,
          outputDirPathRTL = outputDirPathRTL,
          flags = cfg,
          isSimulation = false
        )
        println(s"Emitted RTL to: $outputDirPathRTL")
      }

      if (cfg.project_sc_generation) {
        // Copy software_template folder to outputDirPathSC
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
                StandardCopyOption.REPLACE_EXISTING
              )
            }
          })

        // Rename project_template -> jsonName.
        //
        // We use Files.move rather than File.renameTo because renameTo silently
        // returns false on failure without throwing, which left project_template
        // intact alongside the newly-created jsonName directory.
        //
        // REPLACE_EXISTING on Files.move only handles *empty* directories on
        // Unix (it maps directly to rename(2)), so we explicitly delete a
        // pre-existing non-empty destination from any previous run first,
        // walking the tree in reverse order so leaves are deleted before parents.
        val projectTemplatePath    = Paths.get(s"$outputDirPathSC/projects/project_template")
        val projectDestinationPath = Paths.get(s"$outputDirPathSC/projects/$jsonName")
        if (Files.exists(projectDestinationPath)) {
          java.nio.file.Files
            .walk(projectDestinationPath)
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(Files.delete)
        }
        Files.move(projectTemplatePath, projectDestinationPath)
        val projectDestination = projectDestinationPath.toFile

        // Generate the HDL in outputDirPathSC/projects/${jsonName}/hdl
        new java.io.File(s"$outputDirPathSC/projects/$jsonName/hdl").mkdirs()
        val numHbmPortExports = generateRTL(
          systemDescriptor,
          cfg.json_path,
          s"$outputDirPathSC/projects/$jsonName/hdl",
          cfg,
          true
        )

        // Generate the SystemC project headers
        new java.io.File(s"$outputDirPathSC/projects/$jsonName/include").mkdirs()
        CppHeaderTemplate.generateCppHeader(
          systemDescriptor,
          s"$outputDirPathSC/projects/$jsonName/include",
          numHbmPortExports
        )

        // Generate the SystemC testbench
        TestBenchHeaderTemplate.generateCppHeader(
          systemDescriptor,
          s"$outputDirPathSC/projects/$jsonName/include",
          numHbmPortExports
        )

        // Patch CMakeLists.txt: replace ${project_template} with jsonName
        val cmakeListsPath = s"$outputDirPathSC/projects/$jsonName/CMakeLists.txt"
        val cmakeListsContent = readFile(cmakeListsPath)
        val newCmakeListsContent = cmakeListsContent.replace("${project_template}", jsonName)
        writeFile(cmakeListsPath, newCmakeListsContent)

        // Copy the application software sources into the project
        var source_project_path = s"../software/$jsonName"
        if (systemDescriptor.mFPGASimulation || systemDescriptor.mFPGASynth) {
          source_project_path = s"../software/mfpga/$jsonName"
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
              java.nio.file.Files.copy(
                sourcePath,
                destinationPath,
                StandardCopyOption.REPLACE_EXISTING
              )
            }
          })
      }
  }
}