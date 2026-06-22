# ─────────────────────────────────────────────────────────────────────────────
# csynth_edgemap_helper.tcl — C-synthesis of the sparse_edgemap_helper kernel.
#
# Run with:    vitis-run --tcl csynth_edgemap_helper.tcl
# (from inside this Test/ directory).
#
# Target matches the deployed BFS build: Alveo U55C @ 200 MHz (5 ns).
# ─────────────────────────────────────────────────────────────────────────────

open_project -reset edgemap_helper_csynth_new
set_top sparse_edgemap_helper

# BFS.cpp / util.h live one directory up.
add_files ../BFS.cpp -cflags "-I.."

open_solution -reset "solution1" -flow_target vitis
set_part {xcu55c-fsvh2892-2L-e}
create_clock -period 3.333 -name default

csynth_design

close_project
exit
