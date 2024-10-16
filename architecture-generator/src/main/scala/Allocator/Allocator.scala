package Allocator

import chisel3._
import chisel3.util._

import AXIHelpers._
import Util._

import chext.amba.axi4
import chext.amba.axi4s
import axi4.Ops._
import axi4s.Casts._
import axi4.full.components._

class ClosureAllocatorPEIO(
    pePortWidth: Int,
    peCount: Int
) extends Bundle {
  implicit val axisCfgAddress: axi4s.Config = axi4s.Config(wData = pePortWidth, onlyRV = true)
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
    vcasAxiFullCfg: axi4.Config,
    reduceAxi: Boolean
) extends Bundle {
  val nAxiPorts = if (reduceAxi) 1 else vcasCount
  private val wId = if (reduceAxi) log2Ceil(vcasCount) else 0

  val vcas_axi_full = Vec(nAxiPorts, axi4.full.Master(vcasAxiFullCfg.copy(wId = vcasAxiFullCfg.wId + wId)))
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
  *   The depth of the queue that holds the continuation addresses for the connected PEs
  *
  * @param taskName
  *   The name of the task that is being spawned next
  */

class Allocator(
    addrWidth: Int,
    peCount: Int,
    vcasCount: Int,
    queueDepth: Int,
    pePortWidth: Int,
    reduceAxi: Boolean
) extends Module {

  val continuationNetwork = Module(
    new AllocatorNetwork(addrWidth = addrWidth, peCount = peCount, queueDepth = queueDepth, vcasCount = vcasCount)
  )

  val vcas =
    Seq.fill(vcasCount)(Module(new AllocatorServer(dataWidth = addrWidth, sysAddressWidth = addrWidth, burstLength = 15)))

  val vcasAxiFullCfg = axi4.Config(
    wAddr = addrWidth,
    wData = addrWidth,
    lite = false,
    wId = 1
  )

  val vcasAxiFullCfgSlave = vcasAxiFullCfg.copy(wId = vcasAxiFullCfg.wId + vcasCount)

  val io_export = IO(new ClosureAllocatorPEIO(pePortWidth = pePortWidth, peCount = peCount))
  val io_internal = IO(new ClosureAllocatorAxiIO(vcas(0).regBlock.cfgAxi, vcasCount, vcasAxiFullCfgSlave, reduceAxi))

  val vcasRvmRO = Seq.fill(vcasCount)(Module(new RVtoAXIBridge(addrWidth, addrWidth, write = false, burstLength = 15)))

  val axiFullPorts = vcasRvmRO.map(_.axi)

  for (i <- 0 until vcasCount) {
    io_internal.axi_mgmt_vcas(i) :=> vcas(i).io.axi_mgmt

    vcasRvmRO(i).io.read.get.address <> vcas(i).io.read_address
    vcasRvmRO(i).io.read.get.data <> vcas(i).io.read_data
    vcas(i).io.dataOut <> continuationNetwork.io.connVCAS(i)
  }

  if (reduceAxi) {
    val mux = Module(
      new Mux(new MuxConfig(axiSlaveCfg = vcasAxiFullCfg, numSlaves = vcasCount, slaveBuffers = axi4.BufferConfig.all(2)))
    )
    axiFullPorts.zip(mux.s_axi).foreach { case (port, s_axi) => port :=> s_axi }
    mux.m_axi :=> io_internal.vcas_axi_full(0)
  } else {
    axiFullPorts.zip(io_internal.vcas_axi_full).foreach { case (port, s_axi) => port :=> s_axi }
  }

  val axis_stream_converters_out =
    Seq.fill(peCount)(Module(new AxisDataWidthConverter(dataWidthIn = addrWidth, dataWidthOut = pePortWidth)))
  for (i <- 0 until peCount) {
    axis_stream_converters_out(i).io.dataIn.lite <> continuationNetwork.io.connPE(i)
    io_export.closureOut(i).lite <> axis_stream_converters_out(i).io.dataOut.lite
  }
}
