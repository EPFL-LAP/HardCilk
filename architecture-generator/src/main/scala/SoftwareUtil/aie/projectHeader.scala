package SoftwareUtil.aie

import Descriptors._
import java.io.PrintWriter
import scala.collection.mutable

object ProjectHeaderTemplate {

  def generateProjectHeader(descriptor: FullSysGenDescriptor, projectFolder: String): Unit = {
    val connections = descriptor.getSystemConnectionsDescriptor().connections
    val taskMap = descriptor.taskDescriptors.map(t => t.name -> t).toMap

    // Build per-PE port information.
    val peTaskPorts = mutable.Map[String, mutable.Map[Int, TaskPEPortInfo]]()
    descriptor.taskDescriptors.foreach(task => {
      val peMap = mutable.Map[Int, TaskPEPortInfo]()
      for (peIdx <- 0 until task.numProcessingElements) {
        peMap(peIdx) = TaskPEPortInfo(
          task = task,
          peIndex = peIdx,
          inputs = mutable.ListBuffer[PortInfo](),
          outputs = mutable.ListBuffer[PortInfo]()
        )
      }
      peTaskPorts(task.name) = peMap
    })

    def addUnique(list: mutable.ListBuffer[PortInfo], port: PortInfo): Unit = {
      if (!list.exists(_.plioBaseName == port.plioBaseName)) {
        list += port
      }
    }

    // Extract task-side port information from HardCilk<->PE connections.
    connections.foreach { conn =>
      if (conn.srcPort.parentType == "PE" && conn.dstPort.parentType == "HardCilk") {
        val taskName = conn.srcPort.parentName
        val peIdx = conn.srcPort.parentIndex
        if (peTaskPorts.contains(taskName) && peTaskPorts(taskName).contains(peIdx)) {
          val peSuffix = suffixForPe(peIdx, taskMap(taskName).numProcessingElements)
          addUnique(peTaskPorts(taskName)(peIdx).outputs, PortInfo(
            plioBaseName = s"${taskName}${peSuffix}_${conn.srcPort.portType}",
            bitWidth = conn.bitWidth,
            connectionType = conn.connectionType
          ))
        }
      } else if (conn.srcPort.parentType == "HardCilk" && conn.dstPort.parentType == "PE") {
        val taskName = conn.dstPort.parentName
        val peIdx = conn.dstPort.parentIndex
        if (peTaskPorts.contains(taskName) && peTaskPorts(taskName).contains(peIdx)) {
          val peSuffix = suffixForPe(peIdx, taskMap(taskName).numProcessingElements)
          addUnique(peTaskPorts(taskName)(peIdx).inputs, PortInfo(
            plioBaseName = s"${taskName}${peSuffix}_${conn.dstPort.portType}",
            bitWidth = conn.bitWidth,
            connectionType = conn.connectionType
          ))
        }
      }
    }

    // Expose subPE helper interfaces as external PLIO endpoints.
    // ReadSingle/ReadStream/WriteSingle are not AIE kernels.
    descriptor.subPEList.foreach { case (subPEName, subPE) =>
      val taskName = subPE.peName
      val task = taskMap(taskName)
      val req = subPE.rwRequest
      val portWidth = req.portWidth
      val thisSubPE = normalizeName(subPEName)

      for (peIdx <- 0 until task.numProcessingElements) {
        val peSuffix = suffixForPe(peIdx, task.numProcessingElements)
        if (peTaskPorts.contains(taskName) && peTaskPorts(taskName).contains(peIdx)) {
          if (req.`type` == "read") {
            val outPort =
              if (req.mode == "stream") s"readStream${portWidth}Out"
              else s"readSingle${portWidth}Out"

            addUnique(peTaskPorts(taskName)(peIdx).outputs, PortInfo(
              plioBaseName = s"${thisSubPE}${peSuffix}_$outPort",
              bitWidth = portWidth,
              connectionType = "stream"
            ))

            req.nextsubPE.foreach(nextName => {
              val nextSubPE = normalizeName(nextName)
              val inPort =
                if (req.mode == "stream") s"readStream${portWidth}In"
                else s"readSingle${portWidth}In"

              addUnique(peTaskPorts(taskName)(peIdx).inputs, PortInfo(
                plioBaseName = s"${nextSubPE}${peSuffix}_$inPort",
                bitWidth = portWidth,
                connectionType = "stream"
              ))
            })
          }

          if (req.`type` == "write") {
            val outPort =
              if (req.mode == "stream") s"writeStream${portWidth}Out"
              else s"writeSingle${portWidth}Out"

            addUnique(peTaskPorts(taskName)(peIdx).outputs, PortInfo(
              plioBaseName = s"${thisSubPE}${peSuffix}_$outPort",
              bitWidth = portWidth,
              connectionType = "stream"
            ))
          }
        }
      }
    }

    val graphCode = buildGraphCode(descriptor, peTaskPorts)

    val writer = new PrintWriter(s"$projectFolder/project.h")
    try {
      writer.write(graphCode)
    } finally {
      writer.close()
    }
  }

  private def buildGraphCode(descriptor: FullSysGenDescriptor, peTaskPorts: mutable.Map[String, mutable.Map[Int, TaskPEPortInfo]]): String = {
    val lines = mutable.ListBuffer[String]()

    // Header and includes
    lines += "#include <adf.h>"
    lines += "#include \"kernels.h\""
    lines += ""
    lines += "using namespace adf;"
    lines += ""

    // Graph class declaration
    lines += "class simpleGraph : public adf::graph {"
    lines += "private:"

    // Kernel declarations - one per PE.
    descriptor.taskDescriptors.foreach { task =>
      for (peIdx <- 0 until task.numProcessingElements) {
        val peSuffix = suffixForPe(peIdx, task.numProcessingElements)
        lines += s"  kernel ${task.name}${peSuffix}_kernel;"
      }
    }

    lines += "public:"
    lines += ""

    // PLIO declarations
    descriptor.taskDescriptors.foreach { task =>
      for (peIdx <- 0 until task.numProcessingElements) {
        val ports = peTaskPorts(task.name)(peIdx)

        ports.inputs.foreach { port =>
          val plioName = port.plioBaseName
          lines += s"  input_plio  $plioName;               // ${port.bitWidth} bits"
        }

        ports.outputs.foreach { port =>
          val plioName = port.plioBaseName
          lines += s"  output_plio $plioName;"
        }
      }
    }

    lines += "  simpleGraph(){"
    lines += ""

    // PLIO instantiation
    descriptor.taskDescriptors.foreach { task =>
      for (peIdx <- 0 until task.numProcessingElements) {
        val ports = peTaskPorts(task.name)(peIdx)

        val peSuffix = suffixForPe(peIdx, task.numProcessingElements)
        lines += s"    // ${task.name}${peSuffix} PLIOs"

        ports.inputs.foreach { port =>
          val plioName = port.plioBaseName
          val plioWidth = bitWidthToPlioBits(port.bitWidth)
          val dataFile = s"data/${plioName}.txt"
          lines += s"    $plioName = input_plio::create(\"PLIO_$plioName\", $plioWidth, \"$dataFile\");"
        }

        ports.outputs.foreach { port =>
          val plioName = port.plioBaseName
          val plioWidth = bitWidthToPlioBits(port.bitWidth)
          val dataFile = s"data/${plioName}.txt"
          lines += s"    $plioName = output_plio::create(\"PLIO_$plioName\", $plioWidth, \"$dataFile\");"
        }
      }
      lines += ""
    }

    // Kernel instantiation - one per PE.
    descriptor.taskDescriptors.foreach { task =>
      for (peIdx <- 0 until task.numProcessingElements) {
        val peSuffix = suffixForPe(peIdx, task.numProcessingElements)
        lines += s"    ${task.name}${peSuffix}_kernel = kernel::create(${task.name});"
      }
    }

    lines += ""

    // Connections
    var netCounter = 0
    descriptor.taskDescriptors.foreach { task =>
      for (peIdx <- 0 until task.numProcessingElements) {
        val ports = peTaskPorts(task.name)(peIdx)

        val peSuffix = suffixForPe(peIdx, task.numProcessingElements)

        ports.inputs.zipWithIndex.foreach { case (port, idx) =>
          val plioName = port.plioBaseName
          lines += s"    connect< stream > net$netCounter (${plioName}.out[0], ${task.name}${peSuffix}_kernel.in[$idx]);"
          netCounter += 1
        }

        ports.outputs.zipWithIndex.foreach { case (port, idx) =>
          val plioName = port.plioBaseName
          lines += s"    connect< stream > net$netCounter (${task.name}${peSuffix}_kernel.out[$idx], ${plioName}.in[0]);"
          netCounter += 1
        }
      }
    }

    lines += ""

    // Kernel source and runtime settings
    descriptor.taskDescriptors.foreach { task =>
      for (peIdx <- 0 until task.numProcessingElements) {
        val peSuffix = suffixForPe(peIdx, task.numProcessingElements)
        lines += s"    source(${task.name}${peSuffix}_kernel) = \"kernels/${task.name}.cc\";"
      }
    }

    lines += ""

    descriptor.taskDescriptors.foreach { task =>
      for (peIdx <- 0 until task.numProcessingElements) {
        val peSuffix = suffixForPe(peIdx, task.numProcessingElements)
        lines += s"    runtime<ratio>(${task.name}${peSuffix}_kernel) = 1;"
      }
    }

    lines += "  }"
    lines += "};"
    lines += ""

    lines.mkString("\n")
  }

  private def bitWidthToPlioBits(bitWidth: Int): String = {
    bitWidth match {
      case 32  => "adf::plio_32_bits"
      case 64  => "adf::plio_64_bits"
      case 128 => "adf::plio_128_bits"
      case _   => "adf::plio_128_bits"
    }
  }

  private def suffixForPe(peIndex: Int, peCount: Int): String =
    if (peCount > 1) s"_$peIndex" else ""

  private def normalizeName(value: String): String =
    value.toLowerCase.replaceAll("[^a-z0-9_]", "")

  case class TaskPEPortInfo(
    task: TaskDescriptor,
    peIndex: Int,
    inputs: scala.collection.mutable.ListBuffer[PortInfo],
    outputs: scala.collection.mutable.ListBuffer[PortInfo]
  )

  case class PortInfo(
    plioBaseName: String,
    bitWidth: Int,
    connectionType: String
  )
}
