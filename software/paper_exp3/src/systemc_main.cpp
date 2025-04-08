#include <testBench.h>

#include "verilated_fst_sc.h"
#include <stdint.h>
#include <iostream>
#include "Vpaper_exp3.h"


static constexpr const char *name = "paper_exp3";

int sc_main(int argc, char **argv)
{
#ifdef VERILATED_TRACE_ENABLED
    Verilated::traceEverOn(true);
#endif
    Verilated::commandArgs(argc, argv);

    TestBench testBench("testBench");

    sc_start(SC_ZERO_TIME);

#ifdef VERILATED_TRACE_ENABLED
    auto* verilatedModule = static_cast<Vpaper_exp3*>(testBench.myModule->getVerilatedModule());
    VerilatedFstC* tfp = new VerilatedFstSc;
    verilatedModule->trace(tfp, 99, 0);
    tfp->open(fmt::format("{}.fst", name).c_str());
#endif

    sc_start(1000, SC_MS);
    
#ifdef VERILATED_TRACE_ENABLED
    tfp->close();
#endif

    return 0;
}