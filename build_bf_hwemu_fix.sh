#!/usr/bin/env bash
# Rebuild BellmanFord hw_emu xclbin with the load_vertices split fix.
# Only sparse_edgemap_helper changed (internal split, same module interface),
# so we re-csynth just that PE, swap its RTL into the workspace src/IP, and
# relink. No sbt/Chisel top regen needed.
set -uo pipefail
HC=/beta/bradley/HardCilk
WS=$HC/xclbin-workspace/BellmanFord
HLS=$HC/hls-processing-elements/mfpga/BellmanFord
source /opt/xilinx/xrt/setup.sh

echo "=== [1/3] csynth sparse_edgemap_helper $(date) ==="
source /alpha/tools/Xilinx/Vitis_HLS/2024.1/settings64.sh
cd "$HLS/Test"
rm -rf edgemap_helper_csynth
vitis_hls -f csynth_edgemap_helper.tcl > /tmp/bf_csynth_helper.out 2>&1
echo "csynth rc=$?"; tail -4 /tmp/bf_csynth_helper.out
SYN="$HLS/Test/edgemap_helper_csynth/solution1/syn/verilog"
echo "--- new stage modules present? ---"
ls "$SYN/sparse_edgemap_helper_clear_relaxed_load_bulk.v" "$SYN/sparse_edgemap_helper_read_distance.v" 2>&1

echo "=== [2/3] swap helper RTL into src/IP ==="
rm -fv "$WS"/src/IP/sparse_edgemap_helper*.v "$WS"/src/IP/sparse_edgemap_helper*.tcl >/dev/null
cp -v "$SYN"/sparse_edgemap_helper* "$WS/src/IP/" >/dev/null
echo "--- old load_vertices removed / new stages in place? ---"
ls "$WS"/src/IP/ | grep -E "load_vertices|clear_relaxed|read_distance" || echo "(none matched)"

echo "=== [3/3] build hw_emu xo+link $(date) ==="
source /alpha/tools/Xilinx/Vitis/2024.1/settings64.sh
cd "$WS"
rm -rf _x_temp.hw_emu.* BellmanFord/xo/hw_emu/*.xo build_dir.hw_emu.*/BellmanFord.xclbin
make TARGET=hw_emu > /tmp/bf_link_hwemu.out 2>&1
echo "build rc=$?"; tail -6 /tmp/bf_link_hwemu.out
emconfigutil --platform xilinx_u55c_gen3x16_xdma_3_202210_1 --od build_dir.hw_emu.xilinx_u55c_gen3x16_xdma_3_202210_1 >/dev/null 2>&1 || true
ls -la build_dir.hw_emu.*/BellmanFord.xclbin 2>&1
echo "=== done $(date) ==="
