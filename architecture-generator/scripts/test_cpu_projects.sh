#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export CPU_REPO=${1:?Usage: test_cpu_projects.sh /path/to/CPU-checkout}
CPU_REPO=$(cd "$CPU_REPO" && pwd)
prefix=${HDLSTUFF_PREFIX:-$HOME/.local/opt/hdlstuff}
mkdir -p output
out=$(mktemp -d "$PWD/output/cpu-project-tests.XXXXXX")
run_sbt() {
  sbt "-Dsbt.ivy.home=$prefix/.ivy2" -batch "$1" > "$out/$2.log" 2>&1
}
run_sbt 'Test / runMain CpuIntegration.ProjectGenerationCheck' structure
run_sbt 'Test / runMain CpuIntegration.DescriptorCheck' descriptors
for architecture in fibonacci fibonacci_global; do
  # The first case exercises -k -g; the second emits RTL at build time.
  extra=""; if [[ $architecture == fibonacci ]]; then extra="-g"; fi
  run_sbt "runMain HardCilk.HardCilkEmitter -k $extra --project-template cpu_templates/fibonacci --cpu-repo \"$CPU_REPO\" -o \"$out\" cpu_templates/fibonacci/$architecture.json" "$architecture-generation"
  make -C "$out/${architecture}_hardcilk_output/software/cpu_$architecture" sim > "$out/$architecture-sim.log" 2>&1
  rg '^PASS' "$out/$architecture-sim.log"
done
run_sbt "runMain HardCilk.HardCilkEmitter -k -o \"$out/legacy\" taskDescriptors/fibonacci_nohdl.json" legacy
legacy="$out/legacy/fibonacci_nohdl_hardcilk_output/software/aie_fibonacci_nohdl"
test -s "$legacy/src/aie/project.h"
test -s "$legacy/connectivity.cfg"
test ! -e "$legacy/project.json"
echo "PASS CPU project regressions and legacy -k layout; logs: $out"
