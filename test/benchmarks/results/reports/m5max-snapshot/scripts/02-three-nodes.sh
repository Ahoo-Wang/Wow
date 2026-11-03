#!/usr/bin/env bash
#
# Three nodes on this one machine: one session per backend (100,000 accounts, primed, 1 GB heap a node, the load
# spread over the three public ports), in it closed-loop sweeps of E1 and E3 from 1 to 4,096 clients, then E1 and E3
# at a fixed 1,024 clients (15 s of warm-up, 60 s measured). The session's log digest counts the nodes' warnings and
# errors by step, among them the internal channel's (X-55).
#
#   02-three-nodes.sh [postgres] [mongo]     (both by default; one of them reruns its session)
#
# WOW_REPO and BENCH_OUT as for 01-single-node.sh.
set -uo pipefail

# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"
OUT_ROOT="${BENCH_OUT:-$REPO/test/benchmarks/results/e2e/m5max-snapshot}"
FIXED=(--start-concurrency=1024 --max-concurrency=1024 --step=60 --warm-up=15)

three_nodes() {
  local backend=$1 dir="$OUT_ROOT/3-N-$1"
  local C=(--backend="$backend" --nodes=3 --accounts="$ACCOUNTS" --heap=1g)
  session_up "$dir" "${C[@]}" || return 1
  measure_sweep "$dir/e1-sweep" "${C[@]}" --scenario=E1 --distribution=uniform "${SWEEP[@]}"
  measure_sweep "$dir/e3-sweep" "${C[@]}" --scenario=E3 --distribution=uniform "${SWEEP[@]}"
  measure_sweep "$dir/e1-c1024" "${C[@]}" --scenario=E1 --distribution=uniform "${FIXED[@]}"
  measure_sweep "$dir/e3-c1024" "${C[@]}" --scenario=E3 --distribution=uniform "${FIXED[@]}"
  session_down "$dir"
}

backends=("$@")
if [ ${#backends[@]} = 0 ]; then backends=(postgres mongo); fi
preflight "$WOW_INSTALL" || exit 1
record_environment "$OUT_ROOT"
for backend in "${backends[@]}"; do
  three_nodes "$backend" || say "the $backend cluster failed"
done
say "three nodes done"
