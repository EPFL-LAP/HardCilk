package Allocator

import chisel3._
import chisel3.util._

class AllocatorNetworkIO(beatWidth: Int, pePortWidth: Int, peCount: Int, vcasCount: Int)
    extends Bundle {
  val connVCAS = Vec(vcasCount, Flipped(DecoupledIO(UInt(beatWidth.W))))
  val connPE = Vec(peCount, DecoupledIO(UInt(pePortWidth.W)))
}

// Beat-granularity allocator distribution ring.
//
// The ring slots carry whole packed HBM beats (numPackedPerBeat continuation
// addresses each), NOT individual addresses. Each VCAS injection leg feeds raw
// beats straight from its server's read FIFO; each PE tap grabs one whole beat
// when its local BeatUnpacker has room and unpacks it privately at the PE's own
// drain rate. One leg sustains 1 beat/cycle = numPackedPerBeat addresses/cycle
// aggregate, and a flat-out PE only needs a beat every numPackedPerBeat cycles,
// so the taps' first-come-first-served greed is rate-capped by construction and
// no tap can starve the ones behind it (the old 1-address slots let an upstream
// PE swallow the whole stream).
class AllocatorNetwork(
    beatWidth: Int,
    sysAddressWidth: Int,
    pePortWidth: Int,
    peCount: Int,
    queueDepth: Int, // per-PE buffered addresses (descriptor knob); converted to whole beats
    vcasCount: Int
) extends Module {

  private val addressAlignmentBits = log2Ceil(beatWidth / 8)
  private val continuationAddressBits = sysAddressWidth - addressAlignmentBits
  private val numPackedPerBeat = beatWidth / continuationAddressBits
  // At least 2 beats so a tap can accept the next beat while one drains
  // (gapless 1 address/cycle to a flat-out PE despite ring hop latency).
  private val beatQueueDepth =
    ((queueDepth + numPackedPerBeat - 1) / numPackedPerBeat).max(2)

  val io = IO(new AllocatorNetworkIO(beatWidth, pePortWidth, peCount, vcasCount))

  val vcasNetworkUnits =
    Seq.fill(vcasCount)(Module(new AllocatorServerNetworkUnit(beatWidth)))
  val networkUnits =
    Seq.fill(peCount)(Module(new AllocatorNetworkUnit(beatWidth)))
  val unpackers = Seq.fill(peCount)(
    Module(
      new BeatUnpacker(
        beatWidth = beatWidth,
        sysAddressWidth = sysAddressWidth,
        pePortWidth = pePortWidth,
        beatQueueDepth = beatQueueDepth
      )
    )
  )

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
  // a PE tap unit (both expose the same (beat, valid) shift-register ports).
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

  // Closing the ring: node 0 shifts from the last node, so a surplus beat no
  // PE consumed on a lap circulates back to a hungry PE instead of dead-ending.
  ringAddressIn(0) := ringAddressOut(ringLength - 1)
  ringValidIn(0) := ringValidOut(ringLength - 1)

  // Tap each PE off the ring through its private beat unpacker.
  for (i <- 0 until peCount) {
    networkUnits(i).io.casAddressOut <> unpackers(i).io.beatIn
    unpackers(i).io.addressOut <> io.connPE(i)
  }

  // Feed each VCAS injection unit with packed beats from its VCAS server.
  for (i <- 0 until vcasCount) {
    vcasNetworkUnits(i).io.addressIn1 <> io.connVCAS(i)
  }
}
