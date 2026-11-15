package SoftwareUtil.aie

import Descriptors._
import scala.collection.mutable

/** Port selection shared by kernel XML and linker connectivity generation. */
private[aie] object KernelPortSupport {
  def isExternalPE(descriptor: FullSysGenDescriptor, taskName: String): Boolean =
    descriptor.taskDescriptors.exists(task => task.name == taskName && task.peHDLPath.isEmpty)

  def getHardCilkAxiPortCount(descriptor: FullSysGenDescriptor): Int = {
    val numHBMPorts = if (descriptor.maximumAXIPorts > 0) descriptor.maximumAXIPorts else 6

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
      // Use the same parsed interfaces as VitisWriteBufferModule: argOut
      // notifications alone do not imply an argDataOut memory write buffer.
      val hasArgDataOut = hasPEModule && HLSHelpers.VitisModuleFactory
        .parseVitisModule(task.peHDLPath, task.name, task, descriptor)
        .interfaces.exists(_.name == "argDataOut")

      val peCoreAxi = if (task.hasAXI && hasPEModule) task.numProcessingElements else 0
      val peSpawnNextAxi =
        if (descriptor.spawnNextList.contains(task.name) && hasPEModule)
          task.numProcessingElements
        else
          0
      val peArgOutAxi =
        if (hasArgDataOut && hasPEModule)
          task.numProcessingElements
        else
          0

      val wbSpawnNextAxi = if (task.generateSpawnNextWriteBuffer && !hasPEModule) task.numProcessingElements else 0
      val wbArgDataAxi = if (task.generateArgOutWriteBuffer && !hasPEModule) task.numProcessingElements else 0
      val peIORwAxi =
        if (!hasPEModule) {
          // PeIO shares one AXI master across all read/write sub-PEs in each PE replica.
          if (descriptor.subPEList.values.exists(sub => sub.peName == task.name && sub.rwRequest.nonEmpty))
            task.numProcessingElements
          else
            0
        } else {
          0
        }

      peCoreAxi + peSpawnNextAxi + peArgOutAxi + wbSpawnNextAxi + wbArgDataAxi + peIORwAxi
    }.sum
  }

}
