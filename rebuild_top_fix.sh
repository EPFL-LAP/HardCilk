#!/usr/bin/env bash
set -uo pipefail
HC=/beta/bradley/HardCilk; WS=$HC/xclbin-workspace/triangleCountDecoupled
echo "=== [1/3] sbt regen top (RegNext init fix) $(date) ==="
cd $HC/architecture-generator
sbt "runMain HardCilk.HardCilkEmitter taskDescriptors/mfpga/triangleCountDecoupled.json -o ../HardCilk-output/ -g -c -r 30 -p" > /tmp/sbt_fix.out 2>&1
echo "sbt rc=$?"; tail -2 /tmp/sbt_fix.out
echo "=== verify init reset on the watcher status reg ==="
grep -nE "watcher_io_cont0_status_in_0_REG <= 2'h0|status_in_0_REG <= 2'h0" $HC/HardCilk-output/triangleCountDecoupled_hardcilk_output/rtl/triangleCountDecoupled.v | head -2
echo "=== [2/3] copy top RTL ==="
cp -v "$HC/HardCilk-output/triangleCountDecoupled_hardcilk_output/rtl/triangleCountDecoupled.v" "$WS/src/IP/"
echo "=== [3/3] rebuild hw_emu $(date) ==="
source /opt/xilinx/xrt/setup.sh; source /alpha/tools/Xilinx/Vitis/2024.1/settings64.sh
cd $WS
rm -f triangleCountDecoupled/xo/hw_emu/whileLoopMain_reentry0_0.xo
rm -rf _x_temp.hw_emu.xilinx_u55c_gen3x16_xdma_3_202210_1 build_dir.hw_emu.xilinx_u55c_gen3x16_xdma_3_202210_1/triangleCountDecoupled.xclbin
make TARGET=hw_emu > /tmp/link_fix.out 2>&1; echo "build rc=$?"; tail -3 /tmp/link_fix.out
emconfigutil --platform xilinx_u55c_gen3x16_xdma_3_202210_1 --od build_dir.hw_emu.xilinx_u55c_gen3x16_xdma_3_202210_1 >/dev/null 2>&1 || true
ls -la build_dir.hw_emu.xilinx_u55c_gen3x16_xdma_3_202210_1/triangleCountDecoupled.xclbin 2>&1
echo "=== done $(date) ==="
