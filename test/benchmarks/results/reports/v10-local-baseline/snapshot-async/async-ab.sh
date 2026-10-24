#!/bin/bash
# ABAB on PostgreSQL: main (F2) against snapshot batches committed without waiting for the WAL (I).
set -uo pipefail
SP=${SP:?the working directory holding ab/ and wt-clean/}
S=$SP/ab
B=$SP/wt-clean/test/benchmarks/harness/bench.sh
say() { echo "$(date +%T) $*"; }
unset SPRING_APPLICATION_JSON
for round in 1 2 3; do for arm in F2 I; do
  O=$S/async/$round-$arm; mkdir -p "$O"
  C="--backend=postgres --accounts=100000 --install=$S/$arm"
  say "$round $arm up"
  $B up $C --prime --seed-concurrency=128 --out="$O/up" > "$O/up.log" 2>&1 || { say "up failed"; $B down >/dev/null 2>&1; continue; }
  $B saturate --reuse $C --scenario=E1 --max-concurrency=4 --sweep --out="$O/low" > "$O/low.log" 2>&1 || say "low failed"
  $B run --reuse $C --scenario=E1 --rate=1906 --warm-up=30 --measure=60 --out="$O/e1" > "$O/e1.log" 2>&1 || say "e1 failed"
  $B run --reuse $C --scenario=E3 --rate=800 --warm-up=30 --measure=60 --out="$O/e3" > "$O/e3.log" 2>&1 || say "e3 failed"
  $B down > /dev/null 2>&1; sleep 10
done; done
say done
