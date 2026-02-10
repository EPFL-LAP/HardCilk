module chext_queue_no_data (
	clock,
	reset,
	source_valid,
	source_ready,
	sink_valid,
	sink_ready
);
	parameter COUNT = 16;
	parameter ADDR_WIDTH = 4;
	parameter PIPE = 0;
	parameter FLOW = 0;
	input clock;
	input reset;
	input source_valid;
	output wire source_ready;
	output wire sink_valid;
	input sink_ready;
	reg [ADDR_WIDTH - 1:0] enq_ptr;
	reg [ADDR_WIDTH - 1:0] deq_ptr;
	reg maybe_full;
	wire [ADDR_WIDTH - 1:0] enq_ptr_next = (enq_ptr == (COUNT - 1) ? 0 : enq_ptr + 1);
	wire [ADDR_WIDTH - 1:0] deq_ptr_next = (deq_ptr == (COUNT - 1) ? 0 : deq_ptr + 1);
	wire [ADDR_WIDTH - 1:0] read_addr;
	wire ptr_match = enq_ptr == deq_ptr;
	wire empty = ptr_match & ~maybe_full;
	wire full = ptr_match & maybe_full;
	wire do_enq;
	wire do_deq;
	generate
		if (PIPE) begin : pipe
			assign source_ready = ~full | sink_ready;
		end
		else begin : pipe
			assign source_ready = ~full;
		end
		if (FLOW) begin : flow
			assign sink_valid = ~empty | source_valid;
			assign do_enq = (source_ready & source_valid) & ~(empty & sink_ready);
			assign do_deq = (sink_ready & sink_valid) & ~empty;
		end
		else begin : no_flow
			assign sink_valid = ~empty;
			assign do_enq = source_ready & source_valid;
			assign do_deq = sink_ready & sink_valid;
		end
	endgenerate
	always @(posedge clock)
		if (reset) begin
			enq_ptr <= 0;
			deq_ptr <= 0;
			maybe_full <= 0;
		end
		else begin
			if (do_enq)
				enq_ptr <= enq_ptr_next;
			if (do_deq)
				deq_ptr <= deq_ptr_next;
			if (do_enq != do_deq)
				maybe_full <= do_enq;
		end
endmodule
