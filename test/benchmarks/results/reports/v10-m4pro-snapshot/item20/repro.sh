#!/bin/bash
# Roadmap §6 item 20: what the state cache holds after each sweep, and whether a fixed E1 after it misses.
# One session (MongoDB, --tmpfs, 1 GB heap, 100,000 accounts): E1 fixed; E3 sweep; E1 fixed; E1 uniform, Zipf and hot
# sweeps; E1 fixed. A heap histogram of the node after each step.
set -uo pipefail
export JAVA_HOME=${JAVA_HOME:?a JDK 21}
S=${S:?the scratch directory}
B=${BENCH:?bench.sh}
REPO=${REPO:?the tree whose session file bench.sh writes}
O=$S/item20/run; rm -rf "$O"; mkdir -p "$O"
C="--version=v10 --backend=mongo --accounts=100000 --install=$S/SM-d277 --tmpfs"
say() { echo "$(date +%T) $*"; }
histo() {
  local pid; pid=$(. "$REPO/test/benchmarks/results/e2e/.session"; echo "$S_PIDS" | awk '{print $1}')
  "$JAVA_HOME/bin/jcmd" "$pid" GC.class_histogram > "$O/histo-$1.txt" 2>&1
  say "$1: $(grep -E 'CachedState$|AccountState$|RecentRequests$|Saga|LongArray|\[J$' "$O/histo-$1.txt" | awk '{printf "%s=%s ", $4, $2}')"
}
fixed() { $B run --reuse $C --scenario=E1 --rate=1720 --warm-up=30 --measure=60 --out="$O/$1" > "$O/$1.log" 2>&1; say "$1: $(grep -E 'mongo query|Errors' "$O/$1/summary.md" | head -3 | tr '\n' ' ')"; }
sweep() { $B saturate --reuse $C --scenario=$1 --distribution=$2 ${3:-} --max-concurrency=1024 --sweep --growth=4 --step=30 --warm-up=10 --out="$O/$4" > "$O/$4.log" 2>&1; say "$4 done"; }
$B up $C --prime --seed-concurrency=128 --out="$O/up" > "$O/up.log" 2>&1 || { say "up failed"; exit 1; }
histo 0-primed
fixed 1-e1; histo 1-e1
sweep E3 uniform "" 2-e3-sweep; histo 2-e3-sweep
fixed 3-e1; histo 3-e1
sweep E1 uniform "" 4-e1-sweep; sweep E1 zipf "" 5-zipf-sweep; sweep E1 hot "--hot-keys=1" 6-hot-sweep; histo 6-sweeps
fixed 7-e1; histo 7-e1
$B down > /dev/null 2>&1
say done
