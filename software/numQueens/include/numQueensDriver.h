
#pragma once

#include <hardCilkDriver.h>
#include <chrono> // Include chrono for high-resolution clock
#include <vector>
#include <algorithm> // for std::shuffle
#include <random>    // for std::random_device and std::mt19937
#include <thread>
#include <chrono>

// Define the struct for the qsort arguments
struct nqueens_args
{
    // The continuation address
    uint64_t cont;
    // The arguments
    uint64_t a;
    uint64_t ret_addr;
    uint8_t n;
    uint8_t j;
    uint8_t __padding[6];
};

struct cont_args
{
    // The counter
    uint32_t counter;
    // The arguments
    uint8_t n;
    uint8_t __padding0[3];
    uint64_t cont;
    uint64_t count;
    uint64_t ret_addr;
};

class numQueensDriver : public hardCilkDriver
{
public:
    numQueensDriver(Memory *memory) : hardCilkDriver(memory) {}

    int run_test_bench() override
    {
        const int N = 16;
        // Create a sync_args and write it to the FPGA
        cont_args cont_args;
        cont_args.counter = 0;
        cont_args.n = 0;
        cont_args.cont = 0;
        cont_args.count = 0;
        cont_args.ret_addr = 0;

        uint64_t addr = allocateMemFPGA(sizeof(cont_args), sizeof(cont_args));
        memory_->copyToDevice(addr, reinterpret_cast<const uint8_t *>(&cont_args), sizeof(cont_args));

        std::cout << addr << std::endl;

        // Create the qsort base task
        nqueens_args base_task_data;
        base_task_data.cont = addr;
        base_task_data.a = allocateMemFPGA(N * sizeof(uint32_t), sizeof(uint32_t));
        base_task_data.ret_addr = addr + 4;
        base_task_data.n = 4;
        base_task_data.j = 0;

        // Create the array
        std::vector<uint32_t> a(N);
        for (int i = 0; i < N; i++)
            a[i] = 0;
        memory_->copyToDevice(base_task_data.a, reinterpret_cast<const uint8_t *>(a.data()), N * sizeof(uint32_t));

        // Measure time for initializing the system
        auto start_init = std::chrono::high_resolution_clock::now();
        std::vector<nqueens_args> base_task_data_vector;
        base_task_data_vector.push_back(base_task_data);
        initSystem(base_task_data_vector);
        auto end_init = std::chrono::high_resolution_clock::now();
        std::chrono::duration<double> init_duration = end_init - start_init;
        std::cout << "Time taken by init_system: " << init_duration.count() << " seconds" << std::endl;

        // Start the system
        startSystem();

        // Measure time for the management loop
        auto start_management = std::chrono::high_resolution_clock::now();
        managementLoop();
        auto end_management = std::chrono::high_resolution_clock::now();
        std::chrono::duration<double> management_duration = end_management - start_management;
        std::cout << "Time taken by management_loop: " << management_duration.count() << " seconds" << std::endl;

        // Copy results from the FPGA
        std::cout << "Copying results from the FPGA" << std::endl;
        uint32_t result;
        memory_->copyFromDevice(reinterpret_cast<uint8_t *>(&result), addr+4, sizeof(int32_t));
        std::cout << "Result: " << result << std::endl;

        return 0;
    }
};
