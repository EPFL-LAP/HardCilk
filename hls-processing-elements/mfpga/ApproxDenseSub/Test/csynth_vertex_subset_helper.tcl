# C-synthesis of the ApproxDenseSub vertex_subset_helper kernel.
#
# Run with:    vitis-run --tcl csynth_vertex_subset_helper.tcl
# (from inside this Test/ directory).

open_project -reset vertex_subset_helper_csynth
set_top vertex_subset_helper

add_files ../ApproxDenseSub.cpp -cflags "-I.."

open_solution -reset "solution1" -flow_target vitis
set_part {xcu55c-fsvh2892-2L-e}
create_clock -period 3.333 -name default

csynth_design

close_project
exit
