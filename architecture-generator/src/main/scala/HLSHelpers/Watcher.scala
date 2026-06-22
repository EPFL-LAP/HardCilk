package HLSHelpers

import chisel3._
import chext.amba.axi4

import scala.collection.immutable.SeqMap

/** Blackbox for the free-running `watcher` telemetry kernel (memAccess.cpp).
  *
  * The generic [[VitisModuleFactory.parseVitisModule]] only recovers
  * `m_axi_gmem`, AXIS `*_TDATA` ports and the `ap_*` handshake, so it cannot see
  * the watcher's scalar status pins. We declare the port list explicitly here to
  * match the synthesized `watcher.v` (Vivado IP flow, NOT the vitis kernel flow,
  * so `ap_ctrl_none` + discrete `ap_none` pins survive).
  *
  * Observed `watcher.v` interface:
  *   - ap_clk, ap_rst_n
  *   - mem        [63:0]  input  -- m_axi base pointer (HLS offset=direct)
  *   - start_addr [63:0]  input  -- byte offset added inside the kernel
  *   - m_axi_gmem         master -- wId=1, wAddr=64, wData=512, wUser*=1, full AXI4
  *   - <statusPrefix>_in_<i>  [1:0] input  -- {bit1=full, bit0=empty} of the in queue
  *   - <statusPrefix>_out_<i> [1:0] input  -- {bit1=full, bit0=empty} of the out queue
  *
  * The gmem config is fixed (not parsed from the `.v`) so the top can elaborate
  * before the watcher is synthesized; the platform HBM adapter does the 512->256
  * width step. If the kernel's interface changes, re-synthesize and update here.
  *
  * @param monitored (statusPrefix, peCount) per monitored task, dynamic in PE count
  */
class WatcherBlackBox(
    val moduleName: String,
    val gmemCfg: axi4.Config,
    val addrWidth: Int,
    val monitored: Seq[(String, Int)]
) extends BlackBox {
  override def desiredName: String = moduleName

  def inPinName(statusPrefix: String, i: Int): String = s"${statusPrefix}_in_${i}"
  def outPinName(statusPrefix: String, i: Int): String = s"${statusPrefix}_out_${i}"

  private val statusPins: Seq[String] =
    monitored.flatMap { case (prefix, count) =>
      (0 until count).flatMap(i => Seq(inPinName(prefix, i), outPinName(prefix, i)))
    }

  val io = IO(new chisel3.Record {
    val elements: SeqMap[String, Data] = SeqMap.from(
      Seq(
        "ap_clk" -> Input(Clock()),
        "ap_rst_n" -> Input(Bool()),
        "mem" -> Input(UInt(addrWidth.W)),
        "start_addr" -> Input(UInt(addrWidth.W)),
        "m_axi_gmem" -> axi4.Master(gmemCfg)
      ) ++ statusPins.map(p => p -> Input(UInt(2.W)))
    )
  })

  def getPort(name: String): Data = io.elements.getOrElse(
    name,
    throw new RuntimeException(f"Watcher IO port not found: ${name}")
  )
}
