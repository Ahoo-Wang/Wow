#!/usr/bin/env bash
set -uo pipefail

export JAVA_HOME="/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home"
REPO="/Users/ahoo/.gemini/antigravity/worktrees/Wow/v10_local_baseline_m4pro"
B="$REPO/test/benchmarks/harness/bench.sh"
OUT="/tmp/v10-local-baseline-m4pro/runs"
PROGRESS_LOG="/tmp/v10-local-baseline-m4pro/progress.log"
mkdir -p "$OUT"

say() {
  local msg="$(date '+%Y-%m-%d %H:%M:%S') $*"
  echo "$msg"
  echo "$msg" >> "$PROGRESS_LOG"
}

peak() {
  python3 -I -c "import json,sys;print(json.load(open(sys.argv[1]))['saturationThroughput'])" "$1/saturation.json"
}

rate() {
  python3 -I -c "import sys;print(round(float(sys.argv[1])*float(sys.argv[2])))" "$1" "$2"
}

dump_pg_stats() {
  local out_dir=$1
  {
    echo "# Top statements by total execution time (pg_stat_statements, since the run's warm-up began)"
    docker exec wow-e2e-bench-postgres psql -U postgres -d transfer -X -q -c "select calls, round(total_exec_time::numeric, 1) as total_ms, round(mean_exec_time::numeric, 3) as mean_ms, round(max_exec_time::numeric, 1) as max_ms, rows, left(regexp_replace(query, '\s+', ' ', 'g'), 140) as query from pg_stat_statements order by total_exec_time desc limit 15" 2>&1 || true
    echo "# Checkpoints (pg_stat_checkpointer)"
    docker exec wow-e2e-bench-postgres psql -U postgres -d transfer -X -q -c "select * from pg_stat_checkpointer" 2>&1 || true
    echo "# Waits now (pg_stat_activity)"
    docker exec wow-e2e-bench-postgres psql -U postgres -d transfer -X -q -c "select wait_event_type, wait_event, count(*) from pg_stat_activity group by 1, 2 order by 3 desc" 2>&1 || true
  } > "$out_dir/postgres-stats.txt" 2>&1 || true
}

ACCOUNTS=100000

safe_up() {
  local backend=$1 out=$2
  local attempt=1
  while [ $attempt -le 2 ]; do
    say "safe_up attempt $attempt: backend=$backend out=$out"
    if $B up --backend="$backend" --accounts="$ACCOUNTS" --prime --seed-concurrency=128 --out="$out" > "$out.log" 2>&1; then
      say "safe_up succeeded for $backend"
      return 0
    else
      say "safe_up attempt $attempt failed for $backend; running down and retrying..."
      $B down >/dev/null 2>&1 || true
      sleep 5
      attempt=$((attempt + 1))
    fi
  done
  say "safe_up permanently failed for $backend, skipping this group"
  return 1
}

# ----------------- Stage 0 -----------------
stage0() {
  say "=== Starting Stage 0 (A/A Noise) ==="
  if safe_up postgres "$OUT/s0-sweep-up"; then
    say "stage 0: sweep to find peak"
    if $B saturate --reuse --backend=postgres --accounts="$ACCOUNTS" --scenario=E1 --distribution=uniform --max-concurrency=512 --sweep --out="$OUT/s0-sweep" > "$OUT/s0-sweep.log" 2>&1; then
      S0_PEAK=$(peak "$OUT/s0-sweep")
      S0_RATE=$(rate "$S0_PEAK" 0.5)
      say "stage 0 peak is $S0_PEAK/s, 50% rate is $S0_RATE/s"
    else
      say "stage 0 sweep failed"
    fi
    $B down >/dev/null 2>&1 || true
  else
    say "stage 0 sweep session up failed, exiting stage0"
    return 1
  fi

  for s in 1 2 3; do
    say "stage 0 session $s of 3"
    if safe_up postgres "$OUT/s0-up-$s"; then
      for r in 1 2; do
        say "stage 0 session $s run $r at $S0_RATE/s"
        $B run --reuse --backend=postgres --accounts="$ACCOUNTS" --scenario=E1 --distribution=uniform --rate="$S0_RATE" --warm-up=30 --measure=60 --out="$OUT/s0-$s-$r" > "$OUT/s0-$s-$r.log" 2>&1 || say "stage 0 run $s-$r failed"
        dump_pg_stats "$OUT/s0-$s-$r"
      done
      $B down >/dev/null 2>&1 || true
    else
      say "stage 0 session $s up failed, skipping"
    fi
  done
  say "=== Finished Stage 0 ==="
}

# ----------------- Stage 1 -----------------
stage1_backend() {
  local backend=$1
  say "=== Starting Stage 1: $backend ==="
  if ! safe_up "$backend" "$OUT/s1-$backend-up"; then
    say "stage 1 $backend up failed, skipping"
    return 1
  fi

  for key in E1:uniform E1:zipf E3:uniform; do
    local scenario=${key%%:*}
    local distribution=${key##*:}
    say "stage 1 $backend $scenario $distribution sweep"
    local sweep_out="$OUT/s1-$backend-$scenario-$distribution-sweep"
    if $B saturate --reuse --backend="$backend" --accounts="$ACCOUNTS" --scenario="$scenario" --distribution="$distribution" --max-concurrency=512 --sweep --out="$sweep_out" > "$sweep_out.log" 2>&1; then
      local p=$(peak "$sweep_out")
      say "stage 1 $backend $scenario $distribution peak is $p/s"
      for share in 0.25 0.5 0.75; do
        local r=$(rate "$p" "$share")
        say "stage 1 $backend $scenario $distribution at $share of peak ($r/s)"
        local run_out="$OUT/s1-$backend-$scenario-$distribution-$share"
        $B run --reuse --backend="$backend" --accounts="$ACCOUNTS" --scenario="$scenario" --distribution="$distribution" --rate="$r" --warm-up=30 --measure=60 --out="$run_out" > "$run_out.log" 2>&1 || say "stage 1 $backend $scenario $distribution $share failed"
        if [ "$backend" = postgres ]; then
          dump_pg_stats "$run_out"
        fi
      done
    else
      say "stage 1 $backend $scenario $distribution sweep failed, skipping open-loop runs"
    fi
  done

  $B down >/dev/null 2>&1 || true
  say "=== Finished Stage 1: $backend ==="
}

stage1() {
  stage1_backend postgres
  stage1_backend mongo
}

# ----------------- L1 -----------------
l1() {
  say "=== Starting L1 (Component Benchmarks) ==="
  for backend in postgres mongo; do
    for scenario in E1 E3; do
      for c in 16 64 256; do
        say "L1 $backend $scenario concurrency $c"
        local l1_out="$OUT/l1-$backend-$scenario-$c"
        $B component --backend="$backend" --scenario="$scenario" --accounts="$ACCOUNTS" --concurrency="$c" --out="$l1_out" > "$l1_out.log" 2>&1 || say "L1 $backend $scenario $c failed"
      done
    done
  done
  say "=== Finished L1 ==="
}

say "Baseline runner started at commit $(git -C "$REPO" rev-parse --short HEAD)"
stage0
stage1
l1
say "=== ALL BENCHMARKS COMPLETED SUCCESSFULLY ==="
