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
# drill.sh's cluster and platform (step 1), sourced by drill.sh: its settings and helpers are in scope.

cluster() {
  if kind get clusters | grep -qx "$cluster"; then
    log "cluster $cluster exists"
  else
    kind create cluster --config "$here/kind.yaml" --wait 0s
  fi
  curl -fsSLo "$evidence/calico.yaml" "https://raw.githubusercontent.com/projectcalico/calico/$CALICO_VERSION/manifests/calico.yaml"
  echo "$CALICO_SHA256  $evidence/calico.yaml" | shasum -a 256 -c -
  "$kubectl" --context "kind-$cluster" apply -f "$evidence/calico.yaml" >/dev/null
  "$kubectl" --context "kind-$cluster" -n kube-system rollout status daemonset/calico-node --timeout=600s
  "$kubectl" --context "kind-$cluster" wait --for=condition=Ready nodes --all --timeout=600s
  "$kubectl" --context "kind-$cluster" create namespace "$namespace" --dry-run=client -o yaml |
    "$kubectl" --context "kind-$cluster" apply -f -
  # The cluster test runs kubectl in the context's namespace.
  "$kubectl" config set-context "kind-$cluster" --namespace "$namespace" >/dev/null
  "$kubectl" --context "kind-$cluster" get nodes -L topology.kubernetes.io/zone
}

random() { openssl rand -hex "${1:-24}"; }

# The run's secrets (README.md, "Secrets"): random values, a CA of the run's own, created in the namespace and kept
# in the evidence folder only.
secrets() {
  local dir="$evidence/secrets" name user service password uri hosts
  mkdir -p "$dir"
  chmod 700 "$dir"
  [ -f "$dir/ca.key" ] || {
    openssl req -x509 -newkey rsa:2048 -nodes -days 30 -subj "/CN=wow-rc-drill CA" \
      -keyout "$dir/ca.key" -out "$dir/ca.crt" 2>/dev/null
    certificate elasticsearch "DNS:elasticsearch,DNS:*.elasticsearch-headless,DNS:elasticsearch-headless"
    certificate ingress "DNS:localhost,IP:127.0.0.1"
    random 512 >"$dir/mongodb.keyfile"
    for name in ROOT ESHOP_CATALOG ESHOP_BASKET ESHOP_ORDERING ESHOP_INVENTORY ESHOP_PAYMENT OPS VIEW_STORE \
      ANALYTICS_CATALOG DRILL_READER ELASTIC ES_CATALOG CLICKHOUSE JWT; do
      random >"$dir/$name"
    done
  }
  k create secret generic mongodb-auth --from-file=keyfile="$dir/mongodb.keyfile" --from-literal=username=root \
    --from-file=password="$dir/ROOT" --dry-run=client -o yaml | k apply -f - >/dev/null
  local users=()
  for name in ESHOP_CATALOG ESHOP_BASKET ESHOP_ORDERING ESHOP_INVENTORY ESHOP_PAYMENT OPS VIEW_STORE ANALYTICS_CATALOG \
    DRILL_READER; do
    users+=("--from-file=$name=$dir/$name")
  done
  k create secret generic mongodb-users "${users[@]}" --dry-run=client -o yaml | k apply -f - >/dev/null
  hosts=mongodb-0.mongodb:27017,mongodb-1.mongodb:27017,mongodb-2.mongodb:27017
  for service in eshop-catalog eshop-basket eshop-ordering eshop-inventory eshop-payment ops view-store \
    analytics-catalog; do
    user=$(tr '[:lower:]-' '[:upper:]_' <<<"$service")
    password=$(cat "$dir/$user")
    # No database in the path: the host would take it as wow.mongo.database (README.md, "Secrets").
    uri="mongodb://$service:$password@$hosts/?replicaSet=rs0&w=majority&authSource=admin"
    local extra=()
    case "$service" in
      eshop-catalog) extra+=("--from-file=SPRING_ELASTICSEARCH_PASSWORD=$dir/ES_CATALOG") ;;
      eshop-ordering | analytics-catalog) extra+=("--from-file=WOW_CLICKHOUSE_PASSWORD=$dir/CLICKHOUSE") ;;
    esac
    k create secret generic "$service-service-mongo" --from-literal=SPRING_MONGODB_URI="$uri" ${extra[@]+"${extra[@]}"} \
      --dry-run=client -o yaml | k apply -f - >/dev/null
  done
  k create secret generic elasticsearch-certs --from-file=ca.crt="$dir/ca.crt" \
    --from-file=tls.crt="$dir/elasticsearch.crt" --from-file=tls.key="$dir/elasticsearch.key" --dry-run=client -o yaml |
    k apply -f - >/dev/null
  k create secret generic elasticsearch-credentials --from-file=password="$dir/ELASTIC" \
    --from-file=eshop-catalog="$dir/ES_CATALOG" --dry-run=client -o yaml | k apply -f - >/dev/null
  k create secret generic clickhouse-credentials --from-file=password="$dir/CLICKHOUSE" --dry-run=client -o yaml |
    k apply -f - >/dev/null
  k create secret generic cosec-jwt --from-file=COSEC_JWT_SECRET="$dir/JWT" --dry-run=client -o yaml |
    k apply -f - >/dev/null
  k create secret tls drill-ingress-tls --cert="$dir/ingress.crt" --key="$dir/ingress.key" --dry-run=client -o yaml |
    k apply -f - >/dev/null
  log "secrets created; kept in $dir"
}

# A certificate signed by the run's CA, for the subject alternative names given.
certificate() {
  local name=$1 names=$2 dir="$evidence/secrets"
  openssl req -newkey rsa:2048 -nodes -subj "/CN=$name" -keyout "$dir/$name.key" -out "$dir/$name.csr" 2>/dev/null
  openssl x509 -req -in "$dir/$name.csr" -CA "$dir/ca.crt" -CAkey "$dir/ca.key" -CAcreateserial -days 30 \
    -extfile <(printf 'subjectAltName=%s\nextendedKeyUsage=serverAuth,clientAuth\n' "$names") -out "$dir/$name.crt" \
    2>/dev/null
}

platform() {
  local version=$1
  k apply -k "$here/platform"
  for set in mongodb kafka elasticsearch clickhouse redis prometheus; do
    k rollout status "statefulset/$set" --timeout=900s
  done
  k wait --for=condition=complete job/mongodb-init --timeout=600s
  k logs job/mongodb-init | tail -12
  k delete job kafka-topics elasticsearch-users --ignore-not-found >/dev/null
  k apply -f "$here/platform/kafka/kafka-topics.yaml" -f "$here/platform/elasticsearch/elasticsearch-users.yaml"
  k wait --for=condition=complete job/kafka-topics job/elasticsearch-users --timeout=600s
  k logs job/kafka-topics | grep -E 'Topic: wow\.' | grep -v 'Partition:' || true
  # The telemetry: the operator, then the Instrumentation its CRDs define.
  helm repo add open-telemetry https://open-telemetry.github.io/opentelemetry-helm-charts >/dev/null 2>&1 || true
  helm repo add traefik https://traefik.github.io/charts >/dev/null 2>&1 || true
  helm repo update open-telemetry traefik >/dev/null
  helm --kube-context "kind-$cluster" upgrade --install opentelemetry-operator open-telemetry/opentelemetry-operator \
    --version "$OTEL_OPERATOR_CHART_VERSION" -n opentelemetry --create-namespace \
    -f "$here/platform/observability/otel-operator-values.yaml" --wait --timeout 10m
  k apply -f "$here/platform/observability/instrumentation.yaml"
  k rollout status deployment/otel-collector --timeout=300s
  k rollout status deployment/kube-state-metrics --timeout=300s
  # The gateway does not start without its Redis, and refuses traffic until its documents are there.
  documents
  k apply -f "$here/platform/gateway/gateway.yaml"
  k rollout status deployment/cosec-gateway --timeout=600s
  helm --kube-context "kind-$cluster" upgrade --install traefik traefik/traefik --version "$TRAEFIK_CHART_VERSION" \
    -n traefik --create-namespace -f "$here/platform/ingress/traefik-values.yaml" --wait --timeout 10m
  k apply -f "$here/platform/ingress/ingress.yaml"
  sed "s/DRILL_VERSION/$version/" "$here/platform/probes.yaml" | k apply -f -
  k get pods -o wide
}

# The gateway's documents (.github/ga-e2e/gateway/documents) published into its Redis by `cosec-migrate publish`,
# through a port-forward, as the GA end-to-end run publishes them (.github/ga-e2e/cluster.sh documents).
documents() {
  local dir forward file sums='{}'
  dir=$(mktemp -d)
  cp -R "$root/.github/ga-e2e/gateway/documents/." "$dir/"
  while IFS= read -r file; do
    sums=$(jq --arg file "$file" --arg sha "$(shasum -a 256 "$dir/$file" | cut -d' ' -f1)" '. + {($file): $sha}' <<<"$sums")
  done < <(cd "$dir" && find . -type f -name '*.json' | sed 's|^\./||' | sort)
  jq -n --argjson sums "$sums" '{accepted: true, outputSha256: $sums}' >"$dir/report.json"
  k port-forward service/redis 16379:6379 >/dev/null 2>&1 &
  forward=$!
  for _ in $(seq 1 30); do
    if (exec 3<>/dev/tcp/127.0.0.1/16379) 2>/dev/null; then break; fi
    sleep 1
  done
  "$root/gradlew" -p "$root" --quiet :eshop-e2e:cosecMigrate \
    --args="publish --to-redis redis://127.0.0.1:16379 --in $dir" || { kill "$forward"; return 1; }
  kill "$forward" 2>/dev/null || true
}

teardown() {
  kind delete cluster --name "$cluster"
}
