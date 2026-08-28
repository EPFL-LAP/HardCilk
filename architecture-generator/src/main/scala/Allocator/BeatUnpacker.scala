package Allocator

import chisel3._
import chisel3.util._

// Per-PE edge unpacker for the beat-granularity allocator ring.

class BeatUnpackerIO(beatWidth: Int, pePortWidth: Int) extends Bundle {
  val beatIn = Flipped(DecoupledIO(UInt(beatWidth.W)))
  val addressOut = DecoupledIO(UInt(pePortWidth.W))
}

class BeatUnpacker(
    beatWidth: Int, // HBM beat width (packing basis)
    sysAddressWidth: Int, // HBM address width
    pePortWidth: Int, // addresses zero-extended
    beatQueueDepth: Int
) extends Module {

  private val addressAlignmentBits = log2Ceil(beatWidth / 8)
  private val continuationAddressBits = sysAddressWidth - addressAlignmentBits
  private val numPackedPerBeat = beatWidth / continuationAddressBits
  private val zeroPadBits =
    pePortWidth - continuationAddressBits - addressAlignmentBits
  require(numPackedPerBeat >= 1)
  require(zeroPadBits >= 0)
  require(beatQueueDepth >= 2)

  val io = IO(new BeatUnpackerIO(beatWidth, pePortWidth))

  val beatQueue = Module(new Queue(UInt(beatWidth.W), beatQueueDepth))
  beatQueue.io.enq <> io.beatIn

  private val laneIndex = RegInit(0.U(log2Ceil(numPackedPerBeat.max(2)).W))
  private val lanes = VecInit(Seq.tabulate(numPackedPerBeat) { i =>
    beatQueue.io.deq
      .bits((i + 1) * continuationAddressBits - 1, i * continuationAddressBits)
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
