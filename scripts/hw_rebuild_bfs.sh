#!/usr/bin/env bash
{
# =============================================================================
# hw_rebuild_bfs.sh -- full hardware pipeline for BFS
#
# Usage:
#   bash scripts/hw_rebuild_bfs.sh [benchmark] [workspaceNumber]
#   bash scripts/hw_rebuild_bfs.sh BFS 2
#   RUN_ARGS="/beta/bradley/Graphs/as-skitter.txt 10 0 600" bash scripts/hw_rebuild_bfs.sh
#   SKIP_HLS=1 bash scripts/hw_rebuild_bfs.sh BFS
#   START_STEP=5 bash scripts/hw_rebuild_bfs.sh BFS 2
#
# Watch progress from another shell with:  tail -f scripts/hw_rebuild.log
# =============================================================================
set -e

ROOT=/beta/bradley/HardCilk
LOG=$ROOT/scripts/hw_rebuild.log

BENCHMARK=${1:-${BENCHMARK:-BFS}}
WORKSPACE_NUMBER=${2:-${WORKSPACE_NUMBER:-}}
WATCHDOG=${WATCHDOG:-600}
RUN_TIMEOUT=${RUN_TIMEOUT:-900}
START_STEP=${START_STEP:-1}

declare -A HLS_KERNELS=(
  [BFS]="BFS sparse_edgemap_helper"
)

declare -A HOST_TARGET=(
  [BFS]="BFS_xrt"
)

declare -A XCLBIN_NAME=(
  [BFS]="BFS.xclbin"
)

declare -A REDUCE_AXI=(
  [BFS]=7
)

default_run_args() {
  case "$BENCHMARK" in
    BFS)
      echo "${GRAPH:-/beta/bradley/Graphs/as-skitter.txt} ${SOURCE:-10} ${MAX_DEPTH:-0} ${WATCHDOG}"
      ;;
    *)
      echo "Unknown benchmark '$BENCHMARK'" >&2
      exit 1
      ;;
  esac
}

if [[ ! -v HLS_KERNELS["$BENCHMARK"] ]]; then
  echo "Unknown benchmark '$BENCHMARK'. Valid: ${!HLS_KERNELS[*]}" >&2
  exit 1
fi
if [[ -n "$WORKSPACE_NUMBER" && ! "$WORKSPACE_NUMBER" =~ ^[0-9]+$ ]]; then
  echo "workspaceNumber must be numeric, got '$WORKSPACE_NUMBER'" >&2
  exit 1
fi
if [[ ! "$START_STEP" =~ ^[1-6]$ ]]; then
  echo "START_STEP must be one of 1, 2, 3, 4, 5, 6; got '$START_STEP'" >&2
  exit 1
fi

WORKSPACE_SUFFIX=""
if [[ -n "$WORKSPACE_NUMBER" ]]; then
  WORKSPACE_SUFFIX="-$WORKSPACE_NUMBER"
fi
WORKSPACE_NAME="${BENCHMARK}${WORKSPACE_SUFFIX}"

RUN_ARGS=${RUN_ARGS:-$(default_run_args)}

# Stream everything to console + log.
exec > >(tee "$LOG") 2>&1

PLATFORM=xilinx_u55c_gen3x16_xdma_3_202210_1
WORKSPACE_DIR=$ROOT/xclbin-workspace/$WORKSPACE_NAME
BUILD_DIR=$WORKSPACE_DIR/build_dir.hw.$PLATFORM

echo "===== BENCHMARK $BENCHMARK ====="
echo "WORKSPACE=$WORKSPACE_NAME"
echo "START_STEP=$START_STEP"
echo "RUN_ARGS=$RUN_ARGS"

if (( START_STEP <= 1 )); then
  echo "===== STEP1 HLS ($(date)) ====="
  if [[ "${SKIP_HLS:-0}" == "1" ]]; then
    echo "SKIP_HLS=1 -> reusing $ROOT/hls-kernel-output/$BENCHMARK"
  else
    source /alpha/tools/Xilinx/Vitis_HLS/2024.1/settings64.sh
    cd "$ROOT/scripts"
    rm -rf hls_projects
    read -ra KERNELS <<< "${HLS_KERNELS[$BENCHMARK]}"
    bash build_hls_kernel/build_kernels.sh \
      -d "$ROOT/hls-processing-elements/mfpga/$BENCHMARK" -f 200 -p xcu55c-fsvh2892-2L-e \
      -o "$ROOT/hls-kernel-output/$BENCHMARK" -k "${KERNELS[@]}"
  fi
fi

if (( START_STEP <= 2 )); then
  echo "===== STEP2 GENERATOR ($(date)) ====="
  source ~/.local/opt/hdlstuff/bin/activate-hdlstuff.sh
  rm -rf "$ROOT/HardCilk-output/${BENCHMARK}_hardcilk_output"
  cd "$ROOT/architecture-generator"
  sbt -batch "runMain HardCilk.HardCilkEmitter taskDescriptors/mfpga/${BENCHMARK}.json -o ../HardCilk-output/ -g -c -r ${REDUCE_AXI[$BENCHMARK]} -p"
fi

if (( START_STEP <= 3 )); then
  echo "===== STEP3 STAGE ($(date)) ====="
  rm -rf "$WORKSPACE_DIR/src/IP" "$WORKSPACE_DIR/src/host"
  cd "$ROOT/scripts"
  if [[ -n "$WORKSPACE_NUMBER" ]]; then
    bash generate_benchmark_xclbin_project.sh "$BENCHMARK" "$WORKSPACE_NUMBER"
  else
    bash generate_benchmark_xclbin_project.sh "$BENCHMARK"
  fi
fi

if (( START_STEP <= 4 )); then
  echo "===== STEP4 XCLBIN hw ($(date)) ====="
  source /alpha/tools/Xilinx/Vivado/2024.1/settings64.sh
  source /opt/xilinx/xrt/setup.sh
  cd "$WORKSPACE_DIR"
  make cleanall
  make TARGET=hw
fi

if (( START_STEP <= 5 )); then
  echo "===== STEP5 HOST ($(date)) ====="
  source /opt/xilinx/xrt/setup.sh
  cd "$WORKSPACE_DIR/src/host"
  cmake -B build -S .
  cmake --build build -j --target "${HOST_TARGET[$BENCHMARK]}"
fi

echo "===== STEP5b FPGA RESET ($(date)) ====="
source /opt/xilinx/xrt/setup.sh
xrt-smi reset --force 2>/dev/null || xrt-smi reset || echo "(xrt-smi reset returned non-zero; continuing)"

echo "===== STEP6 RUN ($(date)) ====="
source /alpha/tools/Xilinx/Vitis/2024.1/settings64.sh
cd "$BUILD_DIR"
set +e
timeout "$RUN_TIMEOUT" bash -c \
  "../src/host/build/projects/$BENCHMARK/${HOST_TARGET[$BENCHMARK]} ${XCLBIN_NAME[$BENCHMARK]} $RUN_ARGS"
run_status=$?
set -e
echo "HW_RUN_EXIT=$run_status ($(date))"

exit "$run_status"
}
