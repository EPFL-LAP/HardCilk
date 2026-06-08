#!/usr/bin/env bash
# =============================================================================
# rebuild_and_run.sh — full BFS hw_emu pipeline in one shot
#
# Runs the whole HLS -> generator -> stage -> v++ link -> host -> run chain that
# you'd otherwise do a step at a time (see LockInstallPlan.md "How To Run").
# Sources the right Xilinx env for each stage, cleans stale state between layers,
# and streams progress to the console AND to scripts/cycle.log.
#
# Usage:
#   bash scripts/rebuild_and_run.sh                 # tinyGraph, source 0, full depth
#   GRAPH=/beta/bradley/Graphs/foo.txt bash scripts/rebuild_and_run.sh
#   SOURCE=5 MAX_DEPTH=4 WATCHDOG=1200 bash scripts/rebuild_and_run.sh
#   SKIP_HLS=1 bash scripts/rebuild_and_run.sh      # reuse existing HLS output
#
# Watch progress from another shell with:  tail -f scripts/cycle.log
# Each stage prints a "===== STEPn ... =====" banner; the run ends with
# "CYCLE_DONE_EXIT=<code>".
# =============================================================================
set -e

ROOT=/beta/bradley/HardCilk
LOG=$ROOT/scripts/cycle.log

GRAPH=${GRAPH:-/beta/bradley/Graphs/tinyGraph.txt}
SOURCE=${SOURCE:-0}
MAX_DEPTH=${MAX_DEPTH:-0}     # 0 == unbounded
WATCHDOG=${WATCHDOG:-1200}    # host management-loop timeout (s)
RUN_TIMEOUT=${RUN_TIMEOUT:-1500}  # wall-clock kill for the whole emu run (s)

# Stream everything to console + log.
exec > >(tee "$LOG") 2>&1

PLATFORM=xilinx_u55c_gen3x16_xdma_3_202210_1
BUILD_DIR=$ROOT/xclbin-workspace/BFS/build_dir.hw_emu.$PLATFORM

echo "===== STEP1 HLS ====="
if [[ "${SKIP_HLS:-0}" == "1" ]]; then
  echo "SKIP_HLS=1 -> reusing $ROOT/hls-kernel-output/BFS"
else
  source /alpha/tools/Xilinx/Vitis_HLS/2024.1/settings64.sh
  cd "$ROOT/scripts"
  rm -rf hls_projects
  bash build_hls_kernel/build_kernels.sh \
    -d "$ROOT/hls-processing-elements/mfpga/BFS" -f 200 -p xcu55c-fsvh2892-2L-e \
    -o "$ROOT/hls-kernel-output/BFS" -k BFS sparse_edgemap_helper
fi

echo "===== STEP2 GENERATOR ====="
source ~/.local/opt/hdlstuff/bin/activate-hdlstuff.sh
rm -rf "$ROOT/HardCilk-output/BFS_hardcilk_output"
cd "$ROOT/architecture-generator"
sbt "runMain HardCilk.HardCilkEmitter taskDescriptors/mfpga/BFS.json -o ../HardCilk-output/ -g -c -r 30 -p"

echo "===== STEP3 STAGE ====="
rm -rf "$ROOT/xclbin-workspace/BFS/src/IP" "$ROOT/xclbin-workspace/BFS/src/host"
cd "$ROOT/scripts"
bash generate_benchmark_xclbin_project.sh BFS

echo "===== STEP4 XCLBIN ====="
source /alpha/tools/Xilinx/Vivado/2024.1/settings64.sh
source /opt/xilinx/xrt/setup.sh
cd "$ROOT/xclbin-workspace/BFS"
make cleanall
make TARGET=hw_emu
make emconfig TARGET=hw_emu

echo "===== STEP5 HOST ====="
cd "$ROOT/xclbin-workspace/BFS/src/host"
cmake -B build -S .
cmake --build build -j --target BFS_xrt

echo "===== STEP6 RUN ====="
source /alpha/tools/Xilinx/Vitis/2024.1/settings64.sh
cd "$BUILD_DIR"
timeout "$RUN_TIMEOUT" bash -c \
  "XCL_EMULATION_MODE=hw_emu ../src/host/build/projects/BFS/BFS_xrt BFS.xclbin $GRAPH $SOURCE $MAX_DEPTH $WATCHDOG"
echo "CYCLE_DONE_EXIT=$?"
