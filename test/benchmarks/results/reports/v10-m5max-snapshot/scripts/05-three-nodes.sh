#!/usr/bin/env bash
set -uo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPTS_DIR/common.sh"

run_3nodes() {
  local backend="$1"
  local session_dir="$OUT_ROOT/5-N3-$backend"
  mkdir -p "$session_dir"
  say "=== Section 5: Starting 3-node cluster for $backend (Session 5-N3-$backend) ==="

  if ! safe_up "$backend" "1g" "--nodes=3"; then
    say "[$backend 3nodes] up failed"
    return 1
  fi

  local C="--backend=$backend --nodes=3 --accounts=$ACCOUNTS --heap=1g"

  say "[$backend 3nodes] Running E1 uniform sweep (to 4096)..."
  local p_e1_sweep="$session_dir/e1-sweep"
  # shellcheck disable=SC2086
  if "$B" saturate --reuse $C --scenario=E1 --distribution=uniform $SWEEP_ARGS --out="$p_e1_sweep"; then
    write_sweep_summary "$p_e1_sweep"
  else
    say "[$backend 3nodes] E1 sweep failed"
  fi

  say "[$backend 3nodes] Running E3 uniform sweep (to 4096)..."
  local p_e3_sweep="$session_dir/e3-sweep"
  # shellcheck disable=SC2086
  if "$B" saturate --reuse $C --scenario=E3 --distribution=uniform $SWEEP_ARGS --out="$p_e3_sweep"; then
    write_sweep_summary "$p_e3_sweep"
  else
    say "[$backend 3nodes] E3 sweep failed"
  fi

  safe_down
  say "=== Section 5: $backend 3-node complete ==="
}

say "Starting Section 5: 3-Node Cluster (5-N3-*)"
run_3nodes postgres
run_3nodes mongo
say "Section 5 Finished"
