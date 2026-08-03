// QuestaSim/SystemC co-simulation entry point for countDecoupled.
//
// Mirrors the fibonacci reference: sc_main instantiates the SystemC TestBench
// (which wraps the block-design foreign module `main_sim` + questaMemory + the
// shared CountDecoupledDriver), then advances simulation time. The benchmark
// stops the sim itself (sc_stop) from TestBench::thread once run_test_bench
// returns, so the sc_start upper bound is only a safety cap.
//
// Build: compiled by `sccom` from the generated simulate.do (see the QuestaSim
// project emitted by `HardCilkEmitter -q`). The XRT host uses xrt_main.cpp; both
// share CountDecoupledDriver.

#include "test_bench_questa.h"

#include <stdint.h>
#include <iostream>

int sc_main(int argc, char **argv)
{
  TestBench testBench("TestBench");
  sc_start(SC_ZERO_TIME);
  // Upper bound on simulated time. run_test_bench calls sc_stop when done; this
  // cap only bounds a hung/never-completing run (e.g. the argserver count=0
  // stall we are trying to reproduce) so vsim still returns.
  sc_start(200000, SC_US);
  return 0;
}

#ifdef MTI_SYSTEMC
SC_MODULE_EXPORT(TestBench);
#endif
