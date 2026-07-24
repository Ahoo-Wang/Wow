#!/usr/bin/env bash
set -euo pipefail

baseline_dir=/private/tmp/wow-pure-create-ab-baseline
candidate_dir=/private/tmp/wow-pure-create-ab-candidate
evidence_dir=/private/tmp/wow-pure-create-ab-evidence
result_relative=wow-benchmarks/results/jmh/pure-create-main-pr-pair/pure-create-main-pr-pair
result_stem=threads-14-pure-create-main-pr-pair

if [[ -e "$evidence_dir" ]]; then
  echo "Evidence directory already exists: $evidence_dir" >&2
  exit 1
fi
mkdir -p "$evidence_dir"

run_period() {
  local pair_id=$1
  local position=$2
  local variant=$3
  local run_dir
  case "$variant" in
    baseline) run_dir=$baseline_dir ;;
    candidate) run_dir=$candidate_dir ;;
    *)
      echo "Unsupported variant: $variant" >&2
      exit 1
      ;;
  esac

  echo "START pair=$pair_id position=$position variant=$variant"
  (
    cd "$run_dir"
    ./gradlew :wow-benchmarks:benchmarkPureCreateMainPrPair \
      --no-daemon --no-parallel --console=plain
  )

  local source_dir="$run_dir/$result_relative"
  local target_stem="$evidence_dir/pair-${pair_id}-order-${position}-${variant}"
  cp "$source_dir/$result_stem.json" "$target_stem.json"
  cp "$source_dir/$result_stem-human.txt" "$target_stem.txt"
  cp "$source_dir/$result_stem.manifest.json" "$target_stem.manifest.json"

  jq -e '
    .status == "SUCCESS" and
    .source.dirty == false and
    .runSpec.threads == 14 and
    .runSpec.forks == 1 and
    .runSpec.warmupIterations == 2 and
    .runSpec.warmupTime == "5s" and
    .runSpec.measurementIterations == 3 and
    .runSpec.measurementTime == "10s" and
    .runSpec.parameters == {
      "schedulerStrategy": "PARALLEL",
      "schedulerPoolSize": "14",
      "stripeCount": "896"
    } and
    .artifacts.result.rowCount == 2
  ' "$target_stem.manifest.json" >/dev/null
  echo "DONE pair=$pair_id position=$position variant=$variant"
}

run_pair() {
  local pair_id=$1
  local first=$2
  local second=$3
  run_period "$pair_id" 1 "$first"
  run_period "$pair_id" 2 "$second"
}

run_pair 01 baseline candidate
run_pair 02 candidate baseline
run_pair 03 candidate baseline
run_pair 04 baseline candidate
run_pair 05 candidate baseline
run_pair 06 baseline candidate
run_pair 07 baseline candidate
run_pair 08 candidate baseline

echo "ALL_PAIRS_COMPLETE"
