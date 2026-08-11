package Allocator

import chisel3._
import chisel3.util._

import chext.amba.axi4
import axi4.lite.components.RegisterBlock

/** Handshake between an AllocatorServer's free-address FIFO and the
  * RecycleWriter that shares its AXI port. The server owns the read end and the
  * occupancy; the writer owns the tail pointer and its own burst accumulation,
  * so neither module needs the other's internals.
  */
class AllocatorRecyclePort(sysAddressWidth: Int, beatCountWidth: Int)
    extends Bundle {
  // Beats safe to write: already read BACK (not merely claimed by an issued
  // burst) and not yet re-filled. A slot claimed by an in-flight read is
  // excluded, which is what keeps concurrent AR/AW on one port safe without any
  // global read-after-write ordering.
  val writableBeats = Output(UInt(beatCountWidth.W))
  val baseAddress = Output(UInt(sysAddressWidth.W))
  val capacityBeats = Output(UInt(beatCountWidth.W))
  // Low while the host is still programming the region (and after a terminal
  // out-of-addresses pause), so the writer never targets an unprogrammed base.
  val running = Output(Bool())
  // One pulse per write burst whose B response landed. Only then do the
  // recycled addresses become visible to the read side.
  val commit = Input(Bool())
}

class AllocatorServerIO(
    dataWidth: Int,
    regBlock: RegisterBlock,
    sysAddressWidth: Int,
    beatCountWidth: Int,
    enableRecycling: Boolean
) extends Bundle {
  // Raw packed beats straight off HBM. Unpacking into individual continuation
  // addresses happens at the edge (BeatUnpacker), not here: the beat IS the unit
  // of distribution on the allocator ring.
  val dataOut = DecoupledIO(UInt(dataWidth.W))
  val axi_mgmt = axi4.lite.Slave(regBlock.cfgAxi)
  val read_address = DecoupledIO(UInt(sysAddressWidth.W))
  val read_data = Flipped(DecoupledIO(UInt(dataWidth.W)))
  val paused = Output(Bool())
  val recycle =
    if (enableRecycling)
      Some(new AllocatorRecyclePort(sysAddressWidth, beatCountWidth))
    else None
  // Addresses dropped by this task's resolution collectors, summed by the
  // Allocator. Computed entirely in the collectors; mirrored here only so the
  // host reads it alongside the low-water mark.
  val leakedIn = if (enableRecycling) Some(Input(UInt(64.W))) else None
}

/** Free-continuation-address FIFO server.
  *
  * With recycling the region at `rAddr` is a circular FIFO that a RecycleWriter
  * refills from the tail through the write half of this same AXI port, so reads
  * walk it with a wrapping pointer and hand addresses out in ascending order.
  * Without it, behaviour is unchanged from the original downward stack.
  *
  * Every access is a full burst and the host places the region on a burst-size
  * boundary with a whole number of bursts in it, so no burst can cross a 4 KB
  * boundary or straddle the wrap: no runtime burst splitting anywhere.
  */
class AllocatorServer(
    dataWidth: Int,       // memory/HBM-beat width (e.g. 256): read_data + packing basis
    sysAddressWidth: Int, // HBM address width (e.g. 34): read_address + compact significant bits
    burstLength: Int,     // AXI ARLEN (beats - 1); the RVtoAXIBridge issues FIXED bursts of this length
    maxOutstandingReadBursts: Int = 8,
    enableRecycling: Boolean = false
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
  // A beat holds numPackedPerBeat addresses; the region can hold at most one
  // beat per dataWidth/8 bytes of the address space.
  private val perBeatShift = log2Ceil(numPackedPerBeat)
  private val beatCountWidth = sysAddressWidth - addressAlignmentBits + 1

  val regBlock = new RegisterBlock(wAddr = 6, wData = 64, wMask = 6)
  val io = IO(
    new AllocatorServerIO(
      dataWidth,
      regBlock,
      sysAddressWidth,
      beatCountWidth,
      enableRecycling
    )
  )

  io.axi_mgmt.suggestName("0_S_AXI_MGMT")
  regBlock.s_axil <> io.axi_mgmt

  private val rAddr = RegInit(0.U(64.W))
  private val rPause = RegInit(0.U(64.W))
  private val avaialbleSize = RegInit(
    0.U(64.W)
  ) // Size is in packed continuations, not bytes
  // Region size in packed continuations. Zero means "the region is exactly the
  // initial fill", which is the non-recycling case: the pointer walks it once
  // and never wraps, so every existing host works unchanged.
  private val capacitySize = RegInit(0.U(64.W))
  // Smallest occupancy seen while running. On a successful run this says how
  // much of the pool was never needed -- the only way to size the pool
  // empirically, since peak live closures cannot be derived up front. Zero
  // means "never observed" (nothing was ever handed out).
  private val lowWaterSize = RegInit(0.U(64.W))
  private val leakedAddresses = RegInit(0.U(64.W))
  // Continuations issued from the pool since reset. Only `capacity` distinct
  // addresses exist, so a value above capacity is direct proof that addresses
  // came back and were handed out again -- the simplest possible check that
  // recycling actually ran.
  private val handedOutTotal = RegInit(0.U(64.W))

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
  regBlock.reg(
    capacitySize,
    read = true,
    write = true,
    desc = "Circular FIFO capacity in continuations (0 = no wrap)"
  )
  regBlock.reg(
    lowWaterSize,
    read = true,
    write = true,
    desc = "Lowest occupancy observed while running"
  )
  regBlock.reg(
    leakedAddresses,
    read = true,
    write = true,
    desc = "Addresses dropped by the resolution collectors (should stay 0)"
  )
  regBlock.reg(
    handedOutTotal,
    read = true,
    write = true,
    desc = "Continuations issued from the pool (> capacity means it recycled)"
  )
  io.leakedIn.foreach(leakedAddresses := _)
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

  // Without recycling the FIFO is consumed from the top downward: each issued
  // burst claims the contsPerBurst continuations just below avaialbleSize. The
  // read offset is then a pure function of avaialbleSize, which is what lets the
  // host refill by simply rewriting the region and the size register.
  //
  // With recycling the region is circular -- the writer refills it from the tail
  // -- so reads walk it with an explicit wrapping pointer instead. Nothing ever
  // resets that pointer: running out is terminal, so there is no resume to
  // re-arm.
  private val readOffset =
    if (enableRecycling) {
      val readPtr = RegInit(0.U(64.W))
      when(io.read_address.fire) {
        val next = readPtr +& contsPerBurst.U
        readPtr := Mux(next >= capacitySize, next - capacitySize, next)
      }
      readPtr
    } else {
      avaialbleSize - contsPerBurst.U
    }

  io.read_address.bits := (rAddr + (
    (readOffset >> perBeatShift) << addressAlignmentBits
  ))(sysAddressWidth - 1, 0)
  io.read_address.valid := wantReadBurst && avaialbleSize >= contsPerBurst.U

  // Out of free continuations. Without recycling this is terminal: nothing will
  // ever refill the region, so self-pause and let the host observe rPause. (The
  // register also holds the engine off out of reset until rAddr/avaialbleSize
  // are programmed, which is unchanged either way.)
  //
  // With recycling it is NEVER terminal, so there is no self-pause at all.
  // read_address.valid already gates on having a whole burst free, and a commit
  // re-arms it by itself -- so "no free addresses" is just backpressure, and the
  // engine picks straight back up when the writer lands a burst.
  //
  // Latching a pause here would be self-inflicted deadlock. avaialbleSize
  // reaching zero does not mean the addresses are gone; it means they are live
  // inside the design -- buffered, on the ring, or held by PEs as open closures.
  // A pool sized as an admission cap is SUPPOSED to sit at zero free addresses
  // whenever the cap is saturated. Worse, the read-ahead engine chases
  // readAheadLowWatermark beats, so for any pool smaller than that watermark
  // wantReadBurst is permanently asserted and the pause fires during startup,
  // before a single closure has retired. Detecting an actual hang is the host
  // watchdog's job, not this counter's.
  if (!enableRecycling) {
    when(wantReadBurst && avaialbleSize < contsPerBurst.U) {
      rPause := "hFFFFFFFFFFFFFFFF".U
    }
  }

  // A recycled burst joins the free pool only once its B response has landed,
  // which is what stops the read side from handing out an address whose write
  // may not have reached memory yet.
  private val recycleCommit =
    io.recycle.map(_.commit).getOrElse(false.B)
  when(io.read_address.fire || recycleCommit) {
    avaialbleSize := (avaialbleSize +& Mux(recycleCommit, contsPerBurst.U, 0.U)) -
      Mux(io.read_address.fire, contsPerBurst.U, 0.U)
  }

  // Counted at the point addresses leave the free list, so it leads what the PEs
  // have actually consumed by whatever is still parked in the read-ahead buffer
  // and the distribution ring.
  when(io.read_address.fire) {
    handedOutTotal := handedOutTotal + contsPerBurst.U
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

  // Track the low-water mark once addresses are actually circulating. Occupancy
  // is zero before the host programs the region, hence the guard; zero therefore
  // reads back as "never observed".
  when(rPause === 0.U && avaialbleSize =/= 0.U &&
    (lowWaterSize === 0.U || avaialbleSize < lowWaterSize)) {
    lowWaterSize := avaialbleSize
  }

  io.recycle.foreach { p =>
    p.baseAddress := rAddr(sysAddressWidth - 1, 0)
    p.capacityBeats := (capacitySize >> perBeatShift)(beatCountWidth - 1, 0)
    // Gated on "region programmed", NOT on rPause: a terminal pause ends the run
    // anyway, and letting the writer keep draining afterwards stops the
    // collectors from spuriously overflowing and muddying their leak counts.
    p.running := capacitySize =/= 0.U
    // Slots the writer may target: everything not currently in the FIFO, minus
    // the beats an issued-but-unreturned read burst is still fetching. Beats the
    // writer has absorbed but not yet committed are its own business.
    //
    // The clamp is load-bearing. A full pool has consumed == 0 while reads are
    // already in flight, and an unclamped subtraction underflows to a huge
    // "writable" count that would let the writer overrun the region.
    val consumedBeats = (capacitySize - avaialbleSize) >> perBeatShift
    p.writableBeats := Mux(
      consumedBeats > inflightReadBeats,
      (consumedBeats - inflightReadBeats)(beatCountWidth - 1, 0),
      0.U
    )
  }

  assert(
    rPause =/= 0.U || capacitySize === 0.U || avaialbleSize <= capacitySize,
    "AllocatorServer: occupancy exceeded capacity (double-recycled address?)"
  )

  // Reply to axi management operations.
  when(regBlock.rdReq) {
    regBlock.rdOk()
  }
  when(regBlock.wrReq) {
    regBlock.wrOk()
  }
}
