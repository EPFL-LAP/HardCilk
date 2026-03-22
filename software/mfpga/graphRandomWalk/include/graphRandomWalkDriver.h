#pragma once

#include <mFpgaHardCilkDriver.h>
#include <stdio.h>
#include <graph.h>
#include <chrono>
#include <algorithm>
#include <queue>
#include <set>
#include <numeric>

#define DEBUG_LINE printf("line %d\n", __LINE__);

using addr_t = uint64_t;

struct walk_gen_args {
  uint32_t counter;
  uint32_t num_walks;
  uint32_t walk_length; // Can be maximally vertex count
  float stop_probability;
  addr_t global_buffer;
  addr_t cont;
  addr_t graph;
  uint8_t _padding[24];
};

struct walker_args {
  addr_t cont;
  addr_t walk_buffer;
  uint64_t stop_thresh;
  addr_t graph;
  uint32_t walk_length; 
  uint32_t base_vertex;
  uint8_t _padding[24];
};

bool condition(int32_t val)
{
    return val == 1;
}


class graphRandomWalkDriver : public mFpgaHardCilkDriver
{
private:
    std::string graph_file;

public:
    graphRandomWalkDriver(std::vector<Memory *> memories, const std::string &graph_file) : mFpgaHardCilkDriver(memories), graph_file(graph_file) {}

    int run_test_bench_mFpga() override
    {
        



        Graph g(graph_file, false);

        walk_gen_args walk_gen_args_0 = {0, 0, 0, 0, 0, 0, 0, 0};
        
        int counter = g.getNumVertices()  + 1;

        uint64_t addr = memories_[0]->allocateMemFPGA(sizeof(walk_gen_args_0), 512);
        if (memories_.size() > 1)
            memories_[1]->allocateMemFPGA(sizeof(walk_gen_args_0), 512); // Have the same shift in FPGA 1 memory allocator
        memories_[0]->copyToDevice(addr, reinterpret_cast<const uint8_t *>(&walk_gen_args_0), sizeof(walk_gen_args_0));
        memories_[0]->copyToDevice(addr, reinterpret_cast<const uint8_t *>(&counter), sizeof(counter));



        // Copying the graph data to the FPGA
        uint64_t totalSize = 0;
        std::vector<uint32_t> allLists;

        for (size_t i = 0; i < g.getNumVertices(); i++)
        {
            auto curr_list = g.getNeighbors(i);
            totalSize += (curr_list.size());
            allLists.insert(allLists.end(), curr_list.begin(), curr_list.end());
        }

        uint64_t lists_base_addr = memories_[0]->allocateMemFPGA(totalSize * sizeof(uint32_t), 512);
        memories_[0]->copyToDevice(lists_base_addr, reinterpret_cast<const uint8_t *>(allLists.data()), totalSize * sizeof(uint32_t));

        // Copy to FPGA 1 as well
        if (memories_.size() > 1) {
            uint64_t lists_base_addr_1 = memories_[1]->allocateMemFPGA(totalSize * sizeof(uint32_t), 512);
            memories_[1]->copyToDevice(lists_base_addr_1, reinterpret_cast<const uint8_t *>(allLists.data()), totalSize * sizeof(uint32_t));
        }


        // log lists_base_addr and totalSize and end address of the lists
        printf("lists_base_addr: %lx, totalSize: %d, end address of the lists: %lx\n", lists_base_addr, totalSize, lists_base_addr + totalSize * sizeof(uint32_t));

        std::vector<uint64_t> adj_list_addresses;
        for (size_t i = 0; i < g.getNumVertices(); i++)
        {
            adj_list_addresses.push_back(lists_base_addr);
            adj_list_addresses.push_back((uint64_t)g.getNeighbors(i).size());
            uint64_t size = g.getNeighbors(i).size();
            lists_base_addr += size * sizeof(uint32_t);
        }
        auto list_addr = memories_[0]->allocateMemFPGA(sizeof(uint64_t) * adj_list_addresses.size(), 512);
        memories_[0]->copyToDevice(list_addr, reinterpret_cast<const uint8_t *>(adj_list_addresses.data()), adj_list_addresses.size() * sizeof(uint64_t));

        // Copy to FPGA 1 as well
        if (memories_.size() > 1) {
            auto list_addr_1 = memories_[1]->allocateMemFPGA(sizeof(uint64_t) * adj_list_addresses.size(), 512);
            memories_[1]->copyToDevice(list_addr_1, reinterpret_cast<const uint8_t *>(adj_list_addresses.data()), adj_list_addresses.size() * sizeof(uint64_t));
        }




        const uint32_t MAX_WALK_LEN = 32;
        uint32_t NUM_WALKS = g.getNumVertices();

        // Create the base task 
        walk_gen_args_0.cont = addr;
        walk_gen_args_0.num_walks = NUM_WALKS;
        walk_gen_args_0.walk_length = MAX_WALK_LEN;
        walk_gen_args_0.stop_probability = 0.15f;
        walk_gen_args_0.graph = list_addr;
        walk_gen_args_0.global_buffer = memories_[0]->allocateMemFPGA(sizeof(uint32_t) * NUM_WALKS * MAX_WALK_LEN, 512);
        
        // Write -1 to all the global buffer
        std::vector<uint32_t> global_buffer(NUM_WALKS * MAX_WALK_LEN, -1);
        memories_[0]->copyToDevice(walk_gen_args_0.global_buffer, reinterpret_cast<const uint8_t *>(global_buffer.data()), global_buffer.size() * sizeof(uint32_t));


        std::vector<walk_gen_args> base_task_data = {walk_gen_args_0};


        // Log the time taken for init
        auto start = std::chrono::high_resolution_clock::now();
        initSystemMfpga(base_task_data, condition);
        auto end = std::chrono::high_resolution_clock::now();
        std::chrono::duration<double> elapsed = end - start;
        std::cout << "Time taken for init: " << elapsed.count() << "s" << std::endl;


        startSystemMfpga();

        // Run the management loop
        start = std::chrono::high_resolution_clock::now();
        managementLoopMfpga();
        end = std::chrono::high_resolution_clock::now();
        elapsed = end - start;


        // Read back the Pcurr values from the FPGA
        std::vector<int> global_buffer_back(NUM_WALKS * MAX_WALK_LEN);
        memories_[0]->copyFromDevice(reinterpret_cast<uint8_t *>(global_buffer_back.data()), walk_gen_args_0.global_buffer, global_buffer_back.size() * sizeof(uint32_t));


        for (int i = 0; i < 10; i++)
        {
            int index = i * MAX_WALK_LEN;
            std::cout << "Walk for vertex " << i << " :\n";
            for(int j = index; j <  (index + MAX_WALK_LEN); j++){
                std::cout << global_buffer_back[j] << ", ";
            }
            std::cout << std::endl;
        }

        // Write all the walks lengths to a file
        std::ofstream walks_file("walks_output_1.txt");
        for (uint32_t i = 0; i < NUM_WALKS; i++)
        {
            int index = i * MAX_WALK_LEN;
            int walk_length = 0;
            for(int j = index; j < (index + MAX_WALK_LEN); j++){
                if (global_buffer_back[j] == -1) {
                    break;
                }
                walk_length++;
            }
            walks_file << "Walk for vertex " << i << ": " << walk_length << "\n";
        }
        walks_file.close();


        // Print the neighbourhood of vertex 1267
        // for(int j = 1267 * MAX_WALK_LEN; j < (1267 * MAX_WALK_LEN + MAX_WALK_LEN); j++){
        //     std::cout << global_buffer_back[j] << " ";
        // }
        // std::cout << std::endl;

        std::cout << "Wrote all walks to walks_output.txt" << std::endl;

        std::cout << "Time taken for management loop: " << elapsed.count() << "s" << std::endl;

        return 0;
    }
};