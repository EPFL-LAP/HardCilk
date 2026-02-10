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
  uint64_t vertex;
};

bool condition(int32_t val)
{
    return val == 1;
}


class triangleCountDriver : public hardCilkDriver
{
public:
    triangleCountDriver(Memory *memory) : hardCilkDriver(memory) {}

    int run_test_bench() override
    {
        triangle_args triangle_args_0 = {0, 0, 0, 0, 0};
        uint64_t addr = allocateMemFPGA(sizeof(triangle_args_0), 512);


        //Graph g("/beta/shahawy/graphs/single_triangle.txt", false);
        //Graph g("/beta/shahawy/graphs/two_triangles.txt", false);
        //Graph g("/beta/shahawy/opencilk_ws/benchmarks/triangle/example_graph.txt", false);
        //Graph g("/beta/shahawy/congress_network/congress.edgelist", false);
        //Graph g("/beta/shahawy/graphs/congress_r.txt", false);
        //Graph g("/beta/shahawy/graphs/testingChisel.txt", false);
        // auto g = janberq::Graph::loadFile("/beta/shahawy/graphs/email-EuAll.txt", false);
        //Graph g("/beta/shahawy/graphs/soc-LiveJournal1.txt", false);
        
        // Graph directed;
        
        // auto g = janberq::filterGraph(janberq::Graph::loadFile("/beta/shahawy/graphs/two_triangles.txt", false), janberq::filterEdge);
        // auto g = janberq::filterGraph(janberq::Graph::loadFile("/beta/shahawy/graphs/congress_r.txt", false), janberq::filterEdge);
        // auto g = janberq::filterGraph(janberq::Graph::loadFile("/beta/shahawy/graphs/email-EuAll.txt", false), janberq::filterEdge);
        auto g = janberq::filterGraph(janberq::Graph::loadFile("/beta/shahawy/graphs/com-orkut.ungraph.txt", false), janberq::filterEdge);

        constexpr uint64_t bufferSize = 2048ull << 20;
        uint8_t* buffer = new uint8_t[bufferSize];
        uint64_t ptrBase = allocateMemFPGA(bufferSize, bufferSize), ptrGraph, ptrAdjList;
        g.writeDirect(buffer, ptrBase, ptrGraph, ptrAdjList);
        memory_->copyToDevice(ptrBase /* + (4ull << 32) */, buffer, bufferSize);

        // Create the base task using edgeMap args
        triangle_args_0.cont = addr;

        triangle_args_0.counter = g.countNonEmpty() + 1; //directed.getNumVertices() + 1;

        //print the counter
        printf("Counter: %d\n", triangle_args_0.counter);
        
        memory_->copyToDevice(addr, reinterpret_cast<const uint8_t *>(&triangle_args_0), sizeof(triangle_args_0)); 
        triangle_args_0.vertex_count = g.count();
        triangle_args_0.triangle_count_arr = allocateMemFPGA(sizeof(uint32_t) * g.count(), 512);
        triangle_args_0.adj_list = ptrGraph;

        printf("Triangle count array address: %lx\n", triangle_args_0.triangle_count_arr);

        // Write zeros to the allocated memory
        std::vector<uint32_t> zeros(g.count(), 0);
        memory_->copyToDevice(triangle_args_0.triangle_count_arr, reinterpret_cast<const uint8_t *>(zeros.data()), zeros.size() * sizeof(uint32_t));


        std::vector<triangle_args> base_task_data = {triangle_args_0};


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

        // Read back the traingle count array and reduce it by summing
        std::vector<uint32_t> triangle_count(g.count());
        memory_->copyFromDevice((uint8_t *)triangle_count.data(), triangle_args_0.triangle_count_arr, g.count() * sizeof(uint32_t));


        uint32_t total_triangles = std::accumulate(triangle_count.begin(), triangle_count.end(), 0);

        std::cout << "Total triangles: " << total_triangles << std::endl;

        std::cout << "Time taken for management loop: " << elapsed.count() << "s" << std::endl;

        return 0;
    }
};