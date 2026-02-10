module chext_syncmem_1w1r (
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
	output reg [DATA_WIDTH - 1:0] dataOutB;
	reg [DATA_WIDTH - 1:0] ram [COUNT - 1:0];
	always @(posedge clock)
		if (writeEnA)
			ram[addrA] <= dataInA;
	always @(posedge clock) dataOutB <= ram[addrB];
endmodule
