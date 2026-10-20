package HardCilk

import java.time.format.DateTimeFormatter
import java.time.{LocalDate, LocalTime}
import java.nio.file.{Files, Paths}
import Util.{ArgParser, BuilderConfig, VitisFlowGenerator}
import Descriptors._
import Descriptors.DescriptorJSON._
import Util.HardCilkEmitterUtil._
import SoftwareUtil._
import TclResources._

/**
  * Where each flow's artifacts land.
  *
  * The flow directories sit directly under the output root rather than under a
  * shared `tcl/`, for two reasons. The generated block-design TCL globs the
  * Verilog sources out of `../rtl` and `simulate.do` reaches the software tree with a relative path
  * counted from the QuestaSim run directory, so keeping every flow exactly one
  * level below the root means no generated script has to know which flow it
  * belongs to. And separating them is what lets `-q` and `-P` coexist: both
  * create a Vivado project called `<name>_vivado_project` with `-force`, so
  * sharing a directory meant whichever ran second destroyed the other's project.
  */
case class OutputLayout(root: String) {
  /** RTL shared by the QuestaSim and PCIe flows (native 64-bit management port). */
  val rtl = s"$root/rtl"
  val questa = s"$root/questa"
  val pcie = s"$root/pcie"
  val vitis = s"$root/vitis"
  /** The Vitis flow elaborates its own RTL: 32-bit control port, 0x10 offset. */
  val vitisRtl = s"$root/vitis/rtl"
  val software = s"$root/software"

  def createAll(paths: String*): Unit = paths.foreach(p => Files.createDirectories(Paths.get(p)))
}

/**
  * A failure the user can act on. Thrown rather than `sys.exit`, so a run inside
  * an interactive sbt shell reports it without killing the shell, while
  * `sbt --batch` still exits non-zero. No stack trace: the message is the report.
  */
class EmitterError(message: String) extends RuntimeException(message) {
  override def fillInStackTrace(): Throwable = this
}

object HardCilkEmitter extends App {
  private def fail(message: String): Nothing = throw new EmitterError(message)

  private val parsedCfg = ArgParser.parseArgs(args).getOrElse(fail("invalid command line, see --help"))
  val jsonName = basename(parsedCfg.json_path)

  warnOnLegacyVitisKey(parsedCfg.json_path)
  private val parsedDescriptor = parseJsonFile[FullSysGenDescriptor](parsedCfg.json_path)
  try parsedDescriptor.validate()
  catch { case e: IllegalArgumentException => fail(s"JSON validation failed: ${e.getMessage}") }

  // Resolve the target board once, before anything is elaborated: --board wins
  // over the descriptor's fpgaModel, and the descriptor is rewritten to match so
  // every later consumer of fpgaModel agrees.
  val (cfg, descriptor) = resolveBoard(parsedCfg, parsedDescriptor)

  if (cfg.refreshHeaders) refreshHeaders(cfg, jsonName, descriptor)
  else build(cfg, jsonName, descriptor)

  private def build(cfg: BuilderConfig, jsonName: String, descriptor: FullSysGenDescriptor): Unit = {
    val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    val timeFmt = DateTimeFormatter.ofPattern("HH-mm-ss")
    val outputDirName =
      if (cfg.timestamped)
        s"${jsonName}_${LocalDate.now.format(dateFmt)}_${LocalTime.now.format(timeFmt)}"
      else
        s"${jsonName}_hardcilk_output"

    val layout = OutputLayout(s"${cfg.output_dir}/$outputDirName")

    // The QuestaSim and PCIe flows share one elaboration: both drive the
    // design's native 64-bit AXI-Lite management port, so the Verilog is
    // identical and building it twice would only cost time.
    val sharedRtl =
      if (cfg.needsSharedRtl) Some(buildSharedRtl(cfg, layout, descriptor)) else None

    val vitisRtl = if (cfg.vitis) Some(buildVitis(cfg, layout, descriptor)) else None

    // One software tree for every flow. The header's management base addresses
    // are the only per-flow difference and both variants are emitted, so the
    // port count can come from whichever elaboration ran.
    if (cfg.software) {
      val numHbmPortExports = sharedRtl.orElse(vitisRtl).get.numHbmPortExports
      SoftwareProjectGenerator.generate(descriptor, jsonName, layout.software, numHbmPortExports, cfg)
    }

    ReadmeGenerator.generate(
      descriptor = descriptor,
      jsonName = jsonName,
      layout = layout,
      cfg = cfg,
      commandLine = args.mkString(" "),
      sharedRtl = sharedRtl,
      vitisRtl = vitisRtl
    )

    println(s"\n[HardCilkEmitter] Done. See ${layout.root}/README.md for how to build and run.")
  }

  /**
    * Rewrite the driver headers of a tree an earlier run built, from the JSON
    * alone. Nothing is elaborated and nothing outside the headers is touched, so
    * a finished place-and-route stays valid. The one value the header needs from
    * the hardware, the number of exported memory masters, is read back from the
    * `hbm_port_widths.txt` that run left in its RTL directory.
    */
  private def refreshHeaders(cfg: BuilderConfig, jsonName: String, descriptor: FullSysGenDescriptor): Unit = {
    import scala.jdk.CollectionConverters._
    // -o may name the tree's parent (as for a build) or the tree itself, which
    // is how a timestamped tree is reached.
    val root = Seq(s"${cfg.output_dir}/${jsonName}_hardcilk_output", cfg.output_dir)
      .find(r => Files.isDirectory(Paths.get(s"$r/software/projects/$jsonName")))
      .getOrElse(
        fail(
          s"-c: no software/projects/$jsonName under ${cfg.output_dir}/${jsonName}_hardcilk_output " +
            s"or ${cfg.output_dir}; build the tree with -s first"
        )
      )
    val layout = OutputLayout(root)
    val numHbmPortExports = Seq(layout.rtl, layout.vitisRtl)
      .map(d => Paths.get(s"$d/hbm_port_widths.txt"))
      .find(Files.exists(_))
      .map(p => Files.readAllLines(p).asScala.count(_.trim.nonEmpty))
      .getOrElse(fail(s"-c: no rtl/hbm_port_widths.txt or vitis/rtl/hbm_port_widths.txt under $root"))

    SoftwareProjectGenerator.generateHeaders(
      descriptor, s"${layout.software}/projects/$jsonName", numHbmPortExports)
    println(s"[HardCilkEmitter] Refreshed the driver headers of $root ($numHbmPortExports memory master(s)).")
  }

  /**
    * Elaborate the RTL the QuestaSim and PCIe flows share, then emit whichever of
    * their projects was asked for.
    */
  private def buildSharedRtl(
      cfg: BuilderConfig,
      layout: OutputLayout,
      descriptor: FullSysGenDescriptor
  ): RtlGenResult = {
    layout.createAll(layout.rtl)
    val rtlResult = generateRTL(
      systemDescriptor = descriptor,
      pathInputJsonFile = cfg.json_path,
      outputDirPathRTL = layout.rtl,
      flags = cfg,
      isSimulation = false
    )
    println(s"Emitted RTL to: ${layout.rtl}")

    // With --raw-hbm-ports the design exports one master per memory unit, so the
    // block designs reduce them to the requested -r ports with SmartConnects
    // instead of taking the exported count as the port count.
    val rawLabels = if (cfg.rawHbmPorts) rtlResult.hbmPortLabels else Seq.empty
    // Clamped: asking for more HBM ports than there are masters would leave
    // SmartConnects with no slave, which is not a legal cell. A design with fewer
    // masters than -r simply maps them 1:1.
    val numTclHbmPorts =
      if (cfg.rawHbmPorts) math.min(cfg.hbmPorts, rtlResult.numHbmPortExports)
      else rtlResult.numHbmPortExports

    if (cfg.pcie) {
      layout.createAll(layout.pcie)
      TclGeneratorMemPEs.generate(
        descriptor,
        layout.pcie,
        numTclHbmPorts,
        rtlResult.hbmPortWidths,
        cfg.ramaStriping,
        rtlResult.ramaPortIndices.toSet,
        rawLabels,
        rtlResult.hbmPortAxi3,
        rtlResult.peInstanceNames,
        rtlResult.peIfaceBuffers
      )
      println(s"Emitted Vivado Block Design TCL to: ${layout.pcie}")
    }

    if (cfg.questa) {
      layout.createAll(layout.questa)
      TclQuestaSim.generate(
        descriptor,
        layout.questa,
        numTclHbmPorts,
        rtlResult.ramaPortIndices.toSet,
        rtlResult.hbmPortWidths,
        cfg.ramaStriping,
        rawLabels,
        rtlResult.hbmPortAxi3
      )
      println(s"Emitted QuestaSim project (run ./simulate.sh) to: ${layout.questa}")
    }

    rtlResult
  }

  /**
    * Elaborate and package the Vitis flow.
    *
    * It gets its own descriptor and its own RTL because Vitis reserves the first
    * 0x10 bytes of a kernel's register space for `ap_ctrl` and drives the control
    * port at 32 bits. `copy` re-runs the descriptor body, which recomputes every
    * management base address against the shifted base.
    */
  private def buildVitis(
      cfg: BuilderConfig,
      layout: OutputLayout,
      descriptor: FullSysGenDescriptor
  ): RtlGenResult = {
    val vitisDescriptor = descriptor.copy(vitisMgmtConvention = true)
    layout.createAll(layout.vitis, layout.vitisRtl)

    val rtlResult = generateRTL(
      systemDescriptor = vitisDescriptor,
      pathInputJsonFile = cfg.json_path,
      outputDirPathRTL = layout.vitisRtl,
      flags = cfg,
      isSimulation = false
    )
    println(s"Emitted Vitis RTL to: ${layout.vitisRtl}")

    VitisFlowGenerator.generate(vitisDescriptor, layout.vitis, cfg.hbmPorts, layout.vitisRtl)
    println(s"Emitted Vitis xclbin project to: ${layout.vitis}")
    rtlResult
  }

  private def resolveBoard(
      cfg: BuilderConfig,
      descriptor: FullSysGenDescriptor
  ): (BuilderConfig, FullSysGenDescriptor) = {
    val board = cfg.boardArg.getOrElse(Util.Board.forModel(descriptor.fpgaModel))
    if (cfg.vitis && !board.supportsVitis)
      fail(
        s"-x/--vitis is not supported on ${board.fpgaModel}: there is no Vitis platform for it. " +
          "Use -P (PCIe/AVED flow) and/or -q (QuestaSim)."
      )
    if (board == Util.Board.V80 && cfg.ramaStriping)
      fail(
        "--rama-striping is not supported on the Alveo V80: RAMA is an UltraScale+ HBM IP. " +
          "On the V80 every HBM NMU already addresses all 32 pseudo channels through the NoC."
      )
    if (cfg.pcie && cfg.hbmPortsArg.exists(_ > board.maxPciePorts))
      fail(s"-P needs -r <= ${board.maxPciePorts} on ${board.fpgaModel}")

    var d = if (descriptor.fpgaModel != board.fpgaModel) {
      println(s"[HardCilkEmitter] --board ${board.cliName}: overriding fpgaModel ${descriptor.fpgaModel} -> ${board.fpgaModel}")
      descriptor.copy(fpgaModel = board.fpgaModel)
    } else descriptor
    if (d.widthAXIAddress < board.minAxiAddrWidth) {
      println(
        s"[HardCilkEmitter] ${board.fpgaModel}: widening widthAXIAddress ${d.widthAXIAddress} -> " +
          s"${board.minAxiAddrWidth} to reach HBM at 0x${board.hbmBaseAddress.toString(16)} " +
          s"(+${board.hbmSizeBytes >> 30} GB)"
      )
      d = d.copy(widthAXIAddress = board.minAxiAddrWidth)
    }
    (cfg.copy(board = board), d)
  }

  /** The Vitis management convention comes from `-x` alone; flag a stale JSON key. */
  private def warnOnLegacyVitisKey(jsonPath: String): Unit = {
    val source = scala.io.Source.fromFile(jsonPath)
    val raw = try source.mkString finally source.close()
    if (raw.contains("\"isVitisProject\"")) {
      println(
        "[HardCilkEmitter] WARNING: this JSON still sets 'isVitisProject'. That key is ignored: " +
          "the Vitis management convention (32-bit control port, 0x10 register offset) is now " +
          "applied only to the -x/--vitis flow, and only to its own RTL. Remove the key from the " +
          "descriptor."
      )
    }
  }
}
