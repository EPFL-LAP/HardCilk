#pragma once

#include <hardCilkDriver.h>
#include <memAccess_defs.h>
#include <memIO_xrt.h> // XRTMemory + bank-pinned telemetry allocation helper
#include <WatcherTelemetry.h> // design-agnostic STATUS-stream conservation (derived from descriptor)

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <fstream>
#include <filesystem>
#include <iostream>
#include <sstream>
#include <string>
#include <thread>
#include <utility>
#include <vector>

using Addr = uint64_t;

// The watcher emits a flat stream of 256-bit (32-byte) AXI "beats" to HBM. Each
// beat carries two independent 128-bit bit-packed "bundles" (slot0 = bytes 0..15,
// slot1 = bytes 16..31), each a tagged union (NULL / STATUS / BW_READ / BW_WRITE /
// BW_ADDR). The full bit-level format is documented in HardCilk/traceViewer/format.md
// and decoded by the viewer, not here. The host only locates the populated beat-run
// in the readback window and dumps it verbatim for the viewer.
static constexpr size_t TELEMETRY_BEAT_BYTES = 32;
static constexpr uint64_t TELEMETRY_IO_CHUNK_BYTES = 64ULL << 20;

struct __attribute__((packed)) CountDecoupledRootTask
{
  Addr cont;
  uint32_t continuation_meta;
  Addr A;
  Addr count;       // running match count (starts at 0), carried in the closure
  Addr count_final; // memory address the finished initiator writes the result to
  uint32_t size;
  uint32_t i;
  uint8_t _padding[20];
};

static_assert(sizeof(CountDecoupledRootTask) ==
                  sizeof(taskInitiator_reentry0_task),
              "Host root task must match taskInitiator_reentry0_task layout");

inline bool countDecoupledDoneCondition(int32_t value)
{
  return value != 0;
}

class CountDecoupledDriver : public hardCilkDriver
{
public:
  CountDecoupledDriver(Memory *memory, uint32_t size,
                               uint32_t num_instances = 1,
                               double watchdog_s = 600.0,
                               bool fast_mode = false,
                               std::string xclbin_path = std::string(),
                               bool legacy_single_port_watcher = false,
                               bool hbm_strided_writes = false,
                               uint64_t hbm_continuation_bank_run_entries = 1)
      : hardCilkDriver(memory), size_(std::max<uint32_t>(1, size)),
        num_instances_(std::max<uint32_t>(1, num_instances)),
        watchdog_s_(watchdog_s), fast_mode_(fast_mode),
        legacy_single_port_watcher_(legacy_single_port_watcher),
        hbm_strided_writes_(hbm_strided_writes),
        hbm_continuation_bank_run_entries_(
            std::max<uint64_t>(1, hbm_continuation_bank_run_entries)),
        xclbin_path_(std::move(xclbin_path)) {}

  static int run_cpu_test_bench(uint32_t size)
  {
    size = std::max<uint32_t>(1, size);
    std::vector<int32_t> A;
    buildInputs(size, A);
    int32_t matches = referenceCount(A);
    std::cout << "[countDecoupled-CPU] size=" << size
              << " expected_iterations=" << size
              << " matches=" << matches << "\n";
    return 0;
  }

  int run_test_bench() override
  {
    auto t0 = std::chrono::high_resolution_clock::now();
    const uint32_t N = num_instances_;

    // Every instance solves an identical (but independent, non-overlapping)
    // problem, so one reference count applies to all.
    std::vector<int32_t> A;
    buildInputs(size_, A);
    const int32_t expected = referenceCount(A);
    const uint64_t array_bytes = (uint64_t)size_ * sizeof(int32_t);

    clearComputeHBM();

    // The watcher is mapped to HBM[16:31], while every compute/server master is
    // mapped to HBM[0:15]. Keep telemetry and compute buffers in those disjoint
    // ranges explicitly; the default XRT allocator can otherwise spill small
    // per-instance BOs into the watcher-only banks after enough allocations.
    Addr telemetry_base = reserveTelemetry();
    configureComputeBankRange();
    if (hbm_strided_writes_)
    {
      setHbmWriteDistribution(true, COMPUTE_FIRST_BANK, COMPUTE_LAST_BANK,
                              hbm_continuation_bank_run_entries_);
      // --- Smart per-region HBM placement (port N's region -> bank N) -------------
      // The crossbar behaves like a long ring, so we minimize lateral traversal and
      // keep all remaining lateral traffic (the hot closure writes) flowing one
      // direction. Bank numbers below == the HBM pseudo-channel each master's m_axi
      // port is bound to (hbmports.json / conn_u55c.cfg: port index == HBM bank):
      //   scheduler rings  : taskAdder=7, initiator=8/9  (each ring local to its
      //                      own ring port -> no lateral traffic). memReader has
      //                      NO scheduler server (numVirtualServers=0): it is fed
      //                      purely by its on-chip spawner ring with no HBM spill,
      //                      so it needs no ring bank (bank 14 is now free).
      //   allocator FIFO   : port 15  (allocator read becomes lateral-free, which is
      //                      what removes the earlier allocator-vs-closure freeze)
      //   closure pool     : strided across 10..13, WRITTEN by spawnNext ports 5/6
      //                      and argOut ports 3/4 (all flowing UP, one direction),
      //                      READ by notifier ports 10..13
      //   graph A -> 0, count/done -> 2 (kept off the hot closure banks; see below)
      setContinuationBankRange(CLOSURE_FIRST_BANK, CLOSURE_LAST_BANK);
      setRegionBankOverride("sched:taskAdder_cont0:0", 7);
      setRegionBankOverride("sched:taskInitiator_reentry0:0", 8);
      setRegionBankOverride("sched:taskInitiator_reentry0:1", 9);
      // memReader has no scheduler server (0 virtual servers) -> no ring region to
      // place; it is fed purely by its on-chip spawner ring (no HBM spill).
      setRegionBankOverride("alloc:taskAdder_cont0:0", 15);
      std::cout << "[hbm-dist] smart placement: rings@7/8/9, allocFIFO@15, "
                << "closures@" << CLOSURE_FIRST_BANK << ".." << CLOSURE_LAST_BANK
                << ", A@" << GRAPH_A_BANK << ", count/done@" << COUNT_DONE_BANK
                << " (continuation bank-run entries="
                << hbm_continuation_bank_run_entries_ << ")\n";
    }

    // Aggregate each per-instance buffer type into one BO. Thousands of tiny BOs
    // get distributed across every bank by XRT and leave no completely free,
    // adjacent banks for the large continuation pool allocated by initSystem.
    const uint64_t all_array_bytes = array_bytes * N;
    Addr all_A_addr = allocateGraphInput(all_array_bytes);

    std::vector<int32_t> all_A((uint64_t)N * size_);
    for (uint32_t k = 0; k < N; ++k)
    {
      std::copy(A.begin(), A.end(), all_A.begin() + (uint64_t)k * size_);
    }
    memory_->copyToDevice(all_A_addr,
                          reinterpret_cast<const uint8_t *>(all_A.data()),
                          all_array_bytes);

    // Build N non-overlapping instances and one root task each.
    std::vector<CountDecoupledRootTask> roots(N);
    std::vector<Addr> count_addrs(N), done_addrs(N);
    allocateCountDoneRecords(N, count_addrs, done_addrs);
    for (uint32_t k = 0; k < N; ++k)
    {
      Addr A_addr = all_A_addr + (uint64_t)k * array_bytes;
      Addr count_addr = count_addrs[k];
      Addr done_addr = done_addrs[k];
      CountDecoupledRootTask &r = roots[k];
      r.cont = done_addr;
      r.continuation_meta = 0;
      r.A = A_addr;
      r.count = 0;               // running value, accumulated in the closure
      r.count_final = count_addr; // final result + done flag land here (8-byte store)
      r.size = size_;
      r.i = 0;
      count_addrs[k] = count_addr;
      done_addrs[k] = done_addr;
    }

    std::cout << "[countDecoupled] instances=" << N << " size=" << size_
              << " expected_matches_each=" << expected << "\n";
    if (legacy_single_port_watcher_)
      std::cout << "[telemetry] using legacy single-port watcher layout\n";

    // initSystem writes the whole vector to the root scheduler and sets
    // fifoTail=N, so all N root tasks are live concurrently.
    configureInitialQueueCapacities();
    initSystem(roots, &countDecoupledDoneCondition, 0, 0, false);

    auto t_kernel_start = std::chrono::high_resolution_clock::now();
    startSystem();
    int rc = pollAllDone(count_addrs, t_kernel_start);
    auto t_kernel_done = std::chrono::high_resolution_clock::now();

    // Validate every instance's result.
    uint32_t passed = 0;
    bool any_bad = false;
    uint32_t first_bad_k = 0;
    int32_t first_bad_got = 0;
    for (uint32_t k = 0; k < N; ++k)
    {
      int32_t got = 0;
      memory_->copyFromDevice(reinterpret_cast<uint8_t *>(&got), count_addrs[k],
                              sizeof(got));
      if (got == expected)
        ++passed;
      else if (!any_bad)
      {
        any_bad = true;
        first_bad_k = k;
        first_bad_got = got;
      }
    }
    auto t_done = std::chrono::high_resolution_clock::now();

    std::cout << "[countDecoupled-FPGA] execution time: "
              << std::chrono::duration<double>(t_kernel_done - t_kernel_start)
                     .count()
              << "s\n";
    std::cout << "[countDecoupled-FPGA] end-to-end time: "
              << std::chrono::duration<double>(t_done - t0).count() << "s\n";
    std::cout << "[countDecoupled-FPGA] passed=" << passed << "/" << N
              << " expected_each=" << expected << "\n";

    // The compute done words become visible before the independent ap_ctrl_none
    // watcher has necessarily committed its bounded pipeline tail to HBM. Do not
    // race telemetry readback against those final writes. Hardware drains the
    // complete set of internal FIFOs in far less than 1 ms; hw_emu advances the
    // kernel clock only a few hundred cycles per wall-clock second, so give that
    // model enough time to retire the same bounded tail.
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

    // Always dump telemetry (even on failure) so a hang/mismatch can be analyzed.
    if (legacy_single_port_watcher_)
      dumpTelemetry(telemetry_base);
    else
      dumpTelemetryTwoPort(telemetry_base);

    if (rc == 0 && passed == N)
    {
      std::cout << "[countDecoupled] PASS\n";
      return 0;
    }

    std::cerr << "[countDecoupled] FAIL passed=" << passed << "/" << N;
    if (any_bad)
      std::cerr << " first_bad[" << first_bad_k << "]=" << first_bad_got
                << " expected=" << expected;
    std::cerr << " rc=" << rc << "\n";
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
  // Smart-placement banks (see the map in run_test_bench). Closures stride 10..13;
  // graph A and the count/done records get pinned off those hot banks.
  static constexpr int CLOSURE_FIRST_BANK = 10;
  static constexpr int CLOSURE_LAST_BANK = 13;
  static constexpr int GRAPH_A_BANK = 0;
  static constexpr int COUNT_DONE_BANK = 2;
  static constexpr Addr TELEMETRY_GLOBAL_BASE = 0x200000000ULL;
  // Port B sits exactly 4 GiB above port A (HBM[24] base - HBM[16] base); the
  // watcher kernel adds this same offset internally (FOUR_GB_BEATS in memAccess.cpp).
  static constexpr Addr TELEMETRY_PORT_STRIDE = 0x100000000ULL; // 4 GiB
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
    const std::filesystem::path dir =
        configured != nullptr && configured[0] != '\0' ? configured : "/tmp";
    std::error_code ec;
    std::filesystem::create_directories(dir, ec);
    if (ec)
      std::cerr << "[telemetry] could not create output directory " << dir
                << ": " << ec.message() << "\n";
    return (dir / (std::string("countDecoupled_telemetry_") +
                   telemetryTimestamp() + ".bin"))
        .string();
  }

  // Per-port telemetry region size. Each of the watcher's two physical ports owns
  // a 4 GiB HBM half: port A = HBM[16:23] at the reserve base, port B = HBM[24:31]
  // exactly TELEMETRY_PORT_STRIDE higher. This is the per-region scan/zero window.
  uint64_t perRegionWindowBytes() const
  {
    return isEmulation() ? (256ULL << 20) : (4ULL << 30);
  }

  // The reserve must span BOTH regions: [base, base + 4 GiB + perRegionWindow). On
  // HW that is 8 GiB (all of HBM[16:31], same as the old single-region reserve); in
  // emulation it is 4 GiB + a small window so port B at +4 GiB is still inside the
  // reserved (host-readable) BO.
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

  // Graph input A is read-only and low-bandwidth (one int per iteration), so in
  // smart-placement mode we simply pin it to GRAPH_A_BANK to keep it off the hot
  // closure/ring banks. Falls back to the full compute range if it can't fit one
  // bank (large N) or the memory isn't XRT.
  Addr allocateGraphInput(uint64_t size)
  {
    XRTMemory *xrtMem = dynamic_cast<XRTMemory *>(memory_);
    if (!hbm_strided_writes_ || xrtMem == nullptr)
      return allocateComputeMem(size, 512);
    try
    {
      Addr a = xrtMem->allocateMemFPGAInBankRange(size, 512, GRAPH_A_BANK,
                                                  GRAPH_A_BANK);
      std::cout << "[hbm-dist] graph A -> HBM[" << GRAPH_A_BANK << "] addr=0x"
                << std::hex << a << std::dec << " bytes=" << size << "\n";
      return a;
    }
    catch (const std::exception &)
    {
      std::cerr << "[hbm-dist] graph A did not fit HBM[" << GRAPH_A_BANK
                << "]; falling back to compute range\n";
      return allocateComputeMem(size, 512);
    }
  }

  void allocateCountDoneRecords(uint32_t N, std::vector<Addr> &count_addrs,
                                std::vector<Addr> &done_addrs)
  {
    const uint64_t recordBytes = 2 * sizeof(int32_t);
    if (!hbm_strided_writes_)
    {
      Addr base = allocateComputeMem((uint64_t)N * recordBytes, 512);
      std::vector<int32_t> zeros((uint64_t)N * 2, 0);
      memory_->copyToDevice(base, reinterpret_cast<const uint8_t *>(zeros.data()),
                            zeros.size() * sizeof(int32_t));
      for (uint32_t k = 0; k < N; ++k)
      {
        count_addrs[k] = base + (uint64_t)k * recordBytes;
        done_addrs[k] = count_addrs[k] + sizeof(int32_t);
      }
      return;
    }

    XRTMemory *xrtMem = dynamic_cast<XRTMemory *>(memory_);
    if (xrtMem == nullptr)
    {
      std::cerr << "[hbm-dist] memory is not XRTMemory; count/done records "
                   "fall back to contiguous allocation\n";
      const bool saved = hbm_strided_writes_;
      hbm_strided_writes_ = false;
      allocateCountDoneRecords(N, count_addrs, done_addrs);
      hbm_strided_writes_ = saved;
      return;
    }

    // Smart placement: keep the count/done records on ONE bank (COUNT_DONE_BANK),
    // local to the initiator's main port (which issues the 8-byte done store), so
    // those writes never scatter onto the hot closure banks. Contiguous within the
    // bank; the strided poll path reads each record individually anyway.
    Addr base = xrtMem->allocateMemFPGAInBankRange((uint64_t)N * recordBytes, 512,
                                                   COUNT_DONE_BANK, COUNT_DONE_BANK);
    std::vector<int32_t> zeros((uint64_t)N * 2, 0);
    memory_->copyToDevice(base, reinterpret_cast<const uint8_t *>(zeros.data()),
                          zeros.size() * sizeof(int32_t));
    std::cout << "[hbm-dist] count/done records -> HBM[" << COUNT_DONE_BANK
              << "] addr=0x" << std::hex << base << std::dec
              << " records=" << N << "\n";
    for (uint32_t k = 0; k < N; ++k)
    {
      count_addrs[k] = base + (uint64_t)k * recordBytes;
      done_addrs[k] = count_addrs[k] + sizeof(int32_t);
    }
  }

  void clearComputeHBM()
  {
    XRTMemory *xrtMem = dynamic_cast<XRTMemory *>(memory_);
    if (xrtMem == nullptr)
    {
      std::cerr << "[countDecoupled] memory is not XRTMemory; "
                   "full HBM clear skipped\n";
      return;
    }

    std::cout << "[countDecoupled] clearing 8 GiB compute HBM "
                 "(banks 0-15)\n";
    xrtMem->clearHBMBankRange(COMPUTE_FIRST_BANK, COMPUTE_LAST_BANK);
    std::cout << "[countDecoupled] compute HBM clear complete\n";
  }

  void configureInitialQueueCapacities()
  {
    // Each loop iteration allocates one continuation. Those slots are reclaimed
    // only by host management, so provision the whole run up front to keep the
    // timed execution independent of the management/refill path.
    const uint64_t continuationsNeeded =
        ((uint64_t)size_ * 2 + 1) * num_instances_;
    const uint64_t allocatorCapacity = std::max<uint64_t>(
        INITIAL_SCHEDULER_VIRTUAL_CAPACITY, continuationsNeeded);

    for (auto &task : descriptor.taskDescriptors)
    {
      for (auto &config : task.sidesConfigs)
      {
        if (config.sideType == "scheduler")
          config.capacityVirtualQueue = std::max(
              config.capacityVirtualQueue,
              INITIAL_SCHEDULER_VIRTUAL_CAPACITY);
        else if (config.sideType == "allocator")
          config.capacityVirtualQueue = std::max<uint64_t>(
              config.capacityVirtualQueue, allocatorCapacity);
      }
    }
    // initSystem doubles capacityVirtualQueue when allocating the backing BO.
    std::cout << "[countDecoupled] initial scheduler backing capacity: "
              << (2 * INITIAL_SCHEDULER_VIRTUAL_CAPACITY)
              << " entries per server; continuation allocator capacity: "
              << allocatorCapacity << " entries\n";
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
    XRTMemory *xrtMem = dynamic_cast<XRTMemory *>(memory_);
    if (xrtMem == nullptr)
    {
      std::cerr << "[telemetry] memory is not XRTMemory; telemetry disabled\n";
      return 0;
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
    // port and PE. Located via $CD_HBM_DESCRIPTOR, else "countDecoupled.hbmports.json"
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
            << "!!!  set $CD_HBM_DESCRIPTOR to its path, then re-run.\n"
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

  static void buildInputs(uint32_t size, std::vector<int32_t> &A)
  {
    A.resize(size);
    for (uint32_t i = 0; i < size; i++)
      A[i] = ((i * 1103515245u + 12345u) >> 30) & 1u;
  }

  static int32_t referenceCount(const std::vector<int32_t> &A)
  {
    int32_t count = 0;
    for (int32_t value : A)
      if (value == 1)
        count++;
    return count;
  }

  int pollDone(Addr done_addr,
               std::chrono::high_resolution_clock::time_point start)
  {
    const auto deadline = start + std::chrono::duration<double>(watchdog_s_);
    uint64_t polls = 0;
    while (true)
    {
      if (stopRequested())
      {
        std::cerr << "[countDecoupled] interrupted by user; aborting poll "
                     "(after " << polls << " polls)\n";
        return -1;
      }

      if (!fast_mode_ && checkPaused() == 0)
        managePausedServer();

      int32_t done = 0;
      memory_->copyFromDevice(reinterpret_cast<uint8_t *>(&done), done_addr,
                              sizeof(done));
      if (done != 0)
      {
        std::cout << "[countDecoupled] done after " << polls
                  << " polls";
        if (fast_mode_)
          std::cout << " (fast mode)";
        std::cout << "\n";
        return 0;
      }

      auto now = std::chrono::high_resolution_clock::now();
      if (now > deadline)
      {
        std::cerr << "[countDecoupled] WATCHDOG: " << watchdog_s_
                  << "s elapsed without done. done=" << done << "\n";
        return -1;
      }

      polls++;
      std::this_thread::sleep_for(fast_mode_ ? std::chrono::milliseconds(10)
                                             : std::chrono::microseconds(200));
    }
  }

  uint64_t allocatorAvailable() const
  {
    for (const auto &task : descriptor.taskDescriptors)
      if (task.name == "taskAdder_cont0" &&
          !task.mgmtBaseAddresses.allocationServersBaseAddresses.empty())
        return memory_->readReg64(
            task.mgmtBaseAddresses.allocationServersBaseAddresses.front() +
            alloc_server_availableSize_shift);
    return 0;
  }

  uint64_t allocatorCapacity() const
  {
    for (const auto &task : descriptor.taskDescriptors)
      if (task.name == "taskAdder_cont0")
        return task.getCapacityVirtualQueue("allocator");
    return 0;
  }

  void dumpStallState(const std::vector<char> &done,
                      const std::vector<int32_t> &states)
  {
    std::cout << "[countDecoupled-STALL] unfinished instances:";
    size_t shown = 0;
    for (size_t k = 0; k < done.size() && shown < 16; ++k)
      if (!done[k])
      {
        std::cout << " " << k << "(count=" << states[2 * k] << ")";
        ++shown;
      }
    std::cout << "\n";

    for (const auto &task : descriptor.taskDescriptors)
      for (uint64_t base : task.mgmtBaseAddresses.schedulerServersBaseAddresses)
        std::cout << "[countDecoupled-SCHED] task=" << task.name
                  << " base=0x" << std::hex << base << std::dec
                  << " currLen="
                  << memory_->readReg64(base + scheduler_server_currLen_shift)
                  << " head="
                  << memory_->readReg64(base + scheduler_server_fifoHeadReg_shift)
                  << " tail="
                  << memory_->readReg64(base + scheduler_server_fifoTailReg_shift)
                  << " maxLen="
                  << memory_->readReg64(base + scheduler_server_maxLength_shift)
                  << " rpause="
                  << memory_->readReg64(base + scheduler_server_rpause_shift)
                  << "\n";

    // --- Local-buffer occupancy probe (default OFF) -------------------------
    // currLen/head/tail only track the HBM spill RING; they are blind to each
    // PE's local (BRAM) task queue. A task stranded in a local buffer (e.g. the
    // steal-credit / canOutputTask end-game in SchedulerServer.scala) makes the
    // rings read empty while instances stay stuck -- exactly this stall's
    // signature. queuesUtil exposes those lengths (populated when peCount <= 8).
    //   Enable at runtime, no rebuild-of-this-flag needed:
    //     COUNTDECOUPLED_DUMP_QUEUES=1 ./countDecoupled_xrt ...
    // Nonzero lanes here at the stall => tasks stuck in local buffers (steal/
    // output path). All-zero => loss is upstream (ring wrap / network routing).
    if (const char *e = std::getenv("COUNTDECOUPLED_DUMP_QUEUES");
        e && e[0] == '1')
    {
      for (const auto &task : descriptor.taskDescriptors)
        for (uint64_t base : task.mgmtBaseAddresses.schedulerServersBaseAddresses)
        {
          const uint64_t q =
              memory_->readReg64(base + scheduler_server_queuesUtil_shift);
          std::cout << "[countDecoupled-QUEUES] task=" << task.name
                    << " base=0x" << std::hex << base
                    << " queuesUtil=0x" << q << std::dec << " lanes[PE0..7]=";
          // MSB-first: PE0 occupies the highest byte lane (reduceLeft(Cat)).
          for (int lane = 7; lane >= 0; --lane)
            std::cout << " " << ((q >> (8 * lane)) & 0xFF);
          std::cout << "\n";
        }
    }
  }

  // Wait until every instance is done. Count/done records are contiguous, so a
  // single DMA read replaces thousands of tiny reads on every poll.
  int pollAllDone(const std::vector<Addr> &count_addrs,
                  std::chrono::high_resolution_clock::time_point start)
  {
    const auto deadline = start + std::chrono::duration<double>(watchdog_s_);
    std::vector<char> done(count_addrs.size(), 0);
    std::vector<int32_t> states(count_addrs.size() * 2, 0);
    size_t remaining = count_addrs.size();
    const uint64_t initialAllocatorCapacity = allocatorCapacity();
    auto nextProgress = start;
    uint64_t lastIssued = 0;
    size_t lastRemaining = remaining;
    unsigned stagnantReports = 0;
    uint64_t polls = 0;
    while (remaining > 0)
    {
      if (stopRequested())
      {
        std::cerr << "[countDecoupled] interrupted by user; aborting with "
                  << remaining << "/" << count_addrs.size()
                  << " instances not done (after " << polls << " polls)\n";
        return -1;
      }

      if (!fast_mode_ && checkPaused() == 0)
        managePausedServer();

      if (hbm_strided_writes_)
      {
        for (size_t k = 0; k < count_addrs.size(); ++k)
          memory_->copyFromDevice(
              reinterpret_cast<uint8_t *>(states.data() + 2 * k),
              count_addrs[k], 2 * sizeof(int32_t));
      }
      else
      {
        memory_->copyFromDevice(reinterpret_cast<uint8_t *>(states.data()),
                                count_addrs.front(),
                                states.size() * sizeof(int32_t));
      }
      for (size_t k = 0; k < count_addrs.size(); ++k)
      {
        if (done[k])
          continue;
        if (states[2 * k + 1] != 0)
        {
          done[k] = 1;
          --remaining;
        }
      }

      if (remaining == 0)
      {
        std::cout << "[countDecoupled] all " << count_addrs.size()
                  << " instances done after " << polls << " polls"
                  << (fast_mode_ ? " (fast mode)" : "") << "\n";
        return 0;
      }

      auto now = std::chrono::high_resolution_clock::now();
      if (now >= nextProgress)
      {
        uint64_t available = allocatorAvailable();
        uint64_t issued = initialAllocatorCapacity > available
                              ? initialAllocatorCapacity - available
                              : 0;
        double avgIterations = count_addrs.empty()
                                   ? 0.0
                                   : (double)issued / count_addrs.size();
        double progress = size_ == 0
                              ? 0.0
                              : std::min(100.0,
                                         100.0 * avgIterations / size_);
        int64_t matches = 0;
        for (size_t k = 0; k < count_addrs.size(); ++k)
          matches += states[2 * k];
        double avgMatches = count_addrs.empty()
                                ? 0.0
                                : (double)matches / count_addrs.size();
        double elapsed = std::chrono::duration<double>(now - start).count();
        std::cout << "[countDecoupled] progress: done="
                  << (count_addrs.size() - remaining) << "/"
                  << count_addrs.size() << " avg_iterations="
                  << avgIterations << "/" << size_ << " (" << progress
                  << "%) avg_matches=" << avgMatches
                  << " allocator_available=" << available << "/"
                  << initialAllocatorCapacity << " elapsed=" << elapsed
                  << "s\n";
        if (issued == lastIssued && remaining == lastRemaining)
        {
          ++stagnantReports;
          if (stagnantReports == 2)
            dumpStallState(done, states);
        }
        else
          stagnantReports = 0;
        lastIssued = issued;
        lastRemaining = remaining;
        nextProgress = now + std::chrono::seconds(1);
      }

      if (now > deadline)
      {
        std::cerr << "[countDecoupled] WATCHDOG: " << watchdog_s_
                  << "s elapsed; " << remaining << "/" << count_addrs.size()
                  << " instances NOT done\n";
        return -1;
      }

      polls++;
      std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    return 0;
  }

  uint32_t size_;
  uint32_t num_instances_;
  double watchdog_s_;
  bool fast_mode_;
  bool legacy_single_port_watcher_;
  bool hbm_strided_writes_;
  uint64_t hbm_continuation_bank_run_entries_;
  std::string xclbin_path_;

  static bool endsWith(const std::string &s, const std::string &suffix)
  {
    return s.size() >= suffix.size() &&
           s.compare(s.size() - suffix.size(), suffix.size(), suffix) == 0;
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

    if (const char *e = std::getenv("CD_HBM_DESCRIPTOR"))
      candidates.push_back(e);

    candidates.push_back("countDecoupled.hbmports.json");    // run from workspace
    candidates.push_back("../countDecoupled.hbmports.json"); // run from build folder
    return candidates;
  }
};
