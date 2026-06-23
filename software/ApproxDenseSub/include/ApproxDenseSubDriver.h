#pragma once

#include <GraphBenchmarkCommon.h>
#include <benchmarks/ApproximateDensestSubgraph/ApproxPeelingBKV12/DensestSubgraph.h>

#include <cstddef>
#include <iomanip>

struct ApproxDenseSubHost_args
{
  uint32_t counter;
  uint32_t source;
  uint32_t vertex_count;
  uint32_t round;
  uint32_t epsilon_bits;
  uint32_t frontier_length;
  uint32_t active;
  uint32_t done;
  Addr graph;
  Addr degree;
  Addr frontier0;
  Addr frontier1;
  Addr frontier2;
  Addr nextFChar;
  Addr cont;
  Addr best_frontier;
  uint64_t best_density_bits;
  uint32_t best_length;
  uint32_t phase; // PHASE_CLASSIFY (0) / PHASE_DECREMENT (1)
  Addr removed_list;
  Addr removedChar;
};
static_assert(sizeof(ApproxDenseSubHost_args) == 128,
              "ApproxDenseSubHost_args must be 128 bytes");

// Speed baseline: the *actual* GBBS WorkEfficientDensestSubgraph (BKV12), used
// only for timing. Accuracy is still validated against the GBBS-style exposed
// reference. Note GBBS reports density in the both-directions convention
// (~2x the exposed reference's edges/|S| density), so its returned value is a
// speed artifact only and is not compared for correctness.
struct RealGbbsDenseSubTiming
{
  double density = 0.0;
  double seconds = 0.0;
};

inline RealGbbsDenseSubTiming
runRealGbbsDensestSubgraph(const UnweightedGraph &G, double epsilon)
{
  // Graph construction is excluded from the timer to match how the FPGA kernel
  // and the exposed reference report algorithm time only.
  auto gbbs_graph = buildGbbsUnweightedGraph(G);
  auto t0 = std::chrono::high_resolution_clock::now();
  double density = gbbs::WorkEfficientDensestSubgraph(gbbs_graph, epsilon);
  auto t1 = std::chrono::high_resolution_clock::now();
  RealGbbsDenseSubTiming out;
  out.density = density;
  out.seconds = std::chrono::duration<double>(t1 - t0).count();
  return out;
}

class ApproxDenseSubDriver : public BenchmarkDriverBase
{
public:
  ApproxDenseSubDriver(Memory *memory, const std::string &graph_file,
                       double epsilon = 0.1, double watchdog_s = 600.0,
                       bool fast_mode = false)
      : BenchmarkDriverBase(memory, watchdog_s, fast_mode, "ApproxDenseSub"),
        graph_file_(graph_file), epsilon_(epsilon) {}

  static int run_cpu_test_bench(const std::string &graph_file,
                                double epsilon = 0.1)
  {
    UnweightedGraph G;
    if (!loadUndirectedGraph(graph_file, G))
      return 1;
    std::cout << "[ApproxDenseSub-CPU] vertices=" << G.num_vertices
              << " input_edges=" << G.num_edges << " epsilon=" << epsilon
              << "\n";
    ApproxDenseSubReference ref =
        runExposedApproxDenseSubReference(G, epsilon);
    std::cout << "[ApproxDenseSub-GBBS-style] exposed-state reference length="
              << ref.best_set.size() << " density=" << ref.best_density
              << " rounds=" << ref.rounds
              << " final_frontier=" << ref.final_length
              << " execution time: " << ref.seconds << "s\n";
    const double epsilon_quantized = (double)fixedU16_16(epsilon) / 65536.0;
    RealGbbsDenseSubTiming gbbs =
        runRealGbbsDensestSubgraph(G, epsilon_quantized);
    std::cout << "[ApproxDenseSub-GBBS] real-GBBS execution time: "
              << gbbs.seconds << "s density=" << gbbs.density
              << " (both-directions convention, speed baseline only)\n";
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
      std::cerr << "[ApproxDenseSub] empty graph\n";
      return 1;
    }
    std::cout << "[ApproxDenseSub] vertices=" << G.num_vertices
              << " input_edges=" << G.num_edges << " epsilon=" << epsilon_
              << "\n";

    Addr neighbors_base = 0;
    Addr graph_base = writeUnweightedCsrToHbm(memory_, G, neighbors_base);
    Addr degree_base =
        memory_->allocateMemFPGA((uint64_t)G.num_vertices * sizeof(uint32_t),
                                 512);
    Addr frontier0_base =
        memory_->allocateMemFPGA((uint64_t)G.num_vertices * sizeof(uint32_t),
                                 512);
    Addr frontier1_base =
        memory_->allocateMemFPGA((uint64_t)G.num_vertices * sizeof(uint32_t),
                                 512);
    Addr frontier2_base =
        memory_->allocateMemFPGA((uint64_t)G.num_vertices * sizeof(uint32_t),
                                 512);
    Addr nextFChar_base = memory_->allocateMemFPGA(sizeof(uint64_t), 512);
    Addr removed_list_base =
        memory_->allocateMemFPGA((uint64_t)G.num_vertices * sizeof(uint32_t),
                                 512);
    Addr removedChar_base = memory_->allocateMemFPGA(sizeof(uint64_t), 512);
    Addr cont_base =
        memory_->allocateMemFPGA(sizeof(ApproxDenseSubHost_args), 512);

    std::vector<uint32_t> degree(G.num_vertices);
    std::vector<uint32_t> frontier(G.num_vertices);
    for (uint32_t v = 0; v < G.num_vertices; v++)
    {
      degree[v] = G.degree(v);
      frontier[v] = v;
    }
    uint64_t zero64 = 0;
    copyVectorToDevice(memory_, degree_base, degree);
    copyVectorToDevice(memory_, frontier0_base, frontier);
    copyBytesToDevice(memory_, nextFChar_base, &zero64, sizeof(zero64));
    copyBytesToDevice(memory_, removedChar_base, &zero64, sizeof(zero64));

    ApproxDenseSubHost_args root{};
    root.vertex_count = G.num_vertices;
    root.epsilon_bits = fixedU16_16(epsilon_);
    root.frontier_length = G.num_vertices;
    root.graph = graph_base;
    root.degree = degree_base;
    root.frontier0 = frontier0_base;
    root.frontier1 = frontier1_base;
    root.frontier2 = frontier2_base;
    root.nextFChar = nextFChar_base;
    root.cont = cont_base;
    root.phase = 0; // PHASE_CLASSIFY
    root.removed_list = removed_list_base;
    root.removedChar = removedChar_base;
    copyBytesToDevice(memory_, cont_base, &root, sizeof(root));

    tuneSchedulerQueueCapacities("ApproxDenseSub", G.num_vertices);
    auto t_kernel_start = std::chrono::high_resolution_clock::now();
    int rc = runRootTask(std::vector<ApproxDenseSubHost_args>{root}, cont_base,
                         offsetof(ApproxDenseSubHost_args, done));
    auto t_kernel_done = t_kernel_done_;

    ApproxDenseSubHost_args cont{};
    memory_->copyFromDevice(reinterpret_cast<uint8_t *>(&cont), cont_base,
                            sizeof(cont));
    std::vector<uint32_t> best_kernel(cont.best_length);
    if (cont.best_length != 0 && cont.best_frontier != 0)
      memory_->copyFromDevice(reinterpret_cast<uint8_t *>(best_kernel.data()),
                              cont.best_frontier,
                              (uint64_t)best_kernel.size() *
                                  sizeof(uint32_t));
    auto t_result = std::chrono::high_resolution_clock::now();

    ApproxDenseSubReference ref =
        runExposedApproxDenseSubReference(G, epsilon_);
    std::sort(ref.best_set.begin(), ref.best_set.end());
    std::sort(best_kernel.begin(), best_kernel.end());
    double fpga_density = fixedU32_32ToDouble(cont.best_density_bits);

    bool density_match = std::fabs(fpga_density - ref.best_density) <= 1e-6;
    bool set_match = best_kernel == ref.best_set;

    // Speed baseline only: time the real GBBS implementation at the same
    // (quantized) epsilon the kernel/reference use. Does not affect PASS/FAIL.
    const double epsilon_quantized = (double)fixedU16_16(epsilon_) / 65536.0;
    RealGbbsDenseSubTiming gbbs =
        runRealGbbsDensestSubgraph(G, epsilon_quantized);
    double kernel_seconds =
        std::chrono::duration<double>(t_kernel_done - t_kernel_start).count();

    std::cout << "[ApproxDenseSub-FPGA] execution time: " << kernel_seconds
              << "s\n";
    std::cout << "[ApproxDenseSub-FPGA] end-to-end time: "
              << std::chrono::duration<double>(t_result - t0).count() << "s\n";
    std::cout << "[ApproxDenseSub-FPGA] best_length=" << cont.best_length
              << " density=" << std::setprecision(8) << fpga_density
              << " rounds=" << cont.round
              << " final_frontier=" << cont.frontier_length
              << " done=" << cont.done << "\n";
    std::cout << "[ApproxDenseSub-GBBS-style] exposed-state reference length="
              << ref.best_set.size() << " density=" << ref.best_density
              << " rounds=" << ref.rounds
              << " final_frontier=" << ref.final_length
              << " execution time: " << ref.seconds << "s\n";
    std::cout << "[ApproxDenseSub-GBBS] real-GBBS execution time: "
              << gbbs.seconds << "s density=" << gbbs.density
              << " (both-directions convention, speed baseline only)\n";
    if (kernel_seconds > 0.0 && gbbs.seconds > 0.0)
      std::cout << "[ApproxDenseSub] speedup FPGA vs real-GBBS: "
                << (gbbs.seconds / kernel_seconds) << "x (kernel "
                << kernel_seconds << "s vs GBBS " << gbbs.seconds << "s)\n";

    if (rc == 0 && density_match && set_match)
    {
      std::cout << "[ApproxDenseSub] PASS\n";
      return 0;
    }
    std::cerr << "[ApproxDenseSub] FAIL expected_length=" << ref.best_set.size()
              << " expected_density=" << ref.best_density
              << " density_match=" << density_match
              << " set_match=" << set_match << "\n";
    return 1;
  }

private:
  std::string graph_file_;
  double epsilon_;
};
