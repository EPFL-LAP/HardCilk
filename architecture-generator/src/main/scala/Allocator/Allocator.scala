package Allocator

import chisel3._
import chisel3.util.Valid

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
    addrWidth: Int, // HBM address width (e.g. 34): read address + compact continuation packing
    peCount: Int,
    vcasCount: Int,
    queueDepth: Int,
    pePortWidth: Int, // output pointer width to the PE (e.g. 64); addresses zero-extended to it
    // Number of resolution branches feeding freed addresses back in. Zero disables
    // recycling completely.
    recycleSourceCount: Int = 0
) extends Module
    with AllocatorModule {

  require(vcasCount >= 1 && peCount >= 1)

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

  private val enableRecycling = recycleSourceCount > 0

  val vcas =
    Seq.fill(vcasCount)(
      Module(
        new AllocatorServer(
          dataWidth = memDataWidth,
          sysAddressWidth = addrWidth,
          burstLength = 15,
          enableRecycling = enableRecycling
        )
      )
    )

  val vcasRvmRO = Seq.fill(vcasCount)(
    Module(
      new AllocatorAXIAdapter(
        dataWidth = memDataWidth,
        addrWidth = addrWidth,
        burstLength = 15,
        enableWrite = enableRecycling
      )
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

    vcasRvmRO(i).io.read_address <> vcas(i).io.read_address
    vcasRvmRO(i).io.read_data <> vcas(i).io.read_data
    vcas(i).io.dataOut <> continuationNetwork.io.connVCAS(i)
  }

  // ---- Continuation recycling -------------------------------------------------
  // Freed addresses are packed into beats at each resolution branch, ride a
  // closed ring, and are written back into a server's own region through the
  // write half of that server's port.
  override val io_recycle =
    if (enableRecycling)
      Some(IO(Vec(recycleSourceCount, Flipped(Valid(UInt(addrWidth.W))))))
    else None
  // Aggregate addresses dropped by the collectors. Should read zero; a nonzero
  // value means a collector queue is undersized and the pool is shrinking.
  val io_leaked = if (enableRecycling) Some(IO(Output(UInt(64.W)))) else None

  if (enableRecycling) {
    val collectors = Seq.fill(recycleSourceCount)(
      Module(new ResolutionCollector(memDataWidth, addrWidth))
    )
    val recycleNetwork = Module(
      new RecycleNetwork(memDataWidth, recycleSourceCount, vcasCount)
    )
    val writers = Seq.fill(vcasCount)(
      Module(new RecycleWriter(memDataWidth, addrWidth, burstLength = 15))
    )

    for (i <- 0 until recycleSourceCount) {
      collectors(i).io.in <> io_recycle.get(i)
      collectors(i).io.beatOut <> recycleNetwork.io.collectorIn(i)
    }
    for (i <- 0 until vcasCount) {
      writers(i).io.beatIn <> recycleNetwork.io.writerOut(i)
      writers(i).io.server <> vcas(i).io.recycle.get
      writers(i).io.write_address <> vcasRvmRO(i).io.write_address.get
      writers(i).io.write_data <> vcasRvmRO(i).io.write_data.get
      vcasRvmRO(i).io.write_last.get := writers(i).io.write_last
      writers(i).io.write_done := vcasRvmRO(i).io.write_done.get
    }

    val totalLeaked = collectors.map(_.io.leaked).reduce(_ +& _)
    io_leaked.get := totalLeaked
    vcas.foreach(_.io.leakedIn.get := totalLeaked)
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
