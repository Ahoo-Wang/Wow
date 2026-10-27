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
# example and of the view store it embeds, with both rollbacks, and before it an offline cut-over of the same data, the
# default way (decision D267 ⑳). Two V10 hosts take over: the rehearsal host (context example) and the V10 view store
# service (context view-store, platform/view-store), both on one PostgreSQL database. Every step is timed into
# $WORK/steps.jsonl; report.py renders $WORK/report.md.
#
#   1. seed      the 9.x example-server (v10-9x-baseline) with ORDERS orders and their carts, VIEWS views (saved,
#                renamed, shared and claimed, deleted), its users' preferences and its tenants' dataset wordings, and the
#                9.x compensation service's database with unresolved CartSaga executions
#   1b. offline  the offline cut-over, into its own database (offline): 9.x stops (the write stop starts), import,
#                compare, snapshot and backfill-tags, and the V10 hosts start and serve (the write stop ends); their
#                sampled states equal 9.x's before it stopped. They stop and 9.x starts again for the online cut-over
#   2. import    migrate-data check and import into the V10 log on PostgreSQL (database example)
#   3. dual-run  the V10 hosts start on standby (wow.node.standby): they serve reads and refuse commands while 9.x keeps
#                taking writes (orders, carts, view renames) for DUAL_RUN_SECONDS and migrate-data tail follows them;
#                the lag is how long V10 takes to read what 9.x last wrote once the writes stop
#   4. cut-over  9.x stops (the write stop starts); the final pass (import --final: the tail from its checkpoint,
#                or import --restart with FINAL_PASS=restart), compare, snapshot and backfill-tags; the operator
#                resumes the V10 hosts on their internal ports, and they serve writes (the write stop ends)
#   5. rollback before V10 writes: export --dry-run finds nothing to write back, 9.x starts again on its own store,
#                and its states (orders, views) equal V10's; then the cut-over again
#   6. V10 traffic for V10_SECONDS: orders moved on, carts filled, views renamed; every user's preferences and every
#                wording set again, on the version after the imported one
#   7. rollback after V10 writes: V10 stops, export writes its streams back, 9.x starts, its order states equal
#                V10's, it renames every view and sets every preferences and wording V10 wrote on the version V10
#                left, and it takes writes again
#   8. refusal   V10 creates orders and views (OrderCreated and ViewCreated at revision 2, which 9.x cannot read)
#                and export --dry-run refuses
#
# Environment: NINEX_HOME (the 9.x example-server's installDist), V10_HOME (:migration-rehearsal's installDist),
# VIEWS_HOME (:view-store-server's installDist), MIGRATE_HOME (:migrate-data's installDist), MANIFEST (the rehearsal
# host's Manifest), VIEWS_MANIFEST (the view store service's Manifest), WORK (an empty directory); ORDERS (200),
# VIEWS (ORDERS / 4), DUAL_RUN_SECONDS (20), V10_SECONDS (20), CONCURRENCY (32), EXECUTIONS (ORDERS / 20), SAMPLE
# (every n-th order and view compared, 1), FINAL_PASS (final: import --final; restart: import --restart).
set -euo pipefail

REHEARSAL="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
: "${NINEX_HOME:?the 9.x example-server installation}" "${V10_HOME:?the rehearsal host installation}"
: "${VIEWS_HOME:?the view store service installation}" "${VIEWS_MANIFEST:?the view store service Manifest}"
: "${MIGRATE_HOME:?the migrate-data installation}" "${MANIFEST:?the rehearsal host Manifest}" "${WORK:?a work directory}"
ORDERS="${ORDERS:-200}"
VIEWS="${VIEWS:-$((ORDERS / 4 > 0 ? ORDERS / 4 : 1))}"
DUAL_RUN_SECONDS="${DUAL_RUN_SECONDS:-20}"
V10_SECONDS="${V10_SECONDS:-20}"
CONCURRENCY="${CONCURRENCY:-32}"
EXECUTIONS="${EXECUTIONS:-$((ORDERS / 20 > 0 ? ORDERS / 20 : 1))}"
SAMPLE="${SAMPLE:-1}"
FINAL_PASS="${FINAL_PASS:-final}"
MONGO_PORT="${REHEARSAL_MONGO_PORT:-27017}"
POSTGRES_PORT="${REHEARSAL_POSTGRES_PORT:-5432}"
NINEX_PORT="${NINEX_PORT:-18080}"
V10_PORT="${V10_PORT:-18090}"
V10_INTERNAL_PORT="${V10_INTERNAL_PORT:-19090}"
VIEWS_PORT="${VIEWS_PORT:-18091}"
VIEWS_INTERNAL_PORT="${VIEWS_INTERNAL_PORT:-19091}"
# The database of the V10 log the hosts and migrate-data use: example, or offline through the offline cut-over.
TARGET_DB=example
NINEX_URL="http://localhost:$NINEX_PORT"
V10_URL="http://localhost:$V10_PORT"
V10_INTERNAL_URL="http://localhost:$V10_INTERNAL_PORT"
VIEWS_URL="http://localhost:$VIEWS_PORT"
VIEWS_INTERNAL_URL="http://localhost:$VIEWS_INTERNAL_PORT"
# The services of the V10 hosts: their internal ports, the resume among their routes, take a request that names the
# node's own service (Wow-Service, decision D300).
V10_SERVICE=example-service
VIEWS_SERVICE=view-store-service
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

# The kinds of difference compare may report, by aggregate, each explained and signed off (design 07 §3 step 4):
# - example-service.order:$tags: 9.x merged the tags its extractor derived into the stored ones, so a changed address
#   kept the old province; V10's @Tags replaces each key it derives (decision D220). An intended change.
# - view-store.dataset_wording:@deleted, every difference of a wording 9.x deleted: 9.x's ClearDatasetWording was a
#   DeleteAggregate, which kept the wording's last values and let it never be set again; V10 clears it
#   (DatasetWordingCleared, mapped from the 9.x deletion), keeps it, and sets it again. An intended change.
EXPLAINED_KINDS="${EXPLAINED_KINDS:-example-service.order:\$tags view-store.dataset_wording:@deleted}"

# compared NAME CMD…: a comparison that passes, or whose every DIFFERS line differs only in what EXPLAINED_KINDS
# explains for its aggregate: a field (aggregate:field), or anything of an instance 9.x deleted (aggregate:@deleted).
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
  if ! python3 - "$WORK/$name.log" "$EXPLAINED_KINDS" <<'PY'
import re, sys
explained = set(sys.argv[2].split())
unexplained = set()
for line in open(sys.argv[1]):
    match = re.match(r"^DIFFERS (\S+) \S+ v\d+: (.*)$", line.rstrip("\n"))
    if not match:
        continue
    aggregate, rendered = match.groups()
    if f"{aggregate}:@deleted" in explained and "$deleted: 9.x true" in rendered:
        continue
    for field in re.findall(r"(?:^|; )([^;]+?): 9\.x ", rendered):
        if f"{aggregate}:{field}" not in explained:
            unexplained.add(f"{aggregate}:{field}")
if unexplained:
    print(" ".join(sorted(unexplained)))
    sys.exit(1)
PY
  then
    log "$name found unexplained differences (above)"
    exit 1
  fi
  log "$name: every difference is an explained kind ($EXPLAINED_KINDS)"
}

java_home() { echo "${JAVA_HOME:?JAVA_HOME (JDK 21)}"; }
driver() { "$(java_home)/bin/java" -cp "$V10_HOME/lib/*" me.ahoo.wow.rehearsal.driver.RehearsalDriverKt "$@" --concurrency="$CONCURRENCY"; }
migrate() { JAVA_OPTS="-Xmx2g" "$MIGRATE_HOME/bin/migrate-data" "$1" --config "$WORK/migrate-data-$TARGET_DB.json" "${@:2}"; }

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

# launch_v10 [standby]: the V10 host on TARGET_DB; on standby (wow.node.standby) when asked, until resume_hosts.
launch_v10() {
  local standby="${1:-}"
  (
    cd "$V10_HOME"
    env JAVA_OPTS="-Xmx1g" ./bin/migration-rehearsal \
      --server.port="$V10_PORT" --wow.internal.port="$V10_INTERNAL_PORT" \
      --spring.datasource.url="jdbc:postgresql://localhost:$POSTGRES_PORT/$TARGET_DB" \
      --wow.node.standby="$([ "$standby" = standby ] && echo true || echo false)" \
      >>"$WORK/v10.out" 2>&1 &
    echo $! >"$WORK/v10.pid"
  )
}

# launch_views [standby]: the V10 view store service, on the rehearsal host's database; on standby when asked. It takes
# its callers from the headers (Wow-User, Wow-Owner, Wow-Tenant, CoSec-App-Id), as a development run of it does.
launch_views() {
  local standby="${1:-}"
  (
    cd "$VIEWS_HOME"
    env JAVA_OPTS="-Xmx1g" ./bin/view-store-server \
      --spring.profiles.active=postgres,dev --server.port="$VIEWS_PORT" \
      --wow.internal.port="$VIEWS_INTERNAL_PORT" --wow.internal.host=127.0.0.1 \
      --spring.datasource.url="jdbc:postgresql://localhost:$POSTGRES_PORT/$TARGET_DB" \
      --wow.node.standby="$([ "$standby" = standby ] && echo true || echo false)" \
      --logging.level.root=warn \
      >>"$WORK/views.out" 2>&1 &
    echo $! >"$WORK/views.pid"
  )
}

# start_hosts [standby]: both V10 hosts, started together; returns once both are healthy.
start_hosts() {
  launch_v10 "${1:-}"
  launch_views "${1:-}"
  await_http "$V10_URL" v10
  await_http "$VIEWS_URL" views
}
stop_hosts() { stop_pid v10; stop_pid views; }

# internal URL SERVICE METHOD PATH [BODY]: a call to a V10 host's internal port as a node of SERVICE; prints the answer.
internal() {
  curl -sS -f -X "$3" "$1$4" -H "Wow-Service: $2" -H "Content-Type: application/json" ${5:+-d "$5"}
}

# resume_hosts: the operator's resume of both V10 hosts at the cut-over (deployment run book), at once, as an operator
# resumes every node of every service; prints their status.
resume_hosts() {
  local body='{"by":"rehearsal","reason":"8c.1 cut-over"}' v10 views
  internal "$V10_INTERNAL_URL" "$V10_SERVICE" POST /wow/node/resume "$body" >"$WORK/resume-v10.json" &
  v10=$!
  internal "$VIEWS_INTERNAL_URL" "$VIEWS_SERVICE" POST /wow/node/resume "$body" >"$WORK/resume-views.json" &
  views=$!
  wait "$v10" && wait "$views"
  cat "$WORK/resume-v10.json" "$WORK/resume-views.json"
  echo
  echo '{"resumed":2}'
}

# standby_refuses: a command sent to the V10 host on standby is refused with wow.node.standby (503); prints the status.
standby_refuses() {
  internal "$V10_INTERNAL_URL" "$V10_SERVICE" GET /wow/node/standby
  echo
  internal "$VIEWS_INTERNAL_URL" "$VIEWS_SERVICE" GET /wow/node/standby
  echo
  local answer code
  answer="$(curl -sS -o - -w '\n%{http_code}' -X POST "$V10_URL/example/cart/standby-probe/add-cart-item" \
    -H "Content-Type: application/json" -H "X-Rehearsal-Owner: standby-probe" \
    -d '{"productId":"probe","quantity":1}')"
  code="$(tail -1 <<<"$answer")"
  echo "$answer"
  [ "$code" = 503 ] && grep -q 'wow.node.standby' <<<"$answer"
  printf '{"refused":%s}\n' "$code"
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
  mongosh_run wow_example_db --eval 'print(JSON.stringify({orders: db.order_event_stream.countDocuments({}), carts: db.cart_event_stream.countDocuments({}), views: db.view_event_stream.countDocuments({}), preferences: db.view_preferences_event_stream.countDocuments({}), wordings: db.dataset_wording_event_stream.countDocuments({})}))'
}

# The view store's traffic runs beside the orders' through a window: views_traffic_start VERSION BASE SECONDS NAME,
# then views_traffic_join NAME, which records it as a step.
views_traffic_start() {
  views_traffic_started="$(now_ms)"
  driver views traffic --version="$1" --base="$2" --book="$WORK/views.tsv" --seconds="$3" --touched="$WORK/$4-touched.tsv" \
    >"$WORK/$4.log" 2>&1 &
  echo $! >"$WORK/views-traffic.pid"
}
views_traffic_join() {
  local code=0
  wait "$(cat "$WORK/views-traffic.pid")" || code=$?
  rm -f "$WORK/views-traffic.pid"
  record "$1" "$views_traffic_started" "$code" "$(grep -E '^\{.*\}$' "$WORK/$1.log" | tail -1 || true)"
  [ "$code" = 0 ] || { tail -40 "$WORK/$1.log" >&2; exit 1; }
}

cleanup() {
  stop_pid tail || true
  stop_pid views-traffic || true
  stop_pid v10 || true
  stop_pid views || true
  stop_pid ninex || true
  if [ "${KEEP_STORES:-false}" != "true" ]; then compose down -v >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT

# write_config DB: migrate-data's configuration for the V10 log in database DB, with its own checkpoint. The tail's
# look-back follows the final pass's sizing rule (twice the larger of the clock skew and the slowest 9.x commit
# latency): at 5 s, a commit a loaded runner made visible later than that was never read by the tail (run 39577349352).
write_config() {
  local db="$1"
  cp "$REHEARSAL/migration-map.json" "$WORK/migration-map.json"
  cp "$MANIFEST" "$WORK/manifest.json"
  cp "$VIEWS_MANIFEST" "$WORK/views-manifest.json"
  cat >"$WORK/migrate-data-$db.json" <<EOF
{
  "map": "migration-map.json",
  "manifests": ["manifest.json", "views-manifest.json"],
  "checkpoint": "migrate-data-$db.checkpoint.json",
  "source": { "uri": "mongodb://localhost:$MONGO_PORT", "database": "wow_example_db", "createCommitTimeIndex": true },
  "target": {
    "type": "postgres",
    "jdbcUrl": "jdbc:postgresql://localhost:$POSTGRES_PORT/$db",
    "username": "postgres",
    "password": "postgres"
  },
  "partitions": { "example": 64, "view-store": 64 },
  "classpath": ["$V10_HOME/lib", "$VIEWS_HOME/lib"],
  "compensation": { "database": "compensation_db", "service": "example-service" },
  "lookbackMillis": 20000,
  "pollIntervalMillis": 500,
  "final": { "lookbackMillis": 60000, "maxTailAgeMillis": 600000 }
}
EOF
}

log "stores up"
compose down -v >/dev/null 2>&1 || true
compose up -d --wait
docker exec wow-rehearsal-postgres createdb -U postgres offline
write_config example
write_config offline

# 1. seed
start_ninex
step seed 0 driver seed --version=9x --base="$NINEX_URL" --book="$WORK/book.tsv" --orders="$ORDERS"
step seed-views 0 driver views seed --base="$NINEX_URL" --book="$WORK/views.tsv" --settings="$WORK/settings.tsv" --views="$VIEWS"
step seed-compensation 0 mongosh_run --eval "var count = $EXECUTIONS, example = 'wow_example_db', compensation = 'compensation_db'" /rehearsal/seed-compensation.js
step ninex-seeded 0 ninex_streams
step check 0 migrate check

# 1b. offline: the write stop runs from 9.x stopping to V10 serving, with no tail and no standby
step offline-9x-states 0 driver states --version=9x --base="$NINEX_URL" --book="$WORK/book.tsv" --sample="$SAMPLE" --out="$WORK/9x-offline.json"
step offline-9x-views-states 0 driver views states --version=9x --base="$NINEX_URL" --book="$WORK/views.tsv" --sample="$SAMPLE" --out="$WORK/9x-views-offline.json"
TARGET_DB=offline
offline_started="$(now_ms)"
stop_pid ninex
step offline-import 0 migrate import
compared offline-compare migrate compare
step offline-snapshot 0 migrate snapshot
step offline-backfill-tags 0 migrate backfill-tags
step offline-start 0 start_hosts
record offline-write-stop "$offline_started" 0
step offline-v10-states 0 driver states --version=v10 --base="$V10_URL" --book="$WORK/book.tsv" --sample="$SAMPLE" --out="$WORK/v10-offline.json"
step offline-states-compare 0 driver compare "$WORK/v10-offline.json" "$WORK/9x-offline.json"
step offline-v10-views-states 0 driver views states --version=v10 --base="$VIEWS_URL" --book="$WORK/views.tsv" --sample="$SAMPLE" --out="$WORK/v10-views-offline.json"
step offline-views-compare 0 driver compare "$WORK/v10-views-offline.json" "$WORK/9x-views-offline.json"
stop_hosts
TARGET_DB=example
start_ninex

# 2. import
step import 0 migrate import

# 3. dual-run
start_hosts standby
step standby-refuses 0 standby_refuses
JAVA_OPTS="-Xmx2g" "$MIGRATE_HOME/bin/migrate-data" tail --config "$WORK/migrate-data-example.json" >"$WORK/tail.log" 2>&1 &
echo $! >"$WORK/tail.pid"
views_traffic_start 9x "$NINEX_URL" "$DUAL_RUN_SECONDS" dual-run-views
step dual-run-traffic 0 driver traffic --version=9x --base="$NINEX_URL" --book="$WORK/book.tsv" --seconds="$DUAL_RUN_SECONDS" --touched="$WORK/dual-run-touched.tsv"
views_traffic_join dual-run-views
writes_stopped="$(now_ms)"
step dual-run-lag 0 driver await --base="$V10_URL" --touched="$WORK/dual-run-touched.tsv"
step dual-run-views-lag 0 driver views await --base="$VIEWS_URL" --touched="$WORK/dual-run-views-touched.tsv"
stop_pid tail

# 4. cut-over: the write stop runs from 9.x stopping to V10 serving
cutover_started="$(now_ms)"
stop_pid ninex
step final-import 0 migrate import "--$FINAL_PASS"
compared compare migrate compare
step snapshot 0 migrate snapshot
step backfill-tags 0 migrate backfill-tags
step resume 0 resume_hosts
record write-stop "$cutover_started" 0 "{\"writesStoppedMs\":$writes_stopped}"
step v10-states 0 driver states --version=v10 --base="$V10_URL" --book="$WORK/book.tsv" --sample="$SAMPLE" --out="$WORK/v10-cutover.json"
step v10-views-states 0 driver views states --version=v10 --base="$VIEWS_URL" --book="$WORK/views.tsv" --sample="$SAMPLE" --out="$WORK/v10-views-cutover.json"

# 5. rollback before V10 writes
stop_hosts
step rollback-before-dry-run 0 migrate export --dry-run
start_ninex
step rollback-before-states 0 driver states --version=9x --base="$NINEX_URL" --book="$WORK/book.tsv" --sample="$SAMPLE" --out="$WORK/9x-rollback-before.json"
step rollback-before-compare 0 driver compare "$WORK/v10-cutover.json" "$WORK/9x-rollback-before.json"
step rollback-before-views-states 0 driver views states --version=9x --base="$NINEX_URL" --book="$WORK/views.tsv" --sample="$SAMPLE" --out="$WORK/9x-views-rollback-before.json"
step rollback-before-views-compare 0 driver compare "$WORK/v10-views-cutover.json" "$WORK/9x-views-rollback-before.json"
stop_pid ninex
start_hosts

# 6. V10 traffic
views_traffic_start v10 "$VIEWS_URL" "$V10_SECONDS" v10-views
step v10-traffic 0 driver traffic --version=v10 --base="$V10_URL" --book="$WORK/book.tsv" --seconds="$V10_SECONDS" --touched="$WORK/v10-touched.tsv"
views_traffic_join v10-views
# V10's derived ids find the imported preferences and wordings: each set lands on the version after 9.x's.
step v10-settings 0 driver views settings --version=v10 --base="$VIEWS_URL" --settings="$WORK/settings.tsv"
step v10-states-after 0 driver states --version=v10 --base="$V10_URL" --book="$WORK/book.tsv" --sample="$SAMPLE" --out="$WORK/v10-after.json"

# 7. rollback after V10 writes
rollback_started="$(now_ms)"
stop_hosts
step export-dry-run 0 migrate export --dry-run
step export 0 migrate export
start_ninex
record rollback-after "$rollback_started" 0
step rollback-after-states 0 driver states --version=9x --base="$NINEX_URL" --book="$WORK/book.tsv" --sample="$SAMPLE" --out="$WORK/9x-rollback-after.json"
step rollback-after-compare 0 driver compare "$WORK/v10-after.json" "$WORK/9x-rollback-after.json"
# 9.x reads the views' export: each view V10 renamed takes a 9.x rename on the version V10 left.
step rollback-after-views 0 driver views verify --base="$NINEX_URL" --touched="$WORK/v10-views-touched.tsv"
step rollback-after-settings 0 driver views settings --version=9x --base="$NINEX_URL" --settings="$WORK/settings.tsv"
step ninex-after-rollback-traffic 0 driver traffic --version=9x --base="$NINEX_URL" --book="$WORK/book.tsv" --seconds=5
step ninex-after-rollback 0 ninex_streams

# 8. refusal: V10 writes types 9.x cannot read
stop_pid ninex
start_hosts
step v10-new-orders 0 driver traffic --version=v10 --base="$V10_URL" --book="$WORK/book.tsv" --seconds=3 --create=5
step v10-new-views 0 driver views traffic --version=v10 --base="$VIEWS_URL" --book="$WORK/views.tsv" --seconds=3 --create=5
stop_hosts
step export-refused 1 migrate export --dry-run

log "done: $STEPS"
python3 "$REHEARSAL/report.py" "$WORK" >"$WORK/report.md"
cat "$WORK/report.md"
