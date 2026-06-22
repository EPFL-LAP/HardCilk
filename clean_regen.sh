#!/usr/bin/env bash
set -uo pipefail
HC=/beta/bradley/HardCilk; WS=$HC/xclbin-workspace/triangleCountDecoupled
HLS=$HC/hls-processing-elements/mfpga/triangleCountDecoupled
source /opt/xilinx/xrt/setup.sh
echo "=== [1/4] csynth watcher (my HLS change) $(date) ==="
source /alpha/tools/Xilinx/Vitis_HLS/2024.1/settings64.sh
cd $HLS; rm -rf vitis_hls_work_watcher; mkdir -p vitis_hls_work_watcher; cd vitis_hls_work_watcher
vitis_hls -f ../Test/csynth_watcher.tcl > csynth.out 2>&1; echo "csynth rc=$?"
SYN=$PWD/watcher_proj/solution1/syn/verilog
echo "=== [2/4] official project regen (consistent rtl + xml + cfg + host) ==="
cd $HC
bash scripts/generate_benchmark_xclbin_project.sh triangleCountDecoupled 2>&1 | tail -12
echo "=== [3/4] restore csynth watcher into src/IP ==="
cp -v "$SYN/watcher.v" "$SYN/watcher_gmem_m_axi.v" "$WS/src/IP/"
echo "=== kernel xml m_axi count now (should match new top) ==="
grep -coE "m_axi_[0-9]+" "$WS/src/xml/user_0.xml"
echo "=== workspace driver has my num_instances change? ==="
grep -c "num_instances" "$WS/src/host/projects/triangleCountDecoupled/include/TriangleCountDecoupledDriver.h" 2>/dev/null || echo "driver path differs"
echo "=== [4/4] build hw_emu $(date) ==="
source /alpha/tools/Xilinx/Vitis/2024.1/settings64.sh
cd $WS
rm -rf _x_temp.hw_emu.* triangleCountDecoupled/xo/hw_emu/*.xo build_dir.hw_emu.*/triangleCountDecoupled.xclbin
make TARGET=hw_emu > /tmp/link_clean.out 2>&1; echo "build rc=$?"; tail -3 /tmp/link_clean.out
emconfigutil --platform xilinx_u55c_gen3x16_xdma_3_202210_1 --od build_dir.hw_emu.xilinx_u55c_gen3x16_xdma_3_202210_1 >/dev/null 2>&1 || true
ls -la build_dir.hw_emu.*/triangleCountDecoupled.xclbin 2>&1
echo "=== done $(date) ==="
