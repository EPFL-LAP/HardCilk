package TclResources

import Descriptors._
import scala.collection.mutable.Map
import scala.util.control.Breaks._

object TclGeneratorMemPEs {
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
    val memConnectionsStats = fullSysGenDescriptor.getMemoryConnectionsStats(reduce_axi)

    // Create and configure the xdma
    tclWriteln(TclGeneralConfigs.getXdmaConfigTclSyntax())

    // Create and configure the hbm
    tclWriteln(
      TclGeneralConfigs.getHBMConfigTclSyntax(reduce_axi)
    )


    // Connect the management port from xdma to the compute system
    tclWriteln("create_bd_cell -type ip -vlnv xilinx.com:ip:axi_dwidth_converter:2.1 axi_dwidth_converter_0")
    tclWriteln("connect_bd_intf_net [get_bd_intf_pins axi_dwidth_converter_0/M_AXI] [get_bd_intf_pins */s_axil_mgmt_hardcilk]")
    tclWriteln(
      "connect_bd_intf_net [get_bd_intf_pins axi_clock_converter_1/M_AXI] [get_bd_intf_pins axi_dwidth_converter_0/S_AXI]"
    )

    // Connect the data port from the xdma to the compute system
    tclWriteln("connect_bd_intf_net [get_bd_intf_pins xdma_0/M_AXI] [get_bd_intf_pins axi_clock_converter_0/S_AXI]")
    tclWriteln("connect_bd_intf_net [get_bd_intf_pins axi_clock_converter_0/M_AXI] [get_bd_intf_pins */s_axi_xdma]")

    // Create the clocking wizard and reset for the system
    tclWriteln(TclGeneralConfigs.getSytstemClockingAndResetConfigTclSyntax(fullSysGenDescriptor))

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

    // // clock and reset of register slices
    // tclWriteln("connect_bd_net [get_bd_pins axi_register_slice_*/aclk] [get_bd_pins clk_wiz_0/clk_out1]")
    // tclWriteln("connect_bd_net [get_bd_pins axi_register_slice_*/aresetn] [get_bd_pins proc_sys_reset_1/peripheral_aresetn]")

    // Assign addresses
    tclWriteln("assign_bd_address")
    tclWriteln(
      f"assign_bd_address -target_address_space /xdma_0/M_AXI_LITE [get_bd_addr_segs ${fullSysGenDescriptor.name}_0/s_axil_mgmt_hardcilk/reg0]"
    )

    tclWriteln("set_property range 32K [get_bd_addr_segs {xdma_0/M_AXI_LITE/SEG_axi_gpio_0_Reg}]")
    tclWriteln("set_property offset 0x0008000 [get_bd_addr_segs {xdma_0/M_AXI_LITE/SEG_axi_gpio_0_Reg}]")

    // Write the tcl commands to a file
    val tclFile = new java.io.PrintWriter(new java.io.File(s"${tclFileDirectory}/${fullSysGenDescriptor.name}_memPEs.tcl"))
    tclFile.write(TclGeneralConfigs.getProjectWrapperTCLSyntax(tclCommands.toString(), fullSysGenDescriptor))
    tclFile.close()
  }
}
