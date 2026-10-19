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
# The harness of the end-to-end benchmark (decision D224; wow-benchmarks/e2e/README.md). A single run starts from empty
# stores: it starts the stores and the bus (compose.yml), starts the servers of one version in their topology (one node
# or a cluster), seeds the accounts, checks every scenario once, runs the load generator, then stops the servers and
# removes the containers with their volumes, whatever happens. Runs on macOS's bash 3.2.
#
#   bench.sh build
#   bench.sh run      --version=9x|v10 [--backend=mongo|postgres] [--nodes=1|3] --scenario=E1 --rate=50 [options]
#   bench.sh saturate --version=9x|v10 [--backend=...] [--nodes=...] --scenario=E1 [--max-concurrency=256] [options]
#   bench.sh down
#
# Options: --warm-up=30 --measure=60 --distribution=uniform|zipf|hot --accounts=1000 --label=run --out=<dir>
#          --heap=1g --jfr (a flight recording per server) --settle=<s> (after the nodes are up), and any other
#          --name=value of the load generator's run or saturate, passed through.
#
# The grid drivers (tiers.sh, counts.sh) source this file with BENCH_LIB=1 and keep one session per setup: the
# containers and the server JVMs stay up across the setup's cells (session_up, session_cell_seed, session_clear,
# session_down), so a cell costs its load alone.
set -euo pipefail

HARNESS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HARNESS/../../.." && pwd)"
JAVA_HOME="${JAVA_HOME:?Set JAVA_HOME to a JDK 21.}"
export JAVA_HOME
LOAD="$REPO/wow-benchmarks/e2e/load/build/install/e2e-bench-load/bin/e2e-bench-load"
PUBLIC_PORT=18080
INTERNAL_PORT=19090
SERVER_PIDS=()
TARGETS=()
# Compose files beyond compose.yml (counts.sh adds compose.counts.yml), and server arguments beyond the topology's
# (PS-2 adds the partitions and the idle subscriptions).
COMPOSE_EXTRA=()
SERVER_EXTRA=()

usage() {
  sed -n '14,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit 2
}

log() { echo "[bench] $(date +%H:%M:%S) $*" >&2; }

compose() {
  local files=(-f "$HARNESS/compose.yml") extra
  for extra in ${COMPOSE_EXTRA[@]+"${COMPOSE_EXTRA[@]}"}; do files+=(-f "$extra"); done
  docker compose "${files[@]}" "$@"
}

build() {
  log "building the servers and the load generator"
  (cd "$REPO" && ./gradlew --console=plain -q \
    :e2e-bench-9x:installDist :e2e-bench-v10:installDist :e2e-bench-load:installDist)
}

# Stops the server JVMs this shell started.
stop_nodes() {
  local pid
  for pid in ${SERVER_PIDS[@]+"${SERVER_PIDS[@]}"}; do
    kill "$pid" 2>/dev/null || true
  done
  for pid in ${SERVER_PIDS[@]+"${SERVER_PIDS[@]}"}; do
    wait "$pid" 2>/dev/null || true
  done
  SERVER_PIDS=()
  TARGETS=()
}

down() {
  stop_nodes
  compose down -v --remove-orphans >/dev/null 2>&1 || true
}

# The --name=value options, one name=value per line in OPTIONS (bash 3.2 has no associative arrays); the ones the
# harness does not know go to the load generator in PASS.
OPTIONS=""
PASS=()
parse() {
  local argument key
  for argument in "$@"; do
    case "$argument" in
      --*=*) key=${argument%%=*} ;;
      --*) key=$argument; argument="$argument=true" ;;
      *) log "not an option: $argument"; usage ;;
    esac
    OPTIONS="$OPTIONS${argument#--}
"
    case " --version --backend --nodes --label --out --heap --jfr --settle --accounts " in
      *" $key "*) ;;
      *) PASS+=("$argument") ;;
    esac
  done
}

# The value of option $1, or $2.
opt() {
  local line value="$2"
  while IFS= read -r line; do
    if [ "${line%%=*}" = "$1" ]; then value=${line#*=}; fi
  done <<EOF
$OPTIONS
EOF
  echo "$value"
}

# Starts node $1 (1-based) of $VERSION; its output goes to $LOGS/node-$1.log (appended: a session restarts its nodes).
# The launcher execs java, so the pid it records is the server JVM's.
start_node() {
  local n=$1
  local port=$((PUBLIC_PORT + n - 1)) internal=$((INTERNAL_PORT + n - 1))
  local args=("--server.port=$port")
  if [ "$VERSION" = 9x ]; then
    args+=("--cosid.machine.distributor.manual.machine-id=$n")
  else
    args+=("--spring.profiles.active=$BACKEND" "--wow.internal.port=$internal" "--wow.node.id=node-$n")
  fi
  args+=(${SERVER_EXTRA[@]+"${SERVER_EXTRA[@]}"})
  local jvm="-Xms$HEAP -Xmx$HEAP -XX:+UseG1GC"
  if [ "$JFR" = true ]; then
    jvm="$jvm -XX:StartFlightRecording=filename=$LOGS/node-$n-$(date +%H%M%S).jfr,settings=profile"
  fi
  JAVA_OPTS="$jvm" "$REPO/wow-benchmarks/e2e/bench-$VERSION/build/install/e2e-bench-$VERSION/bin/e2e-bench-$VERSION" \
    "${args[@]}" >>"$LOGS/node-$n.log" 2>&1 &
  SERVER_PIDS+=($!)
  TARGETS+=("http://127.0.0.1:$port")
}

# Waits until node $1 answers on its public port; fails when it exits first.
await_node() {
  local n=$1
  local url=${TARGETS[$((n - 1))]} pid=${SERVER_PIDS[$((n - 1))]} probe=/wow/manifest
  if [ "$VERSION" = 9x ]; then probe=/wow/id/global; fi
  local attempt
  for attempt in $(seq 1 120); do
    if ! kill -0 "$pid" 2>/dev/null; then
      log "node $n exited:"; tail -40 "$LOGS/node-$n.log" >&2; return 1
    fi
    if [ "$(curl -s -o /dev/null -w '%{http_code}' "$url$probe")" = 200 ]; then return 0; fi
    sleep 1
  done
  log "node $n did not start in ${attempt}s"; tail -40 "$LOGS/node-$n.log" >&2; return 1
}

# Starts the $NODES nodes and waits for them to settle; sets TARGET_LIST, PIDS and META.
start_nodes() {
  local n
  for n in $(seq 1 "$NODES"); do
    log "starting $VERSION node $n on $BACKEND"
    start_node "$n"
    await_node "$n"
  done
  local settle=2
  if [ "$NODES" -gt 1 ]; then settle=15; fi
  settle=${SETTLE:-$settle}
  log "nodes up; settling ${settle}s"
  sleep "$settle"
  TARGET_LIST=$(IFS=,; echo "${TARGETS[*]}")
  PIDS=""
  for n in $(seq 1 "$NODES"); do PIDS="${PIDS}node-$n:${SERVER_PIDS[$((n - 1))]},"; done
  PIDS=${PIDS%,}
  local jvm; jvm=$("$JAVA_HOME/bin/java" -version 2>&1 | tr '\n' ' ' | sed 's/;/,/g')
  local extra; extra=$(echo ${SERVER_EXTRA[@]+"${SERVER_EXTRA[@]}"} | sed 's/;/,/g')
  META="backend=$BACKEND;nodes=$NODES;heap=$HEAP;gc=G1;serverJvm=$jvm;stores=kafka $STORE;serverArgs=$extra"
}

# Opens $ACCOUNTS accounts under the id prefix $1 (default acc) and checks every scenario once on them.
seed_accounts() {
  local prefix=${1:-acc} out=${2:-$LOGS}
  log "seeding $ACCOUNTS accounts ($prefix-*)"
  "$LOAD" seed --version="$VERSION" --targets="$TARGET_LIST" --accounts="$ACCOUNTS" --account-prefix="$prefix" \
    | tee -a "$out/seed.log" >&2
  log "checking every scenario once"
  "$LOAD" check --version="$VERSION" --targets="$TARGET_LIST" --accounts="$ACCOUNTS" --account-prefix="$prefix" \
    | tee -a "$out/check.log" >&2
}

# Starts a session: the containers of $BACKEND and Kafka from empty, then the nodes. Expects VERSION, BACKEND, NODES,
# HEAP, ACCOUNTS, LOGS (and optionally JFR, SETTLE, COMPOSE_EXTRA, SERVER_EXTRA).
session_up() {
  if [ "$VERSION" = 9x ] && [ "$BACKEND" != mongo ]; then log "9.x runs on MongoDB only (D224)"; exit 2; fi
  JFR=${JFR:-false}
  mkdir -p "$LOGS"
  if [ ! -x "$LOAD" ]; then build; fi
  down
  STORE=postgres
  if [ "$BACKEND" = mongo ]; then STORE=mongo; fi
  log "starting kafka and $STORE"
  compose up -d --wait kafka "$STORE" >/dev/null
  CONTAINERS="wow-e2e-bench-kafka,wow-e2e-bench-$STORE"
  STORE_OPTION="--$STORE=wow-e2e-bench-$STORE"
  start_nodes
}

# Empties the stores and the bus without removing the containers, the nodes stopped meanwhile and started again: a
# running node keeps state (V10's state cache, partition counters, leases) that must not outlive its data, so the
# harness has no way to clear a store under a running node. Costs a node start, not a container start.
session_clear() {
  stop_nodes
  log "clearing the $STORE store and the Kafka topics"
  if [ "$STORE" = mongo ]; then
    docker exec wow-e2e-bench-mongo mongosh --quiet --eval \
      "db.getMongo().getDBNames().filter(n => !['admin','local','config'].includes(n)).forEach(n => db.getSiblingDB(n).dropDatabase())" \
      >/dev/null
  else
    docker exec wow-e2e-bench-postgres psql -U postgres -d postgres -qc \
      "drop database if exists transfer_v10 with (force)" -c "create database transfer_v10" >/dev/null
  fi
  # Every topic but Kafka's own (__consumer_offsets), in one call: --topic takes a regular expression.
  docker exec wow-e2e-bench-kafka kafka-topics --bootstrap-server localhost:9092 --delete --topic '[^_].*' \
    >/dev/null 2>&1 || true
  # Topic deletion is asynchronous: wait until none is left before a node recreates them.
  local attempt
  for attempt in $(seq 1 30); do
    if [ -z "$(docker exec wow-e2e-bench-kafka kafka-topics --bootstrap-server localhost:9092 --list 2>/dev/null \
      | grep -v '^__' || true)" ]; then break; fi
    sleep 1
  done
  start_nodes
}

session_down() { down; }

# Reads the options of a single-cell command and starts its session.
setup() {
  VERSION=$(opt version "")
  if [ "$VERSION" != 9x ] && [ "$VERSION" != v10 ]; then log "--version=9x|v10"; usage; fi
  BACKEND=$(opt backend mongo)
  NODES=$(opt nodes 1)
  HEAP=$(opt heap 1g)
  ACCOUNTS=$(opt accounts 1000)
  LABEL=$(opt label run)
  JFR=$(opt jfr false)
  SETTLE=$(opt settle "")
  local scenario; scenario=$(opt scenario saturation)
  OUT=$(opt out "$REPO/wow-benchmarks/results/e2e/$(date +%Y%m%d-%H%M%S)-$LABEL-$VERSION-$BACKEND-n$NODES-$scenario")
  LOGS=$OUT
  mkdir -p "$OUT"
  trap down EXIT
  session_up
  seed_accounts acc "$OUT"
}

# One open-loop run against the running session, its results in $1; the rest are the load generator's options.
cell_run() {
  local out=$1; shift
  mkdir -p "$out"
  "$LOAD" run --version="$VERSION" --targets="$TARGET_LIST" --accounts="$ACCOUNTS" --out="$out" \
    --pids="$PIDS" --containers="$CONTAINERS" --kafka=wow-e2e-bench-kafka "$STORE_OPTION" --repository="$REPO" \
    --meta="$META" "$@"
}

# The saturation finder against the running session, its steps in $1/saturation.json.
cell_saturate() {
  local out=$1; shift
  mkdir -p "$out"
  "$LOAD" saturate --version="$VERSION" --targets="$TARGET_LIST" --accounts="$ACCOUNTS" --out="$out" \
    --meta="$META" "$@"
}

run() {
  parse "$@"
  setup
  log "running $(opt scenario ?) at $(opt rate ?)/s"
  cell_run "$OUT" --label="$LABEL" ${PASS[@]+"${PASS[@]}"}
  log "results in $OUT"
}

saturate() {
  parse "$@"
  setup
  cell_saturate "$OUT" ${PASS[@]+"${PASS[@]}"}
  log "results in $OUT"
}

if [ "${BENCH_LIB:-}" = 1 ]; then return 0; fi
command=${1:-}
if [ -z "$command" ]; then usage; fi
shift
case "$command" in
  build) build ;;
  run) run "$@" ;;
  saturate) saturate "$@" ;;
  down) down ;;
  *) usage ;;
esac
