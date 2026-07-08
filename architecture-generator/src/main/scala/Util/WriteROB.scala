package Util

import chisel3._
import chisel3.util._

import chext.amba.{axi4 => a4}
import chext.{elastic => e}
import e.ConnectOp._
import a4.Ops._

/** Reorder buffer that turns a single-ID, in-order write master into a
  * multiple-outstanding write stream.
  *
  * `from_master` issues writes on a single AXI ID (wId == 0). Each write is
  * handed a fresh rotating ID toward `to_slave` (wId > 0) so the memory can
  * keep `1 << wId` writes in flight, and the B responses are reassembled into
  * issue order before returning to the master. Write-only (route reads around
  * it), assumes no same-address (WAW) ordering dependency, and ignores BRESP.
  */
class WriteROB(axiCfgIn: a4.Config, axiCfgOut: a4.Config) extends Module {
  val io = IO(new Bundle {
    val from_master = a4.Slave(cfg = axiCfgIn)
    val to_slave = a4.Master(cfg = axiCfgOut)
  })

  require(
    axiCfgIn.wId == 0,
    "ROB takes requests on a single axiID and spreads them out"
  )
  require(axiCfgOut.wId > 0)
  require(
    axiCfgIn.write && !axiCfgIn.read,
    "WriteROB is write-only; route reads around it"
  )
  require(
    {
      val in_dup = axiCfgIn.copy(wId = 0)
      val out_dup = axiCfgOut.copy(wId = 0)

      in_dup == out_dup
    },
    "All other axi params must be the same"
  )

  private val entries = 1 << axiCfgOut.wId
  val readyList = RegInit(VecInit(Seq.fill(entries)(false.B)))
  // We just need to track when the head of the list is ready and shift it. Otherwise, since all incoming have the same ID, we just need to send a simple bvalid.
  val currentHeadOfList = RegInit(0.U(log2Ceil(entries).W))
  val currentCount = RegInit(0.U(log2Ceil(entries + 1).W))

  private val s = io.from_master.asFull
  private val m = io.to_slave.asFull

  private val full = currentCount === entries.U
  private val issueId =
    (currentHeadOfList +& currentCount)(log2Ceil(entries) - 1, 0)

  private val slotSource = Wire(Irrevocable(UInt(log2Ceil(entries).W)))
  slotSource.valid := !full
  slotSource.bits := issueId

  new e.Join(m.aw) {
    protected def onJoin: Unit = {
      val aw = join(s.aw)
      out.id := join(slotSource)
      out.addr := aw.addr
      out.len := aw.len
      out.size := aw.size
      out.burst := aw.burst
      out.lock := aw.lock
      out.cache := aw.cache
      out.prot := aw.prot
      out.qos := aw.qos
      out.region := aw.region
      out.user := aw.user
    }
  }

  s.w :=> m.w

  m.b.ready := true.B
  when(m.b.valid) {
    readyList(m.b.bits.id) := true.B
  }

  // B out: release one response to the master whenever the head slot is ready.
  s.b.bits := DontCare
  s.b.bits.resp := a4.ResponseFlag.OKAY
  s.b.valid := readyList(currentHeadOfList)
  when(s.b.fire) {
    readyList(currentHeadOfList) := false.B
    currentHeadOfList := currentHeadOfList + 1.U
  }

  // Outstanding count: +1 per issued write, -1 per retired response.
  when(m.aw.fire && !s.b.fire) {
    currentCount := currentCount + 1.U
  }.elsewhen(!m.aw.fire && s.b.fire) {
    currentCount := currentCount - 1.U
  }
}
