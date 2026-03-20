package Util

import scopt.OParser

// Cleaned and self-contained argument parser for HardCilk emitter
case class BuilderConfig(
  debug: Boolean = false,
  reduce_axi: Int = 32,
  timestamped: Boolean = false,
  cpp_header_generation: Boolean = false,
  tcl_generation: Boolean = false,
  rtl_generation: Boolean = false,
  sc_header_generation: Boolean = false,
  project_sc_generation: Boolean = false,
  project_aie_generation: Boolean = false,
  output_dir: String = ".",
  json_path: String = "",
  // additional small flags that the HardCilk constructor sometimes uses
  argumentNotifierCutCount: Int = 1,
  addressTransformFlag: Boolean = false
)

object ArgParser {
  private val builder = OParser.builder[BuilderConfig]
  private val parser = {
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
        .text("Generates the TCL output for Vivado Block Design"),
      opt[Unit]('s', "sc-headers")
        .action((_, c) => c.copy(sc_header_generation = true))
        .text("Generates the C++ header for SystemC simulation"),
      opt[Unit]('p', "project-sc")
        .action((_, c) => c.copy(project_sc_generation = true))
        .text("Generates the C++ project for SystemC simulation"),
      opt[Unit]('k', "aie-project")
        .action((_, c) => c.copy(project_aie_generation = true))
        .text("Generates the AIE project for SystemC simulation"),
      opt[Unit]('a', "all")
        .action((_, c) =>
          c.copy(
            cpp_header_generation = true,
            rtl_generation = true,
            tcl_generation = true,
            sc_header_generation = true,
            project_sc_generation = true,
            project_aie_generation = true
          )
        )
        .text("Generates all outputs (equivalent to `-g -c -b -s`)"),
      opt[Int]("arg-cut")
        .action((x, c) => c.copy(argumentNotifierCutCount = x))
        .text("argument notifier cut count (internal)"),
      opt[Unit]("addr-transform")
        .action((_, c) => c.copy(addressTransformFlag = true))
        .text("enable address transform for exported HBM AXI ports"),
      help("help").text("Prints this help text")
    )
  }

  /** Parse command line args into BuilderConfig. Returns None on parse error. */
  def parseArgs(args: Array[String]): Option[BuilderConfig] = {
    OParser.parse(parser, args, BuilderConfig())
  }
}

/* Example usage in this file.
   This small application demonstrates how to call the parser and build a simple output path.
   You can copy this pattern into HardCilkEmitter or other callers.
*/
object ArgParserExample extends App {
  ArgParser.parseArgs(args) match {
    case Some(cfg) =>
      val jsonName = cfg.json_path.split("/").lastOption.getOrElse(cfg.json_path).split("\\.").head
      val outputDirName =
        if (cfg.timestamped)
          s"${jsonName}_${java.time.LocalDate.now}"
        else
          s"${jsonName}_hardcilk_output"
      val outputDirPath = s"${cfg.output_dir}/$outputDirName"
      val outputDirPathSC = s"${cfg.output_dir}/$outputDirName/software"
      println(s"Parsed config: $cfg")
      println(s"Will use output dir: $outputDirPath")
      // Example: pass `cfg` to HardCilkEmitter or other components
    case None =>
      // parser already printed usage
  }
}


