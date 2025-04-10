#pragma once

#include <mFpgaHardCilkDriver.h>
#include <stdio.h>
#include <chrono>
#include <systemc.h>
#include "FullSysGenDescriptor.h"

#include <unordered_set>

#define DEBUG_LINE printf("line %d\n", __LINE__);

extern uint32_t _exp3_baseDepth;
extern uint32_t _exp3_branchFactor;
extern uint32_t _exp3_initCount;
extern uint32_t _exp3_delay;
extern uint32_t _exp3_serialTasks;

struct task {
    uint32_t counter;
    uint32_t depth;
    uint32_t delay;
    uint32_t branchFactor;
    uint32_t tag;
    uint32_t index;
    uint64_t cont;
  };


int remainingTasks;
double T1 = 0; 
int nodesProcessed = 0;

int tasksSpawned = 0;

int tasksSpawnedNext = 0;
int tasksNotifiedFromA = 0;
int tasksNotifiedFromB = 0;

std::unordered_set <uint32_t> uniqueTags;
int uniqueTagsCount = 0;

class paper_exp3Driver : public mFpgaHardCilkDriver
{
public:
    paper_exp3Driver(std::vector<Memory *> memories) : mFpgaHardCilkDriver(memories) {}

    int run_test_bench_mFpga() override { return 0; }

    int run_test_bench_mFpga(uint64_t &taskCreatedCounter, uint64_t &taskConsumedCounter)
    {
        task task_args_0 = {0, 0, 0, 0, 0, 0, 0};
        uint64_t addr = allocateMemFPGA_Mfpga(sizeof(task_args_0), 512);
        // uint64_t addr= 0x0;

        memories_[0]->copyToDevice(addr, (uint8_t *)&task_args_0, sizeof(task_args_0));

        task_args_0.branchFactor = _exp3_branchFactor;
        task_args_0.delay = _exp3_delay; // delay in cycles, a cycle is 2ns
        //task_args_0.serialTasks = SERIAL_TASKS;
        task_args_0.index = 0;
        task_args_0.counter = 0;    
        task_args_0.cont = addr;

        std::vector<task> base_task_data;

        for (int i = 0; i < _exp3_initCount; i++)
        {
            task_args_0.depth = _exp3_baseDepth + i;
            task_args_0.tag = uniqueTagsCount++;
            base_task_data.push_back(task_args_0);
        }

        initSystemMfpga(base_task_data);

        startSystemMfpga();

        auto T_START = sc_time_stamp();
        // Log T_START
        std::cout << "T_START: " << T_START << std::endl;


        remainingTasks = _exp3_initCount;

        const int logFreq = 10000;

        // Measure simulation wall time start 
        auto start = std::chrono::high_resolution_clock::now();

        while (remainingTasks > 0)
        {
            wait(10000, SC_NS);
            if(nodesProcessed % logFreq == 0){
                std::cout << "nodesProcessed: " << nodesProcessed << std::endl;
            }
            // std::cout << "nodesProcessed: " << nodesProcessed << std::endl;
            // std::cout << "remainingTasks: " << remainingTasks << std::endl;
            // std::cout << "tasksSpawnedNext: " << tasksSpawnedNext << std::endl;
            // std::cout << "tasksNotifiedFromA: " << tasksNotifiedFromA << std::endl;
            // std::cout << "tasksNotifiedFromB: " << tasksNotifiedFromB << std::endl;
            // // total notified
            // std::cout << "total notified: " << tasksNotifiedFromA + tasksNotifiedFromB << std::endl;
            // std::cout << "tasksSpawned: " << tasksSpawned << std::endl;

        }

        auto T_END = sc_time_stamp();
        // Log T_END
        std::cout << "T_END: " << T_END << std::endl;

        std::cout << "nodesProcessed: " << nodesProcessed << std::endl;
        std::cout << "remainingTasks: " << remainingTasks << std::endl;
        std::cout << "tasksSpawnedNext: " << tasksSpawnedNext << std::endl;
        std::cout << "tasksNotifiedFromA: " << tasksNotifiedFromA << std::endl;
        std::cout << "tasksNotifiedFromB: " << tasksNotifiedFromB << std::endl;
        // total notified
        std::cout << "total notified: " << tasksNotifiedFromA + tasksNotifiedFromB << std::endl;
        std::cout << "tasksSpawned: " << tasksSpawned << std::endl;

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


        // Measure simulation wall time end
        auto end = std::chrono::high_resolution_clock::now();

        // Calculate simulation wall time in minutes
        auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(end - start);
        std::cout << "Simulation wall time: " << duration.count() / 60000.0 << " minutes" << std::endl;

        assert(remainingTasks == 0);

        return 0;
    }
};