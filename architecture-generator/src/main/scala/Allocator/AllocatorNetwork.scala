package Allocator

import chisel3._
import chisel3.util._

class AllocatorNetworkIO(addrWidth: Int, peCount: Int, vcasCount: Int)
    extends Bundle {
  val connVCAS = Vec(vcasCount, Flipped(DecoupledIO(UInt(addrWidth.W))))
  val connPE = Vec(peCount, DecoupledIO(UInt(addrWidth.W)))
}

class AllocatorNetwork(
    addrWidth: Int,
    peCount: Int,
    queueDepth: Int,
    vcasCount: Int
) extends Module {

  val io = IO(new AllocatorNetworkIO(addrWidth, peCount, vcasCount))

  val vcasNetworkUnits =
    Seq.fill(vcasCount)(Module(new AllocatorServerNetworkUnit(addrWidth)))
  val networkUnits =
    Seq.fill(peCount)(Module(new AllocatorNetworkUnit(addrWidth)))
  val casServers = Seq.fill(peCount)(Module(new AllocatorClient(addrWidth)))
  val queues =
    Seq.fill(peCount)(Module(new AllocatorBuffer(addrWidth, queueDepth)))

  val step = (peCount + vcasCount).asInstanceOf[Double] / vcasCount
  val vcasIndicies = Array.tabulate(vcasCount)(n => (n * step).toInt)

  var networkUnitsCount = 0
  var vcasNetworkUnitsCount = 0
  val virtualNetwork = Array.tabulate(peCount + vcasCount) { i =>
    if (vcasIndicies.contains(i)) {
      val index = vcasNetworkUnitsCount
      vcasNetworkUnitsCount += 1
      (true, index)
    } else {
      val index = networkUnitsCount
      networkUnitsCount += 1
      (false, index)
    }
  }

  val ringLength = peCount + vcasCount

  // Ring shift-register ports for node i, whether it is a VCAS injection unit or
  // a PE tap unit (both expose the same (address, valid) shift-register ports).
  def ringAddressIn(i: Int): UInt = {
    val (isVcas, idx) = virtualNetwork(i)
    if (isVcas) vcasNetworkUnits(idx).io.addressIn
    else networkUnits(idx).io.addressIn
  }
  def ringValidIn(i: Int): Bool = {
    val (isVcas, idx) = virtualNetwork(i)
    if (isVcas) vcasNetworkUnits(idx).io.validIn
    else networkUnits(idx).io.validIn
  }
  def ringAddressOut(i: Int): UInt = {
    val (isVcas, idx) = virtualNetwork(i)
    if (isVcas) vcasNetworkUnits(idx).io.addressOut
    else networkUnits(idx).io.addressOut
  }
  def ringValidOut(i: Int): Bool = {
    val (isVcas, idx) = virtualNetwork(i)
    if (isVcas) vcasNetworkUnits(idx).io.validOut
    else networkUnits(idx).io.validOut
  }

  // Connecting the ring: each node shifts from its predecessor.
  for (i <- 1 until ringLength) {
    ringAddressIn(i) := ringAddressOut(i - 1)
    ringValidIn(i) := ringValidOut(i - 1)
  }

  // Closing the ring: node 0 shifts from the last node, so a surplus address no
  // PE consumed on a lap circulates back to a hungry PE instead of dead-ending.
  ringAddressIn(0) := ringAddressOut(ringLength - 1)
  ringValidIn(0) := ringValidOut(ringLength - 1)

  // Tap each PE off the ring through its CAS server and local queue.
  for (i <- 0 until peCount) {
    networkUnits(i).io.casAddressOut <> casServers(i).io.addressIn
    casServers(i).io.addressOut <> queues(i).io.addressIn
    queues(i).io.addressOut <> io.connPE(i)
  }

  // Feed each VCAS injection unit with free addresses from its VCAS server.
  for (i <- 0 until vcasCount) {
    vcasNetworkUnits(i).io.addressIn1 <> io.connVCAS(i)
  }
}
