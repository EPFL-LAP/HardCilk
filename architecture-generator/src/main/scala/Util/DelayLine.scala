package Util

import chisel3._
import chisel3.util._

/** Blackbox wrapper for `UramDelayMem.v`: one unconditional write and one read
  * per cycle, read data available two cycles after the address (the URAM's
  * mandatory output register plus its optional pipeline register, both absorbed
  * into the block).
  *
  * A blackbox rather than a `SyncReadMem` because the whole point is the
  * `ram_style = "ultra"` attribute, and Chisel 6 on this project has no route to
  * attach an SV attribute to an inferred memory (the old firrtl annotation
  * library is not on the compile classpath). Without the attribute Vivado infers
  * block RAM for a 128-deep array and the exercise achieves nothing.
  */
class UramDelayMemIO(addrBits: Int, dataBits: Int) extends Bundle {
  val clk = Input(Clock())
  val waddr = Input(UInt(addrBits.W))
  val din = Input(UInt(dataBits.W))
  val raddr = Input(UInt(addrBits.W))
  val dout = Output(UInt(dataBits.W))
}

class UramDelayMem(addrBits: Int, dataBits: Int)
    extends BlackBox(
      Map(
        "DATA" -> dataBits,
        "ADDR" -> addrBits
      )
    )
    with HasBlackBoxResource {
  val io = IO(new UramDelayMemIO(addrBits, dataBits))
  addResource("/UramDelayMem.v")
}

object DelayLine {

  /** Depth at or above which the payload moves to URAM.
    *
    * DISABLED by default, because it was measured and it loses. On the
    * fullTriangleCountDecoupled 8-way build a threshold of 64 sent 12 lanes of
    * 1058 bits to URAM and did everything it promised on paper -- SRL cells
    * 52,576 -> 21,404, SLR1 CLB occupancy 99.76% -> 97.32%, 180 URAMs of 960 --
    * and the design then FAILED TO ROUTE, with 186,851 unroutable signals where
    * the SRL build had merely missed timing by 0.540 ns.
    *
    * The cause is not capacity, it is SLR crossings. URAM lives in a few fixed
    * narrow columns, so the placer cannot put a lane's memory next to the lane;
    * it split them 0/123/57 across the three SLRs, which left roughly four
    * lanes with their memory on the far side of an SLR boundary. Each lane
    * pushes 1058 bits in and 1058 out, so that is ~8,500 nets forced across,
    * funnelled into the SLL columns beside the URAM:
    *
    * {{{ SLR[1-2] per-column SLL demand ... 2968 (206%) 2936 (204%) 2895 (201%) }}}
    *
    * against 1440 SLLs per column. No router setting fixes 200%.
    *
    * The lesson generalises past this module: congestion on this design is
    * driven by WIRE DEMAND, not by cell occupancy. Trading ~31k distributed,
    * placeable SLICEM cells for ~25k nets converging on fixed columns is a bad
    * trade even though it frees area, and the same objection applies to moving
    * any other wide structure into hard blocks.
    *
    * Re-enabling this needs each lane's memory pinned to its lane's SLR, which
    * is a placement constraint. If that ever becomes acceptable, the economics
    * of the threshold itself were sound: a URAM costs `ceil(width/72)` blocks
    * regardless of depth against `width * ceil((delay-1)/32)` SLICEM LUTs for
    * the shift register, so the return is `72 * ceil((delay-1)/32)` LUTs per
    * URAM -- 72 at delay <= 32 (never worth it, one SRL32E per bit is the
    * densest storage on the die), 144 in 33..64, 216 beyond, which is where 64
    * came from.
    */
  val defaultUramThreshold: Int = Int.MaxValue

  /** The threshold the URAM variant was validated at, kept so the tests can
    * still exercise that path explicitly. Not the default -- see above.
    */
  val validatedUramThreshold: Int = 64

  /** Whether a line of this depth is implemented in URAM under `threshold`. */
  def usesUram(delay: Int, threshold: Int = defaultUramThreshold): Boolean =
    delay >= threshold
}

/** Fixed-latency delay line: whatever is presented on `in` reappears on `out`
  * exactly `delay` cycles later, and `inValid` reappears on `outValid` with the
  * same alignment. There is no backpressure and no enable -- the line always
  * advances, which is the entire contract of the argument-notifier front porch.
  *
  * Two implementations, chosen by depth (see [[DelayLine.defaultUramThreshold]]):
  *
  *  - Shallow: an unconditional shift register, which is what the porch already
  *    was. Vivado maps it to SRLC32E/SRL16E chains, one LUT per 32 bits per bit
  *    lane, and there is nothing cheaper.
  *
  *  - Deep: a circular buffer in URAM. Above ~64 cycles the shift register stops
  *    being a good deal: the porches in fullTriangleCountDecoupled are 12 lanes
  *    of 1058 bits at 72-75 cycles, which is ~38k SLICEM LUTs -- 74% of every
  *    LUT-as-shift-register cell in the design -- competing for CLBM sites that
  *    are 99.94% occupied in SLR1. The same storage is 180 URAMs of 960, in a
  *    resource that is otherwise completely idle.
  *
  * The two are behaviourally identical, including during the first `delay`
  * cycles: `outValid` is a reset flip-flop chain in both cases, so the garbage
  * the URAM reads out before the line has filled is never observable. The only
  * difference is in don't-care data -- the shift register happens to hold the
  * previous tenant of a slot whose valid bit is low, the URAM holds whatever was
  * on the input bus -- and neither is readable through the interface.
  */
class DelayLine[T <: Data](
    gen: T,
    val delay: Int,
    uramThreshold: Int = DelayLine.defaultUramThreshold
) extends Module {
  require(delay >= 1, s"DelayLine needs a delay of at least 1, got $delay")

  val useUram = DelayLine.usesUram(delay, uramThreshold)

  // The URAM variant reads at an address trailing the write by delay-2 (two
  // cycles of read latency to absorb). At delay == 2 that is the write address
  // itself, and URAM leaves a same-address two-port access undefined. Neither
  // that nor delay 1 is a case worth supporting: both are a single SRL16E per
  // bit lane, which no URAM can beat.
  require(
    !useUram || delay >= 3,
    s"DelayLine's URAM variant needs delay >= 3, got $delay " +
      s"(uramThreshold = $uramThreshold)"
  )

  val io = IO(new Bundle {
    val inValid = Input(Bool())
    val in = Input(gen)
    val outValid = Output(Bool())
    val out = Output(gen)
  })

  // The valid chain stays in flip-flops in both variants. It is short (one bit
  // per stage), it needs a reset, and it is what makes every unqualified payload
  // slot unobservable -- which is what lets the payload path skip its enable and
  // its reset entirely, and so lets it become an SRL or a URAM at all.
  private val valids = RegInit(VecInit(Seq.fill(delay)(false.B)))
  for (stage <- (1 until delay).reverse) {
    valids(stage) := valids(stage - 1)
  }
  valids(0) := io.inValid
  io.outValid := valids(delay - 1)

  if (!useUram) {
    // Unconditional shift, no enable and no reset: uniform control across every
    // stage with only the tail read, which is exactly the shape Vivado turns
    // into SRLC32E chains.
    val data = Reg(Vec(delay, gen))
    for (stage <- (1 until delay).reverse) {
      data(stage) := data(stage - 1)
    }
    data(0) := io.in
    io.out := data(delay - 1)
  } else {
    // Circular buffer. The write pointer advances every cycle; the read pointer
    // trails it by delay-2, so an address presented at cycle t-2 returns, after
    // the memory's two register stages, the value written at cycle t-delay --
    // arriving at cycle t. The pipeline register therefore costs no latency: it
    // is paid for by reading two cycles earlier, not by delivering later.
    //
    // Depth is the next power of two strictly above `delay`, which buys two
    // things at once. Wrapping is free (the counters just overflow), and the
    // read and write addresses can never coincide: they differ by delay-2, and
    // 1 <= delay-2 <= depth-2. Nothing here depends on read-under-write
    // semantics, which is the trap a URAM would otherwise spring -- it has no
    // read-first behaviour to fall back on.
    val depth = 1 << log2Ceil(delay + 1)
    val addrBits = log2Ceil(depth)

    val mem = Module(new UramDelayMem(addrBits, gen.getWidth))
    mem.io.clk := clock

    val wrPtr = RegInit(0.U(addrBits.W))
    // (0 - (delay - 2)) mod depth, i.e. already trailing on the first cycle.
    val rdPtr = RegInit((depth - (delay - 2)).U(addrBits.W))
    wrPtr := wrPtr + 1.U
    rdPtr := rdPtr + 1.U

    mem.io.waddr := wrPtr
    mem.io.din := io.in.asUInt
    mem.io.raddr := rdPtr
    io.out := mem.io.dout.asTypeOf(gen)
  }
}
