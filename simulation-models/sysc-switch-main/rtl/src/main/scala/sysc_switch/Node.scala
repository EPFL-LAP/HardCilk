import chisel3._
import chisel3.util._

import chext.amba.axi4s
import axi4s.Casts._

import chext.elastic
import elastic.ConnectOp._

import chext.util.BitOps._

// TODO add this to Chext
class Trigger(val count: Int = 1, val wCounter: Int = 32) extends Module {
  assert(count > 0)

  val sink = IO(elastic.Sink(UInt(0.W)))

  private val generated = RegInit(0.U(wCounter.W))

  when(generated === count.U) {
    sink.noenq()
  }.otherwise {
    sink.enq(0.U)

    when(sink.fire) {
      generated := generated +& 1.U
    }
  }
}

// TODO make this API public in Chext
object emitHdlinfo {
  def apply(module: hdlinfo.Module, targetDir: String): Unit = {
    import io.circe.syntax._
    import io.circe.generic.auto._
    import java.io.PrintWriter

    val pw = new PrintWriter(f"${targetDir}/${module.name}.hdlinfo.json")
    pw.write(module.asJson.toString())
    pw.close()
  }
}

case class NetworkNodeConfig() {
  val axisCfg = axi4s.Config(wData = 512, false, 0, 4, 0)

  def hdlinfoModule: hdlinfo.Module = {
    import hdlinfo._
    import io.circe.generic.auto._
    import scala.collection.mutable.ArrayBuffer

    val ports = ArrayBuffer.empty[Port]
    val interfaces = ArrayBuffer.empty[Interface]

    ports.append(
      Port(
        "clock",
        PortDirection.input,
        PortKind.clock,
        PortSensitivity.clockRising,
        associatedReset = "reset"
      )
    )

    ports.append(
      Port(
        "reset",
        PortDirection.input,
        PortKind.reset,
        PortSensitivity.resetActiveHigh,
        associatedClock = "clock"
      )
    )

    ports.append(
      Port(
        "nodeId",
        PortDirection.input,
        PortKind.data,
        PortSensitivity.none,
        true,
        (3, 0),
        associatedClock = "clock",
        associatedReset = "reset"
      )
    )

    interfaces.append(
      Interface(
        "s_axis",
        InterfaceRole("source"),
        InterfaceKind("readyValid[axis4_mFPGA]"),
        associatedClock = "clock",
        associatedReset = "reset"
      )
    )

    interfaces.append(
      Interface(
        "m_axis",
        InterfaceRole("sink"),
        InterfaceKind("readyValid[axis4_mFPGA]"),
        associatedClock = "clock",
        associatedReset = "reset"
      )
    )

    Module(
      "NetworkNode",
      ports.toSeq,
      interfaces.toSeq,
      Map()
    )
  }
}

class NetworkNode(cfg: NetworkNodeConfig) extends Module {
  import cfg._

  val s_axis = IO(axi4s.Slave(axisCfg))
  val m_axis = IO(axi4s.Master(axisCfg))

  val nodeId = IO(Input(UInt(4.W)))

  private val s_axis_ = s_axis.asFull
  private val m_axis_ = m_axis.asFull

  private val genPacket = chiselTypeOf(s_axis_.bits)

  private val newPacketQueue = Module(new Queue(genPacket, 32))
  private val recvdPacketQueue = Module(new Queue(genPacket, 32))

  elastic.BasicArbiter(
    Seq(newPacketQueue.io.deq, recvdPacketQueue.io.deq),
    m_axis_,
    elastic.Chooser.rr
  )

  private val trigger = Module(new Trigger)

  new elastic.Replicate(trigger.sink, newPacketQueue.io.enq, 8) {
    protected def onReplicate: Unit = {
      len := 8.U

      out.data := Cat(8.U(8.W), idx, 15.U(4.W), nodeId)
      out.strobe := (-1).S.asUInt
      out.keep := (-1).S.asUInt
      out.dest.get := idx.lsbN(3) // 8 boards
      out.last := false.B
    }
  }

  new elastic.Arrival(s_axis_, recvdPacketQueue.io.enq) {
    protected def onArrival: Unit = {
      val ttl = in.data(23, 16)
      val idx = in.data(15, 8)
      val nodeIdx = in.data(7, 0)

      out := in

      // make sure that TDEST is always valid
      out.dest.get := (in.dest.get + 1.U).lsbN(3)
      out.data := Cat(ttl - 1.U, idx, nodeIdx)

      when(ttl === 0.U) {
        drop()
      }.otherwise {
        accept()
      }
    }
  }
}

object Emit extends App {
  val cfg = NetworkNodeConfig()
  emitHdlinfo(cfg.hdlinfoModule, ".")
  emitVerilog(new NetworkNode(cfg))
}
