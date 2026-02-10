module AddressTransform (
	s_axi_ar_ready,
	s_axi_ar_valid,
	s_axi_ar_bits_id,
	s_axi_ar_bits_addr,
	s_axi_ar_bits_len,
	s_axi_ar_bits_size,
	s_axi_ar_bits_burst,
	s_axi_r_ready,
	s_axi_r_valid,
	s_axi_r_bits_id,
	s_axi_r_bits_data,
	s_axi_r_bits_resp,
	s_axi_r_bits_last,
	s_axi_aw_ready,
	s_axi_aw_valid,
	s_axi_aw_bits_id,
	s_axi_aw_bits_addr,
	s_axi_aw_bits_len,
	s_axi_aw_bits_size,
	s_axi_aw_bits_burst,
	s_axi_w_ready,
	s_axi_w_valid,
	s_axi_w_bits_data,
	s_axi_w_bits_strb,
	s_axi_w_bits_last,
	s_axi_b_ready,
	s_axi_b_valid,
	s_axi_b_bits_id,
	s_axi_b_bits_resp,
	m_axi_ar_ready,
	m_axi_ar_valid,
	m_axi_ar_bits_id,
	m_axi_ar_bits_addr,
	m_axi_ar_bits_len,
	m_axi_ar_bits_size,
	m_axi_ar_bits_burst,
	m_axi_r_ready,
	m_axi_r_valid,
	m_axi_r_bits_id,
	m_axi_r_bits_data,
	m_axi_r_bits_resp,
	m_axi_r_bits_last,
	m_axi_aw_ready,
	m_axi_aw_valid,
	m_axi_aw_bits_id,
	m_axi_aw_bits_addr,
	m_axi_aw_bits_len,
	m_axi_aw_bits_size,
	m_axi_aw_bits_burst,
	m_axi_w_ready,
	m_axi_w_valid,
	m_axi_w_bits_data,
	m_axi_w_bits_strb,
	m_axi_w_bits_last,
	m_axi_b_ready,
	m_axi_b_valid,
	m_axi_b_bits_id,
	m_axi_b_bits_resp
);
	output wire s_axi_ar_ready;
	input s_axi_ar_valid;
	input [5:0] s_axi_ar_bits_id;
	input [37:0] s_axi_ar_bits_addr;
	input [3:0] s_axi_ar_bits_len;
	input [2:0] s_axi_ar_bits_size;
	input [1:0] s_axi_ar_bits_burst;
	input s_axi_r_ready;
	output wire s_axi_r_valid;
	output wire [5:0] s_axi_r_bits_id;
	output wire [255:0] s_axi_r_bits_data;
	output wire [1:0] s_axi_r_bits_resp;
	output wire s_axi_r_bits_last;
	output wire s_axi_aw_ready;
	input s_axi_aw_valid;
	input [5:0] s_axi_aw_bits_id;
	input [37:0] s_axi_aw_bits_addr;
	input [3:0] s_axi_aw_bits_len;
	input [2:0] s_axi_aw_bits_size;
	input [1:0] s_axi_aw_bits_burst;
	output wire s_axi_w_ready;
	input s_axi_w_valid;
	input [255:0] s_axi_w_bits_data;
	input [31:0] s_axi_w_bits_strb;
	input s_axi_w_bits_last;
	input s_axi_b_ready;
	output wire s_axi_b_valid;
	output wire [5:0] s_axi_b_bits_id;
	output wire [1:0] s_axi_b_bits_resp;
	input m_axi_ar_ready;
	output wire m_axi_ar_valid;
	output wire [5:0] m_axi_ar_bits_id;
	output wire [37:0] m_axi_ar_bits_addr;
	output wire [3:0] m_axi_ar_bits_len;
	output wire [2:0] m_axi_ar_bits_size;
	output wire [1:0] m_axi_ar_bits_burst;
	output wire m_axi_r_ready;
	input m_axi_r_valid;
	input [5:0] m_axi_r_bits_id;
	input [255:0] m_axi_r_bits_data;
	input [1:0] m_axi_r_bits_resp;
	input m_axi_r_bits_last;
	input m_axi_aw_ready;
	output wire m_axi_aw_valid;
	output wire [5:0] m_axi_aw_bits_id;
	output wire [37:0] m_axi_aw_bits_addr;
	output wire [3:0] m_axi_aw_bits_len;
	output wire [2:0] m_axi_aw_bits_size;
	output wire [1:0] m_axi_aw_bits_burst;
	input m_axi_w_ready;
	output wire m_axi_w_valid;
	output wire [255:0] m_axi_w_bits_data;
	output wire [31:0] m_axi_w_bits_strb;
	output wire m_axi_w_bits_last;
	output wire m_axi_b_ready;
	input m_axi_b_valid;
	input [5:0] m_axi_b_bits_id;
	input [1:0] m_axi_b_bits_resp;
	assign s_axi_ar_ready = m_axi_ar_ready;
	assign s_axi_r_valid = m_axi_r_valid;
	assign s_axi_r_bits_id = m_axi_r_bits_id;
	assign s_axi_r_bits_data = m_axi_r_bits_data;
	assign s_axi_r_bits_resp = m_axi_r_bits_resp;
	assign s_axi_r_bits_last = m_axi_r_bits_last;
	assign s_axi_aw_ready = m_axi_aw_ready;
	assign s_axi_w_ready = m_axi_w_ready;
	assign s_axi_b_valid = m_axi_b_valid;
	assign s_axi_b_bits_id = m_axi_b_bits_id;
	assign s_axi_b_bits_resp = m_axi_b_bits_resp;
	assign m_axi_ar_valid = s_axi_ar_valid;
	assign m_axi_ar_bits_id = s_axi_ar_bits_id;
	assign m_axi_ar_bits_addr = {s_axi_ar_bits_addr[37:33], s_axi_ar_bits_addr[17:14], s_axi_ar_bits_addr[28:18], s_axi_ar_bits_addr[32:29], s_axi_ar_bits_addr[13:0]};
	assign m_axi_ar_bits_len = s_axi_ar_bits_len;
	assign m_axi_ar_bits_size = s_axi_ar_bits_size;
	assign m_axi_ar_bits_burst = s_axi_ar_bits_burst;
	assign m_axi_r_ready = s_axi_r_ready;
	assign m_axi_aw_valid = s_axi_aw_valid;
	assign m_axi_aw_bits_id = s_axi_aw_bits_id;
	assign m_axi_aw_bits_addr = {s_axi_aw_bits_addr[37:33], s_axi_aw_bits_addr[17:14], s_axi_aw_bits_addr[28:18], s_axi_aw_bits_addr[32:29], s_axi_aw_bits_addr[13:0]};
	assign m_axi_aw_bits_len = s_axi_aw_bits_len;
	assign m_axi_aw_bits_size = s_axi_aw_bits_size;
	assign m_axi_aw_bits_burst = s_axi_aw_bits_burst;
	assign m_axi_w_valid = s_axi_w_valid;
	assign m_axi_w_bits_data = s_axi_w_bits_data;
	assign m_axi_w_bits_strb = s_axi_w_bits_strb;
	assign m_axi_w_bits_last = s_axi_w_bits_last;
	assign m_axi_b_ready = s_axi_b_ready;
endmodule
module AddressTransformHardcilk (
	clock,
	reset,
	S_AXI_ARREADY,
	S_AXI_ARVALID,
	S_AXI_ARID,
	S_AXI_ARADDR,
	S_AXI_ARLEN,
	S_AXI_ARSIZE,
	S_AXI_ARBURST,
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
	input [5:0] S_AXI_ARID;
	input [37:0] S_AXI_ARADDR;
	input [3:0] S_AXI_ARLEN;
	input [2:0] S_AXI_ARSIZE;
	input [1:0] S_AXI_ARBURST;
	input S_AXI_RREADY;
	output wire S_AXI_RVALID;
	output wire [5:0] S_AXI_RID;
	output wire [255:0] S_AXI_RDATA;
	output wire [1:0] S_AXI_RRESP;
	output wire S_AXI_RLAST;
	output wire S_AXI_AWREADY;
	input S_AXI_AWVALID;
	input [5:0] S_AXI_AWID;
	input [37:0] S_AXI_AWADDR;
	input [3:0] S_AXI_AWLEN;
	input [2:0] S_AXI_AWSIZE;
	input [1:0] S_AXI_AWBURST;
	output wire S_AXI_WREADY;
	input S_AXI_WVALID;
	input [255:0] S_AXI_WDATA;
	input [31:0] S_AXI_WSTRB;
	input S_AXI_WLAST;
	input S_AXI_BREADY;
	output wire S_AXI_BVALID;
	output wire [5:0] S_AXI_BID;
	output wire [1:0] S_AXI_BRESP;
	input M_AXI_ARREADY;
	output wire M_AXI_ARVALID;
	output wire [5:0] M_AXI_ARID;
	output wire [37:0] M_AXI_ARADDR;
	output wire [3:0] M_AXI_ARLEN;
	output wire [2:0] M_AXI_ARSIZE;
	output wire [1:0] M_AXI_ARBURST;
	output wire M_AXI_RREADY;
	input M_AXI_RVALID;
	input [5:0] M_AXI_RID;
	input [255:0] M_AXI_RDATA;
	input [1:0] M_AXI_RRESP;
	input M_AXI_RLAST;
	input M_AXI_AWREADY;
	output wire M_AXI_AWVALID;
	output wire [5:0] M_AXI_AWID;
	output wire [37:0] M_AXI_AWADDR;
	output wire [3:0] M_AXI_AWLEN;
	output wire [2:0] M_AXI_AWSIZE;
	output wire [1:0] M_AXI_AWBURST;
	input M_AXI_WREADY;
	output wire M_AXI_WVALID;
	output wire [255:0] M_AXI_WDATA;
	output wire [31:0] M_AXI_WSTRB;
	output wire M_AXI_WLAST;
	output wire M_AXI_BREADY;
	input M_AXI_BVALID;
	input [5:0] M_AXI_BID;
	input [1:0] M_AXI_BRESP;
	AddressTransform dut(
		.s_axi_ar_ready(S_AXI_ARREADY),
		.s_axi_ar_valid(S_AXI_ARVALID),
		.s_axi_ar_bits_id(S_AXI_ARID),
		.s_axi_ar_bits_addr(S_AXI_ARADDR),
		.s_axi_ar_bits_len(S_AXI_ARLEN),
		.s_axi_ar_bits_size(S_AXI_ARSIZE),
		.s_axi_ar_bits_burst(S_AXI_ARBURST),
		.s_axi_r_ready(S_AXI_RREADY),
		.s_axi_r_valid(S_AXI_RVALID),
		.s_axi_r_bits_id(S_AXI_RID),
		.s_axi_r_bits_data(S_AXI_RDATA),
		.s_axi_r_bits_resp(S_AXI_RRESP),
		.s_axi_r_bits_last(S_AXI_RLAST),
		.s_axi_aw_ready(S_AXI_AWREADY),
		.s_axi_aw_valid(S_AXI_AWVALID),
		.s_axi_aw_bits_id(S_AXI_AWID),
		.s_axi_aw_bits_addr(S_AXI_AWADDR),
		.s_axi_aw_bits_len(S_AXI_AWLEN),
		.s_axi_aw_bits_size(S_AXI_AWSIZE),
		.s_axi_aw_bits_burst(S_AXI_AWBURST),
		.s_axi_w_ready(S_AXI_WREADY),
		.s_axi_w_valid(S_AXI_WVALID),
		.s_axi_w_bits_data(S_AXI_WDATA),
		.s_axi_w_bits_strb(S_AXI_WSTRB),
		.s_axi_w_bits_last(S_AXI_WLAST),
		.s_axi_b_ready(S_AXI_BREADY),
		.s_axi_b_valid(S_AXI_BVALID),
		.s_axi_b_bits_id(S_AXI_BID),
		.s_axi_b_bits_resp(S_AXI_BRESP),
		.m_axi_ar_ready(M_AXI_ARREADY),
		.m_axi_ar_valid(M_AXI_ARVALID),
		.m_axi_ar_bits_id(M_AXI_ARID),
		.m_axi_ar_bits_addr(M_AXI_ARADDR),
		.m_axi_ar_bits_len(M_AXI_ARLEN),
		.m_axi_ar_bits_size(M_AXI_ARSIZE),
		.m_axi_ar_bits_burst(M_AXI_ARBURST),
		.m_axi_r_ready(M_AXI_RREADY),
		.m_axi_r_valid(M_AXI_RVALID),
		.m_axi_r_bits_id(M_AXI_RID),
		.m_axi_r_bits_data(M_AXI_RDATA),
		.m_axi_r_bits_resp(M_AXI_RRESP),
		.m_axi_r_bits_last(M_AXI_RLAST),
		.m_axi_aw_ready(M_AXI_AWREADY),
		.m_axi_aw_valid(M_AXI_AWVALID),
		.m_axi_aw_bits_id(M_AXI_AWID),
		.m_axi_aw_bits_addr(M_AXI_AWADDR),
		.m_axi_aw_bits_len(M_AXI_AWLEN),
		.m_axi_aw_bits_size(M_AXI_AWSIZE),
		.m_axi_aw_bits_burst(M_AXI_AWBURST),
		.m_axi_w_ready(M_AXI_WREADY),
		.m_axi_w_valid(M_AXI_WVALID),
		.m_axi_w_bits_data(M_AXI_WDATA),
		.m_axi_w_bits_strb(M_AXI_WSTRB),
		.m_axi_w_bits_last(M_AXI_WLAST),
		.m_axi_b_ready(M_AXI_BREADY),
		.m_axi_b_valid(M_AXI_BVALID),
		.m_axi_b_bits_id(M_AXI_BID),
		.m_axi_b_bits_resp(M_AXI_BRESP)
	);
endmodule
