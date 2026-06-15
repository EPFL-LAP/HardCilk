package aiehelpers

import chisel3._

import chext.{elasticnew => e}
import e.ConnectOp._

import chext.amba.axi4
import axi4.Ops._
import chext.amba.axi4s
import axi4s.Casts._

import chext.stream
import chisel3.util.isPow2

import chext.amba.axi4.full.components.ResponseBuffer
import chext.amba.axi4.full.components.ResponseBufferConfig

class ReadStream_Task(addressWidth: Int) extends Bundle {
  val ptr = UInt(addressWidth.W)
  val cnt = UInt(32.W)
  // Add padding to make the bundle size multiple of 64 bits
  val _padding = UInt(((64 - ((addressWidth + 32) % 64)) % 64).W)
}

class ReadStream_Result(dataWidth: Int) extends Bundle {
  val data = UInt(dataWidth.W)
}

case class ReadStream_Config(
    addressWidth: Int = 64,
    dataWidth: Int = 32
) {
  require(isPow2(dataWidth), "Data width must be power of 2")
  require(
    new ReadStream_Task(addressWidth).getWidth % 64 == 0,
    "ReadStream_Task width must be multiple of 64 bits"
  )

  def moduleSuffix: String = s"${dataWidth}"

  val axiCfg = axi4.Config(wAddr = addressWidth, wData = dataWidth, wId = 0)

  val inputCfg =
    axi4s.Config(wData = (new ReadStream_Task(addressWidth)).getWidth, true)
  val outputCfg =
    axi4s.Config(wData = (new ReadStream_Result(dataWidth)).getWidth, false)
}

class ReadStream_Basic(cfg: ReadStream_Config) extends Module {
  import cfg._

  override def desiredName: String = s"ReadStream_Basic_${cfg.moduleSuffix}"

  val sourceTask = IO(axi4s.Slave(inputCfg))
  val sinkResult = IO(axi4s.Master(outputCfg))
  val m_axi = IO(axi4.Master(axiCfg))

  val rd = Module(
    new stream.Read(
      stream.ReadConfig(
        axiCfg,
        resultMode = stream.ReadResultMode.DropEmpty
      )
    )
  )

  val srcAxisTransform = new e.Transform(sourceTask.asLite, e.SinkBuffer(rd.sourceTask, 16)) {
    out.address := in(addressWidth - 1, 0)
    out.length := in(in.getWidth - 1, addressWidth)
    out.user := 0.U
  }

  val sinkAxisTransform = new e.Transform(rd.sinkResult, e.SinkBuffer(sinkResult.asFull, 16)) {
    out.data := in.data
    out.last := in.last
    out.strobe := ((1 << (dataWidth / 8)) - 1).U
    out.keep := ((1 << (dataWidth / 8)) - 1).U
  }
  rd.m_axi :=> m_axi.asFull
}



class ReadStreamWSplitter_Basic(cfg: ReadStream_Config) extends Module {
  import cfg._

  override def desiredName: String = s"ReadStreamWSplitter_Basic_${cfg.moduleSuffix}"

  val sourceTask = IO(axi4s.Slave(inputCfg))
  val sinkResult = IO(axi4s.Master(outputCfg))
  val m_axi = IO(axi4.Master(axiCfg))

  val inner = Module(new ReadStream_Basic(cfg))
  val respBufferCfg = ResponseBufferConfig(
    axiCfg = axiCfg,
    bufLengthR = 256
  )
  val respBuffer = Module(new ResponseBuffer(respBufferCfg))

  sourceTask.asLite :=> inner.sourceTask.asLite
  inner.sinkResult.asFull :=> sinkResult.asFull
  inner.m_axi :=> respBuffer.s_axi
  respBuffer.m_axi :=> m_axi.asFull
}