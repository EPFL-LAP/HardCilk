#pragma once

#include <mFpgaHardCilkDriver.h>
#include <stdio.h>
#define DEBUG_LINE printf("line %d\n", __LINE__);



// Define the struct for the fib arguments
struct fib_task {
  uint64_t cont;
  uint32_t n;
  uint8_t _padding[20];
};

// Define the struct for the sum arguments
struct sum_args {
  uint32_t _counter;
  uint32_t f1;
  uint64_t cont;
  uint32_t f2;
  uint8_t _padding[12];
};

bool condition(int32_t val)
{
    return val != 0;
}

class fibonacciDriver: public mFpgaHardCilkDriver
{
    public:
    
    fibonacciDriver(std::vector<Memory *> memories): mFpgaHardCilkDriver(memories) {}

    int run_test_bench_mFpga() override {
        sum_args sum_args_0 = {0, 0, 0, 0, 0, 0};
        uint64_t addr =  memories_[0]->allocateMemFPGA(sizeof(sum_args_0), sizeof(sum_args_0));
        
        memories_[0]->copyToDevice(addr, reinterpret_cast<const uint8_t*>(&sum_args_0), sizeof(sum_args_0));

        // Create the fib base task using fib args
        fib_task args = {addr+0x4, 15, {0}};

        std::vector<fib_task> base_task_data = {args};
        
        // Initialize the system
        initSystemMfpga(base_task_data, &condition);

        startSystemMfpga();
        
        // Run the management loop
        managementLoopMfpga();

        // read the result from the FPGA at addr + 0x4 (Reading is done in 64-bit chunks)
        uint64_t result = 0;
        memories_[0]->copyFromDevice(reinterpret_cast<uint8_t*>(&result), addr, sizeof(result)); 
        result = result >> 32; // Shift to get the result from the sum_args structure
        std::cout << "Result: " << result << std::endl;

        return 0;
    }

};