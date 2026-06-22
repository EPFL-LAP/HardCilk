#!/usr/bin/env bash
set -uo pipefail
HC=/beta/bradley/HardCilk; WS=$HC/xclbin-workspace/triangleCountDecoupled
HLS=$HC/hls-processing-elements/mfpga/triangleCountDecoupled
source /opt/xilinx/xrt/setup.sh
echo "=== [1/5] sbt regen -r 15 $(date) ==="
bash $HC/scripts/generate_benchmarks_hardcilk.sh triangleCountDecoupled > /tmp/sbt_r15.out 2>&1; echo "sbt rc=$?"; tail -2 /tmp/sbt_r15.out
echo "=== emitted cfg master count ==="; grep -cE "sp=.*m_axi" $HC/HardCilk-output/triangleCountDecoupled_hardcilk_output/xrt/conn_u55c.cfg
echo "=== [2/5] csynth watcher ==="
source /alpha/tools/Xilinx/Vitis_HLS/2024.1/settings64.sh
cd $HLS; rm -rf vitis_hls_work_watcher; mkdir -p vitis_hls_work_watcher; cd vitis_hls_work_watcher
vitis_hls -f ../Test/csynth_watcher.tcl > csynth.out 2>&1; echo "csynth rc=$?"
SYN=$PWD/watcher_proj/solution1/syn/verilog
echo "=== [3/5] project-gen (assemble workspace) ==="
cd $HC; bash scripts/generate_benchmark_xclbin_project.sh triangleCountDecoupled 2>&1 | tail -4
echo "=== [4/5] restore csynth watcher + show cfg ==="
cp -v "$SYN/watcher.v" "$SYN/watcher_gmem_m_axi.v" "$WS/src/IP/"
grep -E "m_axi_(00|14|15)" $WS/src/cfg/conn_u55c_hw_emu.cfg
echo "=== [5/5] build hw_emu $(date) ==="
source /alpha/tools/Xilinx/Vitis/2024.1/settings64.sh
cd $WS; rm -rf _x_temp.hw_emu.* triangleCountDecoupled/xo/hw_emu/*.xo build_dir.hw_emu.*/triangleCountDecoupled.xclbin
make TARGET=hw_emu > /tmp/link_r15.out 2>&1; echo "build rc=$?"; tail -3 /tmp/link_r15.out
emconfigutil --platform xilinx_u55c_gen3x16_xdma_3_202210_1 --od build_dir.hw_emu.xilinx_u55c_gen3x16_xdma_3_202210_1 >/dev/null 2>&1 || true
ls -la build_dir.hw_emu.*/triangleCountDecoupled.xclbin 2>&1
echo "=== done $(date) ==="
