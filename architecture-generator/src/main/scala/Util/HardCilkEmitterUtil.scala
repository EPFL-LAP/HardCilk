package Util

import _root_.circt.stage.ChiselStage
import Descriptors._
import HardCilk.HardCilk

import java.nio.file.{Files, Paths}
import scala.util.matching.Regex

object VerilogResetConverter {

  /** Converts active-high `reset` to active-low `reset_n` in the top-level
    * module declaration of a generated Verilog file.
    *
    * Specifically, for the named module it:
    *   1. Replaces `input reset;` → `input reset_n;\n\twire reset = ~reset_n;`
    *   2. Replaces `input reset,` → `input reset_n,` in the port list header
    *
    * All internal uses of `reset` are left untouched — the `wire reset = ~reset_n`
    * handles them transparently.
    *
    * @param verilogPath  Path to the .sv / .v file to modify in-place
    * @param moduleName   Name of the top-level module to patch (others untouched)
    */
  def convertToActivelow(verilogPath: String, moduleName: String): Unit = {
    val path    = Paths.get(verilogPath)
    val content = new String(Files.readAllBytes(path))

    val patched = patchModule(content, moduleName)

    if (patched != content) {
      Files.write(path, patched.getBytes)
      println(s"[VerilogResetConverter] Patched active-low reset in: $verilogPath")
    } else {
      println(s"[VerilogResetConverter] WARNING: No reset port found in module '$moduleName' in $verilogPath")
    }
  }

  /** Core transformation — operates on the string, returns patched string. */
  private def patchModule(content: String, moduleName: String): String = {

    // ── Locate the target module's body ──────────────────────────────────────
    // We find `module <name> (` and then scan forward to `endmodule`,
    // only patching within that span so submodules are untouched.
    val moduleStart = findModuleStart(content, moduleName)
    if (moduleStart < 0) return content

    val moduleEnd = content.indexOf("endmodule", moduleStart)
    if (moduleEnd < 0) return content

    val before = content.substring(0, moduleStart)
    val body   = content.substring(moduleStart, moduleEnd)
    val after  = content.substring(moduleEnd)

    // ── Two patterns to handle both port-list and declaration forms ───────────

    // Pattern 1: standalone declaration  →  `\tinput reset;\n`
    // Replace with declaration + wire alias on the next line
    val declPattern: Regex = """(\tinput reset;)""".r
    val bodyAfterDecl = declPattern.replaceFirstIn(
      body,
      "\tinput reset_n;\n\twire reset = ~reset_n;"
    )

    // Pattern 2: port list entry  →  `\treset,\n`  (the bare name in the header)
    // Chisel emits the port *name* in the port list at the top, then declares
    // it below. The port-list line looks like just `\treset,`
    val portListPattern: Regex = """(\treset,\n)""".r
    val bodyPatched = portListPattern.replaceFirstIn(
      bodyAfterDecl,
      "\treset_n,\n"
    )

    before + bodyPatched + after
  }

  /** Returns the character index of `module <name>` in content, or -1. */
  private def findModuleStart(content: String, moduleName: String): Int = {
    val needle = s"module $moduleName ("
    val idx    = content.indexOf(needle)
    idx
  }
}

object HardCilkEmitterUtil {

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
  * A method to generate RTL called by HardCilk Emitter
  */
  def generateRTL(
      systemDescriptor: FullSysGenDescriptor,
      pathInputJsonFile: String,
      outputDirPathRTL: String,
      flags: BuilderConfig,
      isSimulation: Boolean
  ): Int = {
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
    ChiselStage.emitSystemVerilogFile(
      {
        val module = new HardCilk(
          fullSysGenDescriptor = systemDescriptor,
          outputDirPathRTL = outputDirPathRTL,
          debug = flags.debug,
          reduceAxi = flags.reduce_axi,
          unitedHbm = true,
          isSimulation = isSimulation,
          argumentNotifierCutCount = 1
        )
        numHbmPortExports = module.numHbmPortExports
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

    VerilogResetConverter.convertToActivelow(vFilePath, systemDescriptor.name)

    numHbmPortExports
  }
}