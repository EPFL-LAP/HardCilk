package TclResources

import Descriptors._

object TclGeneralConfigs {

  def getHBMConfigTclSyntax(totalAXIPorts: Int): String = {
    val sb = new StringBuilder

    sb.append("""
            # 1. Create and configure the HBM
            create_bd_cell -type ip -vlnv xilinx.com:ip:hbm:1.0 hbm_0

            set_property -dict [list \
            CONFIG.USER_APB_EN {false} \
            CONFIG.USER_HBM_DENSITY {16GB} \
            CONFIG.USER_MC0_ECC_BYPASS {true} \
            CONFIG.USER_XSDB_INTF_EN {FALSE} \
            ] [get_bd_cells hbm_0]

            # 2. Create a constant of width 32 bits and value 0x00000000 and connect to the parity input of the HBM
            create_bd_cell -type ip -vlnv xilinx.com:ip:xlconstant:1.1 xlconstant_0
            set_property -dict [list CONFIG.CONST_VAL {0} CONFIG.CONST_WIDTH {32} ] [get_bd_cells xlconstant_0]
                # Connecting the constant to the parity bits of the HBM 

        """)

    for (i <- 0 until math.min(totalAXIPorts, 32)) {
      // Connecting the constant to the parity bits of the HBM
      sb.append("connect_bd_net [get_bd_pins xlconstant_0/dout] [get_bd_pins hbm_0/AXI_" + f"${i}%02d" + "_WDATA_PARITY]\n")
    }

    if (totalAXIPorts < 32) {
      // Create a config for the HBm to remove the extra axi ports
      sb.append("set_property -dict [list \\\n")

      for (i <- totalAXIPorts until 32) {
        sb.append("CONFIG.USER_SAXI_" + f"${i}%02d" + " {false} \\\n")
      }
      sb.append("] [get_bd_cells hbm_0]\n")
    }

    sb.append("""
        #  3. EXPORT HBM_CATTRIP_LS 
        create_bd_cell -type ip -vlnv xilinx.com:ip:util_vector_logic:2.0 util_vector_logic_1
        set_property -dict [list CONFIG.C_OPERATION {or} CONFIG.C_SIZE {1} ] [get_bd_cells util_vector_logic_1]
        create_bd_port -dir O HBM_CATTRIP_LS
        connect_bd_net [get_bd_ports HBM_CATTRIP_LS] [get_bd_pins util_vector_logic_1/Res]
        connect_bd_net [get_bd_pins util_vector_logic_1/Op1] [get_bd_pins hbm_0/DRAM_0_STAT_CATTRIP]
        connect_bd_net [get_bd_pins util_vector_logic_1/Op2] [get_bd_pins hbm_0/DRAM_1_STAT_CATTRIP]

        # 4. Create the IBUFDS for the clock of the HBM and connect them, export the input differential clock as sysclk2
        create_bd_cell -type ip -vlnv xilinx.com:ip:util_ds_buf:2.2 util_ds_buf_1
        connect_bd_net [get_bd_pins util_ds_buf_1/IBUF_OUT] [get_bd_pins hbm_0/HBM_REF_CLK_*]
        connect_bd_net [get_bd_pins util_ds_buf_1/IBUF_OUT] [get_bd_pins hbm_0/APB_*_PCLK]
        make_bd_intf_pins_external  [get_bd_intf_pins util_ds_buf_1/CLK_IN_D] -name SYSCLK2

        # 5. Create processor system reset for the HBM APB PRESETN
        create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 proc_sys_reset_0
        connect_bd_net [get_bd_pins proc_sys_reset_0/slowest_sync_clk] [get_bd_pins util_ds_buf_1/IBUF_OUT]
        connect_bd_net [get_bd_pins proc_sys_reset_0/peripheral_aresetn] [get_bd_pins hbm_0/APB_*_PRESET_N]
        connect_bd_net [get_bd_ports PCIE_PERST_LS_65] [get_bd_pins proc_sys_reset_0/ext_reset_in]
        """)

    sb.toString()
  }

  def getXdmaConfigTclSyntax(): String = {
    """
        # 1.Create the xdma
        create_bd_cell -type ip -vlnv xilinx.com:ip:xdma:4.1 xdma_0

        # 2.Configure the xdma
        set_property -dict [list \
                    CONFIG.axil_master_64bit_en {true} \
                    CONFIG.axilite_master_en {true} \
                    CONFIG.axilite_master_scale {Gigabytes} \
                    CONFIG.pl_link_cap_max_link_speed {8.0_GT/s} \
                    CONFIG.pl_link_cap_max_link_width {X16} \
                    CONFIG.xdma_pcie_64bit_en {true} \
                    ] [get_bd_cells xdma_0]

        set_property -dict [list \
            CONFIG.axilite_master_scale {Megabytes} \
            CONFIG.axilite_master_size {4} \
        ] [get_bd_cells xdma_0]

        set_property CONFIG.cfg_mgmt_if {false} [get_bd_cells xdma_0]

        # Ground the irq
        create_bd_cell -type ip -vlnv xilinx.com:ip:xlconstant:1.1 xlconstant_1
        set_property CONFIG.CONST_VAL {0} [get_bd_cells xlconstant_1]
        connect_bd_net [get_bd_pins xlconstant_1/dout] [get_bd_pins xdma_0/usr_irq_req]

        # Export the xdma pcie_mgt interface
        make_bd_intf_pins_external  [get_bd_intf_pins xdma_0/pcie_mgt] -name PEX

        # 3. Create the clocking buffer for the xdma
        create_bd_cell -type ip -vlnv xilinx.com:ip:util_ds_buf:2.2 util_ds_buf_0
        set_property CONFIG.C_BUF_TYPE {IBUFDSGTE} [get_bd_cells util_ds_buf_0]

        # Connect the clocks to the XDMA IP
        connect_bd_net [get_bd_pins util_ds_buf_0/IBUF_DS_ODIV2] [get_bd_pins xdma_0/sys_clk]
        connect_bd_net [get_bd_pins util_ds_buf_0/IBUF_OUT] [get_bd_pins xdma_0/sys_clk_gt]

        # Export the differential clock of the buffer
        make_bd_intf_pins_external  [get_bd_intf_pins util_ds_buf_0/CLK_IN_D] -name PCIE_REFCLK1

        # Export the PCIE reset
        make_bd_pins_external  [get_bd_pins xdma_0/sys_rst_n] -name PCIE_PERST_LS_65

        # Make AXI domain clock converter for the xdma memory access
        create_bd_cell -type ip -vlnv xilinx.com:ip:axi_clock_converter:2.1 axi_clock_converter_0

        # Make AXI domain clock converter for the xdma management access
        create_bd_cell -type ip -vlnv xilinx.com:ip:axi_clock_converter:2.1 axi_clock_converter_1

        # Connect the xdma to the axi clock converters
        connect_bd_intf_net [get_bd_intf_pins xdma_0/M_AXI] [get_bd_intf_pins axi_clock_converter_0/S_AXI]
        connect_bd_net [get_bd_pins xdma_0/axi_aclk] [get_bd_pins axi_clock_converter_0/s_axi_aclk]
        connect_bd_net [get_bd_pins xdma_0/axi_aresetn] [get_bd_pins axi_clock_converter_0/s_axi_aresetn]

        connect_bd_intf_net [get_bd_intf_pins xdma_0/M_AXI_LITE] [get_bd_intf_pins axi_clock_converter_1/S_AXI]
        connect_bd_net [get_bd_pins xdma_0/axi_aclk] [get_bd_pins axi_clock_converter_1/s_axi_aclk]
        connect_bd_net [get_bd_pins xdma_0/axi_aresetn] [get_bd_pins axi_clock_converter_1/s_axi_aresetn]
        """
  }

  def getSytstemClockingAndResetConfigTclSyntax(descriptor: fullSysGenDescriptor): String = {
    val sb = new StringBuilder

    // Create and configure the clock wizard
    sb.append("create_bd_cell -type ip -vlnv xilinx.com:ip:clk_wiz:6.0 clk_wiz_0")
    sb.append(
      """
        set_property -dict [list \
            CONFIG.CLKIN1_JITTER_PS {100.0} \
            CONFIG.CLKOUT1_DRIVES {Buffer} \
            CONFIG.CLKOUT1_JITTER {98.427} \
            CONFIG.CLKOUT1_PHASE_ERROR {87.466} \""" +
        f"CONFIG.CLKOUT1_REQUESTED_OUT_FREQ ${descriptor.targetFrequency}%.3f" + """\""" +
        """
            CONFIG.CLKOUT2_DRIVES {Buffer} \
            CONFIG.CLKOUT3_DRIVES {Buffer} \
            CONFIG.CLKOUT4_DRIVES {Buffer} \
            CONFIG.CLKOUT5_DRIVES {Buffer} \
            CONFIG.CLKOUT6_DRIVES {Buffer} \
            CONFIG.CLKOUT7_DRIVES {Buffer} \
            CONFIG.ENABLE_CDDC {false} \
            CONFIG.ENABLE_CLOCK_MONITOR {false} \
            CONFIG.FEEDBACK_SOURCE {FDBK_AUTO} \
            CONFIG.MMCM_BANDWIDTH {OPTIMIZED} \
            CONFIG.MMCM_CLKFBOUT_MULT_F {11.875} \
            CONFIG.MMCM_CLKIN1_PERIOD {10.000} \
            CONFIG.MMCM_CLKOUT0_DIVIDE_F {4.750} \
            CONFIG.MMCM_COMPENSATION {AUTO} \
            CONFIG.MMCM_DIVCLK_DIVIDE {1} \
            CONFIG.OPTIMIZE_CLOCKING_STRUCTURE_EN {false} \
            CONFIG.PRIMITIVE {MMCM} \
            CONFIG.PRIM_SOURCE {Differential_clock_capable_pin} \
            CONFIG.RESET_PORT {resetn} \
            CONFIG.RESET_TYPE {ACTIVE_LOW} \
            ] [get_bd_cells clk_wiz_0]
        """
    )

    // Export the clock to the external clock
    sb.append("make_bd_intf_pins_external  [get_bd_intf_pins clk_wiz_0/CLK_IN1_D] -name SYSCLK3\n")

    // Connect the axi clocks to this clock
    sb.append("connect_bd_net [get_bd_pins clk_wiz_0/clk_out1] [get_bd_pins hbm_0/AXI_*_ACLK]\n")
    sb.append(f"connect_bd_net [get_bd_pins clk_wiz_0/clk_out1] [get_bd_pins ${descriptor.name}_0/clock]\n")
    sb.append("connect_bd_net [get_bd_pins clk_wiz_0/clk_out1] [get_bd_pins axi_clock_converter_0/m_axi_aclk]\n")
    sb.append("connect_bd_net [get_bd_pins clk_wiz_0/clk_out1] [get_bd_pins axi_clock_converter_1/m_axi_aclk]\n")

    // Connect the clock wizard reset
    sb.append("connect_bd_net [get_bd_ports PCIE_PERST_LS_65] [get_bd_pins clk_wiz_0/resetn]\n")

    // Create reset system
    sb.append("create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 proc_sys_reset_1\n")

    // Connect it to the clock wizard and the axi resets of the system
    sb.append("connect_bd_net [get_bd_pins clk_wiz_0/locked] [get_bd_pins proc_sys_reset_1/dcm_locked]\n")
    sb.append("connect_bd_net [get_bd_pins proc_sys_reset_1/slowest_sync_clk] [get_bd_pins clk_wiz_0/clk_out1]\n")
    sb.append("connect_bd_net [get_bd_pins proc_sys_reset_1/peripheral_aresetn] [get_bd_pins hbm_0/AXI_*_ARESET_N]\n")
    sb.append(f"connect_bd_net [get_bd_pins proc_sys_reset_1/peripheral_reset] [get_bd_pins ${descriptor.name}_0/reset]\n")
    sb.append(
      "connect_bd_net [get_bd_pins proc_sys_reset_1/peripheral_aresetn] [get_bd_pins axi_clock_converter_0/m_axi_aresetn]\n"
    )
    sb.append(
      "connect_bd_net [get_bd_pins proc_sys_reset_1/peripheral_aresetn] [get_bd_pins axi_clock_converter_1/m_axi_aresetn]\n"
    )
    sb.append("connect_bd_net [get_bd_ports PCIE_PERST_LS_65] [get_bd_pins proc_sys_reset_1/ext_reset_in]\n")

    sb.append("connect_bd_net [get_bd_pins clk_wiz_0/clk_out1] [get_bd_pins smartconnect*/*clk]\n") // for the   smartconnects
    sb.append(
      "connect_bd_net [get_bd_pins proc_sys_reset_1/peripheral_aresetn] [get_bd_pins smartconnect*/*aresetn]\n"
    ) // for the smartconnects reset

    sb.append(
      "connect_bd_net [get_bd_pins clk_wiz_0/clk_out1] [get_bd_pins axi_dwidth_converter*/*clk*]\n"
    ) // for the   smartconnects
    sb.append(
      "connect_bd_net [get_bd_pins proc_sys_reset_1/peripheral_aresetn] [get_bd_pins axi_dwidth_converter*/*aresetn*]\n"
    ) // for the smartconnects reset

    sb.toString()
  }

  def getMastersAddressMapTcl(): String = {
    ""
  }
}
