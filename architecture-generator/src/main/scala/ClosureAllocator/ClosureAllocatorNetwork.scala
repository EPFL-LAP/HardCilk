package ClosureAllocator

import chisel3._
import chisel3.util._

class ClosureAllocatorNetworkIO(addrWidth: Int, peCount: Int, vcasCount: Int) extends Bundle {
  val connVCAS = Vec(vcasCount, Flipped(DecoupledIO(UInt(addrWidth.W))))
  val connPE = Vec(peCount, DecoupledIO(UInt(addrWidth.W)))
}

class ClosureAllocatorNetwork(addrWidth: Int, peCount: Int, queueDepth: Int, vcasCount: Int) extends Module {

  val io = IO(new ClosureAllocatorNetworkIO(addrWidth, peCount, vcasCount))

  val vcasNetworkUnits = Seq.fill(vcasCount)(Module(new ClosureAllocatorServerNetworkUnit(addrWidth)))
  val networkUnits = Seq.fill(peCount)(Module(new ClosureAllocatorNetworkUnit(addrWidth)))
  val casServers = Seq.fill(peCount)(Module(new AllocatorClient(addrWidth)))
  val queues = Seq.fill(peCount)(Module(new ClosureAllocatorBuffer(addrWidth, queueDepth)))

  val step = peCount / vcasCount
  val vcasIndicies = Array.tabulate(vcasCount)(n => (n + n * step))

  // io.connVCAS <> networkUnits(0).io.addressIn
  // networkUnits(peCount-1).io.addressOut.ready := false.B

  var networkUnitsCount = 0
  var vcasNetworkUnitsCount = 0

  vcasNetworkUnits(0).io.addressIn0.valid := false.B
  vcasNetworkUnits(0).io.addressIn0.bits := DontCare

  // everyone connects only its outputs to its next input
  for (i <- 0 until (peCount + vcasCount - 1)) {

    if (vcasIndicies.contains(i)) {

      vcasNetworkUnits(vcasNetworkUnitsCount).io.addressOut <> networkUnits(networkUnitsCount).io.addressIn

      vcasNetworkUnitsCount += 1

    } else if (vcasIndicies.contains(i + 1)) {

      networkUnits(networkUnitsCount).io.addressOut <> vcasNetworkUnits(vcasNetworkUnitsCount).io.addressIn0

      networkUnitsCount += 1

    } else {

      networkUnits(networkUnitsCount).io.addressOut <> networkUnits(networkUnitsCount + 1).io.addressIn

      networkUnitsCount += 1

    }
  }

  networkUnits(peCount - 1).io.addressOut.ready := false.B

  for (i <- 0 until peCount) {
    networkUnits(i).io.casAddressOut <> casServers(i).io.addressIn
    casServers(i).io.addressOut <> queues(i).io.addressIn
    queues(i).io.addressOut <> io.connPE(i)
  }

  for (i <- 0 until vcasCount) {
    vcasNetworkUnits(i).io.addressIn1 <> io.connVCAS(i)
  }
}
