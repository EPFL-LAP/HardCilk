#pragma once

#include <SingleFpgaBenchmark.h>
#include <WidestPathDriver.h>

#include <cstdlib>
#include <iostream>
#include <string>

struct WidestPathBenchArgs
{
  std::string xclbin_path;
  std::string graph_file;
  uint32_t source = 0;
  double watchdog_s = 600.0;
  bool fast_mode = false;
  WaveformConfig wave; // hw_emu waveform capture (see --waveform/--fst)
};

inline void widest_path_usage(const char *prog)
{
  std::cerr << "Usage:\n  " << prog
            << " <xclbin_path|--cpu> <weighted_graph.csv> [source]"
               " [watchdog_s] [--fast] [--waveform[=DIR]] [--fst] [--no-vcd]\n"
            << "CSV format: src,dst,weight (directed). Whitespace is also"
               " accepted.\n";
  benchmarkWaveformUsage(std::cerr);
}

inline bool parse_widest_path_args(int argc, char **argv,
                                   WidestPathBenchArgs &out)
{
  if (argc < 3)
  {
    widest_path_usage(argv[0]);
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
      out.source = (uint32_t)std::strtoul(argv[i], nullptr, 0);
    else if (positional == 1)
      out.watchdog_s = std::atof(argv[i]);
    else
      return false;
    positional++;
  }
  return true;
}

inline int run_widest_path_benchmark(int argc, char **argv,
                                     const std::string &kernel_name)
{
  setenv("PARLAY_NUM_THREADS", "4", 0);
  WidestPathBenchArgs args;
  if (!parse_widest_path_args(argc, argv, args))
    return EXIT_FAILURE;
  if (benchmarkCpuOnlyRequested(args.xclbin_path))
    return WidestPathDriver::run_cpu_test_bench(args.graph_file, args.source);
  benchmarkApplyWaveformDefaults(args.wave, kernel_name);
  return runSingleFpgaBenchmark(
      args.xclbin_path, kernel_name,
      [&](Memory *m) {
        WidestPathDriver driver(m, args.graph_file, args.source,
                                args.watchdog_s, args.fast_mode);
        return driver.run_test_bench();
      },
      args.wave);
}
