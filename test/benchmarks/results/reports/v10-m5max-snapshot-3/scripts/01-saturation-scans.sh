#!/usr/bin/env bash
# Phase 1: Single-node saturation scans (PostgreSQL and MongoDB)
# Matches Snapshot 2 parameters exactly.
set -uo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPTS_DIR/common.sh"

run_backend() {
  local backend="$1"
  local session_dir="$OUT_ROOT/1-S-$backend"
  mkdir -p "$session_dir"
  say "=== Phase 1: Starting Backend $backend (Session 1-S-$backend) ==="

  if ! safe_up "$backend" "1g"; then
    say "Skipping backend $backend due to safe_up failure"
    return 1
  fi

  local C="--backend=$backend --accounts=$ACCOUNTS --heap=1g"

  # 1. E1 uniform sweep
  say "[$backend] Running E1 uniform sweep (to 4096)..."
  local p_e1_sweep="$session_dir/e1-sweep"
  # shellcheck disable=SC2086
  if "$B" saturate --reuse $C --scenario=E1 --distribution=uniform $SWEEP_ARGS --out="$p_e1_sweep"; then
    write_sweep_summary "$p_e1_sweep"
  else
    say "[$backend] E1 uniform sweep failed"
  fi

  # 2. E1 zipf sweep
  say "[$backend] Running E1 zipf sweep (to 4096)..."
  local p_e1_zipf="$session_dir/e1-zipf-sweep"
  # shellcheck disable=SC2086
  if "$B" saturate --reuse $C --scenario=E1 --distribution=zipf $SWEEP_ARGS --out="$p_e1_zipf"; then
    write_sweep_summary "$p_e1_zipf"
  else
    say "[$backend] E1 zipf sweep failed"
  fi

  # 3. E1 hot sweep (one hot account)
  say "[$backend] Running E1 hot sweep (to 4096)..."
  local p_e1_hot="$session_dir/e1-hot-sweep"
  # shellcheck disable=SC2086
  if "$B" saturate --reuse $C --scenario=E1 --distribution=hot --hot-keys=1 $SWEEP_ARGS --out="$p_e1_hot"; then
    write_sweep_summary "$p_e1_hot"
  else
    say "[$backend] E1 hot sweep failed"
  fi

  # 4. E3 uniform sweep
  say "[$backend] Running E3 uniform sweep (to 4096)..."
  local p_e3_sweep="$session_dir/e3-sweep"
  # shellcheck disable=SC2086
  if "$B" saturate --reuse $C --scenario=E3 --distribution=uniform $SWEEP_ARGS --out="$p_e3_sweep"; then
    write_sweep_summary "$p_e3_sweep"
  else
    say "[$backend] E3 uniform sweep failed"
  fi

  # Save node logs before tearing down
  save_node_logs "$session_dir"
  safe_down
  say "=== Phase 1: Backend $backend Complete ==="
}

say "Starting Phase 1: Single-node Saturation Scans"
if [ ! -d "$OUT_ROOT/1-S-postgres/e3-sweep" ]; then
  run_backend postgres
else
  say "PostgreSQL sweeps already exist in 1-S-postgres, skipping"
fi
if [ ! -d "$OUT_ROOT/1-S-mongo/e3-sweep" ]; then
  run_backend mongo
else
  say "MongoDB sweeps already exist in 1-S-mongo, skipping"
fi
say "Phase 1 Finished"
