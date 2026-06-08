#pragma once
// ─────────────────────────────────────────────────────────────────────────────
// testBench.h — single-FPGA entry point for the BFS host.
//
// This is the CMAC-free analogue of the driver framework's main_helper.h: it
// opens ONE device, loads the xclbin, opens the single user-managed CU as an
// xrt::ip, wraps it in an XRTMemory, and runs the BFSDriver. No VNx / CMAC,
// no multi-FPGA controls.
// ─────────────────────────────────────────────────────────────────────────────

#include <BFSDriver.h>
#include <memIO_xrt.h>

#include <experimental/xrt_ip.h>
#include <experimental/xrt_xclbin.h>
#include <xrt/xrt_device.h>

#include <atomic>
#include <cstdlib>
#include <chrono>
#include <iostream>
#include <string>
#include <thread>

struct BfsBenchArgs {
  std::string xclbin_path;
  std::string graph_file;
  int source = 0;
  int max_depth = 0;        // 0 == unbounded (vertex_count)
  double watchdog_s = 600;  // wall-clock deadline for the management loop
};

inline void bfs_print_usage(const char *prog) {
  std::cerr
      << "\nUsage:\n  " << prog
      << " <xclbin_path> <graph_file> [source] [max_depth] [watchdog_s]\n\n"
      << "Arguments:\n"
      << "  xclbin_path   .xclbin to load onto the FPGA, or --cpu for CPU-only\n"
      << "  graph_file    edge-list graph (loaded undirected), synthetic:star2m,\n"
      << "                synthetic:star4m, synthetic:ring2m, or synthetic:wikimix\n"
      << "  source        BFS source vertex            [default: 0]\n"
      << "  max_depth     stop after this depth, 0=full [default: 0]\n"
      << "  watchdog_s    management-loop timeout (s)   [default: 600]\n\n"
      << "Synthetic graphs:\n"
      << "  synthetic:star2m creates a 2,000,002-vertex / 4,000,000-edge\n"
      << "  convergence graph: one root -> 2,000,000 middle vertices -> one\n"
      << "  shared sink. Source is fixed to root vertex 0.\n"
      << "  synthetic:star4m creates a 4,000,001-vertex / 4,000,000-edge\n"
      << "  one-level star. Source is fixed to root vertex 0.\n"
      << "  synthetic:ring2m creates a 2,000,000-node undirected ring with\n"
      << "  randomized vertex IDs in memory order (4,000,000 CSR arcs).\n"
      << "  synthetic:wikimix creates a wiki-Talk-like level profile with a\n"
      << "  1,520,907-vertex frontier that mostly points to already visited\n"
      << "  previous/same-level vertices plus about 20k next-level vertices.\n\n";
}

inline bool bfs_parse_args(int argc, char *argv[], BfsBenchArgs &out) {
  if (argc < 3 || argc > 6) {
    bfs_print_usage(argv[0]);
    return false;
  }
  out.xclbin_path = argv[1];
  out.graph_file = argv[2];
  if (argc >= 4) out.source = std::atoi(argv[3]);
  if (argc >= 5) out.max_depth = std::atoi(argv[4]);
  if (argc >= 6) out.watchdog_s = std::atof(argv[5]);
  return true;
}

inline bool bfs_check_runtime_env() {
  const char *emu_mode = std::getenv("XCL_EMULATION_MODE");
  const bool is_emulation = emu_mode != nullptr && std::string(emu_mode).size() != 0;
  if (!is_emulation) return true;

  const char *xrt = std::getenv("XILINX_XRT");
  if (xrt != nullptr && std::string(xrt).size() != 0) return true;

  std::cerr << "[Init] XCL_EMULATION_MODE=" << emu_mode
            << " but XILINX_XRT is not set.\n"
            << "[Init] Run: source /opt/xilinx/xrt/setup.sh\n";
  return false;
}

inline bool bfs_cpu_only_requested(const std::string &xclbin_path) {
  return xclbin_path == "--cpu" || xclbin_path == "cpu" ||
         xclbin_path == "CPU";
}

class ScopedHeartbeat {
public:
  ScopedHeartbeat(const std::string &label, int period_s = 10)
      : label_(label), period_s_(period_s), running_(true),
        start_(std::chrono::high_resolution_clock::now()),
        thread_([this]() { run(); }) {}

  ~ScopedHeartbeat() {
    running_ = false;
    if (thread_.joinable()) thread_.join();
  }

private:
  void run() {
    while (running_) {
      for (int i = 0; i < period_s_ && running_; i++)
        std::this_thread::sleep_for(std::chrono::seconds(1));
      if (!running_) break;

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

inline int run_bfs_benchmark(int argc, char *argv[],
                             const std::string &kernel_name) {
  BfsBenchArgs args;
  if (!bfs_parse_args(argc, argv, args)) return EXIT_FAILURE;
  if (bfs_cpu_only_requested(args.xclbin_path)) {
    auto start = std::chrono::high_resolution_clock::now();
    int rc = BFSDriver::run_cpu_test_bench(args.graph_file, args.source,
                                           args.max_depth);
    auto end = std::chrono::high_resolution_clock::now();
    std::cout << "[Run] CPU-only total wall time: "
              << std::chrono::duration<double>(end - start).count() << "s\n";
    return rc == 0 ? EXIT_SUCCESS : EXIT_FAILURE;
  }

  if (!bfs_check_runtime_env()) return EXIT_FAILURE;

  xrt::device device(0);
  std::cout << "[Init] Loading '" << args.xclbin_path << "' onto FPGA 0..."
            << std::endl;
  xrt::uuid uuid;
  {
    ScopedHeartbeat heartbeat("loading xclbin");
    uuid = device.load_xclbin(args.xclbin_path);
  }
  std::cout << "[Init] xclbin loaded." << std::endl;

  xrt::ip kernel(device, uuid, kernel_name);
  std::cout << "[Init] Opened CU '" << kernel_name << "'.\n";

  XRTMemory memory(device, kernel);

  BFSDriver driver(&memory, args.graph_file, args.source, args.max_depth,
                   args.watchdog_s);

  auto start = std::chrono::high_resolution_clock::now();
  int rc = driver.run_test_bench();
  auto end = std::chrono::high_resolution_clock::now();
  std::cout << "[Run] total wall time: "
            << std::chrono::duration<double>(end - start).count() << "s\n";

  return rc == 0 ? EXIT_SUCCESS : EXIT_FAILURE;
}
