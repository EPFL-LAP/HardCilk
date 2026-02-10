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

// Define the struct for the qsort arguments
struct triangle_args {
  // The counter
  uint32_t counter;
  // The arguments
  uint32_t vertex_count;
  Addr triangle_count_arr;
  Addr adj_list;
  uint64_t cont;
};

struct vertex_map_args {
  // The arguments
  Addr triangle_count_entry;
  Addr adj_list;
  uint64_t return_address;
  uint32_t vertex;
  uint32_t __padding;
};

bool condition(int32_t val)
{
    return val == 1;
}


class triangleCountDriver : public mFpgaHardCilkDriver
{
public:
    triangleCountDriver(std::vector<Memory *> memories, const std::string &graph_file) : mFpgaHardCilkDriver(memories) {}

    int run_test_bench_mFpga() override
    {
        triangle_args triangle_args_0 = {0, 0, 0, 0, 0};
        uint64_t addr = memories_[0]->allocateMemFPGA(sizeof(triangle_args_0), 512);


        //Graph g("/home/shahawy/graphs/single_triangle.txt", false);
        //Graph g("/home/shahawy/graphs/two_triangles.txt", false);
        //Graph g("/home/shahawy/opencilk_ws/benchmarks/triangle/example_graph.txt", false);
        //Graph g("/home/shahawy/congress_network/congress.edgelist", false);
        Graph g("/home/shahawy/graphs/congress_r.txt", false);
        //Graph g("/home/shahawy/graphs/testingChisel.txt", false);
        //Graph g("/home/shahawy/graphs/email-EuAll.txt", false);
        //Graph g("/home/shahawy/graphs/soc-LiveJournal1.txt", false);
        
        Graph directed;

        filterGraph(g, directed, filterEdge);



        // Copying the graph data to the FPGA
            uint64_t totalSize = 0;
            std::vector<uint32_t> allLists;

            for (size_t i = 0; i < directed.getNumVertices(); i++)
            {
                auto curr_list = directed.getNeighbors(i);
                totalSize += (curr_list.size());// + 1);

                //allLists.push_back(curr_list.size());
                allLists.insert(allLists.end(), curr_list.begin(), curr_list.end());
            }

            uint64_t lists_base_addr = memories_[0]->allocateMemFPGA(totalSize * sizeof(uint32_t), 512);
            memories_[0]->copyToDevice(lists_base_addr, reinterpret_cast<const uint8_t *>(allLists.data()), totalSize * sizeof(uint32_t));

            // log lists_base_addr and totalSize and end address of the lists
            printf("lists_base_addr: %lx, totalSize: %d, end address of the lists: %lx\n", lists_base_addr, totalSize, lists_base_addr + totalSize * sizeof(uint32_t));

            std::vector<uint64_t> adj_list_addresses;
            for (size_t i = 0; i < directed.getNumVertices(); i++)
            {
                adj_list_addresses.push_back(lists_base_addr);
                adj_list_addresses.push_back((uint64_t)directed.getNeighbors(i).size());
                uint64_t size = directed.getNeighbors(i).size();
                lists_base_addr += size * sizeof(uint32_t);
            }
            auto list_addr = memories_[0]->allocateMemFPGA(sizeof(uint64_t) * adj_list_addresses.size(), 512);
            memories_[0]->copyToDevice(list_addr, reinterpret_cast<const uint8_t *>(adj_list_addresses.data()), adj_list_addresses.size() * sizeof(uint64_t));
        // End of copying the graph data to the FPGA


        // Create the base task using edgeMap args
        triangle_args_0.cont = addr;

        triangle_args_0.counter =  directed.getNumNonEmpty() + 1; //directed.getNumVertices() + 1;

        //print the counter
        printf("Counter: %d\n", triangle_args_0.counter);
        
        memories_[0]->copyToDevice(addr, reinterpret_cast<const uint8_t *>(&triangle_args_0), sizeof(triangle_args_0)); 
        triangle_args_0.vertex_count = directed.getNumVertices();
        triangle_args_0.triangle_count_arr = memories_[0]->allocateMemFPGA(sizeof(uint32_t) * directed.getNumVertices(), 512);
        triangle_args_0.adj_list = list_addr;

        printf("Triangle count array address: %lx\n", triangle_args_0.triangle_count_arr);

        // Write zeros to the allocated memory
        std::vector<uint32_t> zeros(directed.getNumVertices(), 0);
        memories_[0]->copyToDevice(triangle_args_0.triangle_count_arr, reinterpret_cast<const uint8_t *>(zeros.data()), zeros.size() * sizeof(uint32_t));


        std::vector<triangle_args> base_task_data = {triangle_args_0};


        // Log the time in microseconds
        auto start = std::chrono::high_resolution_clock::now();
        initSystemMfpga(base_task_data, &condition);
        auto end = std::chrono::high_resolution_clock::now();
        std::chrono::duration<double> elapsed = end - start;
        std::cout << "Time taken for init: " << elapsed.count() << "s" << std::endl;


        startSystemMfpga();

        // Run the management loop
        start = std::chrono::high_resolution_clock::now();
        managementLoopMfpga();
        end = std::chrono::high_resolution_clock::now();
        elapsed = end - start;

        // Read back the traingle count array and reduce it by summing
        std::vector<uint32_t> triangle_count(directed.getNumVertices());
        memories_[0]->copyFromDevice((uint8_t *)triangle_count.data(), triangle_args_0.triangle_count_arr, directed.getNumVertices() * sizeof(uint32_t));


        uint32_t total_triangles = std::accumulate(triangle_count.begin(), triangle_count.end(), 0);

        std::cout << "Total triangles: " << total_triangles << std::endl;

        std::cout << "Time taken for management loop: " << elapsed.count() << "s" << std::endl;

        return 0;
    }
};