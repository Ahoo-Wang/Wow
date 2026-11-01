#!/usr/bin/env bash
# Phase 3: Reproduce X-40 — HTTP 500 under in-memory transport overload
# Last time: 1 GB heap, blank Kafka address → in-memory topics → OOM at high concurrency.
# Since X-33 fix: in-memory topics have a capacity limit; wow.transport=none disables transport.
# Test: 8 GB heap, blank Kafka address sweep + wow.transport=none sweep.
set -uo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPTS_DIR/common.sh"

say "=== X-40: Reproduce HTTP 500 under in-memory transport overload ==="

# --- Arm 1: blank Kafka address (triggers in-memory transport) with 8 GB heap ---
run_x40_inmem() {
  say "=== X-40 Arm 1: 8 GB heap, blank Kafka address (in-memory transport) ==="
  local dir="$OUT_ROOT/3-X40-inmem"
  mkdir -p "$dir"

  ensure_clean_env
  if ! safe_up "postgres" "8g" "--server-args=--wow.kafka.bootstrap-servers="; then
    say "[X-40 inmem] up failed"
    safe_down
    return 1
  fi

  local C="--backend=postgres --accounts=$ACCOUNTS --heap=8g"
  local SA='--server-args=--wow.kafka.bootstrap-servers='

  say "[X-40 inmem] Running E1 sweep (to 4096)..."
  # shellcheck disable=SC2086
  "$B" saturate --reuse $C "$SA" --scenario=E1 --distribution=uniform $SWEEP_ARGS --out="$dir/e1-sweep" || say "[X-40 inmem] E1 sweep failed"
  write_sweep_summary "$dir/e1-sweep" 2>/dev/null || true

  say "[X-40 inmem] Running E3 sweep (to 4096)..."
  # shellcheck disable=SC2086
  "$B" saturate --reuse $C "$SA" --scenario=E3 --distribution=uniform $SWEEP_ARGS --out="$dir/e3-sweep" || say "[X-40 inmem] E3 sweep failed"
  write_sweep_summary "$dir/e3-sweep" 2>/dev/null || true

  # Save evidence
  say "[X-40 inmem] Saving node logs and health metrics..."
  save_node_logs "$dir" 1
  save_health_metrics "$dir" 1
  safe_down
}

# --- Arm 2: wow.transport=none with 8 GB heap ---
run_x40_notransport() {
  say "=== X-40 Arm 2: 8 GB heap, wow.transport=none (no transport) ==="
  local dir="$OUT_ROOT/3-X40-notransport"
  mkdir -p "$dir"

  ensure_clean_env
  sleep 10
  if ! safe_up "postgres" "8g" "--server-args=--wow.transport=none"; then
    say "[X-40 none] up failed"
    safe_down
    return 1
  fi

  local C="--backend=postgres --accounts=$ACCOUNTS --heap=8g"
  local SA='--server-args=--wow.transport=none'

  say "[X-40 none] Running E1 sweep (to 4096)..."
  # shellcheck disable=SC2086
  "$B" saturate --reuse $C "$SA" --scenario=E1 --distribution=uniform $SWEEP_ARGS --out="$dir/e1-sweep" || say "[X-40 none] E1 sweep failed"
  write_sweep_summary "$dir/e1-sweep" 2>/dev/null || true

  say "[X-40 none] Running E3 sweep (to 4096)..."
  # shellcheck disable=SC2086
  "$B" saturate --reuse $C "$SA" --scenario=E3 --distribution=uniform $SWEEP_ARGS --out="$dir/e3-sweep" || say "[X-40 none] E3 sweep failed"
  write_sweep_summary "$dir/e3-sweep" 2>/dev/null || true

  # Save evidence
  say "[X-40 none] Saving node logs and health metrics..."
  save_node_logs "$dir" 1
  save_health_metrics "$dir" 1
  safe_down
}

run_x40_inmem
sleep 10
run_x40_notransport

say "=== Phase 3 (X-40) Finished ==="
