package SoftwareUtil.aie
import Descriptors._
import java.io.PrintWriter

object ConnectivityTemplate {
	private case class HelperKernelDef(
			kernelName: String,
			instanceName: String,
			subPEName: String,
			taskName: String,
			peIndex: Int,
			taskPeCount: Int,
			requestType: String,
			mode: String,
			portWidth: Int,
			nextSubPE: Option[String]
	)

	def generateConnectivityCfg(descriptor: FullSysGenDescriptor, projectFolder: String): Unit = {
		val hardCilkKernelName = s"${descriptor.name}"
		val hardCilkInstance = s"${hardCilkKernelName}_1"
		val helperKernels = buildHelperKernelDefs(descriptor)
		val hardCilkAxiPortCount = getHardCilkAxiPortCount(descriptor)

		val sectionConnectivity =
			Seq("[connectivity]") ++
				buildNkLines(hardCilkKernelName, hardCilkInstance, helperKernels) ++
				Seq("") ++
				buildSpLines(hardCilkInstance, helperKernels, hardCilkAxiPortCount) ++
				Seq("") ++
				buildTaskConnections(descriptor, hardCilkInstance) ++
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
		descriptor.subPEList.toList.sortBy(_._1).flatMap { case (subPEName, subPE) =>
			val req = subPE.rwRequest
			val kernelName = helperKernelName(req.`type`, req.mode, req.portWidth)
			val task = taskMap(subPE.peName)

			(0 until task.numProcessingElements).map { peIndex =>
				val instanceBase = helperInstanceBase(req.`type`, req.mode, req.portWidth)
				instanceCounterByBase(instanceBase) += 1
				val instanceName = s"${instanceBase}_${instanceCounterByBase(instanceBase)}"

				HelperKernelDef(
					kernelName = kernelName,
					instanceName = instanceName,
					subPEName = subPEName,
					taskName = subPE.peName,
					peIndex = peIndex,
					taskPeCount = task.numProcessingElements,
					requestType = req.`type`,
					mode = req.mode,
					portWidth = req.portWidth,
					nextSubPE = req.nextsubPE
				)
			}
		}
	}

	private def helperInstanceBase(requestType: String, mode: String, portWidth: Int): String = {
		(requestType, mode) match {
			case ("read", "single")  => s"ReadSingle_Basic_64_$portWidth"
			case ("read", "stream")  => s"ReadStream_64_$portWidth"
			case ("write", "single") => s"WriteSingle_Basic_64_$portWidth"
			case ("write", "stream") => s"WriteStream_Basic_64_$portWidth"
			case _ =>
				throw new IllegalArgumentException(s"Unsupported rwRequest combination: type=$requestType mode=$mode")
		}
	}

	private def helperKernelName(requestType: String, mode: String, portWidth: Int): String = {
		(requestType, mode) match {
			case ("read", "single")  => s"ReadSingle_Basic_64_$portWidth"
			case ("read", "stream")  => s"ReadStreamWSplitter_Basic_64_$portWidth"
			case ("write", "single") => s"WriteSingle_Basic_64_$portWidth"
			case ("write", "stream") => s"WriteStream_Basic_64_$portWidth"
			case _ =>
				throw new IllegalArgumentException(s"Unsupported rwRequest combination: type=$requestType mode=$mode")
		}
	}

	private def buildNkLines(
			hardCilkKernelName: String,
			hardCilkInstance: String,
			helperKernels: List[HelperKernelDef]
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

		nkHardCilk ++ nkHelpers
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
			.map(_.getNumServers("argumentNotifier"))
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

	private def buildTaskConnections(descriptor: FullSysGenDescriptor, hardCilkInstance: String): Seq[String] = {
		val peCountsByTask = descriptor.taskDescriptors.map(t => t.name -> t.numProcessingElements).toMap
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

		val deducedConnections = hcPeConnections
			.distinct
			.map { case (taskName, peIndex, pePortType, fromPeToHardCilk) =>
				val aieTaskName = taskName
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

		val extraWriteBufferConnections = buildWriteBufferExportConnections(descriptor, hardCilkInstance)

		(deducedConnections ++ extraWriteBufferConnections)
			.distinct
			.sorted
	}

	private def buildWriteBufferExportConnections(
			descriptor: FullSysGenDescriptor,
			hardCilkInstance: String
	): Seq[String] = {
		descriptor.taskDescriptors.flatMap { task =>
			val aieTaskName = task.name
			(0 until task.numProcessingElements).flatMap { peIndex =>
				val peSuffix = suffixForPe(peIndex, task.numProcessingElements)

				val argDataOutConnection =
					if (task.generateArgOutWriteBuffer) {
						val bindPort = s"$hardCilkInstance.BindTo_PE_${task.name}_${peIndex}_argDataOut"
						val aiePort = s"ai_engine_0.PLIO_${aieTaskName}${peSuffix}_argDataOut"
						Some(s"sc=$aiePort:$bindPort")
					} else {
						None
					}

				val spawnNextConnection =
					if (task.generateSpawnNextWriteBuffer) {
						val bindPort = s"$hardCilkInstance.BindTo_PE_${task.name}_${peIndex}_spawnNext"
						val aiePort = s"ai_engine_0.PLIO_${aieTaskName}${peSuffix}_spawnNext"
						Some(s"sc=$aiePort:$bindPort")
					} else {
						None
					}

				Seq(argDataOutConnection, spawnNextConnection).flatten
			}
		}
	}

	private def buildSubPEConnections(helperKernels: List[HelperKernelDef]): Seq[String] = {
		val helperBySubPEAndPe = helperKernels.map(k => (k.subPEName, k.peIndex) -> k).toMap

		helperKernels.flatMap { k =>
			val thisSubPE = normalizeName(k.subPEName)
			val peSuffix = suffixForPe(k.peIndex, k.taskPeCount)
			if (k.requestType == "read") {
				val outPort =
					if (k.mode == "stream") s"readStream${k.portWidth}Out"
					else s"readSingle${k.portWidth}Out"

				val start = s"sc=ai_engine_0.PLIO_${thisSubPE}${peSuffix}_$outPort:${k.instanceName}.sourceTask"

				val chain = k.nextSubPE.flatMap(nextName => helperBySubPEAndPe.get((nextName, k.peIndex))).map { nextKernel =>
					val nextSubPE = normalizeName(nextKernel.subPEName)
					val inPort =
						if (k.mode == "stream") s"readStream${k.portWidth}In"
						else s"readSingle${k.portWidth}In"

					s"sc=${k.instanceName}.sinkResult:ai_engine_0.PLIO_${nextSubPE}${peSuffix}_$inPort"
				}

				Seq(start) ++ chain.toSeq
			} else {
				val outPort =
					if (k.mode == "stream") s"writeStream${k.portWidth}Out"
					else s"writeSingle${k.portWidth}Out"
				Seq(s"sc=ai_engine_0.PLIO_${thisSubPE}${peSuffix}_$outPort:${k.instanceName}.sourceTask")
			}
		}
	}

	private def suffixForPe(peIndex: Int, peCount: Int): String =
		if (peCount > 1) s"_$peIndex" else ""

	private def normalizeName(value: String): String =
		value.toLowerCase.replaceAll("[^a-z0-9_]", "")
}
