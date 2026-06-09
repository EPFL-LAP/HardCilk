#!/usr/bin/env bash
# Full HW rebuild after the edgemap_process non-blocking-write fix (BFS.cpp) and
# the SchedulerServer write_idle narrowing. Rebuilds HLS (BFS.cpp changed) ->
# generator -> stage -> bitstream -> host, resets the FPGA, then runs as-skitter.
set -e
ROOT=/beta/bradley/HardCilk
LOG=$ROOT/scripts/hw_rebuild.log
exec > >(tee "$LOG") 2>&1
PLATFORM=xilinx_u55c_gen3x16_xdma_3_202210_1

echo "===== STEP1 HLS ($(date)) ====="
source /alpha/tools/Xilinx/Vitis_HLS/2024.1/settings64.sh
cd "$ROOT/scripts"
rm -rf hls_projects
bash build_hls_kernel/build_kernels.sh \
  -d "$ROOT/hls-processing-elements/mfpga/BFS" -f 200 -p xcu55c-fsvh2892-2L-e \
  -o "$ROOT/hls-kernel-output/BFS" -k BFS sparse_edgemap_helper

echo "===== STEP2 GENERATOR ($(date)) ====="
source ~/.local/opt/hdlstuff/bin/activate-hdlstuff.sh
rm -rf "$ROOT/HardCilk-output/BFS_hardcilk_output"
cd "$ROOT/architecture-generator"
sbt -batch "runMain HardCilk.HardCilkEmitter taskDescriptors/mfpga/BFS.json -o ../HardCilk-output/ -g -c -r 7 -p"

echo "===== STEP3 STAGE ($(date)) ====="
rm -rf "$ROOT/xclbin-workspace/BFS/src/IP" "$ROOT/xclbin-workspace/BFS/src/host"
cd "$ROOT/scripts"
bash generate_benchmark_xclbin_project.sh BFS

echo "===== STEP4 XCLBIN hw ($(date)) ====="
source /alpha/tools/Xilinx/Vivado/2024.1/settings64.sh
source /opt/xilinx/xrt/setup.sh
cd "$ROOT/xclbin-workspace/BFS"
make cleanall
make TARGET=hw
echo "XCLBIN_BUILT=$? ($(date))"

echo "===== STEP5 HOST ($(date)) ====="
cd "$ROOT/xclbin-workspace/BFS/src/host"
cmake -B build -S .
cmake --build build -j --target BFS_xrt

echo "===== STEP5b FPGA RESET ($(date)) ====="
xrt-smi reset --force 2>/dev/null || xrt-smi reset || echo "(xrt-smi reset returned non-zero; continuing)"

echo "===== STEP6 RUN as-skitter ($(date)) ====="
source /alpha/tools/Xilinx/Vitis/2024.1/settings64.sh
cd "$ROOT/xclbin-workspace/BFS/build_dir.hw.$PLATFORM"
set +e
./../src/host/build/projects/BFS/BFS_xrt BFS.xclbin /beta/bradley/Graphs/as-skitter.txt 10 0 600
echo "HW_RUN_EXIT=$? ($(date))"
