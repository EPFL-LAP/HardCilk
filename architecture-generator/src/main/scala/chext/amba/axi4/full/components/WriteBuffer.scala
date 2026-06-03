package chext.amba.axi4.full.components

import chisel3._
import chisel3.util._

import chext.{elasticnew => elastic}
import chext.amba.axi4

case class WriteBufferConfig(
    val bufLengthW: Int = 64,
    val bufLengthAW: Int = 2
)

class WriteBuffer(val axiCfg: axi4.Config, val cfg: WriteBufferConfig) extends Module {
  import cfg._
  private implicit val _axiCfg: axi4.Config = axiCfg

  require(bufLengthW >= 1)
  require(bufLengthAW >= 1)
  require(axiCfg.write && !axiCfg.lite)

  val source = IO(new Bundle {
    val aw = elastic.Source(new axi4.full.WriteAddressChannel)
    val w = elastic.Source(new axi4.full.WriteDataChannel)
  })

  val sink = IO(new Bundle {
    val aw = elastic.Sink(new axi4.full.WriteAddressChannel)
    val w = elastic.Sink(new axi4.full.WriteDataChannel)
  })

  private val ctrAddr = Module(new chext.util.Counter(bufLengthW + 1))

  ctrAddr.noDec()
  ctrAddr.noInc()

  // only when the last write packet arrives, we care about or modify the AW counter
  private val stall0 = new elastic.Stall(source.w, elastic.SinkBuffer(sink.w, bufLengthW)) {
    out := in

    cond { in.last && ctrAddr.full }
    fire {
      when(in.last) { ctrAddr.inc() }
    }
  }

  private val stall1 = new elastic.Stall(elastic.SourceBuffer(source.aw, bufLengthAW), sink.aw) {
    out := in

    cond { ctrAddr.zero }
    fire { ctrAddr.dec() }
  }
}

object WriteBuffer {
  def apply(
      master: axi4.full.Interface,
      slave: axi4.full.Interface,
      cfg: WriteBufferConfig = WriteBufferConfig()
  ) = {
    require(master.cfg == slave.cfg)

    val writeBuffer = Module(new WriteBuffer(axiCfg = master.cfg, cfg))

    import axi4.full.WriteAddressChannel
    import elastic.ConnectOp._

    master.ar :=> slave.ar
    slave.r :=> master.r

    master.aw :=> writeBuffer.source.aw
    writeBuffer.sink.aw :=> slave.aw.asInstanceOf[elastic.Interface[WriteAddressChannel]]

    master.w :=> writeBuffer.source.w
    writeBuffer.sink.w :=> slave.w

    slave.b :=> master.b
  }
}
