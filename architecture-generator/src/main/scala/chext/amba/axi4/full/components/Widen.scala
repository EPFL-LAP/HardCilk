package chext.amba.axi4.full.components

import chisel3._
import chisel3.util.log2Ceil

import chext.elastic
import elastic.ConnectOp._

import chext.amba.axi4

import chisel3.experimental.AffectsChiselPrefix
import chisel3.experimental.prefix
import scala.annotation.nowarn

case class WidenConfig(
    val axiCfg: axi4.Config
) {
  require(!axiCfg.lite, "Widen requires an AXI4-Full interface.")
}

/** Converts narrow transfers to full-sized transfers.
  *
  * @note
  *   Does not work for FIXED bursts.
  */
class Widen(val cfg: WidenConfig) extends Module {
  import cfg._

  val s_axi = IO(axi4.full.Slave(axiCfg))
  val m_axi = IO(axi4.full.Master(axiCfg))

  private class Control extends Bundle {
    val beatFirst = Bool()
    val beatLast = Bool()

    val transferFirst = Bool()
    val transferLast = Bool()
  }

  private val genControl = new Control

  /*
  private def alignDown(x: UInt, size: UInt, width: Int) = {
    x & ~((size - 1.U).pad(width))
  }

  private def alignUp(x: UInt, size: UInt, width: Int) = {
    alignDown(x + (size - 1.U), size, width)
  }
   */

  /** Transforms the address packet to match the target AxSIZE.
    */
  private def transformAx(
      source: elastic.Interface[axi4.full.AddressChannel],
      sink: chisel3.util.IrrevocableIO[axi4.full.AddressChannel],
      size: Int
  ): AffectsChiselPrefix = {
    // calculates the size of the master-side AXI transaction
    // Please check Transaction.hpp, simpleRead to understand the logic
    new elastic.Transform(source, sink) {
      protected def onTransform: Unit = {
        out := in

        val mask0 = WireInit((1.U << in.size) - 1.U)
        val mask1 = WireInit(((1 << size) - 1).U)

        // .pad is necessary, otherwise the negated signal is extended with zeros,
        // corrupting the address
        // HARDCODED: addr(7, 0) and .pad(8)
        val addr0 = WireInit(in.addr(7, 0) & ~(mask0.pad(8)))
        val addr1 = WireInit(in.addr(7, 0) & ~(mask1.pad(8)))

        // aligned transfer size
        // pad(9) and pad(17) to avoid overflows/underflows
        val dtsize =
          ((in.len.pad(9) + 1.U) << in.size).pad(17) +% addr0 -% addr1

        val len0 = WireInit(dtsize >> size.U)
        val len1 = WireInit(Mux((dtsize & mask1) > 0.U, len0 +% 1.U, len0))

        out.size := size.U
        out.len := len1 -% 1.U

        // left here for debugging purposes
        dontTouch(mask0)
        dontTouch(mask1)
        dontTouch(addr0)
        dontTouch(addr1)
        dontTouch(dtsize)
        dontTouch(len0)
        dontTouch(len1)
      }
    }
  }

  private def generateControl(
      source: elastic.Interface[axi4.full.AddressChannel],
      sink: elastic.Interface[Control],
      size: Int
  ) = prefix("control") {
    val gen0 = new Bundle {
      val index = UInt(7.W)
      val size = UInt(3.W)

      val first = Bool()
      val last = Bool()
    }

    val wire0 = Wire(elastic.Interface(gen0))

    @nowarn("cat=unused")
    val transducer0 = new elastic.Transducer(source, wire0) {
      val state = RegInit(false.B)

      val mask = WireInit((1.U << (size.U - in.size)) -% 1.U)

      val index = RegInit(0.U(7.W))
      val nextIndex = Wire(chiselTypeOf(index))

      val counter = RegInit(0.U(8.W))
      val nextCounter = Wire(chiselTypeOf(counter))

      val first = Wire(Bool())
      val last = Wire(Bool())

      nextIndex := Mux(
        state,
        Mux(
          (index & mask) === mask,
          0.U,
          index + 1.U
        ),
        (in.addr >> in.size) & mask
      )

      nextCounter := Mux(
        last,
        0.U,
        counter + 1.U
      )

      first := !state
      last := Mux(
        state,
        counter === in.len,
        in.len === 0.U
      )

      out.index := nextIndex
      out.size := in.size

      out.first := first
      out.last := last

      packet {
        when(state) {
          when(last) {
            accept {
              state := false.B

              index := nextIndex
              counter := nextCounter
            }
          }.otherwise {
            produce {
              index := nextIndex
              counter := nextCounter
            }
          }
        }.otherwise {
          when(last) {
            accept {
              index := nextIndex
              counter := nextCounter
            }
          }.otherwise {
            produce {
              state := true.B

              index := nextIndex
              counter := nextCounter
            }
          }
        }
      }
    }

    dontTouch(wire0)

    @nowarn("cat=unused")
    val transform0 = new elastic.Transform(wire0, sink) {
      protected def onTransform: Unit = {
        val mask = WireInit((1.U << (size.U -% in.size)) - 1.U)

        out.beatFirst := in.first || ((in.index & mask) === 0.U)
        out.beatLast := in.last || ((in.index & mask) === mask)

        out.transferFirst := in.first
        out.transferLast := in.last
      }
    }
  }

  private def implRead() = prefix("read") {
    val wireControl = Wire(elastic.Interface(genControl))
    dontTouch(wireControl)

    @nowarn("cat=unused")
    val fork0 = new elastic.Fork(s_axi.ar) {
      override protected def onFork: Unit = {
        val transform0 =
          transformAx(fork(), m_axi.ar, log2Ceil(axiCfg.wData) - 3)
        generateControl(fork(), wireControl, log2Ceil(axiCfg.wData) - 3)
      }
    }

    @nowarn("cat=unused")
    val fork1 = new elastic.Fork(elastic.SourceBuffer(wireControl, 64)) {
      fork1 =>
      protected def onFork: Unit = {
        val genData = chiselTypeOf(m_axi.r.bits)

        val wireMuxSink = Wire(elastic.Interface(genData))
        val wireDemuxSource = Wire(elastic.Interface(genData))
        val wireDemuxSink = Wire(elastic.Interface(genData))

        val temp = Wire(elastic.Interface(genData))

        val mux0 = elastic.Mux(
          Seq(chext.elastic.SourceBuffer(temp), m_axi.r),
          wireMuxSink,
          fork { in.beatFirst }
        )

        val demux0 = elastic.Demux(
          wireDemuxSource,
          Seq(temp, wireDemuxSink),
          fork { in.beatLast }
        )

        val fork0 = new elastic.Fork(wireMuxSink) {
          protected def onFork: Unit = {
            fork() :=> wireDemuxSource

            val join0 = new elastic.Join(s_axi.r) {
              protected def onJoin: Unit = {
                out := join(fork())
                out.last := join(fork1.fork()).transferLast
              }
            }
          }
        }

        wireDemuxSink.deq() // disposed
      }
    }
  }

  private def implWrite() = prefix("write") {
    // val transform0 = transformAx(s_axi.aw, m_axi.aw, log2Ceil(axiCfg.wData) - 3)

    // fix later:
    s_axi.aw :=> m_axi.aw
    s_axi.w :=> m_axi.w
    m_axi.b :=> s_axi.b
  }

  if (axiCfg.read)
    implRead()

  if (axiCfg.write)
    implWrite()

}

object WidenApp extends App {
  emitVerilog({
    val wAddr = 14
    val wData = 64

    val axiCfg = axi4.Config(0, wAddr, wData)

    val widenCfg = WidenConfig(axiCfg)
    new Widen(widenCfg)
  })
}
