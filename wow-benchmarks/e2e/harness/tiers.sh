#!/usr/bin/env bash
#
# Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#      http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# The time-based tiers of the end-to-end benchmark (decision D224, implementation note "measurement tiers"). Throughput,
# p99 and saturation need a machine of their own: run them only when the user says so.
#
#   tiers.sh plan  quick|full           # the cells and an estimate of the time, nothing run
#   tiers.sh quick [options]            # V10 MongoDB against 9.x MongoDB, E1, uniform and Zipf, at 80% (~40 min)
#   tiers.sh full  [options]            # E1 to E3, the three setups, uniform, Zipf and hot keys, at 50% and 80%
#   tiers.sh night [options]            # full, unattended: refuses while a build runs, keeps the machine awake
#   tiers.sh status quick|full          # which cells have results
#
# Options: --isolation=restart|keys|fresh (between cells; restart by default), --timing=auto|long|short (auto: 20 s
# warm-up and 45 s measured once stability.py has accepted it in the report folder, else 30 and 60), --threshold=5
# (the percent two repetitions may differ by before a third runs), --scenarios=E1,E2,E3 --setups=... --dists=...
# (narrow a tier).
#
# Every cell is resumable: a cell whose result is in the report folder is skipped. Each setup keeps one session (its
# containers, and with --isolation=keys its server JVMs too) across its cells:
#   restart  the stores emptied and the nodes restarted between cells, the containers kept (the default);
#   keys     nothing cleared: each cell seeds an account set of its own on the running nodes. Cheapest, but the stores
#            grow over the cells: a running V10 node cannot have its store emptied under it (its state cache,
#            partition counters and leases would outlive their data), and the framework has no hook for it;
#   fresh    containers removed and started again for each cell, as bench.sh run does.
# Results land in wow-benchmarks/results/reports/v10-vs-9x-<scenario>/<setup>/<dist>/<rate>-r<rep>/ and
# .../saturation/; the servers' logs in the git-ignored wow-benchmarks/results/e2e/tiers/.
set -euo pipefail
BENCH_LIB=1
# shellcheck source=bench.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/bench.sh"
# TIER_REPORTS and TIER_SCRATCH move the results and the logs elsewhere (the harness's own smoke test).
REPORTS="${TIER_REPORTS:-$REPO/wow-benchmarks/results/reports}"
SCRATCH="${TIER_SCRATCH:-$REPO/wow-benchmarks/results/e2e/tiers}"

# Seconds each step takes, for the plan's estimate (measured on the harness: a node restart with clearing about 15 s,
# a container start about 35 s, a saturation search about 90 s).
EST_SATURATION=90
EST_RESTART=15
EST_FRESH=40
EST_KEYS=4

TIER_OPTIONS=""
topt() {
  local line value="$2"
  while IFS= read -r line; do
    if [ "${line%%=*}" = "$1" ]; then value=${line#*=}; fi
  done <<EOF
$TIER_OPTIONS
EOF
  echo "$value"
}

# The defaults of tier $1: TIER_SCENARIOS, TIER_SETUPS, TIER_DISTS, TIER_RATES (percent labels), each narrowed by the
# options.
tier_defaults() {
  case "$1" in
    quick)
      TIER_SCENARIOS=E1; TIER_SETUPS="9x-mongo,v10-mongo"; TIER_DISTS="uniform,zipf"; TIER_PERCENTS=80 ;;
    full)
      TIER_SCENARIOS="E1,E2,E3"; TIER_SETUPS="9x-mongo,v10-mongo,v10-postgres"; TIER_DISTS="uniform,zipf,hot"
      TIER_PERCENTS="50,80" ;;
    *) log "unknown tier $1"; exit 2 ;;
  esac
  TIER_SCENARIOS=$(topt scenarios "$TIER_SCENARIOS")
  TIER_SETUPS=$(topt setups "$TIER_SETUPS")
  TIER_DISTS=$(topt dists "$TIER_DISTS")
  TIER_PERCENTS=$(topt percents "$TIER_PERCENTS")
  ISOLATION=$(topt isolation restart)
  THRESHOLD=$(topt threshold 5)
}

list() { echo "$1" | tr ',' ' '; }

# The rate labels of setup $1: the MongoDB setups run at both sides' saturation rates (a paired comparison), V10 on
# PostgreSQL at its own (D224: never compared with 9.x).
rate_labels() {
  local setup=$1 percent labels=""
  for percent in $(list "$TIER_PERCENTS"); do
    case "$setup" in
      v10-postgres) labels="$labels pg-$percent" ;;
      *)
        case ",$TIER_SETUPS," in *,9x-mongo,*) labels="$labels 9x-$percent" ;; esac
        case ",$TIER_SETUPS," in *,v10-mongo,*) labels="$labels v10-$percent" ;; esac ;;
    esac
  done
  echo "$labels"
}

report_of() { echo "$REPORTS/v10-vs-9x-$(echo "$1" | tr '[:upper:]' '[:lower:]')"; }

# The rate of label $2 (9x-80: 80% of 9.x MongoDB's saturation) in scenario $1 and distribution $3; empty when that
# saturation is not measured yet.
rate_of() {
  local scenario=$1 label=$2 dist=$3 source
  case "${label%-*}" in
    9x) source=9x-mongo ;;
    v10) source=v10-mongo ;;
    pg) source=v10-postgres ;;
  esac
  local file; file="$(report_of "$scenario")/$source/$dist/saturation/saturation.json"
  if [ ! -f "$file" ]; then echo ""; return 0; fi
  python3 -I -c 'import json, sys; print(round(json.load(open(sys.argv[1]))["saturationThroughput"] * int(sys.argv[2]) / 100))' \
    "$file" "${label#*-}"
}

# warm-up and measured seconds: auto takes the short ones once stability.py accepted them for the scenario.
timing_of() {
  local scenario=$1 timing; timing=$(topt timing auto)
  if [ "$timing" = short ] || { [ "$timing" = auto ] && [ -f "$(report_of "$scenario")/stability.json" ] && \
    python3 -I -c 'import json, sys; sys.exit(0 if json.load(open(sys.argv[1]))["shortenAccepted"] else 1)' \
      "$(report_of "$scenario")/stability.json"; }; then
    echo "20 45"
  else
    echo "30 60"
  fi
}

# Whether repetitions $@ (result.json files) differ by more than THRESHOLD percent in p99 or throughput.
differ() {
  python3 -I - "$THRESHOLD" "$@" <<'EOF'
import json, sys
threshold = float(sys.argv[1]) / 100
results = [json.load(open(p))["results"] for p in sys.argv[2:]]
for key in (lambda r: r["latencyMs"]["p99"], lambda r: r["throughput"]):
    values = [key(r) for r in results]
    if min(values) <= 0 or (max(values) - min(values)) / min(values) > threshold:
        sys.exit(0)
sys.exit(1)
EOF
}

SESSION=""
# Makes the session of setup $1 the running one.
use_setup() {
  local setup=$1
  if [ "$SESSION" = "$setup" ]; then return 0; fi
  if [ -n "$SESSION" ]; then session_down; fi
  case "$setup" in
    9x-mongo) VERSION=9x; BACKEND=mongo ;;
    v10-mongo) VERSION=v10; BACKEND=mongo ;;
    v10-postgres) VERSION=v10; BACKEND=postgres ;;
  esac
  NODES=1; HEAP=1g; ACCOUNTS=1000; JFR=false; SETTLE=""
  LOGS="$SCRATCH/$setup"
  mkdir -p "$LOGS"
  session_up
  SESSION=$setup
  CELLS_IN_SESSION=0
}

# Prepares the running session for its next cell; sets PREFIX, the cell's accounts.
next_cell() {
  CELLS_IN_SESSION=$((CELLS_IN_SESSION + 1))
  PREFIX=acc
  if [ "$CELLS_IN_SESSION" -gt 1 ]; then
    case "$ISOLATION" in
      restart) session_clear ;;
      fresh) session_up ;;
      keys) PREFIX="c$CELLS_IN_SESSION" ;;
    esac
  fi
  seed_accounts "$PREFIX" "$LOGS"
}

saturate_cell() {
  local scenario=$1 setup=$2 dist=$3
  local dest; dest="$(report_of "$scenario")/$setup/$dist/saturation"
  if [ -f "$dest/saturation.json" ]; then return 0; fi
  use_setup "$setup"
  next_cell
  log "saturation: $scenario $setup $dist"
  cell_saturate "$SCRATCH/$setup-$scenario-$dist-saturation" --scenario="$scenario" --distribution="$dist" \
    --account-prefix="$PREFIX" --start-concurrency=4 --max-concurrency=1024 --warm-up=2 --step=5 --flat-steps=2 \
    --refine=3
  mkdir -p "$dest"
  cp "$SCRATCH/$setup-$scenario-$dist-saturation/saturation.json" "$dest/"
}

run_cell() {
  local scenario=$1 setup=$2 dist=$3 label=$4 rep=$5
  local dest; dest="$(report_of "$scenario")/$setup/$dist/$label-r$rep"
  if [ -f "$dest/result.json" ]; then return 0; fi
  local rate; rate=$(rate_of "$scenario" "$label" "$dist")
  if [ -z "$rate" ]; then log "skip $scenario $setup $dist $label: its saturation is not measured"; return 0; fi
  local timing warm measure
  timing=$(timing_of "$scenario"); warm=${timing% *}; measure=${timing#* }
  use_setup "$setup"
  next_cell
  log "run: $scenario $setup $dist $label r$rep at $rate/s (${warm}s + ${measure}s, isolation $ISOLATION)"
  local out="$SCRATCH/$setup-$scenario-$dist-$label-r$rep"
  rm -rf "$out"
  cell_run "$out" --scenario="$scenario" --distribution="$dist" --rate="$rate" --warm-up="$warm" \
    --measure="$measure" --label="tier-$label-r$rep" --account-prefix="$PREFIX" \
    --meta="$META;isolation=$ISOLATION;cellInSession=$CELLS_IN_SESSION" >"$out.log" 2>&1 || {
    log "run failed: see $out.log"; tail -20 "$out.log" >&2; return 1; }
  mkdir -p "$dest"
  cp "$out/result.json" "$out/summary.md" "$dest/"
}

# Runs tier $1: saturation first (every setup, so the paired rates are known), then two repetitions of every cell,
# then a third of each cell whose two differ by more than THRESHOLD percent.
run_tier() {
  local tier=$1 scenario setup dist label rep base
  tier_defaults "$tier"
  mkdir -p "$SCRATCH"
  trap 'session_down' EXIT
  log "tier $tier: scenarios $TIER_SCENARIOS, setups $TIER_SETUPS, distributions $TIER_DISTS, isolation $ISOLATION"
  for setup in $(list "$TIER_SETUPS"); do
    for scenario in $(list "$TIER_SCENARIOS"); do
      for dist in $(list "$TIER_DISTS"); do saturate_cell "$scenario" "$setup" "$dist"; done
    done
  done
  for setup in $(list "$TIER_SETUPS"); do
    for rep in 1 2; do
      for scenario in $(list "$TIER_SCENARIOS"); do
        for dist in $(list "$TIER_DISTS"); do
          for label in $(rate_labels "$setup"); do run_cell "$scenario" "$setup" "$dist" "$label" "$rep"; done
        done
      done
    done
    for scenario in $(list "$TIER_SCENARIOS"); do
      for dist in $(list "$TIER_DISTS"); do
        for label in $(rate_labels "$setup"); do
          base="$(report_of "$scenario")/$setup/$dist/$label"
          if [ -f "$base-r1/result.json" ] && [ -f "$base-r2/result.json" ] && \
            differ "$base-r1/result.json" "$base-r2/result.json"; then
            run_cell "$scenario" "$setup" "$dist" "$label" 3
          fi
        done
      done
    done
  done
  session_down
  SESSION=""
  trap - EXIT
  for scenario in $(list "$TIER_SCENARIOS"); do
    python3 -I "$HARNESS/stability.py" "$(report_of "$scenario")" || true
  done
  log "tier $tier done"
}

# The cells of tier $1 and the time they take, before any is run.
plan() {
  tier_defaults "$1"
  local sats=0 runs=0 scenario setup dist label n per warm measure seconds
  for scenario in $(list "$TIER_SCENARIOS"); do
    for setup in $(list "$TIER_SETUPS"); do
      for dist in $(list "$TIER_DISTS"); do
        sats=$((sats + 1))
        n=$(rate_labels "$setup" | wc -w | tr -d ' ')
        runs=$((runs + n * 2))
      done
    done
  done
  case "$ISOLATION" in restart) per=$EST_RESTART ;; fresh) per=$EST_FRESH ;; keys) per=$EST_KEYS ;; esac
  for timing in "30 60" "20 45"; do
    warm=${timing% *}; measure=${timing#* }
    # A third repetition for about a quarter of the cells (two runs of the same cell differ by more than 5% in p99).
    seconds=$((sats * (EST_SATURATION + per) + (runs + runs / 8) * (warm + measure + per + 3)))
    echo "tier $1: $sats saturation searches, $runs runs (+ about $((runs / 8)) third repetitions), isolation" \
      "$ISOLATION, ${warm}s + ${measure}s: about $((seconds / 60)) min"
  done
}

status() {
  tier_defaults "$1"
  local scenario setup dist label rep dest
  for scenario in $(list "$TIER_SCENARIOS"); do
    for setup in $(list "$TIER_SETUPS"); do
      for dist in $(list "$TIER_DISTS"); do
        dest="$(report_of "$scenario")/$setup/$dist"
        printf '%s %s %s saturation:%s' "$scenario" "$setup" "$dist" \
          "$([ -f "$dest/saturation/saturation.json" ] && echo done || echo -)"
        for label in $(rate_labels "$setup"); do
          for rep in 1 2 3; do
            if [ -f "$dest/$label-r$rep/result.json" ]; then printf ' %s-r%s' "$label" "$rep"; fi
          done
        done
        echo
      done
    done
  done
}

# Refuses to run the night tier beside another build or benchmark, then runs the full tier with the machine kept
# awake (caffeinate: no idle, disk or system sleep).
night() {
  local busy
  busy=$(ps -Ao pid,args | grep -E 'GradleDaemon|GradleWrapperMain|KotlinCompileDaemon|org\.gradle\.launcher|e2e-bench-(9x|v10)/' \
    | grep -v grep || true)
  if [ -n "$busy" ]; then
    log "refusing to start: other Gradle or Java builds (or benchmark servers) are running:"
    echo "$busy" | cut -c1-160 >&2
    exit 1
  fi
  if [ -n "$(docker ps -q --filter name=wow-e2e-bench)" ]; then
    log "refusing to start: the benchmark's containers are already running"; exit 1
  fi
  log "night tier: full grid, unattended; the machine stays awake until it ends"
  exec caffeinate -ims "${BASH_SOURCE[0]}" full "$@"
}

command=${1:-}
case "$command" in
  quick|full)
    shift
    for argument in "$@"; do TIER_OPTIONS="$TIER_OPTIONS${argument#--}
"; done
    run_tier "$command" ;;
  night) shift; night "$@" ;;
  plan|status)
    shift; tier=$1; shift
    for argument in "$@"; do TIER_OPTIONS="$TIER_OPTIONS${argument#--}
"; done
    "$command" "$tier" ;;
  *) sed -n '14,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
