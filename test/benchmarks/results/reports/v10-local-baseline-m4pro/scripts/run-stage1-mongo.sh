#!/usr/bin/env bash
set -uo pipefail

export JAVA_HOME="/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home"
unset WOW_BENCH_PG_PRELOAD

REPO="/Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun"
B="$REPO/test/benchmarks/harness/bench.sh"
OUT="$REPO/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun"
ACCOUNTS=100000

say() {
  local msg="$(date '+%Y-%m-%d %H:%M:%S') $*"
  echo "$msg"
  echo "$msg" >> "/tmp/m4pro-rerun.log"
}

peak() {
  python3 -I -c "import json,sys;print(json.load(open(sys.argv[1]))['saturationThroughput'])" "$1/saturation.json"
}

rate() {
  python3 -I -c "import sys;print(round(float(sys.argv[1])*float(sys.argv[2])))" "$1" "$2"
}

say "=== STARTING SUPPLEMENTARY STAGE 1 MONGO RUNS ==="

# Clean any existing containers first
$B down >/dev/null 2>&1 || true

say "safe_up mongo for stage 1"
$B up --backend=mongo --accounts="$ACCOUNTS" --heap=1g --prime --seed-concurrency=128 --out="$OUT/s1-mongo-up" > "$OUT/s1-mongo-up.log" 2>&1

for key in E1:uniform E1:zipf E3:uniform; do
  scenario=${key%%:*}
  distribution=${key##*:}
  max_c=512
  if [ "$scenario" = "E1" ] && [ "$distribution" = "uniform" ]; then max_c=1024; fi

  say "Stage 1 mongo $scenario $distribution sweep (max_concurrency=$max_c)"
  sweep_out="$OUT/s1-mongo-$scenario-$distribution-sweep"
  $B saturate --reuse --backend=mongo --accounts="$ACCOUNTS" --heap=1g --scenario="$scenario" --distribution="$distribution" \
    --sweep --step=30 --warm-up=10 --max-concurrency="$max_c" --out="$sweep_out" > "$sweep_out.log" 2>&1
  p=$(peak "$sweep_out")
  say "Stage 1 mongo $scenario $distribution peak: $p/s"

  for share in 0.25 0.5 0.75; do
    r=$(rate "$p" "$share")
    say "Stage 1 mongo $scenario $distribution at $share ($r/s)"
    run_out="$OUT/s1-mongo-$scenario-$distribution-$share"
    $B run --reuse --backend=mongo --accounts="$ACCOUNTS" --heap=1g --scenario="$scenario" --distribution="$distribution" \
      --rate="$r" --warm-up=30 --measure=60 --out="$run_out" > "$run_out.log" 2>&1 || say "$run_out failed"
  done
done

$B down >/dev/null 2>&1 || true
say "=== FINISHED SUPPLEMENTARY STAGE 1 MONGO RUNS ==="
