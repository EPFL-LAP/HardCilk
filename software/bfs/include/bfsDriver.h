#pragma once

#include <hardCilkDriver.h>
#include <stdio.h>
#include <graph.h>
#include <chrono>
#include <algorithm>
#include <queue>

#define DEBUG_LINE printf("line %d\n", __LINE__);

using Addr = uint64_t;

// Define the struct for the qsort arguments
struct edgemap_args {
    // The counter
    uint32_t counter;
    // The arguments
    uint32_t g_size;
    Addr g_edges;
    Addr sets0;
    Addr flag_visited;
    Addr d;
    Addr sets1;
    uint32_t size;
    uint16_t round;
    uint8_t is_sync;
    uint8_t __padding;
    uint64_t cont;
  };
  
  struct task_args {
    // The arguments
    uint32_t __g_size;
    uint32_t vertex;
    uint16_t round;
    uint16_t __padding;
    uint32_t __padding0;
    Addr g_edges;
    Addr flag_visited;
    Addr d;
    Addr set;
    uint64_t cont;
    uint8_t __padding1[8];
  };

class bfsDriver : public hardCilkDriver
{
public:
    bfsDriver(Memory *memory) : hardCilkDriver(memory) {}

    int run_test_bench() override
    {
        edgemap_args edgemap_args_0 = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        uint64_t addr = allocateMemFPGA(sizeof(edgemap_args_0), 512);

        memory_->copyToDevice(addr, reinterpret_cast<const uint8_t *>(&edgemap_args_0), sizeof(edgemap_args_0));

        Graph g("/alpha/graph.txt");
        

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

        int set_size = this->descriptor.taskDescriptors[0].sidesConfigs[3].virtualEntrtyWidth / 8 / sizeof(uint32_t);

        // Create the base task using edgeMap args
        uint64_t vertex = 1;
        edgemap_args args;
        args.counter = 0;
        args.g_edges = list_addr;
        args.sets0 = allocateMemFPGA(set_size * sizeof(uint64_t), 512);
        args.flag_visited = allocateMemFPGA(g.getNumVertices() * sizeof(uint8_t), 512);
        args.d = allocateMemFPGA(g.getNumVertices() * sizeof(uint16_t), 512);
        args.sets1 = allocateMemFPGA(set_size * sizeof(uint64_t), 512); // This is new to be reused across all reduce tasks
        args.size = 1;
        args.round = 1;
        args.is_sync = 1; // Just for the new task structure with internal buffering
        args.cont = addr;

        uint64_t initialSet = allocateMemFPGA(set_size * sizeof(uint32_t), 512);

        // write the address of f to the sets array on the FPGA
        memory_->copyToDevice(args.sets0, reinterpret_cast<const uint8_t *>(&initialSet), sizeof(uint64_t));

        std::vector<uint32_t> f(set_size, 0);
        f[0] = 1;
        f[1] = vertex;
        memory_->copyToDevice(initialSet, reinterpret_cast<const uint8_t *>(f.data()), set_size * sizeof(uint32_t));

        std::vector<uint8_t> flag_visited(g.getNumVertices(), 0);
        flag_visited[vertex] = 1;
        memory_->copyToDevice(args.flag_visited, reinterpret_cast<const uint8_t *>(flag_visited.data()), g.getNumVertices() * sizeof(uint8_t));

        std::vector<uint16_t> d(g.getNumVertices(), -1);
        d[vertex] = 0;
        memory_->copyToDevice(args.d, reinterpret_cast<const uint8_t *>(d.data()), g.getNumVertices() * sizeof(uint16_t));

        std::vector<edgemap_args> base_task_data = {args};

        // Log the time in microseconds
        auto start = std::chrono::high_resolution_clock::now();
        initSystem(base_task_data);
        auto end = std::chrono::high_resolution_clock::now();
        auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(end - start);
        printf("Time taken for init: %lld milliseconds\n", elapsed.count());

        startSystem();

        // Run the management loop
        start = std::chrono::high_resolution_clock::now();
        managementLoop();
        end = std::chrono::high_resolution_clock::now();
        elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(end - start);

        // read f from FPGA
        // memory_->copyFromDevice(reinterpret_cast<uint8_t *>(f.data()), args.f, 1024 * sizeof(uint32_t));
        // for (int i = 0; i < 1024; i++)
        //     if (f[i] != 0)
        //         printf("f[%d] = %d\n", i, f[i]);

        // read flag_visited from FPGA
        memory_->copyFromDevice(reinterpret_cast<uint8_t *>(flag_visited.data()), args.flag_visited, g.getNumVertices() * sizeof(uint8_t));

        // read d from FPGA
        memory_->copyFromDevice(reinterpret_cast<uint8_t *>(d.data()), args.d, g.getNumVertices() * sizeof(uint16_t));


        int n = 5; // Example value for n

        // Priority queue to store pairs of (distance, vertex)
        std::priority_queue<std::pair<int, int>, std::vector<std::pair<int, int>>, std::greater<>> pq;

        for (int i = 0; i < d.size(); i++)
        {
            if (d[i] == 65535)
                continue;

            // std::cout << "Vertex: " << i << " Distance: " << d[i] << std::endl;
            if (pq.size() < n)
            {
                pq.push(std::make_pair(d[i], i));
            }
            else if (d[i] > pq.top().first)
            {
                pq.pop();
                pq.push(std::make_pair(d[i], i));
            }
        }
        // Extract the top n distances and their corresponding vertices
        std::vector<std::pair<int, int>> top_n_distances;
        while (!pq.empty())
        {
            top_n_distances.push_back(pq.top());
            pq.pop();
        }

        // Print the top n distances and their corresponding vertices
        for (const auto &pair : top_n_distances)
        {
            std::cout << "Vertex: " << pair.second << " Distance: " << pair.first << std::endl;
        }

        printf("Time taken for management loop: %lld milliseconds\n", elapsed.count());

        return 0;
    }
};