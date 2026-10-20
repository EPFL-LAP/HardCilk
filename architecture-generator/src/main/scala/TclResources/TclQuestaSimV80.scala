package TclResources

import Descriptors._

/**
  * QuestaSim project for the Alveo V80 (Versal, Vivado 2025.1).
  *
  * Same contract as the U55C [[TclQuestaSim]] flow -- `questa/simulate.sh [--batch]`
  * builds a Vivado project, then QuestaSim runs the SystemC/DPI host testbench
  * (`software/projects/<name>/src/questa_main.cpp`) against it -- but the memory
  * system is the V80's:
  *
  *   - Every exported HardCilk `m_axi_XX` drives its own HBM NMU (`HBMxx_AXI` of an
  *     `axi_noc`), and every NMU is connected to all 32 HBM pseudo channels, so each
  *     master addresses the whole 32 GB at 0x40_0000_0000 (as in the PCIe design).
  *     Masters that are not 256-bit AXI4 go through a 1:1 SmartConnect first.
  *   - The testbench memory VIP (`axi_vip_0`, 512-bit) gets one more HBM NMU of its
  *     own, also full-map, behind a 1:1 SmartConnect.
  *   - The register VIP (`axi_vip_1`, 32-bit AXI4) reaches `s_axil_mgmt_hardcilk`
  *     through a SmartConnect and `hc_mgmt_guard` (as on the card) at 0x202_0000_0000
  *     -- the register base of CONTRACT 1, so `getBoardRegBaseAddress()` is the same in
  *     simulation and on the card. Before the host's first register access, main_sim
  *     checks that an access one block past the last decoded one gets SLVERR from the
  *     guard (+HC_NO_GUARD_PROBE skips it).
  *   - Everything runs on one clock (the clk_wizard output at the descriptor's
  *     target frequency): the VIPs too, so no clock converters are needed.
  *   - Host memory copies (copyToDevice/copyFromDevice in memIO_questa.h) go
  *     straight into the HBM device model in zero simulated time (HC_SIM_BACKDOOR=0:
  *     through the memory VIP as before). The encrypted Versal HBM device model
  *     (`hbm_responder`) is replaced -- at vopt time, via library hc_hbm_model --
  *     by a same-pin stand-in around AMD's plaintext HBM2e responder from the Vivado
  *     install (hbm_v1_0/verif/model/HBM_xsim.sv); NoC, NMUs, HBM controller and PHY
  *     BFMs are unchanged, so design traffic takes the real path. Backdoor accesses
  *     wait until no design write is in flight and the DRAM saw no column write for
  *     a while (the controller can hold write data after BRESP). HC_HBM_MODEL=xilinx
  *     simulates the encrypted model (no backdoor). See hbmBackdoorSv.
  *
  * The testbench HDL (`main_sim.sv`, `top_sim.sv`) is generated here into
  * `questa/sim_src/`, because it carries per-design progress/watchdog monitors on
  * every exported master. `main_sim` keeps the U55C port list so the SystemC
  * wrapper (`main_sim_wrapper_questa.h`) is shared by both boards.
  */
object TclQuestaSimV80 {

  private val hbmNmuMax = 64
  private val hbmChannels = 16
  /** Per-destination bandwidth hint; 32 destinations x 400 MB/s stays under an NMU's budget. */
  private val nmuBwPerTarget = 400

  def generate(
      d: FullSysGenDescriptor,
      tclFileDirectory: String,
      reduce_axi: Int,
      hbmPortWidths: Seq[Int] = Seq.empty,
      rawPortLabels: Seq[String] = Seq.empty,
      rawPortAxi3: Seq[Boolean] = Seq.empty
  ): Unit = {
    val board = d.board
    val name = d.name
    val numPorts = reduce_axi
    val vipPort = numPorts
    require(
      numPorts + 1 <= hbmNmuMax,
      s"[TclQuestaSimV80] The design exports $numPorts memory master(s); the testbench needs one " +
        s"more HBM NMU for its memory VIP, which exceeds the $hbmNmuMax HBM NMUs. Use -r <= ${hbmNmuMax - 1}."
    )
    val rawMode = rawPortLabels.nonEmpty
    val assignment =
      if (rawMode) Some(HbmPortDistribution.distribute(rawPortLabels, numPorts)) else None

    val hbmBase = board.hbmBaseAddress
    val regBase = BigInt("20200000000", 16)

    val sb = new StringBuilder
    def w(s: String): Unit = { sb.append(s); sb.append("\n") }

    w(s"create_bd_cell -type module -reference $name ${name}_0")
    w(TclGeneralConfigs.getPEsTcl(d))

    // ---- external ports (driven by main_sim.sv) ----
    w("create_bd_port -dir I -type clk -freq_hz 100000000 sys_clk_100")
    w("create_bd_port -dir I -type rst PCIE_PERST_LS_65")
    w("set_property CONFIG.POLARITY ACTIVE_LOW [get_bd_ports PCIE_PERST_LS_65]")
    w("create_bd_port -dir O HBM_CATTRIP_LS")
    w("create_bd_cell -type ip -vlnv xilinx.com:ip:xlconstant:1.1 const_zero")
    w("set_property -dict [list CONFIG.CONST_VAL {0} CONFIG.CONST_WIDTH {1}] [get_bd_cells const_zero]")
    w("connect_bd_net [get_bd_pins const_zero/dout] [get_bd_ports HBM_CATTRIP_LS]")

    // ---- clock and reset ----
    val f = d.targetFrequency
    w("create_bd_cell -type ip -vlnv xilinx.com:ip:clk_wizard:1.0 clk_wizard_0")
    w(
      s"""set_property -dict [list \\
  CONFIG.PRIM_IN_FREQ {100.000} \\
  CONFIG.CLKOUT_USED {true,false,false,false,false,false,false} \\
  CONFIG.CLKOUT_PORT {clk_out1,clk_out2,clk_out3,clk_out4,clk_out5,clk_out6,clk_out7} \\
  CONFIG.CLKOUT_REQUESTED_OUT_FREQUENCY {$f.000,100.000,100.000,100.000,100.000,100.000,100.000} \\
  CONFIG.USE_LOCKED {true} \\
] [get_bd_cells clk_wizard_0]""")
    w("connect_bd_net [get_bd_ports sys_clk_100] [get_bd_pins clk_wizard_0/clk_in1]")
    w("if {[llength [get_bd_pins -quiet clk_wizard_0/reset]] > 0} { connect_bd_net [get_bd_pins const_zero/dout] [get_bd_pins clk_wizard_0/reset] }")
    w("if {[llength [get_bd_pins -quiet clk_wizard_0/resetn]] > 0} { connect_bd_net [get_bd_ports PCIE_PERST_LS_65] [get_bd_pins clk_wizard_0/resetn] }")
    w("create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 proc_sys_reset_0")
    w("connect_bd_net [get_bd_pins clk_wizard_0/clk_out1] [get_bd_pins proc_sys_reset_0/slowest_sync_clk]")
    w("connect_bd_net [get_bd_pins clk_wizard_0/locked] [get_bd_pins proc_sys_reset_0/dcm_locked]")
    w("connect_bd_net [get_bd_ports PCIE_PERST_LS_65] [get_bd_pins proc_sys_reset_0/ext_reset_in]")
    w(s"connect_bd_net [get_bd_pins clk_wizard_0/clk_out1] [get_bd_pins ${name}_0/clock]")
    w(s"connect_bd_net [get_bd_pins proc_sys_reset_0/peripheral_reset] [get_bd_pins ${name}_0/reset]")
    val clk = "[get_bd_pins clk_wizard_0/clk_out1]"
    val rstn = "[get_bd_pins proc_sys_reset_0/peripheral_aresetn]"

    // ---- VIPs ----
    w(
      s"""create_bd_cell -type ip -vlnv xilinx.com:ip:axi_vip:1.1 axi_vip_0
create_bd_cell -type ip -vlnv xilinx.com:ip:axi_vip:1.1 axi_vip_1
set_property -dict [list CONFIG.INTERFACE_MODE {MASTER} CONFIG.PROTOCOL {AXI4} CONFIG.ADDR_WIDTH {64} CONFIG.DATA_WIDTH {256} CONFIG.ID_WIDTH {1}] [get_bd_cells axi_vip_0]
set_property -dict [list CONFIG.INTERFACE_MODE {MASTER} CONFIG.PROTOCOL {AXI4} CONFIG.ADDR_WIDTH {64} CONFIG.DATA_WIDTH {32}] [get_bd_cells axi_vip_1]
connect_bd_net $clk [get_bd_pins axi_vip_0/aclk] [get_bd_pins axi_vip_1/aclk]
connect_bd_net $rstn [get_bd_pins axi_vip_0/aresetn] [get_bd_pins axi_vip_1/aresetn]""")

    // Management: 32-bit AXI4 VIP -> SmartConnect (AXI4 -> AXI4-Lite, 32 -> 64 bit).
    w(
      s"""create_bd_cell -type ip -vlnv xilinx.com:ip:smartconnect:1.0 smartconnect_mgmt
set_property -dict [list CONFIG.NUM_SI {1} CONFIG.NUM_MI {1} CONFIG.NUM_CLKS {1}] [get_bd_cells smartconnect_mgmt]
connect_bd_intf_net [get_bd_intf_pins axi_vip_1/M_AXI] [get_bd_intf_pins smartconnect_mgmt/S00_AXI]
connect_bd_net $clk [get_bd_pins smartconnect_mgmt/aclk]
connect_bd_net $rstn [get_bd_pins smartconnect_mgmt/aresetn]""")
    val mgmtAddrWidth = d.getNumConfigPorts() + 6
    w(TclV80Common.axilGuardTcl(name, "[get_bd_intf_pins smartconnect_mgmt/M00_AXI]", clk, rstn,
      mgmtAddrWidth, d.getNumConfigPorts()))

    if (d.hasAXIDMAInput)
      println("[TclQuestaSimV80] WARNING: s_axi_xdma is left unconnected; the testbench VIP accesses HBM directly.")

    // ---- NoC with HBM NMUs, all full-map ----
    val numNmu = numPorts + 1
    w(
      s"""create_bd_cell -type ip -vlnv xilinx.com:ip:axi_noc:1.1 axi_noc_hbm
set_property -dict [list \\
  CONFIG.NUM_SI {0} CONFIG.NUM_MI {0} CONFIG.NUM_NSI {0} CONFIG.NUM_NMI {0} \\
  CONFIG.NUM_HBM_BLI {$numNmu} CONFIG.NUM_CLKS {1} \\
  CONFIG.HBM_NUM_CHNL {$hbmChannels} CONFIG.HBM_STACKS {2} \\
  CONFIG.HBM_MEMORY_FREQ0 {1600} CONFIG.HBM_MEMORY_FREQ1 {1600} \\
  CONFIG.HBM_REF_CLK_SELECTION {External} \\
  CONFIG.HBM_REF_CLK_FREQ0 {200.000} CONFIG.HBM_REF_CLK_FREQ1 {200.000} \\
  CONFIG.HBM_DENSITY_PER_CHNL {2G} \\
] [get_bd_cells axi_noc_hbm]
# Every HBM NMU -> every pseudo channel (HBMc_PORT0/1 = PC0 of channel c,
# HBMc_PORT2/3 = PC1), alternating PORT0/PORT1 between NMUs. One 32 GB aperture
# at 0x${hbmBase.toString(16)} per NMU.
set hc_busifs {}
for {set i 0} {$$i < $numNmu} {incr i} {
  set p [format HBM%02d_AXI $$i]
  set a [expr {$$i % 2}]
  set b [expr {2 + ($$i % 2)}]
  set conn {}
  for {set c 0} {$$c < $hbmChannels} {incr c} {
    lappend conn HBM$${c}_PORT$${a} "read_bw {$nmuBwPerTarget} write_bw {$nmuBwPerTarget} read_avg_burst {64} write_avg_burst {64}"
    lappend conn HBM$${c}_PORT$${b} "read_bw {$nmuBwPerTarget} write_bw {$nmuBwPerTarget} read_avg_burst {64} write_avg_burst {64}"
  }
  set_property -dict [list CONFIG.CONNECTIONS $$conn CONFIG.CATEGORY {pl_hbm} CONFIG.R_TRAFFIC_CLASS {BEST_EFFORT}] [get_bd_intf_pins axi_noc_hbm/$$p]
  lappend hc_busifs $$p
}
set_property CONFIG.ASSOCIATED_BUSIF [join $$hc_busifs :] [get_bd_pins axi_noc_hbm/aclk0]
connect_bd_net $clk [get_bd_pins axi_noc_hbm/aclk0]
foreach r {hbm_ref_clk0 hbm_ref_clk1} {
  set e [create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_clock_rtl:1.0 $$r]
  set_property CONFIG.FREQ_HZ 200000000 $$e
  connect_bd_intf_net $$e [get_bd_intf_pins axi_noc_hbm/$$r]
}""")

    def nmu(i: Int) = f"[get_bd_intf_pins axi_noc_hbm/HBM${i}%02d_AXI]"

    // How a master reaches its HBM NMU (all validated against nocattrs.dat and the
    // NoC/HBM BFMs):
    //  - 256-bit AXI4: wired straight to the NMU (REG_DWIDTH 0x5).
    //  - Any other width: hc_axi_shim (own width, FORCE_MODIFIABLE) -> 1:1
    //    SmartConnect (up/down-sizer) -> hc_axi_shim (256) -> NMU.
    //    * The HBM controller rejects narrow bursts ("HBM2E does not support narrow
    //      bursts for AXLEN greater than 0"); a narrow master wired straight to the
    //      NMU (which accepts its width) or upsized without the modifiable bit
    //      produces exactly those. AxCACHE[1] lets the upsizer pack them.
    //    * The NoC harvests the NMU width from the end-point behind SmartConnects
    //      and register slices; for a 512-bit master it programs a 128-bit NMU
    //      (REG_DWIDTH 0x4; the NoC BFM then aborts on the first 256-bit beat). The
    //      RTL shim in front of the NMU is an end-point and gives 256 bits.
    //    * Setting DATA_WIDTH on the NoC port instead fails validation (BD 41-237).
    // Shared with the PCIe flow (TclV80Common, read-only here): every NMU is fed
    // through the 4KB burst splitter (HardCilk masters issue INCR bursts that
    // cross 4KB, which the HBM NMU rejects), which is also the RTL end-point that
    // makes the NoC program the NMU 256-bit; non-256-bit masters first go through
    // the FORCE_MODIFIABLE shim + sizing SmartConnect (no narrow bursts to HBM).
    def toNmu(cellSuffix: String, from: String, port: Int, nativeWidth: Int): Unit =
      sb.append(TclV80Common.nmuPathTcl(cellSuffix, from, nmu(port), nativeWidth, clk, rstn))
    def splitTo(cellSuffix: String, from: String, port: Int, idFrom: Option[String]): Unit =
      sb.append(TclV80Common.nmuEndpointTcl(cellSuffix, from, nmu(port), clk, rstn, idFrom))

    assignment match {
      case Some(a) =>
        // Raw export: aggregate the design's masters onto numPorts SmartConnects
        // (which also convert width/protocol); the 256-bit shim makes each one
        // present 256 bits to its NMU. Narrow masters are NOT made modifiable on
        // this path (the shared helper wires them straight to the SmartConnect), so
        // a narrow burst can still reach the HBM controller in raw mode.
        w(TclGeneralConfigs.getHbmAggregateSmartConnectTcl(name, a, rawPortLabels, clk, rstn))
        for (i <- 0 until numPorts)
          splitTo(f"$i%02d", f"[get_bd_intf_pins smartconnect_hbm_${i}%02d/M00_AXI]", i, None)
      case None =>
        for (i <- 0 until numPorts) {
          val master = f"[get_bd_intf_pins ${name}_0/m_axi_${i}%02d]"
          toNmu(f"$i%02d", master, i, hbmPortWidths.lift(i).getOrElse(0))
        }
    }
    // The memory VIP is a native 256-bit master wired straight to its NMU. A 512-bit
    // VIP behind a downsizing SmartConnect got its NMU programmed 128-bit
    // (REG_DWIDTH 4) and the NoC BFM aborted on the first 256-bit beat. main_sim.sv
    // turns the host's 64-byte beats into twice as many 32-byte ones.
    splitTo("vip_mem", "[get_bd_intf_pins axi_vip_0/M_AXI]", vipPort, Some("[get_bd_intf_pins axi_vip_0/M_AXI]"))

    // ---- addresses ----
    w(
      s"assign_bd_address -offset 0x${regBase.toString(16)} -range 4K -target_address_space [get_bd_addr_spaces axi_vip_1/Master_AXI] " +
        s"[get_bd_addr_segs ${TclV80Common.axilGuardCell}/S_AXI/reg0]"
    )
    w("assign_bd_address")
    w(
      """foreach sp [get_bd_addr_spaces -of [get_bd_cells -filter {VLNV =~ "*axi_vip*" || VLNV =~ "*module_ref*"}]] {
  foreach seg [get_bd_addr_segs -of $sp] {
    puts "HC_ADDRMAP: [get_property PATH $seg] OFFSET=[get_property OFFSET $seg] RANGE=[get_property RANGE $seg]"
  }
}""")

    // ---- project + simulation scripts ----
    val tcl = new StringBuilder
    tcl.append(projectWrapper(d, sb.toString))
    val proj = s"./${name}_vivado_project"
    val bdFile = s"$proj/project_1.srcs/sources_1/bd/design_1/design_1.bd"
    tcl.append(
      s"""
set hc_libdir /tools/xilinx_compiled_libs_for_questa
if {[info exists ::env(QUESTA_LIB_DIR)]} { set hc_libdir $$::env(QUESTA_LIB_DIR) }
set_property target_simulator Questa [current_project]
set_property compxlib.questa_compiled_library_dir $$hc_libdir [current_project]
generate_target Simulation [get_files $bdFile]
export_ip_user_files -of_objects [get_files $bdFile] -no_script -sync -force -quiet
# Only write the compile/elaborate scripts: simulate.sh runs compile.sh, then
# simulate.do elaborates the SystemC TestBench as the top (not main_sim alone).
launch_simulation -scripts_only
puts "HC_QUESTA_V80: block design and simulation scripts ready"
""")
    writeFile(s"$tclFileDirectory/${name}_questa.tcl", tcl.toString)

    // ---- testbench HDL ----
    new java.io.File(s"$tclFileDirectory/sim_src").mkdirs()
    writeFile(s"$tclFileDirectory/sim_src/top_sim.sv", topSim)
    writeFile(s"$tclFileDirectory/sim_src/${TclV80Common.shimModule}.v", TclV80Common.shimVerilog)
    writeFile(s"$tclFileDirectory/sim_src/${TclV80Common.split4kModule}.v", TclV80Common.split4kVerilog)
    writeFile(s"$tclFileDirectory/sim_src/${TclV80Common.axilGuardModule}.v", TclV80Common.axilGuardVerilog)
    // HC_HBM_STRIPING: the permutation module only exists in striped designs.
    val stripeFile = new java.io.File(s"$tclFileDirectory/sim_src/${TclV80Common.stripeModule}.v")
    if (TclV80Common.hbmStripeMode != 0) writeFile(stripeFile.getPath, TclV80Common.stripeVerilog)
    else stripeFile.delete()
    // HC_HBM_STRIPING=hbmex|hbmex_stripe: the HBMEX wrapper + core (TclHbmex).
    for (m <- Seq("hc_hbmex_full", "hc_hbmex_stripe")) new java.io.File(s"$tclFileDirectory/sim_src/$m.v").delete()
    if (TclHbmex.enabled) writeFile(s"$tclFileDirectory/sim_src/${TclHbmex.fileName}", TclHbmex.verilog(sim = true))
    writeFile(
      s"$tclFileDirectory/sim_src/main_sim.sv",
      mainSim(name, if (rawMode) rawPortLabels.length else numPorts, hbmBase, regBase, f, d.getNumConfigPorts())
    )

    // HBM backdoor: the registry package goes into the sim_1 fileset with main_sim;
    // the device-model stand-in is compiled by simulate.sh into its own library
    // (sim_src/hbm_model is outside the fileset glob on purpose).
    writeFile(s"$tclFileDirectory/sim_src/hc_hbm_bd_pkg.sv", hbmBdPkg)
    new java.io.File(s"$tclFileDirectory/sim_src/hbm_model").mkdirs()
    writeFile(s"$tclFileDirectory/sim_src/hbm_model/hc_hbm_responder.sv", hbmResponderWrapper)
    writeFile(s"$tclFileDirectory/sim_src/hbm_model/build_hbm_model.sh", buildHbmModelSh)
    new java.io.File(s"$tclFileDirectory/sim_src/hbm_model/build_hbm_model.sh").setExecutable(true)

    writeFile(s"$tclFileDirectory/simulate.do", simulateDo(name))
    writeFile(s"$tclFileDirectory/simulate.sh", simulateSh(name, board.vivadoVersion))
    new java.io.File(s"$tclFileDirectory/simulate.sh").setExecutable(true)

    println(
      s"[TclQuestaSimV80] V80 QuestaSim project: ${numPorts} design master port(s) + 1 VIP port on " +
        s"$numNmu full-map HBM NMU(s) (0x${hbmBase.toString(16)}, 32 GB); registers at 0x${regBase.toString(16)}."
    )
  }

  private def writeFile(path: String, s: String): Unit = {
    val pw = new java.io.PrintWriter(new java.io.File(path))
    try pw.write(s) finally pw.close()
  }

  private def projectWrapper(d: FullSysGenDescriptor, bd: String): String = {
    val b = d.board
    s"""# HardCilk QuestaSim project for ${b.fpgaModel} (generated by TclQuestaSimV80)
set scripts_vivado_version ${b.vivadoVersion}
set current_vivado_version [version -short]
if { [string first $$scripts_vivado_version $$current_vivado_version] == -1 } {
  puts "ERROR: this script targets Vivado $$scripts_vivado_version but is running in $$current_vivado_version"
  exit 1
}
create_project project_1 ${d.name}_vivado_project -part ${b.part} -force
set design_name design_1
create_bd_design $$design_name

add_files -fileset sources_1 [glob ../rtl/*.v] [glob ../rtl/*.vh] [glob ../rtl/*.sv]
import_files -fileset sources_1 [glob ../rtl/*.v] [glob ../rtl/*.vh] [glob ../rtl/*.sv]
${hlsPartSelectFixTcl.replace("HC_DESIGN_NAME", d.name)}set hc_rtl_files [concat [list ./sim_src/hc_axi_shim.v ./sim_src/hc_axi_4k_split.v ./sim_src/hc_axil_guard.v] [glob -nocomplain ./sim_src/hc_axi_stripe.v ./sim_src/hc_hbmex_*.v]]
add_files -fileset sources_1 -norecurse $$hc_rtl_files
import_files -fileset sources_1 -norecurse $$hc_rtl_files
add_files -fileset sim_1 [glob ./sim_src/*.sv]
import_files -fileset sim_1 [glob ./sim_src/*.sv]
update_compile_order -fileset sources_1

current_bd_instance [get_bd_cells /]
$bd
validate_bd_design
save_bd_design
# The NoC only simulates through its logical switch network (xlnoc), which Vivado
# builds around a BD *wrapper* as <wrapper>_sim_wrapper. Without it the NMUs'
# NPP inputs float and the NoC BFM aborts at time 0 ("noc_valid on npp_in ...
# unconnected / unknown").
set hc_wrapper [make_wrapper -files [get_files design_1.bd] -top]
add_files -norecurse $$hc_wrapper
set_property top design_1_wrapper [get_filesets sources_1]
update_compile_order -fileset sources_1
generate_switch_network_for_noc
if {[llength [get_files -all -quiet design_1_wrapper_sim_wrapper.v]] == 0} {
  puts "ERROR: HC_QUESTA_V80: generate_switch_network_for_noc produced no design_1_wrapper_sim_wrapper.v"
  exit 1
}
set_property top main_sim [get_filesets sim_1]
set_property top_lib xil_defaultlib [get_filesets sim_1]
update_compile_order -fileset sources_1
update_compile_order -fileset sim_1
"""
  }

  /** See the comment inside: Questa-only rewrite of HLS cache part-selects. */
  private val hlsPartSelectFixTcl =
    """# Vitis HLS m_axi caches select address bits in a constant-false ternary branch
# with a part-select that is reversed for their parameters, e.g.
#   (CACHE_BURST_LEN == 1) ? 1'b0 : in_HLS_ARADDR[CACHE_LINE_ALIGN - 1 : BUS_ADDR_ALIGN]
# Questa rejects that at elaboration (vopt-3373, not suppressible). Rewrite it as a
# shift, which selects the same bits after truncation, in the project's imported
# simulation copies only (rtl/ stays as HLS wrote it).
foreach f [glob -nocomplain ./HC_DESIGN_NAME_vivado_project/project_1.srcs/sources_1/imports/rtl/*.v] {
  set fh [open $f r]; set t [read $fh]; close $fh
  set n [regsub -all {\? 1'b0 : (in_HLS_A[RW]ADDR)\[([A-Za-z_]+(?: \+ log2\([A-Za-z_]+\))?) - 1 : ([A-Za-z_]+)\]} $t {? 1'b0 : (\1 >> \3)} t]
  if {$n > 0} {
    set fh [open $f w]; puts -nonewline $fh $t; close $fh
    puts "HC_QUESTA_V80: rewrote $n HLS cache part-select(s) in [file tail $f]"
  }
}
"""

  private val topSim =
    """`timescale 1 ps / 1 ps
// V80 QuestaSim wrapper around the block design (generated by TclQuestaSimV80).
// design_1_wrapper_sim_wrapper is Vivado's simulation wrapper: design_1_wrapper
// plus the NoC's logical switch network (xlnoc).
module top_sim (
    output HBM_CATTRIP_LS,
    input  PCIE_PERST_LS_65,
    input  sys_clk_100,
    input  hbm_ref_clk_n,
    input  hbm_ref_clk_p
);
  design_1_wrapper_sim_wrapper design_1_sw (
      .HBM_CATTRIP_LS(HBM_CATTRIP_LS),
      .PCIE_PERST_LS_65(PCIE_PERST_LS_65),
      .sys_clk_100(sys_clk_100),
      .hbm_ref_clk0_clk_n(hbm_ref_clk_n),
      .hbm_ref_clk0_clk_p(hbm_ref_clk_p),
      .hbm_ref_clk1_clk_n(hbm_ref_clk_n),
      .hbm_ref_clk1_clk_p(hbm_ref_clk_p)
  );
endmodule
"""

  /**
    * The DPI-SC host bridge plus progress print and deadlock watchdog.
    *
    * Plusargs (all in microseconds of simulated time):
    *   +HC_PROGRESS_US=n      progress line period (default 50)
    *   +HC_TXN_TIMEOUT_US=n   an AXI transaction outstanding this long with no
    *                          response on its port is a hang (default 200)
    *   +HC_HOST_IDLE_US=n     host thread made no DPI call for this long after its
    *                          last one: it returned, finish the simulation (default 50)
    *   +HC_MAX_SIM_US=n       hard simulated-time limit (default 50000)
    */
  private def mainSim(name: String, numMasters: Int, hbmBase: BigInt, regBase: BigInt, freqMhz: Int,
      numBlocks: Int): String = {
    val hb = hbmBase.toString(16)
    val rb = regBase.toString(16)
    // First undecoded register offset; the probe needs it inside the 4 KB segment.
    val probeOff = numBlocks * 64
    val probeOn = if (probeOff < 4096) 1 else 0
    val n = math.max(numMasters, 1)
    val monitors = new StringBuilder
    for (i <- 0 until numMasters) {
      val p = f"`HC.m_axi_${i}%02d_"
      val pn = f"$i%02d"
      monitors.append(
        f"""
  // ---- m_axi_${i}%02d ----
  always @(posedge hc_clk) begin
    if (hc_rstn) begin
      automatic bit act = 0;
      if (${p}ARVALID && ${p}ARREADY) begin
        ar_cnt[$i]++; last_ar[$i] = ${p}ARADDR; act = 1;
        pc_account(0, ${p}ARADDR, ${p}ARLEN, ${p}ARSIZE);
        if (crosses_4k(${p}ARADDR, ${p}ARLEN, ${p}ARSIZE)) begin
          x4k_ar[$i]++;
          if (x4k_ar[$i] == 1) $$display("HC_TB_X4K: %%t first 4KB-crossing burst on m_axi_$pn: AR addr 0x%%0h len %%0d size %%0d (split in front of the NMU)", $$realtime, ${p}ARADDR, ${p}ARLEN, ${p}ARSIZE);
        end
      end
      if (${p}AWVALID && ${p}AWREADY) begin
        aw_cnt[$i]++; last_aw[$i] = ${p}AWADDR; act = 1;
        pc_account(1, ${p}AWADDR, ${p}AWLEN, ${p}AWSIZE);
        if (crosses_4k(${p}AWADDR, ${p}AWLEN, ${p}AWSIZE)) begin
          x4k_aw[$i]++;
          if (x4k_aw[$i] == 1) $$display("HC_TB_X4K: %%t first 4KB-crossing burst on m_axi_$pn: AW addr 0x%%0h len %%0d size %%0d (split in front of the NMU)", $$realtime, ${p}AWADDR, ${p}AWLEN, ${p}AWSIZE);
        end
      end
      if (${p}WVALID && ${p}WREADY) begin w_cnt[$i]++; act = 1; end
      if (${p}RVALID && ${p}RREADY) begin
        act = 1;
        if (${p}RLAST) r_cnt[$i]++;
        if (${p}RRESP[1]) begin err_cnt++; if (err_cnt <= 10) $$display("HC_TB_ERROR: %%t m_axi_$pn RRESP=%%0d (last ARADDR 0x%%0h)", $$realtime, ${p}RRESP, last_ar[$i]); end
      end
      if (${p}BVALID && ${p}BREADY) begin
        b_cnt[$i]++; act = 1; last_b_any = $$realtime;
        if (${p}BRESP[1]) begin err_cnt++; if (err_cnt <= 10) $$display("HC_TB_ERROR: %%t m_axi_$pn BRESP=%%0d (last AWADDR 0x%%0h)", $$realtime, ${p}BRESP, last_aw[$i]); end
      end
      if (act || (ar_cnt[$i] == r_cnt[$i] && aw_cnt[$i] == b_cnt[$i])) last_act[$i] = $$realtime;
    end
  end
""")
    }

    s"""`timescale 1ns / 1ps
// V80 QuestaSim testbench top (generated by TclQuestaSimV80). DPI-SC bridge for
// the C++ host testbench, plus progress reporting and a deadlock watchdog.
import axi_vip_pkg::*;
import design_1_axi_vip_0_0_pkg::*;
import design_1_axi_vip_1_0_pkg::*;

`define HC DUT.design_1_sw.design_1_wrapper_i.design_1_i.${name}_0

module main_sim(
    output logic HBM_CATTRIP_LS,
    output logic PCIE_PERST_LS_65,
    output logic SYSCLK2_clk_n,
    output logic SYSCLK2_clk_p,
    output logic SYSCLK3_clk_n,
    output logic SYSCLK3_clk_p,
    output logic axi_vip_clk,
    output logic axi_vip_aresetn
);

  export "DPI-SC" task S_AXI_READ_MEM;
  export "DPI-SC" task S_AXI_WRITE_MEM;
  export "DPI-SC" task S_AXI_READ_REG;
  export "DPI-SC" task S_AXI_WRITE_REG;

  localparam longint unsigned HBM_BASE = 64'h$hb;
  localparam longint unsigned REG_BASE = 64'h$rb;
  localparam int NM = $n;

  design_1_axi_vip_0_0_mst_t mst_agent_0_mem;
  design_1_axi_vip_1_0_mst_t mst_agent_1_reg;

  logic perst_n = 0;
  logic sysclk = 0;     // 100 MHz -> clk_wizard
  logic hbmclk_p = 1;   // 200 MHz HBM reference clock (both stacks)
  wire  cattrip;

  // The SystemC side only observes these; the testbench is clocked internally.
  assign HBM_CATTRIP_LS   = cattrip;
  assign PCIE_PERST_LS_65 = perst_n;
  assign SYSCLK2_clk_p    = sysclk;
  assign SYSCLK2_clk_n    = ~sysclk;
  assign SYSCLK3_clk_p    = hbmclk_p;
  assign SYSCLK3_clk_n    = ~hbmclk_p;
  assign axi_vip_clk      = 1'b0;
  assign axi_vip_aresetn  = perst_n;

  top_sim DUT(
      .HBM_CATTRIP_LS(cattrip),
      .PCIE_PERST_LS_65(perst_n),
      .sys_clk_100(sysclk),
      .hbm_ref_clk_n(~hbmclk_p),
      .hbm_ref_clk_p(hbmclk_p)
  );

  always #5    sysclk = ~sysclk;
  always #2.5  hbmclk_p = ~hbmclk_p;
  initial begin
    perst_n = 0;
    #200 perst_n = 1;
  end

  initial begin
    mst_agent_0_mem = new("master vip agent", DUT.design_1_sw.design_1_wrapper_i.design_1_i.axi_vip_0.inst.IF);
    mst_agent_0_mem.set_agent_tag("Master VIP 0");
    mst_agent_0_mem.set_verbosity(0);
    mst_agent_0_mem.start_master();
    mst_agent_1_reg = new("master vip agent", DUT.design_1_sw.design_1_wrapper_i.design_1_i.axi_vip_1.inst.IF);
    mst_agent_1_reg.set_agent_tag("Master VIP 1");
    mst_agent_1_reg.set_verbosity(0);
    mst_agent_1_reg.start_master();
    $$timeformat(-9, 1, " ns", 1);
  end

  // ------------------------------------------------------------------
  // Host-side bookkeeping
  // ------------------------------------------------------------------
  longint unsigned host_rr = 0, host_rw = 0, host_mr = 0, host_mw = 0, host_mr_bytes = 0, host_mw_bytes = 0;
  bit      host_busy = 0;
  realtime host_since = 0, host_last_end = 0;
  string   host_op = "";
  longint unsigned host_addr = 0;
  bit      warned_zero_hbm = 0, warned_zero_reg = 0;
  // Last write response seen by any design master / end of the last host VIP write
  // (HBM backdoor quiesce, see bd_quiesce).
  realtime last_b_any = 0, last_host_vip_w = 0;
  int      err_cnt = 0;

  task automatic host_begin(input string op, input longint unsigned a);
    host_busy = 1; host_since = $$realtime; host_op = op; host_addr = a;
  endtask
  task automatic host_end();
    host_busy = 0; host_last_end = $$realtime;
  endtask

  function automatic longint unsigned mem_addr(input longint unsigned a);
    if (a < HBM_BASE) begin
      if (!warned_zero_hbm) begin
        $$display("HC_TB_WARNING: host used HBM address 0x%0h below the V80 HBM base; adding 0x%0h. The driver should allocate from getBoardHbmBaseAddress().", a, HBM_BASE);
        warned_zero_hbm = 1;
      end
      return a + HBM_BASE;
    end
    return a;
  endfunction

  function automatic longint unsigned reg_addr(input longint unsigned a);
    if (a < REG_BASE) begin
      if (!warned_zero_reg) begin
        $$display("HC_TB_WARNING: host used register offset 0x%0h; adding the V80 register base 0x%0h.", a, REG_BASE);
        warned_zero_reg = 1;
      end
      return a + REG_BASE;
    end
    return a;
  endfunction

  xil_axi_uint id = xil_axi_uint'(0);
  xil_axi_data_beat [255:0] wuser = xil_axi_data_beat'(0);
  xil_axi_len_t   burst_length;
  xil_axi_size_t  burst_size = xil_axi_size_t'(2);  // 4 bytes
  xil_axi_size_t  burst_size_mem;
  xil_axi_burst_t burstType = xil_axi_burst_t'(2'b01);
  xil_axi_resp_t [255:0] resp;
  xil_axi_data_beat [255:0] ruser;
  xil_axi_resp_t  resp_w;

  task automatic check_resp_r(input string what, input longint unsigned a, input int beats);
    for (int i = 0; i < beats; i++)
      if (resp[i] != XIL_AXI_RESP_OKAY) begin
        err_cnt++;
        $$display("HC_TB_ERROR: %t host %s at 0x%0h beat %0d RRESP=%0d", $$realtime, what, a, i, resp[i]);
        break;
      end
  endtask

  // The memory VIP is 256-bit (the HBM NMU width). The host asks for beats of up to
  // 64 bytes (2^6, the U55C VIP width): a beat wider than the bus becomes 2^k beats
  // of 32 bytes. Both lay the bytes out contiguously, so RData/WData are unchanged.
  localparam int MEM_BUS_SIZE = 5;
  task automatic split_beats(input int len_, input int size_);
    int beats;
    beats = len_ + 1;
    if (size_ > MEM_BUS_SIZE) begin
      beats = beats << (size_ - MEM_BUS_SIZE);
      size_ = MEM_BUS_SIZE;
    end
    if (beats > 256) begin
      $$display("HC_TB_ERROR: host burst of %0d x %0d-byte beats exceeds 256 beats after splitting", len_ + 1, 1 << size_);
      err_cnt++;
      beats = 256;
    end
    burst_length = xil_axi_len_t'(beats - 1);
    burst_size_mem = xil_axi_size_t'(size_);
  endtask

  // AxCACHE = 2'b10 (modifiable) on the memory VIP, as in the hand V80 testbench.
  task S_AXI_READ_MEM(input xil_axi_ulong Addr, output bit [32768-1:0] RData, input int burst_length_, input int burst_size_);
    xil_axi_ulong a;
    bd_first_call_hook();
    a = mem_addr(Addr);
    host_begin("MEM_READ", a);
    split_beats(burst_length_, burst_size_);
    mst_agent_0_mem.AXI4_READ_BURST(id, a, burst_length, burst_size_mem, burstType,
        xil_axi_lock_t'(0), xil_axi_cache_t'(2'b10), xil_axi_prot_t'(0), xil_axi_region_t'(0),
        xil_axi_qos_t'(0), xil_axi_user_beat'(0), RData, resp, ruser);
    check_resp_r("MEM_READ", a, int'(burst_length) + 1);
    host_mr++; host_mr_bytes += (burst_length_ + 1) << burst_size_;
    host_end();
  endtask

  task S_AXI_WRITE_MEM(input xil_axi_ulong Addr, input bit [32768-1:0] WData, input int burst_length_, input int burst_size_);
    xil_axi_ulong a;
    bd_first_call_hook();
    a = mem_addr(Addr);
    host_begin("MEM_WRITE", a);
    split_beats(burst_length_, burst_size_);
    if (int'(burst_size_mem) < MEM_BUS_SIZE && burst_length != 0) begin
      // The HBM controller rejects narrow bursts with AxLEN > 0: send a narrow
      // (unaligned-address) copy as single-beat writes.
      int nb, bytes;
      nb = int'(burst_length) + 1;
      bytes = 1 << int'(burst_size_mem);
      for (int k = 0; k < nb; k++) begin
        mst_agent_0_mem.AXI4_WRITE_BURST(id, a + k * bytes, xil_axi_len_t'(0), burst_size_mem, burstType,
            xil_axi_lock_t'(0), xil_axi_cache_t'(2'b10), xil_axi_prot_t'(0), xil_axi_region_t'(0),
            xil_axi_qos_t'(0), xil_axi_user_beat'(0), WData >> (k * bytes * 8), wuser, resp_w);
        if (resp_w != XIL_AXI_RESP_OKAY) begin
          err_cnt++;
          $$display("HC_TB_ERROR: %t host MEM_WRITE at 0x%0h BRESP=%0d", $$realtime, a + k * bytes, resp_w);
        end
      end
    end else begin
      mst_agent_0_mem.AXI4_WRITE_BURST(id, a, burst_length, burst_size_mem, burstType,
          xil_axi_lock_t'(0), xil_axi_cache_t'(2'b10), xil_axi_prot_t'(0), xil_axi_region_t'(0),
          xil_axi_qos_t'(0), xil_axi_user_beat'(0), WData, wuser, resp_w);
      if (resp_w != XIL_AXI_RESP_OKAY) begin
        err_cnt++;
        $$display("HC_TB_ERROR: %t host MEM_WRITE at 0x%0h BRESP=%0d", $$realtime, a, resp_w);
      end
    end
    host_mw++; host_mw_bytes += (burst_length_ + 1) << burst_size_;
    last_host_vip_w = $$realtime;
    host_end();
  endtask

  // hc_mgmt_guard self-check, once, before the host's first register access: a
  // read and a write of the first undecoded register block (offset 0x${probeOff.toHexString})
  // must come back SLVERR from the guard (read data 0xBADADD00_00000000 | offset)
  // instead of hanging the management path. +HC_NO_GUARD_PROBE skips it.
  localparam bit GUARD_PROBE_ON = $probeOn;
  localparam longint unsigned GUARD_PROBE_OFF = 64'h${probeOff.toHexString};
  bit guard_probed = 0;
  task automatic guard_probe();
    bit [4096-1:0] pd;
    xil_axi_resp_t [255:0] pr;
    xil_axi_resp_t pw;
    realtime t0;
    longint unsigned pa;
    guard_probed = 1;
    if (!GUARD_PROBE_ON || $$test$$plusargs("HC_NO_GUARD_PROBE")) return;
    pa = REG_BASE + GUARD_PROBE_OFF;
    t0 = $$realtime;
    host_begin("GUARD_PROBE_READ", pa);
    mst_agent_1_reg.AXI4_READ_BURST(id, pa, xil_axi_len_t'(1), burst_size, burstType,
        xil_axi_lock_t'(0), xil_axi_cache_t'(0), xil_axi_prot_t'(0), xil_axi_region_t'(0),
        xil_axi_qos_t'(0), xil_axi_user_beat'(0), pd, pr, ruser);
    host_end();
    if (pr[0] != XIL_AXI_RESP_SLVERR || pr[1] != XIL_AXI_RESP_SLVERR || pd[63:32] != 32'hBADADD00 ||
        pd[11:0] != GUARD_PROBE_OFF[11:0]) begin
      err_cnt++;
      $$display("HC_TB_ERROR: %t guard probe: read of undecoded register 0x%0h returned RRESP=%0d/%0d data 0x%016h (expected SLVERR, 0xbadadd00_%08h)",
                $$realtime, pa, pr[0], pr[1], pd[63:0], GUARD_PROBE_OFF);
    end
    host_begin("GUARD_PROBE_WRITE", pa);
    mst_agent_1_reg.AXI4_WRITE_BURST(id, pa, xil_axi_len_t'(1), burst_size, burstType,
        xil_axi_lock_t'(0), xil_axi_cache_t'(0), xil_axi_prot_t'(0), xil_axi_region_t'(0),
        xil_axi_qos_t'(0), xil_axi_user_beat'(0), {4096{1'b0}}, wuser, pw);
    host_end();
    if (pw != XIL_AXI_RESP_SLVERR) begin
      err_cnt++;
      $$display("HC_TB_ERROR: %t guard probe: write of undecoded register 0x%0h returned BRESP=%0d (expected SLVERR)",
                $$realtime, pa, pw);
    end else if (pr[0] == XIL_AXI_RESP_SLVERR) begin
      $$display("HC_GUARD_PROBE: PASS -- read and write of undecoded register 0x%0h answered SLVERR (data 0x%016h) in %0t",
                pa, pd[63:0], $$realtime - t0);
    end
  endtask

  task S_AXI_READ_REG(input xil_axi_ulong Addr, output bit [4096-1:0] RData, input int size_in_bytes);
    xil_axi_ulong a;
    assert (size_in_bytes <= 4096);
    bd_first_call_hook();
    a = reg_addr(Addr);
    host_begin("REG_READ", a);
    burst_length = xil_axi_len_t'(size_in_bytes/4 - 1);
    mst_agent_1_reg.AXI4_READ_BURST(id, a, burst_length, burst_size, burstType,
        xil_axi_lock_t'(0), xil_axi_cache_t'(0), xil_axi_prot_t'(0), xil_axi_region_t'(0),
        xil_axi_qos_t'(0), xil_axi_user_beat'(0), RData, resp, ruser);
    check_resp_r("REG_READ", a, size_in_bytes/4);
    host_rr++;
    host_end();
  endtask

  task S_AXI_WRITE_REG(input xil_axi_ulong Addr, input bit [4096-1:0] WData, input int size_in_bytes);
    xil_axi_ulong a;
    assert (size_in_bytes <= 4096);
    bd_first_call_hook();
    a = reg_addr(Addr);
    host_begin("REG_WRITE", a);
    burst_length = xil_axi_len_t'(size_in_bytes/4 - 1);
    mst_agent_1_reg.AXI4_WRITE_BURST(id, a, burst_length, burst_size, burstType,
        xil_axi_lock_t'(0), xil_axi_cache_t'(0), xil_axi_prot_t'(0), xil_axi_region_t'(0),
        xil_axi_qos_t'(0), xil_axi_user_beat'(0), WData, wuser, resp_w);
    if (resp_w != XIL_AXI_RESP_OKAY) begin
      err_cnt++;
      $$display("HC_TB_ERROR: %t host REG_WRITE at 0x%0h BRESP=%0d", $$realtime, a, resp_w);
    end
    host_rw++;
    host_end();
  endtask

  // ------------------------------------------------------------------
  // Design-side AXI monitors (one per exported m_axi_XX)
  // ------------------------------------------------------------------
  wire hc_clk  = DUT.design_1_sw.design_1_wrapper_i.design_1_i.clk_wizard_0.clk_out1;
  wire hc_rstn = DUT.design_1_sw.design_1_wrapper_i.design_1_i.proc_sys_reset_0.peripheral_aresetn;
  longint unsigned ar_cnt[NM], aw_cnt[NM], w_cnt[NM], r_cnt[NM], b_cnt[NM];
  // Bursts of the design's own masters that cross a 4KB boundary (AXI forbids
  // it; hc_axi_4k_split in front of each NMU splits them).
  longint unsigned x4k_ar[NM], x4k_aw[NM];
  function automatic bit crosses_4k(input longint unsigned addr, input int len, input int size);
    longint unsigned off;
    off = (addr & 64'hFFF) & ~((64'd1 << size) - 1);
    return off + ((longint'(len) + 1) << size) > 4096;
  endfunction
  function automatic string x4k_str();
    string r = "";
    for (int i = 0; i < NM; i++)
      if (x4k_aw[i] + x4k_ar[i] != 0) r = {r, (r == "") ? "" : ",", $$sformatf("%02d:aw%0d/ar%0d", i, x4k_aw[i], x4k_ar[i])};
    return (r == "") ? "none" : r;
  endfunction
  longint unsigned last_ar[NM], last_aw[NM];
  realtime last_act[NM];
  initial for (int i = 0; i < NM; i++) begin
    x4k_ar[i] = 0; x4k_aw[i] = 0;
    ar_cnt[i] = 0; aw_cnt[i] = 0; w_cnt[i] = 0; r_cnt[i] = 0; b_cnt[i] = 0;
    last_ar[i] = 0; last_aw[i] = 0; last_act[i] = 0;
  end

  // HBM striping (HC_HBM_STRIPING, TclV80Common.stripeVerilog): the address the
  // design's logical address L reaches HBM at. The backdoor (bd_locate) and the
  // per-pseudo-channel traffic counters below apply it.
  localparam int HC_STRIPE_MODE = ${if (TclHbmex.enabled) TclHbmex.svMode else TclV80Common.hbmStripeMode};
  function automatic longint unsigned hc_stripe_map(input longint unsigned a);
    longint unsigned p;
    bit [4:0] h;
    p = a;
    if (HC_STRIPE_MODE == ${TclHbmex.svMode}) begin  // HBMEX swap (TclHbmex.map)
      ${TclHbmex.svMapBody}
    end else if (HC_STRIPE_MODE != 0) begin
      h = (HC_STRIPE_MODE == 2) ? (a[21:17] ^ a[26:22] ^ a[31:27] ^ {2'b00, a[34:32]}) : 5'd0;
      p[34:30] = a[16:12] ^ h;
      p[29:12] = a[34:17];
    end
    return p;
  endfunction
  // Design traffic per HBM pseudo channel (0..31 = offset[34:30] after striping),
  // counted per 4 KB piece of every AR/AW the design's masters issue.
  longint unsigned pc_rd_bytes[32], pc_wr_bytes[32], pc_rd_req[32], pc_wr_req[32];
  initial for (int i = 0; i < 32; i++) begin pc_rd_bytes[i] = 0; pc_wr_bytes[i] = 0; pc_rd_req[i] = 0; pc_wr_req[i] = 0; end
  function automatic void pc_account(input bit wr, input longint unsigned addr, input int len, input int size);
    longint unsigned a, e, n;
    int pc;
    a = addr & ~((64'd1 << size) - 1);
    e = a + ((longint'(len) + 1) << size);
    while (a < e) begin
      n = 4096 - (a & 64'hFFF);
      if (n > e - a) n = e - a;
      pc = int'(((hc_stripe_map(a) - HBM_BASE) >> 30) & 31);
      if (wr) begin pc_wr_bytes[pc] += n; pc_wr_req[pc]++; end
      else begin pc_rd_bytes[pc] += n; pc_rd_req[pc]++; end
      a += n;
    end
  endfunction
  task automatic pc_summary();
    longint unsigned tot = 0, mx = 0, t;
    int used = 0;
    string rd = "", wr = "";
    for (int i = 0; i < 32; i++) begin
      t = pc_rd_bytes[i] + pc_wr_bytes[i];
      tot += t; if (t > mx) mx = t; if (t != 0) used++;
      rd = {rd, $$sformatf(" %0d", pc_rd_bytes[i])};
      wr = {wr, $$sformatf(" %0d", pc_wr_bytes[i])};
    end
    $$display("HC_TB_PCDIST: striping mode %0d; design HBM traffic %0d B over %0d of 32 pseudo channels; busiest PC %0d B (%0.2fx the 32-PC mean)",
              HC_STRIPE_MODE, tot, used, mx, (tot == 0) ? 0.0 : (real'(mx) * 32.0 / real'(tot)));
    $$display("HC_TB_PCDIST: rd_bytes[pc0..31]:%s", rd);
    $$display("HC_TB_PCDIST: wr_bytes[pc0..31]:%s", wr);
  endtask
${monitors.toString}
${hbmBackdoorSv}
  // ------------------------------------------------------------------
  // Progress print + watchdog
  // ------------------------------------------------------------------
  int progress_us, txn_timeout_us, host_idle_us, max_sim_us;
  longint unsigned prev_total = 0;

  task automatic dump_ports(input string why);
    $$display("HC_TB_FATAL: %s at %t", why, $$realtime);
    $$display("HC_TB_FATAL: host busy=%0d op=%s addr=0x%0h since=%t; reg r/w=%0d/%0d mem r/w=%0d/%0d",
              host_busy, host_op, host_addr, host_since, host_rr, host_rw, host_mr, host_mw);
    for (int i = 0; i < NM; i++)
      $$display("HC_TB_FATAL:   m_axi_%02d AR=%0d R=%0d (out %0d) AW=%0d W=%0d B=%0d (out %0d) lastAR=0x%0h lastAW=0x%0h idle_since=%t",
                i, ar_cnt[i], r_cnt[i], ar_cnt[i] - r_cnt[i], aw_cnt[i], w_cnt[i], b_cnt[i], aw_cnt[i] - b_cnt[i],
                last_ar[i], last_aw[i], last_act[i]);
    $$display("HC_TB_FATAL: paused=%0d done=%0d", `HC.paused, `HC.done);
  endtask

  initial begin
    if (!$$value$$plusargs("HC_PROGRESS_US=%d", progress_us)) progress_us = 50;
    if (!$$value$$plusargs("HC_TXN_TIMEOUT_US=%d", txn_timeout_us)) txn_timeout_us = 200;
    if (!$$value$$plusargs("HC_HOST_IDLE_US=%d", host_idle_us)) host_idle_us = 50;
    if (!$$value$$plusargs("HC_MAX_SIM_US=%d", max_sim_us)) max_sim_us = 50000;
    $$display("HC_TB: V80 testbench, ${name}, $freqMhz MHz, HBM base 0x%0h, register base 0x%0h, %0d master(s); progress every %0d us, txn timeout %0d us, host idle finish %0d us, max %0d us",
              HBM_BASE, REG_BASE, NM, progress_us, txn_timeout_us, host_idle_us, max_sim_us);
    forever begin
      longint unsigned tar, tr, taw, tb, tout;
      #(progress_us * 1us);
      tar = 0; tr = 0; taw = 0; tb = 0; tout = 0;
      for (int i = 0; i < NM; i++) begin
        tar += ar_cnt[i]; tr += r_cnt[i]; taw += aw_cnt[i]; tb += b_cnt[i];
        tout += (ar_cnt[i] - r_cnt[i]) + (aw_cnt[i] - b_cnt[i]);
      end
      $$display("HC_PROGRESS: t=%0.1f us host[reg r/w %0d/%0d, mem r/w %0d/%0d (%0d/%0d B)%s] design[AR %0d R %0d AW %0d B %0d outstanding %0d (+%0d since last)] paused=%0d done=%0d errors=%0d x4k=[%s]%s",
                $$realtime / 1000.0, host_rr, host_rw, host_mr, host_mw, host_mr_bytes, host_mw_bytes,
                host_busy ? {" busy ", host_op} : "", tar, tr, taw, tb, tout, (tr + tb) - prev_total,
                `HC.paused, `HC.done, err_cnt, x4k_str(), bd_str());
      prev_total = tr + tb;
      if (err_cnt > 0) begin dump_ports("AXI error response(s), see HC_TB_ERROR above"); $$fatal(1); end
      for (int i = 0; i < NM; i++)
        if ((ar_cnt[i] != r_cnt[i] || aw_cnt[i] != b_cnt[i]) && ($$realtime - last_act[i]) > txn_timeout_us * 1us) begin
          dump_ports($$sformatf("m_axi_%02d has outstanding transaction(s) and no handshake for %0d us (deadlock?)", i, txn_timeout_us));
          $$fatal(1);
        end
      if (host_busy && ($$realtime - host_since) > txn_timeout_us * 1us) begin
        dump_ports($$sformatf("host %s at 0x%0h has not completed in %0d us (deadlock?)", host_op, host_addr, txn_timeout_us));
        $$fatal(1);
      end
      if (!host_busy && (host_rr + host_rw + host_mr + host_mw) > 0 && ($$realtime - host_last_end) > host_idle_us * 1us) begin
        $$display("HC_TB: host testbench thread made no call for %0d us: it has returned. Finishing at %t.", host_idle_us, $$realtime);
        $$display("HC_TB_X4K: 4KB-crossing bursts per master port (AW/AR): %s", x4k_str());
        bd_summary();
        pc_summary();
        $$display("HC_TB_DONE");
        $$finish;
      end
      if ($$realtime > max_sim_us * 1us) begin
        dump_ports($$sformatf("simulated-time limit +HC_MAX_SIM_US=%0d reached", max_sim_us));
        $$fatal(1);
      end
    end
  end

endmodule
"""
  }

  // ==================================================================
  // HBM backdoor for zero-time host copies
  // ==================================================================

  /** Registry the HBM device-model stand-ins register with (sim_src/hc_hbm_bd_pkg.sv). */
  private val hbmBdPkg: String =
    """`timescale 1ns / 1ps
// HBM backdoor registry (generated by TclQuestaSimV80). Each HBM stack's device
// model (the plaintext hbm_responder stand-in compiled by simulate.sh) registers a
// handle here at time 0; main_sim uses it for zero-time host copies. When the
// encrypted Xilinx model is simulated instead (HC_HBM_MODEL=xilinx), nothing
// registers and the host falls back to the AXI VIP.
package hc_hbm_bd_pkg;

  virtual class hc_hbm_stack_bd;
    // ch: channel 0..7 of the stack, pc: pseudo channel, w30: byte address inside
    // the 1 GB pseudo channel (the MC's AXI-side address). A DRAM word is 32 bytes.
    // get_word returns 0 when the word was never written (data = init value).
    pure virtual function bit get_word(int ch, bit pc, longint unsigned w30, output bit [255:0] data);
    pure virtual function void set_word(int ch, bit pc, longint unsigned w30, input bit [255:0] data);
    // Simulated time, in ns, of the last DRAM column write / reset seen by this
    // stack's model (in ns whatever the caller's timescale).
    pure virtual function real last_mem_write_ns();
    pure virtual function int unsigned resets_seen();
    pure virtual function bit in_reset();
    pure virtual function string describe(int ch, bit pc, longint unsigned w30);
    // Number of written 32-byte words held by the model (all channels).
    pure virtual function longint unsigned words_held();
    // Debug: where (if anywhere) a 64-bit value is stored in this stack's model.
    pure virtual function string find64(bit [63:0] v);
  endclass

  hc_hbm_stack_bd stacks[2];

endpackage
"""

  private val hbmMapDefault =
    "RA14,RA13,RA12,RA11,RA10,RA9,RA8,RA7,RA6,RA5,RA4,RA3,RA2,RA1,RA0,SID,BA1,BA0,BA3,CA5,CA4,CA3,CA2,CA1,BA2,NC,NA,NA,NA,NA"

  /**
    * Plaintext stand-in for the encrypted Versal HBM2e device model (module
    * `hbm_responder` of noc_hbm_responder, instantiated as `hbm_mem` in each HBM
    * stack wrapper). Same module name, parameters and pins, so the generated stack
    * wrappers bind to it (vopt -Lf hc_hbm_model) with the MC/PHY BFMs, NMUs and NoC
    * unchanged. The DRAM itself is AMD's plaintext HBM2/HBM2e responder shipped with
    * Vivado (data/ip/xilinx/hbm_v1_0/verif/model/HBM_xsim.sv, renamed
    * hc_hbm_xsim_model by build_hbm_model.sh), whose per-channel associative memory
    * the testbench reads/writes directly through [[hbmBdPkg]].
    */
  private def hbmResponderWrapper: String = {
    val chs = "abcdefgh"
    val sb = new StringBuilder
    sb.append("""`timescale 1ps/1ps
// HardCilk V80 QuestaSim (generated by TclQuestaSimV80): plaintext stand-in for the
// encrypted Versal HBM2e device model 'hbm_responder', with a backdoor for the
// testbench (zero-time host copies). Compiled into library hc_hbm_model by
// build_hbm_model.sh and bound with vopt -Lf hc_hbm_model; HC_HBM_MODEL=xilinx
// simulates the encrypted model instead.
module hbm_responder #(
""")
    val params =
      (for (c <- chs.toUpperCase; pc <- 0 to 1)
        yield "  parameter C_HBM_ADDRESS_MAP_CH" + c + "_PC" + pc + " = \"" + hbmMapDefault + "\"") ++ Seq(
        "  parameter C_HBM_DEVICE_STACK_NUMBER = 0",
        "  parameter C_HBM_MEM_BACKDOOR_WRITE = \"DISABLED\"",
        "  parameter C_HBM_MEM_INIT_MODE = \"INIT_0\"",
        "  parameter C_HBM_MEM_BACKDOOR_FILE = \"no_file_loaded\"",
        "  parameter C_HBM_DENSITY_CONFIG = \"16G8H\"",
        "  parameter real C_HBM_TDQSCK = 0.6",
        "  parameter C_HBM_TDQSQ = 0",
        "  parameter C_HBM_TDQSS = 0"
      )
    sb.append(params.mkString(",\n")).append("\n) (\n")
    val ports = chs.toSeq.flatMap { c =>
      Seq(
        s"  input          CH${c}_hbm_ck_t", s"  input          CH${c}_hbm_ck_c", s"  input          CH${c}_hbm_cke",
        s"  input  [8:0]   CH${c}_hbm_c", s"  input  [6:0]   CH${c}_hbm_r",
        s"  inout  [127:0] CH${c}_hbm_dq", s"  inout  [15:0]  CH${c}_hbm_dbi", s"  inout  [15:0]  CH${c}_hbm_dm",
        s"  inout  [3:0]   CH${c}_hbm_par", s"  output [3:0]   CH${c}_hbm_derr", s"  output         CH${c}_hbm_aerr",
        s"  input  [3:0]   CH${c}_hbm_wdqs_t", s"  input  [3:0]   CH${c}_hbm_wdqs_c",
        s"  output [3:0]   CH${c}_hbm_rdqs_t", s"  output [3:0]   CH${c}_hbm_rdqs_c",
        s"  input          CH${c}_hbm_rr", s"  input          CH${c}_hbm_rc", s"  inout  [7:0]   CH${c}_hbm_rd"
      )
    } ++ Seq("  inout  [2:0]   TEMP", "  inout          CATRIP", "  input          reset_n")
    sb.append(ports.mkString(",\n")).append("\n);\n\n")
    sb.append(hbmWrapperBody)
    sb.append("""  hc_hbm_xsim_model #(
      .C_HBM_VERSION("HBM2e"),
      .C_HBM_DENSITY_CONFIG(C_HBM_DENSITY_CONFIG),
      .C_HBM_MEM_INIT_MODE(C_HBM_MEM_INIT_MODE),
      .C_HBM_TDQSCK(C_HBM_TDQSCK),
      .C_HBM_TDQSQ(C_HBM_TDQSQ),
      .C_HBM_TDQSS(C_HBM_TDQSS)
  ) model (
      .RESET_n(reset_n), .TEMP0(1'b0), .TEMP1(1'b0), .TEMP2(1'b0), .CATTRIP(1'b0),
      .DA(), .WRCK(1'b0), .WRST_n(1'b1), .SelectWIR(1'b0), .ShiftWR(1'b0), .CaptureWR(1'b0),
      .UpdateWR(1'b0), .WSI(1'b0), .WSO()""")
    val pins = Seq("ck_t", "ck_c", "cke", "r", "c", "par", "dm", "dbi", "dq", "wdqs_t", "wdqs_c", "rdqs_t", "rdqs_c",
      "derr", "aerr", "rr", "rc", "rd")
    for (c <- chs; p <- pins) sb.append(s",\n      .${p}_CH${c.toUpper}(CH${c}_hbm_$p)")
    sb.append("\n  );\nendmodule\n")
    sb.toString
  }

  /** Address-map decode, quiesce bookkeeping and the backdoor class of the stand-in. */
  private val hbmWrapperBody: String =
    """  // ------------------------------------------------------------------
  // Backdoor: AXI-side (MC) byte address inside a pseudo channel -> model word.
  // The MC address map strings (one per channel/PC, MSB first, one token per
  // address bit) say which DRAM address bit each MC address bit drives; NA/NC
  // bits are the byte offset inside a 32-byte DRAM word.
  // ------------------------------------------------------------------
  localparam int MAXB = 40;
  // kind: 0 byte-offset (NA/NC), 1 RA, 2 BA, 3 CA, 4 SID
  int unsigned map_kind [8][2][MAXB];
  int unsigned map_idx  [8][2][MAXB];
  int unsigned map_bits [8][2];
  bit          map_ok = 0;

  function automatic void parse_map(int ch, int pc, string s);
    string tok;
    string toks[$];
    int n;
    tok = "";
    for (int i = 0; i < s.len(); i++) begin
      if (s[i] == ",") begin toks.push_back(tok); tok = ""; end
      else if (s[i] != " ") tok = {tok, s[i]};
    end
    if (tok != "") toks.push_back(tok);
    n = toks.size();
    map_bits[ch][pc] = n;
    for (int k = 0; k < n; k++) begin
      int pos;
      string t;
      pos = n - 1 - k;
      t = toks[k];
      if (t == "NA" || t == "NC") begin map_kind[ch][pc][pos] = 0; map_idx[ch][pc][pos] = 0; end
      else if (t == "SID") begin map_kind[ch][pc][pos] = 4; map_idx[ch][pc][pos] = 0; end
      else if (t.substr(0, 2) == "SID") begin map_kind[ch][pc][pos] = 4; map_idx[ch][pc][pos] = t.substr(3, t.len() - 1).atoi(); end
      else if (t.substr(0, 1) == "RA") begin map_kind[ch][pc][pos] = 1; map_idx[ch][pc][pos] = t.substr(2, t.len() - 1).atoi(); end
      else if (t.substr(0, 1) == "BA") begin map_kind[ch][pc][pos] = 2; map_idx[ch][pc][pos] = t.substr(2, t.len() - 1).atoi(); end
      else if (t.substr(0, 1) == "CA") begin map_kind[ch][pc][pos] = 3; map_idx[ch][pc][pos] = t.substr(2, t.len() - 1).atoi(); end
      else $display("HC_HBM_MODEL_ERROR: %m: unknown HBM address map token '%s' in \"%s\"", t, s);
    end
  endfunction

  initial begin
    parse_map(0, 0, C_HBM_ADDRESS_MAP_CHA_PC0); parse_map(0, 1, C_HBM_ADDRESS_MAP_CHA_PC1);
    parse_map(1, 0, C_HBM_ADDRESS_MAP_CHB_PC0); parse_map(1, 1, C_HBM_ADDRESS_MAP_CHB_PC1);
    parse_map(2, 0, C_HBM_ADDRESS_MAP_CHC_PC0); parse_map(2, 1, C_HBM_ADDRESS_MAP_CHC_PC1);
    parse_map(3, 0, C_HBM_ADDRESS_MAP_CHD_PC0); parse_map(3, 1, C_HBM_ADDRESS_MAP_CHD_PC1);
    parse_map(4, 0, C_HBM_ADDRESS_MAP_CHE_PC0); parse_map(4, 1, C_HBM_ADDRESS_MAP_CHE_PC1);
    parse_map(5, 0, C_HBM_ADDRESS_MAP_CHF_PC0); parse_map(5, 1, C_HBM_ADDRESS_MAP_CHF_PC1);
    parse_map(6, 0, C_HBM_ADDRESS_MAP_CHG_PC0); parse_map(6, 1, C_HBM_ADDRESS_MAP_CHG_PC1);
    parse_map(7, 0, C_HBM_ADDRESS_MAP_CHH_PC0); parse_map(7, 1, C_HBM_ADDRESS_MAP_CHH_PC1);
    map_ok = 1;
  end

  function automatic void decode(int ch, bit pc, longint unsigned w30, output bit [1:0] sid,
      output bit [3:0] ba, output bit [14:0] row, output bit [5:0] col);
    sid = 0; ba = 0; row = 0; col = 0;
    for (int pos = 0; pos < map_bits[ch][pc]; pos++) begin
      bit b;
      int unsigned ix;
      b = w30[pos];
      ix = map_idx[ch][pc][pos];
      case (map_kind[ch][pc][pos])
        1: row[ix] = b;
        2: ba[ix] = b;
        3: col[ix] = b;
        4: sid[ix] = b;
        default: ;
      endcase
    end
  endfunction

  // DRAM activity seen by the model (column writes), for the testbench's quiesce check.
  realtime          last_wr_t = 0;
  int unsigned      reset_cnt = 0;
  always @(negedge reset_n) begin reset_cnt++; last_wr_t = $realtime; end

  class bd_impl extends hc_hbm_bd_pkg::hc_hbm_stack_bd;
    virtual function bit get_word(int ch, bit pc, longint unsigned w30, output bit [255:0] data);
      bit [1:0] sid; bit [3:0] ba; bit [14:0] row; bit [5:0] col;
      decode(ch, pc, w30, sid, ba, row, col);
      return model.hc_bd_get(ch, model.hbm_mem_ch[ch].get_mem_addr(pc, sid, ba, row, col), data);
    endfunction
    virtual function void set_word(int ch, bit pc, longint unsigned w30, input bit [255:0] data);
      bit [1:0] sid; bit [3:0] ba; bit [14:0] row; bit [5:0] col;
      decode(ch, pc, w30, sid, ba, row, col);
      model.hc_bd_set(ch, model.hbm_mem_ch[ch].get_mem_addr(pc, sid, ba, row, col), data);
    endfunction
    virtual function real last_mem_write_ns();
      // Both are in this module's (and the model's) 1 ps time unit.
      return ((model.hc_last_wr_t > last_wr_t) ? model.hc_last_wr_t : last_wr_t) / 1000.0;
    endfunction
    virtual function int unsigned resets_seen();
      return reset_cnt;
    endfunction
    virtual function bit in_reset();
      return reset_n !== 1'b1;
    endfunction
    virtual function string describe(int ch, bit pc, longint unsigned w30);
      bit [1:0] sid; bit [3:0] ba; bit [14:0] row; bit [5:0] col;
      decode(ch, pc, w30, sid, ba, row, col);
      return $sformatf("stack%0d CH%0d PC%0d SID%0d BA%0h ROW%0h COL%0h byte%0d (word 0x%0h)", C_HBM_DEVICE_STACK_NUMBER,
                       ch, pc, sid, ba, row, col, w30[4:0], model.hbm_mem_ch[ch].get_mem_addr(pc, sid, ba, row, col));
    endfunction
    virtual function longint unsigned words_held();
      longint unsigned n = 0;
      for (int c = 0; c < 8; c++) if (model.hbm_mem_ch[c] != null) n += model.hbm_mem_ch[c].hbm_memory.num();
      return n;
    endfunction
    virtual function string find64(bit [63:0] v);
      string r = "";
      for (int c = 0; c < 8; c++)
        if (model.hbm_mem_ch[c] != null)
          foreach (model.hbm_mem_ch[c].hbm_memory[a])
            for (int l = 0; l < 4; l++)
              if (model.hbm_mem_ch[c].hbm_memory[a][l*64 +: 64] == v)
                r = {r, $sformatf(" [stack%0d CH%0d word 0x%0h lane%0d]", C_HBM_DEVICE_STACK_NUMBER, c, a, l)};
      return r;
    endfunction
  endclass

  initial begin
    bd_impl h;
    h = new();
    hc_hbm_bd_pkg::stacks[C_HBM_DEVICE_STACK_NUMBER] = h;
  end

"""

  /**
    * Patches AMD's plaintext HBM responder into hc_hbm_xsim_model (renamed module and
    * interface, a column-write timestamp, backdoor accessors) and compiles it with the
    * stand-in wrapper into library hc_hbm_model of the Questa simulation directory.
    */
  private val buildHbmModelSh: String =
    """#!/bin/bash
# Builds the backdoor-capable HBM device model for the V80 QuestaSim testbench
# (generated by TclQuestaSimV80). Usage: build_hbm_model.sh <questa sim dir>
# Needs XILINX_VIVADO (Vivado settings64.sh) and Questa on PATH. The model is AMD's
# plaintext HBM2/HBM2e responder from the Vivado install, renamed and given a
# column-write timestamp and backdoor accessors; hc_hbm_responder.sv (next to this
# script) wraps it with the pins and parameters of the encrypted Versal model.
set -e
here=$(cd "$(dirname "$0")" && pwd)
simdir=$(cd "$1" && pwd)
src="$XILINX_VIVADO/data/ip/xilinx/hbm_v1_0/verif/model/HBM_xsim.sv"
if [ ! -f "$src" ]; then
  echo "HardCilk: $src not found; it is needed for the HBM backdoor model (HC_HBM_MODEL=xilinx simulates the encrypted model instead)"
  exit 1
fi
out="$simdir/hc_hbm_xsim_model.sv"
sed -e 's/^module HBM#(/module hc_hbm_xsim_model#(/' \
    -e 's/\bhbm2_responder_if\b/hc_hbm2_responder_if/g' \
    -e 's/__HBM2_RESPONDER_IF_SV__/__HC_HBM2_RESPONDER_IF_SV__/g' \
    -e 's/__HBM2_RESPONDER_SV__/__HC_HBM2_RESPONDER_SV__/g' \
    -e 's|^class hbm_responder_class;|realtime hc_last_wr_t = 0; // HardCilk: time of the last column write\n&|' \
    -e 's|^  if(reg_mr0_dbi_ac_write == 1) begin /\*Write DBI Enabled\*/|  hc_last_wr_t = $realtime;\n&|' \
    -e 's|^        reg_mr0_dbi_ac_read             = mrs_opcode\[0\];|        reg_mr0_dbi_ac_read             = 1'"'"'b0; // HardCilk: the Versal HBM controller/PHY BFMs set MR0 read DBI but do not undo it|' \
    "$src" > "$out.tmp"
last=$(grep -n '^endmodule' "$out.tmp" | tail -1 | cut -d: -f1)
head -n $((last - 1)) "$out.tmp" > "$out"
cat >> "$out" <<'HC_EOF'
// ---- HardCilk testbench backdoor (added by build_hbm_model.sh) ----
function automatic bit hc_bd_get(int ch, bit [C_HBM_MEM_ADDR_WIDTH-1:0] a, output bit [C_HBM_BURST_DATA_WIDTH-1:0] d);
  if (hbm_mem_ch[ch].hbm_memory.exists(a)) begin d = hbm_mem_ch[ch].hbm_memory[a]; return 1; end
  d = (C_HBM_MEM_INIT_MODE == "INIT_1") ? {C_HBM_BURST_DATA_WIDTH{1'b1}} : {C_HBM_BURST_DATA_WIDTH{1'b0}};
  return 0;
endfunction
function automatic void hc_bd_set(int ch, bit [C_HBM_MEM_ADDR_WIDTH-1:0] a, input bit [C_HBM_BURST_DATA_WIDTH-1:0] d);
  // Unwritten words read as zero (INIT_0): storing zeros there would only cost memory.
  if (C_HBM_MEM_INIT_MODE == "INIT_0" && d == '0 && !hbm_mem_ch[ch].hbm_memory.exists(a)) return;
  hbm_mem_ch[ch].hbm_memory[a] = d;
endfunction
HC_EOF
tail -n +$last "$out.tmp" >> "$out"
rm -f "$out.tmp"
if [ "$(grep -c 'hc_last_wr_t = \$realtime' "$out")" != 1 ] || [ "$(grep -c '^module hc_hbm_xsim_model' "$out")" != 1 ] ||
   [ "$(grep -c 'HardCilk: the Versal HBM controller/PHY BFMs set MR0 read DBI' "$out")" != 1 ]; then
  echo "HardCilk: patching $src for the HBM backdoor failed (Vivado version changed?); HC_HBM_MODEL=xilinx simulates the encrypted model"
  exit 1
fi
cd "$simdir"
rm -rf questa_lib/msim/hc_hbm_model
vlib questa_lib/msim/hc_hbm_model > /dev/null
vmap hc_hbm_model questa_lib/msim/hc_hbm_model > /dev/null
if ! vlog -64 -sv -work hc_hbm_model -L xil_defaultlib -l build_hbm_model.log hc_hbm_xsim_model.sv "$here/hc_hbm_responder.sv" > /dev/null; then
  echo "HardCilk: compiling the HBM backdoor model failed (see $simdir/build_hbm_model.log)"
  exit 1
fi
echo "HardCilk: built the backdoor-capable HBM model (library hc_hbm_model)"
"""

  /** main_sim.sv part: DPI exports for backdoor copies, quiesce, self-test. */
  private val hbmBackdoorSv: String =
    """
  // ------------------------------------------------------------------
  // HBM backdoor: zero-time host copies straight into the HBM device model
  // (memIO_questa.h uses these when HC_SIM_BACKDOOR != 0). The design's own
  // traffic still goes NMU -> NoC -> HBM MC -> PHY -> device model.
  //   +HC_NO_BACKDOOR           host copies use the memory VIP
  //   +HC_BD_QUIET_NS=n         time since the last write response (design or host
  //                             VIP) and the last DRAM column write required before
  //                             a backdoor access (default 1000)
  //   +HC_BD_WRITE_WAIT_NS=n    a host write waits up to n ns for that quiet, then
  //                             goes through the VIP (default 2000)
  //   +HC_BD_READ_WAIT_NS=n     same for host reads (default 0: a read while the
  //                             design is writing, e.g. polling, uses the VIP at once)
  //   A copy that is not quiet is done through the VIP, 4 KB chunk by chunk (the
  //   host retries the backdoor for the next chunk), so copies never lose coherence
  //   and the host's timing while the design runs stays as with the VIP.
  //   +HC_BD_SELFTEST=n         before the first host call, check the address map
  //                             VIP<->backdoor at n addresses per pseudo channel
  //   +HC_BD_SELFTEST_ONLY      finish after the self-test
  // ------------------------------------------------------------------
  export "DPI-SC" function HC_BD_AVAILABLE;
  export "DPI-SC" task HC_BD_WRITE;
  export "DPI-SC" task HC_BD_READ;

  localparam longint unsigned HBM_SIZE = 64'h800000000;
  int bd_quiet_ns = 1000, bd_write_wait_ns = 2000, bd_read_wait_ns = 0;
  bit bd_ready = 0, bd_written = 0, bd_selftest_done = 0, bd_announced = 0;
  int unsigned bd_resets0[2];
  longint unsigned bd_w_bytes = 0, bd_r_bytes = 0, bd_w_calls = 0, bd_r_calls = 0, bd_fallbacks = 0;
  realtime bd_wait_total = 0;

  initial begin
    void'($value$plusargs("HC_BD_QUIET_NS=%d", bd_quiet_ns));
    void'($value$plusargs("HC_BD_WRITE_WAIT_NS=%d", bd_write_wait_ns));
    void'($value$plusargs("HC_BD_READ_WAIT_NS=%d", bd_read_wait_ns));
  end

  function automatic bit bd_have();
    return hc_hbm_bd_pkg::stacks[0] != null && hc_hbm_bd_pkg::stacks[1] != null;
  endfunction

  function int HC_BD_AVAILABLE();
    if ($test$plusargs("HC_NO_BACKDOOR")) return 0;
    return int'(bd_have());
  endfunction

  // System address -> HBM stack / channel / pseudo channel / byte address in the PC.
  // NoC HBM map (no interleaving): channel g (0..15) PC p is 1 GB at
  // HBM_BASE + g*2GB + p*1GB; channels 0..7 are stack 0 (CHa..CHh), 8..15 stack 1.
  function automatic void bd_locate(input longint unsigned a, output int st, output int ch, output bit pc,
      output longint unsigned w30);
    longint unsigned off;
    int g;
    off = hc_stripe_map(a) - HBM_BASE;  // HC_HBM_STRIPING: same permutation as the BD
    g = int'(off >> 31);
    st = g / 8;
    ch = g % 8;
    pc = off[30];
    w30 = off & 64'h3FFF_FFFF;
  endfunction

  function automatic string bd_where(input longint unsigned a);
    int st, ch; bit pc; longint unsigned w30;
    bd_locate(a, st, ch, pc, w30);
    return hc_hbm_bd_pkg::stacks[st].describe(ch, pc, w30);
  endfunction

  function automatic bit bd_get_byte_range(input longint unsigned a, input int n, inout bit [32768-1:0] d, input int doff);
    // reads n bytes (inside one 32-byte word) at a into d[doff*8 ...]
    int st, ch; bit pc; longint unsigned w30;
    bit [255:0] word;
    int o;
    bd_locate(a & ~64'h1F, st, ch, pc, w30);
    void'(hc_hbm_bd_pkg::stacks[st].get_word(ch, pc, w30, word));
    o = int'(a & 64'h1F);
    for (int k = 0; k < n; k++) d[(doff + k) * 8 +: 8] = word[(o + k) * 8 +: 8];
    return 1;
  endfunction

  function automatic void bd_put_byte_range(input longint unsigned a, input int n, input bit [32768-1:0] d, input int doff);
    int st, ch; bit pc; longint unsigned w30;
    bit [255:0] word;
    int o;
    bd_locate(a & ~64'h1F, st, ch, pc, w30);
    o = int'(a & 64'h1F);
    if (o != 0 || n != 32) void'(hc_hbm_bd_pkg::stacks[st].get_word(ch, pc, w30, word));
    for (int k = 0; k < n; k++) word[(o + k) * 8 +: 8] = d[(doff + k) * 8 +: 8];
    hc_hbm_bd_pkg::stacks[st].set_word(ch, pc, w30, word);
  endfunction

  function automatic void bd_read_bytes(input longint unsigned a, input int n, output bit [32768-1:0] d);
    d = '0;
    for (int i = 0; i < n; ) begin
      int c;
      c = 32 - int'((a + i) & 64'h1F);
      if (c > n - i) c = n - i;
      void'(bd_get_byte_range(a + i, c, d, i));
      i += c;
    end
  endfunction

  function automatic void bd_write_bytes(input longint unsigned a, input int n, input bit [32768-1:0] d);
    for (int i = 0; i < n; ) begin
      int c;
      c = 32 - int'((a + i) & 64'h1F);
      if (c > n - i) c = n - i;
      bd_put_byte_range(a + i, c, d, i);
      i += c;
    end
  endfunction

  // Last DRAM column write in either stack, in this module's time unit (1 ns).
  function automatic realtime bd_last_dram_write();
    real t0, t1;
    t0 = hc_hbm_bd_pkg::stacks[0].last_mem_write_ns();
    t1 = hc_hbm_bd_pkg::stacks[1].last_mem_write_ns();
    return ((t0 > t1) ? t0 : t1) * 1ns;
  endfunction

  function automatic bit bd_design_writes_done();
    for (int i = 0; i < NM; i++) if (aw_cnt[i] != b_cnt[i]) return 0;
    return 1;
  endfunction

  // Wait until no design write is outstanding, and both the last write response
  // (design master or host VIP) and the last DRAM column write are bd_quiet_ns old:
  // the HBM controller acknowledges a write before the DRAM has it (the self-test
  // reports that latency, ~0.5 us), so the controller must have drained.
  function automatic realtime bd_last_write_resp();
    return (last_b_any > last_host_vip_w) ? last_b_any : last_host_vip_w;
  endfunction

  task automatic bd_quiesce(input int wait_ns, output bit ok);
    realtime t0;
    t0 = $realtime;
    ok = 0;
    forever begin
      if (!hc_hbm_bd_pkg::stacks[0].in_reset() && !hc_hbm_bd_pkg::stacks[1].in_reset() &&
          bd_design_writes_done() && ($realtime - bd_last_write_resp()) >= bd_quiet_ns * 1ns &&
          ($realtime - bd_last_dram_write()) >= bd_quiet_ns * 1ns) begin
        ok = 1;
        bd_wait_total += $realtime - t0;
        return;
      end
      if ($realtime - t0 >= wait_ns * 1ns) begin
        bd_wait_total += $realtime - t0;
        return;
      end
      #(20ns);
    end
  endtask

  // Before the first backdoor access: one VIP read, so the HBM is calibrated and out
  // of reset exactly as for a VIP copy (the device model clears itself on reset).
  task automatic bd_prepare();
    bit [32768-1:0] rd;
    if (bd_ready) return;
    host_begin("BD_PREPARE_READ", HBM_BASE);
    mst_agent_0_mem.AXI4_READ_BURST(id, HBM_BASE, xil_axi_len_t'(0), xil_axi_size_t'(MEM_BUS_SIZE), burstType,
        xil_axi_lock_t'(0), xil_axi_cache_t'(2'b10), xil_axi_prot_t'(0), xil_axi_region_t'(0),
        xil_axi_qos_t'(0), xil_axi_user_beat'(0), rd, resp, ruser);
    host_end();
    bd_ready = 1;
    if (!bd_announced) begin
      bd_announced = 1;
      $display("HC_BD: %t host memory copies use the HBM backdoor (zero simulated time); +HC_NO_BACKDOOR or HC_SIM_BACKDOOR=0 for the VIP", $realtime);
    end
  endtask

  task automatic bd_check_resets();
    if (!bd_written) return;
    for (int s = 0; s < 2; s++)
      if (hc_hbm_bd_pkg::stacks[s].resets_seen() != bd_resets0[s]) begin
        err_cnt++;
        $display("HC_TB_ERROR: %t HBM stack %0d was reset after host backdoor writes: their contents are lost", $realtime, s);
        bd_resets0[s] = hc_hbm_bd_pkg::stacks[s].resets_seen();
      end
  endtask

  task HC_BD_WRITE(input xil_axi_ulong Addr, input bit [32768-1:0] WData, input int nbytes, output int ok);
    xil_axi_ulong a;
    bit q;
    ok = 0;
    bd_first_call_hook();
    a = mem_addr(Addr);
    if (HC_BD_AVAILABLE() == 0 || nbytes <= 0 || nbytes > 4096 || a < HBM_BASE || a + nbytes > HBM_BASE + HBM_SIZE) return;
    bd_prepare();
    host_begin("BD_WRITE", a);
    bd_quiesce(bd_write_wait_ns, q);
    if (!q) begin
      bd_fallbacks++;
      if (bd_fallbacks <= 3) $display("HC_BD: %t HBM not quiet (design writes done=%0d, last write response %t, last DRAM write %t, stacks in reset %0d/%0d); host write to 0x%0h goes through the VIP%s",
                                      $realtime, bd_design_writes_done(), bd_last_write_resp(), bd_last_dram_write(), hc_hbm_bd_pkg::stacks[0].in_reset(), hc_hbm_bd_pkg::stacks[1].in_reset(), a,
                                      (bd_fallbacks == 3) ? " (further VIP fallbacks are only counted)" : "");
      host_end();
      return;
    end
    if (!bd_written) begin
      bd_written = 1;
      for (int s = 0; s < 2; s++) bd_resets0[s] = hc_hbm_bd_pkg::stacks[s].resets_seen();
    end
    bd_check_resets();
    bd_write_bytes(a, nbytes, WData);
    bd_w_bytes += nbytes; bd_w_calls++;
    host_mw++; host_mw_bytes += nbytes;
    host_end();
    ok = 1;
  endtask

  task HC_BD_READ(input xil_axi_ulong Addr, output bit [32768-1:0] RData, input int nbytes, output int ok);
    xil_axi_ulong a;
    bit q;
    ok = 0;
    RData = '0;
    bd_first_call_hook();
    a = mem_addr(Addr);
    if (HC_BD_AVAILABLE() == 0 || nbytes <= 0 || nbytes > 4096 || a < HBM_BASE || a + nbytes > HBM_BASE + HBM_SIZE) return;
    bd_prepare();
    host_begin("BD_READ", a);
    bd_quiesce(bd_read_wait_ns, q);
    if (!q) begin
      bd_fallbacks++;
      if (bd_fallbacks <= 3) $display("HC_BD: %t HBM not quiet (design writes done=%0d, last write response %t, last DRAM write %t, stacks in reset %0d/%0d); host read of 0x%0h goes through the VIP%s",
                                      $realtime, bd_design_writes_done(), bd_last_write_resp(), bd_last_dram_write(), hc_hbm_bd_pkg::stacks[0].in_reset(), hc_hbm_bd_pkg::stacks[1].in_reset(), a,
                                      (bd_fallbacks == 3) ? " (further VIP fallbacks are only counted)" : "");
      host_end();
      return;
    end
    bd_check_resets();
    bd_read_bytes(a, nbytes, RData);
    bd_r_bytes += nbytes; bd_r_calls++;
    host_mr++; host_mr_bytes += nbytes;
    host_end();
    ok = 1;
  endtask

  // Address-map self-check (+HC_BD_SELFTEST=n): in every one of the 32 pseudo
  // channels, at n addresses (first and last 64 B of the PC, then random):
  //   VIP write 64 B -> backdoor read (waits for the controller to commit it, and
  //   reports the worst BRESP->DRAM latency); backdoor write 64 B -> VIP read;
  //   backdoor write of an unaligned 13-byte range -> VIP read of the 64 B around it.
  task automatic bd_selftest();
    int n, fails, checks;
    realtime worst;
    bd_selftest_done = 1;
    if (!$value$plusargs("HC_BD_SELFTEST=%d", n)) return;
    if (!bd_have()) begin
      $display("HC_BD_SELFTEST: no backdoor-capable HBM model registered (encrypted Xilinx model?) -- skipped");
      return;
    end
    bd_prepare();
    fails = 0; checks = 0; worst = 0;
    for (int g = 0; g < 16; g++)
      for (int p = 0; p < 2; p++)
        for (int k = 0; k < n; k++) begin
          longint unsigned off, a;
          bit [32768-1:0] wd, rd, exp;
          xil_axi_resp_t bresp;
          realtime t0;
          bit seen;
          int u;
          off = (k == 0) ? 0 : (k == 1) ? 64'h3FFF_FFC0 : ((longint'($urandom) << 6) & 64'h3FFF_FFC0);
          a = HBM_BASE + longint'(g) * 64'h8000_0000 + longint'(p) * 64'h4000_0000 + off;
          // (1) VIP write -> backdoor read
          wd = '0;
          for (int b = 0; b < 16; b++) wd[b * 32 +: 32] = $urandom;
          host_begin("BD_SELFTEST_VIP_WRITE", a);
          mst_agent_0_mem.AXI4_WRITE_BURST(id, a, xil_axi_len_t'(1), xil_axi_size_t'(MEM_BUS_SIZE), burstType,
              xil_axi_lock_t'(0), xil_axi_cache_t'(2'b10), xil_axi_prot_t'(0), xil_axi_region_t'(0),
              xil_axi_qos_t'(0), xil_axi_user_beat'(0), wd, wuser, bresp);
          host_end();
          t0 = $realtime;
          seen = 0;
          while (!seen && $realtime - t0 < 20us) begin
            bd_read_bytes(a, 64, rd);
            if (rd[511:0] == wd[511:0]) seen = 1; else #(10ns);
          end
          checks++;
          if (!seen) begin
            fails++;
            $display("HC_BD_SELFTEST: FAIL VIP write -> backdoor read at 0x%0h (%s): wrote %0h read %0h; first 8 bytes found at:%s%s",
                     a, bd_where(a), wd[511:0], rd[511:0], hc_hbm_bd_pkg::stacks[0].find64(wd[63:0]), hc_hbm_bd_pkg::stacks[1].find64(wd[63:0]));
          end else if ($realtime - t0 > worst) worst = $realtime - t0;
          // (2) backdoor write -> VIP read
          for (int b = 0; b < 16; b++) wd[b * 32 +: 32] = $urandom;
          bd_write_bytes(a, 64, wd);
          host_begin("BD_SELFTEST_VIP_READ", a);
          mst_agent_0_mem.AXI4_READ_BURST(id, a, xil_axi_len_t'(1), xil_axi_size_t'(MEM_BUS_SIZE), burstType,
              xil_axi_lock_t'(0), xil_axi_cache_t'(2'b10), xil_axi_prot_t'(0), xil_axi_region_t'(0),
              xil_axi_qos_t'(0), xil_axi_user_beat'(0), rd, resp, ruser);
          host_end();
          checks++;
          if (rd[511:0] != wd[511:0]) begin
            fails++;
            $display("HC_BD_SELFTEST: FAIL backdoor write -> VIP read at 0x%0h (%s): wrote %0h read %0h", a, bd_where(a), wd[511:0], rd[511:0]);
          end
          // (3) unaligned 13-byte backdoor write -> VIP read
          exp = wd;
          u = 3 + (k % 7) * 4;
          for (int b = 0; b < 13; b++) begin
            wd[b * 8 +: 8] = $urandom;
            exp[(u + b) * 8 +: 8] = wd[b * 8 +: 8];
          end
          bd_write_bytes(a + u, 13, wd);
          host_begin("BD_SELFTEST_VIP_READ", a);
          mst_agent_0_mem.AXI4_READ_BURST(id, a, xil_axi_len_t'(1), xil_axi_size_t'(MEM_BUS_SIZE), burstType,
              xil_axi_lock_t'(0), xil_axi_cache_t'(2'b10), xil_axi_prot_t'(0), xil_axi_region_t'(0),
              xil_axi_qos_t'(0), xil_axi_user_beat'(0), rd, resp, ruser);
          host_end();
          checks++;
          if (rd[511:0] != exp[511:0]) begin
            fails++;
            $display("HC_BD_SELFTEST: FAIL unaligned backdoor write (13 B at +%0d) -> VIP read at 0x%0h: expected %0h read %0h", u, a, exp[511:0], rd[511:0]);
          end
        end
    if (fails == 0)
      $display("HC_BD_SELFTEST: PASS %0d checks over 32 pseudo channels (%0d address(es) each); worst VIP BRESP -> DRAM visibility %0t", checks, n, worst);
    else begin
      err_cnt++;
      $display("HC_TB_ERROR: HC_BD_SELFTEST: %0d of %0d checks FAILED", fails, checks);
    end
    if ($test$plusargs("HC_BD_SELFTEST_ONLY")) begin
      $display("HC_TB_DONE");
      $finish;
    end
  endtask

  // Called at the start of every host DPI call (after the guard probe).
  task automatic bd_first_call_hook();
    if (!guard_probed) guard_probe();
    if (!bd_selftest_done) bd_selftest();
  endtask

  function automatic string bd_str();
    if (bd_w_calls + bd_r_calls + bd_fallbacks == 0) return "";
    return $sformatf(" backdoor[w %0d B r %0d B, VIP fallbacks %0d]", bd_w_bytes, bd_r_bytes, bd_fallbacks);
  endfunction

  task automatic bd_summary();
    if (bd_w_calls + bd_r_calls + bd_fallbacks == 0) return;
    bd_check_resets();
    $display("HC_BD: host backdoor copies: %0d write call(s) / %0d B, %0d read call(s) / %0d B, %0d VIP fallback(s), %0t spent waiting for quiet HBM",
             bd_w_calls, bd_w_bytes, bd_r_calls, bd_r_bytes, bd_fallbacks, bd_wait_total);
  endtask
"""

  private def simulateDo(name: String): String =
    s"""# V80 QuestaSim run script (generated by TclQuestaSimV80). Run from the
# Vivado-generated questa directory after compile.sh.
set sw ../../../../../../software
sccom -DMTI_BIND_SC_MEMBER_FUNCTION -DUSE_MTI_CIN -g -incr -work xil_defaultlib \\
     -I $$sw/projects/$name/include/ \\
     -I $$sw/projects/$name/include/gen/nonvitis/ \\
     -I $$sw/driver/include/ \\
     $$sw/projects/$name/src/questa_main.cpp \\
     $$sw/driver/src/hardCilkDriver.cpp
sccom -incr -work xil_defaultlib -dpilib xil_defaultlib -link -fuse-ld=bfd

# Take the library list from Vivado's own elaboration script, so it always matches
# the IP versions of this Vivado release.
set fh [open main_sim_elaborate.do r]
set elab [read $$fh]
close $$fh
set libs [regexp -all -inline -- {-L\\s+\\S+} $$elab]
set libargs [join $$libs " "]
# HBM device model: the backdoor-capable stand-in (library hc_hbm_model, built by
# simulate.sh) is searched first, so it replaces the encrypted hbm_responder;
# HC_HBM_MODEL=xilinx keeps the encrypted one (host copies then use the VIP).
set hc_lf "-Lf hc_hbm_model"
if {[info exists ::env(HC_HBM_MODEL)] && $$::env(HC_HBM_MODEL) eq "xilinx"} { set hc_lf "" }
eval vopt -suppress vopt-2912 -suppress 3373 -64 +acc=npr -suppress 10016 $$hc_lf $$libargs -work xil_defaultlib xil_defaultlib.TestBench xil_defaultlib.glbl -o main_sim_opt

set vsim_extra ""
if {[info exists ::env(HC_VSIM_ARGS)]} { set vsim_extra $$::env(HC_VSIM_ARGS) }

if {[info exists ::env(HARDCILK_BATCH)]} {
    eval vsim -lib xil_defaultlib $$vsim_extra main_sim_opt
    onerror {quit -code 1}
    onbreak {quit -code 1}
    run -all
    quit -f
}

eval vsim -lib xil_defaultlib $$vsim_extra main_sim_opt
layout load Simulate
view wave
view structure
view signals
add log -r sim:/TestBench/myModule/DUT/design_1_sw/design_1_wrapper_i/design_1_i/${name}_0/*
"""

  private def simulateSh(name: String, vivadoVersion: String): String =
    s"""#!/bin/bash
# V80 QuestaSim flow (generated by TclQuestaSimV80).
#   ./simulate.sh [--batch] [--sim-only]
#     --batch     run headless to completion (log: questa_run.log)
#     --sim-only  reuse the existing Vivado project and compiled HDL; only
#                 recompile the C++ testbench and rerun the simulation
# Environment overrides:
#   VIVADO_SETTINGS  (default /tools/Xilinx/$vivadoVersion/Vivado/settings64.sh)
#   QUESTA_HOME      (default /tools/questasim)
#   QUESTA_LIB_DIR   Xilinx libraries compiled for Questa
#                    (default /tools/xilinx_compiled_libs_for_questa)
#   HC_VSIM_ARGS     extra vsim arguments, e.g. "+HC_MAX_SIM_US=20000 +HC_TXN_TIMEOUT_US=500"
#   HC_SIM_BACKDOOR  1 (default): host copyToDevice/copyFromDevice write/read the HBM
#                    device model directly, in zero simulated time; 0: through the
#                    memory AXI VIP (as before). Design traffic always takes the
#                    NoC/HBM path. Backdoor plusargs (in HC_VSIM_ARGS): +HC_BD_SELFTEST=n
#                    (check the address map VIP<->backdoor at n addresses per pseudo
#                    channel first), +HC_BD_SELFTEST_ONLY, +HC_BD_QUIET_NS,
#                    +HC_BD_WRITE_WAIT_NS, +HC_BD_READ_WAIT_NS (see main_sim.sv)
#   HC_HBM_MODEL     hardcilk (default): AMD's plaintext HBM device model from the
#                    Vivado install with backdoor accessors; xilinx: the encrypted
#                    model of the HBM IP (no backdoor)
set -e
cd "$$(dirname "$$0")"
HC_BATCH=0; HC_SIM_ONLY=0
for a in "$$@"; do
  case "$$a" in
    --batch) HC_BATCH=1 ;;
    --sim-only) HC_SIM_ONLY=1 ;;
    *) echo "unknown option $$a"; exit 2 ;;
  esac
done
export HC_BATCH
VIVADO_SETTINGS=$${VIVADO_SETTINGS:-/tools/Xilinx/$vivadoVersion/Vivado/settings64.sh}
QUESTA_HOME=$${QUESTA_HOME:-/tools/questasim}
export QUESTA_LIB_DIR=$${QUESTA_LIB_DIR:-/tools/xilinx_compiled_libs_for_questa}
export PATH="$$QUESTA_HOME/bin:$$PATH"
source "$$VIVADO_SETTINGS"
SIMDIR=${name}_vivado_project/project_1.sim/sim_1/behav/questa
if [ "$$HC_SIM_ONLY" = 0 ]; then
  vivado -mode batch -nojournal -log vivado_questa.log -source ${name}_questa.tcl
  grep -q "HC_QUESTA_V80: block design and simulation scripts ready" vivado_questa.log
  # Conflicting REG_HBM_MAP (one NMU on two ports of the same HBM channel) silently
  # misroutes traffic; treat it as a failed build.
  # Note: "End-point interface ... data-width 512 where NMU can support only up to
  # 256" is expected for the 512-bit memory VIP (and any 512-bit master): the NoC
  # check traces through the downsizing SmartConnect to the original master. The
  # NMU itself is 256-bit (pinned by the register slice in front of it).
  if grep -q "75-207" vivado_questa.log; then
    echo "HardCilk: NoC CRITICAL WARNING 75-207 (conflicting REG_HBM_MAP) -- refusing to simulate"; exit 1
  fi
  (cd "$$SIMDIR" && ./compile.sh)
  # Every HBM NMU must be programmed 256-bit (REG_DWIDTH 5); see toNmu.
  if grep "REG_DWIDTH" "$$SIMDIR/nocattrs.dat" | grep "HBM[0-9]*_AXI_nmu" | grep -qv "REG_DWIDTH 00000005"; then
    grep "REG_DWIDTH" "$$SIMDIR/nocattrs.dat"
    echo "HardCilk: an HBM NMU is not programmed 256-bit -- refusing to simulate"; exit 1
  fi
fi
export HC_HBM_MODEL=$${HC_HBM_MODEL:-hardcilk}
if [ "$$HC_HBM_MODEL" != xilinx ]; then
  ./sim_src/hbm_model/build_hbm_model.sh "$$SIMDIR"
fi
cp simulate.do "$$SIMDIR/"
cd "$$SIMDIR"
if [ "$$HC_BATCH" = 1 ]; then
  set +e
  HARDCILK_BATCH=1 vsim -c -do simulate.do -l questa_run.log
  rc=$$?
  set -e
  if grep -q "HC_TB_FATAL\\|HC_TB_ERROR\\|^# \\*\\* Fatal" questa_run.log; then
    echo "HardCilk: simulation FAILED (watchdog/AXI error, see $$SIMDIR/questa_run.log)"; exit 1
  fi
  if ! grep -q "HC_TB_DONE" questa_run.log; then
    echo "HardCilk: simulation did not finish cleanly (rc=$$rc, see $$SIMDIR/questa_run.log)"; exit 1
  fi
  grep -h "HC_GUARD_PROBE\\|HC_BD_SELFTEST\\|HC_BD: host backdoor" questa_run.log || true
  echo "HardCilk: simulation finished (log: $$SIMDIR/questa_run.log)"
else
  vsim -do simulate.do
fi
"""
}
