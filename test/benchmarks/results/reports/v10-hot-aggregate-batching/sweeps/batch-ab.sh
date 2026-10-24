#!/bin/bash
# ABAB on one build: wow.executor.max-batch 1 (every command alone) against the default 32.
# Per session: the closed-loop saturation sweeps of E1 with one hot account (half the commands) and with Zipf keys,
# then E1 at a fixed rate with uniform keys (the per-command cost must not grow).
set -uo pipefail
export JAVA_HOME=${JAVA_HOME:?a JDK 21}
S=${S:?the scratch directory with the install}
B=$(git rev-parse --show-toplevel)/test/benchmarks/harness/bench.sh
say() { echo "$(date +%T) $*"; }
for round in ${ROUNDS:-1 2}; do for arm in ${ARMS:-b1 b32}; do for spec in ${SPECS:-postgres:1906 mongo:1720}; do
  IFS=: read -r be e1 <<<"$spec"
  O=$S/${OUTDIR:-batch}/$round-$arm-$be; mkdir -p "$O"
  C="--version=v10 --backend=$be --accounts=100000 --install=$S/I10"
  if [ $arm = b1 ]; then export SPRING_APPLICATION_JSON='{"wow.executor.max-batch":1}'; else unset SPRING_APPLICATION_JSON; fi
  say "$round $arm $be up"
  $B up $C --prime --seed-concurrency=128 --out="$O/up" > "$O/up.log" 2>&1 || { say "up failed"; $B down >/dev/null 2>&1; continue; }
  $B saturate --reuse $C --scenario=E1 --distribution=hot --hot-keys=1 --max-concurrency=256 --sweep --out="$O/hot" > "$O/hot.log" 2>&1 || say "hot failed"
  $B saturate --reuse $C --scenario=E1 --distribution=zipf --max-concurrency=256 --sweep --out="$O/zipf" > "$O/zipf.log" 2>&1 || say "zipf failed"
  $B run --reuse $C --scenario=E1 --rate=$e1 --warm-up=30 --measure=60 --out="$O/e1" > "$O/e1.log" 2>&1 || say "e1 failed"
  $B down > /dev/null 2>&1; sleep 10
done; done; done
say done
