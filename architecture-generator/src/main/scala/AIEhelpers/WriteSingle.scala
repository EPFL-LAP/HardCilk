package aiehelpers

import chisel3._

import chext.{elasticnew => e}

import chext.amba.axi4
import axi4.Ops._
import chext.amba.axi4s
import axi4s.Casts._

import chext.{ldstr => ldst}
import chisel3.util.isPow2
import chext.amba.axi4.full.components.WriteBuffer
import chext.amba.axi4.full.components.WriteBufferConfig

class WriteSingle_Task(addressWidth: Int, dataWidth: Int) extends Bundle {
  val ptr = UInt(addressWidth.W)
  val data = UInt(dataWidth.W)
  val _padding = UInt(((64 - ((addressWidth + dataWidth) % 64)) % 64).W)
}

case class WriteSingle_Config(
    addressWidth: Int = 64,
    dataWidth: Int = 32
) {
  require(isPow2(dataWidth), "Data width must be power of 2")

  def moduleSuffix: String = s"${dataWidth}"

  val axiCfg = axi4.Config(wAddr = addressWidth, wData = dataWidth)

  val inputCfg =
    axi4s.Config(
      wData = (new WriteSingle_Task(addressWidth, dataWidth)).getWidth,
      true
    )
}

class WriteSingle_Basic(cfg: WriteSingle_Config) extends Module {
  import cfg._

  val sourceTask = IO(axi4s.Slave(inputCfg))
  val m_axi = IO(axi4.Master(axiCfg))

  val rd = Module(
    new ldst.Store(
      ldst.StoreConfig(
        axiCfg
      )
    )
  )
  new e.Fork(sourceTask.asLite) {
    val srcAxisTransform = new e.Transform(fork(), e.SinkBuffer(rd.sourceTask, 16)) {
      out.address := in(63, 0)
      out.user := 0.U
    }
    val dataTransform = new e.Transform(fork(), e.SinkBuffer(rd.sourceData, 16)) {
      out := in(127, 64)
    }
  }

  // Magic to drop the result of store.
  rd.sinkResult.deq()

  val writeBufferCfg = WriteBufferConfig(
    bufLengthW = 256,
    bufLengthAW = 32
  )
  val wb = WriteBuffer(rd.m_axi, m_axi.asFull, writeBufferCfg)

  override def desiredName: String = s"WriteSingle_Basic_${cfg.moduleSuffix}"
}
