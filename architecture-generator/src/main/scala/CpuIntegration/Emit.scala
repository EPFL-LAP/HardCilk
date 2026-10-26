package CpuIntegration

import Descriptors._
import Descriptors.DescriptorJSON._
import java.nio.file.{Files, Paths}

/** The normal HardCilk generator, with a deterministic output directory. */
object RuntimeEmitter {
  def emit(descriptor: FullSysGenDescriptor, output: String): Unit = {
    descriptor.validate()
    descriptor.validateCpuIntegration()
    Files.createDirectories(Paths.get(output))
    _root_.circt.stage.ChiselStage.emitSystemVerilogFile(
      new HardCilk.HardCilk(descriptor, output, false, 2, false, true, 1),
      Array("--target-dir", output), Array("--disable-all-randomization"))
    dumpJsonFile(output + "/architecture.json", descriptor)
    // Host/software tooling consumes this ABI without reverse-engineering names.
    import io.circe.Json
    val interfaces = descriptor.taskDescriptors.flatMap { task => task.fineGrainedCpu.map { cpu =>
      val globals = descriptor.spawnList.getOrElse(task.name, Nil).filterNot(_ == task.name)
      val arguments = descriptor.sendArgumentList.getOrElse(task.name, Nil)
      Json.obj("task" -> Json.fromString(task.name), "count" -> Json.fromInt(task.numProcessingElements),
        "streamWidth" -> Json.fromInt(cpu.streamWidth), "taskBeats" -> Json.fromInt(task.widthTask / cpu.streamWidth),
        "globalRoutes" -> Json.arr(globals.zipWithIndex.map { case (name, i) => Json.obj(
          "id" -> Json.fromInt(i + 1), "target" -> Json.fromString(name),
          "payloadBits" -> Json.fromInt(descriptor.taskDescriptors.find(_.name == name).get.widthTask)) }: _*),
        "argumentRoutes" -> Json.arr(arguments.map(Json.fromString): _*),
        "argumentRouteBits" -> Json.fromInt(chisel3.util.log2Ceil(math.max(1, arguments.size))),
        "slabPool" -> cpu.slabPool.map(Json.fromString).getOrElse(Json.Null),
        "slabBytes" -> Json.fromInt(cpu.slabPool.map(name => descriptor.taskDescriptors.find(_.name == name).get.allocatorSlabBytes).getOrElse(0)))
    } }
    Files.writeString(Paths.get(output, "cpu-interfaces.json"), Json.arr(interfaces: _*).spaces2)
  }
}

object Emit extends App {
  require(args.length == 2, "CpuIntegration.Emit architecture.json output-directory")
  RuntimeEmitter.emit(parseJsonFile[FullSysGenDescriptor](args(0)), args(1))
}
