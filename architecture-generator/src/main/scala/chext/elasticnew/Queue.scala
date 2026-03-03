package chext.elasticnew

import chisel3._
import chisel3.util.DecoupledIO
import chisel3.util.ReadyValidIO

import chisel3.experimental.SourceInfo
import chisel3.experimental.AffectsChiselPrefix
import chisel3.experimental.requireIsChiselType
import chisel3.experimental.requireIsHardware
import chisel3.experimental.skipPrefix

import chisel3.util.log2Ceil

import chext.elasticnew.{tracking => enTracking}
import enTracking.Component

import ConnectOp._

private object memory_impl {
  trait Memory {
    val count: Int
    val addrWidth: Int
    val dataWidth: Int

    def noRead(): Unit
    def read(addr: UInt): UInt

    def noWrite(): Unit
    def write(addr: UInt, data: UInt): Unit
  }

  class chisel_mem_1w1r(
      val count: Int,
      val addrWidth: Int,
      val dataWidth: Int
  ) extends AffectsChiselPrefix
      with Memory {
    private val mem = Mem(count, UInt(dataWidth.W))

    val addrA = Wire(UInt(addrWidth.W))
    val writeEnA = Wire(Bool())
    val dataInA = Wire(UInt(dataWidth.W))

    val addrB = Wire(UInt(addrWidth.W))
    val dataOutB = mem(addrB)

    when(writeEnA) {
      mem(addrA) := dataInA
    }

    def noRead(): Unit = {
      addrB := DontCare
    }

    def read(addr: UInt): UInt = {
      addrB := addr
      dataOutB
    }

    def noWrite(): Unit = {
      addrA := DontCare
      writeEnA := false.B
      dataInA := DontCare
    }

    def write(addr: UInt, data: UInt): Unit = {
      addrA := addr
      writeEnA := true.B
      dataInA := data
    }
  }

  class no_data_mem(
      val count: Int,
      val addrWidth: Int,
      val dataWidth: Int
  ) extends Memory {
    require(dataWidth == 0, "no_data_mem needs dataWidth == 0")

    def noRead(): Unit = {}
    def read(addr: UInt): UInt = 0.U
    def noWrite(): Unit = {}
    def write(addr: UInt, data: UInt): Unit = {}
  }

  class single_elem_mem(
      val count: Int,
      val addrWidth: Int,
      val dataWidth: Int
  ) extends Memory
      with AffectsChiselPrefix {
    require(count == 1, "single_elem_mem needs count == 1")

    val mem = RegInit(0.U(dataWidth.W))

    def noRead(): Unit = {}
    def read(addr: UInt): UInt = mem
    def noWrite(): Unit = {}
    def write(addr: UInt, data: UInt): Unit = {
      mem := data
    }
  }
}

object Queue {
  def between[T <: Data](
      source: ReadyValidIO[T],
      sink: ReadyValidIO[T],
      count: Int,
      pipe: Boolean = false,
      flow: Boolean = false,
      useSyncReadMem: Boolean = false
  )(implicit si: SourceInfo): Unit = {
    requireIsHardware(source, "Queue source must be hardware.")
    requireIsHardware(sink, "Queue sink must be hardware.")
    require(count >= 0, "Length must be non-negative.")

    if (count == 0) {
      source :=> sink
    } else {
      val gen = chiselTypeOf(source.bits)
      val queue = skipPrefix { new Queue(gen, count, pipe, flow, useSyncReadMem) }
      source :=> queue.source
      queue.sink :=> sink
    }
  }

  def apply[T <: Data](
      gen: T,
      count: Int,
      pipe: Boolean = false,
      flow: Boolean = false,
      useSyncReadMem: Boolean = false
  )(implicit si: SourceInfo): Queue[T] = {
    require(count > 0, "Length must be positive.")
    new Queue(gen, count, pipe, flow, useSyncReadMem)
  }
}

class Queue[T <: Data](
    val gen: T,
    val count: Int,
    val pipe: Boolean = false,
    val flow: Boolean = false,
    val useSyncReadMem: Boolean = false
)(implicit si_ : SourceInfo)
    extends Component {
  val source = EWire(gen)
  val sink = EWire(gen)

  addSourcePort("source", source)
  addSinkPort("sink", sink)

  val sourceInfo: SourceInfo = si_
  def tpe: String = "Queue"
  def namePrefix: String = "queue"

  require(count > -1, "Queue must have non-negative count.")
  require(count != 0, "Use companion object Queue.apply for empty queue.")
  requireIsChiselType(gen)

  {
    dontTouch(source)
    dontTouch(sink)

    val wAddr = log2Ceil(count)
    val wData = gen.getWidth

    val ram =
      (wData, wAddr) match {
        case (0, _) => new memory_impl.no_data_mem(count, wAddr, wData)
        case (_, 0) => new memory_impl.single_elem_mem(count, wAddr, wData)
        case (_, _) => new memory_impl.chisel_mem_1w1r(count, wAddr, wData)
      }

    ram.noRead()
    ram.noWrite()

    val enq_ptr = chisel3.util.Counter(count)
    val deq_ptr = chisel3.util.Counter(count)
    val maybe_full = RegInit(false.B)
    val ptr_match = enq_ptr.value === deq_ptr.value
    val empty = ptr_match && !maybe_full
    val full = ptr_match && maybe_full
    val do_enq = WireDefault(source.fire)
    val do_deq = WireDefault(sink.fire)

    when(do_enq) {
      ram.write(enq_ptr.value, source.bits.asUInt)
      enq_ptr.inc()
    }

    when(do_deq) {
      deq_ptr.inc()
    }

    when(do_enq =/= do_deq) {
      maybe_full := do_enq
    }

    sink.valid := !empty
    source.ready := !full

    sink.bits := ram.read(deq_ptr.value).asTypeOf(sink.bits)

    if (flow) {
      when(source.valid) { sink.valid := true.B }
      when(empty) {
        sink.bits := source.bits
        do_deq := false.B
        when(sink.ready) { do_enq := false.B }
      }
    }

    if (pipe) {
      when(sink.ready) { source.ready := true.B }
    }
  }
}
