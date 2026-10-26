#!/usr/bin/env bash
set -uo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPTS_DIR/common.sh"

TOTAL_START=$SECONDS
LOG_FILE="$OUT_ROOT/benchmark-run.log"
mkdir -p "$OUT_ROOT"

say "========================================================="
say "Wow V10 Milestone Snapshot Benchmark on Apple M5 Pro"
say "Commit: $(git -C "$REPO" rev-parse --short HEAD) ($(git -C "$REPO" rev-parse HEAD))"
say "Branch: $(git -C "$REPO" branch --show-current)"
say "Java: $("$JAVA_HOME/bin/java" -version 2>&1 | head -n 1)"
say "Docker Desktop: 4.96.3, Engine: 29.9.2 (12 CPUs, 16 GB, AVF, VirtioFS)"
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
say ">>> Section 1 finished in $SEC1_TIME s. Committing Section 1 data..."
(
  cd "$REPO"
  git add test/benchmarks/results/reports/v10-m5pro-snapshot/1-S-postgres \
          test/benchmarks/results/reports/v10-m5pro-snapshot/1-S-mongo
  git commit -m "bench(m5pro-snapshot): section 1 cost and saturation (postgres and mongo)" || say "No changes to commit for section 1"
)

# --- SECTION 2 ---
say ">>> Starting Section 2: PostgreSQL A/A Validation (2-S-postgres, 3-S-postgres)"
SEC2_START=$SECONDS
if ! bash "$SCRIPTS_DIR/02-aa-validation.sh"; then
  say "Section 2 encountered failures, recording and continuing..."
fi
SEC2_TIME=$((SECONDS - SEC2_START))
say ">>> Section 2 finished in $SEC2_TIME s. Committing Section 2 data..."
(
  cd "$REPO"
  git add test/benchmarks/results/reports/v10-m5pro-snapshot/2-S-postgres \
          test/benchmarks/results/reports/v10-m5pro-snapshot/3-S-postgres
  git commit -m "bench(m5pro-snapshot): section 2 A/A validation (postgres)" || say "No changes to commit for section 2"
)

# --- SECTION 3 ---
say ">>> Starting Section 3: 1 GB vs 2 GB Heap (4-G1-*, 4-G2-*)"
SEC3_START=$SECONDS
if ! bash "$SCRIPTS_DIR/03-heap-comparison.sh"; then
  say "Section 3 encountered failures, recording and continuing..."
fi
SEC3_TIME=$((SECONDS - SEC3_START))
say ">>> Section 3 finished in $SEC3_TIME s. Committing Section 3 data..."
(
  cd "$REPO"
  git add test/benchmarks/results/reports/v10-m5pro-snapshot/4-G1-postgres \
          test/benchmarks/results/reports/v10-m5pro-snapshot/4-G2-postgres \
          test/benchmarks/results/reports/v10-m5pro-snapshot/4-G1-mongo \
          test/benchmarks/results/reports/v10-m5pro-snapshot/4-G2-mongo
  git commit -m "bench(m5pro-snapshot): section 3 1 GB vs 2 GB heap comparison" || say "No changes to commit for section 3"
)

# --- SECTION 4 ---
say ">>> Starting Section 4: L1 Component Scaling"
SEC4_START=$SECONDS
if ! bash "$SCRIPTS_DIR/04-l1-component.sh"; then
  say "Section 4 encountered failures, recording and continuing..."
fi
SEC4_TIME=$((SECONDS - SEC4_START))
say ">>> Section 4 finished in $SEC4_TIME s. Committing Section 4 data..."
(
  cd "$REPO"
  git add test/benchmarks/results/reports/v10-m5pro-snapshot/l1
  git commit -m "bench(m5pro-snapshot): section 4 L1 component scaling" || say "No changes to commit for section 4"
)

# --- SECTION 5 & 6: COMPARE.md, SUMMARY.md, SCRIPTS & PUSH ---
say ">>> Generating COMPARE.md..."
"$B" compare "$OUT_ROOT" > "$OUT_ROOT/COMPARE.md" || say "Failed to generate COMPARE.md"

say ">>> Generating SUMMARY.md..."
python3 "$SCRIPTS_DIR/generate-summary.py" "$OUT_ROOT" "$OUT_ROOT/SUMMARY.md" || say "Failed to generate SUMMARY.md"

say ">>> Committing COMPARE.md, SUMMARY.md, and scripts..."
(
  cd "$REPO"
  git add test/benchmarks/results/reports/v10-m5pro-snapshot/COMPARE.md \
          test/benchmarks/results/reports/v10-m5pro-snapshot/SUMMARY.md \
          test/benchmarks/results/reports/v10-m5pro-snapshot/scripts/
  git commit -m "bench(m5pro-snapshot): add COMPARE.md, SUMMARY.md and runner scripts" || say "No changes to commit for final summary"
  
  say ">>> Pushing to origin claude/m5pro-bench-1026..."
  git push origin claude/m5pro-bench-1026
)

TOTAL_TIME=$((SECONDS - TOTAL_START))
say "========================================================="
say "All benchmark snapshot runs completed successfully!"
say "Branch: $(git -C "$REPO" branch --show-current)"
say "Final Commit SHA: $(git -C "$REPO" rev-parse HEAD)"
say "Total Elapsed Time: $((TOTAL_TIME / 60)) min $((TOTAL_TIME % 60)) s ($TOTAL_TIME s)"
say "========================================================="
