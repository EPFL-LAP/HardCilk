// ============================================================
//  BramTestAndSet.scala  —  N-banked atomic Test-and-Set
//
//  Banking scheme
//  ──────────────
//  bank_index = vertex_id % numBanks          (low wBankSel bits)
//  word_addr  = vertex_id / (numBanks * 64)   (row within bank)
//  bit_offset = (vertex_id >> wBankSel) % 64  (bit within word)
//
//  Each bank owns a disjoint subset of vertices, so N requests
//  to N different banks are served fully in parallel with zero
//  structural hazard.
//
//  Conflict (two PEs → same bank same cycle)
//  ──────────────────────────────────────────
//  Resolved by the per-bank RRArbiter.  The losing PE is stalled
//  for one cycle; all other banks keep running independently.
//
//  Pipeline per bank  (4 stages, fully independent per bank)
//  ──────────────────────────────────────────────────────────
//  S0 : RRArbiter selects one PE request targeting this bank
//  S1 : Issue SyncReadMem read  (1-cycle latency → BRAM)
//       ↳ add one pipeline register here to target URAM instead
//  S2 : Read result arrives; check bit; conditional write-back
//  S3 : Forward won/lost to the correct PE's response register
//
//  IO
//  ──
//  s_axis_req(p)  : slave  AXI4-Stream, 32-bit vertex ID
//  m_axis_resp(p) : master AXI4-Stream,  8-bit result
//                   bit[0] = 1 → won  (first to visit this vertex)
//                   bit[0] = 0 → lost (vertex already visited)
//
//  Parameters
//  ──────────
//  numPEs      : number of processing elements
//  numBanks    : number of parallel BRAM banks  (must be power-of-2)
//  numVertices : maximum vertex count
// ============================================================

package Util

import chisel3._
import chisel3.util._
import chext.amba.axi4s

class BramTestAndSet(
    val numPEs:      Int,
    val numBanks:    Int,
    val numVertices: Int
) extends Module {

  require(isPow2(numBanks),   "numBanks must be a power of 2")
  require(numPEs      >= 1,   "need at least one PE")
  require(numVertices >= numBanks * 64, "numVertices too small for banking granularity")

  // ── Derived constants ──────────────────────────────────
  val wBankSel      = log2Ceil(numBanks)
  val vertPerBank   = (numVertices + numBanks - 1) / numBanks
  val wordsPerBank  = (vertPerBank  + 63)          / 64
  val wBankAddr     = log2Ceil(wordsPerBank)
  val wPE           = log2Ceil(numPEs)

  // ── AXI4-Stream configs ────────────────────────────────
  val reqCfg  = axi4s.Config(wData = 32, onlyRV = true)
  val respCfg = axi4s.Config(wData = 8,  onlyRV = true)

  // ── Top-level IO ───────────────────────────────────────
  val s_axis_req  = IO(Vec(numPEs, axi4s.Slave(reqCfg)))
  val m_axis_resp = IO(Vec(numPEs, axi4s.Master(respCfg)))

  // ── Per-PE response output registers ──────────────────
  // Written by whichever bank pipeline finishes first for that PE.
  // A PE can only have one in-flight request at a time (its TREADY
  // goes low until the response register drains), so there is no
  // write-after-write hazard here.
  val resp_valid = RegInit(VecInit(Seq.fill(numPEs)(false.B)))
  val resp_data  = Reg(Vec(numPEs, UInt(8.W)))

  for (p <- 0 until numPEs) {
    when(resp_valid(p) && m_axis_resp(p).TREADY) {
      resp_valid(p) := false.B
    }
    m_axis_resp(p).TVALID := resp_valid(p)
    m_axis_resp(p).TDATA  := resp_data(p)
  }

  // ── Global BRAM init counter (shared across all banks) ─
  // All banks are written in lock-step so one counter suffices.
  val initCnt  = RegInit(0.U((wBankAddr + 1).W))
  val initDone = RegInit(false.B)
  when(!initDone) {
    when(initCnt === (wordsPerBank - 1).U) { initDone := true.B }
    .otherwise                             { initCnt  := initCnt + 1.U }
  }

  // ── Per-PE TREADY accumulator ─────────────────────────
  // Default = false; a bank asserts it when it accepts a PE's req.
  val peReady = Wire(Vec(numPEs, Bool()))
  for (p <- 0 until numPEs) peReady(p) := false.B

  // ── One pipeline per bank ─────────────────────────────
  for (b <- 0 until numBanks) {

    // ╔══════════════════════════════════════╗
    // ║  BRAM for this bank                  ║
    // ╚══════════════════════════════════════╝
    val bram = SyncReadMem(wordsPerBank, UInt(64.W))

    when(!initDone) {
      bram.write(initCnt(wBankAddr - 1, 0), 0.U(64.W))
    }

    // ╔══════════════════════════════════════╗
    // ║  Address decode for this bank        ║
    // ╚══════════════════════════════════════╝
    //   vid[wBankSel-1 : 0]          → bank selector  (must equal b)
    //   vid[wBankSel+5 : wBankSel]   → bit offset within 64-bit word
    //   vid[31         : wBankSel+6] → word address within this bank
    def isMine(vid: UInt)   = vid(wBankSel - 1, 0) === b.U
    def bitOff(vid: UInt)   = vid(wBankSel + 5, wBankSel)
    def wordAddr(vid: UInt) = vid >> (wBankSel + 6)

    // ╔══════════════════════════════════════╗
    // ║  S0 : Per-bank RR arbiter            ║
    // ╚══════════════════════════════════════╝
    val arb = Module(new RRArbiter(UInt(32.W), numPEs))

    for (p <- 0 until numPEs) {
      arb.io.in(p).valid := s_axis_req(p).TVALID &&
                            isMine(s_axis_req(p).TDATA) &&
                            initDone
      arb.io.in(p).bits  := s_axis_req(p).TDATA
    }

    // ╔══════════════════════════════════════╗
    // ║  Pipeline stage registers            ║
    // ╚══════════════════════════════════════╝

    // S0 → S1
    val s1_valid    = RegInit(false.B)
    val s1_peIdx    = Reg(UInt(wPE.W))
    val s1_wAddr    = Reg(UInt(wBankAddr.W))
    val s1_bOff     = Reg(UInt(6.W))

    // S1 → S2  (BRAM word comes out of SyncReadMem as a Wire)
    val s2_valid    = RegInit(false.B)
    val s2_peIdx    = Reg(UInt(wPE.W))
    val s2_bOff     = Reg(UInt(6.W))
    val s2_wAddr    = Reg(UInt(wBankAddr.W))
    val s2_word     = bram.read(s1_wAddr, s1_valid)   // combinational output

    // S2 → S3
    val s3_valid    = RegInit(false.B)
    val s3_peIdx    = Reg(UInt(wPE.W))
    val s3_won      = Reg(Bool())

    // ╔══════════════════════════════════════╗
    // ║  Back-pressure                       ║
    // ╚══════════════════════════════════════╝
    // Stall this bank if S3's target PE response buffer is occupied
    // and the PE has not yet accepted it.
    val stall = s3_valid &&
                resp_valid(s3_peIdx) &&
                !m_axis_resp(s3_peIdx).TREADY

    arb.io.out.ready := !stall && initDone

    // ╔══════════════════════════════════════╗
    // ║  S3 : write result to PE resp reg    ║
    // ╚══════════════════════════════════════╝
    when(!stall && s3_valid) {
      resp_valid(s3_peIdx) := true.B
      resp_data(s3_peIdx)  := Cat(0.U(7.W), s3_won)
    }

    // ╔══════════════════════════════════════╗
    // ║  S2 : check bit, conditional write   ║
    // ╚══════════════════════════════════════╝
    when(!stall) {
      s3_valid := s2_valid
      when(s2_valid) {
        val mask  = (1.U(64.W)) << s2_bOff
        val isSet = (s2_word & mask).orR
        s3_peIdx := s2_peIdx
        s3_won   := !isSet
        when(!isSet) {
          bram.write(s2_wAddr, s2_word | mask)
        }
      }
    }

    // ╔══════════════════════════════════════╗
    // ║  S1 : register BRAM read address     ║
    // ╚══════════════════════════════════════╝
    when(!stall) {
      s2_valid  := s1_valid
      s2_peIdx  := s1_peIdx
      s2_bOff   := s1_bOff
      s2_wAddr  := s1_wAddr
    }

    // ╔══════════════════════════════════════╗
    // ║  S0 : latch arbitrated request       ║
    // ╚══════════════════════════════════════╝
    when(!stall) {
      s1_valid := arb.io.out.valid
      when(arb.io.out.valid) {
        val vid   = arb.io.out.bits
        s1_peIdx := arb.io.chosen
        s1_wAddr := wordAddr(vid)
        s1_bOff  := bitOff(vid)
      }
    }

    // ╔══════════════════════════════════════╗
    // ║  Feed TREADY back to PEs             ║
    // ╚══════════════════════════════════════╝
    // A PE's TREADY is true when this bank's arbiter accepts it.
    for (p <- 0 until numPEs) {
      when(arb.io.out.valid        &&
           (arb.io.chosen === p.U) &&
           arb.io.out.ready) {
        peReady(p) := true.B
      }
    }

  } // ── end per-bank loop ──────────────────────────────

  // ── Connect accumulated TREADY to IO ──────────────────
  for (p <- 0 until numPEs) {
    s_axis_req(p).TREADY := peReady(p)
  }

}

import _root_.circt.stage.ChiselStage
object BramTestAndSetEmitter extends App {
    
    ChiselStage.emitSystemVerilogFile(
      {
        val module = new BramTestAndSet(numPEs = 8, numBanks = 2, numVertices = 128)
        module
      },
      Array(f"--target-dir=output"),
      Array("--disable-all-randomization")
    )

}