// HBMEX (hbmex-fccm2025, Sonmez/Shahawy/Ienne, FCCM'25) core for the HardCilk V80 flow --
// GENERATED, do not edit: architecture-generator/hbmex_v80/build.sh (Chisel 6.0.0, chext 0.1.1,
// HBMEX rtl sources unmodified; modules prefixed, sv2v'd). Config: stripeLog2=14 pcLsb=30 pcBits=5
// wIdIn=12 wIdParallel=8 segmentOffset=30 log2Segments=5 log2lenIdQueue=6. Variant: hbmexS_syn
`timescale 1ns / 1ps
module hbmexS_Queue2_AddressChannel (
	clock,
	reset,
	io_deq_ready,
	io_deq_valid,
	io_deq_bits_addr,
	io_deq_bits_prot
);
	input clock;
	input reset;
	input io_deq_ready;
	output wire io_deq_valid;
	output wire [9:0] io_deq_bits_addr;
	output wire [2:0] io_deq_bits_prot;
	reg wrap_1;
	always @(posedge clock)
		if (reset)
			wrap_1 <= 1'h0;
		else if (io_deq_ready & wrap_1)
			wrap_1 <= wrap_1 - 1'h1;
	assign io_deq_valid = wrap_1;
	assign io_deq_bits_addr = 10'h000;
	assign io_deq_bits_prot = 3'h0;
endmodule
module hbmexS_Queue2_ReadDataChannel (
	clock,
	reset,
	io_enq_ready,
	io_enq_valid
);
	input clock;
	input reset;
	output wire io_enq_ready;
	input io_enq_valid;
	reg wrap;
	reg wrap_1;
	reg maybe_full;
	wire ptr_match = wrap == wrap_1;
	wire empty = ptr_match & ~maybe_full;
	wire full = ptr_match & maybe_full;
	always @(posedge clock)
		if (reset) begin
			wrap <= 1'h0;
			wrap_1 <= 1'h0;
			maybe_full <= 1'h0;
		end
		else begin : sv2v_autoblock_1
			reg do_enq;
			do_enq = ~full & io_enq_valid;
			if (do_enq)
				wrap <= wrap - 1'h1;
			if (~empty)
				wrap_1 <= wrap_1 - 1'h1;
			if (~(do_enq == ~empty))
				maybe_full <= do_enq;
		end
	assign io_enq_ready = ~full;
endmodule
module hbmexS_Queue2_WriteDataChannel (
	clock,
	reset,
	io_deq_ready,
	io_deq_valid,
	io_deq_bits_data,
	io_deq_bits_strb
);
	input clock;
	input reset;
	input io_deq_ready;
	output wire io_deq_valid;
	output wire [31:0] io_deq_bits_data;
	output wire [3:0] io_deq_bits_strb;
	reg wrap_1;
	always @(posedge clock)
		if (reset)
			wrap_1 <= 1'h0;
		else if (io_deq_ready & wrap_1)
			wrap_1 <= wrap_1 - 1'h1;
	assign io_deq_valid = wrap_1;
	assign io_deq_bits_data = 32'h00000000;
	assign io_deq_bits_strb = 4'h0;
endmodule
module hbmexS_Queue2_WriteResponseChannel (
	clock,
	reset,
	io_enq_ready,
	io_enq_valid
);
	input clock;
	input reset;
	output wire io_enq_ready;
	input io_enq_valid;
	reg wrap;
	reg wrap_1;
	reg maybe_full;
	wire ptr_match = wrap == wrap_1;
	wire empty = ptr_match & ~maybe_full;
	wire full = ptr_match & maybe_full;
	always @(posedge clock)
		if (reset) begin
			wrap <= 1'h0;
			wrap_1 <= 1'h0;
			maybe_full <= 1'h0;
		end
		else begin : sv2v_autoblock_1
			reg do_enq;
			do_enq = ~full & io_enq_valid;
			if (do_enq)
				wrap <= wrap - 1'h1;
			if (~empty)
				wrap_1 <= wrap_1 - 1'h1;
			if (~(do_enq == ~empty))
				maybe_full <= do_enq;
		end
	assign io_enq_ready = ~full;
endmodule
module hbmexS_Queue1_AddressChannel (
	clock,
	reset,
	io_enq_ready,
	io_enq_valid,
	io_enq_bits_addr,
	io_enq_bits_prot,
	io_deq_ready,
	io_deq_valid,
	io_deq_bits_addr
);
	input clock;
	input reset;
	output wire io_enq_ready;
	input io_enq_valid;
	input [9:0] io_enq_bits_addr;
	input [2:0] io_enq_bits_prot;
	input io_deq_ready;
	output wire io_deq_valid;
	output wire [9:0] io_deq_bits_addr;
	reg [12:0] ram;
	reg full;
	always @(posedge clock) begin : sv2v_autoblock_1
		reg do_enq;
		do_enq = ~full & io_enq_valid;
		if (do_enq)
			ram <= {io_enq_bits_prot, io_enq_bits_addr};
		if (reset)
			full <= 1'h0;
		else if (~(do_enq == (io_deq_ready & full)))
			full <= do_enq;
	end
	assign io_enq_ready = ~full;
	assign io_deq_valid = full;
	assign io_deq_bits_addr = ram[9:0];
endmodule
module hbmexS_Queue1_ReadDataChannel (
	clock,
	reset,
	io_enq_ready,
	io_enq_valid,
	io_deq_ready,
	io_deq_valid
);
	input clock;
	input reset;
	output wire io_enq_ready;
	input io_enq_valid;
	input io_deq_ready;
	output wire io_deq_valid;
	reg full;
	always @(posedge clock)
		if (reset)
			full <= 1'h0;
		else begin : sv2v_autoblock_1
			reg do_enq;
			do_enq = ~full & io_enq_valid;
			if (~(do_enq == (io_deq_ready & full)))
				full <= do_enq;
		end
	assign io_enq_ready = ~full;
	assign io_deq_valid = full;
endmodule
module hbmexS_Queue1_WriteDataChannel (
	clock,
	reset,
	io_enq_ready,
	io_enq_valid,
	io_enq_bits_data,
	io_enq_bits_strb,
	io_deq_ready,
	io_deq_valid,
	io_deq_bits_data,
	io_deq_bits_strb
);
	input clock;
	input reset;
	output wire io_enq_ready;
	input io_enq_valid;
	input [31:0] io_enq_bits_data;
	input [3:0] io_enq_bits_strb;
	input io_deq_ready;
	output wire io_deq_valid;
	output wire [31:0] io_deq_bits_data;
	output wire [3:0] io_deq_bits_strb;
	reg [35:0] ram;
	reg full;
	always @(posedge clock) begin : sv2v_autoblock_1
		reg do_enq;
		do_enq = ~full & io_enq_valid;
		if (do_enq)
			ram <= {io_enq_bits_strb, io_enq_bits_data};
		if (reset)
			full <= 1'h0;
		else if (~(do_enq == (io_deq_ready & full)))
			full <= do_enq;
	end
	assign io_enq_ready = ~full;
	assign io_deq_valid = full;
	assign io_deq_bits_data = ram[31:0];
	assign io_deq_bits_strb = ram[35:32];
endmodule
module hbmexS_Queue1_WriteResponseChannel (
	clock,
	reset,
	io_enq_ready,
	io_enq_valid,
	io_deq_ready,
	io_deq_valid
);
	input clock;
	input reset;
	output wire io_enq_ready;
	input io_enq_valid;
	input io_deq_ready;
	output wire io_deq_valid;
	reg full;
	always @(posedge clock)
		if (reset)
			full <= 1'h0;
		else begin : sv2v_autoblock_1
			reg do_enq;
			do_enq = ~full & io_enq_valid;
			if (~(do_enq == (io_deq_ready & full)))
				full <= do_enq;
		end
	assign io_enq_ready = ~full;
	assign io_deq_valid = full;
endmodule
module hbmexS_AddressTransform (
	select,
	in,
	out
);
	input select;
	input [63:0] in;
	output wire [63:0] out;
	assign out = {in[63:35], in[18:14], in[29:19], in[34:30], in[13:0]};
endmodule
module hbmexS_Stripe (
	clock,
	reset,
	S_AXI_0_ARREADY,
	S_AXI_0_ARVALID,
	S_AXI_0_ARID,
	S_AXI_0_ARADDR,
	S_AXI_0_ARLEN,
	S_AXI_0_ARSIZE,
	S_AXI_0_ARBURST,
	S_AXI_0_ARCACHE,
	S_AXI_0_ARPROT,
	S_AXI_0_RREADY,
	S_AXI_0_RVALID,
	S_AXI_0_RID,
	S_AXI_0_RDATA,
	S_AXI_0_RRESP,
	S_AXI_0_RLAST,
	S_AXI_0_AWREADY,
	S_AXI_0_AWVALID,
	S_AXI_0_AWID,
	S_AXI_0_AWADDR,
	S_AXI_0_AWLEN,
	S_AXI_0_AWSIZE,
	S_AXI_0_AWBURST,
	S_AXI_0_AWCACHE,
	S_AXI_0_AWPROT,
	S_AXI_0_WREADY,
	S_AXI_0_WVALID,
	S_AXI_0_WDATA,
	S_AXI_0_WSTRB,
	S_AXI_0_WLAST,
	S_AXI_0_BREADY,
	S_AXI_0_BVALID,
	S_AXI_0_BID,
	S_AXI_0_BRESP,
	M_AXI_0_ARREADY,
	M_AXI_0_ARVALID,
	M_AXI_0_ARID,
	M_AXI_0_ARADDR,
	M_AXI_0_ARLEN,
	M_AXI_0_ARSIZE,
	M_AXI_0_ARBURST,
	M_AXI_0_ARCACHE,
	M_AXI_0_ARPROT,
	M_AXI_0_RREADY,
	M_AXI_0_RVALID,
	M_AXI_0_RID,
	M_AXI_0_RDATA,
	M_AXI_0_RRESP,
	M_AXI_0_RLAST,
	M_AXI_0_AWREADY,
	M_AXI_0_AWVALID,
	M_AXI_0_AWID,
	M_AXI_0_AWADDR,
	M_AXI_0_AWLEN,
	M_AXI_0_AWSIZE,
	M_AXI_0_AWBURST,
	M_AXI_0_AWCACHE,
	M_AXI_0_AWPROT,
	M_AXI_0_WREADY,
	M_AXI_0_WVALID,
	M_AXI_0_WDATA,
	M_AXI_0_WSTRB,
	M_AXI_0_WLAST,
	M_AXI_0_BREADY,
	M_AXI_0_BVALID,
	M_AXI_0_BID,
	M_AXI_0_BRESP
);
	input clock;
	input reset;
	output wire S_AXI_0_ARREADY;
	input S_AXI_0_ARVALID;
	input [11:0] S_AXI_0_ARID;
	input [63:0] S_AXI_0_ARADDR;
	input [7:0] S_AXI_0_ARLEN;
	input [2:0] S_AXI_0_ARSIZE;
	input [1:0] S_AXI_0_ARBURST;
	input [3:0] S_AXI_0_ARCACHE;
	input [2:0] S_AXI_0_ARPROT;
	input S_AXI_0_RREADY;
	output wire S_AXI_0_RVALID;
	output wire [11:0] S_AXI_0_RID;
	output wire [255:0] S_AXI_0_RDATA;
	output wire [1:0] S_AXI_0_RRESP;
	output wire S_AXI_0_RLAST;
	output wire S_AXI_0_AWREADY;
	input S_AXI_0_AWVALID;
	input [11:0] S_AXI_0_AWID;
	input [63:0] S_AXI_0_AWADDR;
	input [7:0] S_AXI_0_AWLEN;
	input [2:0] S_AXI_0_AWSIZE;
	input [1:0] S_AXI_0_AWBURST;
	input [3:0] S_AXI_0_AWCACHE;
	input [2:0] S_AXI_0_AWPROT;
	output wire S_AXI_0_WREADY;
	input S_AXI_0_WVALID;
	input [255:0] S_AXI_0_WDATA;
	input [31:0] S_AXI_0_WSTRB;
	input S_AXI_0_WLAST;
	input S_AXI_0_BREADY;
	output wire S_AXI_0_BVALID;
	output wire [11:0] S_AXI_0_BID;
	output wire [1:0] S_AXI_0_BRESP;
	input M_AXI_0_ARREADY;
	output wire M_AXI_0_ARVALID;
	output wire [11:0] M_AXI_0_ARID;
	output wire [63:0] M_AXI_0_ARADDR;
	output wire [7:0] M_AXI_0_ARLEN;
	output wire [2:0] M_AXI_0_ARSIZE;
	output wire [1:0] M_AXI_0_ARBURST;
	output wire [3:0] M_AXI_0_ARCACHE;
	output wire [2:0] M_AXI_0_ARPROT;
	output wire M_AXI_0_RREADY;
	input M_AXI_0_RVALID;
	input [11:0] M_AXI_0_RID;
	input [255:0] M_AXI_0_RDATA;
	input [1:0] M_AXI_0_RRESP;
	input M_AXI_0_RLAST;
	input M_AXI_0_AWREADY;
	output wire M_AXI_0_AWVALID;
	output wire [11:0] M_AXI_0_AWID;
	output wire [63:0] M_AXI_0_AWADDR;
	output wire [7:0] M_AXI_0_AWLEN;
	output wire [2:0] M_AXI_0_AWSIZE;
	output wire [1:0] M_AXI_0_AWBURST;
	output wire [3:0] M_AXI_0_AWCACHE;
	output wire [2:0] M_AXI_0_AWPROT;
	input M_AXI_0_WREADY;
	output wire M_AXI_0_WVALID;
	output wire [255:0] M_AXI_0_WDATA;
	output wire [31:0] M_AXI_0_WSTRB;
	output wire M_AXI_0_WLAST;
	output wire M_AXI_0_BREADY;
	input M_AXI_0_BVALID;
	input [11:0] M_AXI_0_BID;
	input [1:0] M_AXI_0_BRESP;
	wire _wrRespQueue__io_enq_ready;
	wire _wrRespQueue__io_deq_valid;
	wire _wrReqData__deq_q_io_enq_ready;
	wire _wrReqData__deq_q_io_deq_valid;
	wire [31:0] _wrReqData__deq_q_io_deq_bits_data;
	wire [3:0] _wrReqData__deq_q_io_deq_bits_strb;
	wire _wrReq__deq_q_io_enq_ready;
	wire _wrReq__deq_q_io_deq_valid;
	wire [9:0] _wrReq__deq_q_io_deq_bits_addr;
	wire _rdRespQueue__io_enq_ready;
	wire _rdRespQueue__io_deq_valid;
	wire _rdReq__deq_q_io_enq_ready;
	wire _rdReq__deq_q_io_deq_valid;
	wire _s_axil__sinkBuffer_1_io_enq_ready;
	wire _s_axil__sourceBuffer_2_io_deq_valid;
	wire [31:0] _s_axil__sourceBuffer_2_io_deq_bits_data;
	wire [3:0] _s_axil__sourceBuffer_2_io_deq_bits_strb;
	wire _s_axil__sourceBuffer_1_io_deq_valid;
	wire [9:0] _s_axil__sourceBuffer_1_io_deq_bits_addr;
	wire [2:0] _s_axil__sourceBuffer_1_io_deq_bits_prot;
	wire _s_axil__sinkBuffer_io_enq_ready;
	wire _s_axil__sourceBuffer_io_deq_valid;
	wire [9:0] _s_axil__sourceBuffer_io_deq_bits_addr;
	wire [2:0] _s_axil__sourceBuffer_io_deq_bits_prot;
	reg [31:0] rSelect;
	wire rdReq = _rdReq__deq_q_io_deq_valid & _rdRespQueue__io_enq_ready;
	wire wrReq = (_wrReq__deq_q_io_deq_valid & _wrReqData__deq_q_io_deq_valid) & _wrRespQueue__io_enq_ready;
	always @(posedge clock)
		if (reset)
			rSelect <= 32'h00000000;
		else if (wrReq & (_wrReq__deq_q_io_deq_bits_addr[9:2] == 8'h00))
			rSelect <= {(_wrReqData__deq_q_io_deq_bits_strb[3] ? _wrReqData__deq_q_io_deq_bits_data[31:24] : rSelect[31:24]), (_wrReqData__deq_q_io_deq_bits_strb[2] ? _wrReqData__deq_q_io_deq_bits_data[23:16] : rSelect[23:16]), (_wrReqData__deq_q_io_deq_bits_strb[1] ? _wrReqData__deq_q_io_deq_bits_data[15:8] : rSelect[15:8]), (_wrReqData__deq_q_io_deq_bits_strb[0] ? _wrReqData__deq_q_io_deq_bits_data[7:0] : rSelect[7:0])};
	hbmexS_Queue2_AddressChannel s_axil__sourceBuffer(
		.clock(clock),
		.reset(reset),
		.io_deq_ready(_rdReq__deq_q_io_enq_ready),
		.io_deq_valid(_s_axil__sourceBuffer_io_deq_valid),
		.io_deq_bits_addr(_s_axil__sourceBuffer_io_deq_bits_addr),
		.io_deq_bits_prot(_s_axil__sourceBuffer_io_deq_bits_prot)
	);
	hbmexS_Queue2_ReadDataChannel s_axil__sinkBuffer(
		.clock(clock),
		.reset(reset),
		.io_enq_ready(_s_axil__sinkBuffer_io_enq_ready),
		.io_enq_valid(_rdRespQueue__io_deq_valid)
	);
	hbmexS_Queue2_AddressChannel s_axil__sourceBuffer_1(
		.clock(clock),
		.reset(reset),
		.io_deq_ready(_wrReq__deq_q_io_enq_ready),
		.io_deq_valid(_s_axil__sourceBuffer_1_io_deq_valid),
		.io_deq_bits_addr(_s_axil__sourceBuffer_1_io_deq_bits_addr),
		.io_deq_bits_prot(_s_axil__sourceBuffer_1_io_deq_bits_prot)
	);
	hbmexS_Queue2_WriteDataChannel s_axil__sourceBuffer_2(
		.clock(clock),
		.reset(reset),
		.io_deq_ready(_wrReqData__deq_q_io_enq_ready),
		.io_deq_valid(_s_axil__sourceBuffer_2_io_deq_valid),
		.io_deq_bits_data(_s_axil__sourceBuffer_2_io_deq_bits_data),
		.io_deq_bits_strb(_s_axil__sourceBuffer_2_io_deq_bits_strb)
	);
	hbmexS_Queue2_WriteResponseChannel s_axil__sinkBuffer_1(
		.clock(clock),
		.reset(reset),
		.io_enq_ready(_s_axil__sinkBuffer_1_io_enq_ready),
		.io_enq_valid(_wrRespQueue__io_deq_valid)
	);
	hbmexS_Queue1_AddressChannel rdReq__deq_q(
		.clock(clock),
		.reset(reset),
		.io_enq_ready(_rdReq__deq_q_io_enq_ready),
		.io_enq_valid(_s_axil__sourceBuffer_io_deq_valid),
		.io_enq_bits_addr(_s_axil__sourceBuffer_io_deq_bits_addr),
		.io_enq_bits_prot(_s_axil__sourceBuffer_io_deq_bits_prot),
		.io_deq_ready(rdReq),
		.io_deq_valid(_rdReq__deq_q_io_deq_valid),
		.io_deq_bits_addr()
	);
	hbmexS_Queue1_ReadDataChannel rdRespQueue_(
		.clock(clock),
		.reset(reset),
		.io_enq_ready(_rdRespQueue__io_enq_ready),
		.io_enq_valid(rdReq),
		.io_deq_ready(_s_axil__sinkBuffer_io_enq_ready),
		.io_deq_valid(_rdRespQueue__io_deq_valid)
	);
	hbmexS_Queue1_AddressChannel wrReq__deq_q(
		.clock(clock),
		.reset(reset),
		.io_enq_ready(_wrReq__deq_q_io_enq_ready),
		.io_enq_valid(_s_axil__sourceBuffer_1_io_deq_valid),
		.io_enq_bits_addr(_s_axil__sourceBuffer_1_io_deq_bits_addr),
		.io_enq_bits_prot(_s_axil__sourceBuffer_1_io_deq_bits_prot),
		.io_deq_ready(wrReq),
		.io_deq_valid(_wrReq__deq_q_io_deq_valid),
		.io_deq_bits_addr(_wrReq__deq_q_io_deq_bits_addr)
	);
	hbmexS_Queue1_WriteDataChannel wrReqData__deq_q(
		.clock(clock),
		.reset(reset),
		.io_enq_ready(_wrReqData__deq_q_io_enq_ready),
		.io_enq_valid(_s_axil__sourceBuffer_2_io_deq_valid),
		.io_enq_bits_data(_s_axil__sourceBuffer_2_io_deq_bits_data),
		.io_enq_bits_strb(_s_axil__sourceBuffer_2_io_deq_bits_strb),
		.io_deq_ready(wrReq),
		.io_deq_valid(_wrReqData__deq_q_io_deq_valid),
		.io_deq_bits_data(_wrReqData__deq_q_io_deq_bits_data),
		.io_deq_bits_strb(_wrReqData__deq_q_io_deq_bits_strb)
	);
	hbmexS_Queue1_WriteResponseChannel wrRespQueue_(
		.clock(clock),
		.reset(reset),
		.io_enq_ready(_wrRespQueue__io_enq_ready),
		.io_enq_valid(wrReq),
		.io_deq_ready(_s_axil__sinkBuffer_1_io_enq_ready),
		.io_deq_valid(_wrRespQueue__io_deq_valid)
	);
	hbmexS_AddressTransform addressTransform(
		.select(rSelect[0]),
		.in(S_AXI_0_ARADDR),
		.out(M_AXI_0_ARADDR)
	);
	hbmexS_AddressTransform addressTransform_1(
		.select(rSelect[0]),
		.in(S_AXI_0_AWADDR),
		.out(M_AXI_0_AWADDR)
	);
	assign S_AXI_0_ARREADY = M_AXI_0_ARREADY;
	assign S_AXI_0_RVALID = M_AXI_0_RVALID;
	assign S_AXI_0_RID = M_AXI_0_RID;
	assign S_AXI_0_RDATA = M_AXI_0_RDATA;
	assign S_AXI_0_RRESP = M_AXI_0_RRESP;
	assign S_AXI_0_RLAST = M_AXI_0_RLAST;
	assign S_AXI_0_AWREADY = M_AXI_0_AWREADY;
	assign S_AXI_0_WREADY = M_AXI_0_WREADY;
	assign S_AXI_0_BVALID = M_AXI_0_BVALID;
	assign S_AXI_0_BID = M_AXI_0_BID;
	assign S_AXI_0_BRESP = M_AXI_0_BRESP;
	assign M_AXI_0_ARVALID = S_AXI_0_ARVALID;
	assign M_AXI_0_ARID = S_AXI_0_ARID;
	assign M_AXI_0_ARLEN = S_AXI_0_ARLEN;
	assign M_AXI_0_ARSIZE = S_AXI_0_ARSIZE;
	assign M_AXI_0_ARBURST = S_AXI_0_ARBURST;
	assign M_AXI_0_ARCACHE = S_AXI_0_ARCACHE;
	assign M_AXI_0_ARPROT = S_AXI_0_ARPROT;
	assign M_AXI_0_RREADY = S_AXI_0_RREADY;
	assign M_AXI_0_AWVALID = S_AXI_0_AWVALID;
	assign M_AXI_0_AWID = S_AXI_0_AWID;
	assign M_AXI_0_AWLEN = S_AXI_0_AWLEN;
	assign M_AXI_0_AWSIZE = S_AXI_0_AWSIZE;
	assign M_AXI_0_AWBURST = S_AXI_0_AWBURST;
	assign M_AXI_0_AWCACHE = S_AXI_0_AWCACHE;
	assign M_AXI_0_AWPROT = S_AXI_0_AWPROT;
	assign M_AXI_0_WVALID = S_AXI_0_WVALID;
	assign M_AXI_0_WDATA = S_AXI_0_WDATA;
	assign M_AXI_0_WSTRB = S_AXI_0_WSTRB;
	assign M_AXI_0_WLAST = S_AXI_0_WLAST;
	assign M_AXI_0_BREADY = S_AXI_0_BREADY;
endmodule
module hbmexS_ram_2x96 (
	R0_addr,
	R0_en,
	R0_clk,
	R0_data,
	W0_addr,
	W0_en,
	W0_clk,
	W0_data
);
	input R0_addr;
	input R0_en;
	input R0_clk;
	output wire [95:0] R0_data;
	input W0_addr;
	input W0_en;
	input W0_clk;
	input [95:0] W0_data;
	reg [95:0] Memory [0:1];
	always @(posedge W0_clk)
		if (W0_en & 1'h1)
			Memory[W0_addr] <= W0_data;
	assign R0_data = (R0_en ? Memory[R0_addr] : 96'bxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx);
endmodule
module hbmexS_Queue2_ReadAddressChannel (
	clock,
	reset,
	io_enq_ready,
	io_enq_valid,
	io_enq_bits_id,
	io_enq_bits_addr,
	io_enq_bits_len,
	io_enq_bits_size,
	io_enq_bits_burst,
	io_enq_bits_cache,
	io_enq_bits_prot,
	io_deq_ready,
	io_deq_valid,
	io_deq_bits_id,
	io_deq_bits_addr,
	io_deq_bits_len,
	io_deq_bits_size,
	io_deq_bits_burst,
	io_deq_bits_cache,
	io_deq_bits_prot
);
	input clock;
	input reset;
	output wire io_enq_ready;
	input io_enq_valid;
	input [11:0] io_enq_bits_id;
	input [63:0] io_enq_bits_addr;
	input [7:0] io_enq_bits_len;
	input [2:0] io_enq_bits_size;
	input [1:0] io_enq_bits_burst;
	input [3:0] io_enq_bits_cache;
	input [2:0] io_enq_bits_prot;
	input io_deq_ready;
	output wire io_deq_valid;
	output wire [11:0] io_deq_bits_id;
	output wire [63:0] io_deq_bits_addr;
	output wire [7:0] io_deq_bits_len;
	output wire [2:0] io_deq_bits_size;
	output wire [1:0] io_deq_bits_burst;
	output wire [3:0] io_deq_bits_cache;
	output wire [2:0] io_deq_bits_prot;
	wire [95:0] _ram_ext_R0_data;
	reg wrap;
	reg wrap_1;
	reg maybe_full;
	wire ptr_match = wrap == wrap_1;
	wire empty = ptr_match & ~maybe_full;
	wire full = ptr_match & maybe_full;
	wire do_enq = ~full & io_enq_valid;
	always @(posedge clock)
		if (reset) begin
			wrap <= 1'h0;
			wrap_1 <= 1'h0;
			maybe_full <= 1'h0;
		end
		else begin : sv2v_autoblock_1
			reg do_deq;
			do_deq = io_deq_ready & ~empty;
			if (do_enq)
				wrap <= wrap - 1'h1;
			if (do_deq)
				wrap_1 <= wrap_1 - 1'h1;
			if (~(do_enq == do_deq))
				maybe_full <= do_enq;
		end
	hbmexS_ram_2x96 ram_ext(
		.R0_addr(wrap_1),
		.R0_en(1'h1),
		.R0_clk(clock),
		.R0_data(_ram_ext_R0_data),
		.W0_addr(wrap),
		.W0_en(do_enq),
		.W0_clk(clock),
		.W0_data({io_enq_bits_prot, io_enq_bits_cache, io_enq_bits_burst, io_enq_bits_size, io_enq_bits_len, io_enq_bits_addr, io_enq_bits_id})
	);
	assign io_enq_ready = ~full;
	assign io_deq_valid = ~empty;
	assign io_deq_bits_id = _ram_ext_R0_data[11:0];
	assign io_deq_bits_addr = _ram_ext_R0_data[75:12];
	assign io_deq_bits_len = _ram_ext_R0_data[83:76];
	assign io_deq_bits_size = _ram_ext_R0_data[86:84];
	assign io_deq_bits_burst = _ram_ext_R0_data[88:87];
	assign io_deq_bits_cache = _ram_ext_R0_data[92:89];
	assign io_deq_bits_prot = _ram_ext_R0_data[95:93];
endmodule
module hbmexS_ram_2x271 (
	R0_addr,
	R0_en,
	R0_clk,
	R0_data,
	W0_addr,
	W0_en,
	W0_clk,
	W0_data
);
	input R0_addr;
	input R0_en;
	input R0_clk;
	output wire [270:0] R0_data;
	input W0_addr;
	input W0_en;
	input W0_clk;
	input [270:0] W0_data;
	reg [270:0] Memory [0:1];
	always @(posedge W0_clk)
		if (W0_en & 1'h1)
			Memory[W0_addr] <= W0_data;
	assign R0_data = (R0_en ? Memory[R0_addr] : 271'bxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx);
endmodule
module hbmexS_Queue2_ReadDataChannel_1 (
	clock,
	reset,
	io_enq_ready,
	io_enq_valid,
	io_enq_bits_id,
	io_enq_bits_data,
	io_enq_bits_resp,
	io_enq_bits_last,
	io_deq_ready,
	io_deq_valid,
	io_deq_bits_id,
	io_deq_bits_data,
	io_deq_bits_resp,
	io_deq_bits_last
);
	input clock;
	input reset;
	output wire io_enq_ready;
	input io_enq_valid;
	input [11:0] io_enq_bits_id;
	input [255:0] io_enq_bits_data;
	input [1:0] io_enq_bits_resp;
	input io_enq_bits_last;
	input io_deq_ready;
	output wire io_deq_valid;
	output wire [11:0] io_deq_bits_id;
	output wire [255:0] io_deq_bits_data;
	output wire [1:0] io_deq_bits_resp;
	output wire io_deq_bits_last;
	wire [270:0] _ram_ext_R0_data;
	reg wrap;
	reg wrap_1;
	reg maybe_full;
	wire ptr_match = wrap == wrap_1;
	wire empty = ptr_match & ~maybe_full;
	wire full = ptr_match & maybe_full;
	wire do_enq = ~full & io_enq_valid;
	always @(posedge clock)
		if (reset) begin
			wrap <= 1'h0;
			wrap_1 <= 1'h0;
			maybe_full <= 1'h0;
		end
		else begin : sv2v_autoblock_1
			reg do_deq;
			do_deq = io_deq_ready & ~empty;
			if (do_enq)
				wrap <= wrap - 1'h1;
			if (do_deq)
				wrap_1 <= wrap_1 - 1'h1;
			if (~(do_enq == do_deq))
				maybe_full <= do_enq;
		end
	hbmexS_ram_2x271 ram_ext(
		.R0_addr(wrap_1),
		.R0_en(1'h1),
		.R0_clk(clock),
		.R0_data(_ram_ext_R0_data),
		.W0_addr(wrap),
		.W0_en(do_enq),
		.W0_clk(clock),
		.W0_data({io_enq_bits_last, io_enq_bits_resp, io_enq_bits_data, io_enq_bits_id})
	);
	assign io_enq_ready = ~full;
	assign io_deq_valid = ~empty;
	assign io_deq_bits_id = _ram_ext_R0_data[11:0];
	assign io_deq_bits_data = _ram_ext_R0_data[267:12];
	assign io_deq_bits_resp = _ram_ext_R0_data[269:268];
	assign io_deq_bits_last = _ram_ext_R0_data[270];
endmodule
module hbmexS_Queue2_WriteAddressChannel (
	clock,
	reset,
	io_enq_ready,
	io_enq_valid,
	io_enq_bits_id,
	io_enq_bits_addr,
	io_enq_bits_len,
	io_enq_bits_size,
	io_enq_bits_burst,
	io_enq_bits_cache,
	io_enq_bits_prot,
	io_deq_ready,
	io_deq_valid,
	io_deq_bits_id,
	io_deq_bits_addr,
	io_deq_bits_len,
	io_deq_bits_size,
	io_deq_bits_burst,
	io_deq_bits_cache,
	io_deq_bits_prot
);
	input clock;
	input reset;
	output wire io_enq_ready;
	input io_enq_valid;
	input [11:0] io_enq_bits_id;
	input [63:0] io_enq_bits_addr;
	input [7:0] io_enq_bits_len;
	input [2:0] io_enq_bits_size;
	input [1:0] io_enq_bits_burst;
	input [3:0] io_enq_bits_cache;
	input [2:0] io_enq_bits_prot;
	input io_deq_ready;
	output wire io_deq_valid;
	output wire [11:0] io_deq_bits_id;
	output wire [63:0] io_deq_bits_addr;
	output wire [7:0] io_deq_bits_len;
	output wire [2:0] io_deq_bits_size;
	output wire [1:0] io_deq_bits_burst;
	output wire [3:0] io_deq_bits_cache;
	output wire [2:0] io_deq_bits_prot;
	wire [95:0] _ram_ext_R0_data;
	reg wrap;
	reg wrap_1;
	reg maybe_full;
	wire ptr_match = wrap == wrap_1;
	wire empty = ptr_match & ~maybe_full;
	wire full = ptr_match & maybe_full;
	wire do_enq = ~full & io_enq_valid;
	always @(posedge clock)
		if (reset) begin
			wrap <= 1'h0;
			wrap_1 <= 1'h0;
			maybe_full <= 1'h0;
		end
		else begin : sv2v_autoblock_1
			reg do_deq;
			do_deq = io_deq_ready & ~empty;
			if (do_enq)
				wrap <= wrap - 1'h1;
			if (do_deq)
				wrap_1 <= wrap_1 - 1'h1;
			if (~(do_enq == do_deq))
				maybe_full <= do_enq;
		end
	hbmexS_ram_2x96 ram_ext(
		.R0_addr(wrap_1),
		.R0_en(1'h1),
		.R0_clk(clock),
		.R0_data(_ram_ext_R0_data),
		.W0_addr(wrap),
		.W0_en(do_enq),
		.W0_clk(clock),
		.W0_data({io_enq_bits_prot, io_enq_bits_cache, io_enq_bits_burst, io_enq_bits_size, io_enq_bits_len, io_enq_bits_addr, io_enq_bits_id})
	);
	assign io_enq_ready = ~full;
	assign io_deq_valid = ~empty;
	assign io_deq_bits_id = _ram_ext_R0_data[11:0];
	assign io_deq_bits_addr = _ram_ext_R0_data[75:12];
	assign io_deq_bits_len = _ram_ext_R0_data[83:76];
	assign io_deq_bits_size = _ram_ext_R0_data[86:84];
	assign io_deq_bits_burst = _ram_ext_R0_data[88:87];
	assign io_deq_bits_cache = _ram_ext_R0_data[92:89];
	assign io_deq_bits_prot = _ram_ext_R0_data[95:93];
endmodule
module hbmexS_ram_2x289 (
	R0_addr,
	R0_en,
	R0_clk,
	R0_data,
	W0_addr,
	W0_en,
	W0_clk,
	W0_data
);
	input R0_addr;
	input R0_en;
	input R0_clk;
	output wire [288:0] R0_data;
	input W0_addr;
	input W0_en;
	input W0_clk;
	input [288:0] W0_data;
	reg [288:0] Memory [0:1];
	always @(posedge W0_clk)
		if (W0_en & 1'h1)
			Memory[W0_addr] <= W0_data;
	assign R0_data = (R0_en ? Memory[R0_addr] : 289'bxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx);
endmodule
module hbmexS_Queue2_WriteDataChannel_1 (
	clock,
	reset,
	io_enq_ready,
	io_enq_valid,
	io_enq_bits_data,
	io_enq_bits_strb,
	io_enq_bits_last,
	io_deq_ready,
	io_deq_valid,
	io_deq_bits_data,
	io_deq_bits_strb,
	io_deq_bits_last
);
	input clock;
	input reset;
	output wire io_enq_ready;
	input io_enq_valid;
	input [255:0] io_enq_bits_data;
	input [31:0] io_enq_bits_strb;
	input io_enq_bits_last;
	input io_deq_ready;
	output wire io_deq_valid;
	output wire [255:0] io_deq_bits_data;
	output wire [31:0] io_deq_bits_strb;
	output wire io_deq_bits_last;
	wire [288:0] _ram_ext_R0_data;
	reg wrap;
	reg wrap_1;
	reg maybe_full;
	wire ptr_match = wrap == wrap_1;
	wire empty = ptr_match & ~maybe_full;
	wire full = ptr_match & maybe_full;
	wire do_enq = ~full & io_enq_valid;
	always @(posedge clock)
		if (reset) begin
			wrap <= 1'h0;
			wrap_1 <= 1'h0;
			maybe_full <= 1'h0;
		end
		else begin : sv2v_autoblock_1
			reg do_deq;
			do_deq = io_deq_ready & ~empty;
			if (do_enq)
				wrap <= wrap - 1'h1;
			if (do_deq)
				wrap_1 <= wrap_1 - 1'h1;
			if (~(do_enq == do_deq))
				maybe_full <= do_enq;
		end
	hbmexS_ram_2x289 ram_ext(
		.R0_addr(wrap_1),
		.R0_en(1'h1),
		.R0_clk(clock),
		.R0_data(_ram_ext_R0_data),
		.W0_addr(wrap),
		.W0_en(do_enq),
		.W0_clk(clock),
		.W0_data({io_enq_bits_last, io_enq_bits_strb, io_enq_bits_data})
	);
	assign io_enq_ready = ~full;
	assign io_deq_valid = ~empty;
	assign io_deq_bits_data = _ram_ext_R0_data[255:0];
	assign io_deq_bits_strb = _ram_ext_R0_data[287:256];
	assign io_deq_bits_last = _ram_ext_R0_data[288];
endmodule
module hbmexS_ram_2x14 (
	R0_addr,
	R0_en,
	R0_clk,
	R0_data,
	W0_addr,
	W0_en,
	W0_clk,
	W0_data
);
	input R0_addr;
	input R0_en;
	input R0_clk;
	output wire [13:0] R0_data;
	input W0_addr;
	input W0_en;
	input W0_clk;
	input [13:0] W0_data;
	reg [13:0] Memory [0:1];
	always @(posedge W0_clk)
		if (W0_en & 1'h1)
			Memory[W0_addr] <= W0_data;
	assign R0_data = (R0_en ? Memory[R0_addr] : 14'bxxxxxxxxxxxxxx);
endmodule
module hbmexS_Queue2_WriteResponseChannel_1 (
	clock,
	reset,
	io_enq_ready,
	io_enq_valid,
	io_enq_bits_id,
	io_enq_bits_resp,
	io_deq_ready,
	io_deq_valid,
	io_deq_bits_id,
	io_deq_bits_resp
);
	input clock;
	input reset;
	output wire io_enq_ready;
	input io_enq_valid;
	input [11:0] io_enq_bits_id;
	input [1:0] io_enq_bits_resp;
	input io_deq_ready;
	output wire io_deq_valid;
	output wire [11:0] io_deq_bits_id;
	output wire [1:0] io_deq_bits_resp;
	wire [13:0] _ram_ext_R0_data;
	reg wrap;
	reg wrap_1;
	reg maybe_full;
	wire ptr_match = wrap == wrap_1;
	wire empty = ptr_match & ~maybe_full;
	wire full = ptr_match & maybe_full;
	wire do_enq = ~full & io_enq_valid;
	always @(posedge clock)
		if (reset) begin
			wrap <= 1'h0;
			wrap_1 <= 1'h0;
			maybe_full <= 1'h0;
		end
		else begin : sv2v_autoblock_1
			reg do_deq;
			do_deq = io_deq_ready & ~empty;
			if (do_enq)
				wrap <= wrap - 1'h1;
			if (do_deq)
				wrap_1 <= wrap_1 - 1'h1;
			if (~(do_enq == do_deq))
				maybe_full <= do_enq;
		end
	hbmexS_ram_2x14 ram_ext(
		.R0_addr(wrap_1),
		.R0_en(1'h1),
		.R0_clk(clock),
		.R0_data(_ram_ext_R0_data),
		.W0_addr(wrap),
		.W0_en(do_enq),
		.W0_clk(clock),
		.W0_data({io_enq_bits_resp, io_enq_bits_id})
	);
	assign io_enq_ready = ~full;
	assign io_deq_valid = ~empty;
	assign io_deq_bits_id = _ram_ext_R0_data[11:0];
	assign io_deq_bits_resp = _ram_ext_R0_data[13:12];
endmodule
module hbmexS_HbmexV80Stripe (
	clock,
	reset,
	S_AXI_ARREADY,
	S_AXI_ARVALID,
	S_AXI_ARID,
	S_AXI_ARADDR,
	S_AXI_ARLEN,
	S_AXI_ARSIZE,
	S_AXI_ARBURST,
	S_AXI_ARCACHE,
	S_AXI_ARPROT,
	S_AXI_RREADY,
	S_AXI_RVALID,
	S_AXI_RID,
	S_AXI_RDATA,
	S_AXI_RRESP,
	S_AXI_RLAST,
	S_AXI_AWREADY,
	S_AXI_AWVALID,
	S_AXI_AWID,
	S_AXI_AWADDR,
	S_AXI_AWLEN,
	S_AXI_AWSIZE,
	S_AXI_AWBURST,
	S_AXI_AWCACHE,
	S_AXI_AWPROT,
	S_AXI_WREADY,
	S_AXI_WVALID,
	S_AXI_WDATA,
	S_AXI_WSTRB,
	S_AXI_WLAST,
	S_AXI_BREADY,
	S_AXI_BVALID,
	S_AXI_BID,
	S_AXI_BRESP,
	M_AXI_ARREADY,
	M_AXI_ARVALID,
	M_AXI_ARID,
	M_AXI_ARADDR,
	M_AXI_ARLEN,
	M_AXI_ARSIZE,
	M_AXI_ARBURST,
	M_AXI_ARCACHE,
	M_AXI_ARPROT,
	M_AXI_RREADY,
	M_AXI_RVALID,
	M_AXI_RID,
	M_AXI_RDATA,
	M_AXI_RRESP,
	M_AXI_RLAST,
	M_AXI_AWREADY,
	M_AXI_AWVALID,
	M_AXI_AWID,
	M_AXI_AWADDR,
	M_AXI_AWLEN,
	M_AXI_AWSIZE,
	M_AXI_AWBURST,
	M_AXI_AWCACHE,
	M_AXI_AWPROT,
	M_AXI_WREADY,
	M_AXI_WVALID,
	M_AXI_WDATA,
	M_AXI_WSTRB,
	M_AXI_WLAST,
	M_AXI_BREADY,
	M_AXI_BVALID,
	M_AXI_BID,
	M_AXI_BRESP
);
	input clock;
	input reset;
	output wire S_AXI_ARREADY;
	input S_AXI_ARVALID;
	input [11:0] S_AXI_ARID;
	input [63:0] S_AXI_ARADDR;
	input [7:0] S_AXI_ARLEN;
	input [2:0] S_AXI_ARSIZE;
	input [1:0] S_AXI_ARBURST;
	input [3:0] S_AXI_ARCACHE;
	input [2:0] S_AXI_ARPROT;
	input S_AXI_RREADY;
	output wire S_AXI_RVALID;
	output wire [11:0] S_AXI_RID;
	output wire [255:0] S_AXI_RDATA;
	output wire [1:0] S_AXI_RRESP;
	output wire S_AXI_RLAST;
	output wire S_AXI_AWREADY;
	input S_AXI_AWVALID;
	input [11:0] S_AXI_AWID;
	input [63:0] S_AXI_AWADDR;
	input [7:0] S_AXI_AWLEN;
	input [2:0] S_AXI_AWSIZE;
	input [1:0] S_AXI_AWBURST;
	input [3:0] S_AXI_AWCACHE;
	input [2:0] S_AXI_AWPROT;
	output wire S_AXI_WREADY;
	input S_AXI_WVALID;
	input [255:0] S_AXI_WDATA;
	input [31:0] S_AXI_WSTRB;
	input S_AXI_WLAST;
	input S_AXI_BREADY;
	output wire S_AXI_BVALID;
	output wire [11:0] S_AXI_BID;
	output wire [1:0] S_AXI_BRESP;
	input M_AXI_ARREADY;
	output wire M_AXI_ARVALID;
	output wire [11:0] M_AXI_ARID;
	output wire [63:0] M_AXI_ARADDR;
	output wire [7:0] M_AXI_ARLEN;
	output wire [2:0] M_AXI_ARSIZE;
	output wire [1:0] M_AXI_ARBURST;
	output wire [3:0] M_AXI_ARCACHE;
	output wire [2:0] M_AXI_ARPROT;
	output wire M_AXI_RREADY;
	input M_AXI_RVALID;
	input [11:0] M_AXI_RID;
	input [255:0] M_AXI_RDATA;
	input [1:0] M_AXI_RRESP;
	input M_AXI_RLAST;
	input M_AXI_AWREADY;
	output wire M_AXI_AWVALID;
	output wire [11:0] M_AXI_AWID;
	output wire [63:0] M_AXI_AWADDR;
	output wire [7:0] M_AXI_AWLEN;
	output wire [2:0] M_AXI_AWSIZE;
	output wire [1:0] M_AXI_AWBURST;
	output wire [3:0] M_AXI_AWCACHE;
	output wire [2:0] M_AXI_AWPROT;
	input M_AXI_WREADY;
	output wire M_AXI_WVALID;
	output wire [255:0] M_AXI_WDATA;
	output wire [31:0] M_AXI_WSTRB;
	output wire M_AXI_WLAST;
	output wire M_AXI_BREADY;
	input M_AXI_BVALID;
	input [11:0] M_AXI_BID;
	input [1:0] M_AXI_BRESP;
	wire _sinkBuffer_1_io_enq_ready;
	wire _sourceBuffer_2_io_deq_valid;
	wire [255:0] _sourceBuffer_2_io_deq_bits_data;
	wire [31:0] _sourceBuffer_2_io_deq_bits_strb;
	wire _sourceBuffer_2_io_deq_bits_last;
	wire _sourceBuffer_1_io_deq_valid;
	wire [11:0] _sourceBuffer_1_io_deq_bits_id;
	wire [63:0] _sourceBuffer_1_io_deq_bits_addr;
	wire [7:0] _sourceBuffer_1_io_deq_bits_len;
	wire [2:0] _sourceBuffer_1_io_deq_bits_size;
	wire [1:0] _sourceBuffer_1_io_deq_bits_burst;
	wire [3:0] _sourceBuffer_1_io_deq_bits_cache;
	wire [2:0] _sourceBuffer_1_io_deq_bits_prot;
	wire _sinkBuffer_io_enq_ready;
	wire _sourceBuffer_io_deq_valid;
	wire [11:0] _sourceBuffer_io_deq_bits_id;
	wire [63:0] _sourceBuffer_io_deq_bits_addr;
	wire [7:0] _sourceBuffer_io_deq_bits_len;
	wire [2:0] _sourceBuffer_io_deq_bits_size;
	wire [1:0] _sourceBuffer_io_deq_bits_burst;
	wire [3:0] _sourceBuffer_io_deq_bits_cache;
	wire [2:0] _sourceBuffer_io_deq_bits_prot;
	wire _stripe0_S_AXI_0_ARREADY;
	wire _stripe0_S_AXI_0_RVALID;
	wire [11:0] _stripe0_S_AXI_0_RID;
	wire [255:0] _stripe0_S_AXI_0_RDATA;
	wire [1:0] _stripe0_S_AXI_0_RRESP;
	wire _stripe0_S_AXI_0_RLAST;
	wire _stripe0_S_AXI_0_AWREADY;
	wire _stripe0_S_AXI_0_WREADY;
	wire _stripe0_S_AXI_0_BVALID;
	wire [11:0] _stripe0_S_AXI_0_BID;
	wire [1:0] _stripe0_S_AXI_0_BRESP;
	hbmexS_Stripe stripe0(
		.clock(clock),
		.reset(reset),
		.S_AXI_0_ARREADY(_stripe0_S_AXI_0_ARREADY),
		.S_AXI_0_ARVALID(_sourceBuffer_io_deq_valid),
		.S_AXI_0_ARID(_sourceBuffer_io_deq_bits_id),
		.S_AXI_0_ARADDR(_sourceBuffer_io_deq_bits_addr),
		.S_AXI_0_ARLEN(_sourceBuffer_io_deq_bits_len),
		.S_AXI_0_ARSIZE(_sourceBuffer_io_deq_bits_size),
		.S_AXI_0_ARBURST(_sourceBuffer_io_deq_bits_burst),
		.S_AXI_0_ARCACHE(_sourceBuffer_io_deq_bits_cache),
		.S_AXI_0_ARPROT(_sourceBuffer_io_deq_bits_prot),
		.S_AXI_0_RREADY(_sinkBuffer_io_enq_ready),
		.S_AXI_0_RVALID(_stripe0_S_AXI_0_RVALID),
		.S_AXI_0_RID(_stripe0_S_AXI_0_RID),
		.S_AXI_0_RDATA(_stripe0_S_AXI_0_RDATA),
		.S_AXI_0_RRESP(_stripe0_S_AXI_0_RRESP),
		.S_AXI_0_RLAST(_stripe0_S_AXI_0_RLAST),
		.S_AXI_0_AWREADY(_stripe0_S_AXI_0_AWREADY),
		.S_AXI_0_AWVALID(_sourceBuffer_1_io_deq_valid),
		.S_AXI_0_AWID(_sourceBuffer_1_io_deq_bits_id),
		.S_AXI_0_AWADDR(_sourceBuffer_1_io_deq_bits_addr),
		.S_AXI_0_AWLEN(_sourceBuffer_1_io_deq_bits_len),
		.S_AXI_0_AWSIZE(_sourceBuffer_1_io_deq_bits_size),
		.S_AXI_0_AWBURST(_sourceBuffer_1_io_deq_bits_burst),
		.S_AXI_0_AWCACHE(_sourceBuffer_1_io_deq_bits_cache),
		.S_AXI_0_AWPROT(_sourceBuffer_1_io_deq_bits_prot),
		.S_AXI_0_WREADY(_stripe0_S_AXI_0_WREADY),
		.S_AXI_0_WVALID(_sourceBuffer_2_io_deq_valid),
		.S_AXI_0_WDATA(_sourceBuffer_2_io_deq_bits_data),
		.S_AXI_0_WSTRB(_sourceBuffer_2_io_deq_bits_strb),
		.S_AXI_0_WLAST(_sourceBuffer_2_io_deq_bits_last),
		.S_AXI_0_BREADY(_sinkBuffer_1_io_enq_ready),
		.S_AXI_0_BVALID(_stripe0_S_AXI_0_BVALID),
		.S_AXI_0_BID(_stripe0_S_AXI_0_BID),
		.S_AXI_0_BRESP(_stripe0_S_AXI_0_BRESP),
		.M_AXI_0_ARREADY(M_AXI_ARREADY),
		.M_AXI_0_ARVALID(M_AXI_ARVALID),
		.M_AXI_0_ARID(M_AXI_ARID),
		.M_AXI_0_ARADDR(M_AXI_ARADDR),
		.M_AXI_0_ARLEN(M_AXI_ARLEN),
		.M_AXI_0_ARSIZE(M_AXI_ARSIZE),
		.M_AXI_0_ARBURST(M_AXI_ARBURST),
		.M_AXI_0_ARCACHE(M_AXI_ARCACHE),
		.M_AXI_0_ARPROT(M_AXI_ARPROT),
		.M_AXI_0_RREADY(M_AXI_RREADY),
		.M_AXI_0_RVALID(M_AXI_RVALID),
		.M_AXI_0_RID(M_AXI_RID),
		.M_AXI_0_RDATA(M_AXI_RDATA),
		.M_AXI_0_RRESP(M_AXI_RRESP),
		.M_AXI_0_RLAST(M_AXI_RLAST),
		.M_AXI_0_AWREADY(M_AXI_AWREADY),
		.M_AXI_0_AWVALID(M_AXI_AWVALID),
		.M_AXI_0_AWID(M_AXI_AWID),
		.M_AXI_0_AWADDR(M_AXI_AWADDR),
		.M_AXI_0_AWLEN(M_AXI_AWLEN),
		.M_AXI_0_AWSIZE(M_AXI_AWSIZE),
		.M_AXI_0_AWBURST(M_AXI_AWBURST),
		.M_AXI_0_AWCACHE(M_AXI_AWCACHE),
		.M_AXI_0_AWPROT(M_AXI_AWPROT),
		.M_AXI_0_WREADY(M_AXI_WREADY),
		.M_AXI_0_WVALID(M_AXI_WVALID),
		.M_AXI_0_WDATA(M_AXI_WDATA),
		.M_AXI_0_WSTRB(M_AXI_WSTRB),
		.M_AXI_0_WLAST(M_AXI_WLAST),
		.M_AXI_0_BREADY(M_AXI_BREADY),
		.M_AXI_0_BVALID(M_AXI_BVALID),
		.M_AXI_0_BID(M_AXI_BID),
		.M_AXI_0_BRESP(M_AXI_BRESP)
	);
	hbmexS_Queue2_ReadAddressChannel sourceBuffer(
		.clock(clock),
		.reset(reset),
		.io_enq_ready(S_AXI_ARREADY),
		.io_enq_valid(S_AXI_ARVALID),
		.io_enq_bits_id(S_AXI_ARID),
		.io_enq_bits_addr(S_AXI_ARADDR),
		.io_enq_bits_len(S_AXI_ARLEN),
		.io_enq_bits_size(S_AXI_ARSIZE),
		.io_enq_bits_burst(S_AXI_ARBURST),
		.io_enq_bits_cache(S_AXI_ARCACHE),
		.io_enq_bits_prot(S_AXI_ARPROT),
		.io_deq_ready(_stripe0_S_AXI_0_ARREADY),
		.io_deq_valid(_sourceBuffer_io_deq_valid),
		.io_deq_bits_id(_sourceBuffer_io_deq_bits_id),
		.io_deq_bits_addr(_sourceBuffer_io_deq_bits_addr),
		.io_deq_bits_len(_sourceBuffer_io_deq_bits_len),
		.io_deq_bits_size(_sourceBuffer_io_deq_bits_size),
		.io_deq_bits_burst(_sourceBuffer_io_deq_bits_burst),
		.io_deq_bits_cache(_sourceBuffer_io_deq_bits_cache),
		.io_deq_bits_prot(_sourceBuffer_io_deq_bits_prot)
	);
	hbmexS_Queue2_ReadDataChannel_1 sinkBuffer(
		.clock(clock),
		.reset(reset),
		.io_enq_ready(_sinkBuffer_io_enq_ready),
		.io_enq_valid(_stripe0_S_AXI_0_RVALID),
		.io_enq_bits_id(_stripe0_S_AXI_0_RID),
		.io_enq_bits_data(_stripe0_S_AXI_0_RDATA),
		.io_enq_bits_resp(_stripe0_S_AXI_0_RRESP),
		.io_enq_bits_last(_stripe0_S_AXI_0_RLAST),
		.io_deq_ready(S_AXI_RREADY),
		.io_deq_valid(S_AXI_RVALID),
		.io_deq_bits_id(S_AXI_RID),
		.io_deq_bits_data(S_AXI_RDATA),
		.io_deq_bits_resp(S_AXI_RRESP),
		.io_deq_bits_last(S_AXI_RLAST)
	);
	hbmexS_Queue2_WriteAddressChannel sourceBuffer_1(
		.clock(clock),
		.reset(reset),
		.io_enq_ready(S_AXI_AWREADY),
		.io_enq_valid(S_AXI_AWVALID),
		.io_enq_bits_id(S_AXI_AWID),
		.io_enq_bits_addr(S_AXI_AWADDR),
		.io_enq_bits_len(S_AXI_AWLEN),
		.io_enq_bits_size(S_AXI_AWSIZE),
		.io_enq_bits_burst(S_AXI_AWBURST),
		.io_enq_bits_cache(S_AXI_AWCACHE),
		.io_enq_bits_prot(S_AXI_AWPROT),
		.io_deq_ready(_stripe0_S_AXI_0_AWREADY),
		.io_deq_valid(_sourceBuffer_1_io_deq_valid),
		.io_deq_bits_id(_sourceBuffer_1_io_deq_bits_id),
		.io_deq_bits_addr(_sourceBuffer_1_io_deq_bits_addr),
		.io_deq_bits_len(_sourceBuffer_1_io_deq_bits_len),
		.io_deq_bits_size(_sourceBuffer_1_io_deq_bits_size),
		.io_deq_bits_burst(_sourceBuffer_1_io_deq_bits_burst),
		.io_deq_bits_cache(_sourceBuffer_1_io_deq_bits_cache),
		.io_deq_bits_prot(_sourceBuffer_1_io_deq_bits_prot)
	);
	hbmexS_Queue2_WriteDataChannel_1 sourceBuffer_2(
		.clock(clock),
		.reset(reset),
		.io_enq_ready(S_AXI_WREADY),
		.io_enq_valid(S_AXI_WVALID),
		.io_enq_bits_data(S_AXI_WDATA),
		.io_enq_bits_strb(S_AXI_WSTRB),
		.io_enq_bits_last(S_AXI_WLAST),
		.io_deq_ready(_stripe0_S_AXI_0_WREADY),
		.io_deq_valid(_sourceBuffer_2_io_deq_valid),
		.io_deq_bits_data(_sourceBuffer_2_io_deq_bits_data),
		.io_deq_bits_strb(_sourceBuffer_2_io_deq_bits_strb),
		.io_deq_bits_last(_sourceBuffer_2_io_deq_bits_last)
	);
	hbmexS_Queue2_WriteResponseChannel_1 sinkBuffer_1(
		.clock(clock),
		.reset(reset),
		.io_enq_ready(_sinkBuffer_1_io_enq_ready),
		.io_enq_valid(_stripe0_S_AXI_0_BVALID),
		.io_enq_bits_id(_stripe0_S_AXI_0_BID),
		.io_enq_bits_resp(_stripe0_S_AXI_0_BRESP),
		.io_deq_ready(S_AXI_BREADY),
		.io_deq_valid(S_AXI_BVALID),
		.io_deq_bits_id(S_AXI_BID),
		.io_deq_bits_resp(S_AXI_BRESP)
	);
endmodule
