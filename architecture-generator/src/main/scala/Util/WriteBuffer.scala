package Util

import chisel3._
import chisel3.util._

import chext.elastic.ConnectOp._

import chext.amba.axi4
import chext.amba.axi4s
import chext.amba.axi4.Ops._
import chext.amba.axi4s.Casts._

class WriteBufferConfig(
    val wAddr: Int,
    val wData: Int,
    val wAllow: Int,
    val wAllowData: Seq[Int],
    val nOutstanding: Int = 63
) {

  assert(isPow2(wData) && wData >= 8, "Data payload must be sized power of 2 and at least 8 bits.")
  assert(nAllow >= 1, "There must be at least 1 type to pass through")

  def nAllow = wAllowData.size

  val cfgAxi = axi4.Config(
    wAddr = wAddr,
    wData = wData
  )

  val cfgAxisAllows = wAllowData.map(w => axi4s.Config(wData = w, onlyRV = true))
}

// Be careful with the order of the fields in the bundle (MST - LSB)
class WriteBundle(
    cfg: WriteBufferConfig
) extends Bundle {
  import cfg._

  private val totalSize = wAddr + wData + 32 + nAllow * wAllow
  private val paddingSize = (1 << log2Up(totalSize)) - totalSize

  val padding = UInt(paddingSize.W)
  val allow = if (wAllow == 0) None else Some(Vec(nAllow, UInt(wAllow.W)))
  val size = UInt(32.W)
  val data = UInt(wData.W)
  val addr = UInt(wAddr.W)
}

class WriteBuffer(
    cfg: WriteBufferConfig
) extends Module {
  import cfg._
  private val wb_t = new WriteBundle(cfg)

  // Interface
  val m_axi = IO(axi4.Master(cfg = cfgAxi))
  val s_pkg = IO(
    axi4s.Slave(cfg =
      axi4s.Config(
        wData = wb_t.getWidth,
        onlyRV = true
      )
    )
  )

  val s_allows = cfgAxisAllows.map(c => IO(axi4s.Slave(c)))
  val m_allows = cfgAxisAllows.map(c => IO(axi4s.Master(c)))

  // Implementation
  if (wAllow == 0) {
    val wb = Module(
      new WriteBufferLast(
        new WriteBufferLastConfig(
          wAddr = wAddr,
          wData = wData,
          wAllowData = wAllowData,
          nOutstanding = nOutstanding
        )
      )
    )

    wb.m_axi :=> m_axi
    s_pkg.asLite :=> wb.s_pkg.asLite
    s_allows.zip(wb.s_allows).foreach(x => x._1.asLite :=> x._2.asLite)
    wb.m_allows.zip(m_allows).foreach(x => x._1.asLite :=> x._2.asLite)
  } else {
    val wb = Module(
      new WriteBufferCounter(
        new WriteBufferCounterConfig(
          wAddr = wAddr,
          wData = wData,
          wAllow = wAllow,
          wAllowData = wAllowData
        )
      )
    )

    wb.m_axi :=> m_axi
    s_pkg.asLite :=> wb.s_pkg.asLite
    s_allows.zip(wb.s_allows).foreach(x => x._1.asLite :=> x._2.asLite)
    wb.m_allows.zip(m_allows).foreach(x => x._1.asLite :=> x._2.asLite)
  }
}

object WriteBufferEmitter extends App {
  emitVerilog(new WriteBuffer(new WriteBufferConfig(
    64, 128, 0, Seq(64, 128)
  )))
}
