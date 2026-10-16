package Util

import scopt.OParser

/**
  * Command line configuration for the HardCilk emitter.
  *
  * A run either BUILDS projects or REFRESHES the driver headers of a tree an
  * earlier run built:
  *
  *   - build: one or more flows (`-q` QuestaSim, `-P` PCIe, `-x` Vitis), each a
  *     self-contained project, plus modifiers that apply to whichever flows they
  *     are meaningful for. Nothing in the JSON descriptor selects a flow.
  *   - refresh (`-c`): rewrite only `FullSysGenDescriptor.h` from the JSON,
  *     without elaborating anything, so a finished place-and-route stays valid.
  */
case class BuilderConfig(
    json_path: String = "",
    output_dir: String = ".",

    // --- flows -------------------------------------------------------------
    questa: Boolean = false,
    pcie: Boolean = false,
    vitis: Boolean = false,

    // --- modes and modifiers -----------------------------------------------
    /** Emit the driver/software tree shared by all three flows. */
    software: Boolean = false,
    /** Rewrite only the generated driver headers of an existing output tree. */
    refreshHeaders: Boolean = false,
    /** Stripe every HBM port across RAMA IPs (QuestaSim and PCIe flows). */
    ramaStriping: Boolean = false,
    rawHbmPorts: Boolean = false,
    /** `-r` as given; [[hbmPorts]] resolves the default. */
    hbmPortsArg: Option[Int] = None,
    debug: Boolean = false,
    timestamped: Boolean = false
) {

  /** True when at least one flow was requested. */
  def anyFlow: Boolean = questa || pcie || vitis

  /**
    * The QuestaSim and PCIe flows share one RTL elaboration: both use the
    * native 64-bit management convention, so the emitted Verilog is identical.
    * The Vitis flow needs its own (32-bit control port, 0x10 register offset).
    */
  def needsSharedRtl: Boolean = questa || pcie

  /**
    * HBM ports the design's memory masters are reduced to. The PCIe flow keeps
    * one of the 32 for the host DMA path, so its default is 31.
    */
  def hbmPorts: Int = hbmPortsArg.getOrElse(if (pcie) ArgParser.MaxPciePorts else 32)
}

object ArgParser {

  /** HBM ports the design may use in the PCIe flow: the host DMA takes the 32nd. */
  val MaxPciePorts = 31

  private val builder = OParser.builder[BuilderConfig]
  private val parser = {
    import builder._
    OParser.sequence(
      programName("HardCilk"),
      head("HardCilk architecture generator"),
      note(
        """
          |Builds hardware projects for the HardCilk system described by a JSON descriptor.
          |Run it from the architecture-generator/ directory:
          |
          |  sbt "runMain HardCilk.HardCilkEmitter <json-path> [options]"
          |
          |Output goes to <output-dir>/<json-name>_hardcilk_output/:
          |  rtl/        RTL shared by the QuestaSim and PCIe flows
          |  questa/     QuestaSim project      (./simulate.sh [--batch])
          |  pcie/       Vivado XDMA/PCIe design (./build.sh [-i] [-j N])
          |  vitis/      Vitis xclbin project   (make TARGET=hw|hw_emu)
          |  software/   C++ driver and host programs (cmake)
          |  README.md   how to build and run what this run produced
          |
          |Examples:
          |  <json> -q                                   QuestaSim simulation
          |  <json> -P -s -r 18                          PCIe bitstream and its host program
          |  <json> -q -P -x -s --rama-striping -r 18    every flow, HBM striped through RAMA
          |  <json> -o <dir> -c                          refresh only the driver headers
          |""".stripMargin
      ),
      arg[String]("<json-path>")
        .required()
        .action((x, c) => c.copy(json_path = x))
        .text("JSON descriptor of the HardCilk system"),
      opt[String]('o', "output-dir")
        .valueName("<dir>")
        .action((x, c) => c.copy(output_dir = x))
        .text("parent directory of the output tree (default: .)"),

      note("\nFlows -- at least one, unless -c is given:"),
      opt[Unit]('q', "questa")
        .action((_, c) => c.copy(questa = true))
        .text("QuestaSim simulation project. Implies -s: the testbench is the driver program"),
      opt[Unit]('P', "pcie")
        .action((_, c) => c.copy(pcie = true))
        .text("Vivado block design, floorplan and bitstream scripts for the XDMA/PCIe flow"),
      opt[Unit]('x', "vitis")
        .action((_, c) => c.copy(vitis = true))
        .text("Vitis xclbin project for the XRT flow"),

      note("\nModifiers:"),
      opt[Unit]('s', "software")
        .action((_, c) => c.copy(software = true))
        .text(
          "C++ driver and host programs, one tree for every flow. Needs the descriptor's " +
            "driverSoftwarePath (or ../software/<json-name>)"
        ),
      opt[Int]('r', "hbm-ports")
        .valueName("<n>")
        .action((x, c) => c.copy(hbmPortsArg = Some(x)))
        .text(
          "HBM ports the memory masters are reduced to (default 31 with -P, else 32). " +
            "Muxing happens inside the design, or in the block design with --raw-hbm-ports. " +
            "The Vitis flow uses the design's own port count and lets v++ connect them"
        ),
      opt[Unit]("rama-striping")
        .action((_, c) => c.copy(ramaStriping = true))
        .text(
          "QuestaSim and PCIe only: put a RAMA IP on every HBM port and on the host path, " +
            "striping all traffic across the 32 pseudo channels. Without it each port gets its " +
            "own pseudo channel, and only tasks with generateRAMA in the JSON get a RAMA"
        ),
      opt[Unit]("raw-hbm-ports")
        .action((_, c) => c.copy(rawHbmPorts = true))
        .text(
          "Export every memory master un-muxed and reduce them to -r ports with SmartConnects " +
            "in the block design instead. Drops per-task generateRAMA ports"
        ),
      opt[Unit]('d', "debug")
        .action((_, c) => c.copy(debug = true))
        .text("add debug counters to the hardware"),
      opt[Unit]('t', "timestamped")
        .action((_, c) => c.copy(timestamped = true))
        .text("name the output tree <json-name>_<date>_<time> instead of <json-name>_hardcilk_output"),

      note("\nRefresh:"),
      opt[Unit]('c', "refresh-headers")
        .action((_, c) => c.copy(refreshHeaders = true))
        .text(
          "Rewrite only software/projects/<json-name>/include/gen/*/FullSysGenDescriptor.h " +
            "of an existing output tree from the JSON; nothing is elaborated. -o is the tree's " +
            "parent directory, or the tree itself"
        ),
      help('h', "help").text("print this help"),

      checkConfig { c =>
        val r = c.hbmPortsArg
        if (c.refreshHeaders && (c.anyFlow || c.software || c.timestamped || r.nonEmpty ||
                                 c.ramaStriping || c.rawHbmPorts || c.debug))
          failure(
            "-c refreshes the headers of an existing tree and cannot be combined with flows, " +
              "-s, -r, -t, -d, --rama-striping or --raw-hbm-ports"
          )
        else if (!c.refreshHeaders && !c.anyFlow)
          failure("no flow requested: pass at least one of -q, -P or -x (or -c to refresh headers)")
        else if (r.exists(_ < 1))
          failure("-r/--hbm-ports must be >= 1")
        else if (c.pcie && r.exists(_ > MaxPciePorts))
          failure(
            s"-P needs -r <= $MaxPciePorts: the host DMA path takes one of the 32 HBM ports"
          )
        else if (c.ramaStriping && !c.needsSharedRtl)
          failure("--rama-striping only applies to -q and -P")
        else success
      }
    )
  }

  /**
    * Parse command line args into a BuilderConfig, or `None` when they are
    * invalid (scopt has already printed why).
    */
  def parseArgs(args: Array[String]): Option[BuilderConfig] = {
    OParser.parse(parser, args, BuilderConfig()).map { parsed =>
      val cfg =
        if (parsed.questa && !parsed.software) {
          println("[HardCilk] -q implies -s: the QuestaSim testbench is compiled from the software tree.")
          parsed.copy(software = true)
        } else parsed
      if (cfg.rawHbmPorts && cfg.vitis)
        println(
          "[HardCilk] WARNING: --raw-hbm-ports with -x is untested: conn_u55c.cfg will carry " +
            "one sp= line per raw master and let v++ build the interconnect for all of them, " +
            s"rather than the ${cfg.hbmPorts} SmartConnect-reduced port(s) the QuestaSim and " +
            "PCIe flows build."
        )
      cfg
    }
  }
}
