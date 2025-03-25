#pragma once

#include <mFpgaHardCilkDriver.h>
#include <stdio.h>
#include <chrono>
#include <systemc.h>
#include "FullSysGenDescriptor.h"

#define DEBUG_LINE printf("line %d\n", __LINE__);

extern uint32_t _exp1_baseDepth;
extern uint32_t _exp1_branchFactor;
extern uint32_t _exp1_initCount;
extern uint32_t _exp1_delay;

struct task
{
    uint32_t delay;
    uint32_t depth;
    uint32_t branchFactor;
    uint32_t cont; // fake cont
};

int remainingTasks;
double T1 = 0; 
int nodesProcessed = 0;

class paper_exp1Driver : public mFpgaHardCilkDriver
{
public:
    paper_exp1Driver(std::vector<Memory *> memories) : mFpgaHardCilkDriver(memories) {}

    int run_test_bench_mFpga() override { return 0; }

    int run_test_bench_mFpga(uint64_t &taskCreatedCounter, uint64_t &taskConsumedCounter)
    {
        task task_args_0 = {0, 0, 0, 0};
        uint64_t addr = allocateMemFPGA_Mfpga(sizeof(task_args_0), 512);
        // uint64_t addr= 0x0;

        memories_[0]->copyToDevice(addr, (uint8_t *)&task_args_0, sizeof(task_args_0));

        task_args_0.branchFactor = _exp1_branchFactor;
        task_args_0.delay = _exp1_delay; // delay in cycles, a cycle is 2ns

        std::vector<task> base_task_data;

        for (int i = 0; i < _exp1_initCount; i++)
        {
            task_args_0.depth = _exp1_baseDepth + i;
            base_task_data.push_back(task_args_0);
        }

        remainingTasks = _exp1_initCount;

        initSystemMfpga(base_task_data);

        startSystemMfpga();

        auto T_START = sc_time_stamp();
        // Log T_START
        std::cout << "T_START: " << T_START << std::endl;

        const int logFreq = 10000;
        while (remainingTasks > 0)
        {
            wait(task_args_0.delay * 2, SC_NS);
            if(nodesProcessed % logFreq == 0){
                std::cout << "nodesProcessed: " << nodesProcessed << std::endl;
            }

        }


        

        auto T_END = sc_time_stamp();

        // Log T_END
        std::cout << "T_END: " << T_END << std::endl;


        // Calculate T_n in nanoseconds
        FullSysGenDescriptor desc;
        int totalPEs = desc.taskDescriptors[0].numProcessingElements * desc.getFpgaCount();

        
        double T_n_perfect = T1 / totalPEs;
        double T_n = (T_END - T_START).to_seconds();

        // Log T_n_perfect
        std::cout << "T_n_perfect: " << T_n_perfect << std::endl;

        // Log T_n
        std::cout << "T_n: " << T_n << std::endl;

        // Log efficiency T_n_perfect / T_n * 100 % to the nearest 100th
        std::cout << "Efficiency: " << (T_n_perfect / T_n) * 100 << "%" << std::endl;

        return 0;
    }
};