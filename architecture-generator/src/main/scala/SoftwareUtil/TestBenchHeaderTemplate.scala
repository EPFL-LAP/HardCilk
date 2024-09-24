package SoftwareUtil

import Descriptors._
import java.io.PrintWriter

object TestBenchHeaderTemplate {
  def generateCppHeader(descriptor: FullSysGenDescriptor, headerFileDirectory: String, reduceAxi: Boolean): Unit = {

    val headerContent =
      s"""
        |#ifndef TESTBENCH_H
        |#define TESTBENCH_H
        |
        |
        |#include <${descriptor.name}.hpp>
        |#include <FullSysGenDescriptor.h>
        |#include <systemc>
        |#include <tlm>
        |
        |#include <sctlm/tlm_lib/drivers/memory.hpp>
        |#include <sctlm/tlm_lib/modules/iconnect.hpp>
        |#include <sctlm/tlm_lib/modules/memory.hpp>
        |
        |#include <fmt/format.h>
        |
        |using namespace sc_core;
        |using namespace sc_dt;
        |
        | #include <${descriptor.name}Driver.h>
        | #include <tlmMemory.h>
        |
        |FullSysGenDescriptor helperDescriptor;
        |// A memory of ${descriptor.memorySizeSim} GB
        |#define memorySize (${descriptor.memorySizeSim}ull * 1024ull * 1024ull * 1024ull) 
        |
        |class TestBench : public sc_module {
        |public:
        |
        |    SC_HAS_PROCESS(TestBench);
        |    TestBench(const sc_module_name& name = "TestBench")
        |        : sc_module(name)
        |        , memory_("memory", sc_time(0, SC_NS), memorySize, 0, nullptr)
        |        , iconnectMem_("iconnectMem", helperDescriptor.getNumberAxiMasters() + 1, 1)
        |        , iconnectPEMgmt_("iconnectPEMgmt", 1, helperDescriptor.getNumberPEsAXISlaves())
        |        , memoryDriverMemory_("memoryDriverMemory")
        |        , memoryDriverManagement_("memoryDriverManagement")
        |        , clock_("clock", sc_time(2, SC_NS))
        |        , mem_(memoryDriverMemory_, memoryDriverManagement_)
        |        , driver_(&mem_)
        |         {
        |
        |        myModule = new ${descriptor.name}("myModule");
        |        myModule->reset(reset_);
        |        myModule->clock(clock_);
        |
        |        memoryDriverMemory_.socket.bind(*iconnectMem_.target_socket(0));
        |        peMgmtDriver_.socket.bind(*iconnectPEMgmt_.target_socket(0));
        |
        |        (*iconnectMem_.initiator_socket(0)).bind(memory_.socket);
        |
        |        ${generateAXIConnections(descriptor, reduceAxi)}
        |
        |        iconnectMem_.memmap(0x0000, memorySize, 0);
        |        iconnectPEMgmt_.memmap(0x0000, 0x1000, 0);
        |
        |        memoryDriverManagement_.socket.bind(myModule->s_axil_mgmt_hardcilk);
        |        SC_THREAD(thread);
        |        
        |    }
        |
        |    void thread(){
        |       reset_.write(true);
        |       wait(10.0, SC_NS);
        |       reset_.write(false);
        |       wait(10.0, SC_NS);
        |       driver_.run_test_bench();
        |    }
        |
        |    ${descriptor.name} * myModule;
        |    sctlm::tlm_lib::modules::memory memory_;
        |    sctlm::tlm_lib::modules::iconnect iconnectMem_;
        |    sctlm::tlm_lib::modules::iconnect iconnectPEMgmt_;
        |    sctlm::tlm_lib::drivers::memory_interface memoryDriverMemory_;
        |    sctlm::tlm_lib::drivers::memory_interface memoryDriverManagement_;
        |    sctlm::tlm_lib::drivers::memory_interface peMgmtDriver_; // Connected but not used
        |
        |    TlmMemory mem_;
        |    sc_signal<bool> reset_;
        |    sc_clock clock_;
        |    ${descriptor.name}Driver driver_;
        |};
        |
        |#endif
        """.stripMargin

    val writer = new PrintWriter(f"$headerFileDirectory/testBench.h")
    writer.write(headerContent)
    writer.close()
  }

  def generateAXIConnections(descriptor: FullSysGenDescriptor, reduceAxi: Boolean): String = {
    var connections = ""
    var i = 1 // for the memory interconnect
    var k = 0 // for the unused PE mgmt interconnect
    for (task <- descriptor.taskDescriptors) {
      for (side <- task.sidesConfigs) {
        val n = if (reduceAxi) 1 else side.numVirtualServers
        if (side.numVirtualServers > 0 && side.sideType == "scheduler") {
          for (l <- 0 until n) {
            connections += s"""        myModule->${task.name}_schedulerAXI_${l}.bind(*iconnectMem_.target_socket(${i}));\n"""
            i += 1
          }
        } else if (side.numVirtualServers > 0 && side.sideType == "allocator") {
          for (l <- 0 until n) {
            connections += s"""        myModule->${task.name}_closureAllocatorAXI_${l}.bind(*iconnectMem_.target_socket(${i}));\n"""
            i += 1
          }
        } else if (side.numVirtualServers > 0 && side.sideType == "argumentNotifier") {
          for (l <- 0 until 2*n) {
            connections += s"""        myModule->${task.name}_argumentNotifierAXI_${l}.bind(*iconnectMem_.target_socket(${i}));\n"""
            i += 1
          }
        }
      }
      if (task.hasAXI)
        for (j <- 0 until task.numProcessingElements) {
          connections += s"""        myModule->${task.name}_${j}_m_axi_gmem.bind(*iconnectMem_.target_socket(${i}));\n"""
          connections += s"""        iconnectPEMgmt_.initiator_socket(${k})->bind(myModule->${task.name}_${j}_s_axi_control);\n"""
          i += 1
          k += 1
        }
    }

    connections
  }
}
