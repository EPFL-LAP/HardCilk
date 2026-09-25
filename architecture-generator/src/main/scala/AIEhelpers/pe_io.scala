package aiehelpers

import chisel3._

import chext.{elasticnew => e}
import e.ConnectOp._

import chext.amba.{axi4 => a4}
import a4.Ops._
import a4.full.{components => a4fc}
import chext.amba.axi4s
import axi4s.Casts._

import Descriptors._

case class PeRwPort(
    subPEName: String,
    index: Int,
    sourceTask: axi4s.Interface,
    sinkResult: Option[axi4s.Interface],
    m_axi: a4.RawInterface
) {
  def instanceName: String = s"${subPEName}_${index}"
}

class PeIO(subPEs: Map[String, SubPEDescriptor], count: Int) extends Module {
  require(count >= 0, "count must be non-negative")

  private val internalRwPorts: Seq[PeRwPort] =
    (0 until count).flatMap { index =>
      subPEs.toSeq.sortBy(_._1).flatMap { case (name, descriptor) =>
        descriptor.rwRequest.map { req =>
          val instanceName = s"${name}_${index}"
          println(s"Generating rwPort for PE ${descriptor.peName}, subPE $name, index $index, type ${req.`type`}, mode ${req.mode}")

          (req.`type`, req.mode) match {
            case ("read", "single") =>
              val cfg = ReadSingle_Config(dataWidth = req.portWidth)
              val dut = Module(new ReadSingle_Basic(cfg))

              val sourceTask =
                IO(axi4s.Slave(cfg.inputCfg)).suggestName(s"${instanceName}_sourceTask")
              val sinkResult =
                IO(axi4s.Master(cfg.outputCfg)).suggestName(s"${instanceName}_sinkResult")
              val m_axi =
                Wire(a4.Master(cfg.axiCfg)).suggestName(s"${instanceName}_m_axi")

              sourceTask.asLite :=> dut.sourceTask.asLite
              dut.sinkResult.asFull :=> sinkResult.asFull
              dut.m_axi :=> m_axi.asFull

              PeRwPort(name, index, sourceTask, Some(sinkResult), m_axi)

            case ("read", "stream") =>
              val cfg = ReadStream_Config(dataWidth = req.portWidth)
              val dut = Module(new ReadStreamWSplitter_Basic(cfg))

              val sourceTask =
                IO(axi4s.Slave(cfg.inputCfg)).suggestName(s"${instanceName}_sourceTask")
              val sinkResult =
                IO(axi4s.Master(cfg.outputCfg)).suggestName(s"${instanceName}_sinkResult")
              val m_axi =
                Wire(a4.Master(cfg.axiCfg)).suggestName(s"${instanceName}_m_axi")

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
                Wire(a4.Master(cfg.axiCfg)).suggestName(s"${instanceName}_m_axi")

              sourceTask.asLite :=> dut.sourceTask.asLite
              dut.m_axi :=> m_axi.asFull

              PeRwPort(name, index, sourceTask, None, m_axi)

            case other =>
              throw new Exception(s"Unsupported rwRequest for subPE $name: $other")
          }
        }
      }
    }

  // Share memory access within each PE replica, while retaining every task/result port.
  private val groupedMasters = internalRwPorts
    .groupBy(port => (port.index, subPEs(port.subPEName).peName))
    .toSeq.sortBy(_._1)
    .map { case (key @ (index, peName), ports) =>
      val groupName = s"${peName}_${index}"
      val slaveCfg = ports.head.m_axi.cfg.copy(
        wData = ports.map(_.m_axi.cfg.wData).max
      )
      val muxCfg = a4fc.MuxConfig(axiSlaveCfg = slaveCfg, numSlaves = ports.size)
      val m_axi = IO(a4.Master(muxCfg.axiMasterCfg)).suggestName(s"${groupName}_m_axi")

      if (ports.size == 1) {
        ports.head.m_axi :=> m_axi.asFull
      } else {
        val memoryMux = Module(new a4fc.Mux(muxCfg)).suggestName(s"${groupName}_memoryMux")
        ports.zip(memoryMux.s_axi).foreach { case (port, slave) =>
          if (port.m_axi.cfg == slaveCfg) {
            port.m_axi :=> slave
          } else {
            val widthConverter = Module(new a4fc.ProtocolConverter(
              a4fc.ProtocolConverterConfig(
                axiSlaveCfg = port.m_axi.cfg,
                axiMasterCfg = slaveCfg
              )
            )).suggestName(s"${port.instanceName}_widthConverter")
            port.m_axi :=> widthConverter.s_axi
            widthConverter.m_axi :=> slave
          }
        }
        memoryMux.m_axi :=> m_axi.asFull
      }
      key -> m_axi
    }

  private val masterByPE = groupedMasters.toMap

  // Entries in the same PE replica refer to the same exposed AXI master.
  val rwPorts: Seq[PeRwPort] = internalRwPorts.map { port =>
    port.copy(m_axi = masterByPE((port.index, subPEs(port.subPEName).peName)))
  }

  val axiMasters: Seq[a4.full.Interface] =
    groupedMasters.map(_._2.asFull)
}
