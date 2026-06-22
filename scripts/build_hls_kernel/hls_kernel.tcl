# =============================================================================
# hls_kernel.tcl — Vitis HLS 24.04 kernel synthesis template
#
# This file is a TEMPLATE — do not invoke it directly.
# The build_kernels.sh wrapper stamps the @@PLACEHOLDERS@@ at runtime and
# writes the result to  hls_projects/<kernel>/run_hls.tcl  before calling:
#
#   vitis_hls -f run_hls.tcl
#
# Placeholders substituted by the shell wrapper:
#   @@KERNEL@@           — top-function / kernel name
#   @@PART@@             — Xilinx part string  (e.g. xcu250-figd2104-2L-e)
#   @@FREQ_MHZ@@         — requested frequency in MHz
#   @@CLOCK_PERIOD_NS@@  — derived clock period in nanoseconds
#   @@SOURCES@@          — space-separated Tcl list of {/abs/path} source files
# =============================================================================

# ── Project setup ─────────────────────────────────────────────────────────────
open_project -reset @@KERNEL@@_proj
set_top @@KERNEL@@

# ── Add source files ──────────────────────────────────────────────────────────
# Sources are injected as a Tcl list by the shell wrapper: {file1} {file2} ...
foreach src_file { @@SOURCES@@ } {
    add_files $src_file
}

# ── Solution configuration ────────────────────────────────────────────────────
# Most kernels are standalone Vitis kernels (vitis flow). The free-running
# telemetry "watcher" is an internal block of the HardCilk top: it needs the
# Vivado IP flow so that ap_ctrl_none + the discrete ap_none status pins survive
# (the vitis flow forces every scalar through an s_axilite control block).
set flow_target "vitis"
if { {@@KERNEL@@} eq "watcher" } {
    set flow_target "vivado"
}
open_solution -reset "solution1" -flow_target $flow_target
set_part {@@PART@@}
create_clock -period @@CLOCK_PERIOD_NS@@ -name default

# ── Optional: HLS directives ──────────────────────────────────────────────────
# Add your kernel-specific pragmas / config directives here, for example:
#
#   config_interface -m_axi_addr64
#   config_compile -pipeline_loops 0
#   set_directive_pipeline -II 1 "@@KERNEL@@/main_loop"

# ── Synthesis ─────────────────────────────────────────────────────────────────
csynth_design

# ── Teardown ──────────────────────────────────────────────────────────────────
close_project

exit