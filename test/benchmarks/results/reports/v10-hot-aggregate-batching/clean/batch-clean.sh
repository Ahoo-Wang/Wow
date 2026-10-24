#!/bin/bash
# The per-command cost alone: fresh sessions, uniform E1 at a fixed rate first, then a low-concurrency hot sweep
# (1 to 8), no high-concurrency sweep before them. Order b1 b32 b32 b1 per backend.
set -uo pipefail
export JAVA_HOME=${JAVA_HOME:?a JDK 21}
S=${S:?the scratch directory with the install}
B=$(git rev-parse --show-toplevel)/test/benchmarks/harness/bench.sh
say() { echo "$(date +%T) $*"; }
n=0
for arm in b1 b32 b32 b1; do n=$((n+1)); for spec in postgres:1906 mongo:1720; do
  IFS=: read -r be e1 <<<"$spec"
  O=$S/batch-clean/$n-$arm-$be; mkdir -p "$O"
  C="--version=v10 --backend=$be --accounts=100000 --install=$S/I10"
  if [ $arm = b1 ]; then export SPRING_APPLICATION_JSON='{"wow.executor.max-batch":1}'; else unset SPRING_APPLICATION_JSON; fi
  say "$n $arm $be up"
  $B up $C --prime --seed-concurrency=128 --out="$O/up" > "$O/up.log" 2>&1 || { say "up failed"; $B down >/dev/null 2>&1; continue; }
  $B run --reuse $C --scenario=E1 --rate=$e1 --warm-up=30 --measure=60 --out="$O/e1" > "$O/e1.log" 2>&1 || say "e1 failed"
  $B saturate --reuse $C --scenario=E1 --distribution=hot --hot-keys=1 --max-concurrency=8 --sweep --step=20 --out="$O/low" > "$O/low.log" 2>&1 || say "low failed"
  $B down > /dev/null 2>&1; sleep 10
done; done
say done
