package SoftwareUtil.aie
import Descriptors._
import java.io.PrintWriter
import scala.collection.mutable

object ConnectivityTemplate {
	private case class HelperKernelDef(
			kernelName: String,
			instanceName: String,
			subPEName: String,
			taskName: String,
			taskPeCount: Int,
			requestType: String,
			mode: String,
			portWidth: Int,
			nextSubPE: Option[String]
	)

	private case class TaskOutputDef(
			taskName: String,
			peIndex: Int,
			portType: String,
			bitWidth: Int
	)

	private case class StreamSplitterDef(
			kernelName: String,
			instanceName: String,
			taskName: String,
			peIndex: Int,
			aiePortType: String,
			outputPortTypes: Seq[String],
			outputWidths: Seq[Int]
	)

	def generateConnectivityCfg(descriptor: FullSysGenDescriptor, projectFolder: String): Unit = {
		val hardCilkKernelName = s"${descriptor.name}"
		val hardCilkInstance = s"${hardCilkKernelName}_1"
		val helperKernels = buildHelperKernelDefs(descriptor)
		val streamSplitters = buildStreamSplitterDefs(descriptor)
		val hardCilkAxiPortCount = getHardCilkAxiPortCount(descriptor)

		val sectionConnectivity =
			Seq("[connectivity]") ++
				buildNkLines(hardCilkKernelName, hardCilkInstance, helperKernels, streamSplitters) ++
				Seq("") ++
				buildSpLines(hardCilkInstance, helperKernels, hardCilkAxiPortCount) ++
				Seq("") ++
				buildTaskConnections(descriptor, hardCilkInstance, streamSplitters) ++
				(if (helperKernels.nonEmpty) Seq("") ++ buildSubPEConnections(helperKernels) else Seq.empty)

		val writer = new PrintWriter(s"$projectFolder/connectivity.cfg")
		try {
			writer.write(sectionConnectivity.mkString("\n") + "\n")
		} finally {
			writer.close()
		}
	}

	private def buildHelperKernelDefs(descriptor: FullSysGenDescriptor): List[HelperKernelDef] = {
		val taskMap = descriptor.taskDescriptors.map(t => t.name -> t).toMap
		val instanceCounterByBase = scala.collection.mutable.Map[String, Int]().withDefaultValue(0)

		// Sort by key for stable output.
		descriptor.subPEList.toList.sortBy(_._1).map { case (subPEName, subPE) =>
			val req = subPE.rwRequest
			val task = taskMap(subPE.peName)
			val replicationCount = task.numProcessingElements
			val kernelName = helperKernelName(req.`type`, req.mode, req.portWidth, replicationCount)
			val instanceBase = helperInstanceBase(req.`type`, req.mode, req.portWidth, replicationCount)
			instanceCounterByBase(instanceBase) += 1
			val instanceName = s"${instanceBase}_${instanceCounterByBase(instanceBase)}"

			HelperKernelDef(
				kernelName = kernelName,
				instanceName = instanceName,
				subPEName = subPEName,
				taskName = subPE.peName,
				taskPeCount = task.numProcessingElements,
				requestType = req.`type`,
				mode = req.mode,
				portWidth = req.portWidth,
				nextSubPE = req.nextsubPE
			)
		}
	}

	private def helperInstanceBase(requestType: String, mode: String, portWidth: Int, replicationCount: Int): String = {
		(requestType, mode) match {
			case ("read", "single")  => s"ReadSingle_${portWidth}_${replicationCount}"
			case ("read", "stream")  => s"ReadStream_${portWidth}_${replicationCount}"
			case ("write", "single") => s"WriteSingle_${portWidth}_${replicationCount}"
			case ("write", "stream") => s"WriteStream_${portWidth}_${replicationCount}"
			case _ =>
				throw new IllegalArgumentException(s"Unsupported rwRequest combination: type=$requestType mode=$mode")
		}
	}

	private def helperKernelName(requestType: String, mode: String, portWidth: Int, replicationCount: Int): String =
		helperInstanceBase(requestType, mode, portWidth, replicationCount)

	private def buildNkLines(
			hardCilkKernelName: String,
			hardCilkInstance: String,
			helperKernels: List[HelperKernelDef],
			streamSplitters: Seq[StreamSplitterDef]
	): Seq[String] = {
		val nkHardCilk = Seq(s"nk=$hardCilkKernelName:1:$hardCilkInstance")

		// Group helper kernels by kernel name and aggregate into single nk lines
		val nkHelpers = helperKernels
			.groupBy(_.kernelName)
			.map { case (kernelName, instances) =>
				val count = instances.length
				val instanceNames = instances.map(_.instanceName).mkString(",")
				s"nk=$kernelName:$count:$instanceNames"
			}
			.toSeq
			.sorted  // Sort for deterministic output

		val nkSplitters = streamSplitters
			.groupBy(_.kernelName)
			.map { case (kernelName, instances) =>
				val count = instances.length
				val instanceNames = instances.map(_.instanceName).mkString(",")
				s"nk=$kernelName:$count:$instanceNames"
			}
			.toSeq
			.sorted

		nkHardCilk ++ nkHelpers ++ nkSplitters
	}

	private def buildSpLines(
			hardCilkInstance: String,
			helperKernels: List[HelperKernelDef],
			hardCilkAxiPortCount: Int
	): Seq[String] = {
		val hardCilkSPs = (0 until hardCilkAxiPortCount).map(i => s"sp=$hardCilkInstance.m_axi_${f"$i%02d"}:MC_NOC0")
		val helperSPs = helperKernels.map(k => s"sp=${k.instanceName}.m_axi:MC_NOC0")
		hardCilkSPs ++ helperSPs
	}

	private def getHardCilkAxiPortCount(descriptor: FullSysGenDescriptor): Int = {
		val numHBMPorts = if (descriptor.maximumAXIPorts > 0) descriptor.maximumAXIPorts else 6

		// Match HasHBMInterconnect grouping at descriptor level to estimate exported non-empty m_axi ports.
		val interfacesPE = getEstimatedPEInterfacesCount(descriptor)
		val interfacesScheduler = descriptor.taskDescriptors.map(task => task.getNumServers("scheduler") + task.spawnServersCount).sum
		val interfacesClosureAllocator = descriptor.taskDescriptors
			.filter(task => descriptor.getPortCount("spawnNext", task.name) > 0)
			.map(_.getNumServers("allocator"))
			.sum
		val interfacesArgumentNotifier = descriptor.taskDescriptors
			.filter(task => descriptor.getPortCount("sendArgument", task.name) > 0)
			.map(_.getNumServers("argumentNotifier") * 2)
			.sum
		val interfacesMemoryAllocator = descriptor.taskDescriptors
			.filter(task => descriptor.getPortCount("mallocIn", task.name) > 0)
			.map(_.getNumServers("memoryAllocator"))
			.sum
		val interfacesRemoteMemAccess = descriptor.taskDescriptors
			.count(task => task.generateArgOutWriteBuffer && (descriptor.mFPGASimulation || descriptor.mFPGASynth))

		val totalPorts =
			interfacesPE + interfacesMemoryAllocator + interfacesScheduler + interfacesClosureAllocator + interfacesArgumentNotifier + interfacesRemoteMemAccess

		if (totalPorts <= 0) {
			0
		} else {
			val numPortsPerMux = totalPorts.toDouble / numHBMPorts.toDouble
			val peMux = math.max(1, math.ceil(interfacesPE.toDouble / numPortsPerMux).toInt)
			val serverMux = math.max(0, numHBMPorts - peMux)

			val pePortsPerMux = if (peMux > 0 && interfacesPE > 0) interfacesPE.toDouble / peMux else 1.0
			val nonEmptyHBM = scala.collection.mutable.Set[Int]()

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

	private def buildTaskConnections(
			descriptor: FullSysGenDescriptor,
			hardCilkInstance: String,
			streamSplitters: Seq[StreamSplitterDef]
	): Seq[String] = {
		val peCountsByTask = descriptor.taskDescriptors.map(t => t.name -> t.numProcessingElements).toMap
		val aieEndpointByTask = buildAieEndpointByTask(descriptor)
		val hcPeConnections = descriptor.getSystemConnectionsDescriptor().connections.flatMap { connection =>
			val src = connection.srcPort
			val dst = connection.dstPort

			if (src.parentType == "HardCilk" && dst.parentType == "PE") {
				Some((dst.parentName, dst.parentIndex, dst.portType, false))
			} else if (src.parentType == "PE" && dst.parentType == "HardCilk") {
				Some((src.parentName, src.parentIndex, src.portType, true))
			} else {
				None
			}
		}

		val directInputConnections = hcPeConnections
			.filter(!_._4)
			.distinct
			.map { case (taskName, peIndex, pePortType, fromPeToHardCilk) =>
				val taskEndpoints = aieEndpointByTask.getOrElse(taskName, (taskName, taskName))
				val aieTaskName = if (fromPeToHardCilk) taskEndpoints._2 else taskEndpoints._1
				val peCount = peCountsByTask.getOrElse(taskName, 1)
				val peSuffix = suffixForPe(peIndex, peCount)
				val bindPort = s"$hardCilkInstance.BindTo_PE_${taskName}_${peIndex}_${pePortType}"
				val aiePort = s"ai_engine_0.PLIO_${aieTaskName}${peSuffix}_${pePortType}"

				if (fromPeToHardCilk) {
					s"sc=$aiePort:$bindPort"
				} else {
					s"sc=$bindPort:$aiePort"
				}
			}

		val outputConnections = buildTaskOutputConnections(descriptor, hardCilkInstance, aieEndpointByTask, streamSplitters)

		(directInputConnections ++ outputConnections)
			.distinct
			.sorted
	}

	private def buildTaskOutputConnections(
			descriptor: FullSysGenDescriptor,
			hardCilkInstance: String,
			aieEndpointByTask: Map[String, (String, String)],
			streamSplitters: Seq[StreamSplitterDef]
	): Seq[String] = {
		val outputsByTaskPePort = collectTaskOutputs(descriptor)
		val splitterByTaskPePort = streamSplitters.flatMap { s =>
			s.outputPortTypes.map(port => (s.taskName, s.peIndex, port) -> s)
		}.toMap

		outputsByTaskPePort.map { out =>
			val task = descriptor.taskDescriptors.find(_.name == out.taskName)
			val peCount = task.map(_.numProcessingElements).getOrElse(1)
			val peSuffix = suffixForPe(out.peIndex, peCount)
			val aieTaskName = aieEndpointByTask.getOrElse(out.taskName, (out.taskName, out.taskName))._2
			val bindPort = s"$hardCilkInstance.BindTo_PE_${out.taskName}_${out.peIndex}_${out.portType}"

			splitterByTaskPePort.get((out.taskName, out.peIndex, out.portType)) match {
				case Some(splitter) =>
					val outputIdx = splitter.outputPortTypes.indexOf(out.portType)
					s"sc=${splitter.instanceName}.outputs_$outputIdx:$bindPort"
				case None =>
					val aiePort = s"ai_engine_0.PLIO_${aieTaskName}${peSuffix}_${out.portType}"
					s"sc=$aiePort:$bindPort"
			}
		} ++ streamSplitters.map { splitter =>
			val task = descriptor.taskDescriptors.find(_.name == splitter.taskName)
			val peCount = task.map(_.numProcessingElements).getOrElse(1)
			val peSuffix = suffixForPe(splitter.peIndex, peCount)
			val aieTaskName = aieEndpointByTask.getOrElse(splitter.taskName, (splitter.taskName, splitter.taskName))._2
			val aiePort = s"ai_engine_0.PLIO_${aieTaskName}${peSuffix}_${splitter.aiePortType}"
			s"sc=$aiePort:${splitter.instanceName}.input"
		}
	}

	private def buildStreamSplitterDefs(descriptor: FullSysGenDescriptor): Seq[StreamSplitterDef] = {
		val outputsByTaskPe = collectTaskOutputs(descriptor).groupBy(out => (out.taskName, out.peIndex))
		val instanceCounterByKernel = mutable.Map[String, Int]().withDefaultValue(0)

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
						val kernelName = s"StreamSplitter_${widths.mkString("_")}"
						instanceCounterByKernel(kernelName) += 1
						val instanceName = s"${kernelName}_${instanceCounterByKernel(kernelName)}"

						StreamSplitterDef(
							kernelName = kernelName,
							instanceName = instanceName,
							taskName = taskName,
							peIndex = peIndex,
							aiePortType = group.map(_.portType).mkString("_"),
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
			val spawnNextWidth = getSpawnNextBundleWidthBits(descriptor, task)
			val argDataOutWidth = getArgDataOutBundleWidthBits(descriptor, task)
			(0 until task.numProcessingElements).flatMap { peIndex =>
				val argDataOut =
					if (task.generateArgOutWriteBuffer) Some(TaskOutputDef(task.name, peIndex, "argDataOut", argDataOutWidth)) else None
				val spawnNext =
					if (task.generateSpawnNextWriteBuffer) Some(TaskOutputDef(task.name, peIndex, "spawnNext", spawnNextWidth)) else None
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

	private def getSpawnNextDataWidthBits(descriptor: FullSysGenDescriptor, task: TaskDescriptor): Int = {
		descriptor.spawnNextList
			.get(task.name)
			.map(_.flatMap(target => descriptor.taskDescriptors.find(_.name == target).map(_.widthTask)))
			.filter(_.nonEmpty)
			.map(_.max)
			.getOrElse(task.widthTask)
	}

	private def getSpawnNextBundleWidthBits(descriptor: FullSysGenDescriptor, task: TaskDescriptor): Int = {
		val wAddr = descriptor.widthAddress
		val wData = getSpawnNextDataWidthBits(descriptor, task)
		val wAllow = if (task.variableSpawn) 0 else 32
		val nAllow = 1 + descriptor.spawnList.getOrElse(task.name, List.empty).count(_ != task.name)
		val totalSize = wAddr + wData + 32 + nAllow * wAllow
		nextPow2(totalSize)
	}

	private def getArgDataOutDataWidthBits(descriptor: FullSysGenDescriptor, task: TaskDescriptor): Int = {
		if (task.generateArgOutWriteBuffer && (descriptor.mFPGASimulation || descriptor.mFPGASynth)) {
			task.argumentSizeList.headOption.getOrElse(0)
		} else {
			0
		}
	}

	private def getArgDataOutBundleWidthBits(descriptor: FullSysGenDescriptor, task: TaskDescriptor): Int = {
		val wAddr = descriptor.widthAddress
		val wData = getArgDataOutDataWidthBits(descriptor, task)
		val wAllow = 32
		val nAllow = 1
		val totalSize = wAddr + wData + 32 + nAllow * wAllow
		nextPow2(totalSize)
	}

	private def nextPow2(value: Int): Int = {
		var x = 1
		while (x < value) x = x << 1
		x
	}

	private def buildSubPEConnections(helperKernels: List[HelperKernelDef]): Seq[String] = {
		val helperBySubPE = helperKernels.map(k => k.subPEName -> k).toMap

		helperKernels.flatMap { k =>
			val thisSubPE = normalizeName(k.subPEName)
			(0 until k.taskPeCount).flatMap { peIndex =>
				val peSuffix = suffixForPe(peIndex, k.taskPeCount)

				if (k.requestType == "read") {
					val outPort =
						if (k.mode == "stream") s"readStream${k.portWidth}Out"
						else s"readSingle${k.portWidth}Out"

					val start = s"sc=ai_engine_0.PLIO_${thisSubPE}${peSuffix}_$outPort:${k.instanceName}.sourceTasks_${peIndex}"

					val chain = k.nextSubPE.flatMap(nextName => helperBySubPE.get(nextName)).map { nextKernel =>
						val nextSubPE = normalizeName(nextKernel.subPEName)
						val inPort =
							if (k.mode == "stream") s"readStream${k.portWidth}In"
							else s"readSingle${k.portWidth}In"

						s"sc=${k.instanceName}.sinkResults_${peIndex}:ai_engine_0.PLIO_${nextSubPE}${peSuffix}_$inPort"
					}

					Seq(start) ++ chain.toSeq
				} else {
					val outPort =
						if (k.mode == "stream") s"writeStream${k.portWidth}Out"
						else s"writeSingle${k.portWidth}Out"
					Seq(s"sc=ai_engine_0.PLIO_${thisSubPE}${peSuffix}_$outPort:${k.instanceName}.sourceTasks_${peIndex}")
				}
			}
		}
	}

	private def buildAieEndpointByTask(descriptor: FullSysGenDescriptor): Map[String, (String, String)] = {
		descriptor.taskDescriptors.map { task =>
			val orderedSubPEs = getOrderedSubPENamesForTask(descriptor, task.name)
			if (orderedSubPEs.nonEmpty) {
				task.name -> (orderedSubPEs.head, orderedSubPEs.last)
			} else {
				task.name -> (task.name, task.name)
			}
		}.toMap
	}

	private def getOrderedSubPENamesForTask(descriptor: FullSysGenDescriptor, taskName: String): Seq[String] = {
		val subpes = descriptor.subPEList
			.toSeq
			.filter { case (_, sub) => sub.peName == taskName }
			.map { case (name, sub) =>
				(name, normalizeName(name), sub.rwRequest.nextsubPE)
			}

		if (subpes.isEmpty) {
			Seq.empty
		} else {
			val byName = subpes.map(s => s._1 -> s).toMap
			val incomingTargets = subpes.flatMap(_._3).toSet
			val heads = subpes.filterNot(s => incomingTargets.contains(s._1)).sortBy(_._1)

			val ordered = mutable.ArrayBuffer[String]()
			val visited = mutable.Set[String]()

			def walk(start: (String, String, Option[String])): Unit = {
				var current = Option(start)
				while (current.nonEmpty && !visited.contains(current.get._1)) {
					val value = current.get
					ordered += value._2
					visited += value._1
					current = value._3.flatMap(byName.get)
				}
			}

			heads.foreach(walk)
			subpes.sortBy(_._1).filterNot(s => visited.contains(s._1)).foreach(walk)
			ordered.toSeq
		}
	}

	private def suffixForPe(peIndex: Int, peCount: Int): String =
		if (peCount > 1) s"_$peIndex" else ""

	private def normalizeName(value: String): String =
		value.toLowerCase.replaceAll("[^a-z0-9_]", "")
}
