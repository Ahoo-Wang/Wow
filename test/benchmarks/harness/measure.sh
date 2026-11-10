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
# bench.sh's measure command and its programs, sourced by bench.sh: its helpers and settings are in scope.

# One statement $1 on the scale check's ClickHouse, its answer on stdout.
clickhouse() { curl -sS --fail-with-body -u bench:bench "$CLICKHOUSE_URL/" --data-binary "$1"; }

# The access scale check (D390 C4's, test/benchmarks/README.md): on fresh containers, ClickHouse seeded with 100,000
# accounts' access records in each of two tenants and a dataset of 1,000,000 rows over them in each
# (measure/dataset-access.sql), and one node of the server, whose access mode the arm gives (SPRING_APPLICATION_JSON:
# benchmark.access `claims`, no policy of the accounts, or `dataset`, one an analyst's claims pass for a tenth of them).
# Each query of the dataset (a list page, a count, an aggregation) is sent --warm-up-runs times, then --runs times
# timed by curl; the times, one answer of each and ClickHouse's query log of the timed ones go to --out, with
# measure.json naming them.
measure_dataset() {
  check_java
  BACKEND=$(opt backend postgres)
  HEAP=$(opt heap 1g)
  OUT=$(opt out "$REPO/test/benchmarks/results/e2e/$(date +%Y%m%d-%H%M%S)-measure")
  mkdir -p "$OUT/config/wow/dataset"
  local runs warm
  runs=$(opt runs 200)
  warm=$(opt warm-up-runs 20)
  trap down EXIT
  down
  log "starting $BACKEND and ClickHouse"
  OPTIONS="${OPTIONS}no-bus=true
"
  stores_up "$BACKEND"
  pin_containers "$BACKEND"
  docker run -d --name "$CLICKHOUSE" -p 18123:8123 --ulimit nofile=262144:262144 \
    -e CLICKHOUSE_USER=bench -e CLICKHOUSE_PASSWORD=bench "$CLICKHOUSE_IMAGE" >/dev/null
  local store; store=$(opt store-cpus "$(opt server-cpus "")")
  if [ -n "$store" ]; then docker update --cpuset-cpus "$store" "$CLICKHOUSE" >/dev/null; fi
  local attempt
  for attempt in $(seq 1 150); do
    if clickhouse "SELECT 1" >/dev/null 2>&1; then break; fi
    sleep 0.2
  done
  log "seeding ClickHouse"
  local statement
  while IFS= read -r -d ';' statement; do
    statement=$(printf '%s\n' "$statement" | grep -v '^--' | sed '/^[[:space:]]*$/d')
    if [ -n "$statement" ]; then clickhouse "$statement" >/dev/null; fi
  done <"$HARNESS/measure/dataset-access.sql"
  cp "$HARNESS/measure/transfer.account_rows.json" "$OUT/config/wow/dataset/"
  OPTIONS="${OPTIONS}server-args=$(opt server-args "") --wow.transport=none --wow.clickhouse.endpoint=$CLICKHOUSE_URL --wow.clickhouse.username=bench --wow.clickhouse.password=bench --wow.query.dataset.config-directory=$OUT/config
"
  start_node 1
  await_node 1
  local url="${TARGETS[0]}/wow/dataset/transfer/account_rows/query"
  local -a shapes=(list count aggregate)
  local -a bodies=(
    '{"kind":"list","filter":{"op":"match-all"},"sort":[{"field":"created_at","direction":"desc"}],"limit":50}'
    '{"kind":"count","filter":{"op":"match-all"}}'
    '{"kind":"aggregate","filter":{"op":"match-all"},"groupBy":[{"type":"terms","field":"branch","alias":"branch"}],"metrics":[{"type":"count","alias":"rows"},{"type":"numeric","function":"sum","expression":{"type":"field","field":"amount"},"alias":"amount"}],"sort":[{"field":"rows","direction":"desc"}]}'
  )
  local index i since
  for index in "${!shapes[@]}"; do
    local shape=${shapes[$index]} body=${bodies[$index]}
    log "$shape: $warm untimed, then $runs timed"
    for i in $(seq 1 "$warm"); do
      curl -s -o /dev/null -H 'Content-Type: application/json' --data-binary "$body" "$url"
    done
    since=$(clickhouse "SELECT toUnixTimestamp64Milli(now64(3))")
    for i in $(seq 1 "$runs"); do
      curl -s -o /dev/null -w '%{http_code} %{time_total}\n' -H 'Content-Type: application/json' \
        --data-binary "$body" "$url" >>"$OUT/$shape.times"
    done
    curl -s -H 'Content-Type: application/json' --data-binary "$body" "$url" >"$OUT/$shape.json"
    clickhouse "SYSTEM FLUSH LOGS" >/dev/null
    clickhouse "SELECT query_duration_ms, read_rows, read_bytes, memory_usage FROM system.query_log
      WHERE type = 'QueryFinish' AND event_time_microseconds >= fromUnixTimestamp64Milli(toInt64($since))
        AND query LIKE '%account_rows%' AND query NOT LIKE '%system.query_log%'
      FORMAT JSONEachRow" >"$OUT/$shape.log.jsonl"
  done
  printf '{"schema":"wow-e2e-bench/measure/1","measure":"dataset-access","runs":%s,"warmUpRuns":%s,"shapes":["list","count","aggregate"]}\n' \
    "$runs" "$warm" >"$OUT/measure.json"
  log "results in $OUT"
}

# The access walk's scale check (X-77's, test/benchmarks/README.md): on fresh containers, AccessWalkBench on the
# install's class path, which runs its two nodes in its own JVM against the store and writes measure.json to --out.
# Exit 3 when the install has no AccessWalkBench, a baseline older than it.
measure_walk() {
  check_java
  BACKEND=$(opt backend mongo)
  local main=me.ahoo.wow.benchmark.transfer.AccessWalkBenchKt
  OUT=$(opt out "$REPO/test/benchmarks/results/e2e/$(date +%Y%m%d-%H%M%S)-measure-walk-$BACKEND")
  mkdir -p "$OUT"
  local install jar class
  install=$(opt install "$REPO/test/benchmarks/server/build/install/benchmark-server")
  class="$(echo "$main" | tr . /).class"
  for jar in "$install"/lib/benchmark-server*.jar; do
    if [ -f "$jar" ] && "$SERVER_JAVA/bin/jar" tf "$jar" | grep -q "^$class$"; then break; fi
    jar=""
  done
  if [ -z "$jar" ]; then log "$install has no ${main##*.}"; exit 3; fi
  trap down EXIT
  down
  log "starting kafka and $BACKEND"
  stores_up "$BACKEND"
  pin_containers "$BACKEND"
  pin server-cpus
  log "measure walk on $BACKEND"
  ${PREFIX[@]+"${PREFIX[@]}"} "$SERVER_JAVA/bin/java" -Xms"$(opt heap 2g)" -Xmx"$(opt heap 2g)" -XX:+UseG1GC \
    -cp "$install/lib/*" "$main" --backend="$BACKEND" ${PASS[@]+"${PASS[@]}"} --out="$OUT" 2>&1 \
    | tee "$OUT/measure.log" >&2
  log "results in $OUT"
}

# measure: the scale check --program names, dataset-access (the default) or walk.
measure() {
  parse "$@"
  case "$(opt program dataset-access)" in
    dataset-access) measure_dataset ;;
    walk) measure_walk ;;
    *) log "unknown program $(opt program ""): dataset-access or walk"; exit 2 ;;
  esac
}
