# C-synthesis of the GraphColoring launcher.
#
# Run with:    vitis-run --tcl csynth_graph_coloring.tcl
# (from inside this Test/ directory).

open_project -reset GraphColoring_csynth
set_top GraphColoring

add_files ../GraphColoring.cpp -cflags "-I.."

open_solution -reset "solution1" -flow_target vitis
set_part {xcu55c-fsvh2892-2L-e}
create_clock -period 3.333 -name default

csynth_design

close_project
exit
