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
#   @@FLOW_TARGET@@      — vitis, or vivado for discrete ap_none scalar ports
# =============================================================================

# ── Project setup ─────────────────────────────────────────────────────────────
open_project -reset @@KERNEL@@_proj
set_top @@KERNEL@@

# ── Add source files ──────────────────────────────────────────────────────────
# Sources are injected as a Tcl list by the shell wrapper: {file1} {file2} ...
# Optional compiler flags are inherited from the caller. This lets the top-level
# rebuild script select benchmark-specific HLS ABIs without editing this template.
set hls_cflags ""
if {[info exists ::env(HLS_CFLAGS)]} {
    set hls_cflags $::env(HLS_CFLAGS)
}
foreach src_file { @@SOURCES@@ } {
    if {$hls_cflags ne "" && [string match "*.cpp" $src_file]} {
        add_files -cflags $hls_cflags $src_file
    } else {
        add_files $src_file
    }
}

# ── Solution configuration ────────────────────────────────────────────────────
# Most kernels use the Vitis flow. Internal HardCilk blocks with discrete
# ap_none scalar pins use the Vivado IP flow; build_kernels.sh selects it from
# the source marker HARDCILK_HLS_FLOW_TARGET_VIVADO: <kernel>.
set flow_target "@@FLOW_TARGET@@"
open_solution -reset "solution1" -flow_target $flow_target
set_part {@@PART@@}
create_clock -period @@CLOCK_PERIOD_NS@@ -name default

# ── Interface configuration ───────────────────────────────────────────────────
# Assumed m_axi read latency. This drives how many pipeline stages HLS inserts
# between issuing a read and consuming its data, i.e. how much memory round-trip
# it can hide. The vitis flow defaults to 64; the vivado flow defaults to ~0,
# which collapses the pipeline and exposes the round trip as a per-read stall
# (VCD-measured on memReader: read consumed at iter9 with only 8 cycles of cover
# against a 7-cycle m_axi adapter + 3-cycle HBM round trip -> ~1 stall cycle per
# read, II 1 -> 2). Set it explicitly so both flow targets schedule alike.
config_interface -m_axi_latency 64

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
