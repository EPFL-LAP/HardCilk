#pragma once

#include <memIO_xrt.h>

#include <experimental/xrt_ip.h>
#include <experimental/xrt_xclbin.h>
#include <xrt/xrt_device.h>

#include <atomic>
#include <cerrno>
#include <chrono>
#include <climits>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <iterator>
#include <string>
#include <sys/stat.h>
#include <thread>
#include <unistd.h>

inline bool benchmarkCpuOnlyRequested(const std::string &xclbin_path)
{
  return xclbin_path == "--cpu" || xclbin_path == "cpu" ||
         xclbin_path == "CPU";
}

inline bool benchmarkCheckRuntimeEnv()
{
  const char *emu_mode = std::getenv("XCL_EMULATION_MODE");
  const bool is_emulation =
      emu_mode != nullptr && std::string(emu_mode).size() != 0;
  if (!is_emulation)
    return true;

  const char *xrt = std::getenv("XILINX_XRT");
  if (xrt != nullptr && std::string(xrt).size() != 0)
    return true;

  std::cerr << "[Init] XCL_EMULATION_MODE=" << emu_mode
            << " but XILINX_XRT is not set.\n"
            << "[Init] Run: source /opt/xilinx/xrt/setup.sh\n";
  return false;
}

// Configuration for capturing a full xsim waveform during hardware
// emulation (XCL_EMULATION_MODE=hw_emu). When enabled the host writes an
// xrt.ini (debug_mode=batch -> native .wdb) plus pre/post-sim TCL scripts
// (open_vcd/log_vcd/close_vcd -> .vcd) into `dir`, before opening the device.
struct WaveformConfig
{
  bool enabled = false;
  std::string dir = "waveform";      // output directory (relative to cwd)
  std::string vcd_basename = "dump"; // <basename>.vcd inside `dir`
  // Regex (xsim get_scopes -regexp) selecting the hierarchy to log. Defaults
  // to the whole design; pass the kernel CU name to scope to that subtree.
  std::string kernel_scope_regex = ".*";
  // xsim can only emit VCD (not FST). When fst is true, post-sim converts the
  // VCD to a compact GTKWave .fst via `vcd2fst` (if present on PATH). The VCD
  // is removed afterwards unless keep_vcd is also set.
  bool fst = false;
  bool keep_vcd = true;
};

// Print the shared waveform-capture flag documentation. Every benchmark host
// appends this to its own usage message so the controls are documented (and
// behave) identically across drivers.
inline void benchmarkWaveformUsage(std::ostream &os)
{
  os << "  --waveform[=DIR]  (hw_emu only) dump a full xsim waveform into DIR "
        "(default: waveform):\n"
     << "                    native .wdb + .vcd, scoped to the user kernel.\n"
     << "  --fst             also convert the VCD to a compact GTKWave .fst "
        "(needs vcd2fst;\n"
     << "                    implies --waveform).\n"
     << "  --no-vcd          with --fst, delete the VCD after conversion "
        "(keep only .fst + .wdb).\n";
}

// If `arg` is a waveform-related option, apply it to `wave` and return true.
// Drivers call this inside their option-parsing loop (before the
// positional / unknown-flag handling) so the flags work everywhere.
inline bool benchmarkTryParseWaveformArg(const std::string &arg,
                                         WaveformConfig &wave)
{
  if (arg == "--waveform")
  {
    wave.enabled = true;
    return true;
  }
  if (arg.rfind("--waveform=", 0) == 0)
  {
    wave.enabled = true;
    wave.dir = arg.substr(std::string("--waveform=").size());
    return true;
  }
  if (arg == "--fst")
  {
    wave.enabled = true; // FST is produced from the VCD capture
    wave.fst = true;
    return true;
  }
  if (arg == "--no-vcd")
  {
    wave.keep_vcd = false;
    return true;
  }
  return false;
}

// Fill in per-design waveform defaults from the kernel name: scope the capture
// to the kernel CU and name the dump after it. Call after arg parsing, before
// runSingleFpgaBenchmark. Leaves any caller-set overrides untouched.
inline void benchmarkApplyWaveformDefaults(WaveformConfig &wave,
                                           const std::string &kernel_name)
{
  if (!wave.enabled)
    return;
  std::string cu = kernel_name.substr(0, kernel_name.find(':'));
  if (cu.empty())
    cu = "kernel";
  if (wave.vcd_basename == "dump") // struct default -> use a recognizable name
    wave.vcd_basename = cu;
  if (wave.kernel_scope_regex == ".*") // struct default -> scope to the CU
    wave.kernel_scope_regex = ".*" + cu + ".*";
}

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

inline std::string benchmarkAbsPath(const std::string &p)
{
  if (!p.empty() && p[0] == '/')
    return p;
  char cwd[PATH_MAX];
  if (getcwd(cwd, sizeof(cwd)) == nullptr)
    return p;
  return std::string(cwd) + "/" + p;
}

// Best-effort: arrange for the next hw_emu launch to dump a full waveform.
// Returns true if the capture was configured. Must be called BEFORE the
// xrt::device is constructed (XRT reads xrt.ini / the launcher reads env at
// device-open time). Only meaningful under XCL_EMULATION_MODE=hw_emu.
inline bool benchmarkSetupHwEmuWaveform(const WaveformConfig &wave)
{
  const char *emu_mode = std::getenv("XCL_EMULATION_MODE");
  const bool is_hw_emu =
      emu_mode != nullptr && std::string(emu_mode) == "hw_emu";
  if (!is_hw_emu)
  {
    std::cerr << "[Waveform] --waveform ignored: only supported under "
                 "XCL_EMULATION_MODE=hw_emu (current: "
              << (emu_mode ? emu_mode : "<unset>") << ").\n";
    return false;
  }

  const std::string dir = benchmarkAbsPath(wave.dir);
  if (mkdir(dir.c_str(), 0777) != 0 && errno != EEXIST)
  {
    std::cerr << "[Waveform] failed to create '" << dir
              << "': " << std::strerror(errno) << "\n";
    return false;
  }

  const std::string pre = dir + "/pre_sim.tcl";
  const std::string post = dir + "/post_sim.tcl";
  const std::string vcd = dir + "/" + wave.vcd_basename + ".vcd";

  // pre-sim: opened before `run all`, so the VCD captures from time 0.
  {
    std::ofstream f(pre);
    if (!f)
    {
      std::cerr << "[Waveform] cannot write " << pre << "\n";
      return false;
    }
    f << "# Auto-generated by --waveform (do not edit; regenerated each run).\n"
      << "puts \"\\[Waveform\\] pre-sim: opening VCD + logging signals\"\n"
      << "set wave_dir {" << dir << "}\n"
      << "if { [catch { open_vcd {" << vcd
      << "} } msg] } { puts \"\\[Waveform\\] open_vcd failed: $msg\" }\n"
      << "set kre {" << wave.kernel_scope_regex << "}\n";
    // xsim get_scopes is NOT recursive (it only lists the current scope's
    // children), so a plain `get_scopes -regexp` never sees the kernel buried
    // under pfm_top_wrapper/pfm_top_i/pfm_dynamic_inst. Walk the whole tree via
    // current_scope navigation (works across xsim versions), dump every scope to
    // scopes.txt so the real kernel path is discoverable, then log every signal
    // in the subtree(s) whose path matches $kre. Fallback = log the ENTIRE design
    // (not just top-level) so a wrong regex still yields a usable dump.
    f << R"TCL(
proc hc_children {scope} {
  set saved [current_scope]
  set kids {}
  if { ![catch { current_scope $scope }] } { catch { set kids [get_scopes] } }
  catch { current_scope $saved }
  return $kids
}
proc hc_walk {root} {
  set out [list $root]
  foreach c [hc_children $root] { foreach s [hc_walk $c] { lappend out $s } }
  return $out
}
set all_scopes {}
foreach top [hc_children "/"] { foreach s [hc_walk $top] { lappend all_scopes $s } }
if { ![catch { open "$wave_dir/scopes.txt" w } fh] } {
  foreach s $all_scopes { puts $fh $s }
  close $fh
  puts "\[Waveform\] wrote [llength $all_scopes] scopes -> $wave_dir/scopes.txt"
}
set roots {}
foreach s $all_scopes {
  if { [regexp $kre $s] } {
    set ischild 0
    foreach r $roots { if { [string match "${r}/*" $s] } { set ischild 1; break } }
    if { !$ischild } { lappend roots $s }
  }
}
proc hc_log {scopes} {
  set n 0
  set saved [current_scope]
  foreach sc $scopes {
    if { [catch { current_scope $sc }] } { continue }
    set objs {}
    catch { set objs [get_objects] }
    if { [llength $objs] > 0 } { catch { log_wave $objs }; catch { log_vcd $objs }; incr n [llength $objs] }
  }
  catch { current_scope $saved }
  return $n
}
if { [llength $roots] == 0 } {
  puts "\[Waveform\] WARNING: regex '$kre' matched no scope; logging ALL [llength $all_scopes] scopes"
  set cnt [hc_log $all_scopes]
} else {
  set targets {}
  foreach r $roots { puts "\[Waveform\] logging kernel scope $r (subtree)"; foreach s [hc_walk $r] { lappend targets $s } }
  set cnt [hc_log $targets]
}
puts "\[Waveform\] logged $cnt objects to VCD"
)TCL";
  }

  // post-sim: runs in batch mode after `run all`, before quit. Close the VCD
  // and copy the native .wdb (written into the xsim run dir) next to it.
  {
    std::ofstream f(post);
    if (!f)
    {
      std::cerr << "[Waveform] cannot write " << post << "\n";
      return false;
    }
    const std::string fst = dir + "/" + wave.vcd_basename + ".fst";
    f << "# Auto-generated by --waveform (do not edit; regenerated each run).\n"
      << "puts \"\\[Waveform\\] post-sim: closing VCD + copying .wdb\"\n"
      << "set wave_dir {" << dir << "}\n"
      << "if { [catch { close_vcd } msg] } { puts \"\\[Waveform\\] close_vcd: "
         "$msg\" }\n"
      << "foreach w [glob -nocomplain *.wdb] {\n"
      << "  if { [catch { file copy -force $w [file join $wave_dir [file tail "
         "$w]] } msg] } { puts \"\\[Waveform\\] wdb copy failed: $msg\" }\n"
      << "}\n";
    if (wave.fst)
    {
      // xsim cannot emit FST directly; convert the closed VCD via vcd2fst.
      f << "set vcd_file {" << vcd << "}\n"
        << "set fst_file {" << fst << "}\n"
        << "if { [file exists $vcd_file] } {\n"
        << "  if { [catch { exec vcd2fst -v $vcd_file -f $fst_file } msg] } {\n"
        << "    puts \"\\[Waveform\\] vcd2fst failed (is gtkwave installed?): "
           "$msg\"\n"
        << "  } else {\n"
        << "    puts \"\\[Waveform\\] wrote FST $fst_file\"\n"
        << (wave.keep_vcd
                ? ""
                : "    catch { file delete -force $vcd_file }\n")
        << "  }\n"
        << "}\n";
    }
    f << "puts \"\\[Waveform\\] outputs are in $wave_dir\"\n";
  }

  // xrt.ini in cwd selects batch waveform capture. Preserve any pre-existing
  // (non-generated) xrt.ini by backing it up once.
  const char *kMarker = "# generated-by-waveform-flag";
  const std::string ini = "xrt.ini";
  struct stat st;
  if (stat(ini.c_str(), &st) == 0)
  {
    std::ifstream in(ini);
    std::string content((std::istreambuf_iterator<char>(in)),
                        std::istreambuf_iterator<char>());
    if (content.find(kMarker) == std::string::npos)
    {
      if (std::rename(ini.c_str(), "xrt.ini.bak") == 0)
        std::cerr << "[Waveform] existing xrt.ini backed up to xrt.ini.bak\n";
    }
  }
  {
    std::ofstream f(ini);
    if (!f)
    {
      std::cerr << "[Waveform] cannot write xrt.ini\n";
      return false;
    }
    f << kMarker << "\n[Emulation]\ndebug_mode=batch\n";
  }

  // The emulation launcher sources these scripts from the environment.
  setenv("USER_PRE_SIM_SCRIPT", pre.c_str(), 1);
  setenv("USER_POST_SIM_SCRIPT", post.c_str(), 1);

  std::cout << "[Waveform] hw_emu waveform capture enabled.\n"
            << "[Waveform]   output dir : " << dir << "\n"
            << "[Waveform]   native wdb : " << dir << "/<kernel>.wdb\n";
  if (wave.fst)
    std::cout << "[Waveform]   fst        : " << dir << "/"
              << wave.vcd_basename << ".fst (via vcd2fst)\n";
  if (!wave.fst || wave.keep_vcd)
    std::cout << "[Waveform]   vcd        : " << vcd << "\n";
  std::cout << "[Waveform]   scope regex: " << wave.kernel_scope_regex << "\n";
  return true;
}

// Undo a previously generated waveform xrt.ini so that a run WITHOUT
// --waveform does not silently keep capturing (debug_mode=batch is slow).
// Only touches an xrt.ini we generated (identified by the marker); restores
// any backed-up user xrt.ini.bak.
inline void benchmarkClearGeneratedWaveformIni()
{
  const char *kMarker = "# generated-by-waveform-flag";
  const std::string ini = "xrt.ini";
  std::ifstream in(ini);
  if (!in)
    return;
  std::string content((std::istreambuf_iterator<char>(in)),
                      std::istreambuf_iterator<char>());
  in.close();
  if (content.find(kMarker) == std::string::npos)
    return; // not ours, leave it alone
  std::remove(ini.c_str());
  struct stat st;
  if (stat("xrt.ini.bak", &st) == 0)
    std::rename("xrt.ini.bak", ini.c_str());
  std::cerr << "[Waveform] removed generated xrt.ini (no --waveform this run)\n";
}

class BenchmarkHeartbeat
{
public:
  BenchmarkHeartbeat(const std::string &label, int period_s = 10)
      : label_(label), period_s_(period_s), running_(true),
        start_(std::chrono::high_resolution_clock::now()),
        thread_([this]() { run(); }) {}

  ~BenchmarkHeartbeat()
  {
    running_ = false;
    if (thread_.joinable())
      thread_.join();
  }

private:
  void run()
  {
    while (running_)
    {
      for (int i = 0; i < period_s_ && running_; i++)
        std::this_thread::sleep_for(std::chrono::seconds(1));
      if (!running_)
        break;
      double elapsed =
          std::chrono::duration<double>(
              std::chrono::high_resolution_clock::now() - start_)
              .count();
      std::cout << "[Init] still " << label_ << " after " << elapsed << "s"
                << std::endl;
    }
  }

  std::string label_;
  int period_s_;
  std::atomic_bool running_;
  std::chrono::high_resolution_clock::time_point start_;
  std::thread thread_;
};

template <class RunWithMemory>
int runSingleFpgaBenchmark(const std::string &xclbin_path,
                           const std::string &kernel_name,
                           RunWithMemory run_with_memory,
                           const WaveformConfig &wave = WaveformConfig{})
{
  if (!benchmarkCheckRuntimeEnv())
    return EXIT_FAILURE;

  if (wave.enabled)
    benchmarkSetupHwEmuWaveform(wave);
  else
    benchmarkClearGeneratedWaveformIni();

  xrt::device device(0);
  std::cout << "[Init] Loading '" << xclbin_path << "' onto FPGA 0..."
            << std::endl;
  xrt::uuid uuid;
  {
    BenchmarkHeartbeat heartbeat("loading xclbin");
    uuid = device.load_xclbin(xclbin_path);
  }
  std::cout << "[Init] xclbin loaded." << std::endl;

  xrt::ip kernel(device, uuid, kernel_name);
  std::cout << "[Init] Opened CU '" << kernel_name << "'.\n";

  XRTMemory memory(device, kernel);
  auto start = std::chrono::high_resolution_clock::now();
  int rc = run_with_memory(&memory);
  auto end = std::chrono::high_resolution_clock::now();
  std::cout << "[Run] total wall time (including validation): "
            << std::chrono::duration<double>(end - start).count() << "s\n";

  return rc == 0 ? EXIT_SUCCESS : EXIT_FAILURE;
}
