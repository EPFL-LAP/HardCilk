# Out-of-context synthesis of a generated HardCilk kernel top, used to confirm
# that the ArgumentServer payload stores infer block RAM rather than distributed
# (LUT) RAM. Stops after synth_design: no placement, no routing, no bitstream.
#
#   vivado -mode batch -source argumentserver_ooc_synth.tcl \
#          -tclargs <benchmark> <rtlDir> <outDir>

set benchmark [lindex $argv 0]
set rtlDir    [lindex $argv 1]
set outDir    [lindex $argv 2]
set part      xcu55c-fsvh2892-2L-e

file mkdir $outDir

# Every generated .v in the RTL directory, minus the board-level PCIe wrapper in
# synth/ (a different top with platform pins we deliberately do not synthesize).
set sources [glob -nocomplain [file join $rtlDir *.v]]
foreach f [glob -nocomplain [file join $rtlDir *.sv]] {
  lappend sources $f
}
read_verilog $sources

synth_design -top $benchmark -part $part -mode out_of_context \
             -no_iobuf -flatten_hierarchy none

write_checkpoint -force [file join $outDir ${benchmark}_synth.dcp]

set rpt [file join $outDir ${benchmark}_utilization.rpt]
report_utilization -file $rpt
report_utilization -hierarchical -file [file join $outDir ${benchmark}_utilization_hier.rpt]

# --- ArgumentServer memory inference summary --------------------------------
set summary [open [file join $outDir ${benchmark}_argserver_mem.rpt] w]
puts $summary "== $benchmark : ArgumentServer payload-store memory inference =="

# ArgumentServer instances take their names from the enclosing hierarchy, so
# find them by module reference and match cells that live underneath one.
set servers {}
foreach c [get_cells -hierarchical -quiet -filter {REF_NAME =~ ArgumentServer*}] {
  lappend servers [get_property NAME $c]
}

# Every RAMB/LUTRAM primitive whose hierarchical path passes through an
# ArgumentServer, bucketed by the Chisel memory it came from.
array set counts {}
foreach cellType {RAMB36E2 RAMB18E2 RAMD32 RAMD64E RAMS32 RAMS64E RAMS64E1 RAM32M RAM32M16 RAM64M RAM64M8 RAM32X1D RAM64X1D RAM128X1D RAM256X1S} {
  foreach cell [get_cells -hierarchical -quiet -filter "REF_NAME == $cellType"] {
    set path [get_property NAME $cell]
    set inServer 0
    foreach s $servers {
      if {[string first "$s/" $path] == 0} { set inServer 1; break }
    }
    if {!$inServer} { continue }
    set bucket "other"
    foreach m {cacheBaseStores updateStores updateLUTRAMs coupledQs cacheIDStores delayedMissQs perLaneFIFOs updatePipes spawnQ stagedUpdatePipes} {
      if {[string first $m $path] >= 0} { set bucket $m; break }
    }
    set key "$bucket/$cellType"
    if {[info exists counts($key)]} {
      incr counts($key)
    } else {
      set counts($key) 1
    }
  }
}
foreach key [lsort [array names counts]] {
  puts $summary [format "%-34s %6d" $key $counts($key)]
}

puts $summary ""
puts $summary "== whole-design primitive totals =="
foreach cellType {RAMB36E2 RAMB18E2 RAMD32 RAMD64E RAMS32 RAMS64E RAMS64E1 RAM32M RAM32M16 RAM64M RAM64M8} {
  set n [llength [get_cells -hierarchical -quiet -filter "REF_NAME == $cellType"]]
  puts $summary [format "%-12s %8d" $cellType $n]
}

puts $summary ""
puts $summary "== ArgumentServer instances =="
foreach c [get_cells -hierarchical -quiet -filter {REF_NAME =~ ArgumentServer*}] {
  puts $summary "  [get_property NAME $c]  ([get_property REF_NAME $c])"
}
close $summary

puts "OOC synthesis of $benchmark complete; reports in $outDir"
