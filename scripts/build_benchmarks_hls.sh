#!/usr/bin/env bash
# =============================================================================
# build_benchmarks_hls.sh — HardCilk HLS super-script
#
# Synthesises all mfpga processing-element kernels at 250 MHz for Alveo U55C
# using build_kernels.sh as the per-benchmark driver.
#
# Usage:
#   ./build_benchmarks_hls.sh [options]
#
# Options:
#   -s  Path to hls-processing-elements root
#           (default: /home/shahawy/mfpga_ws/HardCilk/hls-processing-elements)
#   -o  Output root directory
#           (default: /home/shahawy/mfpga_ws/HardCilk/hls-kernel-output)
#   -f  Clock frequency in MHz          (default: 250)
#   -b  Comma-separated list of benchmarks to build, or "all"
#           (default: all)
#           Valid names: graphRandomWalk, pageRank, triangleCount
#   -D  Debug mode: pass through to build_kernels.sh to keep all
#           intermediate build state (default: off — delete intermediates)
#   -h  Show this help
#
# Example — build only two benchmarks:
#   ./build_benchmarks_hls.sh -b graphRandomWalk,pageRank
#
# Output layout mirrors hls-processing-elements/mfpga/:
#   hls-kernel-output/
#   ├── graphRandomWalk/
#   │   ├── walker/          ← synthesised Verilog
#   │   └── walk_gen/
#   ├── pageRank/
#   │   ├── page_rank_map/
#   │   └── vertex_map/
#   └── triangleCount/
#       ├── triangle/
#       └── vertex_map/
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

# ── Defaults ──────────────────────────────────────────────────────────────────
WORKSPACE="../"
DEFAULT_PE_ROOT="${WORKSPACE}/hls-processing-elements"
DEFAULT_OUT_ROOT="${WORKSPACE}/hls-kernel-output"
DEFAULT_FREQ=250
PART="xcu55c-fsvh2892-2L-e"   # Alveo U55C

PE_ROOT="$DEFAULT_PE_ROOT"
OUT_ROOT="$DEFAULT_OUT_ROOT"
FREQ_MHZ="$DEFAULT_FREQ"
BUILD_FILTER="all"
DEBUG=false                # passed through to build_kernels.sh; off by default

# ── Benchmark → kernel mapping ────────────────────────────────────────────────
# Associative array:  benchmark_subdir  →  "kernel1 kernel2 ..."
declare -A BENCHMARK_KERNELS=(
    [graphRandomWalk]="walker walk_gen"
    [pageRank]="page_rank_map vertex_map"
    [triangleCount]="triangle vertex_map"
)

# Ordered list so the build sequence is deterministic
BENCHMARK_ORDER=(graphRandomWalk pageRank triangleCount)

# ── Usage ─────────────────────────────────────────────────────────────────────
usage() {
    sed -n '3,27p' "$0" | sed 's/^# \{0,2\}//'
    exit 1
}

# ── Argument parsing ──────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
    case "$1" in
        -s) PE_ROOT="$2";      shift 2 ;;
        -o) OUT_ROOT="$2";     shift 2 ;;
        -f) FREQ_MHZ="$2";    shift 2 ;;
        -b) BUILD_FILTER="$2"; shift 2 ;;
        -D) DEBUG=true;        shift   ;;
        -h|--help) usage ;;
        *) die "Unknown option: $1. Run with -h for help." ;;
    esac
done

# ── Validate environment ──────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_SCRIPT="${SCRIPT_DIR}/build_hls_kernel/build_kernels.sh"

[[ -f "$BUILD_SCRIPT" ]] \
    || die "build_kernels.sh not found at: ${BUILD_SCRIPT}
       Both scripts must live in the same directory."

command -v vitis_hls &>/dev/null \
    || die "'vitis_hls' not found on PATH. Source the Vitis 24.04 settings64.sh first."

[[ "$FREQ_MHZ" =~ ^[0-9]+(\.[0-9]+)?$ ]] \
    || die "Frequency must be a positive number. Got: $FREQ_MHZ"

PE_MFPGA_ROOT="${PE_ROOT}/mfpga"
[[ -d "$PE_MFPGA_ROOT" ]] \
    || die "Processing-elements mfpga directory not found: ${PE_MFPGA_ROOT}"

# ── Resolve which benchmarks to build ────────────────────────────────────────
if [[ "$BUILD_FILTER" == "all" ]]; then
    TARGETS=("${BENCHMARK_ORDER[@]}")
else
    IFS=',' read -ra TARGETS <<< "$BUILD_FILTER"
    for t in "${TARGETS[@]}"; do
        [[ -v BENCHMARK_KERNELS["$t"] ]] \
            || die "Unknown benchmark '${t}'. Valid: ${BENCHMARK_ORDER[*]}"
    done
fi

# ── Print build plan ──────────────────────────────────────────────────────────
echo -e "${BOLD}╔══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║         HardCilk HLS Benchmark Build — Alveo U55C           ║${NC}"
echo -e "${BOLD}╚══════════════════════════════════════════════════════════════╝${NC}"
echo
info "PE source root   : $PE_MFPGA_ROOT"
info "Verilog out root : $OUT_ROOT"
info "FPGA part        : $PART"
info "Clock target     : ${FREQ_MHZ} MHz"
info "Benchmarks       : ${TARGETS[*]}"
info "Driver script    : $BUILD_SCRIPT"
echo

mkdir -p "$OUT_ROOT"

# ── Per-benchmark loop ────────────────────────────────────────────────────────
PASS=(); FAIL=()

for BENCH in "${TARGETS[@]}"; do
    echo -e "${BOLD}══════════════════════════════════════════════════════════════${NC}"
    echo -e "${BOLD}  Benchmark: ${CYAN}${BENCH}${NC}"
    echo -e "${BOLD}══════════════════════════════════════════════════════════════${NC}"

    SRC_DIR="${PE_MFPGA_ROOT}/${BENCH}"
    VERILOG_OUT="${OUT_ROOT}/${BENCH}"
    read -ra KERNELS <<< "${BENCHMARK_KERNELS[$BENCH]}"

    # Sanity-check that the source directory exists
    if [[ ! -d "$SRC_DIR" ]]; then
        error "Source directory not found for '${BENCH}': ${SRC_DIR}"
        FAIL+=("$BENCH")
        echo
        continue
    fi

    info "Source    : $SRC_DIR"
    info "Output    : $VERILOG_OUT"
    info "Kernels   : ${KERNELS[*]}"
    info "Debug     : $DEBUG"
    echo

    DEBUG_FLAG=(); [[ "$DEBUG" == true ]] && DEBUG_FLAG=("-D")

    if bash "$BUILD_SCRIPT" \
            -d "$SRC_DIR"       \
            -f "$FREQ_MHZ"      \
            -p "$PART"          \
            -o "$VERILOG_OUT"   \
            "${DEBUG_FLAG[@]}"  \
            -k "${KERNELS[@]}"; then
        success "Benchmark '${BENCH}' completed."
        PASS+=("$BENCH")
    else
        error "Benchmark '${BENCH}' had one or more failures."
        FAIL+=("$BENCH")
    fi
    echo
done

# ── Global summary ────────────────────────────────────────────────────────────
echo -e "${BOLD}╔══════════════════════════════ GLOBAL SUMMARY ════════════════════════════╗${NC}"
printf   "  %-16s %s\n" "Total benchmarks:" "${#TARGETS[@]}"
printf   "  %-16s %s\n" "Passed:"           "${#PASS[@]}  ${PASS[*]:-}"
printf   "  %-16s %s\n" "Failed:"           "${#FAIL[@]}  ${FAIL[*]:-}"
echo -e "${BOLD}╚══════════════════════════════════════════════════════════════════════════╝${NC}"
echo
if [[ ${#PASS[@]} -gt 0 ]]; then
    info "Synthesised Verilog is under: ${OUT_ROOT}/"
    for b in "${PASS[@]}"; do
        read -ra KS <<< "${BENCHMARK_KERNELS[$b]}"
        for k in "${KS[@]}"; do
            echo -e "      ${GREEN}▸${NC} ${OUT_ROOT}/${b}/${k}/"
        done
    done
fi
echo

[[ ${#FAIL[@]} -gt 0 ]] && exit 1 || exit 0