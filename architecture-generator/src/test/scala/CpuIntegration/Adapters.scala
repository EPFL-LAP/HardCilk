package CpuIntegration

import chisel3._
import Descriptors._
import Descriptors.DescriptorJSON._

class AdapterTestTop extends Module {
  val router = Module(new TaskRouter(32, Seq(64,128,256)))
  val input = IO(chiselTypeOf(router.input)); router.input <> input
  val out0 = IO(chiselTypeOf(router.outputs(0))); out0 <> router.outputs(0)
  val out1 = IO(chiselTypeOf(router.outputs(1))); out1 <> router.outputs(1)
  val out2 = IO(chiselTypeOf(router.outputs(2))); out2 <> router.outputs(2)
  val fault = IO(Output(Bool())); fault := router.fault
  val serializer = Module(new TaskSerializer(64,32))
  val wide = IO(chiselTypeOf(serializer.input)); serializer.input <> wide
  val narrow = IO(chiselTypeOf(serializer.output)); narrow <> serializer.output
  val arguments = Module(new ArgumentRouter(3))
  val argument = IO(chiselTypeOf(arguments.input)); arguments.input <> argument
  val argOut = IO(chiselTypeOf(arguments.outputs)); argOut <> arguments.outputs
  val argFault = IO(Output(Bool())); argFault := arguments.fault
}
object AdapterTestEmit extends App {
  _root_.circt.stage.ChiselStage.emitSystemVerilogFile(new AdapterTestTop,
    Array("--target-dir", args(0)), Array("--disable-all-randomization"))
}
object DescriptorCheck extends App {
  def rejected(body: => Unit): Unit = {
    var failed=false
    try body catch {case _: IllegalArgumentException => failed=true}
    assert(failed,"invalid CPU descriptor was accepted")
  }
  val legacy = parseJsonFile[FullSysGenDescriptor]("taskDescriptors/fibonacci_nohdl.json")
  legacy.validate()
  assert(legacy.taskDescriptors.forall(_.fineGrainedCpu.isEmpty))
  assert(legacy.closureGrantList("fib") == legacy.spawnNextList("fib"))
  assert(!legacy.directMemoryPorts)
  val cpu = parseJsonFile[FullSysGenDescriptor]("taskDescriptors/fibonacci_cpu.json")
  cpu.validate()
  val fib = cpu.taskDescriptors.head
  rejected(fib.copy(generateSpawnNextWriteBuffer=true).validate())
  rejected(fib.copy(fineGrainedCpu=Some(FineGrainedCpuDescriptor(24,Some("fib_cont0")))).validate())
  rejected(cpu.copy(taskDescriptors=fib.copy(fineGrainedCpu=Some(FineGrainedCpuDescriptor())) :: cpu.taskDescriptors.tail).validate())
  rejected(cpu.copy(taskDescriptors=fib.copy(fineGrainedCpu=Some(FineGrainedCpuDescriptor(32,Some("missing")))) :: cpu.taskDescriptors.tail).validate())
  rejected(cpu.copy(taskDescriptors=fib :: cpu.taskDescriptors.tail.map(_.copy(allocatorSlabBytes=0))).validate())
  rejected(cpu.copy(widthAddress=32).validate())
  rejected(cpu.copy(mallocList=Map("fib" -> List("fib_cont0"))).validate())
  rejected(cpu.copy(taskDescriptors=fib :: cpu.taskDescriptors.tail.map(_.copy(
    fineGrainedCpu=None, variableSpawn=true))).validate())
  rejected(cpu.copy(spawnList=Map("fib" -> List("fib_cont0")),
    taskDescriptors=fib :: cpu.taskDescriptors.tail.map(_.copy(widthTask=16))).validate())
  println("PASS CPU descriptor validation and legacy defaults")
}

object HostHeaderEmit extends App {
  val descriptor = parseJsonFile[FullSysGenDescriptor](args(0))
  descriptor.validate()
  java.nio.file.Files.createDirectories(java.nio.file.Paths.get(args(1)))
  SoftwareUtil.CppHeaderTemplate.generateCppHeader(descriptor, args(1), 1)
}
