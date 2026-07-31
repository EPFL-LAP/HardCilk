##################################################################################################################################################################
##
## Alveo U55C constraints for the HardCilk XDMA/PCIe flow (top_pcie).
##
## Derived from the Xilinx U55C master XDC rev 1.00, reduced to the ports
## top_pcie actually exposes. The master XDC constrains the whole board; every
## line naming a port this design does not have resolves to an empty get_ports
## and produces a [Common 17-55] / [Vivado 12-627] error, so those are gone:
## SYSCLK4, TESTCLK_OUT, PPS_*, NS1/NS2_*, SYNCE_*, PCIE_SYSCLK0/1,
## PCIE_REFCLK0, SI_*, I2C_*, CPU_RESET_FPGA, PEX_PWRBRKN, FPGA_UART*,
## MSP_GPIO*, the QSFP28 LEDs and the Quad-130/131 GTY pins.
##
## Design-specific constraints are NOT here. The floorplan is generated per
## design into <name>_floorplan.xdc, and the HLS combinatorial-loop DRC waivers
## into <name>_drc_waivers.tcl (an opt_design pre-hook). This file used to carry
## 144 hand-written ALLOW_COMBINATORIAL_LOOPS constraints naming triangleCount_0
## and pageRank_0 with fixed peArray_N indices, which were wrong for every other
## design.
##
##################################################################################################################################################################

set_property CONFIG_VOLTAGE 1.8 [current_design]

##################################################################################################################################################################
##
## Onboard clocking
##   SYSCLK3 - 100 MHz, drives clk_wiz_0 (the design clock)
##   SYSCLK2 - 100 MHz, drives HBM_REF_CLK and the HBM APB PCLK
##
##################################################################################################################################################################
set_property PACKAGE_PIN BK43 [get_ports SYSCLK3_clk_p]
set_property PACKAGE_PIN BK44 [get_ports SYSCLK3_clk_n]
set_property IOSTANDARD LVDS  [get_ports SYSCLK3_clk_p]
set_property IOSTANDARD LVDS  [get_ports SYSCLK3_clk_n]

set_property PACKAGE_PIN BK10 [get_ports SYSCLK2_clk_p]
set_property PACKAGE_PIN BL10 [get_ports SYSCLK2_clk_n]
set_property IOSTANDARD LVDS  [get_ports SYSCLK2_clk_p]
set_property IOSTANDARD LVDS  [get_ports SYSCLK2_clk_n]

## PCIe reference clock: 100 MHz, Bank 225 MGTREFCLK0 (GTYE4_COMMON_X1Y1),
## distributed to quads 224-227. This is the only PCIe refclk on the U55C.
set_property PACKAGE_PIN AR15 [get_ports PCIE_REFCLK1_clk_p]
set_property PACKAGE_PIN AR14 [get_ports PCIE_REFCLK1_clk_n]

## SYSCLK3 is deliberately not given a create_clock here. The block design's
## own constraints already define a 10 ns clock on this port (it is the clk_wiz
## input, and the clk_wiz propagates a clock to the top-level port), so adding
## one produces "[Constraints 18-1056] Clock 'SYSCLK3' completely overrides
## clock 'SYSCLK3_clk_p'". Same period either way, but the override is noise.
##
## SYSCLK2 and PCIE_REFCLK1 feed plain IBUFDS buffers, which do not propagate a
## clock to the top level, so those two are constrained here.
create_clock -period 10.000 -name SYSCLK2     [get_ports SYSCLK2_clk_p]
create_clock -period 10.000 -name PCIEREFCLK1 [get_ports PCIE_REFCLK1_clk_p]

##################################################################################################################################################################
##
## HBM_CATTRIP_LS      HBM catastrophic over-temperature output to the satellite controller
## PCIE_PERST_LS_65    Active-low reset input from the PCIe connector
##
##################################################################################################################################################################
set_property PACKAGE_PIN BE45    [get_ports HBM_CATTRIP_LS]
set_property IOSTANDARD LVCMOS18 [get_ports HBM_CATTRIP_LS]
set_property PACKAGE_PIN BF41    [get_ports PCIE_PERST_LS_65]
set_property IOSTANDARD LVCMOS18 [get_ports PCIE_PERST_LS_65]

##################################################################################################################################################################
##
## PCIe MGTY interface, quads 224/225/226/227 (all in SLR0 on this device).
##
## Lane 0 is the topmost GT of the topmost quad (PG213): PEX_RX0_P = AL2 =
## GTYE4_CHANNEL_X1Y15 in quad 227, down to lane 15 = BC2 = X1Y0 in quad 224.
## top.v maps these onto the xdma pcie_mgt vectors with PEX_rxp[0] = PEX_RX0_P.
##
##################################################################################################################################################################
set_property PACKAGE_PIN BC2 [get_ports PEX_RX15_P]
set_property PACKAGE_PIN BC1 [get_ports PEX_RX15_N]
set_property PACKAGE_PIN BC7 [get_ports PEX_TX15_P]
set_property PACKAGE_PIN BC6 [get_ports PEX_TX15_N]
set_property PACKAGE_PIN BB4 [get_ports PEX_RX14_P]
set_property PACKAGE_PIN BB3 [get_ports PEX_RX14_N]
set_property PACKAGE_PIN BC11 [get_ports PEX_TX14_P]
set_property PACKAGE_PIN BC10 [get_ports PEX_TX14_N]
set_property PACKAGE_PIN BA2 [get_ports PEX_RX13_P]
set_property PACKAGE_PIN BA1 [get_ports PEX_RX13_N]
set_property PACKAGE_PIN BB9 [get_ports PEX_TX13_P]
set_property PACKAGE_PIN BB8 [get_ports PEX_TX13_N]
set_property PACKAGE_PIN BA6 [get_ports PEX_RX12_P]
set_property PACKAGE_PIN BA5 [get_ports PEX_RX12_N]
set_property PACKAGE_PIN BA11 [get_ports PEX_TX12_P]
set_property PACKAGE_PIN BA10 [get_ports PEX_TX12_N]
set_property PACKAGE_PIN AY4 [get_ports PEX_RX11_P]
set_property PACKAGE_PIN AY3 [get_ports PEX_RX11_N]
set_property PACKAGE_PIN AY9 [get_ports PEX_TX11_P]
set_property PACKAGE_PIN AY8 [get_ports PEX_TX11_N]
set_property PACKAGE_PIN AW2 [get_ports PEX_RX10_P]
set_property PACKAGE_PIN AW1 [get_ports PEX_RX10_N]
set_property PACKAGE_PIN AW11 [get_ports PEX_TX10_P]
set_property PACKAGE_PIN AW10 [get_ports PEX_TX10_N]
set_property PACKAGE_PIN AW6 [get_ports PEX_RX9_P]
set_property PACKAGE_PIN AW5 [get_ports PEX_RX9_N]
set_property PACKAGE_PIN AV9 [get_ports PEX_TX9_P]
set_property PACKAGE_PIN AV8 [get_ports PEX_TX9_N]
set_property PACKAGE_PIN AV4 [get_ports PEX_RX8_P]
set_property PACKAGE_PIN AV3 [get_ports PEX_RX8_N]
set_property PACKAGE_PIN AU7 [get_ports PEX_TX8_P]
set_property PACKAGE_PIN AU6 [get_ports PEX_TX8_N]
set_property PACKAGE_PIN AU2 [get_ports PEX_RX7_P]
set_property PACKAGE_PIN AU1 [get_ports PEX_RX7_N]
set_property PACKAGE_PIN AU11 [get_ports PEX_TX7_P]
set_property PACKAGE_PIN AU10 [get_ports PEX_TX7_N]
set_property PACKAGE_PIN AT4 [get_ports PEX_RX6_P]
set_property PACKAGE_PIN AT3 [get_ports PEX_RX6_N]
set_property PACKAGE_PIN AT9 [get_ports PEX_TX6_P]
set_property PACKAGE_PIN AT8 [get_ports PEX_TX6_N]
set_property PACKAGE_PIN AR2 [get_ports PEX_RX5_P]
set_property PACKAGE_PIN AR1 [get_ports PEX_RX5_N]
set_property PACKAGE_PIN AR7 [get_ports PEX_TX5_P]
set_property PACKAGE_PIN AR6 [get_ports PEX_TX5_N]
set_property PACKAGE_PIN AP4 [get_ports PEX_RX4_P]
set_property PACKAGE_PIN AP3 [get_ports PEX_RX4_N]
set_property PACKAGE_PIN AR11 [get_ports PEX_TX4_P]
set_property PACKAGE_PIN AR10 [get_ports PEX_TX4_N]
set_property PACKAGE_PIN AN2 [get_ports PEX_RX3_P]
set_property PACKAGE_PIN AN1 [get_ports PEX_RX3_N]
set_property PACKAGE_PIN AP9 [get_ports PEX_TX3_P]
set_property PACKAGE_PIN AP8 [get_ports PEX_TX3_N]
set_property PACKAGE_PIN AN6 [get_ports PEX_RX2_P]
set_property PACKAGE_PIN AN5 [get_ports PEX_RX2_N]
set_property PACKAGE_PIN AN11 [get_ports PEX_TX2_P]
set_property PACKAGE_PIN AN10 [get_ports PEX_TX2_N]
set_property PACKAGE_PIN AM4 [get_ports PEX_RX1_P]
set_property PACKAGE_PIN AM3 [get_ports PEX_RX1_N]
set_property PACKAGE_PIN AM9 [get_ports PEX_TX1_P]
set_property PACKAGE_PIN AM8 [get_ports PEX_TX1_N]
set_property PACKAGE_PIN AL2 [get_ports PEX_RX0_P]
set_property PACKAGE_PIN AL1 [get_ports PEX_RX0_N]
set_property PACKAGE_PIN AL11 [get_ports PEX_TX0_P]
set_property PACKAGE_PIN AL10 [get_ports PEX_TX0_N]

##################################################################################################################################################################
##
## Timing exceptions and IP waivers
##
##################################################################################################################################################################
set_false_path -to [get_pins -hier *sync_reg[0]/D]

#---------------------- Waivers for the XDMA example-design level constraints --------------------#

create_waiver -type DRC -id {REQP-1839} -tags "1166691" -scope -internal -user "xdma" -desc "DRC expects synchronous pins to be provided to BRAM inputs. Since synchronization is present one stage before, it is safe to ignore" -objects [list [get_cells -hierarchical -filter {NAME =~ {*/blk_mem_xdma_inst/U0/inst_blk_mem_gen/*.ram}}] [get_cells -hierarchical -filter {NAME =~ {*/AXI_BRAM_CTL/U0/gint_inst*.mem_reg*} && PRIMITIVE_TYPE =~ {*BRAM*}}] [get_cells -hierarchical -filter {NAME =~ {*xdma_inst/U0/gint_inst*.mem_reg*} && PRIMITIVE_TYPE =~ {*BRAM*}}] [get_cells -hierarchical -filter {NAME =~ {*axi_bram_gen_bypass_inst/U0/gint_inst*.mem_reg*} && PRIMITIVE_TYPE =~ {*BRAM*}}] [get_cells -hierarchical -filter {NAME =~ {*/blk_mem_axiLM_inst/U0/inst_blk_mem_gen/*.ram}}] [get_cells -hierarchical -filter {NAME =~ {*/blk_mem_gen_bypass_inst/U0/inst_blk_mem_gen/*.ram}}]]

create_waiver -type CDC -id {CDC-1} -tags "1165825" -scope -internal -user "xdma" -desc "PCIe reset path -Safe to waive" -from [get_ports sys_rst_n] -to [get_pins -hier -filter {NAME =~ {*/user_clk_heartbeat_reg[*]/R}}]

## The master XDC carried a pair of false paths between the two HBM APB clocks:
##
##   set_false_path -from [get_clocks *APB_0_PCLK] -to [get_clocks *APB_1_PCLK]
##   set_false_path -from [get_clocks *APB_1_PCLK] -to [get_clocks *APB_0_PCLK]
##
## They are dropped because TclGeneralConfigs.getHBMConfigTclSyntax generates the
## HBM IP with CONFIG.USER_APB_EN {false}, so no *APB_*_PCLK clock ever exists and
## the constraints only raise [Vivado 12-4739]. They cannot be wrapped in an `if`
## either -- Vivado rejects control flow in an XDC ([Designutils 20-1307]). If the
## APB interface is ever enabled, emit these from the Scala generator into the
## design-specific XDC instead of putting them back here.
