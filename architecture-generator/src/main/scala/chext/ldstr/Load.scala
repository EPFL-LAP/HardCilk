package chext.ldstr

import chisel3._
import chisel3.util._
import chisel3.experimental.prefix

import chext.{elasticnew => elastic}
import elastic.ConnectOp._

import chext.amba.axi4
import axi4.Ops._

case class LoadConfig[Tuser <: Data](
    val axiCfg: axi4.Config,
    val genUser: Tuser = UInt(0.W),
    val numOutstandingTasks: Int = 8
) {
  require(!axiCfg.lite)
  require(axiCfg.read)

  val genTask = new Task(genUser, axiCfg.wAddr)
  val genResult = new LoadResult(this)
}

class LoadResult[Tuser <: Data](cfg: LoadConfig[Tuser]) extends Bundle {
  val data = UInt(cfg.axiCfg.wData.W)
  val user = cfg.genUser.cloneType
}

class Load[Tuser <: Data](val cfg: LoadConfig[Tuser]) extends Module {
  import cfg._

  val sourceTask = IO(elastic.Source(genTask))
  val sinkResult = IO(elastic.Sink(genResult))

  val m_axi = IO(axi4.full.Master(axiCfg))

  {
    val taskAR = Wire(elastic.Interface(genTask))
    val taskR = Wire(elastic.Interface(genTask))

    checkAlignment(sourceTask, axiCfg, "Load")

    val fork0 = new elastic.Fork(sourceTask) {
      fork() :=> taskAR
      fork() :=> taskR
    }

    prefix("ar") {
      val transform0 = new elastic.Transform(taskAR, m_axi.ar) {
        out := 0.U.asTypeOf(out)

        out.addr := in.address
        out.len := 0.U
        out.size := (log2Ceil(axiCfg.wData) - 3).U
        out.burst := axi4.BurstType.INCR
      }
    }

    prefix("r") {
      val wireUser = Wire(elastic.Interface(genUser))

      val transform0 =
        new elastic.Transform(taskR, elastic.SinkBuffer(wireUser, numOutstandingTasks)) {
          out := in.user
        }

      val join0 = new elastic.Join(sinkResult) {
        out.data := join(m_axi.r).data
        out.user := join(wireUser)
      }
    }

    if (axiCfg.write) {
      m_axi.aw.noenq()
      m_axi.w.noenq()
      m_axi.b.nodeq()
    }
  }
}
