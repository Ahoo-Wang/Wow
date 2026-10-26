#!/usr/bin/env bash
set -uo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPTS_DIR/common.sh"

run_aa() {
  say "=== Section 2: PostgreSQL E1 A/A Validation ==="

  local sat_file="$OUT_ROOT/1-S-postgres/e1-sweep/saturation.json"
  if [ ! -f "$sat_file" ]; then
    say "Error: Section 1 postgres e1-sweep saturation file not found at $sat_file"
    exit 1
  fi

  local peak_e1 rate_e1_50
  peak_e1=$(get_saturation_peak "$sat_file")
  rate_e1_50=$(calc_rate "$peak_e1" "0.50")
  say "PostgreSQL E1 50% rate: $rate_e1_50/s (from peak $peak_e1/s)"

  local round
  for round in 2 3; do
    local session_dir="$OUT_ROOT/$round-S-postgres"
    local phase_dir="$session_dir/e1-aa"
    mkdir -p "$session_dir"
    say "Starting A/A Round $round (Session $round-S-postgres)..."

    if ! safe_up postgres "1g"; then
      say "A/A Round $round up failed"
      continue
    fi

    local C="--backend=postgres --accounts=$ACCOUNTS --heap=1g"
    say "Running e1-aa at rate $rate_e1_50/s..."
    "$B" run --reuse $C --scenario=E1 --distribution=uniform --rate="$rate_e1_50" --warm-up=30 --measure=60 --out="$phase_dir" || say "A/A Round $round run failed"

    safe_down
    say "A/A Round $round Complete"
  done

  say "=== Section 2 Complete ==="
}

run_aa
