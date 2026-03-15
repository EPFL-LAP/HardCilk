#!/usr/bin/env bash
# =============================================================================
# build_kernels.sh — Bash wrapper for Vitis HLS 24.04 multi-kernel builds
#
# Usage:
#   ./build_kernels.sh -d <src_dir> -f <MHz> -p <part> [-o <out_dir>] [-D] -k <kernel1> [<kernel2> ...]
#
# Options:
#   -d  Source directory containing .h and .cpp files   (required)
#   -f  Target clock frequency in MHz                   (required)
#   -p  FPGA part string, e.g. xcu250-figd2104-2L-e    (required)
#   -o  Output directory for synthesised Verilog        (optional, default: ./verilog_out)
#   -D  Debug mode: keep all intermediate build state   (optional, default: off)
#   -k  One or more kernel (top-function) names         (required, must be last)
#
# Example (clean build):
#   ./build_kernels.sh -d ./src -f 300 -p xcu250-figd2104-2L-e -o ./rtl -k vadd vmul
# Example (debug — keep all intermediates):
#   ./build_kernels.sh -d ./src -f 300 -p xcu250-figd2104-2L-e -o ./rtl -D -k vadd vmul
#
# This script instantiates hls_kernel.tcl for each kernel and invokes vitis_hls.
# Synthesised Verilog for each kernel is copied to <out_dir>/<kernel_name>/
# Without -D: entire ./hls_projects/<kernel>/ tree is deleted after synthesis.
# With    -D: full project tree (Tcl, logs, HLS project) is kept for inspection.
# =============================================================================

set -euo pipefail

# ── Colour helpers ────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

info()    { echo -e "${CYAN}[INFO]${NC}  $*"; }
success() { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*" >&2; }
die()     { error "$*"; exit 1; }

# ── Usage ─────────────────────────────────────────────────────────────────────
usage() {
    sed -n '3,14p' "$0" | sed 's/^# \{0,2\}//'
    exit 1
}

# ── Argument parsing ──────────────────────────────────────────────────────────
SRC_DIR=""
FREQ_MHZ=""
PART=""
VERILOG_OUT="$(pwd)/verilog_out"   # default; overridden by -o
DEBUG=false                        # default: delete all intermediates after build
KERNELS=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        -d) SRC_DIR="$2";      shift 2 ;;
        -f) FREQ_MHZ="$2";     shift 2 ;;
        -p) PART="$2";         shift 2 ;;
        -o) VERILOG_OUT="$2";  shift 2 ;;
        -D) DEBUG=true;        shift   ;;
        -k) shift; KERNELS=("$@"); break ;;
        -h|--help) usage ;;
        *) die "Unknown option: $1. Run with -h for help." ;;
    esac
done

# ── Validation ────────────────────────────────────────────────────────────────
[[ -z "$SRC_DIR"  ]] && die "Source directory (-d) is required."
[[ -z "$FREQ_MHZ" ]] && die "Clock frequency (-f) is required."
[[ -z "$PART"     ]] && die "FPGA part (-p) is required."
[[ ${#KERNELS[@]} -eq 0 ]] && die "At least one kernel name (-k) is required."
[[ -d "$SRC_DIR" ]] || die "Source directory not found: $SRC_DIR"
[[ "$FREQ_MHZ" =~ ^[0-9]+(\.[0-9]+)?$ ]] || die "Frequency must be a positive number. Got: $FREQ_MHZ"

command -v vitis_hls &>/dev/null \
    || die "'vitis_hls' not found on PATH. Source the Vitis 24.04 settings64.sh first."

# ── Resolve paths & derived values ───────────────────────────────────────────
SRC_DIR="$(realpath "$SRC_DIR")"
PROJECTS_ROOT="$(pwd)/hls_projects"
CLOCK_PERIOD_NS="$(awk "BEGIN{printf \"%.4f\", 1000/$FREQ_MHZ}")"

# The Tcl template must live alongside this script
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TCL_TEMPLATE="${SCRIPT_DIR}/hls_kernel.tcl"
[[ -f "$TCL_TEMPLATE" ]] || die "Tcl template not found: ${TCL_TEMPLATE}
       Expected it next to this script at: ${TCL_TEMPLATE}"

# ── Collect source files ──────────────────────────────────────────────────────
mapfile -t CPP_FILES < <(find "$SRC_DIR" -maxdepth 1 -name "*.cpp" | sort)
mapfile -t H_FILES   < <(find "$SRC_DIR" -maxdepth 1 -name "*.h"   | sort)

[[ ${#CPP_FILES[@]} -eq 0 ]] && die "No .cpp files found in: $SRC_DIR"

# Build a Tcl-safe list string of all source files:  {/abs/path/a.cpp} {/abs/path/b.h} ...
TCL_SOURCES=""
for f in "${CPP_FILES[@]}" "${H_FILES[@]}"; do
    TCL_SOURCES+=" {${f}}"
done
TCL_SOURCES="${TCL_SOURCES# }"   # strip leading space

# ── Print build plan ──────────────────────────────────────────────────────────
info "Source directory : $SRC_DIR"
info "Clock target     : ${FREQ_MHZ} MHz  (period = ${CLOCK_PERIOD_NS} ns)"
info "FPGA part        : $PART"
info "Verilog output   : $VERILOG_OUT"
info "CPP files found  : ${#CPP_FILES[@]}"
info "Header files     : ${#H_FILES[@]}"
info "Kernels to build : ${KERNELS[*]}"
info "Tcl template     : $TCL_TEMPLATE"
info "Debug mode       : $DEBUG  $([ "$DEBUG" = true ] && echo "(intermediates kept)" || echo "(intermediates deleted after build)")"
echo

mkdir -p "$PROJECTS_ROOT"
mkdir -p "$VERILOG_OUT"

# ── Per-kernel build loop ─────────────────────────────────────────────────────
PASS=(); FAIL=()

for KERNEL in "${KERNELS[@]}"; do
    echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    info "Building kernel: ${BOLD}${KERNEL}${NC}"

    PROJ_DIR="${PROJECTS_ROOT}/${KERNEL}"
    TCL_OUT="${PROJ_DIR}/run_hls.tcl"
    LOG_FILE="${PROJ_DIR}/vitis_hls.log"

    mkdir -p "$PROJ_DIR"

    # Stamp the template — replace every placeholder with its runtime value
    sed \
        -e "s|@@KERNEL@@|${KERNEL}|g"                    \
        -e "s|@@PART@@|${PART}|g"                        \
        -e "s|@@CLOCK_PERIOD_NS@@|${CLOCK_PERIOD_NS}|g"  \
        -e "s|@@FREQ_MHZ@@|${FREQ_MHZ}|g"                \
        -e "s|@@SOURCES@@|${TCL_SOURCES}|g"              \
        "$TCL_TEMPLATE" > "$TCL_OUT"

    info "Tcl script written : $TCL_OUT"
    info "Launching vitis_hls …"

    if (cd "$PROJ_DIR" && vitis_hls -f run_hls.tcl 2>&1 | tee vitis_hls.log); then
        success "Kernel '${KERNEL}' synthesised successfully."
        success "Report : ${PROJ_DIR}/${KERNEL}_proj/solution1/syn/report/${KERNEL}_csynth.rpt"

        # ── Copy synthesised Verilog to the output directory ──────────────────
        RTL_SRC="${PROJ_DIR}/${KERNEL}_proj/solution1/syn/verilog"
        RTL_DEST="${VERILOG_OUT}/${KERNEL}"
        if [[ -d "$RTL_SRC" ]]; then
            mkdir -p "$RTL_DEST"
            cp -r "${RTL_SRC}/." "$RTL_DEST/"
            success "Verilog copied to : $RTL_DEST"
        else
            warn "Verilog directory not found at expected path: $RTL_SRC"
            warn "Check the synthesis report — RTL may be under a different sub-path."
        fi

        # ── Clean up intermediate state (unless debug mode is on) ──────────
        if [[ "$DEBUG" == false ]]; then
            info "Removing intermediate build directory: ${PROJ_DIR}"
            rm -rf "${PROJ_DIR}"
            success "Intermediate state deleted."
        else
            warn "Debug mode ON — keeping all intermediates in: ${PROJ_DIR}"
        fi

        PASS+=("$KERNEL")
    else
        error "Kernel '${KERNEL}' FAILED. See log: ${LOG_FILE}"
        FAIL+=("$KERNEL")
    fi
    echo
done

# ── Summary ───────────────────────────────────────────────────────────────────
echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━ BUILD SUMMARY ━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "  Total   : ${#KERNELS[@]}"
echo -e "  ${GREEN}Passed${NC}  : ${#PASS[@]}  ${PASS[*]:-}"
echo -e "  ${RED}Failed${NC}  : ${#FAIL[@]}  ${FAIL[*]:-}"
echo

[[ ${#FAIL[@]} -gt 0 ]] && exit 1 || exit 0