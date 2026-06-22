#pragma once

#include <ApproxDenseSubDriver.h>
#include <SingleFpgaBenchmark.h>

#include <cstdlib>
#include <iostream>
#include <string>

struct ApproxDenseSubBenchArgs
{
  std::string xclbin_path;
  std::string graph_file;
  double epsilon = 0.1;
  double watchdog_s = 600.0;
  bool fast_mode = false;
};

inline void approx_dense_sub_usage(const char *prog)
{
  std::cerr << "Usage:\n  " << prog
            << " <xclbin_path|--cpu> <graph.txt> [epsilon] [watchdog_s]"
               " [--fast]\n"
            << "Graph format: unweighted edge list, loaded undirected.\n";
}

inline bool parse_approx_dense_sub_args(int argc, char **argv,
                                        ApproxDenseSubBenchArgs &out)
{
  if (argc < 3 || argc > 6)
  {
    approx_dense_sub_usage(argv[0]);
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
      out.epsilon = std::atof(argv[i]);
    else if (positional == 1)
      out.watchdog_s = std::atof(argv[i]);
    else
      return false;
    positional++;
  }
  return true;
}

inline int run_approx_dense_sub_benchmark(int argc, char **argv,
                                          const std::string &kernel_name)
{
  setenv("PARLAY_NUM_THREADS", "4", 0);
  ApproxDenseSubBenchArgs args;
  if (!parse_approx_dense_sub_args(argc, argv, args))
    return EXIT_FAILURE;
  if (benchmarkCpuOnlyRequested(args.xclbin_path))
    return ApproxDenseSubDriver::run_cpu_test_bench(args.graph_file,
                                                    args.epsilon);
  return runSingleFpgaBenchmark(args.xclbin_path, kernel_name, [&](Memory *m) {
    ApproxDenseSubDriver driver(m, args.graph_file, args.epsilon,
                                args.watchdog_s, args.fast_mode);
    return driver.run_test_bench();
  });
}
