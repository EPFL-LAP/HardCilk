#pragma once
// ─────────────────────────────────────────────────────────────────────────────
// BFSDriver.h — single-FPGA (no CMAC) host driver for the HardCilk BFS kernel.
//
// Subclasses the plain `hardCilkDriver` (NOT the mFPGA variant): there is one CU
// (BFS_0), one XRT memory view, and the embedded LockServer is internal and
// free-running, so the host never addresses it. The host only has to:
//   1. lay the CSR graph + BFS work buffers into HBM (Visited on an 8-byte
//      stride to match the AMU's full-strobe 64-bit RMW),
//   2. seed the root BFS task into the scheduler queue (via initSystem),
//   3. start the CU and run a management loop that services paused servers,
//      bounded by a wall-clock watchdog so a lock deadlock surfaces as a clear
//      failure instead of a hang,
//   4. read back `distance` and compare against the CPU golden.
//
// The BFS_args / sparse_edgemap_helper_args layouts MUST stay byte-identical to
// hls-processing-elements/mfpga/BFS/util.h.
// ─────────────────────────────────────────────────────────────────────────────

#include <hardCilkDriver.h>
#include <graph.h>

#include <chrono>
#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <thread>
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
  uint32_t counter;          // 0   join counter / done sentinel
  uint32_t source;           // 4
  uint32_t vertex_count;     // 8
  uint32_t currentDistance;  // 12
  uint32_t max_depth;        // 16
  uint32_t frontier_length;  // 20
  uint32_t active;           // 24  ping-pong selector
  uint32_t done;             // 28  set to 1 by the PE on termination
  Addr graph;                // 32  CSR: array of {neighbors_ptr, degree} (16B each)
  Addr distance;             // 40  int32[vertex_count]
  Addr visited;              // 48  uint64[vertex_count]  (8-byte stride!)
  Addr frontier0;            // 56  uint32[vertex_count]
  Addr frontier1;            // 64  uint32[vertex_count]
  Addr nextFChar;            // 72  uint64 atomic counter (next-frontier length)
  Addr cont;                 // 80  continuation closure base (== &this on device)
  uint8_t _padding[40];      // 88..127
};
static_assert(sizeof(BFS_args) == 128, "BFS_args must match util.h (128 bytes)");

// Byte offset of the `done` field inside the continuation closure.
static const Addr BFS_DONE_OFFSET = offsetof(BFS_args, done);

// initSystem requires a condition function pointer; this driver polls the `done`
// field directly (see managementLoopBFS), so the stored condition is unused.
inline bool bfsDoneConditionStub(int32_t /*val*/) { return false; }

class BFSDriver : public hardCilkDriver {
public:
  std::string graph_file_;
  int source_;
  int max_depth_;       // <= 0 means "unbounded" (set to vertex_count)
  double watchdog_s_;   // wall-clock deadline for the management loop

  BFSDriver(Memory *memory, const std::string &graph_file, int source = 0,
            int max_depth = 0, double watchdog_s = 600.0)
      : hardCilkDriver(memory), graph_file_(graph_file), source_(source),
        max_depth_(max_depth), watchdog_s_(watchdog_s) {}

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
    std::cout << "[BFS-CPU] graph setup took "
              << std::chrono::duration<double>(t_loaded - t_load).count()
              << "s\n";

    std::vector<int> dist_ref;
    auto t_bfs = std::chrono::high_resolution_clock::now();
    referenceBFS(G, effective_source, dist_ref, max_depth);
    auto t_done = std::chrono::high_resolution_clock::now();

    std::cout << "[BFS-CPU] reference BFS took "
              << std::chrono::duration<double>(t_done - t_bfs).count()
              << "s\n";
    print_bfs_summary(dist_ref.data(), n, "CPU");
    return 0;
  }

  int run_test_bench() override {
    // ── Load or synthesize the graph ────────────────────────────────────────
    std::string synthetic_name;
    int effective_source = source_;
    Graph G = loadBenchmarkGraph(graph_file_, source_, synthetic_name,
                                 effective_source);
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
    // neighbor id stream (uint32), grouped by vertex == forward_neighbors order.
    std::vector<uint32_t> neighborData(G.forward_neighbors.begin(),
                                       G.forward_neighbors.end());
    const uint64_t neighborBytes =
        std::max<uint64_t>(neighborData.size() * sizeof(uint32_t), sizeof(uint32_t));
    Addr neighbors_base = memory_->allocateMemFPGA(neighborBytes, 512);
    if (!neighborData.empty())
      memory_->copyToDevice(neighbors_base,
                            reinterpret_cast<const uint8_t *>(neighborData.data()),
                            neighborData.size() * sizeof(uint32_t));

    // graph[u] = { neighbors_ptr, degree } — 16 bytes, indexed by (u << 4).
    std::vector<uint64_t> graphEntries(2ull * n);
    for (int u = 0; u < n; u++) {
      uint64_t off = (uint64_t)G.forward_offsets[u] * sizeof(uint32_t);
      graphEntries[2 * u + 0] = neighbors_base + off;  // neighbors_ptr
      graphEntries[2 * u + 1] = (uint64_t)G.getDegree(u);  // degree
    }
    Addr graph_base = memory_->allocateMemFPGA(graphEntries.size() * sizeof(uint64_t), 512);
    memory_->copyToDevice(graph_base,
                          reinterpret_cast<const uint8_t *>(graphEntries.data()),
                          graphEntries.size() * sizeof(uint64_t));

    // ── Work buffers ────────────────────────────────────────────────────────
    Addr distance_base  = memory_->allocateMemFPGA((uint64_t)n * sizeof(int32_t), 512);
    Addr visited_base   = memory_->allocateMemFPGA((uint64_t)n * VISITED_SLOT_BYTES, 512);
    Addr frontier0_base = memory_->allocateMemFPGA((uint64_t)n * sizeof(uint32_t), 512);
    Addr frontier1_base = memory_->allocateMemFPGA((uint64_t)n * sizeof(uint32_t), 512);
    // nextFChar is now a single 64-bit atomic counter (the AMU ADD_ONE handout
    // for next-frontier slots), not a per-vertex flag array. 8 bytes suffice.
    Addr nextFChar_base = memory_->allocateMemFPGA(sizeof(uint64_t), 512);
    Addr cont_base      = memory_->allocateMemFPGA(sizeof(BFS_args), 512);

    // Zero / sentinel init. The PE's init() also does this for the first round,
    // but seeding here keeps the buffers well-defined and zeroes Visited (whose
    // 8-byte slots the AMU test-and-sets).
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
              << " neighbors=0x" << neighbors_base << " distance=0x" << distance_base
              << " visited=0x" << visited_base << " frontier0=0x" << frontier0_base
              << " frontier1=0x" << frontier1_base << " nextFChar=0x" << nextFChar_base
              << " cont=0x" << cont_base << std::dec << "\n";

    // ── Build the root BFS task ─────────────────────────────────────────────
    // currentDistance==0 && frontier_length==0 makes the PE take the init()
    // path (mark the source, build the first frontier). The PE overwrites
    // `counter`/`done` via store_continuation before any helper can decrement
    // the join counter, so the seed values below are just well-defined starting
    // state; `done` starting at 0 is what the watchdog loop relies on.
    BFS_args root{};
    root.counter         = 0;
    root.source          = (uint32_t)effective_source;
    root.vertex_count    = (uint32_t)n;
    root.currentDistance = 0;
    root.max_depth       = (uint32_t)max_depth;
    root.frontier_length = 0;
    root.active          = 0;
    root.done            = 0;
    root.graph           = graph_base;
    root.distance        = distance_base;
    root.visited         = visited_base;
    root.frontier0       = frontier0_base;
    root.frontier1       = frontier1_base;
    root.nextFChar       = nextFChar_base;
    root.cont            = cont_base;

    // Initialize the continuation closure in HBM with the same state.
    memory_->copyToDevice(cont_base, reinterpret_cast<const uint8_t *>(&root),
                          sizeof(root));

    std::vector<BFS_args> base_task_data = {root};

    tuneSchedulerQueueCapacities(n);

    // ── Program management registers and seed the root task ─────────────────
    auto t_init = std::chrono::high_resolution_clock::now();
    initSystem(base_task_data, &bfsDoneConditionStub, /*fpgaId=*/0, /*taskId=*/0,
               /*no_base_task=*/false);
    auto t_started = std::chrono::high_resolution_clock::now();
    std::cout << "[BFS] init took "
              << std::chrono::duration<double>(t_started - t_init).count() << "s\n";

    startSystem();

    // ── Watchdog-bounded management loop ────────────────────────────────────
    int rc = managementLoopBFS(cont_base, visited_base, n);
    if (rc != 0) {
      std::cerr << "[BFS] management loop did not reach done (watchdog/error)\n";
      // fall through and still dump what we have for diagnostics
    }

    // ── Read back distances and validate against the CPU golden ─────────────
    std::vector<int32_t> dist_fpga(n);
    memory_->copyFromDevice(reinterpret_cast<uint8_t *>(dist_fpga.data()),
                            distance_base, (uint64_t)n * sizeof(int32_t));

    std::vector<int> dist_ref;
    referenceBFS(G, effective_source, dist_ref, max_depth);

    int mismatches = 0;
    for (int v = 0; v < n; v++) {
      if (dist_fpga[v] != dist_ref[v]) {
        if (mismatches < 20)
          std::cerr << "[BFS] MISMATCH v=" << v << " fpga=" << dist_fpga[v]
                    << " golden=" << dist_ref[v] << "\n";
        mismatches++;
      }
    }

    print_bfs_summary(dist_fpga.data(), n, "FPGA");
    print_bfs_summary(dist_ref.data(), n, "golden");

    if (mismatches == 0 && rc == 0) {
      std::cout << "[BFS] PASS — FPGA distances match the CPU golden.\n";
      return 0;
    }
    std::cerr << "[BFS] FAIL — " << mismatches << " mismatching vertices.\n";
    return 1;
  }

private:
  static Graph loadBenchmarkGraph(const std::string &graph_file, int source,
                                  std::string &synthetic_name,
                                  int &effective_source) {
    if (isSyntheticStarGraph(graph_file)) {
      synthetic_name = "synthetic:star2m";
      effective_source = 0;
      if (source != effective_source) {
        std::cout << "[BFS] synthetic star graph uses fixed source="
                  << effective_source << " (ignoring requested source="
                  << source << ")\n";
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
                  << effective_source << " (ignoring requested source="
                  << source << ")\n";
      }
      return Graph::fourMillionStar();
    }

    if (isSyntheticWikiMixedGraph(graph_file)) {
      synthetic_name = "synthetic:wikimix";
      effective_source = 0;
      if (source != effective_source) {
        std::cout << "[BFS] synthetic wikimix graph uses fixed source="
                  << effective_source << " (ignoring requested source="
                  << source << ")\n";
      }
      return Graph::wikiMixed();
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

  void tuneSchedulerQueueCapacities(int vertex_count) {
    const uint64_t bfs_queue_entries = 64;
    const uint64_t helper_queue_entries =
        std::max<uint64_t>(64, static_cast<uint64_t>(vertex_count));

    for (auto &task : descriptor.taskDescriptors) {
      uint64_t target = task.name == "BFS" ? bfs_queue_entries
                                           : helper_queue_entries;
      for (auto &config : task.sidesConfigs) {
        if (config.sideType != "scheduler") continue;
        if (config.capacityVirtualQueue <= 0) continue;
        uint64_t old_capacity =
            static_cast<uint64_t>(config.capacityVirtualQueue);
        if (target < old_capacity) {
          config.capacityVirtualQueue = static_cast<int>(target);
          std::cout << "[BFS] scheduler queue cap for " << task.name
                    << ": " << old_capacity << " -> " << target
                    << " entries\n";
        }
      }
    }
  }

  // Drive paused-server management while polling the continuation's `done` flag,
  // bounded by a wall-clock watchdog. Returns 0 on done, -1 on watchdog timeout.
  size_t countVisited(Addr visited_base, int vertex_count) {
    std::vector<uint8_t> visited(vertex_count);
    memory_->copyFromDevice(reinterpret_cast<uint8_t *>(visited.data()),
                            visited_base,
                            (uint64_t)vertex_count * VISITED_SLOT_BYTES);

    size_t count = 0;
    for (uint8_t slot : visited)
      if (slot != 0) count++;
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
    double percent =
        vertex_count == 0 ? 0.0 : (100.0 * (double)visited / (double)vertex_count);
    double elapsed =
        std::chrono::duration<double>(
            std::chrono::high_resolution_clock::now() - start)
            .count();
    std::cout << "[BFS] progress: visited=" << visited << "/" << vertex_count
              << " (" << percent << "%)"
              << " cont.counter=" << cont.counter
              << " dist=" << cont.currentDistance
              << " frontier=" << cont.frontier_length
              << " active=" << cont.active
              << " done=" << cont.done
              << " elapsed=" << elapsed << "s\n";

    // DEBUG: the source helper stashes diagnostics into cont._padding (which
    // starts at byte 88 of BFS_args). Layout written by BFS.cpp:
    //   +88 u  +92 degree  +96 path  +100 locks  +104 winners
    //   +108 appends  +112 first_success  +116 first_current  +120 first_neighbor
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
              << " first_current=" << dbg(116)
              << " first_neighbor=" << dbg(120)
              << " nextFChar_hbm=" << nextfchar_hbm << "\n";
  }

  int managementLoopBFS(Addr cont_base, Addr visited_base, int vertex_count) {
    const auto start = std::chrono::high_resolution_clock::now();
    const auto deadline = std::chrono::high_resolution_clock::now() +
                          std::chrono::duration<double>(watchdog_s_);
    auto next_progress = start;
    uint32_t done = 0;
    uint64_t iters = 0;
    while (true) {
      if (checkPaused() == 0) managePausedServer();

      memory_->copyFromDevice(reinterpret_cast<uint8_t *>(&done),
                              cont_base + BFS_DONE_OFFSET, sizeof(done));
      if (done != 0) {
        printProgress(cont_base, visited_base, vertex_count, start);
        std::cout << "[BFS] done flag set after " << iters << " poll iterations\n";
        return 0;
      }

      auto now = std::chrono::high_resolution_clock::now();
      if (now >= next_progress) {
        printProgress(cont_base, visited_base, vertex_count, start);
        next_progress = now + std::chrono::seconds(2);
      }

      if (now > deadline) {
        std::cerr << "[BFS] WATCHDOG: " << watchdog_s_
                  << "s elapsed without done; possible lock deadlock or stall.\n";
        return -1;
      }
      iters++;
      std::this_thread::sleep_for(std::chrono::microseconds(200));
    }
  }
};
