package SoftwareUtil.aie

import Descriptors._
import java.io.PrintWriter
import scala.collection.mutable

// TODO: PLIO width is not properly handled
object ProjectHeaderTemplate {

  private case class UnitDef(
      unitName: String,
      functionName: String,
      taskName: String,
      peIndex: Int,
      peCount: Int,
      taskInputPorts: Seq[String],
      taskOutputPorts: Seq[String],
      rwInputPorts: Seq[String],
      rwOutputPorts: Seq[String],
      prevChainUnitName: Option[String],
      nextChainUnitName: Option[String]
  )

  private case class PortDef(
      varName: String,
      plioName: String,
      direction: String,
      bits: Int
  )

  private case class HelperSubPE(
      name: String,
      normalizedName: String,
      req: RWRequestDescriptor
  )

  private case class TaskOutputDef(
      taskName: String,
      peIndex: Int,
      portType: String,
      bitWidth: Int
  )

  private case class StreamSplitterGroupDef(
      groupName: String,
      taskName: String,
      peIndex: Int,
      outputPortTypes: Seq[String],
      outputWidths: Seq[Int]
  )

  def generateProjectHeader(descriptor: FullSysGenDescriptor, projectFolder: String): Unit = {
    val units = buildUnits(descriptor)
    val portBitWidthsByTaskPePort = buildHardCilkPortBitWidthsByTaskPePort(descriptor)
    val ports = buildPortDefs(descriptor, units, portBitWidthsByTaskPePort)

    val lines =
      Seq(
        "#include <adf.h>",
        "#include \"kernels.h\"",
        "",
        "using namespace adf;",
        "",
        "class simpleGraph : public adf::graph {",
        "private:"
      ) ++
        buildKernelDeclarations(units) ++
        Seq("public:") ++
        buildPortDeclarations(ports) ++
        Seq("  simpleGraph(){") ++
        Seq("    // PLIOs") ++
        buildPlioCreations(ports) ++
        Seq("", "    // Kernels") ++
        buildKernelCreates(units) ++
        Seq("", "    // Connections") ++
        buildConnections(units, descriptor) ++
        Seq("  }", "};")

    val writer = new PrintWriter(s"$projectFolder/project.h")
    try {
      writer.write(lines.mkString("\n") + "\n")
    } finally {
      writer.close()
    }
  }

  private def buildUnits(descriptor: FullSysGenDescriptor): Seq[UnitDef] = {
    val hardCilkPortsByTaskPe = buildHardCilkPortsByTaskPe(descriptor)
    val units = mutable.ArrayBuffer[UnitDef]()

    descriptor.taskDescriptors.sortBy(_.name).foreach { task =>
      val orderedSubPEs = getOrderedSubPEsForTask(descriptor, task.name)

      (0 until task.numProcessingElements).foreach { peIndex =>
        val peSuffix = suffixForPe(peIndex, task.numProcessingElements)
        val hardPorts = hardCilkPortsByTaskPe.getOrElse((task.name, peIndex), (Seq.empty[String], Seq.empty[String]))
        val hardInputs = hardPorts._1
        val hardOutputs = hardPorts._2

        if (orderedSubPEs.nonEmpty) {
          val incomingReadPortsBySubPE = buildIncomingReadPorts(orderedSubPEs)
          val byName = orderedSubPEs.map(s => s.name -> s).toMap
          val prevByName = mutable.Map[String, String]()

          orderedSubPEs.foreach { subPE =>
            subPE.req.nextsubPE.foreach { nextSubPE =>
              if (byName.contains(nextSubPE)) {
                prevByName(nextSubPE) = subPE.name
              }
            }
          }

          orderedSubPEs.zipWithIndex.foreach { case (subPE, chainIndex) =>
            val isFirst = chainIndex == 0
            val isLast = chainIndex == orderedSubPEs.length - 1

            val taskInputs = {
              val base = Seq("taskIn")
              if (isFirst) {
                uniquePreserveOrder(base ++ hardInputs.filterNot(_ == "taskIn"))
              } else {
                base
              }
            }

            val taskOutputs = {
              val forwarded = if (!isLast) Seq("taskOut") else Seq.empty
              val exported = if (isLast) hardOutputs else Seq.empty
              uniquePreserveOrder(forwarded ++ exported)
            }

            val rwIn = incomingReadPortsBySubPE.getOrElse(subPE.name, Seq.empty)
            val rwOut = rwOutputPorts(subPE.req)
            val prevUnitName = prevByName.get(subPE.name).flatMap(byName.get).map(s => s"${s.normalizedName}$peSuffix")
            val nextUnitName = subPE.req.nextsubPE.flatMap(byName.get).map(s => s"${s.normalizedName}$peSuffix")

            units += UnitDef(
              unitName = s"${subPE.normalizedName}$peSuffix",
              functionName = subPE.normalizedName,
              taskName = task.name,
              peIndex = peIndex,
              peCount = task.numProcessingElements,
              taskInputPorts = taskInputs,
              taskOutputPorts = taskOutputs,
              rwInputPorts = rwIn,
              rwOutputPorts = rwOut,
              prevChainUnitName = prevUnitName,
              nextChainUnitName = nextUnitName
            )
          }
        } else {
          val taskInputs = uniquePreserveOrder(Seq("taskIn") ++ hardInputs.filterNot(_ == "taskIn"))
          val taskOutputs = uniquePreserveOrder(hardOutputs)

          units += UnitDef(
            unitName = s"${task.name}$peSuffix",
            functionName = task.name,
            taskName = task.name,
            peIndex = peIndex,
            peCount = task.numProcessingElements,
            taskInputPorts = taskInputs,
            taskOutputPorts = taskOutputs,
            rwInputPorts = Seq.empty,
            rwOutputPorts = Seq.empty,
            prevChainUnitName = None,
            nextChainUnitName = None
          )
        }
      }
    }

    units.toSeq
  }

  private def buildHardCilkPortsByTaskPe(
      descriptor: FullSysGenDescriptor
  ): Map[(String, Int), (Seq[String], Seq[String])] = {
    val inPorts = mutable.Map[(String, Int), mutable.ArrayBuffer[String]]()
    val outPorts = mutable.Map[(String, Int), mutable.ArrayBuffer[String]]()

    descriptor.getSystemConnectionsDescriptor().connections.foreach { connection =>
      val src = connection.srcPort
      val dst = connection.dstPort

      if (src.parentType == "HardCilk" && dst.parentType == "PE") {
        val key = (dst.parentName, dst.parentIndex)
        val buf = inPorts.getOrElseUpdate(key, mutable.ArrayBuffer[String]())
        buf += dst.portType
      } else if (src.parentType == "PE" && dst.parentType == "HardCilk") {
        val key = (src.parentName, src.parentIndex)
        val buf = outPorts.getOrElseUpdate(key, mutable.ArrayBuffer[String]())
        buf += src.portType
      }
    }

    // Write-buffer exports are not always present in the static PE<->HardCilk connection list.
    // Add them explicitly so project PLIOs include argDataOut/spawnNext when generated.
    descriptor.taskDescriptors.foreach { task =>
      (0 until task.numProcessingElements).foreach { peIndex =>
        val key = (task.name, peIndex)
        val buf = outPorts.getOrElseUpdate(key, mutable.ArrayBuffer[String]())
        if (task.generateArgOutWriteBuffer) {
          buf += "argDataOut"
        }
        if (task.generateSpawnNextWriteBuffer) {
          buf += "spawnNext"
        }
      }
    }

    (inPorts.keySet ++ outPorts.keySet).map { key =>
      val in = uniquePreserveOrder(inPorts.getOrElse(key, mutable.ArrayBuffer.empty[String]).toSeq)
      val out = uniquePreserveOrder(outPorts.getOrElse(key, mutable.ArrayBuffer.empty[String]).toSeq)
      key -> (in, out)
    }.toMap
  }

  private def buildHardCilkPortBitWidthsByTaskPePort(
      descriptor: FullSysGenDescriptor
  ): Map[(String, Int, String), Int] = {
    val widths = mutable.Map[(String, Int, String), Int]()

    descriptor.getSystemConnectionsDescriptor().connections.foreach { connection =>
      val src = connection.srcPort
      val dst = connection.dstPort

      if (src.parentType == "HardCilk" && dst.parentType == "PE") {
        widths((dst.parentName, dst.parentIndex, dst.portType)) = connection.bitWidth
      } else if (src.parentType == "PE" && dst.parentType == "HardCilk") {
        widths((src.parentName, src.parentIndex, src.portType)) = connection.bitWidth
      }
    }

    descriptor.taskDescriptors.foreach { task =>
      (0 until task.numProcessingElements).foreach { peIndex =>
        if (task.generateArgOutWriteBuffer) {
          widths((task.name, peIndex, "argDataOut")) = task.widthTask
        }
        if (task.generateSpawnNextWriteBuffer) {
          widths((task.name, peIndex, "spawnNext")) = task.widthTask
        }
      }
    }

    widths.toMap
  }

  private def getOrderedSubPEsForTask(descriptor: FullSysGenDescriptor, taskName: String): Seq[HelperSubPE] = {
    val subpes = descriptor.subPEList
      .toSeq
      .filter { case (_, sub) => sub.peName == taskName }
      .map { case (name, sub) =>
        HelperSubPE(
          name = name,
          normalizedName = normalizeName(name),
          req = sub.rwRequest
        )
      }

    if (subpes.isEmpty) {
      Seq.empty
    } else {
      val byName = subpes.map(s => s.name -> s).toMap
      val incomingTargets = subpes.flatMap(_.req.nextsubPE).toSet
      val heads = subpes.filterNot(s => incomingTargets.contains(s.name)).sortBy(_.name)

      val ordered = mutable.ArrayBuffer[HelperSubPE]()
      val visited = mutable.Set[String]()

      def walk(start: HelperSubPE): Unit = {
        var current = Option(start)
        while (current.nonEmpty && !visited.contains(current.get.name)) {
          val value = current.get
          ordered += value
          visited += value.name
          current = value.req.nextsubPE.flatMap(byName.get)
        }
      }

      heads.foreach(walk)
      subpes.sortBy(_.name).filterNot(s => visited.contains(s.name)).foreach(walk)
      ordered.toSeq
    }
  }

  private def buildIncomingReadPorts(orderedSubPEs: Seq[HelperSubPE]): Map[String, Seq[String]] = {
    val incoming = mutable.Map[String, mutable.ArrayBuffer[String]]()

    orderedSubPEs.foreach { sub =>
      val req = sub.req
      if (req.`type` == "read") {
        req.nextsubPE.foreach { next =>
          val portName = rwInPortName(req)
          val buf = incoming.getOrElseUpdate(next, mutable.ArrayBuffer[String]())
          buf += portName
        }
      }
    }

    incoming.map { case (name, buf) =>
      name -> uniquePreserveOrder(buf.toSeq)
    }.toMap
  }

  private def rwOutputPorts(req: RWRequestDescriptor): Seq[String] = {
    req.`type` match {
      case "read"  => Seq(rwOutPortName("read", req.mode, req.portWidth))
      case "write" => Seq(rwOutPortName("write", req.mode, req.portWidth))
      case _        => Seq.empty
    }
  }

  private def rwInPortName(req: RWRequestDescriptor): String =
    s"read${modeCap(req.mode)}${req.portWidth}In"

  private def rwOutPortName(requestType: String, mode: String, portWidth: Int): String =
    s"${requestType}${modeCap(mode)}${portWidth}Out"

  private def modeCap(mode: String): String =
    if (mode == "single") "Single" else "Stream"

  private def buildPortDefs(
      descriptor: FullSysGenDescriptor,
      units: Seq[UnitDef],
      portBitWidthsByTaskPePort: Map[(String, Int, String), Int]
  ): Seq[PortDef] = {
    val taskByName = descriptor.taskDescriptors.map(t => t.name -> t).toMap
    val allPorts = mutable.ArrayBuffer[PortDef]()
    val splitterGroups = buildStreamSplitterGroups(descriptor)
    val portsByGroup = splitterGroups.flatMap { group =>
      group.outputPortTypes.map(port => (group.taskName, group.peIndex, port) -> group)
    }.toMap

    units.foreach { unit =>
      val task = taskByName(unit.taskName)
      val inputPorts = orderTaskInputPorts(unit.taskInputPorts) ++ unit.rwInputPorts.sorted
      val outputPorts = orderTaskOutputPorts(unit.taskOutputPorts) ++ unit.rwOutputPorts.sorted

      inputPorts.foreach { p =>
        val isInternalChainInput = p == "taskIn" && unit.prevChainUnitName.nonEmpty
        if (!isInternalChainInput) {
          val varName = s"${unit.unitName}_$p"
          allPorts += PortDef(
            varName = varName,
            plioName = s"PLIO_$varName",
            direction = "input",
            bits = resolvePortWidthBits(
              task = task,
              peIndex = unit.peIndex,
              portName = p,
              isInput = true,
              portBitWidthsByTaskPePort = portBitWidthsByTaskPePort
            )
          )
        }
      }

      outputPorts.foreach { p =>
        val isInternalChainOutput = p == "taskOut" && unit.nextChainUnitName.nonEmpty
        val isGroupedOutput = portsByGroup.contains((unit.taskName, unit.peIndex, p))
        if (!isInternalChainOutput && !isGroupedOutput) {
          val varName = s"${unit.unitName}_$p"
          allPorts += PortDef(
            varName = varName,
            plioName = s"PLIO_$varName",
            direction = "output",
            bits = resolvePortWidthBits(
              task = task,
              peIndex = unit.peIndex,
              portName = p,
              isInput = false,
              portBitWidthsByTaskPePort = portBitWidthsByTaskPePort
            )
          )
        }
      }
    }

    // Add grouped outputs as packed PLIOs
    splitterGroups.foreach { group =>
      val task = taskByName.get(group.taskName)
      val peCount = task.map(_.numProcessingElements).getOrElse(1)
      val peSuffix = if (peCount > 1) s"_${group.peIndex}" else ""
      val varName = s"${group.taskName}$peSuffix${if (group.outputPortTypes.nonEmpty) s"_${group.outputPortTypes.mkString("_")}" else ""}"
      allPorts += PortDef(
        varName = varName,
        plioName = s"PLIO_$varName",
        direction = "output",
        bits = toSupportedPlioBits(group.outputWidths.sum)
      )
    }

    uniquePortDefs(allPorts.toSeq).sortBy(_.varName)
  }

  private def uniquePortDefs(ports: Seq[PortDef]): Seq[PortDef] = {
    val seen = mutable.Set[String]()
    ports.filter { p =>
      if (seen.contains(p.varName)) false
      else {
        seen += p.varName
        true
      }
    }
  }

  private def buildKernelDeclarations(units: Seq[UnitDef]): Seq[String] =
    units.map(u => s"  kernel ${u.unitName}_kernel;")

  private def buildPortDeclarations(ports: Seq[PortDef]): Seq[String] =
    ports.map { p =>
      val kind = if (p.direction == "input") "input_plio" else "output_plio"
      s"  $kind ${p.varName};"
    }

  private def buildPlioCreations(ports: Seq[PortDef]): Seq[String] =
    ports.map { p =>
      val creator = if (p.direction == "input") "input_plio::create" else "output_plio::create"
      s"    ${p.varName} = $creator(\"${p.plioName}\", adf::plio_${p.bits}_bits, \"data/${p.varName}.txt\");"
    }

  private def buildKernelCreates(units: Seq[UnitDef]): Seq[String] =
    units.flatMap { u =>
      Seq(
        s"    ${u.unitName}_kernel = kernel::create(${u.functionName});",
        s"    source(${u.unitName}_kernel) = \"kernels/${u.functionName}.cc\";",
        s"    runtime<ratio>(${u.unitName}_kernel) = 1;"
      )
    }

  private def buildConnections(units: Seq[UnitDef], descriptor: FullSysGenDescriptor): Seq[String] = {
    val lines = mutable.ArrayBuffer[String]()
    var net = 0
    val unitsByName = units.map(u => u.unitName -> u).toMap
    val splitterGroups = buildStreamSplitterGroups(descriptor)

    units.foreach { u =>
      val inputPorts = orderTaskInputPorts(u.taskInputPorts) ++ u.rwInputPorts.sorted
      val outputPorts = orderTaskOutputPorts(u.taskOutputPorts) ++ u.rwOutputPorts.sorted

      inputPorts.zipWithIndex.foreach { case (port, inIdx) =>
        if (port == "taskIn" && u.prevChainUnitName.nonEmpty) {
          val prevUnit = unitsByName(u.prevChainUnitName.get)
          val prevOutputPorts = orderTaskOutputPorts(prevUnit.taskOutputPorts) ++ prevUnit.rwOutputPorts.sorted
          val prevTaskOutIdx = prevOutputPorts.indexOf("taskOut")
          if (prevTaskOutIdx >= 0) {
            lines += s"    connect< stream > net$net (${prevUnit.unitName}_kernel.out[$prevTaskOutIdx], ${u.unitName}_kernel.in[$inIdx]);"
            net += 1
          }
        } else {
          val plioVar = s"${u.unitName}_$port"
          lines += s"    connect< stream > net$net (${plioVar}.out[0], ${u.unitName}_kernel.in[$inIdx]);"
          net += 1
        }
      }

      outputPorts.zipWithIndex.foreach { case (port, outIdx) =>
        val isInternalChainOutput = port == "taskOut" && u.nextChainUnitName.nonEmpty
        val groupForPort = splitterGroups.find(g => g.taskName == u.taskName && g.peIndex == u.peIndex && g.outputPortTypes.contains(port))
        if (!isInternalChainOutput && groupForPort.isEmpty) {
          val plioVar = s"${u.unitName}_$port"
          lines += s"    connect< stream > net$net (${u.unitName}_kernel.out[$outIdx], ${plioVar}.in[0]);"
          net += 1
        }
      }

      // Handle grouped outputs (splitter groups)
      val groupsForUnit = splitterGroups.filter(g => g.taskName == u.taskName && g.peIndex == u.peIndex)
      groupsForUnit.foreach { group =>
        val groupedPorts = group.outputPortTypes
        val groupedIndices = groupedPorts.map { port =>
          outputPorts.indexOf(port)
        }
        val packedVarName = s"${u.taskName}${if (u.peCount > 1) s"_${u.peIndex}" else ""}${if (group.outputPortTypes.nonEmpty) s"_${group.outputPortTypes.mkString("_")}" else ""}"
        groupedIndices.zipWithIndex.foreach { case (outIdx, groupIdx) =>
          if (outIdx >= 0) {
            lines += s"    connect< stream > net$net (${u.unitName}_kernel.out[$outIdx], ${packedVarName}.in[$groupIdx]);"
            net += 1
          }
        }
      }
    }

    lines.toSeq
  }

  private def orderTaskInputPorts(ports: Seq[String]): Seq[String] = {
    val order = Seq("taskIn", "taskInGlobal", "argIn", "closureIn", "mallocIn")
    orderByReferenceThenName(ports, order)
  }

  private def orderTaskOutputPorts(ports: Seq[String]): Seq[String] = {
    val order = Seq("taskOut", "taskOutGlobal", "argOut", "closureOut", "mallocOut", "argDataOut", "spawnNext")
    orderByReferenceThenName(ports, order)
  }

  private def orderByReferenceThenName(ports: Seq[String], order: Seq[String]): Seq[String] = {
    val unique = uniquePreserveOrder(ports)
    val orderMap = order.zipWithIndex.toMap
    unique.sortBy(p => (orderMap.getOrElse(p, Int.MaxValue), p))
  }

  private def uniquePreserveOrder(values: Seq[String]): Seq[String] = {
    val seen = mutable.Set[String]()
    values.filter { v =>
      if (seen.contains(v)) false
      else {
        seen += v
        true
      }
    }
  }

  private def suffixForPe(peIndex: Int, peCount: Int): String =
    if (peCount > 1) s"_$peIndex" else ""

  private def normalizeName(value: String): String =
    value.toLowerCase.replaceAll("[^a-z0-9_]", "")

  private def buildStreamSplitterGroups(descriptor: FullSysGenDescriptor): Seq[StreamSplitterGroupDef] = {
    val outputsByTaskPe = collectTaskOutputs(descriptor).groupBy(out => (out.taskName, out.peIndex))

    outputsByTaskPe
      .toSeq
      .sortBy { case ((taskName, peIndex), _) => (taskName, peIndex) }
      .flatMap { case ((taskName, peIndex), outputs) =>
        val ordered = outputs
          .groupBy(_.portType)
          .map(_._2.head)
          .toSeq
          .sortBy(out => (outputPortPriority(out.portType), out.portType))

        if (ordered.size <= 2) {
          Seq.empty
        } else {
          ordered.grouped(2).collect { case group if group.size > 1 =>
            val widths = group.map(_.bitWidth)
            StreamSplitterGroupDef(
              groupName = s"StreamSplitter_${widths.mkString("_")}",
              taskName = taskName,
              peIndex = peIndex,
              outputPortTypes = group.map(_.portType),
              outputWidths = widths
            )
          }.toSeq
        }
      }
  }

  private def collectTaskOutputs(descriptor: FullSysGenDescriptor): Seq[TaskOutputDef] = {
    val directOutputs = descriptor.getSystemConnectionsDescriptor().connections.flatMap { connection =>
      val src = connection.srcPort
      val dst = connection.dstPort
      if (src.parentType == "PE" && dst.parentType == "HardCilk") {
        Some(TaskOutputDef(src.parentName, src.parentIndex, src.portType, connection.bitWidth))
      } else {
        None
      }
    }

    val writeBufferOutputs = descriptor.taskDescriptors.flatMap { task =>
      (0 until task.numProcessingElements).flatMap { peIndex =>
        val argDataOut =
          if (task.generateArgOutWriteBuffer) Some(TaskOutputDef(task.name, peIndex, "argDataOut", task.widthTask)) else None
        val spawnNext =
          if (task.generateSpawnNextWriteBuffer) Some(TaskOutputDef(task.name, peIndex, "spawnNext", task.widthTask)) else None
        Seq(argDataOut, spawnNext).flatten
      }
    }

    (directOutputs ++ writeBufferOutputs)
      .groupBy(out => (out.taskName, out.peIndex, out.portType))
      .map(_._2.head)
      .toSeq
  }

  private def outputPortPriority(portType: String): Int = {
    portType match {
      case "taskOut" => 0
      case "taskOutGlobal" => 1
      case "argOut" => 2
      case "closureOut" => 3
      case "mallocOut" => 4
      case "argDataOut" => 5
      case "spawnNext" => 6
      case _ => 100
    }
  }

  private def resolvePortWidthBits(
      task: TaskDescriptor,
      peIndex: Int,
      portName: String,
      isInput: Boolean,
      portBitWidthsByTaskPePort: Map[(String, Int, String), Int]
  ): Int = {
    // Explicit rules for subPE helper ports.
    if (portName.startsWith("readSingle") && portName.endsWith("Out")) {
      toSupportedPlioBits(64)
    } else if (portName.startsWith("readStream") && portName.endsWith("Out")) {
      toSupportedPlioBits(128)
    } else if (portName.startsWith("writeSingle") && portName.endsWith("Out")) {
      toSupportedPlioBits(128)
    } else if (portName.startsWith("writeStream") && portName.endsWith("Out")) {
      val dataSize = extractWidthOrDefault(portName, 64)
      val bits = if (dataSize < 64) 64 else 128
      toSupportedPlioBits(bits)
    } else if (portName.startsWith("readSingle") && portName.endsWith("In")) {
      val dataSize = extractWidthOrDefault(portName, 64)
      toSupportedPlioBits(dataSize)
    } else if (portName.startsWith("readStream") && portName.endsWith("In")) {
      val dataSize = extractWidthOrDefault(portName, 128)
      toSupportedPlioBits(dataSize)
    } else {
      val metadataWidth = portBitWidthsByTaskPePort.get((task.name, peIndex, portName))
      val fallbackWidth = portName match {
        case "taskIn" | "taskOut" | "taskInGlobal" | "taskOutGlobal" => task.widthTask
        case "mallocIn" | "mallocOut"                                   => if (task.widthMalloc > 0) task.widthMalloc else 64
        case "closureIn" | "closureOut"                                 => widthFromSideConfigOrDefault(task, "allocator", 64)
        case "argIn" | "argOut"                                         => widthFromArgumentMetadataOrDefault(task, 64)
        case "argDataOut" | "spawnNext"                                 => task.widthTask
        case _                                                              => if (isInput) task.widthTask else task.widthTask
      }
      toSupportedPlioBits(metadataWidth.getOrElse(fallbackWidth))
    }
  }

  private def widthFromArgumentMetadataOrDefault(task: TaskDescriptor, default: Int): Int = {
    val sideWidth = task.sidesConfigs.find(_.sideType == "argumentNotifier").map(_.portWidth)
    val argumentListWidth = task.argumentSizeList.headOption
    sideWidth.orElse(argumentListWidth).getOrElse(default)
  }

  private def widthFromSideConfigOrDefault(task: TaskDescriptor, sideType: String, default: Int): Int =
    task.sidesConfigs.find(_.sideType == sideType).map(_.portWidth).getOrElse(default)

  private def toSupportedPlioBits(rawWidth: Int): Int = {
    val capped = math.max(1, math.min(rawWidth, 128))
    if (capped <= 32) 32
    else if (capped <= 64) 64
    else 128
  }

  private def extractWidthOrDefault(portName: String, default: Int): Int = {
    val digits = """(\d+)""".r.findAllIn(portName).toList
    digits.lastOption.map(_.toInt).getOrElse(default)
  }
}
