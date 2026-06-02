package Atomics

import chisel3._
import chisel3.util._
import chext.amba.axi4

object AtomicMemoryUnit {
  def defaultAxiCfg(addrW: Int): axi4.Config =
    axi4.Config(
      wId = 1,
      wAddr = addrW,
      wData = 64,
      read = true,
      write = true
    )
}

// One atomic memory unit, fed by a single LockServer pipeline lane. It performs a
// read-modify-write to gmem for each forwarded request and returns the previous
// memory value. Many requests are in flight at once (AXI multiple-outstanding),
// tracked in a per-slot table where each entry runs its own small FSM.
//
//   tag      = byte address to read/modify/write
//   data     = operand (value to store, or compare-and-store operand)
//   readValue= the previous memory contents, returned to the PE
//
// The AXI id IS the table slot index, so read/write responses address the table
// directly. The mux appends this AMU's port (lane) index on top, making ids
// globally unique across AMUs.
class AtomicMemoryUnit(
    val n: Int,
    val p: Int,
    val laneIndex: Int,
    val addrW: Int,
    val axiCfg: axi4.Config
) extends Module {
  def this(n: Int, p: Int, laneIndex: Int) =
    this(n, p, laneIndex, 64, AtomicMemoryUnit.defaultAxiCfg(64))

  def this(n: Int, p: Int, laneIndex: Int, addrW: Int) =
    this(n, p, laneIndex, addrW, AtomicMemoryUnit.defaultAxiCfg(addrW))

  require(
    n % (2 * p) == 0,
    "n must be divisible by 2*p (matches arbiter bucketing)"
  )
  val tableSize = n / p
  val idxW = log2Ceil(tableSize)
  require(
    axiCfg.wId == idxW,
    s"axiCfg.wId (${axiCfg.wId}) must equal log2Ceil(n/p) ($idxW)"
  )
  require(axiCfg.wData == 64, "AMU expects single 64-bit data beats")
  require(
    addrW > 0 && addrW == axiCfg.wAddr,
    "addrW must match the AXI address width"
  )

  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new RequestType(n, addrW)))
    val resp = Valid(new RequestType(n, addrW))
    val gmem = axi4.full.Master(axiCfg)
  })

  // ---- Compile-time PE -> local slot LUT ----
  // Lane `laneIndex` is fed only from arbiter buckets `laneIndex` and
  // `p+laneIndex` (InputArbiter), i.e. PEs [base, base+bs) and [base+n/2,
  // base+n/2+bs) with base = laneIndex*bs. That fixed set maps to [0, n/p). Only
  // those PEs ever reach this AMU; other keys are don't-care. Constant-folds to a
  // bit-select when n is a power of two.
  private val bs = n / (2 * p)
  private def localOf(pe: Int): Int = {
    val r1 = laneIndex * bs
    val r2 = laneIndex * bs + n / 2
    if (pe >= r1 && pe < r1 + bs) pe - r1
    else if (pe >= r2 && pe < r2 + bs) bs + (pe - r2)
    else 0
  }
  val peToLocal = VecInit(Seq.tabulate(n)(pe => localOf(pe).U(idxW.W)))

  // ---- Table ----
  object State extends ChiselEnum {
    val Invalid, WaitRead, WantWrite, WaitBResp, RespPending = Value
  }
  class Entry extends Bundle {
    val state = State()
    val req = new RequestType(n, addrW)
    val readValue = UInt(64.W)
  }
  val table = RegInit(VecInit(Seq.fill(tableSize)(0.U.asTypeOf(new Entry))))

  private val fullValueMask = "hffffffffffffffff".U(64.W)
  private val fullStrobe = ((BigInt(1) << (axiCfg.wData / 8)) - 1).U((axiCfg.wData / 8).W)

  private def axiSize(mode: AtomicMode.Type): UInt =
    MuxLookup(mode.asUInt, 3.U)(
      Seq(
        AtomicMode.Byte.asUInt -> 0.U,
        AtomicMode.Word.asUInt -> 2.U,
        AtomicMode.DoubleWord.asUInt -> 3.U
      )
    )

  private def valueMask(mode: AtomicMode.Type): UInt =
    MuxLookup(mode.asUInt, fullValueMask)(
      Seq(
        AtomicMode.Byte.asUInt -> "hff".U(64.W),
        AtomicMode.Word.asUInt -> "hffffffff".U(64.W),
        AtomicMode.DoubleWord.asUInt -> fullValueMask
      )
    )

  private def strobeMask(mode: AtomicMode.Type): UInt =
    MuxLookup(mode.asUInt, fullStrobe)(
      Seq(
        AtomicMode.Byte.asUInt -> 1.U(fullStrobe.getWidth.W),
        AtomicMode.Word.asUInt -> "hf".U(fullStrobe.getWidth.W),
        AtomicMode.DoubleWord.asUInt -> fullStrobe
      )
    )

  private def byteOffset(mode: AtomicMode.Type, addr: UInt): UInt =
    Mux(mode === AtomicMode.DoubleWord, 0.U(3.W), addr.pad(3)(2, 0))

  private def bitOffset(byteOffset: UInt): UInt = Cat(byteOffset, 0.U(3.W))

  private def selectedValue(data: UInt, mode: AtomicMode.Type, addr: UInt): UInt = {
    val shifted = data >> bitOffset(byteOffset(mode, addr))
    shifted & valueMask(mode)
  }

  private def signExtendSelected(value: UInt, mode: AtomicMode.Type): SInt = {
    val byte = Cat(Fill(56, value(7)), value(7, 0)).asSInt
    val word = Cat(Fill(32, value(31)), value(31, 0)).asSInt
    MuxLookup(mode.asUInt, value.asSInt)(
      Seq(
        AtomicMode.Byte.asUInt -> byte,
        AtomicMode.Word.asUInt -> word,
        AtomicMode.DoubleWord.asUInt -> value.asSInt
      )
    )
  }

  // ---- Defaults ----
  io.req.ready := false.B
  io.resp.valid := false.B
  io.resp.bits := 0.U.asTypeOf(new RequestType(n, addrW))

  io.gmem.ar.valid := false.B
  io.gmem.ar.bits := DontCare
  io.gmem.r.ready := true.B // a waiting entry always exists for a returning read
  io.gmem.aw.valid := false.B
  io.gmem.aw.bits := DontCare
  io.gmem.w.valid := false.B
  io.gmem.w.bits := DontCare
  io.gmem.b.ready := true.B // a waiting entry always exists for a returning write

  // ---- Pop FIFO + issue read (AR) ----
  // One pop per cycle; the popped slot must be free (the PE was masked end-to-end).
  val popSlot = peToLocal(io.req.bits.requestingPE)
  io.gmem.ar.valid := io.req.valid
  io.req.ready := io.gmem.ar.ready
  io.gmem.ar.bits.addr := io.req.bits.tag.pad(axiCfg.wAddr)
  io.gmem.ar.bits.id := popSlot
  io.gmem.ar.bits.size := axiSize(io.req.bits.atomicMode)
  io.gmem.ar.bits.len := 0.U // single beat
  io.gmem.ar.bits.burst := axi4.BurstType.INCR
  when(io.req.valid && io.gmem.ar.ready) {
    assert(
      table(popSlot).state === State.Invalid,
      "AMU table slot reused while still busy"
    )
    table(popSlot).state := State.WaitRead
    table(popSlot).req := io.req.bits
  }

  // ---- Read data (R): record value, decide whether to write ----
  when(io.gmem.r.valid) {
    val rslot = io.gmem.r.bits.id
    val current = io.gmem.r.bits.data
    val operand = table(rslot).req.data
    val op = table(rslot).req.operation
    val writeNeeded = WireDefault(false.B)
    val mode = table(rslot).req.atomicMode
    val currentSelected = selectedValue(current, mode, table(rslot).req.tag)
    val operandSelected = operand & valueMask(mode)

    switch(op) {
      is(Operation.LockSetUnlockAndReturnCurrent) { writeNeeded := true.B }
      is(Operation.LockSetIfGreaterUnlockAndReturnCurrent) {
        writeNeeded := operandSelected > currentSelected
      }
      is(Operation.LockSetIfSignedLessUnlockAndReturnCurrent) {
        writeNeeded := signExtendSelected(operandSelected, mode) < signExtendSelected(
          currentSelected,
          mode
        )
      }
      is(Operation.LockAddOneReturnCurrent) {
        writeNeeded := true.B
      }
    }
    table(rslot).readValue := current
    table(rslot).state := Mux(writeNeeded, State.WantWrite, State.RespPending)
  }

  // ---- Issue write (AW + W): one in-flight write at a time ----
  val wantWriteMask = VecInit(table.map(_.state === State.WantWrite)).asUInt
  val writeActive = RegInit(false.B)
  val writeSlot = RegInit(0.U(idxW.W))
  val awDone = RegInit(false.B)
  val wDone = RegInit(false.B)

  when(!writeActive && wantWriteMask.orR) {
    writeActive := true.B
    writeSlot := PriorityEncoder(wantWriteMask)
    awDone := false.B
    wDone := false.B
  }

  when(writeActive) {
    val we = table(writeSlot)
    val mode = we.req.atomicMode
    val offsetBytes = byteOffset(mode, we.req.tag)
    val offsetBits = bitOffset(offsetBytes)
    val selectedMask = valueMask(mode)
    val currentSelected = selectedValue(we.readValue, mode, we.req.tag)
    val writeValue = WireDefault(we.req.data & selectedMask)
    switch(we.req.operation) {
      is(Operation.LockAddOneReturnCurrent) {
        writeValue := (currentSelected + 1.U)(63, 0)
      }
    }
    val shiftedData = (writeValue & selectedMask) << offsetBits
    val shiftedStrobe = (strobeMask(mode) << offsetBytes)(fullStrobe.getWidth - 1, 0)

    io.gmem.aw.valid := !awDone
    io.gmem.aw.bits.addr := we.req.tag.pad(axiCfg.wAddr)
    io.gmem.aw.bits.id := writeSlot
    io.gmem.aw.bits.size := axiSize(mode)
    io.gmem.aw.bits.len := 0.U
    io.gmem.aw.bits.burst := axi4.BurstType.INCR
    io.gmem.w.valid := !wDone
    io.gmem.w.bits.data := shiftedData(63, 0)
    io.gmem.w.bits.strb := shiftedStrobe
    io.gmem.w.bits.last := true.B

    val awFire = io.gmem.aw.valid && io.gmem.aw.ready
    val wFire = io.gmem.w.valid && io.gmem.w.ready
    when(awFire) { awDone := true.B }
    when(wFire) { wDone := true.B }
    when((awDone || awFire) && (wDone || wFire)) {
      table(writeSlot).state := State.WaitBResp
      writeActive := false.B
      awDone := false.B
      wDone := false.B
    }
  }

  // ---- Write response (B): the write committed ----
  when(io.gmem.b.valid) {
    table(io.gmem.b.bits.id).state := State.RespPending
  }

  // ---- Respond to LockServer: one completed entry per cycle ----
  val respMask = VecInit(table.map(_.state === State.RespPending)).asUInt
  when(respMask.orR) {
    val respSlot = PriorityEncoder(respMask)
    io.resp.valid := true.B
    io.resp.bits := table(respSlot).req
    io.resp.bits.data := table(respSlot).readValue
    table(respSlot).state := State.Invalid
  }
}
