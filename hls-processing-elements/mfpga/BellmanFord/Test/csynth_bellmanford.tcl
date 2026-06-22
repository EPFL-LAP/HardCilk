# C-synthesis of the Bellman-Ford orchestration kernel only.
#
# Run with:    vitis-run --tcl csynth_bellmanford.tcl
# (from inside this Test/ directory).

open_project -reset BellmanFord_csynth
set_top BellmanFord

add_files ../BellmanFord.cpp -cflags "-I.."

open_solution -reset "solution1" -flow_target vitis
set_part {xcu55c-fsvh2892-2L-e}
create_clock -period 3.333 -name default

csynth_design

close_project
exit
