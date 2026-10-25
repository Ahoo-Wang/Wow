#!/bin/bash
# Roadmap §6 item 20: wow.state-cache.recent-requests 256 (A, the default before) against 32 (B), one build, MongoDB
# in memory (--tmpfs), 1 GB heap, 100,000 accounts, fresh sessions A B B A. Each session ages the node first with the
# E1 uniform, Zipf and hot sweeps (growth 4, 30 s a step), which reproduce the cache's misses (roadmap §6 item 20; a
# closed loop of uniform E1 alone did not: the skewed keys grow the rings), then runs E1 and E3 at fixed rates (20 s
# warm-up, 40 s measured): the steady state of a node that has served sustained traffic. Fresh sessions need no arm:
# their rings hold fewer than 32 requests.
set -uo pipefail
export JAVA_HOME=${JAVA_HOME:?a JDK 21}
S=${S:?the directory holding the install}
B=${BENCH:?the bench.sh to run}
I=${INSTALL:?the server install}
say() { echo "$(date +%T) $*"; }
n=0
for arm in A B B A; do n=$((n+1))
  O=$S/rr/ab/$n-$arm-mongo; mkdir -p "$O"
  case $arm in
    A) export SPRING_APPLICATION_JSON='{"wow.state-cache.recent-requests":256}' ;;
    B) export SPRING_APPLICATION_JSON='{"wow.state-cache.recent-requests":32}' ;;
  esac
  C="--version=v10 --backend=mongo --accounts=100000 --install=$I --tmpfs"
  say "$n $arm up"
  $B up $C --prime --seed-concurrency=128 --out="$O/up" > "$O/up.log" 2>&1 || { say "up failed"; $B down >/dev/null 2>&1; continue; }
  for d in uniform zipf hot; do
    extra=""; [ $d = hot ] && extra="--hot-keys=1"
    $B saturate --reuse $C --scenario=E1 --distribution=$d $extra --max-concurrency=1024 --sweep --growth=4 --step=30 --warm-up=10 --out="$O/e1-$d-sweep" > "$O/e1-$d-sweep.log" 2>&1 || say "$d sweep failed"
  done
  $B run --reuse $C --scenario=E1 --rate=1720 --warm-up=20 --measure=40 --out="$O/e1-after" > "$O/e1-after.log" 2>&1 || say "e1-after failed"
  $B run --reuse $C --scenario=E3 --rate=700 --warm-up=20 --measure=40 --out="$O/e3-after" > "$O/e3-after.log" 2>&1 || say "e3-after failed"
  $B down > /dev/null 2>&1; sleep 10
  for p in 18080 19090; do lsof -tiTCP:$p -sTCP:LISTEN | xargs -r kill -9; done
done
say done
