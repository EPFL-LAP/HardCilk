#!/usr/bin/env bash
set -uo pipefail
HC=/beta/bradley/HardCilk
WS=$HC/xclbin-workspace/triangleCountDecoupled
HLS=$HC/hls-processing-elements/mfpga/triangleCountDecoupled

echo "=== [1/4] copy regenerated top RTL into src/IP $(date) ==="
cp -v "$HC/HardCilk-output/triangleCountDecoupled_hardcilk_output/rtl/triangleCountDecoupled.v" "$WS/src/IP/"

echo "=== [2/4] csynth watcher (raw-handshake, atomic 2-bit read) ==="
source /opt/xilinx/xrt/setup.sh
source /alpha/tools/Xilinx/Vitis_HLS/2024.1/settings64.sh
cd "$HLS"; rm -rf vitis_hls_work_watcher; mkdir -p vitis_hls_work_watcher; cd vitis_hls_work_watcher
vitis_hls -f ../Test/csynth_watcher.tcl > csynth.out 2>&1
echo "csynth rc=$?"; tail -2 csynth.out
SYN=watcher_proj/solution1/syn/verilog
cp -v "$SYN/watcher.v" "$SYN/watcher_gmem_m_axi.v" "$WS/src/IP/"

echo "=== [3/4] rebuild hw_emu xo $(date) ==="
source /alpha/tools/Xilinx/Vitis/2024.1/settings64.sh
cd "$WS"
rm -f triangleCountDecoupled/xo/hw_emu/whileLoopMain_reentry0_0.xo
make TARGET=hw_emu xo > /tmp/xo_hwemu.out 2>&1; echo "xo rc=$?"; tail -2 /tmp/xo_hwemu.out

echo "=== [4/4] relink hw_emu xclbin $(date) ==="
rm -rf _x_temp.hw_emu.xilinx_u55c_gen3x16_xdma_3_202210_1 \
       build_dir.hw_emu.xilinx_u55c_gen3x16_xdma_3_202210_1/triangleCountDecoupled.xclbin
make TARGET=hw_emu > /tmp/link_hwemu.out 2>&1; echo "link rc=$?"; tail -3 /tmp/link_hwemu.out
emconfigutil --platform xilinx_u55c_gen3x16_xdma_3_202210_1 --od build_dir.hw_emu.xilinx_u55c_gen3x16_xdma_3_202210_1 >/dev/null 2>&1 || true
ls -la build_dir.hw_emu.xilinx_u55c_gen3x16_xdma_3_202210_1/triangleCountDecoupled.xclbin 2>&1
echo "=== done $(date) ==="
