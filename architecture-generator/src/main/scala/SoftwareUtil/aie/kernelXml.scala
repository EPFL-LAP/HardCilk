package SoftwareUtil.aie

import Descriptors._
import java.io.PrintWriter
import scala.collection.mutable

object KernelXmlTemplate {

  private case class HelperXmlDef(
      kernelName: String,
      mAxiDataWidth: Int,
      sourceTaskWidth: Int,
      sinkResultWidth: Option[Int],
      replicationCount: Int
  )

  private case class TopStreamPortDef(
      bindPortName: String,
      mode: String,
      bitWidth: Int
  )

    private case class TopMgmtArgDef(
      argName: String,
      offset: Int
    )

  def generateHelperKernelXmls(descriptor: FullSysGenDescriptor, projectFolder: String): Unit = {
    val xmlFolder = new java.io.File(s"$projectFolder/scripts/xml")
    xmlFolder.mkdirs()

    val topFile = new java.io.File(xmlFolder, s"${descriptor.name}.xml")
    val topWriter = new PrintWriter(topFile)
    try {
      topWriter.write(renderTopKernelXml(descriptor) + "\n")
    } finally {
      topWriter.close()
    }

    buildHelperXmlDefs(descriptor).foreach { helper =>
      val file = new java.io.File(xmlFolder, s"${helper.kernelName}.xml")
      val writer = new PrintWriter(file)
      try {
        writer.write(renderKernelXml(helper) + "\n")
      } finally {
        writer.close()
      }
    }
  }

  private def buildHelperXmlDefs(descriptor: FullSysGenDescriptor): Seq[HelperXmlDef] = {
    val taskPeCountByName = descriptor.taskDescriptors.map(t => t.name -> t.numProcessingElements).toMap

    descriptor.subPEList.toSeq.sortBy(_._1).flatMap { case (_, sub) =>
      val req = sub.rwRequest
      taskPeCountByName.get(sub.peName).map { replicationCount =>
        val kernelName = helperKernelName(req.`type`, req.mode, req.portWidth, replicationCount)
        val sourceTaskWidth = sourceTaskDataWidth(req.`type`, req.mode)
        val sinkResultWidth = if (req.`type` == "read") Some(req.portWidth) else None

        HelperXmlDef(
          kernelName = kernelName,
          mAxiDataWidth = req.portWidth,
          sourceTaskWidth = sourceTaskWidth,
          sinkResultWidth = sinkResultWidth,
          replicationCount = replicationCount
        )
      }
    }.distinctBy(_.kernelName)
  }

  private def helperKernelName(requestType: String, mode: String, dataWidth: Int, replicationCount: Int): String = {
    (requestType, mode) match {
      case ("read", "single")  => s"ReadSingle_${dataWidth}_${replicationCount}"
      case ("read", "stream")  => s"ReadStream_${dataWidth}_${replicationCount}"
      case ("write", "single") => s"WriteSingle_${dataWidth}_${replicationCount}"
      case ("write", "stream") => s"WriteStream_${dataWidth}_${replicationCount}"
      case _ =>
        throw new IllegalArgumentException(s"Unsupported rwRequest combination: type=$requestType mode=$mode")
    }
  }

  private def sourceTaskDataWidth(requestType: String, mode: String): Int = {
    (requestType, mode) match {
      case ("read", "single") => 64
      case ("read", "stream") => 128
      case ("write", "single") => 128
      case ("write", "stream") => 128
      case _ =>
        throw new IllegalArgumentException(s"Unsupported rwRequest combination: type=$requestType mode=$mode")
    }
  }

  private def renderKernelXml(helper: HelperXmlDef): String = {
    val streamPorts = buildStreamPorts(helper)
    val args = buildArgs(helper)

    Seq(
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
      "<root versionMajor=\"1\" versionMinor=\"9\">",
      s"  <kernel name=\"${helper.kernelName}\" language=\"ip\" vlnv=\"epfl.ch:hardcilk:${helper.kernelName}:1.0\" attributes=\"\" preferredWorkGroupSizeMultiple=\"0\" workGroupSize=\"1\" hwControlProtocol=\"ap_ctrl_none\">",
      "    <ports>",
      s"      <port name=\"m_axi\" mode=\"master\" range=\"0x3FFFFFFFF\" dataWidth=\"${helper.mAxiDataWidth}\" portType=\"addressable\" base=\"0x0\"/>",
      "      <!-- AXI-Stream ports for AIE connectivity -->",
      streamPorts,
      "    </ports>",
      "    <args>",
      "      <arg name=\"mem_0\" addressQualifier=\"1\" id=\"0\" port=\"m_axi\" size=\"0x8\" offset=\"0x10\" hostOffset=\"0x0\" hostSize=\"0x8\" type=\"void*\"/>",
      args,
      "    </args>",
      "  </kernel>",
      "</root>"
    ).mkString("\n")
  }

  private def buildStreamPorts(helper: HelperXmlDef): String = {
    val lines = (0 until helper.replicationCount).flatMap { idx =>
      val source = s"      <port name=\"sourceTasks_${idx}\" mode=\"write_only\" dataWidth=\"${helper.sourceTaskWidth}\" portType=\"stream\"/>"
      helper.sinkResultWidth match {
        case Some(width) => Seq(source, s"      <port name=\"sinkResults_${idx}\" mode=\"read_only\" dataWidth=\"${width}\" portType=\"stream\"/>")
        case None => Seq(source)
      }
    }
    lines.mkString("\n")
  }

  private def buildArgs(helper: HelperXmlDef): String = {
    val lines = (0 until helper.replicationCount).flatMap { idx =>
      val baseId = if (helper.sinkResultWidth.isDefined) 1 + idx * 2 else 1 + idx
      val source = s"      <arg name=\"sourceTasks_${idx}\" addressQualifier=\"4\" id=\"${baseId}\" port=\"sourceTasks_${idx}\" size=\"0x4\" offset=\"0x0\" hostOffset=\"0x0\" hostSize=\"0x4\" type=\"stream\"/>"
      helper.sinkResultWidth match {
        case Some(_) => Seq(source, s"      <arg name=\"sinkResults_${idx}\" addressQualifier=\"4\" id=\"${baseId + 1}\" port=\"sinkResults_${idx}\" size=\"0x4\" offset=\"0x0\" hostOffset=\"0x0\" hostSize=\"0x4\" type=\"stream\"/>")
        case None => Seq(source)
      }
    }
    lines.mkString("\n")
  }

  private def renderTopKernelXml(descriptor: FullSysGenDescriptor): String = {
    val mAxiPortCount = getHardCilkAxiPortCount(descriptor)
    val mAxiDataWidth = 256
    val streamPorts = buildTopStreamPortDefs(descriptor)
    val mgmtArgs = buildTopMgmtArgDefs(descriptor)

    val mAxiPortLines = (0 until mAxiPortCount).map { i =>
      s"      <port name=\"m_axi_${f"$i%02d"}\" mode=\"master\" range=\"0x3FFFFFFFF\" dataWidth=\"$mAxiDataWidth\" portType=\"addressable\" base=\"0x0\"/>"
    }

    val streamPortLines = streamPorts.map { p =>
      s"      <port name=\"${p.bindPortName}\" mode=\"${p.mode}\" dataWidth=\"${p.bitWidth}\" portType=\"stream\"/>"
    }

    val mAxiArgLines = (0 until mAxiPortCount).map { i =>
      val id = mgmtArgs.length + i
      val offset = 0x10 + ((mgmtArgs.length + i) * 0x8)
      s"      <arg name=\"mem_$i\" addressQualifier=\"1\" id=\"$id\" port=\"m_axi_${f"$i%02d"}\" size=\"0x8\" offset=\"0x${offset.toHexString}\" hostOffset=\"0x0\" hostSize=\"0x8\" type=\"void*\"/>"
    }

    val streamArgLines = streamPorts.zipWithIndex.map { case (p, idx) =>
      val id = mgmtArgs.length + mAxiPortCount + idx
      s"      <arg name=\"${p.bindPortName}\" addressQualifier=\"4\" id=\"$id\" port=\"${p.bindPortName}\" size=\"0x4\" offset=\"0x0\" hostOffset=\"0x0\" hostSize=\"0x4\" type=\"stream\"/>"
    }

    val mgmtArgLines = mgmtArgs.zipWithIndex.map { case (a, id) =>
      s"      <arg name=\"${a.argName}\" addressQualifier=\"0\" id=\"$id\" port=\"s_axil_mgmt_hardcilk\" size=\"0x8\" offset=\"0x${a.offset.toHexString}\" hostOffset=\"0x0\" hostSize=\"0x8\" type=\"ap_uint&lt;64>\"/>"
    }

    val portSectionLines =
      mAxiPortLines ++
        Seq("      <port name=\"s_axil_mgmt_hardcilk\" mode=\"slave\" range=\"0x1000\" dataWidth=\"32\" portType=\"addressable\" base=\"0x0\"/>") ++
        Seq("      <!-- AXI-Stream ports for AIE connectivity -->") ++
        streamPortLines

    val argSectionLines =
      mgmtArgLines ++
        mAxiArgLines ++
        Seq("      <!-- Stream port args -->") ++
        streamArgLines

    Seq(
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
      "<root versionMajor=\"1\" versionMinor=\"9\">",
      s"  <kernel name=\"${descriptor.name}\" language=\"ip\" vlnv=\"epfl.ch:hardcilk:${descriptor.name}:1.0\" attributes=\"\" preferredWorkGroupSizeMultiple=\"0\" workGroupSize=\"1\" hwControlProtocol=\"user_managed\">",
      "    <ports>",
      portSectionLines.mkString("\n"),
      "    </ports>",
      "    <args>",
      argSectionLines.mkString("\n"),
      "    </args>",
      "  </kernel>",
      "</root>"
    ).mkString("\n")
  }

  private def buildTopStreamPortDefs(descriptor: FullSysGenDescriptor): Seq[TopStreamPortDef] = {
    val ports = mutable.ArrayBuffer[TopStreamPortDef]()

    descriptor.getSystemConnectionsDescriptor().connections.foreach { connection =>
      val src = connection.srcPort
      val dst = connection.dstPort

      if (src.parentType == "HardCilk" && dst.parentType == "PE") {
        ports += TopStreamPortDef(
          bindPortName = s"BindTo_PE_${dst.parentName}_${dst.parentIndex}_${dst.portType}",
          mode = "write_only",
          bitWidth = connection.bitWidth
        )
      } else if (src.parentType == "PE" && dst.parentType == "HardCilk") {
        ports += TopStreamPortDef(
          bindPortName = s"BindTo_PE_${src.parentName}_${src.parentIndex}_${src.portType}",
          mode = "read_only",
          bitWidth = connection.bitWidth
        )
      }
    }

    descriptor.taskDescriptors.foreach { task =>
      (0 until task.numProcessingElements).foreach { peIndex =>
        if (task.generateArgOutWriteBuffer) {
          ports += TopStreamPortDef(
            bindPortName = s"BindTo_PE_${task.name}_${peIndex}_argDataOut",
            mode = "read_only",
            bitWidth = task.widthTask
          )
        }
        if (task.generateSpawnNextWriteBuffer) {
          ports += TopStreamPortDef(
            bindPortName = s"BindTo_PE_${task.name}_${peIndex}_spawnNext",
            mode = "read_only",
            bitWidth = task.widthTask
          )
        }
      }
    }

    ports
      .groupBy(_.bindPortName)
      .map(_._2.head)
      .toSeq
      .sortBy(_.bindPortName)
  }

  private def buildTopMgmtArgDefs(descriptor: FullSysGenDescriptor): Seq[TopMgmtArgDef] = {
    val schedulerRegs = Seq("rPause", "rAddr", "maxLen", "fifoTailReg", "fifoHeadReg", "procInterrupt", "currLen")
    val allocatorRegs = Seq("rPause", "rAddr", "avaialbleSize")

    descriptor.taskDescriptors.flatMap { task =>
      val schedulerArgs = task.mgmtBaseAddresses.schedulerServersBaseAddresses.zipWithIndex.flatMap { case (baseAddr, idx) =>
        schedulerRegs.zipWithIndex.map { case (regName, regIdx) =>
          TopMgmtArgDef(
            argName = s"${task.name}_scheduler_${idx}_$regName",
            offset = baseAddr + (regIdx * 8)
          )
        }
      }

      val allocatorArgs = task.mgmtBaseAddresses.allocationServersBaseAddresses.zipWithIndex.flatMap { case (baseAddr, idx) =>
        allocatorRegs.zipWithIndex.map { case (regName, regIdx) =>
          TopMgmtArgDef(
            argName = s"${task.name}_allocator_${idx}_$regName",
            offset = baseAddr + (regIdx * 8)
          )
        }
      }

      val memoryAllocatorArgs = task.mgmtBaseAddresses.memoryAllocatorServersBaseAddresses.zipWithIndex.flatMap { case (baseAddr, idx) =>
        allocatorRegs.zipWithIndex.map { case (regName, regIdx) =>
          TopMgmtArgDef(
            argName = s"${task.name}_memoryAllocator_${idx}_$regName",
            offset = baseAddr + (regIdx * 8)
          )
        }
      }

      schedulerArgs ++ allocatorArgs ++ memoryAllocatorArgs
    }
  }

  private def getHardCilkAxiPortCount(descriptor: FullSysGenDescriptor): Int = {
    val numHBMPorts = if (descriptor.maximumAXIPorts > 0) descriptor.maximumAXIPorts else 6

    val interfacesPE = getEstimatedPEInterfacesCount(descriptor)
    val interfacesScheduler = descriptor.taskDescriptors.map(task => task.getNumServers("scheduler") + task.spawnServersCount).sum
    val interfacesClosureAllocator = descriptor.taskDescriptors
      .filter(task => descriptor.getPortCount("spawnNext", task.name) > 0)
      .map(_.getNumServers("allocator"))
      .sum
    val interfacesArgumentNotifier = descriptor.taskDescriptors
      .filter(task => descriptor.getPortCount("sendArgument", task.name) > 0)
      .map(_.getNumServers("argumentNotifier"))
      .sum
    val interfacesMemoryAllocator = descriptor.taskDescriptors
      .filter(task => descriptor.getPortCount("mallocIn", task.name) > 0)
      .map(_.getNumServers("memoryAllocator"))
      .sum
    val interfacesRemoteMemAccess = descriptor.taskDescriptors
      .count(task => task.generateArgOutWriteBuffer && (descriptor.mFPGASimulation || descriptor.mFPGASynth))

    printf("Estimated AXI interfaces needed: PE=%d, Scheduler=%d, ClosureAllocator=%d, ArgumentNotifier=%d, MemoryAllocator=%d, RemoteMemAccess=%d\n",
      interfacesPE, interfacesScheduler, interfacesClosureAllocator, interfacesArgumentNotifier, interfacesMemoryAllocator, interfacesRemoteMemAccess)
    val totalPorts =
      interfacesPE + interfacesMemoryAllocator + interfacesScheduler + interfacesClosureAllocator + interfacesArgumentNotifier + interfacesRemoteMemAccess

    if (totalPorts <= 0) {
      0
    } else {
      val numPortsPerMux = totalPorts.toDouble / numHBMPorts.toDouble
      val peMux = math.max(1, math.ceil(interfacesPE.toDouble / numPortsPerMux).toInt)
      val serverMux = math.max(0, numHBMPorts - peMux)

      val pePortsPerMux = if (peMux > 0 && interfacesPE > 0) interfacesPE.toDouble / peMux else 1.0
      val nonEmptyHBM = mutable.Set[Int]()

      if (interfacesPE > 0) {
        (0 until interfacesPE).foreach { idx =>
          val bucket = (idx.toDouble / pePortsPerMux).toInt
          if (bucket >= 0 && bucket < numHBMPorts) {
            nonEmptyHBM += bucket
          }
        }
      }

      val serverInterfaces = interfacesMemoryAllocator + interfacesScheduler + interfacesClosureAllocator + interfacesArgumentNotifier + interfacesRemoteMemAccess
      val serverPortsPerMuxClamped = if (serverInterfaces > 0 && serverMux > 0) serverInterfaces.toDouble / serverMux else 1.0

      if (serverInterfaces > 0 && serverMux > 0) {
        (0 until serverInterfaces).foreach { idx =>
          val bucket = peMux + (idx.toDouble / serverPortsPerMuxClamped).toInt
          if (bucket >= 0 && bucket < numHBMPorts) {
            nonEmptyHBM += bucket
          }
        }
      }

      nonEmptyHBM.size
    }
  }

  private def getEstimatedPEInterfacesCount(descriptor: FullSysGenDescriptor): Int = {
    descriptor.taskDescriptors.map { task =>
      val hasPEModule = task.peHDLPath.nonEmpty

      val peCoreAxi = if (task.hasAXI && hasPEModule) task.numProcessingElements else 0
      val peSpawnNextAxi =
        if ((descriptor.getPortCount("spawnNext", task.name) > 0 || task.generateSpawnNextWriteBuffer) && hasPEModule)
          task.numProcessingElements
        else
          0
      val peArgOutAxi =
        if ((descriptor.getPortCount("sendArgument", task.name) > 0 || task.generateArgOutWriteBuffer) && hasPEModule)
          task.numProcessingElements
        else
          0

      val wbSpawnNextAxi = if (task.generateSpawnNextWriteBuffer && !hasPEModule) task.numProcessingElements else 0
      val wbArgDataAxi = if (task.generateArgOutWriteBuffer && !hasPEModule) task.numProcessingElements else 0

      peCoreAxi + peSpawnNextAxi + peArgOutAxi + wbSpawnNextAxi + wbArgDataAxi
    }.sum
  }
}
