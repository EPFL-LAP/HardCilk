#pragma once

#include <memIO_xrt.h>

#include <experimental/xrt_ip.h>
#include <experimental/xrt_xclbin.h>
#include <xrt/xrt_device.h>

#include <atomic>
#include <chrono>
#include <cstdlib>
#include <iostream>
#include <string>
#include <thread>

inline bool benchmarkCpuOnlyRequested(const std::string &xclbin_path)
{
  return xclbin_path == "--cpu" || xclbin_path == "cpu" ||
         xclbin_path == "CPU";
}

inline bool benchmarkCheckRuntimeEnv()
{
  const char *emu_mode = std::getenv("XCL_EMULATION_MODE");
  const bool is_emulation =
      emu_mode != nullptr && std::string(emu_mode).size() != 0;
  if (!is_emulation)
    return true;

  const char *xrt = std::getenv("XILINX_XRT");
  if (xrt != nullptr && std::string(xrt).size() != 0)
    return true;

  std::cerr << "[Init] XCL_EMULATION_MODE=" << emu_mode
            << " but XILINX_XRT is not set.\n"
            << "[Init] Run: source /opt/xilinx/xrt/setup.sh\n";
  return false;
}

class BenchmarkHeartbeat
{
public:
  BenchmarkHeartbeat(const std::string &label, int period_s = 10)
      : label_(label), period_s_(period_s), running_(true),
        start_(std::chrono::high_resolution_clock::now()),
        thread_([this]() { run(); }) {}

  ~BenchmarkHeartbeat()
  {
    running_ = false;
    if (thread_.joinable())
      thread_.join();
  }

private:
  void run()
  {
    while (running_)
    {
      for (int i = 0; i < period_s_ && running_; i++)
        std::this_thread::sleep_for(std::chrono::seconds(1));
      if (!running_)
        break;
      double elapsed =
          std::chrono::duration<double>(
              std::chrono::high_resolution_clock::now() - start_)
              .count();
      std::cout << "[Init] still " << label_ << " after " << elapsed << "s"
                << std::endl;
    }
  }

  std::string label_;
  int period_s_;
  std::atomic_bool running_;
  std::chrono::high_resolution_clock::time_point start_;
  std::thread thread_;
};

template <class RunWithMemory>
int runSingleFpgaBenchmark(const std::string &xclbin_path,
                           const std::string &kernel_name,
                           RunWithMemory run_with_memory)
{
  if (!benchmarkCheckRuntimeEnv())
    return EXIT_FAILURE;

  xrt::device device(0);
  std::cout << "[Init] Loading '" << xclbin_path << "' onto FPGA 0..."
            << std::endl;
  xrt::uuid uuid;
  {
    BenchmarkHeartbeat heartbeat("loading xclbin");
    uuid = device.load_xclbin(xclbin_path);
  }
  std::cout << "[Init] xclbin loaded." << std::endl;

  xrt::ip kernel(device, uuid, kernel_name);
  std::cout << "[Init] Opened CU '" << kernel_name << "'.\n";

  XRTMemory memory(device, kernel);
  auto start = std::chrono::high_resolution_clock::now();
  int rc = run_with_memory(&memory);
  auto end = std::chrono::high_resolution_clock::now();
  std::cout << "[Run] total wall time (including validation): "
            << std::chrono::duration<double>(end - start).count() << "s\n";

  return rc == 0 ? EXIT_SUCCESS : EXIT_FAILURE;
}
