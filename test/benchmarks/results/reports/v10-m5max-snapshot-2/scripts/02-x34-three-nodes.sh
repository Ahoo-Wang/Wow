#!/usr/bin/env bash
# Phase 2: Reproduce X-34 — three-node 409 wow.command.contended
# Last snapshot: PG 3-node at 1024 had 7 (E1) and 9 (E3) 409 errors.
# This time: save full node logs and health/metrics.
set -uo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPTS_DIR/common.sh"

run_3node_x34() {
  local backend="$1"
  local session_dir="$OUT_ROOT/2-X34-$backend"
  mkdir -p "$session_dir"
  say "=== X-34: Starting 3-node cluster for $backend ==="

  if ! safe_up "$backend" "1g" "--nodes=3"; then
    say "[$backend 3nodes] up failed"
    return 1
  fi

  local C="--backend=$backend --nodes=3 --accounts=$ACCOUNTS --heap=1g"

  # E1 sweep to 4096
  say "[$backend 3nodes X-34] Running E1 uniform sweep (to 4096)..."
  local p_e1="$session_dir/e1-sweep"
  # shellcheck disable=SC2086
  if "$B" saturate --reuse $C --scenario=E1 --distribution=uniform $SWEEP_ARGS --out="$p_e1"; then
    write_sweep_summary "$p_e1"
  else
    say "[$backend 3nodes] E1 sweep failed"
  fi

  # E3 sweep to 4096
  say "[$backend 3nodes X-34] Running E3 uniform sweep (to 4096)..."
  local p_e3="$session_dir/e3-sweep"
  # shellcheck disable=SC2086
  if "$B" saturate --reuse $C --scenario=E3 --distribution=uniform $SWEEP_ARGS --out="$p_e3"; then
    write_sweep_summary "$p_e3"
  else
    say "[$backend 3nodes] E3 sweep failed"
  fi

  # Fixed concurrency at 1024 for E1
  say "[$backend 3nodes X-34] Running E1 at fixed concurrency 1024..."
  local p_e1_fixed="$session_dir/e1-c1024"
  # shellcheck disable=SC2086
  "$B" saturate --reuse $C --scenario=E1 --distribution=uniform \
    --start-concurrency=1024 --max-concurrency=1024 --step=60 --warm-up=15 --out="$p_e1_fixed" || say "e1-c1024 failed"
  write_sweep_summary "$p_e1_fixed" 2>/dev/null || true

  # Fixed concurrency at 1024 for E3
  say "[$backend 3nodes X-34] Running E3 at fixed concurrency 1024..."
  local p_e3_fixed="$session_dir/e3-c1024"
  # shellcheck disable=SC2086
  "$B" saturate --reuse $C --scenario=E3 --distribution=uniform \
    --start-concurrency=1024 --max-concurrency=1024 --step=60 --warm-up=15 --out="$p_e3_fixed" || say "e3-c1024 failed"
  write_sweep_summary "$p_e3_fixed" 2>/dev/null || true

  # Save evidence BEFORE tearing down
  say "[$backend 3nodes X-34] Saving node logs and health metrics..."
  save_node_logs "$session_dir" 3
  save_health_metrics "$session_dir" 3

  safe_down
  say "=== X-34: $backend 3-node complete ==="
}

say "Starting Phase 2: X-34 Reproduction (3-node 409)"
run_3node_x34 postgres
run_3node_x34 mongo
say "Phase 2 Finished"
