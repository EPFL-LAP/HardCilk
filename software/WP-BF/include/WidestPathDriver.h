#pragma once

#include <GraphBenchmarkCommon.h>
#include <benchmarks/SSWidestPath/JulienneDBS17/SSWidestPath.h>

#include <cstddef>
#include <iomanip>

struct WidestPath_args
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
static_assert(sizeof(WidestPath_args) == 128,
              "WidestPath_args must be 128 bytes");

class WidestPathDriver : public BenchmarkDriverBase
{
public:
  WidestPathDriver(Memory *memory, const std::string &graph_file,
                   uint32_t source = 0, double watchdog_s = 600.0,
                   bool fast_mode = false)
      : BenchmarkDriverBase(memory, watchdog_s, fast_mode, "WP-BF"),
        graph_file_(graph_file), source_(source) {}

  static int run_cpu_test_bench(const std::string &graph_file,
                                uint32_t source = 0)
  {
    WeightedGraph G;
    if (!loadWeightedDirectedCsv(graph_file, source, G))
      return 1;
    std::cout << "[WP-BF-CPU] vertices=" << G.num_vertices
              << " edges=" << G.num_edges << " source=" << source
              << " format=weighted_csv(src,dst,weight)\n";

    auto start = std::chrono::high_resolution_clock::now();
    std::vector<double> ref = referenceWidestPath(G, source);
    auto end = std::chrono::high_resolution_clock::now();
    std::cout << "[WP-BF-CPU] reference time: "
              << std::chrono::duration<double>(end - start).count() << "s\n";

    if (weightedGraphHasIntegerWeights(G))
    {
      double gbbs_s = 0.0;
      (void)runGbbsIntegerReference(G, source, gbbs_s);
      std::cout << "[WP-BF-GBBS] exact integer baseline time: " << gbbs_s
                << "s\n";
    }
    else
    {
      std::cout << "[WP-BF-GBBS] skipped exact GBBS baseline: local GBBS widest"
                   " path is int-weighted, while this HLS kernel uses float"
                   " weights.\n";
    }

    uint32_t finite = 0, unreachable = 0;
    for (double x : ref)
      (std::isinf(x) && x < 0.0) ? unreachable++ : finite++;
    std::cout << "[WP-BF-CPU] finite_or_source=" << finite
              << " unreachable=" << unreachable << "\n";
    printNumericSummary("[WP-BF-CPU] width summary",
                        summarizeNumericVector(ref));
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
      std::cerr << "[WP-BF] empty graph\n";
      return 1;
    }

    std::cout << "[WP-BF] vertices=" << G.num_vertices
              << " edges=" << G.num_edges << " source=" << source_
              << " format=weighted_csv(src,dst,weight)\n";

    Addr edges_base = 0;
    Addr graph_base = writeWeightedCsrToHbm(memory_, G, edges_base);
    Addr distance_base =
        memory_->allocateMemFPGA((uint64_t)G.num_vertices * sizeof(float), 512);
    // relaxed[] stores the last round each vertex was enqueued for (a 4-byte
    // round stamp advanced atomically by SET_IF_GREATER), not a 1-byte flag.
    Addr relaxed_base =
        memory_->allocateMemFPGA((uint64_t)G.num_vertices * sizeof(uint32_t),
                                 512);
    Addr frontier0_base =
        memory_->allocateMemFPGA((uint64_t)G.num_vertices * sizeof(uint32_t),
                                 512);
    Addr frontier1_base =
        memory_->allocateMemFPGA((uint64_t)G.num_vertices * sizeof(uint32_t),
                                 512);
    Addr nextFChar_base = memory_->allocateMemFPGA(sizeof(uint64_t), 512);
    Addr cont_base = memory_->allocateMemFPGA(sizeof(WidestPath_args), 512);

    std::vector<float> init_dist(G.num_vertices,
                                 -std::numeric_limits<float>::infinity());
    std::vector<uint32_t> zeros_relaxed(G.num_vertices, 0);
    uint64_t zero64 = 0;
    copyVectorToDevice(memory_, distance_base, init_dist);
    copyVectorToDevice(memory_, relaxed_base, zeros_relaxed);
    copyBytesToDevice(memory_, nextFChar_base, &zero64, sizeof(zero64));

    WidestPath_args root{};
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

    tuneSchedulerQueueCapacities("WidestPath", G.num_vertices);
    auto t_kernel_start = std::chrono::high_resolution_clock::now();
    int rc = runRootTask(std::vector<WidestPath_args>{root}, cont_base,
                         offsetof(WidestPath_args, done));
    auto t_kernel_done = t_kernel_done_;

    std::vector<float> got(G.num_vertices);
    memory_->copyFromDevice(reinterpret_cast<uint8_t *>(got.data()),
                            distance_base,
                            (uint64_t)got.size() * sizeof(float));
    WidestPath_args cont{};
    memory_->copyFromDevice(reinterpret_cast<uint8_t *>(&cont), cont_base,
                            sizeof(cont));
    auto t_result = std::chrono::high_resolution_clock::now();

    const bool use_gbbs_exact = weightedGraphHasIntegerWeights(G);
    double gbbs_s = 0.0;
    std::vector<double> ref =
        use_gbbs_exact ? runGbbsIntegerReference(G, source_, gbbs_s)
                       : referenceWidestPath(G, source_);
    int mismatches = 0;
    for (uint32_t v = 0; v < G.num_vertices; v++)
    {
      bool match = use_gbbs_exact ? exactIntegerWidthMatch(ref[v], got[v])
                                  : floatDistanceMatch(ref[v], got[v]);
      if (!match)
      {
        if (mismatches < 20)
          std::cerr << std::setprecision(8) << "[WP-BF] MISMATCH v=" << v
                    << " fpga=" << got[v]
                    << (use_gbbs_exact ? " gbbs=" : " ref=") << ref[v]
                    << "\n";
        mismatches++;
      }
    }

    std::cout << "[WP-BF-FPGA] execution time: "
              << std::chrono::duration<double>(t_kernel_done - t_kernel_start)
                     .count()
              << "s\n";
    std::cout << "[WP-BF-FPGA] end-to-end time: "
              << std::chrono::duration<double>(t_result - t0).count() << "s\n";
    std::cout << "[WP-BF-FPGA] rounds=" << cont.round
              << " final_frontier=" << cont.frontier_length
              << " done=" << cont.done << "\n";
    printNumericSummary("[WP-BF-FPGA] width summary",
                        summarizeNumericVector(got));
    printNumericSummary("[WP-BF-REF] width summary",
                        summarizeNumericVector(ref));
    if (use_gbbs_exact)
      std::cout << "[WP-BF-GBBS] exact integer GBBS comparison used. time="
                << gbbs_s << "s\n";
    else
      std::cout << "[WP-BF-GBBS] exact GBBS comparison unavailable for"
                   " non-integer float weights; validation used local float"
                   " reference.\n";

    if (rc == 0 && mismatches == 0)
    {
      std::cout << "[WP-BF] PASS\n";
      return 0;
    }
    std::cerr << "[WP-BF] FAIL mismatches=" << mismatches << "\n";
    return 1;
  }

private:
  static std::vector<double> runGbbsIntegerReference(const WeightedGraph &G,
                                                    uint32_t source,
                                                    double &elapsed_s)
  {
    auto gbbs_graph = buildGbbsWeightedIntGraph(G);
    auto start = std::chrono::high_resolution_clock::now();
    ScopedCoutSilencer silence;
    auto gbbs_width = gbbs::SSWidestPathBF(gbbs_graph, (gbbs::uintE)source);
    auto end = std::chrono::high_resolution_clock::now();
    elapsed_s = std::chrono::duration<double>(end - start).count();

    std::vector<double> out(G.num_vertices,
                            -std::numeric_limits<double>::infinity());
    for (uint32_t v = 0; v < G.num_vertices; v++)
    {
      gbbs::intE width = gbbs_width[v];
      if (width == INT_E_MAX)
        out[v] = std::numeric_limits<double>::infinity();
      else if (width == static_cast<gbbs::intE>(-1))
        out[v] = -std::numeric_limits<double>::infinity();
      else
        out[v] = (double)width;
    }
    return out;
  }

  static bool exactIntegerWidthMatch(double ref, float got)
  {
    if (std::isinf(ref))
      return std::isinf(got) && ((ref < 0.0) == (got < 0.0));
    if (!std::isfinite(got))
      return false;
    return (double)got == ref;
  }

  std::string graph_file_;
  uint32_t source_;
};
