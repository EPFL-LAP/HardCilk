# QuestaSim co-simulation do-file (run from the generated
# <project>/project_1.sim/sim_1/behav/questa directory; simulate.sh cd's there).
# DESCRIPTOR_NAME is substituted by TclQuestaSim when the project is emitted.
#
# Host tree to compile. Defaults to the emitter's own `software/` output; set the
# HARDCILK_HOST_DIR environment variable to build a different one (e.g. the
# staged xclbin-workspace copy, which is what scripts/rebuild_and_run.sh does).
set WS "../../../../../../software"
if {[info exists ::env(HARDCILK_HOST_DIR)]} { set WS $::env(HARDCILK_HOST_DIR) }
puts "== compiling host from: $WS"

# The generated memory-layout sidecar is the source of truth for host-side RAMA
# striping. Callers may override this when testing a timestamped xclbin backup.
if {![info exists ::env(HARDCILK_HBM_DESCRIPTOR)]} {
  set ::env(HARDCILK_HBM_DESCRIPTOR) [file normalize "../../../../../../rtl/DESCRIPTOR_NAME.hbmports.json"]
}
puts "== HBM descriptor: $::env(HARDCILK_HBM_DESCRIPTOR)"

# Compile + link the SystemC host.
# NOTE: do NOT add -std=c++17 here. sccom uses QuestaSim's bundled gcc (7.4.0),
# whose C++17 support is incomplete; building the SystemC module that way has
# been observed to destabilise vopt. If the host needs <filesystem>, alias it to
# <experimental/filesystem> under MTI_SYSTEMC and link with -lstdc++fs (below).
# Beware also of C++14 ODR rules: a `static constexpr` member passed to
# something taking a const& (e.g. std::max) needs a definition -- write
# `+MEMBER` to pass a prvalue instead.
sccom -DMTI_BIND_SC_MEMBER_FUNCTION -DUSE_MTI_CIN -g -incr -work xil_defaultlib \
     -I $WS/projects/DESCRIPTOR_NAME/include/ \
     -I $WS/driver/include/ \
     -I $WS/projects/common/include/ \
     $WS/projects/DESCRIPTOR_NAME/src/questa_main.cpp \
     $WS/driver/src/hardCilkDriver.cpp

sccom -incr -work xil_defaultlib -dpilib xil_defaultlib -link -fuse-ld=bfd -lstdc++fs

# Elaborate by REUSING Vivado's own generated vopt command (main_sim_elaborate.do)
# and injecting the SystemC top. Vivado knows the exact -L library set/order for
# this design; a hand-maintained list drifts and makes vopt resolve the wrong
# library versions (crashes with "library type not recognized" / signal 11).
#
# -undefsyms=off stops vopt from compiling a DPI stub .so with QuestaSim's
# bundled gcc. On a modern distro that gcc's old `ld` cannot link against the
# system glibc ("unknown type [0x13] section .relr.dyn"), which otherwise fails
# elaboration with "(vopt-3827) Could not compile STUB_SYMS_OF_systemc.so".
set fh [open main_sim_elaborate.do r]
set velab [read $fh]
close $fh
set voptCmd ""
foreach line [split $velab "\n"] {
  if {[string match "vopt *" [string trim $line]]} { set voptCmd [string trim $line] }
}
regsub {xil_defaultlib\.main_sim} $voptCmd {xil_defaultlib.TestBench xil_defaultlib.main_sim} voptCmd
regsub {^vopt } $voptCmd {vopt -undefsyms=off } voptCmd
puts "== elaborating with: $voptCmd"
eval $voptCmd

vsim -lib xil_defaultlib main_sim_opt

# Waveform: log to the compact native WLF rather than streaming a live VCD --
# a full-kernel VCD (>100k signals) throttles the co-simulation badly. Convert
# afterwards if a portable format is needed:
#   wlf2vcd vsim.wlf -o DESCRIPTOR_NAME_sim.vcd
#   vcd2fst DESCRIPTOR_NAME_sim.vcd DESCRIPTOR_NAME_sim.fst
log -r sim:/TestBench/myModule/DUT/design_1_i/DESCRIPTOR_NAME_0/*

# The SystemC testbench calls sc_stop() when the benchmark finishes; the cap
# below only bounds a run that hangs (so vsim still returns and the waveform is
# still written).
run 60 ms

quit -f
