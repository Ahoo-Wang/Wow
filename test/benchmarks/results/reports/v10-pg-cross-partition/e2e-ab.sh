#!/bin/bash
# Roadmap §6 item 15 (D280) end to end: main (A) against the branch (B) on PostgreSQL, one node, the store on disk
# (no --tmpfs: commits and WAL syncs are what it changes), 1 GB heap, 100,000 accounts, fresh sessions A B B A:
# E1 at 2,500/s and E3 at 700/s (20 s warm-up, 40 s measured), then an E1 uniform sweep to 1,024 (growth 4, 30 s a
# step) for the peak.
set -uo pipefail
export JAVA_HOME=${JAVA_HOME:?a JDK 21}
S=${S:?scratch}; B=${BENCH:?bench.sh}
say() { echo "$(date +%T) $*"; }
n=0
for arm in A B B A; do n=$((n+1))
  O=$S/pgx/e2e/$n-$arm-postgres; mkdir -p "$O"
  case $arm in A) I=$S/SM-main ;; B) I=$S/SM-pgx ;; esac
  C="--version=v10 --backend=postgres --accounts=100000 --install=$I"
  say "$n $arm up"
  $B up $C --prime --seed-concurrency=128 --out="$O/up" > "$O/up.log" 2>&1 || { say "up failed"; $B down >/dev/null 2>&1; continue; }
  $B run --reuse $C --scenario=E1 --rate=2500 --warm-up=20 --measure=40 --out="$O/e1" > "$O/e1.log" 2>&1 || say "e1 failed"
  $B run --reuse $C --scenario=E3 --rate=700 --warm-up=20 --measure=40 --out="$O/e3" > "$O/e3.log" 2>&1 || say "e3 failed"
  $B saturate --reuse $C --scenario=E1 --distribution=uniform --max-concurrency=1024 --sweep --growth=4 --step=30 --warm-up=10 --out="$O/e1-sweep" > "$O/e1-sweep.log" 2>&1 || say "sweep failed"
  $B down > /dev/null 2>&1; sleep 10
  for p in 18080 19090; do lsof -tiTCP:$p -sTCP:LISTEN | xargs -r kill -9; done
done
say done
