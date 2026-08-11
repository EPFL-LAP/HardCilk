#pragma once

#include <SingleFpgaBenchmark.h>
#include <FullTriangleCountDecoupledDriver.h>

#include <cstdlib>
#include <iostream>
#include <string>

struct FullTriangleCountBenchArgs
{
  std::string xclbin_path;
  std::string graph_path = "/beta/bradley/Graphs/facebook_combined.txt";
  double watchdog_s = 600.0;
  bool fast_mode = false;
  bool legacy_single_port_watcher = false;
  // Build the degree-oriented CSR with GBBS before staging it for the FPGA.
  bool degree_ordering = false;
  // Host-side workaround for pre-fix bitstreams whose recycling allocator
  // self-pauses when its free list cannot cover a burst. Off by default.
  bool pause_reset = false;
  std::string telemetry_dir;
  bool telemetry_dir_explicit = false;
  WaveformConfig wave; // hw_emu waveform capture (see --waveform/--fst)
};

inline void full_triangle_count_usage(const char *prog)
{
  std::cerr << "Usage:\n  " << prog
            << " <xclbin_path|--cpu> [graph_path] [watchdog_s] "
               "[--fast] [--legacy-single-port-watcher] [--pause-reset] "
               "[--degree-order] "
               "[--telemetry-dir=DIR] "
               "[--waveform[=DIR]] [--fst] [--keep-vcd|--no-vcd]\n";
  std::cerr << "  graph_path           undirected edge list; lists are sorted,\n"
            << "                       deduplicated and stripped of self loops\n"
            << "                       by the driver before they reach HBM.\n"
            << "  --degree-order      use GBBS rankNodes/filterGraph to build\n"
            << "                       forward-neighbour lists for the FPGA;\n"
            << "                       report ordering + FPGA time.\n"
            << "  --pause-reset        clear a recycling allocator's terminal\n"
            << "                       rPause whenever a whole burst of\n"
            << "                       continuations has been recycled. Only for\n"
            << "                       bitstreams built before the allocator\n"
            << "                       self-pause was removed; a no-op otherwise.\n"
            << "  --telemetry-dir=DIR  write the telemetry .bin file into DIR\n"
            << "                       (defaults to --waveform DIR when enabled,\n"
            << "                       otherwise $HARDCILK_TELEMETRY_DIR or /tmp).\n";
  benchmarkWaveformUsage(std::cerr);
}

inline bool parse_full_triangle_count_args(int argc, char **argv,
                                           FullTriangleCountBenchArgs &out)
{
  if (argc < 2)
  {
    full_triangle_count_usage(argv[0]);
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
    if (arg == "--legacy-single-port-watcher")
    {
      out.legacy_single_port_watcher = true;
      continue;
    }
    if (arg == "--pause-reset")
    {
      out.pause_reset = true;
      continue;
    }
    if (arg == "--degree-order")
    {
      out.degree_ordering = true;
      continue;
    }
    const std::string telemetryDirPrefix = "--telemetry-dir=";
    if (arg.rfind(telemetryDirPrefix, 0) == 0)
    {
      out.telemetry_dir = arg.substr(telemetryDirPrefix.size());
      if (out.telemetry_dir.empty())
        return false;
      out.telemetry_dir_explicit = true;
      continue;
    }
    if (benchmarkTryParseWaveformArg(arg, out.wave))
      continue;
    if (positional == 0)
      out.graph_path = argv[i];
    else if (positional == 1)
      out.watchdog_s = std::atof(argv[i]);
    else
      return false;
    positional++;
  }
  return true;
}

inline int run_full_triangle_count_benchmark(int argc, char **argv,
                                            const std::string &kernel_name)
{
  FullTriangleCountBenchArgs args;
  if (!parse_full_triangle_count_args(argc, argv, args))
    return EXIT_FAILURE;
  if (benchmarkCpuOnlyRequested(args.xclbin_path))
    return FullTriangleCountDecoupledDriver::run_cpu_test_bench(
        args.graph_path, args.degree_ordering);

  benchmarkApplyWaveformDefaults(args.wave, kernel_name,
                                 "fullTriangleCountDecoupled_telemetry");
  if (!args.telemetry_dir_explicit && args.wave.enabled)
    args.telemetry_dir = args.wave.dir;
  if (!args.telemetry_dir.empty())
    setenv("HARDCILK_TELEMETRY_DIR", args.telemetry_dir.c_str(), 1);
  return runSingleFpgaBenchmark(
      args.xclbin_path, kernel_name,
      [&](Memory *m) {
        FullTriangleCountDecoupledDriver driver(
            m, args.graph_path, args.watchdog_s, args.fast_mode,
            args.xclbin_path, args.legacy_single_port_watcher,
            args.pause_reset, args.degree_ordering);
        return driver.run_test_bench();
      },
      args.wave);
}
