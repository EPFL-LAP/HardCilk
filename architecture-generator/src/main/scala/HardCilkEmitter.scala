package HardCilk

import _root_.circt.stage.ChiselStage
import java.time.format.DateTimeFormatter
import java.time.{LocalDate, LocalTime}
import java.nio.file.{Files, Paths}
import Util.ArgParser
import Descriptors._
import Descriptors.DescriptorJSON._
import Util.HardCilkEmitterUtil._


object HardCilkEmitter extends App {
  ArgParser.parseArgs(args) match {
    case None =>
      // parser printed usage; exit quietly
    case Some(cfg) =>
      if (!cfg.rtl_generation) {
        println("RTL generation not requested; nothing to do.")
      } else {
        val jsonName = basename(cfg.json_path)
        val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val timeFmt = DateTimeFormatter.ofPattern("HH-mm-ss")
        val outputDirName =
          if (cfg.timestamped)
            s"${jsonName}_${LocalDate.now.format(dateFmt)}_${LocalTime.now.format(timeFmt)}"
          else
            s"${jsonName}_hardcilk_output"

        val outputDirPathRTL = s"${cfg.output_dir}/$outputDirName/rtl"
        Files.createDirectories(Paths.get(outputDirPathRTL))

        // Read system descriptor from JSON
        val systemDescriptor = parseJsonFile[FullSysGenDescriptor](cfg.json_path)
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
  }
}