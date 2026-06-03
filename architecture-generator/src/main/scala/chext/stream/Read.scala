package chext.stream

import chisel3._
import chisel3.util._
import chisel3.experimental.prefix

import chext.{elasticnew => elastic}
import elastic.ConnectOp._

import chext.amba.axi4
import axi4.Ops._

abstract sealed case class ReadResultMode(val desc: String)

object ReadResultMode {

  /** For a given task of length `N > 0`, output `N` result tokens, all of which are valid and the
    * last one is marked. If there is an empty task, output nothing.
    */
  object DropEmpty extends ReadResultMode("DropEmpty")

  /** For a given task of length `N`, output `N + 1` result tokens, the last of which is always
    * invalid. An empty task results in a single invalid last token.
    */
  object LastAlwaysInvalid extends ReadResultMode("LastAlwaysInvalid")

  /** For a given task of length `N > 0`, output `N` result tokens, all of which are valid and the
    * last one is marked. If there is an empty task, only output a single invalid last token.
    */
  object LastSometimesInvalid extends ReadResultMode("LastSometimesInvalid")
}

case class ReadConfig[Tuser <: Data](
    val axiCfg: axi4.Config,
    val genUser: Tuser = UInt(0.W),
    val resultMode: ReadResultMode = ReadResultMode.LastAlwaysInvalid,
    val maxBurstLength: Int = 256,
    val wLength: Int = 32,
    val numOutstandingTasks: Int = 8
) {
  require(!axiCfg.lite)
  require(axiCfg.read)

  if (axiCfg.axi3Compat)
    require(maxBurstLength <= 16, "maxBurstLength <= 16")
  else
    require(maxBurstLength <= 256, "maxBurstLength <= 256")

  val genTask = new Task(genUser, axiCfg.wAddr, wLength)
  val genResult =
    if (resultMode == ReadResultMode.LastSometimesInvalid)
      new ReadResult_LastValid(this)
    else
      new ReadResult_Last(this)

  // used by the Read0
  private[stream] val genResult0 = new ReadResult_Last(this)
}

abstract class ReadResult[Tuser <: Data](cfg: ReadConfig[Tuser]) extends Bundle {
  val data = UInt(cfg.axiCfg.wData.W)
  val index = UInt(cfg.wLength.W)

  def last: Bool
  def valid: Bool

  val user = cfg.genUser.cloneType
}

private class ReadResult_Last[Tuser <: Data](cfg: ReadConfig[Tuser]) extends ReadResult(cfg) {
  val last = Bool()
  def valid: Bool = throw new NoSuchElementException(
    f"ReadResult_Last: no valid field for ${cfg.resultMode}"
  )
}

private class ReadResult_LastValid[Tuser <: Data](cfg: ReadConfig[Tuser]) extends ReadResult(cfg) {
  val last = Bool()
  val valid = Bool()
}

private class Read0[Tuser <: Data](val cfg: ReadConfig[Tuser]) extends Module {
  import cfg._

  val sourceTask = IO(elastic.Source(genTask))
  val sinkResult = IO(elastic.Sink(genResult0: ReadResult[Tuser]))

  val m_axi = IO(axi4.full.Master(axiCfg))

  {
    val taskFiltered = Wire(elastic.Interface(genTask))

    val taskAR = Wire(elastic.Interface(genTask))
    val taskR = Wire(elastic.Interface(genTask))

    checkAlignment(sourceTask, axiCfg, "Read")

    val drop0 = new elastic.Drop(sourceTask, taskFiltered) {
      cond { in.length === 0.U }

      out := in
    }

    val fork0 = new elastic.Fork(taskFiltered) {
      fork() :=> taskAR
      fork() :=> taskR
    }

    prefix("ar") {
      val chunk0 = Module(
        new Chunk(
          ChunkConfig(
            wAddress = cfg.axiCfg.wAddr,
            wLength = cfg.wLength,
            wData = axiCfg.wData,
            maxBurstLength = cfg.maxBurstLength
          )
        )
      )

      val transform0 =
        new elastic.Transform(taskAR, elastic.SinkBuffer(chunk0.source, numOutstandingTasks)) {
          out.address := in.address
          out.length := in.length
          out.user := 0.U
        }

      val transform1 = new elastic.Transform(chunk0.sink, m_axi.ar) {
        out := 0.U.asTypeOf(out)

        out.addr := in.address
        out.len := in.length -% 1.U
        out.size := (log2Ceil(axiCfg.wData) - 3).U
        out.burst := axi4.BurstType.INCR
      }
    }

    prefix("r") {
      val genLengthUser = new Bundle {
        val length = UInt(wLength.W)
        val user = genUser.cloneType
      }

      val genIndexLastUser = new Bundle {
        val index = UInt(wLength.W)
        val last = Bool()
        val user = genUser.cloneType
      }

      val wireLengthUser = Wire(elastic.Interface(genLengthUser))
      val wireIndexLastUser = Wire(elastic.Interface(genIndexLastUser))

      val transform0 =
        new elastic.Transform(taskR, elastic.SinkBuffer(wireLengthUser, numOutstandingTasks)) {
          out.length := in.length
          out.user := in.user
        }

      val repeat0 = new elastic.Repeat(
        wireLengthUser,
        wireIndexLastUser,
        wLength
      ) {
        len { _.length }

        outExplicit { (in, index, _, last, out) =>
          out.index := index
          out.last := last
          out.user := in.user
        }
      }

      val join0 = new elastic.Join(sinkResult) {
        val r = join(m_axi.r)
        val indexLastUser = join(wireIndexLastUser)

        out.data := r.data
        out.index := indexLastUser.index
        out.last := indexLastUser.last
        out.user := indexLastUser.user
      }
    }

    if (axiCfg.write) {
      m_axi.aw.noenq()
      m_axi.w.noenq()
      m_axi.b.nodeq()
    }
  }
}

final class Read[Tuser <: Data](val cfg: ReadConfig[Tuser]) extends Module {
  import cfg._

  val sourceTask = IO(elastic.Source(genTask))
  val sinkResult = IO(elastic.Sink(genResult))

  val m_axi = IO(axi4.full.Master(axiCfg))

  private val read0 = Module(new Read0(cfg))
  read0.m_axi :=> m_axi

  if (resultMode == ReadResultMode.DropEmpty) { //
    sourceTask :=> read0.sourceTask
    read0.sinkResult :=> sinkResult
  } else if (resultMode == ReadResultMode.LastAlwaysInvalid) {
    val wireLength = Wire(elastic.Interface(UInt(wLength.W)))
    val wireTask = Wire(elastic.Interface(genTask))

    val fork0 = new elastic.Fork(sourceTask) {
      fork { in.length } :=> wireLength
      fork() :=> wireTask
      fork() :=> read0.sourceTask
    }

    val wireSelect = Wire(elastic.Interface(UInt(1.W)))

    val wireSource0 = Wire(elastic.Interface(genResult))
    val wireSource1 = Wire(elastic.Interface(genResult))

    val repeat0 =
      new elastic.Repeat(
        elastic.SourceBuffer(wireLength, numOutstandingTasks),
        wireSelect,
        wLength + 1 /* to avoid overflows */
      ) {
        len { _ +& 1.U }
        out { (_, _, _, last) => Mux(last, 1.U, 0.U) }
      }

    val transform0 =
      new elastic.Transform(read0.sinkResult, wireSource0) {
        out := in

        // last is generated by the other branch
        out.last := false.B
      }

    val transform1 =
      new elastic.Transform(wireTask, wireSource1) {
        out.data := 0.U // invalid
        out.index := in.length
        out.last := true.B
        out.user := in.user
      }

    val mux0 = elastic.Mux(Seq(wireSource0, wireSource1), sinkResult, wireSelect)

  } else if (resultMode == ReadResultMode.LastSometimesInvalid) {
    val wireLength = Wire(elastic.Interface(UInt(wLength.W)))
    val wireTask = Wire(elastic.Interface(genTask))

    val fork0 = new elastic.Fork(sourceTask) {
      fork { in.length } :=> wireLength
      fork() :=> wireTask
      fork() :=> read0.sourceTask
    }

    val wireSelect = Wire(elastic.Interface(UInt(1.W)))

    val wireSource0 = Wire(elastic.Interface(genResult))
    val wireSource1 = Wire(elastic.Interface(genResult))

    val repeat0 =
      new elastic.Repeat(
        elastic.SourceBuffer(wireLength, numOutstandingTasks),
        wireSelect,
        wLength
      ) {
        len { (in) => Mux(in > 0.U, in, 1.U) }
        out { (in, _, _, last) => Mux(in > 0.U, 0.U, 1.U) }
      }

    val transform0 =
      new elastic.Transform(read0.sinkResult, wireSource0) {
        out.data := in.data
        out.index := in.index
        out.last := in.last
        out.valid := true.B
        out.user := in.user
      }

    val drop0 =
      new elastic.Drop(wireTask, elastic.SinkBuffer(wireSource1, numOutstandingTasks)) {
        cond { in.length > 0.U }

        out.data := 0.U // invalid
        out.index := in.length
        out.last := true.B
        out.valid := false.B
        out.user := in.user
      }

    val mux0 = elastic.Mux(Seq(wireSource0, wireSource1), sinkResult, wireSelect)

  } else
    throw new IllegalArgumentException(
      "stream.Read: Incorrect result mode. Valid ones: ReadResultMode.{DropEmpty, LastAlwaysInvalid, LastSometimesInvalid}"
    )
}
