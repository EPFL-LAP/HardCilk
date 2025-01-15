#include "fib.h"
#include <cstdint>
#include <etc/autopilot_ssdm_op.h>

void sum(hls::stream<sum_args> &taskIn, hls::stream<uint64_t> &argOut,
         hls::stream<sum_arg_out> &argDataOut, void *mem) {

#pragma HLS INTERFACE mode = axis port = taskIn
#pragma HLS INTERFACE mode = axis port = argOut
#pragma HLS INTERFACE mode = axis port = argDataOut
#pragma HLS INTERFACE mode = m_axi port = mem

  sum_args args = taskIn.read();
  uint32_t sum = args.x + args.y;
  argOut.write(args.cont);
  sum_arg_out a;
  a.addr = args.cont;
  a.data = sum;
  a.size = 2;
  a.allow = 1;
  argDataOut.write(a);
}