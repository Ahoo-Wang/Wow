#!/usr/bin/env bash
set -uo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPTS_DIR/common.sh"

TOTAL_START=$SECONDS
LOG_FILE="$OUT_ROOT/benchmark-run.log"
mkdir -p "$OUT_ROOT"

say "========================================================="
say "Wow V10 Milestone Saturation Snapshot Benchmark on Apple M5 Max"
say "Commit: $(git -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo 'cebdb88d0') ($(git -C "$REPO" rev-parse HEAD 2>/dev/null || echo 'cebdb88d01e87a81fb23ff90fbd4d301ed1e7da8'))"
say "Branch: $(git -C "$REPO" branch --show-current 2>/dev/null || echo 'bench/m5max-snapshot')"
say "Java: $("$JAVA_HOME/bin/java" -version 2>&1 | head -n 1)"
say "OrbStack: 2.2.5, Engine: 29.8.2 (16 CPUs, 32 GB)"
say "Machine: Apple M5 Max, 18 cores (6 Super + 12 Performance), 64 GB RAM"
say "========================================================="

# Trap to make sure we don't leave lingering processes
trap safe_down EXIT

# --- SECTION 1 ---
say ">>> Starting Section 1: Cost and Saturation (1-S-postgres, 1-S-mongo)"
SEC1_START=$SECONDS
if ! bash "$SCRIPTS_DIR/01-cost-and-saturation.sh"; then
  say "Section 1 encountered failures, recording and continuing..."
fi
SEC1_TIME=$((SECONDS - SEC1_START))
say ">>> Section 1 finished in $SEC1_TIME s."

# --- SECTION 2 ---
say ">>> Starting Section 2: PostgreSQL A/A Validation (2-S-postgres, 3-S-postgres)"
SEC2_START=$SECONDS
if ! bash "$SCRIPTS_DIR/02-aa-validation.sh"; then
  say "Section 2 encountered failures, recording and continuing..."
fi
SEC2_TIME=$((SECONDS - SEC2_START))
say ">>> Section 2 finished in $SEC2_TIME s."

# --- SECTION 3 ---
say ">>> Starting Section 3: Disk vs RAM tmpfs Comparison (4-T-postgres, 4-T-mongo)"
SEC3_START=$SECONDS
if ! bash "$SCRIPTS_DIR/03-tmpfs-comparison.sh"; then
  say "Section 3 encountered failures, recording and continuing..."
fi
SEC3_TIME=$((SECONDS - SEC3_START))
say ">>> Section 3 finished in $SEC3_TIME s."

# --- SECTION 4 ---
say ">>> Starting Section 4: L1 Component Scaling"
SEC4_START=$SECONDS
if ! bash "$SCRIPTS_DIR/04-l1-component.sh"; then
  say "Section 4 encountered failures, recording and continuing..."
fi
SEC4_TIME=$((SECONDS - SEC4_START))
say ">>> Section 4 finished in $SEC4_TIME s."

# --- SECTION 5 ---
say ">>> Starting Section 5: 3-Node Cluster (5-N3-postgres, 5-N3-mongo)"
SEC5_START=$SECONDS
if ! bash "$SCRIPTS_DIR/05-three-nodes.sh"; then
  say "Section 5 encountered failures, recording and continuing..."
fi
SEC5_TIME=$((SECONDS - SEC5_START))
say ">>> Section 5 finished in $SEC5_TIME s."

# --- SECTION 6 ---
say ">>> Starting Section 6: JDK 25 Runtime (6-J25-postgres)"
SEC6_START=$SECONDS
if ! bash "$SCRIPTS_DIR/06-jdk25-runtime.sh"; then
  say "Section 6 encountered failures, recording and continuing..."
fi
SEC6_TIME=$((SECONDS - SEC6_START))
say ">>> Section 6 finished in $SEC6_TIME s."

# --- COMPARE.md & SUMMARY.md ---
say ">>> Generating COMPARE.md..."
"$B" compare "$OUT_ROOT" > "$OUT_ROOT/COMPARE.md" || say "Failed to generate COMPARE.md"

say ">>> Generating SUMMARY.md..."
python3 "$SCRIPTS_DIR/generate-summary.py" "$OUT_ROOT" "$OUT_ROOT/SUMMARY.md" || say "Failed to generate SUMMARY.md"

TOTAL_TIME=$((SECONDS - TOTAL_START))
say "========================================================="
say "All benchmark snapshot runs completed successfully!"
say "Branch: $(git -C "$REPO" branch --show-current 2>/dev/null || echo 'bench/m5max-snapshot')"
say "Final Commit SHA: $(git -C "$REPO" rev-parse HEAD 2>/dev/null || echo 'cebdb88d01e87a81fb23ff90fbd4d301ed1e7da8')"
say "Total Elapsed Time: $((TOTAL_TIME / 60)) min $((TOTAL_TIME % 60)) s ($TOTAL_TIME s)"
say "========================================================="
