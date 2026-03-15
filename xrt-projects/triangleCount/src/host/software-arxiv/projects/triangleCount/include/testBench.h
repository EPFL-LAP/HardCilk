
#ifndef TESTBENCH_H
#define TESTBENCH_H


#include <triangleCount.hpp>
#include <FullSysGenDescriptor.h>
#include <systemc>
#include <tlm>

#include <sctlm/tlm_lib/drivers/memory.hpp>
#include <sctlm/tlm_lib/modules/iconnect.hpp>
#include <sctlm/tlm_lib/modules/memory.hpp>

#include <fmt/format.h>

using namespace sc_core;
using namespace sc_dt;

 #include <triangleCountDriver.h>
 #include <memIO_tlm.h>

FullSysGenDescriptor helperDescriptor;
// A memory of 16 GB
#define memorySize (16ull * 1024ull * 1024ull * 1024ull) 

class TestBench : public sc_module {
public:

    SC_HAS_PROCESS(TestBench);
    TestBench(const sc_module_name& name = "TestBench")
        : sc_module(name)
        , memory_("memory", sc_time(0, SC_NS), memorySize, 0, nullptr)
        , iconnectMem_("iconnectMem", helperDescriptor.getNumberAxiMasters(), 1)
        , iconnectPEMgmt_("iconnectPEMgmt", 1, helperDescriptor.getNumberPEsAXISlaves())
        , memoryDriverMemory_("memoryDriverMemory")
        , memoryDriverManagement_("memoryDriverManagement")
        , clock_("clock", sc_time(2, SC_NS))
        , mem_(memoryDriverMemory_, memoryDriverManagement_)
        , driver_(&mem_)
         {

        myModule = new triangleCount("myModule");
        myModule->reset(reset_);
        myModule->clock(clock_);
        myModule->paused(paused_);
        myModule->done(done_);

        memoryDriverMemory_.socket.bind(myModule->s_axi_xdma);
        peMgmtDriver_.socket.bind(*iconnectPEMgmt_.target_socket(0));

        (*iconnectMem_.initiator_socket(0)).bind(memory_.socket);

                myModule->m_axi_00.bind(*iconnectMem_.target_socket(0));
        myModule->m_axi_01.bind(*iconnectMem_.target_socket(1));
        myModule->m_axi_02.bind(*iconnectMem_.target_socket(2));
        myModule->m_axi_03.bind(*iconnectMem_.target_socket(3));
        iconnectPEMgmt_.initiator_socket(0)->bind(myModule->triangle_0_s_axi_control);
        iconnectPEMgmt_.initiator_socket(1)->bind(myModule->vertex_map_0_s_axi_control);


        iconnectMem_.memmap(0x0000, memorySize, 0);
        iconnectPEMgmt_.memmap(0x0000, 0x1000, 0);

        memoryDriverManagement_.socket.bind(myModule->s_axil_mgmt_hardcilk);
        SC_THREAD(thread);
        
    }

    void thread(){
       reset_.write(true);
       wait(10.0, SC_NS);
       reset_.write(false);
       wait(10.0, SC_NS);
       driver_.run_test_bench();
    }

    triangleCount * myModule;
    sctlm::tlm_lib::modules::memory memory_;
    sctlm::tlm_lib::modules::iconnect iconnectMem_;
    sctlm::tlm_lib::modules::iconnect iconnectPEMgmt_;
    sctlm::tlm_lib::drivers::memory_interface memoryDriverMemory_;
    sctlm::tlm_lib::drivers::memory_interface memoryDriverManagement_;
    sctlm::tlm_lib::drivers::memory_interface peMgmtDriver_; // Connected but not used

    TlmMemory mem_;
    sc_signal<bool> reset_;
    sc_clock clock_;
    sc_signal<bool> paused_;
    sc_signal<bool> done_;
    triangleCountDriver driver_;
};

#endif
        