package AXIHelpers

import chisel3._
import chisel3.util._

import chext.amba.axi4

// (Bradley) This file written by AI

// Plain-Chisel replacement for the scheduler's RVtoAXIBridge + AxiWriteBuffer
// stack. Drives the HBM AXI master DIRECTLY from the SchedulerServer's decoupled
// read/write ports, with NO chext.elastic (no Arrival, no depth-2 SinkBuffer, no
// :=> connects) — so nothing can buffer/reorder the aw/w/b/ar/r channels under
// the memReader scheduler's saturated, back-to-back wrap traffic.
//
// Ordering contract (identical to what the FSM relies on):
//   * at most ONE write burst outstanding (the FSM already serialises pushes via
//     write_idle, but we also gate aw on it);
//   * write_idle asserts only when there is NO write awaiting its B response;
//   * a read address is never issued while any write lacks its B (read-after-
//     write to a reused ring slot must observe the committed write).
//
// All channels are in-order combinational passthroughs gated by a single
// outstanding-write counter. The bundle TYPE is chext axi4 (it is just the port
// struct); none of chext's elastic LOGIC is used.
class SchedulerAXIAdapter(taskWidth: Int, addrWidth: Int) extends Module {

  val cfg = axi4.Config(wAddr = addrWidth, wData = taskWidth, lite = false)

  val io = IO(new Bundle {
    val read_address = Flipped(DecoupledIO(UInt(addrWidth.W)))
    val read_data = DecoupledIO(UInt(taskWidth.W))
    val read_burst_len = Input(UInt(4.W))
    val write_address = Flipped(DecoupledIO(UInt(addrWidth.W)))
    val write_data = Flipped(DecoupledIO(UInt(taskWidth.W)))
    val write_burst_len = Input(UInt(4.W))
    val write_last = Input(UInt(1.W))
    val write_idle = Output(Bool())
  })

  val axi = IO(axi4.full.Master(cfg))

  private def connectZeros[T <: Data](bits: T): Unit =
    bits := 0.U(bits.getWidth.W).asTypeOf(bits)

  connectZeros(axi.aw.bits)
  connectZeros(axi.ar.bits)
  connectZeros(axi.w.bits)

  private val sizeEnc = log2Ceil(taskWidth / 8).U
  private val burstIncr = 1.U

  // Outstanding write bursts = AW accepted − B returned. The FSM keeps this <= 1.
  private val outstanding = RegInit(0.U(8.W))
  private val awFire = axi.aw.valid && axi.aw.ready
  private val bFire = axi.b.valid && axi.b.ready

  io.write_idle := outstanding === 0.U

  // ---- Write address ----
  axi.aw.valid := io.write_address.valid
  io.write_address.ready := axi.aw.ready
  axi.aw.bits.addr := io.write_address.bits
  axi.aw.bits.len := io.write_burst_len
  axi.aw.bits.size := sizeEnc
  axi.aw.bits.burst := burstIncr
  axi.aw.bits.id := 0.U

  // ---- Write data ----
  axi.w.valid := io.write_data.valid
  io.write_data.ready := axi.w.ready
  axi.w.bits.data := io.write_data.bits
  axi.w.bits.strb := (-1).S(cfg.wStrobe.W).asUInt
  axi.w.bits.last := io.write_last

  // ---- Write response ----
  axi.b.ready := true.B

  // ---- Read address: held until all writes have their B (read-after-write),
  //      and never the same cycle an AW fires. ----
  private val readOk = (outstanding === 0.U) && !awFire
  axi.ar.valid := io.read_address.valid && readOk
  io.read_address.ready := axi.ar.ready && readOk
  axi.ar.bits.addr := io.read_address.bits
  axi.ar.bits.len := io.read_burst_len
  axi.ar.bits.size := sizeEnc
  axi.ar.bits.burst := burstIncr
  axi.ar.bits.id := 0.U

  // ---- Read data ----
  io.read_data.valid := axi.r.valid
  axi.r.ready := io.read_data.ready
  io.read_data.bits := axi.r.bits.data

  when(awFire && !bFire) { outstanding := outstanding + 1.U }
    .elsewhen(bFire && !awFire) { outstanding := outstanding - 1.U }
}
