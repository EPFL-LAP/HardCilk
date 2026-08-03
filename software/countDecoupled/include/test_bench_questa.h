#ifndef COUNTDECOUPLED_TEST_BENCH_QUESTA_H
#define COUNTDECOUPLED_TEST_BENCH_QUESTA_H

// SystemC/QuestaSim co-simulation entry for countDecoupled.
//
// This is the QuestaSim backend counterpart of the XRT host (xrt_main.cpp ->
// run_count_decoupled_benchmark -> runSingleFpgaBenchmark). It deliberately
// reuses the SAME shared benchmark driver, CountDecoupledDriver::run_test_bench(),
// so any fix to the benchmark logic (seeding, init, polling, validation,
// telemetry) affects both backends. The ONLY difference is the Memory backend:
// here it is `questaMemory` (DPI bridge into the QuestaSim AXI-VIP masters),
// whereas the XRT host uses `XRTMemory`.
//
// The block design (kernel + Xilinx HBM IP + memory VIP) is instantiated as the
// SystemC foreign module `main_sim` (see main_sim_wrapper_questa.h / the generic
// main_sim.sv testbench resource). questaMemory drives it through the exported
// DPI tasks S_AXI_{READ,WRITE}_{MEM,REG}.

#include <FullSysGenDescriptor.h>
#include <systemc>
#include <main_sim_wrapper_questa.h>

#include <cstdlib>
#include <iostream>

using namespace sc_core;
using namespace sc_dt;

#include <CountDecoupledDriver.h>
#include <memIO_questa.h>

// A 16 GiB device memory image (matches the HBM map the design addresses).
#define memorySize (16ull * 1024ull * 1024ull * 1024ull)

// Small helpers so the simulation problem size can be tuned from the environment
// without recompiling (RTL simulation is orders of magnitude slower than HW, so
// the defaults are tiny; see the repro notes for forcing the argserver stall).
namespace {
inline uint32_t questaEnvU32(const char *name, uint32_t dflt)
{
  const char *v = std::getenv(name);
  return (v && *v) ? static_cast<uint32_t>(std::strtoul(v, nullptr, 10)) : dflt;
}
inline uint64_t questaEnvU64(const char *name, uint64_t dflt)
{
  const char *v = std::getenv(name);
  return (v && *v) ? static_cast<uint64_t>(std::strtoull(v, nullptr, 10)) : dflt;
}
inline double questaEnvDouble(const char *name, double dflt)
{
  const char *v = std::getenv(name);
  return (v && *v) ? std::strtod(v, nullptr) : dflt;
}
} // namespace

class TestBench : public sc_module
{
public:
  SC_HAS_PROCESS(TestBench);
  TestBench(const sc_module_name &name = "TestBench")
      : sc_module(name)
      , mem_()
  {
    // NOTE: the benchmark driver is deliberately NOT constructed here. An
    // sc_module constructor runs during ELABORATION, which QuestaSim's vopt also
    // performs while optimizing the design; doing the driver's work there
    // destabilizes vopt. It is created in thread() instead, i.e. at simulation
    // time, where the DPI bridge into the AXI VIPs is actually usable.
#ifdef MTI_SYSTEMC
    myModule = new main_sim("myModule", "xil_defaultlib.main_sim");
#else
    myModule = new main_sim("myModule");
#endif

    myModule->HBM_CATTRIP_LS(HBM_CATTRIP_LS_);
    myModule->PCIE_PERST_LS_65(PCIE_PERST_LS_65_);
    myModule->SYSCLK2_clk_n(SYSCLK2_clk_n_);
    myModule->SYSCLK2_clk_p(SYSCLK2_clk_p_);
    myModule->SYSCLK3_clk_n(SYSCLK3_clk_n_);
    myModule->SYSCLK3_clk_p(SYSCLK3_clk_p_);
    myModule->axi_vip_clk(axi_vip_clk_);
    myModule->axi_vip_aresetn(axi_vip_aresetn_);

    SC_THREAD(thread);
  }

  void thread()
  {
    // Construct the shared benchmark driver at SIMULATION time (see the note in
    // the constructor). Same class the XRT host uses; only the Memory backend
    // differs (questaMemory DPI bridge vs XRTMemory).
    CountDecoupledDriver driver_(
        &mem_,
        /*size*/ questaEnvU32("COUNTDECOUPLED_SIZE", 10),
        /*num_instances*/ questaEnvU32("COUNTDECOUPLED_INSTANCES", 2),
        // Simulation time is virtual; the host-side wall-clock watchdog must not
        // fire while the (slow) RTL sim advances. Keep it huge by default, but
        // allow bounding a run that deadlocks (COUNTDECOUPLED_WATCHDOG_S) so vsim
        // returns with the waveform instead of grinding out the whole `run` cap.
        /*watchdog_s*/ questaEnvDouble("COUNTDECOUPLED_WATCHDOG_S", 1.0e12),
        /*fast_mode*/ false,
        /*xclbin_path*/ std::string(),
        /*legacy_single_port_watcher*/ false,
        // HBM smart-placement / strided writes are XRT-bank optimizations with no
        // effect under the single behavioral HBM model; leave off.
        /*hbm_strided_writes*/ false,
        /*hbm_continuation_bank_run_entries*/
        questaEnvU64("COUNTDECOUPLED_BANK_RUN", 1));
    // Wait for the FULL memory-subsystem bring-up before issuing any traffic.
    // This is dominated by the Xilinx HBM model, not by the block design's own
    // reset: the model only de-asserts its per-channel Resetb at ~21 us and then
    // runs its init sequence. Transactions issued before that never get a BVALID
    // ("XILINX_RECS_WLCMD_TO_BVALID_MAX_WAIT" from the AXI VIP protocol checker)
    // and the host's blocking DPI write hangs forever. Keep a healthy margin.
    wait(questaEnvU32("COUNTDECOUPLED_START_DELAY_US", 200), SC_US);
    const int rc = driver_.run_test_bench();
    std::cout << "[countDecoupled-questa] run_test_bench rc=" << rc << "\n";
    // Stop the simulation so vsim returns; the waveform/telemetry are already
    // captured by this point.
    sc_stop();
  }

  main_sim *myModule;

  sc_signal<sc_dt::sc_logic> HBM_CATTRIP_LS_;
  sc_signal<sc_dt::sc_logic> PCIE_PERST_LS_65_;
  sc_signal<sc_dt::sc_logic> SYSCLK2_clk_n_;
  sc_signal<sc_dt::sc_logic> SYSCLK2_clk_p_;
  sc_signal<sc_dt::sc_logic> SYSCLK3_clk_n_;
  sc_signal<sc_dt::sc_logic> SYSCLK3_clk_p_;
  sc_signal<sc_dt::sc_logic> axi_vip_clk_;
  sc_signal<sc_dt::sc_logic> axi_vip_aresetn_;

  // Backend-specific memory (DPI bridge). Declared before driver_ so it is
  // constructed first (driver_ captures &mem_).
  questaMemory mem_;
};

#endif // COUNTDECOUPLED_TEST_BENCH_QUESTA_H
