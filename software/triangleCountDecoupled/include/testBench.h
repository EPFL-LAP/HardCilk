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
  bool legacy_single_port_watcher = false;
  std::string telemetry_dir;
  bool telemetry_dir_explicit = false;
  WaveformConfig wave; // hw_emu waveform capture (see --waveform/--fst)
};

inline void triangle_count_decoupled_usage(const char *prog)
{
  std::cerr << "Usage:\n  " << prog
            << " <xclbin_path|--cpu> [size] [num_instances] [watchdog_s] "
               "[--fast] [--legacy-single-port-watcher] "
               "[--telemetry-dir=DIR] "
               "[--waveform[=DIR]] [--fst] [--keep-vcd|--no-vcd]\n";
  std::cerr << "  --telemetry-dir=DIR  write the telemetry .bin file into DIR\n"
            << "                       (defaults to --waveform DIR when enabled,\n"
            << "                       otherwise $HARDCILK_TELEMETRY_DIR or /tmp).\n";
  benchmarkWaveformUsage(std::cerr);
}

inline bool parse_triangle_count_decoupled_args(
    int argc, char **argv, TriangleCountDecoupledBenchArgs &out)
{
  if (argc < 2)
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
    if (arg == "--legacy-single-port-watcher")
    {
      out.legacy_single_port_watcher = true;
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

  benchmarkApplyWaveformDefaults(args.wave, kernel_name,
                                 "triangleCountDecoupled_telemetry");
  if (!args.telemetry_dir_explicit && args.wave.enabled)
    args.telemetry_dir = args.wave.dir;
  if (!args.telemetry_dir.empty())
    setenv("HARDCILK_TELEMETRY_DIR", args.telemetry_dir.c_str(), 1);
  return runSingleFpgaBenchmark(
      args.xclbin_path, kernel_name,
      [&](Memory *m) {
        TriangleCountDecoupledDriver driver(m, args.size, args.num_instances,
                                            args.watchdog_s, args.fast_mode,
                                            args.xclbin_path,
                                            args.legacy_single_port_watcher);
        return driver.run_test_bench();
      },
      args.wave);
}
