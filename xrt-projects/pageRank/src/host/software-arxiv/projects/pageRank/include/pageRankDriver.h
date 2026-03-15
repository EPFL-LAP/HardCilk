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

#define eps 1e-6
#define damping 0.85

using addr_t = uint64_t;
using Addr = uint64_t;

struct page_rank_map_args {
  uint32_t counter;
  float value;
  addr_t pGraph;
  addr_t pPrCurr;
  addr_t pPrNext;
  Addr inv_deg;
  float gamma;
  float inv_vertex_count;
  uint32_t vertex_count;
  float epsilon;
  addr_t cont;
};

struct vertex_map_args {
  // The arguments
  Addr pPrCurr;
  Addr pPrNext;
  Addr inv_deg;
  Addr pGraph;
  uint32_t vertex;
  uint32_t vertex_count;
  addr_t cont;
  float gamma;
  float inv_vertex_count;
  uint8_t __padding[4];
};

bool condition(int32_t val)
{
    //return val == -265096;
    return val == 1;

}


class pageRankDriver : public mFpgaHardCilkDriver
{
private:
    std::string graph_file;

public:
    pageRankDriver(std::vector<Memory *> memories, const std::string &graph_file) : mFpgaHardCilkDriver(memories), graph_file(graph_file) {}

    int run_test_bench_mFpga() override
    {
        Graph g(graph_file, false);

        page_rank_map_args page_rank_map_args_0 = {0, 0, 0, 0, 0, 0, 0, 0};
        
        int counter = g.getNumVertices()  + 1;

        // Print the counter value
        printf("Join counter value: %d\n", counter);

        memories_[0]->allocateMemFPGA(4096, 512);
        memories_[1]->allocateMemFPGA(4096, 512);



        uint64_t addr = memories_[0]->allocateMemFPGA(sizeof(page_rank_map_args_0), 512);
        memories_[1]->allocateMemFPGA(sizeof(page_rank_map_args_0), 512);
        
        memories_[0]->copyToDevice(addr, reinterpret_cast<const uint8_t *>(&page_rank_map_args_0), sizeof(page_rank_map_args_0));
        memories_[0]->copyToDevice(addr, reinterpret_cast<const uint8_t *>(&counter), sizeof(counter));


        // inverted_degree vector
        std::vector<_Float32> inv_degree(g.getNumVertices(), 0.0f);

        // Copying the graph data to the FPGA
        uint64_t totalSize = 0;
        std::vector<uint32_t> allLists;

        for (size_t i = 0; i < g.getNumVertices(); i++)
        {
            auto curr_list = g.getNeighbors(i);
            
            if(curr_list.size() == 0){

                inv_degree[i] = 0;
                //std::cout << "Found a zero neighbourhood vertex\n";
            }
            else
                inv_degree[i] = 1.0f / curr_list.size();

            totalSize += (curr_list.size());// + 1);

            //allLists.push_back(curr_list.size());
            allLists.insert(allLists.end(), curr_list.begin(), curr_list.end());
        }

        uint64_t lists_base_addr = memories_[0]->allocateMemFPGA(totalSize * sizeof(uint32_t), 512);
        uint64_t fpga_other_lists_base_addr = memories_[1]->allocateMemFPGA(totalSize * sizeof(uint32_t), 512);
        memories_[0]->copyToDevice(lists_base_addr, reinterpret_cast<const uint8_t *>(allLists.data()), totalSize * sizeof(uint32_t));
        memories_[1]->copyToDevice(fpga_other_lists_base_addr, reinterpret_cast<const uint8_t *>(allLists.data()), totalSize * sizeof(uint32_t));

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
        auto fpga_other_list_addr = memories_[1]->allocateMemFPGA(sizeof(uint64_t) * adj_list_addresses.size(), 512);
        memories_[0]->copyToDevice(list_addr, reinterpret_cast<const uint8_t *>(adj_list_addresses.data()), adj_list_addresses.size() * sizeof(uint64_t));
        memories_[1]->copyToDevice(fpga_other_list_addr, reinterpret_cast<const uint8_t *>(adj_list_addresses.data()), adj_list_addresses.size() * sizeof(uint64_t));



        // Create the base task 
        page_rank_map_args_0.cont = addr;
        page_rank_map_args_0.pPrCurr = memories_[0]->allocateMemFPGA(sizeof(_Float32) * g.getNumVertices(), 512);
        auto fpga_other_pPrCurr = memories_[1]->allocateMemFPGA(sizeof(_Float32) * g.getNumVertices(), 512);
        page_rank_map_args_0.pPrNext = memories_[0]->allocateMemFPGA(sizeof(_Float32) * g.getNumVertices(), 512);
        auto fpga_other_pPrNext = memories_[1]->allocateMemFPGA(sizeof(_Float32) * g.getNumVertices(), 512);
        page_rank_map_args_0.pGraph = list_addr;
        page_rank_map_args_0.vertex_count = g.getNumVertices();
        page_rank_map_args_0.epsilon = eps;
        page_rank_map_args_0.gamma = damping;
        page_rank_map_args_0.inv_vertex_count = 1.0f / g.getNumVertices();
        page_rank_map_args_0.value = 0.0f;
        page_rank_map_args_0.inv_deg = memories_[0]->allocateMemFPGA(sizeof(_Float32) * inv_degree.size(), 512);
        auto fpga_other_inv_deg = memories_[1]->allocateMemFPGA(sizeof(_Float32) * inv_degree.size(), 512);
        

        // Write the inverted degree vector to the FPGA
        //memory_->copyToDevice(page_rank_map_args_0.pInvDegree, reinterpret_cast<const uint8_t *>(inv_degree.data()), inv_degree.size() * sizeof(_Float32));


        // Write 1.0  / g.getNumVertices() to elements of Pnext // not in MFPGA (switched in first iteration on FPGA code)
        std::vector<_Float32> Pnext(g.getNumVertices(), (float)0);
        memories_[0]->copyToDevice(page_rank_map_args_0.pPrNext, reinterpret_cast<const uint8_t *>(Pnext.data()), Pnext.size() * sizeof(_Float32));
        memories_[1]->copyToDevice(fpga_other_pPrNext, reinterpret_cast<const uint8_t *>(Pnext.data()), Pnext.size() * sizeof(_Float32));
    
        // Write zeros to elements of Pcurr
        std::vector<_Float32> Pcurr(g.getNumVertices(), (float)1.0 / g.getNumVertices());
        memories_[0]->copyToDevice(page_rank_map_args_0.pPrCurr, reinterpret_cast<const uint8_t *>(Pcurr.data()), Pcurr.size() * sizeof(_Float32));
        memories_[1]->copyToDevice(fpga_other_pPrCurr, reinterpret_cast<const uint8_t *>(Pcurr.data()), Pcurr.size() * sizeof(_Float32));

        // Write the inverted degree vector to the FPGA
        memories_[0]->copyToDevice(page_rank_map_args_0.inv_deg, reinterpret_cast<const uint8_t *>(inv_degree.data()), inv_degree.size() * sizeof(_Float32));
        memories_[1]->copyToDevice(fpga_other_inv_deg, reinterpret_cast<const uint8_t *>(inv_degree.data()), inv_degree.size() * sizeof(_Float32));

        std::vector<page_rank_map_args> base_task_data = {page_rank_map_args_0};


        // Log the time taken for init
        auto start = std::chrono::high_resolution_clock::now();
        initSystemMfpga(base_task_data, condition);
        auto end = std::chrono::high_resolution_clock::now();
        std::chrono::duration<double> elapsed = end - start;
        std::cout << "Time taken for init: " << elapsed.count() << "s" << std::endl;


        startSystemMfpga();

        // Run the management loop
        start = std::chrono::high_resolution_clock::now();
        
        float diff = 1e9;
        
        managementLoopMfpga();


        end = std::chrono::high_resolution_clock::now();
        elapsed = end - start;


        // Read back the Pcurr values from the FPGA
        std::vector<_Float32> Pcurr_back(g.getNumVertices());
        memories_[0]->copyFromDevice(reinterpret_cast<uint8_t *>(Pcurr_back.data()), page_rank_map_args_0.pPrNext, Pcurr_back.size() * sizeof(_Float32));

        // Sort and print the top n values
        std::vector<size_t> indices(Pcurr_back.size());
        std::iota(indices.begin(), indices.end(), 0);
        std::sort(indices.begin(), indices.end(), [&Pcurr_back](size_t i1, size_t i2) { return Pcurr_back[i1] > Pcurr_back[i2]; });

        for (size_t i = 0; i < 10; i++)
        {
            std::cout << "Vertex: " << indices[i] << " PageRank: " << Pcurr_back[indices[i]] << std::endl;
        }

        // Write all the pr values with verticies into a file
        std::ofstream file("pageRank_output_2_fpga.txt");
        for (size_t i = 0; i < g.getNumVertices(); i++)
        {
            file << "Vertex: " << indices[i] << " PageRank: " << Pcurr_back[indices[i]] << std::endl;
        }
        file.close();

        std::cout << "Time taken for management loop: " << elapsed.count() << "s" << std::endl;

        sleep(5);
        auto messages_received_0 = memories_[0]->readReg64(drivers[0]->descriptor.getMfpgaBaseAddress() + messages_receveid_count_shift);
        auto messages_sent_0 = memories_[0]->readReg64(drivers[0]->descriptor.getMfpgaBaseAddress() + messages_sent_count_shift);

        auto messages_received_1 = memories_[1]->readReg64(drivers[1]->descriptor.getMfpgaBaseAddress() + messages_receveid_count_shift);
        auto messages_sent_1 = memories_[1]->readReg64(drivers[1]->descriptor.getMfpgaBaseAddress() + messages_sent_count_shift);

        // log the message exchange count
        printf("Messages received by FPGA 0: %lu, Messages sent by FPGA 0: %lu\n", messages_received_0, messages_sent_0);
        printf("Messages received by FPGA 1: %lu, Messages sent by FPGA 1: %lu\n", messages_received_1, messages_sent_1);

        return 0;
    }
};