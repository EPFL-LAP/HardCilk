#pragma once

#include <hardCilkDriver.h>
#include <stdio.h>
#define DEBUG_LINE printf("line %d\n", __LINE__);



// Define the struct for the fib arguments
struct fib_args {
    // The continuation address
    uint64_t cont;
    // The n value
    uint64_t n;
    //uint32_t Padding = 0;
};

// Define the struct for the sum arguments
struct sum_args {
    // The counter
    uint32_t join_counter;

    // The first summand
    int32_t x;

    // The second summand
    int32_t y;

    // Padding 1
    uint32_t pad1 = 0;

    // Padding 2
    uint64_t pad2 = 0;

    // The continuation address
    uint64_t cont;
};

class fibonacciDriver: public hardCilkDriver
{
    public:
    
    fibonacciDriver(Memory * memory): hardCilkDriver(memory) {}

    int run_test_bench() override {
        sum_args sum_args_0 = {2, 0, 0, 0, 0, 0};
        uint64_t addr = allocateMemFPGA(sizeof(sum_args_0), sizeof(sum_args_0));
        memory_->copyToDevice(addr, reinterpret_cast<const uint8_t*>(&sum_args_0), sizeof(sum_args_0));

        // Create the fib base task using fib args
        fib_args args = {addr+0x4, 20 };

        std::vector<fib_args> base_task_data = {args};
        
        // Initialize the system
        init_system(base_task_data);

        startSystem();
        
        // Run the management loop
        managementLoop();

        return 0;
    }

};