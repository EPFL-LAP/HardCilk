package aiehelpers

import chisel3._

import chext.{elasticnew => e}

import chext.amba.axi4
import axi4.Ops._
import chext.amba.axi4s
import axi4s.Casts._

import chext.{ldstr => ldst}
import chisel3.util.isPow2
import chext.amba.axi4.full.components.ResponseBuffer
import chext.amba.axi4.full.components.ResponseBufferConfig

class ReadSingle_Task(addressWidth: Int) extends Bundle {
  val ptr = UInt(addressWidth.W)
}

class ReadSingle_Result(dataWidth: Int) extends Bundle {
  val data = UInt(dataWidth.W)
}

case class ReadSingle_Config(
    addressWidth: Int = 64,
    dataWidth: Int = 32
) {
  require(isPow2(dataWidth), "Data width must be power of 2")

  def moduleSuffix: String = s"${dataWidth}"

  val axiCfg = axi4.Config(wAddr = addressWidth, wData = dataWidth)

  val inputCfg =
    axi4s.Config(wData = (new ReadSingle_Task(addressWidth)).getWidth, true)
  val outputCfg =
    axi4s.Config(wData = (new ReadSingle_Result(dataWidth)).getWidth, false)
}

class ReadSingle_Basic(cfg: ReadSingle_Config) extends Module {
  import cfg._

  val sourceTask = IO(axi4s.Slave(inputCfg))
  val sinkResult = IO(axi4s.Master(outputCfg))
  val m_axi = IO(axi4.Master(axiCfg))

  val rd = Module(
    new ldst.Load(
      ldst.LoadConfig(
        axiCfg
      )
    )
  )

  val srcAxisTransform = new e.Transform(sourceTask.asLite, e.SinkBuffer(rd.sourceTask, 512)) {
    out.address := in(addressWidth - 1, 0)
    out.user := 0.U
  }

  val sinkAxisTransform = new e.Transform(rd.sinkResult, e.SinkBuffer(sinkResult.asFull, 512)) {
    // out := in.data
    out.last := true.B
    out.data := in.data
    out.strobe := ((1 << (dataWidth / 8)) - 1).U
    out.keep := ((1 << (dataWidth / 8)) - 1).U
  }

  val respBufferCfg = ResponseBufferConfig(
    axiCfg = axiCfg,
    bufLengthR = 256
  )
  val respBuffer = Module(new ResponseBuffer(respBufferCfg))
  rd.m_axi :=> respBuffer.s_axi
  respBuffer.m_axi :=> m_axi.asFull

  override def desiredName: String = s"ReadSingle_Basic_${cfg.moduleSuffix}"
}
