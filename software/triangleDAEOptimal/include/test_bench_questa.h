
#ifndef TESTBENCH_H
#define TESTBENCH_H


#include <FullSysGenDescriptor.h>
#include <systemc>
#include <main_sim_wrapper_questa.h>


using namespace sc_core;
using namespace sc_dt;

#include <triangleCountDriver.h>
#include <memIO_questa.h>

#include <cstdlib>
#include <string>

FullSysGenDescriptor helperDescriptor;
// A memory of 16 GB
#define memorySize (16ull * 1024ull * 1024ull * 1024ull)

// Edge list this run counts triangles on. Questa instantiates TestBench through
// SC_MODULE_EXPORT, so there is no argv to take it from the way xrt_main.cpp
// does; override it per run with the HARDCILK_GRAPH environment variable.
#ifndef HARDCILK_DEFAULT_GRAPH
#define HARDCILK_DEFAULT_GRAPH "graph.txt"
#endif

static inline std::string hardCilkGraphPath()
{
    const char *fromEnv = std::getenv("HARDCILK_GRAPH");
    return (fromEnv != nullptr && fromEnv[0] != '\0') ? std::string(fromEnv)
                                                      : std::string(HARDCILK_DEFAULT_GRAPH);
}

class TestBench : public sc_module {
public:

    SC_HAS_PROCESS(TestBench);
    TestBench(const sc_module_name& name = "TestBench")
        : sc_module(name)
        , mem_()
        , driver_(&mem_, hardCilkGraphPath())
         {

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

    void thread(){
        wait(25.0, SC_US);
        driver_.run_test_bench();
    }

    main_sim * myModule;
    
    sc_signal<sc_dt::sc_logic> HBM_CATTRIP_LS_;
    sc_signal<sc_dt::sc_logic> PCIE_PERST_LS_65_;
    sc_signal<sc_dt::sc_logic> SYSCLK2_clk_n_;
    sc_signal<sc_dt::sc_logic> SYSCLK2_clk_p_;
    sc_signal<sc_dt::sc_logic> SYSCLK3_clk_n_;
    sc_signal<sc_dt::sc_logic> SYSCLK3_clk_p_;
    sc_signal<sc_dt::sc_logic> axi_vip_clk_;
    sc_signal<sc_dt::sc_logic> axi_vip_aresetn_;

  

    questaMemory mem_;
    triangleCountDriver driver_;
};

#endif
