#!/bin/bash
# D275's committers on MongoDB, one build: merging off (A), one committer (B, K=1) and four (C, K=4), fresh sessions
# A B C C B A, 1 GB heap, 100,000 accounts: E1 and E3 at fixed rates, their latencies and costs per operation.
set -uo pipefail
export JAVA_HOME=${JAVA_HOME:?a JDK 21}
S=${S:?the directory holding the install SM}
B=${BENCH:?the bench.sh to run}
say() { echo "$(date +%T) $*"; }
n=0
for arm in A B C C B A; do n=$((n+1))
  O=$S/mergers/$n-$arm-mongo; mkdir -p "$O"
  C="--version=v10 --backend=mongo --accounts=100000 --install=$S/SM"
  case $arm in
    A) export SPRING_APPLICATION_JSON='{"wow.log.append-queue.merge":false}' ;;
    B) export SPRING_APPLICATION_JSON='{"wow.log.append-queue.mergers":1}' ;;
    C) export SPRING_APPLICATION_JSON='{"wow.log.append-queue.mergers":4}' ;;
  esac
  say "$n $arm up"
  $B up $C --prime --seed-concurrency=128 --out="$O/up" > "$O/up.log" 2>&1 || { say "up failed"; $B down >/dev/null 2>&1; continue; }
  $B run --reuse $C --scenario=E1 --rate=1720 --warm-up=30 --measure=60 --out="$O/e1" > "$O/e1.log" 2>&1 || say "e1 failed"
  $B run --reuse $C --scenario=E3 --rate=700 --warm-up=30 --measure=60 --out="$O/e3" > "$O/e3.log" 2>&1 || say "e3 failed"
  $B down > /dev/null 2>&1; sleep 10
done
say done
