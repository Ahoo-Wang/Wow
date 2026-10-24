#!/usr/bin/env bash
set -uo pipefail

export JAVA_HOME="/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home"
REPO="/Users/ahoo/.gemini/antigravity/worktrees/Wow/v10_local_baseline_m4pro"
B="$REPO/test/benchmarks/harness/bench.sh"
OUT="/tmp/v10-local-baseline-m4pro/heap2g-runs"
mkdir -p "$OUT"

say() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') $*"
}

peak() {
  python3 -I -c "import json,sys;print(json.load(open(sys.argv[1]))['saturationThroughput'])" "$1/saturation.json"
}

rate() {
  python3 -I -c "import sys;print(round(float(sys.argv[1])*float(sys.argv[2])))" "$1" "$2"
}

ACCOUNTS=100000
HEAP=2g

# Postgres
say "=== Starting Heap 2g: Postgres ==="
$B up --backend=postgres --accounts="$ACCOUNTS" --heap="$HEAP" --prime --seed-concurrency=128 --out="$OUT/heap2g-postgres-up" > "$OUT/heap2g-postgres-up.log" 2>&1
say "Postgres 2g up finished"

say "Postgres 2g E1 uniform sweep"
$B saturate --reuse --backend=postgres --accounts="$ACCOUNTS" --heap="$HEAP" --scenario=E1 --distribution=uniform --max-concurrency=512 --sweep --out="$OUT/heap2g-postgres-E1-sweep" > "$OUT/heap2g-postgres-E1-sweep.log" 2>&1
P_PG_E1=$(peak "$OUT/heap2g-postgres-E1-sweep")
say "Postgres 2g E1 uniform sweep peak: $P_PG_E1/s"

say "Postgres 2g E1 uniform open-loop at 6904/s (1g 50% baseline)"
$B run --reuse --backend=postgres --accounts="$ACCOUNTS" --heap="$HEAP" --scenario=E1 --distribution=uniform --rate=6904 --warm-up=30 --measure=60 --out="$OUT/heap2g-postgres-E1-rate6904" > "$OUT/heap2g-postgres-E1-rate6904.log" 2>&1

say "Postgres 2g E3 uniform sweep"
$B saturate --reuse --backend=postgres --accounts="$ACCOUNTS" --heap="$HEAP" --scenario=E3 --distribution=uniform --max-concurrency=512 --sweep --out="$OUT/heap2g-postgres-E3-sweep" > "$OUT/heap2g-postgres-E3-sweep.log" 2>&1
P_PG_E3=$(peak "$OUT/heap2g-postgres-E3-sweep")
say "Postgres 2g E3 uniform sweep peak: $P_PG_E3/s"

say "Postgres 2g E3 uniform open-loop at 1330/s (1g 50% baseline)"
$B run --reuse --backend=postgres --accounts="$ACCOUNTS" --heap="$HEAP" --scenario=E3 --distribution=uniform --rate=1330 --warm-up=30 --measure=60 --out="$OUT/heap2g-postgres-E3-rate1330" > "$OUT/heap2g-postgres-E3-rate1330.log" 2>&1

$B down >/dev/null 2>&1 || true
say "=== Finished Heap 2g: Postgres ==="

# Mongo
say "=== Starting Heap 2g: Mongo ==="
$B up --backend=mongo --accounts="$ACCOUNTS" --heap="$HEAP" --prime --seed-concurrency=128 --out="$OUT/heap2g-mongo-up" > "$OUT/heap2g-mongo-up.log" 2>&1
say "Mongo 2g up finished"

say "Mongo 2g E1 uniform sweep"
$B saturate --reuse --backend=mongo --accounts="$ACCOUNTS" --heap="$HEAP" --scenario=E1 --distribution=uniform --max-concurrency=512 --sweep --out="$OUT/heap2g-mongo-E1-sweep" > "$OUT/heap2g-mongo-E1-sweep.log" 2>&1
P_MG_E1=$(peak "$OUT/heap2g-mongo-E1-sweep")
say "Mongo 2g E1 uniform sweep peak: $P_MG_E1/s"

say "Mongo 2g E1 uniform open-loop at 6277/s (1g 50% baseline)"
$B run --reuse --backend=mongo --accounts="$ACCOUNTS" --heap="$HEAP" --scenario=E1 --distribution=uniform --rate=6277 --warm-up=30 --measure=60 --out="$OUT/heap2g-mongo-E1-rate6277" > "$OUT/heap2g-mongo-E1-rate6277.log" 2>&1

say "Mongo 2g E3 uniform sweep"
$B saturate --reuse --backend=mongo --accounts="$ACCOUNTS" --heap="$HEAP" --scenario=E3 --distribution=uniform --max-concurrency=512 --sweep --out="$OUT/heap2g-mongo-E3-sweep" > "$OUT/heap2g-mongo-E3-sweep.log" 2>&1
P_MG_E3=$(peak "$OUT/heap2g-mongo-E3-sweep")
say "Mongo 2g E3 uniform sweep peak: $P_MG_E3/s"

say "Mongo 2g E3 uniform open-loop at 1608/s (1g 50% baseline)"
$B run --reuse --backend=mongo --accounts="$ACCOUNTS" --heap="$HEAP" --scenario=E3 --distribution=uniform --rate=1608 --warm-up=30 --measure=60 --out="$OUT/heap2g-mongo-E3-rate1608" > "$OUT/heap2g-mongo-E3-rate1608.log" 2>&1

$B down >/dev/null 2>&1 || true
say "=== Finished Heap 2g: Mongo ==="
say "=== ALL HEAP 2G BENCHMARKS FINISHED ==="
