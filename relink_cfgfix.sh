#!/usr/bin/env bash
set -uo pipefail
source /opt/xilinx/xrt/setup.sh; source /alpha/tools/Xilinx/Vitis/2024.1/settings64.sh
cd /beta/bradley/HardCilk/xclbin-workspace/triangleCountDecoupled
echo "=== relink hw_emu with shared-HBM cfg $(date) ==="
rm -rf _x_temp.hw_emu.* build_dir.hw_emu.*/triangleCountDecoupled.xclbin
make TARGET=hw_emu 2>&1 | tail -5
echo "rc=${PIPESTATUS[0]}"
ls -la build_dir.hw_emu.*/triangleCountDecoupled.xclbin 2>&1
emconfigutil --platform xilinx_u55c_gen3x16_xdma_3_202210_1 --od build_dir.hw_emu.xilinx_u55c_gen3x16_xdma_3_202210_1 >/dev/null 2>&1 || true
echo "=== done $(date) ==="
