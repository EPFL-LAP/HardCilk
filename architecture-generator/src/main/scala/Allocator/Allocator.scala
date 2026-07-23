package Allocator

import chisel3._

import Util._

import chext.amba.axi4
import chext.amba.axi4s
import axi4.Ops._
import axi4s.Casts._

class ClosureAllocatorPEIO(
    pePortWidth: Int,
    peCount: Int
) extends Bundle {
  implicit val axisCfgAddress: axi4s.Config =
    axi4s.Config(wData = pePortWidth, onlyRV = true)
  val closureOut = Vec(peCount, axi4s.Master(axisCfgAddress))

  def getPort(name: String, index: Int): axi4s.Interface = {
    name match {
      case "closureOut" => closureOut(index)
      case "mallocOut"  => closureOut(index)
    }
  }
}

class ClosureAllocatorAxiIO(
    axiMgmtCfg: axi4.Config,
    vcasCount: Int,
    vcasAxiFullCfg: axi4.Config
) extends Bundle {
  val nAxiPorts = vcasCount

  val vcas_axi_full = Vec(nAxiPorts, axi4.full.Master(vcasAxiFullCfg))
  val axi_mgmt_vcas = Vec(vcasCount, axi4.lite.Slave(axiMgmtCfg))
}

/** *
  *
  * @param addrWidth
  *   The width of the system address
  *
  * @param peCount
  *   The total number of PEs that issue a spawn_next of this task type
  *
  * @param queueDepth
  *   The depth of the queue that holds the continuation addresses for the
  *   connected PEs
  *
  * @param taskName
  *   The name of the task that is being spawned next
  */

class Allocator(
    addrWidth: Int,   // HBM address width (e.g. 34): read address + compact continuation packing
    peCount: Int,
    vcasCount: Int,
    queueDepth: Int,
    pePortWidth: Int  // output pointer width to the PE (e.g. 64); addresses zero-extended to it
) extends Module {

  require(vcasCount >= 1 && peCount >= 1)
  // HBM beat / task width; continuations pack at addrWidth - log2(memDataWidth/8)
  // significant bits (closures are memDataWidth-bit aligned) -- see AllocatorServer.
  // The distribution ring carries whole packed beats; each VCAS server has ONE
  // injection leg and the per-PE BeatUnpackers do the unpacking at the edge.
  private val memDataWidth = 256

  val continuationNetwork = Module(
    new AllocatorNetwork(
      beatWidth = memDataWidth,
      sysAddressWidth = addrWidth,
      pePortWidth = pePortWidth,
      peCount = peCount,
      queueDepth = queueDepth,
      vcasCount = vcasCount
    )
  )

  val vcas =
    Seq.fill(vcasCount)(
      Module(
        new AllocatorServer(
          dataWidth = memDataWidth,
          sysAddressWidth = addrWidth,
          burstLength = 15
        )
      )
    )

  val vcasRvmRO = Seq.fill(vcasCount)(
    Module(
      new RVtoAXIBridge(memDataWidth, addrWidth, write = false, burstLength = 15)
    )
  )

  val axiFullPorts = vcasRvmRO.map(_.axi)

  val io_export = IO(
    new ClosureAllocatorPEIO(pePortWidth = pePortWidth, peCount = peCount)
  )
  val io_internal = IO(
    new ClosureAllocatorAxiIO(
      vcas(0).regBlock.cfgAxi,
      vcasCount,
      axiFullPorts(0).cfg
    )
  )
  val io_paused = IO(Output(Bool()))
  io_paused := vcas.map(_.io.paused).reduce(_ || _)

  for (i <- 0 until vcasCount) {
    io_internal.axi_mgmt_vcas(i) :=> vcas(i).io.axi_mgmt

    vcasRvmRO(i).io.read.get.address <> vcas(i).io.read_address
    vcasRvmRO(i).io.read.get.data <> vcas(i).io.read_data
    vcas(i).io.dataOut <> continuationNetwork.io.connVCAS(i)
  }

  axiFullPorts.zip(io_internal.vcas_axi_full).foreach { case (port, s_axi) =>
    port :=> s_axi
  }

  // closureOut and the continuation network are both pePortWidth, so the unpacked
  // pointers wire straight to the PE closure ports -- no width conversion.
  for (i <- 0 until peCount) {
    io_export.closureOut(i).asLite <> continuationNetwork.io.connPE(i)
  }
}
