
#ifndef TESTBENCH_H
#define TESTBENCH_H


#include <quickSort.hpp>
#include <FullSysGenDescriptor.h>
#include <systemc>
#include <tlm>

#include <sctlm/tlm_lib/drivers/memory.hpp>
#include <sctlm/tlm_lib/modules/iconnect.hpp>
#include <sctlm/tlm_lib/modules/memory.hpp>

#include <fmt/format.h>

using namespace sc_core;
using namespace sc_dt;

 #include <quickSortDriver.h>
 #include <tlmMemory.h>

FullSysGenDescriptor helperDescriptor;
// A memory of 1 GB
#define memorySize (1ull * 1024ull * 1024ull * 1024ull) 

class TestBench : public sc_module {
public:

    SC_HAS_PROCESS(TestBench);
    TestBench(const sc_module_name& name = "TestBench")
        : sc_module(name)
        , memory_("memory", sc_time(120, SC_NS), memorySize, 0, nullptr)
        , iconnectMem_("iconnectMem", helperDescriptor.getNumberAxiMasters() + 1, 1)
        , iconnectPEMgmt_("iconnectPEMgmt", 1, helperDescriptor.getNumberPEsAXISlaves())
        , memoryDriverMemory_("memoryDriverMemory")
        , memoryDriverManagement_("memoryDriverManagement")
        , clock_("clock", sc_time(2, SC_NS))
        , mem_(memoryDriverMemory_, memoryDriverManagement_)
        , driver_(&mem_)
         {

        myModule = new quickSort("myModule");
        myModule->reset(reset_);
        myModule->clock(clock_);

        memoryDriverMemory_.socket.bind(*iconnectMem_.target_socket(0));
        peMgmtDriver_.socket.bind(*iconnectPEMgmt_.target_socket(0));

        (*iconnectMem_.initiator_socket(0)).bind(memory_.socket);

                myModule->qsort_schedulerAXI_0.bind(*iconnectMem_.target_socket(1));
        myModule->qsort_0_m_axi_gmem.bind(*iconnectMem_.target_socket(2));
        iconnectPEMgmt_.initiator_socket(0)->bind(myModule->qsort_0_s_axi_control);
        myModule->qsort_1_m_axi_gmem.bind(*iconnectMem_.target_socket(3));
        iconnectPEMgmt_.initiator_socket(1)->bind(myModule->qsort_1_s_axi_control);
        myModule->qsort_2_m_axi_gmem.bind(*iconnectMem_.target_socket(4));
        iconnectPEMgmt_.initiator_socket(2)->bind(myModule->qsort_2_s_axi_control);
        myModule->qsort_3_m_axi_gmem.bind(*iconnectMem_.target_socket(5));
        iconnectPEMgmt_.initiator_socket(3)->bind(myModule->qsort_3_s_axi_control);
        myModule->qsort_4_m_axi_gmem.bind(*iconnectMem_.target_socket(6));
        iconnectPEMgmt_.initiator_socket(4)->bind(myModule->qsort_4_s_axi_control);
        myModule->qsort_5_m_axi_gmem.bind(*iconnectMem_.target_socket(7));
        iconnectPEMgmt_.initiator_socket(5)->bind(myModule->qsort_5_s_axi_control);
        myModule->qsort_6_m_axi_gmem.bind(*iconnectMem_.target_socket(8));
        iconnectPEMgmt_.initiator_socket(6)->bind(myModule->qsort_6_s_axi_control);
        myModule->qsort_7_m_axi_gmem.bind(*iconnectMem_.target_socket(9));
        iconnectPEMgmt_.initiator_socket(7)->bind(myModule->qsort_7_s_axi_control);
        myModule->qsort_8_m_axi_gmem.bind(*iconnectMem_.target_socket(10));
        iconnectPEMgmt_.initiator_socket(8)->bind(myModule->qsort_8_s_axi_control);
        myModule->qsort_9_m_axi_gmem.bind(*iconnectMem_.target_socket(11));
        iconnectPEMgmt_.initiator_socket(9)->bind(myModule->qsort_9_s_axi_control);
        myModule->qsort_10_m_axi_gmem.bind(*iconnectMem_.target_socket(12));
        iconnectPEMgmt_.initiator_socket(10)->bind(myModule->qsort_10_s_axi_control);
        myModule->qsort_11_m_axi_gmem.bind(*iconnectMem_.target_socket(13));
        iconnectPEMgmt_.initiator_socket(11)->bind(myModule->qsort_11_s_axi_control);
        myModule->qsort_12_m_axi_gmem.bind(*iconnectMem_.target_socket(14));
        iconnectPEMgmt_.initiator_socket(12)->bind(myModule->qsort_12_s_axi_control);
        myModule->qsort_13_m_axi_gmem.bind(*iconnectMem_.target_socket(15));
        iconnectPEMgmt_.initiator_socket(13)->bind(myModule->qsort_13_s_axi_control);
        myModule->qsort_14_m_axi_gmem.bind(*iconnectMem_.target_socket(16));
        iconnectPEMgmt_.initiator_socket(14)->bind(myModule->qsort_14_s_axi_control);
        myModule->qsort_15_m_axi_gmem.bind(*iconnectMem_.target_socket(17));
        iconnectPEMgmt_.initiator_socket(15)->bind(myModule->qsort_15_s_axi_control);
        myModule->qsort_16_m_axi_gmem.bind(*iconnectMem_.target_socket(18));
        iconnectPEMgmt_.initiator_socket(16)->bind(myModule->qsort_16_s_axi_control);
        myModule->qsort_17_m_axi_gmem.bind(*iconnectMem_.target_socket(19));
        iconnectPEMgmt_.initiator_socket(17)->bind(myModule->qsort_17_s_axi_control);
        myModule->qsort_18_m_axi_gmem.bind(*iconnectMem_.target_socket(20));
        iconnectPEMgmt_.initiator_socket(18)->bind(myModule->qsort_18_s_axi_control);
        myModule->qsort_19_m_axi_gmem.bind(*iconnectMem_.target_socket(21));
        iconnectPEMgmt_.initiator_socket(19)->bind(myModule->qsort_19_s_axi_control);
        myModule->qsort_20_m_axi_gmem.bind(*iconnectMem_.target_socket(22));
        iconnectPEMgmt_.initiator_socket(20)->bind(myModule->qsort_20_s_axi_control);
        myModule->qsort_21_m_axi_gmem.bind(*iconnectMem_.target_socket(23));
        iconnectPEMgmt_.initiator_socket(21)->bind(myModule->qsort_21_s_axi_control);
        myModule->qsort_22_m_axi_gmem.bind(*iconnectMem_.target_socket(24));
        iconnectPEMgmt_.initiator_socket(22)->bind(myModule->qsort_22_s_axi_control);
        myModule->qsort_23_m_axi_gmem.bind(*iconnectMem_.target_socket(25));
        iconnectPEMgmt_.initiator_socket(23)->bind(myModule->qsort_23_s_axi_control);
        myModule->qsort_24_m_axi_gmem.bind(*iconnectMem_.target_socket(26));
        iconnectPEMgmt_.initiator_socket(24)->bind(myModule->qsort_24_s_axi_control);
        myModule->qsort_25_m_axi_gmem.bind(*iconnectMem_.target_socket(27));
        iconnectPEMgmt_.initiator_socket(25)->bind(myModule->qsort_25_s_axi_control);
        myModule->qsort_26_m_axi_gmem.bind(*iconnectMem_.target_socket(28));
        iconnectPEMgmt_.initiator_socket(26)->bind(myModule->qsort_26_s_axi_control);
        myModule->qsort_27_m_axi_gmem.bind(*iconnectMem_.target_socket(29));
        iconnectPEMgmt_.initiator_socket(27)->bind(myModule->qsort_27_s_axi_control);
        myModule->qsort_28_m_axi_gmem.bind(*iconnectMem_.target_socket(30));
        iconnectPEMgmt_.initiator_socket(28)->bind(myModule->qsort_28_s_axi_control);
        myModule->qsort_29_m_axi_gmem.bind(*iconnectMem_.target_socket(31));
        iconnectPEMgmt_.initiator_socket(29)->bind(myModule->qsort_29_s_axi_control);
        myModule->qsort_30_m_axi_gmem.bind(*iconnectMem_.target_socket(32));
        iconnectPEMgmt_.initiator_socket(30)->bind(myModule->qsort_30_s_axi_control);
        myModule->qsort_31_m_axi_gmem.bind(*iconnectMem_.target_socket(33));
        iconnectPEMgmt_.initiator_socket(31)->bind(myModule->qsort_31_s_axi_control);
        myModule->qsort_32_m_axi_gmem.bind(*iconnectMem_.target_socket(34));
        iconnectPEMgmt_.initiator_socket(32)->bind(myModule->qsort_32_s_axi_control);
        myModule->qsort_33_m_axi_gmem.bind(*iconnectMem_.target_socket(35));
        iconnectPEMgmt_.initiator_socket(33)->bind(myModule->qsort_33_s_axi_control);
        myModule->qsort_34_m_axi_gmem.bind(*iconnectMem_.target_socket(36));
        iconnectPEMgmt_.initiator_socket(34)->bind(myModule->qsort_34_s_axi_control);
        myModule->qsort_35_m_axi_gmem.bind(*iconnectMem_.target_socket(37));
        iconnectPEMgmt_.initiator_socket(35)->bind(myModule->qsort_35_s_axi_control);
        myModule->qsort_36_m_axi_gmem.bind(*iconnectMem_.target_socket(38));
        iconnectPEMgmt_.initiator_socket(36)->bind(myModule->qsort_36_s_axi_control);
        myModule->qsort_37_m_axi_gmem.bind(*iconnectMem_.target_socket(39));
        iconnectPEMgmt_.initiator_socket(37)->bind(myModule->qsort_37_s_axi_control);
        myModule->qsort_38_m_axi_gmem.bind(*iconnectMem_.target_socket(40));
        iconnectPEMgmt_.initiator_socket(38)->bind(myModule->qsort_38_s_axi_control);
        myModule->qsort_39_m_axi_gmem.bind(*iconnectMem_.target_socket(41));
        iconnectPEMgmt_.initiator_socket(39)->bind(myModule->qsort_39_s_axi_control);
        myModule->qsort_40_m_axi_gmem.bind(*iconnectMem_.target_socket(42));
        iconnectPEMgmt_.initiator_socket(40)->bind(myModule->qsort_40_s_axi_control);
        myModule->qsort_41_m_axi_gmem.bind(*iconnectMem_.target_socket(43));
        iconnectPEMgmt_.initiator_socket(41)->bind(myModule->qsort_41_s_axi_control);
        myModule->qsort_42_m_axi_gmem.bind(*iconnectMem_.target_socket(44));
        iconnectPEMgmt_.initiator_socket(42)->bind(myModule->qsort_42_s_axi_control);
        myModule->qsort_43_m_axi_gmem.bind(*iconnectMem_.target_socket(45));
        iconnectPEMgmt_.initiator_socket(43)->bind(myModule->qsort_43_s_axi_control);
        myModule->qsort_44_m_axi_gmem.bind(*iconnectMem_.target_socket(46));
        iconnectPEMgmt_.initiator_socket(44)->bind(myModule->qsort_44_s_axi_control);
        myModule->qsort_45_m_axi_gmem.bind(*iconnectMem_.target_socket(47));
        iconnectPEMgmt_.initiator_socket(45)->bind(myModule->qsort_45_s_axi_control);
        myModule->qsort_46_m_axi_gmem.bind(*iconnectMem_.target_socket(48));
        iconnectPEMgmt_.initiator_socket(46)->bind(myModule->qsort_46_s_axi_control);
        myModule->qsort_47_m_axi_gmem.bind(*iconnectMem_.target_socket(49));
        iconnectPEMgmt_.initiator_socket(47)->bind(myModule->qsort_47_s_axi_control);
        myModule->qsort_48_m_axi_gmem.bind(*iconnectMem_.target_socket(50));
        iconnectPEMgmt_.initiator_socket(48)->bind(myModule->qsort_48_s_axi_control);
        myModule->qsort_49_m_axi_gmem.bind(*iconnectMem_.target_socket(51));
        iconnectPEMgmt_.initiator_socket(49)->bind(myModule->qsort_49_s_axi_control);
        myModule->qsort_50_m_axi_gmem.bind(*iconnectMem_.target_socket(52));
        iconnectPEMgmt_.initiator_socket(50)->bind(myModule->qsort_50_s_axi_control);
        myModule->qsort_51_m_axi_gmem.bind(*iconnectMem_.target_socket(53));
        iconnectPEMgmt_.initiator_socket(51)->bind(myModule->qsort_51_s_axi_control);
        myModule->qsort_52_m_axi_gmem.bind(*iconnectMem_.target_socket(54));
        iconnectPEMgmt_.initiator_socket(52)->bind(myModule->qsort_52_s_axi_control);
        myModule->qsort_53_m_axi_gmem.bind(*iconnectMem_.target_socket(55));
        iconnectPEMgmt_.initiator_socket(53)->bind(myModule->qsort_53_s_axi_control);
        myModule->qsort_54_m_axi_gmem.bind(*iconnectMem_.target_socket(56));
        iconnectPEMgmt_.initiator_socket(54)->bind(myModule->qsort_54_s_axi_control);
        myModule->qsort_55_m_axi_gmem.bind(*iconnectMem_.target_socket(57));
        iconnectPEMgmt_.initiator_socket(55)->bind(myModule->qsort_55_s_axi_control);
        myModule->qsort_56_m_axi_gmem.bind(*iconnectMem_.target_socket(58));
        iconnectPEMgmt_.initiator_socket(56)->bind(myModule->qsort_56_s_axi_control);
        myModule->qsort_57_m_axi_gmem.bind(*iconnectMem_.target_socket(59));
        iconnectPEMgmt_.initiator_socket(57)->bind(myModule->qsort_57_s_axi_control);
        myModule->qsort_58_m_axi_gmem.bind(*iconnectMem_.target_socket(60));
        iconnectPEMgmt_.initiator_socket(58)->bind(myModule->qsort_58_s_axi_control);
        myModule->qsort_59_m_axi_gmem.bind(*iconnectMem_.target_socket(61));
        iconnectPEMgmt_.initiator_socket(59)->bind(myModule->qsort_59_s_axi_control);
        myModule->qsort_60_m_axi_gmem.bind(*iconnectMem_.target_socket(62));
        iconnectPEMgmt_.initiator_socket(60)->bind(myModule->qsort_60_s_axi_control);
        myModule->qsort_61_m_axi_gmem.bind(*iconnectMem_.target_socket(63));
        iconnectPEMgmt_.initiator_socket(61)->bind(myModule->qsort_61_s_axi_control);
        myModule->qsort_62_m_axi_gmem.bind(*iconnectMem_.target_socket(64));
        iconnectPEMgmt_.initiator_socket(62)->bind(myModule->qsort_62_s_axi_control);
        myModule->qsort_63_m_axi_gmem.bind(*iconnectMem_.target_socket(65));
        iconnectPEMgmt_.initiator_socket(63)->bind(myModule->qsort_63_s_axi_control);
        myModule->sync_schedulerAXI_0.bind(*iconnectMem_.target_socket(66));
        myModule->sync_closureAllocatorAXI_0.bind(*iconnectMem_.target_socket(67));
        myModule->sync_closureAllocatorAXI_1.bind(*iconnectMem_.target_socket(68));
        myModule->sync_closureAllocatorAXI_2.bind(*iconnectMem_.target_socket(69));
        myModule->sync_closureAllocatorAXI_3.bind(*iconnectMem_.target_socket(70));
        myModule->sync_argumentNotifierAXI_0.bind(*iconnectMem_.target_socket(71));
        myModule->sync_argumentNotifierAXI_1.bind(*iconnectMem_.target_socket(72));
        myModule->sync_argumentNotifierAXI_2.bind(*iconnectMem_.target_socket(73));
        myModule->sync_argumentNotifierAXI_3.bind(*iconnectMem_.target_socket(74));
        myModule->sync_argumentNotifierAXI_4.bind(*iconnectMem_.target_socket(75));
        myModule->sync_argumentNotifierAXI_5.bind(*iconnectMem_.target_socket(76));
        myModule->sync_argumentNotifierAXI_6.bind(*iconnectMem_.target_socket(77));
        myModule->sync_argumentNotifierAXI_7.bind(*iconnectMem_.target_socket(78));
        myModule->sync_argumentNotifierAXI_8.bind(*iconnectMem_.target_socket(79));
        myModule->sync_argumentNotifierAXI_9.bind(*iconnectMem_.target_socket(80));
        myModule->sync_argumentNotifierAXI_10.bind(*iconnectMem_.target_socket(81));
        myModule->sync_argumentNotifierAXI_11.bind(*iconnectMem_.target_socket(82));
        myModule->sync_argumentNotifierAXI_12.bind(*iconnectMem_.target_socket(83));
        myModule->sync_argumentNotifierAXI_13.bind(*iconnectMem_.target_socket(84));
        myModule->sync_argumentNotifierAXI_14.bind(*iconnectMem_.target_socket(85));
        myModule->sync_argumentNotifierAXI_15.bind(*iconnectMem_.target_socket(86));
        myModule->sync_argumentNotifierAXI_16.bind(*iconnectMem_.target_socket(87));
        myModule->sync_argumentNotifierAXI_17.bind(*iconnectMem_.target_socket(88));
        myModule->sync_argumentNotifierAXI_18.bind(*iconnectMem_.target_socket(89));
        myModule->sync_argumentNotifierAXI_19.bind(*iconnectMem_.target_socket(90));
        myModule->sync_argumentNotifierAXI_20.bind(*iconnectMem_.target_socket(91));
        myModule->sync_argumentNotifierAXI_21.bind(*iconnectMem_.target_socket(92));
        myModule->sync_argumentNotifierAXI_22.bind(*iconnectMem_.target_socket(93));
        myModule->sync_argumentNotifierAXI_23.bind(*iconnectMem_.target_socket(94));
        myModule->sync_argumentNotifierAXI_24.bind(*iconnectMem_.target_socket(95));
        myModule->sync_argumentNotifierAXI_25.bind(*iconnectMem_.target_socket(96));
        myModule->sync_argumentNotifierAXI_26.bind(*iconnectMem_.target_socket(97));
        myModule->sync_argumentNotifierAXI_27.bind(*iconnectMem_.target_socket(98));
        myModule->sync_argumentNotifierAXI_28.bind(*iconnectMem_.target_socket(99));
        myModule->sync_argumentNotifierAXI_29.bind(*iconnectMem_.target_socket(100));
        myModule->sync_argumentNotifierAXI_30.bind(*iconnectMem_.target_socket(101));
        myModule->sync_argumentNotifierAXI_31.bind(*iconnectMem_.target_socket(102));


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

    quickSort * myModule;
    sctlm::tlm_lib::modules::memory memory_;
    sctlm::tlm_lib::modules::iconnect iconnectMem_;
    sctlm::tlm_lib::modules::iconnect iconnectPEMgmt_;
    sctlm::tlm_lib::drivers::memory_interface memoryDriverMemory_;
    sctlm::tlm_lib::drivers::memory_interface memoryDriverManagement_;
    sctlm::tlm_lib::drivers::memory_interface peMgmtDriver_; // Connected but not used

    TlmMemory mem_;
    sc_signal<bool> reset_;
    sc_clock clock_;
    quickSortDriver driver_;
};

#endif
        