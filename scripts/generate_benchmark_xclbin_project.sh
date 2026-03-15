#!/bin/bash
set -euo pipefail

VALID_BENCHMARKS=("graphRandomWalk" "pageRank" "triangleCount")

usage() {
    echo "Usage: $0 <benchmarkName>"
    echo "  benchmarkName: one of ${VALID_BENCHMARKS[*]}"
    exit 1
}

# --- Argument validation ---
if [[ $# -ne 1 ]]; then
    usage
fi

BENCHMARK="$1"
VALID=false
for b in "${VALID_BENCHMARKS[@]}"; do
    [[ "$b" == "$BENCHMARK" ]] && VALID=true && break
done
if [[ "$VALID" == false ]]; then
    echo "Error: '$BENCHMARK' is not a valid benchmark. Choose from: ${VALID_BENCHMARKS[*]}"
    exit 1
fi

# --- Paths ---
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HARDCILK_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

HARDCILK_OUTPUT_DIR="$HARDCILK_ROOT/HardCilk-output/${BENCHMARK}_hardcilk_output"
XRT_PROJECTS_DIR="$HARDCILK_ROOT/xrt-projects/$BENCHMARK"
XCLBIN_WORKSPACE_DIR="$HARDCILK_ROOT/xclbin-workspace/$BENCHMARK"

# --- Check HardCilk output exists ---
if [[ ! -d "$HARDCILK_OUTPUT_DIR" ]]; then
    echo "Error: HardCilk output directory not found: $HARDCILK_OUTPUT_DIR"
    exit 1
fi

RTL_DIR="$HARDCILK_OUTPUT_DIR/rtl"
SOFTWARE_DIR="$HARDCILK_OUTPUT_DIR/software"

# --- Check rtl and software subfolders exist and are non-empty ---
if [[ ! -d "$RTL_DIR" ]]; then
    echo "Error: rtl subfolder not found under $HARDCILK_OUTPUT_DIR"
    exit 1
fi
if [[ ! -d "$SOFTWARE_DIR" ]]; then
    echo "Error: software subfolder not found under $HARDCILK_OUTPUT_DIR"
    exit 1
fi

if [[ -z "$(ls -A "$RTL_DIR")" ]]; then
    echo "Error: rtl subfolder is empty: $RTL_DIR"
    exit 1
fi
if [[ -z "$(ls -A "$SOFTWARE_DIR")" ]]; then
    echo "Error: software subfolder is empty: $SOFTWARE_DIR"
    exit 1
fi

# --- Check xrt-projects source exists ---
if [[ ! -d "$XRT_PROJECTS_DIR" ]]; then
    echo "Error: xrt-projects directory not found: $XRT_PROJECTS_DIR"
    exit 1
fi

# --- Step 1: Copy xrt-projects/<benchmark> to xclbin-workspace, excluding *-arxiv folders ---
echo "Creating workspace at $XCLBIN_WORKSPACE_DIR ..."
mkdir -p "$XCLBIN_WORKSPACE_DIR"

rsync -a --exclude='*-arxiv' "$XRT_PROJECTS_DIR/" "$XCLBIN_WORKSPACE_DIR/"

# --- Step 2: Copy only files (not subfolders) from rtl/ into xclbin-workspace/<benchmark>/IP/ ---
echo "Copying RTL files into $XCLBIN_WORKSPACE_DIR/IP/ ..."
mkdir -p "$XCLBIN_WORKSPACE_DIR/src/IP"

find "$RTL_DIR" -maxdepth 1 -type f -exec cp {} "$XCLBIN_WORKSPACE_DIR/src/IP/" \;

# --- Step 3: Copy software folder into xclbin-workspace/<benchmark>/host/ ---
echo "Copying software into $XCLBIN_WORKSPACE_DIR/src/host/ ..."
mkdir -p "$XCLBIN_WORKSPACE_DIR/src/host"

cp -r "$SOFTWARE_DIR/." "$XCLBIN_WORKSPACE_DIR/src/host/"

echo "Done. Workspace ready at: $XCLBIN_WORKSPACE_DIR"
