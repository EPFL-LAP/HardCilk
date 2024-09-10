package ClosureAllocator

import chisel3._

import AXIHelpers._
import ClosureAllocator._
import Util._

import chext.amba.axi4
import chext.amba.axi4s
import axi4.Ops._
import axi4s.Casts._
import axi4.full.components._

class ClosureAllocatorPEIO(
    val pePortWidth: Int,
    val peCount: Int
) extends Bundle {
  implicit val axisCfgAddress: axi4s.Config = axi4s.Config(wData = pePortWidth, onlyRV = true)
  val closureOut = Vec(peCount, axi4s.Master(axisCfgAddress))

  def getPort(name: String, index: Int): axi4s.Interface = {
    name match {
      case "closureOut" => closureOut(index)
    }
  }
}

class ClosureAllocatorAxiIO(
    val axiMgmtCfg: axi4.Config,
    val vcasCount: Int,
    val vcasAxiFullCfg: axi4.Config
) extends Bundle {
  val axi_mgmt_vcas = Vec(vcasCount, axi4.lite.Slave(axiMgmtCfg))
  val vcas_axi_full = axi4.full.Master(vcasAxiFullCfg)
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

class ClosureAllocator(
    val addrWidth: Int,
    val peCount: Int,
    val vcasCount: Int,
    val queueDepth: Int,
    val pePortWidth: Int
) extends Module {

  val continuationNetwork = Module(
    new ClosureAllocatorNetwork(addrWidth = addrWidth, peCount = peCount, queueDepth = queueDepth, vcasCount = vcasCount)
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
  val io_internal = IO(new ClosureAllocatorAxiIO(vcas(0).regBlock.cfgAxi, vcasCount, vcasAxiFullCfgSlave))

  val vcasRvmRO = Seq.fill(vcasCount)(Module(new RVtoAXIBridge(addrWidth, addrWidth, write = false, burstLength = 15)))

  val mux = Module(
    new Mux(axiCfgSlave = vcasAxiFullCfg, numSlaves = vcasCount, muxCfg = MuxConfig(slaveBuffers = axi4.BufferConfig.all(2)))
  )
  mux.m_axi :=> io_internal.vcas_axi_full

  for (i <- 0 until vcasCount) {
    io_internal.axi_mgmt_vcas(i) :=> vcas(i).io.axi_mgmt

    vcasRvmRO(i).io.read.get.address <> vcas(i).io.read_address
    vcasRvmRO(i).io.read.get.data <> vcas(i).io.read_data
    vcasRvmRO(i).axi :=> mux.s_axi(i)
    vcas(i).io.dataOut <> continuationNetwork.io.connVCAS(i)
  }

  val axis_stream_converters_out =
    Seq.fill(peCount)(Module(new AxisDataWidthConverter(dataWidthIn = addrWidth, dataWidthOut = pePortWidth)))
  for (i <- 0 until peCount) {
    axis_stream_converters_out(i).io.dataIn.lite <> continuationNetwork.io.connPE(i)
    io_export.closureOut(i).lite <> axis_stream_converters_out(i).io.dataOut.lite
  }
}
/*
class wrapper() extends Module {
    val alloc = Module (new ClosureAllocator(
                                addrWidth = 64,
                                peCount = 8,
                                queueDepth = 2,
                        ))


    val vcasConn = IO(chiselTypeOf(alloc.io)).suggestName("fib")
    vcasConn <> alloc.io
}
 */
