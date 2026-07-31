package Util

import _root_.circt.stage.ChiselStage
import Descriptors._
import HardCilk.HardCilk


object HardCilkEmitterUtil {

  /**
    * What the downstream generators need to know about the HBM masters the RTL
    * ended up exporting.
    *
    * @param numHbmPortExports number of `m_axi_XX` masters on the top level
    * @param hbmPortWidths     data width of each exported `m_axi_XX` master
    * @param ramaPortIndices   the subset of those indices that belong to a
    *                          `generateRAMA` task and want a RAMA IP in front of
    *                          their HBM slave
    * @param hbmPortLabels     `<kind>/<task>` of the unit behind each exported
    *                          master (`pe.gmem/pageRank`, `sched.vss/update`,
    *                          ...). Only filled in `--raw-hbm-ports` mode, where
    *                          each exported port is exactly one master; the TCL
    *                          generators use it to spread different unit kinds
    *                          over the aggregating SmartConnects.
    */
  case class RtlGenResult(
      numHbmPortExports: Int,
      hbmPortWidths: Seq[Int],
      ramaPortIndices: Seq[Int],
      hbmPortLabels: Seq[String] = Seq.empty
  )

  def basename(path: String): String = path.split("/").last.split("\\.").head


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

  /**
    * Tag each exported `m_axi_XX` master with the number of transactions it can
    * keep in flight.
    *
    * A block design instantiates the HardCilk top with `create_bd_cell -type
    * module`, so Vivado has no IP-XACT to read and infers the AXI interfaces from
    * the port names alone. The inferred interfaces come out with
    * NUM_READ_OUTSTANDING = NUM_WRITE_OUTSTANDING = 2, and those properties are
    * read-only on a module-reference pin -- the block-design TCL cannot raise
    * them. Every SmartConnect on the HBM path then sizes its transaction tracker
    * for two outstanding transactions and throttles the design accordingly.
    *
    * The supported override is a Verilog attribute on one port of the interface,
    * which Vivado picks up when it infers the interface and then propagates
    * downstream (SmartConnect, RAMA, ...) during `validate_bd_design`.
    *
    * @param verilog          the post-sv2v top-level Verilog
    * @param numPorts         number of exported `m_axi_XX` masters
    * @param numOutstanding   value to advertise for reads and writes
    */
  def annotateAxiOutstanding(verilog: String, numPorts: Int, numOutstanding: Int): String = {
    val attribute =
      s"""(* X_INTERFACE_PARAMETER = "NUM_READ_OUTSTANDING $numOutstanding, """ +
        s"""NUM_WRITE_OUTSTANDING $numOutstanding" *)"""

    (0 until numPorts).foldLeft(verilog) { case (text, i) =>
      val port = f"m_axi_${i}%02d"
      // Anchor on the port *declaration* (`output wire [33:0] m_axi_00_ARADDR;`),
      // not the earlier port list or the later instance connections. Which signal
      // comes first depends on the bus flavour -- a read-only master has no AW*
      // ports -- so take whichever declaration of this interface appears first.
      val decl = raw"(?m)^([ \t]*)((?:input|output)\s+wire\s+(?:\[[^\]]*\]\s*)?" +
        java.util.regex.Pattern.quote(port) + raw"_\w+\s*;)"
      decl.r.findFirstMatchIn(text) match {
        case Some(m) =>
          val indent = m.group(1)
          text.patch(m.start, s"$indent$attribute\n$indent${m.group(2)}", m.end - m.start)
        case None =>
          println(
            s"[HardCilkEmitter] WARNING: no port declaration found for $port; it keeps Vivado's " +
              s"default of 2 outstanding transactions, which will throttle its SmartConnect."
          )
          text
      }
    }
  }

  /**
  * A method to generate RTL called by HardCilk Emitter
  */
  def generateRTL(
      systemDescriptor: FullSysGenDescriptor,
      pathInputJsonFile: String,
      outputDirPathRTL: String,
      flags: BuilderConfig,
      isSimulation: Boolean
  ): RtlGenResult = {
    // for task in system descriptor copy all the files in the peHDLPath to the outputDirRTL
    systemDescriptor.taskDescriptors.foreach { task =>
      val peHDLPath = task.peHDLPath
      if(peHDLPath != ""){ 
        val peHDLPathFiles = new java.io.File(peHDLPath).listFiles()
        peHDLPathFiles.foreach { file =>
          val fileName = file.getName()
          val fileContent = readFile(file.getAbsolutePath())
          writeFile(s"$outputDirPathRTL/$fileName", fileContent)
        }
      }
    }

    // Copy all the files in the src/main/resources/ to the outputDirRTL except the DualPortBRAM_sim.v
    val resourcesPath = "src/main/resources/"
    val synthDirectory = f"${outputDirPathRTL}/synth"
    val questaDirectory = f"${outputDirPathRTL}/questa"
    new java.io.File(synthDirectory).mkdirs()
    new java.io.File(questaDirectory).mkdirs()

    val resourcesFiles = new java.io.File(resourcesPath).listFiles()
    
    val listOfFilesForRTL = List("DualPortBRAM_sim.v", "DualPortBRAM_xpm.v", "top.v", "u55c.xdc")
    val listOfFilesForQuesta = List("top_sim.sv", "main_sim.sv")

    writeFile(s"$outputDirPathRTL/empty.vh", "")
    writeFile(s"$outputDirPathRTL/empty.sv", "")
    resourcesFiles.foreach { file =>
      val fileName = file.getName()
      val fileContent = readFile(file.getAbsolutePath())

      if (fileName.startsWith("DualPortBRAM")) {
        if (
          (isSimulation && fileName == "DualPortBRAM_sim.v") || (!isSimulation && fileName == "DualPortBRAM_xpm.v")
        ) {
          writeFile(s"$outputDirPathRTL/DualPortBRAM.v", fileContent)
        }
      } else if (listOfFilesForQuesta.contains(fileName)) {
        writeFile(s"$questaDirectory/$fileName", fileContent)
      } else {
        writeFile(s"$synthDirectory/$fileName", fileContent)
      }
    }

    var numHbmPortExports = 0
    var hbmPortWidths = Seq.empty[Int]
    var ramaPortIndices = Seq.empty[Int]
    var hbmPortLabels = Seq.empty[String]
    ChiselStage.emitSystemVerilogFile(
      {
        val module = new HardCilk(
          fullSysGenDescriptor = systemDescriptor,
          outputDirPathRTL = outputDirPathRTL,
          debug = flags.debug,
          reduceAxi = flags.reduce_axi,
          unitedHbm = true,
          isSimulation = isSimulation,
          argumentNotifierCutCount = 1,
          rawHbmPorts = flags.rawHbmPorts
        )
        numHbmPortExports = module.numHbmPortExports
        // Data width of each exported HBM master, in m_axi_00, m_axi_01, ... order.
        // The Vitis flow generator reads this back to size register slices/ports.
        hbmPortWidths = module.axiOuts.map(_.cfg.wData).toSeq
        ramaPortIndices = module.ramaPortIndices
        hbmPortLabels = module.hbmPortLabels
        module
      },
      Array(f"--target-dir=${outputDirPathRTL}"),
      Array("--disable-all-randomization")
    )

    // Emit a small metadata file describing the exported HBM port widths so that
    // the (separately-invoked) Vitis flow generator can build width-correct
    // register slices and interface ports instead of assuming a fixed 256 bits.
    writeFile(s"$outputDirPathRTL/hbm_port_widths.txt", hbmPortWidths.mkString("\n"))

    // Same idea for the exported masters that asked for a RAMA IP: one m_axi_XX
    // index per line (empty file when no task sets "generateRAMA").
    writeFile(s"$outputDirPathRTL/hbm_rama_ports.txt", ramaPortIndices.mkString("\n"))

    // And the producing unit behind each exported master, one `<kind>/<task>` per
    // line in m_axi_00, m_axi_01, ... order. Empty unless --raw-hbm-ports is set,
    // since a muxed port has no single producer.
    writeFile(s"$outputDirPathRTL/hbm_port_labels.txt", hbmPortLabels.mkString("\n"))

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
      writeFile(vFilePath, annotateAxiOutstanding(sv2vOutput, numHbmPortExports, systemDescriptor.axiNumOutstanding))

    } else {
      println(s"Error: File $svFilePath does not exist.")
    }

    RtlGenResult(numHbmPortExports, hbmPortWidths, ramaPortIndices, hbmPortLabels)
  }
}
