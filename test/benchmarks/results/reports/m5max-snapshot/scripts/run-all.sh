#!/usr/bin/env bash
#
# The whole snapshot: the single-node sessions, then the clusters, each script's output into <raw root>/<script>.log;
# then the summaries (each sweep's summary.md, the run's SUMMARY.md). About 95 minutes. A session run again replaces
# its earlier run (lib.sh); the summaries cover the sessions in place.
#   ALLOW_JVM_PIDS="<pid>..." run-all.sh     (WOW_REPO and BENCH_OUT as for 01-single-node.sh; ALLOW_JVM_PIDS in lib.sh)
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib.sh
. "$here/lib.sh"
OUT_ROOT="${BENCH_OUT:-$REPO/test/benchmarks/results/e2e/m5max-snapshot}"
export WOW_REPO="$REPO" BENCH_OUT="$OUT_ROOT"
mkdir -p "$OUT_ROOT"
"$here/01-single-node.sh" 2>&1 | tee -a "$OUT_ROOT/01-single-node.log"
"$here/02-three-nodes.sh" 2>&1 | tee -a "$OUT_ROOT/02-three-nodes.log"
python3 -I "$here/summarize.py" "$OUT_ROOT" --json="$OUT_ROOT/summary.json" >"$OUT_ROOT/SUMMARY.md"
say "done; SUMMARY.md and summary.json in $OUT_ROOT"
