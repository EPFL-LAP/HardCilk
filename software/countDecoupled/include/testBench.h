#pragma once

#include <SingleFpgaBenchmark.h>
#include <CountDecoupledDriver.h>

#include <cstdlib>
#include <iostream>
#include <string>

struct CountDecoupledBenchArgs
{
  std::string xclbin_path;
  uint32_t size = 100;
  uint32_t num_instances = 1; // independent root tasks launched concurrently
  double watchdog_s = 600.0;
  bool fast_mode = false;
  bool legacy_single_port_watcher = false;
  bool hbm_strided_writes = false;
  uint64_t hbm_continuation_bank_run_entries = 1;
  std::string telemetry_dir;
  bool telemetry_dir_explicit = false;
  WaveformConfig wave; // hw_emu waveform capture (see --waveform/--fst)
};

inline void count_decoupled_usage(const char *prog)
{
  std::cerr << "Usage:\n  " << prog
            << " <xclbin_path|--cpu> [size] [num_instances] [watchdog_s] "
               "[--fast] [--legacy-single-port-watcher] "
               "[--hbm-strided-writes[=N]] [--hbm-phased-continuations[=N]] "
               "[--telemetry-dir=DIR] "
               "[--waveform[=DIR]] [--fst] [--keep-vcd|--no-vcd]\n";
  std::cerr << "  --telemetry-dir=DIR  write the telemetry .bin file into DIR\n"
            << "                       (defaults to --waveform DIR when enabled,\n"
            << "                       otherwise $HARDCILK_TELEMETRY_DIR or /tmp).\n";
  benchmarkWaveformUsage(std::cerr);
}

inline bool count_decoupled_parse_positive_u64(const std::string &text,
                                               uint64_t &out)
{
  char *end = nullptr;
  unsigned long long value = std::strtoull(text.c_str(), &end, 0);
  if (end == text.c_str() || *end != '\0' || value == 0)
    return false;
  out = static_cast<uint64_t>(value);
  return true;
}

inline bool parse_count_decoupled_args(
    int argc, char **argv, CountDecoupledBenchArgs &out)
{
  if (argc < 2)
  {
    count_decoupled_usage(argv[0]);
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
    if (arg == "--hbm-strided-writes")
    {
      out.hbm_strided_writes = true;
      continue;
    }
    const std::string stridedPrefix = "--hbm-strided-writes=";
    if (arg.rfind(stridedPrefix, 0) == 0)
    {
      out.hbm_strided_writes = true;
      if (!count_decoupled_parse_positive_u64(arg.substr(stridedPrefix.size()),
                                              out.hbm_continuation_bank_run_entries))
        return false;
      continue;
    }
    if (arg == "--hbm-phased-continuations")
    {
      out.hbm_strided_writes = true;
      out.hbm_continuation_bank_run_entries = 32;
      continue;
    }
    const std::string phasedPrefix = "--hbm-phased-continuations=";
    if (arg.rfind(phasedPrefix, 0) == 0)
    {
      out.hbm_strided_writes = true;
      if (!count_decoupled_parse_positive_u64(arg.substr(phasedPrefix.size()),
                                              out.hbm_continuation_bank_run_entries))
        return false;
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

inline int run_count_decoupled_benchmark(
    int argc, char **argv, const std::string &kernel_name)
{
  CountDecoupledBenchArgs args;
  if (!parse_count_decoupled_args(argc, argv, args))
    return EXIT_FAILURE;
  if (benchmarkCpuOnlyRequested(args.xclbin_path))
    return CountDecoupledDriver::run_cpu_test_bench(args.size);
  benchmarkApplyWaveformDefaults(args.wave, kernel_name,
                                 "countDecoupled_telemetry");
  if (!args.telemetry_dir_explicit && args.wave.enabled)
    args.telemetry_dir = args.wave.dir;
  if (!args.telemetry_dir.empty())
    setenv("HARDCILK_TELEMETRY_DIR", args.telemetry_dir.c_str(), 1);
  return runSingleFpgaBenchmark(
      args.xclbin_path, kernel_name,
      [&](Memory *m) {
        CountDecoupledDriver driver(m, args.size, args.num_instances,
                                    args.watchdog_s, args.fast_mode,
                                    args.xclbin_path,
                                    args.legacy_single_port_watcher,
                                    args.hbm_strided_writes,
                                    args.hbm_continuation_bank_run_entries);
        return driver.run_test_bench();
      },
      args.wave);
}
