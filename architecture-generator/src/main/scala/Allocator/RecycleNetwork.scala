package Allocator

import chisel3._
import chisel3.util._

/** Closed, data-only ring carrying beats of freed continuation addresses from
  * the resolution collectors to the recycle writers.
  *
  * Same registered shift-ring as the allocator distribution network, and it
  * reuses the same two node types -- collectors inject
  * (AllocatorServerNetworkUnit), writers tap (AllocatorNetworkUnit). Writers are
  * spread evenly through the collectors so a beat is never far from one.
  *
  * A writer only takes a beat it has already reserved a region slot for, so a
  * refused beat keeps circulating rather than dying at a full writer. Because
  * every address in existence came from some region and total capacity equals
  * that address count, all regions full would mean nothing is live and nothing
  * is in transit -- so a beat on the ring is itself proof that some writer has
  * room for it. The ring therefore cannot deadlock and no region can overflow.
  */
class RecycleNetwork(
    beatWidth: Int,
    collectorCount: Int,
    writerCount: Int
) extends Module {

  require(collectorCount >= 1 && writerCount >= 1)

  val io = IO(new Bundle {
    val collectorIn = Vec(collectorCount, Flipped(DecoupledIO(UInt(beatWidth.W))))
    val writerOut = Vec(writerCount, DecoupledIO(UInt(beatWidth.W)))
  })

  private val collectorUnits =
    Seq.fill(collectorCount)(Module(new AllocatorServerNetworkUnit(beatWidth)))
  private val writerUnits =
    Seq.fill(writerCount)(Module(new AllocatorNetworkUnit(beatWidth)))

  private val ringLength = collectorCount + writerCount
  private val step = ringLength.asInstanceOf[Double] / writerCount
  private val writerIndices = Array.tabulate(writerCount)(n => (n * step).toInt)

  private var writerSeen = 0
  private var collectorSeen = 0
  private val ringNodes = Array.tabulate(ringLength) { i =>
    if (writerIndices.contains(i)) {
      val idx = writerSeen
      writerSeen += 1
      (true, idx)
    } else {
      val idx = collectorSeen
      collectorSeen += 1
      (false, idx)
    }
  }

  private def dataIn(i: Int): UInt = {
    val (isWriter, idx) = ringNodes(i)
    if (isWriter) writerUnits(idx).io.addressIn else collectorUnits(idx).io.addressIn
  }
  private def validIn(i: Int): Bool = {
    val (isWriter, idx) = ringNodes(i)
    if (isWriter) writerUnits(idx).io.validIn else collectorUnits(idx).io.validIn
  }
  private def dataOut(i: Int): UInt = {
    val (isWriter, idx) = ringNodes(i)
    if (isWriter) writerUnits(idx).io.addressOut else collectorUnits(idx).io.addressOut
  }
  private def validOut(i: Int): Bool = {
    val (isWriter, idx) = ringNodes(i)
    if (isWriter) writerUnits(idx).io.validOut else collectorUnits(idx).io.validOut
  }

  for (i <- 1 until ringLength) {
    dataIn(i) := dataOut(i - 1)
    validIn(i) := validOut(i - 1)
  }
  // Close the ring so a beat a full writer refused circulates to another one.
  dataIn(0) := dataOut(ringLength - 1)
  validIn(0) := validOut(ringLength - 1)

  for (i <- 0 until collectorCount) {
    collectorUnits(i).io.addressIn1 <> io.collectorIn(i)
  }
  for (i <- 0 until writerCount) {
    writerUnits(i).io.casAddressOut <> io.writerOut(i)
  }
}
