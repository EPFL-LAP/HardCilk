package Util

import chisel3._
import chisel3.util._

import chext.elastic
import chext.elastic.ConnectOp._

import chext.amba.axi4
import chext.amba.axi4s
import chext.amba.axi4.Ops._
import chext.amba.axi4s.Casts._
import chext.bundles.Bundle2

class WriteBufferConfig(
    val wAddr: Int,
    val wData: Int,
    val wAllow: Int,
    val wAllowData: Seq[Int]
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

  private val totalSize = wAddr + wData + 8 + nAllow * wAllow
  private val paddingSize = (1 << log2Up(totalSize)) - totalSize

  val padding = UInt(paddingSize.W)
  val allow = Vec(nAllow, UInt(wAllow.W))
  val size = UInt(8.W)
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
  private val m_axi_ = m_axi.asFull
  private val s_pkg_ = elastic.SourceBuffer(s_pkg.lite, 4)
  private val s_allows_ = s_allows.map(p => elastic.SourceBuffer(p.lite, 8))
  private val m_allows_ = m_allows.map(p => elastic.SinkBuffer(p.lite, 8))

  m_axi_.ar.noenq()
  m_axi_.r.nodeq()
  m_axi_.aw.noenq()
  m_axi_.w.noenq()
  m_axi_.b.nodeq()

  // To Memory
  private val mAddrStrbGen = Module(new axi4.full.components.AddressStrobeGenerator(wAddr, wData))
  private val pkgPayload = Wire(chiselTypeOf(s_pkg_))
  private val numNext = Wire(Vec(nAllow, DecoupledIO(UInt(wAllow.W))))

  // private val s_pkg_payload = s_pkg_.bits.asTypeOf(wb_t)
  // when(s_pkg_.fire) {
  //   printf("WriteBuffer: addr = %x, data = %x, size = %x, allow = %x\n", s_pkg_payload.addr, s_pkg_payload.data, s_pkg_payload.size, s_pkg_payload.allow.asUInt)
  // }

  new elastic.Fork(s_pkg_) {
    protected def onFork: Unit = {
      fork() :=> pkgPayload

      new elastic.Transform(fork(), mAddrStrbGen.source) {
        protected def onTransform: Unit = {
          out.addr := in.asTypeOf(wb_t).addr
          out.size := in.asTypeOf(wb_t).size
          out.burst := axi4.BurstType.FIXED
          out.len := 0.U
        }
      }

      numNext.zipWithIndex.foreach(x => {
        new elastic.Transform(fork(), elastic.SinkBuffer(x._1, 4)) {
          protected def onTransform: Unit = {
            out := in.asTypeOf(wb_t).allow(x._2)
          }
        }
      })
    }
  }

  private val toAxi = Wire(DecoupledIO(new Bundle2(chiselTypeOf(pkgPayload.bits), chiselTypeOf(mAddrStrbGen.sink.bits))))
  new elastic.Join(toAxi) {
    protected def onJoin: Unit = {
      out._1 := join(pkgPayload)
      out._2 := join(mAddrStrbGen.sink)
    }
  }

  new elastic.Fork(toAxi) {
    protected def onFork: Unit = {
      new elastic.Transform(fork(), m_axi_.aw) {
        protected def onTransform: Unit = {
          out := 0.U.asTypeOf(out)
          out.addr := in._2.addr
          out.burst := axi4.BurstType.FIXED
          out.size := in._1.asTypeOf(wb_t).size
        }
      }

      new elastic.Transform(fork(), m_axi_.w) {
        protected def onTransform: Unit = {
          out := 0.U.asTypeOf(out)
          out.data := in._1.asTypeOf(wb_t).data << (in._2.lowerByteIndex * 8.U)
          out.strb := in._2.strb
          out.last := in._2.last
        }
      }
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
    new elastic.Replicate(replIn, token) {
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