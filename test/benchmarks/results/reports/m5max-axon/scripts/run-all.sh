#!/usr/bin/env bash
#
# The comparison with Axon Framework 4 on the Apple M5 Max (D392) in one go: it waits until the machine is quiet (no
# other project's JMH lock, then the snapshot's quiet rule), checks it (the snapshot's preflight: no other JVM but those
# in ALLOW_JVM_PIDS), samples the host for the whole run, runs run.mjs's suites, and judges each measurement by the
# samples around it (window.py). About four hours.
#
#   ALLOW_JVM_PIDS="<pid>..." run-all.sh
#
# WOW_REPO names the checkout measured (by default the one this script is in), with the builds of `bench.sh build` and
# axon-server's `installDist`, and BENCH_OUT the raw output's root (by default its results/e2e/m5max-axon). The
# helpers are the snapshot's (m5max-snapshot/scripts/lib.sh, summarize.py).
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
export WOW_REPO="${WOW_REPO:-$(git -C "$here" rev-parse --show-toplevel)}"
JAVA_HOME="$(/usr/libexec/java_home -v 25)"
export JAVA_HOME
SNAPSHOT="$WOW_REPO/test/benchmarks/results/reports/m5max-snapshot/scripts"
# shellcheck source=../../m5max-snapshot/scripts/lib.sh
. "$SNAPSHOT/lib.sh"
OUT_ROOT="${BENCH_OUT:-$REPO/test/benchmarks/results/e2e/m5max-axon}"
LOCK=/tmp/cocache-jmh.lock

mkdir -p "$OUT_ROOT"
waited=0
while [ -e "$LOCK" ]; do
  if [ "$waited" -ge 7200 ]; then say "$LOCK stayed for 2 hours; not started"; exit 3; fi
  sleep 30
  waited=$((waited + 30))
done
say "waiting for a quiet host"
if ! wait_quiet; then say "the host stayed busy for $QUIET_MAX_SECONDS s; not started"; exit 3; fi
preflight "$WOW_INSTALL" "$AXON_INSTALL" || exit 1
record_environment "$OUT_ROOT"
host_record "$OUT_ROOT/host.txt" begin
sampler_start "$OUT_ROOT/host-load.tsv"
say "suites into $OUT_ROOT"
node "$here/run.mjs" all "$OUT_ROOT" 2>&1 | stamp >"$OUT_ROOT/run.log"
status=${PIPESTATUS[0]}
sampler_stop
host_record "$OUT_ROOT/host.txt" end
"$B" down >/dev/null 2>&1 || true
python3 -I "$here/window.py" "$SNAPSHOT/summarize.py" "$OUT_ROOT" >"$OUT_ROOT/window.md"
verdict=$?
say "done: run.mjs $status, window.py $verdict (window.md), the report in $OUT_ROOT/report.md"
exit "$status"
