#!/usr/bin/env bash
#
# The check before a long run, once per arm: one session of the server build SERVER_INSTALL names (else the
# checkout's) on one backend, 1,000 accounts, one closed-loop E1 step of 64 clients (5 s of warm-up, 10 s measured),
# then `summarize.py --check` on it. A void or disturbed step fails it at once, where run-all.sh would wait
# QUIET_MAX_SECONDS for a quiet host before each rerun. About two minutes; run it under the batch lock.
#
#   dry-run.sh [postgres|mongo]     (postgres by default; WOW_REPO and SERVER_INSTALL as for 01-single-node.sh,
#                                   BENCH_OUT the raw output's root, by default its results/e2e/m5max-dry-run)
set -uo pipefail

# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"
OUT_ROOT="${BENCH_OUT:-$REPO/test/benchmarks/results/e2e/m5max-dry-run}"
backend=${1:-postgres}
dir="$OUT_ROOT/1-S-$backend"
C=(--backend="$backend" --accounts=1000 --heap=1g ${INSTALL_ARGS[@]+"${INSTALL_ARGS[@]}"})

preflight "$WOW_INSTALL" || exit 1
record_environment "$OUT_ROOT"
session_up "$dir" "${C[@]}" || exit 1
measure saturate "$dir/e1-step" saturate.log "${C[@]}" --scenario=E1 --distribution=uniform \
  --start-concurrency=64 --max-concurrency=64 --step=10 --warm-up=5
status=$?
session_down "$dir"
if [ "$status" != 0 ]; then exit "$status"; fi
if ! verdict=$(python3 -I "$LIB_DIR/summarize.py" --check "$dir/e1-step"); then
  say "$verdict"
  exit 1
fi
say "no step void or disturbed; server build $(build_of "$WOW_INSTALL"), the measurement's: $(cat "$dir/e1-step/install.txt")"
