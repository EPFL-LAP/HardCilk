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

  when(processR) {
    val rslot = toTableIndex(rBufBits.id)
    val current = rBufBits.data
    for (slot <- 0 until tableSize) {
      when(rslot === slot.U) {
        val entry = table(slot)
        val operand = entry.req.data
        val op = entry.req.operation
        val writeNeeded = WireDefault(false.B)
        val mode = entry.req.atomicMode
        val currentSelected = selectedValue(current, mode, entry.req.tag)
        val operandSelected = operand & valueMask(mode)

        switch(op) {
          is(Operation.LockSetUnlockAndReturnCurrent) {
            writeNeeded := true.B
          }
          is(Operation.LockSetIfGreaterUnlockAndReturnCurrent) {
            writeNeeded := operandSelected > currentSelected
          }
          is(Operation.LockSetIfSignedLessUnlockAndReturnCurrent) {
            writeNeeded := signExtendSelected(operandSelected, mode) < signExtendSelected(
              currentSelected,
              mode
            )
          }
          is(Operation.LockAddNReturnCurrent) {
            writeNeeded := true.B
          }
        }
        table(slot).readValue := current
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
    val currentSelected = selectedValue(writeReadValue, mode, writeReq.tag)
    val writeValue = WireDefault(writeReq.data & selectedMask)
    switch(writeReq.operation) {
      is(Operation.LockAddNReturnCurrent) {
        // Add the per-request operand N (masked to the atomic width) to the
        // current value. N=1 reproduces the old add-one behavior.
        writeValue := (currentSelected + (writeReq.data & selectedMask))(63, 0)
      }
    }
    val shiftedData = (writeValue & selectedMask) << offsetBits
    val shiftedStrobe = (strobeMask(mode) << offsetBytes)(fullStrobe.getWidth - 1, 0)

    io.gmem.aw.valid := !awDone
    io.gmem.aw.bits.addr := writeReq.tag.pad(axiCfg.wAddr)
    io.gmem.aw.bits.id := toAxiId(writeSlot)
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
  when(io.resp.fire) {
    for (slot <- 0 until tableSize) {
      when(respSlot === slot.U) {
        table(slot).state := State.Invalid
      }
    }
  }
}
