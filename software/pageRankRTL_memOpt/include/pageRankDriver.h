#pragma once

#include <algorithm>
#include <chrono>
#include <graph.h>
#include <hardCilkDriver.h>
#include <numeric>
#include <queue>
#include <set>
#include <stdio.h>

#include <cassert>

#define DEBUG_LINE printf("line %d\n", __LINE__);

#define eps 1e-6
#define damping 0.85

using addr_t = uint64_t;

struct vertex_map_args {
  addr_t pGraph;     // constant pointer (the same as triangle count)
  addr_t pPrCurr;    // constant pointer (float 32)
  addr_t pPrNext;    // constant pointer (float 32)
  addr_t pInvDegree; // constant pointer (float 32)
  float fpConst0;
  float fpConst1;
  uint32_t uVertex;
  uint8_t padding[16];
};

struct page_rank_reduce_task {
  float value;
  uint32_t iter_count;
  addr_t pGraph;
  addr_t pPrCurr;
  addr_t pPrNext;
  addr_t pInvDegree;
  float fpConst0;
  float fpConst1;
  uint32_t vertex_count;
  float epsilon;
  addr_t cont;
};

bool condition(int32_t val) { return val == 1; }

class pageRankDriver : public hardCilkDriver {
public:
  pageRankDriver(Memory *memory) : hardCilkDriver(memory) {}

  int run_test_bench() override {
    page_rank_reduce_task pageRankReduce_args_0 = {0, 0, 0, 0, 0, 0, 0, 0};
    int counter = 2;
    uint64_t addr = allocateMemFPGA(sizeof(pageRankReduce_args_0), 512);
    memory_->copyToDevice(
        addr, reinterpret_cast<const uint8_t *>(&pageRankReduce_args_0),
        sizeof(pageRankReduce_args_0));
    memory_->copyToDevice(addr, reinterpret_cast<const uint8_t *>(&counter),
                          sizeof(counter));

    // Graph
    // g("/home/shahawy/opencilk_ws/benchmarks/triangle/example_graph.txt",
    // false); Graph g("/home/shahawy/graphs/single_triangle.txt", false); Graph
    // g("/home/shahawy/graphs/two_triangles.txt", false); Graph
    // g("/home/shahawy/opencilk_ws/benchmarks/triangle/example_graph.txt",
    // false);
    // auto g = janberq::Graph::loadFile("/home/shahawy/congress_network/congress.edgelist", false);
    // Graph g("/beta/shahawy/graphs/congress_r.txt", false); Graph
    // g("/home/shahawy/graphs/testingChisel.txt", false); Graph
    // auto g = janberq::Graph::loadFile("/beta/shahawy/graphs/email-EuAll.txt", false);
    // g("/home/shahawy/graphs/soc-LiveJournal1.txt", false); Graph
    auto g = janberq::Graph::loadFile("/beta/shahawy/graphs/com-orkut.ungraph.txt", false);
    // auto g = janberq::Graph::loadFile("/beta/shahawy/graphs/com-orkut.ungraph.txt", false);

    pageRankReduce_args_0.cont = addr;
    pageRankReduce_args_0.iter_count = 0;
    pageRankReduce_args_0.pPrCurr =
        allocateMemFPGA(sizeof(_Float32) * g.count(), 1ull << 14);
    pageRankReduce_args_0.pPrNext =
        allocateMemFPGA(sizeof(_Float32) * g.count(), 1ull << 14);

    // auto g =
    // janberq::Graph::loadFile("/home/shahawy/congress_network/congress.edgelist",
    // false); auto g =
    // janberq::Graph::loadFile("/beta/shahawy/graphs/congress_r.txt", false);
    // auto g = janberq::Graph::loadFile("/beta/shahawy/graphs/email-EuAll.txt",
    // false);

    constexpr uint64_t bufferSize = 2048ull << 20;
    uint8_t *buffer = new uint8_t[bufferSize];
    uint64_t ptrBase = allocateMemFPGA(bufferSize, bufferSize), ptrGraph,
             ptrAdjList;
    g.writeDirect(buffer, ptrBase, ptrGraph, ptrAdjList);
    memory_->copyToDevice(ptrBase /* + (4ull << 32) */, buffer, bufferSize);

    // inverted_degree vector
    std::vector<_Float32> inv_degree(g.count(), 0.0f);

    // Copying the graph data to the FPGA
    // uint64_t totalSize = 0;
    // std::vector<uint32_t> allLists;

    for (size_t i = 0; i < g.count(); i++) {
      auto const &curr_list = g.nodes()[i];

      if (curr_list.size() == 0)
        inv_degree[i] = 0;

      else
        inv_degree[i] = 1.0f / curr_list.size();
    }

    // Create the base task

    pageRankReduce_args_0.pGraph = ptrGraph;
    pageRankReduce_args_0.vertex_count = g.count();
    pageRankReduce_args_0.epsilon = eps;
    pageRankReduce_args_0.fpConst0 = damping;
    pageRankReduce_args_0.fpConst1 = (1 - damping) / g.count();
    pageRankReduce_args_0.pInvDegree = allocateMemFPGA(sizeof(_Float32) * g.count(), 1ull << 14);
    pageRankReduce_args_0.value = 0.0f;

    // Write the inverted degree vector to the FPGA
    memory_->copyToDevice(pageRankReduce_args_0.pInvDegree,
                          reinterpret_cast<const uint8_t *>(inv_degree.data()),
                          inv_degree.size() * sizeof(_Float32));

    // Write 1.0  / g.count() to elements of Pnext (switched in first iteration
    // on FPGA code)
    std::vector<_Float32> Pnext(g.count(), 1.0f / g.count());
    memory_->copyToDevice(pageRankReduce_args_0.pPrNext,
                          reinterpret_cast<const uint8_t *>(Pnext.data()),
                          Pnext.size() * sizeof(_Float32));

    // Write zeros to elements of Pcurr
    std::vector<_Float32> Pcurr(g.count(), 0.0f);
    memory_->copyToDevice(pageRankReduce_args_0.pPrCurr,
                          reinterpret_cast<const uint8_t *>(Pcurr.data()),
                          Pcurr.size() * sizeof(_Float32));

    std::vector<page_rank_reduce_task> base_task_data = {pageRankReduce_args_0};

    // Log the time taken for init
    auto start = std::chrono::high_resolution_clock::now();
    initSystem(base_task_data, condition);
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
    std::vector<_Float32> Pcurr_back(g.count());
    memory_->copyFromDevice(reinterpret_cast<uint8_t *>(Pcurr_back.data()),
                            pageRankReduce_args_0.pPrCurr,
                            Pcurr_back.size() * sizeof(_Float32));

    // Sort and print the top n values
    std::vector<size_t> indices(Pcurr_back.size());
    std::iota(indices.begin(), indices.end(), 0);
    std::sort(indices.begin(), indices.end(),
              [&Pcurr_back](size_t i1, size_t i2) {
                return Pcurr_back[i1] > Pcurr_back[i2];
              });

    for (size_t i = 0; i < 10 && i < g.count(); i++) {
      std::cout << "Vertex: " << indices[i]
                << " PageRank: " << Pcurr_back[indices[i]] << std::endl;
    }

    std::ofstream outfile("pagerank_results.txt");
    if (!outfile) {
      std::cerr << "Error opening output file\n";
    }

    // Write header
    outfile << "node rank prValue\n";

    // Write sorted results
    for (size_t i = 0; i < g.count(); i++) {
      outfile << indices[i] << " "                  // node
              << (i + 1) << " "                     // rank position
              << std::fixed << std::setprecision(6) // match Python format
              << Pcurr_back[indices[i]] << "\n";    // pagerank value
    }

    outfile.close();

    std::cout << "Time taken for management loop: " << elapsed.count() << "s"
              << std::endl;

    return 0;
  }
};