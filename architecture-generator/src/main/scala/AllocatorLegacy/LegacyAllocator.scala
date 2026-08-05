package AllocatorLegacy

import chisel3._
import AXIHelpers._
import Util._
import Allocator.{AllocatorModule, ClosureAllocatorAxiIO, ClosureAllocatorPEIO}
import chext.amba.axi4
import chext.amba.axi4s
import axi4.Ops._
import axi4s.Casts._

/** Allocator datapath extracted from commit 2469686. */
class LegacyAllocator(
    addrWidth: Int,
    peCount: Int,
    vcasCount: Int,
    queueDepth: Int,
    pePortWidth: Int
) extends Module with AllocatorModule {
  val continuationNetwork = Module(
    new AllocatorNetwork(addrWidth, peCount, queueDepth, vcasCount)
  )
  val vcas = Seq.fill(vcasCount)(Module(
    new LegacyAllocatorServer(addrWidth, addrWidth, burstLength = 15)
  ))
  val vcasAxiFullCfg = axi4.Config(
    wAddr = addrWidth, wData = addrWidth, lite = false, wId = 1
  )
  val vcasAxiFullCfgSlave =
    vcasAxiFullCfg.copy(wId = vcasAxiFullCfg.wId + vcasCount)

  val io_export = IO(new ClosureAllocatorPEIO(pePortWidth, peCount))
  val io_internal = IO(new ClosureAllocatorAxiIO(
    vcas(0).regBlock.cfgAxi, vcasCount, vcasAxiFullCfgSlave
  ))
  val io_paused = IO(Output(Bool()))
  io_paused := vcas.map(_.io.paused).reduce(_ || _)

  val vcasRvmRO = Seq.fill(vcasCount)(Module(
    new RVtoAXIBridge(addrWidth, addrWidth, write = false, burstLength = 15)
  ))
  val axiFullPorts = vcasRvmRO.map(_.axi)

  for (i <- 0 until vcasCount) {
    io_internal.axi_mgmt_vcas(i) :=> vcas(i).io.axi_mgmt
    vcasRvmRO(i).io.read.get.address <> vcas(i).io.read_address
    vcasRvmRO(i).io.read.get.data <> vcas(i).io.read_data
    vcas(i).io.dataOut <> continuationNetwork.io.connVCAS(i)
  }
  axiFullPorts.zip(io_internal.vcas_axi_full).foreach { case (port, s_axi) =>
    port :=> s_axi
  }

  val converters = Seq.fill(peCount)(Module(
    new AxisDataWidthConverter(addrWidth, pePortWidth)
  ))
  for (i <- 0 until peCount) {
    converters(i).io.dataIn.lite <> continuationNetwork.io.connPE(i)
    io_export.closureOut(i).lite <> converters(i).io.dataOut.lite
  }
}
