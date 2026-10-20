package SoftwareUtil

import Descriptors._
import Util.{BuilderConfig, HardCilkEmitterUtil}
import HardCilkEmitterUtil.{readFile, writeFile}
import java.nio.file.{Files, Path, Paths, StandardCopyOption}

/**
  * Emits the C++ driver/software tree that drives a generated design.
  *
  * There is one tree for all three flows. The driver ABI is identical between
  * them -- `hardCilkDriver` only ever reads and writes 64-bit registers at the
  * management base addresses -- so a flow differs only in its `Memory` backend
  * (`memIO_questa.h` / `memIO_pcie.h` / `memIO_xrt.h`) and its entry point.
  *
  * The one thing that genuinely differs per flow is the generated
  * `FullSysGenDescriptor.h`: Vitis shifts every management base address by 0x10
  * to make room for the kernel's own `ap_ctrl` block. Rather than teach the
  * driver about that, both variants are emitted under the same file name in
  * different directories and CMake puts the right one on each target's include
  * path (see [[headerDirFor]]).
  */
object SoftwareProjectGenerator {

  /** Directory holding the generated headers for a given management convention. */
  private def headerDirFor(projectDir: String, vitis: Boolean): String =
    s"$projectDir/include/gen/${if (vitis) "vitis" else "nonvitis"}"

  /**
    * Emit `FullSysGenDescriptor.h` for both management conventions.
    *
    * `copy` re-runs the descriptor body, which recomputes every
    * `mgmtBaseAddresses` with the 0x10 base, so the two headers differ by exactly
    * that offset on every address.
    *
    * The two copies are built and consumed one at a time on purpose.
    * `mgmtBaseAddresses` is a mutable field of `TaskDescriptor`, and a copied
    * `FullSysGenDescriptor` shares the very same `TaskDescriptor` instances, so
    * the descriptor body of the second copy overwrites what the first one wrote.
    * Building both up front would give both headers the addresses of whichever
    * copy was created last.
    */
  def generateHeaders(
      descriptor: FullSysGenDescriptor,
      projectDir: String,
      numHbmPortExports: Int
  ): Unit = {
    Seq(false, true).foreach { isVitis =>
      val d = descriptor.copy(vitisMgmtConvention = isVitis)
      val dir = headerDirFor(projectDir, isVitis)
      Files.createDirectories(Paths.get(dir))
      CppHeaderTemplate.generateCppHeader(d, dir, numHbmPortExports)
    }
    println(
      s"[SoftwareProjectGenerator] Emitted FullSysGenDescriptor.h to " +
        s"$projectDir/include/gen/{nonvitis,vitis}"
    )
  }

  /**
    * Build the whole software tree.
    *
    * @param cfg used only to decide which CMake targets to enable: a run that did
    *            not ask for a flow should not leave a target behind that cannot
    *            be built
    */
  def generate(
      descriptor: FullSysGenDescriptor,
      jsonName: String,
      outputDirPathSC: String,
      numHbmPortExports: Int,
      cfg: BuilderConfig
  ): Unit = {
    val templateDir = new java.io.File("software_template")
    require(
      templateDir.isDirectory,
      s"[SoftwareProjectGenerator] ${templateDir.getPath} not found. The emitter must be run " +
        s"from the `architecture-generator` directory."
    )

    copyTree(templateDir.toPath, Paths.get(outputDirPathSC))

    // Rename projects/project_template -> projects/<name>
    val projectTemplate = Paths.get(s"$outputDirPathSC/projects/project_template")
    val projectDir = Paths.get(s"$outputDirPathSC/projects/$jsonName")
    if (Files.exists(projectDir)) deleteTree(projectDir)
    if (Files.exists(projectTemplate)) Files.move(projectTemplate, projectDir)
    Files.createDirectories(projectDir)

    // The SystemC target used to get its own `isSimulation = true` elaboration
    // into projects/<name>/hdl here. It is off by default (it needs verilator,
    // SystemC, sctlm and hdlscw) and unsupported, and generating it meant a full
    // second Chisel + sv2v run on every invocation, so it is no longer emitted.

    generateHeaders(descriptor, projectDir.toString, numHbmPortExports)

    // The SystemC testbench header still tracks the descriptor, so keep emitting
    // it: it is cheap, and the hand-written driver project below overwrites it
    // when it ships one of its own. Re-establish the native convention first --
    // generateHeaders left the shared TaskDescriptor addresses on the Vitis pass.
    TestBenchHeaderTemplate.generateCppHeader(
      descriptor.copy(vitisMgmtConvention = false),
      s"$projectDir/include",
      numHbmPortExports
    )

    patchProjectCMakeLists(s"$projectDir/CMakeLists.txt", jsonName, cfg.board == Util.Board.V80)
    patchRootCMakeLists(s"$outputDirPathSC/CMakeLists.txt", cfg)

    // The QuestaSim testbench is compiled by `sccom` from simulate.do rather than
    // by CMake, so its template only makes sense next to a QuestaSim project.
    if (!cfg.questa) Files.deleteIfExists(Paths.get(s"$outputDirPathSC/simulate.do"))

    overlayDriverProject(descriptor, jsonName, projectDir)

    println(s"[SoftwareProjectGenerator] Emitted software project to: $outputDirPathSC")
  }

  /**
    * Copy the hand-written driver project (its per-backend `main`, its testbench
    * headers, its data) over the generated skeleton.
    *
    * `driverSoftwarePath` in the descriptor wins; otherwise the relative
    * `../software/<name>` convention applies, resolved against the emitter's
    * working directory.
    */
  private def overlayDriverProject(
      descriptor: FullSysGenDescriptor,
      jsonName: String,
      projectDir: Path
  ): Unit = {
    val sourceProjectPath = descriptor.driverSoftwarePath.getOrElse {
      if (descriptor.mFPGASimulation || descriptor.mFPGASynth) s"../software/mfpga/$jsonName"
      else s"../software/$jsonName"
    }

    // Without it the tree has no main() and no testbench, so nothing in it can
    // be built or simulated: fail the run rather than emit a tree that looks done.
    val sourceProject = Paths.get(sourceProjectPath)
    if (!Files.isDirectory(sourceProject))
      throw new HardCilk.EmitterError(
        s"driver project '$sourceProjectPath' not found. Set 'driverSoftwarePath' in the JSON, " +
          s"or place the project at ../software/$jsonName relative to architecture-generator."
      )
    copyTree(sourceProject, projectDir)
    println(s"[SoftwareProjectGenerator] Overlaid driver project from: $sourceProjectPath")
  }

  /**
    * Point each target at the generated header matching its management
    * convention, and substitute the project name.
    *
    * The XRT target talks to a Vitis kernel and so needs the 0x10-shifted
    * addresses; the PCIe and SystemC targets drive the design's native register
    * map. The QuestaSim build is not a CMake target at all -- `sccom` compiles it
    * from `simulate.do`, which reaches into `include/gen/nonvitis` directly.
    */
  private def patchProjectCMakeLists(path: String, jsonName: String, v80: Boolean): Unit = {
    if (!Files.exists(Paths.get(path))) return
    val content = readFile(path).replace("${project_template}", jsonName)

    // Appended as its own block rather than edited into the existing
    // target_include_directories() calls: those are spelled differently per
    // target (one line for _pcie, several for _xrt), and a second
    // target_include_directories() on the same target is simply additive.
    val genIncludes =
      s"""
         |# --- generated by HardCilk ---------------------------------------------
         |# FullSysGenDescriptor.h exists once per management convention. The XRT
         |# target drives a Vitis kernel, whose register map is shifted by 0x10 to
         |# clear the kernel's own ap_ctrl block; the others use the design's native
         |# map. Giving each target its own directory keeps the driver sources
         |# identical across backends.
         |if(HC_ENABLE_SYSTEMC_TARGETS)
         |    target_include_directories($${HC_TARGET_NAME}_systemc PUBLIC $${CMAKE_CURRENT_SOURCE_DIR}/include/gen/nonvitis)
         |endif()
         |
         |if(HC_ENABLE_PCIE_TARGETS)
         |    target_include_directories($${HC_TARGET_NAME}_pcie PUBLIC $${CMAKE_CURRENT_SOURCE_DIR}/include/gen/nonvitis)
         |endif()
         |
         |if(HC_ENABLE_XRT_TARGETS)
         |    target_include_directories($${HC_TARGET_NAME}_xrt PUBLIC $${CMAKE_CURRENT_SOURCE_DIR}/include/gen/vitis)
         |endif()
         |
         |if(HC_ENABLE_QDMA_TARGETS AND TARGET $${HC_TARGET_NAME}_v80)
         |    target_include_directories($${HC_TARGET_NAME}_v80 PUBLIC $${CMAKE_CURRENT_SOURCE_DIR}/include/gen/nonvitis)
         |endif()
         |""".stripMargin

    // V80 only: hc_v80_probe (driver/tools) reads this design's decoded register
    // range (HC_NUM_CONFIG_PORTS) from the generated header, so it never touches
    // an undecoded register block (which would hang the PCIe path).
    val probeIncludes =
      if (!v80) ""
      else
        s"""
           |if(HC_ENABLE_QDMA_TARGETS AND TARGET hc_v80_probe)
           |    target_include_directories(hc_v80_probe PRIVATE $${CMAKE_CURRENT_SOURCE_DIR}/include/gen/nonvitis)
           |endif()
           |""".stripMargin

    writeFile(path, content + genIncludes + probeIncludes)
  }

  /**
    * Default the target switches to the flows this run actually built, so
    * `cmake -B build` in the generated tree configures without being told which
    * backends exist. They stay overridable with `-DHC_ENABLE_*`.
    */
  private def patchRootCMakeLists(path: String, cfg: BuilderConfig): Unit = {
    if (!Files.exists(Paths.get(path))) return
    // The default only appears as `<OPTION> ON` inside the set(); the surrounding
    // `if(NOT DEFINED <OPTION>)` has no value after the name, so a plain literal
    // replace hits exactly the line that matters.
    //
    // The V80 has no XDMA and no Vitis platform: its host program is the QDMA
    // target (`<spec>_v80`), which has no build dependencies beyond POSIX, so it
    // is on for every V80 tree; the XDMA (`_pcie`) and XRT targets are off.
    val v80 = cfg.board == Util.Board.V80
    val content = readFile(path)
      .replace("HC_ENABLE_PCIE_TARGETS ON", s"HC_ENABLE_PCIE_TARGETS ${onOff(cfg.pcie && !v80)}")
      .replace("HC_ENABLE_XRT_TARGETS ON", s"HC_ENABLE_XRT_TARGETS ${onOff(cfg.vitis && !v80)}")
      .replace("HC_ENABLE_QDMA_TARGETS OFF", s"HC_ENABLE_QDMA_TARGETS ${onOff(v80)}")
    writeFile(path, content)
  }

  private def onOff(b: Boolean): String = if (b) "ON" else "OFF"

  /** Recursive copy, overwriting existing files. */
  private def copyTree(source: Path, destination: Path): Unit = {
    val walk = Files.walk(source)
    try {
      walk.forEach { sourcePath =>
        val destinationPath = destination.resolve(source.relativize(sourcePath))
        if (Files.isDirectory(sourcePath)) Files.createDirectories(destinationPath)
        else {
          Files.createDirectories(destinationPath.getParent)
          Files.copy(sourcePath, destinationPath, StandardCopyOption.REPLACE_EXISTING)
          // Keep scripts (scripts/v80_bringup.sh, ...) runnable.
          if (Files.isExecutable(sourcePath)) destinationPath.toFile.setExecutable(true, false)
        }
      }
    } finally walk.close()
  }

  private def deleteTree(path: Path): Unit = {
    val walk = Files.walk(path)
    try walk.sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))
    finally walk.close()
  }
}
