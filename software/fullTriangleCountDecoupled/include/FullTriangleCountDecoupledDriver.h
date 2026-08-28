#pragma once
// ─────────────────────────────────────────────────────────────────────────────
// FullTriangleCountDecoupledDriver.h — single-FPGA host driver for the HardCilk
// fullTriangleCountDecoupled kernels.
//
// Counts, for every vertex v and every neighbour u of v, |adj(v) ∩ adj(u)|.
// Each triangle is therefore counted six times: three choices of base vertex,
// two triangle-neighbours each. The FPGA total divided by six is compared
// against the official GBBS triangle count, and the FPGA is timed against it the
// way BFSDriver does — a kernel-only stopwatch alongside an end-to-end one.
//
// The host owes the kernels three things beyond the usual CSR layout, all of
// them load-bearing:
//   1. every adjacency list is SORTED and free of duplicates and self loops —
//      the adder is a merge, so an unsorted list silently miscounts,
//   2. every list starts on a 64-byte window boundary — the memReader's
//      hls::burst_maxi indexes in windows, so a misaligned base reads the wrong
//      data rather than failing,
//   3. every list is padded by one window — the tail fetch always reads a full
//      ADDER_WINDOW and would otherwise run off the end of the buffer.
//
// The task layouts here MUST stay byte-identical to
// hls-processing-elements/mfpga/fullTriangleCountDecoupled/util.h.
// ─────────────────────────────────────────────────────────────────────────────

#include <hardCilkDriver.h>
#include <util.h>
#include <memIO_xrt.h>
#include <WatcherTelemetry.h>
#include <GraphBenchmarkCommon.h>
#include <benchmarks/TriangleCounting/ShunTangwongsan15/Triangle.h>

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <fstream>
#ifdef MTI_SYSTEMC
#include <experimental/filesystem>
namespace hcfs = std::experimental::filesystem;
#else
#include <filesystem>
namespace hcfs = std::filesystem;
#endif
#include <iostream>
#include <sstream>
#include <string>
#include <thread>
#include <utility>
#include <vector>

using Addr = uint64_t;

static constexpr size_t TELEMETRY_BEAT_BYTES = 32;
static constexpr uint64_t TELEMETRY_IO_CHUNK_BYTES = 64ULL << 20;

// The root task handed to `triangle`. Must match triangle_task in util.h.
struct __attribute__((packed)) FullTriangleCountRootTask
{
  Addr cont;              // unused: completion is per-vertex, in the result array
  Addr adj_list;          // {neighbours, degree} per vertex, 16 bytes each
  Addr triangle_count_arr; // one {done, count} 8-byte word per vertex
  uint32_t first_vertex;   // half-open vertex range [first_vertex, +vertex_count)
  uint32_t vertex_count;
  uint8_t _padding[32];
};

static_assert(sizeof(FullTriangleCountRootTask) == sizeof(triangle_task),
              "host root task must match triangle_task layout");
static_assert(sizeof(FullTriangleCountRootTask) == 64, "root task ABI");

// Each launcher commits its vertex as one 8-byte store: done flag in the high
// word, count in the low word (see adder_unit_launcher's done_word).
static inline bool vertexDone(uint64_t word) { return (word >> 32) != 0; }
static inline uint32_t vertexCount(uint64_t word) { return (uint32_t)word; }

class FullTriangleCountDecoupledDriver : public hardCilkDriver
{
public:
  struct DegreeOrderingTiming
  {
    double graph_build_s = 0.0;  // conversion to GBBS's symmetric graph
    double ordering_s = 0.0;     // GBBS rankNodes + filterGraph
    double materialize_s = 0.0;  // conversion back to the FPGA CSR
    uint64_t directed_edges = 0;
  };

  FullTriangleCountDecoupledDriver(Memory *memory, std::string graph_path,
                                   double watchdog_s = 600.0,
                                   bool fast_mode = false,
                                   std::string xclbin_path = std::string(),
                                   bool legacy_single_port_watcher = false,
                                   bool pause_reset = false,
                                   bool degree_ordering = false)
      : hardCilkDriver(memory), graph_path_(std::move(graph_path)),
        watchdog_s_(watchdog_s), fast_mode_(fast_mode),
        legacy_single_port_watcher_(legacy_single_port_watcher),
        degree_ordering_(degree_ordering),
        pause_reset_(pause_reset || pauseResetFromEnv()),
        xclbin_path_(std::move(xclbin_path)) {}

  // CPU-only mode: load the graph and report what the FPGA should produce.
  static int run_cpu_test_bench(const std::string &graph_path,
                                bool degree_ordering = false)
  {
    UnweightedGraph G;
    if (!loadUndirectedGraph(graph_path, G))
      return 1;
    conditionGraph(G);
    const GbbsTriangleResult gbbs = runTimedOfficialGBBSTriangle(G);
    DegreeOrderingTiming ordering;
    if (degree_ordering)
    {
      UnweightedGraph ordered;
      ordering = degreeOrderGraphWithGBBS(G, ordered);
      G = std::move(ordered);
      printDegreeOrderingTiming(ordering);
    }
    std::vector<uint64_t> per_vertex;
    const uint64_t total = referenceWedgeClosures(G, per_vertex);
    const uint64_t divisor = degree_ordering ? 1 : 6;
    std::cout << "[fullTriangleCountDecoupled-CPU] vertices=" << G.num_vertices
              << " directed_edges=" << G.offsets.back()
              << " wedge_closures=" << total
              << " triangles=" << (total / divisor) << "\n";
    std::cout << "[fullTriangleCountDecoupled-GBBS] triangles="
              << gbbs.triangles << " execution time: " << gbbs.count_s
              << "s (graph build " << gbbs.graph_build_s << "s)\n";
    return (total % divisor == 0 && total / divisor == gbbs.triangles) ? 0 : 1;
  }

  int run_test_bench() override
  {
    auto t0 = std::chrono::high_resolution_clock::now();

    // ── Load and condition the graph ────────────────────────────────────────
    auto t_load = std::chrono::high_resolution_clock::now();
    std::cout << "[fullTriangleCountDecoupled] loading " << graph_path_
              << std::endl;
    UnweightedGraph G;
    if (!loadUndirectedGraph(graph_path_, G))
      return 1;
    std::cout << "[fullTriangleCountDecoupled] sorting/deduplicating "
              << G.num_vertices << " adjacency lists" << std::endl;
    conditionGraph(G);
    auto t_graph_loaded = std::chrono::high_resolution_clock::now();
    const uint32_t n = G.num_vertices;
    if (n == 0)
    {
      std::cerr << "[fullTriangleCountDecoupled] empty graph, aborting\n";
      return 1;
    }
    const uint64_t directed_edges = G.offsets.back();
    const double graph_load_s =
        std::chrono::duration<double>(t_graph_loaded - t_load).count();
    std::cout << "[fullTriangleCountDecoupled] graph=" << graph_path_
              << " vertices=" << n << " directed_edges=" << directed_edges
              << "\n";

    // ── Reference, timed the same way BFSDriver times GBBS ──────────────────
    std::cout << "[fullTriangleCountDecoupled] running the GBBS reference"
              << std::endl;
    const GbbsTriangleResult gbbs = runTimedOfficialGBBSTriangle(G);
    std::cout << "[fullTriangleCountDecoupled-GBBS] execution time: "
              << gbbs.count_s << "s\n";
    std::cout << "[fullTriangleCountDecoupled-GBBS] end-to-end time: "
              << (graph_load_s + gbbs.total_s) << "s\n";
    std::cout << "[fullTriangleCountDecoupled-GBBS] triangles="
              << gbbs.triangles << "\n";

    DegreeOrderingTiming ordering;
    if (degree_ordering_)
    {
      UnweightedGraph ordered;
      ordering = degreeOrderGraphWithGBBS(G, ordered);
      G = std::move(ordered);
      printDegreeOrderingTiming(ordering);
      std::cout << "[fullTriangleCountDecoupled] degree-ordered graph: vertices="
                << G.num_vertices << " directed_edges=" << G.offsets.back()
                << "\n";
    }

    std::cout << "[fullTriangleCountDecoupled] clearing compute HBM"
              << std::endl;
    clearComputeHBM();

    // The watcher owns HBM[16:31] and every compute master HBM[0:15]; keep the
    // two disjoint explicitly or XRT spills small BOs into the watcher banks.
    Addr telemetry_base = reserveTelemetry();
    configureComputeBankRange();

    // ── Lay the CSR into HBM ────────────────────────────────────────────────
    // One flat neighbour buffer, each list window-aligned and window-padded.
    const uint64_t window_bytes = ADDER_WINDOW * sizeof(uint32_t);
    std::vector<uint64_t> list_offset(n, 0);
    uint64_t neighbour_bytes = 0;
    for (uint32_t v = 0; v < n; v++)
    {
      list_offset[v] = neighbour_bytes;
      const uint64_t raw = ((uint64_t)G.degree(v) + ADDER_WINDOW) * sizeof(uint32_t);
      neighbour_bytes += (raw + window_bytes - 1) & ~(window_bytes - 1);
    }

    std::vector<uint32_t> neighbour_image(neighbour_bytes / sizeof(uint32_t), 0);
    for (uint32_t v = 0; v < n; v++)
    {
      uint32_t *dst = neighbour_image.data() + list_offset[v] / sizeof(uint32_t);
      std::copy(G.neighbors.begin() + G.offsets[v],
                G.neighbors.begin() + G.offsets[v + 1], dst);
    }

    // allocateComputeMem aligns to 512, which is a multiple of the window, so
    // every list_offset stays window-aligned once rebased.
    std::cout << "[fullTriangleCountDecoupled] staging " << (neighbour_bytes >> 20)
              << " MiB of adjacency lists into HBM" << std::endl;
    Addr neighbours_base = allocateComputeMem(std::max<uint64_t>(neighbour_bytes, 512), 512);
    memory_->copyToDevice(neighbours_base,
                          reinterpret_cast<const uint8_t *>(neighbour_image.data()),
                          neighbour_bytes);

    // adj_list[u] = { neighbours_ptr, degree } — 16 bytes, exactly BFS's layout
    // and exactly adj_entry / adj_entry_beat in util.h.
    std::vector<uint64_t> adj_entries(2ull * n);
    for (uint32_t v = 0; v < n; v++)
    {
      adj_entries[2 * v + 0] = neighbours_base + list_offset[v];
      adj_entries[2 * v + 1] = G.degree(v);
    }
    const uint64_t staged_bytes =
        neighbour_bytes + adj_entries.size() * sizeof(uint64_t);
    Addr adj_base = allocateComputeMem(adj_entries.size() * sizeof(uint64_t), 512);
    memory_->copyToDevice(adj_base,
                          reinterpret_cast<const uint8_t *>(adj_entries.data()),
                          adj_entries.size() * sizeof(uint64_t));

    // One {done, count} word per vertex, zeroed so `done` starts clear.
    const uint64_t result_bytes = (uint64_t)n * sizeof(uint64_t);
    Addr result_base = allocateComputeMem(result_bytes, 512);
    {
      std::vector<uint64_t> zero(n, 0);
      memory_->copyToDevice(result_base,
                            reinterpret_cast<const uint8_t *>(zero.data()),
                            result_bytes);
    }

    // ── Size the closure pool ───────────────────────────────────────────────
    // The pool recycles in hardware: a continuation's address goes back on the
    // free list the moment it resolves, so the pool only has to hold the peak
    // number alive at once, not every closure the run will take.
    //
    // Peak live cannot be computed from the graph (it depends on how much work
    // the scheduler keeps in flight), so we take the whole-run estimate as an
    // upper bound and cap it at what fits. If that cap is still too small, the
    // allocator pauses and the run fails with the low-water numbers needed to
    // size the next one.
    const uint64_t pool_available =
        (COMPUTE_HBM_BYTES > staged_bytes + result_bytes)
            ? (COMPUTE_HBM_BYTES - staged_bytes - result_bytes)
            : 0;
    // Leave room for the scheduler virtual queues and the allocator's own
    // address FIFO, which initSystem allocates from the same banks.
    const uint64_t pool_budget_bytes = (uint64_t)(pool_available * 0.6);
    // Every continuation type gets its OWN pool of this size (adder and
    // adder_unit_launcher here), so the budget has to be divided between them or
    // the allocation overruns HBM. Split it evenly: nothing predicts the right
    // ratio up front, and the per-pool low-water marks printed at the end of a
    // passing run are how you find it -- one pool near empty while the other
    // barely moved means shift the split.
    //
    // The admission-cap pool is excluded from the split. It is a fixed, small,
    // deliberately-chosen number rather than something sized to fit the graph, so
    // counting it here would shrink the two real pools for no reason.
    uint64_t continuation_pools = 0;
    for (const auto &task : descriptor.taskDescriptors)
      for (const auto &config : task.sidesConfigs)
        if (config.sideType == "allocator" && !isAdmissionCapPool(task.name))
          ++continuation_pools;
    continuation_pools = std::max<uint64_t>(1, continuation_pools);
    const uint64_t budget_closures = std::max<uint64_t>(
        1, pool_budget_bytes / sizeof(counter_continuation) / continuation_pools);

    closure_estimate_ = estimateClosuresByType(G);
    closures_needed_ = closure_estimate_.total();
    const uint64_t whole_run_closures = closures_needed_;
    if (closures_needed_ > budget_closures)
    {
      std::cout << "[fullTriangleCountDecoupled] whole-graph demand "
                << whole_run_closures << " closures ("
                << (whole_run_closures * sizeof(counter_continuation) >> 20)
                << " MiB) exceeds the per-pool budget; allocating the maximum "
                << budget_closures
                << " and relying on recycling. This only fails if more than "
                << budget_closures
                << " closures of one type are ever live at the same time."
                << std::endl;
      closures_needed_ = budget_closures;
    }
    std::cout << "[fullTriangleCountDecoupled] closure pool=" << closures_needed_
              << " per type x " << continuation_pools << " type(s) ("
              << (closures_needed_ * continuation_pools *
                      sizeof(counter_continuation) >> 20)
              << " MiB total), whole-run demand=" << whole_run_closures
              << ", single pass" << std::endl;

    std::vector<FullTriangleCountRootTask> roots(1);
    std::memset(&roots[0], 0, sizeof(roots[0]));
    roots[0].cont = 0; // completion is per-vertex, not a root continuation
    roots[0].adj_list = adj_base;
    roots[0].triangle_count_arr = result_base;
    roots[0].first_vertex = 0;
    roots[0].vertex_count = n;

    if (legacy_single_port_watcher_)
      std::cout << "[telemetry] using legacy single-port watcher layout\n";

    configureInitialQueueCapacities();
    initSystem(roots, &hardcilkDoneConditionStub, /*fpgaId=*/0, /*taskId=*/0,
               /*no_base_task=*/false);

    // Kernel-only stopwatch, the FPGA analog of GBBS's count_s: the graph is
    // already resident and the root task already seeded. It excludes the copies,
    // initSystem, and the readback, which live in the end-to-end number — the
    // same split BFSDriver uses.
    std::cout << "[fullTriangleCountDecoupled] starting kernel" << std::endl;
    auto t_kernel_start = std::chrono::high_resolution_clock::now();
    startSystem();
    const int rc = pollAllVerticesDone(result_base, n, t_kernel_start, 0, n);
    auto t_kernel_done = std::chrono::high_resolution_clock::now();
    const double active_s =
        std::chrono::duration<double>(t_kernel_done - t_kernel_start).count();

    // ── Read back and compare ───────────────────────────────────────────────
    std::vector<uint64_t> words(n, 0);
    memory_->copyFromDevice(reinterpret_cast<uint8_t *>(words.data()),
                            result_base, result_bytes);
    auto t_fpga_result_ready = std::chrono::high_resolution_clock::now();

    uint64_t fpga_total = 0;
    uint32_t not_done = 0;
    for (uint32_t v = 0; v < n; v++)
    {
      if (!vertexDone(words[v]))
        not_done++;
      fpga_total += vertexCount(words[v]);
    }

    const double kernel_s =
        std::chrono::duration<double>(t_kernel_done - t_kernel_start).count();
    const double kernel_active_s = active_s;
    const double fpga_end_to_end_s =
        std::chrono::duration<double>(t_fpga_result_ready - t0).count();
    std::cout << "[fullTriangleCountDecoupled-FPGA] execution time: "
              << kernel_s << "s (single pass)\n";
    std::cout << "[fullTriangleCountDecoupled-FPGA] end-to-end time: "
              << fpga_end_to_end_s << "s\n";
    const double compared_fpga_s =
        kernel_active_s + (degree_ordering_ ? ordering.ordering_s : 0.0);
    if (degree_ordering_)
      std::cout << "[fullTriangleCountDecoupled-FPGA] ordered algorithm time: "
                << ordering.ordering_s << "s ordering + " << kernel_active_s
                << "s FPGA = " << compared_fpga_s << "s\n";
    if (gbbs.count_s > 0.0)
      std::cout << "[fullTriangleCountDecoupled] speedup vs GBBS: execution "
                << (gbbs.count_s / std::max(compared_fpga_s, 1e-12))
                << "x (active), end-to-end "
                << ((graph_load_s + gbbs.total_s) /
                    std::max(fpga_end_to_end_s, 1e-12))
                << "x\n";
    const uint64_t result_divisor = degree_ordering_ ? 1 : 6;
    std::cout << "[fullTriangleCountDecoupled] wedge_closures=" << fpga_total
              << " triangles=" << (fpga_total / result_divisor)
              << " gbbs_triangles=" << gbbs.triangles << "\n";

    if (telemetry_base != 0)
    {
      const char *emu = std::getenv("XCL_EMULATION_MODE");
      const bool hw_emu = emu != nullptr && std::strcmp(emu, "hw_emu") == 0;
      const auto grace = hw_emu ? std::chrono::seconds(10)
                                : std::chrono::milliseconds(1);
      std::cout << "[telemetry] waiting for watcher pipeline to drain ("
                << (hw_emu ? "10 s hw_emu" : "1 ms hardware") << ")\n";
      std::this_thread::sleep_for(grace);
    }
    if (legacy_single_port_watcher_)
      dumpTelemetry(telemetry_base);
    else
      dumpTelemetryTwoPort(telemetry_base);

    const bool counts_match = (fpga_total % result_divisor == 0) &&
                              (fpga_total / result_divisor == gbbs.triangles);
    // capacity - low_water is the peak number of simultaneously live closures this
    // run actually needed. With two continuation types sharing the HBM budget,
    // comparing their two lines is how you tell whether one pool is starved while
    // the other sits mostly idle. Reported on every outcome, not just PASS: a run
    // that dies on closure exhaustion is precisely the one whose pool numbers
    // matter.
    reportContinuationPools("[fullTriangleCountDecoupled]");

    if (rc == 0 && not_done == 0 && counts_match)
    {
      std::cout << "[fullTriangleCountDecoupled] PASS — FPGA count matches "
                   "official GBBS.\n";
      return 0;
    }

    std::cerr << "[fullTriangleCountDecoupled] FAIL rc=" << rc
              << " vertices_not_retired=" << not_done
              << " wedge_closures=" << fpga_total
              << " (expected " << (gbbs.triangles * 6) << ")\n";
    return 1;
  }


private:

  // --- Telemetry layout (must agree with the HW tie-off + connectivity) ---
  // HBM bank 16 base = 16 * 512 MB = 0x2_0000_0000 = start of the watcher's
  // exclusive HBM[16:31] window. The watcher's kernel-side start_addr is tied to
  // 0 (relative to that mapped window), so its bundles land at this global base.
  static constexpr int TELEMETRY_FIRST_BANK = 16;
  static constexpr int COMPUTE_FIRST_BANK = 0;
  static constexpr int COMPUTE_LAST_BANK = 15;
  static constexpr Addr TELEMETRY_GLOBAL_BASE = 0x200000000ULL;
  // Banks 0..15 at 512 MiB each: everything the compute side can address.
  static constexpr uint64_t COMPUTE_HBM_BYTES =
      (uint64_t)(COMPUTE_LAST_BANK - COMPUTE_FIRST_BANK + 1) * (512ULL << 20);
  // The second watcher writer owns HBM[24:31], exactly 4 GiB above port A's
  // HBM[16:23] region. The kernel applies the same offset internally.
  static constexpr Addr TELEMETRY_PORT_STRIDE = 0x100000000ULL;
  static constexpr int INITIAL_SCHEDULER_VIRTUAL_CAPACITY = 32768;
  bool isEmulation() const
  {
    const char *emuMode = std::getenv("XCL_EMULATION_MODE");
    return emuMode != nullptr && !std::string(emuMode).empty();
  }

  static std::string telemetryTimestamp()
  {
    const char *env = std::getenv("HARDCILK_RUN_TIMESTAMP");
    if (env != nullptr && env[0] != '\0')
      return std::string(env);

    char ts[32];
    std::time_t now = std::time(nullptr);
    std::strftime(ts, sizeof(ts), "%Y%m%d_%H%M%S", std::localtime(&now));
    return std::string(ts);
  }

  static std::string telemetryOutputPath()
  {
    const char *configured = std::getenv("HARDCILK_TELEMETRY_DIR");
    const hcfs::path dir =
        configured != nullptr && configured[0] != '\0' ? configured : "/tmp";
    std::error_code ec;
    hcfs::create_directories(dir, ec);
    if (ec)
      std::cerr << "[telemetry] could not create output directory " << dir
                << ": " << ec.message() << "\n";
    return (dir / (std::string("fullTriangleCountDecoupled_telemetry_") +
                   telemetryTimestamp() + ".bin"))
        .string();
  }

  bool isSimBackend() const
  {
    return dynamic_cast<XRTMemory *>(memory_) == nullptr;
  }

  static uint64_t questaSimClearBytes()
  {
    const char *v = std::getenv("HARDCILK_QUESTA_CLEAR_BYTES");
    return (v && *v) ? std::strtoull(v, nullptr, 10) : 0ULL;
  }

  static uint64_t questaSimQueueFloor()
  {
    const char *v = std::getenv("HARDCILK_QUESTA_QUEUE_FLOOR");
    return (v && *v) ? std::strtoull(v, nullptr, 10) : 256ULL;
  }

  uint64_t perRegionWindowBytes() const
  {
    if (isSimBackend())
    {
      const char *w = std::getenv("HARDCILK_QUESTA_TELEMETRY_WINDOW_KB");
      const uint64_t kb = (w && *w) ? std::strtoull(w, nullptr, 10) : 1024ULL;
      return kb << 10;
    }
    return isEmulation() ? (256ULL << 20) : (4ULL << 30);
  }

  uint64_t getTelemetryReserveBytes() const
  {
    if (legacy_single_port_watcher_)
      return isEmulation() ? (256ULL << 20) : (8ULL << 30);
    return TELEMETRY_PORT_STRIDE + perRegionWindowBytes();
  }

  uint64_t getTelemetryWindowBytes() const
  {
    if (legacy_single_port_watcher_)
      return getTelemetryReserveBytes();
    return perRegionWindowBytes();
  }

  Addr allocateComputeMem(uint64_t size, uint64_t alignment)
  {
    XRTMemory *xrtMem = dynamic_cast<XRTMemory *>(memory_);
    if (xrtMem == nullptr)
      return memory_->allocateMemFPGA(size, alignment);
    return xrtMem->allocateMemFPGAInBankRange(size, alignment,
                                              COMPUTE_FIRST_BANK,
                                              COMPUTE_LAST_BANK);
  }

  void clearComputeHBM()
  {
    XRTMemory *xrtMem = dynamic_cast<XRTMemory *>(memory_);
    if (xrtMem == nullptr)
    {
      // Full-HBM clearing through the RTL simulation bridge is impractical. The
      // model normally starts at zero; allow a bounded low-memory clear when a
      // particular simulation needs it forced explicitly.
      const uint64_t bytes = questaSimClearBytes();
      if (bytes == 0)
      {
        std::cout << "[fullTriangleCountDecoupled] (sim) HBM clear skipped (model "
                     "zero-initialises; set HARDCILK_QUESTA_CLEAR_BYTES to force)\n";
        return;
      }
      std::vector<uint8_t> zeros(
          static_cast<size_t>(std::min<uint64_t>(64ULL << 10, bytes)), 0);
      for (uint64_t off = 0; off < bytes; off += zeros.size())
        memory_->copyToDevice(off, zeros.data(),
                              std::min<uint64_t>(zeros.size(), bytes - off));
      std::cout << "[fullTriangleCountDecoupled] (sim) zeroed low HBM window: "
                << (bytes >> 10) << " KB\n";
      return;
    }

    std::cout << "[fullTriangleCountDecoupled] clearing 8 GiB compute HBM "
                 "(banks 0-15)\n";
    xrtMem->clearHBMBankRange(COMPUTE_FIRST_BANK, COMPUTE_LAST_BANK);
    std::cout << "[fullTriangleCountDecoupled] compute HBM clear complete\n";
  }

  // The vertexWriteback pool is the run's admission cap, not a pool sized to fit
  // the graph: how many of these closures exist IS how many vertices may be in
  // flight, because triangle takes one before spawning each launcher and only
  // gets it back when that vertex retires. Every path that would otherwise grow
  // an allocator to fit the workload has to leave this one exactly as the
  // descriptor set it.
  static bool isAdmissionCapPool(const std::string &taskName)
  {
    return taskName == "vertexWriteback";
  }

  void configureInitialQueueCapacities()
  {
    // The allocator pool is monotonic -- nothing is ever recycled -- so it has
    // to hold every closure the whole run will take: one per launcher round plus
    // one per window fetch. closures_needed_ is that bound, computed from the
    // graph in run_test_bench.
    const uint64_t continuationsNeeded = closures_needed_;
    const uint64_t schedulerFloor =
        isSimBackend() ? questaSimQueueFloor()
                       : (uint64_t)INITIAL_SCHEDULER_VIRTUAL_CAPACITY;
    uint64_t allocatorCapacity =
        std::max<uint64_t>(schedulerFloor, continuationsNeeded);

    if (const char *v = std::getenv("FULLTRIANGLECOUNTDECOUPLED_ALLOC_CAPACITY_CAP");
        v && *v)
    {
      const uint64_t cap = std::strtoull(v, nullptr, 10);
      if (cap != 0 && cap < allocatorCapacity)
      {
        std::cout << "[fullTriangleCountDecoupled] CAPPING allocator capacity "
                  << allocatorCapacity << " -> " << cap
                  << " entries (FULLTRIANGLECOUNTDECOUPLED_ALLOC_CAPACITY_CAP)\n";
        allocatorCapacity = cap;
      }
    }

    uint64_t admissionCap = 0;
    for (auto &task : descriptor.taskDescriptors)
    {
      for (auto &config : task.sidesConfigs)
      {
        if (config.sideType == "scheduler")
          config.capacityVirtualQueue = std::max<uint64_t>(
              config.capacityVirtualQueue, schedulerFloor);
        else if (config.sideType == "allocator")
        {
          // The admission-cap pool keeps exactly the size the descriptor gave
          // it. Raising it to fit the graph -- which is what the max() below
          // does for every other pool -- would silently delete the cap, since
          // the cap IS the pool size.
          if (isAdmissionCapPool(task.name))
          {
            admissionCap = config.capacityVirtualQueue;
            continue;
          }
          config.capacityVirtualQueue = std::max<uint64_t>(
              config.capacityVirtualQueue, allocatorCapacity);
        }
      }
    }
    // initSystem doubles capacityVirtualQueue when allocating the backing BO.
    std::cout << "[fullTriangleCountDecoupled] initial scheduler backing capacity: "
              << (2 * schedulerFloor)
              << " entries per server; continuation allocator capacity: "
              << allocatorCapacity << " entries\n";
    if (admissionCap != 0)
      std::cout << "[fullTriangleCountDecoupled] admission cap: " << admissionCap
                << " vertices in flight (vertexWriteback pool, taken from the "
                   "descriptor and NOT resized to fit the graph)\n";
  }

  void configureComputeBankRange()
  {
    if (XRTMemory *xrtMem = dynamic_cast<XRTMemory *>(memory_))
      xrtMem->setDefaultBankRange(COMPUTE_FIRST_BANK, COMPUTE_LAST_BANK);
  }

  // Reserve + zero the telemetry region. Returns the device base address, or 0 if
  // telemetry is unavailable (non-XRT memory / allocation failure) -- the run then
  // proceeds normally without telemetry.
  Addr reserveTelemetry()
  {
    // Do not reserve telemetry HBM for a build that has no watcher.
    //
    // The reservation is TELEMETRY_PORT_STRIDE (4 GiB) from TELEMETRY_FIRST_BANK
    // (16), so it owns banks 16..23 outright. That is harmless while compute
    // allocations stay inside banks 0..15, but under a RAMA per_memory mapping
    // spanning all 32 banks every allocation is striped and needs a slice of EVERY
    // bank, so the first striped allocation dies with "RAMA-striped allocation
    // exhausted an HBM bank" -- which reads like the graph does not fit when it is
    // nothing of the sort.
    //
    // "pes" is the configured physical STATUS-slot table, so an empty or absent
    // array means no watcher was built and there is nothing to reserve for.
    // Reserving anyway could not work in that case either: the span is allocated
    // linearly but addressed through the striped map, so its zero-fill fails and
    // telemetry disables itself after having consumed the 4 GiB.
    //
    // HARDCILK_TELEMETRY=0 forces the same skip when a watcher IS present.
    if (const char *t = std::getenv("HARDCILK_TELEMETRY");
        t != nullptr && (std::string(t) == "0" || std::string(t) == "off"))
    {
      std::cout << "[telemetry] disabled by HARDCILK_TELEMETRY=0; "
                   "no HBM reserved (banks 16+ stay free)\n";
      return 0;
    }
    if (!descriptorHasWatcher())
    {
      std::cout << "[telemetry] descriptor declares no watcher (empty \"pes\"); "
                   "skipping the "
                << (getTelemetryReserveBytes() >> 20)
                << " MiB HBM reservation (banks "
                << TELEMETRY_FIRST_BANK << "+ stay free)\n";
      return 0;
    }

    XRTMemory *xrtMem = dynamic_cast<XRTMemory *>(memory_);
    if (xrtMem == nullptr)
    {
      // Simulation backend (QuestaSim/TLM). The watcher still writes telemetry to
      // the fixed TELEMETRY_GLOBAL_BASE in the HBM model, so telemetry works the
      // same way as on HW -- there is just no bank-pinned allocation to perform
      // (host writes/reads go through the Memory abstraction). Opt-in because the
      // zero-fill + readback traverse the slow DPI bridge.
      if (std::getenv("HARDCILK_QUESTA_TELEMETRY") == nullptr)
      {
        std::cerr << "[telemetry] simulation backend; telemetry disabled "
                     "(set HARDCILK_QUESTA_TELEMETRY=1 to enable)\n";
        return 0;
      }
      const Addr base = TELEMETRY_GLOBAL_BASE;
      const uint64_t windowBytes = getTelemetryWindowBytes();
      std::vector<uint8_t> zeros(
          static_cast<size_t>(std::min<uint64_t>(TELEMETRY_IO_CHUNK_BYTES, windowBytes)), 0);
      auto zeroSimWindow = [&](Addr wbase)
      {
        for (uint64_t off = 0; off < windowBytes; off += zeros.size())
        {
          const uint64_t n = std::min<uint64_t>(zeros.size(), windowBytes - off);
          memory_->copyToDevice(wbase + off, zeros.data(), n);
        }
      };
      zeroSimWindow(base);
      if (!legacy_single_port_watcher_)
        zeroSimWindow(base + TELEMETRY_PORT_STRIDE);
      std::cout << "[telemetry] (sim) region at 0x" << std::hex << base << std::dec
                << "; zeroed " << (windowBytes >> 10) << " KB per port region\n";
      return base;
    }
    try
    {
      uint64_t reserveBytes = getTelemetryReserveBytes();
      uint64_t windowBytes = getTelemetryWindowBytes();
      Addr base = xrtMem->allocateMemFPGASpanFromBank(reserveBytes,
                                                      4096, TELEMETRY_FIRST_BANK);
      if (base != TELEMETRY_GLOBAL_BASE)
      {
        std::cerr << "[telemetry] WARNING reserved base 0x" << std::hex << base
                  << " != expected 0x" << TELEMETRY_GLOBAL_BASE << std::dec
                  << " (watcher start_addr tie-off assumes the expected base)\n";
      }
      std::vector<uint8_t> zeros(
          static_cast<size_t>(std::min<uint64_t>(TELEMETRY_IO_CHUNK_BYTES, windowBytes)),
          0);
      auto zeroWindow = [&](Addr wbase)
      {
        for (uint64_t off = 0; off < windowBytes; off += zeros.size())
        {
          const uint64_t n = std::min<uint64_t>(zeros.size(), windowBytes - off);
          memory_->copyToDevice(wbase + off, zeros.data(), n);
          if (auto *xm = dynamic_cast<XRTMemory *>(memory_))
          {
            try { xm->syncRegionToDevice(wbase + off, n); }
            catch (const std::exception &e)
            { std::cerr << "[telemetry] zero-fill sync threw: " << e.what() << "\n"; }
          }
        }
      };
      if (legacy_single_port_watcher_)
      {
        zeroWindow(base);
        std::cout << "[telemetry] reserved " << (reserveBytes >> 20)
                  << " MB at 0x" << std::hex << base << std::dec
                  << ", zeroed " << (windowBytes >> 10)
                  << " KB legacy single-port readback window\n";
      }
      else
      {
        // Zero BOTH port regions (A at base, B at base + 4 GiB). This keeps stale
        // device contents from masquerading as bundles and gives each region a clean
        // all-zero terminator for the populated-prefix scan.
        zeroWindow(base);
        zeroWindow(base + TELEMETRY_PORT_STRIDE);
        std::cout << "[telemetry] reserved " << (reserveBytes >> 20)
                  << " MB at 0x" << std::hex << base << std::dec
                  << ", zeroed two " << (windowBytes >> 20)
                  << " MB port regions (A@base, B@+4GiB)\n";
      }
      return base;
    }
    catch (const std::exception &e)
    {
      std::cerr << "[telemetry] disabled: " << e.what() << "\n";
      return 0;
    }
  }

  using StatusConservation = hardcilk_telemetry::StatusConservation;

  // STATUS-stream conservation check. For each monitored PE, sum the cycles its
  // input handshake (in_valid & in_ready) and output handshake (out_valid &
  // out_ready) were held, reconstructed from the edge-triggered STATUS samples
  // (each sample's bits hold until the next sample's cycle_count). A memReader PE
  // is strictly 1-task-in / 1-result-out, so its accepts MUST equal its outputs.
  void reportStatusConservation(const std::vector<uint8_t> &buf,
                                size_t firstBundle, size_t lastBundle)
  {
    StatusConservation conservation;
    conservation.consumeTraceBytes(
        buf.data() + firstBundle * TELEMETRY_BEAT_BYTES,
        (lastBundle - firstBundle) * TELEMETRY_BEAT_BYTES);
    conservation.report();
  }

  // When the 64 MB readback window is empty, sparse-scan the FULL telemetry
  // reserve to classify the failure: data found beyond the window => the
  // free-running watcher's write_idx carried over past it (a prior run without an
  // FPGA reprogram) -> reprogram/reset before the run; nothing anywhere => the
  // watcher wrote NOTHING this program cycle (start_gate not firing, reserve base
  // mismatch, or a stalled watcher) -> a real HW/wiring bug, not carryover.
  void diagnoseEmptyTelemetry(Addr telemetry_base)
  {
    const uint64_t reserveBytes = getTelemetryReserveBytes();
    const uint64_t windowBytes = getTelemetryWindowBytes();
    const uint64_t step = 1ULL << 20; // 1 MB probe granularity
    std::vector<uint8_t> probe(TELEMETRY_BEAT_BYTES, 0);
    uint64_t firstDataOff = UINT64_MAX;
    for (uint64_t off = 0; off < reserveBytes; off += step)
    {
      if (auto *xm = dynamic_cast<XRTMemory *>(memory_))
      {
        try { xm->syncRegionFromDevice(telemetry_base + off, TELEMETRY_BEAT_BYTES); }
        catch (...) { continue; }
      }
      try { memory_->copyFromDevice(probe.data(), telemetry_base + off, TELEMETRY_BEAT_BYTES); }
      catch (...) { continue; }
      bool nz = false;
      for (uint8_t b : probe)
        if (b) { nz = true; break; }
      if (nz) { firstDataOff = off; break; }
    }
    if (firstDataOff == UINT64_MAX)
      std::cerr << "[telemetry] DIAGNOSIS: no data anywhere in the "
                << (reserveBytes >> 20) << " MB reserve -> the watcher wrote "
                   "NOTHING this program cycle (start_gate not firing, reserve "
                   "base != 0x2_0000_0000, or watcher stalled). NOT a write_idx "
                   "carryover -- this is a real HW/wiring issue.\n";
    else if (firstDataOff >= windowBytes)
      std::cerr << "[telemetry] DIAGNOSIS: window empty but data exists at reserve "
                   "offset " << (firstDataOff >> 20) << " MB (past the "
                << (windowBytes >> 20) << " MB window) -> the watcher's write_idx "
                   "CARRIED OVER from a prior run. Reprogram/reset the FPGA "
                   "(xrt-smi reset) before the run.\n";
    else
      std::cerr << "[telemetry] DIAGNOSIS: data at offset " << (firstDataOff >> 20)
                << " MB is inside the window yet the prefix scan missed it "
                   "(unexpected -- possible zero-fill/sync race).\n";
  }

  // ==================================================================
  // Two-port telemetry reassembly
  // ==================================================================
  // The watcher writes across two physical HBM ports (memAccess.cpp): bursts
  // ALTERNATE port A (HBM[16:23], region base) and port B (HBM[24:31], base +
  // TELEMETRY_PORT_STRIDE). On HW, concurrent writer channels can reorder
  // timestamps even WITHIN one physical region, so a 2-way merge is insufficient.
  // We rebuild the viewer stream by globally sorting all beats on cycle_count:
  //   * STATUS bundles carry an exact 32-bit per-beat cycle (format.md §3), and the
  //     watcher emits <=1 beat/cycle, so STATUS cycles are unique -> STATUS ordering
  //     is byte-exact and the conservation check (which needs monotonic cycles) holds.
  //   * BW_READ/BW_WRITE bundles carry no timestamp; they inherit the window cycle
  //     from their group's BW_ADDR bundle (or carry-forward). That is exactly how the
  //     viewer time-anchors bandwidth (§4/§5), so a BW sample's 128-cycle window is
  //     preserved. Only the byte-position of a BW group relative to the OTHER region's
  //     same-cycle beats is approximate; a BW group that straddles a 64-beat burst
  //     boundary may split, which the viewer tolerates (self-describing bundles, §7).

  // Cycle key for one 32-byte beat: exact STATUS cycle if present, else the BW_ADDR
  // window cycle, else the carried last-known cycle. Do not assume these keys are
  // monotonic in raw HBM address order: concurrent writer channels can reorder burst
  // contents on HW, so the host globally sorts all keyed beats below.
  static uint64_t beatCycleKey(const uint8_t *beat, uint64_t &carry)
  {
    // Prefer an exact STATUS cycle (unique per beat: <=1 beat/cycle at II=1).
    for (int slot = 0; slot < 2; ++slot)
    {
      const uint8_t *b = beat + slot * 16;
      uint64_t lo = 0, hi = 0;
      for (int i = 0; i < 8; ++i)
      {
        lo |= static_cast<uint64_t>(b[i]) << (8 * i);
        hi |= static_cast<uint64_t>(b[8 + i]) << (8 * i);
      }
      if ((lo & 0xFF) == 1) // STATUS: cycle_count in bits [127:96]
      {
        carry = hi >> 32;
        return carry;
      }
    }
    // Else a BW_ADDR bundle carries the window's final cycle in bits [84:53].
    for (int slot = 0; slot < 2; ++slot)
    {
      const uint8_t *b = beat + slot * 16;
      uint64_t lo = 0, hi = 0;
      for (int i = 0; i < 8; ++i)
      {
        lo |= static_cast<uint64_t>(b[i]) << (8 * i);
        hi |= static_cast<uint64_t>(b[8 + i]) << (8 * i);
      }
      if ((lo & 0xFF) == 8) // BW_ADDR
      {
        carry = (lo >> 53) | ((hi & 0x1FFFFFULL) << 11);
        return carry;
      }
    }
    return carry; // BW_READ / BW_WRITE / NULL: inherit the last known cycle
  }

  // Verify the precondition required by mergeTelemetryByCycle: each raw port region
  // must already be ordered by its embedded cycle key. Multiple concurrent HLS AXI
  // writers can violate that assumption on HW even though the address ranges are
  // allocated in stream order. Report regressions before the host merge hides which
  // physical region they came from.
  static void reportTelemetryRegionOrder(const char *label,
                                         const std::vector<uint8_t> &region)
  {
    const size_t stride = TELEMETRY_BEAT_BYTES;
    const size_t beats = region.size() / stride;
    uint64_t carry = 0, prev = 0;
    uint64_t regressions = 0, equalKeys = 0, maxRegression = 0;
    uint64_t firstFrom = 0, firstTo = 0;
    bool havePrev = false;
    for (size_t i = 0; i < beats; ++i)
    {
      const uint64_t key = beatCycleKey(region.data() + i * stride, carry);
      if (havePrev)
      {
        if (key < prev)
        {
          if (regressions == 0) { firstFrom = prev; firstTo = key; }
          ++regressions;
          maxRegression = std::max(maxRegression, prev - key);
        }
        else if (key == prev)
          ++equalKeys;
      }
      prev = key;
      havePrev = true;
    }
    std::cout << "[telemetry-order-diag] port " << label << " beats=" << beats
              << " timestamp_regressions=" << regressions
              << " equal_keys=" << equalKeys;
    if (regressions != 0)
      std::cout << " first=" << firstFrom << "->" << firstTo
                << " max_regression=" << maxRegression;
    std::cout << "\n";
  }

  // Read one port's telemetry region: chunked sync+copy from device, return the
  // contiguous populated prefix (first non-zero beat up to the next all-zero beat).
  // firstBeatOut = index of that first non-zero beat (0 in the normal
  // reset-before-run case; nonzero means write_idx carried over from a prior run).
  std::vector<uint8_t> readTelemetryRegion(Addr regionBase, uint64_t windowBytes,
                                           uint64_t &firstBeatOut)
  {
    std::vector<uint8_t> data;
    const size_t stride = TELEMETRY_BEAT_BYTES;
    std::vector<uint8_t> buf(static_cast<size_t>(
        std::min<uint64_t>(TELEMETRY_IO_CHUNK_BYTES, windowBytes)));
    bool inRun = false, done = false, sawTerminator = false;
    uint64_t firstBeat = UINT64_MAX;
    uint64_t terminatorBeat = UINT64_MAX;
    uint64_t firstNonzeroAfter = UINT64_MAX;
    uint64_t lastNonzeroAfter = UINT64_MAX;
    uint64_t nonzeroAfter = 0;
    for (uint64_t off = 0; off < windowBytes && !done; off += buf.size())
    {
      const uint64_t n = std::min<uint64_t>(buf.size(), windowBytes - off);
      if (auto *xm = dynamic_cast<XRTMemory *>(memory_))
      {
        try { xm->syncRegionFromDevice(regionBase + off, n); }
        catch (const std::exception &e)
        { std::cerr << "[telemetry] sync-from-device failed: " << e.what() << "\n"; }
      }
      try { memory_->copyFromDevice(buf.data(), regionBase + off, n); }
      catch (const std::exception &e)
      { std::cerr << "[telemetry] readback failed: " << e.what() << "\n"; break; }

      size_t writeBegin = 0, writeEnd = static_cast<size_t>(n);
      bool writeChunk = inRun;
      for (size_t local = 0; local + stride <= n; local += stride)
      {
        bool zero = true;
        for (size_t k = 0; k < stride; ++k)
          if (buf[local + k] != 0) { zero = false; break; }
        const uint64_t beatIdx = (off + local) / stride;
        if (!inRun)
        {
          if (zero) continue;
          inRun = true; writeChunk = true; writeBegin = local; firstBeat = beatIdx;
        }
        else if (!sawTerminator && zero)
        {
          // Keep scanning the remainder of this 64 MiB readback chunk. The watcher
          // has multiple concurrent AXI writers and no drain-complete handshake, so
          // a temporarily unwritten lower-address burst can leave a zero hole in
          // front of valid later telemetry. Preserve the legacy contiguous-prefix
          // return value for now; this pass only diagnoses whether such a tail exists.
          writeEnd = local;
          sawTerminator = true;
          terminatorBeat = beatIdx;
        }
        else if (sawTerminator && !zero)
        {
          if (firstNonzeroAfter == UINT64_MAX)
            firstNonzeroAfter = beatIdx;
          lastNonzeroAfter = beatIdx;
          ++nonzeroAfter;
        }
      }
      if (writeChunk && writeEnd > writeBegin)
        data.insert(data.end(), buf.begin() + writeBegin, buf.begin() + writeEnd);
      if (sawTerminator)
      {
        const uint64_t chunkLastBeat = (off + n) / stride - 1;
        std::cout << "[telemetry-gap-diag] region 0x" << std::hex << regionBase
                  << std::dec << " first zero at beat " << terminatorBeat;
        if (nonzeroAfter != 0)
          std::cout << "; FOUND " << nonzeroAfter
                    << " later nonzero beat(s), first=" << firstNonzeroAfter
                    << " last=" << lastNonzeroAfter;
        else
          std::cout << "; no later nonzero beat through beat " << chunkLastBeat;
        std::cout << "\n";
        done = true;
      }
    }
    firstBeatOut = (firstBeat == UINT64_MAX) ? 0 : firstBeat;
    return data;
  }

  // Globally order both raw regions by cycle key. A conventional 2-way merge is not
  // valid here: HW measurements show timestamp regressions inside each individual
  // region because the concurrent writer channels do not commit burst contents in
  // stream order. STATUS beats carry exact keys, so a stable global sort restores
  // their true order. Equal-key BW/carry beats retain raw A-then-B insertion order.
  std::vector<uint8_t> mergeTelemetryByCycle(const std::vector<uint8_t> &A,
                                             const std::vector<uint8_t> &B)
  {
    const size_t stride = TELEMETRY_BEAT_BYTES;
    const size_t nA = A.size() / stride, nB = B.size() / stride;
    struct KeyedBeat
    {
      uint64_t key;
      const uint8_t *data;
    };
    std::vector<KeyedBeat> beats;
    beats.reserve(nA + nB);
    uint64_t carry = 0;
    for (size_t i = 0; i < nA; ++i)
    {
      const uint8_t *beat = A.data() + i * stride;
      beats.push_back({beatCycleKey(beat, carry), beat});
    }
    carry = 0;
    for (size_t j = 0; j < nB; ++j)
    {
      const uint8_t *beat = B.data() + j * stride;
      beats.push_back({beatCycleKey(beat, carry), beat});
    }

    std::stable_sort(beats.begin(), beats.end(),
                     [](const KeyedBeat &x, const KeyedBeat &y) {
                       return x.key < y.key;
                     });

    std::vector<uint8_t> out;
    out.reserve(A.size() + B.size());
    for (const auto &beat : beats)
      out.insert(out.end(), beat.data, beat.data + stride);
    return out;
  }

  // Two-port dump: read both regions, merge by cycle, write the standard
  // self-describing .bin the viewer already understands (header format unchanged).
  void dumpTelemetryTwoPort(Addr telemetry_base)
  {
    if (telemetry_base == 0)
      return;
    const uint64_t windowBytes = perRegionWindowBytes();
    const Addr baseA = telemetry_base;
    const Addr baseB = telemetry_base + TELEMETRY_PORT_STRIDE;

    uint64_t firstA = 0, firstB = 0;
    std::vector<uint8_t> regionA = readTelemetryRegion(baseA, windowBytes, firstA);
    std::vector<uint8_t> regionB = readTelemetryRegion(baseB, windowBytes, firstB);
    const size_t stride = TELEMETRY_BEAT_BYTES;
    const size_t beatsA = regionA.size() / stride, beatsB = regionB.size() / stride;

    reportTelemetryRegionOrder("A", regionA);
    reportTelemetryRegionOrder("B", regionB);

    if (beatsA == 0 && beatsB == 0)
    {
      std::cout << "[telemetry] both port regions empty (watcher wrote nothing)\n";
      diagnoseEmptyTelemetry(baseA);
      return;
    }
    if (firstA != 0 || firstB != 0)
      std::cout << "[telemetry] populated run does not start at beat 0 (A@" << firstA
                << ", B@" << firstB << ") -- write_idx carried over from a prior run; "
                   "reset the FPGA (xrt-smi reset) for a clean trace\n";

    std::vector<uint8_t> merged = mergeTelemetryByCycle(regionA, regionB);
    const size_t written = merged.size() / stride;

    std::string path = telemetryOutputPath();
    std::ofstream out(path, std::ios::binary);
    if (!out)
    {
      std::cerr << "[telemetry] could not open " << path << " for writing\n";
      return;
    }

    // Self-describing HBM-port descriptor header (traceViewer/format.md §0), same as
    // the single-port path.
    std::vector<hardcilk_telemetry::WatcherPe> watcherPes;
    {
      std::vector<std::string> candidates = hbmDescriptorCandidates();
      std::string descPath, descJson, triedPaths;
      for (const auto &c : candidates)
      {
        if (!triedPaths.empty()) triedPaths += ", ";
        triedPaths += c;
        std::ifstream df(c, std::ios::binary);
        if (df)
        {
          std::ostringstream ss; ss << df.rdbuf();
          descJson = ss.str(); descPath = c; break;
        }
      }
      if (!descJson.empty())
      {
        const uint64_t jlen = descJson.size();
        uint64_t beats_off = (32 + jlen + 31) & ~uint64_t(31);
        char hdr[32] = {0};
        std::memcpy(hdr, "HCKTRACE", 8);
        uint32_t ver = 2;
        uint32_t flags = isEmulation() ? 0x1u : 0x0u;
        std::memcpy(hdr + 8, &ver, 4);
        std::memcpy(hdr + 12, &flags, 4);
        std::memcpy(hdr + 16, &jlen, 8);
        std::memcpy(hdr + 24, &beats_off, 8);
        out.write(hdr, 32);
        out.write(descJson.data(), static_cast<std::streamsize>(jlen));
        const uint64_t padBytes = beats_off - 32 - jlen;
        if (padBytes)
        {
          std::vector<char> pad(padBytes, 0);
          out.write(pad.data(), static_cast<std::streamsize>(padBytes));
        }
        std::cout << "[telemetry] embedded HBM port descriptor (" << jlen
                  << " bytes) from " << descPath << "\n";
        watcherPes = hardcilk_telemetry::parseWatcherPes(descJson);
      }
      else
      {
        std::cerr
            << "\n"
            << "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n"
            << "!!!  WARNING: HBM PORT DESCRIPTOR NOT FOUND -- TRACE IS UNLABELED !!!\n"
            << "!!!  looked for: " << triedPaths << "\n"
            << "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n\n";
      }
    }

    out.write(reinterpret_cast<const char *>(merged.data()),
              static_cast<std::streamsize>(merged.size()));
    out.close();

    StatusConservation conservation(watcherPes);
    conservation.consumeTraceBytes(merged.data(), merged.size());

    std::cout << "[telemetry] merged " << beatsA << " (port A) + " << beatsB
              << " (port B) = " << written << " beats (" << merged.size()
              << " bytes) to:\n[telemetry] " << path << "\n";
    conservation.report();
  }

  // Legacy single-port watcher dump path. Used when running old xclbins built
  // before the watcher split telemetry across port A and port B.
  // Read back the telemetry window, find the populated prefix (up to the first
  // all-zero/unwritten bundle), and write it to a timestamped /tmp file.
  void dumpTelemetry(Addr telemetry_base)
  {
    if (telemetry_base == 0)
      return;

    if (getTelemetryWindowBytes() > TELEMETRY_IO_CHUNK_BYTES)
    {
      const uint64_t windowBytes = getTelemetryWindowBytes();
      const size_t stride = TELEMETRY_BEAT_BYTES;
      std::vector<uint8_t> buf(static_cast<size_t>(TELEMETRY_IO_CHUNK_BYTES));

      std::string path = telemetryOutputPath();

      std::ofstream out(path, std::ios::binary);
      if (!out)
      {
        std::cerr << "[telemetry] could not open " << path << " for writing\n";
        return;
      }

      // Derived PE table (count + slot + labels) for the STATUS conservation check;
      // populated from the descriptor below so switching PE counts needs no host edit.
      std::vector<hardcilk_telemetry::WatcherPe> watcherPes;
      {
        std::vector<std::string> candidates = hbmDescriptorCandidates();
        std::string descPath, descJson, triedPaths;
        for (const auto &c : candidates)
        {
          if (!triedPaths.empty())
            triedPaths += ", ";
          triedPaths += c;
          std::ifstream df(c, std::ios::binary);
          if (df)
          {
            std::ostringstream ss;
            ss << df.rdbuf();
            descJson = ss.str();
            descPath = c;
            break;
          }
        }
        if (!descJson.empty())
        {
          const uint64_t jlen = descJson.size();
          uint64_t beats_off = (32 + jlen + 31) & ~uint64_t(31);
          char hdr[32] = {0};
          std::memcpy(hdr, "HCKTRACE", 8);
          uint32_t ver = 2;
          // offset 12: u32 flags. bit0 = run mode (1 = hw_emu/sw_emu, 0 = real HW).
          uint32_t flags = isEmulation() ? 0x1u : 0x0u;
          std::memcpy(hdr + 8, &ver, 4);
          std::memcpy(hdr + 12, &flags, 4);
          std::memcpy(hdr + 16, &jlen, 8);
          std::memcpy(hdr + 24, &beats_off, 8);
          out.write(hdr, 32);
          out.write(descJson.data(), static_cast<std::streamsize>(jlen));
          const uint64_t padBytes = beats_off - 32 - jlen;
          if (padBytes)
          {
            std::vector<char> pad(padBytes, 0);
            out.write(pad.data(), static_cast<std::streamsize>(padBytes));
          }
          std::cout << "[telemetry] embedded HBM port descriptor (" << jlen
                    << " bytes) from " << descPath << "\n";
          watcherPes = hardcilk_telemetry::parseWatcherPes(descJson);
        }
        else
        {
          std::cerr
              << "\n"
              << "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n"
              << "!!!  WARNING: HBM PORT DESCRIPTOR NOT FOUND -- TRACE IS UNLABELED !!!\n"
              << "!!!  looked for: " << triedPaths << "\n"
              << "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n\n";
        }
      }

      bool inRun = false;
      bool done = false;
      uint64_t firstBundle = UINT64_MAX;
      uint64_t written = 0;
      StatusConservation conservation(watcherPes);
      for (uint64_t off = 0; off < windowBytes && !done; off += buf.size())
      {
        const uint64_t n = std::min<uint64_t>(buf.size(), windowBytes - off);
        if (auto *xrtMem = dynamic_cast<XRTMemory *>(memory_))
        {
          try { xrtMem->syncRegionFromDevice(telemetry_base + off, n); }
          catch (const std::exception &e)
          { std::cerr << "[telemetry] sync-from-device failed: " << e.what() << "\n"; }
        }
        try
        {
          memory_->copyFromDevice(buf.data(), telemetry_base + off, n);
        }
        catch (const std::exception &e)
        {
          std::cerr << "[telemetry] readback failed: " << e.what() << "\n";
          return;
        }

        size_t writeBegin = 0;
        size_t writeEnd = static_cast<size_t>(n);
        bool writeChunk = inRun;
        for (size_t local = 0; local + stride <= n; local += stride)
        {
          bool zero = true;
          for (size_t k = 0; k < stride; ++k)
            if (buf[local + k] != 0)
            {
              zero = false;
              break;
            }
          const uint64_t globalBundle = (off + local) / stride;
          if (!inRun)
          {
            if (zero)
              continue;
            inRun = true;
            writeChunk = true;
            writeBegin = local;
            firstBundle = globalBundle;
          }
          else if (zero)
          {
            writeEnd = local;
            done = true;
            break;
          }
        }
        if (writeChunk && writeEnd > writeBegin)
        {
          out.write(reinterpret_cast<const char *>(buf.data() + writeBegin),
                    static_cast<std::streamsize>(writeEnd - writeBegin));
          conservation.consumeTraceBytes(buf.data() + writeBegin,
                                         writeEnd - writeBegin);
          written += (writeEnd - writeBegin) / stride;
        }
      }

      if (firstBundle == UINT64_MAX)
      {
        std::cout << "[telemetry] window empty (watcher wrote nothing in [base, "
                     "base+window)\n";
        diagnoseEmptyTelemetry(telemetry_base);
      }
      else if (firstBundle != 0)
        std::cout << "[telemetry] populated run starts at beat " << firstBundle
                  << " (byte offset " << (firstBundle * stride)
                  << ") -- watcher write_idx carried over from a prior run\n";

      out.close();
      std::cout << "[telemetry] wrote " << written << " beats ("
                << (written * stride) << " bytes) to:\n"
                << "[telemetry] " << path << "\n";
      conservation.report();
      return;
    }

    std::vector<uint8_t> buf(getTelemetryWindowBytes());
    // The watcher writes its bundles directly to device HBM (it reaches the
    // telemetry BO by hardcoded address, not as a kernel argument), so the host
    // backing is stale until we DMA the region back. This sync is required for
    // the readback to reflect the watcher's writes (on both hw_emu and HW).
    // Guarded: a sync failure must not lose the run.
    if (auto *xrtMem = dynamic_cast<XRTMemory *>(memory_))
    {
      try
      {
        xrtMem->syncRegionFromDevice(telemetry_base, buf.size());
      }
      catch (const std::exception &e)
      {
        std::cerr << "[telemetry] sync-from-device failed: " << e.what() << "\n";
      }
    }
    try
    {
      memory_->copyFromDevice(buf.data(), telemetry_base, buf.size());
    }
    catch (const std::exception &e)
    {
      std::cerr << "[telemetry] readback failed: " << e.what() << "\n";
      return;
    }

    const size_t stride = TELEMETRY_BEAT_BYTES;
    const size_t maxBundles = buf.size() / stride;

    // A beat is "unwritten" iff all 32 bytes are 0. reserveTelemetry zero-fills
    // the window beforehand, and the watcher only stores real telemetry beats,
    // so an all-zero beat still means untouched memory.
    auto bundleIsZero = [&](size_t idx) {
      const uint8_t *b = buf.data() + idx * stride;
      for (size_t k = 0; k < stride; ++k)
        if (b[k] != 0)
          return false;
      return true;
    };

    // The watcher's write_idx is a static in a free-running (ap_ctrl_none) kernel,
    // so it persists across host runs until the FPGA is reprogrammed -- the
    // populated run does NOT necessarily start at offset 0. Find the first
    // populated beat, then count the contiguous populated run from there.
    size_t firstBundle = 0;
    while (firstBundle < maxBundles && bundleIsZero(firstBundle))
      ++firstBundle;
    size_t lastBundle = firstBundle;
    while (lastBundle < maxBundles && !bundleIsZero(lastBundle))
      ++lastBundle;
    const size_t written = lastBundle - firstBundle;
    const size_t byteOffset = firstBundle * stride;
    if (firstBundle == maxBundles)
    {
      std::cout << "[telemetry] window empty (watcher wrote nothing in [base, "
                   "base+window)\n";
      diagnoseEmptyTelemetry(telemetry_base);
    }
    else if (firstBundle != 0)
      std::cout << "[telemetry] populated run starts at beat " << firstBundle
                << " (byte offset " << byteOffset
                << ") -- watcher write_idx carried over from a prior run\n";

    if (written > 0)
      reportStatusConservation(buf, firstBundle, lastBundle);

    std::string path = telemetryOutputPath();

    std::ofstream out(path, std::ios::binary);
    if (!out)
    {
      std::cerr << "[telemetry] could not open " << path << " for writing\n";
      return;
    }
    // Standard self-describing header: the HBM-port -> module descriptor emitted by
    // the generator (<design>.hbmports.json), so the viewer can label each bandwidth
    // port and PE. Located via $FTCD_HBM_DESCRIPTOR, else "fullTriangleCountDecoupled.hbmports.json"
    // in the cwd. If it is missing we print a LOUD warning and fall back to a
    // headerless trace (beats at offset 0) -- that is a misconfiguration, not a mode.
    // Layout when present (see traceViewer/format.md §0):
    //   [0:8)  magic "HCKTRACE"   [8:12) u32 version=2   [12:16) u32 flags
    //   [16:24) u64 json_length   [24:32) u64 beats_offset (32-aligned)
    //   [32 : 32+json_length) JSON descriptor, then zero pad to beats_offset.
    {
      std::vector<std::string> candidates = hbmDescriptorCandidates();
      std::string descPath, descJson, triedPaths;
      for (const auto &c : candidates)
      {
        if (!triedPaths.empty())
          triedPaths += ", ";
        triedPaths += c;
        std::ifstream df(c, std::ios::binary);
        if (df)
        {
          std::ostringstream ss;
          ss << df.rdbuf();
          descJson = ss.str();
          descPath = c;
          break;
        }
      }
      if (!descJson.empty())
      {
        const uint64_t jlen = descJson.size();
        uint64_t beats_off = (32 + jlen + 31) & ~uint64_t(31); // 32-byte align
        char hdr[32] = {0};
        std::memcpy(hdr, "HCKTRACE", 8);
        uint32_t ver = 2;
        // offset 12: u32 flags. bit0 = run mode (1 = hw_emu/sw_emu, 0 = real HW).
        uint32_t flags = isEmulation() ? 0x1u : 0x0u;
        std::memcpy(hdr + 8, &ver, 4);
        std::memcpy(hdr + 12, &flags, 4);
        std::memcpy(hdr + 16, &jlen, 8);
        std::memcpy(hdr + 24, &beats_off, 8);
        out.write(hdr, 32);
        out.write(descJson.data(), static_cast<std::streamsize>(jlen));
        const uint64_t padBytes = beats_off - 32 - jlen;
        if (padBytes)
        {
          std::vector<char> pad(padBytes, 0);
          out.write(pad.data(), static_cast<std::streamsize>(padBytes));
        }
        std::cout << "[telemetry] embedded HBM port descriptor (" << jlen
                  << " bytes) from " << descPath << "\n";
      }
      else
      {
        std::cerr
            << "\n"
            << "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n"
            << "!!!  WARNING: HBM PORT DESCRIPTOR NOT FOUND -- TRACE IS UNLABELED !!!\n"
            << "!!!  looked for: " << triedPaths << "\n"
            << "!!!  The .bin will have NO header, so the viewer cannot map ports/\n"
            << "!!!  PEs. Fix: copy <design>.hbmports.json (emitted by the sbt\n"
            << "!!!  generator next to <design>.hdlinfo.json) into the run cwd, or\n"
            << "!!!  set $FTCD_HBM_DESCRIPTOR to its path, then re-run.\n"
            << "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n\n";
      }
    }

    out.write(reinterpret_cast<const char *>(buf.data() + byteOffset),
              static_cast<std::streamsize>(written * stride));
    out.close();

    std::cout << "[telemetry] wrote " << written << " beats ("
              << (written * stride) << " bytes) to:\n"
              << "[telemetry] " << path << "\n";
  }

  // ── Graph conditioning, GBBS reference, per-vertex completion ─────────────

  // The adder is a merge, so every list must be sorted. Duplicates would inflate
  // an intersection and a self loop would let a vertex match itself, so both go.
  // loadUndirectedGraph symmetrises but does none of this.
  static void conditionGraph(UnweightedGraph &G)
  {
    if (G.num_vertices == 0)
    {
      G.offsets.assign(1, 0);
      G.neighbors.clear();
      return;
    }
    std::vector<std::vector<uint32_t>> adj(G.num_vertices);
    for (uint32_t v = 0; v < G.num_vertices; v++)
    {
      auto first = G.neighbors.begin() + G.offsets[v];
      auto last = G.neighbors.begin() + G.offsets[v + 1];
      std::vector<uint32_t> list(first, last);
      list.erase(std::remove(list.begin(), list.end(), v), list.end());
      std::sort(list.begin(), list.end());
      list.erase(std::unique(list.begin(), list.end()), list.end());
      adj[v] = std::move(list);
    }
    G.offsets.assign((size_t)G.num_vertices + 1, 0);
    for (uint32_t v = 0; v < G.num_vertices; v++)
      G.offsets[(size_t)v + 1] = G.offsets[v] + (uint32_t)adj[v].size();
    G.neighbors.clear();
    G.neighbors.reserve(G.offsets.back());
    for (uint32_t v = 0; v < G.num_vertices; v++)
      G.neighbors.insert(G.neighbors.end(), adj[v].begin(), adj[v].end());
    G.num_edges = G.offsets.back() / 2;
  }

  // Exactly what the kernel accumulates: six times the number of triangles.
  static uint64_t referenceWedgeClosures(const UnweightedGraph &G,
                                         std::vector<uint64_t> &per_vertex)
  {
    per_vertex.assign(G.num_vertices, 0);
    uint64_t total = 0;
    for (uint32_t v = 0; v < G.num_vertices; v++)
    {
      uint64_t sum = 0;
      for (uint32_t k = G.offsets[v]; k < G.offsets[v + 1]; k++)
      {
        const uint32_t u = G.neighbors[k];
        uint32_t i = G.offsets[v], j = G.offsets[u];
        while (i < G.offsets[v + 1] && j < G.offsets[u + 1])
        {
          if (G.neighbors[i] < G.neighbors[j])
            i++;
          else if (G.neighbors[j] < G.neighbors[i])
            j++;
          else
          {
            sum++;
            i++;
            j++;
          }
        }
      }
      per_vertex[v] = sum;
      total += sum;
    }
    return total;
  }

  struct GbbsTriangleResult
  {
    size_t triangles = 0;
    double graph_build_s = 0.0;
    double count_s = 0.0;
    double total_s = 0.0;
  };

  // Construct exactly the directed graph used by GBBS's degree-ordering
  // triangle counter. graph_build_s and materialize_s are representation
  // conversion costs, analogous to graph staging, and are reported separately.
  // ordering_s is the apples-to-apples algorithmic cost: the same rankNodes and
  // filterGraph calls, using the same Parlay worker pool, that execute inside
  // Triangle_degree_ordering().
  static DegreeOrderingTiming
  degreeOrderGraphWithGBBS(const UnweightedGraph &G, UnweightedGraph &ordered)
  {
    DegreeOrderingTiming timing;
    using GbbsEdge = std::tuple<gbbs::uintE, gbbs::uintE, gbbs::empty>;

    const auto t_build_start = std::chrono::high_resolution_clock::now();
    auto edges = gbbs::sequence<GbbsEdge>::uninitialized(G.offsets.back());
    size_t out = 0;
    for (uint32_t u = 0; u < G.num_vertices; u++)
      for (uint32_t i = G.offsets[u]; i < G.offsets[u + 1]; i++)
        edges[out++] = GbbsEdge{(gbbs::uintE)u,
                                (gbbs::uintE)G.neighbors[i], gbbs::empty{}};
    auto gbbs_graph =
        gbbs::symmetric_graph<gbbs::symmetric_vertex, gbbs::empty>::from_edges(
            edges, G.num_vertices);
    const auto t_build_done = std::chrono::high_resolution_clock::now();

    const auto t_order_start = std::chrono::high_resolution_clock::now();
    gbbs::uintE *rank = gbbs::rankNodes(gbbs_graph, gbbs_graph.n);
    auto keep_forward = [&](const gbbs::uintE &u, const gbbs::uintE &v,
                            const gbbs::empty &) { return rank[u] < rank[v]; };
    auto directed = gbbs::filterGraph(gbbs_graph, keep_forward);
    const auto t_order_done = std::chrono::high_resolution_clock::now();
    gbbs::free_array(rank, gbbs_graph.n);

    const auto t_materialize_start = std::chrono::high_resolution_clock::now();
    ordered.num_vertices = G.num_vertices;
    ordered.num_edges = static_cast<uint32_t>(directed.m);
    ordered.offsets.assign((size_t)ordered.num_vertices + 1, 0);
    for (uint32_t u = 0; u < ordered.num_vertices; u++)
      ordered.offsets[(size_t)u + 1] =
          ordered.offsets[u] + directed.get_vertex(u).out_degree();
    ordered.neighbors.resize(ordered.offsets.back());
    gbbs::parallel_for(0, ordered.num_vertices, [&](size_t u) {
      auto nghs = directed.get_vertex(u).out_neighbors();
      const uint32_t base = ordered.offsets[u];
      for (uint32_t i = 0; i < nghs.get_degree(); i++)
        ordered.neighbors[(size_t)base + i] = nghs.get_neighbor(i);
    });
    const auto t_materialize_done = std::chrono::high_resolution_clock::now();

    timing.graph_build_s =
        std::chrono::duration<double>(t_build_done - t_build_start).count();
    timing.ordering_s =
        std::chrono::duration<double>(t_order_done - t_order_start).count();
    timing.materialize_s = std::chrono::duration<double>(
                               t_materialize_done - t_materialize_start)
                               .count();
    timing.directed_edges = ordered.offsets.back();
    return timing;
  }

  static void printDegreeOrderingTiming(const DegreeOrderingTiming &timing)
  {
    std::cout << "[fullTriangleCountDecoupled-ordering] GBBS rank/filter time: "
              << timing.ordering_s << "s\n"
              << "[fullTriangleCountDecoupled-ordering] representation setup: "
              << timing.graph_build_s << "s before ordering, "
              << timing.materialize_s
              << "s to materialize FPGA CSR (excluded from active sum)\n";
  }

  // Timed the way BFSDriver times official GBBS: graph build and the algorithm
  // itself are separate, and only the algorithm is the execution-time number.
  static GbbsTriangleResult runTimedOfficialGBBSTriangle(const UnweightedGraph &G)
  {
    GbbsTriangleResult result;
    const auto t0 = std::chrono::high_resolution_clock::now();
    if (G.num_vertices == 0)
    {
      result.total_s = 0.0;
      return result;
    }

    using GbbsEdge = std::tuple<gbbs::uintE, gbbs::uintE, gbbs::empty>;
    auto edges = gbbs::sequence<GbbsEdge>::uninitialized(G.offsets.back());
    size_t out = 0;
    for (uint32_t u = 0; u < G.num_vertices; u++)
      for (uint32_t i = G.offsets[u]; i < G.offsets[u + 1]; i++)
        edges[out++] = GbbsEdge{(gbbs::uintE)u, (gbbs::uintE)G.neighbors[i],
                                gbbs::empty{}};
    auto gbbs_graph =
        gbbs::symmetric_graph<gbbs::symmetric_vertex, gbbs::empty>::from_edges(
            edges, G.num_vertices);
    const auto t_graph_done = std::chrono::high_resolution_clock::now();

    {
      ScopedCoutSilencer silence_gbbs_progress;
      auto noop = [](gbbs::uintE, gbbs::uintE, gbbs::uintE) {};
      result.triangles = gbbs::Triangle_degree_ordering(gbbs_graph, noop);
    }
    const auto t_done = std::chrono::high_resolution_clock::now();

    result.graph_build_s =
        std::chrono::duration<double>(t_graph_done - t0).count();
    result.count_s =
        std::chrono::duration<double>(t_done - t_graph_done).count();
    result.total_s = std::chrono::duration<double>(t_done - t0).count();
    return result;
  }


  // Closures this one vertex will take: its launcher rounds, plus a window fetch
  // per side for each merge rooted at it.
  static uint64_t closuresForVertex(const UnweightedGraph &G, uint32_t v)
  {
    auto ceilDiv = [](uint64_t x, uint64_t y) { return (x + y - 1) / y; };
    const uint64_t dv = G.degree(v);
    if (dv == 0)
      return 0; // triangle retires it with no launcher and no closure
    uint64_t c = 0;
    for (uint32_t begin = G.offsets[v]; begin < G.offsets[v + 1];
         begin += LAUNCHER_BATCH)
    {
      const uint32_t end = std::min<uint32_t>(
          G.offsets[v + 1], begin + LAUNCHER_BATCH);
      bool launches = false;
      for (uint32_t k = begin; k < end; k++)
        launches |= G.degree(G.neighbors[k]) != 0;
      c += launches ? 1 : 0;
    }
    for (uint32_t k = G.offsets[v]; k < G.offsets[v + 1]; k++)
    {
      const uint64_t du = G.degree(G.neighbors[k]);
      if (du == 0)
        continue; // launcher filters empty forward lists
      c += ceilDiv(dv, ADDER_WINDOW) + ceilDiv(du, ADDER_WINDOW);
    }
    return c;
  }


  static constexpr const char *ANSI_RED = "\033[1;31m";
  static constexpr const char *ANSI_RESET = "\033[0m";

  // Completion is per vertex: every launcher commits a {done, count} word when
  // its vertex is finished, so the run is over when every word has its done bit.
  // The whole array comes back in one DMA per poll, with a backoff, because at
  // millions of vertices a per-vertex read would dominate the measurement.
  int pollAllVerticesDone(Addr result_base, uint32_t n,
                          std::chrono::high_resolution_clock::time_point start,
                          uint32_t first_vertex = 0, uint32_t count = 0)
  {
    if (count == 0)
      count = n - first_vertex;
    const auto deadline = start + std::chrono::duration<double>(watchdog_s_);
    std::vector<uint64_t> words(n, 0);
    const uint64_t bytes = (uint64_t)n * sizeof(uint64_t);
    auto interval = std::chrono::microseconds(200);
    const auto max_interval = std::chrono::milliseconds(250);
    uint32_t last_done = 0;
    auto last_progress = std::chrono::high_resolution_clock::now();
    // A long run should say what it is doing rather than sit silent until the
    // watchdog. Progress is reported in CLOSURES CONSUMED, not retired vertices:
    // hundreds of vertices are in flight at once, so the vertex count barely moves
    // for most of the run and then jumps at the end, which is useless both as a
    // progress bar and for an ETA. Closures consumed climbs smoothly from the
    // first cycle. Vertices are still shown, as the ground truth that ends the
    // run.
    auto next_status = start + std::chrono::seconds(status_interval_s_);
    uint64_t status_last_closures = 0;
    auto status_last_time = start;

    uint64_t pump_resumes = 0;

    while (true)
    {
      // Cooperative stop point. Without this the Ctrl-C flag is set but nothing
      // reads it, so the loop keeps polling until watchdog_s_ (600 s by default)
      // -- the host prints "stopping gracefully" and then appears to do nothing
      // for ten minutes. Returning here takes the same path the watchdog takes:
      // readback, telemetry dump, FAIL, exit.
      if (stopRequested())
      {
        std::cerr << "[fullTriangleCountDecoupled] interrupted by user; aborting "
                     "with " << last_done << "/" << count
                  << " vertices retired\n";
        // Same dump the watchdog takes. Ctrl-C is how a hang normally gets
        // stopped, so it is exactly when ring and pool state is wanted -- and a
        // SECOND Ctrl-C force-terminates, which would skip the end-of-run
        // report entirely.
        dumpStallState(last_done, count);
        return 1;
      }

      memory_->copyFromDevice(reinterpret_cast<uint8_t *>(words.data()),
                              result_base, bytes);
      uint32_t done = 0;
      for (uint32_t v = first_vertex; v < first_vertex + count; v++)
        if (vertexDone(words[v]))
          done++;
      if (done == count)
        return 0;

      const auto now = std::chrono::high_resolution_clock::now();
      if (done != last_done)
      {
        last_done = done;
        last_progress = now;
      }
      if (status_interval_s_ > 0 && now >= next_status)
      {
        const double window_s =
            std::chrono::duration<double>(now - status_last_time).count();
        // Progress is measured against the LAUNCHER pool, because that
        // denominator is exact (see ClosureEstimate). The adder pool is shown
        // too, but only as a rate -- its total is a bound, so a percentage
        // against it would level off short of 100% and read as a stall.
        const uint64_t launcherOut = allocatorHandedOut("adder_unit_launcher");
        const uint64_t adderOut = allocatorHandedOut("adder");
        const uint64_t launcherTotal = closure_estimate_.launcher;
        const double rate =
            window_s > 0.0
                ? (double)(launcherOut - status_last_closures) / window_s
                : 0.0;
        std::cout << "[fullTriangleCountDecoupled] t="
                  << std::chrono::duration<double>(now - start).count() << "s ";
        if (launcherTotal != 0)
        {
          const double pct = std::min(
              100.0, 100.0 * (double)launcherOut / (double)launcherTotal);
          std::cout << "launchers=" << launcherOut << "/" << launcherTotal
                    << " (" << pct << "%) " << rate << "/s";
          // The ETA assumes a uniform cost per launcher round. Adder merge work
          // is degree-skewed, so a graph with a heavy tail spends longer per
          // launcher near the start than the average -- treat it as indicative.
          if (rate > 0.0 && launcherOut < launcherTotal)
            std::cout << " eta~" << ((launcherTotal - launcherOut) / rate) << "s";
        }
        else
        {
          std::cout << "launchers=" << launcherOut;
        }
        std::cout << " adders=" << adderOut << " vertices=" << done << "/"
                  << count;
        // Nonzero means this bitstream still has the allocator self-pause and
        // the host is driving recycling by hand. It should read 0 on fixed RTL;
        // if it does not, the workaround is load-bearing and the run's admission
        // rate is bounded by the pump period, not by the design.
        if (pump_resumes != 0)
          std::cout << " alloc_resumes=" << pump_resumes;
        // Every pool, not just the adder one: allocatorAvailable() hardcodes
        // "adder", and the launcher pool is both the tighter of the two and the
        // one that empties first when continuations stop being recycled.
        // Per-pool detail costs five AXI-lite reads PER POOL -- microseconds on
        // hardware, but seconds each under hw_emu, where it would dominate the
        // poll period. The end-of-run and stall reports still print the full
        // per-pool breakdown, so only the live view is reduced in emulation.
        if (!isEmulation())
          std::cout << continuationPoolsCompact();
        else
        {
          const uint64_t avail = allocatorAvailable();
          const uint64_t cap = allocatorCapacity();
          if (cap)
            std::cout << " adder_pool=" << avail << "/" << cap;
        }
        std::cout << std::endl;
        status_last_closures = launcherOut;
        // Re-armed from a FRESH reading, not from `now` -- `now` predates the
        // readout above, so a readout slower than the interval would leave the
        // deadline already expired and fire again on the very next iteration,
        // making the status block the poll period instead of a periodic sample.
        const auto after_status = std::chrono::high_resolution_clock::now();
        status_last_time = after_status;
        next_status = after_status + std::chrono::seconds(status_interval_s_);
      }
      if (now > deadline)
      {
        std::cerr << "[fullTriangleCountDecoupled] WATCHDOG after "
                  << std::chrono::duration<double>(now - start).count()
                  << "s: " << done << "/" << count << " vertices retired, no "
                  << "progress for "
                  << std::chrono::duration<double>(now - last_progress).count()
                  << "s, alloc_resumes=" << pump_resumes << "\n";
        dumpStallState(done, count);
        return 1;
      }
      // With --pause-reset on, pump WHILE waiting out the backoff rather than once
      // per iteration. Each resume releases at most one pool's worth of addresses,
      // so the pump period is a hard ceiling on the admission rate, and `interval`
      // backs off to 250 ms. The readback itself cannot run that fast (it DMAs the
      // whole result array, ~19 MB at orkut scale, which is why the backoff
      // exists), so only the pump gets the fine cadence. Under emulation a register
      // read costs seconds, so there it stays at one pump per iteration.
      const bool fine_pump = pause_reset_ && !isEmulation();
      if (!fine_pump)
      {
        std::this_thread::sleep_for(interval);
        pump_resumes += pumpAdmissionCapAllocators();
      }
      else
      {
        const auto wake = std::chrono::high_resolution_clock::now() + interval;
        do
        {
          std::this_thread::sleep_for(std::chrono::microseconds(200));
          pump_resumes += pumpAdmissionCapAllocators();
        } while (std::chrono::high_resolution_clock::now() < wake);
      }

      if (interval < max_interval)
        interval = std::min<std::chrono::microseconds>(
            interval * 2,
            std::chrono::duration_cast<std::chrono::microseconds>(max_interval));
    }
  }

  // One compact fragment covering EVERY continuation pool, for the periodic
  // status line. reportContinuationPools() is the detailed multi-line form; this
  // is the version that has to fit alongside the rest of the progress readout.
  // Both are driven from continuationPoolStats(), which enumerates the allocator
  // servers generically, so a new continuation type appears here for free.
  std::string continuationPoolsCompact()
  {
    std::ostringstream os;
    for (const auto &s : continuationPoolStats())
    {
      os << ' ' << s.task << "_pool=" << s.available << '/' << s.capacity
         << "(peak<=" << (s.capacity - s.lowWater);
      if (s.leaked != 0)
        os << " LEAKED=" << s.leaked;
      os << ')';
    }
    return os.str();
  }

  // What the scheduler rings and the closure pools look like when nothing is
  // moving. The pool numbers are the ones that matter most here: a run that
  // exhausts a pool stalls with every ring empty.
  void dumpStallState(uint32_t done, uint32_t n)
  {
    std::cerr << "[fullTriangleCountDecoupled-STALL] retired=" << done << "/"
              << n << " adder_closures_available=" << allocatorAvailable()
              << "/" << allocatorCapacity() << "\n";
    // Full per-pool detail for BOTH continuation types. The line above only
    // ever reports the adder pool (allocatorAvailable/Capacity hardcode
    // "adder"), which is the one least likely to be the culprit: a launcher
    // pool driven to zero by unrecycled continuations is invisible without this.
    reportContinuationPools("[fullTriangleCountDecoupled-STALL]");
    for (const auto &task : descriptor.taskDescriptors)
      for (uint64_t base : task.mgmtBaseAddresses.schedulerServersBaseAddresses)
        std::cerr << "[fullTriangleCountDecoupled-STALL] task=" << task.name
                  << " base=0x" << std::hex << base << std::dec << " currLen="
                  << memory_->readReg64(base + scheduler_server_currLen_shift)
                  << " head="
                  << memory_->readReg64(base + scheduler_server_fifoHeadReg_shift)
                  << " tail="
                  << memory_->readReg64(base + scheduler_server_fifoTailReg_shift)
                  << " rpause="
                  << memory_->readReg64(base + scheduler_server_rpause_shift)
                  << "\n";
  }

  // Per-continuation-type closure counts. Each type has its own pool and its own
  // handed-out counter, so keeping them apart lets both the sizing and the
  // progress readout be per-pool.
  //
  // `launcher` is EXACT. A launcher round takes one closure for each
  // LAUNCHER_BATCH-sized candidate chunk containing at least one nonempty
  // target list. All-empty chunks are consumed by the launcher's rescan loop
  // without taking a closure; this matters for degree-oriented sink vertices.
  //
  // `adder` is an UPPER BOUND: a merge stops as soon as either side is
  // exhausted, but the bound charges every merge for walking all of adj(v) AND
  // all of adj(u). Real runs consume fewer, often far fewer on skewed graphs.
  struct ClosureEstimate
  {
    uint64_t launcher = 0; // adder_unit_launcher pool -- exact
    uint64_t adder = 0;    // adder pool -- upper bound
    uint64_t total() const { return launcher + adder; }
  };

  static ClosureEstimate estimateClosuresByType(const UnweightedGraph &G)
  {
    auto ceilDiv = [](uint64_t x, uint64_t y) { return (x + y - 1) / y; };
    ClosureEstimate e;
    for (uint32_t v = 0; v < G.num_vertices; v++)
    {
      const uint64_t dv = G.degree(v);
      // triangle retires a degree-0 vertex itself, without a launcher.
      if (dv == 0)
        continue;
      for (uint32_t begin = G.offsets[v]; begin < G.offsets[v + 1];
           begin += LAUNCHER_BATCH)
      {
        const uint32_t end = std::min<uint32_t>(
            G.offsets[v + 1], begin + LAUNCHER_BATCH);
        bool launches = false;
        for (uint32_t k = begin; k < end; k++)
          launches |= G.degree(G.neighbors[k]) != 0;
        e.launcher += launches ? 1 : 0;
      }
      // Every nonempty target produces one merge. Each merge walks at most all
      // of adj(v) and adj(u), one closure per window on each side.
      for (uint32_t k = G.offsets[v]; k < G.offsets[v + 1]; k++)
      {
        const uint64_t du = G.degree(G.neighbors[k]);
        if (du == 0)
          continue;
        e.adder += ceilDiv(dv, ADDER_WINDOW) +
                   ceilDiv(du, ADDER_WINDOW);
      }
    }
    return e;
  }

  static uint64_t estimateClosures(const UnweightedGraph &G)
  {
    return estimateClosuresByType(G).total();
  }

  // Continuations issued from a pool since reset -- monotonic, and the only
  // progress signal that moves smoothly. Retired vertices are the ground truth
  // but they are useless as a progress bar here: hundreds of vertices are in
  // flight at once, so the count sits near zero and then jumps at the end.
  // Closures consumed rises continuously from the first cycle.
  uint64_t allocatorHandedOut(const char *taskName) const
  {
    for (const auto &task : descriptor.taskDescriptors)
      if (task.name == taskName &&
          !task.mgmtBaseAddresses.allocationServersBaseAddresses.empty())
        return memory_->readReg64(
            task.mgmtBaseAddresses.allocationServersBaseAddresses.front() +
            alloc_server_handedOut_shift);
    return 0;
  }

  uint64_t allocatorAvailable() const
  {
    for (const auto &task : descriptor.taskDescriptors)
      if (task.name == "adder" &&
          !task.mgmtBaseAddresses.allocationServersBaseAddresses.empty())
        return memory_->readReg64(
            task.mgmtBaseAddresses.allocationServersBaseAddresses.front() +
            alloc_server_availableSize_shift);
    return 0;
  }

  uint64_t allocatorCapacity() const
  {
    for (const auto &task : descriptor.taskDescriptors)
      if (task.name == "adder")
        return task.getCapacityVirtualQueue("allocator");
    return 0;
  }

  // WORKAROUND for a recycling AllocatorServer that latches a TERMINAL rPause the
  // instant its free list cannot cover one burst. For an admission-cap pool that is
  // not exhaustion: the addresses are alive inside the design and the RecycleWriter
  // is about to hand them back. The read-ahead engine chases 112 beats (896
  // addresses), so for any pool smaller than that wantReadBurst is pinned high and
  // the latch fires during startup, before a single closure retires; the server then
  // ignores every address that comes home. The RTL fix is to not self-pause while
  // recycling; delete this once such a bitstream is in use.
  //
  // rPause is a read/write register, so the host can resume the engine -- but only
  // with a whole burst free, since clearing it on an empty free list re-latches on
  // the next cycle. Resizing the pool does not help: the buffered addresses are not
  // a reserve (triangle consumes those too), so the effective cap IS the capacity
  // and a bigger pool only moves the latch.
  //
  // Harmless against fixed RTL: rPause is never nonzero there, so this reads one
  // register and writes nothing.
  uint64_t pumpAdmissionCapAllocators()
  {
    if (!pause_reset_)
      return 0;
    uint64_t resumed = 0;
    for (const auto &task : descriptor.taskDescriptors)
    {
      if (!isAdmissionCapPool(task.name) || !task.recyclesContinuations())
        continue;
      for (uint64_t base : task.mgmtBaseAddresses.allocationServersBaseAddresses)
      {
        if (memory_->readReg64(base + alloc_server_availableSize_shift) <
            alloc_conts_per_burst)
          continue;
        if (memory_->readReg64(base + alloc_server_rpause_shift) == 0)
          continue;
        memory_->writeReg64(base + alloc_server_rpause_shift, 0);
        resumed++;
      }
    }
    return resumed;
  }

  static bool endsWith(const std::string &s, const std::string &suffix)
  {
    return s.size() >= suffix.size() &&
           s.compare(s.size() - suffix.size(), suffix.size(), suffix) == 0;
  }

  // True when the build's descriptor declares at least one watcher STATUS slot.
  // "pes" is that table, so empty or absent means the design carries no watcher and
  // nothing should be reserved for telemetry. Cached because reserveTelemetry and
  // the telemetry dump both ask.
  //
  // Fails OPEN: if no descriptor can be read at all we reserve anyway, rather than
  // silently dropping telemetry on a build that does have a watcher.
  bool descriptorHasWatcher() const
  {
    if (descriptor_watcher_known_)
      return descriptor_has_watcher_;
    descriptor_watcher_known_ = true;
    descriptor_has_watcher_ = true;
    for (const auto &c : hbmDescriptorCandidates())
    {
      std::ifstream df(c, std::ios::binary);
      if (!df)
        continue;
      std::ostringstream ss;
      ss << df.rdbuf();
      descriptor_has_watcher_ =
          !hardcilk_telemetry::parseWatcherPes(ss.str()).empty();
      return descriptor_has_watcher_;
    }
    return descriptor_has_watcher_;
  }

  static std::string matchingHbmDescriptorPath(const std::string &xclbinPath)
  {
    if (xclbinPath.empty())
      return std::string();

    const std::string suffix = ".xclbin";
    if (endsWith(xclbinPath, suffix))
      return xclbinPath.substr(0, xclbinPath.size() - suffix.size()) +
             ".hbmports.json";

    return xclbinPath + ".hbmports.json";
  }

  std::vector<std::string> hbmDescriptorCandidates() const
  {
    std::vector<std::string> candidates;

    const std::string matching = matchingHbmDescriptorPath(xclbin_path_);
    if (!matching.empty())
      candidates.push_back(matching);

    if (const char *e = std::getenv("FTCD_HBM_DESCRIPTOR"))
      candidates.push_back(e);

    candidates.push_back("fullTriangleCountDecoupled.hbmports.json");    // run from workspace
    candidates.push_back("../fullTriangleCountDecoupled.hbmports.json"); // run from build folder
    return candidates;
  }

  std::string graph_path_;
  double watchdog_s_ = 600.0;
  bool fast_mode_ = false;
  bool legacy_single_port_watcher_ = false;
  // Cache for descriptorHasWatcher(); mutable so the const query can memoise.
  mutable bool descriptor_watcher_known_ = false;
  mutable bool descriptor_has_watcher_ = true;
  // Opt-in: stage GBBS's degree-oriented forward-neighbour graph instead of
  // the default full undirected graph. Controlled by --degree-order.
  bool degree_ordering_ = false;
  // Opt-in workaround for pre-fix bitstreams; see pumpAdmissionCapAllocators().
  // Off by default: on fixed RTL it is dead weight, and clearing an allocator's
  // rPause by hand is exactly the kind of thing that should never happen unless
  // asked for. --pause-reset, or FULLTRIANGLECOUNTDECOUPLED_PAUSE_RESET=1.
  bool pause_reset_ = false;
  std::string xclbin_path_;
  // Upper bound on closures the run will take; see estimateClosures().
  uint64_t closures_needed_ = 0;
  // Per-type upper bounds on closures the run will consume; the denominator for
  // the progress readout. See estimateClosuresByType for why it is a bound.
  ClosureEstimate closure_estimate_;
  // Seconds between progress lines during the run; 0 silences them.
  // Override with FULLTRIANGLECOUNTDECOUPLED_STATUS_SECONDS.
  int status_interval_s_ = statusIntervalFromEnv();

  static bool pauseResetFromEnv()
  {
    const char *v = std::getenv("FULLTRIANGLECOUNTDECOUPLED_PAUSE_RESET");
    return v && *v && std::string(v) != "0";
  }

  static int statusIntervalFromEnv()
  {
    if (const char *v = std::getenv("FULLTRIANGLECOUNTDECOUPLED_STATUS_SECONDS");
        v && *v)
      return std::atoi(v);
    return 5;
  }
};
