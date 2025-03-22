#pragma once

#include <hardCilkDriver.h>
#include <stdio.h>
#include <chrono>

#define DEBUG_LINE printf("line %d\n", __LINE__);



struct task{
    uint32_t delay;
    uint32_t depth;
    uint32_t branchFactor;
    uint32_t cont; // fake cont
};


class paper_exp1Driver : public hardCilkDriver
{
public:
paper_exp1Driver(Memory *memory) : hardCilkDriver(memory) {}

    int run_test_bench() override
    {
        task task_args_0 = {0, 0, 0, 0};
        uint64_t addr = allocateMemFPGA(sizeof(task_args_0), 512);

        memory_->copyToDevice(addr, (uint8_t *)&task_args_0, sizeof(task_args_0));

        task_args_0.branchFactor = 2;
        task_args_0.depth = 10;
        task_args_0.delay = 64;

        std::vector<task> base_task_data = {task_args_0};

        // Log the time in microseconds
        auto start = std::chrono::high_resolution_clock::now();
        initSystem(base_task_data);
        auto end = std::chrono::high_resolution_clock::now();
        std::chrono::duration<double> elapsed = end - start;
        std::cout << "Time taken for init: " << elapsed.count() << "s" << std::endl;


        startSystem();

        // Run the management loop
        start = std::chrono::high_resolution_clock::now();
        managementLoop();
        end = std::chrono::high_resolution_clock::now();
        elapsed = end - start;

        std::cout << "Time taken for management loop: " << elapsed.count() << "s" << std::endl;

        return 0;
    }
};