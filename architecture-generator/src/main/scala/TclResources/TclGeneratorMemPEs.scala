package TclResources

import Descriptors._
import scala.collection.mutable.Map
import scala.util.control.Breaks._

object TclGeneratorMemPEs {
  def generate(fullSysGenDescriptor: FullSysGenDescriptor, tclFileDirectory: String, reduce_axi: Boolean) = {
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

    // Get system AXI Ports Names and create a map to int initialzed with 1 for each one of them
    val systemAXIPortsNames = fullSysGenDescriptor.getSystemAXIPortsNames(reduce_axi)
    val systemAXIPortsMap = Map[String, Int]()
    for (portName <- systemAXIPortsNames) {
      systemAXIPortsMap(portName) = 1
    }

    // Mutable Map from a string to an integer
    val interconnectMap = Map[String, Int]()
    for (task <- fullSysGenDescriptor.taskDescriptors) {
      interconnectMap(task.name) = task.numProcessingElements
    }
    interconnectMap("numSysAXI") = memConnectionsStats.numSysAXI

    // Task PE index map
    val taskPEIndexMap = Map[String, Int]()
    for (task <- fullSysGenDescriptor.taskDescriptors) {
      taskPEIndexMap(task.name) = 0
    }

    // Create and configure the xdma
    tclWriteln(TclGeneralConfigs.getXdmaConfigTclSyntax())

    // Create and configure the hbm
    tclWriteln(
      TclGeneralConfigs.getHBMConfigTclSyntax(fullSysGenDescriptor.getMemoryConnectionsStats(reduce_axi).totalAXIPorts)
    )

    // Instantiate and configure the smart connects needed
    var index = 0
    var totalConnections = 0
    var xdma_connected = false
    for (interconnectDescriptor <- memConnectionsStats.interconnectDescriptors) {

      for (i <- 0 until interconnectDescriptor.count) {
        tclWriteln(f"create_bd_cell -type ip -vlnv xilinx.com:ip:smartconnect:1.0 smartconnect_${index}")
        tclWriteln(
          f"set_property -dict [list CONFIG.NUM_SI {${interconnectDescriptor.ratio}} CONFIG.NUM_CLKS {2}] [get_bd_cells smartconnect_${index}]"
        )

        var possibleConnections = interconnectDescriptor.ratio

        while (possibleConnections > 0) {
          breakable {
            // Check if all tasks PEs are connected to the smart connect, connect them to this one if not
            for (task <- fullSysGenDescriptor.taskDescriptors) {
              if (task.hasAXI)
                for (i <- taskPEIndexMap(task.name) until task.numProcessingElements) {
                  if (interconnectMap(task.name) > 0 && possibleConnections > 0) {
                    tclWriteln(
                      f"connect_bd_intf_net [get_bd_intf_pins ${fullSysGenDescriptor.name}_0/${task.name}_${taskPEIndexMap(
                          task.name
                        )}_m_axi_gmem] [get_bd_intf_pins smartconnect_${index}/S${(possibleConnections - 1)}%02d_AXI]"
                    )
                    interconnectMap(task.name) -= 1
                    possibleConnections -= 1
                    taskPEIndexMap(task.name) += 1
                    totalConnections += 1
                    break()
                  }
                }
            }
          }

          breakable {
            // Check if the system AXI is connected to the smart connect, connect it to this one if not
            for (systemAXIPort <- systemAXIPortsNames) {
              if (systemAXIPortsMap(systemAXIPort) > 0 && possibleConnections > 0) {
                tclWriteln(
                  f"connect_bd_intf_net [get_bd_intf_pins ${fullSysGenDescriptor.name}_0/${systemAXIPort}] [get_bd_intf_pins smartconnect_${index}/S${(possibleConnections - 1)}%02d_AXI]"
                )
                systemAXIPortsMap(systemAXIPort) -= 1
                possibleConnections -= 1
                totalConnections += 1
                break()
              }
            }
          }

          // Connect the xdma axi port
          if (possibleConnections > 0 && !xdma_connected) {
            // tclWriteln(f"connect_bd_intf_net [get_bd_intf_pins xdma_0/M_AXI] [get_bd_intf_pins smartconnect_${index}/S${(possibleConnections-1)}%02d_AXI]")
            tclWriteln(
              f"connect_bd_intf_net  [get_bd_intf_pins smartconnect_${index}/S${(possibleConnections - 1)}%02d_AXI] [get_bd_intf_pins axi_clock_converter_0/M_AXI]"
            )
            // Configure the port to match the xdma port with the max configuration of data
            possibleConnections -= 1
            totalConnections += 1
            xdma_connected = true
          }
        }
        index += 1
      }
    }
    assert(
      totalConnections == memConnectionsStats.totalAXIPorts,
      f"Not all connections were made, only ${totalConnections} out of ${memConnectionsStats.totalAXIPorts} were made"
    )

    // Connect the management port from xdma to the compute system
    tclWriteln("create_bd_cell -type ip -vlnv xilinx.com:ip:axi_dwidth_converter:2.1 axi_dwidth_converter_0")
    tclWriteln("connect_bd_intf_net [get_bd_intf_pins axi_dwidth_converter_0/M_AXI] [get_bd_intf_pins */s_axil_mgmt_hardcilk]")
    tclWriteln(
      "connect_bd_intf_net [get_bd_intf_pins axi_clock_converter_1/M_AXI] [get_bd_intf_pins axi_dwidth_converter_0/S_AXI]"
    )

    // Connect the smart connect masters to the HBM
    for (i <- 0 until index) {
      tclWriteln(f"connect_bd_intf_net [get_bd_intf_pins smartconnect_${i}/M00_AXI] [get_bd_intf_pins hbm_0/SAXI_${i}%02d_8HI]")
    }

    // Create the clocking wizard and reset for the system
    tclWriteln(TclGeneralConfigs.getSytstemClockingAndResetConfigTclSyntax(fullSysGenDescriptor))

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
