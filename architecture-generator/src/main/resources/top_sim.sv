//Copyright 1986-2022 Xilinx, Inc. All Rights Reserved.
//--------------------------------------------------------------------------------
//Tool Version: Vivado v.2022.2 (lin64) Build 3671981 Fri Oct 14 04:59:54 MDT 2022
//Date        : Fri Jun 21 14:40:20 2024
//Host        : IC-LAP-LPC01 running 64-bit Ubuntu 22.04.4 LTS
//Command     : generate_target design_1_wrapper.bd
//Design      : design_1_wrapper
//Purpose     : IP block netlist
//--------------------------------------------------------------------------------
`timescale 1 ps / 1 ps

module top_sim
   (HBM_CATTRIP_LS,
    PCIE_PERST_LS_65,

    SYSCLK2_clk_n,
    SYSCLK2_clk_p,
    SYSCLK3_clk_n,
    SYSCLK3_clk_p,
    axi_vip_clk,
    axi_vip_aresetn);
    
  output HBM_CATTRIP_LS;
  input PCIE_PERST_LS_65;


  input SYSCLK2_clk_n;
  input SYSCLK2_clk_p;
  input SYSCLK3_clk_n;
  input SYSCLK3_clk_p;
  input axi_vip_clk;
  input axi_vip_aresetn;



  wire HBM_CATTRIP_LS;
  wire PCIE_PERST_LS_65;
  wire PCIE_REFCLK1_clk_n;
  wire PCIE_REFCLK1_clk_p;
  
  wire SYSCLK2_clk_n;
  wire SYSCLK2_clk_p;
  wire SYSCLK3_clk_n;
  wire SYSCLK3_clk_p;



  design_1 design_1_i
       (.HBM_CATTRIP_LS(HBM_CATTRIP_LS),
        .PCIE_PERST_LS_65(PCIE_PERST_LS_65),
        .axi_vip_clk(axi_vip_clk),
        .axi_vip_aresetn(axi_vip_aresetn),
        .SYSCLK2_clk_n(SYSCLK2_clk_n),
        .SYSCLK2_clk_p(SYSCLK2_clk_p),
        .SYSCLK3_clk_n(SYSCLK3_clk_n),
        .SYSCLK3_clk_p(SYSCLK3_clk_p));
endmodule