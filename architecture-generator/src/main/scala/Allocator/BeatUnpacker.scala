package Allocator

import chisel3._
import chisel3.util._

// Per-PE edge unpacker for the beat-granularity allocator ring.
//
// The allocator ring carries whole packed HBM beats (numPackedPerBeat
// continuation addresses each); a PE tap grabs an entire beat and this module
// pops the addresses out one at a time at the PE's own pace. Distributing at
// beat granularity is what makes the ring fair without arbitration: a PE can
// absorb at most one beat per numPackedPerBeat closureIn reads, so a hungry
// upstream tap is rate-capped by construction and beats flow past it to
// downstream PEs (previously a flat-out upstream PE could swallow the entire
// 1-address/cycle stream and starve everyone behind it).
//
// The beat queue must be at least 2 deep so the tap can accept the next beat
// while the current one drains: that hides the ring hop latency and sustains a
// gapless 1 address/cycle to a flat-out PE.
class BeatUnpackerIO(beatWidth: Int, pePortWidth: Int) extends Bundle {
  val beatIn = Flipped(DecoupledIO(UInt(beatWidth.W)))
  val addressOut = DecoupledIO(UInt(pePortWidth.W))
}

class BeatUnpacker(
    beatWidth: Int,       // HBM beat width (e.g. 256); packing basis
    sysAddressWidth: Int, // HBM address width (e.g. 34)
    pePortWidth: Int,     // output pointer width to the PE (e.g. 64); addresses zero-extended
    beatQueueDepth: Int
) extends Module {

  // Must mirror AllocatorServer's packing exactly: addresses are beatWidth-bit
  // aligned so the low alignment bits are dropped, and each beat carries
  // beatWidth / continuationAddressBits packed pointers (top pad bits unused).
  private val addressAlignmentBits = log2Ceil(beatWidth / 8)
  private val continuationAddressBits = sysAddressWidth - addressAlignmentBits
  private val numPackedPerBeat = beatWidth / continuationAddressBits
  private val zeroPadBits = pePortWidth - continuationAddressBits - addressAlignmentBits
  require(numPackedPerBeat >= 1)
  require(zeroPadBits >= 0)
  require(beatQueueDepth >= 2)

  val io = IO(new BeatUnpackerIO(beatWidth, pePortWidth))

  val beatQueue = Module(new Queue(UInt(beatWidth.W), beatQueueDepth))
  beatQueue.io.enq <> io.beatIn

  private val laneIndex = RegInit(0.U(log2Ceil(numPackedPerBeat.max(2)).W))
  private val lanes = VecInit(Seq.tabulate(numPackedPerBeat) { i =>
    beatQueue.io.deq.bits((i + 1) * continuationAddressBits - 1, i * continuationAddressBits)
  })

  io.addressOut.valid := beatQueue.io.deq.valid
  io.addressOut.bits :=
    (if (zeroPadBits > 0)
       Cat(0.U(zeroPadBits.W), lanes(laneIndex), 0.U(addressAlignmentBits.W))
     else
       Cat(lanes(laneIndex), 0.U(addressAlignmentBits.W)))

  private val lastLane = laneIndex === (numPackedPerBeat - 1).U
  // Pop the beat only when its final address is consumed.
  beatQueue.io.deq.ready := io.addressOut.ready && lastLane

  when(io.addressOut.fire) {
    laneIndex := Mux(lastLane, 0.U, laneIndex + 1.U)
  }
}
