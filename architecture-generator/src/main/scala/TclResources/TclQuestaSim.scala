package TclResources

import Descriptors._

/**
  * Generates the one-click QuestaSim simulation project for a HardCilk system.
  *
  * The block design mirrors the Vivado block-design flow ([[TclGeneratorMemPEs]])
  * -- the kernel plus the Xilinx HBM IP, with the exact same per-master AXI4 -> AXI3
  * protocol-converter wiring so the simulated memory subsystem matches hardware --
  * but replaces the XDMA host bridge with AXI Verification IPs:
  *
  *   - `axi_vip_0` (512-bit master) drives the compute-memory path directly.
  *     In striped mode the shared host driver performs RAMA's address transform
  *     in software, exactly as it does for real XRT hardware.
  *   - `axi_vip_1` (32-bit master) drives the management (AXI-Lite) slave.
  *   - when a watcher and global striping are both enabled, `axi_vip_2` drives
  *     the watcher's exclusive, direct upper-memory window.
  *
  * The C++ driver is co-simulated over SystemC/TLM (see the `questa`/`tlm` memIO
  * backends and `simulate.do`), so the SAME host code that runs on hardware drives
  * the simulation, and the Xilinx HBM IP provides realistic HBM timing that the
  * Vitis `hw_emu` flow (instant, coherent memory) cannot.
  *
  * @param reduce_axi number of exported HBM master ports of the design (already
  *                   including any lock/watcher masters). Host memory VIPs use
  *                   subsequent HBM slave ports.
  */
object TclQuestaSim {

  def generate(
      fullSysGenDescriptor: FullSysGenDescriptor,
      tclFileDirectory: String,
      reduce_axi: Int,
      ramaPorts: Set[Int] = Set.empty,
      enableRamaStriping: Boolean = false
  ) = {
    val tclCommands = new StringBuilder()
    def tclWriteln(s: String) = {
      tclCommands.append(s)
      tclCommands.append("\n")
    }

    val hasWatcher = fullSysGenDescriptor.watcherConfig.isDefined
    val watcherFirstPort = if (hasWatcher) reduce_axi - 2 else reduce_axi
    require(!hasWatcher || reduce_axi >= 2, "watcher-enabled design needs two AXI telemetry masters")
    val invalidRamaPorts = ramaPorts.filter(i => i < 0 || i >= watcherFirstPort)
    require(
      invalidRamaPorts.isEmpty,
      s"selective RAMA ports must be non-watcher indices, got ${invalidRamaPorts.toSeq.sorted.mkString(", ")}"
    )
    val dualMemoryVip = enableRamaStriping && hasWatcher

    // VIP0 follows the compute address map. With watcher + striping, VIP2 is a
    // direct path used only for the exclusive upper 8 GiB telemetry window.
    val vipHbmPort = reduce_axi
    val watcherVipHbmPort = reduce_axi + 1
    val enabledHbmPorts = reduce_axi + 1 + (if (dualMemoryVip) 1 else 0)
    require(
      enabledHbmPorts <= 32,
      s"[TclQuestaSim] The design exports $reduce_axi HBM port(s) and needs " +
        s"${enabledHbmPorts - reduce_axi} host VIP port(s), exceeding the 32 HBM slave ports."
    )

    // Create an instance of the compute system
    tclWriteln(f"create_bd_cell -type module -reference ${fullSysGenDescriptor.name} ${fullSysGenDescriptor.name}_0")

    // Add any tcl generated with the PEs from HLS
    tclWriteln(TclGeneralConfigs.getPEsTcl(fullSysGenDescriptor))

    // Create and configure the axi verification IPs to replace the xdma
    // (axi_vip_0 = 512-bit data master, axi_vip_1 = 32-bit management master,
    //  plus axi_clock_converter_0/1 for the two paths).
    tclWriteln(TclGeneralConfigs.getAxiVipConfig(dualMemoryVip))

    // Create and configure the hbm (one extra port for the testbench memory VIP)
    tclWriteln(
      TclGeneralConfigs.getHBMConfigTclSyntax(enabledHbmPorts)
    )

    // Management path: axi_clock_converter_1 -> width converter -> management
    // slave. The management slave `s_axil_mgmt_hardcilk` is a 32-bit AXI-Lite port
    // (Vitis-style), which matches the 32-bit management VIP, so NO width converter
    // is needed: connect the clock converter master straight to the slave. The
    // VIP-side of the clock converter (axi_vip_1 -> smartconnect_32 -> cc_1/S_AXI)
    // and its m_axi-side clock/reset are wired by
    // getSytstemClockingAndResetConfigTclSyntax(..., isQuestaSim=true) below; here
    // we only add the master side and the converter's SLAVE-side clock (axi_vip_clk
    // domain), which that helper does not connect.
    tclWriteln("connect_bd_intf_net [get_bd_intf_pins axi_clock_converter_1/M_AXI] [get_bd_intf_pins */s_axil_mgmt_hardcilk]")
    tclWriteln("connect_bd_net [get_bd_ports axi_vip_clk] [get_bd_pins axi_clock_converter_1/s_axi_aclk]")
    tclWriteln("connect_bd_net [get_bd_ports axi_vip_aresetn] [get_bd_pins axi_clock_converter_1/s_axi_aresetn]")

    // Data path: axi_vip_0 (512-bit) -> clock converter -> (SmartConnect below)
    // -> its own HBM port. This mirrors what XRT does on hardware: the host
    // writes/reads HBM directly instead of tunneling through the kernel.
    tclWriteln("connect_bd_intf_net [get_bd_intf_pins axi_vip_0/M_AXI] [get_bd_intf_pins axi_clock_converter_0/S_AXI]")
    tclWriteln("connect_bd_net [get_bd_ports axi_vip_clk] [get_bd_pins axi_clock_converter_0/s_axi_aclk]")
    tclWriteln("connect_bd_net [get_bd_ports axi_vip_aresetn] [get_bd_pins axi_clock_converter_0/s_axi_aresetn]")
    if (dualMemoryVip) {
      tclWriteln("connect_bd_intf_net [get_bd_intf_pins axi_vip_2/M_AXI] [get_bd_intf_pins axi_clock_converter_2/S_AXI]")
      tclWriteln("connect_bd_net [get_bd_ports axi_vip_clk] [get_bd_pins axi_clock_converter_2/s_axi_aclk]")
      tclWriteln("connect_bd_net [get_bd_ports axi_vip_aresetn] [get_bd_pins axi_clock_converter_2/s_axi_aresetn]")
    }

    if (fullSysGenDescriptor.hasAXIDMAInput) {
      println(
        "[TclQuestaSim] WARNING: the design exports s_axi_xdma but the QuestaSim testbench " +
          "drives HBM directly; s_axi_xdma is left unconnected."
      )
      tclWriteln(
        "puts \"WARNING: s_axi_xdma is left unconnected; the QuestaSim testbench VIP accesses HBM directly.\""
      )
    }

    // Create the clock/reset fabric before the memory paths; the RAMA helpers
    // wire their cells to it as they are emitted.
    tclWriteln(TclGeneralConfigs.getSytstemClockingAndResetConfigTclSyntax(fullSysGenDescriptor, true))
    if (dualMemoryVip) {
      tclWriteln("connect_bd_net [get_bd_pins clk_wiz_0/clk_out1] [get_bd_pins axi_clock_converter_2/m_axi_aclk]")
      tclWriteln("connect_bd_net [get_bd_pins proc_sys_reset_1/peripheral_aresetn] [get_bd_pins axi_clock_converter_2/m_axi_aresetn]")
      tclWriteln("set_property verilog_define {HC_DUAL_MEMORY_VIP} [get_filesets sources_1]")
      tclWriteln("set_property verilog_define {HC_DUAL_MEMORY_VIP} [get_filesets sim_1]")
    }

    val memoryCount = if (hasWatcher) 16 else 32
    def directHbmPath(name: String, upstreamPin: String, hbmPort: Int): Unit = {
      val sc = s"smartconnect_$name"
      tclWriteln(f"create_bd_cell -type ip -vlnv xilinx.com:ip:smartconnect:1.0 ${sc}")
      tclWriteln(f"set_property -dict [list CONFIG.NUM_SI {1} CONFIG.NUM_MI {1} CONFIG.NUM_CLKS {1}] [get_bd_cells ${sc}]")
      tclWriteln(f"connect_bd_intf_net ${upstreamPin} [get_bd_intf_pins ${sc}/S00_AXI]")
      tclWriteln(f"connect_bd_intf_net [get_bd_intf_pins ${sc}/M00_AXI] [get_bd_intf_pins hbm_0/SAXI_${hbmPort}%02d_8HI]")
      tclWriteln(f"connect_bd_net [get_bd_pins clk_wiz_0/clk_out1] [get_bd_pins ${sc}/aclk]")
      tclWriteln(f"connect_bd_net [get_bd_pins proc_sys_reset_1/peripheral_aresetn] [get_bd_pins ${sc}/aresetn]")
    }

    // The RTL elaboration has already resolved the CLI default and tri-state
    // descriptor overrides into this exact port set. The final two watcher
    // masters are rejected above and therefore always bypass RAMA.
    for (i <- 0 until reduce_axi) {
      val upstream = f"[get_bd_intf_pins ${fullSysGenDescriptor.name}_0/m_axi_${i}%02d]"
      val useRama = ramaPorts.contains(i)
      if (useRama) {
        tclWriteln(
          TclGeneralConfigs.getRamaHbmPathTcl(
            name = f"hbm_${i}%02d",
            upstreamPin = upstream,
            hbmPort = i,
            addressWidth = fullSysGenDescriptor.widthAXIAddress,
            striped = enableRamaStriping,
            memoryCount = if (enableRamaStriping) memoryCount else 4,
            clkPin = "[get_bd_pins clk_wiz_0/clk_out1]",
            resetPin = "[get_bd_pins proc_sys_reset_1/peripheral_aresetn]"
          )
        )
      } else directHbmPath(f"hbm_${i}%02d", upstream, i)
    }

    // Keep the host VIP direct even in striped mode. questaMemory consumes the
    // generated hbmports.json and emits the physical per-bank addresses itself;
    // another RAMA here would apply the permutation twice.
    directHbmPath("vip_compute", "[get_bd_intf_pins axi_clock_converter_0/M_AXI]", vipHbmPort)
    if (dualMemoryVip) {
      directHbmPath("vip_watcher", "[get_bd_intf_pins axi_clock_converter_2/M_AXI]", watcherVipHbmPort)
    }

    // Assign addresses. This maps the HBM segments into the axi_vip_0 master
    // address space and the management registers into the axi_vip_1 one.
    tclWriteln("assign_bd_address")
    tclWriteln(
      f"assign_bd_address -target_address_space /axi_vip_1/Master_AXI [get_bd_addr_segs ${fullSysGenDescriptor.name}_0/s_axil_mgmt_hardcilk/reg0]"
    )

    // The reset GPIO keeps whatever address assign_bd_address gives it: the
    // QuestaSim testbench drives resets from the testbench and relies on the
    // GPIO's 0xFFFFFFFF default, so (unlike the PCIe flow) we do not pin it.

    tclWriteln(f"set_property target_simulator Questa [current_project]\nset_property compxlib.questa_compiled_library_dir /alpha/questa [current_project]")

    // Write the block-design tcl (simulation project wrapper) to a file.
    val tclFile = new java.io.PrintWriter(new java.io.File(s"${tclFileDirectory}/${fullSysGenDescriptor.name}_questa.tcl"))
    tclFile.write(TclGeneralConfigs.getProjectWrapperTCLSyntax(tclCommands.toString(), fullSysGenDescriptor, true))

    // Append the simulation-generation tcl commands: build the sim sources for
    // the block design, export the IP user files/sim scripts (pointing at the
    // pre-compiled Questa simlib at /alpha/questa), then launch simulation.
    val simTclCommands = new StringBuilder()
    simTclCommands.append(f"generate_target Simulation [get_files ./${fullSysGenDescriptor.name}_vivado_project/project_1.srcs/sources_1/bd/design_1/design_1.bd]\n")
    simTclCommands.append(f"export_ip_user_files -of_objects [get_files ./${fullSysGenDescriptor.name}_vivado_project/project_1.srcs/sources_1/bd/design_1/design_1.bd] -no_script -sync -force -quiet\n")
    simTclCommands.append(f"export_simulation -of_objects [get_files ./${fullSysGenDescriptor.name}_vivado_project/project_1.srcs/sources_1/bd/design_1/design_1.bd] -directory ./${fullSysGenDescriptor.name}_vivado_project/project_1.ip_user_files/sim_scripts -ip_user_files_dir ./${fullSysGenDescriptor.name}_vivado_project/project_1.ip_user_files -ipstatic_source_dir ./${fullSysGenDescriptor.name}_vivado_project/project_1.ip_user_files/ipstatic -lib_map_path [list {modelsim=./${fullSysGenDescriptor.name}_vivado_project/project_1.cache/compile_simlib/modelsim} {questa=/alpha/questa} {xcelium=./${fullSysGenDescriptor.name}_vivado_project/project_1.cache/compile_simlib/xcelium} {vcs=./${fullSysGenDescriptor.name}_vivado_project/project_1.cache/compile_simlib/vcs} {riviera=./${fullSysGenDescriptor.name}_vivado_project/project_1.cache/compile_simlib/riviera}] -use_ip_compiled_libs -force -quiet\n")
    simTclCommands.append(f"launch_simulation\n")
    tclFile.write(simTclCommands.toString())

    tclFile.close()

    // Copy the QuestaSim do-file template, substituting the descriptor name.
    val doFilePath = "./software_template/simulate.do"
    require(
      new java.io.File(doFilePath).exists(),
      s"[TclQuestaSim] $doFilePath not found. The emitter must be run from the " +
        s"`architecture-generator` directory."
    )
    val doFile = scala.io.Source.fromFile(doFilePath)
    val doFileString = doFile.mkString.replace("DESCRIPTOR_NAME", fullSysGenDescriptor.name)
    doFile.close()

    val doFileOut = new java.io.PrintWriter(new java.io.File(s"${tclFileDirectory}/simulate.do"))
    doFileOut.write(doFileString)
    doFileOut.close()

    // Emit a one-click shell script: build the block design in Vivado batch
    // mode, copy the do-file into the generated sim directory, and run vsim.
    val shellFile = new java.io.PrintWriter(new java.io.File(s"${tclFileDirectory}/simulate.sh"))
    val shellFileStringBuilder = new StringBuilder()
    shellFileStringBuilder.append("#!/bin/bash\n")
    // Stop at the first failing step: without this a failed block-design build
    // shows up much later as a confusing "cannot open questa_main.cpp" from
    // sccom, because the simulation directory was never created.
    shellFileStringBuilder.append("set -e\n")
    shellFileStringBuilder.append("export XILINX_ROOT=/alpha/tools/Xilinx/\n")
    shellFileStringBuilder.append("source $XILINX_ROOT/Vivado/2024.1/settings64.sh\n")
    shellFileStringBuilder.append(f"vivado -mode batch -source ${fullSysGenDescriptor.name}_questa.tcl\n")
    shellFileStringBuilder.append(f"cp simulate.do ${fullSysGenDescriptor.name}_vivado_project/project_1.sim/sim_1/behav/questa/\n")
    shellFileStringBuilder.append(f"cd ${fullSysGenDescriptor.name}_vivado_project/project_1.sim/sim_1/behav/questa/\n")
    shellFileStringBuilder.append("vsim -do simulate.do\n")
    shellFile.write(shellFileStringBuilder.toString())
    shellFile.close()

    // make the shell file executable
    val p = new java.lang.ProcessBuilder("chmod", "+x", s"${tclFileDirectory}/simulate.sh").start()
    p.waitFor()
  }
}
