#pragma once

#include "FullSysGenDescriptor.h"
#include <chrono>
#include <mFpgaHardCilkDriver.h>
#include <stdio.h>
#include <systemc.h>

#define DEBUG_LINE printf("line %d\n", __LINE__);

extern uint32_t _exp2_baseDepth;
extern uint32_t _exp2_branchFactor;
extern uint32_t _exp2_initCount;
extern uint32_t _exp2_delay;


struct task_0 {
    uint32_t delay;
    uint32_t depth;
    uint32_t branchFactor;
    uint32_t cont; // fake conts
  };
  
struct task_1 {
    uint32_t delay;
};


int remainingTasks;
double T1 = 0; 
int nodesProcessed = 0;


class paper_exp2Driver : public mFpgaHardCilkDriver {
public:
  paper_exp2Driver(std::vector<Memory *> memories)
      : mFpgaHardCilkDriver(memories) {}

  int run_test_bench_mFpga() override { return 0; }

  int run_test_bench_mFpga(uint64_t &taskCreatedCounter,
                           uint64_t &taskConsumedCounter) {
    task_0 task_args_0 = {0, 0, 0, 0};
    uint64_t addr = allocateMemFPGA_Mfpga(sizeof(task_args_0), 512);

    memories_[0]->copyToDevice(addr, (uint8_t *)&task_args_0,
                               sizeof(task_args_0));

    task_args_0.branchFactor = _exp2_branchFactor;
    task_args_0.delay = _exp2_delay; // delay in cycles, a cycle is 2ns

    std::vector<task_0> base_task_data;

    for (int i = 0; i < _exp2_initCount; i++) {
      task_args_0.depth = _exp2_baseDepth + i;
      base_task_data.push_back(task_args_0);
    }

    remainingTasks = _exp2_initCount;

    initSystemMfpga(base_task_data);

    startSystemMfpga();

    auto T_START = sc_time_stamp();
    // Log T_START
    std::cout << "T_START: " << T_START << std::endl;

    const int logFreq = 10000;
    int nodesProcessedHistory = 0;
    while (remainingTasks > 0)
    {
        wait(task_args_0.delay * 2, SC_NS);

        if(nodesProcessed - nodesProcessedHistory > logFreq){
          std::cout << "nodesProcessed: " << nodesProcessed << std::endl;
          std::cout << "remainingTasks: " << remainingTasks << std::endl;
          nodesProcessedHistory = nodesProcessed;
        }
    }
    std::cout << "nodesProcessed: " << nodesProcessed << std::endl;


    auto T_END = sc_time_stamp();

    // Log T_END
    std::cout << "T_END: " << T_END << std::endl;

 

    // Calculate T_n in nanoseconds
    FullSysGenDescriptor desc;
    int totalPEs =
        desc.taskDescriptors[0].numProcessingElements * desc.getFpgaCount();

    double T_n_perfect = T1 / totalPEs;
    double T_n = (T_END - T_START).to_seconds();

    // Log T_n_perfect
    std::cout << "T_n_perfect: " << T_n_perfect << std::endl;

    // Log T_n
    std::cout << "T_n: " << T_n << std::endl;

    // Log task delay
    std::cout << "task delay (for correct efficiency calculation): " << task_args_0.delay << std::endl;

    // Log efficiency T_n_perfect / T_n * 100 % to the nearest 100th
    std::cout << "Efficiency (Not final!): " << (T_n_perfect / T_n) * 100 << "%"
              << std::endl;

    return 0;
  }
};