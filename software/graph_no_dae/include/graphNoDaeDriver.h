#pragma once

#include <hardCilkDriver.h>
#include <stdio.h>
#include <thread>
#include <chrono>
#include <systemc>

#define DEBUG_LINE printf("line %d\n", __LINE__);


using addr_t = uint64_t;

// #define DEPTH 8
// #define BRANCH_FACTOR 3

#define DEPTH 6
#define BRANCH_FACTOR 4


typedef struct {
  uint32_t visited;
  uint32_t len;
  addr_t neighbors;
} node_t;

typedef struct {
  uint64_t n_len;
  addr_t nodes;
} graph_t;

struct visit_task {
  uint32_t counter; // added by hand to provide a way to synchronize with Host
  addr_t cont;
  addr_t g;
  addr_t n;
};


bool condition(int32_t val)
{
    return val == 1;
}

class graphNoDaeDriver: public hardCilkDriver
{
    public:
    
    graphNoDaeDriver(Memory * memory): hardCilkDriver(memory) {}

    int run_test_bench() override {

        const size_t TOTAL_NODES = (pow(BRANCH_FACTOR, DEPTH + 1) - 1) / (BRANCH_FACTOR - 1);

        std::cout << "TOTAL NODES "<< TOTAL_NODES << std::endl;



        // Initialize the main continuation task
        visit_task visit_cont0_task_0 = {TOTAL_NODES+1, 0, 0, 0};
        uint64_t addr = allocateMemFPGA(sizeof(visit_cont0_task_0), 512);
        memory_->copyToDevice(addr, reinterpret_cast<const uint8_t*>(&visit_cont0_task_0), sizeof(visit_cont0_task_0));


        // Initialize the graph in the FPGA
        node_t *nodes = (node_t*) malloc(sizeof(node_t) * TOTAL_NODES); // allocate on the cpu
        uint64_t nodes_fpga = allocateMemFPGA(sizeof(node_t) * TOTAL_NODES, 512); // allocate on the fpga


        // Initialize the nodes to zero length and not visited
        for (size_t i = 0; i < TOTAL_NODES; ++i)
        {
            nodes[i] = (node_t){
                .visited = false,
                .len = 0,
                .neighbors = 0
            };
        }

        // Allocate all nodes
        std::vector<std::vector<int>> adjacency_list(TOTAL_NODES); // temporary to store edges

        // Build the tree
        size_t current = 0;
        size_t next = 1;

        uint64_t neighbourhoods_fpga = allocateMemFPGA(sizeof(int) * (TOTAL_NODES-1), 512);
        uint64_t moving_pointer = neighbourhoods_fpga;
        std::vector<int> neighbourhoods;

        for (int depth = 0; depth < DEPTH; ++depth) {

            size_t nodes_at_this_level = pow(BRANCH_FACTOR, depth);
            std::cout << "NODES AT LEVEL "<< nodes_at_this_level << " LEVEL " << depth  << std::endl;
        
            for (size_t i = 0; i < nodes_at_this_level; ++i) {
                
                size_t parent_index = current + i;

                for (int j = 0; j < BRANCH_FACTOR; ++j) {
                    size_t child_index = next;
                    next++;
                    if (child_index >= TOTAL_NODES) break;
                    adjacency_list[parent_index].push_back(child_index);
                }

                if(adjacency_list[parent_index].empty()) {
                    nodes[parent_index] = (node_t){
                        .visited = false,
                        .len = 0,
                        .neighbors = 0
                    };
                } else{
                    // Allocate memory for the neighbors
                    size_t neighbors_size = adjacency_list[parent_index].size() * sizeof(int);
                    //uint64_t neighbors_fpga = allocateMemFPGA(neighbors_size, 512);
                    
                    // Copy the neighbors to the FPGA
                    //memory_->copyToDevice(neighbors_fpga, reinterpret_cast<const uint8_t*>(adjacency_list[parent_index].data()), neighbors_size);

                    // insert the adjacency list into the neighbourhoods vector
                    neighbourhoods.insert(neighbourhoods.end(), adjacency_list[parent_index].begin(), adjacency_list[parent_index].end());

                    // Log the length of the neighbors
                    std::cout << "Node " << parent_index << " has " << adjacency_list[parent_index].size() << " neighbors" << std::endl;
                    // log neighbourhoods size
                    std::cout << "Neighbourhoods size: " << neighbourhoods.size() << std::endl;


                    nodes[parent_index] = (node_t){
                        .visited = false,
                        .len = adjacency_list[parent_index].size(),
                        .neighbors = moving_pointer
                    };

                    moving_pointer += neighbors_size;
                }

            }
            current += nodes_at_this_level;
        }

        //assert(neighbourhoods.size() == TOTAL_NODES-1);
        std::cout << "Neighbourhoods size: " << neighbourhoods.size() << std::endl;

        // copy the neighbourhoods to the fpga
        memory_->copyToDevice(neighbourhoods_fpga, reinterpret_cast<const uint8_t*>(neighbourhoods.data()), sizeof(int) * (TOTAL_NODES-1));
    
        // copy the nodes to the fpga
        memory_->copyToDevice(nodes_fpga, reinterpret_cast<const uint8_t*>(nodes), sizeof(node_t) * TOTAL_NODES);

        graph_t *g = (graph_t*) malloc(sizeof(graph_t)); // allocate on the cpu
        uint64_t g_fpga = allocateMemFPGA(sizeof(graph_t), 512); // allocate on the fpga
        g->n_len = TOTAL_NODES;
        g->nodes = (addr_t)nodes_fpga;

        // copy the graph to the fpga
        memory_->copyToDevice(g_fpga, reinterpret_cast<const uint8_t*>(g), sizeof(graph_t));


        // Create the visit base task
        visit_task args = {
            .counter = 0,
            .cont = addr, // addr + offset of the node_t in the continuation task
            .g = g_fpga,
            .n = (addr_t)nodes_fpga
        };

        std::vector<visit_task> base_task_data = {args};
        
        // Initialize the system
        initSystem(base_task_data, &condition);

        startSystem();
        
        // Run the management loop
        managementLoop();

        // log the time of system c 
        std::cout << "Time: " << sc_core::sc_time_stamp()  << "ns All nodes were visited!" << std::endl;


        node_t *nodes_read = (node_t*) malloc(sizeof(node_t) * TOTAL_NODES);
        memory_->copyFromDevice(reinterpret_cast<uint8_t*>(nodes_read), nodes_fpga, sizeof(node_t) * TOTAL_NODES);


        for (int i = 0; i < TOTAL_NODES; i += 1) {
            if (nodes_read[i].visited == false) {
                printf("Node %d was not visited\n", i);
                return -1;
            }
        }
        printf("Success, all nodes were visited!\n");

        return 0;
    }

};
