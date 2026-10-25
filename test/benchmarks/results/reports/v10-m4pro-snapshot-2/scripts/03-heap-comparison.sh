#!/usr/bin/env bash
set -uo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPTS_DIR/common.sh"

run_backend_heaps() {
  local backend="$1"
  say "=== Section 3: 1 GB vs 2 GB Heap for $backend (Round 4) ==="

  # --- 1 GB Session (arm G1) ---
  local dir_g1="$OUT_ROOT/4-G1-$backend"
  mkdir -p "$dir_g1"
  say "[$backend] Starting 1 GB session (4-G1-$backend)..."
  if ! safe_up "$backend" "1g"; then
    say "[$backend 1g] up failed"
    return 1
  fi

  local C1="--backend=$backend --accounts=$ACCOUNTS --heap=1g"

  say "[$backend 1g] Running E1 uniform sweep (to 1024)..."
  local p_g1_sweep="$dir_g1/e1-sweep"
  if "$B" saturate --reuse $C1 --scenario=E1 --distribution=uniform --max-concurrency=1024 --sweep --growth=4 --step=30 --warm-up=10 --out="$p_g1_sweep"; then
    write_sweep_summary "$p_g1_sweep"
  else
    say "[$backend 1g] E1 sweep failed"
  fi

  local peak_g1 rate_g1_50
  peak_g1=$(get_saturation_peak "$p_g1_sweep/saturation.json")
  rate_g1_50=$(calc_rate "$peak_g1" "0.50")
  say "[$backend 1g] E1 sweep peak: $peak_g1/s, fixed rate (50%): $rate_g1_50/s"

  say "[$backend 1g] Running E1 fixed rate ($rate_g1_50/s)..."
  local p_g1_fixed="$dir_g1/e1-fixed"
  if [ "$backend" = mongo ]; then
    dump_mongo_stats "$p_g1_fixed/mongo-stats-before.json"
  fi

  "$B" run --reuse $C1 --scenario=E1 --distribution=uniform --rate="$rate_g1_50" --warm-up=30 --measure=60 --out="$p_g1_fixed" || say "[$backend 1g] e1-fixed failed"

  if [ "$backend" = mongo ]; then
    dump_mongo_stats "$p_g1_fixed/mongo-stats-after.json"
  fi

  safe_down

  # --- 2 GB Session (arm G2) ---
  local dir_g2="$OUT_ROOT/4-G2-$backend"
  mkdir -p "$dir_g2"
  say "[$backend] Starting 2 GB session (4-G2-$backend)..."
  if ! safe_up "$backend" "2g"; then
    say "[$backend 2g] up failed"
    return 1
  fi

  local C2="--backend=$backend --accounts=$ACCOUNTS --heap=2g"

  say "[$backend 2g] Running E1 uniform sweep (to 1024)..."
  local p_g2_sweep="$dir_g2/e1-sweep"
  if "$B" saturate --reuse $C2 --scenario=E1 --distribution=uniform --max-concurrency=1024 --sweep --growth=4 --step=30 --warm-up=10 --out="$p_g2_sweep"; then
    write_sweep_summary "$p_g2_sweep"
  else
    say "[$backend 2g] E1 sweep failed"
  fi

  say "[$backend 2g] Running E1 fixed rate using 1 GB 50% rate ($rate_g1_50/s)..."
  local p_g2_fixed="$dir_g2/e1-fixed"
  if [ "$backend" = mongo ]; then
    dump_mongo_stats "$p_g2_fixed/mongo-stats-before.json"
  fi

  "$B" run --reuse $C2 --scenario=E1 --distribution=uniform --rate="$rate_g1_50" --warm-up=30 --measure=60 --out="$p_g2_fixed" || say "[$backend 2g] e1-fixed failed"

  if [ "$backend" = mongo ]; then
    dump_mongo_stats "$p_g2_fixed/mongo-stats-after.json"
  fi

  safe_down
  say "=== Section 3: $backend Complete ==="
}

say "Starting Section 3: 1 GB vs 2 GB Heap"
run_backend_heaps postgres
run_backend_heaps mongo
say "Section 3 Finished"
