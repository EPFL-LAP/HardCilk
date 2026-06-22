# C-synthesis of the MaximalIndependentSet NGS primitive.
#
# Run with:    vitis-run --tcl csynth_ngs.tcl
# (from inside this Test/ directory).

open_project -reset NGS_csynth
set_top NGS

add_files ../MaximalIndependentSet.cpp -cflags "-I.."

open_solution -reset "solution1" -flow_target vitis
set_part {xcu55c-fsvh2892-2L-e}
create_clock -period 3.333 -name default

csynth_design

close_project
exit
