#!/bin/bash
# D275 ABAB on MongoDB, one build: the merged groups off (A, wow.log.append-queue.merge=false) and on (B, the
# default), fresh sessions A B B A, 1 GB heap, 100,000 accounts. Each session: E1 and E3 at fixed rates, then E1 and
# E3 closed-loop sweeps with --growth=4.
set -uo pipefail
export JAVA_HOME=${JAVA_HOME:?a JDK 21}
S=${S:?the directory holding the install SM}
B=${BENCH:?the bench.sh of the tree whose load generator has --growth}
say() { echo "$(date +%T) $*"; }
n=0
for arm in A B B A; do n=$((n+1))
  O=$S/merge/$n-$arm-mongo; mkdir -p "$O"
  C="--version=v10 --backend=mongo --accounts=100000 --install=$S/SM"
  if [ $arm = A ]; then export SPRING_APPLICATION_JSON='{"wow.log.append-queue.merge":false}'; else unset SPRING_APPLICATION_JSON; fi
  say "$n $arm up"
  $B up $C --prime --seed-concurrency=128 --out="$O/up" > "$O/up.log" 2>&1 || { say "up failed"; $B down >/dev/null 2>&1; continue; }
  $B run --reuse $C --scenario=E1 --rate=${E1:-1720} --warm-up=30 --measure=60 --out="$O/e1" > "$O/e1.log" 2>&1 || say "e1 failed"
  $B run --reuse $C --scenario=E3 --rate=${E3:-700} --warm-up=30 --measure=60 --out="$O/e3" > "$O/e3.log" 2>&1 || say "e3 failed"
  $B saturate --reuse $C --scenario=E1 --max-concurrency=1024 --sweep --growth=4 --step=30 --warm-up=10 --out="$O/e1-sweep" > "$O/e1-sweep.log" 2>&1 || say "e1 sweep failed"
  $B saturate --reuse $C --scenario=E3 --max-concurrency=1024 --sweep --growth=4 --step=30 --warm-up=10 --out="$O/e3-sweep" > "$O/e3-sweep.log" 2>&1 || say "e3 sweep failed"
  $B down > /dev/null 2>&1; sleep 10
done
say done
