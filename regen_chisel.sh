#!/usr/bin/env bash
set -uo pipefail
cd /beta/bradley/HardCilk/architecture-generator
echo "=== sbt HardCilkEmitter triangleCountDecoupled $(date) ==="
sbt "runMain HardCilk.HardCilkEmitter taskDescriptors/mfpga/triangleCountDecoupled.json -o ../HardCilk-output/ -g -c -r 30 -p" 2>&1
echo "=== sbt rc=$? $(date) ==="
ls -la /beta/bradley/HardCilk/HardCilk-output/triangleCountDecoupled_hardcilk_output/rtl/triangleCountDecoupled.v
