package TclResources

import Descriptors._

object TclQuestaSim {

  def generate(fullSysGenDescriptor: FullSysGenDescriptor, tclFileDirectory: String, reduce_axi: Int) = {
    val tclCommands = new StringBuilder()
    def tclWriteln(s: String) = {
      tclCommands.append(s)
      tclCommands.append("\n")
    }

    // The testbench data VIP drives HBM through its own port, so one more HBM
    // slave port than the design exports has to be enabled.
    val vipHbmPort = reduce_axi
    require(
      vipHbmPort + 1 <= 32,
      s"[TclQuestaSim] The design exports $reduce_axi HBM port(s); the QuestaSim testbench needs " +
        s"one more for its memory VIP, which exceeds the 32 HBM slave ports. Reduce the AXI port " +
        s"count (-r) to at most 31."
    )

    // Make the tcl file as one group of commands
    // tclWriteln("startgroup")

    // Create an instance of the compute system
    tclWriteln(f"create_bd_cell -type module -reference ${fullSysGenDescriptor.name} ${fullSysGenDescriptor.name}_0")

    // Add any tcl generated with the PEs from HLS
    tclWriteln(TclGeneralConfigs.getPEsTcl(fullSysGenDescriptor))

    // Get the stats of the memory connections
    val memConnectionsStats = fullSysGenDescriptor.getMemoryConnectionsStats(reduce_axi)

    // Create and configure the axi verfication IPs to replace the xdma
    tclWriteln(TclGeneralConfigs.getAxiVipConfig())

    // Create and configure the hbm (one extra port for the testbench memory VIP)
    tclWriteln(
      TclGeneralConfigs.getHBMConfigTclSyntax(vipHbmPort + 1)
    )

    // Connect the management port from axi_vip_1 to the compute system. A Vitis
    // descriptor already exposes a 32-bit AXI-Lite slave, so the width converter
    // is only needed for the 64-bit (non-Vitis) management slave.
    if (fullSysGenDescriptor.isVitisProject) {
      tclWriteln(
        "connect_bd_intf_net [get_bd_intf_pins axi_clock_converter_1/M_AXI] [get_bd_intf_pins */s_axil_mgmt_hardcilk]"
      )
    } else {
      tclWriteln("create_bd_cell -type ip -vlnv xilinx.com:ip:axi_dwidth_converter:2.1 axi_dwidth_converter_0")
      tclWriteln("connect_bd_intf_net [get_bd_intf_pins axi_dwidth_converter_0/M_AXI] [get_bd_intf_pins */s_axil_mgmt_hardcilk]")
      tclWriteln(
        "connect_bd_intf_net [get_bd_intf_pins axi_clock_converter_1/M_AXI] [get_bd_intf_pins axi_dwidth_converter_0/S_AXI]"
      )
    }
    tclWriteln("connect_bd_net [get_bd_ports axi_vip_clk] [get_bd_pins axi_clock_converter_1/s_axi_aclk]")
    tclWriteln("connect_bd_net [get_bd_ports axi_vip_aresetn] [get_bd_pins axi_clock_converter_1/s_axi_aresetn]")

    // Connect the data port of axi_vip_0 to its own HBM port. This mirrors what
    // XRT does on hardware: the host writes/reads HBM directly instead of
    // tunneling through the kernel.
    tclWriteln("connect_bd_intf_net [get_bd_intf_pins axi_vip_0/M_AXI] [get_bd_intf_pins axi_clock_converter_0/S_AXI]")
    tclWriteln("connect_bd_net [get_bd_ports axi_vip_clk] [get_bd_pins axi_clock_converter_0/s_axi_aclk]")
    tclWriteln("connect_bd_net [get_bd_ports axi_vip_aresetn] [get_bd_pins axi_clock_converter_0/s_axi_aresetn]")

    if (fullSysGenDescriptor.hasAXIDMAInput) {
      println(
        "[TclQuestaSim] WARNING: the design exports s_axi_xdma but the QuestaSim testbench " +
          "drives HBM directly; s_axi_xdma is left unconnected."
      )
      tclWriteln(
        "puts \"WARNING: s_axi_xdma is left unconnected; the QuestaSim testbench VIP accesses HBM directly.\""
      )
    }

    // Create the clocking wizard and reset for the system
    tclWriteln(TclGeneralConfigs.getSytstemClockingAndResetConfigTclSyntax(fullSysGenDescriptor, true))

    // Connect each exported AXI4 master to its HBM AXI3 slave through a 1:1
    // SmartConnect, which handles the data-width and AXI4 -> AXI3 conversion.
    tclWriteln(
      TclGeneralConfigs.getHbmSmartConnectTcl(
        descriptorName = fullSysGenDescriptor.name,
        numPorts = reduce_axi,
        clkPin = "[get_bd_pins clk_wiz_0/clk_out1]",
        resetPin = "[get_bd_pins proc_sys_reset_1/peripheral_aresetn]"
      )
    )

    // Same conversion for the testbench memory VIP on the spare HBM port.
    val vipSmartConnect = f"smartconnect_hbm_${vipHbmPort}%02d"
    tclWriteln(f"create_bd_cell -type ip -vlnv xilinx.com:ip:smartconnect:1.0 ${vipSmartConnect}")
    tclWriteln(
      f"set_property -dict [list CONFIG.NUM_SI {1} CONFIG.NUM_MI {1} CONFIG.NUM_CLKS {1}] [get_bd_cells ${vipSmartConnect}]"
    )
    tclWriteln(
      f"connect_bd_intf_net [get_bd_intf_pins axi_clock_converter_0/M_AXI] [get_bd_intf_pins ${vipSmartConnect}/S00_AXI]"
    )
    tclWriteln(
      f"connect_bd_intf_net [get_bd_intf_pins ${vipSmartConnect}/M00_AXI] [get_bd_intf_pins hbm_0/SAXI_${vipHbmPort}%02d_8HI]"
    )
    tclWriteln(f"connect_bd_net [get_bd_pins clk_wiz_0/clk_out1] [get_bd_pins ${vipSmartConnect}/aclk]")
    tclWriteln(f"connect_bd_net [get_bd_pins proc_sys_reset_1/peripheral_aresetn] [get_bd_pins ${vipSmartConnect}/aresetn]")

    // Assign addresses. This maps the HBM segments into the axi_vip_0 master
    // address space and the management registers into the axi_vip_1 one.
    tclWriteln("assign_bd_address")
    tclWriteln(
      f"assign_bd_address -target_address_space /axi_vip_1/Master_AXI [get_bd_addr_segs ${fullSysGenDescriptor.name}_0/s_axil_mgmt_hardcilk/reg0]"
    )

    // The reset GPIO keeps whatever address assign_bd_address gives it: only the
    // PCIe driver pokes it (RESET_ADDR in pcie_main.cpp), while the QuestaSim
    // testbench drives the resets from main_sim.sv and relies on the GPIO's
    // 0xFFFFFFFF default. Pinning it at 0x8000 as the PCIe flow does would
    // collide with the management window, which is 2M wide on larger systems.

    tclWriteln(f"set_property target_simulator Questa [current_project]\nset_property compxlib.questa_compiled_library_dir /alpha/questa [current_project]")

    // Write the tcl commands to a file
    val tclFile = new java.io.PrintWriter(new java.io.File(s"${tclFileDirectory}/${fullSysGenDescriptor.name}_questa.tcl"))
    tclFile.write(TclGeneralConfigs.getProjectWrapperTCLSyntax(tclCommands.toString(), fullSysGenDescriptor, true))

    // Create a new string builder to write the simulation tcl commands
    val simTclCommands = new StringBuilder()
    simTclCommands.append(f"generate_target Simulation [get_files ./${fullSysGenDescriptor.name}_vivado_project/project_1.srcs/sources_1/bd/design_1/design_1.bd]\n")
    simTclCommands.append(f"export_ip_user_files -of_objects [get_files ./${fullSysGenDescriptor.name}_vivado_project/project_1.srcs/sources_1/bd/design_1/design_1.bd] -no_script -sync -force -quiet\n")
    simTclCommands.append(f"export_simulation -of_objects [get_files ./${fullSysGenDescriptor.name}_vivado_project/project_1.srcs/sources_1/bd/design_1/design_1.bd] -directory ./${fullSysGenDescriptor.name}_vivado_project/project_1.ip_user_files/sim_scripts -ip_user_files_dir ./${fullSysGenDescriptor.name}_vivado_project/project_1.ip_user_files -ipstatic_source_dir ./${fullSysGenDescriptor.name}_vivado_project/project_1.ip_user_files/ipstatic -lib_map_path [list {modelsim=./${fullSysGenDescriptor.name}_vivado_project/project_1.cache/compile_simlib/modelsim} {questa=/alpha/questa} {xcelium=./${fullSysGenDescriptor.name}_vivado_project/project_1.cache/compile_simlib/xcelium} {vcs=./${fullSysGenDescriptor.name}_vivado_project/project_1.cache/compile_simlib/vcs} {riviera=./${fullSysGenDescriptor.name}_vivado_project/project_1.cache/compile_simlib/riviera}] -use_ip_compiled_libs -force -quiet\n")
    simTclCommands.append(f"launch_simulation\n")
    // Write the simulation tcl commands to a file
    tclFile.write(simTclCommands.toString())

    tclFile.close()

    // Read the do file at ./software_template/simulate.do
    val doFilePath = "./software_template/simulate.do"
    require(
      new java.io.File(doFilePath).exists(),
      s"[TclQuestaSim] $doFilePath not found. The emitter must be run from the " +
        s"`architecture-generator` directory."
    )
    val doFile = scala.io.Source.fromFile(doFilePath)

    // Replace "DESCRIPTOR_NAME" with the name of the descriptor
    val doFileString = doFile.mkString.replace("DESCRIPTOR_NAME", fullSysGenDescriptor.name)
    doFile.close()

    // Write the do file to the tcl directory of the output
    val doFileOut = new java.io.PrintWriter(new java.io.File(s"${tclFileDirectory}/simulate.do"))
    doFileOut.write(doFileString)
    doFileOut.close()

    // Now we create a shell file to run the tcl file, copy the do file to the simulation directory and run the simulation
    // The shell file should run enable_xilinx_2024.1 and then run the tcl file
    val shellFile = new java.io.PrintWriter(new java.io.File(s"${tclFileDirectory}/simulate.sh"))

    val shellFileStringBuilder = new StringBuilder()
    shellFileStringBuilder.append("#!/bin/bash\n")
    // Stop at the first failing step: without this a failed block design build
    // shows up much later as a confusing "cannot open questa_main.cpp" from
    // sccom, because the simulation directory was never created.
    shellFileStringBuilder.append("set -e\n")
    shellFileStringBuilder.append("export XILINX_ROOT=/alpha/tools/Xilinx/\n")
    shellFileStringBuilder.append("source $XILINX_ROOT/Vivado/2024.1/settings64.sh\n")
    shellFileStringBuilder.append(f"vivado -mode batch -source ${fullSysGenDescriptor.name}_questa.tcl\n")
    shellFileStringBuilder.append(f"cp simulate.do ${fullSysGenDescriptor.name}_vivado_project/project_1.sim/sim_1/behav/questa/\n")
    shellFileStringBuilder.append(f"cd ${fullSysGenDescriptor.name}_vivado_project/project_1.sim/sim_1/behav/questa/\n")
    shellFileStringBuilder.append("vsim -do simulate.do\n")


    // Write the shell file to the tcl directory of the output
    shellFile.write(shellFileStringBuilder.toString())
    shellFile.close()

    // make the shell file executable
    val p = new java.lang.ProcessBuilder("chmod", "+x", s"${tclFileDirectory}/simulate.sh").start()
    p.waitFor()


  }
}
