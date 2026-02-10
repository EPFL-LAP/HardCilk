module chext_mem_1w1r (
	clock,
	addrA,
	writeEnA,
	dataInA,
	addrB,
	dataOutB
);
	parameter COUNT = 16;
	parameter ADDR_WIDTH = 4;
	parameter DATA_WIDTH = 16;
	input clock;
	input [ADDR_WIDTH - 1:0] addrA;
	input writeEnA;
	input [DATA_WIDTH - 1:0] dataInA;
	input [ADDR_WIDTH - 1:0] addrB;
	output wire [DATA_WIDTH - 1:0] dataOutB;
	reg [DATA_WIDTH - 1:0] ram [COUNT - 1:0];
	always @(posedge clock)
		if (writeEnA)
			ram[addrA] <= dataInA;
	assign dataOutB = ram[addrB];
endmodule
