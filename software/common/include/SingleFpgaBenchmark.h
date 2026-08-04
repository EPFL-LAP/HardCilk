#pragma once

#include <memIO_xrt.h>
#include <hardCilkDriver.h> // hardCilkDriver::stopRequested()

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
#include <ctime>
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

inline std::string benchmarkTimestampNow()
{
  char ts[32];
  std::time_t now = std::time(nullptr);
  std::strftime(ts, sizeof(ts), "%Y%m%d_%H%M%S", std::localtime(&now));
  return std::string(ts);
}

inline std::string benchmarkGetOrCreateRunTimestamp()
{
  const char *env = std::getenv("HARDCILK_RUN_TIMESTAMP");
  if (env != nullptr && env[0] != '\0')
    return std::string(env);

  std::string ts = benchmarkTimestampNow();
  setenv("HARDCILK_RUN_TIMESTAMP", ts.c_str(), 1);
  return ts;
}

// Configuration for capturing a full xsim waveform during hardware
// emulation (XCL_EMULATION_MODE=hw_emu). When enabled the host writes an
// xrt.ini (debug_mode=batch -> native .wdb) plus pre/post-sim TCL scripts
// (open_vcd/log_vcd/close_vcd -> .vcd) into `dir`, before opening the device.
struct WaveformConfig
{
  bool enabled = false;
  std::string dir = "/tmp";          // output directory
  std::string vcd_basename = "dump"; // <basename>.vcd inside `dir`
  // Regex (xsim get_scopes -regexp) selecting the hierarchy to log. Defaults
  // to the whole design; pass the kernel CU name to scope to that subtree.
  std::string kernel_scope_regex = ".*";
  // xsim can only emit VCD (not FST). When fst is true, post-sim converts the
  // VCD to a compact GTKWave .fst via `vcd2fst` (if present on PATH). --fst
  // deletes the intermediate VCD by default; --keep-vcd preserves it.
  bool fst = false;
  bool keep_vcd = false;
};

// Print the shared waveform-capture flag documentation. Every benchmark host
// appends this to its own usage message so the controls are documented (and
// behave) identically across drivers.
inline void benchmarkWaveformUsage(std::ostream &os)
{
  os << "  --waveform[=DIR]  (hw_emu only) dump a full xsim waveform into DIR "
        "(default: /tmp):\n"
     << "                    native .wdb + .vcd, scoped to the user kernel.\n"
     << "  --fst             convert the VCD to a compact GTKWave .fst "
        "(needs vcd2fst;\n"
     << "                    implies --waveform and deletes the VCD by default).\n"
     << "  --keep-vcd        with --fst, keep the intermediate VCD.\n"
     << "  --no-vcd          with --fst, delete the intermediate VCD (default).\n";
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
  if (arg == "--keep-vcd")
  {
    wave.keep_vcd = true;
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
// to the kernel CU and name the dump after the telemetry file when a design
// telemetry prefix is supplied. Call after arg parsing, before
// runSingleFpgaBenchmark. Leaves any caller-set overrides untouched.
inline void benchmarkApplyWaveformDefaults(WaveformConfig &wave,
                                           const std::string &kernel_name,
                                           const std::string &telemetry_prefix = "")
{
  if (!wave.enabled)
    return;
  std::string cu = kernel_name.substr(0, kernel_name.find(':'));
  if (cu.empty())
    cu = "kernel";
  if (wave.vcd_basename == "dump") // struct default -> use a recognizable name
  {
    if (!telemetry_prefix.empty())
      wave.vcd_basename =
          telemetry_prefix + "_" + benchmarkGetOrCreateRunTimestamp();
    else
      wave.vcd_basename = cu;
  }
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
  const std::string wdb = dir + "/" + wave.vcd_basename + ".wdb";

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
      << "set wdb_file {" << wdb << "}\n"
      << "if { [catch { close_vcd } msg] } { puts \"\\[Waveform\\] close_vcd: "
         "$msg\" }\n"
      << "foreach w [glob -nocomplain *.wdb] {\n"
      << "  if { [catch { file copy -force $w $wdb_file } msg] } "
         "{ puts \"\\[Waveform\\] wdb copy failed: $msg\" }\n"
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
            << "[Waveform]   native wdb : " << wdb << "\n";
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

  // Capture the pristine terminal so we can always restore it (the hw_emu
  // simulator can leave the tty raw / no-echo when it takes a signal).
  hardCilkDriver::saveTerminalState();

  // Make the emulator child inherit SIG_IGN for SIGINT. The xrt::device
  // constructor below spawns xsim INTO our foreground process group, so a terminal
  // Ctrl-C would otherwise be delivered to xsim too -- pausing the simulator at its
  // interactive prompt (which then hangs every telemetry read and holds the tty).
  // With SIGINT ignored at spawn time, xsim keeps running; the host installs its
  // own handler later (driver ctor) so Ctrl-C still triggers a graceful HOST stop
  // while the simulator stays alive to service the telemetry readback and waveform
  // save. Cancelling during xclbin load is intentionally disabled for that short
  // window (no host handler yet); it is restored once the driver is constructed.
  std::signal(SIGINT, SIG_IGN);

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

  // The matching <xclbin>.hbmports.json sidecar carries any RAMA host-address
  // transform. XRTMemory automatically disables it under hw_emu.
  XRTMemory memory(device, kernel, xclbin_path);
  auto start = std::chrono::high_resolution_clock::now();
  int rc;
  try
  {
    rc = run_with_memory(&memory);
  }
  catch (const std::exception &e)
  {
    // A device I/O call (typically a register read/write over the hw_emu socket)
    // threw and unwound past the driver. memIO_xrt already retries transient
    // failures; reaching here means it was unrecoverable. Do NOT let this
    // std::terminate() the process -- that aborts without teardown and orphans the
    // simulator. Terminate the simulator ourselves and exit with a failure code.
    std::cerr << "[Run] FATAL: benchmark aborted by an unrecoverable device error: "
              << e.what() << "\n";
    hardCilkDriver::terminateSimulator();
    hardCilkDriver::restoreTerminalState();
    _exit(EXIT_FAILURE);
  }
  auto end = std::chrono::high_resolution_clock::now();
  std::cout << "[Run] total wall time (including validation): "
            << std::chrono::duration<double>(end - start).count() << "s\n";

  const int exit_code = rc == 0 ? EXIT_SUCCESS : EXIT_FAILURE;

  // Prompt hw_emu exit. Under hw_emu the simulator (xsim) never halts on its own
  // (the watcher CU is ap_ctrl_none), so the device teardown below blocks and the
  // process would otherwise hang until the watchdog fires. When we are NOT
  // capturing a waveform there is nothing left to flush -- all host work and
  // telemetry are already done by this point -- so terminate the simulator and
  // exit immediately for a clean, prompt return. With --waveform we must let the
  // graceful teardown run so post_sim.tcl closes the VCD / copies the WDB (the
  // watchdog below is the backstop, and it also kills xsim). On real hardware
  // there is no xsim descendant, so this branch is skipped.
  {
    const char *emu_mode = std::getenv("XCL_EMULATION_MODE");
    const bool is_hw_emu =
        emu_mode != nullptr && std::string(emu_mode) == "hw_emu";
    if (is_hw_emu && !wave.enabled && !hardCilkDriver::stopRequested())
    {
      std::cout << "[Run] hw_emu: terminating simulator and exiting.\n"
                << std::flush;
      hardCilkDriver::terminateSimulator();
      hardCilkDriver::restoreTerminalState();
      _exit(exit_code); // skip the teardown that would hang on a live emulator
    }
  }

  // Teardown watchdog. Returning from this function destroys `memory` then
  // `device`; the xrt::device destructor calls xclClose, which under hw_emu
  // blocks until the simulator quiesces. After a Ctrl-C interrupt the compute
  // CUs are still active (and the ap_ctrl_none watcher never stops on its own),
  // so that close can hang indefinitely -- this is exactly why a second, forced
  // Ctrl-C used to be needed to get the shell back. All host work and telemetry
  // are already done by this point, so we spawn a detached watchdog: if teardown
  // hasn't finished within a grace period, terminate the process ourselves with
  // the right exit code. A normal run's teardown completes well inside the grace
  // window, main returns, and the process exits before the watchdog ever fires
  // (the sleeping detached thread is reaped by process exit). We use a short
  // grace after an interrupt (teardown will hang) and a generous safety-net
  // otherwise. Deliberately thread-based, not SIGALRM, to avoid perturbing any
  // signal handling XRT relies on during close.
  const bool interrupted = hardCilkDriver::stopRequested();
  // Grace before the watchdog force-kills teardown. When capturing a waveform,
  // teardown must run post_sim.tcl to finalize/save the VCD and copy the WDB,
  // which is slow in hw_emu -- give it a generous backstop (whether or not we were
  // interrupted) so the save is NEVER cut off. If teardown completes, main returns
  // and this detached thread is reaped before the timeout ever matters, so a large
  // value costs nothing on a healthy run. With no waveform there is nothing left to
  // save (telemetry was already dumped inside run_test_bench), so a short grace
  // after an interrupt is fine; a normal no-waveform hw_emu run already exited
  // above. A second Ctrl-C (force_requested_) bypasses all of this immediately.
  const int grace_s = wave.enabled ? 600 : (interrupted ? 3 : 60);
  std::thread([exit_code, grace_s]() {
    std::this_thread::sleep_for(std::chrono::seconds(grace_s));
    static const char msg[] =
        "\n[Run] device teardown did not complete in time; terminating the "
        "simulator and forcing a clean exit\n";
    ssize_t written = ::write(STDERR_FILENO, msg, sizeof(msg) - 1);
    (void)written;
    std::fflush(nullptr);
    // The stuck teardown means xclClose never told the emulator to quit; kill it
    // ourselves so a forced _exit() never leaves an orphaned xsim pinning /tmp.
    hardCilkDriver::terminateSimulator();
    hardCilkDriver::restoreTerminalState();
    _exit(exit_code);
  }).detach();

  // Normal-return path: restore the terminal too, in case the simulator perturbed
  // it during the run (cheap insurance; a no-op if it was left clean).
  hardCilkDriver::restoreTerminalState();
  return exit_code;
}
