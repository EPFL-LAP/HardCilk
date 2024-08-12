
#ifndef TESTBENCH_H
#define TESTBENCH_H


#include <fibonacci.hpp>
#include <FullSysGenDescriptor.h>
#include <systemc>
#include <tlm>

#include <sctlm/tlm_lib/drivers/memory.hpp>
#include <sctlm/tlm_lib/modules/iconnect.hpp>
#include <sctlm/tlm_lib/modules/memory.hpp>

#include <fmt/format.h>

using namespace sc_core;
using namespace sc_dt;


FullSysGenDescriptor helperDescriptor;
// A memory of 1 GB
#define memorySize 1 * 1024 * 1024 * 1024 

class TestBench : public sc_module {
public:

    TestBench(const sc_module_name& name = "TestBench")
        : sc_module(name)
        , memory_("memory", sc_time(0, SC_NS), memorySize, 0, nullptr)
        , iconnectMem_("iconnectMem", helperDescriptor.getNumberAxiMasters() + 1, 1)
        , iconnectPEMgmt_("iconnectPEMgmt", 1, helperDescriptor.getNumberPEsAXISlaves())
        , memoryDriverMemory_("memoryDriverMemory")
        , memoryDriverManagement_("memoryDriverManagement")
        , clock_("clock", sc_time(2, SC_NS)) {

        myModule = new fibonacci("myModule");
        myModule->reset(reset_);
        myModule->clock(clock_);

        memoryDriverMemory_.socket.bind(*iconnectMem_.target_socket(0));
        peMgmtDriver_.socket.bind(*iconnectPEMgmt_.target_socket(0));

        (*iconnectMem_.initiator_socket(0)).bind(memory_.socket);

                myModule->fib_schedulerAXI.bind(*iconnectMem_.target_socket(86));
        myModule->fib_0_m_axi_gmem.bind(*iconnectMem_.target_socket(1));
        iconnectPEMgmt_.initiator_socket(0)->bind(myModule->fib_0_s_axi_control);
        myModule->fib_1_m_axi_gmem.bind(*iconnectMem_.target_socket(2));
        iconnectPEMgmt_.initiator_socket(1)->bind(myModule->fib_1_s_axi_control);
        myModule->fib_2_m_axi_gmem.bind(*iconnectMem_.target_socket(3));
        iconnectPEMgmt_.initiator_socket(2)->bind(myModule->fib_2_s_axi_control);
        myModule->fib_3_m_axi_gmem.bind(*iconnectMem_.target_socket(4));
        iconnectPEMgmt_.initiator_socket(3)->bind(myModule->fib_3_s_axi_control);
        myModule->fib_4_m_axi_gmem.bind(*iconnectMem_.target_socket(5));
        iconnectPEMgmt_.initiator_socket(4)->bind(myModule->fib_4_s_axi_control);
        myModule->fib_5_m_axi_gmem.bind(*iconnectMem_.target_socket(6));
        iconnectPEMgmt_.initiator_socket(5)->bind(myModule->fib_5_s_axi_control);
        myModule->fib_6_m_axi_gmem.bind(*iconnectMem_.target_socket(7));
        iconnectPEMgmt_.initiator_socket(6)->bind(myModule->fib_6_s_axi_control);
        myModule->fib_7_m_axi_gmem.bind(*iconnectMem_.target_socket(8));
        iconnectPEMgmt_.initiator_socket(7)->bind(myModule->fib_7_s_axi_control);
        myModule->fib_8_m_axi_gmem.bind(*iconnectMem_.target_socket(9));
        iconnectPEMgmt_.initiator_socket(8)->bind(myModule->fib_8_s_axi_control);
        myModule->fib_9_m_axi_gmem.bind(*iconnectMem_.target_socket(10));
        iconnectPEMgmt_.initiator_socket(9)->bind(myModule->fib_9_s_axi_control);
        myModule->fib_10_m_axi_gmem.bind(*iconnectMem_.target_socket(11));
        iconnectPEMgmt_.initiator_socket(10)->bind(myModule->fib_10_s_axi_control);
        myModule->fib_11_m_axi_gmem.bind(*iconnectMem_.target_socket(12));
        iconnectPEMgmt_.initiator_socket(11)->bind(myModule->fib_11_s_axi_control);
        myModule->fib_12_m_axi_gmem.bind(*iconnectMem_.target_socket(13));
        iconnectPEMgmt_.initiator_socket(12)->bind(myModule->fib_12_s_axi_control);
        myModule->fib_13_m_axi_gmem.bind(*iconnectMem_.target_socket(14));
        iconnectPEMgmt_.initiator_socket(13)->bind(myModule->fib_13_s_axi_control);
        myModule->fib_14_m_axi_gmem.bind(*iconnectMem_.target_socket(15));
        iconnectPEMgmt_.initiator_socket(14)->bind(myModule->fib_14_s_axi_control);
        myModule->fib_15_m_axi_gmem.bind(*iconnectMem_.target_socket(16));
        iconnectPEMgmt_.initiator_socket(15)->bind(myModule->fib_15_s_axi_control);
        myModule->fib_16_m_axi_gmem.bind(*iconnectMem_.target_socket(17));
        iconnectPEMgmt_.initiator_socket(16)->bind(myModule->fib_16_s_axi_control);
        myModule->fib_17_m_axi_gmem.bind(*iconnectMem_.target_socket(18));
        iconnectPEMgmt_.initiator_socket(17)->bind(myModule->fib_17_s_axi_control);
        myModule->fib_18_m_axi_gmem.bind(*iconnectMem_.target_socket(19));
        iconnectPEMgmt_.initiator_socket(18)->bind(myModule->fib_18_s_axi_control);
        myModule->fib_19_m_axi_gmem.bind(*iconnectMem_.target_socket(20));
        iconnectPEMgmt_.initiator_socket(19)->bind(myModule->fib_19_s_axi_control);
        myModule->fib_20_m_axi_gmem.bind(*iconnectMem_.target_socket(21));
        iconnectPEMgmt_.initiator_socket(20)->bind(myModule->fib_20_s_axi_control);
        myModule->fib_21_m_axi_gmem.bind(*iconnectMem_.target_socket(22));
        iconnectPEMgmt_.initiator_socket(21)->bind(myModule->fib_21_s_axi_control);
        myModule->fib_22_m_axi_gmem.bind(*iconnectMem_.target_socket(23));
        iconnectPEMgmt_.initiator_socket(22)->bind(myModule->fib_22_s_axi_control);
        myModule->fib_23_m_axi_gmem.bind(*iconnectMem_.target_socket(24));
        iconnectPEMgmt_.initiator_socket(23)->bind(myModule->fib_23_s_axi_control);
        myModule->fib_24_m_axi_gmem.bind(*iconnectMem_.target_socket(25));
        iconnectPEMgmt_.initiator_socket(24)->bind(myModule->fib_24_s_axi_control);
        myModule->fib_25_m_axi_gmem.bind(*iconnectMem_.target_socket(26));
        iconnectPEMgmt_.initiator_socket(25)->bind(myModule->fib_25_s_axi_control);
        myModule->fib_26_m_axi_gmem.bind(*iconnectMem_.target_socket(27));
        iconnectPEMgmt_.initiator_socket(26)->bind(myModule->fib_26_s_axi_control);
        myModule->fib_27_m_axi_gmem.bind(*iconnectMem_.target_socket(28));
        iconnectPEMgmt_.initiator_socket(27)->bind(myModule->fib_27_s_axi_control);
        myModule->fib_28_m_axi_gmem.bind(*iconnectMem_.target_socket(29));
        iconnectPEMgmt_.initiator_socket(28)->bind(myModule->fib_28_s_axi_control);
        myModule->fib_29_m_axi_gmem.bind(*iconnectMem_.target_socket(30));
        iconnectPEMgmt_.initiator_socket(29)->bind(myModule->fib_29_s_axi_control);
        myModule->fib_30_m_axi_gmem.bind(*iconnectMem_.target_socket(31));
        iconnectPEMgmt_.initiator_socket(30)->bind(myModule->fib_30_s_axi_control);
        myModule->fib_31_m_axi_gmem.bind(*iconnectMem_.target_socket(32));
        iconnectPEMgmt_.initiator_socket(31)->bind(myModule->fib_31_s_axi_control);
        myModule->fib_32_m_axi_gmem.bind(*iconnectMem_.target_socket(33));
        iconnectPEMgmt_.initiator_socket(32)->bind(myModule->fib_32_s_axi_control);
        myModule->fib_33_m_axi_gmem.bind(*iconnectMem_.target_socket(34));
        iconnectPEMgmt_.initiator_socket(33)->bind(myModule->fib_33_s_axi_control);
        myModule->fib_34_m_axi_gmem.bind(*iconnectMem_.target_socket(35));
        iconnectPEMgmt_.initiator_socket(34)->bind(myModule->fib_34_s_axi_control);
        myModule->fib_35_m_axi_gmem.bind(*iconnectMem_.target_socket(36));
        iconnectPEMgmt_.initiator_socket(35)->bind(myModule->fib_35_s_axi_control);
        myModule->fib_36_m_axi_gmem.bind(*iconnectMem_.target_socket(37));
        iconnectPEMgmt_.initiator_socket(36)->bind(myModule->fib_36_s_axi_control);
        myModule->fib_37_m_axi_gmem.bind(*iconnectMem_.target_socket(38));
        iconnectPEMgmt_.initiator_socket(37)->bind(myModule->fib_37_s_axi_control);
        myModule->fib_38_m_axi_gmem.bind(*iconnectMem_.target_socket(39));
        iconnectPEMgmt_.initiator_socket(38)->bind(myModule->fib_38_s_axi_control);
        myModule->fib_39_m_axi_gmem.bind(*iconnectMem_.target_socket(40));
        iconnectPEMgmt_.initiator_socket(39)->bind(myModule->fib_39_s_axi_control);
        myModule->fib_40_m_axi_gmem.bind(*iconnectMem_.target_socket(41));
        iconnectPEMgmt_.initiator_socket(40)->bind(myModule->fib_40_s_axi_control);
        myModule->fib_41_m_axi_gmem.bind(*iconnectMem_.target_socket(42));
        iconnectPEMgmt_.initiator_socket(41)->bind(myModule->fib_41_s_axi_control);
        myModule->fib_42_m_axi_gmem.bind(*iconnectMem_.target_socket(43));
        iconnectPEMgmt_.initiator_socket(42)->bind(myModule->fib_42_s_axi_control);
        myModule->fib_43_m_axi_gmem.bind(*iconnectMem_.target_socket(44));
        iconnectPEMgmt_.initiator_socket(43)->bind(myModule->fib_43_s_axi_control);
        myModule->fib_44_m_axi_gmem.bind(*iconnectMem_.target_socket(45));
        iconnectPEMgmt_.initiator_socket(44)->bind(myModule->fib_44_s_axi_control);
        myModule->fib_45_m_axi_gmem.bind(*iconnectMem_.target_socket(46));
        iconnectPEMgmt_.initiator_socket(45)->bind(myModule->fib_45_s_axi_control);
        myModule->fib_46_m_axi_gmem.bind(*iconnectMem_.target_socket(47));
        iconnectPEMgmt_.initiator_socket(46)->bind(myModule->fib_46_s_axi_control);
        myModule->fib_47_m_axi_gmem.bind(*iconnectMem_.target_socket(48));
        iconnectPEMgmt_.initiator_socket(47)->bind(myModule->fib_47_s_axi_control);
        myModule->fib_48_m_axi_gmem.bind(*iconnectMem_.target_socket(49));
        iconnectPEMgmt_.initiator_socket(48)->bind(myModule->fib_48_s_axi_control);
        myModule->fib_49_m_axi_gmem.bind(*iconnectMem_.target_socket(50));
        iconnectPEMgmt_.initiator_socket(49)->bind(myModule->fib_49_s_axi_control);
        myModule->sum_schedulerAXI.bind(*iconnectMem_.target_socket(51));
        myModule->sum_closureAllocatorAXI.bind(*iconnectMem_.target_socket(52));
        myModule->sum_argumentNotifierAXI.bind(*iconnectMem_.target_socket(53));
        myModule->sum_0_m_axi_gmem.bind(*iconnectMem_.target_socket(54));
        iconnectPEMgmt_.initiator_socket(50)->bind(myModule->sum_0_s_axi_control);
        myModule->sum_1_m_axi_gmem.bind(*iconnectMem_.target_socket(55));
        iconnectPEMgmt_.initiator_socket(51)->bind(myModule->sum_1_s_axi_control);
        myModule->sum_2_m_axi_gmem.bind(*iconnectMem_.target_socket(56));
        iconnectPEMgmt_.initiator_socket(52)->bind(myModule->sum_2_s_axi_control);
        myModule->sum_3_m_axi_gmem.bind(*iconnectMem_.target_socket(57));
        iconnectPEMgmt_.initiator_socket(53)->bind(myModule->sum_3_s_axi_control);
        myModule->sum_4_m_axi_gmem.bind(*iconnectMem_.target_socket(58));
        iconnectPEMgmt_.initiator_socket(54)->bind(myModule->sum_4_s_axi_control);
        myModule->sum_5_m_axi_gmem.bind(*iconnectMem_.target_socket(59));
        iconnectPEMgmt_.initiator_socket(55)->bind(myModule->sum_5_s_axi_control);
        myModule->sum_6_m_axi_gmem.bind(*iconnectMem_.target_socket(60));
        iconnectPEMgmt_.initiator_socket(56)->bind(myModule->sum_6_s_axi_control);
        myModule->sum_7_m_axi_gmem.bind(*iconnectMem_.target_socket(61));
        iconnectPEMgmt_.initiator_socket(57)->bind(myModule->sum_7_s_axi_control);
        myModule->sum_8_m_axi_gmem.bind(*iconnectMem_.target_socket(62));
        iconnectPEMgmt_.initiator_socket(58)->bind(myModule->sum_8_s_axi_control);
        myModule->sum_9_m_axi_gmem.bind(*iconnectMem_.target_socket(63));
        iconnectPEMgmt_.initiator_socket(59)->bind(myModule->sum_9_s_axi_control);
        myModule->sum_10_m_axi_gmem.bind(*iconnectMem_.target_socket(64));
        iconnectPEMgmt_.initiator_socket(60)->bind(myModule->sum_10_s_axi_control);
        myModule->sum_11_m_axi_gmem.bind(*iconnectMem_.target_socket(65));
        iconnectPEMgmt_.initiator_socket(61)->bind(myModule->sum_11_s_axi_control);
        myModule->sum_12_m_axi_gmem.bind(*iconnectMem_.target_socket(66));
        iconnectPEMgmt_.initiator_socket(62)->bind(myModule->sum_12_s_axi_control);
        myModule->sum_13_m_axi_gmem.bind(*iconnectMem_.target_socket(67));
        iconnectPEMgmt_.initiator_socket(63)->bind(myModule->sum_13_s_axi_control);
        myModule->sum_14_m_axi_gmem.bind(*iconnectMem_.target_socket(68));
        iconnectPEMgmt_.initiator_socket(64)->bind(myModule->sum_14_s_axi_control);
        myModule->sum_15_m_axi_gmem.bind(*iconnectMem_.target_socket(69));
        iconnectPEMgmt_.initiator_socket(65)->bind(myModule->sum_15_s_axi_control);
        myModule->sum_16_m_axi_gmem.bind(*iconnectMem_.target_socket(70));
        iconnectPEMgmt_.initiator_socket(66)->bind(myModule->sum_16_s_axi_control);
        myModule->sum_17_m_axi_gmem.bind(*iconnectMem_.target_socket(71));
        iconnectPEMgmt_.initiator_socket(67)->bind(myModule->sum_17_s_axi_control);
        myModule->sum_18_m_axi_gmem.bind(*iconnectMem_.target_socket(72));
        iconnectPEMgmt_.initiator_socket(68)->bind(myModule->sum_18_s_axi_control);
        myModule->sum_19_m_axi_gmem.bind(*iconnectMem_.target_socket(73));
        iconnectPEMgmt_.initiator_socket(69)->bind(myModule->sum_19_s_axi_control);
        myModule->sum_20_m_axi_gmem.bind(*iconnectMem_.target_socket(74));
        iconnectPEMgmt_.initiator_socket(70)->bind(myModule->sum_20_s_axi_control);
        myModule->sum_21_m_axi_gmem.bind(*iconnectMem_.target_socket(75));
        iconnectPEMgmt_.initiator_socket(71)->bind(myModule->sum_21_s_axi_control);
        myModule->sum_22_m_axi_gmem.bind(*iconnectMem_.target_socket(76));
        iconnectPEMgmt_.initiator_socket(72)->bind(myModule->sum_22_s_axi_control);
        myModule->sum_23_m_axi_gmem.bind(*iconnectMem_.target_socket(77));
        iconnectPEMgmt_.initiator_socket(73)->bind(myModule->sum_23_s_axi_control);
        myModule->sum_24_m_axi_gmem.bind(*iconnectMem_.target_socket(78));
        iconnectPEMgmt_.initiator_socket(74)->bind(myModule->sum_24_s_axi_control);
        myModule->sum_25_m_axi_gmem.bind(*iconnectMem_.target_socket(79));
        iconnectPEMgmt_.initiator_socket(75)->bind(myModule->sum_25_s_axi_control);
        myModule->sum_26_m_axi_gmem.bind(*iconnectMem_.target_socket(80));
        iconnectPEMgmt_.initiator_socket(76)->bind(myModule->sum_26_s_axi_control);
        myModule->sum_27_m_axi_gmem.bind(*iconnectMem_.target_socket(81));
        iconnectPEMgmt_.initiator_socket(77)->bind(myModule->sum_27_s_axi_control);
        myModule->sum_28_m_axi_gmem.bind(*iconnectMem_.target_socket(82));
        iconnectPEMgmt_.initiator_socket(78)->bind(myModule->sum_28_s_axi_control);
        myModule->sum_29_m_axi_gmem.bind(*iconnectMem_.target_socket(83));
        iconnectPEMgmt_.initiator_socket(79)->bind(myModule->sum_29_s_axi_control);
        myModule->sum_30_m_axi_gmem.bind(*iconnectMem_.target_socket(84));
        iconnectPEMgmt_.initiator_socket(80)->bind(myModule->sum_30_s_axi_control);
        myModule->sum_31_m_axi_gmem.bind(*iconnectMem_.target_socket(85));
        iconnectPEMgmt_.initiator_socket(81)->bind(myModule->sum_31_s_axi_control);


        iconnectMem_.memmap(0x0000, memorySize, 0);
        iconnectPEMgmt_.memmap(0x0000, 0x1000, 0);

        memoryDriverManagement_.socket.bind(myModule->s_axil_mgmt_hardcilk);
        
    }

    fibonacci * myModule;
    sctlm::tlm_lib::modules::memory memory_;
    sctlm::tlm_lib::modules::iconnect iconnectMem_;
    sctlm::tlm_lib::modules::iconnect iconnectPEMgmt_;
    sctlm::tlm_lib::drivers::memory_interface memoryDriverMemory_;
    sctlm::tlm_lib::drivers::memory_interface memoryDriverManagement_;
    sctlm::tlm_lib::drivers::memory_interface peMgmtDriver_; // Connected but not used

    sc_signal<bool> reset_;
    sc_clock clock_;
};

#endif
        