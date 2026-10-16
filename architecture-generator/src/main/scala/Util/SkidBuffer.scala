package Util

import chisel3._
import chisel3.util.{Decoupled, DecoupledIO, ReadyValidIO}

/**
  * A two-entry elastic buffer whose every output pin is a register Q.
  *
  * WHY THIS EXISTS AND WHY `Queue` IS NOT IT
  * -----------------------------------------
  * `chext.elastic.SourceBuffer` / `SinkBuffer` build a `chisel3.util.Queue`, and
  * HardCilk has been treating a `Queue(2)` as a skid buffer -- a launch flop that
  * makes a link cross an SLR flop-to-flop. It is not one. This is the whole body
  * of a `Queue(8)` as firtool emits it:
  *
  * {{{
  * wire ptr_match = enq_ptr_value == deq_ptr_value;   // pointer compare -> LUT
  * wire empty     = ptr_match & ~maybe_full;
  * wire full      = ptr_match &  maybe_full;
  * assign io_enq_ready     = ~full;                   // LUT, not a flop Q
  * assign io_deq_valid     = ~empty;                  // LUT, not a flop Q
  * assign io_deq_bits_data = _ram_ext_R0_data[31:0];  // async LUTRAM read
  * }}}
  *
  * Not one of the three port signals comes off a flop. That matters more than it
  * looks: a Laguna SLL register (TX_REG/RX_REG) can only be used when the
  * crossing net is flop-to-flop with nothing in between, so every `Decoupled`
  * port in the design was structurally ineligible for one, and routed builds put
  * a Laguna register on almost none of their SLR crossings.
  *
  * It also explains a result that otherwise looks backwards: deepening a queue
  * makes its boundary timing *worse*, because `ptr_match` is a comparison over
  * pointers that widen with depth.
  *
  * So this module keeps the storage question and the boundary-timing question
  * apart. It is not a replacement for the `Queue`s -- put it around one
  * (`Queue(n)` -> `SkidBuffer`) and capacity stays wherever it was, while the
  * three signals that actually leave the module become plain register outputs.
  *
  * COST
  * ----
  * One cycle of latency per direction, one transfer per cycle sustained (the
  * steady state is the one-entry state, which accepts and delivers in the same
  * cycle). Two copies of the payload in flops rather than in LUTRAM.
  *
  * `enq.ready` is held in a register of its own rather than derived as `!skidV`,
  * because even the single inverter would be a LUT1 on the launch side and would
  * cost the Laguna flop this module exists to enable.
  *
  * The payload registers are deliberately `Reg`, not `RegInit`: they are
  * qualified by their valid bits, so initialising them would only add a reset
  * net across payloads that are ~700 bits wide here.
  */
class SkidBuffer[T <: Data](gen: T) extends Module {
  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(gen))
    val deq = Decoupled(gen)
  })

  // Head of the buffer: what `deq` presents.
  private val out  = Reg(gen)
  private val outV = RegInit(false.B)

  // The reserve entry, filled only when `deq` stalls while `enq` is offering.
  // `skidFree` is the registered form of `enq.ready`; `skidV` is its inverse and
  // is used only inside the module, where an inverter merges into another LUT.
  private val skid     = Reg(gen)
  private val skidFree = RegInit(true.B)
  private val skidV    = !skidFree

  // The three port signals, each straight off a flop.
  io.enq.ready := skidFree
  io.deq.valid := outV
  io.deq.bits := out

  private val enqFire = io.enq.valid && skidFree
  private val deqFire = outV && io.deq.ready

  when(deqFire) {
    when(skidV) {
      // Full -> one. `enqFire` is false here by construction (`skidFree` is low).
      out := skid
      skidFree := true.B
    }.elsewhen(enqFire) {
      // One -> one: the sustained-throughput case.
      out := io.enq.bits
    }.otherwise {
      // One -> empty.
      outV := false.B
    }
  }.elsewhen(enqFire) {
    when(outV) {
      // One -> full: the head is stalled, so the new beat goes to the reserve.
      skid := io.enq.bits
      skidFree := false.B
    }.otherwise {
      // Empty -> one.
      out := io.enq.bits
      outV := true.B
    }
  }
}

object SkidBuffer {

  /**
    * Buffer `source` through `stages` skid buffers and return the far side.
    *
    * Shaped after `chext.elastic.SourceBuffer.decoupled` so it drops into the
    * same call sites: takes any `ReadyValidIO` producer, hands back a
    * `DecoupledIO` to connect onward. `stages = 0` is the identity, which makes
    * "how many stages on this link" a knob rather than an edit.
    *
    * `name` is worth passing. These are elaborated in the HardCilk top, so
    * without it the instances come out as `stage`, `stage_1`, ... and a timing
    * report can tell you a path starts in one but not which link it belongs to.
    * Reading exactly those names is how the SLL problem got diagnosed.
    */
  def apply[T <: Data](
      source: ReadyValidIO[T],
      stages: Int = 1,
      name: String = ""
  ): DecoupledIO[T] = {
    require(stages >= 0, s"SkidBuffer needs a non-negative stage count, got $stages")

    val head = Wire(Decoupled(chiselTypeOf(source.bits)))
    chext.elastic.connect(source, head)

    (0 until stages).foldLeft(head) { case (src, i) =>
      val stage = Module(new SkidBuffer(chiselTypeOf(src.bits)))
      if (name.nonEmpty)
        stage.suggestName(if (stages == 1) s"skid_$name" else s"skid_${name}_$i")
      chext.elastic.connect(src, stage.io.enq)
      stage.io.deq
    }
  }
}
