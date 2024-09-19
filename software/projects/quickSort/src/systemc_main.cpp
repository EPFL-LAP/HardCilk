#include <testBench.h>

#include <verilated_vcd_sc.h>
#include <stdint.h>
#include <iostream>

static constexpr const char *name = "qsort";

int sc_main(int argc, char **argv)
{
#ifdef VERILATED_TRACE_ENABLED
    Verilated::traceEverOn(true);
#endif
    Verilated::commandArgs(argc, argv);

    TestBench testBench("testBench");

    sc_start(SC_ZERO_TIME);

#ifdef VERILATED_TRACE_ENABLED
    auto tfp = std::make_unique<VerilatedVcdSc>();
    testBench.myModule->traceVerilated(tfp.get(), 99);
    tfp->open(fmt::format("{}.vcd", name).c_str());
#endif

    sc_start(1, SC_US);
    
#ifdef VERILATED_TRACE_ENABLED
    tfp->close();
#endif

    return 0;
}