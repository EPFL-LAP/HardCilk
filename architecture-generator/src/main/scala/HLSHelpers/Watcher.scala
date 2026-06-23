package HLSHelpers

import chisel3._
import chext.amba.axi4

import scala.collection.immutable.SeqMap

/** Blackbox for the free-running `watcher` telemetry kernel (memAccess.cpp).
  *
  * The generic [[VitisModuleFactory.parseVitisModule]] only recovers
  * `m_axi_gmem`, AXIS `*_TDATA` ports and the `ap_*` handshake, so it cannot see
  * the watcher's scalar status / bandwidth pins. We declare the port list
  * explicitly here to match the synthesized `watcher.v` (Vivado IP flow, NOT the
  * vitis kernel flow, so `ap_ctrl_none` + discrete `ap_none` pins survive).
  *
  * Observed `watcher.v` interface:
  *   - ap_clk, ap_rst_n
  *   - mem        [63:0]  input  -- m_axi base pointer (HLS offset=direct)
  *   - start_addr [63:0]  input  -- byte offset added inside the kernel
  *   - start_gate [0:0]   input  -- 1 once the spawn scheduler dispatches its first
  *                                  task; the watcher stays idle until then
  *   - m_axi_gmem         master -- wId=1, wAddr=64, wData=256, wUser*=1, full AXI4
  *                                  (256-bit beat = two 128-bit telemetry bundles)
  *   - <statusPrefix>_in_<i>  [1:0] input  -- {bit1=ready, bit0=valid} of the in queue
  *   - <statusPrefix>_out_<i> [1:0] input  -- {bit1=ready, bit0=valid} of the out queue
  *   - bw_wbytes_<p> [7:0]   input  -- write bytes transferred this cycle on HBM port p
  *   - bw_rbytes_<p> [15:0]  input  -- read  bytes requested this cycle on HBM port p
  *   - bw_awaddr_<p> [19:0]  input  -- most-recent AW addr[63:44] on HBM port p (future)
  *   - bw_araddr_<p> [19:0]  input  -- most-recent AR addr[63:44] on HBM port p (future)
  *     (p = 0 .. maxHbmPorts-1)
  *
  * The gmem config is fixed (not parsed from the `.v`) so the top can elaborate
  * before the watcher is synthesized; the platform HBM adapter does the 256->256
  * pass-through. If the kernel's interface changes, re-synthesize and update here.
  *
  * @param monitored   (statusPrefix, peCount) per monitored task, dynamic in PE count
  * @param maxHbmPorts number of per-HBM-port bandwidth/address pin groups (kernel
  *                    MAX_HBM_PORTS); the actual exported compute ports are wired in
  *                    connectWatcher and any extra pins are tied to 0.
  */
class WatcherBlackBox(
    val moduleName: String,
    val gmemCfg: axi4.Config,
    val addrWidth: Int,
    val monitored: Seq[(String, Int)],
    val maxHbmPorts: Int
) extends BlackBox {
  override def desiredName: String = moduleName

  def inPinName(statusPrefix: String, i: Int): String = s"${statusPrefix}_in_${i}"
  def outPinName(statusPrefix: String, i: Int): String = s"${statusPrefix}_out_${i}"

  def wbytesPin(p: Int): String = s"bw_wbytes_${p}"
  def rbytesPin(p: Int): String = s"bw_rbytes_${p}"
  def awaddrPin(p: Int): String = s"bw_awaddr_${p}"
  def araddrPin(p: Int): String = s"bw_araddr_${p}"

  // Widths must match the csynth'd watcher.v exactly.
  val wbytesWidth: Int = 8
  val rbytesWidth: Int = 16
  val addrWidthTap: Int = 20

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
        "start_gate" -> Input(UInt(1.W)),
        "m_axi_gmem" -> axi4.Master(gmemCfg)
      )
        ++ statusPins.map(p => p -> Input(UInt(2.W)))
        ++ (0 until maxHbmPorts).map(p => wbytesPin(p) -> Input(UInt(wbytesWidth.W)))
        ++ (0 until maxHbmPorts).map(p => rbytesPin(p) -> Input(UInt(rbytesWidth.W)))
        ++ (0 until maxHbmPorts).map(p => awaddrPin(p) -> Input(UInt(addrWidthTap.W)))
        ++ (0 until maxHbmPorts).map(p => araddrPin(p) -> Input(UInt(addrWidthTap.W)))
    )
  })

  def getPort(name: String): Data = io.elements.getOrElse(
    name,
    throw new RuntimeException(f"Watcher IO port not found: ${name}")
  )
}
