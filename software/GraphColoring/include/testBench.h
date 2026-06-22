#pragma once

#include <GraphColoringDriver.h>
#include <SingleFpgaBenchmark.h>

#include <cstdlib>
#include <iostream>
#include <string>

struct GraphColoringBenchArgs
{
  std::string xclbin_path;
  std::string graph_file;
  uint32_t max_colors = 64;
  uint32_t seed = 1;
  double watchdog_s = 600.0;
  bool fast_mode = false;
};

inline void graph_coloring_usage(const char *prog)
{
  std::cerr << "Usage:\n  " << prog
            << " <xclbin_path|--cpu> <graph.txt> [max_colors] [seed]"
               " [watchdog_s] [--fast]\n"
            << "Graph format: unweighted edge list, loaded undirected.\n";
}

inline bool parse_graph_coloring_args(int argc, char **argv,
                                      GraphColoringBenchArgs &out)
{
  if (argc < 3 || argc > 7)
  {
    graph_coloring_usage(argv[0]);
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
      out.max_colors = (uint32_t)std::strtoul(argv[i], nullptr, 0);
    else if (positional == 1)
      out.seed = (uint32_t)std::strtoul(argv[i], nullptr, 0);
    else if (positional == 2)
      out.watchdog_s = std::atof(argv[i]);
    else
      return false;
    positional++;
  }
  return true;
}

inline int run_graph_coloring_benchmark(int argc, char **argv,
                                        const std::string &kernel_name)
{
  setenv("PARLAY_NUM_THREADS", "4", 0);
  GraphColoringBenchArgs args;
  if (!parse_graph_coloring_args(argc, argv, args))
    return EXIT_FAILURE;
  if (benchmarkCpuOnlyRequested(args.xclbin_path))
    return GraphColoringDriver::run_cpu_test_bench(
        args.graph_file, args.max_colors, args.seed);
  return runSingleFpgaBenchmark(args.xclbin_path, kernel_name, [&](Memory *m) {
    GraphColoringDriver driver(m, args.graph_file, args.max_colors, args.seed,
                               args.watchdog_s, args.fast_mode);
    return driver.run_test_bench();
  });
}
