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
# bench.sh's diagnostics of a run, sourced by bench.sh: its helpers and settings are in scope. A reused run's flight
# recordings (dump_recordings, with --jfr on its session) and, with --dump-every, the periodic dumps of the servers'
# threads and PostgreSQL's sessions while the load runs (start_dumps, stop_dumps).

# A reused run's flight recordings: each server's, over the last $1 seconds (its warm-up and measured phase).
dump_recordings() {
  [ "$(opt reuse false)" = true ] && [ "${S_JFR:-false}" = true ] || return 0
  local n
  for n in $(seq 1 "$NODES"); do
    "$SERVER_JAVA/bin/jcmd" "${SERVER_PIDS[$((n - 1))]}" JFR.dump filename="$OUT/node-$n.jfr" begin="-${1}s" \
      >/dev/null 2>&1 || log "no recording of node $n"
  done
}

# With --dump-every=<s>, a diagnostic: every <s> seconds while the load runs, each server's platform threads with the
# locks they hold (jcmd Thread.print -l) and every thread, the virtual ones too (Thread.dump_to_file), and on PostgreSQL
# its sessions, what each waits on and which pids block it (pg_stat_activity, pg_blocking_pids), into $OUT/dumps.
# The dumper stops with the load, or when this script is gone.
DUMPER=""
POSTGRES_SESSIONS="select now()::time(0) as at, pid, state, wait_event_type, wait_event,
  round(extract(epoch from now() - xact_start)::numeric, 1) as xact_s,
  round(extract(epoch from now() - query_start)::numeric, 1) as query_s,
  pg_blocking_pids(pid) as blocked_by, left(regexp_replace(query, '\s+', ' ', 'g'), 100) as query
  from pg_stat_activity where datname = 'transfer' and pid <> pg_backend_pid() order by xact_start nulls last"
start_dumps() {
  local every; every=$(opt dump-every "")
  [ -n "$every" ] || return 0
  mkdir -p "$OUT/dumps"
  (
    t=0
    while kill -0 "$$" 2>/dev/null; do
      sleep "$every"
      t=$((t + every))
      for pid in ${SERVER_PIDS[@]+"${SERVER_PIDS[@]}"}; do
        "$SERVER_JAVA/bin/jcmd" "$pid" Thread.print -l >"$OUT/dumps/$t-locks-$pid.txt" 2>&1 || true
        "$SERVER_JAVA/bin/jcmd" "$pid" Thread.dump_to_file -format=text "$OUT/dumps/$t-threads-$pid.txt" >/dev/null 2>&1 || true
      done
      case "${CONTAINERS:-}" in
        *postgres*) postgres_sql "$POSTGRES_SESSIONS" >"$OUT/dumps/$t-postgres.txt" 2>&1 || true ;;
      esac
    done
  ) &
  DUMPER=$!
}
stop_dumps() {
  if [ -n "$DUMPER" ]; then
    kill "$DUMPER" 2>/dev/null || true
    wait "$DUMPER" 2>/dev/null || true
    DUMPER=""
  fi
}
