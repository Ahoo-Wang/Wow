#!/usr/bin/env bash
set -uo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPTS_DIR/common.sh"

run_backend() {
  local backend="$1"
  local session_dir="$OUT_ROOT/1-S-$backend"
  mkdir -p "$session_dir"
  say "=== Section 1: Starting Backend $backend (Session 1-S-$backend) ==="

  if ! safe_up "$backend" "1g"; then
    say "Skipping backend $backend due to safe_up failure"
    return 1
  fi

  local C="--backend=$backend --accounts=$ACCOUNTS --heap=1g"

  # 1. Sweeps
  say "[$backend] Running E1 uniform sweep (to 1024)..."
  local p_e1_sweep="$session_dir/e1-sweep"
  if "$B" saturate --reuse $C --scenario=E1 --distribution=uniform --max-concurrency=1024 --sweep --growth=4 --step=30 --warm-up=10 --out="$p_e1_sweep"; then
    write_sweep_summary "$p_e1_sweep"
  else
    say "[$backend] E1 uniform sweep failed"
  fi

  say "[$backend] Running E1 zipf sweep (to 1024)..."
  local p_e1_zipf="$session_dir/e1-zipf-sweep"
  if "$B" saturate --reuse $C --scenario=E1 --distribution=zipf --max-concurrency=1024 --sweep --growth=4 --step=30 --warm-up=10 --out="$p_e1_zipf"; then
    write_sweep_summary "$p_e1_zipf"
  else
    say "[$backend] E1 zipf sweep failed"
  fi

  say "[$backend] Running E1 hot sweep (to 1024)..."
  local p_e1_hot="$session_dir/e1-hot-sweep"
  if "$B" saturate --reuse $C --scenario=E1 --distribution=hot --hot-keys=1 --max-concurrency=1024 --sweep --growth=4 --step=30 --warm-up=10 --out="$p_e1_hot"; then
    write_sweep_summary "$p_e1_hot"
  else
    say "[$backend] E1 hot sweep failed"
  fi

  say "[$backend] Running E3 uniform sweep (to 1024)..."
  local p_e3_sweep="$session_dir/e3-sweep"
  if "$B" saturate --reuse $C --scenario=E3 --distribution=uniform --max-concurrency=1024 --sweep --growth=4 --step=30 --warm-up=10 --out="$p_e3_sweep"; then
    write_sweep_summary "$p_e3_sweep"
  else
    say "[$backend] E3 uniform sweep failed"
  fi

  # Calculate rates for open-loop runs
  local peak_e1 peak_e3
  peak_e1=$(get_saturation_peak "$p_e1_sweep/saturation.json")
  peak_e3=$(get_saturation_peak "$p_e3_sweep/saturation.json")
  say "[$backend] Sweep peaks: E1=$peak_e1/s, E3=$peak_e3/s"

  local rate_e1_25 rate_e1_50 rate_e1_75
  rate_e1_25=$(calc_rate "$peak_e1" "0.25")
  rate_e1_50=$(calc_rate "$peak_e1" "0.50")
  rate_e1_75=$(calc_rate "$peak_e1" "0.75")

  local rate_e3_25 rate_e3_50 rate_e3_75
  rate_e3_25=$(calc_rate "$peak_e3" "0.25")
  rate_e3_50=$(calc_rate "$peak_e3" "0.50")
  rate_e3_75=$(calc_rate "$peak_e3" "0.75")

  say "[$backend] E1 open-loop rates: 25%=$rate_e1_25, 50%=$rate_e1_50, 75%=$rate_e1_75"
  say "[$backend] E3 open-loop rates: 25%=$rate_e3_25, 50%=$rate_e3_50, 75%=$rate_e3_75"

  # Helper for open-loop run
  run_open_loop() {
    local scenario="$1"
    local rate="$2"
    local phase="$3"
    local phase_dir="$session_dir/$phase"

    say "[$backend] Running $scenario at $rate/s ($phase)..."
    if [ "$backend" = mongo ]; then
      dump_mongo_stats "$phase_dir/mongo-stats-before.json"
    fi

    "$B" run --reuse $C --scenario="$scenario" --distribution=uniform --rate="$rate" --warm-up=30 --measure=60 --out="$phase_dir" || say "[$backend] $phase failed"

    if [ "$backend" = mongo ]; then
      dump_mongo_stats "$phase_dir/mongo-stats-after.json"
    fi
  }

  run_open_loop E1 "$rate_e1_25" "e1-25"
  run_open_loop E1 "$rate_e1_50" "e1-50"
  run_open_loop E1 "$rate_e1_75" "e1-75"

  run_open_loop E3 "$rate_e3_25" "e3-25"
  run_open_loop E3 "$rate_e3_50" "e3-50"
  run_open_loop E3 "$rate_e3_75" "e3-75"

  safe_down
  say "=== Section 1: Backend $backend Complete ==="
}

say "Starting Section 1: Cost and Saturation"
run_backend postgres
run_backend mongo
say "Section 1 Finished"
