#pragma once

#include <GraphBenchmarkCommon.h>
#include <benchmarks/GraphColoring/Hasenplaugh14/GraphColoring.h>

#include <cstddef>

struct GraphColoringHost_args
{
  uint32_t counter;
  uint32_t vertex_count;
  uint32_t init_done;
  uint32_t active;
  uint32_t done;
  uint32_t finished;
  uint32_t frontier_length;
  uint32_t max_colors;
  Addr graph;
  Addr rank;
  Addr priority;
  Addr color;
  Addr roots0;
  Addr roots1;
  Addr nextFChar;
  Addr colorsUsed;
  Addr cont;
  uint8_t _padding[24];
};
static_assert(sizeof(GraphColoringHost_args) == 128,
              "GraphColoringHost_args must be 128 bytes");

class GraphColoringDriver : public BenchmarkDriverBase
{
public:
  GraphColoringDriver(Memory *memory, const std::string &graph_file,
                      uint32_t max_colors = 64, uint32_t seed = 1,
                      double watchdog_s = 600.0, bool fast_mode = false)
      : BenchmarkDriverBase(memory, watchdog_s, fast_mode, "GraphColoring"),
        graph_file_(graph_file), max_colors_(max_colors), seed_(seed) {}

  static int run_cpu_test_bench(const std::string &graph_file,
                                uint32_t max_colors = 64,
                                uint32_t seed = 1)
  {
    UnweightedGraph G;
    if (!loadUndirectedGraph(graph_file, G))
      return 1;
    std::vector<uint32_t> rank = seededPermutation(G.num_vertices, seed);
    ColoringReference ref = runSeededColoringReference(G, rank, max_colors);
    std::cout << "[GraphColoring-CPU] vertices=" << G.num_vertices
              << " input_edges=" << G.num_edges
              << " max_colors=" << max_colors << " seed=" << seed << "\n";
    printColorHistogram("[GraphColoring-GBBS-style] color summary",
                        ref.colors, ref.colors_used);
    std::cout << "[GraphColoring-GBBS-style] seeded reference colors_used="
              << ref.colors_used << " rounds=" << ref.rounds
              << " last_frontier=" << ref.last_frontier_length
              << " execution time=" << ref.seconds << "s\n";
    return 0;
  }

  int run_test_bench() override
  {
    auto t0 = std::chrono::high_resolution_clock::now();
    UnweightedGraph G;
    if (!loadUndirectedGraph(graph_file_, G))
      return 1;
    if (G.num_vertices == 0)
    {
      std::cerr << "[GraphColoring] empty graph\n";
      return 1;
    }
    std::cout << "[GraphColoring] vertices=" << G.num_vertices
              << " input_edges=" << G.num_edges
              << " max_colors=" << max_colors_ << " seed=" << seed_ << "\n";

    std::vector<uint32_t> rank = seededPermutation(G.num_vertices, seed_);
    ColoringReference ref =
        runSeededColoringReference(G, rank, max_colors_);

    Addr neighbors_base = 0;
    Addr graph_base = writeUnweightedCsrToHbm(memory_, G, neighbors_base);
    Addr rank_base =
        memory_->allocateMemFPGA((uint64_t)G.num_vertices * sizeof(uint32_t),
                                 512);
    Addr priority_base =
        memory_->allocateMemFPGA((uint64_t)G.num_vertices * sizeof(uint32_t),
                                 512);
    Addr color_base =
        memory_->allocateMemFPGA((uint64_t)G.num_vertices * sizeof(uint32_t),
                                 512);
    Addr roots0_base =
        memory_->allocateMemFPGA((uint64_t)G.num_vertices * sizeof(uint32_t),
                                 512);
    Addr roots1_base =
        memory_->allocateMemFPGA((uint64_t)G.num_vertices * sizeof(uint32_t),
                                 512);
    Addr nextFChar_base = memory_->allocateMemFPGA(sizeof(uint64_t), 512);
    Addr colorsUsed_base = memory_->allocateMemFPGA(sizeof(uint32_t), 512);
    Addr cont_base =
        memory_->allocateMemFPGA(sizeof(GraphColoringHost_args), 512);

    std::vector<uint32_t> zeros32(G.num_vertices, 0);
    uint64_t zero64 = 0;
    uint32_t zero32 = 0;
    copyVectorToDevice(memory_, rank_base, rank);
    copyVectorToDevice(memory_, priority_base, zeros32);
    std::fill(zeros32.begin(), zeros32.end(), UNCOLORED_U32);
    copyVectorToDevice(memory_, color_base, zeros32);
    copyBytesToDevice(memory_, nextFChar_base, &zero64, sizeof(zero64));
    copyBytesToDevice(memory_, colorsUsed_base, &zero32, sizeof(zero32));

    GraphColoringHost_args root{};
    root.vertex_count = G.num_vertices;
    root.max_colors = max_colors_;
    root.graph = graph_base;
    root.rank = rank_base;
    root.priority = priority_base;
    root.color = color_base;
    root.roots0 = roots0_base;
    root.roots1 = roots1_base;
    root.nextFChar = nextFChar_base;
    root.colorsUsed = colorsUsed_base;
    root.cont = cont_base;
    copyBytesToDevice(memory_, cont_base, &root, sizeof(root));

    tuneSchedulerQueueCapacities("GraphColoring", G.num_vertices);
    auto t_kernel_start = std::chrono::high_resolution_clock::now();
    int rc = runRootTask(std::vector<GraphColoringHost_args>{root}, cont_base,
                         offsetof(GraphColoringHost_args, done));
    auto t_kernel_done = t_kernel_done_;

    std::vector<uint32_t> got(G.num_vertices, UNCOLORED_U32);
    memory_->copyFromDevice(reinterpret_cast<uint8_t *>(got.data()),
                            color_base,
                            (uint64_t)got.size() * sizeof(uint32_t));
    uint32_t colors_used = 0;
    memory_->copyFromDevice(reinterpret_cast<uint8_t *>(&colors_used),
                            colorsUsed_base, sizeof(colors_used));
    GraphColoringHost_args cont{};
    memory_->copyFromDevice(reinterpret_cast<uint8_t *>(&cont), cont_base,
                            sizeof(cont));
    auto t_result = std::chrono::high_resolution_clock::now();

    uint32_t bad_edges = 0, uncolored = 0;
    bool valid =
        validateColoring(G, got, max_colors_, bad_edges, uncolored);
    uint32_t mismatches = 0;
    for (uint32_t v = 0; v < G.num_vertices; v++)
    {
      if (got[v] != ref.colors[v])
      {
        if (mismatches < 20)
          std::cerr << "[GraphColoring] MISMATCH v=" << v
                    << " fpga=" << got[v]
                    << " gbbs_seeded=" << ref.colors[v] << "\n";
        mismatches++;
      }
    }

    std::cout << "[GraphColoring-FPGA] execution time: "
              << std::chrono::duration<double>(t_kernel_done - t_kernel_start)
                     .count()
              << "s\n";
    std::cout << "[GraphColoring-FPGA] end-to-end time: "
              << std::chrono::duration<double>(t_result - t0).count() << "s\n";
    std::cout << "[GraphColoring-FPGA] colors_used=" << colors_used
              << " finished=" << cont.finished
              << " final_frontier=" << cont.frontier_length
              << " done=" << cont.done
              << " expected_seeded_colors_used=" << ref.colors_used << "\n";
    printColorHistogram("[GraphColoring-FPGA] color summary", got,
                        colors_used);
    printColorHistogram("[GraphColoring-GBBS-style] color summary",
                        ref.colors, ref.colors_used);
    std::cout << "[GraphColoring-GBBS-style] seeded reference colors_used="
              << ref.colors_used << " rounds=" << ref.rounds
              << " last_frontier=" << ref.last_frontier_length
              << " execution time=" << ref.seconds << "s\n";

    if (rc == 0 && valid && mismatches == 0 &&
        colors_used == ref.colors_used && cont.finished == G.num_vertices)
    {
      std::cout << "[GraphColoring] PASS\n";
      return 0;
    }
    std::cerr << "[GraphColoring] FAIL bad_edges=" << bad_edges
              << " uncolored=" << uncolored
              << " mismatches=" << mismatches << "\n";
    return 1;
  }

private:
  std::string graph_file_;
  uint32_t max_colors_;
  uint32_t seed_;
};
