package HLSHelpers

import chisel3._
import chext.amba.axi4

import scala.collection.immutable.SeqMap

/** Blackbox for the free-running `watcher` telemetry kernel (memAccess.cpp). AI
  * generated to match HLS
  *
  * The generic [[VitisModuleFactory.parseVitisModule]] only recovers
  * `m_axi_gmem`, AXIS `*_TDATA` ports and the `ap_*` handshake, so it cannot
  * see the watcher's scalar status / bandwidth pins. We declare the port list
  * explicitly here to match the synthesized `watcher.v` (Vivado IP flow, NOT
  * the vitis kernel flow, so `ap_ctrl_none` + discrete `ap_none` pins survive).
  *
  * Observed `watcher.v` interface:
  *   - ap_clk, ap_rst_n
  *   - mem_0, mem_8 [63:0] input -- sole base pointers for gmem and gmem1 in
  *     the default one-writer-per-port build
  *   - start_addr [63:0] input -- byte offset added inside the kernel
  *   - start_gate [0:0] input -- 1 once the spawn scheduler dispatches its
  *     first task; the watcher stays idle until then
  *   - m_axi_gmem master -- port A: wId=3, wAddr=64, wData=256, wUser*=1, full
  *     AXI4 (256-bit beat = two 128-bit telemetry bundles)
  *   - m_axi_gmem1 master -- port B: identical config; telemetry alternates
  *     whole bursts across the two masters
  *   - status_<i> [3:0] input -- twenty-two generic physical STATUS nibbles;
  *     JSON and generator wiring define each nibble's meaning
  *   - bw_wbytes_<p> [7:0] input -- write bytes transferred this cycle on HBM
  *     port p
  *   - bw_rbytes_<p> [15:0] input -- read bytes requested this cycle on HBM
  *     port p
  *   - bw_awaddr_<p> [19:0] input -- most-recent AW addr[63:44] on HBM port p
  *     (future)
  *   - bw_araddr_<p> [19:0] input -- most-recent AR addr[63:44] on HBM port p
  *     (future) (p = 0 .. maxHbmPorts-1)
  *
  * The gmem config is fixed (not parsed from the `.v`) so the top can elaborate
  * before the watcher is synthesized; the platform HBM adapter does the
  * 256->256 pass-through. If the kernel's interface changes, re-synthesize and
  * update here.
  *
  * @param statusSlots
  *   fixed physical STATUS pin count; currently 22
  * @param maxHbmPorts
  *   number of per-HBM-port bandwidth/address pin groups (kernel
  *   MAX_HBM_PORTS); the actual exported compute ports are wired in
  *   connectWatcher and any extra pins are tied to 0.
  * @param memBaseChannels
  *   scalar m_axi base-pointer suffixes present in watcher.v; Seq(0, 8) for the
  *   default two-writer build
  */
class WatcherBlackBox(
    val moduleName: String,
    val gmemCfg: axi4.Config,
    val addrWidth: Int,
    val statusSlots: Int,
    val maxHbmPorts: Int,
    val memBaseChannels: Seq[Int]
) extends BlackBox {
  override def desiredName: String = moduleName

  require(statusSlots > 0)
  def statusPinName(i: Int): String = s"status_$i"
  def memBasePin(channel: Int): String = s"mem_${channel}"

  def wbytesPin(p: Int): String = s"bw_wbytes_${p}"
  def rbytesPin(p: Int): String = s"bw_rbytes_${p}"
  def awaddrPin(p: Int): String = s"bw_awaddr_${p}"
  def araddrPin(p: Int): String = s"bw_araddr_${p}"

  // Widths must match the csynth'd watcher.v exactly.
  val wbytesWidth: Int = 8
  val rbytesWidth: Int = 16
  val addrWidthTap: Int = 20

  private val statusPins: Seq[String] =
    (0 until statusSlots).map(statusPinName)

  val io = IO(new chisel3.Record {
    val elements: SeqMap[String, Data] = SeqMap.from(
      Seq(
        "ap_clk" -> Input(Clock()),
        "ap_rst_n" -> Input(Bool()),
        "start_addr" -> Input(UInt(addrWidth.W)),
        "start_gate" -> Input(UInt(1.W)),
        "m_axi_gmem" -> axi4.Master(gmemCfg),
        "m_axi_gmem1" -> axi4.Master(gmemCfg)
      )
        ++ memBaseChannels.map(i => memBasePin(i) -> Input(UInt(addrWidth.W)))
        ++ statusPins.map(p => p -> Input(UInt(4.W)))
        ++ (0 until maxHbmPorts).map(p =>
          wbytesPin(p) -> Input(UInt(wbytesWidth.W))
        )
        ++ (0 until maxHbmPorts).map(p =>
          rbytesPin(p) -> Input(UInt(rbytesWidth.W))
        )
        ++ (0 until maxHbmPorts).map(p =>
          awaddrPin(p) -> Input(UInt(addrWidthTap.W))
        )
        ++ (0 until maxHbmPorts).map(p =>
          araddrPin(p) -> Input(UInt(addrWidthTap.W))
        )
    )
  })

  def getPort(name: String): Data = io.elements.getOrElse(
    name,
    throw new RuntimeException(f"Watcher IO port not found: ${name}")
  )
}
