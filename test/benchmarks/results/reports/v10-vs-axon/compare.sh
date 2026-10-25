#!/bin/bash
# The industry comparison (user 2026-10-25): Wow against Axon Framework 4 on PostgreSQL, one node, the store on disk,
# 1 GB heap, 100,000 accounts primed, fresh sessions W A A W (W: Wow with Kafka off, a blank broker address, so no
# state stream is published; A:
# Axon with its JDBC event storage engine, no Axon Server), then K K (Wow as the other reports run it, publishing to
# Kafka). Each session: E1 at 2,500/s and E3 at 700/s (20 s warm-up, 40 s measured), then E1 and E3 closed-loop
# sweeps to 1,024 (growth 4, 30 s a step) for the peaks.
#
#   S=<scratch> BENCH=test/benchmarks/harness/bench.sh WOW=<benchmark-server install> AXON=<axon-server install> \
#     [ARMS="W A A W K K"] [ROUND=vs-axon] compare.sh
#
# The sessions' checks run E1 and E3 alone (--mix): the Axon server serves those two scenarios only.
set -uo pipefail
export JAVA_HOME=${JAVA_HOME:?a JDK 21}
S=${S:?scratch}; B=${BENCH:?bench.sh}; WOW=${WOW:?the Wow server install}; AXON=${AXON:?the Axon server install}
ARMS=${ARMS:-W A A W K K}; ROUND=${ROUND:-vs-axon}
say() { echo "$(date +%T) $*"; }
n=0
for arm in $ARMS; do n=$((n+1))
  O=$S/$ROUND/$n-$arm-postgres; mkdir -p "$O"
  case $arm in
    # Not wow.topics.in-memory: those topics keep every record in the JVM (local development), and a 1 GB node ran
    # out of heap in the first E1 sweep.
    W) C=(--install="$WOW" "--server-args=--wow.kafka.bootstrap-servers=") ;;
    A) C=(--install="$AXON") ;;
    K) C=(--install="$WOW") ;;
  esac
  C+=(--backend=postgres --accounts=100000)
  say "$n $arm up"
  $B up "${C[@]}" --mix=E1,E3 --prime --seed-concurrency=128 --out="$O/up" > "$O/up.log" 2>&1 || { say "up failed"; $B down >/dev/null 2>&1; continue; }
  $B run --reuse "${C[@]}" --scenario=E1 --rate=2500 --warm-up=20 --measure=40 --out="$O/e1" > "$O/e1.log" 2>&1 || say "e1 failed"
  $B run --reuse "${C[@]}" --scenario=E3 --rate=700 --warm-up=20 --measure=40 --out="$O/e3" > "$O/e3.log" 2>&1 || say "e3 failed"
  $B saturate --reuse "${C[@]}" --scenario=E1 --distribution=uniform --max-concurrency=1024 --sweep --growth=4 --step=30 --warm-up=10 --out="$O/e1-sweep" > "$O/e1-sweep.log" 2>&1 || say "e1 sweep failed"
  $B saturate --reuse "${C[@]}" --scenario=E3 --distribution=uniform --max-concurrency=1024 --sweep --growth=4 --step=30 --warm-up=10 --out="$O/e3-sweep" > "$O/e3-sweep.log" 2>&1 || say "e3 sweep failed"
  $B down > /dev/null 2>&1; sleep 10
  for p in 18080 19090; do lsof -tiTCP:$p -sTCP:LISTEN | xargs -r kill -9; done
done
say done
