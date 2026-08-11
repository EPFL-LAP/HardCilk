package NewArgumentNotifier

import chisel3._
import chisel3.util._

/** A FIFO whose payload storage is a synchronous-read memory, so it infers
  * block RAM instead of the LUTRAM a wide `Queue`/`BankedQueue` would use.
  *
  * `chisel3.util.Queue(useSyncReadMem = true)` is the obvious choice and is NOT
  * usable on this target. It issues the RAM read for the next dequeue position
  * in the very cycle the enqueue writes that position -- on every empty ->
  * nonempty transition, and again on every simultaneous enqueue/dequeue while
  * the queue holds exactly one entry, which is the II=1 steady state here. Its
  * correctness therefore rests on cross-port write-first read-under-write. A
  * Xilinx block RAM cannot provide that: Vivado (2024.1, xcu55c) infers the
  * memory with WRITE_MODE=READ_FIRST and adds no bypass logic, so on real
  * hardware those dequeues would return the slot's PREVIOUS tenant. RTL
  * simulation hides it completely, because the emitted Verilog reads the memory
  * array asynchronously at a registered address and so is naturally write-first.
  *
  * Here the read is gated on the entry already being resident in the RAM. Reads
  * need `ramCount > 0` and writes need `ramCount < entries`, so whenever both
  * fire the two pointers are provably distinct and no read-under-write
  * behaviour is assumed at all. The cost is one extra cycle of latency on an
  * empty -> nonempty transition; the sustained rate is still one transfer per
  * cycle in each direction.
  *
  * Chisel's emitted synchronous-memory model drives read data to X while its
  * enable is low; it does not model the physical BRAM's output hold. A stalled
  * valid dequeue must nevertheless keep `bits` stable. Remember the address
  * that produced the output-stage entry and re-read it while that entry is
  * stalled. This expresses the hold without adding a payload-width register.
  *
  * Externally: same depth, same Decoupled contract, same `count` as the queue
  * it replaces.
  */
class BramQueue[T <: Data](gen: T, val entries: Int) extends Module {
  require(entries >= 2, "BramQueue needs room for an output stage plus storage")

  private val countWidth = log2Ceil(entries + 1)
  private val ptrWidth = log2Ceil(entries)

  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(gen))
    val deq = Decoupled(gen)
    val count = Output(UInt(countWidth.W))
  })

  private val ram = SyncReadMem(entries, gen)
  private val enqPtr = RegInit(0.U(ptrWidth.W))
  private val deqPtr = RegInit(0.U(ptrWidth.W))
  // Address backing the entry currently in the output stage. The generated RAM
  // model's disabled-read value is undefined, so a stalled head is kept defined
  // by re-reading this address until the downstream accepts it.
  private val heldReadPtr = RegInit(0.U(ptrWidth.W))
  // Entries written into the RAM and not yet read out of it. The entry sitting
  // in the output stage has already left the RAM and is counted separately.
  private val ramCount = RegInit(0.U(countWidth.W))
  private val outValid = RegInit(false.B)

  private def wrap(p: UInt): UInt = Mux(p === (entries - 1).U, 0.U, p + 1.U)

  // Total occupancy = RAM + output stage, capped at `entries`. That cap is what
  // guarantees ramCount < entries whenever a write fires, which together with
  // the ramCount > 0 read condition keeps the pointers apart.
  private val occupancy = Wire(UInt(countWidth.W))
  occupancy := ramCount + outValid.asUInt
  io.enq.ready := occupancy < entries.U
  io.count := occupancy

  private val doEnq = io.enq.fire
  private val outAccept = !outValid || io.deq.ready
  private val doRead = (ramCount =/= 0.U) && outAccept

  when(doEnq) {
    ram.write(enqPtr, io.enq.bits)
    enqPtr := wrap(enqPtr)
  }
  when(doRead) {
    heldReadPtr := deqPtr
    deqPtr := wrap(deqPtr)
  }
  ramCount := (ramCount +& doEnq.asUInt) - doRead.asUInt

  when(doRead) {
    outValid := true.B
  }.elsewhen(io.deq.ready) {
    outValid := false.B
  }

  io.deq.valid := outValid
  private val holdHead = outValid && !io.deq.ready
  val readEnable = doRead || holdHead
  private val readAddress = Mux(doRead, deqPtr, heldReadPtr)
  io.deq.bits := ram.read(readAddress, readEnable)

  // The invariant this module exists to hold. It is structural (0 < ramCount <
  // entries whenever both fire), but assert it anyway: a simulator's memory
  // model is write-first and would happily hide a regression that a block RAM
  // turns into silently stale dequeued data.
  val addressCollision =
    doEnq && readEnable && (enqPtr === readAddress)
  assert(
    !addressCollision,
    "BramQueue: read and write hit the same address; a block RAM would " +
      "return the slot's previous contents"
  )
}
