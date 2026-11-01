#!/usr/bin/env bash
# Master benchmark runner for v10-m5max-snapshot-2
# Runs all phases sequentially: single-node scans, X-34, X-40
set -uo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPTS_DIR/common.sh"

TOTAL_START=$SECONDS
LOG_FILE="$OUT_ROOT/benchmark-run.log"
mkdir -p "$OUT_ROOT"

say "========================================================="
say "Wow V10 Milestone Saturation Snapshot 2 on Apple M5 Max"
say "Commit: $(git -C "$REPO" rev-parse --short HEAD 2>/dev/null) ($(git -C "$REPO" rev-parse HEAD 2>/dev/null))"
say "Branch: $(git -C "$REPO" branch --show-current 2>/dev/null)"
say "Java: $("$JAVA_HOME/bin/java" -version 2>&1 | head -n 1)"
say "OrbStack: $(orbctl version 2>&1 | head -1)"
say "Machine: $(sysctl -n machdep.cpu.brand_string), $(sysctl -n hw.ncpu) cores, $(($(sysctl -n hw.memsize) / 1073741824)) GB"
say "========================================================="

trap safe_down EXIT

# --- Phase 1: Single-node saturation scans ---
say ">>> Starting Phase 1: Single-node Saturation Scans"
SEC1_START=$SECONDS
if ! bash "$SCRIPTS_DIR/01-saturation-scans.sh"; then
  say "Phase 1 encountered failures, recording and continuing..."
fi
SEC1_TIME=$((SECONDS - SEC1_START))
say ">>> Phase 1 finished in $((SEC1_TIME / 60)) min $((SEC1_TIME % 60)) s."

# --- Phase 2: X-34 reproduction ---
say ">>> Starting Phase 2: X-34 Reproduction (3-node 409)"
SEC2_START=$SECONDS
if ! bash "$SCRIPTS_DIR/02-x34-three-nodes.sh"; then
  say "Phase 2 encountered failures, recording and continuing..."
fi
SEC2_TIME=$((SECONDS - SEC2_START))
say ">>> Phase 2 finished in $((SEC2_TIME / 60)) min $((SEC2_TIME % 60)) s."

# --- Phase 3: X-40 reproduction ---
say ">>> Starting Phase 3: X-40 Reproduction (in-memory transport 500)"
SEC3_START=$SECONDS
if ! bash "$SCRIPTS_DIR/03-x40-inmem-transport.sh"; then
  say "Phase 3 encountered failures, recording and continuing..."
fi
SEC3_TIME=$((SECONDS - SEC3_START))
say ">>> Phase 3 finished in $((SEC3_TIME / 60)) min $((SEC3_TIME % 60)) s."

TOTAL_TIME=$((SECONDS - TOTAL_START))
say "========================================================="
say "All benchmark snapshot runs completed!"
say "Total Elapsed Time: $((TOTAL_TIME / 60)) min $((TOTAL_TIME % 60)) s ($TOTAL_TIME s)"
say "========================================================="
