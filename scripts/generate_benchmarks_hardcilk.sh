#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

declare -A REDUCE_AXI=(
  [BFS]=30
  [WP-BF]=30
  [BellmanFord]=30
  [ApproxDenseSub]=30
  [MaximalIndependentSet]=30
  [GraphColoring]=30
  [graphRandomWalk]=22
  [pageRank]=22
  [triangleCount]=23
  [triangleCountDecoupled]=15
  [countDecoupled]=16
)

BENCHMARK_ORDER=(BFS WP-BF BellmanFord ApproxDenseSub MaximalIndependentSet GraphColoring graphRandomWalk pageRank triangleCount triangleCountDecoupled countDecoupled)

usage() {
  echo "Usage: $0 [all|benchmark[,benchmark...]]"
  echo "Valid benchmarks: ${BENCHMARK_ORDER[*]}"
}

if [[ $# -gt 1 ]]; then
  usage
  exit 1
fi

FILTER="${1:-all}"
if [[ "$FILTER" == "all" ]]; then
  TARGETS=("${BENCHMARK_ORDER[@]}")
else
  IFS=',' read -ra TARGETS <<< "$FILTER"
  for target in "${TARGETS[@]}"; do
    if [[ ! -v REDUCE_AXI["$target"] ]]; then
      echo "Unknown benchmark '$target'. Valid: ${BENCHMARK_ORDER[*]}" >&2
      exit 1
    fi
  done
fi

cd "$ROOT/architecture-generator"
for benchmark in "${TARGETS[@]}"; do
  reduce_axi="${REDUCE_AXI[$benchmark]}"
  echo "===== HardCilkEmitter $benchmark -r $reduce_axi ====="
  sbt "runMain HardCilk.HardCilkEmitter taskDescriptors/mfpga/${benchmark}.json -o ../HardCilk-output/ -g -c -r ${reduce_axi} -p"
done
