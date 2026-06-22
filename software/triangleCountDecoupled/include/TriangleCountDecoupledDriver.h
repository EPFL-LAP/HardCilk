#pragma once

#include <hardCilkDriver.h>
#include <memAccess_defs.h>
#include <memIO_xrt.h> // XRTMemory + bank-pinned telemetry allocation helper

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <fstream>
#include <iostream>
#include <string>
#include <thread>
#include <vector>

using Addr = uint64_t;

// Host-side mirror of the HLS watcher's TelemetryBundle as it actually lands in
// HBM (hls-processing-elements/.../triangleCountDecoupled/memAccess.cpp). NOTE:
// the `compact=bit` pragma only packs the kernel's internal register -- the AXI
// store uses the struct's NATURAL ap_uint layout, so each ap_uint<1> occupies a
// full byte and the record is 64 bytes (NOT the 128-bit packed form). Verified
// against on-device traces. Layout:
//   bytes  0..15  cont0[0..3]      (4 bytes/PE)
//   bytes 16..31  memReader[0..3]
//   bytes 32..47  reentry0[0..3]
//   bytes 48..49  padding (uint16, 0)
//   bytes 50..55  alignment hole (0)   <- cycle_count is 8-byte aligned
//   bytes 56..63  cycle_count (uint64, little-endian)
struct __attribute__((packed)) TelemetryPEStatusHost
{
  uint8_t in_empty;  // 1 = input queue empty  -> PE WAITING (no incoming task)
  uint8_t in_full;   // 1 = input task present but PE !ready -> PE STALLED
  uint8_t out_empty; // 1 = output queue empty
  uint8_t out_full;  // 1 = output produced but downstream !ready -> output STALLED
};
static_assert(sizeof(TelemetryPEStatusHost) == 4, "PE status must be 4 bytes");

struct __attribute__((packed)) TelemetryBundleHost
{
  TelemetryPEStatusHost cont0[4];     // bytes  0..15
  TelemetryPEStatusHost memReader[4]; // bytes 16..31
  TelemetryPEStatusHost reentry0[4];  // bytes 32..47
  uint16_t padding;                   // bytes 48..49
  uint8_t _reserved[6];               // bytes 50..55 (cycle_count 8B alignment)
  uint64_t cycle_count;               // bytes 56..63
};
static_assert(sizeof(TelemetryBundleHost) == 64,
              "TelemetryBundleHost must match the 64-byte HLS watcher record");

struct __attribute__((packed)) TriangleCountDecoupledRootTask
{
  Addr cont;
  Addr A;
  Addr B;
  Addr count;
  uint32_t size;
  uint32_t i;
  uint32_t j;
  uint32_t a_i;
  uint32_t b_j;
  uint8_t _padding[12];
};

static_assert(sizeof(TriangleCountDecoupledRootTask) ==
                  sizeof(whileLoopMain_reentry0_task),
              "Host root task must match whileLoopMain_reentry0_task layout");

inline bool triangleCountDecoupledDoneCondition(int32_t value)
{
  return value != 0;
}

class TriangleCountDecoupledDriver : public hardCilkDriver
{
public:
  TriangleCountDecoupledDriver(Memory *memory, uint32_t size,
                               uint32_t num_instances = 1,
                               double watchdog_s = 600.0,
                               bool fast_mode = false)
      : hardCilkDriver(memory), size_(std::max<uint32_t>(1, size)),
        num_instances_(std::max<uint32_t>(1, num_instances)),
        watchdog_s_(watchdog_s), fast_mode_(fast_mode) {}

  static int run_cpu_test_bench(uint32_t size)
  {
    size = std::max<uint32_t>(1, size);
    std::vector<int32_t> A, B;
    buildInputs(size, A, B);
    int32_t matches = referenceCount(A, B);
    std::cout << "[triangleCountDecoupled-CPU] size=" << size
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
    std::vector<int32_t> A, B;
    buildInputs(size_, A, B);
    const int32_t expected = referenceCount(A, B);
    const uint64_t array_bytes = (uint64_t)size_ * sizeof(int32_t);

    // The watcher is mapped to HBM[16:31], while every compute/server master is
    // mapped to HBM[0:15]. Keep telemetry and compute buffers in those disjoint
    // ranges explicitly; the default XRT allocator can otherwise spill small
    // per-instance BOs into the watcher-only banks after enough allocations.
    Addr telemetry_base = reserveTelemetry();
    configureComputeBankRange();

    // Build N non-overlapping instances and one root task each.
    std::vector<TriangleCountDecoupledRootTask> roots(N);
    std::vector<Addr> count_addrs(N), done_addrs(N);
    int32_t count_state[2] = {0, 0};
    for (uint32_t k = 0; k < N; ++k)
    {
      Addr A_addr = allocateComputeMem(array_bytes, 512);
      Addr B_addr = allocateComputeMem(array_bytes, 512);
      Addr count_addr = allocateComputeMem(2 * sizeof(int32_t), 512);
      Addr done_addr = count_addr + sizeof(int32_t);
      memory_->copyToDevice(A_addr, reinterpret_cast<const uint8_t *>(A.data()),
                            array_bytes);
      memory_->copyToDevice(B_addr, reinterpret_cast<const uint8_t *>(B.data()),
                            array_bytes);
      memory_->copyToDevice(count_addr,
                            reinterpret_cast<const uint8_t *>(count_state),
                            sizeof(count_state));
      TriangleCountDecoupledRootTask &r = roots[k];
      r.cont = done_addr;
      r.A = A_addr;
      r.B = B_addr;
      r.count = count_addr;
      r.size = size_;
      r.i = 0;
      r.j = 0;
      r.a_i = 0;
      r.b_j = 0;
      count_addrs[k] = count_addr;
      done_addrs[k] = done_addr;
    }

    std::cout << "[triangleCountDecoupled] instances=" << N << " size=" << size_
              << " expected_matches_each=" << expected << "\n";

    // initSystem writes the whole vector to the root scheduler and sets
    // fifoTail=N, so all N root tasks are live concurrently.
    initSystem(roots, &triangleCountDecoupledDoneCondition, 0, 0, false);

    auto t_kernel_start = std::chrono::high_resolution_clock::now();
    startSystem();
    int rc = pollAllDone(done_addrs, t_kernel_start);
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

    std::cout << "[triangleCountDecoupled-FPGA] execution time: "
              << std::chrono::duration<double>(t_kernel_done - t_kernel_start)
                     .count()
              << "s\n";
    std::cout << "[triangleCountDecoupled-FPGA] end-to-end time: "
              << std::chrono::duration<double>(t_done - t0).count() << "s\n";
    std::cout << "[triangleCountDecoupled-FPGA] passed=" << passed << "/" << N
              << " expected_each=" << expected << "\n";

    // Always dump telemetry (even on failure) so a hang/mismatch can be analyzed.
    dumpTelemetry(telemetry_base);

    if (rc == 0 && passed == N)
    {
      std::cout << "[triangleCountDecoupled] PASS\n";
      return 0;
    }

    std::cerr << "[triangleCountDecoupled] FAIL passed=" << passed << "/" << N;
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
    return isEmulation() ? (4ULL << 20) : (64ULL << 20);
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
      std::vector<uint8_t> zeros(windowBytes, 0);
      memory_->copyToDevice(base, zeros.data(), zeros.size());
      // Push the zeroed window down to device memory so stale device contents
      // can't masquerade as bundles, and so the watcher overwrites a known-0 region.
      if (auto *xm = dynamic_cast<XRTMemory *>(memory_))
      {
        try { xm->syncRegionToDevice(base, zeros.size()); }
        catch (const std::exception &e)
        { std::cerr << "[telemetry] zero-fill sync threw: " << e.what() << "\n"; }
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

  // Read back the telemetry window, find the populated prefix (up to the first
  // all-zero/unwritten bundle), and write it to a timestamped /tmp file.
  void dumpTelemetry(Addr telemetry_base)
  {
    if (telemetry_base == 0)
      return;

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

    const size_t stride = sizeof(TelemetryBundleHost);
    const size_t maxBundles = buf.size() / stride;

    // A record is "unwritten" iff its cycle_count is 0: the watcher stamps every
    // emitted record with the (always non-zero) free-running cycle counter, and
    // reserveTelemetry zero-fills the window beforehand.
    auto bundleIsZero = [&](size_t idx) {
      const TelemetryBundleHost *b = reinterpret_cast<const TelemetryBundleHost *>(
          buf.data() + idx * stride);
      return b->cycle_count == 0;
    };

    // The watcher's write_idx is a static in a free-running (ap_ctrl_none) kernel,
    // so it persists across host runs until the FPGA is reprogrammed -- the
    // populated run does NOT necessarily start at offset 0. Find the first
    // populated bundle, then count the contiguous populated run from there.
    size_t firstBundle = 0;
    while (firstBundle < maxBundles && bundleIsZero(firstBundle))
      ++firstBundle;
    size_t lastBundle = firstBundle;
    while (lastBundle < maxBundles && !bundleIsZero(lastBundle))
      ++lastBundle;
    const size_t written = lastBundle - firstBundle;
    const size_t byteOffset = firstBundle * stride;
    if (firstBundle == maxBundles)
      std::cout << "[telemetry] window empty (watcher wrote nothing in [base, "
                   "base+window); if this is a repeat run, reprogram the FPGA to "
                   "reset the watcher write index)\n";
    else if (firstBundle != 0)
      std::cout << "[telemetry] populated run starts at bundle " << firstBundle
                << " (byte offset " << byteOffset
                << ") -- watcher write_idx carried over from a prior run\n";

    char ts[32];
    std::time_t now = std::time(nullptr);
    std::strftime(ts, sizeof(ts), "%Y%m%d_%H%M%S", std::localtime(&now));
    std::string path =
        std::string("/tmp/triangleCountDecoupled_telemetry_") + ts + ".bin";

    std::ofstream out(path, std::ios::binary);
    if (!out)
    {
      std::cerr << "[telemetry] could not open " << path << " for writing\n";
      return;
    }
    out.write(reinterpret_cast<const char *>(buf.data() + byteOffset),
              static_cast<std::streamsize>(written * stride));
    out.close();

    std::cout << "[telemetry] wrote " << written << " bundles ("
              << (written * stride) << " bytes) to:\n"
              << "[telemetry] " << path << "\n";
  }

  static void buildInputs(uint32_t size, std::vector<int32_t> &A,
                          std::vector<int32_t> &B)
  {
    A.resize(size);
    B.resize(size);
    uint32_t matches = size / 2;
    for (uint32_t i = 0; i < matches; i++)
    {
      A[i] = (int32_t)i;
      B[i] = (int32_t)i;
    }
    for (uint32_t i = matches; i < size; i++)
    {
      A[i] = (int32_t)(i + size);
      B[i] = (int32_t)i;
    }
  }

  static int32_t referenceCount(const std::vector<int32_t> &A,
                                const std::vector<int32_t> &B)
  {
    uint32_t i = 0;
    uint32_t j = 0;
    int32_t count = 0;
    while (i < A.size() && j < B.size())
    {
      if (A[i] == B[j])
      {
        count++;
        i++;
        j++;
      }
      else if (A[i] < B[j])
      {
        i++;
      }
      else
      {
        j++;
      }
    }
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
        std::cout << "[triangleCountDecoupled] done after " << polls
                  << " polls";
        if (fast_mode_)
          std::cout << " (fast mode)";
        std::cout << "\n";
        return 0;
      }

      auto now = std::chrono::high_resolution_clock::now();
      if (now > deadline)
      {
        std::cerr << "[triangleCountDecoupled] WATCHDOG: " << watchdog_s_
                  << "s elapsed without done. done=" << done << "\n";
        return -1;
      }

      polls++;
      std::this_thread::sleep_for(fast_mode_ ? std::chrono::milliseconds(10)
                                             : std::chrono::microseconds(200));
    }
  }

  // Wait until EVERY instance's continuation has fired (all done flags non-zero).
  // Each instance writes its own done flag, so completion is detected per-instance
  // and we stop re-reading an instance once it is done.
  int pollAllDone(const std::vector<Addr> &done_addrs,
                  std::chrono::high_resolution_clock::time_point start)
  {
    const auto deadline = start + std::chrono::duration<double>(watchdog_s_);
    std::vector<char> done(done_addrs.size(), 0);
    size_t remaining = done_addrs.size();
    uint64_t polls = 0;
    while (remaining > 0)
    {
      if (!fast_mode_ && checkPaused() == 0)
        managePausedServer();

      for (size_t k = 0; k < done_addrs.size(); ++k)
      {
        if (done[k])
          continue;
        int32_t d = 0;
        memory_->copyFromDevice(reinterpret_cast<uint8_t *>(&d), done_addrs[k],
                                sizeof(d));
        if (d != 0)
        {
          done[k] = 1;
          --remaining;
        }
      }

      if (remaining == 0)
      {
        std::cout << "[triangleCountDecoupled] all " << done_addrs.size()
                  << " instances done after " << polls << " polls"
                  << (fast_mode_ ? " (fast mode)" : "") << "\n";
        return 0;
      }

      if (std::chrono::high_resolution_clock::now() > deadline)
      {
        std::cerr << "[triangleCountDecoupled] WATCHDOG: " << watchdog_s_
                  << "s elapsed; " << remaining << "/" << done_addrs.size()
                  << " instances NOT done\n";
        return -1;
      }

      polls++;
      std::this_thread::sleep_for(fast_mode_ ? std::chrono::milliseconds(10)
                                             : std::chrono::microseconds(200));
    }
    return 0;
  }

  uint32_t size_;
  uint32_t num_instances_;
  double watchdog_s_;
  bool fast_mode_;
};
