proc safe_set_property {property value net_pattern} {
    set nets [get_nets -quiet $net_pattern]
    if {[llength $nets] == 0} {
        puts "WARNING: net not found, skipping: $net_pattern"
    } else {
        set_property $property $value $nets
    }
}

# ─────────────────────────────────────────────────────────────────────────────
# ALLOW_COMBINATORIAL_LOOPS waivers for triangleCount_0 PE array
# ─────────────────────────────────────────────────────────────────────────────

set base    "level0_i/ulp/triangleCount_0/inst"
set pe_path "pe/gmem_m_axi_U/bus_read/rreq_burst_conv/burst_interleave/req_buffer"

set nets_to_waive {
    could_multi_bursts.rem_req_valid_reg
    DI[0]
}

for {set i 0} {$i < 16} {incr i} {
    foreach net $nets_to_waive {
        safe_set_property ALLOW_COMBINATORIAL_LOOPS TRUE \
            "${base}/peMap_1_2_${i}/${pe_path}/${net}"
    }
}

