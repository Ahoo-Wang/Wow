#!/usr/bin/env bash
#
# The single-node ceilings on disk: one session per backend (100,000 accounts, primed, 1 GB heap), in it closed-loop
# sweeps from 1 to 4,096 clients of E1 with uniform keys, Zipf keys and one hot account, then of E3 with uniform keys.
# Each E1 sweep goes on past 4,096 clients until its throughput stops rising: 4,096 again (the noise band) and 8,192,
# then 12,288 if 8,192 failed 1% of its commands or less, still rose past the band, and 12,288 fits the client's
# ephemeral ports (`summarize.py --peak`, whose verdict is <sweep>-peak.md: e1-peak.md, e1-zipf-peak.md, e1-hot-peak.md).
#
#   01-single-node.sh [postgres] [mongo]     (both by default; one of them reruns its session)
#
# WOW_REPO names the checkout measured (by default the one this script is in) and BENCH_OUT the raw output's root (by
# default its results/e2e/m5max-snapshot).
set -uo pipefail

# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"
OUT_ROOT="${BENCH_OUT:-$REPO/test/benchmarks/results/e2e/m5max-snapshot}"
# The client's ephemeral ports, every connection of a single node's sweep going to its one public port.
PORTS_EPHEMERAL=$(($(sysctl -n net.inet.ip.portrange.last) - $(sysctl -n net.inet.ip.portrange.first) + 1))

# The E1 sweep $2 (e1, e1-zipf or e1-hot) of session directory $1, its keys and the harness's arguments after them,
# from 1 to 4,096 clients and on past them until its throughput stops rising; the verdict in $1/$2-peak.md.
e1_to_limit() {
  local dir=$1 name=$2
  shift 2
  measure_sweep "$dir/$name-sweep" "$@" --scenario=E1 "${SWEEP[@]}"
  local last
  last=$(python3 -I -c 'import json, sys; print(json.load(open(sys.argv[1]))["steps"][-1]["concurrency"])' \
    "$dir/$name-sweep/saturation.json" 2>/dev/null)
  if [ "$last" != 4096 ]; then say "the $name sweep ended at ${last:-no step}; no steps past it"; return 0; fi
  measure_sweep "$dir/$name-top" "$@" --scenario=E1 \
    --sweep --growth=2 --start-concurrency=4096 --max-concurrency=8192 --step=30 --warm-up=10
  local measured=("$dir/$name-sweep" "$dir/$name-top") next
  next=$(python3 -I "$LIB_DIR/summarize.py" --peak --next --ports="$PORTS_EPHEMERAL" "${measured[@]}")
  if [ -n "$next" ]; then
    # The connections of the steps before close first: a closed port waits out TIME_WAIT before it is free again.
    sleep 60
    measure_sweep "$dir/$name-top-$next" "$@" --scenario=E1 \
      --start-concurrency="$next" --max-concurrency="$next" --step=30 --warm-up=10
    measured+=("$dir/$name-top-$next")
  fi
  python3 -I "$LIB_DIR/summarize.py" --peak --ports="$PORTS_EPHEMERAL" "${measured[@]}" >"$dir/$name-peak.md"
  say "$name: $(head -1 "$dir/$name-peak.md")"
}

single_node() {
  local backend=$1 dir="$OUT_ROOT/1-S-$1"
  local C=(--backend="$backend" --accounts="$ACCOUNTS" --heap=1g)
  session_up "$dir" "${C[@]}" || return 1
  e1_to_limit "$dir" e1 "${C[@]}" --distribution=uniform
  e1_to_limit "$dir" e1-zipf "${C[@]}" --distribution=zipf
  e1_to_limit "$dir" e1-hot "${C[@]}" --distribution=hot --hot-keys=1
  measure_sweep "$dir/e3-sweep" "${C[@]}" --scenario=E3 --distribution=uniform "${SWEEP[@]}"
  session_down "$dir"
}

backends=("$@")
if [ ${#backends[@]} = 0 ]; then backends=(postgres mongo); fi
preflight "$WOW_INSTALL" || exit 1
record_environment "$OUT_ROOT"
for backend in "${backends[@]}"; do
  single_node "$backend" || say "the $backend session failed"
done
say "single node done"
