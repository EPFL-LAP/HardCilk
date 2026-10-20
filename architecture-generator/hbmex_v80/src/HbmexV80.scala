package hcv80

// HBMEX (hbmex-fccm2025) per-HBM-port attachment for the HardCilk V80 flow.
//
// HbmexV80Full  : the complete HBMEX path of SpmvExp3 (Stripe -> IdParallelizeNoReadBurst
//                 -> Enhance), preceded by an adapter (chext IdSerialize + chext Unburst)
//                 that turns HardCilk's multi-ID, bursting AXI4 into the only traffic
//                 IdParallelizeNoReadBurst accepts: one ID (wId = 0), AxLEN = 0 only.
// HbmexV80Stripe: HBMEX's Stripe alone (address-bit permutation; any ID/burst that does
//                 not cross a 2^stripeLog2 boundary, i.e. anything after the 4KB splitter).
//
// Stripe permutation for the V80 HBM NMU map: every NMU sees 32 GB linearly at
// 0x40_0000_0000, pseudo channel = offset[34:30]. Bits [34:30] are swapped with
// [stripeLog2+4 : stripeLog2], so consecutive 2^stripeLog2-byte blocks go to
// consecutive PCs (HBMEX's transformation #5, re-targeted from the U55C's [33:29]).
// The swap is an involution: the same permutation maps physical -> logical.

import chisel3._
import chisel3.util._

import chext.elastic
import elastic.ConnectOp._

import chext.amba.axi4
import axi4.Ops._

import hbmex.components.stripe
import hbmex.components.id_parallelize
import hbmex.components.enhance

case class HbmexV80Config(
    wIdIn: Int = 12,
    wAddr: Int = 64,
    wData: Int = 256,
    stripeLog2: Int = 14,
    pcLsb: Int = 30,
    pcBits: Int = 5,
    wIdParallel: Int = 8,
    segmentOffset: Int = 30,
    log2Segments: Int = 5,
    log2lenIdQueue: Int = 6,
    idQueueR: Int = 256,
    idQueueW: Int = 64,
    unburstQueue: Int = 32
) {
  require(stripeLog2 >= 12, "the stripe granule must be >= 4KB (AXI bursts never cross 4KB)")
  require(stripeLog2 + pcBits <= pcLsb)

  def axiCfg(wId: Int) = axi4.Config(
    wId = wId,
    wAddr = wAddr,
    wData = wData,
    hasLock = false,
    hasCache = true,
    hasProt = true,
    hasQos = false,
    hasRegion = false
  )

  val perm: Seq[Int] = (0 until wAddr).map { i =>
    if (i >= stripeLog2 && i < stripeLog2 + pcBits) pcLsb + (i - stripeLog2)
    else if (i >= pcLsb && i < pcLsb + pcBits) stripeLog2 + (i - pcLsb)
    else i
  }
  // Stripe selects transformations(REG_SELECT); REG_SELECT resets to 0 and the
  // control port is tied off, so entry 0 is the one in use (two entries: the
  // select must be at least one bit wide).
  def stripeCfg(wId: Int) = stripe.StripeConfig(1, axiCfg(wId), Seq(perm, perm))
}

object TieOffControl {
  def apply(c: axi4.RawInterface): Unit = {
    c.ARVALID.foreach(_ := false.B)
    c.ARADDR.foreach(_ := 0.U)
    c.ARPROT.foreach(_ := 0.U)
    c.RREADY.foreach(_ := true.B)
    c.AWVALID.foreach(_ := false.B)
    c.AWADDR.foreach(_ := 0.U)
    c.AWPROT.foreach(_ := 0.U)
    c.WVALID.foreach(_ := false.B)
    c.WDATA.foreach(_ := 0.U)
    c.WSTRB.foreach(_ := 0.U)
    c.BREADY.foreach(_ := true.B)
  }
}

class HbmexV80Stripe(cfg: HbmexV80Config = HbmexV80Config()) extends Module {
  import cfg._
  override val desiredName = "HbmexV80Stripe"

  val S_AXI = IO(axi4.Slave(axiCfg(wIdIn)))
  val M_AXI = IO(axi4.Master(axiCfg(wIdIn)))

  private val stripe0 = Module(new stripe.Stripe(stripeCfg(wIdIn)))
  TieOffControl(stripe0.S_AXI_CONTROL)

  S_AXI.asFull :=> axi4.full.MasterBuffer(stripe0.S_AXI(0).asFull, axi4.BufferConfig.all(2))
  stripe0.M_AXI(0).asFull :=> M_AXI.asFull
}

class HbmexV80Full(cfg: HbmexV80Config = HbmexV80Config()) extends Module {
  import cfg._
  override val desiredName = "HbmexV80Full"

  private val cfgIn = axiCfg(wIdIn)
  private val cfg0 = axiCfg(0)

  // adapter: many IDs -> ID 0 (in order), bursts -> single beats
  private val idSerialize0 = Module(
    new axi4.full.components.IdSerialize(
      axi4.full.components.IdSerializeConfig(cfgIn, capacityIdQueueR = idQueueR, capacityIdQueueW = idQueueW)
    )
  )
  private val unburst0 = Module(
    new axi4.full.components.Unburst(
      axi4.full.components.UnburstConfig(cfg0, arQueueCapacity = unburstQueue, awQueueCapacity = unburstQueue)
    )
  )

  // HBMEX proper (as in hbmex.experiments.SpmvExp3)
  private val stripe0 = Module(new stripe.Stripe(stripeCfg(0)))
  TieOffControl(stripe0.S_AXI_CONTROL)

  private val idParallelize0 = Module(
    new id_parallelize.IdParallelizeNoReadBurst(
      id_parallelize.IdParallelizeNoReadBurstConfig(axiSlaveCfg = cfg0, wIdMaster = wIdParallel)
    )
  )
  private val enhance0 = Module(
    new enhance.Enhance(
      enhance.EnhanceConfig(
        axiSlaveCfg = idParallelize0.m_axi.cfg,
        wSegmentOffset = segmentOffset,
        log2numSegments = log2Segments,
        log2lenIdQueue = log2lenIdQueue
      )
    )
  )

  val S_AXI = IO(axi4.Slave(cfgIn))
  val M_AXI = IO(axi4.Master(enhance0.m_axi.cfg))

  private val bc = axi4.BufferConfig.all(2)
  S_AXI.asFull :=> axi4.full.MasterBuffer(idSerialize0.s_axi, bc)
  idSerialize0.m_axi :=> axi4.full.MasterBuffer(unburst0.s_axi, bc)
  unburst0.m_axi :=> axi4.full.MasterBuffer(stripe0.S_AXI(0).asFull, bc)
  stripe0.M_AXI(0).asFull :=> axi4.full.MasterBuffer(idParallelize0.s_axi, bc)
  idParallelize0.m_axi :=> axi4.full.MasterBuffer(enhance0.s_axi, bc)
  enhance0.m_axi :=> axi4.full.MasterBuffer(M_AXI.asFull, bc)
}

object EmitHbmexV80 extends App {
  val outDir = if (args.nonEmpty) args(0) else "./emit"
  val log2 = if (args.length > 1) args(1).toInt else 14
  val cfg = HbmexV80Config(stripeLog2 = log2)
  emitVerilog(new HbmexV80Stripe(cfg), Array("--target-dir", outDir))
  emitVerilog(new HbmexV80Full(cfg), Array("--target-dir", outDir))
}
