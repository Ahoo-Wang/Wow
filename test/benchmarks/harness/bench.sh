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
# The harness of the end-to-end benchmark (decision D224; test/benchmarks/README.md). Each run starts from empty
# stores: it starts the stores and the bus (compose.yml), starts the servers in their topology (one node or a cluster),
# seeds the accounts, checks every scenario once, runs the load generator, then stops the servers and removes the
# containers with their volumes, whatever happens. Runs on macOS's bash 3.2.
#
#   bench.sh build
#   bench.sh run      [--backend=mongo|postgres] [--nodes=1|3] --scenario=E1 --rate=50 [options]
#   bench.sh saturate [--backend=...] [--nodes=...] --scenario=E1 [--max-concurrency=256] [options]
#   bench.sh component --backend=mongo|postgres --scenario=E1|E3|E1-large [--ops=20000] [--warm-up-ops=2000]
#                      [--concurrency=64] [options]   (L1: the V10 domain on the kernel and one store, in one JVM)
#   bench.sh up       [--backend=...] [--nodes=...] [--accounts=...] [--prime] [--jfr] [options]
#   bench.sh down
#
# A session: `up` starts the stores, the servers, seeds and primes as a run would, and leaves them up; a `run` or
# `saturate` with --reuse then measures on them (after waiting until the nodes are quiet), and leaves them up too;
# `down` stops them. A reused run must name the session's backend, nodes, accounts, heap and install. With
# `up --jfr`, each reused run dumps the servers' recordings over its warm-up and measured phase.
#
# Options: --warm-up=30 --measure=60 --distribution=uniform|zipf|hot --accounts=1000 --label=run --out=<dir>
#          --heap=1g --jfr (a flight recording per server) --settle=<s> (after the nodes are up), --install=<dir> (the
#          server's installed distribution, by default this tree's server/build/install/benchmark-server: another
#          build, such as a baseline's), --seed-concurrency=32 (accounts opened at once),
#          --load-cpus=<list> and --server-cpus=<list> (Linux, taskset lists: the load generator on its own CPUs, the
#          servers and the containers on the others), --prime (an untimed pass over every account before the run,
#          then a wait until the nodes are quiet again),
#          --jfr-settings=<.jfc> (with --jfr; on PostgreSQL --jfr also dumps pg_stat_statements and checkpoints),
#          and any other --name=value of the load generator's run or saturate, passed through.
set -euo pipefail

HARNESS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HARNESS/../../.." && pwd)"
JAVA_HOME="${JAVA_HOME:?Set JAVA_HOME to a JDK 21.}"
export JAVA_HOME
LOAD="$REPO/test/benchmarks/load/build/install/benchmark-load/bin/benchmark-load"
PUBLIC_PORT=18080
INTERNAL_PORT=19090
SERVER_PIDS=()
TARGETS=()
# The session `up` leaves for runs with --reuse: its settings, servers and targets.
SESSION="$REPO/test/benchmarks/results/e2e/.session"
UP=false

usage() {
  sed -n '14,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit 2
}

log() { echo "[bench] $*" >&2; }

compose() { docker compose -f "$HARNESS/compose.yml" "$@"; }

build() {
  log "building the servers and the load generator"
  (cd "$REPO" && ./gradlew --console=plain -q :benchmark-server:installDist :benchmark-load:installDist)
}

down() {
  local pid
  if [ -f "$SESSION" ]; then
    # shellcheck disable=SC1090
    SERVER_PIDS=($(. "$SESSION"; echo "$S_PIDS"))
    rm -f "$SESSION"
  fi
  for pid in ${SERVER_PIDS[@]+"${SERVER_PIDS[@]}"}; do
    kill "$pid" 2>/dev/null || true
  done
  for pid in ${SERVER_PIDS[@]+"${SERVER_PIDS[@]}"}; do
    wait "$pid" 2>/dev/null || true
    # A session's server is not this shell's child: wait until it is gone, at most 30 s.
    local _
    for _ in $(seq 1 150); do kill -0 "$pid" 2>/dev/null || break; sleep 0.2; done
  done
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
    case " --backend --nodes --label --out --heap --jfr --jfr-settings --settle --accounts --install --seed-concurrency --load-cpus --server-cpus --prime --reuse " in
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

# PREFIX: the words that run a command on the CPUs of option $1 (--load-cpus, --server-cpus), `taskset -c <list>`
# when the option names some and taskset is there (Linux), else none. taskset execs the command, so a pid recorded for
# a pinned server is still the server's.
PREFIX=()
pin() {
  local cpus; cpus=$(opt "$1" "")
  PREFIX=()
  if [ -n "$cpus" ] && command -v taskset >/dev/null 2>&1; then PREFIX=(taskset -c "$cpus"); fi
}

# The load generator, on the --load-cpus.
load() {
  pin load-cpus
  ${PREFIX[@]+"${PREFIX[@]}"} "$LOAD" "$@"
}

# The launcher of the server distribution $1: benchmark-server, or a baseline's from before test/benchmarks/
# (e2e-bench-v10).
server_launcher() {
  local name
  for name in benchmark-server e2e-bench-v10; do
    if [ -x "$1/bin/$name" ]; then echo "$1/bin/$name"; return; fi
  done
  echo "$1/bin/benchmark-server"
}

# Starts node $1 (1-based); its output goes to $OUT/node-$1.log. The launcher execs java, so the pid it
# records is the server JVM's.
start_node() {
  local n=$1
  local port=$((PUBLIC_PORT + n - 1)) internal=$((INTERNAL_PORT + n - 1))
  local args=("--server.port=$port" "--spring.profiles.active=$BACKEND" "--wow.internal.port=$internal" "--wow.node.id=node-$n")
  local jvm="-Xms$HEAP -Xmx$HEAP -XX:+UseG1GC"
  if [ "$(opt jfr false)" = true ]; then
    jvm="$jvm -XX:StartFlightRecording=filename=$OUT/node-$n.jfr,settings=$(opt jfr-settings profile)"
  fi
  # --install names the build; else the one in this tree.
  local install
  install=$(opt install "$REPO/test/benchmarks/server/build/install/benchmark-server")
  local launcher
  launcher=$(server_launcher "$install")
  pin server-cpus
  JAVA_OPTS="$jvm" ${PREFIX[@]+"${PREFIX[@]}"} "$launcher" \
    "${args[@]}" >"$OUT/node-$n.log" 2>&1 &
  SERVER_PIDS+=($!)
  TARGETS+=("http://127.0.0.1:$port")
}

# Waits until node $1 answers on its public port; fails when it exits first.
await_node() {
  local n=$1
  local url=${TARGETS[$((n - 1))]} pid=${SERVER_PIDS[$((n - 1))]} probe=/wow/manifest
  local attempt
  for attempt in $(seq 1 120); do
    if ! kill -0 "$pid" 2>/dev/null; then
      log "node $n exited:"; tail -40 "$OUT/node-$n.log" >&2; return 1
    fi
    if [ "$(curl -s -o /dev/null -w '%{http_code}' "$url$probe")" = 200 ]; then return 0; fi
    sleep 1
  done
  log "node $n did not start in ${attempt}s"; tail -40 "$OUT/node-$n.log" >&2; return 1
}

setup() {
  BACKEND=$(opt backend mongo)
  NODES=$(opt nodes 1)
  HEAP=$(opt heap 1g)
  ACCOUNTS=$(opt accounts 1000)
  LABEL=$(opt label run)
  local scenario; scenario=$(opt scenario saturation)
  OUT=$(opt out "$REPO/test/benchmarks/results/e2e/$(date +%Y%m%d-%H%M%S)-$LABEL-$BACKEND-n$NODES-$scenario")
  mkdir -p "$OUT"
  if [ ! -x "$LOAD" ]; then build; fi
  if [ "$(opt reuse false)" = true ]; then
    resume
    return
  fi
  # Everything started here stops when the script ends, also for `up` until its session is written, so an `up`
  # that fails leaves no server behind.
  trap down EXIT
  down
  local store=postgres
  if [ "$BACKEND" = mongo ]; then store=mongo; fi
  log "starting kafka and $store"
  # A profiled PostgreSQL run loads pg_stat_statements, for the statements behind the tails (postgres_stats).
  if [ "$(opt jfr false)" = true ] && [ "$store" = postgres ]; then export WOW_BENCH_PG_PRELOAD=pg_stat_statements; fi
  compose up -d --wait kafka "$store" >/dev/null
  if [ -n "$(opt server-cpus "")" ]; then
    docker update --cpuset-cpus "$(opt server-cpus "")" wow-e2e-bench-kafka "wow-e2e-bench-$store" >/dev/null
  fi
  CONTAINERS="wow-e2e-bench-kafka,wow-e2e-bench-$store"
  STORE_OPTION="--$store=wow-e2e-bench-$store"
  local n
  for n in $(seq 1 "$NODES"); do
    log "starting node $n on $BACKEND"
    start_node "$n"
    await_node "$n"
  done
  local settle=2
  if [ "$NODES" -gt 1 ]; then settle=15; fi
  settle=$(opt settle "$settle")
  log "nodes up; settling ${settle}s"
  sleep "$settle"
  TARGET_LIST=$(IFS=,; echo "${TARGETS[*]}")
  log "seeding $ACCOUNTS accounts"
  load seed --targets="$TARGET_LIST" --accounts="$ACCOUNTS" \
    --concurrency="$(opt seed-concurrency 32)" | tee "$OUT/seed.log" >&2
  log "checking every scenario once"
  # Only the run's scenarios, so a baseline without a newer scenario (E1-large) still runs the ones it has.
  load check --targets="$TARGET_LIST" --accounts="$ACCOUNTS" --scenario="$(opt scenario "")" \
    --mix="$(opt mix "")" | tee "$OUT/check.log" >&2
  # --prime: an untimed pass over every seeded account (D224 note ⑤), so a large data set is measured warm.
  local primed=false prime_seconds=0 settle_seconds=0
  if [ "$(opt prime false)" = true ]; then
    log "priming $ACCOUNTS accounts"
    local prime_started=$SECONDS
    load prime --targets="$TARGET_LIST" --accounts="$ACCOUNTS" \
      --concurrency="$(opt seed-concurrency 256)" --out="$OUT" | tee "$OUT/prime.log" >&2
    primed=true
    prime_seconds=$((SECONDS - prime_started))
    # The prime leaves the nodes a backlog (roadmap §6, performance item 2): wait until they are quiet again, so the
    # warm-up starts from the steady state rather than from that backlog.
    local settle_pids="" settle_started=$SECONDS
    for n in $(seq 1 "$NODES"); do settle_pids="${settle_pids}node-$n:${SERVER_PIDS[$((n - 1))]},"; done
    load settle --targets="$TARGET_LIST" --pids="${settle_pids%,}" --out="$OUT" \
      | tee "$OUT/settle.log" >&2
    settle_seconds=$((SECONDS - settle_started))
  fi
  # The statements counted from here on are the run's (warm-up and measured phase), not the seeding's.
  if [ -n "${WOW_BENCH_PG_PRELOAD:-}" ]; then postgres_sql "create extension if not exists pg_stat_statements; select pg_stat_statements_reset();" >/dev/null; fi
  PIDS=""
  for n in $(seq 1 "$NODES"); do PIDS="${PIDS}node-$n:${SERVER_PIDS[$((n - 1))]},"; done
  PIDS=${PIDS%,}
  local jvm; jvm=$("$JAVA_HOME/bin/java" -version 2>&1 | tr '\n' ' ' | sed 's/;/,/g')
  META="backend=$BACKEND;nodes=$NODES;heap=$HEAP;gc=G1;serverJvm=$jvm;stores=kafka $store;primed=$primed"
  META="$META;primeSeconds=$prime_seconds;settleSeconds=$settle_seconds;accounts=$ACCOUNTS"
  if [ "$UP" = true ]; then
    # results/e2e is ignored by git: a fresh checkout has no such directory until a run's output makes one.
    mkdir -p "$(dirname "$SESSION")"
    {
      printf 'S_KEY=%q\n' "$(session_key)"
      printf 'S_PIDS=%q\n' "${SERVER_PIDS[*]}"
      printf 'S_TARGETS=%q\n' "${TARGETS[*]}"
      printf 'S_STORE=%q\n' "$store"
      printf 'S_META=%q\n' "$META"
      printf 'S_JFR=%q\n' "$(opt jfr false)"
      printf 'S_OUT=%q\n' "$OUT"
    } >"$SESSION"
  fi
}

# What a reused run must match of the session: the backend, nodes, accounts, heap and install.
session_key() {
  echo "backend=$BACKEND;nodes=$NODES;accounts=$ACCOUNTS;heap=$HEAP;install=$(opt install "")"
}

# Takes up the session `up` left (--reuse): its servers and targets, once the nodes are quiet again.
resume() {
  if [ ! -f "$SESSION" ]; then log "--reuse: no session; run bench.sh up first"; exit 2; fi
  # shellcheck disable=SC1090
  . "$SESSION"
  if [ "$S_KEY" != "$(session_key)" ]; then
    log "--reuse: the session is $S_KEY, not $(session_key)"; exit 2
  fi
  SERVER_PIDS=($S_PIDS)
  TARGETS=($S_TARGETS)
  local pid
  for pid in "${SERVER_PIDS[@]}"; do
    if ! kill -0 "$pid" 2>/dev/null; then log "--reuse: the session's server $pid is gone; run bench.sh up again"; exit 2; fi
  done
  TARGET_LIST=$(IFS=,; echo "${TARGETS[*]}")
  CONTAINERS="wow-e2e-bench-kafka,wow-e2e-bench-$S_STORE"
  STORE_OPTION="--$S_STORE=wow-e2e-bench-$S_STORE"
  local n
  PIDS=""
  for n in $(seq 1 "$NODES"); do PIDS="${PIDS}node-$n:${SERVER_PIDS[$((n - 1))]},"; done
  PIDS=${PIDS%,}
  # The run before may have left the nodes a backlog, as a prime does: wait until they are quiet.
  local settle_started=$SECONDS
  load settle --targets="$TARGET_LIST" --pids="$PIDS" --out="$OUT" | tee "$OUT/settle.log" >&2
  if [ "$S_STORE" = postgres ] && [ "$S_JFR" = true ]; then
    postgres_sql "select pg_stat_statements_reset();" >/dev/null || true
  fi
  META="${S_META%%;settleSeconds=*};settleSeconds=$((SECONDS - settle_started));accounts=$ACCOUNTS;session=$S_OUT"
}

# A reused run's flight recordings: each server's, over the last $1 seconds (its warm-up and measured phase).
dump_recordings() {
  [ "$(opt reuse false)" = true ] && [ "${S_JFR:-false}" = true ] || return 0
  local n
  for n in $(seq 1 "$NODES"); do
    "$JAVA_HOME/bin/jcmd" "${SERVER_PIDS[$((n - 1))]}" JFR.dump filename="$OUT/node-$n.jfr" begin="-${1}s" \
      >/dev/null 2>&1 || log "no recording of node $n"
  done
}

# Starts a session (see the header): the stores and servers stay up for runs with --reuse until `down`.
up() {
  parse "$@"
  UP=true
  setup
  trap - EXIT
  log "session up: $(session_key); measure with --reuse, stop with bench.sh down"
}

# Runs SQL on the benchmark's PostgreSQL container.
postgres_sql() { docker exec wow-e2e-bench-postgres psql -U postgres -d transfer -X -q -c "$1"; }

# A profiled PostgreSQL run's store side (D224 profile): the top statements by total time and the checkpoints, into
# $OUT/postgres-stats.txt.
postgres_stats() {
  {
    echo "# Top statements by total execution time (pg_stat_statements, since the run's warm-up began)"
    postgres_sql "select calls, round(total_exec_time::numeric, 1) as total_ms, round(mean_exec_time::numeric, 3) as mean_ms,
      round(max_exec_time::numeric, 1) as max_ms, rows, left(regexp_replace(query, '\s+', ' ', 'g'), 140) as query
      from pg_stat_statements order by total_exec_time desc limit 15"
    echo "# Checkpoints (pg_stat_checkpointer)"
    postgres_sql "select * from pg_stat_checkpointer"
    echo "# Waits now (pg_stat_activity)"
    postgres_sql "select wait_event_type, wait_event, count(*) from pg_stat_activity group by 1, 2 order by 3 desc"
  } >"$OUT/postgres-stats.txt" 2>&1 || true
}

run() {
  parse "$@"
  setup
  log "running $(opt scenario ?) at $(opt rate ?)/s"
  load run --targets="$TARGET_LIST" --accounts="$ACCOUNTS" --label="$LABEL" --out="$OUT" \
    --pids="$PIDS" --containers="$CONTAINERS" --kafka=wow-e2e-bench-kafka "$STORE_OPTION" --repository="$REPO" \
    --meta="$META" ${PASS[@]+"${PASS[@]}"}
  dump_recordings "$(( $(opt warm-up 30) + $(opt measure 60) + 5 ))"
  if [ -n "${WOW_BENCH_PG_PRELOAD:-}" ] || { [ "${S_STORE:-}" = postgres ] && [ "${S_JFR:-false}" = true ]; }; then
    postgres_stats
  fi
  log "results in $OUT"
}

saturate() {
  parse "$@"
  setup
  load saturate --targets="$TARGET_LIST" --accounts="$ACCOUNTS" --out="$OUT" \
    --meta="$META" ${PASS[@]+"${PASS[@]}"}
  log "results in $OUT"
}

# The L1 component run (D224's ladder): the V10 server's domain, kernel and one store in one JVM, no HTTP and no load
# generator, on fresh containers; ComponentBench in the server's own jar writes component.json to --out. A build that
# has no ComponentBench (one older than it) exits 3.
component() {
  parse "$@"
  BACKEND=$(opt backend mongo)
  local scenario; scenario=$(opt scenario E1)
  OUT=$(opt out "$REPO/test/benchmarks/results/e2e/$(date +%Y%m%d-%H%M%S)-component-$BACKEND-$scenario")
  mkdir -p "$OUT"
  local install
  install=$(opt install "$REPO/test/benchmarks/server/build/install/benchmark-server")
  local jar
  for jar in "$install"/lib/benchmark-server*.jar "$install"/lib/e2e-bench-v10*.jar; do
    if "$JAVA_HOME/bin/jar" tf "$jar" | grep -q 'ComponentBenchKt.class'; then break; fi
    jar=""
  done
  if [ -z "$jar" ]; then log "$install has no ComponentBench"; exit 3; fi
  trap down EXIT
  down
  local store=postgres
  if [ "$BACKEND" = mongo ]; then store=mongo; fi
  log "starting kafka and $store"
  compose up -d --wait kafka "$store" >/dev/null
  if [ -n "$(opt server-cpus "")" ]; then
    docker update --cpuset-cpus "$(opt server-cpus "")" wow-e2e-bench-kafka "wow-e2e-bench-$store" >/dev/null
  fi
  pin server-cpus
  log "component $scenario on $BACKEND"
  ${PREFIX[@]+"${PREFIX[@]}"} "$JAVA_HOME/bin/java" -Xms"$(opt heap 1g)" -Xmx"$(opt heap 1g)" -XX:+UseG1GC \
    -cp "$install/lib/*" me.ahoo.wow.benchmark.transfer.ComponentBenchKt --backend="$BACKEND" --scenario="$scenario" \
    --accounts="$(opt accounts 1000)" --ops="$(opt ops 20000)" --warm-up-ops="$(opt warm-up-ops 2000)" \
    --concurrency="$(opt concurrency 64)" --out="$OUT" 2>&1 | tee "$OUT/component.log" >&2
  log "results in $OUT"
}

command=${1:-}
if [ -z "$command" ]; then usage; fi
shift
case "$command" in
  build) build ;;
  run) run "$@" ;;
  saturate) saturate "$@" ;;
  component) component "$@" ;;
  up) up "$@" ;;
  down) down ;;
  *) usage ;;
esac
