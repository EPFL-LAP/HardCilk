package chext.stream

import chisel3._
import chisel3.experimental.AffectsChiselPrefix

import chext.{elasticnew => elastic}

import chext.amba.axi4

case class ChunkConfig[Tuser <: Data](
    val wAddress: Int,
    val wLength: Int,
    val wData: Int,
    val maxBurstLength: Int,
    val genUser: Tuser = UInt(0.W)
) {
  val genSource = new ChunkTask(this)
  val genSink = new ChunkResult(this)
}

class ChunkTask[Tuser <: Data](val cfg: ChunkConfig[Tuser]) extends Bundle {
  val address = UInt(cfg.wAddress.W)
  val length = UInt(cfg.wLength.W)
  val user = cfg.genUser.cloneType
}

class ChunkResult[Tuser <: Data](val cfg: ChunkConfig[Tuser]) extends Bundle {
  val address = UInt(cfg.wAddress.W)
  val length = UInt(cfg.wLength.W)

  val first = Bool()
  val last = Bool()

  val user = cfg.genUser.cloneType
}

private class ChunkState(val cfg: ChunkConfig[_]) extends Bundle {
  val address = UInt(cfg.wAddress.W)
  val remaining = UInt(cfg.wLength.W)
}

/** Chunks and creates address packets.
  *
  * @param cfg
  * @param source
  * @param sink
  */
final class Chunk[Tuser <: Data](
    cfg: ChunkConfig[Tuser]
) extends Module {
  import cfg._

  val source = IO(elastic.Source(genSource))
  val sink = IO(elastic.Sink(genSink))

  private val count0 = new elastic.Count(source, sink, new ChunkState(cfg)) {
    val beatIncr = (wData / 8)

    def calcLength(state: ChunkState) =
      Mux(state.remaining >= maxBurstLength.U, maxBurstLength.U, state.remaining)

    initExplicit { //
      case (in, state) => {
        if (wAddress > 0) {
          val mask = (beatIncr - 1).U(wAddress.W)
          state.address := in.address & ~mask
        }
        else {
          state.address := 0.U
        }
        
        state.remaining := in.length
      }
    }

    cond { //
      case (_, state) => state.remaining =/= 0.U
    }

    nextExplicit { //
      case (_, state, stateNext) => {
        val length = calcLength(state)

        stateNext.address := state.address +% length * beatIncr.U
        stateNext.remaining := state.remaining - length
      }
    }

    outExplicit {
      case (in, state, first, last, out) => {
        out := 0.U.asTypeOf(out)

        out.address := state.address
        out.length := calcLength(state)

        out.first := first
        out.last := last

        out.user := in.user
      }
    }
  }
}
