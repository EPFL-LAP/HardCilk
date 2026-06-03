package chext.amba.axi4.full.components

import chisel3._
import chisel3.util._
import chisel3.experimental.prefix

import chext.{elasticnew => elastic}
import elastic.ConnectOp._

import chext.amba.axi4
import axi4.Ops._

private class CounterEx(maxExclusive: Int) extends Module {
  val wCounter = log2Ceil(maxExclusive)

  val io = IO(new Bundle {
    val up = Input(UInt(wCounter.W))
    val down = Input(UInt(wCounter.W))

    val used = Output(UInt(wCounter.W))
    val left = Output(UInt(wCounter.W))
  })

  private val rUsed = RegInit(0.U(wCounter.W))
  private val rLeft = RegInit((maxExclusive - 1).U(wCounter.W))

  when(io.up > io.down) {
    rUsed := rUsed + (io.up - io.down)
    rLeft := rLeft - (io.up - io.down)
  }.otherwise {
    rUsed := rUsed - (io.down - io.up)
    rLeft := rLeft + (io.down - io.up)
  }

  io.used := rUsed
  io.left := rLeft

  def canUp(x: UInt) = io.left >= x
  def canDown(x: UInt) = io.used >= x

  def up(x: UInt) = io.up := x
  def down(x: UInt) = io.down := x

  def noUp() = up(0.U)
  def noDown() = down(0.U)
}

case class ResponseBufferConfig(
    val axiCfg: axi4.Config,
    val bufLengthR: Int = 2,
    val bufLengthB: Int = 2,
    val writePassThrough: Boolean = false,
    val readPassThrough: Boolean = false
) {
  require(bufLengthR >= 2)
  require(bufLengthB >= 2)
}

class ResponseBuffer(val cfg: ResponseBufferConfig) extends Module {
  import cfg._

  val s_axi = IO(axi4.full.Slave(axiCfg))
  val m_axi = IO(axi4.full.Master(axiCfg))

  def implRead(): Unit = prefix("read") {
    val ctrR = Module(new CounterEx(bufLengthR + 1))

    ctrR.noUp()
    ctrR.noDown()

    val stall0 = new elastic.Stall(s_axi.ar, m_axi.ar) {
      out := in
      val len = in.len +& 1.U

      cond { !ctrR.canUp(len) }
      fire { ctrR.up(len) }
    }

    val connect0 = new elastic.Connect(elastic.SourceBuffer(m_axi.r, bufLengthR), s_axi.r) {
      fire { ctrR.down(1.U) }
    }
  }

  def implWrite(): Unit = prefix("write") {
    val ctrB = Module(new chext.util.Counter(bufLengthB + 1))

    ctrB.noDec()
    ctrB.noInc()

    val stall0 = new elastic.Stall(s_axi.aw, m_axi.aw) {
      out := in

      cond { ctrB.full }
      fire { ctrB.inc() }
    }

    val connect0 = new elastic.Connect(elastic.SourceBuffer(m_axi.b, bufLengthB), s_axi.b) {
      fire { ctrB.dec() }
    }

    s_axi.w :=> m_axi.w
  }

  if (axiCfg.read) {
    if (readPassThrough) {
      s_axi.ar :=> m_axi.ar
      m_axi.r :=> s_axi.r
    } else
      implRead()
  }

  if (axiCfg.write) {
    if (writePassThrough) {
      s_axi.aw :=> m_axi.aw
      s_axi.w :=> m_axi.w
      m_axi.b :=> s_axi.b
    } else
      implWrite()
  }

}
