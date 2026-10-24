#!/bin/bash
# ABAB on one build: the state cache at its default size (about 25,600 at a 1 GB heap) against 131,072.
set -uo pipefail
S=${S:?the working directory holding the installs and wt-bench/}
B=$S/wt-bench/test/benchmarks/harness/bench.sh
say() { echo "$(date +%T) $*"; }
for round in 1 2; do for arm in default large; do for spec in ${SPECS:-postgres:1906:800 mongo:1720:722}; do
  IFS=: read -r be e1 e3 <<<"$spec"
  O=$S/${OUTDIR:-cache}/$round-$arm-$be; mkdir -p "$O"
  C="--version=v10 --backend=$be --accounts=100000 --install=$S/${INSTALL:-D2}"
  if [ $arm = large ]; then export SPRING_APPLICATION_JSON='{"wow.state-cache.capacity":131072}'; else unset SPRING_APPLICATION_JSON; fi
  say "$round $arm $be up"
  $B up $C --prime --seed-concurrency=128 --out="$O/up" > "$O/up.log" 2>&1 || { say "up failed"; $B down >/dev/null 2>&1; continue; }
  $B saturate --reuse $C --scenario=E1 --max-concurrency=4 --sweep --out="$O/low" > "$O/low.log" 2>&1 || say "low failed"
  $B run --reuse $C --scenario=E1 --rate=$e1 --warm-up=30 --measure=60 --out="$O/e1" > "$O/e1.log" 2>&1 || say "e1 failed"
  $B run --reuse $C --scenario=E3 --rate=$e3 --warm-up=30 --measure=60 --out="$O/e3" > "$O/e3.log" 2>&1 || say "e3 failed"
  $B down > /dev/null 2>&1; sleep 10
done; done; done
say done
