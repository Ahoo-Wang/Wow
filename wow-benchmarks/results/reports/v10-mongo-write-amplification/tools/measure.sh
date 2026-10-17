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
# B.D diagnostics (D224): counts the store operations of E1 `Deposit` at a low open-loop rate, so throttling stays out
# of the numbers. One server node on empty containers; after seeding, an idle window (the periodic work alone), then
# the load window; MongoDB's profiler (level 2) and `serverStatus` counters, or PostgreSQL's `pg_stat_statements` and
# `pg_stat_user_tables`, per window. `analyze.py <out>` prints the operations per command, the idle rate removed.
#
#   JAVA_HOME=<jdk 21> measure.sh <9x|v10> <mongo|postgres> <out dir> [rate=20] [seconds=30] [accounts=1000]
#
# Build first: wow-benchmarks/e2e/harness/bench.sh build.
set -euo pipefail
VERSION=$1; BACKEND=$2; OUT=$3; RATE=${4:-20}; SECONDS_=${5:-30}; ACCOUNTS=${6:-1000}
DIAG="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$DIAG/../../../../.." && pwd)"
HARNESS=$REPO/wow-benchmarks/e2e/harness
export JAVA_HOME="${JAVA_HOME:?Set JAVA_HOME to a JDK 21.}"
LOAD=$REPO/wow-benchmarks/e2e/load/build/install/e2e-bench-load/bin/e2e-bench-load
LABEL=$(basename "$OUT")
rm -rf "$OUT"; mkdir -p "$OUT"
compose() { docker compose -f "$HARNESS/compose.yml" -f "$DIAG/override.yml" "$@"; }
PID=""
cleanup() {
  if [ -n "$PID" ]; then kill "$PID" 2>/dev/null || true; wait "$PID" 2>/dev/null || true; fi
  compose down -v --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT
compose down -v --remove-orphans >/dev/null 2>&1 || true
STORE=$BACKEND
compose up -d --wait kafka "$STORE" >/dev/null
args=("--server.port=18080")
if [ "$VERSION" = v10 ]; then args+=("--spring.profiles.active=$BACKEND" "--wow.internal.port=19090" "--wow.node.id=node-1"); fi
if [ "$VERSION" = 9x ]; then args+=("--cosid.machine.distributor.manual.machine-id=1"); fi
JAVA_OPTS="-Xms1g -Xmx1g -XX:+UseG1GC ${EXTRA_JAVA_OPTS:-}" "$REPO/wow-benchmarks/e2e/bench-$VERSION/build/install/e2e-bench-$VERSION/bin/e2e-bench-$VERSION" \
  "${args[@]}" >"$OUT/server.log" 2>&1 &
PID=$!
probe=/wow/manifest; if [ "$VERSION" = 9x ]; then probe=/wow/id/global; fi
for i in $(seq 1 120); do
  kill -0 "$PID" 2>/dev/null || { tail -40 "$OUT/server.log"; exit 1; }
  [ "$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:18080$probe")" = 200 ] && break
  sleep 1
done
sleep 2
"$LOAD" seed --version="$VERSION" --targets=http://127.0.0.1:18080 --accounts="$ACCOUNTS" | tee "$OUT/seed.log"
sleep 5
mongo_eval() { docker exec wow-e2e-bench-mongo mongosh --quiet --eval "$1"; }
mongo_file() { docker exec -i wow-e2e-bench-mongo mongosh --quiet "$@"; }
psql_() { docker exec wow-e2e-bench-postgres psql -U postgres -d transfer_v10 -At -F $'\t' -c "$1"; }
pg_snapshot() {
  psql_ "select calls, rows, regexp_replace(query, '\s+', ' ', 'g') from pg_stat_statements where dbid = (select oid from pg_database where datname='transfer_v10') order by calls desc" >"$OUT/$1-statements.tsv"
  psql_ "select relname, seq_scan, idx_scan, n_tup_ins, n_tup_upd, n_tup_hot_upd, n_tup_del from pg_stat_user_tables order by relname" >"$OUT/$1-tables.tsv"
  psql_ "select xact_commit, xact_rollback, tup_returned, tup_fetched, tup_inserted, tup_updated, tup_deleted from pg_stat_database where datname='transfer_v10'" >"$OUT/$1-database.tsv"
}
pg_reset() { psql_ "create extension if not exists pg_stat_statements; select pg_stat_statements_reset(); select pg_stat_reset();" >/dev/null; }
if [ "$BACKEND" = mongo ]; then
  docker cp "$DIAG/profile_start.js" wow-e2e-bench-mongo:/tmp/profile_start.js
  docker cp "$DIAG/profile_dump.js" wow-e2e-bench-mongo:/tmp/profile_dump.js
  docker cp "$DIAG/counters.js" wow-e2e-bench-mongo:/tmp/counters.js
  mongo_file /tmp/profile_start.js
  mongo_file /tmp/counters.js >"$OUT/counters-0.json"
else
  pg_reset
fi
T0=$(($(date +%s) * 1000))
sleep "$SECONDS_"
T1=$(($(date +%s) * 1000))
if [ "$BACKEND" = mongo ]; then mongo_file /tmp/counters.js >"$OUT/counters-1.json"; else pg_snapshot idle; pg_reset; fi
"$LOAD" run --version="$VERSION" --targets=http://127.0.0.1:18080 --accounts="$ACCOUNTS" --scenario=E1 --rate="$RATE" \
  --warm-up=0 --measure="$SECONDS_" --out="$OUT/load" --label="$LABEL" >"$OUT/load.log" 2>&1
sleep 5
T2=$(($(date +%s) * 1000))
if [ "$BACKEND" = mongo ]; then
  mongo_file /tmp/counters.js >"$OUT/counters-2.json"
  mongo_eval "const since=$T0, until=$T1; load('/tmp/profile_dump.js')" >"$OUT/idle-profile.jsonl"
  mongo_eval "const since=$T1, until=$T2; load('/tmp/profile_dump.js')" >"$OUT/load-profile.jsonl"
  docker cp "$DIAG/profile_detail.js" wow-e2e-bench-mongo:/tmp/profile_detail.js
  mongo_eval "const since=$T1, until=$T2; load('/tmp/profile_detail.js')" >"$OUT/load-detail.txt"
  mongo_eval "const since=$T0, until=$T1; load('/tmp/profile_detail.js')" >"$OUT/idle-detail.txt"
  mongo_eval "db.getMongo().getDBNames().forEach(n => { if (!['admin','local','config'].includes(n)) { const d = db.getSiblingDB(n); d.getCollectionNames().forEach(c => print(n + '.' + c + ' ' + d.getCollection(c).countDocuments({}) + ' ' + JSON.stringify(d.getCollection(c).getIndexes().map(i => i.key)))); } })" >"$OUT/collections.txt"
else
  pg_snapshot load
  psql_ "select schemaname||'.'||relname, n_live_tup from pg_stat_user_tables order by 1" >"$OUT/collections.txt"
fi
echo "idle_ms=$((T1 - T0)) load_ms=$((T2 - T1))" >"$OUT/windows.txt"
grep -E "ok|error|count|\"operations\"" "$OUT/load/summary.md" | head -20 || true
echo "done: $OUT"
