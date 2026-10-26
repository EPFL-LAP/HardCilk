package CpuIntegration

import Descriptors._
import Descriptors.DescriptorJSON._
import SoftwareUtil.cpu.ProjectGenerator
import Util.{ArgParser, BuilderConfig}
import java.nio.file.{Files, Paths}

object ProjectGenerationCheck extends App {
  val descriptor = parseJsonFile[FullSysGenDescriptor]("cpu_templates/fibonacci/fibonacci_global.json")
  val output = Files.createTempDirectory("hardcilk-cpu-project-")
  val flags = ArgParser.parseArgs(Array("-k", "--project-template", "cpu_templates/fibonacci",
    "cpu_templates/fibonacci/fibonacci_global.json")).get
  assert(flags.project_aie_generation && !flags.rtl_generation)
  ProjectGenerator.generate(descriptor, flags, output, "fib")
  val project = output.resolve("software/cpu_fib")
  def read(relative: String) = Files.readString(project.resolve(relative))
  assert(read("src/cpu/generated/fib/hardcilk_task.h").contains("HC_GLOBAL_ROUTE_fib_remote 1"))
  assert(read("src/cpu/generated/fib_remote/hardcilk_task.h").contains("HC_GLOBAL_ROUTE_fib 1"))
  assert(read("src/cpu/generated/fib/hardcilk_task.h").contains("HC_CLOSURE_TYPE_fib_cont0 0"))
  assert(read("project.json").contains("\"count\" : 1"))
  assert(!Files.exists(project.resolve("src/aie")))
  assert(Files.isRegularFile(project.resolve("src/host/projects/fibonacci/src/main.cpp")))
  assert(Files.isRegularFile(project.resolve("src/host/generated/include/FullSysGenDescriptor.h")))
  val worker = project.resolve("src/cpu/fib/worker.c")
  Files.writeString(worker, "// local application edit\n")
  Files.writeString(project.resolve("Makefile"), "stale generated file\n")
  ProjectGenerator.generate(descriptor, flags, output, "fib")
  assert(Files.readString(worker) == "// local application edit\n")
  assert(read("Makefile").contains("scripts/project.py"))
  def rejected(body: => Unit): Unit = {
    var failed = false
    try body catch {case _: IllegalArgumentException => failed = true}
    assert(failed, "invalid project configuration accepted")
  }
  rejected(ProjectGenerator.validate(descriptor.copy(directMemoryPorts=false), flags))
  rejected(ProjectGenerator.validate(descriptor, flags.copy(project_sc_generation=true)))
  rejected(ProjectGenerator.validate(descriptor, flags.copy(cpu_repo="/missing-cpu-repository")))
  rejected(ProjectGenerator.validate(descriptor.copy(taskDescriptors=descriptor.taskDescriptors.map(t =>
    if(t.name=="fib_cont0") t.copy(fineGrainedCpu=None) else t)), flags))
  val first = descriptor.taskDescriptors.head
  rejected(first.copy(fineGrainedCpu=first.fineGrainedCpu.map(_.copy(instructionMemoryWords=1000))).validate())
  // Repeated PEs and per-type core settings must survive into generated files.
  val scaled = descriptor.copy(taskDescriptors=descriptor.taskDescriptors.map(t =>
    if(t.name=="fib") t.copy(numProcessingElements=2,
      fineGrainedCpu=t.fineGrainedCpu.map(_.copy(numThreads=8, instructionMemoryWords=2048))) else t))
  ProjectGenerator.generate(scaled, flags, output, "scaled")
  val scaledConfig = Files.readString(output.resolve("software/cpu_scaled/config/cpu/fib.json"))
  assert(scaledConfig.contains("\"numThreads\" : 8") && scaledConfig.contains("\"instructionMemoryWords\" : 2048"))
  println(s"PASS CPU project generation, template ownership, routing, settings, and validation ($output)")
}
