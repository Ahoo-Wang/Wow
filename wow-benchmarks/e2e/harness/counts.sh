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
# The count-based tier of the end-to-end benchmark (decision D224, implementation note "measurement tiers"): what each
# operation costs in store round trips, heap allocations and Kafka messages, and what each partition costs when there
# is no traffic (PS-2, decision D240 ②). Counts do not depend on the machine's load, so they run at a low rate on any
# machine at any time. The method is B.D's (results/reports/v10-mongo-write-amplification.md): an idle window measures
# the periodic work alone, each load window's count less the idle rate over its length is the operations' own.
#
#   counts.sh scenarios <9x-mongo|v10-mongo|v10-postgres> <out> [--scenarios=E1,E2,E3] [--rate=20] [--seconds=30]
#             [--idle=30] [--accounts=1000]
#   counts.sh ps2 <mongo|postgres> <out> [--partitions=64,128,256] [--subscriptions=0,1,5,10] [--idle=60]
#             [--trickle-rate=10] [--trickle=30] [--accounts=1000]
#
# Each window leaves in <out>/<config>/: windows.txt (name, start and end in ms), <window>-counters-{0,1}.json
# (MongoDB's serverStatus command counters), <window>-profile.jsonl (MongoDB's profiler, level 2, grouped by
# collection, operation and filter shape), <window>-statements.tsv (PostgreSQL's pg_stat_statements),
# <window>-kafka-{0,1}.txt (the end offsets of every topic) and <window>-alloc-{0,1}.json (each server JVM's bytes
# allocated). counts.py turns them into the per-operation and per-partition tables. Build first: bench.sh build.
set -euo pipefail
BENCH_LIB=1
# shellcheck source=bench.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/bench.sh"
COUNTS="$HARNESS/counts"
COMPOSE_EXTRA=("$HARNESS/compose.counts.yml")

now_ms() { python3 -c 'import time; print(int(time.time() * 1000))'; }
mongo_eval() { docker exec wow-e2e-bench-mongo mongosh --quiet --eval "$1"; }
mongo_file() { docker exec -i wow-e2e-bench-mongo mongosh --quiet "$@"; }
psql_() { docker exec wow-e2e-bench-postgres psql -U postgres -d transfer_v10 -At -F $'\t' -c "$1"; }
kafka_offsets() {
  docker exec wow-e2e-bench-kafka kafka-get-offsets --bootstrap-server localhost:9092 2>/dev/null | grep -v '^__' || true
}

# Starts the store's own counting: the profiler on every database of the service, or pg_stat_statements.
counting_on() {
  if [ "$STORE" = mongo ]; then
    docker cp "$COUNTS/profile_start.js" wow-e2e-bench-mongo:/tmp/profile_start.js >/dev/null
    docker cp "$COUNTS/profile_dump.js" wow-e2e-bench-mongo:/tmp/profile_dump.js >/dev/null
    docker cp "$COUNTS/counters.js" wow-e2e-bench-mongo:/tmp/counters.js >/dev/null
    mongo_file /tmp/profile_start.js >/dev/null
  else
    psql_ "create extension if not exists pg_stat_statements" >/dev/null
  fi
}

# Opens window $2 in directory $1: the snapshots the counters are compared with, the start time last.
window_begin() {
  local dir=$1 name=$2
  kafka_offsets >"$dir/$name-kafka-0.txt"
  "$LOAD" alloc --pids="$PIDS" >"$dir/$name-alloc-0.json" || true
  if [ "$STORE" = mongo ]; then
    mongo_file /tmp/counters.js >"$dir/$name-counters-0.json"
  else
    psql_ "select pg_stat_statements_reset()" >/dev/null
    psql_ "select xact_commit from pg_stat_database where datname = 'transfer_v10'" >"$dir/$name-xact-0.txt"
  fi
  WINDOW_START=$(now_ms)
}

# Closes window $2 in directory $1: the end time first, then the snapshots.
window_end() {
  local dir=$1 name=$2 end
  end=$(now_ms)
  if [ "$STORE" = mongo ]; then
    mongo_file /tmp/counters.js >"$dir/$name-counters-1.json"
  else
    psql_ "select calls, rows, regexp_replace(query, '\s+', ' ', 'g') from pg_stat_statements where dbid = (select oid from pg_database where datname = 'transfer_v10') order by calls desc" \
      >"$dir/$name-statements.tsv"
    psql_ "select xact_commit from pg_stat_database where datname = 'transfer_v10'" >"$dir/$name-xact-1.txt"
  fi
  "$LOAD" alloc --pids="$PIDS" >"$dir/$name-alloc-1.json" || true
  kafka_offsets >"$dir/$name-kafka-1.txt"
  echo "$name $WINDOW_START $end" >>"$dir/windows.txt"
}

# The profiler's groups of every window of $1, once its windows are over.
profile_dump() {
  local dir=$1 name start end
  if [ "$STORE" != mongo ]; then return 0; fi
  while read -r name start end; do
    mongo_eval "const since=$start, until=$end; load('/tmp/profile_dump.js')" >"$dir/$name-profile.jsonl"
  done <"$dir/windows.txt"
}

# One load window $2 in $1: scenario $2 at $RATE/s for $SECONDS_ s, open loop, then 5 s for the chain to finish.
load_window() {
  local dir=$1 scenario=$2 rate=$3 seconds=$4
  window_begin "$dir" "$scenario"
  "$LOAD" run --version="$VERSION" --targets="$TARGET_LIST" --accounts="$ACCOUNTS" --scenario="${scenario%%-*}" \
    --rate="$rate" --warm-up=0 --measure="$seconds" --out="$dir/$scenario-load" --label="counts-$scenario" \
    --meta="$META" >"$dir/$scenario-load.log" 2>&1
  sleep 5
  window_end "$dir" "$scenario"
}

idle_window() {
  local dir=$1 seconds=$2
  window_begin "$dir" idle
  sleep "$seconds"
  window_end "$dir" idle
}

describe() {
  local dir=$1
  {
    echo "version=$VERSION"
    echo "backend=$BACKEND"
    echo "commit=$(git -C "$REPO" rev-parse HEAD)"
    echo "dirty=$(git -C "$REPO" status --porcelain -- . ':!wow-benchmarks/results' | wc -l | tr -d ' ')"
    echo "serverArgs=$(echo ${SERVER_EXTRA[@]+"${SERVER_EXTRA[@]}"})"
    echo "accounts=$ACCOUNTS"
    echo "machine=$(sysctl -n machdep.cpu.brand_string 2>/dev/null || uname -m), $(sysctl -n hw.ncpu 2>/dev/null) cores"
    echo "jvm=$("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
  } >"$dir/config.txt"
}

COUNT_OPTIONS=""
copt() {
  local line value="$2"
  while IFS= read -r line; do
    if [ "${line%%=*}" = "$1" ]; then value=${line#*=}; fi
  done <<EOF
$COUNT_OPTIONS
EOF
  echo "$value"
}

# The per-operation counts of scenarios E1 to E3 (E4 and E5 are not measured now: user, 2026-10-19).
scenarios() {
  local setup=$1 out=$2; shift 2
  local argument
  for argument in "$@"; do COUNT_OPTIONS="$COUNT_OPTIONS${argument#--}
"; done
  case "$setup" in
    9x-mongo) VERSION=9x; BACKEND=mongo ;;
    v10-mongo) VERSION=v10; BACKEND=mongo ;;
    v10-postgres) VERSION=v10; BACKEND=postgres ;;
    *) log "unknown setup $setup"; exit 2 ;;
  esac
  NODES=1; HEAP=1g; ACCOUNTS=$(copt accounts 1000)
  local rate seconds idle list
  rate=$(copt rate 20); seconds=$(copt seconds 30); idle=$(copt idle 30); list=$(copt scenarios E1,E2,E3)
  local dir="$out/$setup"
  rm -rf "$dir"; mkdir -p "$dir"
  LOGS="$dir/logs"
  trap down EXIT
  session_up
  seed_accounts acc "$LOGS"
  sleep 5
  describe "$dir"
  echo "rate=$rate seconds=$seconds idle=$idle" >>"$dir/config.txt"
  counting_on
  idle_window "$dir" "$idle"
  local scenario
  for scenario in $(echo "$list" | tr ',' ' '); do
    log "$setup: $scenario at $rate/s for ${seconds}s"
    load_window "$dir" "$scenario" "$rate" "$seconds"
  done
  profile_dump "$dir"
  down
  trap - EXIT
  log "counts in $dir"
}

# PS-2: what a partition costs with no traffic, and under a trickle, per partition count and subscription count.
ps2() {
  BACKEND=$1; local out=$2; shift 2
  local argument
  for argument in "$@"; do COUNT_OPTIONS="$COUNT_OPTIONS${argument#--}
"; done
  VERSION=v10; NODES=1; HEAP=1g; ACCOUNTS=$(copt accounts 1000)
  local partitions subscriptions idle trickle_rate trickle
  partitions=$(copt partitions 64,128,256); subscriptions=$(copt subscriptions 0,1,5,10)
  idle=$(copt idle 60); trickle_rate=$(copt trickle-rate 10); trickle=$(copt trickle 30)
  trap down EXIT
  local first=true p s dir
  for p in $(echo "$partitions" | tr ',' ' '); do
    for s in $(echo "$subscriptions" | tr ',' ' '); do
      dir="$out/$BACKEND/p$p-s$s"
      if [ -f "$dir/windows.txt" ] && grep -q '^E1-trickle ' "$dir/windows.txt"; then
        log "skip $BACKEND p$p s$s"; continue
      fi
      rm -rf "$dir"; mkdir -p "$dir"
      LOGS="$dir/logs"; mkdir -p "$LOGS"
      SERVER_EXTRA=("--wow.log.partitions=$p" "--bench.extra-subscriptions=$s")
      if [ "$first" = true ]; then session_up; first=false; else session_clear; fi
      seed_accounts acc "$LOGS"
      # Lets the seeding's checkpoints, snapshots and catch-up reads finish before the idle window.
      sleep 15
      describe "$dir"
      echo "partitions=$p extraSubscriptions=$s idle=$idle trickleRate=$trickle_rate trickle=$trickle" >>"$dir/config.txt"
      counting_on
      log "$BACKEND p$p s$s: idle ${idle}s"
      idle_window "$dir" "$idle"
      log "$BACKEND p$p s$s: trickle E1 at $trickle_rate/s for ${trickle}s"
      load_window "$dir" E1-trickle "$trickle_rate" "$trickle"
      profile_dump "$dir"
      # The subscriptions the node runs, from its checkpoints (one per subscription and partition it moved).
      if [ "$STORE" = mongo ]; then
        mongo_eval "db.getSiblingDB('transfer_v10').wow_checkpoint.distinct('subscription').sort().forEach(s => print(s))" \
          >"$dir/subscriptions.txt" 2>/dev/null || true
      else
        psql_ "select distinct subscription from wow_checkpoint order by 1" >"$dir/subscriptions.txt" 2>/dev/null || true
      fi
    done
  done
  down
  trap - EXIT
}

command=${1:-}
case "$command" in
  scenarios) shift; scenarios "$@" ;;
  ps2) shift; ps2 "$@" ;;
  *) sed -n '14,32p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
