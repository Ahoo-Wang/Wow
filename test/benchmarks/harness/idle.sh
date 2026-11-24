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
# bench.sh idle, sourced by bench.sh: what an idle service costs its store (#6702).
#
#   bench.sh idle [--backend=mongo|postgres] [--nodes=1|3] [--accounts=100] [--quiet=45] [--window=60]
#                 [--profile=20] [--server-args="..."] [--install=<dir>] [--out=<dir> | --report[=<session>]]
#
# One session, as `up` starts it, but with no bus: the store alone, the nodes with --wow.transport=none, the accounts
# seeded and every scenario checked once. Then nothing is sent to the nodes for --quiet seconds, and for --window
# seconds the store is measured while they idle: what it serves a second, by operation (MongoDB's opcounters, then its
# profiler for --profile seconds; PostgreSQL's pg_stat_statements), and its container's CPU. Then the nodes are stopped
# and the same store's CPU is measured again for --window seconds, with no node: what the nodes add is the difference.
# The raw inputs go to --out (session.txt, the CPU samples, opcounters.json and profile.json, or statements.tsv), and
# `pnpm bench:idle <out>` (.github/scripts/bench-idle.mjs) writes idle.json and idle.txt from them. The session stops
# when it ends, whatever happens.
#
# The machine is read, not assumed: its chip, cores and memory and Docker's CPUs and memory go into session.txt, and
# --report writes the session into results/reports/<environment>-idle/<session>, the environment named for that
# machine (`pnpm bench:idle --environment`: `actions` on GitHub Actions, else the chip, `m4pro` for an Apple M4 Pro),
# the session `<backend>-<nodes>-node[s]` unless --report names it. `pnpm bench:idle --table <report>` gives the
# report's table.

# The store's container ($1: mongo or postgres).
idle_container() { echo "wow-e2e-bench-$1"; }

# Writes the CPU of container $1, in percent of one core, sampled about every 3 s for $2 seconds, one a line, to $3.
idle_cpu() {
  local container=$1 file=$3 until=$((SECONDS + $2))
  : >"$file"
  while [ "$SECONDS" -lt "$until" ]; do
    docker stats --no-stream --format '{{.CPUPerc}}' "$container" | tr -d '%' >>"$file"
    sleep 2
  done
}

# The machine, one `machine.<key>=<value>` line each: its chip, cores (with the performance and efficiency split where
# the system gives one), memory and system, Docker's CPUs and memory, and whether it is a GitHub Actions runner.
idle_machine() {
  if [ "$(uname)" = Darwin ]; then
    echo "machine.chip=$(sysctl -n machdep.cpu.brand_string)"
    echo "machine.cores=$(sysctl -n hw.ncpu)"
    echo "machine.performanceCores=$(sysctl -n hw.perflevel0.physicalcpu 2>/dev/null || true)"
    echo "machine.efficiencyCores=$(sysctl -n hw.perflevel1.physicalcpu 2>/dev/null || true)"
    echo "machine.memoryBytes=$(sysctl -n hw.memsize)"
    echo "machine.os=macOS $(sw_vers -productVersion)"
  else
    echo "machine.chip=$(sed -n 's/^model name[[:space:]]*:[[:space:]]*//p' /proc/cpuinfo | head -1)"
    echo "machine.cores=$(nproc)"
    echo "machine.memoryBytes=$(($(sed -n 's/^MemTotal:[[:space:]]*\([0-9]*\).*/\1/p' /proc/meminfo) * 1024))"
    echo "machine.os=$(uname -sr)"
  fi
  echo "machine.dockerCpus=$(docker info --format '{{.NCPU}}' 2>/dev/null || true)"
  echo "machine.dockerMemoryBytes=$(docker info --format '{{.MemTotal}}' 2>/dev/null || true)"
  echo "machine.actions=${GITHUB_ACTIONS:-false}"
}

# The chip idle_machine reads, for the report's environment name.
idle_chip() { idle_machine | sed -n 's/^machine\.chip=//p'; }

# Runs JavaScript $1 in MongoDB's shell, on the benchmark's database.
idle_mongo() { docker exec wow-e2e-bench-mongo mongosh --quiet transfer --eval "$1"; }

# MongoDB: the opcounters a second over the window (and the CPU meanwhile), then the profiler's operations, by
# namespace and command, over --profile seconds.
idle_measure_mongo() {
  local window=$1 profile=$2 before after started
  before=$(idle_mongo 'JSON.stringify(db.serverStatus().opcounters)')
  started=$(now_ms)
  idle_cpu "$(idle_container mongo)" "$window" "$OUT/cpu-with-nodes.txt"
  after=$(idle_mongo 'JSON.stringify(db.serverStatus().opcounters)')
  printf '{"millis":%s,"before":%s,"after":%s}\n' "$(($(now_ms) - started))" "$before" "$after" >"$OUT/opcounters.json"
  # A profile collection large enough for the whole window: the default (1 MB) keeps the last few seconds only.
  idle_mongo 'db.setProfilingLevel(0); db.system.profile.drop();
    db.createCollection("system.profile", {capped: true, size: 256 * 1024 * 1024}); db.setProfilingLevel(2)' >/dev/null
  sleep "$profile"
  # shellcheck disable=SC2016 # the aggregation's $ paths are MongoDB's, not the shell's
  idle_mongo 'db.setProfilingLevel(0);
    const ops = db.system.profile.aggregate([
      {$project: {op: 1, ns: 1, command: {$arrayElemAt: [
        {$map: {input: {$objectToArray: {$ifNull: ["$command", {}]}}, in: "$$this.k"}}, 0]}}},
      {$group: {_id: {op: "$op", ns: "$ns", command: "$command"}, count: {$sum: 1}}}, {$sort: {count: -1}}]).toArray();
    const first = db.system.profile.find().sort({ts: 1}).limit(1).toArray()[0];
    const last = db.system.profile.find().sort({ts: -1}).limit(1).toArray()[0];
    print(JSON.stringify({millis: first && last ? last.ts - first.ts : 0,
      operations: ops.map(o => ({op: o._id.op, ns: o._id.ns, command: o._id.command || null, count: o.count}))}))' \
    >"$OUT/profile.json"
}

# PostgreSQL: every statement's calls over the window (pg_stat_statements, reset at its start), and the CPU meanwhile.
idle_measure_postgres() {
  local window=$1 started
  postgres_sql "create extension if not exists pg_stat_statements; select pg_stat_statements_reset();" >/dev/null
  started=$(now_ms)
  idle_cpu "$(idle_container postgres)" "$window" "$OUT/cpu-with-nodes.txt"
  docker exec wow-e2e-bench-postgres psql -U postgres -d transfer -X -q -At -F $'\t' -c \
    "select calls, regexp_replace(query, '\s+', ' ', 'g') from pg_stat_statements
      where dbid = (select oid from pg_database where datname = 'transfer') order by calls desc" >"$OUT/statements.tsv"
  echo "{\"millis\":$(($(now_ms) - started))}" >"$OUT/statements.json"
}

idle() {
  # No bus, and nodes that need none; the session's other options as up takes them.
  parse "$@" --no-bus
  local args; args=$(opt server-args "")
  case " $args " in *" --wow.transport="*) ;; *) args="--wow.transport=none $args" ;; esac
  local backend; backend=$(opt backend mongo)
  local nodes; nodes=$(opt nodes 1)
  local stamp; stamp=$(date +%Y%m%d-%H%M%S)
  local out; out=$(opt out "$REPO/test/benchmarks/results/e2e/$stamp-idle-$backend-n$nodes")
  local report; report=$(opt report "")
  if [ -n "$report" ]; then
    local environment; environment=$(bench_node bench-idle.mjs --environment "$(idle_chip)")
    if [ "$report" = true ]; then
      report="$backend-$nodes-node"
      if [ "$nodes" -gt 1 ]; then report="${report}s"; fi
    fi
    out="$REPO/test/benchmarks/results/reports/$environment-idle/$report"
  fi
  OPTIONS="${OPTIONS}server-args=$args
accounts=$(opt accounts 100)
out=$out
"
  local window; window=$(opt window 60)
  local quiet; quiet=$(opt quiet 45)
  local profile; profile=$(opt profile 20)
  if [ "$backend" = postgres ]; then export WOW_BENCH_PG_PRELOAD=pg_stat_statements; fi
  setup
  log "idle for ${quiet}s, then measuring the store for ${window}s"
  sleep "$quiet"
  case "$backend" in
    mongo) idle_measure_mongo "$window" "$profile" ;;
    *) idle_measure_postgres "$window" ;;
  esac
  log "stopping the nodes; measuring the store alone for ${window}s"
  stop_nodes ${SERVER_PIDS[@]+"${SERVER_PIDS[@]}"}
  SERVER_PIDS=()
  sleep 15
  idle_cpu "$(idle_container "$backend")" "$window" "$OUT/cpu-without-nodes.txt"
  {
    echo "backend=$backend"
    echo "nodes=$(opt nodes 1)"
    echo "accounts=$(opt accounts 100)"
    echo "serverArgs=$args"
    echo "quietSeconds=$quiet"
    echo "windowSeconds=$window"
    echo "commit=$(git -C "$REPO" rev-parse HEAD)"
    idle_machine
    echo "meta=$META"
  } >"$OUT/session.txt"
  bench_node bench-idle.mjs "$OUT" >&2
  log "idle measurement in $OUT (idle.json, idle.txt)"
}
