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
          systemDescriptor.validate() // <-- EXPLICITLY VALIDATE HERE
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
        val projectTemplate =
          new java.io.File(s"$outputDirPathSC/projects/project_template")
        val projectDestination =
          new java.io.File(s"$outputDirPathSC/projects/${jsonName}")
        projectTemplate.renameTo(projectDestination)

        // Generate the HDL in the `outputDirPathSC/projects/${jsonName}/hdl`
        new java.io.File(s"$outputDirPathSC/projects/${jsonName}/hdl").mkdirs()
        val numHbmPortExports = generateRTL(
          systemDescriptor,
          cfg.json_path,
          s"$outputDirPathSC/projects/${jsonName}/hdl",
          cfg,
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
              java.nio.file.Files.copy(sourcePath, destinationPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
          })
      }


  }
}