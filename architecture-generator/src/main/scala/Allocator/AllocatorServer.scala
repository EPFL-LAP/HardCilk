package Allocator

import chisel3._
import chisel3.util._

import chext.amba.axi4
import axi4.lite.components.RegisterBlock

class AllocatorServerIO(
    dataWidth: Int,
    regBlock: RegisterBlock,
    sysAddressWidth: Int
) extends Bundle {
  // Raw packed beats straight off HBM. Unpacking into individual continuation
  // addresses happens at the edge (BeatUnpacker), not here: the beat IS the unit
  // of distribution on the allocator ring.
  val dataOut = DecoupledIO(UInt(dataWidth.W))
  val axi_mgmt = axi4.lite.Slave(regBlock.cfgAxi)
  val read_address = DecoupledIO(UInt(sysAddressWidth.W))
  val read_data = Flipped(DecoupledIO(UInt(dataWidth.W)))
  val paused = Output(Bool())
}

class AllocatorServer(
    dataWidth: Int,       // memory/HBM-beat width (e.g. 256): read_data + packing basis
    sysAddressWidth: Int, // HBM address width (e.g. 34): read_address + compact significant bits
    burstLength: Int,     // AXI ARLEN (beats - 1); the RVtoAXIBridge issues FIXED bursts of this length
    maxOutstandingReadBursts: Int = 8
) extends Module {

  assert(burstLength <= 15) // 15 is equivalent to 16 beats

  // Continuations point to dataWidth-bit (dataWidth/8-byte) aligned task closures,
  // so the low log2(dataWidth/8) address bits are always zero and are dropped. Each
  // pointer packs into sysAddressWidth - log2(dataWidth/8) significant bits =>
  // dataWidth/that per beat (any remaining beat bits are left zero). BeatUnpacker
  // (at the PE tap) must use the SAME constants to slice them back out.
  private val addressAlignmentBits = log2Ceil(dataWidth / 8)
  private val continuationAddressBits = sysAddressWidth - addressAlignmentBits
  private val numPackedPerBeat = dataWidth / continuationAddressBits
  require(numPackedPerBeat >= 1)

  private val burstBeats = burstLength + 1
  private val contsPerBurst = burstBeats * numPackedPerBeat

  // Read-ahead engine sized like SchedulerServer's: keep up to
  // maxOutstandingReadBursts bursts claimed against a local buffer one burst
  // deeper than the watermark, so a full HBM round-trip latency's worth of beats
  // is in flight and the ring can drain a fresh beat EVERY cycle (8 addresses/
  // cycle sustained) instead of trickling one burst at a time.
  private val localQueueDepth = maxOutstandingReadBursts * burstBeats
  private val readAheadLowWatermark = (maxOutstandingReadBursts - 1) * burstBeats
  private val readCountWidth = log2Ceil(maxOutstandingReadBursts + 1) + 1
  private val readBeatCountWidth = log2Ceil(localQueueDepth + burstBeats + 1) + 1

  val regBlock = new RegisterBlock(wAddr = 6, wData = 64, wMask = 6)
  val io = IO(new AllocatorServerIO(dataWidth, regBlock, sysAddressWidth))

  io.axi_mgmt.suggestName("0_S_AXI_MGMT")
  regBlock.s_axil <> io.axi_mgmt

  private val rAddr = RegInit(0.U(64.W))
  private val rPause = RegInit(0.U(64.W))
  private val avaialbleSize = RegInit(
    0.U(64.W)
  ) // Size is in packed continuations, not bytes

  regBlock.base(0x00)
  regBlock.reg(
    rPause,
    read = true,
    write = true,
    desc = "Register to indicate whether the FSM is paused or not."
  )
  regBlock.reg(
    rAddr,
    read = true,
    write = true,
    desc = "Base address of virtual continuation FIFO"
  )
  regBlock.reg(
    avaialbleSize,
    read = true,
    write = true,
    desc = "Availble address FIFO size"
  )
  io.paused := rPause =/= 0.U

  val readyAddressChunksFIFO = Module(
    new Queue(UInt(dataWidth.W), localQueueDepth)
  )

  private val outstandingReads = RegInit(0.U(readCountWidth.W))
  private val inflightReadBeats = RegInit(0.U(readBeatCountWidth.W))
  private val returnedReadBeats = RegInit(0.U(log2Ceil(burstBeats + 1).W))

  // Issue-ahead: beats already buffered plus beats claimed by in-flight bursts.
  // A new burst may issue while claimed is below the watermark; the buffer is one
  // burst deeper than the watermark so an issued burst always has room to land.
  private val claimedReadBeats =
    readyAddressChunksFIFO.io.count +& inflightReadBeats
  private val wantReadBurst =
    outstandingReads < maxOutstandingReadBursts.U &&
      claimedReadBeats < readAheadLowWatermark.U &&
      rPause === 0.U

  // The free-address FIFO is consumed from the top downward: each issued burst
  // claims the contsPerBurst continuations just below avaialbleSize, which is
  // decremented at issue so concurrent outstanding bursts never overlap.
  io.read_address.bits := (rAddr + (
    ((avaialbleSize - contsPerBurst.U) >> log2Ceil(numPackedPerBeat)) << addressAlignmentBits
  ))(sysAddressWidth - 1, 0)
  io.read_address.valid := wantReadBurst && avaialbleSize >= contsPerBurst.U

  // Out of free continuations: self-pause (host observes rPause and refills /
  // resizes). This also holds the engine off out of reset until the host programs
  // rAddr/avaialbleSize and clears rPause.
  when(wantReadBurst && avaialbleSize < contsPerBurst.U) {
    rPause := "hFFFFFFFFFFFFFFFF".U
  }

  when(io.read_address.fire) {
    avaialbleSize := avaialbleSize - contsPerBurst.U
  }

  private val readLastBeat =
    io.read_data.fire && returnedReadBeats === burstLength.U

  // outstandingReads: +1 per issued AR, -1 per completed returned burst.
  when(io.read_address.fire && !readLastBeat) {
    outstandingReads := outstandingReads + 1.U
  }.elsewhen(!io.read_address.fire && readLastBeat) {
    outstandingReads := outstandingReads - 1.U
  }

  // inflightReadBeats: += the issued burst, -= each returned beat.
  inflightReadBeats := inflightReadBeats +
    Mux(io.read_address.fire, burstBeats.U, 0.U) -
    Mux(io.read_data.fire, 1.U, 0.U)

  // Bursts are fixed-length and same-id (in-order), so a mod-burstBeats counter
  // of returned beats is enough to detect burst completion.
  when(io.read_data.fire) {
    when(returnedReadBeats === burstLength.U) {
      returnedReadBeats := 0.U
    }.otherwise {
      returnedReadBeats := returnedReadBeats + 1.U
    }
  }

  // When a chunk comes in, add it to the queue
  readyAddressChunksFIFO.io.enq.valid := io.read_data.valid
  readyAddressChunksFIFO.io.enq.bits := io.read_data.bits
  io.read_data.ready := readyAddressChunksFIFO.io.enq.ready

  // Whole beats go out; the ring carries them to whichever PE tap wants one.
  io.dataOut <> readyAddressChunksFIFO.io.deq

  // Reply to axi management operations.
  when(regBlock.rdReq) {
    regBlock.rdOk()
  }
  when(regBlock.wrReq) {
    regBlock.wrOk()
  }
}
