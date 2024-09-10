package TclResources

import Descriptors._

class TclGeneratorMem(val fullSysGenDescriptor: fullSysGenDescriptor, val tclFileDirectory: String){
    private def initialize() = {
        // create a data structure to hold the tcl commands to be dumped later
        val tclCommands = new StringBuilder()
        def tclWriteln(s: String) = {
            tclCommands.append(s)
            tclCommands.append("\n")
        }

        // Make the tcl file as one group of commands
        tclWriteln("startgroup")

        // Create an instance of the compute system
        tclWriteln("create_bd_cell -type container -reference compute compute_0")

        // Create and configure the xdma
        tclWriteln(TclGeneralConfigs.getXdmaConfigTclSyntax())
        
        // Create and configure the hbm 
        tclWriteln(TclGeneralConfigs.getHBMConfigTclSyntax(fullSysGenDescriptor.getMemoryConnectionsStats().totalAXIPorts))

        // Connect the management port from xdma to the compute system
        tclWriteln(f"connect_bd_intf_net [get_bd_intf_pins axi_clock_converter_1/M_AXI] [get_bd_intf_pins compute_0/s_axil_mgmt_hardcilk_0]")

        // Connect the memory port of the xdma axi clock converter to the compute system
        tclWriteln(f"connect_bd_intf_net [get_bd_intf_pins axi_clock_converter_0/M_AXI] -boundary_type upper [get_bd_intf_pins compute_0/XDMA_AXI_SLAVE]")

        // Get the stats of the memory connections
        val memConnectionsStats = fullSysGenDescriptor.getMemoryConnectionsStats()
        
        // Connect the exported compute memory ports to the HBM
        val exported_ports = math.min(32, memConnectionsStats.totalAXIPorts) 
        for (i <- 0 until exported_ports){
            tclWriteln(f"connect_bd_intf_net [get_bd_intf_pins compute_0/M00_AXI_${i}] [get_bd_intf_pins hbm_0/SAXI_${i}%02d_8HI]")
        }

        // Create the clocking wizard and reset for the system
        tclWriteln(TclGeneralConfigs.getSytstemClockingAndResetConfigTclSyntax(fullSysGenDescriptor))


        // Assign addresses
        tclWriteln("assign_bd_address")
        tclWriteln("set_property range 2M [get_bd_addr_segs {xdma_0/M_AXI_LITE/SEG_quickSort_0_reg0}]")

        // End the group of commands
        tclWriteln("endgroup")


        // Write the tcl commands to a file
        val tclFile = new java.io.PrintWriter(new java.io.File(s"${tclFileDirectory}/${fullSysGenDescriptor.name}_mem.tcl"))
        tclFile.write(tclCommands.toString())
        tclFile.close()
    }

    initialize()
}
