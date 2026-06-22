package Atomics

import chisel3._
import chisel3.util._
import chext.amba.axi4

object AtomicMemoryUnit {
  def defaultAxiCfg(addrW: Int, tableSize: Int): axi4.Config =
    axi4.Config(
      wId = math.max(1, log2Ceil(tableSize)),
      wAddr = addrW,
      wData = 64,
      read = true,
      write = true
    )
}

// One atomic memory unit, fed by a single LockServer pipeline lane. It performs a
// read-modify-write to gmem for each forwarded request and returns the previous
// memory value. Many requests are in flight at once (AXI multiple-outstanding),
// tracked in a `tableSize`-entry table where each entry runs its own small FSM.
// Slots are allocated from whatever is free (every outstanding request holds a
// unique tag, so there are no same-address ordering hazards between slots).
//
//   tag      = byte address to read/modify/write
//   data     = operand (value to store, or compare-and-store operand)
//   readValue= the previous memory contents, returned to the PE
//
// The AXI id IS the table slot index, so read/write responses address the table
// directly. The mux appends this AMU's port (lane) index on top, making ids
// globally unique across AMUs.
//
// io.resp is Decoupled: a completed entry holds its slot until LockServer
// accepts the return (it may briefly stall it behind a same-PE retry).
class AtomicMemoryUnit(
    val n: Int,
    val tableSize: Int,
    val addrW: Int,
    val axiCfg: axi4.Config
) extends Module {
  def this(n: Int, tableSize: Int) =
    this(n, tableSize, 64, AtomicMemoryUnit.defaultAxiCfg(64, tableSize))

  def this(n: Int, tableSize: Int, addrW: Int) =
    this(n, tableSize, addrW, AtomicMemoryUnit.defaultAxiCfg(addrW, tableSize))

  require(tableSize >= 1, "tableSize must be at least 1")
  val tableIdxW = log2Ceil(tableSize)
  val axiIdW = math.max(1, tableIdxW)
  require(
    axiCfg.wId == axiIdW,
    s"axiCfg.wId (${axiCfg.wId}) must equal max(1, log2Ceil(tableSize)) ($axiIdW)"
  )
  require(axiCfg.wData == 64, "AMU expects single 64-bit data beats")
  require(
    addrW > 0 && addrW == axiCfg.wAddr,
    "addrW must match the AXI address width"
  )

  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new RequestType(n, addrW)))
    val resp = Decoupled(new RequestType(n, addrW))
    val gmem = axi4.full.Master(axiCfg)
  })

  private def toAxiId(slot: UInt): UInt =
    if (tableSize == 1) 0.U(axiIdW.W) else slot
  private def toTableIndex(id: UInt): UInt =
    if (tableSize == 1) 0.U(0.W) else id(tableIdxW - 1, 0)

  // ---- Table ----
  object State extends ChiselEnum {
    val Invalid, WaitRead, WantWrite, WaitBResp, RespPending = Value
  }
  class Entry extends Bundle {
    val state = State()
    val req = new RequestType(n, addrW)
    val readValue = UInt(64.W)
    // Whether this op's read-modify-write actually stored. Always true for the
    // unconditional ops (SET, ADD_N); for the conditional SET_IF_* ops it is
    // true only when the predicate held. Surfaced in the response status byte.
    val wrote = Bool()
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

  // Map an IEEE-754 bit pattern to an unsigned key whose unsigned ordering
  // matches numeric float ordering. IEEE floats are sign-magnitude, so a raw
  // integer compare misorders negatives; the standard monotonic fix is:
  // non-negative -> flip the sign bit, negative -> flip all bits. Width follows
  // the atomic mode (Word = 32b single, DoubleWord = 64b double); Byte has no
  // float meaning and is passed through unchanged.
  private def floatOrderKey(value: UInt, mode: AtomicMode.Type): UInt = {
    def remap(w: Int): UInt = {
      val v = value(w - 1, 0)
      val signBit = (BigInt(1) << (w - 1)).U(w.W)
      Mux(v(w - 1), ~v, v | signBit)
    }
    MuxLookup(mode.asUInt, value)(
      Seq(
        AtomicMode.Word.asUInt -> remap(32),
        AtomicMode.DoubleWord.asUInt -> remap(64)
      )
    )
  }

  // ---- Defaults ----
  io.req.ready := false.B
  io.resp.valid := false.B
  io.resp.bits := 0.U.asTypeOf(new RequestType(n, addrW))

  io.gmem.ar.valid := false.B
  io.gmem.ar.bits := DontCare
  io.gmem.r.ready := false.B
  io.gmem.aw.valid := false.B
  io.gmem.aw.bits := DontCare
  io.gmem.w.valid := false.B
  io.gmem.w.bits := DontCare
  io.gmem.b.ready := true.B // a waiting entry always exists for a returning write

  // ---- Pop FIFO + issue read (AR) ----
  // One pop per cycle into the first free table slot. The AR channel is issued
  // from a local holding register so AXI mux arbitration/ready cannot feed
  // combinationally back into table allocation or the upstream FIFO.
  val freeMask = VecInit(table.map(_.state === State.Invalid)).asUInt
  val canAlloc = freeMask.orR
  val allocSlot = PriorityEncoder(freeMask)
  val arReqValid = RegInit(false.B)
  val arReq = Reg(new RequestType(n, addrW))
  val arSlot = Reg(UInt(tableIdxW.W))

  io.req.ready := !arReqValid && canAlloc
  when(io.req.fire) {
    arReqValid := true.B
    arReq := io.req.bits
    arSlot := allocSlot
    for (slot <- 0 until tableSize) {
      when(allocSlot === slot.U) {
        table(slot).state := State.WaitRead
        table(slot).req := io.req.bits
      }
    }
  }.elsewhen(io.gmem.ar.fire) {
    arReqValid := false.B
  }

  io.gmem.ar.valid := arReqValid
  io.gmem.ar.bits.addr := arReq.tag.pad(axiCfg.wAddr)
  io.gmem.ar.bits.id := toAxiId(arSlot)
  io.gmem.ar.bits.size := axiSize(arReq.atomicMode)
  io.gmem.ar.bits.len := 0.U // single beat
  io.gmem.ar.bits.burst := axi4.BurstType.INCR

  // ---- Read data (R): record value, decide whether to write ----
  // Buffer the AXI R channel before the read/modify decision. Without this
  // register cut, routed timing has to carry HBM mux read-data bits directly
  // into every table-entry state update and compare path.
  val rBufValid = RegInit(false.B)
  val rBufBits = Reg(chiselTypeOf(io.gmem.r.bits))
  val processR = rBufValid
  io.gmem.r.ready := true.B
  when(io.gmem.r.fire) {
    rBufValid := true.B
    rBufBits := io.gmem.r.bits
  }.elsewhen(processR) {
    rBufValid := false.B
  }

  // The read/modify decision is pipelined into two stages. The path
  // "gather the addressed entry out of the (spread) tableSize-deep table ->
  // sub-word shift -> ordered compare -> scatter to the entry's state" is
  // route-bound (the table read dominates), so it is split with a register:
  //   stage 1: select table(rslot).req + the read data, and register them;
  //   stage 2: compute writeNeeded from the registered entry and apply it.
  // This costs one extra cycle before the entry leaves WaitRead, but throughput
  // is unchanged -- one read still retires per cycle, and a slot never has two
  // reads outstanding (one AR/R per slot), so the two stages never touch the
  // same slot in the same cycle. Functionally identical otherwise.
  val rd1Valid = RegInit(false.B)
  val rd1Slot = Reg(UInt(tableIdxW.W))
  val rd1Req = Reg(new RequestType(n, addrW))
  val rd1Current = Reg(UInt(64.W))

  rd1Valid := false.B
  when(processR) {
    val rslot = toTableIndex(rBufBits.id)
    rd1Valid := true.B
    rd1Slot := rslot
    rd1Req := table(rslot).req
    rd1Current := rBufBits.data
  }

  when(rd1Valid) {
    val current = rd1Current
    val operand = rd1Req.data
    val op = rd1Req.operation
    val mode = rd1Req.atomicMode
    val writeNeeded = WireDefault(false.B)
    val currentSelected = selectedValue(current, mode, rd1Req.tag)
    val operandSelected = operand & valueMask(mode)
    // Map each side to an order-preserving UNSIGNED key, then do a SINGLE
    // unsigned compare per direction. The earlier form computed an int compare
    // AND a float compare and muxed the boolean -- two 64-bit carry chains per
    // conditional op (four total), which both spread the AMU (congestion) and
    // deepened the read-decision path. Keying first folds int/float into one
    // comparator each:
    //   greater (int unsigned / float): key = floatCmp ? floatOrderKey : value
    //   less    (int signed   / float): key = floatCmp ? floatOrderKey
    //                                          : signExtend(value) ^ MSB
    // using the identities  a <s b  <=>  (a ^ msb) <u (b ^ msb)  and that
    // floatOrderKey turns IEEE float order into unsigned order. Bit-identical to
    // the per-op int/float compares.
    val floatCmp = rd1Req.floatCompare
    val signFlip = (BigInt(1) << 63).U(64.W)
    def greaterKey(v: UInt): UInt = Mux(floatCmp, floatOrderKey(v, mode), v)
    def lessKey(v: UInt): UInt =
      Mux(floatCmp, floatOrderKey(v, mode), signExtendSelected(v, mode).asUInt ^ signFlip)

    switch(op) {
      is(Operation.LockSetUnlockAndReturnCurrent) {
        writeNeeded := true.B
      }
      is(Operation.LockSetIfGreaterUnlockAndReturnCurrent) {
        writeNeeded := greaterKey(operandSelected) > greaterKey(currentSelected)
      }
      is(Operation.LockSetIfSignedLessUnlockAndReturnCurrent) {
        writeNeeded := lessKey(operandSelected) < lessKey(currentSelected)
      }
      is(Operation.LockAddNReturnCurrent) {
        writeNeeded := true.B
      }
    }

    for (slot <- 0 until tableSize) {
      when(rd1Slot === slot.U) {
        table(slot).readValue := current
        table(slot).wrote := writeNeeded
        table(slot).state := Mux(writeNeeded, State.WantWrite, State.RespPending)
      }
    }
  }

  // ---- Issue write (AW + W): one in-flight write at a time ----
  val wantWriteMask = VecInit(table.map(_.state === State.WantWrite)).asUInt
  val writeActive = RegInit(false.B)
  val writeSlot = RegInit(0.U(tableIdxW.W))
  val writeReq = Reg(new RequestType(n, addrW))
  val writeReadValue = Reg(UInt(64.W))
  val awDone = RegInit(false.B)
  val wDone = RegInit(false.B)

  when(!writeActive && wantWriteMask.orR) {
    val nextWriteSlot = PriorityEncoder(wantWriteMask)
    writeActive := true.B
    writeSlot := nextWriteSlot
    writeReq := table(nextWriteSlot).req
    writeReadValue := table(nextWriteSlot).readValue
    awDone := false.B
    wDone := false.B
  }

  when(writeActive) {
    val mode = writeReq.atomicMode
    val offsetBytes = byteOffset(mode, writeReq.tag)
    val offsetBits = bitOffset(offsetBytes)
    val selectedMask = valueMask(mode)
    // Position the masked operand into its byte lane once, then add it straight
    // into the raw read value for AddN. This removes the redundant
    // down-shift (selectedValue) + re-up-shift that previously sat in series
    // with the barrel shift and the long route to the shared gmem mux. For the
    // strobed lanes, readValue + (operand << off) equals (currentSelected +
    // operand) positioned in place: no carry enters the sub-word from below
    // (the operand is zero there), carry within propagates correctly, and any
    // carry beyond the sub-word lands in unstrobed bytes -- dropped exactly as
    // the old `& selectedMask` did. Non-AddN ops write the positioned operand,
    // identical to before.
    val positionedOperand = ((writeReq.data & selectedMask) << offsetBits)(63, 0)
    val shiftedData = WireDefault(positionedOperand)
    switch(writeReq.operation) {
      is(Operation.LockAddNReturnCurrent) {
        // N=1 reproduces the old add-one behavior.
        shiftedData := (writeReadValue + positionedOperand)(63, 0)
      }
    }
    val shiftedStrobe = (strobeMask(mode) << offsetBytes)(fullStrobe.getWidth - 1, 0)

    io.gmem.aw.valid := !awDone
    io.gmem.aw.bits.addr := writeReq.tag.pad(axiCfg.wAddr)
    io.gmem.aw.bits.id := toAxiId(writeSlot)
    io.gmem.aw.bits.size := axiSize(mode)
    io.gmem.aw.bits.len := 0.U
    io.gmem.aw.bits.burst := axi4.BurstType.INCR
    io.gmem.w.valid := !wDone
    io.gmem.w.bits.data := shiftedData
    io.gmem.w.bits.strb := shiftedStrobe
    io.gmem.w.bits.last := true.B

    val awFire = io.gmem.aw.valid && io.gmem.aw.ready
    val wFire = io.gmem.w.valid && io.gmem.w.ready
    when(awFire) { awDone := true.B }
    when(wFire) { wDone := true.B }
    when((awDone || awFire) && (wDone || wFire)) {
      for (slot <- 0 until tableSize) {
        when(writeSlot === slot.U) {
          table(slot).state := State.WaitBResp
        }
      }
      writeActive := false.B
      awDone := false.B
      wDone := false.B
    }
  }

  // ---- Write response (B): the write committed ----
  val bslot = toTableIndex(io.gmem.b.bits.id)
  when(io.gmem.b.valid) {
    for (slot <- 0 until tableSize) {
      when(bslot === slot.U) {
        table(slot).state := State.RespPending
      }
    }
  }

  // ---- Respond to LockServer: one completed entry per cycle ----
  // The slot is freed only when the return is accepted, so backpressure from
  // LockServer simply holds the entry in RespPending.
  val respMask = VecInit(table.map(_.state === State.RespPending)).asUInt
  val respSlot = PriorityEncoder(respMask)
  io.resp.valid := respMask.orR
  io.resp.bits := table(respSlot).req
  // Return the addressed sub-word right-justified into the low bits rather than
  // the raw beat. readValue stays the raw beat for the internal write/AddN path
  // above; only the PE-facing response is selected, so a byte-mode SET hands
  // back its single byte in bits [7:0] (no consumer-side shift needed).
  io.resp.bits.data := selectedValue(
    table(respSlot).readValue,
    table(respSlot).req.atomicMode,
    table(respSlot).req.tag
  )
  // Carry the store-happened bit back to LockServer so it can place it in the
  // response status byte (bit 1). For the conditional SET_IF_* ops this is 0
  // when the predicate failed and nothing was written.
  io.resp.bits.writeOccurred := table(respSlot).wrote
  when(io.resp.fire) {
    for (slot <- 0 until tableSize) {
      when(respSlot === slot.U) {
        table(slot).state := State.Invalid
      }
    }
  }
}
