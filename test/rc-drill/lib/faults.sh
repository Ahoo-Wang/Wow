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
# drill.sh's faults and operations under load (steps 5 to 8, 11 and 12), sourced by drill.sh: its settings and
# helpers are in scope.

worker() { echo "$cluster-worker$1"; }

# Step 5: the partition owner of ordering killed (the cluster test's own scenario), then a whole worker, a zone, stopped
# for three minutes under load and started again.
node-loss() {
  e2e owner-deletion owner-deletion
  under_load node-loss PT120S zone_down "$(worker 2)"
}

zone_down() {
  local node=$1
  docker stop "$node" >/dev/null
  log "node $node stopped"
  sleep 180
  docker start "$node" >/dev/null
  kubectl --context "kind-$cluster" wait --for=condition=Ready "node/$node" --timeout=600s
  for set in mongodb kafka elasticsearch; do k rollout status "statefulset/$set" --timeout=900s; done
}

# mongosh in the drill's shell as drill-reader, whose password is in the shell's environment: kubectl runs no shell,
# so sh expands it there.
mongo() {
  local hosts=mongodb-0.mongodb:27017,mongodb-1.mongodb:27017,mongodb-2.mongodb:27017
  # shellcheck disable=SC2016 # expanded by the shell in the pod
  k exec mongo-shell -- sh -c \
    "mongosh --quiet \"mongodb://drill-reader:\$DRILL_READER_PASSWORD@$hosts/admin?replicaSet=rs0\" --eval \"\$1\"" \
    _ "$1"
}

# Step 6, each under load: MongoDB's primary killed, then a secondary; a Kafka broker killed; Elasticsearch stopped for
# two minutes and started again.
store-faults() {
  under_load mongo-primary PT60S kill_member primary
  under_load mongo-secondary PT60S kill_member secondary
  under_load kafka-broker PT120S kill_pod kafka-1 statefulset/kafka
  under_load elasticsearch PT0S elasticsearch_down
}

kill_member() {
  local role=$1 member
  if [ "$role" = primary ]; then
    member=$(mongo 'db.hello().primary' | cut -d. -f1)
  else
    member=$(mongo 'db.hello().hosts.find(h => h !== db.hello().primary)' | cut -d. -f1)
  fi
  log "MongoDB $role: $member"
  kill_pod "$member" statefulset/mongodb
}

kill_pod() {
  local pod=$1 owner=$2
  k delete pod "$pod" --grace-period=0 --force
  k rollout status "$owner" --timeout=900s
}

elasticsearch_down() {
  k scale statefulset/elasticsearch --replicas=0
  k wait --for=delete pod -l app.kubernetes.io/name=elasticsearch --timeout=300s
  sleep 120
  k scale statefulset/elasticsearch --replicas=3
  k rollout status statefulset/elasticsearch --timeout=900s
}

# Step 7: the ordering scaled 2 → 4 → 2 under load.
scale() {
  under_load scale PT30S scale_ordering
}

scale_ordering() {
  k scale deployment/eshop-ordering-service --replicas=4
  k rollout status deployment/eshop-ordering-service --timeout=600s
  sleep 60
  k scale deployment/eshop-ordering-service --replicas=2
  k rollout status deployment/eshop-ordering-service --timeout=600s
}

# Step 8: a worker drained under load, every disruption budget respected, then uncordoned.
drain() {
  under_load drain PT60S drain_worker "$(worker 3)"
}

drain_worker() {
  local node=$1
  kubectl --context "kind-$cluster" drain "$node" --ignore-daemonsets --delete-emptydir-data --timeout=900s
  sleep 60
  kubectl --context "kind-$cluster" uncordon "$node"
  for set in mongodb kafka elasticsearch; do k rollout status "statefulset/$set" --timeout=900s; done
}

# Step 11, as Operate › Backup says: the service's nodes stopped, its database dumped, dropped and restored, the nodes
# started; then the verdict. The dump is kept in the evidence folder.
backup() {
  local root_password
  root_password=$(cat "$evidence/secrets/ROOT")
  k scale deployment/eshop-ordering-service --replicas=0
  k wait --for=delete pod -l app.kubernetes.io/name=eshop-ordering-service --timeout=600s
  local uri="mongodb://root:$root_password@mongodb-0.mongodb:27017,mongodb-1.mongodb:27017,mongodb-2.mongodb:27017/?replicaSet=rs0&authSource=admin"
  k exec mongo-shell -- mongodump --quiet --uri "$uri" --db eshop_ordering --archive=/tmp/eshop_ordering.archive
  k cp mongo-shell:/tmp/eshop_ordering.archive "$evidence/eshop_ordering.archive" >/dev/null
  k exec mongo-shell -- mongosh --quiet "$uri" --eval 'db.getSiblingDB("eshop_ordering").dropDatabase()'
  k exec mongo-shell -- mongorestore --quiet --uri "$uri" --archive=/tmp/eshop_ordering.archive
  k scale deployment/eshop-ordering-service --replicas=2
  k rollout status deployment/eshop-ordering-service --timeout=900s
  e2e verdict backup
}

# Step 12: every service uninstalled, the platform's stores kept, then the same version installed on them again, and the
# verdict: what was there before is there after.
reinstall() {
  local version=$1 index name
  for ((index = ${#WAVES[@]} - 1; index >= 0; index--)); do
    for name in ${WAVES[index]}; do
      helm --kube-context "kind-$cluster" -n "$namespace" uninstall "$name" --wait --timeout 10m
    done
  done
  [ -z "$(k get pods -l app.kubernetes.io/managed-by=Helm -o name)" ] || fail "pods left after the uninstall"
  install "$version"
  e2e verdict reinstall
}
