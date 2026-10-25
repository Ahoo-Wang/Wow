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
# The MongoDB backend on a cluster (roadmap §6 item 18): a three-member replica set, or a sharded cluster of two
# three-member shards behind mongos, in one container (supervise.sh), and the backend's integration tests and TCK
# against it. The tests take the cluster's URL from wow.mongo.url in place of their single-member container; the
# replica set's failover tests run only on a cluster of three members. Runs on macOS's bash 3.2.
#
#   cluster.sh up rs|sharded     start the cluster and wait until it serves
#   cluster.sh url rs|sharded    print its connection string
#   cluster.sh test rs|sharded   start it, run :mongo:integrationTest against it, remove it
#   cluster.sh down              remove the container and its data
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
NAME=wow-mongo-cluster
IMAGE=${MONGO_IMAGE:-mongo:7.0}

url() {
  case $1 in
    rs) echo "mongodb://localhost:27101,localhost:27102,localhost:27103/?replicaSet=rs0" ;;
    sharded) echo "mongodb://localhost:27200/" ;;
    *) echo "unknown mode $1" >&2; exit 2 ;;
  esac
}

up() {
  local mode=$1 ports=()
  url "$mode" >/dev/null
  down
  case $mode in
    rs) ports=(-p 27101-27103:27101-27103) ;;
    sharded) ports=(-p 27200:27200) ;;
  esac
  # --init reaps the members that stop, so the supervisor sees them gone and starts them again.
  docker run -d --init --name "$NAME" "${ports[@]}" -v "$HERE/supervise.sh:/supervise.sh:ro" \
    --entrypoint bash "$IMAGE" /supervise.sh "$mode" >/dev/null
  local waited=0
  until docker exec "$NAME" test -f /data/cluster/ready 2>/dev/null; do
    if [ "$(docker inspect -f '{{.State.Running}}' "$NAME")" != true ] || [ $waited -ge 180 ]; then
      docker logs "$NAME" >&2
      echo "the $mode cluster did not start" >&2
      exit 1
    fi
    sleep 1
    waited=$((waited + 1))
  done
  echo "$mode cluster serving at $(url "$mode")"
}

down() {
  docker rm -f -v "$NAME" >/dev/null 2>&1 || true
}

run_tests() {
  local mode=$1
  up "$mode"
  trap down EXIT
  shift
  (cd "$ROOT" && ./gradlew :mongo:integrationTest "-Dwow.mongo.url=$(url "$mode")" --rerun "$@")
}

case ${1:-} in
  up) up "${2:?rs or sharded}" ;;
  url) url "${2:?rs or sharded}" ;;
  test) run_tests "${2:?rs or sharded}" "${@:3}" ;;
  down) down ;;
  *) sed -n '14,22p' "$0" >&2; exit 2 ;;
esac
