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

module fib_pcie
   (HBM_CATTRIP_LS,
    PCIE_PERST_LS_65,
    PCIE_REFCLK1_clk_n,
    PCIE_REFCLK1_clk_p,

    PEX_RX15_P,
    PEX_RX15_N,
    PEX_TX15_P,
    PEX_TX15_N,
    PEX_RX14_P,
    PEX_RX14_N,
    PEX_TX14_P,
    PEX_TX14_N,
    PEX_RX13_P,
    PEX_RX13_N,
    PEX_TX13_P,
    PEX_TX13_N,
    PEX_RX12_P,
    PEX_RX12_N,
    PEX_TX12_P,
    PEX_TX12_N,
    PEX_RX11_P,
    PEX_RX11_N,
    PEX_TX11_P,
    PEX_TX11_N,
    PEX_RX10_P,
    PEX_RX10_N,
    PEX_TX10_P,
    PEX_TX10_N,
    PEX_RX9_P,
    PEX_RX9_N,
    PEX_TX9_P,
    PEX_TX9_N,
    PEX_RX8_P,
    PEX_RX8_N,
    PEX_TX8_P,
    PEX_TX8_N,
    PEX_RX7_P,
    PEX_RX7_N,
    PEX_TX7_P,
    PEX_TX7_N,
    PEX_RX6_P,
    PEX_RX6_N,
    PEX_TX6_P,
    PEX_TX6_N,
    PEX_RX5_P,
    PEX_RX5_N,
    PEX_TX5_P,
    PEX_TX5_N,
    PEX_RX4_P,
    PEX_RX4_N,
    PEX_TX4_P,
    PEX_TX4_N,
    PEX_RX3_P,
    PEX_RX3_N,
    PEX_TX3_P,
    PEX_TX3_N,
    PEX_RX2_P,
    PEX_RX2_N,
    PEX_TX2_P,
    PEX_TX2_N,
    PEX_RX1_P,
    PEX_RX1_N,
    PEX_TX1_P,
    PEX_TX1_N,
    PEX_RX0_P,
    PEX_RX0_N,
    PEX_TX0_P,
    PEX_TX0_N,

    SYSCLK2_clk_n,
    SYSCLK2_clk_p,
    SYSCLK3_clk_n,
    SYSCLK3_clk_p);
  output HBM_CATTRIP_LS;
  input PCIE_PERST_LS_65;
  input PCIE_REFCLK1_clk_n;
  input PCIE_REFCLK1_clk_p;

  input SYSCLK2_clk_n;
  input SYSCLK2_clk_p;
  input SYSCLK3_clk_n;
  input SYSCLK3_clk_p;

  input wire PEX_RX0_N;
  input wire PEX_RX0_P;
  output wire PEX_TX0_N;
  output wire PEX_TX0_P;
  input wire PEX_RX1_N;
  input wire PEX_RX1_P;
  output wire PEX_TX1_N;
  output wire PEX_TX1_P;
  input wire PEX_RX2_N;
  input wire PEX_RX2_P;
  output wire PEX_TX2_N;
  output wire PEX_TX2_P;
  input wire PEX_RX3_N;
  input wire PEX_RX3_P;
  output wire PEX_TX3_N;
  output wire PEX_TX3_P;
  input wire PEX_RX4_N;
  input wire PEX_RX4_P;
  output wire PEX_TX4_N;
  output wire PEX_TX4_P;
  input wire PEX_RX5_N;
  input wire PEX_RX5_P;
  output wire PEX_TX5_N;
  output wire PEX_TX5_P;
  input wire PEX_RX6_N;
  input wire PEX_RX6_P;
  output wire PEX_TX6_N;
  output wire PEX_TX6_P;
  input wire PEX_RX7_N;
  input wire PEX_RX7_P;
  output wire PEX_TX7_N;
  output wire PEX_TX7_P;
  input wire PEX_RX8_N;
  input wire PEX_RX8_P;
  output wire PEX_TX8_N;
  output wire PEX_TX8_P;
  input wire PEX_RX9_N;
  input wire PEX_RX9_P;
  output wire PEX_TX9_N;
  output wire PEX_TX9_P;
  input wire PEX_RX10_N;
  input wire PEX_RX10_P;
  output wire PEX_TX10_N;
  output wire PEX_TX10_P;
  input wire PEX_RX11_N;
  input wire PEX_RX11_P;
  output wire PEX_TX11_N;
  output wire PEX_TX11_P;
  input wire PEX_RX12_N;
  input wire PEX_RX12_P;
  output wire PEX_TX12_N;
  output wire PEX_TX12_P;
  input wire PEX_RX13_N;
  input wire PEX_RX13_P;
  output wire PEX_TX13_N;
  output wire PEX_TX13_P;
  input wire PEX_RX14_N;
  input wire PEX_RX14_P;
  output wire PEX_TX14_N;
  output wire PEX_TX14_P;
  input wire PEX_RX15_N;
  input wire PEX_RX15_P;
  output wire PEX_TX15_N;
  output wire PEX_TX15_P;


  wire HBM_CATTRIP_LS;
  wire PCIE_PERST_LS_65;
  wire PCIE_REFCLK1_clk_n;
  wire PCIE_REFCLK1_clk_p;
  
  wire SYSCLK2_clk_n;
  wire SYSCLK2_clk_p;
  wire SYSCLK3_clk_n;
  wire SYSCLK3_clk_p;

  wire [15:0]PEX_rxn;
  wire [15:0]PEX_rxp;
  wire [15:0]PEX_txn;
  wire [15:0]PEX_txp;

  assign PEX_rxn = {PEX_RX0_N, PEX_RX1_N, PEX_RX2_N, PEX_RX3_N, PEX_RX4_N, PEX_RX5_N, PEX_RX6_N, PEX_RX7_N, PEX_RX8_N, PEX_RX9_N, PEX_RX10_N, PEX_RX11_N, PEX_RX12_N, PEX_RX13_N, PEX_RX14_N, PEX_RX15_N};
  assign PEX_rxp = {PEX_RX0_P, PEX_RX1_P, PEX_RX2_P, PEX_RX3_P, PEX_RX4_P, PEX_RX5_P, PEX_RX6_P, PEX_RX7_P, PEX_RX8_P, PEX_RX9_P, PEX_RX10_P, PEX_RX11_P, PEX_RX12_P, PEX_RX13_P, PEX_RX14_P, PEX_RX15_P};
  assign PEX_txn = {PEX_TX0_N, PEX_TX1_N, PEX_TX2_N, PEX_TX3_N, PEX_TX4_N, PEX_TX5_N, PEX_TX6_N, PEX_TX7_N, PEX_TX8_N, PEX_TX9_N, PEX_TX10_N, PEX_TX11_N, PEX_TX12_N, PEX_TX13_N, PEX_TX14_N, PEX_TX15_N};
  assign PEX_txp = {PEX_TX0_P, PEX_TX1_P, PEX_TX2_P, PEX_TX3_P, PEX_TX4_P, PEX_TX5_P, PEX_TX6_P, PEX_TX7_P, PEX_TX8_P, PEX_TX9_P, PEX_TX10_P, PEX_TX11_P, PEX_TX12_P, PEX_TX13_P, PEX_TX14_P, PEX_TX15_P};
  

  design_1 design_1_i
       (.HBM_CATTRIP_LS(HBM_CATTRIP_LS),
        .PCIE_PERST_LS_65(PCIE_PERST_LS_65),
        .PCIE_REFCLK1_clk_n(PCIE_REFCLK1_clk_n),
        .PCIE_REFCLK1_clk_p(PCIE_REFCLK1_clk_p),
        .PEX_rxn(PEX_rxn),
        .PEX_rxp(PEX_rxp),
        .PEX_txn(PEX_txn),
        .PEX_txp(PEX_txp),
        .SYSCLK2_clk_n(SYSCLK2_clk_n),
        .SYSCLK2_clk_p(SYSCLK2_clk_p),
        .SYSCLK3_clk_n(SYSCLK3_clk_n),
        .SYSCLK3_clk_p(SYSCLK3_clk_p));
endmodule