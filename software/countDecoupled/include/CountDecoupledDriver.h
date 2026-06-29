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
#include <iostream>
#include <sstream>
#include <string>
#include <thread>
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
  Addr A;
  Addr count;
  uint32_t size;
  uint32_t i;
  uint8_t _padding[32];
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
                               bool fast_mode = false)
      : hardCilkDriver(memory), size_(std::max<uint32_t>(1, size)),
        num_instances_(std::max<uint32_t>(1, num_instances)),
        watchdog_s_(watchdog_s), fast_mode_(fast_mode) {}

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

    // Aggregate each per-instance buffer type into one BO. Thousands of tiny BOs
    // get distributed across every bank by XRT and leave no completely free,
    // adjacent banks for the large continuation pool allocated by initSystem.
    const uint64_t all_array_bytes = array_bytes * N;
    Addr all_A_addr = allocateComputeMem(all_array_bytes, 512);
    Addr all_count_addr = allocateComputeMem((uint64_t)N * 2 * sizeof(int32_t),
                                             512);

    std::vector<int32_t> all_A((uint64_t)N * size_);
    std::vector<int32_t> all_count((uint64_t)N * 2, 0);
    for (uint32_t k = 0; k < N; ++k)
    {
      std::copy(A.begin(), A.end(), all_A.begin() + (uint64_t)k * size_);
    }
    memory_->copyToDevice(all_A_addr,
                          reinterpret_cast<const uint8_t *>(all_A.data()),
                          all_array_bytes);
    memory_->copyToDevice(all_count_addr,
                          reinterpret_cast<const uint8_t *>(all_count.data()),
                          all_count.size() * sizeof(int32_t));

    // Build N non-overlapping instances and one root task each.
    std::vector<CountDecoupledRootTask> roots(N);
    std::vector<Addr> count_addrs(N), done_addrs(N);
    for (uint32_t k = 0; k < N; ++k)
    {
      Addr A_addr = all_A_addr + (uint64_t)k * array_bytes;
      Addr count_addr = all_count_addr +
                        (uint64_t)k * 2 * sizeof(int32_t);
      Addr done_addr = count_addr + sizeof(int32_t);
      CountDecoupledRootTask &r = roots[k];
      r.cont = done_addr;
      r.A = A_addr;
      r.count = count_addr;
      r.size = size_;
      r.i = 0;
      count_addrs[k] = count_addr;
      done_addrs[k] = done_addr;
    }

    std::cout << "[countDecoupled] instances=" << N << " size=" << size_
              << " expected_matches_each=" << expected << "\n";

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

    // Always dump telemetry (even on failure) so a hang/mismatch can be analyzed.
    dumpTelemetry(telemetry_base);

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
  static constexpr Addr TELEMETRY_GLOBAL_BASE = 0x200000000ULL;
  static constexpr int INITIAL_SCHEDULER_VIRTUAL_CAPACITY = 32768;
  bool isEmulation() const
  {
    const char *emuMode = std::getenv("XCL_EMULATION_MODE");
    return emuMode != nullptr && !std::string(emuMode).empty();
  }

  uint64_t getTelemetryReserveBytes() const
  {
    return isEmulation() ? (256ULL << 20) : (8ULL << 30);
  }

  uint64_t getTelemetryWindowBytes() const
  {
    return getTelemetryReserveBytes();
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
      for (uint64_t off = 0; off < windowBytes; off += zeros.size())
      {
        const uint64_t n = std::min<uint64_t>(zeros.size(), windowBytes - off);
        memory_->copyToDevice(base + off, zeros.data(), n);
        // Push the zeroed window down to device memory so stale device contents
        // can't masquerade as bundles, and so the watcher overwrites a known-0 region.
        if (auto *xm = dynamic_cast<XRTMemory *>(memory_))
        {
          try { xm->syncRegionToDevice(base + off, n); }
          catch (const std::exception &e)
          { std::cerr << "[telemetry] zero-fill sync threw: " << e.what() << "\n"; }
        }
      }
      std::cout << "[telemetry] reserved " << (reserveBytes >> 20)
                << " MB at 0x" << std::hex << base << std::dec
                << ", zeroed " << (windowBytes >> 10)
                << " KB readback window\n";
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

      char ts[32];
      std::time_t now = std::time(nullptr);
      std::strftime(ts, sizeof(ts), "%Y%m%d_%H%M%S", std::localtime(&now));
      std::string path =
          std::string("/tmp/countDecoupled_telemetry_") + ts + ".bin";

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
        std::vector<std::string> candidates;
        if (const char *e = std::getenv("CD_HBM_DESCRIPTOR"))
          candidates.push_back(e);
        candidates.push_back("countDecoupled.hbmports.json");
        candidates.push_back("../countDecoupled.hbmports.json");
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
          uint32_t ver = 1;
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

    char ts[32];
    std::time_t now = std::time(nullptr);
    std::strftime(ts, sizeof(ts), "%Y%m%d_%H%M%S", std::localtime(&now));
    std::string path =
        std::string("/tmp/countDecoupled_telemetry_") + ts + ".bin";

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
    //   [0:8)  magic "HCKTRACE"   [8:12) u32 version=1   [12:16) u32 flags
    //   [16:24) u64 json_length   [24:32) u64 beats_offset (32-aligned)
    //   [32 : 32+json_length) JSON descriptor, then zero pad to beats_offset.
    {
      std::vector<std::string> candidates;
      if (const char *e = std::getenv("CD_HBM_DESCRIPTOR"))
        candidates.push_back(e);
      candidates.push_back("countDecoupled.hbmports.json");    // run from workspace
      candidates.push_back("../countDecoupled.hbmports.json"); // run from build folder
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
        uint32_t ver = 1;
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
      if (!fast_mode_ && checkPaused() == 0)
        managePausedServer();

      memory_->copyFromDevice(reinterpret_cast<uint8_t *>(states.data()),
                              count_addrs.front(),
                              states.size() * sizeof(int32_t));
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
};
