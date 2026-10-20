package TclResources

/**
  * Shared Alveo V80 (Versal, AVED shell) TCL helpers.
  *
  * Owned by the PCIe flow ([[TclGeneratorMemPEsV80]]). Other V80 flows (e.g. a
  * QuestaSim block design) may call these but should not edit them: the strings
  * here encode facts about the AVED `amd_v80_gen5x8_25.1` shell and the V80 NoC
  * that were validated on hardware.
  *
  * Every TCL template below is a plain (non-interpolated) Scala string with
  * `@KEY@` placeholders, so Tcl `$var` references need no escaping.
  */
object TclV80Common {

  /** CPM5 PF1 BAR2 (AXI bridge master) target, and the HardCilk register window. */
  val regBaseAddress: BigInt = BigInt("20200000000", 16)

  /** Soft-reset AXI GPIO, in the same 64 KB window as the registers. */
  val resetGpioAddress: BigInt = regBaseAddress + 0x8000

  /** Size of the NoC M_AXI aperture and of PF1 BAR2: registers (32 KB) + reset GPIO (32 KB). */
  val mgmtWindowBytes: Int = 64 * 1024

  /** Data width of every HBM NMU (`axi_noc/HBMxx_AXI`). */
  val hbmNmuDataWidth: Int = 256

  /**
    * Requested bandwidth per (HBM NMU, pseudo channel) connection, MB/s each way.
    * 32 connections x 2 directions x 250 = 16000 MB/s is the most one HBM NMU
    * can be asked for; 400 per direction (12.8 GB/s read + 12.8 GB/s write
    * booked against separate R/W budgets) validated cleanly at 4 ports and with
    * a single bandwidth-relaxation critical warning at 32.
    */
  val hbmNmuBwPerTarget: Int =
    // HC_HBM_NMU_BW=<MB/s> (opt-in): override, e.g. to keep the total booked
    // bandwidth constant when HC_HBM_MAX_NMUS raises the HBM NMU count above 32.
    sys.env.get("HC_HBM_NMU_BW").flatMap(v => scala.util.Try(v.trim.toInt).toOption).filter(_ > 0).getOrElse(400)

  /**
    * HC_HBM_MAX_NMUS=<n> (opt-in, 32..64): the most HBM NMUs (`HBMxx_AXI`) the
    * V80 flows connect. Unset: 32 (two per memory-controller port pair). The
    * device has 64 NOC_NMU_HBM2E sites; more than 32 still uses the same pair
    * map (NMU i -> HBMc_PORT{i%2} and HBMc_PORT{2+i%2}), so every HBM port then
    * serves n/2 NMUs instead of 16.
    */
  val hbmMaxNmus: Int =
    sys.env.get("HC_HBM_MAX_NMUS").flatMap(v => scala.util.Try(v.trim.toInt).toOption)
      .filter(n => n >= 1 && n <= 64).getOrElse(32)

  /**
    * HC_NOC_HIEFFORT=1 (opt-in): run the NoC compiler at high effort (the
    * remedy Vivado itself suggests when NoC place-and-route fails with
    * "Reached timeout limit in solver" / Ipconfig 75-4217). Empty when unset.
    */
  def nocHiEffortTcl: String =
    if (!sys.env.get("HC_NOC_HIEFFORT").exists(_.trim == "1")) ""
    else
      "# HC_NOC_HIEFFORT=1: NoC compiler at high effort.\n" +
        "set_param noc.enableCompilerHiEffort true\n" +
        "puts \"HardCilk: noc.enableCompilerHiEffort [get_param noc.enableCompilerHiEffort]\"\n"

  def hex(v: BigInt): String = "0x" + v.toString(16).toUpperCase

  /** Substitute `@KEY@` placeholders. */
  def fill(template: String, kv: (String, String)*): String =
    kv.foldLeft(template) { case (s, (k, v)) => s.replace(s"@$k@", v) }

  /**
    * TCL procs used by the V80 block design.
    *
    *  - `hc_v80_hbm_nmu_connect noc port idx bw`: points HBM NMU `port` of `noc`
    *    at all 32 HBM pseudo channels, which gives it one contiguous 32 GB
    *    aperture 0x40_0000_0000 - 0x47_FFFF_FFFF. Pseudo channel c.PC0 is
    *    reachable through `HBMc_PORT0/1`, c.PC1 through `HBMc_PORT2/3`;
    *    alternating the port with `idx` spreads masters over all four ports of
    *    each memory controller.
    *  - `hc_v80_add_noc_connection pin dest bw`: adds one destination to an
    *    existing NoC slave's CONNECTIONS, keeping whatever was there.
    */
  val bdProcs: String =
    """
proc hc_v80_hbm_nmu_connect {noc port idx bw} {
    set a [expr {$idx % 2}]
    set b [expr {2 + ($idx % 2)}]
    set conn {}
    for {set c 0} {$c < 16} {incr c} {
        lappend conn HBM${c}_PORT${a} "read_bw {$bw} write_bw {$bw} read_avg_burst {64} write_avg_burst {64}"
        lappend conn HBM${c}_PORT${b} "read_bw {$bw} write_bw {$bw} read_avg_burst {64} write_avg_burst {64}"
    }
    # No DATA_WIDTH here: the NMU takes the width of the master it is wired to
    # (see TclV80Common.nmuWidthFor).
    set_property -dict [list \
        CONFIG.CONNECTIONS $conn \
        CONFIG.CATEGORY {pl_hbm} \
    ] [get_bd_intf_pins $noc/$port]
}

proc hc_v80_add_noc_connection {pin dest bw} {
    set c [get_property CONFIG.CONNECTIONS $pin]
    dict set c $dest "read_bw {$bw} write_bw {$bw} read_avg_burst {64} write_avg_burst {64}"
    set_property CONFIG.CONNECTIONS $c $pin
}
"""

  /**
    * CPM5 changes on top of the stock AVED CIPS, as in the hand-built image
    * that ran HardCilk on the card:
    *
    *  - PF1 BAR0 = QDMA DMA registers, 512 KB, 64-bit, non-prefetchable
    *    (stock AVED leaves PF1 BAR0 disabled, so the QDMA driver cannot bind);
    *  - PF1 BAR2 = AXI bridge master, 64 KB, translated to 0x202_0000_0000
    *    (`PCIEBAR2AXIBAR_QDMA_2` is already 0x202_0000_0000 in stock AVED);
    *  - ARI capability on, AxCACHE = 1 (modifiable) on PF1 BARs 0-3.
    *
    * Applied as dict edits of the stock CPM_CONFIG so any other key keeps its
    * AVED value.
    */
  val cpmPf1ConfigTcl: String =
    """
# ---------------------------------------------------------------------------
# CPM5 PF1: QDMA DMA BAR0 + 64 KB AXI-bridge BAR2 -> 0x202_0000_0000
# ---------------------------------------------------------------------------
set hc_cips [get_bd_cells /cips]
set hc_cpm [get_property CONFIG.CPM_CONFIG $hc_cips]
foreach {k v} {
    CPM_PCIE1_ARI_CAP_ENABLED            1
    CPM_PCIE1_PF1_BAR0_QDMA_ENABLED      1
    CPM_PCIE1_PF1_BAR0_QDMA_TYPE         DMA
    CPM_PCIE1_PF1_BAR0_QDMA_64BIT        1
    CPM_PCIE1_PF1_BAR0_QDMA_PREFETCHABLE 0
    CPM_PCIE1_PF1_BAR0_QDMA_SCALE        Kilobytes
    CPM_PCIE1_PF1_BAR0_QDMA_SIZE         512
    CPM_PCIE1_PF1_BAR0_QDMA_AXCACHE      1
    CPM_PCIE1_PF1_BAR1_QDMA_AXCACHE      1
    CPM_PCIE1_PF1_BAR2_QDMA_ENABLED      1
    CPM_PCIE1_PF1_BAR2_QDMA_TYPE         AXI_Bridge_Master
    CPM_PCIE1_PF1_BAR2_QDMA_64BIT        0
    CPM_PCIE1_PF1_BAR2_QDMA_PREFETCHABLE 0
    CPM_PCIE1_PF1_BAR2_QDMA_SCALE        Kilobytes
    CPM_PCIE1_PF1_BAR2_QDMA_SIZE         64
    CPM_PCIE1_PF1_BAR2_QDMA_AXCACHE      1
    CPM_PCIE1_PF1_BAR3_QDMA_AXCACHE      1
    CPM_PCIE1_PF1_PCIEBAR2AXIBAR_QDMA_2  0x0000020200000000
} {
    dict set hc_cpm $k $v
}
set_property CONFIG.CPM_CONFIG $hc_cpm $hc_cips
puts "HardCilk: CPM PF1 BAR0=DMA 512K, BAR2=AXI bridge 64K -> 0x202_0000_0000"
"""

  /**
    * `pcie/build.sh` for the V80. Stages a private copy of the AVED shell
    * sources (never touching the AVED repo), builds the block design, and with
    * `-i` runs synthesis -> implementation -> write_device_image, the AMC
    * firmware for the new XSA and the FPT packaging into a flashable PDI.
    */
  def buildShellScript(name: String, lutramDefault: String = "off"): String =
    fill(buildShTemplate, "NAME" -> name, "LUTRAM_DEFAULT" -> lutramDefault, "LUTRAM_STEP" -> V80Lutram.buildShFragment)

  private val buildShTemplate: String =
    """#!/bin/bash
# Generated by HardCilk -- Alveo V80 (AVED amd_v80_gen5x8_25.1) build driver.
#
#   ./build.sh            stage the AVED shell sources and build the block design
#   ./build.sh -i [-j N]  ... then synthesis, implementation, device image,
#                         AMC firmware and FPT packaging -> output/@NAME@.pdi
#
# See README_V80.md.
set -euo pipefail

cd "$(dirname "$0")"
HERE=$(pwd)

NAME=@NAME@
PROJECT_DIR=$HERE/${NAME}_v80_project
XPR=$PROJECT_DIR/prj.xpr
BD_TCL=$HERE/${NAME}_v80_bd.tcl
IMPL_TCL=$HERE/${NAME}_v80_impl.tcl
SHELL_DIR=$HERE/aved_shell
OUT=$HERE/output
LOGS=$OUT/logs

AVED_HW_DIR=${AVED_HW_DIR:-/home/shahawy/AVED/hw/amd_v80_gen5x8_25.1}
AVED_FW_DIR=${AVED_FW_DIR:-$AVED_HW_DIR/../../fw/AMC}
VIVADO_SETTINGS=${VIVADO_SETTINGS:-/tools/Xilinx/2025.1/Vivado/settings64.sh}
VITIS_SETTINGS=${VITIS_SETTINGS:-/tools/Xilinx/2025.1/Vitis/settings64.sh}
AMC_ELF=${AMC_ELF:-}

MODE=bd
PACKAGE_ONLY=0
FORCE=0
OPEN_GUI=0
HC_JOBS=${HC_JOBS:-32}
# Block RAM -> LUTRAM conversion of shallow kernel memories (TclResources.V80Lutram).
HC_LUTRAM=${HC_LUTRAM:-@LUTRAM_DEFAULT@}

usage() {
    cat <<EOF
Usage: ./build.sh [options]

  (default)          Stage the AVED shell sources into aved_shell/ and build the
                     block design (validate_bd_design must pass).
  -i, --impl         ... then synthesis, implementation, write_device_image,
                     AMC firmware build for the new XSA and FPT packaging.
  -j, --jobs N       Parallel Vivado jobs (default: 32).
  -f, --force        Delete the Vivado project (and the staged shell) first.
  -g, --gui          Open the project in the Vivado GUI at the end.
  -o, --open         Open the existing project in the GUI and exit.
      --package      Skip Vivado; redo only AMC firmware + FPT packaging from
                     the output/ of an earlier -i run.
      --amc-elf F    Do not rebuild the AMC firmware; package F instead (only
                     valid if the shell's RPU-visible address map is unchanged,
                     which HardCilk never touches). Same as AMC_ELF=F.
  -h, --help         This message.

Environment:
  AVED_HW_DIR      AVED hardware design   (default: /home/shahawy/AVED/hw/amd_v80_gen5x8_25.1)
  AVED_FW_DIR      AVED AMC firmware      (default: \$AVED_HW_DIR/../../fw/AMC)
  VIVADO_SETTINGS  Vivado 2025.1 settings (default: /tools/Xilinx/2025.1/Vivado/settings64.sh)
  VITIS_SETTINGS   Vitis 2025.1 settings, for the AMC firmware build
  HC_JOBS          same as -j
  HC_MAX_THREADS   threads per Vivado run for opt/place/route (default: -j, at most 32)
  HC_LUTRAM        auto|off: LUTRAM conversion of shallow kernel BRAMs (default: @LUTRAM_DEFAULT@)
  HC_LUTRAM_REF    synthesized kernel checkpoint to derive ${NAME}_lutram.xdc from
                   (default: synthesize the kernel once first)
  HC_LUTRAM_ARGS   extra hc_lutram.py options (e.g. --max-lut-per-bram 0)

Outputs (after -i):
  output/${NAME}.pdi             flashable PDI (FPT + design + AMC firmware)
  output/${NAME}_routed.dcp      routed checkpoint
  output/${NAME}.xsa             hardware platform the AMC firmware was built from
  output/reports/*.rpt           utilization (flat, hierarchical, per SLR), timing, power, DRC
  output/logs/*.log              Vivado and firmware logs
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        -i|--impl)  MODE=impl ;;
        -j|--jobs)  shift; HC_JOBS="$1" ;;
        -f|--force) FORCE=1 ;;
        -g|--gui)   OPEN_GUI=1 ;;
        -o|--open)  MODE=open; OPEN_GUI=1 ;;
        --amc-elf)  shift; AMC_ELF="$1" ;;
        --package)  MODE=impl; PACKAGE_ONLY=1 ;;
        -h|--help)  usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
    esac
    shift
done
export HC_JOBS
# Threads per Vivado run (place/route/opt): HC_MAX_THREADS, else -j, capped at Vivado's 32.
HC_MAX_THREADS=${HC_MAX_THREADS:-$(( HC_JOBS < 32 ? HC_JOBS : 32 ))}
export HC_MAX_THREADS

if [ ! -f "$VIVADO_SETTINGS" ]; then
    echo "error: no Vivado settings script at $VIVADO_SETTINGS (set VIVADO_SETTINGS)" >&2
    exit 1
fi
# The Xilinx settings scripts reference unset variables.
set +u
# shellcheck disable=SC1090
source "$VIVADO_SETTINGS"
set -u

if [ "$MODE" = open ]; then
    [ -f "$XPR" ] || { echo "error: no project at $XPR -- run ./build.sh first." >&2; exit 1; }
    exec vivado "$XPR"
fi

if [ "$FORCE" = 1 ]; then
    echo "==> removing $PROJECT_DIR and $SHELL_DIR"
    rm -rf "$PROJECT_DIR" "$SHELL_DIR"
fi
mkdir -p "$OUT/reports" "$LOGS"

# ---------------------------------------------------------------------------
# 1. Private copy of the AVED shell sources. The AVED repo is only read.
# ---------------------------------------------------------------------------
stage_shell() {
    [ -d "$AVED_HW_DIR/src/iprepo" ] || { echo "error: $AVED_HW_DIR is not an AVED amd_v80_gen5x8_25.1 tree" >&2; exit 1; }
    [ -d "$AVED_FW_DIR/src" ] || { echo "error: $AVED_FW_DIR is not an AVED fw/AMC tree" >&2; exit 1; }
    echo "==> staging AVED shell sources from $AVED_HW_DIR into $SHELL_DIR"
    rm -rf "$SHELL_DIR.tmp"
    mkdir -p "$SHELL_DIR.tmp/src/bd" "$SHELL_DIR.tmp/fw"
    cp -a "$AVED_HW_DIR/src/iprepo" "$AVED_HW_DIR/src/constraints" "$SHELL_DIR.tmp/src/"
    cp -a "$AVED_HW_DIR/fpt" "$AVED_HW_DIR/scripts" "$SHELL_DIR.tmp/"

    # The shell block design comes from the committed AVED script, not the
    # working tree: hand edits made in a local AVED checkout (e.g. a design
    # added to the BD by hand) must not leak into a generated build.
    local rel=hw/amd_v80_gen5x8_25.1/src/bd/create_bd_design.tcl
    local repo
    repo=$(git -C "$AVED_HW_DIR" rev-parse --show-toplevel 2>/dev/null || true)
    if [ -n "$repo" ] && git -C "$repo" cat-file -e "HEAD:$rel" 2>/dev/null; then
        git -C "$repo" show "HEAD:$rel" > "$SHELL_DIR.tmp/src/bd/create_bd_design.tcl"
        if ! cmp -s "$SHELL_DIR.tmp/src/bd/create_bd_design.tcl" "$AVED_HW_DIR/src/bd/create_bd_design.tcl"; then
            echo "    NOTE: $AVED_HW_DIR/src/bd/create_bd_design.tcl has local edits; using the committed (HEAD) version"
        fi
    else
        echo "    NOTE: $AVED_HW_DIR is not a git checkout; using its create_bd_design.tcl as is"
        cp -a "$AVED_HW_DIR/src/bd/create_bd_design.tcl" "$SHELL_DIR.tmp/src/bd/"
    fi

    # AMC firmware sources only (no build/ or amc_bsp/ products).
    mkdir -p "$SHELL_DIR.tmp/fw/AMC"
    cp -a "$AVED_FW_DIR/src" "$AVED_FW_DIR/scripts" "$AVED_FW_DIR/CMakeLists.txt" "$SHELL_DIR.tmp/fw/AMC/"
    [ -f "$AVED_FW_DIR/merge_amc_version.json" ] && cp -a "$AVED_FW_DIR/merge_amc_version.json" "$SHELL_DIR.tmp/fw/AMC/"
    (git -C "$AVED_HW_DIR" rev-parse HEAD 2>/dev/null || echo unknown) > "$SHELL_DIR.tmp/AVED_COMMIT"
    mv "$SHELL_DIR.tmp" "$SHELL_DIR"
}
[ -d "$SHELL_DIR" ] || stage_shell

# ---------------------------------------------------------------------------
# 2. Block design (AVED top + HardCilk), project with AVED hooks.
# ---------------------------------------------------------------------------
if [ ! -f "$PROJECT_DIR/.hc_bd_ok" ]; then
    rm -rf "$PROJECT_DIR"
    echo "==> building block design ($BD_TCL)"
    vivado -mode batch -nojournal -log "$LOGS/build_bd.log" -source "$BD_TCL" \
        -tclargs "$SHELL_DIR" "$PROJECT_DIR"
    grep -q "HardCilk: block design validated" "$LOGS/build_bd.log" || {
        echo "error: block design build failed -- see $LOGS/build_bd.log" >&2; exit 1; }
    # Conflicting HBM NMU address maps (two ports of one memory controller
    # behind the same NMU) are only a critical warning, but break HBM access.
    if grep -Eq "CRITICAL WARNING: \[[A-Za-z_ ]+75-207\]" "$LOGS/build_bd.log"; then
        echo "error: NoC CRITICAL WARNING 75-207 (conflicting HBM NMU map) -- see $LOGS/build_bd.log" >&2
        rm -rf "$PROJECT_DIR"; exit 1
    fi
    touch "$PROJECT_DIR/.hc_bd_ok"
else
    echo "==> reusing existing project $XPR (use --force to rebuild)"
fi

if [ "$MODE" = impl ]; then
@LUTRAM_STEP@
    # -----------------------------------------------------------------------
    # 3. Synthesis, implementation, write_device_image, reports.
    # -----------------------------------------------------------------------
    if [ "$PACKAGE_ONLY" = 0 ]; then
    echo "==> synthesis, implementation and device image ($HC_JOBS jobs)"
    vivado -mode batch -nojournal -log "$LOGS/build_impl.log" -source "$IMPL_TCL" \
        -tclargs "$PROJECT_DIR" "$OUT"
    fi
    grep -q "HardCilk: device image written" "$LOGS/build_impl.log" || {
        echo "error: implementation failed -- see $LOGS/build_impl.log" >&2; exit 1; }
    XSA=$OUT/$NAME.xsa
    DESIGN_PDI=$OUT/${NAME}_design.pdi

    # -----------------------------------------------------------------------
    # 4. AMC firmware (RPU) for this XSA, in the staged copy.
    # -----------------------------------------------------------------------
    AMC_DIR=$SHELL_DIR/fw/AMC
    if [ -n "$AMC_ELF" ]; then
        echo "==> reusing AMC firmware $AMC_ELF"
        cp -a "$AMC_ELF" "$OUT/amc.elf"
    else
        echo "==> building AMC firmware from $XSA"
        set +u
        # shellcheck disable=SC1090
        [ -f "$VITIS_SETTINGS" ] && source "$VITIS_SETTINGS"
        set -u
        # getVersion.sh stamps the git hash; point git at the AVED repo.
        GIT_DIR_AVED=$(git -C "$AVED_FW_DIR" rev-parse --absolute-git-dir 2>/dev/null || true)
        (
            cd "$AMC_DIR"
            [ -n "$GIT_DIR_AVED" ] && export GIT_DIR="$GIT_DIR_AVED"
            ./scripts/build.sh -os freertos10_xilinx -profile v80 -xsa "$XSA"
        ) > "$LOGS/amc_build.log" 2>&1 || { echo "error: AMC build failed -- see $LOGS/amc_build.log" >&2; exit 1; }
        [ -f "$AMC_DIR/build/amc.elf" ] || { echo "error: AMC build produced no amc.elf -- see $LOGS/amc_build.log" >&2; exit 1; }
        cp -a "$AMC_DIR/build/amc.elf" "$OUT/amc.elf"
    fi

    # -----------------------------------------------------------------------
    # 5. FPT + PDI packaging (as AVED build_all.sh).
    # -----------------------------------------------------------------------
    echo "==> FPT packaging"
    mkdir -p "$AMC_DIR/build"
    ( cd "$AMC_DIR/build" && python3 ../scripts/gen_fpt.py -f ../scripts/fpt.json ) > "$LOGS/fpt.log" 2>&1
    cp -a "$AMC_DIR/build/fpt.bin" "$OUT/fpt.bin"
    cat > "$OUT/pdi_combine.bif" <<EOF
all:
{
    image {
      { type=bootimage, file=$DESIGN_PDI }
    }
    image {
        id = 0x1c000000, name=rpu_subsystem, delay_handoff
        { core=r5-0, file=$OUT/amc.elf }
    }
}
EOF
    bootgen -arch versal -image "$OUT/pdi_combine.bif" -w -o "$OUT/${NAME}_nofpt.pdi" >> "$LOGS/fpt.log" 2>&1
    python3 "$SHELL_DIR/fpt/fpt_pdi_gen.py" --fpt "$OUT/fpt.bin" --pdi "$OUT/${NAME}_nofpt.pdi" \
        --output "$OUT/$NAME.pdi" >> "$LOGS/fpt.log" 2>&1
    [ -f "$OUT/$NAME.pdi" ] || { echo "error: PDI packaging failed -- see $LOGS/fpt.log" >&2; exit 1; }

    grep "HardCilk: WNS" "$LOGS/build_impl.log" | tail -1 || true
    echo "==> flashable PDI: $OUT/$NAME.pdi"
    echo "HardCilk: synthesis complete"
fi

if [ "$OPEN_GUI" = 1 ]; then
    exec vivado "$XPR"
fi
echo "==> done"
"""

  /**
    * Batch implementation script: XSA, synthesis, implementation up to
    * write_device_image, reports and artifacts. Arguments: project dir, output dir.
    *
    * `write_hw_platform` runs before synthesis exactly as AVED's
    * `build_design.tcl` does: the AMC BSP only needs the fixed shell's view.
    */
  def implTcl(name: String, nmuWidths: Seq[Int] = Seq.empty, lutram: Boolean = false,
      nmuNames: Seq[String] = Seq.empty): String =
    fill(implTemplate, "NAME" -> name, "NMU_CHECK" -> nocDwidthCheckTcl(nmuWidths, nmuNames),
      "LUTRAM" -> (if (lutram) V80Lutram.implTclFragment(name) else ""),
      "LUTRAM_REPORT" -> (if (lutram) V80Lutram.implTclReport(name) else ""))

  /**
    * How a HardCilk memory master reaches its HBM NMU. Two hardware facts
    * (found in the NoC/HBM BFMs and nocattrs.dat, Vivado 2025.1):
    *
    *  1. The NoC compiler programs each NMU's data width (REG_DWIDTH in
    *     `prj.gen/.../nsln/nocattrs.dat`) from the END-POINT master it
    *     harvests, hopping over infrastructure IP (SmartConnect, register
    *     slices) -- not from the NoC port. A width converter in front of an NMU
    *     leaves it programmed for the far master: broken, and silent in Vivado.
    *  2. HBM2E rejects narrow bursts (AxLEN > 0 with AxSIZE below the NMU
    *     width), and a SmartConnect upsizer only packs a narrow master's bursts
    *     into full beats if AxCACHE[1] (modifiable) is set, which HardCilk's
    *     masters do not set.
    *
    *  3. HardCilk masters issue INCR bursts that cross 4KB boundaries, which
    *     the HBM NMU rejects.
    *
    * Hence ([[nmuPathTcl]]): every NMU is fed by [[split4kModule]] (256 bits;
    * the 4KB splitter and the RTL end-point for the width harvest, so every
    * NMU is programmed 256 bits -- [[nmuWidthFor]] is always 256). A 256-bit
    * master goes straight to it; any other width first goes through
    * [[shimModule]] at its own width with FORCE_MODIFIABLE=1 and a 1:1
    * SmartConnect (the up/down-sizer).
    */
  def nmuWidthFor(nativeWidth: Int): Int = hbmNmuDataWidth

  /** Name of the AXI4 pass-through shim module, see [[nmuWidthFor]]. */
  val shimModule: String = "hc_axi_shim"

  /**
    * TCL that connects AXI master interface pin `from` (a master of
    * `nativeWidth` bits) to HBM NMU interface pin `nmu`, following
    * [[nmuWidthFor]]. Cells are named `shim_in_<sfx>`, `smartconnect_sizer_<sfx>`
    * and `split4k_<sfx>`. `propsFrom` (default `from`) is the interface the
    * shim takes its ID/address widths from.
    */
  def nmuPathTcl(
      sfx: String,
      from: String,
      nmu: String,
      nativeWidth: Int,
      clk: String,
      rstn: String,
      propsFrom: Option[String] = None,
      outputSlice: Boolean = false
  ): String = {
    // ID/address widths come from the original master: a register slice's
    // M_AXI has not propagated them yet when this runs.
    val props = propsFrom.getOrElse(from)
    val sb = new StringBuilder
    def ln(x: String): Unit = { sb.append(x); sb.append("\n") }
    if (nativeWidth == hbmNmuDataWidth) sb.append(nmuEndpointTcl(sfx, from, nmu, clk, rstn, Some(props), outputSlice))
    else {
      val shimIn = s"shim_in_$sfx"
      val sc = s"smartconnect_sizer_$sfx"
      ln(s"create_bd_cell -type module -reference $shimModule $shimIn")
      ln(s"set_property -dict [list CONFIG.DATA_WIDTH {$nativeWidth} " +
        s"CONFIG.ID_WIDTH [expr {max(1, [get_property CONFIG.ID_WIDTH $props])}] " +
        s"CONFIG.ADDR_WIDTH [get_property CONFIG.ADDR_WIDTH $props] CONFIG.FORCE_MODIFIABLE {1}] [get_bd_cells $shimIn]")
      ln(s"connect_bd_intf_net $from [get_bd_intf_pins $shimIn/S_AXI]")
      ln(s"create_bd_cell -type ip -vlnv xilinx.com:ip:smartconnect $sc")
      ln(s"set_property -dict [list CONFIG.NUM_SI {1} CONFIG.NUM_MI {1} CONFIG.NUM_CLKS {1}] [get_bd_cells $sc]")
      ln(s"connect_bd_intf_net [get_bd_intf_pins $shimIn/M_AXI] [get_bd_intf_pins $sc/S00_AXI]")
      ln(s"connect_bd_net $clk [get_bd_pins $shimIn/aclk]")
      ln(s"connect_bd_net $rstn [get_bd_pins $shimIn/aresetn]")
      ln(s"connect_bd_net $clk [get_bd_pins $sc/aclk]")
      ln(s"connect_bd_net $rstn [get_bd_pins $sc/aresetn]")
      sb.append(nmuEndpointTcl(sfx, s"[get_bd_intf_pins $sc/M00_AXI]", nmu, clk, rstn, None, outputSlice))
    }
    sb.toString
  }

  /**
    * `from` -> `split4k_<sfx>` ([[split4kModule]], 256 bits: the 4KB splitter
    * and the NoC width-harvest end-point) -> `nmu`. `idFrom`, if given, is the
    * interface the splitter takes its ID/address widths from (a SmartConnect
    * output needs none: the defaults are wide enough and the BD adapts).
    * `outputSlice` adds a fully-registered `axi_register_slice` (`rs_nmu_<sfx>`)
    * between the splitter and the NMU: the splitter drives AxLEN
    * combinationally from its address state, and that path into the NMU's
    * hard-block pins was the critical path of the V80 PCIe builds at 250 MHz
    * (-0.06 to -0.15 ns). The width harvest hops over the slice to the
    * splitter, so the NMU stays 256 bits.
    */
  def nmuEndpointTcl(
      sfx: String,
      from: String,
      nmu: String,
      clk: String,
      rstn: String,
      idFrom: Option[String] = None,
      outputSlice: Boolean = false,
      addrXorHi: Long = 0
  ): String = {
    val c = s"split4k_$sfx"
    val rs = s"rs_nmu_$sfx"
    // HC_HBM_STRIPING=stripe4k|stripe4k_xor: the address permutation sits after
    // the splitter (no burst crosses 4KB any more, so each one stays inside one
    // permuted 4KB block) and becomes the NMU's RTL width-harvest end-point.
    val (last, stripe) =
      if (TclHbmex.enabled) TclHbmex.cellTcl(sfx, c, clk, rstn) // HC_HBM_STRIPING=hbmex|hbmex_stripe
      else if (hbmStripeMode == 0) (c, Seq.empty[String])
      else {
        val st = s"stripe_$sfx"
        (st, Seq(
          s"create_bd_cell -type module -reference $stripeModule $st",
          s"set_property -dict [list CONFIG.MODE {$hbmStripeMode} " +
            (if (addrXorHi != 0) s"CONFIG.ADDR_XOR_HI {$addrXorHi} " else "") +
            s"CONFIG.ID_WIDTH [get_property CONFIG.ID_WIDTH [get_bd_cells $c]] " +
            s"CONFIG.ADDR_WIDTH [get_property CONFIG.ADDR_WIDTH [get_bd_cells $c]]] [get_bd_cells $st]",
          s"connect_bd_intf_net [get_bd_intf_pins $c/M_AXI] [get_bd_intf_pins $st/S_AXI]",
          s"connect_bd_net $clk [get_bd_pins $st/aclk]",
          s"connect_bd_net $rstn [get_bd_pins $st/aresetn]"
        ))
      }
    val toNmu = stripe ++ (
      if (!outputSlice) Seq(s"connect_bd_intf_net [get_bd_intf_pins $last/M_AXI] $nmu")
      else
        Seq(
          s"create_bd_cell -type ip -vlnv xilinx.com:ip:axi_register_slice $rs",
          s"set_property -dict [list CONFIG.REG_AW {1} CONFIG.REG_AR {1} CONFIG.REG_W {1} CONFIG.REG_R {1} CONFIG.REG_B {1}] [get_bd_cells $rs]",
          s"connect_bd_intf_net [get_bd_intf_pins $last/M_AXI] [get_bd_intf_pins $rs/S_AXI]",
          s"connect_bd_intf_net [get_bd_intf_pins $rs/M_AXI] $nmu",
          s"connect_bd_net $clk [get_bd_pins $rs/aclk]",
          s"connect_bd_net $rstn [get_bd_pins $rs/aresetn]"
        ))
    (Seq(s"create_bd_cell -type module -reference $split4kModule $c") ++
      idFrom.toSeq.map(m =>
        s"set_property -dict [list CONFIG.ID_WIDTH [expr {max(1, [get_property CONFIG.ID_WIDTH $m])}] " +
          s"CONFIG.ADDR_WIDTH [get_property CONFIG.ADDR_WIDTH $m]] [get_bd_cells $c]") ++
      Seq(
        s"connect_bd_intf_net $from [get_bd_intf_pins $c/S_AXI]",
        s"connect_bd_net $clk [get_bd_pins $c/aclk]",
        s"connect_bd_net $rstn [get_bd_pins $c/aresetn]"
      ) ++ toNmu).mkString("", "\n", "\n")
  }


  /** REG_DWIDTH encoding: 2=32, 3=64, 4=128, 5=256 bits. */
  def regDwidthCode(width: Int): Int = Integer.numberOfTrailingZeros(width / 8)

  /**
    * Post-generate_target guard: parse nocattrs.dat and fail if any
    * `axi_noc_cips/inst/HBMxx_AXI_nmu` REG_DWIDTH differs from the width of
    * the master the block design drives it with.
    */
  def nocDwidthCheckTcl(nmuWidths: Seq[Int], nmuNames: Seq[String] = Seq.empty): String =
    if (nmuWidths.isEmpty) ""
    else {
      // HC_NOC_PES: explicit NMU cell names (HBMxx_AXI_nmu and Sxx_AXI_nmu).
      val t0 = if (nmuNames.isEmpty) nocDwidthTemplate
        else nocDwidthTemplate.replace("set nmu [format HBM%02d_AXI_nmu $i]",
          s"set nmu [lindex {${nmuNames.mkString(" ")}} $$i]")
      fill(t0,
        "LIST" -> nmuWidths.map(regDwidthCode).mkString(" ")
      )
    }

  private val nocDwidthTemplate: String =
        """
# HBM NMU data widths as programmed by the NoC compiler vs. what the BD drives.
set hc_expected_dw {@LIST@}
set hc_attrs [glob -nocomplain [file join $proj_dir prj.gen sources_1 common nsln nocattrs.dat]]
if {$hc_attrs eq ""} { error "HardCilk: nocattrs.dat not found after generate_target" }
set fh [open $hc_attrs r]; set hc_txt [read $fh]; close $fh
set hc_bad 0
for {set i 0} {$i < [llength $hc_expected_dw]} {incr i} {
    set nmu [format HBM%02d_AXI_nmu $i]
    set want [lindex $hc_expected_dw $i]
    if {![regexp "set_property REG_DWIDTH (\[0-9a-fA-F\]+) \\\[get_cells axi_noc_cips/inst/$nmu\\\]" $hc_txt -> got]} {
        puts "HardCilk: ERROR -- no REG_DWIDTH for $nmu in $hc_attrs"; incr hc_bad; continue
    }
    scan $got %x got
    if {$got != $want} {
        puts "HardCilk: ERROR -- $nmu REG_DWIDTH $got, expected $want (2=32,3=64,4=128,5=256 bit)"
        incr hc_bad
    }
}
if {$hc_bad} { error "HardCilk: $hc_bad HBM NMU(s) programmed with the wrong data width" }
puts "HardCilk: HBM NMU data widths OK ([llength $hc_expected_dw] port(s))"
"""

  /** Name of the 4KB-boundary burst splitter, see [[split4kVerilog]]. */
  val split4kModule: String = "hc_axi_4k_split"

  /**
    * Verilog of [[split4kModule]] (written by the Questa workstream, unit-tested
    * in Questa; moved here as the single source for both V80 flows). HardCilk
    * masters issue INCR bursts that cross 4KB boundaries, which the HBM NMU
    * rejects; this splits them, regenerates WLAST, merges B and suppresses the
    * intermediate RLASTs. It sits right in front of every HBM NMU and is also
    * the RTL end-point of the NoC's width harvest.
    */
  private val split4kVerilogBase: String =
    """// AXI4 4KB-boundary burst splitter (generated by HardCilk, TclResources.TclV80Common).
//
// The Versal HBM NMU rejects INCR bursts that cross a 4KB boundary ("AXI Address
// ... is not alligned with respect to 4K boundry"), which AXI forbids but some
// HardCilk masters issue (task-queue spills/refills). This stage, placed right in
// front of an HBM NMU, splits such bursts at every 4KB boundary:
//   AW/AR: a crossing burst is issued as consecutive sub-bursts;
//   W:     WLAST is regenerated at each sub-burst end;
//   B:     the sub-bursts' responses are merged into one (worst response);
//   R:     RLAST is suppressed on all but the last sub-burst.
// Responses are paired with sub-bursts by order, so a crossing burst is only
// started once every outstanding transaction in that direction has completed,
// and nothing new is accepted until it has (same ID => in-order responses).
// Non-crossing bursts pass with one cycle of AW/AR latency and full overlap.
module hc_axi_4k_split #(
  parameter DATA_WIDTH = 256,
  parameter ID_WIDTH = 6,
  parameter ADDR_WIDTH = 64
) (
  (* X_INTERFACE_PARAMETER = "ASSOCIATED_BUSIF S_AXI:M_AXI, ASSOCIATED_RESET aresetn" *)
  input aclk,
  input aresetn,
  // slave side
  input  [ID_WIDTH-1:0] s_axi_awid,
  input  [ADDR_WIDTH-1:0] s_axi_awaddr,
  input  [7:0] s_axi_awlen,
  input  [2:0] s_axi_awsize,
  input  [1:0] s_axi_awburst,
  input  s_axi_awlock,
  input  [3:0] s_axi_awcache,
  input  [2:0] s_axi_awprot,
  input  [3:0] s_axi_awqos,
  input  [3:0] s_axi_awregion,
  input  s_axi_awvalid,
  output s_axi_awready,
  input  [DATA_WIDTH-1:0] s_axi_wdata,
  input  [DATA_WIDTH/8-1:0] s_axi_wstrb,
  input  s_axi_wlast,
  input  s_axi_wvalid,
  output s_axi_wready,
  output [ID_WIDTH-1:0] s_axi_bid,
  output [1:0] s_axi_bresp,
  output s_axi_bvalid,
  input  s_axi_bready,
  input  [ID_WIDTH-1:0] s_axi_arid,
  input  [ADDR_WIDTH-1:0] s_axi_araddr,
  input  [7:0] s_axi_arlen,
  input  [2:0] s_axi_arsize,
  input  [1:0] s_axi_arburst,
  input  s_axi_arlock,
  input  [3:0] s_axi_arcache,
  input  [2:0] s_axi_arprot,
  input  [3:0] s_axi_arqos,
  input  [3:0] s_axi_arregion,
  input  s_axi_arvalid,
  output s_axi_arready,
  output [ID_WIDTH-1:0] s_axi_rid,
  output [DATA_WIDTH-1:0] s_axi_rdata,
  output [1:0] s_axi_rresp,
  output s_axi_rlast,
  output s_axi_rvalid,
  input  s_axi_rready,
  // master side
  output [ID_WIDTH-1:0] m_axi_awid,
  output [ADDR_WIDTH-1:0] m_axi_awaddr,
  output [7:0] m_axi_awlen,
  output [2:0] m_axi_awsize,
  output [1:0] m_axi_awburst,
  output m_axi_awlock,
  output [3:0] m_axi_awcache,
  output [2:0] m_axi_awprot,
  output [3:0] m_axi_awqos,
  output [3:0] m_axi_awregion,
  output m_axi_awvalid,
  input  m_axi_awready,
  output [DATA_WIDTH-1:0] m_axi_wdata,
  output [DATA_WIDTH/8-1:0] m_axi_wstrb,
  output m_axi_wlast,
  output m_axi_wvalid,
  input  m_axi_wready,
  input  [ID_WIDTH-1:0] m_axi_bid,
  input  [1:0] m_axi_bresp,
  input  m_axi_bvalid,
  output m_axi_bready,
  output [ID_WIDTH-1:0] m_axi_arid,
  output [ADDR_WIDTH-1:0] m_axi_araddr,
  output [7:0] m_axi_arlen,
  output [2:0] m_axi_arsize,
  output [1:0] m_axi_arburst,
  output m_axi_arlock,
  output [3:0] m_axi_arcache,
  output [2:0] m_axi_arprot,
  output [3:0] m_axi_arqos,
  output [3:0] m_axi_arregion,
  output m_axi_arvalid,
  input  m_axi_arready,
  input  [ID_WIDTH-1:0] m_axi_rid,
  input  [DATA_WIDTH-1:0] m_axi_rdata,
  input  [1:0] m_axi_rresp,
  input  m_axi_rlast,
  input  m_axi_rvalid,
  output m_axi_rready
);

  localparam FD = 32;  // FIFO depth (outstanding sub-bursts per direction)
  localparam FW = 5;

  // beats from addr (aligned down to the beat) to the next 4KB boundary
  function [12:0] beats_to_4k(input [11:0] off, input [2:0] size);
    reg [12:0] aligned;
    begin
      aligned = {1'b0, off} & ~((13'd1 << size) - 13'd1);
      beats_to_4k = (13'd4096 - aligned) >> size;
    end
  endfunction

  // ---------------------------------------------------------------- write ---
  reg               w_gen;             // issuing sub-bursts of a loaded AW
  reg  [ADDR_WIDTH-1:0] w_addr;
  reg  [8:0]        w_rem;             // beats left in the loaded AW (1..256)
  reg               w_split;           // loaded AW crosses 4KB
  reg  [ID_WIDTH-1:0] w_id;
  reg  [2:0]        w_size; reg [1:0] w_burst; reg w_lock;
  reg  [3:0]        w_cache, w_qos, w_region; reg [2:0] w_prot;
  reg               w_split_out;       // a split write's final B not yet returned

  reg  [8:0]        wf_len [0:FD-1];   // beats per issued sub-burst (W side)
  reg  [FW:0]       wf_cnt; reg [FW-1:0] wf_rd, wf_wr;
  reg  [1:0]        bf_flag [0:FD-1];  // {absorb, split_final} per issued sub-burst
  reg  [FW:0]       bf_cnt; reg [FW-1:0] bf_rd, bf_wr;
  reg  [1:0]        b_acc;
  reg  [8:0]        w_beat;

  wire [12:0] aw_in_room = beats_to_4k(s_axi_awaddr[11:0], s_axi_awsize);
  wire        aw_in_cross = ({4'd0, s_axi_awlen} + 13'd1) > aw_in_room;
  assign s_axi_awready = aresetn && !w_gen && !w_split_out && (!aw_in_cross || bf_cnt == 0);

  wire [12:0] w_room  = beats_to_4k(w_addr[11:0], w_size);
  wire [8:0]  w_beats = ({4'd0, w_rem} > w_room) ? w_room[8:0] : w_rem;
  wire        w_space = (wf_cnt < FD) && (bf_cnt < FD);
  assign m_axi_awvalid  = w_gen && w_space;
  assign m_axi_awaddr   = w_addr;
  assign m_axi_awlen    = w_beats - 9'd1;
  assign m_axi_awid     = w_id;
  assign m_axi_awsize   = w_size;
  assign m_axi_awburst  = w_burst;
  assign m_axi_awlock   = w_lock;
  assign m_axi_awcache  = w_cache;
  assign m_axi_awprot   = w_prot;
  assign m_axi_awqos    = w_qos;
  assign m_axi_awregion = w_region;

  wire wf_any = wf_cnt != 0;
  assign m_axi_wdata  = s_axi_wdata;
  assign m_axi_wstrb  = s_axi_wstrb;
  assign m_axi_wvalid = s_axi_wvalid && wf_any;
  assign s_axi_wready = m_axi_wready && wf_any;
  assign m_axi_wlast  = (w_beat == wf_len[wf_rd] - 9'd1);

  wire bf_absorb = bf_flag[bf_rd][1];
  wire bf_final  = bf_flag[bf_rd][0];
  wire [1:0] b_worst = (m_axi_bresp > b_acc) ? m_axi_bresp : b_acc;
  assign s_axi_bvalid = m_axi_bvalid && (bf_cnt != 0) && !bf_absorb;
  assign s_axi_bid    = m_axi_bid;
  assign s_axi_bresp  = b_worst;
  assign m_axi_bready = (bf_cnt != 0) && (bf_absorb || s_axi_bready);

  wire aw_acc = s_axi_awvalid && s_axi_awready;
  wire aw_iss = m_axi_awvalid && m_axi_awready;
  wire w_hs   = m_axi_wvalid && m_axi_wready;
  wire b_hs   = m_axi_bvalid && m_axi_bready;

  always @(posedge aclk) begin
    if (!aresetn) begin
      w_gen <= 0; w_split_out <= 0; wf_cnt <= 0; wf_rd <= 0; wf_wr <= 0;
      bf_cnt <= 0; bf_rd <= 0; bf_wr <= 0; b_acc <= 0; w_beat <= 0;
    end else begin
      if (aw_acc) begin
        w_gen <= 1; w_addr <= s_axi_awaddr; w_rem <= {1'b0, s_axi_awlen} + 9'd1;
        w_split <= aw_in_cross; w_id <= s_axi_awid; w_size <= s_axi_awsize;
        w_burst <= s_axi_awburst; w_lock <= s_axi_awlock; w_cache <= s_axi_awcache;
        w_prot <= s_axi_awprot; w_qos <= s_axi_awqos; w_region <= s_axi_awregion;
        if (aw_in_cross) w_split_out <= 1;
      end
      if (aw_iss) begin
        wf_len[wf_wr] <= w_beats; wf_wr <= wf_wr + 1'b1;
        bf_flag[bf_wr] <= {(w_beats != w_rem), (w_split && w_beats == w_rem)};
        bf_wr <= bf_wr + 1'b1;
        w_addr <= w_addr + ({{(ADDR_WIDTH-9){1'b0}}, w_beats} << w_size);
        w_rem <= w_rem - w_beats;
        if (w_beats == w_rem) w_gen <= 0;
      end
      if (w_hs) begin
        if (m_axi_wlast) begin w_beat <= 0; wf_rd <= wf_rd + 1'b1; end
        else w_beat <= w_beat + 9'd1;
      end
      wf_cnt <= wf_cnt + (aw_iss ? 1 : 0) - ((w_hs && m_axi_wlast) ? 1 : 0);
      if (b_hs) begin
        bf_rd <= bf_rd + 1'b1;
        if (bf_absorb) b_acc <= b_worst;
        else begin
          b_acc <= 0;
          if (bf_final) w_split_out <= 0;
        end
      end
      bf_cnt <= bf_cnt + (aw_iss ? 1 : 0) - (b_hs ? 1 : 0);
    end
  end

  // ----------------------------------------------------------------- read ---
  reg               r_gen;
  reg  [ADDR_WIDTH-1:0] r_addr;
  reg  [8:0]        r_rem;
  reg               r_split;
  reg  [ID_WIDTH-1:0] r_id;
  reg  [2:0]        r_size; reg [1:0] r_burst; reg r_lock;
  reg  [3:0]        r_cache, r_qos, r_region; reg [2:0] r_prot;
  reg               r_split_out;
  reg  [1:0]        rf_flag [0:FD-1];  // {suppress_last, split_final}
  reg  [FW:0]       rf_cnt; reg [FW-1:0] rf_rd, rf_wr;

  wire [12:0] ar_in_room = beats_to_4k(s_axi_araddr[11:0], s_axi_arsize);
  wire        ar_in_cross = ({4'd0, s_axi_arlen} + 13'd1) > ar_in_room;
  assign s_axi_arready = aresetn && !r_gen && !r_split_out && (!ar_in_cross || rf_cnt == 0);

  wire [12:0] r_room  = beats_to_4k(r_addr[11:0], r_size);
  wire [8:0]  r_beats = ({4'd0, r_rem} > r_room) ? r_room[8:0] : r_rem;
  assign m_axi_arvalid  = r_gen && (rf_cnt < FD);
  assign m_axi_araddr   = r_addr;
  assign m_axi_arlen    = r_beats - 9'd1;
  assign m_axi_arid     = r_id;
  assign m_axi_arsize   = r_size;
  assign m_axi_arburst  = r_burst;
  assign m_axi_arlock   = r_lock;
  assign m_axi_arcache  = r_cache;
  assign m_axi_arprot   = r_prot;
  assign m_axi_arqos    = r_qos;
  assign m_axi_arregion = r_region;

  wire rf_supp  = rf_flag[rf_rd][1];
  wire rf_final = rf_flag[rf_rd][0];
  assign s_axi_rvalid = m_axi_rvalid;
  assign s_axi_rid    = m_axi_rid;
  assign s_axi_rdata  = m_axi_rdata;
  assign s_axi_rresp  = m_axi_rresp;
  assign s_axi_rlast  = m_axi_rlast && !rf_supp;
  assign m_axi_rready = s_axi_rready;

  wire ar_acc = s_axi_arvalid && s_axi_arready;
  wire ar_iss = m_axi_arvalid && m_axi_arready;
  wire rl_hs  = m_axi_rvalid && m_axi_rready && m_axi_rlast;

  always @(posedge aclk) begin
    if (!aresetn) begin
      r_gen <= 0; r_split_out <= 0; rf_cnt <= 0; rf_rd <= 0; rf_wr <= 0;
    end else begin
      if (ar_acc) begin
        r_gen <= 1; r_addr <= s_axi_araddr; r_rem <= {1'b0, s_axi_arlen} + 9'd1;
        r_split <= ar_in_cross; r_id <= s_axi_arid; r_size <= s_axi_arsize;
        r_burst <= s_axi_arburst; r_lock <= s_axi_arlock; r_cache <= s_axi_arcache;
        r_prot <= s_axi_arprot; r_qos <= s_axi_arqos; r_region <= s_axi_arregion;
        if (ar_in_cross) r_split_out <= 1;
      end
      if (ar_iss) begin
        rf_flag[rf_wr] <= {(r_beats != r_rem), (r_split && r_beats == r_rem)};
        rf_wr <= rf_wr + 1'b1;
        r_addr <= r_addr + ({{(ADDR_WIDTH-9){1'b0}}, r_beats} << r_size);
        r_rem <= r_rem - r_beats;
        if (r_beats == r_rem) r_gen <= 0;
      end
      if (rl_hs) begin
        rf_rd <= rf_rd + 1'b1;
        if (!rf_supp && rf_final) r_split_out <= 0;
      end
      rf_cnt <= rf_cnt + (ar_iss ? 1 : 0) - (rl_hs ? 1 : 0);
    end
  end

endmodule
"""

  // ---------------------------------------------------------------------------
  // HC_SPLIT4K_FAST (noc_choke): full-rate 4KB splitter (opt-in, default off)
  // ---------------------------------------------------------------------------

  /**
    * `HC_SPLIT4K_FAST=1` (default off; generated output unchanged when off)
    * emits a variant of [[split4kModule]] (same name, ports and BD TCL) whose
    * non-crossing bursts pass straight through: AW/AR combinational, zero added
    * latency, one burst per cycle, up to 64 bursts per direction in flight.
    * The base module registers every AW/AR (`s_axi_arready = !r_gen`), so it
    * accepts at most one burst every 2 cycles and caps 32 in flight: in the
    * QuestaSim characterisation (noc_choke) that half-rate cap was the
    * ARVALID-high/ARREADY-low stall on the busiest pageRank_overlap HBM port
    * (min. AR spacing 2 cycles at the NMU, 0.455 AR/cycle). Crossing bursts
    * still take the original split/merge path, with the same ordering rules.
    */
  lazy val split4kFastLevel: Int =
    sys.env.get("HC_SPLIT4K_FAST").map(_.trim.toLowerCase) match {
      case Some("1" | "true" | "on" | "yes") => 1
      case Some("2") => 2
      case _ => 0
    }
  lazy val split4kFast: Boolean = split4kFastLevel > 0

  /**
    * Verilog of [[split4kModule]]: the base module, its fast variant
    * (`HC_SPLIT4K_FAST=1`), or the registered-ready fast variant
    * (`HC_SPLIT4K_FAST=2`: the same pass-through core behind a 2-entry
    * full-rate skid buffer on AW and AR, so s_axi_awready/s_axi_arready come
    * from flops -- the combinational ready of variant 1 was the critical net
    * of a V80 PCIe build).
    */
  def split4kVerilog: String = split4kCarry(split4kFastLevel match {
    case 1 => split4kFastVerilog
    case 2 => split4kFast2Verilog
    case _ => split4kVerilogBase
  })

  /**
    * `HC_SPLIT4K_CARRY=1` (slr3_sched, default off; output unchanged when off):
    * a sub-burst that is followed by another one always ends exactly on a 4 KB
    * boundary, so the next sub-burst address is `{addr[63:12] + 1, 12'h000}`.
    * That replaces the `addr + (beats << size)` update, whose ADDR_WIDTH-bit
    * adder sat behind the beats-to-boundary subtract/min/shift (13-20 logic
    * levels, a failing-path family of the 64-PE V80 builds), by a plain
    * increment of the upper bits. After the last sub-burst the register is
    * not used again until it is reloaded, so the value it gets then is moot.
    */
  private def split4kCarry(v: String): String =
    if (!sys.env.get("HC_SPLIT4K_CARRY").exists(_.trim == "1")) v
    else {
      val out = Seq("w", "r").foldLeft(v) { case (t, c) =>
        val from = s"${c}_addr <= ${c}_addr + ({{(ADDR_WIDTH-9){1'b0}}, ${c}_beats} << ${c}_size);"
        require(t.contains(from), s"[TclV80Common] HC_SPLIT4K_CARRY: '$from' not found in the splitter")
        t.replace(from, s"${c}_addr <= {${c}_addr[ADDR_WIDTH-1:12] + 1'b1, 12'h000}; // HC_SPLIT4K_CARRY")
      }
      println("[TclV80Common] HC_SPLIT4K_CARRY: 4KB splitter sub-burst address = upper-bit increment")
      out
    }

  private lazy val split4kFast2Verilog: String = {
    val fast = split4kFastVerilog
    val hdrStart = fast.indexOf(s"module $split4kModule #(")
    require(hdrStart >= 0, "[TclV80Common] HC_SPLIT4K_FAST=2: splitter header not found")
    val header = fast.substring(hdrStart, fast.indexOf(");", hdrStart) + 2)
    val core = fast.replaceFirst(java.util.regex.Pattern.quote(s"module $split4kModule #("), s"module ${split4kModule}_core #(")
    val ports = "(?:input|output)\\s+(?:\\[[^\\]]+\\]\\s*)?(\\w+)".r.findAllMatchIn(header).map(_.group(1)).toSeq
      .filterNot(n => n == "aclk" || n == "aresetn")
    val aw = Seq("awid" -> "ID_WIDTH", "awaddr" -> "ADDR_WIDTH", "awlen" -> "8", "awsize" -> "3", "awburst" -> "2",
      "awlock" -> "1", "awcache" -> "4", "awprot" -> "3", "awqos" -> "4", "awregion" -> "4")
    val ar = aw.map { case (n, w) => ("ar" + n.drop(2), w) }
    val fields = (aw ++ ar).map(_._1).toSet
    def cat(fs: Seq[(String, String)], pre: String) = fs.map(f => pre + f._1).mkString("{", ", ", "}")
    val sb = new StringBuilder
    sb.append("// HC_SPLIT4K_FAST=2 wrapper (generated by HardCilk, TclResources.TclV80Common).\n")
    sb.append(header).append("\n")
    sb.append("  // HC_SPLIT4K_FAST=2: registered-ready variant. A 2-entry full-rate skid buffer on the AW\n" +
      "  // and AR inputs drives s_axi_awready/s_axi_arready from flops; the pass-through core\n" +
      "  // (HC_SPLIT4K_FAST=1 logic) then sees registered request fields.\n")
    for ((ch, fs) <- Seq("aw" -> aw, "ar" -> ar)) {
      val W = ch.toUpperCase + "W"
      sb.append(s"  localparam $W = ${fs.map(f => s"(${f._2})").mkString(" + ")};\n")
      sb.append(s"  wire [$W-1:0] ${ch}_in = ${cat(fs, "s_axi_")};\n")
      sb.append(s"  wire [$W-1:0] ${ch}_q; wire ${ch}_qvalid, ${ch}_qready;\n")
      sb.append(s"  ${split4kModule}_skid #(.W($W)) ${ch}_skid (.aclk(aclk), .aresetn(aresetn), .s_valid(s_axi_${ch}valid), " +
        s".s_ready(s_axi_${ch}ready), .s_data(${ch}_in), .m_valid(${ch}_qvalid), .m_ready(${ch}_qready), .m_data(${ch}_q));\n")
      fs.foreach { case (n, w) => sb.append(s"  wire [($w)-1:0] c_$n;\n") }
      sb.append(s"  assign ${cat(fs, "c_")} = ${ch}_q;\n")
    }
    val conns = Seq(".aclk(aclk)", ".aresetn(aresetn)") ++ ports.map { n =>
      if (n.startsWith("s_axi_") && fields.contains(n.drop(6))) s".$n(c_${n.drop(6)})"
      else if (n == "s_axi_awvalid" || n == "s_axi_arvalid") s".$n(${n.slice(6, 8)}_qvalid)"
      else if (n == "s_axi_awready" || n == "s_axi_arready") s".$n(${n.slice(6, 8)}_qready)"
      else s".$n($n)"
    }
    sb.append(s"  ${split4kModule}_core #(.DATA_WIDTH(DATA_WIDTH), .ID_WIDTH(ID_WIDTH), .ADDR_WIDTH(ADDR_WIDTH)) core (\n    " +
      conns.mkString(",\n    ") + ");\nendmodule\n")
    core + s"""
// 2-entry skid buffer: registered s_ready and m_valid/m_data, one transfer per cycle.
module ${split4kModule}_skid #(parameter W = 8) (
  input aclk, input aresetn,
  input s_valid, output s_ready, input [W-1:0] s_data,
  output m_valid, input m_ready, output [W-1:0] m_data
);
  reg mv, sv; reg [W-1:0] md, sd;
  assign s_ready = aresetn && !sv;
  assign m_valid = mv; assign m_data = md;
  always @(posedge aclk) begin
    if (!aresetn) begin mv <= 0; sv <= 0; end
    else begin
      if (!mv || m_ready) begin
        if (sv) begin mv <= 1; md <= sd; sv <= 0; end
        else begin mv <= s_valid && s_ready; if (s_valid && s_ready) md <= s_data; end
      end else if (s_valid && s_ready) begin sv <= 1; sd <= s_data; end
    end
  end
endmodule
""" + sb.toString
  }

  private lazy val split4kFastVerilog: String = {
    var v = split4kVerilogBase
    def rep(from: String, to: String): Unit = {
      require(v.split(java.util.regex.Pattern.quote(from), -1).length == 2,
        s"[TclV80Common] HC_SPLIT4K_FAST: splitter template changed, cannot patch: $from")
      v = v.replace(from, to)
    }
    rep("// Non-crossing bursts pass with one cycle of AW/AR latency and full overlap.\n",
      "// Fast variant (HC_SPLIT4K_FAST=1): a non-crossing burst passes straight through\n" +
      "// (combinational AW/AR, zero added latency, one burst per cycle), so the stage no\n" +
      "// longer halves the NMU's address rate; up to 64 bursts per direction in flight.\n")
    rep("  localparam FD = 32;  // FIFO depth (outstanding sub-bursts per direction)\n  localparam FW = 5;\n",
      "  localparam FD = 64;  // FIFO depth (outstanding sub-bursts per direction)\n  localparam FW = 6;\n")
    rep("  assign s_axi_awready = aresetn && !w_gen && !w_split_out && (!aw_in_cross || bf_cnt == 0);\n",
      "  // pass-through for a burst that stays inside its 4KB block\n" +
      "  wire        aw_pass = aresetn && !w_gen && !w_split_out && !aw_in_cross;\n" +
      "  assign s_axi_awready = aresetn && !w_gen && !w_split_out &&\n" +
      "                         (aw_in_cross ? (bf_cnt == 0) : ((wf_cnt < FD) && (bf_cnt < FD) && m_axi_awready));\n")
    val awSig = Seq("addr", "id", "size", "burst", "lock", "cache", "prot", "qos", "region")
    rep("  assign m_axi_awvalid  = w_gen && w_space;\n", "  assign m_axi_awvalid  = w_gen ? w_space : (aw_pass && w_space && s_axi_awvalid);\n")
    rep("  assign m_axi_awlen    = w_beats - 9'd1;\n", "  assign m_axi_awlen    = w_gen ? (w_beats - 9'd1) : s_axi_awlen;\n")
    rep("  assign m_axi_arvalid  = r_gen && (rf_cnt < FD);\n", "  assign m_axi_arvalid  = r_gen ? (rf_cnt < FD) : (ar_pass && (rf_cnt < FD) && s_axi_arvalid);\n")
    rep("  assign m_axi_arlen    = r_beats - 9'd1;\n", "  assign m_axi_arlen    = r_gen ? (r_beats - 9'd1) : s_axi_arlen;\n")
    for (n <- awSig) {
      val pad = " " * (7 - n.length)
      rep(s"  assign m_axi_aw$n$pad= w_$n;\n", s"  assign m_axi_aw$n$pad= w_gen ? w_$n : s_axi_aw$n;\n")
      rep(s"  assign m_axi_ar$n$pad= r_$n;\n", s"  assign m_axi_ar$n$pad= r_gen ? r_$n : s_axi_ar$n;\n")
    }
    rep("      if (aw_acc) begin\n        w_gen <= 1;", "      if (aw_acc && aw_in_cross) begin\n        w_gen <= 1;")
    rep("      if (aw_iss) begin\n        wf_len[wf_wr] <= w_beats; wf_wr <= wf_wr + 1'b1;",
      "      if (aw_iss && !w_gen) begin  // pass-through burst\n" +
      "        wf_len[wf_wr] <= {1'b0, s_axi_awlen} + 9'd1; wf_wr <= wf_wr + 1'b1;\n" +
      "        bf_flag[bf_wr] <= 2'b00; bf_wr <= bf_wr + 1'b1;\n" +
      "      end\n" +
      "      if (aw_iss && w_gen) begin\n        wf_len[wf_wr] <= w_beats; wf_wr <= wf_wr + 1'b1;")
    rep("  assign s_axi_arready = aresetn && !r_gen && !r_split_out && (!ar_in_cross || rf_cnt == 0);\n",
      "  wire        ar_pass = aresetn && !r_gen && !r_split_out && !ar_in_cross;\n" +
      "  assign s_axi_arready = aresetn && !r_gen && !r_split_out &&\n" +
      "                         (ar_in_cross ? (rf_cnt == 0) : ((rf_cnt < FD) && m_axi_arready));\n")
    rep("      if (ar_acc) begin\n        r_gen <= 1;", "      if (ar_acc && ar_in_cross) begin\n        r_gen <= 1;")
    rep("      if (ar_iss) begin\n        rf_flag[rf_wr] <= {(r_beats != r_rem), (r_split && r_beats == r_rem)};",
      "      if (ar_iss && !r_gen) begin  // pass-through burst\n" +
      "        rf_flag[rf_wr] <= 2'b00; rf_wr <= rf_wr + 1'b1;\n" +
      "      end\n" +
      "      if (ar_iss && r_gen) begin\n        rf_flag[rf_wr] <= {(r_beats != r_rem), (r_split && r_beats == r_rem)};")
    v
  }

  // ---------------------------------------------------------------------------
  // HBM striping (HC_HBM_STRIPING at generation time)
  // ---------------------------------------------------------------------------

  /**
    * `HC_HBM_STRIPING` = none (default) | stripe4k | stripe4k_xor | hbmex.
    *
    * The V80 NoC maps each HBM NMU's 32 GB linearly (pseudo channel =
    * offset[34:30]), so contiguous data sits in one or two of the 32 PCs.
    * `stripe4k` / `stripe4k_xor` put [[stripeModule]] after every
    * [[split4kModule]] (see [[nmuEndpointTcl]]); `hbmex` is the HBMEX
    * integration and is not handled by this helper (mode 0 here).
    */
  lazy val hbmStriping: String =
    sys.env.getOrElse("HC_HBM_STRIPING", "none").trim.toLowerCase match {
      case "" | "none" | "off" => "none"
      case s @ ("stripe4k" | "stripe4k_xor" | "hbmex" | "hbmex_stripe") => s
      case other =>
        throw new IllegalArgumentException(s"HC_HBM_STRIPING=$other: expected none, stripe4k, stripe4k_xor, hbmex or hbmex_stripe")
    }

  /** [[stripeModule]] MODE: 0 off, 1 stripe4k (rotate), 2 stripe4k_xor (rotate + XOR hash). */
  def hbmStripeMode: Int = hbmStriping match {
    case "stripe4k" => 1
    case "stripe4k_xor" => 2
    case _ => 0
  }

  /**
    * `HC_HBM_HOST_PATH` = sw (default) | pl, for striped PCIe designs: how the
    * host's QDMA (which enters the NoC at the CPM, not through the PL) reaches
    * striped HBM. sw: memIO_qdma.h applies the permutation. pl: the CPM gets a
    * 32 GB PL window at [[hbmHostAliasAddress]] -> hc_axi_4k_split ->
    * hc_axi_stripe (rebased onto HBM) -> one more HBM NMU; the host copies to
    * the alias unpermuted. The direct CPM->HBM (linear) map is kept for raw access.
    */
  lazy val hbmHostPath: String =
    sys.env.getOrElse("HC_HBM_HOST_PATH", "sw").trim.toLowerCase match {
      case s @ ("sw" | "pl") => s
      case other => throw new IllegalArgumentException(s"HC_HBM_HOST_PATH=$other: expected sw or pl")
    }

  /** CPM-side alias of HBM for HC_HBM_HOST_PATH=pl (32 GB, 32 GB-aligned, in the NoC's PL range). */
  val hbmHostAliasAddress: BigInt = BigInt("20800000000", 16)

  /** The alias base when the design has the PL host path. */
  def hbmHostAliasBase: Option[BigInt] =
    if (hbmStripeMode != 0 && hbmHostPath == "pl") Some(hbmHostAliasAddress) else None

  /** Name of the HBM address-permutation module, see [[stripeVerilog]]. */
  val stripeModule: String = "hc_axi_stripe"

  /**
    * Reference model of [[stripeModule]] on an absolute address (HBM base
    * 0x40_0000_0000 is 2^38-aligned, so offset bits == address bits [34:0]).
    * The host must put the byte the PEs see at logical address `a` at
    * physical address `stripeMap(a, mode)` (memIO_qdma.h / main_sim backdoor
    * implement the same function).
    */
  def stripeMap(a: BigInt, mode: Int): BigInt = {
    if (mode == 0) return a
    def bits(hi: Int, lo: Int): BigInt = (a >> lo) & ((BigInt(1) << (hi - lo + 1)) - 1)
    val blk = bits(16, 12)
    val pos = bits(34, 17)
    val h = if (mode == 2) bits(21, 17) ^ bits(26, 22) ^ bits(31, 27) ^ bits(34, 32) else BigInt(0)
    val keep = a & ~((BigInt(1) << 35) - (BigInt(1) << 12))
    keep | ((blk ^ h) << 30) | (pos << 12)
  }

  /**
    * Verilog of [[stripeModule]]: AXI4 pass-through except AWADDR/ARADDR.
    *
    * Logical address L (bits [34:12] = 4 KB block number b = {pos[17:0], blk[4:0]}):
    *   MODE 1  P[34:30] = blk,        P[29:12] = pos      (rotate by 5)
    *   MODE 2  P[34:30] = blk ^ h(pos), P[29:12] = pos,
    *           h = pos[4:0] ^ pos[9:5] ^ pos[14:10] ^ pos[17:15]
    * i.e. 4 KB block b goes to pseudo channel (b mod 32) [XOR a fold of b/32]
    * at block b/32 of that PC. Bijective; every 4 KB block stays contiguous in
    * one PC, so bursts already split at 4 KB (hc_axi_4k_split in front) stay
    * legal. Each PC's share of a contiguous buffer is itself contiguous, which
    * lets the host gather it into one DMA per PC.
    */
  val stripeVerilog: String = {
    val fwd = Seq(
      ("awid", "ID_WIDTH-1:0"), ("awlen", "7:0"), ("awsize", "2:0"),
      ("awburst", "1:0"), ("awlock", ""), ("awcache", "3:0"), ("awprot", "2:0"), ("awqos", "3:0"),
      ("awregion", "3:0"), ("awvalid", ""), ("wdata", "DATA_WIDTH-1:0"), ("wstrb", "DATA_WIDTH/8-1:0"),
      ("wlast", ""), ("wvalid", ""), ("bready", ""), ("arid", "ID_WIDTH-1:0"),
      ("arlen", "7:0"), ("arsize", "2:0"), ("arburst", "1:0"), ("arlock", ""), ("arcache", "3:0"),
      ("arprot", "2:0"), ("arqos", "3:0"), ("arregion", "3:0"), ("arvalid", ""), ("rready", "")
    )
    val bwd = Seq(
      ("awready", ""), ("wready", ""), ("bid", "ID_WIDTH-1:0"), ("bresp", "1:0"), ("bvalid", ""),
      ("arready", ""), ("rid", "ID_WIDTH-1:0"), ("rdata", "DATA_WIDTH-1:0"), ("rresp", "1:0"), ("rlast", ""),
      ("rvalid", "")
    )
    val rng = (r: String) => if (r.isEmpty) "" else s"[$r] "
    val ports =
      Seq("  input  [ADDR_WIDTH-1:0] s_axi_awaddr", "  output [ADDR_WIDTH-1:0] m_axi_awaddr",
        "  input  [ADDR_WIDTH-1:0] s_axi_araddr", "  output [ADDR_WIDTH-1:0] m_axi_araddr") ++
        fwd.flatMap { case (n, r) => Seq(s"  input  ${rng(r)}s_axi_$n", s"  output ${rng(r)}m_axi_$n") } ++
        bwd.flatMap { case (n, r) => Seq(s"  output ${rng(r)}s_axi_$n", s"  input  ${rng(r)}m_axi_$n") }
    val assigns =
      Seq("  assign m_axi_awaddr = remap(s_axi_awaddr);", "  assign m_axi_araddr = remap(s_axi_araddr);") ++
        fwd.map { case (n, _) => s"  assign m_axi_$n = s_axi_$n;" } ++
        bwd.map { case (n, _) => s"  assign s_axi_$n = m_axi_$n;" }
    s"""// Generated by HardCilk (TclResources.TclV80Common) -- HBM 4 KB striping (HC_HBM_STRIPING).
// The V80 NoC maps an HBM NMU's 32 GB linearly (pseudo channel = offset[34:30]);
// this permutes the address so consecutive 4 KB blocks rotate over all 32 PCs:
//   MODE 1 (stripe4k):     P[34:30] = L[16:12],             P[29:12] = L[34:17]
//   MODE 2 (stripe4k_xor): P[34:30] = L[16:12] ^ h(L[34:17]), P[29:12] = L[34:17]
//                          h = L[21:17] ^ L[26:22] ^ L[31:27] ^ L[34:32]
// Other bits pass. Bijective and 4 KB-block preserving: it must sit AFTER
// hc_axi_4k_split (no burst may cross 4 KB). Pure wiring (+ one XOR level).
// Host copies must apply the same map (memIO_qdma.h, main_sim backdoor).
module $stripeModule #(
  parameter DATA_WIDTH = 256,
  parameter ID_WIDTH = 6,
  parameter ADDR_WIDTH = 64,
  parameter MODE = 1,
  // XORed into address bits [63:32] first: rebases the host path's PL alias
  // window onto HBM (HC_HBM_HOST_PATH=pl); 0 on the PEs' paths.
  parameter [31:0] ADDR_XOR_HI = 0
) (
  (* X_INTERFACE_PARAMETER = "ASSOCIATED_BUSIF S_AXI:M_AXI, ASSOCIATED_RESET aresetn" *)
  input  aclk,
  input  aresetn,
${ports.mkString(",\n")}
);
  localparam [95:0] XOR96 = {32'h0, ADDR_XOR_HI, 32'h0};
  function [ADDR_WIDTH-1:0] remap(input [ADDR_WIDTH-1:0] a0);
    reg [4:0] h;
    reg [ADDR_WIDTH-1:0] a;
    begin
      a = a0 ^ XOR96[ADDR_WIDTH-1:0];
      remap = a;
      if (MODE != 0) begin
        h = (MODE == 2) ? (a[21:17] ^ a[26:22] ^ a[31:27] ^ {2'b00, a[34:32]}) : 5'd0;
        remap[34:30] = a[16:12] ^ h;
        remap[29:12] = a[34:17];
      end
    end
  endfunction
${assigns.mkString("\n")}
endmodule
"""
  }

  /**
    * Verilog of [[shimModule]]: an AXI4 pass-through (wires only) that is an
    * RTL end-point for the NoC's NMU width harvest and, with
    * FORCE_MODIFIABLE=1, ORs 4'b0010 into AWCACHE/ARCACHE.
    */
  def shimVerilog: String = shimOutstanding match {
    case None => shimVerilogBase
    case Some(n) =>
      // HC_SHIM_OUTSTANDING (noc_choke): declare the shim's AXI interfaces with
      // NUM_READ/WRITE_OUTSTANDING n, which the sizing SmartConnect behind it
      // propagates (the module-reference default it otherwise infers is 2, and
      // the SmartConnect upsizer then accepts only 16 reads in flight).
      val attr = s"""  (* X_INTERFACE_PARAMETER = "NUM_READ_OUTSTANDING $n, NUM_WRITE_OUTSTANDING $n" *)\n"""
      val v = shimVerilogBase
      val (sa, ma) = ("  input  [ID_WIDTH-1:0] s_axi_awid,\n", "  output [ID_WIDTH-1:0] m_axi_awid,\n")
      require(v.contains(sa) && v.contains(ma), "[TclV80Common] HC_SHIM_OUTSTANDING: shim template changed")
      v.replace(sa, attr + sa).replace(ma, attr + ma)
  }

  /**
    * `HC_SHIM_OUTSTANDING=<n>` (default unset: generated output unchanged):
    * NUM_READ_OUTSTANDING / NUM_WRITE_OUTSTANDING of [[shimModule]]'s interfaces.
    */
  lazy val shimOutstanding: Option[Int] =
    sys.env.get("HC_SHIM_OUTSTANDING").map(_.trim).filter(_.nonEmpty).map { v =>
      val n = v.toInt
      require(n >= 1 && n <= 256, s"HC_SHIM_OUTSTANDING=$v: expected 1..256")
      n
    }

  private val shimVerilogBase: String = {
    val fwd = Seq(
      ("awid", "ID_WIDTH-1:0"), ("awaddr", "ADDR_WIDTH-1:0"), ("awlen", "7:0"), ("awsize", "2:0"),
      ("awburst", "1:0"), ("awlock", ""), ("awcache", "3:0"), ("awprot", "2:0"), ("awqos", "3:0"),
      ("awregion", "3:0"), ("awvalid", ""), ("wdata", "DATA_WIDTH-1:0"), ("wstrb", "DATA_WIDTH/8-1:0"),
      ("wlast", ""), ("wvalid", ""), ("bready", ""), ("arid", "ID_WIDTH-1:0"), ("araddr", "ADDR_WIDTH-1:0"),
      ("arlen", "7:0"), ("arsize", "2:0"), ("arburst", "1:0"), ("arlock", ""), ("arcache", "3:0"),
      ("arprot", "2:0"), ("arqos", "3:0"), ("arregion", "3:0"), ("arvalid", ""), ("rready", "")
    )
    val bwd = Seq(
      ("awready", ""), ("wready", ""), ("bid", "ID_WIDTH-1:0"), ("bresp", "1:0"), ("bvalid", ""),
      ("arready", ""), ("rid", "ID_WIDTH-1:0"), ("rdata", "DATA_WIDTH-1:0"), ("rresp", "1:0"), ("rlast", ""),
      ("rvalid", "")
    )
    val rng = (r: String) => if (r.isEmpty) "" else s"[$r] "
    val ports =
      fwd.flatMap { case (n, r) => Seq(s"  input  ${rng(r)}s_axi_$n", s"  output ${rng(r)}m_axi_$n") } ++
        bwd.flatMap { case (n, r) => Seq(s"  output ${rng(r)}s_axi_$n", s"  input  ${rng(r)}m_axi_$n") }
    val assigns =
      fwd.map {
        case (n, _) if n == "awcache" || n == "arcache" =>
          s"  assign m_axi_$n = FORCE_MODIFIABLE ? (s_axi_$n | 4'b0010) : s_axi_$n;"
        case (n, _) => s"  assign m_axi_$n = s_axi_$n;"
      } ++ bwd.map { case (n, _) => s"  assign s_axi_$n = m_axi_$n;" }
    s"""// Generated by HardCilk (TclResources.TclV80Common) -- AXI4 pass-through shim.
// - FORCE_MODIFIABLE=1 at a narrow master's own width, ahead of the sizing
//   SmartConnect: sets AxCACHE[1] so the upsizer packs narrow bursts into full
//   256-bit beats (HBM2E rejects narrow bursts with AxLEN > 0).
// - DATA_WIDTH=256 right before an HBM NMU: an RTL end-point for the NoC's NMU
//   data-width harvest, which otherwise hops over the SmartConnect to the far
//   master and programs a wrong NMU width.
module $shimModule #(
  parameter DATA_WIDTH = 256,
  parameter ID_WIDTH = 6,
  parameter ADDR_WIDTH = 64,
  parameter FORCE_MODIFIABLE = 0
) (
  (* X_INTERFACE_PARAMETER = "ASSOCIATED_BUSIF S_AXI:M_AXI, ASSOCIATED_RESET aresetn" *)
  input  aclk,
  input  aresetn,
${ports.mkString(",\n")}
);
${assigns.mkString("\n")}
endmodule
"""
  }

  /** Name of the management-slave guard, see [[axilGuardVerilog]]. */
  val axilGuardModule: String = "hc_axil_guard"

  /** BD cell name of the guard in both V80 flows. */
  val axilGuardCell: String = "hc_mgmt_guard"

  /**
    * Address bits the guard decodes: the 4 KB segment the interconnect assigns to
    * the management slave (or the kernel's own width if that is wider).
    */
  def axilGuardWindowBits(kernelAddrWidth: Int): Int = math.max(12, kernelAddrWidth)

  /** Guard watchdog: a forwarded access unanswered for 2^this cycles gets SLVERR. */
  val axilGuardTimeoutLog2: Int = 16

  /**
    * BD TCL: [[axilGuardModule]] between `from` (an AXI4-Lite master interface
    * pin) and `<name>_0/s_axil_mgmt_hardcilk`. `from` must not be connected to
    * the kernel yet. The address segment to assign afterwards is
    * `[[axilGuardCell]]/S_AXI/reg0` (a module reference's slave is an address
    * end-point; the kernel's own segment is behind it).
    */
  def axilGuardTcl(name: String, from: String, clk: String, rstn: String, kernelAddrWidth: Int, numBlocks: Int): String =
    fill(
      """# HardCilk's management demux never answers an access to a register block it
# does not decode, which wedges the host's PCIe path (fatal completion timeout).
# hc_axil_guard answers those itself with SLVERR and adds a response watchdog.
create_bd_cell -type module -reference @MOD@ @CELL@
set_property -dict [list \
    CONFIG.ADDR_WIDTH {@AW@} \
    CONFIG.S_ADDR_WIDTH {@SAW@} \
    CONFIG.DATA_WIDTH {64} \
    CONFIG.NUM_BLOCKS {@NB@} \
    CONFIG.BLOCK_BITS {6} \
    CONFIG.TIMEOUT_LOG2 {@TO@} \
] [get_bd_cells @CELL@]
connect_bd_intf_net @FROM@ [get_bd_intf_pins @CELL@/S_AXI]
connect_bd_intf_net [get_bd_intf_pins @CELL@/M_AXI] [get_bd_intf_pins @NAME@_0/s_axil_mgmt_hardcilk]
connect_bd_net @CLK@ [get_bd_pins @CELL@/aclk]
connect_bd_net @RSTN@ [get_bd_pins @CELL@/aresetn]
set hc_guard_proto [get_property CONFIG.PROTOCOL [get_bd_intf_pins @CELL@/S_AXI]]
if {$hc_guard_proto ne "" && $hc_guard_proto ne "AXI4LITE"} {
    error "HardCilk: @CELL@/S_AXI was inferred as $hc_guard_proto, not AXI4-Lite"
}
""",
      "MOD" -> axilGuardModule,
      "CELL" -> axilGuardCell,
      "AW" -> kernelAddrWidth.toString,
      "SAW" -> axilGuardWindowBits(kernelAddrWidth).toString,
      "NB" -> numBlocks.toString,
      "TO" -> axilGuardTimeoutLog2.toString,
      "FROM" -> from,
      "NAME" -> name,
      "CLK" -> clk,
      "RSTN" -> rstn
    )

  /**
    * Verilog of [[axilGuardModule]] (unit-tested in Questa: every block of the
    * 4 KB window, random backpressure, the watchdog and late responses).
    */
  val axilGuardVerilog: String =
    """// AXI4-Lite guard for the HardCilk management slave (generated by HardCilk,
// TclResources.TclV80Common).
//
// HardCilk's management demux (s_axil_mgmt_hardcilk) decodes addr >> BLOCK_BITS
// into NUM_BLOCKS register blocks and never answers an access to any other
// block index. On the V80 such an access (e.g. a host read one block past the
// last one) stalls the CPM5 AXI bridge forever; the PCIe completion timeout that
// follows is fatal and resets the server. This stage sits directly in front of
// s_axil_mgmt_hardcilk and:
//   - answers an access to an undecoded block itself with SLVERR
//     (RDATA = 64'hBADA_DD00_0000_0000 | addr), without forwarding it;
//   - forwards every other access unchanged (one read and one write at a time;
//     management traffic is low-rate);
//   - (TIMEOUT_LOG2 > 0) answers SLVERR (RDATA = 64'hBADA_DD01_0000_0000 | addr)
//     if a forwarded access gets no response within 2^TIMEOUT_LOG2 cycles, and
//     from then on answers every access SLVERR (RDATA = 64'hBADA_DD02_...)
//     without forwarding it, until aresetn. The late response, if it ever comes,
//     is accepted and dropped; the forwarded request stays asserted until the
//     slave takes it, as AXI requires.
// S_ADDR_WIDTH is the width of the address window the interconnect decodes for
// this slave (12: the 4 KB segment); ADDR_WIDTH is the slave's own address
// width (<= S_ADDR_WIDTH). The guard decodes all S_ADDR_WIDTH bits, so the
// aliases of the register blocks above 2^ADDR_WIDTH in the window are refused
// too, and forwards the low ADDR_WIDTH bits.
module hc_axil_guard #(
  parameter ADDR_WIDTH = 12,
  parameter S_ADDR_WIDTH = 12,
  parameter DATA_WIDTH = 64,
  parameter NUM_BLOCKS = 1,
  parameter BLOCK_BITS = 6,
  parameter TIMEOUT_LOG2 = 16
) (
  (* X_INTERFACE_PARAMETER = "ASSOCIATED_BUSIF S_AXI:M_AXI, ASSOCIATED_RESET aresetn" *)
  input  aclk,
  input  aresetn,
  // slave side (from the interconnect)
  input  [S_ADDR_WIDTH-1:0] s_axi_awaddr,
  input  [2:0] s_axi_awprot,
  input  s_axi_awvalid,
  output s_axi_awready,
  input  [DATA_WIDTH-1:0] s_axi_wdata,
  input  [DATA_WIDTH/8-1:0] s_axi_wstrb,
  input  s_axi_wvalid,
  output s_axi_wready,
  output [1:0] s_axi_bresp,
  output s_axi_bvalid,
  input  s_axi_bready,
  input  [S_ADDR_WIDTH-1:0] s_axi_araddr,
  input  [2:0] s_axi_arprot,
  input  s_axi_arvalid,
  output s_axi_arready,
  output [DATA_WIDTH-1:0] s_axi_rdata,
  output [1:0] s_axi_rresp,
  output s_axi_rvalid,
  input  s_axi_rready,
  // master side (to s_axil_mgmt_hardcilk)
  output [ADDR_WIDTH-1:0] m_axi_awaddr,
  output [2:0] m_axi_awprot,
  output m_axi_awvalid,
  input  m_axi_awready,
  output [DATA_WIDTH-1:0] m_axi_wdata,
  output [DATA_WIDTH/8-1:0] m_axi_wstrb,
  output m_axi_wvalid,
  input  m_axi_wready,
  input  [1:0] m_axi_bresp,
  input  m_axi_bvalid,
  output m_axi_bready,
  output [ADDR_WIDTH-1:0] m_axi_araddr,
  output [2:0] m_axi_arprot,
  output m_axi_arvalid,
  input  m_axi_arready,
  input  [DATA_WIDTH-1:0] m_axi_rdata,
  input  [1:0] m_axi_rresp,
  input  m_axi_rvalid,
  output m_axi_rready
);

  localparam [1:0] SLVERR = 2'b10;
  localparam [63:0] TAG_UNDECODED = 64'hBADA_DD00_0000_0000;
  localparam [63:0] TAG_TIMEOUT   = 64'hBADA_DD01_0000_0000;
  localparam [63:0] TAG_BLOCKED   = 64'hBADA_DD02_0000_0000;
  localparam TW = (TIMEOUT_LOG2 > 0) ? TIMEOUT_LOG2 : 1;

  function decoded(input [S_ADDR_WIDTH-1:0] a);
    decoded = ((a >> BLOCK_BITS) < NUM_BLOCKS);
  endfunction

  function [DATA_WIDTH-1:0] err_data(input [63:0] tag, input [S_ADDR_WIDTH-1:0] a);
    reg [63:0] v;
    begin
      v = tag | {{(64-S_ADDR_WIDTH){1'b0}}, a};
      err_data = v[DATA_WIDTH-1:0];
    end
  endfunction

  // Set when a forwarded access timed out; cleared only by aresetn.
  reg dead;

  // ----------------------------------------------------------------- write ---
  reg                    aw_have, w_have, w_decided;
  reg [S_ADDR_WIDTH-1:0] aw_addr;
  reg [2:0]              aw_prot;
  reg [DATA_WIDTH-1:0]   w_data;
  reg [DATA_WIDTH/8-1:0] w_strb;
  // master side, held stable while their valids are up
  reg                    m_aw_pend, m_w_pend, m_b_wait, w_fwd;
  reg [S_ADDR_WIDTH-1:0] m_awaddr_r;
  reg [2:0]              m_awprot_r;
  reg [DATA_WIDTH-1:0]   m_wdata_r;
  reg [DATA_WIDTH/8-1:0] m_wstrb_r;
  reg [TW-1:0]           w_timer;
  reg                    s_bvalid_r;
  reg [1:0]              s_bresp_r;

  assign s_axi_awready = aresetn && !aw_have;
  assign s_axi_wready  = aresetn && !w_have;
  assign s_axi_bvalid  = s_bvalid_r;
  assign s_axi_bresp   = s_bresp_r;
  assign m_axi_awvalid = m_aw_pend;
  assign m_axi_awaddr  = m_awaddr_r[ADDR_WIDTH-1:0];
  assign m_axi_awprot  = m_awprot_r;
  assign m_axi_wvalid  = m_w_pend;
  assign m_axi_wdata   = m_wdata_r;
  assign m_axi_wstrb   = m_wstrb_r;
  assign m_axi_bready  = m_b_wait;

  wire w_expired = (TIMEOUT_LOG2 > 0) && (&w_timer);

  // ------------------------------------------------------------------ read ---
  reg                    r_busy;
  reg                    m_ar_pend, m_r_wait, r_fwd;
  reg [S_ADDR_WIDTH-1:0] m_araddr_r;  // full address, for the timeout tag
  reg [2:0]              m_arprot_r;
  reg [TW-1:0]           r_timer;
  reg                    s_rvalid_r;
  reg [1:0]              s_rresp_r;
  reg [DATA_WIDTH-1:0]   s_rdata_r;

  assign s_axi_arready = aresetn && !r_busy;
  assign s_axi_rvalid  = s_rvalid_r;
  assign s_axi_rresp   = s_rresp_r;
  assign s_axi_rdata   = s_rdata_r;
  assign m_axi_arvalid = m_ar_pend;
  assign m_axi_araddr  = m_araddr_r[ADDR_WIDTH-1:0];
  assign m_axi_arprot  = m_arprot_r;
  assign m_axi_rready  = m_r_wait;

  wire r_expired = (TIMEOUT_LOG2 > 0) && (&r_timer);

  always @(posedge aclk) begin
    if (!aresetn) begin
      dead <= 1'b0;
      aw_have <= 1'b0; w_have <= 1'b0; w_decided <= 1'b0;
      m_aw_pend <= 1'b0; m_w_pend <= 1'b0; m_b_wait <= 1'b0; w_fwd <= 1'b0;
      w_timer <= {TW{1'b0}};
      s_bvalid_r <= 1'b0; s_bresp_r <= 2'b00;
      r_busy <= 1'b0; m_ar_pend <= 1'b0; m_r_wait <= 1'b0; r_fwd <= 1'b0;
      r_timer <= {TW{1'b0}};
      s_rvalid_r <= 1'b0; s_rresp_r <= 2'b00; s_rdata_r <= {DATA_WIDTH{1'b0}};
    end else begin
      // ---- write: collect AW and W ----
      if (s_axi_awvalid && s_axi_awready) begin
        aw_have <= 1'b1; aw_addr <= s_axi_awaddr; aw_prot <= s_axi_awprot;
      end
      if (s_axi_wvalid && s_axi_wready) begin
        w_have <= 1'b1; w_data <= s_axi_wdata; w_strb <= s_axi_wstrb;
      end
      // ---- write: decide (refuse or forward) ----
      if (aw_have && w_have && !w_decided) begin
        w_decided <= 1'b1;
        if (dead || !decoded(aw_addr)) begin
          s_bvalid_r <= 1'b1; s_bresp_r <= SLVERR;
        end else begin
          m_aw_pend <= 1'b1; m_w_pend <= 1'b1; m_b_wait <= 1'b1; w_fwd <= 1'b1;
          m_awaddr_r <= aw_addr; m_awprot_r <= aw_prot;
          m_wdata_r <= w_data; m_wstrb_r <= w_strb;
          w_timer <= {TW{1'b0}};
        end
      end
      // ---- write: master side ----
      if (m_aw_pend && m_axi_awready) m_aw_pend <= 1'b0;
      if (m_w_pend && m_axi_wready) m_w_pend <= 1'b0;
      if (m_b_wait && m_axi_bvalid) begin
        m_b_wait <= 1'b0;
        if (w_fwd) begin
          w_fwd <= 1'b0; s_bvalid_r <= 1'b1; s_bresp_r <= m_axi_bresp;
        end
      end else if (w_fwd) begin
        if (w_expired) begin
          w_fwd <= 1'b0; dead <= 1'b1; s_bvalid_r <= 1'b1; s_bresp_r <= SLVERR;
        end else begin
          w_timer <= w_timer + 1'b1;
        end
      end
      // ---- write: response to the interconnect ----
      if (s_bvalid_r && s_axi_bready) begin
        s_bvalid_r <= 1'b0; aw_have <= 1'b0; w_have <= 1'b0; w_decided <= 1'b0;
      end

      // ---- read: accept, refuse or forward ----
      if (s_axi_arvalid && s_axi_arready) begin
        r_busy <= 1'b1;
        if (dead) begin
          s_rvalid_r <= 1'b1; s_rresp_r <= SLVERR; s_rdata_r <= err_data(TAG_BLOCKED, s_axi_araddr);
        end else if (!decoded(s_axi_araddr)) begin
          s_rvalid_r <= 1'b1; s_rresp_r <= SLVERR; s_rdata_r <= err_data(TAG_UNDECODED, s_axi_araddr);
        end else begin
          m_ar_pend <= 1'b1; m_r_wait <= 1'b1; r_fwd <= 1'b1;
          m_araddr_r <= s_axi_araddr; m_arprot_r <= s_axi_arprot;
          r_timer <= {TW{1'b0}};
        end
      end
      // ---- read: master side ----
      if (m_ar_pend && m_axi_arready) m_ar_pend <= 1'b0;
      if (m_r_wait && m_axi_rvalid) begin
        m_r_wait <= 1'b0;
        if (r_fwd) begin
          r_fwd <= 1'b0; s_rvalid_r <= 1'b1; s_rresp_r <= m_axi_rresp; s_rdata_r <= m_axi_rdata;
        end
      end else if (r_fwd) begin
        if (r_expired) begin
          r_fwd <= 1'b0; dead <= 1'b1;
          s_rvalid_r <= 1'b1; s_rresp_r <= SLVERR; s_rdata_r <= err_data(TAG_TIMEOUT, m_araddr_r);
        end else begin
          r_timer <= r_timer + 1'b1;
        end
      end
      // ---- read: response to the interconnect ----
      if (s_rvalid_r && s_axi_rready) begin
        s_rvalid_r <= 1'b0; r_busy <= 1'b0;
      end
    end
  end

endmodule
"""

  private val implTemplate: String =
    """# Generated by HardCilk -- Alveo V80 synthesis / implementation / device image.
# Usage: vivado -mode batch -source @NAME@_v80_impl.tcl -tclargs <project_dir> <output_dir>
lassign $argv proj_dir out_dir
set name @NAME@
open_project [file join $proj_dir prj.xpr]
file mkdir $out_dir [file join $out_dir reports]

set jobs 32
if {[info exists ::env(HC_JOBS)]} { set jobs $::env(HC_JOBS) }
puts "HardCilk: running with $jobs job(s)"

generate_target all [get_files top.bd]
@NMU_CHECK@
@LUTRAM@

# Resume after a killed build: out-of-context IP runs left incomplete (killed
# mid-way, e.g. by the OOM killer) can stay marked as running; reset them so
# launch_runs reruns them. Completed IP runs are kept.
foreach hc_r [get_runs -quiet -filter {IS_SYNTHESIS && NAME != synth_1}] {
    if {[get_property PROGRESS $hc_r] ne "100%"} { reset_run $hc_r }
}
if {[get_property PROGRESS [get_runs synth_1]] ne "100%"} {
    reset_run synth_1
    launch_runs synth_1 -jobs $jobs
    wait_on_run synth_1
}
if {[get_property PROGRESS [get_runs synth_1]] ne "100%"} {
    error "HardCilk: synth_1 failed -- see [get_property DIRECTORY [get_runs synth_1]]/runme.log"
}
puts "HardCilk: synth_1 finished"
@LUTRAM_REPORT@
# A previous (failed or killed) implementation has to be reset before relaunching.
if {[get_property PROGRESS [get_runs impl_1]] ne "0%" || [get_property NEEDS_REFRESH [get_runs impl_1]]} {
    reset_run impl_1
}
launch_runs impl_1 -to_step write_device_image -jobs $jobs
wait_on_run impl_1
if {[get_property PROGRESS [get_runs impl_1]] ne "100%"} {
    error "HardCilk: impl_1 failed -- see [get_property DIRECTORY [get_runs impl_1]]/runme.log"
}

set impl_dir [get_property DIRECTORY [get_runs impl_1]]
set pdi [glob -nocomplain [file join $impl_dir top_wrapper.pdi]]
if {$pdi eq ""} { error "HardCilk: impl_1 produced no top_wrapper.pdi" }
file copy -force $pdi [file join $out_dir ${name}_design.pdi]
file copy -force [file join $impl_dir top_wrapper_routed.dcp] [file join $out_dir ${name}_routed.dcp]

# Hardware platform for the AMC BSP. Written from the implemented project:
# right after generate_target (where AVED's build_design.tcl writes it) the
# hardware handoff is silently dropped from the XSA when a module reference is
# in the BD (CRITICAL WARNING Project 1-1924, a 1.5 KB XSA without .hwh).
set xsa [file join $out_dir $name.xsa]
write_hw_platform -force -fixed -minimal $xsa
if {[file size $xsa] < 100000} { error "HardCilk: $xsa has no hardware handoff ([file size $xsa] bytes)" }

open_run impl_1
set r [file join $out_dir reports]
report_timing_summary -max_paths 20 -report_unconstrained -file [file join $r timing_summary.rpt]
report_utilization                  -file [file join $r utilization.rpt]
report_utilization -hierarchical -hierarchical_depth 4 -file [file join $r utilization_hierarchical.rpt]
report_utilization -slr             -file [file join $r utilization_slr.rpt]
report_power                        -file [file join $r power.rpt]
report_drc                          -file [file join $r drc_routed.rpt]
report_clock_utilization            -file [file join $r clock_utilization.rpt]
catch { report_noc_qos -file [file join $r noc_qos.rpt] }

set wns [get_property SLACK [get_timing_paths -max_paths 1 -nworst 1 -setup]]
set whs [get_property SLACK [get_timing_paths -max_paths 1 -nworst 1 -hold]]
puts "HardCilk: WNS = $wns ns, WHS = $whs ns"
if {$wns < 0 || $whs < 0} {
    puts "HardCilk: WARNING -- design does NOT meet timing; the device image is still written."
} else {
    puts "HardCilk: timing met"
}
puts "HardCilk: device image written"
"""
}
