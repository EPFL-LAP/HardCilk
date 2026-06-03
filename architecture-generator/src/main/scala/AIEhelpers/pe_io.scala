package aiehelpers

import chisel3._

import chext.{elasticnew => e}
import e.ConnectOp._

import chext.amba.axi4
import axi4.Ops._
import chext.amba.axi4s
import axi4s.Casts._

import Descriptors._

case class PeRwPort(
    subPEName: String,
    index: Int,
    sourceTask: axi4s.Interface,
    sinkResult: Option[axi4s.Interface],
    m_axi: axi4.RawInterface
) {
  def instanceName: String = s"${subPEName}_${index}"
}

class PeIO(subPEs: Map[String, SubPEDescriptor], count: Int) extends Module {
  require(count >= 0, "count must be non-negative")

  val rwPorts: Seq[PeRwPort] =
    (0 until count).flatMap { index =>
      subPEs.toSeq.sortBy(_._1).flatMap { case (name, descriptor) =>
        descriptor.rwRequest.map { req =>
          val instanceName = s"${name}_${index}"

          (req.`type`, req.mode) match {
            case ("read", "single") =>
              val cfg = ReadSingle_Config(dataWidth = req.portWidth)
              val dut = Module(new ReadSingle_Basic(cfg))

              val sourceTask =
                IO(axi4s.Slave(cfg.inputCfg)).suggestName(s"${instanceName}_sourceTask")
              val sinkResult =
                IO(axi4s.Master(cfg.outputCfg)).suggestName(s"${instanceName}_sinkResult")
              val m_axi =
                IO(axi4.Master(cfg.axiCfg)).suggestName(s"${instanceName}_m_axi")

              sourceTask.asLite :=> dut.sourceTask.asLite
              dut.sinkResult.asFull :=> sinkResult.asFull
              dut.m_axi :=> m_axi.asFull

              PeRwPort(name, index, sourceTask, Some(sinkResult), m_axi)

            case ("read", "stream") =>
              val cfg = ReadStream_Config(dataWidth = req.portWidth)
              val dut = Module(new ReadStream_Basic(cfg))

              val sourceTask =
                IO(axi4s.Slave(cfg.inputCfg)).suggestName(s"${instanceName}_sourceTask")
              val sinkResult =
                IO(axi4s.Master(cfg.outputCfg)).suggestName(s"${instanceName}_sinkResult")
              val m_axi =
                IO(axi4.Master(cfg.axiCfg)).suggestName(s"${instanceName}_m_axi")

              sourceTask.asLite :=> dut.sourceTask.asLite
              dut.sinkResult.asFull :=> sinkResult.asFull
              dut.m_axi :=> m_axi.asFull

              PeRwPort(name, index, sourceTask, Some(sinkResult), m_axi)

            case ("write", "single") =>
              val cfg = WriteSingle_Config(dataWidth = req.portWidth)
              val dut = Module(new WriteSingle_Basic(cfg))

              val sourceTask =
                IO(axi4s.Slave(cfg.inputCfg)).suggestName(s"${instanceName}_sourceTask")
              val m_axi =
                IO(axi4.Master(cfg.axiCfg)).suggestName(s"${instanceName}_m_axi")

              sourceTask.asLite :=> dut.sourceTask.asLite
              dut.m_axi :=> m_axi.asFull

              PeRwPort(name, index, sourceTask, None, m_axi)

            case other =>
              throw new Exception(s"Unsupported rwRequest for subPE $name: $other")
          }
        }
      }
    }

  val axiMasters: Seq[axi4.full.Interface] =
    rwPorts.map(_.m_axi.asFull)
}
