package Util

import chisel3._

import chext.{elasticnew => e}
import e.ConnectOp._

import chext.amba.axi4
import axi4.Ops._

import chisel3.util.isPow2
import chisel3.util.log2Ceil
import chext.amba.axi4.full.components.helpers.SteerRight
import chext.exportIO

case class AxiPageBoundarySplitter_Config(
    alignmentBits: Int = 12,
    addressWidth: Int = 64,
    dataWidth: Int = 32,
    idWidth: Int = 4,
    numberOfOutstanding: Int = 8
) {
  require(isPow2(dataWidth), "Data width must be power of 2")
  require(isPow2(addressWidth), "Address width must be power of 2")

  def moduleSuffix: String =
    s"${addressWidth}_${dataWidth}_${alignmentBits}_${idWidth}_${numberOfOutstanding}"

  val axiCfg = axi4.Config(wAddr = addressWidth, wData = dataWidth, wId = idWidth)

}

class AW_Bool_Len(awType: axi4.full.AddressChannel) extends Bundle {
  val aw = awType.cloneType
  val drop_ack = Bool()
  val len = UInt(9.W)
}

class B_bool(bType: axi4.full.WriteResponseChannel) extends Bundle {
  val b = bType.cloneType
  val drop_ack = Bool()
}

class AxiPageBoundarySplitter_Basic(cfg: AxiPageBoundarySplitter_Config)
    extends Module {
  import cfg._

  override def desiredName: String =
    s"AxiPageBoundarySplitter_Basic_${cfg.moduleSuffix}"

  val s_axi = IO(axi4.Slave(axiCfg))
  val m_axi = IO(axi4.Master(axiCfg))

  val size_queue_ar = new e.Queue(
    UInt(9.W),
    count = numberOfOutstanding + 1,
    pipe = false,
    flow = false
  )

  val size_queue_aw = new e.Queue(
    UInt(9.W),
    count = 2 * numberOfOutstanding + 1,
    pipe = false,
    flow = false
  )

  val bool_queue_b = new e.Queue(
    Bool(),
    count = 2 * numberOfOutstanding + 1,
    pipe = false,
    flow = false
  )

  val ar_path = new e.Fork(s_axi.asFull.ar) {
    val check_align = new e.Transducer(fork(), m_axi.asFull.ar) {
      val mask = ~((1.U(addressWidth.W) << in.size) - 1.U)
      val addr_low_bits = in.addr(alignmentBits - 1, 0) & mask
      val addr_end_low_bits =
        (addr_low_bits + ((in.len + 1.U(9.W)) << in.size) - 1.U) & ((1.U(
          addressWidth.W
        ) << alignmentBits) - 1.U)
      val crossing = addr_end_low_bits < addr_low_bits

      val sent_len = RegInit(0.U(9.W))

      out := in
      packet {
        when(crossing) {
          when(sent_len === 0.U) {
            val first_len =
              ((1.U << alignmentBits) - addr_low_bits) >> in.size
            out.len := first_len - 1.U
            produce { sent_len := first_len }
          }.otherwise {
            val rem_len = in.len - sent_len
            out.addr := (in.addr + (sent_len.pad(
              addressWidth
            ) << in.size)) & mask
            out.len := rem_len
            accept { sent_len := 0.U }
          }
        }.otherwise {
          accept { sent_len := 0.U }
        }
      }
    }
    val size_enqueue = new e.Transform(fork(), size_queue_ar.source) {
      out := in.len + 1.U(9.W)
    }
  }

  // Create the AW_Bool bundle type and EWire
  val aw_bool_len_wire =
    e.EWire(new AW_Bool_Len(chiselTypeOf(m_axi.asFull.aw.bits)))

  val aw_path = new e.Transducer(s_axi.asFull.aw, aw_bool_len_wire) {
    val mask = ~((1.U(addressWidth.W) << in.size) - 1.U)
    val addr_low_bits = in.addr(alignmentBits - 1, 0) & mask
    val addr_end_low_bits =
      (addr_low_bits + ((in.len + 1.U) << in.size) - 1.U) & ((1.U(
        addressWidth.W
      ) << alignmentBits) - 1.U)
    val crossing = addr_end_low_bits < addr_low_bits

    val sent_len = RegInit(0.U(9.W))

    out.aw := in
    packet {
      when(crossing) {
        when(sent_len === 0.U) {
          val first_len =
            ((1.U << alignmentBits) - addr_low_bits) >> in.size
          out.aw.len := first_len - 1.U
          out.drop_ack := true.B
          out.len := first_len
          produce { sent_len := first_len }
        }.otherwise {
          val rem_len = in.len - sent_len
          out.aw.addr := (in.addr + (sent_len.pad(
            addressWidth
          ) << in.size)) & mask
          out.aw.len := rem_len
          out.drop_ack := false.B
          out.len := rem_len + 1.U(9.W)
          accept { sent_len := 0.U }
        }
      }.otherwise {
        out.drop_ack := false.B
        out.len := in.len + 1.U(9.W)
        accept { sent_len := 0.U }
      }
    }
  }

  val aw_bool_len = new e.Fork(aw_bool_len_wire) {
    val size_enqueue = new e.Transform(fork(), size_queue_aw.source) {
      out := in.len
    }
    val aw_channel = new e.Transform(fork(), m_axi.asFull.aw) {
      out := in.aw
    }
    val b_bool_enqueue = new e.Transform(fork(), bool_queue_b.source) {
      out := in.drop_ack
    }
  }
  val repeat_r = e.EWire(Bool())
  val repeater_r = new e.Repeat(size_queue_ar.sink, repeat_r, 9) {
    len { (in) => in }
    out { (in, index, first, last) => last }
  }
  val join_r = new e.Join(s_axi.asFull.r) {
    out := join(m_axi.asFull.r)
    val last = join(repeat_r)
    when(last) {
      out.last := true.B
    }.otherwise {
      out.last := false.B
    }
  }

  val repeat_w = e.EWire(Bool())
  val repeater_w = new e.Repeat(size_queue_aw.sink, repeat_w, 9) {
    len { (in) => in }
    out { (in, index, first, last) => last }
  }
  val join_w = new e.Join(m_axi.asFull.w) {
    out := join(s_axi.asFull.w)
    val last = join(repeat_w)
    when(last) {
      out.last := true.B
    }.otherwise {
      out.last := false.B
    }
  }

  val b_with_drop = e.EWire(new B_bool(chiselTypeOf(m_axi.asFull.b.bits)))
  new e.Join(b_with_drop) {
    out.b := join(m_axi.asFull.b)
    out.drop_ack := join(bool_queue_b.sink)
  }
  val b_dropper = new e.Drop(b_with_drop, s_axi.asFull.b) {
    out := in.b
    cond { in.drop_ack }
  }
}

object AxiPageBoundarySplitter_Emit extends App {
  emitVerilog(
    new AxiPageBoundarySplitter_Basic(
      AxiPageBoundarySplitter_Config(dataWidth = 32)
    )
  )
}
