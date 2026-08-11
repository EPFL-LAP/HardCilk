package Allocator

import chisel3._
import chisel3.util._

/** Packs freed continuation addresses into whole beats for the recycle ring.
  *
  * One collector sits at each resolution branch (every ArgumentServer spawn lane
  * plus every SlowArgumentHandler). Collecting here rather than after the ring
  * is what makes the rates work out: a branch resolves at most one address per
  * cycle, so packing numPackedPerBeat of them into a beat gives the shared ring
  * an 8:1 compression and one beat/cycle downstream matches the writer exactly.
  *
  * The input is a Valid tap on the spawn handshake, never a consumer of it. If
  * this collector cannot take an address it DROPS it and counts it. That is
  * deliberate: making the tap backpressure the spawn would let a stalled recycle
  * path wedge the argument server's resolution credits and deadlock the machine,
  * whereas a dropped address only shrinks the pool by one closure -- a leak that
  * is bounded, harmless, and above all visible. `leaked` should read zero; if it
  * does not, the queue depth is too small.
  */
class ResolutionCollector(
    dataWidth: Int,       // recycle ring beat width (matches the allocator's)
    sysAddressWidth: Int, // byte addresses in, compact packed addresses out
    beatQueueDepth: Int = 4
) extends Module {

  // Must mirror AllocatorServer/BeatUnpacker packing exactly.
  private val addressAlignmentBits = log2Ceil(dataWidth / 8)
  private val continuationAddressBits = sysAddressWidth - addressAlignmentBits
  private val numPackedPerBeat = dataWidth / continuationAddressBits
  private val padBits = dataWidth - numPackedPerBeat * continuationAddressBits
  require(numPackedPerBeat >= 1)
  require(beatQueueDepth >= 2)

  val io = IO(new Bundle {
    val in = Flipped(Valid(UInt(sysAddressWidth.W)))
    val beatOut = Decoupled(UInt(dataWidth.W))
    val leaked = Output(UInt(64.W))
    val residue = Output(UInt(log2Ceil(numPackedPerBeat + 1).W))
  })

  private val beatQueue = Module(new Queue(UInt(dataWidth.W), beatQueueDepth))
  private val laneIndex = RegInit(0.U(log2Ceil(numPackedPerBeat.max(2)).W))
  private val lanes = Reg(Vec(numPackedPerBeat, UInt(continuationAddressBits.W)))
  private val leaked = RegInit(0.U(64.W))

  private val compact = io.in.bits(sysAddressWidth - 1, addressAlignmentBits)
  private val isLastLane = laneIndex === (numPackedPerBeat - 1).U

  // Room is only needed on the arrival that completes a beat; the partial beat
  // lives in registers and costs nothing to hold. Dropping the arrival rather
  // than the assembled beat keeps a leak to a single address.
  private val canAccept = !isLastLane || beatQueue.io.enq.ready

  private val assembled = VecInit(Seq.tabulate(numPackedPerBeat) { i =>
    if (i == numPackedPerBeat - 1) compact else lanes(i)
  })
  beatQueue.io.enq.valid := io.in.valid && isLastLane
  beatQueue.io.enq.bits :=
    (if (padBits > 0) Cat(0.U(padBits.W), Cat(assembled.reverse))
     else Cat(assembled.reverse))

  when(io.in.valid && canAccept) {
    lanes(laneIndex) := compact
    laneIndex := Mux(isLastLane, 0.U, laneIndex + 1.U)
  }
  when(io.in.valid && !canAccept) {
    leaked := leaked + 1.U
  }

  beatQueue.io.deq <> io.beatOut
  io.leaked := leaked
  io.residue := laneIndex
}
