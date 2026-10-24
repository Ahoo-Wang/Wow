#!/bin/bash
# The local baseline: stage 0 (A/A noise of a fixed-rate run, across server restarts and within a session), stage 1
# (cost attribution per backend and scenario on one session: a sweep, then 25%, 50% and 75% of its peak), then L1.
set -uo pipefail
# Needs JAVA_HOME at a JDK 21; writes to $OUT. The analysis scripts beside it read $OUT: cost.py <out>, threads.py and
# buckets.py <recording>...
REPO=$(git rev-parse --show-toplevel)
B=$REPO/test/benchmarks/harness/bench.sh
OUT=${OUT:-$REPO/test/benchmarks/results/e2e/baseline}
mkdir -p "$OUT"
ACCOUNTS=100000
say() { echo "$(date +%T) $*"; }
peak() { python3 -I -c "import json,sys;print(json.load(open(sys.argv[1]))['saturationThroughput'])" "$1/saturation.json"; }
rate() { python3 -I -c "import sys;print(round(float(sys.argv[1])*float(sys.argv[2])))" "$1" "$2"; }

common() { echo "--version=v10 --backend=$1 --accounts=$ACCOUNTS"; }

# Stage 0: PostgreSQL E1 at a fixed rate, three sessions of two runs each.
stage0() {
  local s r
  for s in 1 2 3; do
    say "stage 0 session $s"
    $B up $(common postgres) --prime --seed-concurrency=128 --out="$OUT/s0-up-$s" > "$OUT/s0-up-$s.log" 2>&1 || say "up failed"
    if [ "$s" = 1 ]; then
      $B saturate --reuse $(common postgres) --scenario=E1 --max-concurrency=512 --sweep --out="$OUT/s0-sweep" \
        > "$OUT/s0-sweep.log" 2>&1
      S0_RATE=$(rate "$(peak "$OUT/s0-sweep")" 0.5)
      say "stage 0 rate $S0_RATE/s"
    fi
    for r in 1 2; do
      $B run --reuse $(common postgres) --scenario=E1 --rate="$S0_RATE" --warm-up=30 --measure=60 \
        --out="$OUT/s0-$s-$r" > "$OUT/s0-$s-$r.log" 2>&1 || say "run failed"
    done
    $B down
  done
}

# Stage 1: one session per backend, with recordings; per scenario a sweep, then three fixed rates.
stage1() {
  local backend=$1 key scenario distribution p share
  say "stage 1 $backend up"
  $B up $(common "$backend") --prime --jfr --seed-concurrency=128 --out="$OUT/s1-$backend-up" \
    > "$OUT/s1-$backend-up.log" 2>&1 || say "up failed"
  for key in E1:uniform E1:zipf E3:uniform; do
    scenario=${key%%:*}; distribution=${key##*:}
    say "stage 1 $backend $scenario $distribution sweep"
    $B saturate --reuse $(common "$backend") --scenario="$scenario" --distribution="$distribution" --max-concurrency=512 \
      --sweep --out="$OUT/s1-$backend-$scenario-$distribution-sweep" > "$OUT/s1-$backend-$scenario-$distribution-sweep.log" 2>&1
    p=$(peak "$OUT/s1-$backend-$scenario-$distribution-sweep")
    for share in 0.25 0.5 0.75; do
      say "stage 1 $backend $scenario $distribution at $share of $p/s"
      $B run --reuse $(common "$backend") --scenario="$scenario" --distribution="$distribution" --rate="$(rate "$p" "$share")" \
        --warm-up=30 --measure=60 --out="$OUT/s1-$backend-$scenario-$distribution-$share" \
        > "$OUT/s1-$backend-$scenario-$distribution-$share.log" 2>&1 || say "run failed"
    done
  done
  $B down
}

# L1: the kernel and one store in one JVM, at three concurrencies, for its counts and its ceiling.
l1() {
  local backend scenario c
  for backend in postgres mongo; do
    for scenario in E1 E3; do
      for c in 16 64 256; do
        say "L1 $backend $scenario at $c"
        $B component --backend="$backend" --scenario="$scenario" --accounts=$ACCOUNTS --concurrency=$c \
          --out="$OUT/l1-$backend-$scenario-$c" > "$OUT/l1-$backend-$scenario-$c.log" 2>&1 || say "L1 failed"
      done
    done
  done
}

say "start on $(git -C "$REPO" rev-parse --short HEAD)"
stage0
stage1 postgres
stage1 mongo
l1
say "done"
