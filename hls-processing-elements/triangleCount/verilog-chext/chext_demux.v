module chext_demux_control (
	source_data,
	source_last,
	source_valid,
	source_ready,
	sink_data_n,
	sink_valid_n,
	sink_ready_n,
	select_bits,
	select_valid,
	select_ready
);
	parameter COUNT = 16;
	parameter DATA_WIDTH = 32;
	parameter SEL_WIDTH = 4;
	input [DATA_WIDTH - 1:0] source_data;
	input source_last;
	input source_valid;
	output wire source_ready;
	output wire [(COUNT * DATA_WIDTH) - 1:0] sink_data_n;
	output wire [COUNT - 1:0] sink_valid_n;
	input [COUNT - 1:0] sink_ready_n;
	input [SEL_WIDTH - 1:0] select_bits;
	input select_valid;
	output wire select_ready;
	wire valid = source_valid & select_valid;
	wire fire = valid & sink_ready_n[select_bits];
	assign source_ready = fire;
	assign select_ready = fire & source_last;
	genvar _gv_i_1;
	generate
		for (_gv_i_1 = 0; _gv_i_1 < COUNT; _gv_i_1 = _gv_i_1 + 1) begin : valid_logic
			localparam i = _gv_i_1;
			assign sink_valid_n[i] = valid & (i == select_bits);
		end
	endgenerate
	genvar _gv_i_2;
	generate
		for (_gv_i_2 = 0; _gv_i_2 < COUNT; _gv_i_2 = _gv_i_2 + 1) begin : data_logic
			localparam i = _gv_i_2;
			assign sink_data_n[((i + 1) * DATA_WIDTH) - 1:i * DATA_WIDTH] = source_data;
		end
	endgenerate
endmodule
