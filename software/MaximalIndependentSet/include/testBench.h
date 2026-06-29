#pragma once

#include <MaximalIndependentSetDriver.h>
#include <SingleFpgaBenchmark.h>

#include <cstdlib>
#include <iostream>
#include <string>

struct MisBenchArgs
{
  std::string xclbin_path;
  std::string graph_file;
  uint32_t seed = 1;
  double watchdog_s = 600.0;
  bool fast_mode = false;
  WaveformConfig wave; // hw_emu waveform capture (see --waveform/--fst)
};

inline void mis_usage(const char *prog)
{
  std::cerr << "Usage:\n  " << prog
            << " <xclbin_path|--cpu> <graph.txt> [seed] [watchdog_s]"
               " [--fast] [--waveform[=DIR]] [--fst] [--no-vcd]\n"
            << "Graph format: unweighted edge list, loaded undirected.\n";
  benchmarkWaveformUsage(std::cerr);
}

inline bool parse_mis_args(int argc, char **argv, MisBenchArgs &out)
{
  if (argc < 3)
  {
    mis_usage(argv[0]);
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
    if (benchmarkTryParseWaveformArg(arg, out.wave))
      continue;
    if (positional == 0)
      out.seed = (uint32_t)std::strtoul(argv[i], nullptr, 0);
    else if (positional == 1)
      out.watchdog_s = std::atof(argv[i]);
    else
      return false;
    positional++;
  }
  return true;
}

inline int run_mis_benchmark(int argc, char **argv,
                             const std::string &kernel_name)
{
  setenv("PARLAY_NUM_THREADS", "4", 0);
  MisBenchArgs args;
  if (!parse_mis_args(argc, argv, args))
    return EXIT_FAILURE;
  if (benchmarkCpuOnlyRequested(args.xclbin_path))
    return MaximalIndependentSetDriver::run_cpu_test_bench(args.graph_file,
                                                           args.seed);
  benchmarkApplyWaveformDefaults(args.wave, kernel_name);
  return runSingleFpgaBenchmark(
      args.xclbin_path, kernel_name,
      [&](Memory *m) {
        MaximalIndependentSetDriver driver(m, args.graph_file, args.seed,
                                           args.watchdog_s, args.fast_mode);
        return driver.run_test_bench();
      },
      args.wave);
}
