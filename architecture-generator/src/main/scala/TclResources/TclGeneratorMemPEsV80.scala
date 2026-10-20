package TclResources

import Descriptors._

/**
  * Vivado project + block design for the Alveo V80 PCIe flow.
  *
  * The HardCilk kernel is added to the stock AVED `amd_v80_gen5x8_25.1` shell
  * (monolithic, no DFX), the same way the hand-built image that ran on the
  * card was made:
  *
  * {{{
  *   <name>_0/m_axi_XX -> rs0_m_axi_XX -> rs1_m_axi_XX       (native width, SLR crossing)
  *                     [-> shim_in_XX -> smartconnect_sizer_XX]  (width != 256)
  *                     -> split4k_XX -> rs_nmu_XX            (4KB burst splitter, output register)
  *                     -> axi_noc_cips/HBMxx_AXI             (HBM NMU -> all 32 PCs, 32 GB)
  *
  *   cips/CPM_PCIE_NOC_0/1 -> axi_noc_cips/M01_AXI (0x202_0000_0000, 64 KB, user clock)
  *                     -> smartconnect_mgmt -> rs_s_axil_mgmt -> hc_mgmt_guard -> <name>_0/s_axil_mgmt_hardcilk  (+0x0000, 4 KB)
  *                                          -> axi_gpio_hc_reset                                 (+0x8000)
  * }}}
  *
  * PF1 BAR2 (AXI bridge, 64 KB) is translated to 0x202_0000_0000 and arrives on
  * CPM_PCIE_NOC_0/1 like QDMA MM traffic, so both host paths reach the same
  * window. The user clock is AVED's `clock_reset/clk_usr_0` (usr_clk_wiz
  * clk_out1) retuned to `targetFrequency`. The kernel's active-high `reset`
  * comes from `hc_psr/peripheral_reset`, whose input is the AND of the soft
  * reset GPIO (active low, default 1) and the shell's user-clock reset.
  */
object TclGeneratorMemPEsV80 {

  def generate(
      descriptor: FullSysGenDescriptor,
      dir: String,
      reduce_axi_all: Int,
      hbmPortWidthsAll: Seq[Int] = Seq.empty,
      enableRamaStriping: Boolean = false,
      ramaPorts: Set[Int] = Set.empty,
      rawPortLabels: Seq[String] = Seq.empty,
      rawPortAxi3: Seq[Boolean] = Seq.empty,
      peInstanceNames: Seq[(String, Seq[String])] = Seq.empty,
      peIfaceBuffers: Seq[(String, Seq[String])] = Seq.empty
  ): Unit = {
    val name = descriptor.name
    val board = descriptor.board
    val rawMode = rawPortLabels.nonEmpty
    // HC_NOC_PES (Util.NocPePolicy): the trailing exported ports are NoC ports
    // of the off-hub NoC PEs; only the leading ones go to HBM NMUs.
    val portSlr = Util.NocPePolicy.exportedPortSlr
    val nocPorts: Seq[(Int, String)] =
      if (portSlr.isEmpty) Seq.empty
      else portSlr.zipWithIndex.collect { case (s, i) if s != "HBM" => i -> s }
    require(nocPorts.isEmpty || portSlr.length == reduce_axi_all,
      s"[TclGeneratorMemPEsV80] HC_NOC_PES: ${portSlr.length} port die(s) recorded for $reduce_axi_all port(s)")
    require(nocPorts.forall(_._1 >= reduce_axi_all - nocPorts.length),
      "[TclGeneratorMemPEsV80] HC_NOC_PES: NoC ports must follow the HBM ports")
    val numAll = reduce_axi_all
    val reduce_axi = reduce_axi_all - nocPorts.length
    val reduce_axi_hbm = reduce_axi
    val hbmPortWidths = if (nocPorts.isEmpty) hbmPortWidthsAll else hbmPortWidthsAll.take(reduce_axi)
    require(reduce_axi >= 1, "[TclGeneratorMemPEsV80] the design exports no HBM master")
    require(
      reduce_axi_hbm <= TclV80Common.hbmMaxNmus,
      s"[TclGeneratorMemPEsV80] $reduce_axi_hbm HBM ports requested; this flow connects at most " +
        s"${TclV80Common.hbmMaxNmus} HBM NMUs (two per memory-controller port pair, each mapped to all 32 " +
        "pseudo channels; HC_HBM_MAX_NMUS raises the limit up to 64)."
    )
    require(
      rawMode || hbmPortWidthsAll.isEmpty || hbmPortWidthsAll.length == numAll,
      s"[TclGeneratorMemPEsV80] Expected $reduce_axi HBM port width(s), got ${hbmPortWidths.length}."
    )
    require(!enableRamaStriping, "[TclGeneratorMemPEsV80] --rama-striping is not supported on the V80")
    if (ramaPorts.nonEmpty)
      println(
        "[TclGeneratorMemPEsV80] WARNING: generateRAMA ports are ignored on the V80: every HBM NMU " +
          "already reaches all 32 pseudo channels through the NoC."
      )
    if (descriptor.hasAXIDMAInput)
      println(
        "[TclGeneratorMemPEsV80] WARNING: s_axi_xdma is left unconnected; on the V80 the host " +
          "reaches HBM through the CPM QDMA."
      )

    val localClk = kernelClockMode == "local"
    val clk = if (localClk) "[get_bd_pins hc_kclk_wiz/clk_out1]" else "[get_bd_pins clock_reset/clk_usr_0]"
    // Resets synchronous to the kernel clock: the shell's usr_0 ones, or with
    // HC_KCLK=local those of hc_kclk_psr.
    val rstPeriph = if (localClk) "[get_bd_pins hc_kclk_psr/peripheral_aresetn]" else "[get_bd_pins clock_reset/resetn_usr_0_periph]"
    val rstIc = if (localClk) "[get_bd_pins hc_kclk_psr/interconnect_aresetn]" else "[get_bd_pins clock_reset/resetn_usr_0_ic]"
    val rstn = "[get_bd_pins hc_psr/peripheral_aresetn]"
    val nativeWidths =
      if (rawMode || hbmPortWidths.isEmpty) Seq.fill(reduce_axi)(256) else hbmPortWidths

    val assignment =
      if (rawMode) Some(HbmPortDistribution.distribute(rawPortLabels, reduce_axi)) else None
    // No SmartConnect bypass on the V80: every aggregated port needs its
    // SmartConnect and an end-point shim (see TclV80Common.nmuWidthFor).
    val bypassPorts = Set.empty[Int]

    val sb = new StringBuilder
    def ln(s: String): Unit = { sb.append(s); sb.append("\n") }

    // --- user clock and kernel reset ------------------------------------------
    ln(TclV80Common.fill(
      if (localClk) """
# ---------------------------------------------------------------------------
# Kernel clock (HC_KCLK=local): a DPLL clock wizard placed in SLR2 (LOC in the
# floorplan XDC), fed by the CIPS PL0 reference (100 MHz, BUFG_PS), @FREQ@ MHz,
# with its own reset synchroniser. Everything HardCilk adds runs on it.
# ---------------------------------------------------------------------------
create_bd_cell -type ip -vlnv xilinx.com:ip:clk_wizard hc_kclk_wiz
set_property -dict [list \
    CONFIG.PRIMITIVE_TYPE {DPLL} \
    CONFIG.PRIM_SOURCE {No_buffer} \
    CONFIG.CLKOUT_USED {true} \
    CONFIG.CLKOUT_REQUESTED_OUT_FREQUENCY {@FREQ@} \
    CONFIG.USE_LOCKED {true} \
    CONFIG.USE_RESET {false} \
] [get_bd_cells hc_kclk_wiz]
connect_bd_net [get_bd_pins cips/pl0_ref_clk] [get_bd_pins hc_kclk_wiz/clk_in1]
create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset hc_kclk_psr
set_property CONFIG.C_EXT_RST_WIDTH {1} [get_bd_cells hc_kclk_psr]
connect_bd_net @CLK@ [get_bd_pins hc_kclk_psr/slowest_sync_clk]
connect_bd_net [get_bd_pins hc_kclk_wiz/locked] [get_bd_pins hc_kclk_psr/dcm_locked]
connect_bd_net [get_bd_pins clock_reset/resetn_pl_ic] [get_bd_pins hc_kclk_psr/ext_reset_in]
""" else """
# ---------------------------------------------------------------------------
# User clock: AVED clock_reset/usr_clk_wiz clk_out1 (-> clk_usr_0) at @FREQ@ MHz.
# ---------------------------------------------------------------------------
set_property -dict [list \
    CONFIG.CLKOUT_REQUESTED_OUT_FREQUENCY {@FREQ@,500} \
    CONFIG.CLKOUT_DRIVES {BUFG,No_buffer} \
] [get_bd_cells clock_reset/usr_clk_wiz]
""",
      "FREQ" -> descriptor.targetFrequency.toString,
      "CLK" -> clk
    ))
    ln(TclV80Common.fill(
      """
# ---------------------------------------------------------------------------
# Kernel reset: soft-reset GPIO (write 0 then 1; default 1) AND the shell's
# user-clock reset, synchronised by hc_psr. The kernel's port is active high.
# ---------------------------------------------------------------------------
create_bd_cell -type ip -vlnv xilinx.com:ip:axi_gpio axi_gpio_hc_reset
set_property -dict [list \
    CONFIG.C_ALL_OUTPUTS {1} \
    CONFIG.C_DOUT_DEFAULT {0xFFFFFFFF} \
    CONFIG.C_GPIO_WIDTH {1} \
] [get_bd_cells axi_gpio_hc_reset]
create_bd_cell -type inline_hdl -vlnv xilinx.com:inline_hdl:ilvector_logic hc_reset_and
set_property -dict [list CONFIG.C_OPERATION {and} CONFIG.C_SIZE {1}] [get_bd_cells hc_reset_and]
create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset hc_psr
set_property CONFIG.C_EXT_RST_WIDTH {1} [get_bd_cells hc_psr]
connect_bd_net [get_bd_pins axi_gpio_hc_reset/gpio_io_o] [get_bd_pins hc_reset_and/Op1]
connect_bd_net @RSTP@ [get_bd_pins hc_reset_and/Op2]
connect_bd_net [get_bd_pins hc_reset_and/Res] [get_bd_pins hc_psr/ext_reset_in]
connect_bd_net @CLK@ [get_bd_pins hc_psr/slowest_sync_clk]
connect_bd_net @CLK@ [get_bd_pins axi_gpio_hc_reset/s_axi_aclk]
connect_bd_net @RSTP@ [get_bd_pins axi_gpio_hc_reset/s_axi_aresetn]

create_bd_cell -type module -reference @NAME@ @NAME@_0
connect_bd_net @CLK@ [get_bd_pins @NAME@_0/clock]
connect_bd_net [get_bd_pins hc_psr/peripheral_reset] [get_bd_pins @NAME@_0/reset]
""",
      "CLK" -> clk,
      "RSTP" -> rstPeriph,
      "NAME" -> name
    ))

    // HLS-provided block-design fragments, as in the U55C flow.
    ln(TclGeneralConfigs.getPEsTcl(descriptor))

    // --- raw export: aggregate before the crossing ----------------------------
    // Each raw master gets a shim setting AxCACHE[1] so the aggregating
    // SmartConnect packs its narrow bursts; the aggregate then crosses at 256 bits.
    assignment.foreach { a =>
      ln("# Raw HBM export: masters aggregated onto one SmartConnect per HBM NMU")
      HbmPortDistribution.summary(a, rawPortLabels).linesIterator.foreach(l => ln(s"# $l"))
      for ((masters, port) <- a.zipWithIndex) {
        val sc = f"smartconnect_hbm_${port}%02d"
        ln(s"create_bd_cell -type ip -vlnv xilinx.com:ip:smartconnect $sc")
        ln(s"set_property -dict [list CONFIG.NUM_SI {${masters.length}} CONFIG.NUM_MI {1} CONFIG.NUM_CLKS {1}] [get_bd_cells $sc]")
        for ((m, slot) <- masters.zipWithIndex) {
          val shim = f"shim_raw_${m}%02d"
          val from = f"[get_bd_intf_pins ${name}_0/m_axi_${m}%02d]"
          val w = hbmPortWidths.lift(m).getOrElse(256)
          ln(s"create_bd_cell -type module -reference ${TclV80Common.shimModule} $shim")
          ln(s"set_property -dict [list CONFIG.DATA_WIDTH {$w} " +
            s"CONFIG.ID_WIDTH [expr {max(1, [get_property CONFIG.ID_WIDTH $from])}] " +
            s"CONFIG.ADDR_WIDTH [get_property CONFIG.ADDR_WIDTH $from] CONFIG.FORCE_MODIFIABLE {1}] [get_bd_cells $shim]")
          ln(s"connect_bd_intf_net $from [get_bd_intf_pins $shim/S_AXI]")
          ln(f"connect_bd_intf_net [get_bd_intf_pins $shim/M_AXI] [get_bd_intf_pins $sc/S${slot}%02d_AXI]")
          ln(s"connect_bd_net $clk [get_bd_pins $shim/aclk]")
          ln(s"connect_bd_net $rstn [get_bd_pins $shim/aresetn]")
        }
        ln(s"connect_bd_net $clk [get_bd_pins $sc/aclk]")
        ln(s"connect_bd_net $rstn [get_bd_pins $sc/aresetn]")
      }
    }

    // --- register slices (memory + management) --------------------------------
    // The management slice carries the whole 4 KB window to the guard, which
    // decodes it and forwards the kernel's own address bits.
    val mgmtAddrWidth = descriptor.getNumConfigPorts() + 6
    ln(
      TclGeneralConfigs.getAxiRegisterSliceTcl(
        descriptorName = name,
        portWidths = nativeWidths,
        addrWidth = descriptor.widthAXIAddress,
        liteAddrWidth = TclV80Common.axilGuardWindowBits(mgmtAddrWidth),
        liteDataWidth = 64,
        clkPin = clk,
        resetPin = rstn,
        masterPinOf = assignment.map(a => TclGeneralConfigs.rawPortMasterPin(name, a, bypassPorts))
      )
    )
    // rs_s_axil_mgmt -> hc_mgmt_guard -> s_axil_mgmt_hardcilk (the slice TCL above
    // connects the slice straight to the kernel; re-route through the guard).
    ln(s"delete_bd_objs [get_bd_intf_nets -of_objects [get_bd_intf_pins ${name}_0/s_axil_mgmt_hardcilk]]")
    ln(TclV80Common.axilGuardTcl(name, "[get_bd_intf_pins rs_s_axil_mgmt/M_AXI]", clk, rstn,
      mgmtAddrWidth, descriptor.getNumConfigPorts()))

    // --- HBM NMUs ---------------------------------------------------------------
    // HC_HBM_HOST_PATH=pl (striped designs): one more NMU, fed by the host's PL
    // alias window (see TclV80Common.hbmHostPath).
    val hostAlias = TclV80Common.hbmHostAliasBase
    val numNmu = reduce_axi + (if (hostAlias.isDefined) 1 else 0)
    require(numNmu <= 64, s"[TclGeneratorMemPEsV80] $numNmu HBM NMUs requested; the V80 has 64")
    ln(TclV80Common.bdProcs)
    ln(TclV80Common.fill(
      """
# ---------------------------------------------------------------------------
# axi_noc_cips: @N@ HBM NMU(s) for the kernel, one M_AXI for its management
# window, and a clock input for both in the user clock domain.
# ---------------------------------------------------------------------------
set hc_noc [get_bd_cells /axi_noc_cips]
set hc_old_clks [get_property CONFIG.NUM_CLKS $hc_noc]
set hc_old_mi   [get_property CONFIG.NUM_MI $hc_noc]
set_property -dict [list \
    CONFIG.NUM_HBM_BLI @N@ \
    CONFIG.NUM_CLKS [expr {$hc_old_clks + 1}] \
    CONFIG.NUM_MI [expr {$hc_old_mi + 1}] \
] $hc_noc
set hc_aclk [format aclk%d $hc_old_clks]
set hc_mgmt_mi [format M%02d_AXI $hc_old_mi]
set hc_busifs {}
for {set i 0} {$i < @N@} {incr i} {
    set p [format HBM%02d_AXI $i]
    hc_v80_hbm_nmu_connect $hc_noc $p $i @BW@
    lappend hc_busifs $p
}
""",
      "N" -> numNmu.toString,
      "BW" -> TclV80Common.hbmNmuBwPerTarget.toString
    ))

    // Every NMU ends up 256 bits wide; see TclV80Common.nmuWidthFor for why
    // non-256-bit masters go through shim (modifiable) -> SmartConnect -> shim.
    val nmuWidths = nativeWidths.zipWithIndex.map { case (w, i) =>
      val rs1 = f"[get_bd_intf_pins rs1_m_axi_${i}%02d/M_AXI]"
      val nmu = f"[get_bd_intf_pins axi_noc_cips/HBM${i}%02d_AXI]"
      if (rawMode) ln(TclV80Common.nmuEndpointTcl(f"$i%02d", rs1, nmu, clk, rstn, outputSlice = true))
      else
        ln(TclV80Common.nmuPathTcl(f"$i%02d", rs1, nmu, w, clk, rstn,
          propsFrom = Some(f"[get_bd_intf_pins ${name}_0/m_axi_${i}%02d]"), outputSlice = true))
      TclV80Common.nmuWidthFor(w)
    } ++ hostAlias.toSeq.map { base =>
      // Host path: CPM -> NoC M_AXI (PL, 32 GB alias, user clock) -> split4k_host
      // -> stripe_host (alias rebased onto HBM, then permuted) -> rs_nmu_host -> NMU.
      val hbmHi = (TclV80Common.hbmHostAliasAddress >> 32).toLong ^ (board.hbmBaseAddress >> 32).toLong
      ln(TclV80Common.fill(
        """
# ---------------------------------------------------------------------------
# HC_HBM_HOST_PATH=pl: host HBM window @BASE@ (32 GB) -> PL striping -> HBM NMU
# ---------------------------------------------------------------------------
set hc_old_mi2 [get_property CONFIG.NUM_MI $hc_noc]
set_property CONFIG.NUM_MI [expr {$hc_old_mi2 + 1}] $hc_noc
set hc_host_mi [format M%02d_AXI $hc_old_mi2]
set_property -dict [list \
    CONFIG.DATA_WIDTH {256} \
    CONFIG.APERTURES {{@BASE_US@ 32G}} \
    CONFIG.CATEGORY {pl} \
] [get_bd_intf_pins axi_noc_cips/$hc_host_mi]
hc_v80_add_noc_connection [get_bd_intf_pins axi_noc_cips/S00_AXI] $hc_host_mi 800
hc_v80_add_noc_connection [get_bd_intf_pins axi_noc_cips/S01_AXI] $hc_host_mi 800
lappend hc_busifs $hc_host_mi
""",
        "BASE" -> TclV80Common.hex(base),
        "BASE_US" -> ("0x" + base.toString(16).reverse.grouped(4).mkString("_").reverse)
      ))
      ln(TclV80Common.nmuEndpointTcl("host", "[get_bd_intf_pins axi_noc_cips/$hc_host_mi]",
        f"[get_bd_intf_pins axi_noc_cips/HBM${reduce_axi}%02d_AXI]", clk, rstn,
        idFrom = Some("[get_bd_intf_pins axi_noc_cips/$hc_host_mi]"), outputSlice = true, addrXorHi = hbmHi))
      TclV80Common.hbmNmuDataWidth
    }

    // --- HC_NOC_PES: NoC NMUs on the PEs' own die -------------------------------
    // Each NoC port: m_axi_XX -> rs_noc_XX -> [shim/sizer] -> split4k_XX
    // [-> stripe_XX] -> rs_nmu_XX -> axi_noc_cips/Sxx_AXI (category pl, NMU512
    // pinned to the die by PHYSICAL_LOC), mapped to all 32 HBM pseudo channels.
    val nocNmuNames = scala.collection.mutable.ArrayBuffer[String]()
    if (nocPorts.nonEmpty) {
      val sites = nocPorts.groupBy(_._2).toSeq.flatMap { case (slr, ps) =>
        val avail = v80NocNmuSites(slr)
        require(ps.length <= avail.length, s"[TclGeneratorMemPEsV80] HC_NOC_PES: ${ps.length} NoC ports on $slr, " +
          s"${avail.length} NOC_NMU512 sites")
        ps.map(_._1).sorted.zip(avail)
      }.toMap
      ln(TclV80Common.fill(
        """
# ---------------------------------------------------------------------------
# HC_NOC_PES (Util.NocPePolicy): @N@ regular NoC NMU(s) (NOC_NMU512, category pl)
# for the memory ports of the PEs placed off the hub die, each pinned to its die
# and mapped to all 32 HBM pseudo channels (pair mode, like an HBM NMU / the CPM).
# ---------------------------------------------------------------------------
proc hc_v80_noc_nmu_connect {pin idx bw loc} {
    set a [expr {$idx % 2}]
    set b [expr {2 + ($idx % 2)}]
    set conn {}
    for {set c 0} {$c < 16} {incr c} {
        lappend conn HBM${c}_PORT${a} "read_bw {$bw} write_bw {$bw} read_avg_burst {64} write_avg_burst {64}"
        lappend conn HBM${c}_PORT${b} "read_bw {$bw} write_bw {$bw} read_avg_burst {64} write_avg_burst {64}"
    }
    set_property -dict [list CONFIG.CONNECTIONS $conn CONFIG.CATEGORY {pl} CONFIG.PHYSICAL_LOC $loc] $pin
}
set hc_old_si [get_property CONFIG.NUM_SI $hc_noc]
set_property CONFIG.NUM_SI [expr {$hc_old_si + @N@}] $hc_noc
""",
        "N" -> nocPorts.length.toString
      ))
      nocPorts.zipWithIndex.foreach { case ((i, slr), k) =>
        val w = hbmPortWidthsAll.lift(i).getOrElse(256)
        val rs = f"rs_noc_$i%02d"
        val from = f"[get_bd_intf_pins ${name}_0/m_axi_$i%02d]"
        ln(s"# m_axi_${f"$i%02d"} -> NoC NMU on $slr (${sites(i)})")
        ln(s"set hc_si [format S%02d_AXI [expr {$$hc_old_si + $k}]]")
        ln(s"hc_v80_noc_nmu_connect [get_bd_intf_pins axi_noc_cips/$$hc_si] $i ${Util.NocPePolicy.bwPerTarget} ${sites(i)}")
        ln("lappend hc_busifs $hc_si")
        ln(s"create_bd_cell -type ip -vlnv xilinx.com:ip:axi_register_slice $rs")
        ln(s"set_property -dict [list CONFIG.REG_AW {1} CONFIG.REG_AR {1} CONFIG.REG_W {1} CONFIG.REG_R {1} CONFIG.REG_B {1}] [get_bd_cells $rs]")
        ln(s"connect_bd_intf_net $from [get_bd_intf_pins $rs/S_AXI]")
        ln(s"connect_bd_net $clk [get_bd_pins $rs/aclk]")
        ln(s"connect_bd_net $rstn [get_bd_pins $rs/aresetn]")
        ln(TclV80Common.nmuPathTcl(f"$i%02d", s"[get_bd_intf_pins $rs/M_AXI]", "[get_bd_intf_pins axi_noc_cips/$hc_si]",
          w, clk, rstn, propsFrom = Some(from), outputSlice = true))
        nocNmuNames += f"S${4 + k}%02d_AXI_nmu"
      }
    }

    // --- management window ------------------------------------------------------
    ln(TclV80Common.fill(
      """
# ---------------------------------------------------------------------------
# Management window @BASE@ (64 KB) on a NoC M_AXI in the user clock domain,
# reachable from both CPM NoC ports (QDMA MM and PF1 BAR2 may use either).
# ---------------------------------------------------------------------------
set_property -dict [list \
    CONFIG.DATA_WIDTH {64} \
    CONFIG.APERTURES {{@BASE_US@ 64K}} \
    CONFIG.CATEGORY {pl} \
] [get_bd_intf_pins axi_noc_cips/$hc_mgmt_mi]
hc_v80_add_noc_connection [get_bd_intf_pins axi_noc_cips/S00_AXI] $hc_mgmt_mi 5
hc_v80_add_noc_connection [get_bd_intf_pins axi_noc_cips/S01_AXI] $hc_mgmt_mi 5
lappend hc_busifs $hc_mgmt_mi
set_property CONFIG.ASSOCIATED_BUSIF [join $hc_busifs :] [get_bd_pins axi_noc_cips/$hc_aclk]
connect_bd_net @CLK@ [get_bd_pins axi_noc_cips/$hc_aclk]

create_bd_cell -type ip -vlnv xilinx.com:ip:smartconnect smartconnect_mgmt
set_property -dict [list CONFIG.NUM_SI {1} CONFIG.NUM_MI {2} CONFIG.NUM_CLKS {1}] [get_bd_cells smartconnect_mgmt]
connect_bd_intf_net [get_bd_intf_pins axi_noc_cips/$hc_mgmt_mi] [get_bd_intf_pins smartconnect_mgmt/S00_AXI]
connect_bd_intf_net [get_bd_intf_pins smartconnect_mgmt/M00_AXI] [get_bd_intf_pins rs_s_axil_mgmt/S_AXI]
connect_bd_intf_net [get_bd_intf_pins smartconnect_mgmt/M01_AXI] [get_bd_intf_pins axi_gpio_hc_reset/S_AXI]
connect_bd_net @CLK@ [get_bd_pins smartconnect_mgmt/aclk]
# Not the soft reset: the GPIO that releases it is behind this SmartConnect.
connect_bd_net @RSTI@ [get_bd_pins smartconnect_mgmt/aresetn]

# CPM_CONFIG last: it does not affect the NoC edits above.
@CPM@

# ---------------------------------------------------------------------------
# Addresses. Management first (pinned), then everything else (HBM segments are
# fixed by the NoC).
# ---------------------------------------------------------------------------
foreach sp {cips/CPM_PCIE_NOC_0 cips/CPM_PCIE_NOC_1} {
    assign_bd_address -offset @BASE@ -range 4K -target_address_space [get_bd_addr_spaces $sp] \
        [get_bd_addr_segs @GUARD@/S_AXI/reg0] -force
    assign_bd_address -offset @GPIO@ -range 32K -target_address_space [get_bd_addr_spaces $sp] \
        [get_bd_addr_segs axi_gpio_hc_reset/S_AXI/Reg] -force
}
@HOSTADDR@assign_bd_address
set hc_mgmt_segs [get_bd_addr_segs -of [get_bd_addr_spaces cips/CPM_PCIE_NOC_0] -filter {NAME =~ SEG_@GUARD@_*}]
if {[llength $hc_mgmt_segs] != 1} { error "HardCilk: expected one CPM address segment for @GUARD@, got '$hc_mgmt_segs'" }
foreach seg $hc_mgmt_segs {
    set r [get_property RANGE $seg]
    if {$r > 0x8000} { error "HardCilk: register window is $r bytes; the V80 map allows 32 KB" }
    puts "HardCilk: s_axil_mgmt_hardcilk (via @GUARD@) at [get_property OFFSET $seg] range $r"
}
# The kernel itself must be reachable only through the guard.
if {[llength [get_bd_addr_segs -quiet -of [get_bd_addr_spaces cips/CPM_PCIE_NOC_0] -filter {NAME =~ SEG_@NAME@_0_*}]] != 0} {
    error "HardCilk: s_axil_mgmt_hardcilk is mapped directly in the CPM address space, bypassing @GUARD@"
}
""",
      "BASE" -> TclV80Common.hex(TclV80Common.regBaseAddress),
      "BASE_US" -> "0x202_0000_0000",
      "GPIO" -> TclV80Common.hex(TclV80Common.resetGpioAddress),
      "CLK" -> clk,
      "RSTI" -> rstIc,
      "NAME" -> name,
      "CPM" -> TclV80Common.cpmPf1ConfigTcl,
      "GUARD" -> TclV80Common.axilGuardCell,
      "HOSTADDR" -> hostAlias.map(b =>
        s"""foreach sp {cips/CPM_PCIE_NOC_0 cips/CPM_PCIE_NOC_1} {
    assign_bd_address -offset ${TclV80Common.hex(b)} -range 32G -target_address_space [get_bd_addr_spaces $$sp] \\
        [get_bd_addr_segs split4k_host/S_AXI/reg0] -force
}
puts "HardCilk: host HBM alias (PL striping path) at ${TclV80Common.hex(b)}, 32 GB"
""").getOrElse("")
    ))

    // --- floorplan / waivers -----------------------------------------------------
    val floorplanXdcName = s"${name}_floorplan.xdc"
    val drcWaiverName = s"${name}_drc_waivers.tcl"
    val floorplanXdc = Floorplan.v80Xdc(descriptor, rawMode, peInstanceNames, peIfaceBuffers) + kernelClockXdc
    writeFile(s"$dir/$floorplanXdcName", floorplanXdc)
    // Runs started by launch_runs do not inherit set_param from the parent
    // session, so the thread count is set here, in the impl run's first hook.
    val threadsTcl =
      """# Vivado threads for opt/place/route of this run: $HC_MAX_THREADS from build.sh
        |# (Vivado's own default 8; it caps the value at 32 on Linux).
        |if {[info exists ::env(HC_MAX_THREADS)]} {
        |    set_param general.maxThreads $::env(HC_MAX_THREADS)
        |    puts "HardCilk: general.maxThreads [get_param general.maxThreads]"
        |}
        |""".stripMargin
    writeFile(s"$dir/$drcWaiverName", threadsTcl + TclV80Common.nocHiEffortTcl + TclGeneralConfigs.getDrcWaiverTcl())

    // --- project script ------------------------------------------------------------
    val bdTcl = TclV80Common.fill(
      projectTemplate,
      "NAME" -> name,
      "PART" -> board.part,
      "VIVADO" -> board.vivadoVersion,
      "KERNEL" -> sb.toString,
      "FLOORPLAN" -> floorplanXdcName,
      "WAIVERS" -> drcWaiverName,
      "STRATEGY" -> TclGeneralConfigs.getImplStrategyTcl(descriptor),
      "NOCEFFORT" -> TclV80Common.nocHiEffortTcl
    )
    writeFile(s"$dir/${name}_v80_bd.tcl", bdTcl)
    writeFile(s"$dir/${TclV80Common.shimModule}.v", TclV80Common.shimVerilog)
    writeFile(s"$dir/${TclV80Common.split4kModule}.v", TclV80Common.split4kVerilog)
    writeFile(s"$dir/${TclV80Common.axilGuardModule}.v", TclV80Common.axilGuardVerilog)
    // HC_HBM_STRIPING: the permutation module only exists in striped designs.
    val stripeFile = new java.io.File(s"$dir/${TclV80Common.stripeModule}.v")
    if (TclV80Common.hbmStripeMode != 0) writeFile(stripeFile.getPath, TclV80Common.stripeVerilog)
    else stripeFile.delete()
    // HC_HBM_STRIPING=hbmex|hbmex_stripe: the HBMEX wrapper + core (TclHbmex).
    for (m <- Seq("hc_hbmex_full", "hc_hbmex_stripe")) new java.io.File(s"$dir/$m.v").delete()
    if (TclHbmex.enabled) writeFile(s"$dir/${TclHbmex.fileName}", TclHbmex.verilog(sim = false))
    println(s"[TclGeneratorMemPEsV80] HBM striping: HC_HBM_STRIPING=${TclV80Common.hbmStriping}")
    val implTclText =
      if (nocPorts.isEmpty) TclV80Common.implTcl(name, nmuWidths, lutram = true)
      else TclV80Common.implTcl(name, nmuWidths ++ nocPorts.map(_ => TclV80Common.hbmNmuDataWidth), lutram = true,
        nmuNames = (0 until reduce_axi).map(i => f"HBM$i%02d_AXI_nmu") ++
          (if (TclV80Common.hbmHostAliasBase.isDefined) Seq(f"HBM$reduce_axi%02d_AXI_nmu") else Seq.empty) ++ nocNmuNames)
    writeFile(s"$dir/${name}_v80_impl.tcl", implTclText)
    writeFile(s"$dir/build.sh", TclV80Common.buildShellScript(name, V80Lutram.mode))
    writeFile(s"$dir/${V80Lutram.scriptName}", V80Lutram.pythonScript)
    new java.lang.ProcessBuilder("chmod", "+x", s"$dir/${V80Lutram.scriptName}").start().waitFor()
    println(s"[TclGeneratorMemPEsV80] LUTRAM conversion of shallow kernel BRAMs: ${V80Lutram.mode} (HC_LUTRAM)" +
      (if (V80Lutram.profile.nonEmpty) ", floorplan uses HC_LUTRAM_PROFILE" else ""))
    writeFile(s"$dir/README_V80.md", readme(name, descriptor, reduce_axi))
    new java.lang.ProcessBuilder("chmod", "+x", s"$dir/build.sh").start().waitFor()

    println(
      s"[TclGeneratorMemPEsV80] $reduce_axi HBM NMU(s) (each mapped to all 32 GB at " +
        s"${TclV80Common.hex(board.hbmBaseAddress)}), registers at " +
        s"${TclV80Common.hex(TclV80Common.regBaseAddress)}, soft reset at " +
        s"${TclV80Common.hex(TclV80Common.resetGpioAddress)}, user clock ${descriptor.targetFrequency} MHz."
    )
    println(
      "[TclGeneratorMemPEsV80] Run ./build.sh in the pcie directory (block design), " +
        "./build.sh -i -j 32 for the flashable PDI. See README_V80.md."
    )
  }

  /**
    * NOC_NMU512 sites per die for HC_NOC_PES ports, nearest the hub first (the
    * upper rows of the die), spread over the four vertical NoC columns.
    * Measured on xcv80-lsva4737 (Vivado 2025.1, all 28/24 free in the AVED
    * shell): SLR0 X0..3 Y0..6 (CR rows 1..4), SLR1 X0..3 Y7..12 (CR rows 5..7).
    */
  def v80NocNmuSites(slr: String): Seq[String] = {
    val rows = slr match {
      case "SLR0" => (6 to 0 by -1)
      case "SLR1" => (12 to 7 by -1)
      case other  => throw new IllegalArgumentException(s"HC_NOC_PES: no NoC NMU sites for $other")
    }
    rows.flatMap(y => (0 to 3).map(x => s"NOC_NMU512_X${x}Y$y"))
  }

  /**
    * Kernel clock scheme, `HC_KCLK` at generation time:
    *
    *  - `deskew` (default): AVED's `clock_reset/usr_clk_wiz` (MMCM + BUFGCE in
    *    SLR0) with Versal calibrated global-clock deskew (GCLK_DESKEW CALIBRATED)
    *    on its net;
    *  - `shell`: the same clock without deskew (the original flow);
    *  - `local`: a DPLL clock wizard (`hc_kclk_wiz`) LOCed in SLR2, fed by the
    *    CIPS PL0 reference, with its own reset synchroniser (`hc_kclk_psr`).
    *
    * On the xcv80 there is no MMCM, XPLL, BUFGCE or BUFGCTRL outside SLR0;
    * SLR1/SLR2 only have DPLLs in the GT columns (X0/X9 -> BUFG_GT) and
    * BUFG_FABRIC, so `local` means a DPLL at the die edge. Measured in a probe
    * design (16 register banks over SLR1+SLR2, 250 MHz, routed;
    * agent_runs/lutram/clk): worst skew -0.365 ns (SLR0 MMCM), -0.276 ns
    * (SLR0 MMCM + calibrated deskew, SLR1->SLR2 crossing -0.359 -> -0.239 ns,
    * WNS +1.686 -> +1.950 ns), -0.443 ns (DPLL_X3Y17, right edge of SLR2) and
    * -0.636 ns (DPLL_X0Y17, left edge). Hence the default.
    */
  private def kernelClockMode: String =
    sys.env.getOrElse("HC_KCLK", "deskew").trim.toLowerCase match {
      case m @ ("shell" | "local" | "deskew") => m
      case other => throw new IllegalArgumentException(s"HC_KCLK=$other: expected deskew, shell or local")
    }

  /** Kernel-clock constraints appended to the (implementation-only, late) floorplan XDC. */
  private def kernelClockXdc: String = {
    println(s"[TclGeneratorMemPEsV80] kernel clock: HC_KCLK=$kernelClockMode")
    kernelClockMode match {
      case "shell" => ""
      case "local" =>
        """
# Kernel clock (HC_KCLK=local): DPLL in SLR2 (right GT column, clock region X9Y9),
# driving a BUFG_GT; see TclGeneratorMemPEsV80.kernelClockMode.
set_property LOC DPLL_X3Y17 [get_cells -hierarchical -filter {NAME =~ *hc_kclk_wiz/* && REF_NAME == DPLL}]
"""
      case _ =>
        """
# Kernel clock (clock_reset/usr_clk_wiz clk_out1, MMCM in SLR0): Versal calibrated
# global-clock deskew across SLRs (HC_KCLK=deskew, see TclGeneratorMemPEsV80.kernelClockMode).
set_property GCLK_DESKEW CALIBRATED [get_nets -of_objects [get_pins -hierarchical -filter {NAME =~ *clock_reset/usr_clk_wiz/inst/clock_primitive_inst/BUFG_clkout1_inst/O}]]
"""
    }
  }

  /**
    * Project wrapper. Arguments: staged AVED shell dir, project dir. The project
    * mirrors AVED's `create_design.tcl` (name `prj`, BD `top`, top
    * `top_wrapper`, AVED constraints and hooks), so the AVED hooks, which
    * reference `top_i/...` and `../synth_1/top_wrapper.dcp`, apply unchanged.
    */
  private val projectTemplate: String =
    """# Generated by HardCilk -- Alveo V80 project + block design (AVED shell + @NAME@).
# Usage: vivado -mode batch -source @NAME@_v80_bd.tcl -tclargs <aved_shell_dir> <project_dir>
set hc_pcie_dir [file dirname [file normalize [info script]]]
lassign $argv shell_dir proj_dir
if {$shell_dir eq ""} { set shell_dir [file join $hc_pcie_dir aved_shell] }
if {$proj_dir eq ""}  { set proj_dir  [file join $hc_pcie_dir @NAME@_v80_project] }
set shell_dir [file normalize $shell_dir]
set src_dir   [file join $shell_dir src]

if {[string first @VIVADO@ [version -short]] != 0} {
    puts "HardCilk: WARNING -- generated for Vivado @VIVADO@, running [version -short]"
}

create_project prj $proj_dir -part @PART@ -force
set_property ip_repo_paths [file join $src_dir iprepo] [current_project]
update_ip_catalog

# HardCilk RTL (the U55C wrapper under rtl/synth is not used on the V80).
set hc_rtl [file normalize [file join $hc_pcie_dir .. rtl]]
set hc_files [concat [glob -nocomplain $hc_rtl/*.v] [glob -nocomplain $hc_rtl/*.vh] [glob -nocomplain $hc_rtl/*.sv] \
    [file join $hc_pcie_dir hc_axi_shim.v] [file join $hc_pcie_dir hc_axi_4k_split.v] \
    [file join $hc_pcie_dir hc_axil_guard.v] {*}[glob -nocomplain [file join $hc_pcie_dir hc_axi_stripe.v] [file join $hc_pcie_dir hc_hbmex_*.v]]]
add_files -fileset sources_1 $hc_files
import_files -fileset sources_1 $hc_files
update_compile_order -fileset sources_1

create_bd_design top
current_bd_design top
source [file join $src_dir bd create_bd_design.tcl]
create_root_design ""
current_bd_instance /

@KERNEL@

@NOCEFFORT@validate_bd_design
save_bd_design
puts "HardCilk: block design validated"

add_files -norecurse [make_wrapper -files [get_files top.bd] -top]
update_compile_order -fileset sources_1
set_property top top_wrapper [current_fileset]

# AVED constraints and hooks.
set cdir [file join $src_dir constraints]
import_files -fileset constrs_1 -norecurse [file join $cdir impl.xdc]
import_files -fileset constrs_1 -norecurse [file join $cdir impl.pins.xdc]
import_files -fileset utils_1   -norecurse [file join $cdir opt.post.tcl]
import_files -fileset utils_1   -norecurse [file join $cdir place.pre.tcl]
import_files -fileset utils_1   -norecurse [file join $cdir write_device_image.pre.tcl]
set_property -dict { used_in_synthesis false processing_order NORMAL } [get_files *impl.xdc]
set_property -dict { used_in_synthesis false processing_order NORMAL } [get_files *impl.pins.xdc]
set_property STEPS.OPT_DESIGN.TCL.POST        [get_files *opt.post.tcl]               [get_runs impl_1]
set_property STEPS.PLACE_DESIGN.TCL.PRE       [get_files *place.pre.tcl]              [get_runs impl_1]
set_property STEPS.WRITE_DEVICE_IMAGE.TCL.PRE [get_files *write_device_image.pre.tcl] [get_runs impl_1]

# HardCilk floorplan (implementation only, after the shell's pblocks) and the
# HLS combinational-loop DRC waiver.
set hc_fp [file join $hc_pcie_dir @FLOORPLAN@]
add_files -fileset constrs_1 -norecurse $hc_fp
set_property USED_IN          {implementation} [get_files $hc_fp]
set_property PROCESSING_ORDER LATE             [get_files $hc_fp]
import_files -fileset utils_1 -norecurse [file join $hc_pcie_dir @WAIVERS@]
set_property STEPS.OPT_DESIGN.TCL.PRE [get_files *@WAIVERS@] [get_runs impl_1]
@STRATEGY@
puts "HardCilk: project ready at $proj_dir"
"""

  private def readme(name: String, d: FullSysGenDescriptor, ports: Int): String =
    TclV80Common.fill(
      """# @NAME@ on the Alveo V80 (AVED amd_v80_gen5x8_25.1)

Generated by the HardCilk architecture generator (`--board v80 -P`).

## Build

```bash
cd pcie
./build.sh                 # stage AVED sources into aved_shell/, build + validate the block design
./build.sh -i -j 32        # ... synthesis, implementation, device image, AMC firmware, FPT -> PDI
```

Environment: `AVED_HW_DIR` (default `/home/shahawy/AVED/hw/amd_v80_gen5x8_25.1`),
`AVED_FW_DIR` (default `$AVED_HW_DIR/../../fw/AMC`), `VIVADO_SETTINGS`, `VITIS_SETTINGS`.
The AVED repository is only read: `build.sh` copies `src/iprepo`, `src/constraints`, `fpt/`,
`scripts/`, the AMC firmware sources and the *committed* `src/bd/create_bd_design.tcl`
(`git show HEAD:...`, so local edits of the AVED checkout do not leak in) into `aved_shell/`.
`--amc-elf <file>` packages an existing `amc.elf` instead of rebuilding it (about one minute).

Artifacts (`pcie/output/`):

| file | |
|---|---|
| `@NAME@.pdi` | flashable image (FPT + design PDI + AMC firmware) |
| `@NAME@_routed.dcp` | routed checkpoint |
| `@NAME@.xsa` | hardware platform (AMC BSP input) |
| `reports/` | `timing_summary.rpt`, `utilization{,_hierarchical,_slr}.rpt`, `power.rpt`, `drc_routed.rpt` |
| `logs/` | `build_bd.log`, `build_impl.log`, `amc_build.log`, `fpt.log` |

The build prints `HardCilk: synthesis complete` when the PDI is ready.

## Flash and boot (run by the user, needs sudo)

```bash
sudo ami_tool cfgmem_program -d <PF0 BDF> -t primary -i pcie/output/@NAME@.pdi -p 0
sudo ami_tool device_boot -d <PF0 BDF> -p 0     # or cold reboot the host
```

After boot the card shows PF0 `10ee:50b4` (AMI management) and PF1 `10ee:50b5` (QDMA).

## Address map (Versal AXI addresses)

| what | address | size | host access |
|---|---|---|---|
| HBM (every `m_axi_*`, QDMA MM) | `0x40_0000_0000` | 32 GB | QDMA MM queues |
| HardCilk registers (`s_axil_mgmt_hardcilk`) | `0x202_0000_0000` | 4 KB | PF1 BAR2 offset 0, or QDMA MM |
| soft reset GPIO (channel 1 data, bit 0) | `0x202_0000_8000` | 32 KB | PF1 BAR2 offset 0x8000, or QDMA MM |

The registers sit behind `hc_mgmt_guard` (`hc_axil_guard.v`). HardCilk decodes only
`getNumConfigPorts()` 64-byte register blocks (offsets `0` .. `@NBLK@ * 64 - 1` here) and never
answers an access to any other offset, which stalls the CPM AXI bridge; the resulting PCIe
completion timeout is fatal and resets the host. The guard answers any other offset in the 4 KB
segment with SLVERR (read data `0xBADADD00_00000000 | offset`) without forwarding it, and answers
SLVERR (`0xBADADD01...`) if HardCilk does not respond within 2^16 cycles; after such a timeout it
refuses every access (`0xBADADD02...`) until the soft reset. Offsets 0x1000-0x7FFF get DECERR from
the SmartConnect. The host driver (`memIO_qdma.h`) also refuses offsets outside the decoded blocks.

PF1 BAR0 = QDMA DMA registers (512 KB). PF1 BAR2 = AXI bridge master, 64 KB -> `0x202_0000_0000`.
Soft reset: write 0 then 1 to the GPIO data register (offset 0x0 of the GPIO); 0 holds the
kernel (and its HBM-side register slices) in reset. Its default after configuration is 1.

## Design

- @PORTS@ HardCilk memory port(s) -> register slice pair -> (not 256 bit: hc_axi_shim setting AxCACHE[1] -> SmartConnect sizer) -> hc_axi_4k_split ->
  `axi_noc_cips/HBMxx_AXI` HBM NMUs, each connected to all 32 HBM pseudo channels.
- Kernel clock at @FREQ@ MHz, chosen with `HC_KCLK` at generation time (here: @KCLK@):
  `deskew` (default) = `clock_reset/clk_usr_0` (AVED `usr_clk_wiz`, MMCM in SLR0) with
  `GCLK_DESKEW CALIBRATED` on its net (floorplan XDC); `shell` = the same without deskew;
  `local` = `hc_kclk_wiz`, a DPLL clock wizard LOCed in SLR2 (DPLL_X3Y17) fed by CIPS
  `pl0_ref_clk`, with `hc_kclk_psr` for the kernel, management SmartConnect, GPIO and HBM path
  resets. The xcv80 has no MMCM/BUFGCE outside SLR0; SLR1/SLR2 only have GT-column DPLLs.
- Kernel reset (active high): `hc_psr/peripheral_reset`, from the soft-reset GPIO AND the shell user reset.

## Block RAM -> LUTRAM conversion (`HC_LUTRAM`, default @LUTRAM@)

Wide, shallow kernel memories (the OVERLAP wrapper FIFOs, the HLS dataflow context FIFOs) fill
a block RAM to only a few percent. `hc_lutram.py` finds them in a synthesized kernel netlist and
writes `@NAME@_lutram.xdc` (`set_property RAM_STYLE distributed` on them, generalized over the
PE instances `peMap_<task>_2_*`); the kernel's out-of-context synthesis run
(`top_@NAME@_0_0_synth_1`) reads it through a `STEPS.SYNTH_DESIGN.TCL.PRE` hook
(`hc_lutram_synth_pre.tcl`, written by `@NAME@_v80_impl.tcl`). Constraints read there apply to the
elaborated netlist and override the RTL `(* ram_style = "block" *)` / HLS `MEM_STYLE`.

```bash
# default: build.sh -i synthesizes the kernel once, analyses it, then builds with the XDC
./build.sh -i -j 16
# reuse the kernel checkpoint of an earlier build of the same design (any PE count)
HC_LUTRAM_REF=<old>/prj.runs/top_@NAME@_0_0_synth_1/top_@NAME@_0_0.dcp ./build.sh -i -j 16
# by hand (writes the XDC, a per-PE JSON profile and a table of every BRAM memory)
python3 hc_lutram.py --dcp <kernel.dcp> --workdir output/lutram --xdc @NAME@_lutram.xdc \
    --json output/lutram/@NAME@_lutram_profile.json [--max-depth 128] [--max-eff 50] [--max-lut-per-bram 128]
# after the build: check that every targeted memory left block RAM
python3 hc_lutram.py --dcp @NAME@_v80_project/prj.runs/top_@NAME@_0_0_synth_1/top_@NAME@_0_0.dcp \
    --workdir output/lutram/verify --verify @NAME@_lutram.xdc
HC_LUTRAM=off ./build.sh -i     # build without it (the XDC is kept but not attached)
```

Selection defaults: BRAM-mapped, depth <= 128, fill < 50%, estimated <= 128 LUTs per RAMB36 tile
saved (the 128x1031 wrapper state/loop FIFOs cost ~206 LUTs per tile and stay in BRAM; pass
`HC_LUTRAM_ARGS="--max-lut-per-bram 0"` to convert them too), XPM and true-dual-port memories kept.
An existing `@NAME@_lutram.xdc` is used as is (delete it to re-analyse). Proof that it took effect:
`HardCilk: kernel OOC synthesis: <N> BRAM tiles ...` in `output/logs/build_impl.log` and
`output/reports/kernel_utilization_synth.rpt`.

Floorplan: the generator packs SLRs from HLS estimates unless `HC_LUTRAM_PROFILE=<json>` (the
`--json` output for this design) is set at generation time; then it uses the measured per-PE
LUT/FF and the post-conversion BRAM per PE (pre-conversion figures with `HC_LUTRAM=off`).
Per die the generator prints the binding resource (BRAM vs CLB) and, per task, how many PEs fit
within both the occupancy cap and the SLR1<->SLR2 SLL budget (18870). Generation-time knobs:
`HC_SLR_CAP=<fraction>` (estimated CLB/BRAM occupancy cap per die, default 0.65; Vivado's slice
utilization runs ~1.15-1.6x this estimate) and `HC_FLOORPLAN_OVERFLOW=sll|cap` (what to break
when nothing fits both: `sll`, the default, keeps the SLL budget and overfills the hub die; `cap`
keeps the cap and spends SLLs beyond the budget).
"""
      ,
      "NAME" -> name,
      "PORTS" -> ports.toString,
      "FREQ" -> d.targetFrequency.toString,
      "NBLK" -> d.getNumConfigPorts().toString,
      "LUTRAM" -> V80Lutram.mode,
      "KCLK" -> kernelClockMode
    )

  private def writeFile(path: String, contents: String): Unit = {
    val f = new java.io.PrintWriter(new java.io.File(path))
    try f.write(contents)
    finally f.close()
  }
}
