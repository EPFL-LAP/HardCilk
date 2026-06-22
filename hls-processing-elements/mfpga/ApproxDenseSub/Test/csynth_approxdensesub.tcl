# C-synthesis of the ApproxDenseSub orchestration kernel only.
#
# Run with:    vitis-run --tcl csynth_approxdensesub.tcl
# (from inside this Test/ directory).

open_project -reset ApproxDenseSub_csynth
set_top ApproxDenseSub

add_files ../ApproxDenseSub.cpp -cflags "-I.."

open_solution -reset "solution1" -flow_target vitis
set_part {xcu55c-fsvh2892-2L-e}
create_clock -period 3.333 -name default

csynth_design

close_project
exit
