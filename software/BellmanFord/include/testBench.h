#pragma once

#include <BellmanFordDriver.h>
#include <SingleFpgaBenchmark.h>

#include <cstdlib>
#include <iostream>
#include <string>

struct BellmanFordBenchArgs
{
  std::string xclbin_path;
  std::string graph_file;
  uint32_t source = 0;
  double watchdog_s = 600.0;
  bool fast_mode = false;
};

inline void bellman_ford_usage(const char *prog)
{
  std::cerr << "Usage:\n  " << prog
            << " <xclbin_path|--cpu> <weighted_graph.csv> [source]"
               " [watchdog_s] [--fast]\n"
            << "CSV format: src,dst,weight (directed). Whitespace is also"
               " accepted.\n";
}

inline bool parse_bellman_ford_args(int argc, char **argv,
                                    BellmanFordBenchArgs &out)
{
  if (argc < 3 || argc > 6)
  {
    bellman_ford_usage(argv[0]);
    return false;
  }
  out.xclbin_path = argv[1];
  out.graph_file = argv[2];
  int positional = 0;
  for (int i = 3; i < argc; i++)
  {
    std::string arg = argv[i];
    if (arg == "--fast")
    {
      out.fast_mode = true;
      continue;
    }
    if (positional == 0)
      out.source = (uint32_t)std::strtoul(argv[i], nullptr, 0);
    else if (positional == 1)
      out.watchdog_s = std::atof(argv[i]);
    else
      return false;
    positional++;
  }
  return true;
}

inline int run_bellman_ford_benchmark(int argc, char **argv,
                                      const std::string &kernel_name)
{
  setenv("PARLAY_NUM_THREADS", "4", 0);
  BellmanFordBenchArgs args;
  if (!parse_bellman_ford_args(argc, argv, args))
    return EXIT_FAILURE;
  if (benchmarkCpuOnlyRequested(args.xclbin_path))
    return BellmanFordDriver::run_cpu_test_bench(args.graph_file, args.source);
  return runSingleFpgaBenchmark(args.xclbin_path, kernel_name, [&](Memory *m) {
    BellmanFordDriver driver(m, args.graph_file, args.source, args.watchdog_s,
                             args.fast_mode);
    return driver.run_test_bench();
  });
}
