#!/bin/bash
set -euo pipefail

VALID_BENCHMARKS=("BFS" "WP-BF" "BellmanFord" "ApproxDenseSub" "MaximalIndependentSet" "GraphColoring" "graphRandomWalk" "pageRank" "triangleCount" "triangleCountDecoupled" "countDecoupled")

usage() {
    echo "Usage: $0 <benchmarkName> [workspaceNumber]"
    echo "  benchmarkName: one of ${VALID_BENCHMARKS[*]}"
    echo "  workspaceNumber: optional numeric suffix, e.g. BFS 2 -> xclbin-workspace/BFS-2"
    exit 1
}

# --- Argument validation ---
if [[ $# -lt 1 || $# -gt 2 ]]; then
    usage
fi

BENCHMARK="$1"
WORKSPACE_SUFFIX=""
if [[ $# -eq 2 ]]; then
    if [[ ! "$2" =~ ^[0-9]+$ ]]; then
        echo "Error: workspaceNumber must be numeric, got '$2'"
        exit 1
    fi
    WORKSPACE_SUFFIX="-$2"
fi

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
XCLBIN_WORKSPACE_DIR="$HARDCILK_ROOT/xclbin-workspace/${BENCHMARK}${WORKSPACE_SUFFIX}"
DRIVER_TEMPLATE_INCLUDE_DIR="$HARDCILK_ROOT/architecture-generator/software_template/driver/include"
DRIVER_TEMPLATE_SRC_DIR="$HARDCILK_ROOT/architecture-generator/software_template/driver/src"
COMMON_INCLUDE_DIR="$HARDCILK_ROOT/software/common/include"

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
if [[ ! -d "$DRIVER_TEMPLATE_INCLUDE_DIR" ]]; then
    echo "Error: driver template include directory not found: $DRIVER_TEMPLATE_INCLUDE_DIR"
    exit 1
fi
if [[ ! -d "$DRIVER_TEMPLATE_SRC_DIR" ]]; then
    echo "Error: driver template source directory not found: $DRIVER_TEMPLATE_SRC_DIR"
    exit 1
fi
if [[ ! -d "$COMMON_INCLUDE_DIR" ]]; then
    echo "Error: common benchmark include directory not found: $COMMON_INCLUDE_DIR"
    exit 1
fi

# --- Step 1: Copy xrt-projects/<benchmark> to xclbin-workspace, excluding *-arxiv folders ---
echo "Creating workspace at $XCLBIN_WORKSPACE_DIR ..."
mkdir -p "$XCLBIN_WORKSPACE_DIR"

rsync -a --no-owner --no-group --exclude='*-arxiv' "$XRT_PROJECTS_DIR/" "$XCLBIN_WORKSPACE_DIR/"

# --- Step 2: Copy only files (not subfolders) from rtl/ into xclbin-workspace/<benchmark>/IP/ ---
echo "Copying RTL files into $XCLBIN_WORKSPACE_DIR/IP/ ..."
rm -rf "$XCLBIN_WORKSPACE_DIR/src/IP"
mkdir -p "$XCLBIN_WORKSPACE_DIR/src/IP"

find "$RTL_DIR" -maxdepth 1 -type f -exec cp {} "$XCLBIN_WORKSPACE_DIR/src/IP/" \;
if [[ -f "$RTL_DIR/${BENCHMARK}.hbmports.json" ]]; then
    cp "$RTL_DIR/${BENCHMARK}.hbmports.json" "$XCLBIN_WORKSPACE_DIR/"
fi

# --- Step 3: Copy software folder into xclbin-workspace/<benchmark>/host/ ---
echo "Copying software into $XCLBIN_WORKSPACE_DIR/src/host/ ..."
mkdir -p "$XCLBIN_WORKSPACE_DIR/src/host"

DEST_DRIVER_INCLUDE_DIR="$XCLBIN_WORKSPACE_DIR/src/host/driver/include"
DEST_DRIVER_SRC_DIR="$XCLBIN_WORKSPACE_DIR/src/host/driver/src"
if [[ -d "$DEST_DRIVER_INCLUDE_DIR" ]]; then
    echo "Removing stale driver headers from $DEST_DRIVER_INCLUDE_DIR ..."
    rm -rf "$DEST_DRIVER_INCLUDE_DIR"
fi
if [[ -d "$DEST_DRIVER_SRC_DIR" ]]; then
    echo "Removing stale driver sources from $DEST_DRIVER_SRC_DIR ..."
    rm -rf "$DEST_DRIVER_SRC_DIR"
fi

cp -r "$SOFTWARE_DIR/." "$XCLBIN_WORKSPACE_DIR/src/host/"

echo "Refreshing common benchmark headers from $COMMON_INCLUDE_DIR ..."
DEST_COMMON_PROJECT_DIR="$XCLBIN_WORKSPACE_DIR/src/host/projects/common"
DEST_COMMON_INCLUDE_DIR="$DEST_COMMON_PROJECT_DIR/include"
mkdir -p "$DEST_COMMON_PROJECT_DIR"
if [[ ! -f "$DEST_COMMON_PROJECT_DIR/CMakeLists.txt" ]]; then
    touch "$DEST_COMMON_PROJECT_DIR/CMakeLists.txt"
fi
mkdir -p "$DEST_COMMON_INCLUDE_DIR"
cp -a "$COMMON_INCLUDE_DIR/." "$DEST_COMMON_INCLUDE_DIR/"

echo "Refreshing driver headers from $DRIVER_TEMPLATE_INCLUDE_DIR ..."
mkdir -p "$DEST_DRIVER_INCLUDE_DIR"
cp -a "$DRIVER_TEMPLATE_INCLUDE_DIR/." "$DEST_DRIVER_INCLUDE_DIR/"

echo "Refreshing driver sources from $DRIVER_TEMPLATE_SRC_DIR ..."
mkdir -p "$DEST_DRIVER_SRC_DIR"
cp -a "$DRIVER_TEMPLATE_SRC_DIR/." "$DEST_DRIVER_SRC_DIR/"

# --- Step 4: Stage the generated kernel.xml / connectivity cfg
#     (if the emitter produced them). For BFS the generator emits these into
#     <output>/xrt/; other benchmarks keep their hand-written files under
#     xrt-projects/<name>/src/{xml,cfg}/ (already rsynced in at Step 1), so this
#     block is a no-op for them. ---
XRT_GEN_DIR="$HARDCILK_OUTPUT_DIR/xrt"
if [[ -d "$XRT_GEN_DIR" ]]; then
    echo "Staging generated kernel.xml / cfg from $XRT_GEN_DIR ..."
    mkdir -p "$XCLBIN_WORKSPACE_DIR/src/xml" "$XCLBIN_WORKSPACE_DIR/src/cfg"
    [[ -f "$XRT_GEN_DIR/user_0.xml" ]]                 && cp "$XRT_GEN_DIR/user_0.xml"                 "$XCLBIN_WORKSPACE_DIR/src/xml/"
    if [[ -f "$XRT_GEN_DIR/conn_u55c.cfg" ]]; then
        cp "$XRT_GEN_DIR/conn_u55c.cfg" "$XCLBIN_WORKSPACE_DIR/src/cfg/"
        awk '
            /^\[clock\]/ { skip = 1; next }
            /^\[/ { skip = 0 }
            !skip { print }
        ' "$XRT_GEN_DIR/conn_u55c.cfg" > "$XCLBIN_WORKSPACE_DIR/src/cfg/conn_u55c_hw_emu.cfg"
    fi
fi

echo "Done. Workspace ready at: $XCLBIN_WORKSPACE_DIR"
