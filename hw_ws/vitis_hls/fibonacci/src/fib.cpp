#include "fib.h"
#include <cstdint>
#include <etc/autopilot_ssdm_op.h>

void fib(hls::stream<fib_args> &taskIn, hls::stream<fib_args> &taskOut,
         hls::stream<uint64_t> &argOut, hls::stream<sum_arg_out> &argDataOut,
         hls::stream<uint64_t> &closureIn,
         hls::stream<fib_spawn_next> &spawnNext, void *mem) {

#pragma HLS INTERFACE mode = axis port = taskIn
#pragma HLS INTERFACE mode = axis port = taskOut
#pragma HLS INTERFACE mode = axis port = argOut
#pragma HLS INTERFACE mode = axis port = argDataOut
#pragma HLS INTERFACE mode = axis port = closureIn
#pragma HLS INTERFACE mode = axis port = spawnNext
#pragma HLS INTERFACE mode = m_axi port = mem

  fib_args args = taskIn.read();

  if (args.n <= 1) {
    argOut.write((uint64_t)args.cont);
    sum_arg_out a;
    a.addr = args.cont;
    a.data = args.n;
    a.size = 2;
    a.allow = 1;
    argDataOut.write(a);
  } else {
    sum_args sum_args0;
    sum_args0.cont = args.cont;
    sum_args0.join_counter = 2;
    uint64_t sum_addr = closureIn.read();
    uint64_t x_addr = sum_addr + 0x4;
    uint64_t y_addr = sum_addr + 0x8;
    fib_args fib_args1;
    fib_args fib_args2;
    fib_args1.cont = x_addr;
    fib_args1.n = args.n - 1;
    fib_args2.cont = y_addr;
    fib_args2.n = args.n - 2;

    taskOut.write(fib_args1);
    taskOut.write(fib_args2);

    fib_spawn_next sn;
    sn.addr = sum_addr;
    sn.data = sum_args0;
    sn.size = 5;
    sn.allow = 2;
    spawnNext.write(sn);
  }
}