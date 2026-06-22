#pragma once

#include <GraphBenchmarkCommon.h>
#include <benchmarks/GeneralWeightSSSP/BellmanFord/BellmanFord.h>

#include <cstddef>
#include <iomanip>

struct BellmanFord_args
{
  uint32_t counter;
  uint32_t source;
  uint32_t vertex_count;
  uint32_t round;
  uint32_t _placeholder;
  uint32_t frontier_length;
  uint32_t active;
  uint32_t done;
  Addr graph;
  Addr distance;
  Addr relaxed;
  Addr frontier0;
  Addr frontier1;
  Addr nextFChar;
  Addr cont;
  uint8_t _padding[40];
};
static_assert(sizeof(BellmanFord_args) == 128,
              "BellmanFord_args must be 128 bytes");

class BellmanFordDriver : public BenchmarkDriverBase
{
public:
  BellmanFordDriver(Memory *memory, const std::string &graph_file,
                    uint32_t source = 0, double watchdog_s = 600.0,
                    bool fast_mode = false)
      : BenchmarkDriverBase(memory, watchdog_s, fast_mode, "BellmanFord"),
        graph_file_(graph_file), source_(source) {}

  static std::vector<double> runGbbs(const WeightedGraph &G, uint32_t source,
                                     double &seconds)
  {
    auto t0 = std::chrono::high_resolution_clock::now();
    auto gbbs_graph = buildGbbsWeightedFloatGraph(G);
    gbbs::sequence<float> dist;
    {
      ScopedCoutSilencer silence;
      dist = gbbs::BellmanFord(gbbs_graph, (gbbs::uintE)source);
    }
    auto t1 = std::chrono::high_resolution_clock::now();
    seconds = std::chrono::duration<double>(t1 - t0).count();

    std::vector<double> out(G.num_vertices);
    for (uint32_t v = 0; v < G.num_vertices; v++)
      out[v] = (dist[v] == std::numeric_limits<float>::max())
                   ? std::numeric_limits<double>::infinity()
                   : (double)dist[v];
    return out;
  }

  static int run_cpu_test_bench(const std::string &graph_file,
                                uint32_t source = 0)
  {
    WeightedGraph G;
    if (!loadWeightedDirectedCsv(graph_file, source, G))
      return 1;
    std::cout << "[BellmanFord-CPU] vertices=" << G.num_vertices
              << " edges=" << G.num_edges << " source=" << source
              << " format=weighted_csv(src,dst,weight)\n";

    double gbbs_s = 0.0;
    std::vector<double> ref = runGbbs(G, source, gbbs_s);
    printNumericSummary("[BellmanFord-GBBS] distance summary",
                        summarizeNumericVector(ref));
    std::cout << "[BellmanFord-GBBS] execution time: " << gbbs_s << "s\n";
    return 0;
  }

  int run_test_bench() override
  {
    auto t0 = std::chrono::high_resolution_clock::now();
    WeightedGraph G;
    if (!loadWeightedDirectedCsv(graph_file_, source_, G))
      return 1;
    if (G.num_vertices == 0)
    {
      std::cerr << "[BellmanFord] empty graph\n";
      return 1;
    }

    std::cout << "[BellmanFord] vertices=" << G.num_vertices
              << " edges=" << G.num_edges << " source=" << source_
              << " format=weighted_csv(src,dst,weight)\n";

    Addr edges_base = 0;
    Addr graph_base = writeWeightedCsrToHbm(memory_, G, edges_base);
    Addr distance_base =
        memory_->allocateMemFPGA((uint64_t)G.num_vertices * sizeof(float), 512);
    Addr relaxed_base =
        memory_->allocateMemFPGA((uint64_t)G.num_vertices, 512);
    Addr frontier0_base =
        memory_->allocateMemFPGA((uint64_t)G.num_vertices * sizeof(uint32_t),
                                 512);
    Addr frontier1_base =
        memory_->allocateMemFPGA((uint64_t)G.num_vertices * sizeof(uint32_t),
                                 512);
    Addr nextFChar_base = memory_->allocateMemFPGA(sizeof(uint64_t), 512);
    Addr cont_base = memory_->allocateMemFPGA(sizeof(BellmanFord_args), 512);

    std::vector<float> init_dist(G.num_vertices,
                                 std::numeric_limits<float>::infinity());
    std::vector<uint8_t> zeros8(G.num_vertices, 0);
    uint64_t zero64 = 0;
    copyVectorToDevice(memory_, distance_base, init_dist);
    copyVectorToDevice(memory_, relaxed_base, zeros8);
    copyBytesToDevice(memory_, nextFChar_base, &zero64, sizeof(zero64));

    BellmanFord_args root{};
    root.source = source_;
    root.vertex_count = G.num_vertices;
    root.graph = graph_base;
    root.distance = distance_base;
    root.relaxed = relaxed_base;
    root.frontier0 = frontier0_base;
    root.frontier1 = frontier1_base;
    root.nextFChar = nextFChar_base;
    root.cont = cont_base;
    copyBytesToDevice(memory_, cont_base, &root, sizeof(root));

    tuneSchedulerQueueCapacities("BellmanFord", G.num_vertices);
    auto t_kernel_start = std::chrono::high_resolution_clock::now();
    int rc = runRootTask(std::vector<BellmanFord_args>{root}, cont_base,
                         offsetof(BellmanFord_args, done));
    auto t_kernel_done = t_kernel_done_;

    std::vector<float> got(G.num_vertices);
    memory_->copyFromDevice(reinterpret_cast<uint8_t *>(got.data()),
                            distance_base,
                            (uint64_t)got.size() * sizeof(float));
    BellmanFord_args cont{};
    memory_->copyFromDevice(reinterpret_cast<uint8_t *>(&cont), cont_base,
                            sizeof(cont));
    auto t_result = std::chrono::high_resolution_clock::now();

    double gbbs_s = 0.0;
    std::vector<double> ref = runGbbs(G, source_, gbbs_s);
    int mismatches = 0;
    for (uint32_t v = 0; v < G.num_vertices; v++)
    {
      if (!floatDistanceMatch(ref[v], got[v]))
      {
        if (mismatches < 20)
          std::cerr << std::setprecision(8)
                    << "[BellmanFord] MISMATCH v=" << v << " fpga="
                    << got[v] << " gbbs=" << ref[v] << "\n";
        mismatches++;
      }
    }

    std::cout << "[BellmanFord-FPGA] execution time: "
              << std::chrono::duration<double>(t_kernel_done - t_kernel_start)
                     .count()
              << "s\n";
    std::cout << "[BellmanFord-FPGA] end-to-end time: "
              << std::chrono::duration<double>(t_result - t0).count() << "s\n";
    std::cout << "[BellmanFord-FPGA] rounds=" << cont.round
              << " final_frontier=" << cont.frontier_length
              << " done=" << cont.done << "\n";
    printNumericSummary("[BellmanFord-FPGA] distance summary",
                        summarizeNumericVector(got));
    printNumericSummary("[BellmanFord-GBBS] distance summary",
                        summarizeNumericVector(ref));
    std::cout << "[BellmanFord-GBBS] execution time: " << gbbs_s << "s\n";

    if (rc == 0 && mismatches == 0)
    {
      std::cout << "[BellmanFord] PASS\n";
      return 0;
    }
    std::cerr << "[BellmanFord] FAIL mismatches=" << mismatches << "\n";
    return 1;
  }

private:
  std::string graph_file_;
  uint32_t source_;
};
