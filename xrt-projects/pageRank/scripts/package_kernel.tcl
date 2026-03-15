
#/*
# Copyright (C) 2023, Advanced Micro Devices, Inc. All rights reserved.
# SPDX-License-Identifier: X11
#*/

set path_to_hdl "./src/IP"
set path_to_packaged "./packaged_kernel_${suffix}"
set path_to_tmp_project "./tmp_kernel_pack_${suffix}"

create_project -force kernel_pack $path_to_tmp_project 
add_files -norecurse [glob $path_to_hdl/*.v $path_to_hdl/*.sv]
update_compile_order -fileset sources_1
update_compile_order -fileset sim_1

foreach tcl_file [glob -nocomplain $path_to_hdl/*.tcl] {
    source $tcl_file
}

ipx::package_project -root_dir $path_to_packaged -vendor xilinx.com -library RTLKernel -taxonomy /KernelIP -import_files -set_current false
ipx::unload_core $path_to_packaged/component.xml
ipx::edit_ip_in_project -upgrade true -name tmp_edit_project -directory $path_to_packaged $path_to_packaged/component.xml
set_property core_revision 2 [ipx::current_core]

foreach up [ipx::get_user_parameters] {
  ipx::remove_user_parameter [get_property NAME $up] [ipx::current_core]
}
set_property sdx_kernel true [ipx::current_core]
set_property sdx_kernel_type rtl [ipx::current_core]
ipx::create_xgui_files [ipx::current_core]

# ipx::associate_bus_interfaces -busif m_axi_00 -clock clock [ipx::current_core]
# ipx::associate_bus_interfaces -busif m_axi_01 -clock clock [ipx::current_core]
# ipx::associate_bus_interfaces -busif m_axi_02 -clock clock [ipx::current_core]
# ipx::associate_bus_interfaces -busif m_axi_03 -clock clock [ipx::current_core]


set core [ipx::current_core]
set clk  clock

foreach busif [ipx::get_bus_interfaces -of_objects $core] {
  if {[string match "m_axi_*" $busif]} {
    ipx::associate_bus_interfaces -busif $busif -clock $clk $core
  }
}

ipx::associate_bus_interfaces -busif m_axis_mFPGA -clock clock [ipx::current_core]
ipx::associate_bus_interfaces -busif s_axis_mFPGA -clock clock [ipx::current_core]
ipx::associate_bus_interfaces -busif s_axil_mgmt_hardcilk -clock clock [ipx::current_core]
ipx::associate_bus_interfaces -clock clock -reset reset_n [ipx::current_core]


set_property xpm_libraries {XPM_CDC XPM_MEMORY XPM_FIFO} [ipx::current_core]
set_property supported_families { } [ipx::current_core]
set_property auto_family_support_level level_2 [ipx::current_core]
ipx::update_checksums [ipx::current_core]
ipx::save_core [ipx::current_core]
close_project -delete
