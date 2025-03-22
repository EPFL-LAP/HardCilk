package TclResources

import Descriptors._

object TclQuestaSim {

  def generate(fullSysGenDescriptor: FullSysGenDescriptor, tclFileDirectory: String, reduce_axi: Int) = {
    val tclCommands = new StringBuilder()
    def tclWriteln(s: String) = {
      tclCommands.append(s)
      tclCommands.append("\n")
    }

    // Make the tcl file as one group of commands
    // tclWriteln("startgroup")

    // Create an instance of the compute system
    tclWriteln(f"create_bd_cell -type module -reference ${fullSysGenDescriptor.name} ${fullSysGenDescriptor.name}_0")

    // Add any tcl generated with the PEs from HLS
    tclWriteln(TclGeneralConfigs.getPEsTcl(fullSysGenDescriptor))

    // Get the stats of the memory connections
    //val memConnectionsStats = fullSysGenDescriptor.getMemoryConnectionsStats(reduce_axi)

    // Create and configure the axi verfication IPs to replace the xdma
    tclWriteln(TclGeneralConfigs.getAxiVipConfig())

    // Create and configure the hbm
    tclWriteln(
      TclGeneralConfigs.getHBMConfigTclSyntax(reduce_axi)
    )

    // Connect the management port from axi_vip_1 to the compute system
    tclWriteln("create_bd_cell -type ip -vlnv xilinx.com:ip:axi_dwidth_converter:2.1 axi_dwidth_converter_0")

    tclWriteln("connect_bd_intf_net [get_bd_intf_pins axi_dwidth_converter_0/M_AXI] [get_bd_intf_pins */s_axil_mgmt_hardcilk]")
    tclWriteln(
      "connect_bd_intf_net [get_bd_intf_pins axi_clock_converter_1/M_AXI] [get_bd_intf_pins axi_dwidth_converter_0/S_AXI]"
    )
    tclWriteln("connect_bd_net [get_bd_ports axi_vip_clk] [get_bd_pins axi_clock_converter_1/s_axi_aclk]")
    tclWriteln("connect_bd_net [get_bd_ports axi_vip_aresetn] [get_bd_pins axi_clock_converter_1/s_axi_aresetn]")

    // Connect the data port from the axi_vip_0 to the compute system
    tclWriteln("connect_bd_intf_net [get_bd_intf_pins axi_vip_0/M_AXI] [get_bd_intf_pins axi_clock_converter_0/S_AXI]")
    tclWriteln("connect_bd_intf_net [get_bd_intf_pins axi_clock_converter_0/M_AXI] [get_bd_intf_pins */s_axi_xdma]")
    tclWriteln("connect_bd_net [get_bd_ports axi_vip_clk] [get_bd_pins axi_clock_converter_0/s_axi_aclk]")
    tclWriteln("connect_bd_net [get_bd_ports axi_vip_aresetn] [get_bd_pins axi_clock_converter_0/s_axi_aresetn]")

    // Connect the smart connect masters to the HBM
    // [get_bd_intf_pins ${fullSysGenDescriptor.name}_0/${systemAXIPort}]
    for (i <- 0 until reduce_axi) {
        val portName = f"m_axi_${i}%02d"
        // Add an axi firewall to the connection, checking slave side transactions
        tclWriteln(f"create_bd_cell -type ip -vlnv xilinx.com:ip:axi_firewall:1.2 axi_firewall_${i}")
        tclWriteln(f"set_property CONFIG.FIREWALL_MODE {SI_SIDE} [get_bd_cells axi_firewall_${i}]")
        tclWriteln(f"connect_bd_intf_net [get_bd_intf_pins axi_firewall_${i}/M_AXI] [get_bd_intf_pins hbm_0/SAXI_${i}%02d_8HI]")
        tclWriteln(f"connect_bd_intf_net [get_bd_intf_pins ${fullSysGenDescriptor.name}_0/${portName}] [get_bd_intf_pins axi_firewall_${i}/S_AXI]")
        
    }

    // Create the clocking wizard and reset for the system
    tclWriteln(TclGeneralConfigs.getSytstemClockingAndResetConfigTclSyntax(fullSysGenDescriptor, true))

    for(i <- 0 until reduce_axi){
        tclWriteln(f"connect_bd_net [get_bd_pins clk_wiz_0/clk_out1] [get_bd_pins axi_firewall_${i}/aclk]")
        tclWriteln(f"connect_bd_net [get_bd_pins proc_sys_reset_1/peripheral_aresetn] [get_bd_pins axi_firewall_${i}/aresetn]")
    }

    // Assign addresses
    tclWriteln("assign_bd_address")
    // tclWriteln(
    // f"assign_bd_address -target_address_space /xdma_0/M_AXI_LITE [get_bd_addr_segs ${fullSysGenDescriptor.name}_0/s_axil_mgmt_hardcilk/reg0]"
    // )
    tclWriteln(
      f"assign_bd_address -target_address_space /axi_vip_1/Master_AXI [get_bd_addr_segs ${fullSysGenDescriptor.name}_0/s_axil_mgmt_hardcilk/reg0]"
    )

    tclWriteln("set_property range 32K [get_bd_addr_segs {axi_vip_1/Master_AXI/SEG_axi_gpio_0_Reg}]")
    tclWriteln("set_property offset 0x0008000 [get_bd_addr_segs {axi_vip_1/Master_AXI/SEG_axi_gpio_0_Reg}]")
    
    tclWriteln(f"set_property range 16G [get_bd_addr_segs {axi_vip_0/Master_AXI/SEG_${fullSysGenDescriptor.name}_0_reg0}]")

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
    val doFile = scala.io.Source.fromFile("./software_template/simulate.do")

    // Replace "DESCRIPTOR_NAME" with the name of the descriptor
    val doFileString = doFile.mkString.replace("DESCRIPTOR_NAME", fullSysGenDescriptor.name)

    // Write the do file to the tcl directory of the output
    val doFileOut = new java.io.PrintWriter(new java.io.File(s"${tclFileDirectory}/simulate.do"))
    doFileOut.write(doFileString)
    doFileOut.close()

    // Now we create a shell file to run the tcl file, copy the do file to the simulation directory and run the simulation
    // The shell file should run enable_xilinx_2024.1 and then run the tcl file
    val shellFile = new java.io.PrintWriter(new java.io.File(s"${tclFileDirectory}/simulate.sh"))

    val shellFileStringBuilder = new StringBuilder()
    shellFileStringBuilder.append("#!/bin/bash\n")
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
