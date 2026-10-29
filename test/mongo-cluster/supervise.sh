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
# Runs inside the mongo image (cluster.sh mounts it): every member of one cluster as a process of one container, each
# on its own port and named localhost:<port>, so the members reach each other and a client on the host reaches them
# under the same names through the published ports, on Docker Desktop and on Linux alike. A member that stops, as a
# test's shutdown command stops it, is started again on the same data after RESTART_DELAY seconds: a crash and a
# restart. Roadmap §6 item 18.
#
#   supervise.sh rs        three members of the replica set rs0 on 27101-27103
#   supervise.sh sharded   mongos routers on 27200 and 27202; the config server cfg on 27201; shards sh1 (27211-27213),
#                          sh2 (27221-27223)
set -euo pipefail

MODE=${1:?rs or sharded}
CACHE_GB=${CACHE_GB:-0.25}
RESTART_DELAY=${RESTART_DELAY:-2}
ROOT=/data/cluster
MEMBERS=()

# A step that fails ends the supervisor, and so the container: say which, with the members' last log lines, so that
# cluster.sh's "did not start" shows why (docker logs).
trap 'echo "supervise.sh: line $LINENO failed" >&2; tail -n 20 "$ROOT"/*/log >&2 2>/dev/null || true' ERR

# member <name> <port> <mongod or mongos arguments...>: records how to start the member.
member() {
  local name=$1 port=$2
  shift 2
  mkdir -p "$ROOT/$name"
  MEMBERS+=("$name|$port|$*")
}

start() {
  local name port args
  IFS='|' read -r name port args <<<"$1"
  if [[ $args == mongos* ]]; then
    # shellcheck disable=SC2086 # the recorded arguments split on purpose
    ${args} --port "$port" --bind_ip_all --logpath "$ROOT/$name/log" --logappend \
      --pidfilepath "$ROOT/$name.pid" --fork >/dev/null
  else
    # shellcheck disable=SC2086
    mongod ${args} --port "$port" --bind_ip_all --dbpath "$ROOT/$name" --wiredTigerCacheSizeGB "$CACHE_GB" \
      --logpath "$ROOT/$name/log" --logappend --pidfilepath "$ROOT/$name.pid" --fork >/dev/null
  fi
}

running() {
  local name port args
  IFS='|' read -r name port args <<<"$1"
  [[ -f $ROOT/$name.pid ]] && kill -0 "$(cat "$ROOT/$name.pid")" 2>/dev/null
}

# initiate <set> <config server: true|false> <ports...>
initiate() {
  local set=$1 configsvr=$2 members="" i=0
  shift 2
  for port in "$@"; do
    members+="{_id: $i, host: 'localhost:$port'},"
    i=$((i + 1))
  done
  echo "initiating $set"
  mongosh --quiet --port "$1" --eval "rs.initiate({_id: '$set', configsvr: $configsvr, members: [${members%,}]})" >/dev/null
  until mongosh --quiet --port "$1" --eval 'db.hello().isWritablePrimary' 2>/dev/null | grep -q true; do sleep 1; done
  echo "$set has a primary"
}

case $MODE in
  rs)
    for port in 27101 27102 27103; do member "rs0-$port" "$port" --replSet rs0; done
    ;;
  sharded)
    member cfg 27201 --configsvr --replSet cfg
    for port in 27211 27212 27213; do member "sh1-$port" "$port" --shardsvr --replSet sh1; done
    for port in 27221 27222 27223; do member "sh2-$port" "$port" --shardsvr --replSet sh2; done
    ;;
  *)
    echo "unknown mode $MODE" >&2
    exit 2
    ;;
esac

for m in "${MEMBERS[@]}"; do start "$m"; done
echo "members started"
case $MODE in
  rs)
    initiate rs0 false 27101 27102 27103
    ;;
  sharded)
    initiate cfg true 27201
    initiate sh1 false 27211 27212 27213
    initiate sh2 false 27221 27222 27223
    member mongos 27200 mongos --configdb cfg/localhost:27201
    start "${MEMBERS[-1]}"
    # A second router: the tests reserve the same keys through both at once (decision D307).
    member mongos2 27202 mongos --configdb cfg/localhost:27201
    start "${MEMBERS[-1]}"
    # A router just started may not reach the config server yet: retried for a minute, then the start fails.
    echo "adding the shards"
    tries=0
    until mongosh --quiet --port 27200 --eval "
      sh.addShard('sh1/localhost:27211,localhost:27212,localhost:27213');
      sh.addShard('sh2/localhost:27221,localhost:27222,localhost:27223');" >/dev/null; do
      tries=$((tries + 1))
      if [ $tries -ge 60 ]; then
        echo "the shards could not be added" >&2
        exit 1
      fi
      sleep 1
    done
    ;;
esac
touch "$ROOT/ready"
echo "cluster $MODE ready"

while true; do
  sleep "$RESTART_DELAY"
  for m in "${MEMBERS[@]}"; do
    running "$m" || { echo "restarting ${m%%|*}"; start "$m" || true; }
  done
done
