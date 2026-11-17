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
# bench.sh's node processes, sourced by bench.sh: a session's nodes are stopped while its stores are still up, a node
# that does not stop is killed, and a session does not start while a node of an earlier one still listens. A node
# left running past `down` writes into the next session's new stores: a membership heartbeat's upsert created
# MongoDB's wow_lease_member with no format record, and the next node rightly refused it (L4 run 44055712763).
# Every helper answers rather than fails: bench.sh runs under `set -euo pipefail`.

# How long a node has to end after TERM before it is killed; WOW_BENCH_NODE_STOP_SECONDS replaces it (a test's own).
NODE_STOP_SECONDS="${WOW_BENCH_NODE_STOP_SECONDS:-30}"
# The ports a session refuses while anything listens on them: the first NODE_PORT_SPAN public and internal ports.
NODE_PORT_SPAN=10

# The pids a hand-over adopted, one a line in the file WOW_BENCH_ADOPTED_PIDS names: a node it restarted, which this
# shell did not start (the hand-over helper, bench-common.mjs's HANDOVER_SCRIPT). None without the file.
adopted_pids() {
  if [ -n "${WOW_BENCH_ADOPTED_PIDS:-}" ] && [ -f "$WOW_BENCH_ADOPTED_PIDS" ]; then
    grep -E '^[0-9]+$' "$WOW_BENCH_ADOPTED_PIDS" || true
  fi
}

# Whether pid $1 still runs: a zombie, ended and not yet reaped, does not.
node_alive() {
  kill -0 "$1" 2>/dev/null || return 1
  case "$(ps -o stat= -p "$1" 2>/dev/null || true)" in Z* | *Z*) return 1 ;; esac
}

# Stops the node pids $@: TERM, up to NODE_STOP_SECONDS for each to end, then KILL for any still alive and up to 10 s
# more. A pid this shell started is reaped; one it did not start is waited for until it is gone.
stop_nodes() {
  local pid _
  [ "$#" -gt 0 ] || return 0
  for pid in "$@"; do kill "$pid" 2>/dev/null || true; done
  for pid in "$@"; do
    for _ in $(seq 1 $((NODE_STOP_SECONDS * 5))); do node_alive "$pid" || break; sleep 0.2; done
    if node_alive "$pid"; then
      log "node pid $pid did not stop in ${NODE_STOP_SECONDS}s: killing it"
      kill -KILL "$pid" 2>/dev/null || true
      for _ in $(seq 1 50); do node_alive "$pid" || break; sleep 0.2; done
    fi
    wait "$pid" 2>/dev/null || true
  done
}

# The pids listening on TCP port $1, one a line: lsof's, else ss's; nothing when neither is there.
port_listeners() {
  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"$1" -sTCP:LISTEN -t 2>/dev/null || true
  elif command -v ss >/dev/null 2>&1; then
    ss -Hltnp "sport = :$1" 2>/dev/null | grep -oE 'pid=[0-9]+' | cut -d= -f2 | sort -u || true
  fi
}

# Fails, naming each pid and its command, while anything listens on a node's public or internal port (from
# PUBLIC_PORT and INTERNAL_PORT, NODE_PORT_SPAN of each): a node of an earlier session still runs.
refuse_busy_ports() {
  local busy="" port pid offset
  for offset in $(seq 0 $((NODE_PORT_SPAN - 1))); do
    for port in $((PUBLIC_PORT + offset)) $((INTERNAL_PORT + offset)); do
      for pid in $(port_listeners "$port"); do
        busy="$busy
  port $port: pid $pid $(ps -o command= -p "$pid" 2>/dev/null | cut -c1-160 || true)"
      done
    done
  done
  if [ -n "$busy" ]; then
    log "a node of an earlier session still listens; stop it before a new session starts:$busy"
    return 1
  fi
}
