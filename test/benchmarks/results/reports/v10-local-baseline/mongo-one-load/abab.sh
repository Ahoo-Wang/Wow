#!/bin/bash
# ABAB: arm A (main c58787552) and arm B (one-aggregation load), MongoDB, 100k accounts.
set -uo pipefail
S=${S:?the working directory holding A/, B/ and wt-bench/}
B=$S/wt-bench/test/benchmarks/harness/bench.sh
say() { echo "$(date +%T) $*"; }
for round in 1 2; do
  for arm in A B; do
    I=$S/$arm; O=$S/out/$round-$arm; mkdir -p "$O"
    C="--version=v10 --backend=mongo --accounts=100000 --install=$I"
    for sc in E1 E3; do
      say "$round $arm L1 $sc"
      $B component --backend=mongo --scenario=$sc --accounts=100000 --concurrency=16 --install=$I --out="$O/l1-$sc" > "$O/l1-$sc.log" 2>&1 || say "L1 failed"
    done
    say "$round $arm up"
    $B up $C --prime --seed-concurrency=128 --out="$O/up" > "$O/up.log" 2>&1 || { say "up failed, retrying"; $B down >/dev/null 2>&1; pkill -9 -f "scratchpad/ab/[AB]/lib" ; mv "$O/up.log" "$O/up-failed.log"; mv "$O/up" "$O/up-failed"; sleep 10; $B up $C --prime --seed-concurrency=128 --out="$O/up" > "$O/up.log" 2>&1 || say "up failed again"; }
    $B saturate --reuse $C --scenario=E1 --max-concurrency=4 --sweep --out="$O/low" > "$O/low.log" 2>&1 || say "low failed"
    $B run --reuse $C --scenario=E1 --rate=1720 --warm-up=30 --measure=60 --out="$O/e1" > "$O/e1.log" 2>&1 || say "e1 failed"
    $B run --reuse $C --scenario=E3 --rate=714 --warm-up=30 --measure=60 --out="$O/e3" > "$O/e3.log" 2>&1 || say "e3 failed"
    $B down > /dev/null 2>&1
    sleep 10
  done
done
say done
