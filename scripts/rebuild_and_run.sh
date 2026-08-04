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
#   RAMA_MODE=striped bash scripts/rebuild_and_run.sh countDecoupled
#   RAMA_MODE=no-striping bash scripts/rebuild_and_run.sh countDecoupled
#   HLS_CFLAGS=-DCOUNTDECOUPLED_LEGACY_ARGUMENT_NOTIFIER=1 \
#     bash scripts/rebuild_and_run.sh countDecoupled
#
# QuestaSim co-simulation instead of Vitis hw_emu:
#   QUESTA=1 bash scripts/rebuild_and_run.sh countDecoupled
#   QUESTA=1 SIZE=10 INSTANCES=2000 bash scripts/rebuild_and_run.sh countDecoupled
#   QUESTA=1 START_STEP=6 bash scripts/rebuild_and_run.sh countDecoupled   # re-run sim only
#
#   Same 6 steps, but: step2 also emits the QuestaSim project (-q); step4 builds
#   the Vivado block design (kernel + real Xilinx HBM IP + AXI-VIP host bridge)
#   and its simulation sources instead of running v++; step5 is a no-op (the host
#   is compiled by sccom inside the simulation); step6 runs `vsim` instead of the
#   XRT driver. The SAME host code runs in both flows -- only the Memory backend
#   differs (questaMemory DPI bridge vs XRTMemory).
#   Sim-only knobs: COUNTDECOUPLED_SIZE / COUNTDECOUPLED_INSTANCES (problem size,
#   taken from SIZE/INSTANCES), COUNTDECOUPLED_START_DELAY_US (HBM bring-up wait),
#   HARDCILK_QUESTA_QUEUE_FLOOR (queue sizing), HARDCILK_QUESTA_TELEMETRY=1.
#   Waveform: vsim.wlf in the sim dir; convert with
#     wlf2vcd vsim.wlf -o x.vcd && vcd2fst x.vcd x.fst && rm x.vcd
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
  [triangleCountDecoupled]="whileLoopMain whileLoopMain_reentry0 whileLoopMain_reentry0_cont0 memReader"
  [countDecoupled]="taskInitiator_reentry0 taskAdder_cont0 memReader"
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
# QuestaSim co-simulation mode (see the header). The generated block-design tcl,
# simulate.do and the Vivado sim project all live next to the emitted RTL.
QUESTA=${QUESTA:-0}
QUESTA_TCL_DIR=$ROOT/HardCilk-output/${BENCHMARK}_hardcilk_output/tcl

echo "===== BENCHMARK $BENCHMARK ====="
echo "WORKSPACE=$WORKSPACE_NAME"
echo "START_STEP=$START_STEP"
echo "MODE=$( [[ "$QUESTA" == "1" ]] && echo 'QuestaSim co-simulation' || echo 'Vitis hw_emu' )"
echo "RUN_ARGS=$RUN_ARGS"
echo "HLS_CFLAGS=${HLS_CFLAGS:-<none>}"
echo "RAMA_MODE=${RAMA_MODE:-descriptor-only}"

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
      -d "$ROOT/hls-processing-elements/mfpga/$BENCHMARK" -f 300 -p xcu55c-fsvh2892-2L-e \
      -o "$ROOT/hls-kernel-output/$BENCHMARK" -k "${KERNELS[@]}"

    # The watcher is a reusable opt-in HLS block, not a benchmark PE. Build it
    # into one shared output location whenever the selected descriptor includes
    # watcherConfig. Designs without watcherConfig are completely unchanged.
    DESCRIPTOR="$ROOT/architecture-generator/taskDescriptors/mfpga/$BENCHMARK.json"
    if grep -q '"watcherConfig"' "$DESCRIPTOR"; then
      bash build_hls_kernel/build_kernels.sh \
        -d "$ROOT/hls-processing-elements/watcher" -f 300 -p xcu55c-fsvh2892-2L-e \
        -o "$ROOT/hls-kernel-output/watcher" -k watcher
    fi
  fi
fi

if (( START_STEP <= 2 )); then
  echo "===== STEP2 GENERATOR ====="
  source ~/.local/opt/hdlstuff/bin/activate-hdlstuff.sh
  rm -rf "$ROOT/HardCilk-output/${BENCHMARK}_hardcilk_output"
  cd "$ROOT/architecture-generator"
  # Kernel-global start broadcast (simultaneous server release + deterministic
  # watcher start gate). This script defaults it ON to match the verified build;
  # set GLOBAL_START=0 to build the pre-feature design instead. (The emitter/Chisel
  # flag itself stays default-OFF, so unit tests and other callers are unaffected.)
  GLOBAL_START=${GLOBAL_START:-1}
  GLOBAL_START_FLAG=""
  if [[ "$GLOBAL_START" != "0" ]]; then
    GLOBAL_START_FLAG="--global-start"
  fi
  # -q additionally emits the QuestaSim project (block-design tcl + simulate.do
  # + simulate.sh) next to the RTL. Harmless for the hw_emu flow, so it is only
  # added when asked for.
  QUESTA_FLAG=""
  if [[ "$QUESTA" == "1" ]]; then
    QUESTA_FLAG="-q"
  fi
  RAMA_FLAG=""
  case "${RAMA_MODE:-}" in
    "") ;;
    striped) RAMA_FLAG="--rama-striping" ;;
    no-striping) RAMA_FLAG="--rama-no-striping" ;;
    *) echo "RAMA_MODE must be 'striped', 'no-striping', or empty" >&2; exit 2 ;;
  esac
  sbt "runMain HardCilk.HardCilkEmitter taskDescriptors/mfpga/${BENCHMARK}.json -o ../HardCilk-output/ -g -c -r ${REDUCE_AXI[$BENCHMARK]} -p ${GLOBAL_START_FLAG} ${QUESTA_FLAG} ${RAMA_FLAG}"
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

if (( START_STEP <= 4 )) && [[ "$QUESTA" == "1" ]]; then
  echo "===== STEP4 QUESTA BLOCK DESIGN ====="
  # Builds design_1 (kernel + Xilinx HBM IP + AXI-VIP host bridge) and generates
  # + compiles its simulation sources. Replaces the v++/xclbin step.
  source /alpha/tools/Xilinx/Vivado/2024.1/settings64.sh
  cd "$QUESTA_TCL_DIR"
  rm -rf "${BENCHMARK}_vivado_project" .Xil
  vivado -mode batch -source "${BENCHMARK}_questa.tcl" \
         -log vivado_questa.log -journal vivado_questa.jou
  echo "block design + simulation sources built in $QUESTA_TCL_DIR"
fi

if (( START_STEP <= 4 )) && [[ "$QUESTA" != "1" ]]; then
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
  if [[ "$QUESTA" == "1" ]]; then
    echo "===== STEP5 HOST (questa: compiled by sccom during step 6) ====="
  else
    echo "===== STEP5 HOST ====="
    source /opt/xilinx/xrt/setup.sh
    cd "$WORKSPACE_DIR/src/host"
    cmake -B build -S .
    cmake --build build -j --target "${HOST_TARGET[$BENCHMARK]}"
  fi
fi

if [[ "$QUESTA" == "1" ]]; then
  echo "===== STEP6 QUESTA RUN ====="
  source /alpha/tools/Xilinx/Vivado/2024.1/settings64.sh
  SIM_DIR="$QUESTA_TCL_DIR/${BENCHMARK}_vivado_project/project_1.sim/sim_1/behav/questa"
  if [[ ! -d "$SIM_DIR" ]]; then
    echo "No simulation directory at $SIM_DIR -- run step 4 first (drop START_STEP)." >&2
    exit 1
  fi
  cp "$QUESTA_TCL_DIR/simulate.do" "$SIM_DIR/"
  cd "$SIM_DIR"
  # Stale optimised DB from an aborted run confuses vopt.
  rm -rf main_sim_opt
  # The simulation compiles the STAGED workspace host, so the code under test is
  # the same one the XRT flow builds.
  export HARDCILK_HOST_DIR="$WORKSPACE_DIR/src/host"
  export HARDCILK_HBM_DESCRIPTOR="$WORKSPACE_DIR/${BENCHMARK}.hbmports.json"
  # Problem size comes from the same SIZE/INSTANCES knobs the hw_emu args use.
  export COUNTDECOUPLED_SIZE=${SIZE:-10}
  export COUNTDECOUPLED_INSTANCES=${INSTANCES:-10}
  echo "host=$HARDCILK_HOST_DIR size=$COUNTDECOUPLED_SIZE instances=$COUNTDECOUPLED_INSTANCES"
  # No `timeout`: an RTL co-simulation is far slower than hw_emu and simulate.do
  # already bounds itself with `run <N> ms`. Override with QUESTA_TIMEOUT if wanted.
  if [[ -n "${QUESTA_TIMEOUT:-}" ]]; then
    timeout "$QUESTA_TIMEOUT" vsim -c -do simulate.do
  else
    vsim -c -do simulate.do
  fi
  echo "CYCLE_DONE_EXIT=$?"
  echo "waveform: $SIM_DIR/vsim.wlf"
  echo "  convert: wlf2vcd vsim.wlf -o x.vcd && vcd2fst x.vcd x.fst && rm x.vcd"
  exit 0
fi

echo "===== STEP6 RUN ====="
source /opt/xilinx/xrt/setup.sh
source /alpha/tools/Xilinx/Vitis/2024.1/settings64.sh
cd "$BUILD_DIR"
timeout "$RUN_TIMEOUT" bash -c \
  "XCL_EMULATION_MODE=hw_emu ../src/host/build/projects/$BENCHMARK/${HOST_TARGET[$BENCHMARK]} ${XCLBIN_NAME[$BENCHMARK]} $RUN_ARGS"
echo "CYCLE_DONE_EXIT=$?"
