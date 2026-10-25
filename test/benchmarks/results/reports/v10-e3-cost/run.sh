#!/bin/bash
# Roadmap §6 item 16: where an E3 command's cost goes, against E1's. One session per backend, the stores in memory
# (--tmpfs, so the disk's stalls stay out of the CPU), 1 GB heap, 100,000 accounts, a flight recording of the node:
# E1 at 1,720/s and E3 at 700/s, 30 s warm-up and 60 s measured each.
set -uo pipefail
export JAVA_HOME=${JAVA_HOME:?a JDK 21}
S=${S:?scratch}; B=${BENCH:?bench.sh}; I=${INSTALL:?install}
for backend in mongo postgres; do
  O=$S/e3/runs/$backend; mkdir -p "$O"
  C="--version=v10 --backend=$backend --accounts=100000 --install=$I --tmpfs"
  $B up $C --prime --seed-concurrency=128 --jfr --out="$O/up" > "$O/up.log" 2>&1 || { echo "up failed"; $B down >/dev/null 2>&1; continue; }
  $B run --reuse $C --scenario=E1 --rate=1720 --warm-up=30 --measure=60 --out="$O/e1" > "$O/e1.log" 2>&1
  $B run --reuse $C --scenario=E3 --rate=700 --warm-up=30 --measure=60 --out="$O/e3" > "$O/e3.log" 2>&1
  $B down > /dev/null 2>&1; sleep 10
  for p in 18080 19090; do lsof -tiTCP:$p -sTCP:LISTEN | xargs -r kill -9; done
  echo "$(date +%T) $backend done"
done
