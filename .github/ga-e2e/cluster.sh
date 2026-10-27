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

# The GA end-to-end cluster (roadmap 9.3g, decision D317), driven by ga-e2e.yml, one command per step:
#
#   cluster.sh agent              the OpenTelemetry Java agent, checked against its SHA-256, where kind.yaml mounts it
#   cluster.sh images             build every service's image from its Gradle distribution (installDist done)
#   cluster.sh network            Calico on the kind cluster helm/kind-action created without a CNI (NetworkPolicy
#                                 enforced), and the images loaded into it
#   cluster.sh backends <arm>     PostgreSQL, Kafka and its topics, ClickHouse, the CoSec gateway, its Redis and the
#                                 flaky upstream behind it, the probe pods, and on the mongo arm the replica set and
#                                 Elasticsearch
#   cluster.sh services <arm>     the services in waves, each with its overlay (services/<service>), until ready
#   cluster.sh dns <file>         what CoreDNS answers for the internal ports' headless Services (SRV and A), for D305
#   cluster.sh diagnostics <dir>  every pod's description and log (the previous container's too) and the events
#
# GitHub Actions only: it creates a cluster on the machine it runs on (the repository runs no cluster locally).
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
cluster=wow-ga-e2e

CALICO_VERSION=v3.32.2
CALICO_SHA256=a8c828a06a87c629a282ebbc424895b77f3a030251993e41ea400a743675bb02

# The OpenTelemetry Java agent (F6): every node's Wow meters through its Prometheus endpoint (services/*), CI only.
OTEL_AGENT_VERSION=2.32.0
OTEL_AGENT_SHA256=f787eb6c7f3d18e69a431e108a15278d25ee37f83d68b678f621e063f3988f82
OTEL_AGENT_DIR=/tmp/ga-e2e-otel

# image | Gradle distribution directory | Dockerfile (empty: the context's own)
IMAGES=(
  "wow-eshop-catalog-server:local|example/eshop/catalog/catalog-server|deploy/eshop-catalog/Dockerfile"
  "wow-eshop-basket-server:local|example/eshop/basket/basket-server|deploy/eshop-basket/Dockerfile"
  "wow-eshop-ordering-server:local|example/eshop/ordering/ordering-server|deploy/eshop-ordering/Dockerfile"
  "wow-eshop-inventory-server:local|example/eshop/inventory/inventory-server|deploy/eshop-inventory/Dockerfile"
  "wow-eshop-payment-server:local|example/eshop/payment/payment-server|deploy/eshop-payment/Dockerfile"
  "wow-ops-server:local|platform/ops/ops-server|"
  "wow-view-store-server:local|platform/view-store/view-store-server|"
  "wow-analytics-catalog-server:local|platform/analytics/catalog/server|"
)

# The waves of services: the platform first (ops records the ledgers), then the contexts others call or send to,
# then the ordering, and the basket, which calls the ordering, last.
WAVES=(
  "ops view-store analytics-catalog"
  "eshop-catalog eshop-inventory eshop-payment"
  "eshop-ordering"
  "eshop-basket"
)

log() { echo "[ga-e2e] $*"; }

agent() {
  local jar="$OTEL_AGENT_DIR/opentelemetry-javaagent.jar"
  mkdir -p "$OTEL_AGENT_DIR"
  curl -fsSL -o "$jar" \
    "https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/$OTEL_AGENT_VERSION/opentelemetry-javaagent-$OTEL_AGENT_VERSION.jar"
  echo "$OTEL_AGENT_SHA256  $jar" | sha256sum -c -
}

images() {
  local entry image context dockerfile
  for entry in "${IMAGES[@]}"; do
    IFS='|' read -r image context dockerfile <<<"$entry"
    log "image $image from $context"
    if [ -z "$dockerfile" ]; then
      docker build -q -t "$image" "$root/$context"
    else
      docker build -q -f "$root/$dockerfile" -t "$image" "$root/$context"
    fi
  done
}

# The third-party images are not loaded: the node pulls each by its digest, as the manifests pin them.
network() {
  local entry image
  curl -fsSLo /tmp/calico.yaml "https://raw.githubusercontent.com/projectcalico/calico/$CALICO_VERSION/manifests/calico.yaml"
  echo "$CALICO_SHA256  /tmp/calico.yaml" | sha256sum -c -
  kubectl apply -f /tmp/calico.yaml >/dev/null
  for entry in "${IMAGES[@]}"; do
    image=${entry%%|*}
    kind load docker-image --name "$cluster" "$image"
  done
  kubectl -n kube-system rollout status daemonset/calico-node --timeout=300s
  kubectl -n kube-system rollout status deployment/calico-kube-controllers --timeout=300s
  kubectl wait --for=condition=Ready node --all --timeout=300s
}

backends() {
  local arm=$1
  kubectl apply -f "$here/backends/postgres.yaml" -f "$here/backends/kafka.yaml" -f "$here/backends/clickhouse.yaml" \
    -f "$here/gateway/redis.yaml" -f "$here/backends/probes.yaml"
  if [ "$arm" = mongo ]; then
    kubectl apply -k "$here/backends/mongodb"
    kubectl apply -f "$here/backends/elasticsearch.yaml"
  fi
  kubectl rollout status statefulset/postgres --timeout=300s
  kubectl rollout status statefulset/kafka --timeout=300s
  kubectl rollout status statefulset/clickhouse --timeout=300s
  kubectl rollout status deployment/redis --timeout=300s
  kubectl apply -f "$here/backends/kafka-topics.yaml"
  kubectl wait --for=condition=complete job/kafka-topics --timeout=300s
  kubectl logs job/kafka-topics | tail -20
  if [ "$arm" = mongo ]; then
    kubectl rollout status statefulset/mongodb --timeout=300s
    kubectl wait --for=condition=complete job/mongodb-init --timeout=300s
    kubectl logs job/mongodb-init | tail -5
    kubectl rollout status statefulset/elasticsearch --timeout=300s
  fi
  # The gateway does not start without its Redis.
  kubectl apply -f "$here/gateway/gateway.yaml" -f "$here/gateway/flaky.yaml"
  kubectl rollout status deployment/cosec-gateway --timeout=420s
  kubectl rollout status deployment/flaky --timeout=120s
  kubectl wait --for=condition=Ready pod/outsider pod/insider --timeout=120s
}

# The overlay of one service on the arm (services/<service>, deploy/<service> with the scenarios' settings): on the
# postgres arm, an eShop service's postgres/<service>, which adds deploy/'s postgres component.
service() {
  local name=$1 arm=$2 dir="$here/services/$1"
  if [ "$arm" = postgres ] && [ -d "$here/services/postgres/$name" ]; then
    dir="$here/services/postgres/$name"
  fi
  kubectl apply -k "$dir"
}

services() {
  local arm=$1 wave name failed=0
  for wave in "${WAVES[@]}"; do
    # GA_EXCLUDED: services a run leaves out, for a reason its report states (the runner reads the same list).
    for name in ${GA_EXCLUDED:-}; do wave=${wave//$name/}; done
    for name in $wave; do service "$name" "$arm"; done
    for name in $wave; do
      # Every object is named after the service, its spring.application.name (deploy/).
      if ! kubectl rollout status "deployment/$name-service" --timeout=600s; then
        log "::error::$name did not become ready on the $arm arm"
        failed=1
      fi
    done
    [ "$failed" = 0 ] || return 1
  done
  kubectl get pods -o wide
}

# The headless Services' records as a peer discovering a service's nodes reads them (D305's DNS SRV seed,
# _internal._tcp.<spring.application.name>-cluster): through a port-forward to CoreDNS, over TCP.
dns() {
  local out=$1 forward name target
  mkdir -p "$(dirname "$out")"
  kubectl -n kube-system port-forward service/kube-dns 5353:53 >/dev/null 2>&1 &
  forward=$!
  sleep 3
  {
    for name in eshop-catalog eshop-basket eshop-ordering eshop-inventory eshop-payment ops view-store analytics-catalog; do
      echo "## $name-service-cluster"
      dig +tcp -p 5353 @127.0.0.1 +noall +answer "_internal._tcp.$name-service-cluster.default.svc.cluster.local" SRV
      dig +tcp -p 5353 @127.0.0.1 +noall +answer "$name-service-cluster.default.svc.cluster.local" A
      for target in $(dig +tcp -p 5353 @127.0.0.1 +short "_internal._tcp.$name-service-cluster.default.svc.cluster.local" SRV |
        awk '{print $4}'); do
        dig +tcp -p 5353 @127.0.0.1 +noall +answer "$target" A
      done
    done
  } >"$out" 2>&1 || true
  kill "$forward" 2>/dev/null || true
}

diagnostics() {
  local out=$1 pod container
  mkdir -p "$out/logs"
  kubectl get all,networkpolicy,configmap,pvc -o wide >"$out/resources.txt" 2>&1 || true
  kubectl get events --sort-by=.lastTimestamp >"$out/events.txt" 2>&1 || true
  kubectl describe pods >"$out/pods.txt" 2>&1 || true
  kubectl -n kube-system get pods -o wide >"$out/kube-system.txt" 2>&1 || true
  for pod in $(kubectl get pods -o name 2>/dev/null); do
    pod=${pod#pod/}
    for container in $(kubectl get pod "$pod" -o jsonpath='{.spec.containers[*].name}'); do
      kubectl logs "$pod" -c "$container" >"$out/logs/$pod.log" 2>&1 || true
      kubectl logs "$pod" -c "$container" --previous >"$out/logs/$pod.previous.log" 2>/dev/null ||
        rm -f "$out/logs/$pod.previous.log"
    done
  done
}

command=${1:?usage: cluster.sh agent|images|network|backends <arm>|services <arm>|dns <file>|diagnostics <dir>}
shift
"$command" "$@"
