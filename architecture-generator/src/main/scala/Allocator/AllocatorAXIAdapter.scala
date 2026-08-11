package Allocator

import chisel3._
import chisel3.util._

import chext.amba.axi4

/** Fixed-burst AXI engine for one AllocatorServer's HBM port.
  *
  * Unlike SchedulerAXIAdapter, reads are NOT held behind outstanding writes.
  * The scheduler needs that ordering because its ring slots are read back at
  * the same addresses it just wrote; the allocator free list does not: the
  * circular FIFO keeps a head/tail gap, and a recycled beat only becomes
  * readable once its B response has landed (AllocatorServer advances occupancy
  * on `write_done`, not on WLAST). So AR and AW run concurrently on their own
  * channels, which is what lets the recycle path sustain the same beat rate as
  * the allocation path on a single port.
  *
  * Every transfer is a full `burstLength + 1` beat burst. The AllocatorServer
  * guarantees the region is burst-aligned and a whole number of bursts long, so
  * no burst can cross a 4 KB boundary or straddle the FIFO wrap and there is no
  * runtime burst splitting to do.
  */
class AllocatorAXIAdapter(
    dataWidth: Int,
    addrWidth: Int,
    burstLength: Int,
    enableWrite: Boolean
) extends Module {

  require(burstLength >= 0 && burstLength <= 15)

  val cfg = axi4.Config(wAddr = addrWidth, wData = dataWidth, lite = false)

  val io = IO(new Bundle {
    val read_address = Flipped(Decoupled(UInt(addrWidth.W)))
    val read_data = Decoupled(UInt(dataWidth.W))

    val write_address =
      if (enableWrite) Some(Flipped(Decoupled(UInt(addrWidth.W)))) else None
    val write_data =
      if (enableWrite) Some(Flipped(Decoupled(UInt(dataWidth.W)))) else None
    val write_last = if (enableWrite) Some(Input(Bool())) else None
    // One pulse per completed write burst (B response).
    val write_done = if (enableWrite) Some(Output(Bool())) else None
  })

  val axi = IO(axi4.full.Master(cfg))

  private def connectZeros[T <: Data](bits: T): Unit =
    bits := 0.U(bits.getWidth.W).asTypeOf(bits)

  connectZeros(axi.aw.bits)
  connectZeros(axi.ar.bits)
  connectZeros(axi.w.bits)

  private val sizeEnc = log2Ceil(dataWidth / 8).U
  private val burstIncr = 1.U

  // ---- Read ----
  axi.ar.valid := io.read_address.valid
  io.read_address.ready := axi.ar.ready
  axi.ar.bits.addr := io.read_address.bits
  axi.ar.bits.len := burstLength.U
  axi.ar.bits.size := sizeEnc
  axi.ar.bits.burst := burstIncr
  axi.ar.bits.id := 0.U
  axi.ar.bits.prot := 0.U

  io.read_data.valid := axi.r.valid
  axi.r.ready := io.read_data.ready
  io.read_data.bits := axi.r.bits.data

  // ---- Write ----
  if (enableWrite) {
    axi.aw.valid := io.write_address.get.valid
    io.write_address.get.ready := axi.aw.ready
    axi.aw.bits.addr := io.write_address.get.bits
    axi.aw.bits.len := burstLength.U
    axi.aw.bits.size := sizeEnc
    axi.aw.bits.burst := burstIncr
    axi.aw.bits.id := 0.U

    axi.w.valid := io.write_data.get.valid
    io.write_data.get.ready := axi.w.ready
    axi.w.bits.data := io.write_data.get.bits
    axi.w.bits.strb := (-1).S(cfg.wStrobe.W).asUInt
    axi.w.bits.last := io.write_last.get

    axi.b.ready := true.B
    io.write_done.get := axi.b.valid
  } else {
    axi.aw.valid := false.B
    axi.w.valid := false.B
    axi.b.ready := false.B
  }
}
