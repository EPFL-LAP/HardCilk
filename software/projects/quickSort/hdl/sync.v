// Create a verilog module constitue a sync PE

/************************************************************************
 *  Module: sync
 *  Inputs: ap_clk, ap_rst_n, taskIn(AXI Stream 128 bits)
 *  Outputs: argOut(AXI Stream 64 bits)
 *  Function: In a pipeline reads taskIn and writes taskIn_TDATA upper half to argOut
 ************************************************************************/

module sync (
     ap_clk,
     ap_rst_n,
     taskIn_TDATA,
     taskIn_TVALID,
     taskIn_TREADY,
     argOut_TDATA,
     argOut_TVALID,
     argOut_TREADY);

input ap_clk;
input ap_rst_n;
input  [127:0] taskIn_TDATA;
input   taskIn_TVALID;
output   taskIn_TREADY;
output  [63:0] argOut_TDATA;
output   argOut_TVALID;
input   argOut_TREADY;

reg [127:0] taskIn_TDATA_reg;
reg taskIn_TVALID_reg;
reg taskIn_TREADY_reg;
reg [63:0] argOut_TDATA_reg;
reg argOut_TVALID_reg;

    always @(posedge ap_clk) begin
        if (!ap_rst_n) begin
            taskIn_TDATA_reg <= 128'b0;
            taskIn_TVALID_reg <= 1'b0;
            taskIn_TREADY_reg <= 1'b0;
            argOut_TDATA_reg <= 64'b0;
            argOut_TVALID_reg <= 1'b0;
        end else begin
            taskIn_TDATA_reg <= taskIn_TDATA;
            taskIn_TVALID_reg <= taskIn_TVALID;
            taskIn_TREADY_reg <= taskIn_TVALID && argOut_TREADY;

            argOut_TDATA_reg <= taskIn_TDATA_reg[127:64];
            argOut_TVALID_reg <= taskIn_TVALID_reg;
        end
    end

    assign taskIn_TREADY = taskIn_TREADY_reg;

    assign argOut_TDATA = argOut_TDATA_reg;
    assign argOut_TVALID = argOut_TVALID_reg;

endmodule



