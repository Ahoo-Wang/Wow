#!/usr/bin/env bash
set -uo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPTS_DIR/common.sh"

say "=== Section 4: L1 Component Scaling ==="

for backend in postgres mongo; do
  for scenario in E1 E3; do
    for concurrency in 16 64 256 1024; do
      out_dir="$OUT_ROOT/l1/$backend-$scenario-$concurrency"
      mkdir -p "$out_dir"
      say "Running L1: backend=$backend scenario=$scenario concurrency=$concurrency..."
      ensure_clean_env
      "$B" component --backend="$backend" --scenario="$scenario" --accounts="$ACCOUNTS" --concurrency="$concurrency" --out="$out_dir" || say "L1 $backend-$scenario-$concurrency failed"
      safe_down
    done
  done
done

say "=== Section 4 Complete ==="
