#pragma once

#include <hardCilkDriver.h>
#include <stdio.h>
#include <graph.h>

#define DEBUG_LINE printf("line %d\n", __LINE__);

using Addr = uint64_t;

// Define the struct for the qsort arguments
struct edgemap_args
{
    // The counter
    uint32_t counter;
    // The arguments
    uint32_t g_size;
    Addr g_edges;
    Addr f;
    Addr flag_visited;
    Addr d;
    Addr sets;
    uint32_t size;
    uint16_t round;
    uint8_t is_sync;
    uint8_t __padding;
    uint64_t cont;
};

struct task_args
{
    // The arguments
    uint32_t g_size;
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
        uint64_t addr = allocateMemFPGA(sizeof(edgemap_args_0), sizeof(edgemap_args_0));

        memory_->copyToDevice(addr, reinterpret_cast<const uint8_t *>(&edgemap_args_0), sizeof(edgemap_args_0));

        Graph g("/home/cembelentepe/Documents/opencilk_ws/bfs/graph.txt");
        std::vector<Pair32> edges = g.getEdges();
        uint64_t g_edges = allocateMemFPGA(edges.size() * sizeof(Pair32), sizeof(Pair32));
        memory_->copyToDevice(g_edges, reinterpret_cast<const uint8_t *>(edges.data()), edges.size() * sizeof(Pair32));

        // Create the fib base task using edgeMap args
        uint64_t vertex = 2;
        edgemap_args args;
        args.counter = 0;
        args.g_size = edges.size();
        args.g_edges = g_edges;
        args.f = allocateMemFPGA(1024 * sizeof(uint32_t), sizeof(uint32_t));
        args.flag_visited = allocateMemFPGA(g.getNumVertices() * sizeof(uint8_t), sizeof(uint8_t));
        args.d = allocateMemFPGA(g.getNumVertices() * sizeof(uint16_t), sizeof(uint16_t));
        args.sets = 0;
        args.size = 0;
        args.round = 1;
        args.is_sync = 0;
        args.cont = addr;

        uint32_t f[1024] = {0};
        f[0] = 1;
        f[1] = vertex;
        memory_->copyToDevice(args.f, reinterpret_cast<const uint8_t *>(f), 1024 * sizeof(uint32_t));

        std::vector<uint8_t> flag_visited(g.getNumVertices(), 0);
        flag_visited[2] = 1;
        memory_->copyToDevice(args.flag_visited, reinterpret_cast<const uint8_t *>(flag_visited.data()), g.getNumVertices() * sizeof(uint8_t));
        
        std::vector<uint16_t> d(g.getNumVertices(), -1);
        d[2] = 0;
        memory_->copyToDevice(args.d, reinterpret_cast<const uint8_t *>(d.data()), g.getNumVertices() * sizeof(uint16_t));

        std::vector<edgemap_args> base_task_data = {args};

        // Initialize the system
        initSystem(base_task_data);

        startSystem();

        // Run the management loop
        managementLoop();

        // read f from FPGA
        memory_->copyFromDevice(reinterpret_cast<uint8_t *>(f), args.f, 1024 * sizeof(uint32_t));
        for (int i = 0; i < 1024; i++)
            if (f[i] != 0)
                printf("f[%d] = %d\n", i, f[i]);

        // read flag_visited from FPGA
        memory_->copyFromDevice(reinterpret_cast<uint8_t *>(flag_visited.data()), args.flag_visited, g.getNumVertices() * sizeof(uint8_t));
        for (int i = 0; i < g.getNumVertices(); i++)
            printf("flag_visited[%d] = %d\n", i, flag_visited[i]);

        // read d from FPGA
        memory_->copyFromDevice(reinterpret_cast<uint8_t *>(d.data()), args.d, g.getNumVertices() * sizeof(uint16_t));
        for (int i = 0; i < g.getNumVertices(); i++)
            printf("d[%d] = %d\n", i, d[i]);

        return 0;
    }
};