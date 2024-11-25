`timescale 1ns / 1ps
//////////////////////////////////////////////////////////////////////////////////
// Company: 
// Engineer: 
// 
// Create Date: 08/22/2024 07:10:30 PM
// Design Name: 
// Module Name: top_sim
// Project Name: 
// Target Devices: 
// Tool Versions: 
// Description: 
// 
// Dependencies: 
// 
// Revision:
// Revision 0.01 - File Created
// Additional Comments:
// 
//////////////////////////////////////////////////////////////////////////////////

import axi_vip_pkg::*;

import design_1_axi_vip_0_0_pkg::*;

import design_1_axi_vip_1_0_pkg::*;

module main_sim(
        output logic  HBM_CATTRIP_LS,
        output logic  PCIE_PERST_LS_65,

        output logic  SYSCLK2_clk_n,
        output logic  SYSCLK2_clk_p,
        output logic  SYSCLK3_clk_n,
        output logic  SYSCLK3_clk_p,
        output logic  axi_vip_clk,
        output logic  axi_vip_aresetn
    );
    

export "DPI-SC" task S_AXI_READ_MEM;
export "DPI-SC" task S_AXI_WRITE_MEM;
export "DPI-SC" task S_AXI_READ_REG;
export "DPI-SC" task S_AXI_WRITE_REG;
    
design_1_axi_vip_0_0_mst_t        mst_agent_0_mem;
design_1_axi_vip_1_0_mst_t        mst_agent_1_reg;


logic HBM_CATTRIP_LS_;
logic PCIE_PERST_LS_65_;
logic SYSCLK2_clk_n_;
logic SYSCLK2_clk_p_;
logic SYSCLK3_clk_n_;
logic SYSCLK3_clk_p_;
logic axi_vip_clk_;
logic axi_vip_aresetn_;

assign HBM_CATTRIP_LS = HBM_CATTRIP_LS_;
assign PCIE_PERST_LS_65 = PCIE_PERST_LS_65_;
assign SYSCLK2_clk_n = SYSCLK2_clk_n_;
assign SYSCLK2_clk_p = SYSCLK2_clk_p_;
assign SYSCLK3_clk_n = SYSCLK3_clk_n_;
assign SYSCLK3_clk_p = SYSCLK3_clk_p_;
assign axi_vip_clk = axi_vip_clk_;
assign axi_vip_aresetn = axi_vip_aresetn_;


top_sim DUT( HBM_CATTRIP_LS_, PCIE_PERST_LS_65_, SYSCLK2_clk_n_, SYSCLK2_clk_p_, SYSCLK3_clk_n_, SYSCLK3_clk_p_, axi_vip_clk_, axi_vip_aresetn_);


  initial begin
    mst_agent_0_mem = new("master vip agent",DUT.design_1_i.axi_vip_0.inst.IF);//ms  
    mst_agent_0_mem.set_agent_tag("Master VIP 0"); 
    mst_agent_0_mem.start_master(); 
    
    mst_agent_1_reg = new("master vip agent",DUT.design_1_i.axi_vip_1.inst.IF);//ms  
    mst_agent_1_reg.set_agent_tag("Master VIP 1"); 
    mst_agent_1_reg.start_master(); 
    
    
    $timeformat (-12, 1, " ps", 1);
  end

  // Create the initial begin block for the resets
  initial begin
    axi_vip_aresetn_ <= 0;
    PCIE_PERST_LS_65_ <= 0;
    #170 PCIE_PERST_LS_65_ <= 1;
    #10 axi_vip_aresetn_ <= 1;
  end

    // Initialize the differential clock signals
    initial begin
        // Initialize SysClk2 (100 MHz)
        SYSCLK2_clk_n_ <= 0;
        SYSCLK2_clk_p_ <= 1;
        
    
        // Initialize SysClk3 (225 MHz)
        SYSCLK3_clk_n_ <= 0;
        SYSCLK3_clk_p_ <= 1;
        
    end
    
    // SysClk2 is differential, running at 100 MHz
    always begin
        #5;
        SYSCLK2_clk_n_ <= ~SYSCLK2_clk_n_;
        SYSCLK2_clk_p_ <= ~SYSCLK2_clk_p_;
    end
    
    // SysClk3 is differential, running at 100 MHz
    always begin
        #5;
        SYSCLK3_clk_n_ <= ~SYSCLK3_clk_n_;
        SYSCLK3_clk_p_ <= ~SYSCLK3_clk_p_;
    end
    

  always begin
    axi_vip_clk_ <= 0;
    #2 axi_vip_clk_ <= 1;
    #2 axi_vip_clk_ <= 0;
  end


  // use explicit casting to avoid warnings
  xil_axi_uint id = xil_axi_uint'(0);
  xil_axi_data_beat 	[255:0] 	wuser = xil_axi_data_beat'(0);
  xil_axi_len_t burst_length;
  xil_axi_size_t burst_size = xil_axi_size_t'(2); // 4 bytes
  xil_axi_size_t burst_size_mem; // 4 bytes
  xil_axi_burst_t burstType = xil_axi_burst_t'(2'b01);
  xil_axi_resp_t [255:0] resp;
  xil_axi_data_beat 	[255:0] 	ruser;
  xil_axi_resp_t resp_w;






  task S_AXI_READ_MEM(input xil_axi_ulong Addr, output bit [32768-1:0] RData, input int burst_length_, input int burst_size_);
  begin
    burst_length = xil_axi_len_t'(burst_length_);
    burst_size_mem = xil_axi_size_t'(burst_size_);
    mst_agent_0_mem.AXI4_READ_BURST (
            id,
            Addr,
            burst_length,
            burst_size_mem,
            burstType,
            xil_axi_lock_t'(0),
            xil_axi_cache_t'(0),
            xil_axi_prot_t'(0),
            xil_axi_region_t'(0),
            xil_axi_qos_t'(0),
            xil_axi_user_beat'(0),
            RData,
            resp,
            ruser
          );
  end
  endtask


  task S_AXI_WRITE_MEM(input xil_axi_ulong Addr, input bit [32768-1:0] WData, input int burst_length_, input int burst_size_);
    begin
    burst_length = xil_axi_len_t'(burst_length_);
    burst_size_mem = xil_axi_size_t'(burst_size_);
    mst_agent_0_mem.AXI4_WRITE_BURST(
            id,
            Addr,
            burst_length,
            burst_size_mem,
            burstType,
            xil_axi_lock_t'(0),
            xil_axi_cache_t'(0),
            xil_axi_prot_t'(0),
            xil_axi_region_t'(0),
            xil_axi_qos_t'(0),
            xil_axi_user_beat'(0),
            WData,
            wuser,  
            resp_w
          ); 
    end
  endtask

  task S_AXI_READ_REG(input xil_axi_ulong Addr, output bit [4096-1:0] RData, input int size_in_bytes);
  begin

    assert (size_in_bytes <= 4096);
    burst_length = xil_axi_size_t'(size_in_bytes/4 - 1);
    mst_agent_1_reg.AXI4_READ_BURST (
            id,
            Addr,
            burst_length,
            burst_size,
            burstType,
            xil_axi_lock_t'(0),
            xil_axi_cache_t'(0),
            xil_axi_prot_t'(0),
            xil_axi_region_t'(0),
            xil_axi_qos_t'(0),
            xil_axi_user_beat'(0),
            RData,
            resp,
            ruser
          );
  end
  endtask

  task S_AXI_WRITE_REG(input xil_axi_ulong Addr, input bit [4096-1:0] WData, input int size_in_bytes);
  begin
    assert (size_in_bytes <= 4096);
    burst_length = xil_axi_size_t'(size_in_bytes/4 - 1);
    mst_agent_1_reg.AXI4_WRITE_BURST(
            id,
            Addr,
            burst_length,
            burst_size,
            burstType,
            xil_axi_lock_t'(0),
            xil_axi_cache_t'(0),
            xil_axi_prot_t'(0),
            xil_axi_region_t'(0),
            xil_axi_qos_t'(0),
            xil_axi_user_beat'(0),
            WData,
            wuser,  
            resp_w
          ); 
    end
  endtask

endmodule
