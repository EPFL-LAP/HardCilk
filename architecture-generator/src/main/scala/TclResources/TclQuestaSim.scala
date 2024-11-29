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

    // Get the stats of the memory connections
    val memConnectionsStats = fullSysGenDescriptor.getMemoryConnectionsStats(reduce_axi)

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
    tclFile.close()

  }
}
