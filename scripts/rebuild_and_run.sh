#!/usr/bin/env bash
# =============================================================================
# rebuild_and_run.sh — full hw_emu pipeline in one shot
#
# Runs the HLS -> generator -> stage -> v++ link -> host -> run chain that
# you'd otherwise do a step at a time (see LockInstallPlan.md "How To Run").
#
# Usage:
#   bash scripts/rebuild_and_run.sh [benchmark] [workspaceNumber]
#   bash scripts/rebuild_and_run.sh BFS 2
#   BENCHMARK=GraphColoring RUN_ARGS="/path/graph.txt 64 1 1200" bash scripts/rebuild_and_run.sh
#   SKIP_HLS=1 bash scripts/rebuild_and_run.sh BFS
#   START_STEP=5 bash scripts/rebuild_and_run.sh BFS 2
#
# Watch progress from another shell with:  tail -f scripts/cycle.log
# =============================================================================
set -e

ROOT=/beta/bradley/HardCilk
LOG=$ROOT/scripts/cycle.log

BENCHMARK=${1:-${BENCHMARK:-BFS}}
WORKSPACE_NUMBER=${2:-${WORKSPACE_NUMBER:-}}
WATCHDOG=${WATCHDOG:-1200}
RUN_TIMEOUT=${RUN_TIMEOUT:-1500}
START_STEP=${START_STEP:-1}

declare -A HLS_KERNELS=(
  [BFS]="BFS sparse_edgemap_helper"
  [WP-BF]="WidestPath sparse_edgemap_helper"
  [BellmanFord]="BellmanFord sparse_edgemap_helper"
  [ApproxDenseSub]="ApproxDenseSub vertex_subset_helper"
  [MaximalIndependentSet]="MaximalIndependentSet NGS mis_loop_helper"
  [GraphColoring]="GraphColoring color_init_helper color_loop_helper"
  [triangleCountDecoupled]="whileLoopMain whileLoopMain_reentry0 whileLoopMain_reentry0_cont0 memReader watcher"
  [countDecoupled]="taskInitiator_reentry0 taskAdder_cont0 memReader watcher"
)

declare -A HOST_TARGET=(
  [BFS]="BFS_xrt"
  [WP-BF]="WP_BF_xrt"
  [BellmanFord]="BellmanFord_xrt"
  [ApproxDenseSub]="ApproxDenseSub_xrt"
  [MaximalIndependentSet]="MaximalIndependentSet_xrt"
  [GraphColoring]="GraphColoring_xrt"
  [triangleCountDecoupled]="triangleCountDecoupled_xrt"
  [countDecoupled]="countDecoupled_xrt"
)

declare -A XCLBIN_NAME=(
  [BFS]="BFS.xclbin"
  [WP-BF]="WP-BF.xclbin"
  [BellmanFord]="BellmanFord.xclbin"
  [ApproxDenseSub]="ApproxDenseSub.xclbin"
  [MaximalIndependentSet]="MaximalIndependentSet.xclbin"
  [GraphColoring]="GraphColoring.xclbin"
  [triangleCountDecoupled]="triangleCountDecoupled.xclbin"
  [countDecoupled]="countDecoupled.xclbin"
)

declare -A REDUCE_AXI=(
  [BFS]=7
  [WP-BF]=30
  [BellmanFord]=30
  [ApproxDenseSub]=30
  [MaximalIndependentSet]=30
  [GraphColoring]=30
  [triangleCountDecoupled]=16
  [countDecoupled]=16
)

default_run_args() {
  case "$BENCHMARK" in
    BFS)
      echo "${GRAPH:-/beta/bradley/Graphs/tinyGraph.txt} ${SOURCE:-0} ${MAX_DEPTH:-0} ${WATCHDOG}"
      ;;
    WP-BF|BellmanFord)
      echo "${GRAPH:-/beta/bradley/Graphs/tinyWeightedGraph.csv} ${SOURCE:-0} ${WATCHDOG}"
      ;;
    ApproxDenseSub)
      echo "${GRAPH:-/beta/bradley/Graphs/tinyGraph.txt} ${EPSILON:-0.1} ${WATCHDOG}"
      ;;
    MaximalIndependentSet)
      echo "${GRAPH:-/beta/bradley/Graphs/tinyGraph.txt} ${SEED:-1} ${WATCHDOG}"
      ;;
    GraphColoring)
      echo "${GRAPH:-/beta/bradley/Graphs/tinyGraph.txt} ${MAX_COLORS:-64} ${SEED:-1} ${WATCHDOG}"
      ;;
    triangleCountDecoupled)
      echo "${SIZE:-10} ${INSTANCES:-10} ${WATCHDOG}"
      ;;
    countDecoupled)
      echo "${SIZE:-10} ${INSTANCES:-10} ${WATCHDOG}"
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
BUILD_DIR=$WORKSPACE_DIR/build_dir.hw_emu.$PLATFORM

echo "===== BENCHMARK $BENCHMARK ====="
echo "WORKSPACE=$WORKSPACE_NAME"
echo "START_STEP=$START_STEP"
echo "RUN_ARGS=$RUN_ARGS"

if (( START_STEP <= 1 )); then
  echo "===== STEP1 HLS ====="
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
  echo "===== STEP2 GENERATOR ====="
  source ~/.local/opt/hdlstuff/bin/activate-hdlstuff.sh
  rm -rf "$ROOT/HardCilk-output/${BENCHMARK}_hardcilk_output"
  cd "$ROOT/architecture-generator"
  sbt "runMain HardCilk.HardCilkEmitter taskDescriptors/mfpga/${BENCHMARK}.json -o ../HardCilk-output/ -g -c -r ${REDUCE_AXI[$BENCHMARK]} -p"
fi

if (( START_STEP <= 3 )); then
  echo "===== STEP3 STAGE ====="
  rm -rf "$WORKSPACE_DIR/src/IP" "$WORKSPACE_DIR/src/host"
  cd "$ROOT/scripts"
  if [[ -n "$WORKSPACE_NUMBER" ]]; then
    bash generate_benchmark_xclbin_project.sh "$BENCHMARK" "$WORKSPACE_NUMBER"
  else
    bash generate_benchmark_xclbin_project.sh "$BENCHMARK"
  fi
fi

if (( START_STEP <= 4 )); then
  echo "===== STEP4 XCLBIN ====="
  source /alpha/tools/Xilinx/Vivado/2024.1/settings64.sh
  source /opt/xilinx/xrt/setup.sh
  cd "$WORKSPACE_DIR"
  # `make clean` (per-target) instead of `make cleanall`: cleanall also wipes
  # xclbin-backups/ (it is meant as the explicit "nuke everything" target), so
  # using it for routine rebuilds destroyed saved bitstreams. Remove the Vitis
  # package/XO caches separately so stale staged RTL cannot survive a relink.
  make clean TARGET=hw_emu
  rm -rf packaged_kernel_* tmp_kernel_pack_* .ipcache .Xil */xo/hw_emu
  make TARGET=hw_emu
  make emconfig TARGET=hw_emu
fi

if (( START_STEP <= 5 )); then
  echo "===== STEP5 HOST ====="
  source /opt/xilinx/xrt/setup.sh
  cd "$WORKSPACE_DIR/src/host"
  cmake -B build -S .
  cmake --build build -j --target "${HOST_TARGET[$BENCHMARK]}"
fi

echo "===== STEP6 RUN ====="
source /opt/xilinx/xrt/setup.sh
source /alpha/tools/Xilinx/Vitis/2024.1/settings64.sh
cd "$BUILD_DIR"
timeout "$RUN_TIMEOUT" bash -c \
  "XCL_EMULATION_MODE=hw_emu ../src/host/build/projects/$BENCHMARK/${HOST_TARGET[$BENCHMARK]} ${XCLBIN_NAME[$BENCHMARK]} $RUN_ARGS"
echo "CYCLE_DONE_EXIT=$?"
