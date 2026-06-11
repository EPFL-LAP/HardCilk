# ─────────────────────────────────────────────────────────────────────────────
# csynth_bfs.tcl — C-synthesis of the BFS orchestration kernel only.
#
# Run with:    vitis-run --tcl csynth_bfs.tcl
# (from inside this Test/ directory).
#
# Target matches the deployed BFS build: Alveo U55C @ 200 MHz (5 ns).
# ─────────────────────────────────────────────────────────────────────────────

open_project -reset BFS_csynth_new
set_top BFS

# BFS.cpp / util.h live one directory up.
add_files BFS_new.cpp -cflags "-I.."

open_solution -reset "solution1" -flow_target vitis
set_part {xcu55c-fsvh2892-2L-e}
create_clock -period 5 -name default

csynth_design

close_project
exit
