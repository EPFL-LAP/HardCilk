#!/usr/bin/env bash
# =============================================================================
# rebuild_and_run.sh — full hw_emu pipeline in one shot
#
# Runs the HLS -> generator -> stage -> v++ link -> host -> run chain that
# you'd otherwise do a step at a time (see LockInstallPlan.md "How To Run").
#
# Usage:
#   bash scripts/rebuild_and_run.sh [benchmark] [workspaceLabel]
#   bash scripts/rebuild_and_run.sh BFS 2
#   bash scripts/rebuild_and_run.sh countDecoupled legacyNoCache
#   BENCHMARK=GraphColoring RUN_ARGS="/path/graph.txt 64 1 1200" bash scripts/rebuild_and_run.sh
#   SKIP_HLS=1 bash scripts/rebuild_and_run.sh BFS
#   START_STEP=5 bash scripts/rebuild_and_run.sh BFS 2
#   RAMA_MODE=striped bash scripts/rebuild_and_run.sh countDecoupled
#   RAMA_MODE=no-striping bash scripts/rebuild_and_run.sh countDecoupled
#   ARGUMENT_SERVER=no-cache bash scripts/rebuild_and_run.sh countDecoupled
#   ARGUMENT_SERVER=cached bash scripts/rebuild_and_run.sh triangleCountDecoupled
#   ARGUMENT_SERVER=no-cache bash scripts/rebuild_and_run.sh triangleCountDecoupled
#   bash scripts/rebuild_and_run.sh triangleCount
#   ARCHITECTURE=updated ARGUMENT_SERVER=no-cache \
#     bash scripts/rebuild_and_run.sh triangleCount updatedNoCache
#   bash scripts/rebuild_and_run.sh fullTriangleCountDecoupled
#   GRAPH=/beta/shahawy/graphs/congress.txt bash scripts/rebuild_and_run.sh fullTriangleCountDecoupled
#   ARCHITECTURE=legacy bash scripts/rebuild_and_run.sh countDecoupled
#   ARCHITECTURE=legacy ARGUMENT_SERVER=no-cache \
#     bash scripts/rebuild_and_run.sh countDecoupled legacyNoCache
#   JSON=architecture-generator/taskDescriptors/experiments/countDecoupled-small.json \
#     bash scripts/rebuild_and_run.sh countDecoupled small
#   JSON=architecture-generator/taskDescriptors/mfpga/fullTriangleCountDecoupled32Adder.json \
#     REDUCE_AXI_PORTS=32 RAMA_MODE=striped \
#     bash scripts/rebuild_and_run.sh fullTriangleCountDecoupled 8Way
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
INVOCATION_DIR=$(pwd -P)

BENCHMARK=${1:-${BENCHMARK:-BFS}}
# WORKSPACE_NUMBER remains a compatibility alias for existing invocations.
WORKSPACE_LABEL=${2:-${WORKSPACE_LABEL:-${WORKSPACE_NUMBER:-}}}
WATCHDOG=${WATCHDOG:-1200}
RUN_TIMEOUT=${RUN_TIMEOUT:-1500}
START_STEP=${START_STEP:-1}
if [[ -z "${ARCHITECTURE+x}" ]]; then
  # regular triangleCount's unchanged HLS speaks the historical no-cache
  # argDataOut/argOut ABI, so restore its original architecture by default.
  if [[ "$BENCHMARK" == "triangleCount" ]]; then
    ARCHITECTURE=legacy
  else
    ARCHITECTURE=updated
  fi
fi
ARGUMENT_SERVER=${ARGUMENT_SERVER:-}

if [[ -n "${JSON:-}" ]]; then
  if [[ "$JSON" = /* ]]; then
    DESCRIPTOR=$JSON
  else
    DESCRIPTOR=$INVOCATION_DIR/$JSON
  fi
else
  DESCRIPTOR=$ROOT/architecture-generator/taskDescriptors/mfpga/$BENCHMARK.json
fi

declare -A HLS_KERNELS=(
  [BFS]="BFS sparse_edgemap_helper"
  [WP-BF]="WidestPath sparse_edgemap_helper"
  [BellmanFord]="BellmanFord sparse_edgemap_helper"
  [ApproxDenseSub]="ApproxDenseSub vertex_subset_helper"
  [MaximalIndependentSet]="MaximalIndependentSet NGS mis_loop_helper"
  [GraphColoring]="GraphColoring color_init_helper color_loop_helper"
  [triangleCount]="triangle vertex_map"
  [triangleCountDecoupled]="whileLoopMain whileLoopMain_reentry0 whileLoopMain_reentry0_cont0 memReader"
  [fullTriangleCountDecoupled]="triangle adder_unit_launcher adder memReader"
  [countDecoupled]="taskInitiator_reentry0 taskAdder_cont0 memReader"
)

declare -A HOST_TARGET=(
  [BFS]="BFS_xrt"
  [WP-BF]="WP_BF_xrt"
  [BellmanFord]="BellmanFord_xrt"
  [ApproxDenseSub]="ApproxDenseSub_xrt"
  [MaximalIndependentSet]="MaximalIndependentSet_xrt"
  [GraphColoring]="GraphColoring_xrt"
  [triangleCount]="triangleCount_xrt"
  [triangleCountDecoupled]="triangleCountDecoupled_xrt"
  [fullTriangleCountDecoupled]="fullTriangleCountDecoupled_xrt"
  [countDecoupled]="countDecoupled_xrt"
)

declare -A XCLBIN_NAME=(
  [BFS]="BFS.xclbin"
  [WP-BF]="WP-BF.xclbin"
  [BellmanFord]="BellmanFord.xclbin"
  [ApproxDenseSub]="ApproxDenseSub.xclbin"
  [MaximalIndependentSet]="MaximalIndependentSet.xclbin"
  [GraphColoring]="GraphColoring.xclbin"
  [triangleCount]="triangleCount.xclbin"
  [triangleCountDecoupled]="triangleCountDecoupled.xclbin"
  [fullTriangleCountDecoupled]="fullTriangleCountDecoupled.xclbin"
  [countDecoupled]="countDecoupled.xclbin"
)

declare -A REDUCE_AXI=(
  [BFS]=16
  [WP-BF]=30
  [BellmanFord]=30
  [ApproxDenseSub]=30
  [MaximalIndependentSet]=30
  [GraphColoring]=30
  [triangleCount]=16
  [triangleCountDecoupled]=16
  [countDecoupled]=16
  [fullTriangleCountDecoupled]=16
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
    triangleCount)
      echo "${GRAPH:-/beta/bradley/Graphs/tinyGraph.txt} ${WATCHDOG}"
      ;;
    triangleCountDecoupled)
      echo "${SIZE:-10} ${INSTANCES:-10} ${WATCHDOG}"
      ;;
    fullTriangleCountDecoupled)
      # Real graph in, triangle count out. graph_64_tri is the default because it
      # is small enough for hw_emu and still has 64 triangles to get wrong --
      # tinyGraph has none, so it would pass without proving anything. Large
      # graphs are limited by the closure pool, not by time: see the driver's
      # closure budget line and its sliced-execution banner.
      echo "${GRAPH:-/beta/shahawy/graphs/graph_64_tri.txt} ${WATCHDOG}"
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
REDUCE_AXI_PORTS=${REDUCE_AXI_PORTS:-${REDUCE_AXI[$BENCHMARK]}}
if [[ ! "$REDUCE_AXI_PORTS" =~ ^[1-9][0-9]*$ || "$REDUCE_AXI_PORTS" -gt 32 ]]; then
  echo "REDUCE_AXI_PORTS must be an integer in [1,32], got '$REDUCE_AXI_PORTS'" >&2
  exit 1
fi
if [[ -n "$WORKSPACE_LABEL" && ! "$WORKSPACE_LABEL" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; then
  echo "workspaceLabel must contain only letters, digits, '.', '_', or '-' and start with a letter or digit; got '$WORKSPACE_LABEL'" >&2
  exit 1
fi
if [[ ! -f "$DESCRIPTOR" ]]; then
  echo "JSON descriptor not found: $DESCRIPTOR" >&2
  exit 1
fi
if [[ ! "$START_STEP" =~ ^[1-6]$ ]]; then
  echo "START_STEP must be one of 1, 2, 3, 4, 5, 6; got '$START_STEP'" >&2
  exit 1
fi
if [[ "$ARCHITECTURE" != "updated" && "$ARCHITECTURE" != "legacy" ]]; then
  echo "ARCHITECTURE must be 'updated' or 'legacy', got '$ARCHITECTURE'" >&2
  exit 1
fi
if [[ -n "$ARGUMENT_SERVER" && "$ARGUMENT_SERVER" != "cached" && "$ARGUMENT_SERVER" != "no-cache" ]]; then
  echo "ARGUMENT_SERVER must be 'cached', 'no-cache', or empty, got '$ARGUMENT_SERVER'" >&2
  exit 1
fi
if [[ "$ARCHITECTURE" == "legacy" && "$ARGUMENT_SERVER" == "cached" ]]; then
  echo "ARCHITECTURE=legacy is incompatible with ARGUMENT_SERVER=cached" >&2
  exit 1
fi
if [[ -z "$ARGUMENT_SERVER" ]]; then
  if [[ "$ARCHITECTURE" == "legacy" ]]; then ARGUMENT_SERVER=no-cache; else ARGUMENT_SERVER=cached; fi
fi
if [[ "$BENCHMARK" == "triangleCount" && "$ARGUMENT_SERVER" != "no-cache" ]]; then
  echo "triangleCount's existing HLS requires ARGUMENT_SERVER=no-cache; use ARCHITECTURE=legacy (default) or ARCHITECTURE=updated ARGUMENT_SERVER=no-cache" >&2
  exit 2
fi

WORKSPACE_SUFFIX=""
if [[ -n "$WORKSPACE_LABEL" ]]; then
  WORKSPACE_SUFFIX="-$WORKSPACE_LABEL"
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
echo "ARCHITECTURE=$ARCHITECTURE"
echo "ARGUMENT_SERVER=$ARGUMENT_SERVER"
echo "RAMA_MODE=${RAMA_MODE:-descriptor-only}"
echo "REDUCE_AXI_PORTS=$REDUCE_AXI_PORTS"
echo "JSON=$DESCRIPTOR"

if (( START_STEP <= 1 )); then
  echo "===== STEP1 HLS ====="
  if [[ "${SKIP_HLS:-0}" == "1" ]]; then
    echo "SKIP_HLS=1 -> reusing $ROOT/hls-kernel-output/$BENCHMARK"
  else
    source /alpha/tools/Xilinx/Vitis_HLS/2024.1/settings64.sh
    cd "$ROOT/scripts"
    rm -rf hls_projects
    read -ra KERNELS <<< "${HLS_KERNELS[$BENCHMARK]}"
    HLS_OUTPUT="$ROOT/hls-kernel-output/$BENCHMARK"
    SELECTED_HLS_CFLAGS=${HLS_CFLAGS:-}
    # triangleCount and fullTriangleCountDecoupled are deliberately not in this
    # list: triangleCount already has the legacy argDataOut/argOut interface and
    # needs no compile-time HLS variant; fullTriangleCountDecoupled has only
    # the cached-notifier ABI (no COUNTDECOUPLED_LEGACY_ARGUMENT_NOTIFIER path in
    # its util.h), and its descriptor names one peHDLPath per task rather than
    # cached/no-cache variants, so its kernels build straight into
    # hls-kernel-output/<benchmark>/<kernel>.
    if [[ "$BENCHMARK" == "countDecoupled" ||
          "$BENCHMARK" == "triangleCountDecoupled" ||
          "$BENCHMARK" == "BFS" ]]; then
      HLS_OUTPUT="$HLS_OUTPUT/$ARGUMENT_SERVER"
      if [[ "$BENCHMARK" == "BFS" ]]; then
        if [[ "$SELECTED_HLS_CFLAGS" == *BFS_LEGACY_ARGUMENT_NOTIFIER* ]]; then
          echo "Do not set BFS_LEGACY_ARGUMENT_NOTIFIER manually; use ARGUMENT_SERVER" >&2
          exit 2
        fi
        if [[ "$ARGUMENT_SERVER" == "no-cache" ]]; then
          SELECTED_HLS_CFLAGS="$SELECTED_HLS_CFLAGS -DBFS_LEGACY_ARGUMENT_NOTIFIER=1"
        else
          SELECTED_HLS_CFLAGS="$SELECTED_HLS_CFLAGS -DBFS_LEGACY_ARGUMENT_NOTIFIER=0"
        fi
      else
        if [[ "$SELECTED_HLS_CFLAGS" == *COUNTDECOUPLED_LEGACY_ARGUMENT_NOTIFIER* ]]; then
          echo "Do not set COUNTDECOUPLED_LEGACY_ARGUMENT_NOTIFIER manually; use ARGUMENT_SERVER" >&2
          exit 2
        fi
        if [[ "$ARGUMENT_SERVER" == "no-cache" ]]; then
          SELECTED_HLS_CFLAGS="$SELECTED_HLS_CFLAGS -DCOUNTDECOUPLED_LEGACY_ARGUMENT_NOTIFIER=1"
        else
          SELECTED_HLS_CFLAGS="$SELECTED_HLS_CFLAGS -DCOUNTDECOUPLED_LEGACY_ARGUMENT_NOTIFIER=0"
        fi
      fi
    fi
    HLS_CFLAGS="$SELECTED_HLS_CFLAGS" bash build_hls_kernel/build_kernels.sh \
      -d "$ROOT/hls-processing-elements/mfpga/$BENCHMARK" -f 300 -p xcu55c-fsvh2892-2L-e \
      -o "$HLS_OUTPUT" -k "${KERNELS[@]}"

    # The watcher is a reusable opt-in HLS block, not a benchmark PE. Build it
    # into one shared output location whenever the selected descriptor includes
    # watcherConfig. Designs without watcherConfig are completely unchanged.
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
  if [[ -z "${GLOBAL_START+x}" ]]; then
    if [[ "$ARCHITECTURE" == "legacy" ]]; then GLOBAL_START=0; else GLOBAL_START=1; fi
  fi
  if [[ "$ARCHITECTURE" == "legacy" && "$GLOBAL_START" != "0" ]]; then
    echo "GLOBAL_START is not supported by ARCHITECTURE=legacy; set GLOBAL_START=0" >&2
    exit 2
  fi
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
  sbt "runMain HardCilk.HardCilkEmitter \"${DESCRIPTOR}\" --benchmark-name ${BENCHMARK} -o ../HardCilk-output/ -g -c -r ${REDUCE_AXI_PORTS} -p --architecture ${ARCHITECTURE} --argument-server ${ARGUMENT_SERVER} ${GLOBAL_START_FLAG} ${QUESTA_FLAG} ${RAMA_FLAG}"
fi

if (( START_STEP <= 3 )); then
  echo "===== STEP3 STAGE ====="
  rm -rf "$WORKSPACE_DIR/src/IP" "$WORKSPACE_DIR/src/host"
  cd "$ROOT/scripts"
  if [[ -n "$WORKSPACE_LABEL" ]]; then
    bash generate_benchmark_xclbin_project.sh "$BENCHMARK" "$WORKSPACE_LABEL"
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
# --foreground keeps the host in THIS shell's foreground process group. Without it
# `timeout` puts the host in a fresh background group, and the driver's Ctrl-C
# takeover (which disables the tty's VINTR so the interrupt never reaches xsim)
# refuses to arm from a background group -- a Ctrl-C would then fall back to a
# group-wide SIGINT, cancel the simulator's `run all`, and hang the host on its
# next register read. The cost is that a RUN_TIMEOUT expiry now SIGTERMs only the
# host instead of the whole group; the host's own handler treats that as a
# graceful stop and tears the simulator down itself, which also keeps the
# telemetry dump a hard group-kill used to throw away.
timeout --foreground "$RUN_TIMEOUT" bash -c \
  "XCL_EMULATION_MODE=hw_emu HARDCILK_HBM_DESCRIPTOR=../${BENCHMARK}.hbmports.json ../src/host/build/projects/$BENCHMARK/${HOST_TARGET[$BENCHMARK]} ${XCLBIN_NAME[$BENCHMARK]} $RUN_ARGS"
echo "CYCLE_DONE_EXIT=$?"
