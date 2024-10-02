#include "descriptors.h"
#include "hls_print.h"
#include <cstddef>
#include <cstdint>
#include <etc/autopilot_ssdm_op.h>

#define N 16

/*
 * <a> contains array of <n> queen positions.  Returns 1
 * if none of the queens conflict, and returns 0 otherwise.
 */
uint8_t ok(uint8_t n, uint64_t a, void *mem) {
#pragma HLS INTERFACE mode = m_axi port = mem

  for (int i = 0; i < n; i++) {
    // p = a[i];
    uint8_t p = MEM_ARR_IN(mem, a, i, uint8_t);
    for (int j = i + 1; j < n; j++) {
#pragma HLS PIPELINE off
      // q = a[j];
      uint8_t q = MEM_ARR_IN(mem, a, j, uint8_t);
      if (q == p || q == p - (j - i) || q == p + (j - i))
        return 0;
    }
  }

  return 1;
}

void cont(hls::stream<cont_args> &taskIn, hls::stream<uint64_t> &argOut,
          void *mem) {
#pragma HLS INTERFACE mode = axis port = taskIn
#pragma HLS INTERFACE mode = axis port = argOut
#pragma HLS INTERFACE mode = m_axi port = mem
  // Read the arguments
  cont_args task = taskIn.read();
  uint8_t n = task.n;
  uint64_t count = task.count;

  // Function body
  uint64_t solNum = 0;
  for (int i = 0; i < n; i++) {
    // solNum += count[i];
    solNum += MEM_ARR_IN(mem, count, i, uint32_t);
  }
// return solNum;
io_section : {
  MEM_OUT(mem, task.ret_addr, uint32_t, solNum);
  ap_wait_until(MEM_IN(mem, task.ret_addr, uint32_t) == solNum);
  argOut.write(task.cont);
}
}

void nqueens(hls::stream<nqueens_args> &taskIn,
             hls::stream<nqueens_args> &taskOut, hls::stream<uint64_t> &argOut,
             hls::stream<cont_args> &taskOutGlobal,
             hls::stream<uint64_t> &closureIn, hls::stream<uint64_t> &mallocIn,
             void *mem) {

#pragma HLS INTERFACE mode = axis port = taskIn
#pragma HLS INTERFACE mode = axis port = taskOut
#pragma HLS INTERFACE mode = axis port = argOut
#pragma HLS INTERFACE mode = axis port = taskOutGlobal
#pragma HLS INTERFACE mode = axis port = closureIn
#pragma HLS INTERFACE mode = axis port = mallocIn
#pragma HLS INTERFACE mode = m_axi port = mem

  // Read the arguments
  nqueens_args task = taskIn.read();
  uint8_t n = task.n;
  uint8_t j = task.j;
  uint64_t a = task.a;

  // Function body
  if (n == j) {
  // return 1;
  io_section0 : {
    MEM_OUT(mem, task.ret_addr, uint32_t, 1);
    ap_wait_until(MEM_IN(mem, task.ret_addr, uint32_t) == 1);
    argOut.write(task.cont);
  }
    return;
  }

  // uint32_t[n] count
  uint64_t count = mallocIn.read();
  for (int i = 0; i < n; i++) {
    MEM_ARR_OUT(mem, count, i, uint32_t, 0);
  }

  bool spawn[N];
  uint64_t bs[N];
  uint32_t spawn_count = 0;

  for (int i = 0; i < n; i++) {
    // uint8_t[j+1] b;
    // memcpy(b, a, j * sizeof(uint8_t));
    uint64_t b = mallocIn.read();
    for (int k = 0; k < j; k++) {
#pragma HLS PIPELINE off
      uint8_t tmp = MEM_ARR_IN(mem, a, k, uint8_t);
      MEM_ARR_OUT(mem, b, k, uint8_t, tmp);
    }

    bs[i] = b;
  io_section2 : {
    // b[j] = i;
    MEM_ARR_OUT(mem, b, j, uint8_t, i);
    ap_wait_until(MEM_ARR_IN(mem, b, j, uint8_t) == i);
    bool is_ok = ok(j + 1, b, mem);
    spawn[i] = is_ok;
    spawn_count += is_ok ? 1 : 0;
  }
  }

  uint64_t cont_addr = closureIn.read();
  cont_args c_args;
  c_args.counter = spawn_count;
  c_args.n = n;
  c_args.cont = task.cont;
  c_args.count = count;
  c_args.ret_addr = task.ret_addr;
  if (spawn_count != 0) {
  io_section1 : {
    // Spawn Next
    // return nqueens_cont(n, count);
    MEM_OUT(mem, cont_addr, cont_args, c_args);
    ap_wait_until(MEM_IN(mem, cont_addr + offsetof(cont_args, ret_addr),
                         uint64_t) == c_args.ret_addr);

    // Spawn
    for (int i = 0; i < n; i++) {
      if (spawn[i]) {
        // count[i] = cilk_spawn nqueens(n, j + 1, b);
        nqueens_args arg;
        arg.cont = cont_addr;
        arg.n = n;
        arg.j = j + 1;
        arg.a = bs[i];
        arg.ret_addr = count + i * sizeof(uint32_t);
        taskOut.write(arg);
      }
    }
  }
  } else {
    taskOutGlobal.write(c_args);
  }
}
