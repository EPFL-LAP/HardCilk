#pragma once

#include <GraphBenchmarkCommon.h>
#include <benchmarks/GeneralWeightSSSP/BellmanFord/BellmanFord.h>
#include <memIO_xrt.h> // XRTMemory + whole-HBM clear helper

#include <cstddef>
#include <cstdlib>
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
                    bool fast_mode = false, uint32_t max_depth = 0)
      : BenchmarkDriverBase(memory, watchdog_s, fast_mode, "BellmanFord"),
        graph_file_(graph_file), source_(source), max_depth_(max_depth) {}

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

    // max_depth is a host-only debugging knob. The kernel is ap_ctrl_none and
    // free-runs to completion, so we cannot stop it at a given depth. Instead,
    // shrink the PROBLEM: keep only the vertices reachable from the source
    // within max_depth hops (computed on the CPU) and the edges induced on that
    // set. The kernel then runs an ordinary full Bellman-Ford on this small
    // subgraph (fast, clean done), and we validate against a full GBBS reference
    // on the *same* subgraph. No hardware change required.
    if (max_depth_ != 0)
    {
      uint32_t orig_v = G.num_vertices;
      uint32_t orig_e = G.num_edges;
      G = restrictToReachable(G, source_, max_depth_);
      std::cout << "[BellmanFord] max_depth=" << max_depth_
                << " restricting to " << max_depth_
                << "-hop neighborhood of source " << source_ << ": "
                << orig_e << " -> " << G.num_edges << " edges over "
                << orig_v << " vertices\n";
    }

    std::cout << "[BellmanFord] vertices=" << G.num_vertices
              << " edges=" << G.num_edges << " source=" << source_
              << " max_depth=" << (max_depth_ == 0 ? "unlimited" : std::to_string(max_depth_))
              << " format=weighted_csv(src,dst,weight)\n";

    // Physically zero the full 16 GiB of HBM before staging any inputs, so a
    // fresh run never reads stale data left in device memory by a previous run
    // (an xrt-smi reset does NOT clear HBM). Mirrors triangleCountDecoupled's
    // clearComputeHBM, but BellmanFord maps its masters across all 32 banks
    // (HBM[0:31]), so clear the whole range.
    clearHBM();

    Addr edges_base = 0;
    Addr graph_base = writeWeightedCsrToHbm(memory_, G, edges_base);
    Addr distance_base =
        memory_->allocateMemFPGA((uint64_t)G.num_vertices * sizeof(float), 512);
    // relaxed[] now stores, per vertex, the last round it was enqueued for (a
    // 4-byte round stamp advanced atomically by SET_IF_GREATER), not a 1-byte
    // flag -- so it needs sizeof(uint32_t) per vertex.
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
    Addr cont_base = memory_->allocateMemFPGA(sizeof(BellmanFord_args), 512);

    std::vector<float> init_dist(G.num_vertices,
                                 std::numeric_limits<float>::infinity());
    std::vector<uint32_t> zeros_relaxed(G.num_vertices, 0);
    uint64_t zero64 = 0;
    copyVectorToDevice(memory_, distance_base, init_dist);
    copyVectorToDevice(memory_, relaxed_base, zeros_relaxed);
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
    // Always wait for the kernel to signal done. With max_depth set, the graph
    // has already been shrunk above, so this converges quickly. Passing 0 here
    // avoids the old behavior of breaking the poll loop early and reading a torn
    // mid-flight snapshot of the still-running (ap_ctrl_none) kernel.
    int rc = runBellmanFordRootTask(root, cont_base, distance_base,
                                    G.num_vertices, 0);
    auto t_kernel_done = t_kernel_done_;

    std::vector<float> got(G.num_vertices);
    memory_->copyFromDevice(reinterpret_cast<uint8_t *>(got.data()),
                            distance_base,
                            (uint64_t)got.size() * sizeof(float));
    BellmanFord_args cont{};
    memory_->copyFromDevice(reinterpret_cast<uint8_t *>(&cont), cont_base,
                            sizeof(cont));
    auto t_result = std::chrono::high_resolution_clock::now();

    // Reference is a full Bellman-Ford on whatever graph we actually ran (the
    // original graph, or the restricted subgraph when max_depth is set).
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
                    << got[v] << " ref=" << ref[v] << "\n";
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
    printNumericSummary("[BellmanFord-REF]  distance summary",
                        summarizeNumericVector(ref));
    std::cout << "[BellmanFord-REF]  execution time: " << gbbs_s << "s\n";

    if (rc == 0 && mismatches == 0)
    {
      std::cout << "[BellmanFord] PASS\n";
      return 0;
    }
    std::cerr << "[BellmanFord] FAIL mismatches=" << mismatches << "\n";
    return 1;
  }

private:
  bool isEmulation() const
  {
    const char *emuMode = std::getenv("XCL_EMULATION_MODE");
    return emuMode != nullptr && emuMode[0] != '\0';
  }

  // Physically zero every HBM bank (banks 0-31 = 16 GiB) so a fresh run never
  // reads stale device contents. No-op (with a warning) if the backing memory
  // is not an XRTMemory (e.g. a software model).
  void clearHBM()
  {
    // Under emulation HBM is already zero-initialized and a full clear is
    // ruinously slow, so skip it entirely. The clear only matters on real
    // hardware, where a previous run can leave stale device contents behind.
    if (isEmulation())
    {
      std::cout << "[BellmanFord] emulation: skipping HBM clear (already 0)\n";
      return;
    }
    XRTMemory *xrtMem = dynamic_cast<XRTMemory *>(memory_);
    if (xrtMem == nullptr)
    {
      std::cerr << "[BellmanFord] memory is not XRTMemory; full HBM clear "
                   "skipped\n";
      return;
    }
    std::cout << "[BellmanFord] clearing 16 GiB HBM (banks 0-31)\n";
    xrtMem->clearHBMBankRange(0, 31);
    std::cout << "[BellmanFord] HBM clear complete\n";
  }

  // Build the subgraph induced by the set of vertices reachable from `source`
  // within `max_depth` hops (unweighted BFS levels). Vertex ids and
  // num_vertices are preserved (so distance[] indexing is unchanged); only
  // edges with BOTH endpoints in the reachable set are kept, and offsets are
  // rebuilt. This is the CPU-computed "slice" used to shrink huge graphs for
  // debugging without touching the kernel.
  static WeightedGraph restrictToReachable(const WeightedGraph &G,
                                           uint32_t source, uint32_t max_depth)
  {
    std::vector<uint8_t> reached(G.num_vertices, 0);
    if (source < G.num_vertices)
    {
      reached[source] = 1;
      std::vector<uint32_t> frontier{source};
      for (uint32_t hop = 0; hop < max_depth && !frontier.empty(); hop++)
      {
        std::vector<uint32_t> next;
        for (uint32_t u : frontier)
          for (uint32_t i = G.offsets[u]; i < G.offsets[u + 1]; i++)
          {
            uint32_t v = G.edges[i].dst;
            if (!reached[v])
            {
              reached[v] = 1;
              next.push_back(v);
            }
          }
        frontier.swap(next);
      }
    }

    WeightedGraph H;
    H.num_vertices = G.num_vertices;
    std::vector<uint32_t> deg(G.num_vertices, 0);
    for (const WeightedEdge &e : G.edges)
      if (reached[e.src] && reached[e.dst])
        deg[e.src]++;

    H.offsets.assign((size_t)G.num_vertices + 1, 0);
    for (uint32_t v = 0; v < G.num_vertices; v++)
      H.offsets[v + 1] = H.offsets[v] + deg[v];
    H.num_edges = H.offsets.back();

    H.edges.resize(H.num_edges);
    std::vector<uint32_t> cursor(H.offsets.begin(), H.offsets.end());
    for (const WeightedEdge &e : G.edges)
      if (reached[e.src] && reached[e.dst])
        H.edges[cursor[e.src]++] = e;
    return H;
  }

  size_t countVisited(Addr distance_base, uint32_t vertex_count)
  {
    std::vector<float> distances(vertex_count);
    memory_->copyFromDevice(reinterpret_cast<uint8_t *>(distances.data()),
                            distance_base,
                            (uint64_t)vertex_count * sizeof(float));

    return std::count_if(distances.begin(), distances.end(),
                         [](float distance) { return std::isfinite(distance); });
  }

  void printProgress(Addr cont_base, Addr distance_base,
                     uint32_t vertex_count,
                     std::chrono::high_resolution_clock::time_point start)
  {
    BellmanFord_args cont{};
    memory_->copyFromDevice(reinterpret_cast<uint8_t *>(&cont), cont_base,
                            sizeof(cont));
    size_t visited = countVisited(distance_base, vertex_count);
    double percent = vertex_count == 0
                         ? 0.0
                         : 100.0 * (double)visited / (double)vertex_count;
    double elapsed = std::chrono::duration<double>(
                         std::chrono::high_resolution_clock::now() - start)
                         .count();
    std::cout << "[BellmanFord] progress: visited=" << visited << "/"
              << vertex_count << " (" << percent << "%)"
              << " round=" << cont.round
              << " frontier=" << cont.frontier_length
              << " active=" << cont.active << " done=" << cont.done
              << " elapsed=" << elapsed << "s\n";
  }

  static std::vector<double> runDepthLimitedReference(
      const WeightedGraph &G, uint32_t source, uint32_t max_depth,
      double &seconds)
  {
    auto t0 = std::chrono::high_resolution_clock::now();
    const double inf = std::numeric_limits<double>::infinity();
    std::vector<double> dist(G.num_vertices, inf);
    if (source < G.num_vertices)
    {
      dist[source] = 0.0;
      for (uint32_t pass = 0; pass < max_depth; pass++)
      {
        bool changed = false;
        for (const WeightedEdge &e : G.edges)
        {
          if (std::isinf(dist[e.src]) && dist[e.src] > 0.0)
            continue;
          double candidate = dist[e.src] + (double)e.weight;
          if (candidate + 1e-12 < dist[e.dst])
          {
            dist[e.dst] = candidate;
            changed = true;
          }
        }
        if (!changed)
          break;
      }
    }
    auto t1 = std::chrono::high_resolution_clock::now();
    seconds = std::chrono::duration<double>(t1 - t0).count();
    return dist;
  }

  int runBellmanFordRootTask(const BellmanFord_args &root, Addr cont_base,
                             Addr distance_base, uint32_t vertex_count,
                             uint32_t max_depth)
  {
    initSystem(std::vector<BellmanFord_args>{root},
               &hardcilkDoneConditionStub, 0, 0, false);
    const auto start = std::chrono::high_resolution_clock::now();
    const auto deadline = start + std::chrono::duration<double>(watchdog_s_);

    // Stall detection: if the kernel makes no observable forward progress for
    // STALL_WINDOW seconds, bail out immediately so a hang is easy to debug
    // (rather than waiting out the full watchdog). "Progress" is any change in
    // the continuation's counters or in the number of finalized distances. This
    // is a debug aid, so it is disabled in fast_mode (no per-poll reads) and
    // relaxed heavily under emulation, where wall-clock time is meaningless.
    const double stall_window_s = isEmulation() ? 120.0 : 1.0;
    const auto sample_period = std::chrono::milliseconds(250);
    auto next_sample = start;
    auto last_change = start;
    // Signature of the last observed progress: (counter, round, frontier_length,
    // active, visited). Seed with a sentinel so the first sample always counts
    // as progress and arms the stall timer from a real baseline.
    bool have_signature = false;
    uint32_t last_counter = 0, last_round = 0, last_frontier = 0, last_active = 0;
    size_t last_visited = 0;

    uint64_t iters = 0;
    startSystem();

    while (true)
    {
      if (!fast_mode_ && checkPaused() == 0)
        managePausedServer();

      uint32_t done = 0;
      memory_->copyFromDevice(reinterpret_cast<uint8_t *>(&done),
                              cont_base + offsetof(BellmanFord_args, done),
                              sizeof(done));

      // Check if FPGA signaled done
      if (done != 0)
      {
        t_kernel_done_ = std::chrono::high_resolution_clock::now();
        if (!fast_mode_)
          printProgress(cont_base, distance_base, vertex_count, start);
        std::cout << "[BellmanFord] done after " << iters << " polls";
        if (fast_mode_)
          std::cout << " (fast mode)";
        std::cout << "\n";
        return 0;
      }

      // Check if we've reached max_depth rounds
      if (max_depth != 0)
      {
        uint32_t round = 0;
        memory_->copyFromDevice(reinterpret_cast<uint8_t *>(&round),
                                cont_base + offsetof(BellmanFord_args, round),
                                sizeof(round));
        if (round >= max_depth)
        {
          t_kernel_done_ = std::chrono::high_resolution_clock::now();
          std::cout << "[BellmanFord] max_depth=" << max_depth
                    << " reached at round=" << round
                    << " after " << iters << " polls, stopping early\n";
          return 0;
        }
      }

      auto now = std::chrono::high_resolution_clock::now();
      if (!fast_mode_ && now >= next_sample)
      {
        next_sample = now + sample_period;

        BellmanFord_args cont{};
        memory_->copyFromDevice(reinterpret_cast<uint8_t *>(&cont), cont_base,
                                sizeof(cont));
        size_t visited = countVisited(distance_base, vertex_count);
        printProgress(cont_base, distance_base, vertex_count, start);

        bool changed = !have_signature || cont.counter != last_counter ||
                       cont.round != last_round ||
                       cont.frontier_length != last_frontier ||
                       cont.active != last_active || visited != last_visited;
        if (changed)
        {
          have_signature = true;
          last_counter = cont.counter;
          last_round = cont.round;
          last_frontier = cont.frontier_length;
          last_active = cont.active;
          last_visited = visited;
          last_change = now;
        }
        else if (std::chrono::duration<double>(now - last_change).count() >=
                 stall_window_s)
        {
          t_kernel_done_ = now;
          std::cerr << "[BellmanFord] STALL: no progress for " << stall_window_s
                    << "s (round=" << cont.round
                    << " frontier=" << cont.frontier_length
                    << " active=" << cont.active << " visited=" << visited
                    << "/" << vertex_count
                    << "). Exiting early for debug.\n";
          return -1;
        }
      }
      if (now > deadline)
      {
        t_kernel_done_ = now;
        std::cerr << "[BellmanFord] WATCHDOG: " << watchdog_s_
                  << "s elapsed without done.\n";
        return -1;
      }

      iters++;
      std::this_thread::sleep_for(fast_mode_
                                      ? std::chrono::milliseconds(10)
                                      : std::chrono::microseconds(200));
    }
  }

  std::string graph_file_;
  uint32_t source_;
  uint32_t max_depth_;
};
