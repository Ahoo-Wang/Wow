#!/usr/bin/env bash
# Phase 2: Three-node cluster (PostgreSQL and MongoDB)
# Observe X-55: HTTP/2 "too many concurrent streams" on internal ports, dropped wait signals, 409, 503, 504.
set -uo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPTS_DIR/common.sh"

run_3node() {
  local backend="$1"
  local session_dir="$OUT_ROOT/2-X55-$backend"
  mkdir -p "$session_dir"
  say "=== Phase 2 (X-55): Starting 3-node cluster for $backend ==="

  if ! safe_up "$backend" "1g" "--nodes=3"; then
    say "[$backend 3nodes] up failed"
    return 1
  fi

  local C="--backend=$backend --nodes=3 --accounts=$ACCOUNTS --heap=1g"

  # E1 sweep to 4096
  say "[$backend 3nodes] Running E1 uniform sweep (to 4096)..."
  local p_e1="$session_dir/e1-sweep"
  # shellcheck disable=SC2086
  if "$B" saturate --reuse $C --scenario=E1 --distribution=uniform $SWEEP_ARGS --out="$p_e1"; then
    write_sweep_summary "$p_e1"
  else
    say "[$backend 3nodes] E1 sweep failed"
  fi

  # E3 sweep to 4096
  say "[$backend 3nodes] Running E3 uniform sweep (to 4096)..."
  local p_e3="$session_dir/e3-sweep"
  # shellcheck disable=SC2086
  if "$B" saturate --reuse $C --scenario=E3 --distribution=uniform $SWEEP_ARGS --out="$p_e3"; then
    write_sweep_summary "$p_e3"
  else
    say "[$backend 3nodes] E3 sweep failed"
  fi

  # Fixed concurrency at 1024 for E1 (same as Snapshot 2)
  say "[$backend 3nodes] Running E1 at fixed concurrency 1024..."
  local p_e1_fixed="$session_dir/e1-c1024"
  # shellcheck disable=SC2086
  "$B" saturate --reuse $C --scenario=E1 --distribution=uniform \
    --start-concurrency=1024 --max-concurrency=1024 --step=60 --warm-up=15 --out="$p_e1_fixed" || say "e1-c1024 failed"
  write_sweep_summary "$p_e1_fixed" 2>/dev/null || true

  # Fixed concurrency at 1024 for E3 (same as Snapshot 2)
  say "[$backend 3nodes] Running E3 at fixed concurrency 1024..."
  local p_e3_fixed="$session_dir/e3-c1024"
  # shellcheck disable=SC2086
  "$B" saturate --reuse $C --scenario=E3 --distribution=uniform \
    --start-concurrency=1024 --max-concurrency=1024 --step=60 --warm-up=15 --out="$p_e3_fixed" || say "e3-c1024 failed"
  write_sweep_summary "$p_e3_fixed" 2>/dev/null || true

  # Save evidence BEFORE tearing down
  say "[$backend 3nodes] Saving node logs and health metrics..."
  save_node_logs "$session_dir"
  save_health_metrics "$session_dir" 3

  safe_down
  say "=== Phase 2: $backend 3-node complete ==="
}

say "Starting Phase 2: Three-node Cluster Measurements (X-55)"
run_3node postgres
run_3node mongo
say "Phase 2 Finished"
