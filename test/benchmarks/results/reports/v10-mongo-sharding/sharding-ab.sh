#!/bin/bash
# D277 on MongoDB, one node: main (A, 6da8fd2c7) against this branch (B), the stores in memory (--tmpfs), fresh
# sessions A B B A, 1 GB heap, 100,000 accounts: E1 and E3 at fixed rates, their latencies and costs per operation.
set -uo pipefail
export JAVA_HOME=${JAVA_HOME:?a JDK 21}
S=${S:?the directory holding the installs SM-main and SM-d277}
B=${BENCH:?the bench.sh to run}
say() { echo "$(date +%T) $*"; }
n=0
for arm in A B B A; do n=$((n+1))
  O=$S/sharding-ab/$n-$arm-mongo; mkdir -p "$O"
  case $arm in A) I=$S/SM-main ;; B) I=$S/SM-d277 ;; esac
  C="--version=v10 --backend=mongo --accounts=100000 --install=$I --tmpfs"
  say "$n $arm up"
  $B up $C --prime --seed-concurrency=128 --out="$O/up" > "$O/up.log" 2>&1 || { say "up failed"; $B down >/dev/null 2>&1; continue; }
  $B run --reuse $C --scenario=E1 --rate=1720 --warm-up=30 --measure=60 --out="$O/e1" > "$O/e1.log" 2>&1 || say "e1 failed"
  $B run --reuse $C --scenario=E3 --rate=700 --warm-up=30 --measure=60 --out="$O/e3" > "$O/e3.log" 2>&1 || say "e3 failed"
  $B down > /dev/null 2>&1; sleep 10
done
say done
