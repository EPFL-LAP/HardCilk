package SoftwareUtil

import Descriptors._
import java.io.PrintWriter
import os.read

object TestBenchHeaderTemplate {
  def generateCppHeader(descriptor: FullSysGenDescriptor, headerFileDirectory: String, reduceAxi: Int, memLatency:Int = 70): Unit = {

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
        |#include <sysc_netw/MfpgaSwitch.hpp>
        |
        |${generateInludes(descriptor)}
        |
        |
        |#include <fmt/format.h>
        |
        |using namespace sc_core;
        |using namespace sc_dt;
        |
        | #include <${descriptor.name}Driver.h>
        | #include <memIO_tlm.h>
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
        |        , iconnectPEMgmt_("iconnectPEMgmt", 1, helperDescriptor.getNumberPEsAXISlaves() * helperDescriptor.getFpgaCount())
        |        , clock_("clock", sc_time(2, SC_NS))
        |        ${generateClassCall(descriptor, memLatency)}
        |         {
        |
        |        myModule = new ${descriptor.name}("myModule");
        |        myModule->reset(reset_);
        |        myModule->clock(clock_);
        |        myModule->paused(paused_);
        |        myModule->done(done_);
        |
        |        ${generateMfpgaMemoryBidinings(descriptor)}
        |
        |
        |        // The pe management driver is connected but not used
        |        peMgmtDriver_.socket.bind(*iconnectPEMgmt_.target_socket(0));
        |
        |
        |        ${generateAXIConnections(descriptor, reduceAxi)}
        |
        |        std::vector<chext_test::elastic::Sink<axis4_mFPGA_Signals>*> fpgaSinks;
        |        std::vector<chext_test::elastic::Source<axis4_mFPGA_Signals>*> fpgaSources;
        |
        |        
        |        ${generateMFPGASinkAndSource(descriptor)}
        |
        |        sysc_netw::MfpgaSwitchConfig config;
        |                config.numNodes = ${descriptor.fpgaCount};
        |                config.switchPeriod = sc_time(500, SC_NS);
        |                config.linkToSwitchDelay = sc_time(500, SC_NS);
        |                config.linkToSwitchPeriod = sc_time(8, SC_NS);
        |                config.linkToFpgaDelay = sc_time(500, SC_NS);
        |                config.linkToFpgaPeriod = sc_time(8, SC_NS);
        |
        |        switch_ = std::make_unique<sysc_netw::MfpgaSwitch>("switch", config, std::move(fpgaSinks), std::move(fpgaSources));
        |
        |
        |        SC_THREAD(thread);
        |        SC_THREAD(readRegister);
        |        
        |    }
        |
        |    ${generateReadRegister(descriptor)}
        |
        |    std::unique_ptr<sysc_netw::MfpgaSwitch> switch_;
        |
        |    void thread(){
        |       reset_.write(true);
        |       wait(10.0, SC_NS);
        |       reset_.write(false);
        |       wait(10.0, SC_NS);
        |       driver_.run_test_bench_mFpga(taskCreatedCounter, taskConsumedCounter);
        |       sc_stop();
        |    }
        |
        |    ${descriptor.name} * myModule;
        |
        |    ${generateMfpgaMemoriesAndIct(descriptor)}
        |
        |    sctlm::tlm_lib::modules::iconnect iconnectPEMgmt_; // single interconnect mgmt for all PEs
        |    sctlm::tlm_lib::drivers::memory_interface peMgmtDriver_; // single master Connected but not used
        |
        |    sc_signal<bool> reset_;
        |    sc_clock clock_;
        |    sc_signal<bool> paused_;
        |    sc_signal<bool> done_;
        |    std::vector<Memory*> memories_;
        |    ${descriptor.name}Driver driver_;
        |};
        |
        |#endif
        """.stripMargin

    val writer = new PrintWriter(f"$headerFileDirectory/testBench.h")
    writer.write(headerContent)
    writer.close()
  }

  def generateInludes(descriptor: FullSysGenDescriptor): String = {
    var includes = ""
    includes += s"""#include "V${descriptor.name}_${descriptor.name}.h"\n"""
    for(fpgaIndex <- 0 until descriptor.fpgaCount){
      includes += s"""#include "V${descriptor.name}_${descriptor.name}_${fpgaIndex}.h"\n"""
    }
    includes += s"""#include "V${descriptor.name}_Scheduler.h"\n"""
    includes += s"""#include "V${descriptor.name}_Counter64.h"\n"""

    // if the number of tasks is more than 2, incloude Scheduler_n where n is the number of tasks -1 in a for loop 
    // this is for exposing counters of paper_exp2
    if(descriptor.taskDescriptors.length > 1){
      for(i <- 1 until descriptor.taskDescriptors.length){
        includes += s"""#include "V${descriptor.name}_Scheduler_${i}.h"\n"""
      }
    }
  

    includes
  }

  def getSchedulerMap(descriptor: FullSysGenDescriptor): Map[String, String] = {
    var schedulerMap = Map[String, String]()
    for(i <- 0 until descriptor.taskDescriptors.length){
      if(i == 0){
        schedulerMap += (descriptor.taskDescriptors(i).name -> "Scheduler")
      }else{
        schedulerMap += (descriptor.taskDescriptors(i).name -> s"Scheduler_${i}")
      }
    }
    schedulerMap
  }

  def generateReadRegister(descriptor: FullSysGenDescriptor): String = {
    var readRegister = ""
    readRegister += s"""    void readRegister(){\n"""
    readRegister += s"""        while(true){\n"""
    readRegister += s"""            wait(32, SC_NS);\n"""
    readRegister += s"""            V${descriptor.name} * verilatedModule = (V${descriptor.name} *) myModule->getVerilatedModule();\n"""

    readRegister += s"""
            taskCreatedCounter = 0;
            taskConsumedCounter = 0;
        """

    // task type 0 -> "Scheduler", task type 1 -> "Scheduler_1", task type 2 -> "Scheduler_2", ...
    // create a map that stores the above mapping
    val taskTypeMap = getSchedulerMap(descriptor)

    for(fpgaIndex <- 0 until descriptor.fpgaCount){
      for(task <- descriptor.taskDescriptors){
        
        readRegister += s"""            taskConsumedCounter += verilatedModule->${descriptor.name}->${descriptor.name}_${fpgaIndex}->${taskTypeMap.get(task.name).get}->taskOutCounter->counter;\n"""
        if(descriptor.selfSpawnedCount(task.name) > 0){
          readRegister += s"""            taskCreatedCounter += verilatedModule->${descriptor.name}->${descriptor.name}_${fpgaIndex}->${taskTypeMap.get(task.name).get}->taskInCounter->counter;\n"""
        }
      }
    }
    readRegister += s"""        }\n"""
    readRegister += s"""    }\n"""
    readRegister += s"""    uint64_t taskCreatedCounter = 0;\n"""
    readRegister += s"""    uint64_t taskConsumedCounter = 0;\n"""
    readRegister
  }

  def generateMFPGASinkAndSource(descriptor: FullSysGenDescriptor): String = {
    var connections = ""
    for(fpgaIndex <- 0 until descriptor.fpgaCount){
      connections += s"""        fpgaSinks.push_back(&myModule->m_axis_mFPGA_${fpgaIndex});\n"""
      connections += s"""        fpgaSources.push_back(&myModule->s_axis_mFPGA_${fpgaIndex});\n"""
    }
    connections
  }

  def generateClassCall(descriptor: FullSysGenDescriptor, memLatency:Int): String = {
    var instantiations = ""
    var memList = ""
    for(fpgaIndex <- 0 until descriptor.fpgaCount){
      instantiations += s"""        , memory_${fpgaIndex}_("memory_${fpgaIndex}", sc_time(${memLatency}, SC_NS), memorySize, 0, nullptr)\n"""
      instantiations += s"""        , iconnectMem_${fpgaIndex}_("iconnectMem_${fpgaIndex}", helperDescriptor.getNumberAxiMasters()+1, 1)\n"""
      instantiations += s"""        , memoryDriverMemory_${fpgaIndex}_("memoryDriverMemory_${fpgaIndex}")\n"""
      instantiations += s"""        , memoryDriverManagement_${fpgaIndex}_("memoryDriverManagement_${fpgaIndex}")\n"""
      instantiations += s"""        , mem_${fpgaIndex}_(memoryDriverMemory_${fpgaIndex}_, memoryDriverManagement_${fpgaIndex}_)\n"""
      memList += s"""&mem_${fpgaIndex}_"""
      if(fpgaIndex < descriptor.fpgaCount - 1){
        memList += ", "
      }
    }
    instantiations += s"""        , memories_({${memList}})\n"""

    instantiations += s"""        , driver_(memories_)\n"""

    instantiations
  }

  def generateMfpgaMemoriesAndIct(descriptor: FullSysGenDescriptor): String = {
    var instantiations = ""

    for(fpgaIndex <- 0 until descriptor.fpgaCount) {
      instantiations += s"""        sctlm::tlm_lib::modules::memory memory_${fpgaIndex}_;\n"""
      instantiations += s"""        sctlm::tlm_lib::modules::iconnect iconnectMem_${fpgaIndex}_;\n"""
      instantiations += s"""        sctlm::tlm_lib::drivers::memory_interface memoryDriverMemory_${fpgaIndex}_;\n"""
      instantiations += s"""        sctlm::tlm_lib::drivers::memory_interface memoryDriverMemory_${fpgaIndex}_toDropXDMAandHaveDirectConnection;\n"""
      instantiations += s"""        sctlm::tlm_lib::drivers::memory_interface memoryDriverManagement_${fpgaIndex}_;\n"""
      instantiations += s"""        TlmMemory mem_${fpgaIndex}_;\n"""
    }

    instantiations
  }

  def generateMfpgaMemoryBidinings(descriptor: FullSysGenDescriptor): String = {
    var bindings = ""

    for(fpgaIndex <- 0 until descriptor.fpgaCount) {
      // memmap for the memory and the system management
      bindings += s"""        iconnectMem_${fpgaIndex}_.memmap(0x0000, memorySize, 0);\n"""

      // Connect the data interconnect to the corresponding memory
      bindings += s"""        (*iconnectMem_${fpgaIndex}_.initiator_socket(0)).bind(memory_${fpgaIndex}_.socket);\n"""

      // Connect the s_axi_xdma slave from our driver simulation to the memory
      bindings += s"""        memoryDriverMemory_${fpgaIndex}_.socket.bind(*iconnectMem_${fpgaIndex}_.target_socket(helperDescriptor.getNumberAxiMasters()));\n"""

      bindings += s"""        memoryDriverMemory_${fpgaIndex}_toDropXDMAandHaveDirectConnection.socket.bind(myModule->s_axi_xdma_${fpgaIndex});\n"""

      // Connect the s_axil_mgmt_hardcilk slave from our driver simulation to system management register files
      bindings += s"""        memoryDriverManagement_${fpgaIndex}_.socket.bind(myModule->s_axil_mgmt_hardcilk_${fpgaIndex});\n"""
    }

    bindings
  }


  def generateAXIConnections(descriptor: FullSysGenDescriptor, reduceAxi: Int): String = {
    var connections = ""
    var i = 0 // for the memory interconnect
    var k = 0 // for the unused PE mgmt interconnect

    for(fpgaIndex <- 0 until descriptor.fpgaCount) {
      i = 0

      for (j <- 0 until reduceAxi) {
        val portName = f"m_axi_${j}%02d_${fpgaIndex}"
        connections += s"""        myModule->${portName}.bind(*iconnectMem_${fpgaIndex}_.target_socket(${i}));\n"""
        i += 1
      }

      var slave_index = 0
      for (task <- descriptor.taskDescriptors) {
        if (task.hasAXI) {
          for (j <- 0 until task.numProcessingElements) {
            connections += f"""        iconnectPEMgmt_.initiator_socket(${k})->bind(myModule->task_s_axi_control_${slave_index}%02d_${fpgaIndex});\n"""
            k += 1
            slave_index += 1
          }
        }
      }

    }

    connections
  }
}
