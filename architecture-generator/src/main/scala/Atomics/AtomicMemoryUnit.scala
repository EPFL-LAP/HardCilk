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

// There should be an AMU for each LockServer pipeline lane. It performs a RMW
// under one of the functions featured in the Operation enum. The LockServer
// enforces that no two outstanding AMU operations share a memory address.

// Notes:
// The AMU sends AXI requests where the id is the table slot index.
// A request contains the following (see LockServer defintion):
//    tag = byte address in memory that also serves as the unique tag in the LockServer table
//    data = operand
//    readValue = the previous memory contents to send back to the PE
// The same data type is used to recieve requests from the server and to send back responses
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

  object State extends ChiselEnum {
    val Invalid, WaitRead, WantWrite, WaitBResp, RespPending = Value
  }
  class Entry extends Bundle {
    val state = State()
    val req = new RequestType(n, addrW)
    val readValue = UInt(64.W)
    val wrote =
      Bool() // Whether this op's read-modify-write actually stored. Always true for unconditional
  }
  // AMU table size caps the number of outstanding requests to MEM
  val table = RegInit(VecInit(Seq.fill(tableSize)(0.U.asTypeOf(new Entry))))

  private val fullValueMask = "hffffffffffffffff".U(64.W)
  private val fullStrobe =
    ((BigInt(1) << (axiCfg.wData / 8)) - 1).U((axiCfg.wData / 8).W)

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

  private def selectedValue(
      data: UInt,
      mode: AtomicMode.Type,
      addr: UInt
  ): UInt = {
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

  // Mapping to treat float as unsigned integer for sake of comparison ops
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

  // Issue reads from the incoming FIFO
  val freeMask = VecInit(table.map(_.state === State.Invalid)).asUInt
  val canAlloc = freeMask.orR
  val allocSlot = PriorityEncoder(freeMask)
  val arReqValid = RegInit(false.B)
  val arReq = Reg(new RequestType(n, addrW))
  val arSlot = Reg(UInt(tableIdxW.W))

  // Note that this can issue requests, at most, every other cycle.
  // ALSO note that this is NOT the bottleneck almost ever, as single
  // beat reads will suffer from poor performance regardless.
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

  // Process reads as they are returned on r
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

  // Stage 1: select table(rslot).req + the read data, and register them;
  // Stage 2: compute writeNeeded from the registered entry and apply it.

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

    val floatCmp = rd1Req.floatCompare
    val signFlip = (BigInt(1) << 63).U(64.W)
    def greaterKey(v: UInt): UInt = Mux(floatCmp, floatOrderKey(v, mode), v)
    def lessKey(v: UInt): UInt =
      Mux(
        floatCmp,
        floatOrderKey(v, mode),
        signExtendSelected(v, mode).asUInt ^ signFlip
      )

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
        table(slot).state := Mux(
          writeNeeded,
          State.WantWrite,
          State.RespPending
        )
      }
    }
  }

  // Send out writes from the done elements in the table
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
    val positionedOperand =
      ((writeReq.data & selectedMask) << offsetBits)(63, 0)
    val shiftedData = WireDefault(positionedOperand)
    switch(writeReq.operation) {
      is(Operation.LockAddNReturnCurrent) {
        shiftedData := (writeReadValue + positionedOperand)(63, 0)
      }
    }
    val shiftedStrobe =
      (strobeMask(mode) << offsetBytes)(fullStrobe.getWidth - 1, 0)

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

  // When the write commits, we update the table state
  val bslot = toTableIndex(io.gmem.b.bits.id)
  when(io.gmem.b.valid) {
    for (slot <- 0 until tableSize) {
      when(bslot === slot.U) {
        table(slot).state := State.RespPending
      }
    }
  }

  // Send back result to the LockServer
  val respMask = VecInit(table.map(_.state === State.RespPending)).asUInt
  val respSlot = PriorityEncoder(respMask)
  io.resp.valid := respMask.orR
  io.resp.bits := table(respSlot).req

  io.resp.bits.data := selectedValue(
    table(respSlot).readValue,
    table(respSlot).req.atomicMode,
    table(respSlot).req.tag
  )

  io.resp.bits.writeOccurred := table(respSlot).wrote
  when(io.resp.fire) {
    for (slot <- 0 until tableSize) {
      when(respSlot === slot.U) {
        table(slot).state := State.Invalid
      }
    }
  }
}
