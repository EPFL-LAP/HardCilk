#pragma once

#include <SingleFpgaBenchmark.h>
#include <TriangleCountDriver.h>

#include <cstdlib>
#include <iostream>
#include <string>

struct TriangleCountBenchArgs
{
  std::string xclbin_path;
  std::string graph_path = "/beta/bradley/Graphs/tinyGraph.txt";
  double watchdog_s = 600.0;
  bool fast_mode = false;
  WaveformConfig wave;
};

inline void triangle_count_usage(const char *prog)
{
  std::cerr << "Usage:\n  " << prog
            << " <xclbin_path|--cpu> [graph_path] [watchdog_s] [--fast] "
               "[--waveform[=DIR]] [--fst] [--keep-vcd|--no-vcd]\n"
            << "  graph_path   undirected edge list; the driver removes self "
               "loops and duplicates,\n"
            << "               then applies the original degree ordering before "
               "staging it.\n"
            << "  --fast       reduce host polling and suppress periodic progress "
               "reports.\n";
  benchmarkWaveformUsage(std::cerr);
}

inline bool parse_triangle_count_args(int argc, char **argv,
                                      TriangleCountBenchArgs &out)
{
  if (argc < 2)
  {
    triangle_count_usage(argv[0]);
    return false;
  }

  out.xclbin_path = argv[1];
  int positional = 0;
  for (int i = 2; i < argc; ++i)
  {
    const std::string arg = argv[i];
    if (arg == "--fast")
    {
      out.fast_mode = true;
      continue;
    }
    if (benchmarkTryParseWaveformArg(arg, out.wave))
      continue;
    if (arg.rfind("--", 0) == 0)
    {
      std::cerr << "[triangleCount] unknown option: " << arg << "\n";
      triangle_count_usage(argv[0]);
      return false;
    }
    if (positional == 0)
      out.graph_path = arg;
    else if (positional == 1)
      out.watchdog_s = std::atof(argv[i]);
    else
    {
      triangle_count_usage(argv[0]);
      return false;
    }
    ++positional;
  }
  return true;
}

inline int run_triangle_count_benchmark(int argc, char **argv,
                                        const std::string &kernel_name)
{
  // Match the other GBBS-backed graph drivers while leaving an explicit user
  // setting untouched.
  setenv("PARLAY_NUM_THREADS", "4", 0);

  TriangleCountBenchArgs args;
  if (!parse_triangle_count_args(argc, argv, args))
    return EXIT_FAILURE;

  if (benchmarkCpuOnlyRequested(args.xclbin_path))
    return TriangleCountDriver::run_cpu_test_bench(args.graph_path);

  benchmarkApplyWaveformDefaults(args.wave, kernel_name);
  return runSingleFpgaBenchmark(
      args.xclbin_path, kernel_name,
      [&](Memory *memory) {
        TriangleCountDriver driver(memory, args.graph_path, args.watchdog_s,
                                   args.fast_mode);
        return driver.run_test_bench();
      },
      args.wave);
}
