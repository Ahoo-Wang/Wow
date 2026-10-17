#!/usr/bin/env bash
# The B.1 (E1 write) grid of decision D224, cell by cell and resumable: a cell whose result is already here is skipped.
# Every cell is one bench.sh invocation, so it starts from empty containers and freshly started servers.
#
#   grid.sh saturate <setup> <distribution>
#   grid.sh run      <setup> <distribution> <rate-label> <rate> <repetition>
#
# setup: 9x-mongo | v10-mongo | v10-postgres; distribution: uniform | zipf | hot (the harness defaults: Zipf exponent
# 1.1; hot keys: 50% of the requests to one account, the rest uniform). A cell's result.json and summary.md land in
# <setup>/<distribution>/<rate-label>-r<repetition>/, the saturation finder's in <setup>/<distribution>/saturation/;
# the servers' logs stay in the git-ignored wow-benchmarks/results/e2e/b1/.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../../../.." && pwd)"
BENCH="$REPO/wow-benchmarks/e2e/harness/bench.sh"
SCRATCH="$REPO/wow-benchmarks/results/e2e/b1"

setup_args() {
  case "$1" in
    9x-mongo) echo "--version=9x --backend=mongo" ;;
    v10-mongo) echo "--version=v10 --backend=mongo" ;;
    v10-postgres) echo "--version=v10 --backend=postgres" ;;
    *) echo "unknown setup $1" >&2; exit 2 ;;
  esac
}

command=$1; setup=$2; dist=$3; shift 3
case "$command" in
  saturate)
    dest="$HERE/$setup/$dist/saturation"
    if [ -f "$dest/saturation.json" ]; then echo "[grid] skip $setup $dist saturation"; exit 0; fi
    out="$SCRATCH/$setup-$dist-saturation"
    rm -rf "$out"
    # shellcheck disable=SC2046
    "$BENCH" saturate $(setup_args "$setup") --scenario=E1 --distribution="$dist" --max-concurrency=256 \
      --label=b1 --out="$out"
    mkdir -p "$dest"
    cp "$out/saturation.json" "$dest/"
    ;;
  run)
    label=$1; rate=$2; rep=$3
    dest="$HERE/$setup/$dist/$label-r$rep"
    if [ -f "$dest/result.json" ]; then echo "[grid] skip $setup $dist $label r$rep"; exit 0; fi
    out="$SCRATCH/$setup-$dist-$label-r$rep"
    rm -rf "$out"
    # shellcheck disable=SC2046
    "$BENCH" run $(setup_args "$setup") --scenario=E1 --distribution="$dist" --rate="$rate" --warm-up=30 \
      --measure=60 --label="b1-$label-r$rep" --out="$out"
    mkdir -p "$dest"
    cp "$out/result.json" "$out/summary.md" "$dest/"
    ;;
  *) echo "usage: grid.sh saturate|run ..." >&2; exit 2 ;;
esac
