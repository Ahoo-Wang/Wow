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

# The raw-driver ceiling's program.
# shellcheck source=raw.sh
. "$HARNESS/raw.sh"

# The measure programs' Elasticsearch: the version CI runs (WowContainers.ELASTICSEARCH_IMAGE), one node, on its own
# port; bench.sh's down removes it with ClickHouse.
ELASTICSEARCH=wow-e2e-bench-elasticsearch
ELASTICSEARCH_IMAGE=docker.elastic.co/elasticsearch/elasticsearch:9.2.6
ELASTICSEARCH_URL=http://127.0.0.1:19200

# One statement $1 on the scale check's ClickHouse, its answer on stdout.
clickhouse() { curl -sS --fail-with-body -u bench:bench "$CLICKHOUSE_URL/" --data-binary "$1"; }

# Starts the measure programs' ClickHouse ($CLICKHOUSE_IMAGE, the version CI runs) on the store's CPUs, and waits until
# it answers.
clickhouse_up() {
  docker run -d --name "$CLICKHOUSE" -p 18123:8123 --ulimit nofile=262144:262144 \
    -e CLICKHOUSE_USER=bench -e CLICKHOUSE_PASSWORD=bench "$CLICKHOUSE_IMAGE" >/dev/null
  local store; store=$(opt store-cpus "$(opt server-cpus "")")
  if [ -n "$store" ]; then docker update --cpuset-cpus "$store" "$CLICKHOUSE" >/dev/null; fi
  local attempt
  for attempt in $(seq 1 150); do
    if clickhouse "SELECT 1" >/dev/null 2>&1; then break; fi
    sleep 0.2
  done
}

# Starts the measure programs' Elasticsearch ($ELASTICSEARCH_IMAGE), one node with security off and a 1 GB heap, on
# the store's CPUs, and waits until its cluster is yellow, at most 120 s; ELASTICSEARCH_URL is its address.
elasticsearch_up() {
  docker run -d --name "$ELASTICSEARCH" -p 19200:9200 -e discovery.type=single-node -e xpack.security.enabled=false \
    -e ES_JAVA_OPTS="-Xms1g -Xmx1g" "$ELASTICSEARCH_IMAGE" >/dev/null
  local store; store=$(opt store-cpus "$(opt server-cpus "")")
  if [ -n "$store" ]; then docker update --cpuset-cpus "$store" "$ELASTICSEARCH" >/dev/null; fi
  local attempt
  for attempt in $(seq 1 120); do
    if curl -sf "$ELASTICSEARCH_URL/_cluster/health?wait_for_status=yellow&timeout=1s" >/dev/null 2>&1; then
      export ELASTICSEARCH_URL
      return 0
    fi
    sleep 1
  done
  log "Elasticsearch did not start in 120s"; docker logs --tail 40 "$ELASTICSEARCH" >&2; return 1
}

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
  clickhouse_up
  log "seeding ClickHouse"
  # --codecs=lz4 creates the tables without the Sink's codecs, ClickHouse's LZ4 (CH-6's reads); the default keeps them.
  local statement codecs
  codecs=$(opt codecs sink)
  while IFS= read -r -d ';' statement; do
    statement=$(printf '%s\n' "$statement" | grep -v '^--' | sed '/^[[:space:]]*$/d')
    if [ "$codecs" = lz4 ]; then
      statement=$(printf '%s' "$statement" | perl -pe 's/ CODEC\((?:[^()]|\([^()]*\))*\)//g')
    fi
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

# The type of computed column $1 (0-based) of the insert experiment: a string, an integer, a float and a boolean in
# turn, as a state's top-level scalars are.
insert_type() {
  case $(( $1 % 4 )) in
    0) echo String ;;
    1) echo Int64 ;;
    2) echo Float64 ;;
    *) echo Bool ;;
  esac
}

# The computed columns of the insert experiment's table: $2 of them, each read from the JSON text `state` by its own
# JSONExtract ($1 per-column), or each an element of one JSONExtract into a tuple of all of them ($1 tuple), the two
# forms D412 compares; none for $1 none.
insert_columns() {
  local mode=$1 n=$2 i tuple=""
  [ "$mode" = none ] && return 0
  for i in $(seq 0 $(( n - 1 ))); do tuple="${tuple:+$tuple, }c$i $(insert_type "$i")"; done
  for i in $(seq 0 $(( n - 1 ))); do
    if [ "$mode" = tuple ]; then
      printf ', c%s %s MATERIALIZED tupleElement(JSONExtract(state, %s), %s)' "$i" "$(insert_type "$i")" \
        "'Tuple($tuple)'" "'c$i'"
    elif [ "$(insert_type "$i")" = String ]; then
      printf ", c%s String MATERIALIZED JSONExtractString(state, 'c%s')" "$i" "$i"
    else
      printf ", c%s %s MATERIALIZED JSONExtract(state, 'c%s', '%s')" "$i" "$(insert_type "$i")" "$i" "$(insert_type "$i")"
    fi
  done
}

# The state text of row `number` with $1 properties c0.., of the types insert_type gives.
insert_state() {
  local n=$1 i parts=""
  for i in $(seq 0 $(( n - 1 ))); do
    case "$(insert_type "$i")" in
      String) parts="${parts:+$parts, }'\"c$i\":\"v', toString(number % 1000), '\"'" ;;
      Int64) parts="${parts:+$parts, }'\"c$i\":', toString(number)" ;;
      Float64) parts="${parts:+$parts, }'\"c$i\":', toString(number / 7)" ;;
      *) parts="${parts:+$parts, }'\"c$i\":', if(number % 2 = 0, 'true', 'false')" ;;
    esac
  done
  echo "concat('{', $parts, '}')"
}

# D412's insert experiment (d412-d413-design.md §2), on ClickHouse alone: what N computed columns cost an insert per
# row, each column's own JSONExtract against one extraction into a tuple, inserted into the table itself and through a
# materialized view into its TO table, as the Sink's _state is filled; and whether a skip index is used under FINAL, and
# reads the right rows. Each insert takes --rows (200,000) rows of a state of N scalars from a table that holds them, in
# blocks of --block (1,000, the Sink's batch), --repeats (5) times; insert.jsonl holds one line per insert with
# ClickHouse's query log of it, skip.json the EXPLAIN and the counts. measure.json names them.
measure_clickhouse_insert() {
  OUT=$(opt out "$REPO/test/benchmarks/results/e2e/$(date +%Y%m%d-%H%M%S)-measure-clickhouse-insert")
  mkdir -p "$OUT"
  local rows block repeats
  rows=$(opt rows 200000)
  block=$(opt block 1000)
  repeats=$(opt repeats 5)
  local -a widths=(5 20 50)
  trap down EXIT
  down
  log "starting ClickHouse"
  clickhouse_up
  local settings="max_block_size = $block, max_insert_block_size = $block, min_insert_block_size_rows = $block, min_insert_block_size_bytes = 0"
  local n mode target repeat table
  for n in "${widths[@]}"; do
    clickhouse "CREATE TABLE src_$n ENGINE = MergeTree ORDER BY id AS SELECT number AS id, $(insert_state "$n") AS state FROM numbers($rows)" >/dev/null
    for mode in none per-column tuple; do
      for target in table view; do
        table="t_${mode//-/_}_${target}_$n"
        clickhouse "CREATE TABLE $table (id UInt64, state String$(insert_columns "$mode" "$n")) ENGINE = MergeTree ORDER BY id" >/dev/null
        if [ "$target" = view ]; then
          clickhouse "CREATE TABLE ${table}_in (id UInt64, state String) ENGINE = Null" >/dev/null
          clickhouse "CREATE MATERIALIZED VIEW ${table}_mv TO $table AS SELECT id, state FROM ${table}_in" >/dev/null
        fi
        for repeat in $(seq 1 "$repeats"); do
          clickhouse "TRUNCATE TABLE $table" >/dev/null
          local into=$table
          if [ "$target" = view ]; then into="${table}_in"; fi
          local id="insert-$mode-$target-$n-$repeat"
          curl -sS --fail-with-body -u bench:bench "$CLICKHOUSE_URL/?query_id=$id" \
            --data-binary "INSERT INTO $into (id, state) SELECT id, state FROM src_$n SETTINGS $settings" >/dev/null
          clickhouse "SYSTEM FLUSH LOGS" >/dev/null
          clickhouse "SELECT '$mode' AS mode, $n AS columns, '$target' AS target, $repeat AS repeat,
              $rows AS rows, written_rows AS writtenRows, query_duration_ms AS durationMs,
              ProfileEvents['UserTimeMicroseconds'] AS userMicros,
              ProfileEvents['OSCPUVirtualTimeMicroseconds'] AS cpuMicros
            FROM system.query_log WHERE query_id = '$id' AND type = 'QueryFinish'
            FORMAT JSONEachRow" >>"$OUT/insert.jsonl"
        done
        log "inserted $mode, $n columns, into the $target: $repeats times"
      done
    done
  done
  # CH-6's codecs (D421): the Sink's `_state_events` as it creates it (measure/clickhouse-codecs.sql, held to the Sink's
  # DDL by ScaleCheckDdlTest), against the same table without its codecs (LZ4, ClickHouse's default). Each arm takes --rows commits of the Sink's shape (a state of about 1 KB, random ids,
  # headers, one event, 20 tenants, ten versions an aggregate over two months) in the Sink's blocks, --repeats times,
  # timed by the query log; then once more as parts merges stay off, merged by one OPTIMIZE FINAL, whose query log
  # gives the merge's CPU, and the parts' bytes after it. codec.jsonl holds the inserts, codec-merge.jsonl the merges.
  local wow
  wow=$(grep -v '^--' "$HARNESS/measure/clickhouse-codecs.sql" | tr -d ';')
  local -a codec_arms=(lz4 wow)
  local arm ddl
  for arm in "${codec_arms[@]}"; do
    case "$arm" in
      lz4) ddl=$(printf '%s' "$wow" | perl -pe 's/ CODEC\((?:[^()]|\([^()]*\))*\)//g') ;;
      *) ddl=$wow ;;
    esac
    clickhouse "$(printf '%s' "$ddl" | sed "s/\`transfer_account_state_events\`/\`codec_$arm\`/")" >/dev/null
  done
  clickhouse "CREATE TABLE codec_source AS codec_lz4 ENGINE = MergeTree ORDER BY tuple()" >/dev/null
  clickhouse "INSERT INTO codec_source
    SELECT concat(aid, '@', toString(v)), tenant, aid, v, generateUUIDv4()::String, generateUUIDv4()::String,
      ['transfer.account.Deposit', 'transfer.account.Withdraw', 'transfer.account.Prepare', 'transfer.account.Entry',
        'transfer.account.Confirm'][1 + v % 5],
      toDateTime64('2026-09-01 00:00:00', 3, 'UTC') + toIntervalMillisecond(n * 25000 + rand() % 1000),
      concat('user-', toString(a % 2000)), if(a % 3 = 0, concat('space-', toString(a % 40)), ''), false,
      if(a % 4 = 0, '', concat('{\"owner\":\"user-', toString(a % 2000), '\"}')),
      generateUUIDv4()::String, generateUUIDv4()::String, map('wow.client', 'web', 'trace_id', hex(rand64())),
      concat('user-', toString(a % 2000)), tenant, false,
      [tuple(['deposited', 'withdrawn', 'prepared', 'entered', 'confirmed'][1 + v % 5], 0,
        concat('{\"accountId\":\"', aid, '\",\"amount\":', toString(rand() % 100000 / 100), ',\"note\":\"',
          randomPrintableASCII(40), '\"}'))],
      concat('{\"id\":\"', aid, '\",\"owner\":{\"id\":\"user-', toString(a % 2000), '\",\"name\":\"Owner ',
        toString(a % 2000), '\",\"phone\":\"138', toString(10000000 + a), '\"},\"status\":\"',
        ['OPEN', 'FROZEN', 'ACTIVE', 'CLOSED'][1 + v % 4], '\",\"address\":{\"city\":\"',
        ['Shanghai', 'Beijing', 'Shenzhen', 'Hangzhou'][1 + a % 4], '\",\"street\":\"', randomPrintableASCII(30),
        '\"},\"entries\":[', arrayStringConcat(arrayMap(i -> concat('{\"sku\":\"sku-', toString((a + i) % 500),
          '\",\"qty\":', toString(1 + i % 5), ',\"price\":', toString(((a * 7 + i) % 9000) / 100), '}'),
          range(1 + a % 8)), ','), '],\"balance\":', toString((a % 100000) / 100), ',\"version\":', toString(v), '}'),
      'fp-account-1', map('balance', toString((a % 100000) / 100))
    FROM (SELECT number AS n, intDiv(number, 10) AS a, 1 + number % 10 AS v,
      concat('acc', leftPad(toString(intDiv(number, 10)), 6, '0')) AS aid,
      concat('tenant-', toString(intDiv(number, 10) % 20)) AS tenant FROM numbers($rows))" >/dev/null
  for arm in "${codec_arms[@]}"; do
    table="codec_$arm"
    for repeat in $(seq 1 "$repeats"); do
      clickhouse "TRUNCATE TABLE $table" >/dev/null
      local id="codec-$arm-$repeat"
      curl -sS --fail-with-body -u bench:bench "$CLICKHOUSE_URL/?query_id=$id" \
        --data-binary "INSERT INTO $table SELECT * FROM codec_source SETTINGS $settings" >/dev/null
      clickhouse "SYSTEM FLUSH LOGS" >/dev/null
      clickhouse "SELECT '$arm' AS arm, $repeat AS repeat, $rows AS rows, written_rows AS writtenRows,
          query_duration_ms AS durationMs, ProfileEvents['UserTimeMicroseconds'] AS userMicros,
          ProfileEvents['OSCPUVirtualTimeMicroseconds'] AS cpuMicros
        FROM system.query_log WHERE query_id = '$id' AND type = 'QueryFinish'
        FORMAT JSONEachRow" >>"$OUT/codec.jsonl"
    done
    # The merge: merges stay off the parts (no part is under one byte), and OPTIMIZE FINAL, which is not bound by
    # that, merges each month's parts in its own thread, so its query log holds the merge's CPU.
    clickhouse "TRUNCATE TABLE $table" >/dev/null
    clickhouse "ALTER TABLE $table MODIFY SETTING max_bytes_to_merge_at_max_space_in_pool = 1" >/dev/null
    clickhouse "INSERT INTO $table SELECT * FROM codec_source SETTINGS $settings" >/dev/null
    local before
    before=$(clickhouse "SELECT count() FROM system.parts WHERE database = currentDatabase() AND table = '$table' AND active")
    curl -sS --fail-with-body -u bench:bench "$CLICKHOUSE_URL/?query_id=codec-$arm-merge" \
      --data-binary "OPTIMIZE TABLE $table FINAL" >/dev/null
    clickhouse "SYSTEM FLUSH LOGS" >/dev/null
    clickhouse "SELECT '$arm' AS arm, $rows AS rows, $(echo "$before" | tr -d '\n') AS partsBefore,
        (SELECT count() FROM system.parts WHERE database = currentDatabase() AND table = '$table' AND active) AS partsAfter,
        q.query_duration_ms AS durationMs, q.ProfileEvents['UserTimeMicroseconds'] AS userMicros,
        q.ProfileEvents['OSCPUVirtualTimeMicroseconds'] AS cpuMicros, q.ProfileEvents['MergedRows'] AS mergedRows,
        (SELECT sum(data_compressed_bytes) FROM system.parts
          WHERE database = currentDatabase() AND table = '$table' AND active) AS compressedBytes,
        (SELECT sum(data_uncompressed_bytes) FROM system.parts
          WHERE database = currentDatabase() AND table = '$table' AND active) AS uncompressedBytes
      FROM system.query_log AS q WHERE q.query_id = 'codec-$arm-merge' AND q.type = 'QueryFinish'
      FORMAT JSONEachRow" >>"$OUT/codec-merge.jsonl"
    log "inserted and merged the codec arm $arm: $repeats times"
  done
  # A _state of 1,000,000 aggregates over 100 tenants, half of each tenant's with a newer row in a part merges stay off,
  # and a set index on status. Ids sort as they were inserted, a status holds runs of 2,000 of them and granules are 128
  # rows, so the index can pass over granules. A newer row moves its aggregate to the next status: the right count reads
  # it with every older one, which skipping the newer row's granule would not.
  clickhouse "CREATE TABLE skip_state (tenant_id String, aggregate_id String, version UInt32, status String,
      INDEX status_set status TYPE set(100) GRANULARITY 1)
    ENGINE = ReplacingMergeTree(version) ORDER BY (tenant_id, aggregate_id) SETTINGS index_granularity = 128" >/dev/null
  clickhouse "SYSTEM STOP MERGES skip_state" >/dev/null
  clickhouse "INSERT INTO skip_state SELECT concat('t', toString(number % 100)), concat('a', leftPad(toString(number), 7, '0')), 1,
      concat('s', toString(intDiv(number, 2000) % 50)) FROM numbers(1000000)" >/dev/null
  clickhouse "INSERT INTO skip_state SELECT concat('t', toString(number % 100)), concat('a', leftPad(toString(number), 7, '0')), 2,
      concat('s', toString((intDiv(number, 2000) + 1) % 50)) FROM numbers(1000000) WHERE intDiv(number, 100) % 2 = 0" >/dev/null
  local where="tenant_id = 't7' AND status = 's3'" value
  local correct
  correct=$(clickhouse "SELECT count() FROM (SELECT status FROM skip_state FINAL WHERE tenant_id = 't7') WHERE status = 's3'
    SETTINGS use_skip_indexes = 0")
  printf '{"correct":%s,"settings":[' "$correct" >"$OUT/skip.json"
  for value in 0 1; do
    local explain count read
    explain=$(clickhouse "EXPLAIN indexes = 1 SELECT count() FROM skip_state FINAL WHERE $where SETTINGS use_skip_indexes_if_final = $value FORMAT JSONEachRow")
    curl -sS --fail-with-body -u bench:bench "$CLICKHOUSE_URL/?query_id=skip-$value" \
      --data-binary "SELECT count() FROM skip_state FINAL WHERE $where SETTINGS use_skip_indexes_if_final = $value" >"$OUT/skip-$value.count"
    count=$(tr -d '\n' <"$OUT/skip-$value.count")
    clickhouse "SYSTEM FLUSH LOGS" >/dev/null
    read=$(clickhouse "SELECT read_rows FROM system.query_log WHERE query_id = 'skip-$value' AND type = 'QueryFinish'")
    printf '%s{"useSkipIndexesIfFinal":%s,"count":%s,"readRows":%s,"explain":%s}' "$([ "$value" = 1 ] && echo ,)" \
      "$value" "$count" "$(echo "$read" | tr -d '\n')" "$(printf '%s' "$explain" | jq -Rs .)" >>"$OUT/skip.json"
  done
  printf ']}\n' >>"$OUT/skip.json"
  printf '{"schema":"wow-e2e-bench/measure/1","measure":"clickhouse-insert","rows":%s,"block":%s,"repeats":%s,"columns":[5,20,50],"server":%s}\n' \
    "$rows" "$block" "$repeats" "$(clickhouse "SELECT version()" | jq -Rs 'rtrimstr("\n")')" >"$OUT/measure.json"
  log "results in $OUT"
}

# The tables the sink keeps of the benchmark's account (context transfer), as ClickHouse names them.
SINK_TABLES="'transfer_account_state_events', 'transfer_account_state', 'transfer_account_events'"

# The sink's counts (D413's "before" and "after"): one node on --backend (postgres) with --sink's sink (clickhouse, the
# default, switched on by wow.clickhouse.sink.enabled; elasticsearch, the search server's, on PostgreSQL only),
# --accounts accounts seeded (1,000 spread the commits over many aggregates, 10 make a few hot ones), then E1 deposits
# at --rate a second for --seconds, open loop, the load's --distribution (`hot`: a --hot-fraction of them to the first
# --hot-keys accounts). Once the sink has written every commit (its rows reach the deposits the load counted, or stop
# growing for 30 s), it reads what the sink did since the seeding: on ClickHouse, the part log and the query log give
# the parts each of the sink's tables got, their rows, and the inserts the sink sent; on Elasticsearch, the search
# server's `search-requests` counts give its `_bulk`, `_refresh` and `_update_by_query` requests, and the node's
# indexing, bulk and refresh totals cross-check them. sink.json, with the load's result.json under load/. measure.json
# names them.
#
# --backlog=true makes the sink drain a backlog instead (a rebuild of the warehouse, a catch-up after a pause): the
# seeding and the deposits go to a node without the sink (the benchmark server; ClickHouse's sink not switched on),
# which is then stopped, and a node with the sink starts on the same log. Its subscription starts at the log's
# beginning and writes every commit, the seeding's too; sink.json counts all of them, and drainMs is the time from that
# node's launch, its start-up included, until the sink holds them.
measure_sink() {
  check_java
  BACKEND=$(opt backend postgres)
  HEAP=$(opt heap 1g)
  local sink accounts rate seconds backlog
  sink=$(opt sink clickhouse)
  backlog=$(opt backlog false)
  accounts=$(opt accounts 1000)
  rate=$(opt rate 200)
  seconds=$(opt seconds 60)
  case "$sink:$BACKEND" in
    clickhouse:* | elasticsearch:postgres) ;;
    *) log "--sink=$sink on --backend=$BACKEND: clickhouse, or elasticsearch on postgres"; exit 2 ;;
  esac
  OUT=$(opt out "$REPO/test/benchmarks/results/e2e/$(date +%Y%m%d-%H%M%S)-measure-sink-$sink-$accounts")
  mkdir -p "$OUT"
  trap down EXIT
  down
  log "starting $BACKEND and $sink"
  OPTIONS="${OPTIONS}no-bus=true
"
  stores_up "$BACKEND"
  pin_containers "$BACKEND"
  local args extra install sinking
  args="$(opt server-args "") --wow.transport=none"
  if [ "$sink" = clickhouse ]; then
    clickhouse_up
    sinking="--wow.clickhouse.endpoint=$CLICKHOUSE_URL --wow.clickhouse.username=bench --wow.clickhouse.password=bench --wow.clickhouse.sink.enabled=true"
    install=$(opt install "")
  else
    elasticsearch_up
    sinking="--spring.elasticsearch.uris=$ELASTICSEARCH_URL"
    # The search server, installed beside the benchmark server, unless --install names another.
    install=$(opt install "$REPO/test/benchmarks/server/build/install/benchmark-search-server")
  fi
  # With a backlog, the node that takes the load has no sink: ClickHouse's not switched on, or the benchmark server
  # instead of the search server.
  local server="$REPO/test/benchmarks/server/build/install/benchmark-server" first=$install
  if [ "$backlog" = true ]; then
    extra=$args
    if [ "$sink" = elasticsearch ]; then first=$server; fi
  else
    extra="$args $sinking"
  fi
  OPTIONS="${OPTIONS}install=${first:-$server}
"
  OPTIONS="${OPTIONS}server-args=$extra
"
  start_node 1
  await_node 1
  TARGET_LIST=${TARGETS[0]}
  log "seeding $accounts accounts"
  load seed --targets="$TARGET_LIST" --accounts="$accounts" --concurrency=32 | tee "$OUT/seed.log" >&2
  # What is counted from here on is the deposits', not the seeding's; with a backlog, from the sink's node on.
  local since
  if [ "$backlog" = true ]; then
    :
  elif [ "$sink" = clickhouse ]; then
    clickhouse "SYSTEM FLUSH LOGS" >/dev/null
    since=$(clickhouse "SELECT toUnixTimestamp64Milli(now64(3))")
  else
    sink_await "$accounts"
    since=$(es_counts)
  fi
  log "E1 at $rate/s for ${seconds}s over $accounts accounts"
  load run --targets="$TARGET_LIST" --accounts="$accounts" --scenario=E1 --rate="$rate" --warm-up=0 \
    --measure="$seconds" --distribution="$(opt distribution uniform)" --hot-fraction="$(opt hot-fraction 0.5)" \
    --hot-keys="$(opt hot-keys 1)" --label=sink --out="$OUT/load" | tee "$OUT/load.log" >&2
  local expected drain=null
  expected=$(jq '.results.ok + ('"$accounts"')' "$OUT/load/result.json")
  if [ "$backlog" = true ]; then
    sink_stop
    OPTIONS="${OPTIONS}install=${install:-$server}
"
    OPTIONS="${OPTIONS}server-args=$args $sinking
"
    if [ "$sink" = clickhouse ]; then
      clickhouse "SYSTEM FLUSH LOGS" >/dev/null
      since=$(clickhouse "SELECT toUnixTimestamp64Milli(now64(3))")
    else
      # The node's totals before it; the search server's own counts start with it.
      since=$(es_counts | jq -c '.requests = {}')
    fi
    log "the sink drains a backlog of $expected commits"
    local started
    started=$(now_ms)
    start_node 1
    await_node 1
  fi
  log "waiting for the sink to write $expected commits"
  sink_await "$expected"
  if [ "$backlog" = true ]; then drain=$(( $(now_ms) - started )); fi
  if [ "$sink" = clickhouse ]; then
    clickhouse "SYSTEM FLUSH LOGS" >/dev/null
    local parts inserts
    parts=$(clickhouse "SELECT table, count() AS parts, sum(rows) AS rows FROM system.part_log
        WHERE event_type = 'NewPart' AND table IN ($SINK_TABLES)
          AND event_time_microseconds >= fromUnixTimestamp64Milli(toInt64($since))
        GROUP BY table ORDER BY table FORMAT JSONEachRow" | jq -s .)
    inserts=$(clickhouse "SELECT count() AS inserts, sum(written_rows) AS writtenRows,
        toUnixTimestamp64Milli(min(event_time_microseconds - toIntervalMillisecond(query_duration_ms))) AS firstMs,
        toUnixTimestamp64Milli(max(event_time_microseconds)) AS lastMs FROM system.query_log
        WHERE type = 'QueryFinish' AND query_kind = 'Insert' AND has(tables, 'default.transfer_account_state_events')
          AND event_time_microseconds >= fromUnixTimestamp64Milli(toInt64($since))
        FORMAT JSONEachRow" | jq -s '.[0]')
    printf '{"sink":"clickhouse","accounts":%s,"rate":%s,"seconds":%s,"expected":%s,"written":%s,"parts":%s,"inserts":%s,"backlog":%s,"drainMs":%s}\n' \
      "$accounts" "$rate" "$seconds" "$expected" "$(sink_written)" "$parts" "$inserts" "$backlog" "$drain" \
      >"$OUT/sink.json"
  else
    jq -n --argjson before "$since" --argjson after "$(es_counts)" \
      --argjson accounts "$accounts" --argjson rate "$rate" --argjson seconds "$seconds" \
      --argjson expected "$expected" --argjson written "$(sink_written)" \
      --argjson backlog "$backlog" --argjson drain "$drain" \
      '{sink: "elasticsearch", accounts: $accounts, rate: $rate, seconds: $seconds, expected: $expected,
        written: $written, backlog: $backlog, drainMs: $drain,
        requests: ($after.requests | with_entries(.value -= ($before.requests[.key] // 0))),
        node: ($after.node | with_entries(.value -= ($before.node[.key] // 0)))}' >"$OUT/sink.json"
  fi
  printf '{"schema":"wow-e2e-bench/measure/1","measure":"sink","sink":"%s","accounts":%s}\n' "$sink" "$accounts" \
    >"$OUT/measure.json"
  log "results in $OUT"
}

# Stops measure_sink's node and forgets it, so that the next start_node 1 is a node of its own.
sink_stop() {
  local pid=${SERVER_PIDS[0]}
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  SERVER_PIDS=()
  TARGETS=()
  STARTED_AT=()
  READY=()
}

# The commits measure_sink's sink holds: the ClickHouse sink's _state_events rows, or the Elasticsearch sink's event
# documents (the account's index, wow_events__transfer__account), 0 while there are none.
sink_written() {
  if [ "$(opt sink clickhouse)" = clickhouse ]; then
    clickhouse "SELECT count() FROM transfer_account_state_events" 2>/dev/null || echo 0
  else
    curl -sf "$ELASTICSEARCH_URL/wow_events__transfer__account/_count" 2>/dev/null | jq -r '.count // 0' || echo 0
  fi
}

# Waits until measure_sink's sink holds $1 commits, or its count stops growing for 30 s.
sink_await() {
  local rows last=-1 still=0
  while [ "$still" -lt 30 ]; do
    rows=$(sink_written)
    rows=${rows:-0}
    if [ "$rows" -ge "$1" ]; then break; fi
    if [ "$rows" = "$last" ]; then still=$((still + 1)); else still=0; last=$rows; fi
    sleep 1
  done
}

# The Elasticsearch sink's counts so far, as one JSON object: `requests`, the search server's last `search-requests`
# line (logged once a second; this waits for the next), and `node`, the node's indexing, bulk and refresh totals.
es_counts() {
  sleep 1.2
  local requests
  requests=$(grep -o 'search-requests {.*}' "$OUT/node-1.log" | tail -1 | sed 's/^search-requests //') || true
  if [ -z "$requests" ]; then requests='{}'; fi
  curl -sf "$ELASTICSEARCH_URL/_nodes/stats/indices/indexing,bulk,refresh" | jq -c --argjson requests "$requests" \
    '{requests: $requests, node: ([.nodes[].indices] | {indexTotal: (map(.indexing.index_total) | add),
      bulkOperations: (map(.bulk.total_operations) | add), refreshTotal: (map(.refresh.total) | add)})}'
}

# measure: the scale check --program names, dataset-access (the default), walk, clickhouse-insert, sink or raw (raw.sh).
measure() {
  parse "$@"
  case "$(opt program dataset-access)" in
    dataset-access) measure_dataset ;;
    walk) measure_walk ;;
    clickhouse-insert) measure_clickhouse_insert ;;
    sink) measure_sink ;;
    raw) measure_raw ;;
    *) log "unknown program $(opt program ""): dataset-access, walk, clickhouse-insert, sink or raw"; exit 2 ;;
  esac
}
