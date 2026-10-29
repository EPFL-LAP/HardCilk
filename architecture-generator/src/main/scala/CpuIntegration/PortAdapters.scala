package CpuIntegration

import chisel3._
import chext.amba.axi4s
import Descriptors._
import HardCilk.HardCilkBuilder.PortToExport

/** Shared by the production generator and integration harness. All changes are
  * opt-in; legacy exports and their names/configurations are untouched.
  */
object PortAdapters {
  def attach(desc: FullSysGenDescriptor, ports: Seq[PortToExport],
      resolve: PortDescriptor => axi4s.Interface,
      record: (String, axi4s.Config, Boolean) => Unit): Seq[Bool] = {
    val faults = scala.collection.mutable.ArrayBuffer.empty[Bool]
    // Memory readers use the same 64-bit task streams as CPUs.
    for (task <- desc.taskDescriptors if task.hasStreamPe;
         streamWidth = task.fineGrainedCpu.map(_.streamWidth).getOrElse(64);
         pe <- 0 until task.numProcessingElements) {
      val selected = ports.filter(p => p.pePortDescriptor.parentName == task.name && p.pePortDescriptor.parentIndex == pe)
      def matching(kind: String) = selected.filter(_.pePortDescriptor.portType == kind).sortBy(_.pePortDescriptor.portIndex)
      def external(kind: String, cfg: axi4s.Config, fromCpu: Boolean): axi4s.Interface =
        chisel3.experimental.noPrefix {
          val name = s"BindTo_PE_${task.name}_${pe}_$kind"
          record(name, cfg, fromCpu)
          IO(if (fromCpu) axi4s.Slave(cfg) else axi4s.Master(cfg)).suggestName(name)
        }
      val serializer = Module(new TaskSerializer(task.widthTask, streamWidth))
      serializer.input <> resolve(matching("taskIn").head.subsystemPortDescriptor)
      external("taskIn", serializer.output.cfg, false) <> serializer.output
      matching("taskOut").foreach { p =>
        val assembler = Module(new TaskAssembler(streamWidth, task.widthTask))
        assembler.input <> external("taskOut", assembler.input.cfg, true)
        resolve(p.subsystemPortDescriptor) <> assembler.output
        faults += assembler.fault
      }
      val globals = matching("taskOutGlobal")
      if (globals.nonEmpty) {
        val router = Module(new TaskRouter(streamWidth, globals.map(p => resolve(p.subsystemPortDescriptor).cfg.wData)))
        router.input <> external("taskOutGlobal", router.input.cfg, true)
        globals.zip(router.outputs).foreach { case (p, out) => resolve(p.subsystemPortDescriptor) <> out }
        faults += router.fault
      }
      val arguments = matching("argOut")
      if (arguments.nonEmpty) {
        val router = Module(new ArgumentRouter(arguments.size))
        router.input <> external("sendArgument", router.input.cfg, true)
        arguments.zip(router.outputs).foreach { case (p, out) => resolve(p.subsystemPortDescriptor) <> out }
        faults += router.fault
      }
      matching("closureIn").foreach { p =>
        val source = resolve(p.subsystemPortDescriptor)
        external("closureIn", source.cfg, false) <> source
      }
      require(matching("mallocIn").isEmpty, "CPU integration has no mallocIn endpoint")
    }
    faults.toSeq
  }
}
