#pragma once

#include <hardCilkDriver.h>
#include <stdio.h>
#include <graph.h>
#include <chrono>
#include <algorithm>
#include <queue>
#include <set>
#include <numeric>

#define DEBUG_LINE printf("line %d\n", __LINE__);

using Addr = uint64_t;

struct pageRankReduce_args {
  // The counter
  uint32_t counter;
  // The arguments
  uint32_t vertex_count;
  Addr adj_list;
  Addr Pcurr;
  Addr Pnext;
  Addr diffs;
  Addr cont;
  uint8_t __padding[16];
};

struct vertex_map_args {
  // The arguments
  Addr Pcurr;
  Addr Pnext;
  Addr diffs;
  Addr adj_list;
  uint64_t return_address;
  uint32_t vertex;
  uint32_t vertex_count;
  uint8_t __padding[16];
};



class pageRankDriver : public hardCilkDriver
{
public:
    pageRankDriver(Memory *memory) : hardCilkDriver(memory) {}

    int run_test_bench() override
    {
        pageRankReduce_args pageRankReduce_args_0 = {0, 0, 0, 0, 0, 0, 0, 0};
        uint64_t addr = allocateMemFPGA(sizeof(pageRankReduce_args_0), 512);
        memory_->copyToDevice(addr, reinterpret_cast<const uint8_t *>(&pageRankReduce_args_0), sizeof(pageRankReduce_args_0));


        Graph g("/home/shahawy/opencilk_ws/benchmarks/triangle/example_graph.txt", false);


        // Copying the graph data to the FPGA
            uint64_t totalSize = 0;
            std::vector<uint32_t> allLists;

            for (size_t i = 0; i < g.getNumVertices(); i++)
            {
                auto curr_list = g.getNeighbors(i);
                totalSize += (curr_list.size() + 1);

                allLists.push_back(curr_list.size());
                allLists.insert(allLists.end(), curr_list.begin(), curr_list.end());
            }

            uint64_t lists_base_addr = allocateMemFPGA(totalSize * sizeof(uint32_t), 512);
            memory_->copyToDevice(lists_base_addr, reinterpret_cast<const uint8_t *>(allLists.data()), totalSize * sizeof(uint32_t));
 
            // log lists_base_addr and totalSize and end address of the lists
            printf("lists_base_addr: %lx, totalSize: %d, end address of the lists: %lx\n", lists_base_addr, totalSize, lists_base_addr + totalSize * sizeof(uint32_t));

            std::vector<uint64_t> adj_list_addresses;
            for (size_t i = 0; i < g.getNumVertices(); i++)
            {
                adj_list_addresses.push_back(lists_base_addr);
                uint64_t size = g.getNeighbors(i).size() + 1;
                lists_base_addr += size * sizeof(uint32_t);
            }
            auto list_addr = allocateMemFPGA(sizeof(uint64_t) * adj_list_addresses.size(), 512);
            memory_->copyToDevice(list_addr, reinterpret_cast<const uint8_t *>(adj_list_addresses.data()), adj_list_addresses.size() * sizeof(uint64_t));
        // End of copying the graph data to the FPGA


        // Create the base task 
        pageRankReduce_args_0.cont = addr;
        pageRankReduce_args_0.Pcurr = allocateMemFPGA(sizeof(double) * g.getNumVertices(), 512);
        pageRankReduce_args_0.Pnext = allocateMemFPGA(sizeof(double) * g.getNumVertices(), 512);
        pageRankReduce_args_0.diffs = allocateMemFPGA(sizeof(double) * g.getNumVertices(), 512);
        pageRankReduce_args_0.adj_list = list_addr;
        pageRankReduce_args_0.vertex_count = g.getNumVertices();

        // Write 1.0  / g.getNumVertices() to elements of Pnext (switched in first iteration on FPGA code)
        std::vector<double> Pnext(g.getNumVertices(), 1.0 / g.getNumVertices());
        memory_->copyToDevice(pageRankReduce_args_0.Pnext, reinterpret_cast<const uint8_t *>(Pnext.data()), Pnext.size() * sizeof(double));
        
        // Write the same values to elements of diffs to trigger execution
        memory_->copyToDevice(pageRankReduce_args_0.diffs, reinterpret_cast<const uint8_t *>(Pnext.data()), Pnext.size() * sizeof(double));
        
        // Write zeros to elements of Pcurr
        std::vector<double> Pcurr(g.getNumVertices(), 0.0);
        memory_->copyToDevice(pageRankReduce_args_0.Pcurr, reinterpret_cast<const uint8_t *>(Pcurr.data()), Pcurr.size() * sizeof(double));



        std::vector<pageRankReduce_args> base_task_data = {pageRankReduce_args_0};


        // Log the time taken for init
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


        // Read back the Pcurr values from the FPGA
        std::vector<double> Pcurr_back(g.getNumVertices());
        memory_->copyFromDevice(reinterpret_cast<uint8_t *>(Pcurr_back.data()), pageRankReduce_args_0.Pcurr, Pcurr_back.size() * sizeof(double));

        // Sort and print the top n values
        std::vector<size_t> indices(Pcurr_back.size());
        std::iota(indices.begin(), indices.end(), 0);
        std::sort(indices.begin(), indices.end(), [&Pcurr_back](size_t i1, size_t i2) { return Pcurr_back[i1] > Pcurr_back[i2]; });

        for (size_t i = 0; i < 10 && i < g.getNumVertices(); i++)
        {
            std::cout << "Vertex: " << indices[i] << " PageRank: " << Pcurr_back[indices[i]] << std::endl;
        }

        

        std::cout << "Time taken for management loop: " << elapsed.count() << "s" << std::endl;

        return 0;
    }
};