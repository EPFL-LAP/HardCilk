#pragma once

#include <hardCilkDriver.h>

// Define the struct for the fib arguments
struct fib_args {
    // The continuation address
    uint64_t returnAddress;
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
    uint64_t returnAddress;
};

class fibonacciDriver: public hardCilkDriver
{
    public:
    
    fibonacciDriver(Memory * memory): hardCilkDriver(memory) {}

    int run_test_bench() override {
        // Create a sum_args and write it to the FPGA
        sum_args sum_args = {2, 0, 0, 0, 0, 0};
        uint64_t addr = allocateMemFPGA(sizeof(sum_args), sizeof(sum_args));
        memory_->copyToDevice(addr, reinterpret_cast<const uint8_t*>(&sum_args), sizeof(sum_args));

        // Create the fib base task using fib args
        fib_args args = {addr+0x4, 20};
        std::vector<fib_args> base_task_data = {args};
        
        // Initialize the system
        init_system(base_task_data);

        // Start the system
        start_system();
        
        // Run the management loop
        management_loop();

        return 0;
    }

};