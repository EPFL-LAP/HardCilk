# C-synthesis of the GraphColoring init helper.
#
# Run with:    vitis-run --tcl csynth_color_init_helper.tcl
# (from inside this Test/ directory).

open_project -reset color_init_helper_csynth
set_top color_init_helper

add_files ../GraphColoring.cpp -cflags "-I.."

open_solution -reset "solution1" -flow_target vitis
set_part {xcu55c-fsvh2892-2L-e}
create_clock -period 3.333 -name default

csynth_design

close_project
exit
