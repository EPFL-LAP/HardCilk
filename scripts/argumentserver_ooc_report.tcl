# Re-report ArgumentServer memory inference from a post-synthesis checkpoint
# written by argumentserver_ooc_synth.tcl. Reads only; runs in seconds.
#
#   vivado -mode batch -source argumentserver_ooc_report.tcl \
#          -tclargs <benchmark> <dcp> <outFile>

set benchmark [lindex $argv 0]
set dcp       [lindex $argv 1]
set outFile   [lindex $argv 2]

open_checkpoint $dcp

set f [open $outFile w]
puts $f "== $benchmark : ArgumentServer payload-store memory inference =="
puts $f ""

# ArgumentServer instances are named by their parent hierarchy, so find them by
# module reference and then match cells that live underneath one of them.
set servers {}
foreach c [get_cells -hierarchical -quiet -filter {REF_NAME =~ ArgumentServer*}] {
  lappend servers [get_property NAME $c]
}
puts $f "ArgumentServer instances: [llength $servers]"
foreach s [lsort $servers] { puts $f "  $s" }
puts $f ""

set bramTypes  {RAMB36E2 RAMB18E2}
set lutramTypes {RAMD32 RAMD64E RAMS32 RAMS64E RAMS64E1 RAM32M RAM32M16 RAM64M RAM64M8 RAM32X1D RAM64X1D RAM128X1D RAM256X1S}

array set counts {}
foreach cellType [concat $bramTypes $lutramTypes] {
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
    set key "$bucket|$cellType"
    if {[info exists counts($key)]} { incr counts($key) } else { set counts($key) 1 }
  }
}

puts $f "== primitives inside ArgumentServer lanes, by source memory =="
puts $f [format "%-22s %-12s %8s" "memory" "primitive" "count"]
foreach key [lsort [array names counts]] {
  set parts [split $key "|"]
  puts $f [format "%-22s %-12s %8d" [lindex $parts 0] [lindex $parts 1] $counts($key)]
}

puts $f ""
puts $f "== whole-design primitive totals =="
foreach cellType [concat $bramTypes $lutramTypes] {
  set n [llength [get_cells -hierarchical -quiet -filter "REF_NAME == $cellType"]]
  if {$n > 0} { puts $f [format "%-12s %8d" $cellType $n] }
}
close $f

report_utilization -file [file rootname $outFile]_utilization.rpt
puts "wrote $outFile"
