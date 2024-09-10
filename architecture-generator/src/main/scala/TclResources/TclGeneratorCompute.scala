package TclResources

import Descriptors._
import scala.collection.mutable.Map

class TclGeneratorCompute(val fullSysGenDescriptor: fullSysGenDescriptor, val tclFileDirectory: String){
    private def initialize() = {
        // create a data structure to hold the tcl commands to be dumped later
        val tclCommands = new StringBuilder()
        def tclWriteln(s: String) = {
            tclCommands.append(s)
            tclCommands.append("\n")
        }

        // Make the tcl file as one group of commands
        tclWriteln("startgroup")

        // Create an instance of the system
        tclWriteln(f"create_bd_cell -type module -reference ${fullSysGenDescriptor.name} ${fullSysGenDescriptor.name}_0")

        

        // For each PE in the fullSysGenDescriptor
        for (task <- fullSysGenDescriptor.taskDescriptors){
            // For each PE, create a tcl command to create a PE given the task.numProcessingElements
            for (index <- 0 until task.numProcessingElements){
                tclWriteln(f"create_bd_cell -type ip -vlnv xilinx.com:hls:${task.name}:${task.peVersion} ${task.name}_${index}")
            }

        }
        
        val systemConnectionsDescriptor = fullSysGenDescriptor.getSystemConnectionsDescriptor()
        
        // Connect the PEs to the system based on the connections from the connectionsDescriptor
        for (connection <- systemConnectionsDescriptor.connections){
            // For each connection, create a tcl command to connect the source to the destination    
            tclWriteln(f"connect_bd_intf_net [get_bd_intf_pins ${connection.srcPort.getFormatedPortName(fullSysGenDescriptor)}] [get_bd_intf_pins ${connection.dstPort.getFormatedPortName(fullSysGenDescriptor)}]")
        }

        

        // Get the stats of the memory connections
        val memConnectionsStats = fullSysGenDescriptor.getMemoryConnectionsStats()
        

        // Get system AXI Ports Names and create a map to int initialzed with 1 for each one of them
        val systemAXIPortsNames = fullSysGenDescriptor.getSystemAXIPortsNames()
        val systemAXIPortsMap = Map[String, Int]()
        for (portName <- systemAXIPortsNames){
            systemAXIPortsMap(portName) = 1
        }

        // Mutable Map from a string to an integer
        val interconnectMap = Map[String, Int]()
        for (task <- fullSysGenDescriptor.taskDescriptors){
            interconnectMap(task.name) = task.numProcessingElements
        }
        interconnectMap("numSysAXI") = memConnectionsStats.numSysAXI

        // Task PE index map
        val taskPEIndexMap = Map[String, Int]()
        for (task <- fullSysGenDescriptor.taskDescriptors){
            taskPEIndexMap(task.name) = 0
        }


        // Instantiate and configure the smart connects needed 
        var index = 0
        var totalConnections = 0
        for (interconnectDescriptor <- memConnectionsStats.interconnectDescriptors){
        
            for(i <-0 until interconnectDescriptor.count){
                tclWriteln(f"create_bd_cell -type ip -vlnv xilinx.com:ip:smartconnect:1.0 smartconnect_${index}")
                tclWriteln(f"set_property CONFIG.NUM_SI {${interconnectDescriptor.ratio}} [get_bd_cells smartconnect_${index}]") 
                
                var possibleConnections = interconnectDescriptor.ratio
                // Check if all tasks PEs are connected to the smart connect, connect them to this one if not
                for (task <- fullSysGenDescriptor.taskDescriptors){
                    if (interconnectMap(task.name) > 0 && possibleConnections > 0){
                        tclWriteln(f"connect_bd_intf_net [get_bd_intf_pins ${task.name}_${taskPEIndexMap(task.name)}/m_axi_gmem] [get_bd_intf_pins smartconnect_${index}/S${(possibleConnections-1)}%02d_AXI]")
                        interconnectMap(task.name) -= 1
                        possibleConnections -= 1
                        taskPEIndexMap(task.name) += 1
                        totalConnections += 1
                    }
                }
                // Check if the system AXI is connected to the smart connect, connect it to this one if not
                for(systemAXIPort <- systemAXIPortsNames){
                    if (systemAXIPortsMap(systemAXIPort) > 0 && possibleConnections > 0){
                        tclWriteln(f"connect_bd_intf_net [get_bd_intf_pins ${fullSysGenDescriptor.name}_0/${systemAXIPort}] [get_bd_intf_pins smartconnect_${index}/S${(possibleConnections-1)}%02d_AXI]")
                        systemAXIPortsMap(systemAXIPort) -= 1
                        possibleConnections -= 1
                        totalConnections += 1
                    }
                }

                // Export the xdma axi port
                if (possibleConnections > 0){
                    //tclWriteln(f"connect_bd_intf_net [get_bd_intf_pins xdma_0/M_AXI] [get_bd_intf_pins smartconnect_${index}/S${(possibleConnections-1)}%02d_AXI]")
                    tclWriteln(f"make_bd_intf_pins_external  [get_bd_intf_pins smartconnect_${index}/S${(possibleConnections-1)}%02d_AXI] -name XDMA_AXI_SLAVE")
                    // Configure the port to match the xdma port with the max configuration of data
                    tclWriteln("set_property -dict [list CONFIG.ADDR_WIDTH 64 CONFIG.DATA_WIDTH 512 CONFIG.NUM_WRITE_OUTSTANDING 16 CONFIG.NUM_READ_OUTSTANDING 32 CONFIG.ID_WIDTH 4] [get_bd_intf_ports XDMA_AXI_SLAVE]")
                    possibleConnections -= 1
                    totalConnections += 1
                }
                index += 1
            }
        }
        assert(totalConnections == memConnectionsStats.totalAXIPorts, "Not all connections were made")

        // Export the smart connect masters for the Memory Connection
        for (i <- 0 until index){
            tclWriteln(f"make_bd_intf_pins_external [get_bd_intf_pins smartconnect_${i}/M00_AXI]")
        }
        // Config the ports to match the HBM
        tclWriteln("set_property -dict [list CONFIG.ADDR_WIDTH 34 CONFIG.DATA_WIDTH 256 CONFIG.NUM_READ_OUTSTANDING 2 CONFIG.NUM_WRITE_OUTSTANDING 2 CONFIG.PROTOCOL AXI3] [get_bd_intf_ports M00_AXI_*]")

        // Export the axil mgmt port for HardCilk with 32-bit data width converter
        tclWriteln("create_bd_cell -type ip -vlnv xilinx.com:ip:axi_dwidth_converter:2.1 axi_dwidth_converter_0")
        tclWriteln("connect_bd_intf_net [get_bd_intf_pins axi_dwidth_converter_0/M_AXI] [get_bd_intf_pins */s_axil_mgmt_hardcilk]")
        tclWriteln("make_bd_intf_pins_external  [get_bd_intf_pins axi_dwidth_converter_0/S_AXI] -name s_axil_mgmt_hardcilk_0")
        tclWriteln("set_property -dict [list CONFIG.PROTOCOL AXI4LITE] [get_bd_intf_ports s_axil_mgmt_hardcilk_0]")


        // Create the clk and reset_n port to export
        tclWriteln(f"create_bd_port -dir I -type clk -freq_hz ${fullSysGenDescriptor.targetFrequency*1000000} clk")
        tclWriteln(f"create_bd_port -dir I -type rst reset_n")

        // Connect the internal clocks and resets to the output of the BD
        tclWriteln("connect_bd_net [get_bd_ports clk] [get_bd_pins */*clk]") // for the PEs and smartconnects
        tclWriteln("connect_bd_net [get_bd_ports clk] [get_bd_pins */*clock]") // for the system
        tclWriteln("connect_bd_net [get_bd_ports reset_n] [get_bd_pins */ap_rst_n]") // for the PEs reset
        tclWriteln("connect_bd_net [get_bd_ports reset_n] [get_bd_pins */*aresetn]") // for the smartconnects reset
        
        // Create a polarity reversal for the system reset and connect it with the external negative reset
        tclWriteln("create_bd_cell -type ip -vlnv xilinx.com:ip:util_vector_logic:2.0 util_vector_logic_0")
        tclWriteln("""set_property -dict [list \
                CONFIG.C_OPERATION {not} \
                CONFIG.C_SIZE {1} \
                ] [get_bd_cells util_vector_logic_0]""")
        tclWriteln("connect_bd_net [get_bd_ports reset_n] [get_bd_pins util_vector_logic_0/Op1]")
        tclWriteln("connect_bd_net [get_bd_pins util_vector_logic_0/Res] [get_bd_pins */reset]")

        // Create a constant value of 1 and connect it to the ap_start of the PEs to always start
        tclWriteln("create_bd_cell -type ip -vlnv xilinx.com:ip:xlconstant:1.1 xlconstant_0")
        tclWriteln("connect_bd_net [get_bd_pins xlconstant_0/dout] [get_bd_pins */ap_start]")

        // Validate the design 
        tclWriteln("validate_bd_design")

        // End the group of commands
        tclWriteln("endgroup")


        // Write the tcl commands to a file
        val tclFile = new java.io.PrintWriter(new java.io.File(s"${tclFileDirectory}/${fullSysGenDescriptor.name}_compute.tcl"))
        tclFile.write(tclCommands.toString())
        tclFile.close()
    }

    initialize()
}

