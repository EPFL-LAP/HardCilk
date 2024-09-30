 
#pragma once

#include <hardCilkDriver.h>
#include <chrono>  // Include chrono for high-resolution clock
#include <vector>
#include <algorithm> // for std::shuffle
#include <random>    // for std::random_device and std::mt19937
#include <thread>
#include <chrono>
struct qsort_args {
    // The continuation address
    uint64_t cont;
    // Array start
    uint64_t begin;
    // Array end
    uint64_t end;
    // Padding
    uint64_t padding;
};

struct sync_args {
    // Counter
    uint32_t join_counter;
    // Padding
    uint32_t padding;
    // The continuation address
    uint64_t cont;
};

// Create unsorted vector of integers of size n that has the numbers between zero and n-1
std::vector<int32_t> createUnsortedVector(int n) {
    std::vector<int32_t> vec(n);

    // Fill vector with numbers from 0 to n-1
    for (int i = 0; i < n; ++i) {
        vec[i] = n - i - 1;
    }


    // Shuffle the vector to unsort it
    std::random_device rd;
    std::mt19937 g(0);
    std::shuffle(vec.begin(), vec.end(), g);

    return vec;
}

class quickSortDriver: public hardCilkDriver
{
    public:

    quickSortDriver(Memory * memory): hardCilkDriver(memory) {}

    int run_test_bench() override {
        int count = 256;
        // Create a sync_args and write it to the FPGA
        sync_args sync_args_0;
        sync_args_0.join_counter = 2;
        sync_args_0.cont = 0;
        sync_args_0.padding = 0;

        uint64_t addr = allocateMemFPGA(sizeof(sync_args_0), sizeof(sync_args_0));
        memory_->copyToDevice(addr, reinterpret_cast<const uint8_t*>(&sync_args_0), sizeof(sync_args_0));

        std::cout <<addr << std::endl;

        // Create the unsorted array and copy it to FPGA
        std::vector<int32_t> data = createUnsortedVector(count);
        uint64_t array_addr = allocateMemFPGA(sizeof(int32_t)*count, sizeof(int32_t));
        memory_->copyToDevice(array_addr, reinterpret_cast<const uint8_t*>(data.data()), count * sizeof(int32_t));

        // Print the array
        for(int i = 0; i< count; i++){
            std::cout << " " << data[i];
        }
        std::cout << std::endl;

        // Create the qsort base task
        qsort_args args = {addr, array_addr, array_addr + sizeof(int32_t)*(count), 0};
        std::vector<qsort_args> base_task_data = {args};

        // Measure time for initializing the system
        auto start_init = std::chrono::high_resolution_clock::now();
        init_system(base_task_data);
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
        std::cout << "Time taken by managementLoop: " << management_duration.count() << " seconds" << std::endl;


        // Copy results from the FPGA
        memory_->copyFromDevice(reinterpret_cast<uint8_t*>(data.data()), array_addr,  sizeof(int32_t) * count);

        // Make sure the array is sorted
        for(auto i = 0; i < count; i++){
            if(i != data[i]){
                std::cout << "Found a number " << data[i] << " at index: " << i << std::endl;
            }
        }

        return 0;
    }


};
