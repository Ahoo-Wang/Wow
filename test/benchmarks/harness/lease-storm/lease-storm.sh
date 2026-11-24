#!/usr/bin/env bash
#
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
# lease-storm.sh: #6675's lease storm and takeover, on a MongoDB replica set with an arbiter and three eShop catalog
# nodes. See the "Lease storm and takeover" section of test/benchmarks/README.md.
#
#   lease-storm.sh run [--label=<name>] [--install=<dir>] [--nodes=3] [--degraded=120] [--takeovers=3]
#   lease-storm.sh up | down | status | sample
#   lease-storm.sh nodes start|stop [--label=<name>] [--install=<dir>] [--nodes=3]
#   lease-storm.sh storm [--label=<name>] [--degraded=120]
#   lease-storm.sh takeover <i> [--label=<name>]
#
# `run` does it all: builds the catalog server of this tree (unless --install names another build's installDist
# output), starts the replica set and the nodes, runs the storm, then each takeover on fresh nodes, and stops
# everything. Its output goes to test/benchmarks/results/e2e/lease-storm-<label>/, which git ignores. JAVA_HOME is a
# JDK 25. The heavy steps run through quiet.sh, so they wait for another batch holding the machine.
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$HERE" rev-parse --show-toplevel)
QUIET="$ROOT/test/benchmarks/harness/quiet.sh"
CONTAINER=wow-lease-storm
PARTITIONS=64
URI="mongodb://localhost:27117,localhost:27118/?replicaSet=rs0"

LABEL="head"
INSTALL=""
NODES=3
DEGRADED=120
TAKEOVERS=3
ARGS=()
for arg in "$@"; do
  case $arg in
    --label=*) LABEL=${arg#*=} ;;
    --install=*) INSTALL=${arg#*=} ;;
    --nodes=*) NODES=${arg#*=} ;;
    --degraded=*) DEGRADED=${arg#*=} ;;
    --takeovers=*) TAKEOVERS=${arg#*=} ;;
    *) ARGS+=("$arg") ;;
  esac
done
set -- "${ARGS[@]+"${ARGS[@]}"}"
OUT="$ROOT/test/benchmarks/results/e2e/lease-storm-$LABEL"
BUILT="$ROOT/examples/eshop/catalog/catalog-server/build/install/eshop-catalog-server"

mongo() { docker exec "$CONTAINER" mongosh --quiet --port "$1" "${@:2}"; }

# Three mongod in one container, each published on the same host port, so the members' names (localhost:<port>)
# resolve inside the container and on the host alike: a primary (27117), a secondary (27118) and an arbiter (27119).
# The default write concern is w:1 so that the arbiter can be added; Wow writes with w:majority itself.
up() {
  docker rm -f "$CONTAINER" >/dev/null 2>&1
  docker run -d --name "$CONTAINER" -p 127.0.0.1:27117:27117 -p 127.0.0.1:27118:27118 -p 127.0.0.1:27119:27119 \
    --entrypoint bash mongo:7.0 -c '
      mkdir -p /data/p /data/s /data/a
      mongod --replSet rs0 --port 27117 --bind_ip_all --dbpath /data/p --fork --logpath /data/p.log --wiredTigerCacheSizeGB 0.5
      mongod --replSet rs0 --port 27118 --bind_ip_all --dbpath /data/s --fork --logpath /data/s.log --wiredTigerCacheSizeGB 0.5
      mongod --replSet rs0 --port 27119 --bind_ip_all --dbpath /data/a --fork --logpath /data/a.log
      sleep infinity' >/dev/null
  sleep 3
  mongo 27117 --eval '
    rs.initiate({_id:"rs0",members:[{_id:0,host:"localhost:27117",priority:2},{_id:1,host:"localhost:27118",priority:1}]});
    while (!db.hello().isWritablePrimary) { sleep(200) }
    db.adminCommand({setDefaultRWConcern:1, defaultWriteConcern:{w:1}});
    rs.addArb("localhost:27119");
    printjson(rs.status().members.map(m => m.name + " " + m.stateStr))'
}

down() { docker rm -f "$CONTAINER" >/dev/null 2>&1; echo "replica set removed"; }

status() { mongo 27117 --eval 'printjson(rs.status().members.map(m => m.name + " " + m.stateStr))'; }

# One line on the primary: its connections (current/active), the lease writes in flight on wow_lease (findAndModify and
# update), those waiting for flow control, the median and longest seconds a findAndModify has run, the live leases and
# their owners. Reads of the lease collection still answer while the store cannot acknowledge a write.
sample() {
  docker cp "$HERE/sample.js" "$CONTAINER:/sample.js" >/dev/null 2>&1
  mongo 27117 --file /sample.js 2>&1 | tail -1
}

stop_secondary() {
  mongo 27118 --eval 'db.getSiblingDB("admin").shutdownServer({force:true})' >/dev/null 2>&1
  echo "secondary stopped $(date +%T)"
}

start_secondary() {
  docker exec "$CONTAINER" mongod --replSet rs0 --port 27118 --bind_ip_all --dbpath /data/s --fork \
    --logpath /data/s.log --wiredTigerCacheSizeGB 0.5 >/dev/null
  echo "secondary started $(date +%T)"
}

# The nodes: the eShop catalog server, profiles mongo and dev, default wow.cluster settings, one lease group of
# $PARTITIONS partitions. Ports 18301.. (public) and 19301.. (internal); logs and pids in $OUT/nodes.
nodes() {
  local install=${INSTALL:-$BUILT}
  mkdir -p "$OUT/nodes"
  case $1 in
    start)
      for i in $(seq 1 "$NODES"); do
        JAVA_OPTS="-Xmx512m" "$install/bin/eshop-catalog-server" \
          --spring.profiles.active=mongo,dev --spring.mongodb.uri="$URI" \
          --server.port=$((18300 + i)) --wow.internal.port=$((19300 + i)) --wow.node.id="node-$i" \
          --logging.level.me.ahoo.wow.runtime.cluster=info > "$OUT/nodes/node-$i.log" 2>&1 &
        echo $! > "$OUT/nodes/node-$i.pid"
      done
      for i in $(seq 1 "$NODES"); do
        for _ in $(seq 1 200); do
          [ "$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$((18300 + i))/wow/manifest")" = 200 ] && break
          kill -0 "$(cat "$OUT/nodes/node-$i.pid")" 2>/dev/null || { echo "node-$i exited"; tail -20 "$OUT/nodes/node-$i.log"; return 1; }
          sleep 0.3
        done
      done
      echo "started $NODES nodes ($LABEL) $(date +%T)"
      ;;
    stop)
      for f in "$OUT"/nodes/node-*.pid; do [ -f "$f" ] && kill "$(cat "$f")" 2>/dev/null; done
      sleep 3
      for f in "$OUT"/nodes/node-*.pid; do [ -f "$f" ] && kill -9 "$(cat "$f")" 2>/dev/null; done
      rm -f "$OUT"/nodes/node-*.pid
      echo "nodes stopped"
      ;;
  esac
}

# Node connections the primary has ended so far (its log; mongosh's own, from inside the container, left out).
ended() { docker exec "$CONTAINER" grep '"Connection ended"' /data/p.log | grep -vc '"remote":"127.0.0.1'; }

# Stops the secondary for --degraded seconds, sampling every ~2 s, starts it, and samples until every partition is
# owned for five samples in a row (at most 300 s). Then the summary of the degraded window and the sockets dropped.
storm() {
  local out="$OUT/storm.txt" before after end start stable line
  mkdir -p "$OUT"; : > "$out"
  before=$(ended)
  sample | tee -a "$out"
  stop_secondary | tee -a "$out"
  end=$((SECONDS + DEGRADED))
  while [ $SECONDS -lt $end ]; do sample | tee -a "$out"; sleep 2; done
  start_secondary | tee -a "$out"
  start=$SECONDS; stable=0
  while [ $((SECONDS - start)) -lt 300 ]; do
    line=$(sample); echo "$line" | tee -a "$out"
    if echo "$line" | grep -q " live=$PARTITIONS "; then stable=$((stable + 1)); else stable=0; fi
    [ $stable -ge 5 ] && break
    sleep 2
  done
  after=$(ended)
  echo "recovered after $((SECONDS - start))s ($PARTITIONS live for 5 samples)" | tee -a "$out"
  {
    awk '/secondary stopped/{on=1; next} /secondary started/{on=0}
      on {split($2,c,"[=/]"); split($3,f,"="); split($7,m,"="); n++; conn+=c[2]; fam+=f[2];
          if (f[2]+0>fmax) fmax=f[2]+0; if (m[2]+0>smax) smax=m[2]+0}
      END {printf "degraded samples %d, avg connections %d, avg lease writes in flight %.1f (max %d), longest wait %ds\n",
           n, conn/n, fam/n, fmax, smax}' "$out"
    echo "sockets the nodes' driver closed on a client-side timeout (their logs): $(cat "$OUT"/nodes/node-*.log | grep -c 'Caused by: com.mongodb.MongoSocketReadTimeoutException')"
    echo "node connections the primary ended during the storm (its log): $((after - before))"
    tail -1 "$out"
  } | tee "$OUT/storm-summary.txt"
  cp -r "$OUT/nodes" "$OUT/storm-nodes"
}

# Kills node <i> as a crash (SIGKILL) and polls the lease collection every 100 ms until none of its leases is live and
# the survivors own all $PARTITIONS partitions.
takeover() {
  local i=$1
  docker cp "$HERE/takeover.js" "$CONTAINER:/takeover.js" >/dev/null
  kill -9 "$(cat "$OUT/nodes/node-$i.pid")"; echo "killed node-$i $(date +%T)"
  mongo 27117 --eval "const DEAD='node-$i'; const PARTITIONS=$PARTITIONS" --file /takeover.js | tee -a "$OUT/takeover.txt"
}

run() {
  if [ -z "$INSTALL" ]; then
    (cd "$ROOT" && "$QUIET" ./gradlew -q :eshop-catalog-server:installDist) || exit 1
  fi
  rm -rf "$OUT"; mkdir -p "$OUT"
  "$QUIET" "$HERE/lease-storm.sh" up | tail -1
  sleep 5; status
  nodes start || exit 1
  sleep 6; sample
  storm > /dev/null
  echo "== storm ($LABEL)"; cat "$OUT/storm-summary.txt"
  echo "== takeovers ($LABEL)"
  for i in $(seq "$TAKEOVERS" -1 1); do
    nodes stop > /dev/null; sleep 12
    nodes start > /dev/null || exit 1; sleep 8
    takeover "$(( (i - 1) % NODES + 1 ))" | grep after
  done
  nodes stop > /dev/null
  down
}

case ${1:-} in
  run) run ;;
  up) up ;;
  down) down ;;
  status) status ;;
  sample) sample ;;
  nodes) nodes "${2:?start or stop}" ;;
  storm) storm ;;
  takeover) takeover "${2:?the node to kill}" ;;
  *) sed -n '15,27p' "${BASH_SOURCE[0]}"; exit 2 ;;
esac
