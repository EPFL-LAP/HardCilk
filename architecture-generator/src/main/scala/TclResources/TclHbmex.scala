package TclResources

/**
  * HBMEX (hbmex-fccm2025: Sonmez, Shahawy, Ienne, "HBMex: An Attachment for
  * Nonbursting Accelerators to Enhance HBM Performance", FCCM 2025) as an opt-in
  * per-HBM-port attachment of the V80 flows (QuestaSim and PCIe).
  *
  * Selected at generation time with `HC_HBM_STRIPING` (see
  * [[TclV80Common.hbmStriping]]); nothing here is used otherwise:
  *
  *   - `hbmex`        the full HBMEX path of the paper's SpmvExp3 -- Stripe ->
  *                    IdParallelizeNoReadBurst -> Enhance -- behind an adapter
  *                    (chext IdSerialize + chext Unburst) that turns HardCilk's
  *                    multi-ID bursting AXI4 into the only traffic
  *                    IdParallelizeNoReadBurst accepts (one ID, AxLEN = 0).
  *   - `hbmex_stripe` HBMEX's Stripe module alone (address-bit permutation).
  *
  * Per port the BD path becomes
  *   master -> [hc_axi_shim + SmartConnect sizer] -> hc_axi_4k_split -> hbmex_<sfx> -> [rs] -> NMU
  * (the HBMEX cell is RTL, so it is the NoC width-harvest end-point: NMU 256 bits).
  *
  * Address map: HBMEX's transformation #5 re-targeted at the V80 HBM NMU map
  * (pseudo channel = offset[34:30]): bits [34:30] are swapped with
  * [[stripeLog2]]+4 .. [[stripeLog2]] (16 KB granule), so consecutive 16 KB
  * blocks go to consecutive pseudo channels. An involution: logical ->
  * physical and physical -> logical are the same map ([[map]]). Host copies must
  * apply it (QuestaSim backdoor: main_sim `hc_stripe_map`, mode [[svMode]]).
  *
  * The Verilog cores are generated out of tree from the unmodified HBMEX
  * sources (architecture-generator/hbmex_v80/build.sh) and shipped as
  * files ([[rtlDir]]/hbmex{F,S}_{sim,syn}.v); module names
  * carry an hbmexF_/hbmexS_ prefix so they cannot clash with HardCilk's own
  * Chisel modules. `_sim` zero-initialises registers and memories (FPGA
  * power-up state; IdParallelizeNoReadBurst's valid bits have no reset) and
  * keeps HBMEX's AxLEN == 0 assertions; `_syn` is the SYNTHESIS build.
  */
object TclHbmex {

  /** Granule of the shipped cores (log2 bytes). Must match the resources. */
  val stripeLog2: Int = 14
  val pcLsb: Int = 30
  val pcBits: Int = 5
  /** Core ID width on the slave side (the wrapper zero-extends narrower IDs). */
  val coreIdWidth: Int = 12
  /** M_AXI ID width of the full core (= Enhance's log2numSegments, one segment per PC). */
  val fullMasterIdWidth: Int = 5

  /** main_sim `hc_stripe_map` mode for the HBMEX swap (modes 1/2 are stripe4k[_xor]). */
  val svMode: Int = 3

  def enabled: Boolean = TclV80Common.hbmStriping.startsWith("hbmex")
  def full: Boolean = TclV80Common.hbmStriping == "hbmex"

  /** BD module name of the wrapper in use. */
  def module: String = if (full) "hc_hbmex_full" else "hc_hbmex_stripe"

  /** File name (without directory) the flows write the wrapper + core to. */
  def fileName: String = s"$module.v"

  /** HBMEX swap on an absolute address (HBM base 0x40_0000_0000 is 2^38-aligned). */
  def map(a: BigInt): BigInt = {
    val m = (BigInt(1) << pcBits) - 1
    val hi = (a >> pcLsb) & m
    val lo = (a >> stripeLog2) & m
    val clear = a & ~((m << pcLsb) | (m << stripeLog2))
    clear | (lo << pcLsb) | (hi << stripeLog2)
  }

  /** SystemVerilog statement block for main_sim's hc_stripe_map (input a, output p). */
  val svMapBody: String =
    s"p[${pcLsb + pcBits - 1}:$pcLsb] = a[${stripeLog2 + pcBits - 1}:$stripeLog2]; " +
      s"p[${stripeLog2 + pcBits - 1}:$stripeLog2] = a[${pcLsb + pcBits - 1}:$pcLsb];"

  /**
    * BD TCL for the HBMEX cell after the 4KB splitter `split` (whose ID/address
    * widths it copies). Returns (cell name, TCL lines); the caller connects
    * `<cell>/M_AXI` onward.
    */
  def cellTcl(sfx: String, split: String, clk: String, rstn: String): (String, Seq[String]) = {
    val c = s"hbmex_$sfx"
    (c, Seq(
      s"create_bd_cell -type module -reference $module $c",
      s"set_property -dict [list CONFIG.ID_WIDTH [get_property CONFIG.ID_WIDTH [get_bd_cells $split]] " +
        s"CONFIG.ADDR_WIDTH [get_property CONFIG.ADDR_WIDTH [get_bd_cells $split]]] [get_bd_cells $c]",
      s"connect_bd_intf_net [get_bd_intf_pins $split/M_AXI] [get_bd_intf_pins $c/S_AXI]",
      s"connect_bd_net $clk [get_bd_pins $c/aclk]",
      s"connect_bd_net $rstn [get_bd_pins $c/aresetn]"
    ))
  }

  /**
    * Directory of the generated cores. Not under src/main/resources: the RTL
    * emitter copies every file there into each design. Relative to the
    * generator's working directory (architecture-generator), like
    * src/main/resources; HC_HBMEX_RTL_DIR overrides it.
    */
  def rtlDir: String = sys.env.getOrElse("HC_HBMEX_RTL_DIR", "hbmex_v80/rtl")

  private def resource(name: String): String = {
    val f = new java.io.File(rtlDir, name)
    require(f.isFile, s"[TclHbmex] ${f.getAbsolutePath} missing (run architecture-generator/hbmex_v80/build.sh)")
    new String(java.nio.file.Files.readAllBytes(f.toPath), "UTF-8")
  }

  /** Wrapper + core Verilog (`sim`: the QuestaSim variant). */
  def verilog(sim: Boolean): String = {
    val core = resource(s"${if (full) "hbmexF" else "hbmexS"}_${if (sim) "sim" else "syn"}.v")
    wrapper + "\n" + core
  }

  /** Verilog wrapper: Xilinx-style s_axi_/m_axi_ ports, parameterised widths, active-low reset. */
  private def wrapper: String = {
    val coreTop = if (full) "hbmexF_HbmexV80Full" else "hbmexS_HbmexV80Stripe"
    val mid = if (full) s"${fullMasterIdWidth - 1}:0" else "ID_WIDTH-1:0"
    val coreMid = if (full) fullMasterIdWidth else coreIdWidth
    s"""`timescale 1ns / 1ps
// Generated by HardCilk (TclResources.TclHbmex) -- HBMEX per-HBM-port attachment
// (HC_HBM_STRIPING=${TclV80Common.hbmStriping}). ${if (full) "Full HBMEX path: IdSerialize + Unburst adapter -> Stripe -> IdParallelizeNoReadBurst -> Enhance." else "HBMEX Stripe only."}
// Address map: P[${pcLsb + pcBits - 1}:$pcLsb] <-> L[${stripeLog2 + pcBits - 1}:$stripeLog2] swapped (16 KB granule over the 32 HBM PCs).
// Must sit after hc_axi_4k_split${if (full) "" else " (bursts must not cross 16 KB)"}. ID_WIDTH <= $coreIdWidth, ADDR_WIDTH <= 64, DATA_WIDTH = 256.
module $module #(
  parameter DATA_WIDTH = 256,
  parameter ID_WIDTH = 6,
  parameter ADDR_WIDTH = 64
) (
  (* X_INTERFACE_PARAMETER = "ASSOCIATED_BUSIF S_AXI:M_AXI, ASSOCIATED_RESET aresetn" *)
  input aclk,
  input aresetn,
  input  [ID_WIDTH-1:0] s_axi_awid,
  input  [ADDR_WIDTH-1:0] s_axi_awaddr,
  input  [7:0] s_axi_awlen,
  input  [2:0] s_axi_awsize,
  input  [1:0] s_axi_awburst,
  input  s_axi_awlock,
  input  [3:0] s_axi_awcache,
  input  [2:0] s_axi_awprot,
  input  [3:0] s_axi_awqos,
  input  [3:0] s_axi_awregion,
  input  s_axi_awvalid,
  output s_axi_awready,
  input  [255:0] s_axi_wdata,
  input  [31:0] s_axi_wstrb,
  input  s_axi_wlast,
  input  s_axi_wvalid,
  output s_axi_wready,
  output [ID_WIDTH-1:0] s_axi_bid,
  output [1:0] s_axi_bresp,
  output s_axi_bvalid,
  input  s_axi_bready,
  input  [ID_WIDTH-1:0] s_axi_arid,
  input  [ADDR_WIDTH-1:0] s_axi_araddr,
  input  [7:0] s_axi_arlen,
  input  [2:0] s_axi_arsize,
  input  [1:0] s_axi_arburst,
  input  s_axi_arlock,
  input  [3:0] s_axi_arcache,
  input  [2:0] s_axi_arprot,
  input  [3:0] s_axi_arqos,
  input  [3:0] s_axi_arregion,
  input  s_axi_arvalid,
  output s_axi_arready,
  output [ID_WIDTH-1:0] s_axi_rid,
  output [255:0] s_axi_rdata,
  output [1:0] s_axi_rresp,
  output s_axi_rlast,
  output s_axi_rvalid,
  input  s_axi_rready,
  output [$mid] m_axi_awid,
  output [ADDR_WIDTH-1:0] m_axi_awaddr,
  output [7:0] m_axi_awlen,
  output [2:0] m_axi_awsize,
  output [1:0] m_axi_awburst,
  output m_axi_awlock,
  output [3:0] m_axi_awcache,
  output [2:0] m_axi_awprot,
  output [3:0] m_axi_awqos,
  output [3:0] m_axi_awregion,
  output m_axi_awvalid,
  input  m_axi_awready,
  output [255:0] m_axi_wdata,
  output [31:0] m_axi_wstrb,
  output m_axi_wlast,
  output m_axi_wvalid,
  input  m_axi_wready,
  input  [$mid] m_axi_bid,
  input  [1:0] m_axi_bresp,
  input  m_axi_bvalid,
  output m_axi_bready,
  output [$mid] m_axi_arid,
  output [ADDR_WIDTH-1:0] m_axi_araddr,
  output [7:0] m_axi_arlen,
  output [2:0] m_axi_arsize,
  output [1:0] m_axi_arburst,
  output m_axi_arlock,
  output [3:0] m_axi_arcache,
  output [2:0] m_axi_arprot,
  output [3:0] m_axi_arqos,
  output [3:0] m_axi_arregion,
  output m_axi_arvalid,
  input  m_axi_arready,
  input  [$mid] m_axi_rid,
  input  [255:0] m_axi_rdata,
  input  [1:0] m_axi_rresp,
  input  m_axi_rlast,
  input  m_axi_rvalid,
  output m_axi_rready
);
  // widths: zero-extend into the core, truncate out of it
  wire [${coreIdWidth - 1}:0] c_s_awid = s_axi_awid, c_s_arid = s_axi_arid;
  wire [${coreIdWidth - 1}:0] c_s_bid, c_s_rid;
  wire [63:0] c_s_awaddr = s_axi_awaddr, c_s_araddr = s_axi_araddr;
  wire [63:0] c_m_awaddr, c_m_araddr;
  wire [${coreMid - 1}:0] c_m_awid, c_m_arid;
  wire [${coreMid - 1}:0] c_m_bid = m_axi_bid, c_m_rid = m_axi_rid;
  assign s_axi_bid = c_s_bid[ID_WIDTH-1:0];
  assign s_axi_rid = c_s_rid[ID_WIDTH-1:0];
  assign m_axi_awaddr = c_m_awaddr[ADDR_WIDTH-1:0];
  assign m_axi_araddr = c_m_araddr[ADDR_WIDTH-1:0];
  assign m_axi_awid = c_m_awid;
  assign m_axi_arid = c_m_arid;
  assign m_axi_awlock = 1'b0;
  assign m_axi_arlock = 1'b0;
  assign m_axi_awqos = 4'd0;
  assign m_axi_arqos = 4'd0;
  assign m_axi_awregion = 4'd0;
  assign m_axi_arregion = 4'd0;

  $coreTop core (
    .clock(aclk),
    .reset(~aresetn),
    .S_AXI_ARREADY(s_axi_arready), .S_AXI_ARVALID(s_axi_arvalid), .S_AXI_ARID(c_s_arid),
    .S_AXI_ARADDR(c_s_araddr), .S_AXI_ARLEN(s_axi_arlen), .S_AXI_ARSIZE(s_axi_arsize),
    .S_AXI_ARBURST(s_axi_arburst), .S_AXI_ARCACHE(s_axi_arcache), .S_AXI_ARPROT(s_axi_arprot),
    .S_AXI_RREADY(s_axi_rready), .S_AXI_RVALID(s_axi_rvalid), .S_AXI_RID(c_s_rid),
    .S_AXI_RDATA(s_axi_rdata), .S_AXI_RRESP(s_axi_rresp), .S_AXI_RLAST(s_axi_rlast),
    .S_AXI_AWREADY(s_axi_awready), .S_AXI_AWVALID(s_axi_awvalid), .S_AXI_AWID(c_s_awid),
    .S_AXI_AWADDR(c_s_awaddr), .S_AXI_AWLEN(s_axi_awlen), .S_AXI_AWSIZE(s_axi_awsize),
    .S_AXI_AWBURST(s_axi_awburst), .S_AXI_AWCACHE(s_axi_awcache), .S_AXI_AWPROT(s_axi_awprot),
    .S_AXI_WREADY(s_axi_wready), .S_AXI_WVALID(s_axi_wvalid), .S_AXI_WDATA(s_axi_wdata),
    .S_AXI_WSTRB(s_axi_wstrb), .S_AXI_WLAST(s_axi_wlast),
    .S_AXI_BREADY(s_axi_bready), .S_AXI_BVALID(s_axi_bvalid), .S_AXI_BID(c_s_bid), .S_AXI_BRESP(s_axi_bresp),
    .M_AXI_ARREADY(m_axi_arready), .M_AXI_ARVALID(m_axi_arvalid), .M_AXI_ARID(c_m_arid),
    .M_AXI_ARADDR(c_m_araddr), .M_AXI_ARLEN(m_axi_arlen), .M_AXI_ARSIZE(m_axi_arsize),
    .M_AXI_ARBURST(m_axi_arburst), .M_AXI_ARCACHE(m_axi_arcache), .M_AXI_ARPROT(m_axi_arprot),
    .M_AXI_RREADY(m_axi_rready), .M_AXI_RVALID(m_axi_rvalid), .M_AXI_RID(c_m_rid),
    .M_AXI_RDATA(m_axi_rdata), .M_AXI_RRESP(m_axi_rresp), .M_AXI_RLAST(m_axi_rlast),
    .M_AXI_AWREADY(m_axi_awready), .M_AXI_AWVALID(m_axi_awvalid), .M_AXI_AWID(c_m_awid),
    .M_AXI_AWADDR(c_m_awaddr), .M_AXI_AWLEN(m_axi_awlen), .M_AXI_AWSIZE(m_axi_awsize),
    .M_AXI_AWBURST(m_axi_awburst), .M_AXI_AWCACHE(m_axi_awcache), .M_AXI_AWPROT(m_axi_awprot),
    .M_AXI_WREADY(m_axi_wready), .M_AXI_WVALID(m_axi_wvalid), .M_AXI_WDATA(m_axi_wdata),
    .M_AXI_WSTRB(m_axi_wstrb), .M_AXI_WLAST(m_axi_wlast),
    .M_AXI_BREADY(m_axi_bready), .M_AXI_BVALID(m_axi_bvalid), .M_AXI_BID(c_m_bid), .M_AXI_BRESP(m_axi_bresp)
  );
endmodule
"""
  }
}
