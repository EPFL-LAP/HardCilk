package Util

import chisel3._
import chisel3.util._

import chext.elastic
import chext.elastic.ConnectOp._

import chext.amba.axi4
import chext.amba.axi4s
import chext.amba.axi4.Ops._
import chext.amba.axi4s.Casts._

class WriteBufferCounterConfig(
    val wAddr: Int,
    val wData: Int,
    val wAllow: Int,
    val wAllowData: Seq[Int],
    val wId: Int = 6,
    val bufferDepth: Int = 128
) {

  assert(
    isPow2(wData) && wData >= 8,
    "Data payload must be sized power of 2 and at least 8 bits."
  )
  assert(nAllow >= 1, "There must be at least 1 type to pass through")
  assert(bufferDepth >= 1, "Buffer depth must be at least 1")

  def nAllow = wAllowData.size

  val cfgAxi = axi4.Config(
    wAddr = wAddr,
    wData = wData,
    wId = wId
  )

  val cfgAxisAllows =
    wAllowData.map(w => axi4s.Config(wData = w, onlyRV = true))
}

// Be careful with the order of the fields in the bundle (MST - LSB)
class WriteBundleCounter(
    cfg: WriteBufferCounterConfig
) extends Bundle {
  import cfg._

  private val totalSize = wAddr + wData + 32 + nAllow * wAllow
  private val paddingSize = (1 << log2Up(totalSize)) - totalSize

  val padding = UInt(paddingSize.W)
  val allow = Vec(nAllow, UInt(wAllow.W))
  val size = UInt(32.W)
  val data = UInt(wData.W)
  val addr = UInt(wAddr.W)
}

class WriteBufferCounter(
    cfg: WriteBufferCounterConfig
) extends Module {
  import cfg._
  private val wb_t = new WriteBundleCounter(cfg)

  // Interface
  val m_axi = IO(axi4.Master(cfg = cfgAxi))
  private val robCfgIn = cfgAxi.copy(wId = 0, read = false)
  private val robCfgOut = cfgAxi.copy(read = false)
  private val m_axi_single_id = Wire(axi4.Master(cfg = robCfgIn))

  private val writeRob = Module(new WriteROB(robCfgIn, robCfgOut))
  m_axi_single_id :=> writeRob.io.from_master
  writeRob.io.to_slave.asFull.aw :=> m_axi.asFull.aw
  writeRob.io.to_slave.asFull.w :=> m_axi.asFull.w
  m_axi.asFull.b :=> writeRob.io.to_slave.asFull.b

  if (cfgAxi.read) {
    m_axi.asFull.ar.noenq()
    m_axi.asFull.r.nodeq()
  }

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
  private val m_axi_ =
    axi4.full.MasterBuffer(m_axi_single_id.asFull, axi4.BufferConfig(b = 8))

  private val s_pkg_ = elastic.SourceBuffer(s_pkg.asLite, bufferDepth)
  private val s_allows_ =
    s_allows.map(p => elastic.SourceBuffer(p.asLite, bufferDepth))
  private val m_allows_ = m_allows.map(p => elastic.SinkBuffer(p.asLite, 8))

  m_axi_.aw.noenq()
  m_axi_.w.noenq()
  m_axi_.b.nodeq()

  private val numNext = Wire(Vec(nAllow, DecoupledIO(UInt(wAllow.W))))

  private val s_pkg_payload = s_pkg_.bits.asTypeOf(wb_t)
  when(s_pkg_.fire) {
    printf(
      "WriteBuffer: addr = %x, data = %x, size = %x, allow = ",
      s_pkg_payload.addr,
      s_pkg_payload.data,
      s_pkg_payload.size
    )
    for (i <- 0 until nAllow) {
      printf(" %x", s_pkg_payload.allow(i))
    }
    printf("\n")
  }

  new elastic.Fork(s_pkg_) {
    protected def onFork: Unit = {
      new elastic.Transform(fork(), m_axi_.aw) {
        protected def onTransform: Unit = {
          val payload = in.asTypeOf(wb_t)
          out := 0.U.asTypeOf(out)
          out.addr := payload.addr
          out.burst := axi4.BurstType.INCR
          out.len := 0.U
          out.size := payload.size
        }
      }

      new elastic.Transform(fork(), m_axi_.w) {
        protected def onTransform: Unit = {
          val payload = in.asTypeOf(wb_t)
          out := 0.U.asTypeOf(out)
          out.data := payload.data
          out.strb := Fill(wData / 8, 1.U(1.W))
          out.last := true.B
        }
      }

      numNext.zipWithIndex.foreach(x => {
        new elastic.Transform(fork(), elastic.SinkBuffer(x._1, bufferDepth)) {
          protected def onTransform: Unit = {
            out := in.asTypeOf(wb_t).allow(x._2)
          }
        }
      })
    }
  }

  // From Memory
  val duplB = Wire(Vec(nAllow, chiselTypeOf(m_axi_.b)))
  new elastic.Fork(m_axi_.b) {
    protected def onFork: Unit =
      duplB.foreach(x => fork() :=> x)
  }

  for (i <- 0 until nAllow) {
    val replIn = Wire(DecoupledIO(UInt(wAllow.W)))
    new elastic.Join(replIn) {
      protected def onJoin: Unit = {
        join(duplB(i))
        out := join(numNext(i))
      }
    }

    val token = Wire(DecoupledIO(Bool()))
    new elastic.Replicate(replIn, token, wAllow) {
      protected def onReplicate: Unit = {
        len := in
        out := true.B
      }
    }

    new elastic.Join(m_allows_(i)) {
      protected def onJoin: Unit = {
        join(token)
        out := join(s_allows_(i))
      }
    }
  }
}

// object WriteBufferEmitter extends App {
//   emitVerilog(new WriteBuffer(new WriteBufferConfig(
//     64, 128, 8, Seq(64, 128)
//   )))
// }
