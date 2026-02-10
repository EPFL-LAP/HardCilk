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

import scala.annotation.nowarn
import os.write


class RemoteWriteBufferConfig(
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
class WriteBundleRemote(
    cfg: RemoteWriteBufferConfig
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

class RemoteWriteBuffer(
    cfg: RemoteWriteBufferConfig
) extends Module {
  import cfg._
  private val wb_t = new WriteBundleRemote(cfg)

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

  val fpgaId = IO(Input(UInt(4.W)))
  val memReqToRemote = IO(DecoupledIO(new MemReq(64, wData)))
  val memRespFromRemote = IO(Flipped(DecoupledIO(new WriteResp())))


  val s_allows = cfgAxisAllows.map(c => IO(axi4s.Slave(c)))
  val m_allows = cfgAxisAllows.map(c => IO(axi4s.Master(c)))

  // Implementation
  private val m_axi_ = axi4.full.MasterBuffer(m_axi.asFull, axi4.BufferConfig(b = 8))
  private val s_pkg_ = elastic.SourceBuffer(s_pkg.asLite, 4)
  private val s_allows_ = s_allows.map(p => elastic.SourceBuffer(p.asLite, 16))
  private val m_allows_ = m_allows.map(p => elastic.SinkBuffer(p.asLite, 16))

  m_axi_.ar.noenq()
  m_axi_.r.nodeq()
  m_axi_.aw.noenq()
  m_axi_.w.noenq()
  m_axi_.b.nodeq()

  // To Memory
  private val mAddrStrbGen = Module(new axi4.full.components.AddressStrobeGenerator(wAddr, wData))
  private val pkgPayload = Wire(chiselTypeOf(s_pkg_))
  private val numNext = Wire(Vec(nAllow, DecoupledIO(UInt(wAllow.W))))

  private val s_pkg_payload = s_pkg_.bits.asTypeOf(wb_t)
  when(s_pkg_.fire) {
    printf("WriteBuffer: addr = %x, data = %x, size = %x, allow = ", s_pkg_payload.addr, s_pkg_payload.data, s_pkg_payload.size)
    for (i <- 0 until nAllow) {
      printf(" %x", s_pkg_payload.allow(i))
    }
    printf("\n")
  }


  // Demux the elastic package based on the address

  private val s_pkg_to_local = Wire(chiselTypeOf(s_pkg_))
  private val s_pkg_to_remote = Wire(chiselTypeOf(s_pkg_))

  // Create a queue of single bit size
  private val select_queue = Module(new Queue(Bool(), 32))

  new elastic.Fork(s_pkg_) {
    @nowarn("cat=unused")
    protected def onFork: Unit = {
      val requests_responses_demux = elastic.Demux(
        source = fork(in),
        sinks =  Seq(elastic.SinkBuffer(s_pkg_to_remote, 128), s_pkg_to_local),
        // select based on the isRequest bit in TDATA
        select = fork(in.asTypeOf(wb_t).addr(64- 5, 56) === fpgaId)
      )

      val select_queue_transform = new elastic.Transform(fork(), select_queue.io.enq) {
        protected def onTransform: Unit = {
          out := in.asTypeOf(wb_t).addr(64- 5, 56) === fpgaId // remember the selection bit
        }
      }

      numNext.zipWithIndex.foreach(x => {
        new elastic.Transform(fork(), elastic.SinkBuffer(x._1, 32)) {
          protected def onTransform: Unit = {
              out := in.asTypeOf(wb_t).allow(x._2)
            }
          }
        }
      )
    }
  }

  val memResponseFromRemoteQueueBuffer = Module(new Queue(new WriteResp(), 256))


  //val sent_package_counter = RegInit(8.U(4.W))
  val s_pkg_to_remote_throttled = Wire(chiselTypeOf(s_pkg_to_remote))
  
  //s_pkg_to_remote <> s_pkg_to_remote_throttled
  
  s_pkg_to_remote_throttled.bits := s_pkg_to_remote.bits

  s_pkg_to_remote_throttled.valid := s_pkg_to_remote.valid && (memResponseFromRemoteQueueBuffer.io.count < 256.U)
  s_pkg_to_remote.ready := s_pkg_to_remote_throttled.ready && (memResponseFromRemoteQueueBuffer.io.count < 256.U)



  // Create the remote memory request through the memReqToRemote port
  new elastic.Transform(s_pkg_to_remote_throttled, elastic.SinkBuffer(memReqToRemote, 64)) {
    protected def onTransform: Unit = {
      out.address := in.asTypeOf(wb_t).addr
      out.data := in.asTypeOf(wb_t).data
      out.axiSize := in.asTypeOf(wb_t).size
      out.isWrite := true.B
    }
  }


  // Create the local memory request through the s_pkg_to_local port
  new elastic.Fork(s_pkg_to_local) {
    protected def onFork: Unit = {
      fork() :=> pkgPayload

      new elastic.Transform(fork(), mAddrStrbGen.source) {
        protected def onTransform: Unit = {
          out.addr := in.asTypeOf(wb_t).addr
          out.size := in.asTypeOf(wb_t).size
          out.burst := axi4.BurstType.INCR
          out.len := 0.U
        }
      }
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
          out.burst := axi4.BurstType.INCR
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


  // Transform the m_axi_.b to the simple WriteResp
  private val local_replicate_token = Wire(DecoupledIO(new WriteResp()))
  new elastic.Transform(m_axi_.b, local_replicate_token){
    protected def onTransform: Unit = {
      out.success := 1.U
    }
  }


  memResponseFromRemoteQueueBuffer.io.enq <> memRespFromRemote

  // Mux between local memory replies and remote memory replies
  private val selectedReplicationToken = Wire(DecoupledIO(new WriteResp()))
  val duplication_mux = elastic.Mux(
    Seq(memResponseFromRemoteQueueBuffer.io.deq, local_replicate_token),
    selectedReplicationToken,
    select_queue.io.deq
    // Last is always true
  )


  
  
  val duplB = Wire(Vec(nAllow, chiselTypeOf(selectedReplicationToken)))


  new elastic.Fork(selectedReplicationToken) {
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

object RemoteWriteBufferEmitter extends App {
  emitVerilog(new RemoteWriteBuffer(new RemoteWriteBufferConfig(
    64, 128, 8, Seq(64, 128)
  )),
  Array(
      "--target-dir=output/RemoteWriteBuffer/"
    ))
  
}