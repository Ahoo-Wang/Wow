#!/usr/bin/env bash
set -uo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPTS_DIR/common.sh"

run_tmpfs() {
  local backend="$1"
  local session_dir="$OUT_ROOT/4-T-$backend"
  mkdir -p "$session_dir"
  say "=== Section 3: Starting tmpfs comparison for $backend (Session 4-T-$backend) ==="

  if ! safe_up "$backend" "1g" "--tmpfs"; then
    say "[$backend tmpfs] up failed"
    return 1
  fi

  local C="--backend=$backend --accounts=$ACCOUNTS --heap=1g"

  say "[$backend tmpfs] Running E1 uniform sweep (to 4096)..."
  local p_e1_sweep="$session_dir/e1-sweep"
  # shellcheck disable=SC2086
  if "$B" saturate --reuse $C --tmpfs --scenario=E1 --distribution=uniform $SWEEP_ARGS --out="$p_e1_sweep"; then
    write_sweep_summary "$p_e1_sweep"
  else
    say "[$backend tmpfs] E1 sweep failed"
  fi

  say "[$backend tmpfs] Running E3 uniform sweep (to 4096)..."
  local p_e3_sweep="$session_dir/e3-sweep"
  # shellcheck disable=SC2086
  if "$B" saturate --reuse $C --tmpfs --scenario=E3 --distribution=uniform $SWEEP_ARGS --out="$p_e3_sweep"; then
    write_sweep_summary "$p_e3_sweep"
  else
    say "[$backend tmpfs] E3 sweep failed"
  fi

  safe_down
  say "=== Section 3: $backend tmpfs complete ==="
}

say "Starting Section 3: Disk vs RAM (tmpfs) Comparison"
run_tmpfs postgres
run_tmpfs mongo
say "Section 3 Finished"
