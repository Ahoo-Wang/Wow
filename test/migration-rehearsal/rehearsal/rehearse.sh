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
# The blue-green rehearsal of roadmap 8c.1 (design 07 §3, decision D32): one complete 9.x → V10 cut-over of the 9.x
# example, with both rollbacks. Every step is timed into $WORK/steps.jsonl; report.py renders $WORK/report.md.
#
#   1. seed      the 9.x example-server (v10-9x-baseline) with ORDERS orders and their carts, and the 9.x compensation
#                service's database with unresolved CartSaga executions
#   2. import    migrate-data check and import into the V10 log on PostgreSQL
#   3. dual-run  9.x keeps taking writes for DUAL_RUN_SECONDS while migrate-data tail follows it; the lag is how long
#                the tail takes to catch up once the writes stop
#   4. cut-over  9.x stops (the write stop starts); the final import --restart, compare, snapshot and backfill-tags;
#                the V10 host starts and serves writes (the write stop ends)
#   5. rollback before V10 writes: export --dry-run finds nothing to write back, 9.x starts again on its own store,
#                and its states equal V10's; then the cut-over again
#   6. V10 traffic for V10_SECONDS: orders moved on, carts filled
#   7. rollback after V10 writes: V10 stops, export writes its streams back, 9.x starts and its states equal V10's,
#                and it takes writes again
#   8. refusal   V10 creates orders (OrderCreated at revision 2, which 9.x cannot read) and export --dry-run refuses
#
# Environment: NINEX_HOME (the 9.x example-server's installDist), V10_HOME (:migration-rehearsal's installDist),
# MIGRATE_HOME (:migrate-data's installDist), MANIFEST (the rehearsal host's Manifest), WORK (an empty directory);
# ORDERS (200), DUAL_RUN_SECONDS (20), V10_SECONDS (20), CONCURRENCY (32), EXECUTIONS (ORDERS / 20), SAMPLE (every
# n-th order compared, 1), DUAL_RUN_HOST (off: the V10 host starts at the cut-over; on: it runs through the dual-run).
set -euo pipefail

REHEARSAL="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
: "${NINEX_HOME:?the 9.x example-server installation}" "${V10_HOME:?the rehearsal host installation}"
: "${MIGRATE_HOME:?the migrate-data installation}" "${MANIFEST:?the rehearsal host Manifest}" "${WORK:?a work directory}"
ORDERS="${ORDERS:-200}"
DUAL_RUN_SECONDS="${DUAL_RUN_SECONDS:-20}"
V10_SECONDS="${V10_SECONDS:-20}"
CONCURRENCY="${CONCURRENCY:-32}"
EXECUTIONS="${EXECUTIONS:-$((ORDERS / 20 > 0 ? ORDERS / 20 : 1))}"
SAMPLE="${SAMPLE:-1}"
DUAL_RUN_HOST="${DUAL_RUN_HOST:-off}"
MONGO_PORT="${REHEARSAL_MONGO_PORT:-27017}"
POSTGRES_PORT="${REHEARSAL_POSTGRES_PORT:-5432}"
NINEX_PORT="${NINEX_PORT:-18080}"
V10_PORT="${V10_PORT:-18090}"
V10_INTERNAL_PORT="${V10_INTERNAL_PORT:-19090}"
NINEX_URL="http://localhost:$NINEX_PORT"
V10_URL="http://localhost:$V10_PORT"
mkdir -p "$WORK"
STEPS="$WORK/steps.jsonl"
: >"$STEPS"

log() { echo "[rehearsal] $*" >&2; }
now_ms() { python3 -c 'import time; print(int(time.time() * 1000))'; }
compose() { docker compose -f "$REHEARSAL/compose.yml" "$@"; }
mongosh_run() { docker exec wow-rehearsal-mongo mongosh --quiet "$@"; }

# record NAME START_MS EXIT [JSON]: one line of steps.jsonl.
record() {
  local name="$1" start="$2" code="$3" data="${4:-}"
  local end
  end="$(now_ms)"
  [ -n "$data" ] || data='{}'
  printf '{"step":"%s","startMs":%s,"millis":%s,"exit":%s,"data":%s}\n' "$name" "$start" "$((end - start))" "$code" "$data" >>"$STEPS"
  log "$name: exit $code, $((end - start)) ms"
}

# step NAME EXPECTED CMD…: runs CMD, its output to $WORK/NAME.log, its last JSON line as the step's data; fails the
# rehearsal unless it exits with EXPECTED.
step() {
  local name="$1" expected="$2"
  shift 2
  local start code data
  start="$(now_ms)"
  set +e
  "$@" >"$WORK/$name.log" 2>&1
  code=$?
  set -e
  data="$(grep -E '^\{.*\}$' "$WORK/$name.log" | tail -1 || true)"
  record "$name" "$start" "$code" "$data"
  if [ "$expected" != any ] && [ "$code" != "$expected" ]; then
    log "$name exited with $code, not $expected; its log:"
    tail -40 "$WORK/$name.log" >&2
    exit 1
  fi
}

# The kinds of difference compare may report, each explained and signed off (design 07 §3 step 4):
# - $tags: 9.x merged the tags its extractor derived into the stored ones, so a changed address kept the old province;
#   V10's @Tags replaces each key it derives (decision D220). An intended change.
EXPLAINED_KINDS="${EXPLAINED_KINDS:-\$tags}"

# compared NAME CMD…: a comparison that passes, or differs only in the EXPLAINED_KINDS.
compared() {
  local name="$1"
  shift
  step "$name" any "$@"
  local code
  code="$(tail -1 "$STEPS" | python3 -c 'import json, sys; print(json.load(sys.stdin)["exit"])')"
  [ "$code" = 0 ] && return 0
  if [ "$code" != 3 ]; then
    log "$name exited with $code"
    exit 1
  fi
  local kinds unexplained
  kinds="$(grep -oE 'differing fields: .*' "$WORK/$name.log" | sed -E 's/differing fields: //; s/ \([0-9]+\)//g; s/, /|/g' | tr '|' '\n' | sort -u)"
  unexplained="$(printf '%s\n' "$kinds" | grep -vxF -f <(printf '%s\n' $EXPLAINED_KINDS) || true)"
  if [ -n "$unexplained" ]; then
    log "$name found unexplained differences: $unexplained"
    exit 1
  fi
  log "$name: every difference is an explained kind ($(echo $kinds))"
}

java_home() { echo "${JAVA_HOME:?JAVA_HOME (JDK 21)}"; }
driver() { "$(java_home)/bin/java" -cp "$V10_HOME/lib/*" me.ahoo.wow.rehearsal.driver.RehearsalDriverKt "$@" --concurrency="$CONCURRENCY"; }
migrate() { JAVA_OPTS="-Xmx2g" "$MIGRATE_HOME/bin/migrate-data" "$1" --config "$WORK/migrate-data.json" "${@:2}"; }

await_http() {
  local url="$1" name="$2"
  for _ in $(seq 1 120); do
    if curl -sf "$url/actuator/health" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  log "$name did not come up; its log:"
  tail -60 "$WORK/$name.out" >&2
  return 1
}

start_ninex() {
  (
    cd "$NINEX_HOME"
    env JAVA_OPTS="-Dcom.sun.management.jmxremote.port=0" \
      SERVER_PORT="$NINEX_PORT" \
      SPRING_MONGODB_URI="mongodb://localhost:$MONGO_PORT/wow_example_db" \
      SPRING_AUTOCONFIGURE_EXCLUDE="org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchClientAutoConfiguration,org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchRestClientAutoConfiguration" \
      WOW_EVENTSOURCING_STORE_STORAGE=mongo WOW_EVENTSOURCING_SNAPSHOT_STORAGE=mongo \
      LOGGING_LEVEL_ME_AHOO_WOW=WARN \
      ./bin/example-server >>"$WORK/ninex.out" 2>&1 &
    echo $! >"$WORK/ninex.pid"
  )
  await_http "$NINEX_URL" ninex
}

start_v10() {
  (
    cd "$V10_HOME"
    env JAVA_OPTS="-Xmx1g" ./bin/migration-rehearsal \
      --server.port="$V10_PORT" --wow.internal.port="$V10_INTERNAL_PORT" \
      --spring.datasource.url="jdbc:postgresql://localhost:$POSTGRES_PORT/example" \
      >>"$WORK/v10.out" 2>&1 &
    echo $! >"$WORK/v10.pid"
  )
  await_http "$V10_URL" v10
}

stop_pid() {
  local file="$WORK/$1.pid"
  [ -f "$file" ] || return 0
  local pid
  pid="$(cat "$file")"
  # The launchers exec java: their pid is the JVM's. A graceful stop drains what is in flight.
  kill "$pid" 2>/dev/null || true
  for _ in $(seq 1 60); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
  kill -9 "$pid" 2>/dev/null || true
  rm -f "$file"
}

ninex_streams() {
  mongosh_run wow_example_db --eval 'print(JSON.stringify({orders: db.order_event_stream.countDocuments({}), carts: db.cart_event_stream.countDocuments({})}))'
}

cleanup() {
  stop_pid tail || true
  stop_pid v10 || true
  stop_pid ninex || true
  if [ "${KEEP_STORES:-false}" != "true" ]; then compose down -v >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT

write_config() {
  cp "$REHEARSAL/migration-map.json" "$WORK/migration-map.json"
  cp "$MANIFEST" "$WORK/manifest.json"
  cat >"$WORK/migrate-data.json" <<EOF
{
  "map": "migration-map.json",
  "manifests": ["manifest.json"],
  "checkpoint": "migrate-data.checkpoint.json",
  "source": { "uri": "mongodb://localhost:$MONGO_PORT", "database": "wow_example_db", "createCommitTimeIndex": true },
  "target": {
    "type": "postgres",
    "jdbcUrl": "jdbc:postgresql://localhost:$POSTGRES_PORT/example",
    "username": "postgres",
    "password": "postgres"
  },
  "partitions": { "example": 64 },
  "classpath": ["$V10_HOME/lib"],
  "compensation": { "database": "compensation_db", "service": "example-service" },
  "lookbackMillis": 5000,
  "pollIntervalMillis": 500
}
EOF
}

log "stores up"
compose down -v >/dev/null 2>&1 || true
compose up -d --wait
write_config

# 1. seed
start_ninex
step seed 0 driver seed --version=9x --base="$NINEX_URL" --book="$WORK/book.tsv" --orders="$ORDERS"
step seed-compensation 0 mongosh_run --eval "var count = $EXECUTIONS, example = 'wow_example_db', compensation = 'compensation_db'" /rehearsal/seed-compensation.js
step ninex-seeded 0 ninex_streams

# 2. import
step check 0 migrate check
step import 0 migrate import

# 3. dual-run
if [ "$DUAL_RUN_HOST" = "on" ]; then start_v10; fi
JAVA_OPTS="-Xmx2g" "$MIGRATE_HOME/bin/migrate-data" tail --config "$WORK/migrate-data.json" >"$WORK/tail.log" 2>&1 &
echo $! >"$WORK/tail.pid"
step dual-run-traffic 0 driver traffic --version=9x --base="$NINEX_URL" --book="$WORK/book.tsv" --seconds="$DUAL_RUN_SECONDS" --touched="$WORK/dual-run-touched.tsv"
writes_stopped="$(now_ms)"
if [ "$DUAL_RUN_HOST" = "on" ]; then
  step dual-run-lag 0 driver await --base="$V10_URL" --touched="$WORK/dual-run-touched.tsv"
  stop_pid tail
else
  # Without a V10 node the lag is how long the tail takes to drain what 9.x wrote: one pass after the writes stop.
  stop_pid tail
  step dual-run-lag 0 migrate tail --once
fi

# 4. cut-over: the write stop runs from 9.x stopping to V10 serving
cutover_started="$(now_ms)"
stop_pid ninex
step final-import 0 migrate import --restart
compared compare migrate compare
step snapshot 0 migrate snapshot
step backfill-tags 0 migrate backfill-tags
if [ "$DUAL_RUN_HOST" != "on" ]; then start_v10; fi
record write-stop "$cutover_started" 0 "{\"writesStoppedMs\":$writes_stopped}"
step v10-states 0 driver states --version=v10 --base="$V10_URL" --book="$WORK/book.tsv" --sample="$SAMPLE" --out="$WORK/v10-cutover.json"

# 5. rollback before V10 writes
stop_pid v10
step rollback-before-dry-run 0 migrate export --dry-run
start_ninex
step rollback-before-states 0 driver states --version=9x --base="$NINEX_URL" --book="$WORK/book.tsv" --sample="$SAMPLE" --out="$WORK/9x-rollback-before.json"
step rollback-before-compare 0 driver compare "$WORK/v10-cutover.json" "$WORK/9x-rollback-before.json"
stop_pid ninex
start_v10

# 6. V10 traffic
step v10-traffic 0 driver traffic --version=v10 --base="$V10_URL" --book="$WORK/book.tsv" --seconds="$V10_SECONDS" --touched="$WORK/v10-touched.tsv"
step v10-states-after 0 driver states --version=v10 --base="$V10_URL" --book="$WORK/book.tsv" --sample="$SAMPLE" --out="$WORK/v10-after.json"

# 7. rollback after V10 writes
rollback_started="$(now_ms)"
stop_pid v10
step export-dry-run 0 migrate export --dry-run
step export 0 migrate export
start_ninex
record rollback-after "$rollback_started" 0
step rollback-after-states 0 driver states --version=9x --base="$NINEX_URL" --book="$WORK/book.tsv" --sample="$SAMPLE" --out="$WORK/9x-rollback-after.json"
step rollback-after-compare 0 driver compare "$WORK/v10-after.json" "$WORK/9x-rollback-after.json"
step ninex-after-rollback-traffic 0 driver traffic --version=9x --base="$NINEX_URL" --book="$WORK/book.tsv" --seconds=5
step ninex-after-rollback 0 ninex_streams

# 8. refusal: V10 writes a type 9.x cannot read
stop_pid ninex
start_v10
step v10-new-orders 0 driver traffic --version=v10 --base="$V10_URL" --book="$WORK/book.tsv" --seconds=3 --create=5
stop_pid v10
step export-refused 1 migrate export --dry-run

log "done: $STEPS"
python3 "$REHEARSAL/report.py" "$WORK" >"$WORK/report.md"
cat "$WORK/report.md"
