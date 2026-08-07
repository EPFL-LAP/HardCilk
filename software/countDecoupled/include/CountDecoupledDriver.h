#pragma once

#include <hardCilkDriver.h>
#include <memAccess_defs.h>
#include <memIO_xrt.h>

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <iostream>
#include <string>
#include <thread>
#include <vector>

using Addr = uint64_t;

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
  static constexpr int COMPUTE_FIRST_BANK = 0;
  static constexpr int COMPUTE_LAST_BANK = 31;
  static constexpr int INITIAL_SCHEDULER_VIRTUAL_CAPACITY = 32768;

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

    std::cout << "[countDecoupled] clearing 16 GiB compute HBM "
                 "(banks 0-31)\n";
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
