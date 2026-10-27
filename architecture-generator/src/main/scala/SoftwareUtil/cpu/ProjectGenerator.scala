package SoftwareUtil.cpu

import Descriptors._
import Descriptors.DescriptorJSON._
import Util.BuilderConfig
import io.circe.Json
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import scala.jdk.CollectionConverters._

/** CPU companion to the legacy -k AIE/HLS project generator. Application files
  * are copied only when absent; infrastructure is always regenerated.
  */
object ProjectGenerator {
  private val identifier = "[A-Za-z_][A-Za-z0-9_]*"
  def validate(desc: FullSysGenDescriptor, flags: BuilderConfig): Unit = {
    desc.validate()
    require(desc.name.matches(identifier) && desc.taskDescriptors.forall(_.name.matches(identifier)),
      "CPU project architecture/task names must be C/RTL identifiers")
    require(desc.taskDescriptors.forall(_.fineGrainedCpu.nonEmpty),
      "CPU project generation currently requires all PEs to be fineGrainedCpu (mixed AIE/HLS projects are not supported)")
    require(desc.directMemoryPorts, "CPU projects require directMemoryPorts=true for the portable external AXI boundary")
    require(!flags.project_sc_generation,
      "Use -k for the CPU project; the legacy -p/-a SystemC project flow is not compatible")
    if (flags.project_template.nonEmpty)
      require(Files.isDirectory(Paths.get(flags.project_template)), "CPU application template directory does not exist")
    if (flags.cpu_repo.nonEmpty)
      require(Files.isRegularFile(Paths.get(flags.cpu_repo, "src/main/scala/cpu/finegrained/HardCilkCpu.scala")),
        "--cpu-repo must point to the CPU checkout containing HardCilkCpu.scala")
  }
  private def write(path: Path, value: String): Unit = {
    Files.createDirectories(path.getParent)
    Files.writeString(path, value)
  }
  private def copyTree(source: Path, target: Path, replace: Boolean): Unit = {
    if (!Files.exists(source)) return
    val stream = Files.walk(source)
    try stream.iterator.asScala.filter(p => Files.isRegularFile(p) &&
      !source.relativize(p).iterator.asScala.exists(_.toString == "__pycache__")).foreach { src =>
      val dst = target.resolve(source.relativize(src))
      Files.createDirectories(dst.getParent)
      if (replace) Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
      else if (!Files.exists(dst)) Files.copy(src, dst)
      else if (!java.util.Arrays.equals(Files.readAllBytes(src), Files.readAllBytes(dst)))
        println(s"Preserving existing application file: $dst (template differs; use a fresh output directory to refresh)")
    } finally stream.close()
  }
  def generate(desc: FullSysGenDescriptor, flags: BuilderConfig, output: Path, stem: String): Unit = {
    validate(desc, flags)
    val root = output.resolve("software").resolve(s"cpu_$stem")
    Files.createDirectories(root)
    copyTree(Paths.get("cpu_project_template"), root, replace = true)
    copyTree(Paths.get("software_template/driver"), root.resolve("src/host/driver"), replace = true)
    if (flags.project_template.nonEmpty) {
      val template = Paths.get(flags.project_template)
      for (folder <- Seq("src/cpu", "src/host/projects", "tests", "assets"))
        copyTree(template.resolve(folder), root.resolve(folder), replace = false)
    }
    val applications = root.resolve("src/host/projects")
    if (!Files.isDirectory(applications)) {
      Files.createDirectories(applications.resolve(stem).resolve("src"))
      Files.createDirectories(applications.resolve(stem).resolve("include"))
      write(applications.resolve(stem).resolve("README.md"),
        "Add application host sources under src/ and headers under include/. See ../../runtime/simulation.hpp.\n")
    }
    dumpJsonFile(root.resolve("architecture.json").toString, desc)
    val tasks = desc.taskDescriptors.map { task =>
      val cpu = task.fineGrainedCpu.get
      val slabBytes = cpu.slabPool.map(n => desc.taskDescriptors.find(_.name == n).get.allocatorSlabBytes).getOrElse(4096)
      val closures = desc.spawnNextList.getOrElse(task.name, Nil)
      val sizes = closures.map(n => desc.taskDescriptors.find(_.name == n).get.widthTask / 8)
      require(sizes.forall(n => n > 0 && n <= slabBytes), s"${task.name}: continuation does not fit CPU slab")
      val config = Json.obj("name" -> Json.fromString("HardCilkCpu"),
        "taskWidth" -> Json.fromInt(task.widthTask), "streamWidth" -> Json.fromInt(cpu.streamWidth),
        "slabBytes" -> Json.fromInt(slabBytes), "closureBytes" -> Json.arr((if (sizes.isEmpty) List(32) else sizes).map(Json.fromInt): _*),
        "numThreads" -> Json.fromInt(cpu.numThreads), "instructionMemoryWords" -> Json.fromInt(cpu.instructionMemoryWords),
        "taskQueueDepth" -> Json.fromInt(cpu.taskQueueDepth), "memoryAddressWidth" -> Json.fromInt(64), "memoryDataWidth" -> Json.fromInt(256))
      write(root.resolve(s"config/cpu/${task.name}.json"), config.spaces2)
      val globals = desc.spawnList.getOrElse(task.name, Nil).filterNot(_ == task.name)
      val arguments = desc.sendArgumentList.getOrElse(task.name, Nil)
      val defines = Seq(s"#define HC_TASK_BEATS ${task.widthTask / cpu.streamWidth}",
        s"#define HC_STREAM_BITS ${cpu.streamWidth}", s"#define HC_SLAB_BYTES $slabBytes", "#define HC_LOCAL_ROUTE 0") ++
        globals.zipWithIndex.map { case (n, i) => s"#define HC_GLOBAL_ROUTE_${n} ${i + 1}" } ++
        arguments.zipWithIndex.map { case (n, i) => s"#define HC_ARGUMENT_ROUTE_${n} $i" } ++
        Seq(s"#define HC_ARGUMENT_ROUTE_BITS ${chisel3.util.log2Ceil(math.max(1, arguments.size))}") ++
        closures.zipWithIndex.map { case (n, i) => s"#define HC_CLOSURE_TYPE_${n} $i" }
      write(root.resolve(s"src/cpu/generated/${task.name}/hardcilk_task.h"), "#pragma once\n" + defines.mkString("\n") + "\n")
      val imageBytes = cpu.instructionMemoryWords * 4
      write(root.resolve(s"config/cpu/${task.name}.ld"), s"""ENTRY(_start)
SECTIONS {
  . = 0;
  .text : { *(.text.start) *(.text*) }
  .rodata : { *(.rodata*) }
  /DISCARD/ : { *(.comment) *(.eh_frame*) *(.riscv.attributes) }
  ASSERT(SIZEOF(.text) <= $imageBytes, "CPU image exceeds instruction SRAM")
  .data : { *(.data*) *(.sdata*) }
  .bss : { *(.bss*) *(.sbss*) *(COMMON) }
  ASSERT(SIZEOF(.data) + SIZEOF(.bss) + SIZEOF(.rodata) == 0, "Firmware must not require initialized scratchpad data")
}
""")
      Files.createDirectories(root.resolve(s"src/cpu/${task.name}"))
      Json.obj("name" -> Json.fromString(task.name), "count" -> Json.fromInt(task.numProcessingElements),
        "taskBeats" -> Json.fromInt(task.widthTask / cpu.streamWidth), "slabBytes" -> Json.fromInt(slabBytes),
        "numThreads" -> Json.fromInt(cpu.numThreads))
    }
    val host = root.resolve("src/host/generated/include")
    Files.createDirectories(host)
    SoftwareUtil.CppHeaderTemplate.generateCppHeader(desc, host.toString, 1)
    write(root.resolve("project.json"), Json.obj("format" -> Json.fromInt(1),
      "name" -> Json.fromString(desc.name), "tasks" -> Json.arr(tasks: _*),
      "hardcilkRepo" -> Json.fromString(Paths.get("..").toAbsolutePath.normalize.toString),
      "cpuRepo" -> Json.fromString(if (flags.cpu_repo.isEmpty) "" else Paths.get(flags.cpu_repo).toAbsolutePath.normalize.toString),
      "applicationTemplate" -> Json.fromString(if (flags.project_template.isEmpty) "" else Paths.get(flags.project_template).toAbsolutePath.normalize.toString)).spaces2)
    if (flags.rtl_generation) copyTree(output.resolve("rtl"), root.resolve(s"src/rtl/${desc.name}"), replace = true)
    println(s"Generated CPU project: $root")
    println("Application sources: src/cpu/<task>/ and src/host/projects/<application>/. Build with make sim.")
  }
}
