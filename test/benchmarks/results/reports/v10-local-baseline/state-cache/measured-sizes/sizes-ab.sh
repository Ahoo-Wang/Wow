#!/bin/bash
# D274 ABAB: main (A) against the measured state sizes (B), sessions A B B A per backend, 1 GB heap, 100,000 accounts.
# Each session: E1 uniform at a fixed rate on the fresh cache, an E1 uniform sweep to 1024 (30 s a step, as on the
# M4 Pro), E1 uniform at the fixed rate again, then the cache's states counted in a heap histogram.
set -uo pipefail
export JAVA_HOME=${JAVA_HOME:?a JDK 21}
S=${S:?the directory holding the installs SA and SB}
B=$(git rev-parse --show-toplevel)/test/benchmarks/harness/bench.sh
say() { echo "$(date +%T) $*"; }
n=0
for arm in A B B A; do n=$((n+1)); for spec in ${SPECS:-postgres:1906 mongo:1720}; do
  IFS=: read -r be e1 <<<"$spec"
  O=$S/sizes/$n-$arm-$be; mkdir -p "$O"
  C="--version=v10 --backend=$be --accounts=100000 --install=$S/S$arm"
  say "$n $arm $be up"
  $B up $C --prime --seed-concurrency=128 --out="$O/up" > "$O/up.log" 2>&1 || { say "up failed"; $B down >/dev/null 2>&1; continue; }
  $B run --reuse $C --scenario=E1 --rate=$e1 --warm-up=30 --measure=60 --out="$O/fresh" > "$O/fresh.log" 2>&1 || say "fresh failed"
  $B saturate --reuse $C --scenario=E1 --max-concurrency=1024 --sweep --step=30 --warm-up=10 --out="$O/sweep" > "$O/sweep.log" 2>&1 || say "sweep failed"
  $B run --reuse $C --scenario=E1 --rate=$e1 --warm-up=30 --measure=60 --out="$O/after" > "$O/after.log" 2>&1 || say "after failed"
  "$JAVA_HOME/bin/jcmd" "$(pgrep -f benchmark-server | head -1)" GC.class_histogram > "$O/after.hist" 2>&1 || true
  $B down > /dev/null 2>&1; sleep 10
done; done
say done
