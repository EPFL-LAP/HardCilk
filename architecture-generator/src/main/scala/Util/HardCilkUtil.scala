package Util

import Descriptors._
import Scheduler._
import Allocator._
import ArgumentNotifier._
import HLSHelpers._
import HardCilk.HardCilkBuilder.PortToExport

/**
 * Shared utility functions for building and connecting the HardCilk system.
 */
object HardCilkUtil {

  /**
   * Finds the physical Chisel port for a HardCilk subsystem (Scheduler, Allocator, etc.).
   * Assumes port.parentType is "HardCilk".
   */
  def getPhysicalSubsystemPort(
      port: PortDescriptor,
      scheds: Map[String, Scheduler],
      allocs: Map[String, Allocator],
      notifiers: Map[String, ArgumentNotifier],
      memAllocs: Map[String, Allocator]
  ): chisel3.Data = {
    port.portType match {
      case "taskIn" | "taskOut" | "taskInGlobal" =>
        scheds(port.parentName).io_export
          .getPort(port.portType, port.portIndex)
      case "closureOut" =>
        allocs(port.parentName).io_export
          .getPort(port.portType, port.portIndex)
      case "mallocOut" =>
        memAllocs(port.parentName).io_export
          .getPort(port.portType, port.portIndex)
      case "argIn" =>
        notifiers(port.parentName).io_export
          .getPort(port.portType, port.portIndex)
    }
  }

  def getPhysicalWBPort(
      port: PortDescriptor,
      spawnNextWBs: Map[String, Seq[WriteBuffer]],
      sendArgumentWBs: Map[String, Seq[WriteBuffer]]
  ): chisel3.Data = {
    port.parentType match {
      case "spawnNextWB" =>
        spawnNextWBs(port.parentName)(port.parentIndex).getPort(port.portType, port.portIndex)
      case "sendArgumentWB" =>
        sendArgumentWBs(port.parentName)(port.parentIndex).getPort(port.portType, port.portIndex)
    }
  }

  /**
   * Finds the physical Chisel port for any component (HardCilk subsystem or PE).
   */
  def getPhysicalPort(
      port: PortDescriptor,
      scheds: Map[String, Scheduler],
      allocs: Map[String, Allocator],
      notifiers: Map[String, ArgumentNotifier],
      memAllocs: Map[String, Allocator],
      pes: Map[String, Seq[VitisWriteBufferModule]],
      spawnNextWBs: Map[String, Seq[WriteBuffer]],
      sendArgumentWBs: Map[String, Seq[WriteBuffer]]
  ): chisel3.Data = {
    port.parentType match {
      case "HardCilk" =>
        // Call the other helper for the HardCilk case
        getPhysicalSubsystemPort(port, scheds, allocs, notifiers, memAllocs)
      case "PE" =>
        pes(port.parentName)(port.parentIndex)
          .getPort(port.portType)
      case "spawnNextWB" | "sendArgumentWB" =>
        getPhysicalWBPort(port, spawnNextWBs, sendArgumentWBs)
    }
  }

  /**
    * A method to decide whether an interface of a non-existent PE needs a write buffer or not
    * based on the task descriptor and the type of the port
    */

  def needsWriteBuffer(
    port: PortToExport,
    desc: FullSysGenDescriptor
  ): Boolean = {
    
    val taskName = port.pePortDescriptor.parentName
    val taskDesciptor = desc.taskDescriptors.filter(t => t.name == taskName)(0)

    port.pePortDescriptor.portType match {
      case "taskOutGlobal"  =>
        taskDesciptor.generateSpawnNextWriteBuffer
      case "argOut"  =>
        taskDesciptor.generateArgOutWriteBuffer
      case "taskOut"  =>
        taskDesciptor.generateSpawnNextWriteBuffer
      case _ =>
        false
    }
  }
}
