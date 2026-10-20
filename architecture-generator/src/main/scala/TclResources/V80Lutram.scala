package TclResources

/**
  * Alveo V80 PCIe flow: block RAM -> LUTRAM conversion of shallow, badly filled
  * memories in the kernel, and the per-PE figures the floorplanner packs with.
  *
  * WHY
  * ---
  * On pageRank_overlap (22 PEs) the kernel used 1830 BRAM tiles (48.9% of the
  * device), almost all of it in wide shallow memories that fill a BRAM to
  * 3-25%: the OVERLAP wrapper's `(* ram_style = "block" *)` FIFOs (128x1031,
  * 16x512, 16x144, 128x7) and the HLS dataflow context FIFOs (63x864, 63x432,
  * `MEM_STYLE "block"`). BRAM is what limits how many PEs fit on the hub die
  * (SLR2), and every PE that does not fit there costs ~2100 SLR1<->SLR2 SLLs.
  *
  * HOW
  * ---
  *  1. `hc_lutram.py` (written into `pcie/`) reads `report_ram_utilization
  *     -detail` of a synthesized kernel netlist -- the out-of-context
  *     checkpoint of the kernel's module-reference run of this or any earlier
  *     build of the same design (memory shapes do not depend on the PE count) --
  *     selects BRAM-mapped memories with depth <= 128, fill < 50% and an
  *     estimated LUT cost <= 128 LUTs per RAMB36 saved, and writes
  *     `<name>_lutram.xdc`: `set_property RAM_STYLE distributed` on those
  *     memories, generalized over PE instances (`peMap_<t>_2_*`).
  *  2. `<name>_v80_impl.tcl` attaches that XDC to the kernel's OOC synthesis
  *     run (`top_<name>_0_0_synth_1`) through a `STEPS.SYNTH_DESIGN.TCL.PRE`
  *     hook that `read_xdc`s it before `synth_design`; constraints read there
  *     are applied to the elaborated netlist and override the RTL `ram_style`
  *     attributes (verified: the HLS `MEM_STYLE "block"` FIFOs and the wrapper
  *     FIFOs move to LUTRAM).
  *  3. `build.sh -i` makes the XDC when it is missing: from `HC_LUTRAM_REF`
  *     (a kernel checkpoint) if set, else by synthesizing the kernel run once
  *     first (about 30 minutes for a 22-PE pageRank_overlap) and analysing that.
  *  4. At generation time, `HC_LUTRAM_PROFILE=<json from hc_lutram.py --json>`
  *     gives [[Floorplan]] the measured post-synthesis per-PE LUT/FF and the
  *     post-LUTRAM BRAM figures instead of the HLS estimates.
  *
  * `HC_LUTRAM=off` (at generation or build time) disables all of it.
  */
object V80Lutram {

  /** `HC_LUTRAM` at generation time: "auto" (default) or "off". */
  def mode: String =
    sys.env.getOrElse("HC_LUTRAM", "auto").trim.toLowerCase match {
      case "off" | "0" | "no" | "false" | "none" => "off"
      case _                                      => "auto"
    }

  def enabled: Boolean = mode != "off"

  /** File names in the generated `pcie/` directory. */
  val scriptName: String = "hc_lutram.py"
  def xdcName(name: String): String = s"${name}_lutram.xdc"

  /** One PE type from `hc_lutram.py --json`. */
  case class PeProfile(
      task: String,
      pePrefix: String,
      bramTilesBefore: Double,
      bramTilesAfter: Double,
      lutBefore: Long,
      lutAfter: Long,
      ff: Long
  ) {
    def bramTiles: Double = if (enabled) bramTilesAfter else bramTilesBefore
    def lut: Long = if (enabled) lutAfter else lutBefore
  }

  /** `HC_LUTRAM_PROFILE` (path), parsed once; empty when unset or unreadable. */
  lazy val profile: Seq[PeProfile] = sys.env.get("HC_LUTRAM_PROFILE").filter(_.nonEmpty) match {
    case None => Seq.empty
    case Some(path) =>
      try {
        val src = scala.io.Source.fromFile(path)
        val js = try play.api.libs.json.Json.parse(src.mkString) finally src.close()
        val pes = (js \ "pes").as[play.api.libs.json.JsObject]
        val out = pes.fields.map { case (task, v) =>
          def d(k: String) = (v \ k).asOpt[Double].getOrElse(0.0)
          PeProfile(task, (v \ "pe_prefix").asOpt[String].getOrElse(""), d("bram_tiles_before"),
            d("bram_tiles_after"), d("lut_before").round, d("lut_after").round, d("ff").round)
        }.toSeq
        println(s"[V80Lutram] per-PE profile from $path: " +
          out.map(p => s"${p.task} BRAM ${p.bramTilesBefore}->${p.bramTilesAfter}, LUT ${p.lutBefore}->${p.lutAfter}").mkString("; "))
        out
      } catch {
        case e: Throwable =>
          println(s"[V80Lutram] WARNING: cannot read HC_LUTRAM_PROFILE=$path (${e.getMessage}); using HLS estimates")
          Seq.empty
      }
  }

  /**
    * Profile entry of a task: by name, else by the PE instance prefix
    * (`peMap_<entry>_2`) its instances carry in this elaboration.
    */
  def forTask(task: String, instances: Seq[String]): Option[PeProfile] =
    profile.find(_.task == task).orElse {
      val pre = instances.headOption.map(_.replaceAll("_\\d+$", ""))
      profile.find(p => pre.contains(p.pePrefix) && p.pePrefix.nonEmpty)
    }

  /**
    * `<name>_v80_impl.tcl` fragment, after `generate_target`: attach (or detach)
    * the LUTRAM XDC on the kernel's OOC synthesis run; a completed run whose
    * hook changes is reset. With `HC_KERNEL_SYNTH_ONLY` set, synthesize only the
    * kernel run and stop (build.sh's analysis pre-pass).
    */
  def implTclFragment(name: String): String =
    TclV80Common.fill(
      """
# ---------------------------------------------------------------------------
# LUTRAM conversion (TclResources.V80Lutram): <name>_lutram.xdc is read by the
# kernel's out-of-context synthesis through a STEPS.SYNTH_DESIGN.TCL.PRE hook.
# ---------------------------------------------------------------------------
set hc_pcie_dir [file dirname [file normalize [info script]]]
set hc_krun_name top_@NAME@_0_0_synth_1
if {[get_runs -quiet $hc_krun_name] eq ""} { create_ip_run [get_files top.bd] }
set hc_krun [get_runs -quiet $hc_krun_name]
if {$hc_krun eq ""} { error "HardCilk: no out-of-context synthesis run $hc_krun_name for the kernel" }
set hc_lutram auto
if {[info exists ::env(HC_LUTRAM)]} { set hc_lutram $::env(HC_LUTRAM) }
set hc_lutram_xdc [file join $hc_pcie_dir @XDC@]
set hc_hook ""
if {$hc_lutram ne "off" && [file exists $hc_lutram_xdc] && ![info exists ::env(HC_KERNEL_SYNTH_ONLY)]} {
    set hc_hook [file join $hc_pcie_dir hc_lutram_synth_pre.tcl]
    set fh [open $hc_hook w]
    puts $fh "# Generated by @NAME@_v80_impl.tcl: LUTRAM XDC for the kernel's OOC synthesis."
    puts $fh "read_xdc [list $hc_lutram_xdc]"
    puts $fh "set_property used_in_implementation false \[get_files [list $hc_lutram_xdc]\]"
    puts $fh "puts \"HardCilk: LUTRAM XDC [list $hc_lutram_xdc] read for kernel synthesis\""
    close $fh
}
set hc_old_hook [get_property STEPS.SYNTH_DESIGN.TCL.PRE $hc_krun]
if {[file normalize [expr {$hc_old_hook eq "" ? "/" : $hc_old_hook}]] ne [file normalize [expr {$hc_hook eq "" ? "/" : $hc_hook}]]} {
    set_property STEPS.SYNTH_DESIGN.TCL.PRE $hc_hook $hc_krun
    if {[get_property PROGRESS $hc_krun] ne "0%"} { reset_run $hc_krun }
}
if {$hc_hook ne ""} {
    puts "HardCilk: LUTRAM conversion ON for $hc_krun_name ($hc_lutram_xdc)"
} else {
    puts "HardCilk: LUTRAM conversion OFF for $hc_krun_name"
}
if {[info exists ::env(HC_KERNEL_SYNTH_ONLY)]} {
    if {[get_property PROGRESS $hc_krun] ne "100%"} {
        reset_run $hc_krun
        launch_runs $hc_krun -jobs 1
        wait_on_run $hc_krun
    }
    if {[get_property PROGRESS $hc_krun] ne "100%"} { error "HardCilk: $hc_krun_name failed" }
    puts "HardCilk: kernel synthesized for LUTRAM analysis"
    return
}
""",
      "NAME" -> name,
      "XDC" -> xdcName(name)
    )

  /**
    * `<name>_v80_impl.tcl` fragment, after synth_1: the kernel's synthesized
    * BRAM/LUT totals (the proof that the XDC took effect), copied to reports/.
    */
  def implTclReport(name: String): String =
    TclV80Common.fill(
      """
set hc_kdir [get_property DIRECTORY [get_runs top_@NAME@_0_0_synth_1]]
set hc_ku [file join $hc_kdir top_@NAME@_0_0_utilization_synth.rpt]
if {[file exists $hc_ku]} {
    file copy -force $hc_ku [file join $out_dir reports kernel_utilization_synth.rpt]
    set fh [open $hc_ku r]; set hc_t [read $fh]; close $fh
    set hc_b ?; set hc_l ?; set hc_m ?
    regexp {\|\s*Block RAM Tile\s*\|\s*([0-9.]+)} $hc_t -> hc_b
    regexp {\|\s*CLB LUTs\*?\s*\|\s*([0-9]+)} $hc_t -> hc_l
    regexp {\|\s*LUT as Memory\s*\|\s*([0-9]+)} $hc_t -> hc_m
    puts "HardCilk: kernel OOC synthesis: $hc_b BRAM tiles, $hc_l LUTs ($hc_m as memory); LUTRAM hook '[get_property STEPS.SYNTH_DESIGN.TCL.PRE [get_runs top_@NAME@_0_0_synth_1]]'"
}
""",
      "NAME" -> name
    )

  /** build.sh fragment (bash), run before synthesis: make `<name>_lutram.xdc` if needed. */
  def buildShFragment: String =
    """
    # -----------------------------------------------------------------------
    # 3a. LUTRAM conversion XDC for the kernel's synthesis (see README_V80.md).
    # -----------------------------------------------------------------------
    LUTRAM_XDC=$HERE/${NAME}_lutram.xdc
    if [ "$PACKAGE_ONLY" = 0 ] && [ "$HC_LUTRAM" != off ] && [ ! -f "$LUTRAM_XDC" ]; then
        LUTRAM_DIR=$OUT/lutram
        mkdir -p "$LUTRAM_DIR"
        REF=${HC_LUTRAM_REF:-}
        if [ -z "$REF" ]; then
            echo "==> LUTRAM analysis: synthesizing the kernel out of context first (HC_LUTRAM_REF not set)"
            HC_KERNEL_SYNTH_ONLY=1 vivado -mode batch -nojournal -log "$LOGS/build_kernel_synth.log" \
                -source "$IMPL_TCL" -tclargs "$PROJECT_DIR" "$OUT"
            grep -q "HardCilk: kernel synthesized for LUTRAM analysis" "$LOGS/build_kernel_synth.log" || {
                echo "error: kernel synthesis failed -- see $LOGS/build_kernel_synth.log" >&2; exit 1; }
            REF=$PROJECT_DIR/prj.runs/top_${NAME}_0_0_synth_1/top_${NAME}_0_0.dcp
        fi
        echo "==> LUTRAM analysis of $REF"
        # shellcheck disable=SC2086
        python3 "$HERE/hc_lutram.py" --dcp "$REF" --workdir "$LUTRAM_DIR" \
            --xdc "$LUTRAM_XDC.tmp" --json "$LUTRAM_DIR/${NAME}_lutram_profile.json" ${HC_LUTRAM_ARGS:-} \
            | tee "$LUTRAM_DIR/hc_lutram.txt"
        [ -f "$LUTRAM_XDC.tmp" ] || { echo "error: LUTRAM analysis failed" >&2; exit 1; }
        mv "$LUTRAM_XDC.tmp" "$LUTRAM_XDC"
    fi
    export HC_LUTRAM
"""

  // The analysis script, written verbatim into pcie/hc_lutram.py. Source of
  // truth for edits; keep it free of triple double quotes.
  val pythonScript: String =
    """#!/usr/bin/env python3
'''HardCilk V80: find block RAMs that are better as LUTRAM, and write a synthesis XDC.

Input: a synthesized kernel netlist -- normally the out-of-context checkpoint of the
kernel's module-reference run (prj.runs/top_<name>_0_0_synth_1/top_<name>_0_0.dcp).
Memory shapes do not depend on the PE count, so the checkpoint of any earlier build of
the same design works. With --dcp the script runs Vivado itself
(report_ram_utilization -detail, report_utilization -hierarchical); --ram-rpt/--hier-rpt
reuse reports that already exist.

Selection (all configurable):
  * the logical memory is mapped to RAMB36/RAMB18 (not URAM, not already LUTRAM),
  * depth <= --max-depth (default 128),
  * BRAM fill efficiency (array bits / (tiles * 36 Kb)) < --max-eff percent (default 50),
  * estimated LUTs per RAMB36 tile saved <= --max-lut-per-bram (default 128; the
    measured cost of the 128-deep x 1031-bit wrapper FIFOs is ~206 LUTs per tile, of the
    64-deep HLS FIFOs ~88, of the 16-deep ones ~40-50),
  * not an XPM memory (its primitive is fixed by a parameter), unless --include-xpm,
  * --exclude / --include regexes on the generalized name.

LUTRAM cost model (Versal SLICEM, simple dual port, 1R1W):
  depth <= 32 : RAM32M16, 14 bits x 32 per 8 LUTs
  depth <= 64 : RAM64M8,   7 bits x 64 per 8 LUTs
  deeper      : ceil(depth/64) RAM64M8 banks + a read mux, ~0.62 LUT per bit per extra bank
  Calibrated by synthesizing single FIFOs (Vivado 2025.1, xcv80; total LUTs):
  128x1031 2991, 64x1031 1251, 32x1031 631, 64x864 1052, 64x432 541, 16x512 319,
  16x144 102, 128x7 53 -- the model is within ~5% except for tiny memories.

Outputs:
  --xdc  : set_property RAM_STYLE distributed on the selected memories, generalized over
           PE instances (peMap_<task>_2_<i> -> peMap_<task>_2_*), for the kernel's OOC
           synthesis run (it overrides the RTL (* ram_style = "block" *) of the HLS FIFOs
           and the OVERLAP wrapper memories);
  --json : per-PE-type before/after figures (RAMB36 tiles, LUTs, FFs) for the floorplanner
           (HC_LUTRAM_PROFILE at generation time);
  stdout : a table of every BRAM memory with its decision.
'''
import argparse
import json
import math
import os
import re
import subprocess
import sys
from collections import OrderedDict, defaultdict

PE_RE = re.compile(r"peMap_(\d+)_(\d+)_(\d+)")

REPORT_TCL = r'''
lassign $argv dcp outdir
open_checkpoint $dcp
report_ram_utilization -detail -file [file join $outdir ram.rpt]
report_utilization -hierarchical -hierarchical_depth 2 -file [file join $outdir hier.rpt]
puts "HC_LUTRAM: reports written"
'''


def run_vivado_reports(dcp, outdir, vivado):
    os.makedirs(outdir, exist_ok=True)
    ram, hier = os.path.join(outdir, "ram.rpt"), os.path.join(outdir, "hier.rpt")
    if all(os.path.exists(f) and os.path.getmtime(f) >= os.path.getmtime(dcp) for f in (ram, hier)):
        print(f"[hc_lutram] reusing {ram}, {hier} (newer than the checkpoint)")
        return ram, hier
    tcl = os.path.join(outdir, "hc_lutram_reports.tcl")
    with open(tcl, "w") as f:
        f.write(REPORT_TCL)
    print(f"[hc_lutram] opening {dcp} in Vivado for report_ram_utilization (a few minutes)")
    log = os.path.join(outdir, "hc_lutram_vivado.log")
    r = subprocess.run([vivado, "-mode", "batch", "-nojournal", "-log", log, "-source", tcl,
                        "-tclargs", os.path.abspath(dcp), os.path.abspath(outdir)],
                       stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
    if r.returncode != 0 or not os.path.exists(ram):
        sys.exit(f"[hc_lutram] Vivado failed, see {log}")
    return ram, hier


def table_rows(lines, start, end):
    '''Rows of the first ascii table between line indices [start, end).'''
    hdr, rows = None, []
    for ln in lines[start:end]:
        if not ln.startswith("|"):
            continue
        cells = ln.rstrip("\n").split("|")[1:-1]
        if hdr is None:
            hdr = [c.strip() for c in cells]
            continue
        rows.append(cells)
    return hdr, rows


def parse_ram_rpt(path):
    lines = open(path, errors="replace").readlines()
    secs = {}
    for i, ln in enumerate(lines):
        m = re.match(r"^(\d)\. (.*\S)\s*$", ln)
        if m and i + 1 < len(lines) and lines[i + 1].startswith("---"):
            secs[int(m.group(1))] = i
    if 2 not in secs or 3 not in secs:
        sys.exit(f"[hc_lutram] {path}: no 'Memory Description' / 'Memory Utilization' tables "
                 "(was it written with report_ram_utilization -detail?)")
    end3 = secs.get(4, len(lines))
    # Section 2: logical memories and their dimensions.
    hdr, rows = table_rows(lines, secs[2], secs[3])
    mems = OrderedDict()
    i_name, i_size, i_type, i_p1 = (hdr.index("Memory Name"), hdr.index("Array Size"),
                                     hdr.index("Memory Type"), hdr.index("Port 1 Dimension / Map"))
    i_p2 = hdr.index("Port 2 Dimension / Map")
    for c in rows:
        raw = c[i_name]
        name = raw.strip()
        if raw.startswith("  "):  # a primitive of the previous logical memory
            continue
        dims = [x.strip() for x in (c[i_p1], c[i_p2]) if re.match(r"^\s*\d+x\d+\s*$", x)]
        depth = width = 0
        for d in dims:
            a, b = d.split("x")
            depth, width = max(depth, int(a)), max(width, int(b))
        try:
            size = int(c[i_size].strip())
        except ValueError:
            size = depth * width
        mems[name] = dict(name=name, depth=depth, width=width, bits=size,
                          type=c[i_type].strip(), prims=[])
    # Section 3: primitives.
    hdr, rows = table_rows(lines, secs[3], end3)
    i_name, i_prim, i_style = hdr.index("Memory Name"), hdr.index("Primitive"), hdr.index("RAM STYLE")
    cur = None
    for c in rows:
        raw = c[i_name]
        name = raw.strip()
        if not raw.startswith("  "):
            cur = mems.get(name)
            continue
        if cur is not None and c[i_prim].strip():
            cur["prims"].append((c[i_prim].strip(), c[i_style].strip()))
    for m in mems.values():
        m["ramb36"] = sum(1 for p, _ in m["prims"] if p.startswith("RAMB36"))
        m["ramb18"] = sum(1 for p, _ in m["prims"] if p.startswith("RAMB18"))
        m["uram"] = sum(1 for p, _ in m["prims"] if p.startswith("URAM"))
        m["tiles"] = m["ramb36"] + 0.5 * m["ramb18"]
    return list(mems.values())


def parse_hier_rpt(path):
    '''Per-instance LUT/FF/RAMB36/RAMB18 of the peMap_* cells (depth-2 hierarchy).'''
    out = {}
    if not path or not os.path.exists(path):
        return out
    lines = open(path, errors="replace").readlines()
    hdr = None
    for ln in lines:
        if not ln.startswith("|"):
            continue
        cells = [c.strip() for c in ln.rstrip("\n").split("|")[1:-1]]
        if hdr is None and "Instance" in cells:
            hdr = cells
            continue
        if hdr is None or len(cells) != len(hdr):
            continue
        inst = cells[0]
        if not PE_RE.fullmatch(inst):
            continue
        g = lambda k: float(cells[hdr.index(k)]) if k in hdr and cells[hdr.index(k)] else 0.0
        out[inst] = dict(module=cells[1], lut=g("Total LUTs"), lutram=g("LUTRAMs"), ff=g("FFs"),
                         ramb36=g("RAMB36"), ramb18=g("RAMB18"))
    return out


def lutram_luts(depth, width):
    if depth <= 32:
        return math.ceil(width / 14) * 8
    banks = math.ceil(depth / 64)
    # Read mux between the 64-deep banks: ~0.62 LUT per bit per extra bank
    # (measured, Vivado 2025.1 xcv80: 128x1031 -> 2360 LUTRAM + 631 LUTs).
    return banks * math.ceil(width / 7) * 8 + (banks - 1) * math.ceil(0.62 * width)


def generalize(name):
    '''inst/peMap_2_2_13/pe/u_loop_fifo/mem -> (peMap_2_2_*/pe/u_loop_fifo/mem, 'peMap_2_2').'''
    n = re.sub(r"^inst/", "", name)
    m = PE_RE.search(n)
    pe = f"peMap_{m.group(1)}_{m.group(2)}" if m else None
    return PE_RE.sub(lambda mm: f"peMap_{mm.group(1)}_{mm.group(2)}_*", n), pe


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    src = ap.add_argument_group("input")
    src.add_argument("--dcp", help="synthesized kernel checkpoint (runs Vivado for the reports)")
    src.add_argument("--ram-rpt", help="existing report_ram_utilization -detail output")
    src.add_argument("--hier-rpt", help="existing report_utilization -hierarchical output (per-PE figures)")
    src.add_argument("--workdir", default=".", help="where --dcp reports go (default .)")
    src.add_argument("--vivado", default="vivado")
    sel = ap.add_argument_group("selection")
    sel.add_argument("--max-depth", type=int, default=int(os.environ.get("HC_LUTRAM_MAX_DEPTH", 128)))
    sel.add_argument("--max-eff", type=float, default=float(os.environ.get("HC_LUTRAM_MAX_EFF", 50)),
                     help="maximum BRAM fill efficiency in percent (default 50)")
    sel.add_argument("--max-lut-per-bram", type=float,
                     default=float(os.environ.get("HC_LUTRAM_MAX_LUT_PER_BRAM", 128)),
                     help="maximum estimated LUTs per RAMB36 tile saved (default 128; 0 = no limit)")
    sel.add_argument("--include", action="append", default=[], help="regex: also convert matching memories")
    sel.add_argument("--exclude", action="append", default=[], help="regex: never convert matching memories")
    sel.add_argument("--include-xpm", action="store_true", help="consider XPM memories too")
    out = ap.add_argument_group("output")
    out.add_argument("--xdc", help="synthesis XDC to write")
    out.add_argument("--json", help="per-PE profile for the floorplanner")
    out.add_argument("--verify", metavar="XDC",
                     help="check a netlist synthesized WITH this XDC: every memory it targets must be gone from BRAM")
    a = ap.parse_args()

    if a.dcp:
        ram_rpt, hier_rpt = run_vivado_reports(a.dcp, a.workdir, a.vivado)
    elif a.ram_rpt:
        ram_rpt, hier_rpt = a.ram_rpt, a.hier_rpt
    else:
        ap.error("give --dcp or --ram-rpt")

    mems = [m for m in parse_ram_rpt(ram_rpt) if m["tiles"] > 0]
    hier = parse_hier_rpt(hier_rpt)

    groups = OrderedDict()
    for m in mems:
        key, pe = generalize(m["name"])
        g = groups.setdefault(key, dict(key=key, pe=pe, depth=m["depth"], width=m["width"],
                                         bits=m["bits"], tiles=m["tiles"], count=0, type=m["type"]))
        g["count"] += 1

    sel_keys = []
    for g in groups.values():
        eff = 100.0 * g["bits"] / (g["tiles"] * 36864) if g["tiles"] else 100.0
        luts = lutram_luts(g["depth"], g["width"])
        cost = luts / g["tiles"]
        why = []
        if g["depth"] > a.max_depth:
            why.append(f"depth>{a.max_depth}")
        if eff >= a.max_eff:
            why.append(f"eff>={a.max_eff:g}%")
        if a.max_lut_per_bram > 0 and cost > a.max_lut_per_bram:
            why.append(f"LUT/BRAM>{a.max_lut_per_bram:g}")
        if "xpm_memory" in g["key"] and not a.include_xpm:
            why.append("XPM")
        if g["type"] == "RAM_TDP":
            why.append("true dual port")
        if any(re.search(x, g["key"]) for x in a.exclude):
            why.append("excluded")
        if any(re.search(x, g["key"]) for x in a.include):
            why = []
        g.update(eff=eff, luts=luts, cost=cost, convert=not why, why=",".join(why))
        if not why:
            sel_keys.append(g["key"])

    # Report
    print(f"{'memory (generalized)':<78} {'n':>3} {'shape':>9} {'tiles':>5} {'eff%':>5} "
          f"{'LUTs':>5} {'LUT/BR':>6}  decision")
    tot_save = tot_lut = 0.0
    for g in sorted(groups.values(), key=lambda x: -x["tiles"] * x["count"]):
        d = "LUTRAM" if g["convert"] else f"keep ({g['why']})"
        print(f"{g['key'][:78]:<78} {g['count']:>3} {g['depth']:>4}x{g['width']:<4} {g['tiles']:>5g} "
              f"{g['eff']:>5.1f} {g['luts']:>5} {g['cost']:>6.0f}  {d}")
        if g["convert"]:
            tot_save += g["tiles"] * g["count"]
            tot_lut += g["luts"] * g["count"]
    tot_tiles = sum(m["tiles"] for m in mems)
    print(f"[hc_lutram] kernel BRAM tiles {tot_tiles:g} -> {tot_tiles - tot_save:g} "
          f"(-{tot_save:g}), about +{tot_lut:.0f} LUTs as LUTRAM; {len(sel_keys)} pattern(s)")

    # Per PE type
    per_pe = OrderedDict()
    for inst, h in sorted(hier.items()):
        m = PE_RE.fullmatch(inst)
        k = f"peMap_{m.group(1)}_{m.group(2)}"
        per_pe.setdefault(k, []).append(h)
    profile = OrderedDict()
    for k, hs in per_pe.items():
        n = len(hs)
        avg = {f: sum(h[f] for h in hs) / n for f in ("lut", "lutram", "ff", "ramb36", "ramb18")}
        mod = hs[0]["module"]
        task = re.sub(r"(__xdcDup__\d+)$", "", mod)
        task = re.sub(r"^top_.*?_0_0_", "", task)
        task = re.sub(r"_\d+$", "", task)
        save = sum(g["tiles"] for g in groups.values() if g["convert"] and g["pe"] == k)
        add = sum(g["luts"] for g in groups.values() if g["convert"] and g["pe"] == k)
        tiles = avg["ramb36"] + 0.5 * avg["ramb18"]
        profile[task] = OrderedDict(
            pe_prefix=k, instances=n, module=mod,
            bram_tiles_before=round(tiles, 1), bram_tiles_after=round(tiles - save, 1),
            lut_before=round(avg["lut"]), lut_after=round(avg["lut"] + add),
            lutram_before=round(avg["lutram"]), lutram_after=round(avg["lutram"] + add),
            ff=round(avg["ff"]))
        print(f"[hc_lutram] PE {task} ({k}, {n} inst): BRAM tiles {tiles:g} -> {tiles - save:g}, "
              f"LUT {avg['lut']:.0f} -> {avg['lut'] + add:.0f}, FF {avg['ff']:.0f}")

    if a.verify:
        pats = re.findall(r'NAME =~ "([^"]+)"', open(a.verify).read())
        rx = [re.compile("^" + re.escape(p).replace(r"\*", ".*") + "$") for p in pats]
        left = [m for m in mems if any(r.match(m["name"] + "_reg_bram_0") or r.match(m["name"] + "_reg")
                                       for r in rx)]
        print(f"[hc_lutram] verify {a.verify}: {len(pats)} pattern(s), "
              f"{len(left)} targeted memorie(s) still in block RAM")
        for m in left:
            print(f"[hc_lutram]   STILL BRAM: {m['name']} {m['depth']}x{m['width']} ({m['tiles']:g} tiles)")
        if left:
            sys.exit(2)
        print("[hc_lutram] verify OK: all targeted memories are distributed RAM; kernel BRAM tiles now "
              f"{tot_tiles:g}")
        return
    if a.xdc:
        with open(a.xdc, "w") as f:
            f.write("# Generated by hc_lutram.py (HardCilk V80) from\n#   %s\n" % os.path.abspath(ram_rpt))
            f.write(f"# Selection: depth <= {a.max_depth}, BRAM efficiency < {a.max_eff:g}%, "
                    f"LUTs per RAMB36 saved <= {a.max_lut_per_bram:g}.\n")
            f.write(f"# Estimated: -{tot_save:g} BRAM tiles, +{tot_lut:.0f} LUTs (kernel of the reference build).\n")
            f.write("# Read by the kernel's out-of-context synthesis run (STEPS.SYNTH_DESIGN.TCL.PRE\n"
                    "# hook -> read_xdc), applied to the elaborated netlist. XDC overrides the RTL\n"
                    "# (* ram_style = \"block\" *) of the HLS FIFOs and the OVERLAP wrapper.\n")
            for g in groups.values():
                if not g["convert"]:
                    continue
                pat = "*/" + g["key"] + "_reg*"
                f.write(f"# {g['count']} x {g['depth']}x{g['width']}: {g['tiles']:g} BRAM tiles each "
                        f"({g['eff']:.1f}% full) -> ~{g['luts']} LUTs each\n")
                f.write(f"set_property RAM_STYLE distributed [get_cells -quiet -hierarchical -filter {{NAME =~ \"{pat}\"}}]\n")
        print(f"[hc_lutram] wrote {a.xdc}")
    if a.json:
        with open(a.json, "w") as f:
            json.dump(OrderedDict(
                source=os.path.abspath(ram_rpt),
                selection=dict(max_depth=a.max_depth, max_eff=a.max_eff, max_lut_per_bram=a.max_lut_per_bram),
                kernel=dict(bram_tiles_before=tot_tiles, bram_tiles_after=tot_tiles - tot_save, lut_added=round(tot_lut)),
                patterns=[g["key"] for g in groups.values() if g["convert"]],
                pes=profile), f, indent=1)
        print(f"[hc_lutram] wrote {a.json}")


if __name__ == "__main__":
    main()
"""
  // END pythonScript
}
