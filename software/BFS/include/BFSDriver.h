#pragma once
// ─────────────────────────────────────────────────────────────────────────────
// BFSDriver.h — single-FPGA (no CMAC) host driver for the HardCilk BFS kernel.
//
// Subclasses the plain `hardCilkDriver` (NOT the mFPGA variant): there is one
// CU (BFS_0), one XRT memory view, and the embedded LockServer is internal and
// free-running, so the host never addresses it. The host only has to:
//   1. lay the CSR graph + BFS work buffers into HBM (Visited on an 8-byte
//      stride to match the AMU's full-strobe 64-bit RMW),
//   2. seed the root BFS task into the scheduler queue (via initSystem),
//   3. start the CU and run a management loop that services paused servers,
//      bounded by a wall-clock watchdog so a lock deadlock surfaces as a clear
//      failure instead of a hang,
//   4. read back `distance` and compare against the official GBBS BFS.
//
// The BFS_args / sparse_edgemap_helper_args layouts MUST stay byte-identical to
// hls-processing-elements/mfpga/BFS/util.h.
// ─────────────────────────────────────────────────────────────────────────────

#include <benchmarks/BFS/NonDeterministicBFS/BFS.h>
#include <graph.h>
#include <hardCilkDriver.h>

#include <algorithm>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <limits>
#include <streambuf>
#include <thread>
#include <tuple>
#include <vector>

using Addr = uint64_t;

// 1-byte Visited slot: the LockServer AMU runs in byte-atomic mode and
// test-and-sets a single byte per request, so each Visited entry is one byte
// (4x less HBM than the 32-bit layout).
static const Addr VISITED_SLOT_BYTES = 1;

// Mirror of hls-processing-elements/mfpga/BFS/util.h (128 bytes). The PE writes
// its continuation closure here field-by-field via store_continuation(); the
// host seeds the root copy and later polls the `done` field of this struct.
struct BFS_args {
  uint32_t counter;         // 0   join counter / done sentinel
  uint32_t source;          // 4
  uint32_t vertex_count;    // 8
  uint32_t currentDistance; // 12
  uint32_t max_depth;       // 16
  uint32_t frontier_length; // 20
  uint32_t active;          // 24  ping-pong selector
  uint32_t done;            // 28  set to 1 by the PE on termination
  Addr graph;           // 32  CSR: array of {neighbors_ptr, degree} (16B each)
  Addr distance;        // 40  int32[vertex_count]
  Addr visited;         // 48  uint64[vertex_count]  (8-byte stride!)
  Addr frontier0;       // 56  uint32[vertex_count]
  Addr frontier1;       // 64  uint32[vertex_count]
  Addr nextFChar;       // 72  uint64 atomic counter (next-frontier length)
  Addr cont;            // 80  continuation closure base (== &this on device)
  uint8_t _padding[40]; // 88..127
};
static_assert(sizeof(BFS_args) == 128,
              "BFS_args must match util.h (128 bytes)");

// Byte offset of the `done` field inside the continuation closure.
static const Addr BFS_DONE_OFFSET = offsetof(BFS_args, done);

// initSystem requires a condition function pointer; this driver polls the
// `done` field directly (see managementLoopBFS), so the stored condition is
// unused.
inline bool bfsDoneConditionStub(int32_t /*val*/) { return false; }

class BFSDriver : public hardCilkDriver {
public:
  std::string graph_file_;
  int source_;
  int max_depth_;     // <= 0 means "unbounded" (set to vertex_count)
  double watchdog_s_; // wall-clock deadline for the management loop
  bool fast_mode_;    // poll only done; skip progress/debug readbacks

  BFSDriver(Memory *memory, const std::string &graph_file, int source = 0,
            int max_depth = 0, double watchdog_s = 600.0,
            bool fast_mode = false)
      : hardCilkDriver(memory), graph_file_(graph_file), source_(source),
        max_depth_(max_depth), watchdog_s_(watchdog_s),
        fast_mode_(fast_mode) {}

  static int run_cpu_test_bench(const std::string &graph_file, int source = 0,
                                int max_depth_arg = 0) {
    std::string synthetic_name;
    int effective_source = source;

    auto t_load = std::chrono::high_resolution_clock::now();
    Graph G = loadBenchmarkGraph(graph_file, source, synthetic_name,
                                 effective_source);
    auto t_loaded = std::chrono::high_resolution_clock::now();

    const int n = G.getNumVertices();
    if (n <= 0) {
      std::cerr << "[BFS-CPU] empty graph, aborting\n";
      return 1;
    }
    const int max_depth = (max_depth_arg <= 0) ? n : max_depth_arg;
    std::cout << "[BFS-CPU] vertices=" << n << " edges=" << G.num_edges
              << " source=" << effective_source << " max_depth=" << max_depth;
    if (!synthetic_name.empty())
      std::cout << " graph=" << synthetic_name;
    std::cout << "\n";
    const double graph_load_s =
        std::chrono::duration<double>(t_loaded - t_load).count();
    std::cout << "[BFS-CPU] graph setup took " << graph_load_s << "s\n";

    OfficialGBBSResult gbbs_result =
        runTimedOfficialGBBSBFS(G, effective_source, max_depth);
    std::vector<int> &dist_ref = gbbs_result.distances;

    std::cout << "[BFS-GBBS] execution time: " << gbbs_result.timing.bfs_s
              << "s\n";
    std::cout << "[BFS-GBBS] end-to-end time: "
              << (graph_load_s + gbbs_result.timing.total_s) << "s\n";
    print_bfs_summary(dist_ref.data(), n, "GBBS");
    return 0;
  }

  int run_test_bench() override {
    auto t_benchmark = std::chrono::high_resolution_clock::now();

    // ── Load or synthesize the graph ────────────────────────────────────────
    std::string synthetic_name;
    int effective_source = source_;
    Graph G = loadBenchmarkGraph(graph_file_, source_, synthetic_name,
                                 effective_source);
    auto t_graph_loaded = std::chrono::high_resolution_clock::now();
    const int n = G.getNumVertices();
    if (n <= 0) {
      std::cerr << "[BFS] empty graph, aborting\n";
      return 1;
    }
    const int max_depth = (max_depth_ <= 0) ? n : max_depth_;
    std::cout << "[BFS] vertices=" << n << " edges=" << G.num_edges
              << " source=" << effective_source << " max_depth=" << max_depth;
    if (!synthetic_name.empty())
      std::cout << " graph=" << synthetic_name;
    std::cout << "\n";

    // ── Lay the CSR graph into HBM ──────────────────────────────────────────
    // neighbor id stream (uint32), grouped by vertex == forward_neighbors
    // order.
    std::vector<uint32_t> neighborData(G.forward_neighbors.begin(),
                                       G.forward_neighbors.end());
    const uint64_t neighborBytes = std::max<uint64_t>(
        neighborData.size() * sizeof(uint32_t), sizeof(uint32_t));
    Addr neighbors_base = memory_->allocateMemFPGA(neighborBytes, 512);
    if (!neighborData.empty())
      memory_->copyToDevice(
          neighbors_base,
          reinterpret_cast<const uint8_t *>(neighborData.data()),
          neighborData.size() * sizeof(uint32_t));

    // graph[u] = { neighbors_ptr, degree } — 16 bytes, indexed by (u << 4).
    std::vector<uint64_t> graphEntries(2ull * n);
    for (int u = 0; u < n; u++) {
      uint64_t off = (uint64_t)G.forward_offsets[u] * sizeof(uint32_t);
      graphEntries[2 * u + 0] = neighbors_base + off;     // neighbors_ptr
      graphEntries[2 * u + 1] = (uint64_t)G.getDegree(u); // degree
    }
    Addr graph_base =
        memory_->allocateMemFPGA(graphEntries.size() * sizeof(uint64_t), 512);
    memory_->copyToDevice(
        graph_base, reinterpret_cast<const uint8_t *>(graphEntries.data()),
        graphEntries.size() * sizeof(uint64_t));

    // ── Work buffers ────────────────────────────────────────────────────────
    Addr distance_base =
        memory_->allocateMemFPGA((uint64_t)n * sizeof(int32_t), 512);
    Addr visited_base =
        memory_->allocateMemFPGA((uint64_t)n * VISITED_SLOT_BYTES, 512);
    Addr frontier0_base =
        memory_->allocateMemFPGA((uint64_t)n * sizeof(uint32_t), 512);
    Addr frontier1_base =
        memory_->allocateMemFPGA((uint64_t)n * sizeof(uint32_t), 512);
    // nextFChar is now a single 64-bit atomic counter (the AMU ADD_ONE handout
    // for next-frontier slots), not a per-vertex flag array. 8 bytes suffice.
    Addr nextFChar_base = memory_->allocateMemFPGA(sizeof(uint64_t), 512);
    Addr cont_base = memory_->allocateMemFPGA(sizeof(BFS_args), 512);

    // Required host-side init. The PE no longer has an init loop (BFS_new.cpp
    // dropped it), so the host is solely responsible for pre-initializing
    // distance to -1, visited to 0, and nextFChar to 0 before the first task.
    {
      std::vector<int32_t> distInit(n, -1);
      memory_->copyToDevice(distance_base,
                            reinterpret_cast<const uint8_t *>(distInit.data()),
                            (uint64_t)n * sizeof(int32_t));
      std::vector<uint8_t> visInit(n, 0);
      memory_->copyToDevice(visited_base,
                            reinterpret_cast<const uint8_t *>(visInit.data()),
                            (uint64_t)n * VISITED_SLOT_BYTES);
      uint64_t nextFCharInit = 0;
      memory_->copyToDevice(nextFChar_base,
                            reinterpret_cast<const uint8_t *>(&nextFCharInit),
                            sizeof(uint64_t));
    }

    std::cout << "[BFS] buffers: graph=0x" << std::hex << graph_base
              << " neighbors=0x" << neighbors_base << " distance=0x"
              << distance_base << " visited=0x" << visited_base
              << " frontier0=0x" << frontier0_base << " frontier1=0x"
              << frontier1_base << " nextFChar=0x" << nextFChar_base
              << " cont=0x" << cont_base << std::dec << "\n";

    // ── Build the root BFS task ─────────────────────────────────────────────
    // currentDistance==0 && frontier_length==0 makes the PE take the init()
    // path (mark the source, build the first frontier). The PE overwrites
    // `counter`/`done` via store_continuation before any helper can decrement
    // the join counter, so the seed values below are just well-defined starting
    // state; `done` starting at 0 is what the watchdog loop relies on.
    BFS_args root{};
    root.counter = 0;
    root.source = (uint32_t)effective_source;
    root.vertex_count = (uint32_t)n;
    root.currentDistance = 0;
    root.max_depth = (uint32_t)max_depth;
    root.frontier_length = 0;
    root.active = 0;
    root.done = 0;
    root.graph = graph_base;
    root.distance = distance_base;
    root.visited = visited_base;
    root.frontier0 = frontier0_base;
    root.frontier1 = frontier1_base;
    root.nextFChar = nextFChar_base;
    root.cont = cont_base;

    // Initialize the continuation closure in HBM with the same state.
    memory_->copyToDevice(cont_base, reinterpret_cast<const uint8_t *>(&root),
                          sizeof(root));

    std::vector<BFS_args> base_task_data = {root};

    tuneSchedulerQueueCapacities(n);

    // ── Program management registers and seed the root task ─────────────────
    auto t_init = std::chrono::high_resolution_clock::now();
    initSystem(base_task_data, &bfsDoneConditionStub, /*fpgaId=*/0,
               /*taskId=*/0,
               /*no_base_task=*/false);
    auto t_started = std::chrono::high_resolution_clock::now();
    std::cout << "[BFS] init took "
              << std::chrono::duration<double>(t_started - t_init).count()
              << "s\n";

    // Kernel-only stopwatch: this brackets startSystem() -> done edge, the FPGA
    // analog of GBBS's bfs_s (graph already resident, source already seeded). It
    // deliberately excludes the buffer copies, the (one-time) initSystem
    // register programming, and the distance readback -- those are the FPGA
    // counterpart of GBBS's excluded graph-build/conversion and live only in the
    // end-to-end number.
    auto t_kernel_start = std::chrono::high_resolution_clock::now();
    startSystem();

    // ── Watchdog-bounded management loop ────────────────────────────────────
    int rc = managementLoopBFS(cont_base, visited_base, n);
    auto t_kernel_done = t_kernel_done_;
    if (rc != 0) {
      std::cerr
          << "[BFS] management loop did not reach done (watchdog/error)\n";
      // fall through and still dump what we have for diagnostics
    }

    // ── Read back distances and validate against the official GBBS BFS ──────
    std::vector<int32_t> dist_fpga(n);
    memory_->copyFromDevice(reinterpret_cast<uint8_t *>(dist_fpga.data()),
                            distance_base, (uint64_t)n * sizeof(int32_t));
    auto t_fpga_result_ready = std::chrono::high_resolution_clock::now();

    OfficialGBBSResult gbbs_result =
        runTimedOfficialGBBSBFS(G, effective_source, max_depth);
    std::vector<int> dist_ref = std::move(gbbs_result.distances);
    const double graph_load_s =
        std::chrono::duration<double>(t_graph_loaded - t_benchmark).count();
    const double fpga_execution_s =
        std::chrono::duration<double>(t_kernel_done - t_kernel_start).count();
    const double fpga_elapsed_s =
        std::chrono::duration<double>(t_fpga_result_ready - t_benchmark)
            .count();
    const double gbbs_elapsed_s = graph_load_s + gbbs_result.timing.total_s;
    std::cout << "[BFS-FPGA] execution time: " << fpga_execution_s << "s\n";
    std::cout << "[BFS-FPGA] end-to-end time: " << fpga_elapsed_s << "s\n";
    std::cout << "[BFS-GBBS] execution time: " << gbbs_result.timing.bfs_s
              << "s\n";
    std::cout << "[BFS-GBBS] end-to-end time: " << gbbs_elapsed_s << "s\n";

    int mismatches = 0;
    for (int v = 0; v < n; v++) {
      if (dist_fpga[v] != dist_ref[v]) {
        if (mismatches < 20)
          std::cerr << "[BFS] MISMATCH v=" << v << " fpga=" << dist_fpga[v]
                    << " gbbs=" << dist_ref[v] << "\n";
        mismatches++;
      }
    }

    print_bfs_summary(dist_fpga.data(), n, "FPGA");
    print_bfs_summary(dist_ref.data(), n, "GBBS");

    if (mismatches == 0 && rc == 0) {
      std::cout << "[BFS] PASS — FPGA distances match official GBBS BFS.\n";
      return 0;
    }
    std::cerr << "[BFS] FAIL — " << mismatches << " mismatching vertices.\n";
    return 1;
  }

private:
  // Set by managementLoopBFS the instant the `done` edge is observed (before any
  // diagnostic readback), so the kernel-only stopwatch in run_test_bench stops
  // at the FPGA's completion rather than after the progress dump.
  std::chrono::high_resolution_clock::time_point t_kernel_done_;

  class NullStreambuf : public std::streambuf {
  protected:
    int_type overflow(int_type ch) override { return traits_type::not_eof(ch); }
  };

  class ScopedCoutSilencer {
  public:
    ScopedCoutSilencer() : previous_(std::cout.rdbuf(&null_)) {}
    ~ScopedCoutSilencer() { std::cout.rdbuf(previous_); }

  private:
    NullStreambuf null_;
    std::streambuf *previous_;
  };

  static Graph loadBenchmarkGraph(const std::string &graph_file, int source,
                                  std::string &synthetic_name,
                                  int &effective_source) {
    if (isSyntheticStarGraph(graph_file)) {
      synthetic_name = "synthetic:star2m";
      effective_source = 0;
      if (source != effective_source) {
        std::cout << "[BFS] synthetic star graph uses fixed source="
                  << effective_source
                  << " (ignoring requested source=" << source << ")\n";
      }
      return Graph::twoMillionTwoLevelStar();
    }

    if (isSyntheticRingGraph(graph_file)) {
      synthetic_name = "synthetic:ring2m";
      effective_source = source;
      return Graph::twoMillionRing();
    }

    if (isSyntheticStar4mGraph(graph_file)) {
      synthetic_name = "synthetic:star4m";
      effective_source = 0;
      if (source != effective_source) {
        std::cout << "[BFS] synthetic star4m graph uses fixed source="
                  << effective_source
                  << " (ignoring requested source=" << source << ")\n";
      }
      return Graph::fourMillionStar();
    }

    if (isSyntheticWikiMixedGraph(graph_file)) {
      synthetic_name = "synthetic:wikimix";
      effective_source = 0;
      if (source != effective_source) {
        std::cout << "[BFS] synthetic wikimix graph uses fixed source="
                  << effective_source
                  << " (ignoring requested source=" << source << ")\n";
      }
      return Graph::wikiMixed();
    }

    if (isSyntheticWikiMixedTargetGraph(graph_file)) {
      synthetic_name = "synthetic:wm_target";
      effective_source = 0;
      if (source != effective_source) {
        std::cout << "[BFS] synthetic wm_target graph uses fixed source="
                  << effective_source
                  << " (ignoring requested source=" << source << ")\n";
      }
      int next_count = 0;
      int pairs = syntheticWikiMixedTargetShape(graph_file, next_count);
      return Graph::wikiMixedTarget(pairs, next_count);
    }

    if (isSyntheticWikiMixedTargetBurstGraph(graph_file)) {
      synthetic_name = "synthetic:wm_target_burst";
      effective_source = 0;
      if (source != effective_source) {
        std::cout << "[BFS] synthetic wm_target_burst graph uses fixed source="
                  << effective_source
                  << " (ignoring requested source=" << source << ")\n";
      }
      int degree_per_frontier = 112;
      int visited_edges_per_frontier = 0;
      int frontier_count = syntheticWikiMixedTargetBurstShape(
          graph_file, degree_per_frontier, visited_edges_per_frontier);
      return Graph::wikiMixedTargetBurst(frontier_count, degree_per_frontier,
                                         visited_edges_per_frontier);
    }

    if (isSyntheticWikiMixedTargetPrefixGraph(graph_file)) {
      synthetic_name = "synthetic:wm_target_prefix";
      effective_source = 0;
      if (source != effective_source) {
        std::cout << "[BFS] synthetic wm_target_prefix graph uses fixed source="
                  << effective_source
                  << " (ignoring requested source=" << source << ")\n";
      }
      int frontier_count = 7529;
      int next_count = 840007;
      int first_level_count = syntheticWikiMixedTargetPrefixShape(
          graph_file, frontier_count, next_count);
      return Graph::wikiMixedTargetPrefix(first_level_count, frontier_count,
                                          next_count);
    }

    synthetic_name.clear();
    effective_source = source;
    return Graph(graph_file, false);
  }

  static bool isSyntheticStarGraph(const std::string &graph_file) {
    return graph_file == "synthetic:star2m" || graph_file == "star2m" ||
           graph_file == "--star2m";
  }

  static bool isSyntheticRingGraph(const std::string &graph_file) {
    return graph_file == "synthetic:ring2m" || graph_file == "ring2m" ||
           graph_file == "--ring2m";
  }

  static bool isSyntheticStar4mGraph(const std::string &graph_file) {
    return graph_file == "synthetic:star4m" || graph_file == "star4m" ||
           graph_file == "--star4m";
  }

  static bool isSyntheticWikiMixedGraph(const std::string &graph_file) {
    return graph_file == "synthetic:wikimix" || graph_file == "wikimix" ||
           graph_file == "--wikimix";
  }

  static bool isSyntheticWikiMixedTargetGraph(const std::string &graph_file) {
    return graph_file == "synthetic:wm_target" || graph_file == "wm_target" ||
           graph_file == "--wm_target" ||
           graph_file.rfind("synthetic:wm_target:", 0) == 0 ||
           graph_file.rfind("wm_target:", 0) == 0;
  }

  static bool
  isSyntheticWikiMixedTargetBurstGraph(const std::string &graph_file) {
    return graph_file == "synthetic:wm_target_burst" ||
           graph_file == "wm_target_burst" ||
           graph_file == "--wm_target_burst" ||
           graph_file.rfind("synthetic:wm_target_burst:", 0) == 0 ||
           graph_file.rfind("wm_target_burst:", 0) == 0;
  }

  static bool
  isSyntheticWikiMixedTargetPrefixGraph(const std::string &graph_file) {
    return graph_file == "synthetic:wm_target_prefix" ||
           graph_file == "wm_target_prefix" ||
           graph_file == "--wm_target_prefix" ||
           graph_file.rfind("synthetic:wm_target_prefix:", 0) == 0 ||
           graph_file.rfind("wm_target_prefix:", 0) == 0;
  }

  static int syntheticWikiMixedTargetShape(const std::string &graph_file,
                                           int &next_count) {
    next_count = 0;
    const std::string prefix =
        graph_file.rfind("synthetic:wm_target:", 0) == 0
            ? "synthetic:wm_target:"
            : (graph_file.rfind("wm_target:", 0) == 0 ? "wm_target:" : "");
    if (prefix.empty())
      return 1;
    std::string params = graph_file.substr(prefix.size());
    size_t split = params.find(':');
    std::string pairs_str =
        split == std::string::npos ? params : params.substr(0, split);
    if (split != std::string::npos)
      next_count = std::atoi(params.substr(split + 1).c_str());
    int pairs = std::atoi(pairs_str.c_str());
    return pairs < 1 ? 1 : pairs;
  }

  static int
  syntheticWikiMixedTargetBurstShape(const std::string &graph_file,
                                     int &degree_per_frontier,
                                     int &visited_edges_per_frontier) {
    degree_per_frontier = 112;
    visited_edges_per_frontier = 0;
    const std::string prefix =
        graph_file.rfind("synthetic:wm_target_burst:", 0) == 0
            ? "synthetic:wm_target_burst:"
            : (graph_file.rfind("wm_target_burst:", 0) == 0 ? "wm_target_burst:"
                                                            : "");
    if (prefix.empty())
      return 7529;
    std::string params = graph_file.substr(prefix.size());
    size_t split = params.find(':');
    std::string frontier_str =
        split == std::string::npos ? params : params.substr(0, split);
    if (split != std::string::npos) {
      std::string rest = params.substr(split + 1);
      size_t split2 = rest.find(':');
      std::string degree_str =
          split2 == std::string::npos ? rest : rest.substr(0, split2);
      degree_per_frontier = std::atoi(degree_str.c_str());
      if (split2 != std::string::npos)
        visited_edges_per_frontier = std::atoi(rest.substr(split2 + 1).c_str());
    }
    int frontier_count = std::atoi(frontier_str.c_str());
    if (degree_per_frontier < 1)
      degree_per_frontier = 1;
    if (visited_edges_per_frontier < 0)
      visited_edges_per_frontier = 0;
    return frontier_count < 1 ? 1 : frontier_count;
  }

  static int syntheticWikiMixedTargetPrefixShape(const std::string &graph_file,
                                                 int &frontier_count,
                                                 int &next_count) {
    frontier_count = 7529;
    next_count = 840007;
    const std::string prefix =
        graph_file.rfind("synthetic:wm_target_prefix:", 0) == 0
            ? "synthetic:wm_target_prefix:"
            : (graph_file.rfind("wm_target_prefix:", 0) == 0
                   ? "wm_target_prefix:"
                   : "");
    if (prefix.empty())
      return 20;

    std::string params = graph_file.substr(prefix.size());
    size_t split = params.find(':');
    std::string first_str =
        split == std::string::npos ? params : params.substr(0, split);
    if (split != std::string::npos) {
      std::string rest = params.substr(split + 1);
      size_t split2 = rest.find(':');
      std::string frontier_str =
          split2 == std::string::npos ? rest : rest.substr(0, split2);
      frontier_count = std::atoi(frontier_str.c_str());
      if (split2 != std::string::npos)
        next_count = std::atoi(rest.substr(split2 + 1).c_str());
    }
    int first_level_count = std::atoi(first_str.c_str());
    if (frontier_count < 1)
      frontier_count = 1;
    if (next_count < 1)
      next_count = 1;
    return first_level_count < 1 ? 1 : first_level_count;
  }

  struct OfficialGBBSTiming {
    double graph_build_s = 0.0;
    double bfs_s = 0.0;
    double distance_conversion_s = 0.0;
    double total_s = 0.0;
  };

  struct OfficialGBBSResult {
    std::vector<int> distances;
    OfficialGBBSTiming timing;
  };

  static auto buildOfficialGBBSGraph(const Graph &G) {
    using GbbsEdge = std::tuple<gbbs::uintE, gbbs::uintE, gbbs::empty>;

    auto edges = gbbs::sequence<GbbsEdge>::uninitialized(
        static_cast<size_t>(G.num_edges));
    size_t out = 0;
    for (int u = 0; u < G.num_vertices; u++) {
      for (int j = G.forward_offsets[u]; j < G.forward_offsets[u + 1]; j++) {
        const int v = G.forward_neighbors[j];
        if (v < 0 || v >= G.num_vertices)
          continue;
        edges[out++] = GbbsEdge{static_cast<gbbs::uintE>(u),
                                static_cast<gbbs::uintE>(v), gbbs::empty{}};
      }
    }
    if (out != edges.size()) {
      auto trimmed = gbbs::sequence<GbbsEdge>::from_function(
          out, [&](size_t i) { return edges[i]; });
      edges = std::move(trimmed);
    }
    return gbbs::asymmetric_graph<gbbs::asymmetric_vertex,
                                  gbbs::empty>::from_edges(edges,
                                                           static_cast<size_t>(
                                                               G.num_vertices));
  }

  static std::vector<int>
  parentsToDistances(const gbbs::sequence<gbbs::uintE> &parents, int source,
                     int max_depth) {
    const int n = static_cast<int>(parents.size());
    std::vector<int> dist(n, -1);
    std::vector<uint8_t> state(n, 0);

    auto compute_distance = [&](int start) {
      int v = start;
      std::vector<int> path;
      while (true) {
        if (v < 0 || v >= n) {
          for (int u : path)
            state[u] = 2;
          return -1;
        }
        if (dist[v] >= 0 || state[v] == 2)
          break;
        if (state[v] == 1) {
          for (int u : path)
            state[u] = 2;
          return -1;
        }
        const gbbs::uintE parent = parents[v];
        if (parent == UINT_E_MAX) {
          for (int u : path)
            state[u] = 2;
          return -1;
        }
        state[v] = 1;
        path.push_back(v);
        if (v == source) {
          dist[v] = 0;
          break;
        }
        if (parent >
            static_cast<gbbs::uintE>(std::numeric_limits<int>::max())) {
          for (int u : path)
            state[u] = 2;
          return -1;
        }
        v = static_cast<int>(parent);
      }

      int next_dist = dist[v];
      if (next_dist < 0) {
        for (int u : path)
          state[u] = 2;
        return -1;
      }
      for (auto it = path.rbegin(); it != path.rend(); ++it) {
        if (*it == v) {
          state[*it] = 2;
          continue;
        }
        dist[*it] = ++next_dist;
        state[*it] = 2;
      }
      return dist[start];
    };

    if (source >= 0 && source < n)
      dist[source] = 0;
    for (int v = 0; v < n; v++) {
      int d = compute_distance(v);
      if (d > max_depth)
        dist[v] = -1;
    }
    return dist;
  }

  static std::vector<int> runOfficialGBBSBFS(const Graph &G, int source,
                                             int max_depth) {
    return runTimedOfficialGBBSBFS(G, source, max_depth).distances;
  }

  static OfficialGBBSResult runTimedOfficialGBBSBFS(const Graph &G, int source,
                                                    int max_depth) {
    OfficialGBBSResult result;
    const auto t0 = std::chrono::high_resolution_clock::now();
    if (source < 0 || source >= G.num_vertices) {
      result.distances = std::vector<int>(G.num_vertices, -1);
      const auto t_done = std::chrono::high_resolution_clock::now();
      result.timing.total_s =
          std::chrono::duration<double>(t_done - t0).count();
      return result;
    }

    auto gbbs_graph = buildOfficialGBBSGraph(G);
    const auto t_graph_done = std::chrono::high_resolution_clock::now();

    gbbs::sequence<gbbs::uintE> parents;
    {
      ScopedCoutSilencer silence_gbbs_progress;
      parents = gbbs::BFS(gbbs_graph, static_cast<gbbs::uintE>(source));
    }
    const auto t_bfs_done = std::chrono::high_resolution_clock::now();

    result.distances = parentsToDistances(parents, source, max_depth);
    const auto t_done = std::chrono::high_resolution_clock::now();

    result.timing.graph_build_s =
        std::chrono::duration<double>(t_graph_done - t0).count();
    result.timing.bfs_s =
        std::chrono::duration<double>(t_bfs_done - t_graph_done).count();
    result.timing.distance_conversion_s =
        std::chrono::duration<double>(t_done - t_bfs_done).count();
    result.timing.total_s = std::chrono::duration<double>(t_done - t0).count();
    return result;
  }

  void tuneSchedulerQueueCapacities(int vertex_count) {
    const uint64_t bfs_queue_entries = 64;
    const uint64_t helper_queue_entries =
        std::max<uint64_t>(64, static_cast<uint64_t>(vertex_count));

    for (auto &task : descriptor.taskDescriptors) {
      uint64_t target =
          task.name == "BFS" ? bfs_queue_entries : helper_queue_entries;
      for (auto &config : task.sidesConfigs) {
        if (config.sideType != "scheduler")
          continue;
        if (config.capacityVirtualQueue <= 0)
          continue;
        uint64_t old_capacity =
            static_cast<uint64_t>(config.capacityVirtualQueue);
        if (target < old_capacity) {
          config.capacityVirtualQueue = static_cast<int>(target);
          std::cout << "[BFS] scheduler queue cap for " << task.name << ": "
                    << old_capacity << " -> " << target << " entries\n";
        }
      }
    }
  }

  // Drive paused-server management while polling the continuation's `done`
  // flag, bounded by a wall-clock watchdog. Returns 0 on done, -1 on watchdog
  // timeout.
  size_t countVisited(Addr visited_base, int vertex_count) {
    std::vector<uint8_t> visited(vertex_count);
    memory_->copyFromDevice(reinterpret_cast<uint8_t *>(visited.data()),
                            visited_base,
                            (uint64_t)vertex_count * VISITED_SLOT_BYTES);

    size_t count = 0;
    for (uint8_t slot : visited)
      if (slot != 0)
        count++;
    return count;
  }

  BFS_args readContinuation(Addr cont_base) {
    BFS_args cont{};
    memory_->copyFromDevice(reinterpret_cast<uint8_t *>(&cont), cont_base,
                            sizeof(cont));
    return cont;
  }

  void printProgress(Addr cont_base, Addr visited_base, int vertex_count,
                     std::chrono::high_resolution_clock::time_point start) {
    size_t visited = countVisited(visited_base, vertex_count);
    BFS_args cont = readContinuation(cont_base);
    double percent = vertex_count == 0
                         ? 0.0
                         : (100.0 * (double)visited / (double)vertex_count);
    double elapsed = std::chrono::duration<double>(
                         std::chrono::high_resolution_clock::now() - start)
                         .count();
    std::cout << "[BFS] progress: visited=" << visited << "/" << vertex_count
              << " (" << percent << "%)"
              << " cont.counter=" << cont.counter
              << " dist=" << cont.currentDistance
              << " frontier=" << cont.frontier_length
              << " active=" << cont.active << " done=" << cont.done
              << " elapsed=" << elapsed << "s\n";

    // DEBUG: the source helper stashes diagnostics into cont._padding (which
    // starts at byte 88 of BFS_args). Layout written by BFS.cpp:
    //   +88 u  +92 degree  +96 path  +100 locks  +104 winners
    //   +108 appends  +112 first_success  +116 first_current  +120
    //   first_neighbor
    const uint8_t *pad = reinterpret_cast<const uint8_t *>(&cont) + 88;
    auto dbg = [&](int byteOff) {
      uint32_t v;
      std::memcpy(&v, pad + (byteOff - 88), sizeof(v));
      return v;
    };
    uint32_t path = dbg(96);
    const char *pathStr = path == 0xE1   ? "over-max-depth"
                          : path == 0xE2 ? "u-out-of-range"
                          : path == 0xE3 ? "degree==0"
                          : path == 0xA0 ? "ran-lock-loop"
                                         : "(unset)";
    // DEBUG: read the atomic counter straight from HBM. If this is nonzero
    // while the BFS PE saw next_length==0, the AMU write landed but the PE read
    // it stale/early; if it's 0, the ADD_ONE write never persisted.
    uint64_t nextfchar_hbm = 0;
    memory_->copyFromDevice(reinterpret_cast<uint8_t *>(&nextfchar_hbm),
                            cont.nextFChar, sizeof(nextfchar_hbm));
    std::cout << "[BFS-DBG] u=" << dbg(88) << " degree=" << dbg(92)
              << " path=0x" << std::hex << path << std::dec << "(" << pathStr
              << ") locks=" << dbg(100) << " winners=" << dbg(104)
              << " appends=" << dbg(108) << " first_success=" << dbg(112)
              << " first_current=" << dbg(116) << " first_neighbor=" << dbg(120)
              << " nextFChar_hbm=" << nextfchar_hbm << "\n";

    dumpSchedulerState();
  }

  // Dump every scheduler server's FIFO occupancy. This localizes a hang: if the
  // helper scheduler still shows currLen>0 while cont.counter is stuck, helper
  // tasks are backed up before PE execution. If the helper scheduler is drained
  // (currLen==0) but cont.counter is still nonzero, the remaining helpers
  // either reached the argOut notification path and wedged there, or their
  // same-address join-counter decrements were lost/collapsed before
  // re-injection. rpause!=0 would mean the host owes the server a resize -- it
  // never should here, but print it so we can rule it out.
  void dumpSchedulerState() {
    for (auto &task : descriptor.taskDescriptors) {
      for (auto base : task.mgmtBaseAddresses.schedulerServersBaseAddresses) {
        uint64_t rpause =
            memory_->readReg64(base + scheduler_server_rpause_shift);
        uint64_t maxLen =
            memory_->readReg64(base + scheduler_server_maxLength_shift);
        uint64_t head =
            memory_->readReg64(base + scheduler_server_fifoHeadReg_shift);
        uint64_t tail =
            memory_->readReg64(base + scheduler_server_fifoTailReg_shift);
        uint64_t curr =
            memory_->readReg64(base + scheduler_server_currLen_shift);
        std::cout << "[BFS-SCHED] task=" << task.name << " base=0x" << std::hex
                  << base << std::dec << " currLen=" << curr << " head=" << head
                  << " tail=" << tail << " maxLen=" << maxLen
                  << " rpause=" << rpause << "\n";
      }
    }
  }

  int managementLoopBFS(Addr cont_base, Addr visited_base, int vertex_count) {
    const auto start = std::chrono::high_resolution_clock::now();
    const auto deadline = std::chrono::high_resolution_clock::now() +
                          std::chrono::duration<double>(watchdog_s_);
    auto next_progress = start;
    uint32_t done = 0;
    uint64_t iters = 0;
    while (true) {
      if (!fast_mode_ && checkPaused() == 0)
        managePausedServer();

      memory_->copyFromDevice(reinterpret_cast<uint8_t *>(&done),
                              cont_base + BFS_DONE_OFFSET, sizeof(done));
      if (done != 0) {
        t_kernel_done_ = std::chrono::high_resolution_clock::now();
        if (!fast_mode_)
          printProgress(cont_base, visited_base, vertex_count, start);
        std::cout << "[BFS] done flag set after " << iters
                  << " poll iterations";
        if (fast_mode_)
          std::cout << " (fast mode)";
        std::cout << "\n";
        return 0;
      }

      auto now = std::chrono::high_resolution_clock::now();
      if (!fast_mode_ && now >= next_progress) {
        printProgress(cont_base, visited_base, vertex_count, start);
        next_progress = now + std::chrono::microseconds(10);
      }

      if (now > deadline) {
        t_kernel_done_ = now;
        std::cerr
            << "[BFS] WATCHDOG: " << watchdog_s_
            << "s elapsed without done; possible lock deadlock or stall.\n";
        return -1;
      }
      iters++;
      std::this_thread::sleep_for(fast_mode_
                                      ? std::chrono::milliseconds(10)
                                      : std::chrono::microseconds(200));
    }
  }
};
