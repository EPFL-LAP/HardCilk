#pragma once

#include <SingleFpgaBenchmark.h>
#include <TriangleCountDecoupledDriver.h>

#include <cstdlib>
#include <iostream>
#include <string>

struct TriangleCountDecoupledBenchArgs
{
  std::string xclbin_path;
  uint32_t size = 100;
  uint32_t num_instances = 1; // independent root tasks launched concurrently
  double watchdog_s = 600.0;
  bool fast_mode = false;
};

inline void triangle_count_decoupled_usage(const char *prog)
{
  std::cerr << "Usage:\n  " << prog
            << " <xclbin_path|--cpu> [size] [num_instances] [watchdog_s] "
               "[--fast]\n";
}

inline bool parse_triangle_count_decoupled_args(
    int argc, char **argv, TriangleCountDecoupledBenchArgs &out)
{
  if (argc < 2 || argc > 6)
  {
    triangle_count_decoupled_usage(argv[0]);
    return false;
  }
  out.xclbin_path = argv[1];
  int positional = 0;
  for (int i = 2; i < argc; i++)
  {
    std::string arg = argv[i];
    if (arg == "--fast")
    {
      out.fast_mode = true;
      continue;
    }
    if (positional == 0)
      out.size = (uint32_t)std::strtoul(argv[i], nullptr, 0);
    else if (positional == 1)
      out.num_instances = (uint32_t)std::strtoul(argv[i], nullptr, 0);
    else if (positional == 2)
      out.watchdog_s = std::atof(argv[i]);
    else
      return false;
    positional++;
  }
  return true;
}

inline int run_triangle_count_decoupled_benchmark(
    int argc, char **argv, const std::string &kernel_name)
{
  TriangleCountDecoupledBenchArgs args;
  if (!parse_triangle_count_decoupled_args(argc, argv, args))
    return EXIT_FAILURE;
  if (benchmarkCpuOnlyRequested(args.xclbin_path))
    return TriangleCountDecoupledDriver::run_cpu_test_bench(args.size);
  return runSingleFpgaBenchmark(args.xclbin_path, kernel_name, [&](Memory *m) {
    TriangleCountDecoupledDriver driver(m, args.size, args.num_instances,
                                        args.watchdog_s, args.fast_mode);
    return driver.run_test_bench();
  });
}
