// Simple-dual-port memory backing the URAM variant of Util.DelayLine.
//
// One unconditional write and one read per cycle. The delay line keeps the two
// addresses provably distinct -- they trail each other by `delay-1` within a
// depth strictly greater than `delay` -- so nothing here relies on read-under-
// write behaviour. That matters more than it would for block RAM: URAM does not
// define a read-first result for a same-address access across its two ports, so
// "the old value" is not something that can be assumed the way READ_FIRST BRAM
// allows.
//
// ram_style = "ultra" is not optional. A 128-deep array is far too shallow for
// Vivado to reach for URAM on its own -- it would infer block RAM, which on this
// design is the resource already at 84.7% in the SLR that is full. The point of
// this module is to move the front porch off SLICEM (99.94% site-occupied in
// SLR1) and into URAM (0% used), so the style has to be stated.
//
// Read latency is TWO cycles, and Util.DelayLine presents the address two cycles
// before the data is due to match. The first register is the URAM's mandatory
// output (it has no combinational read path, which is what makes the array
// URAM-inferable at all); the second is its optional output pipeline register.
//
// The second stage is not free-floating latency -- the delay line absorbs it by
// trailing the read pointer one further, so the porch still delivers at exactly
// `delay`. It buys the frequency: without it Vivado reports "no optional output
// register could be merged into the ram block" and the URAM drives fabric
// directly off its array access, which is comfortable at 100 MHz and is the
// first thing to give at 200.
//
// Writing the two as a plain chain with nothing in between is what lets Vivado
// absorb BOTH into the block. Put any logic between them and the second becomes
// DATA flip-flops of fabric -- 1058 of them per lane, which would hand back a
// slice of exactly what this module exists to reclaim.
module UramDelayMem #(
    parameter DATA = 32,
    parameter ADDR = 7
) (
    input  wire            clk,
    input  wire [ADDR-1:0] waddr,
    input  wire [DATA-1:0] din,
    input  wire [ADDR-1:0] raddr,
    output reg  [DATA-1:0] dout
);

  (* ram_style = "ultra" *)
  reg [DATA-1:0] mem[0:(1 << ADDR) - 1];

  reg [DATA-1:0] mem_out;

  // No reset on the array or on either output register: URAM has neither an
  // INIT capability nor a reset, and the delay line does not need one. Every
  // payload slot is qualified by a separate valid chain that DOES reset, so the
  // contents read out before the line has filled are unobservable.
  always @(posedge clk) begin
    mem[waddr] <= din;
    mem_out <= mem[raddr];
    dout <= mem_out;
  end

endmodule
