#pragma once

#include <hardCilkDriver.h>
#include <stdio.h>
#include <chrono>
#include <algorithm>
#include <queue>
#include <set>
#include <numeric>

#define DEBUG_LINE printf("line %d\n", __LINE__);


#define TEST_SIZE 1024
#define DATA_VECTOR_SIZE 32
using Addr = uint64_t;


struct mem_issuer_task{
    Addr m_entry;
    Addr data_vector;
    Addr cont;
    uint64_t data_vector_size;
};

struct processor_task{
    uint32_t counter;
    uint32_t data_vector_size;  
    uint64_t m_value;
    Addr data_vector;
    Addr cont;
};


bool condition(int32_t val)
{
    return val == 1;
}


class daeDriver : public hardCilkDriver
{
public:
    daeDriver(Memory *memory) : hardCilkDriver(memory) {}

    int run_test_bench() override
    {
        const size_t AS = TEST_SIZE * sizeof(uint64_t);
        const size_t BS = TEST_SIZE * DATA_VECTOR_SIZE * sizeof(uint64_t);

        processor_task processor_args_0 = {TEST_SIZE+1, 0, 0, 0, 0};
        uint64_t addr = allocateMemFPGA(sizeof(processor_args_0), 512);
        memory_->copyToDevice(addr,  reinterpret_cast<const uint8_t *>(&processor_args_0), sizeof(processor_args_0));

        uint64_t *A = (uint64_t*)malloc(AS);
        uint64_t *B = (uint64_t*)malloc(BS);
        for (int i = 0; i < TEST_SIZE; i++) {
            A[i] = rand();
            for (int j = 0; j < DATA_VECTOR_SIZE; j++) {
                B[i * DATA_VECTOR_SIZE + j] = rand();
            }
        }
        uint64_t AF = allocateMemFPGA(AS, 512);
        memory_->copyToDevice(AF,  reinterpret_cast<const uint8_t *>(A), AS);
        uint64_t BF = allocateMemFPGA(BS, 512);
        memory_->copyToDevice(BF,  reinterpret_cast<const uint8_t *>(B), BS);

        std::vector<mem_issuer_task> base_task_data;
        for(int i = 0; i < TEST_SIZE; i++) {
            base_task_data.push_back(mem_issuer_task{
                .m_entry = AF + i * sizeof(uint64_t),
                .data_vector = BF + i * sizeof(uint64_t) * DATA_VECTOR_SIZE,
                .cont = addr + 8,
                .data_vector_size = DATA_VECTOR_SIZE
            });
        }

        // Log the time in microseconds
        auto start = std::chrono::high_resolution_clock::now();
        initSystem(base_task_data, &condition);
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


        // Read back the BF and make sure they are now equal to AF[i]*BF
        //uint64_t *AFP = (uint64_t*)malloc(AS);
        uint64_t *BFP = (uint64_t*)malloc(BS);
        //memory_->copyFromDevice(reinterpret_cast<uint8_t *>(AFP), AF, AS);
        memory_->copyFromDevice(reinterpret_cast<uint8_t *>(BFP), BF, BS);


        for(int i = 0; i < TEST_SIZE; i++) {
            for (int j = 0; j < DATA_VECTOR_SIZE; j++) {
                if(BFP[i * DATA_VECTOR_SIZE + j] != A[i] * B[i * DATA_VECTOR_SIZE + j]){
                    std::cout << "Failed calculation " << BFP[i * DATA_VECTOR_SIZE + j]  << " != " << A[i] * B[i * DATA_VECTOR_SIZE + j] << std::endl;
                    std::cout << "A value: " << A[i] << ", B value: " << B[i * DATA_VECTOR_SIZE + j] << std::endl;
                }
            }
            // std::cout << "\n";
        }


        return 0;
    }
};