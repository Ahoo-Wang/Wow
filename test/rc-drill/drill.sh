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

# The rc drill (README.md): the eShop and the platform deployed from a release's published artifacts in the shape of
# platform/ and values/, then the drill's steps, each timed and its evidence kept. One command per step, or `run` for
# all of them in the plan's order:
#
#   drill.sh artifacts <version>             0  the release's artifacts on every registry it publishes to
#   drill.sh compare <from> <to>                the changes between two releases a running deployment cannot cross
#   drill.sh cluster                         1  the kind cluster, Calico and the namespace
#   drill.sh secrets                         1  the run's secrets, generated and created
#   drill.sh platform <version>              1  the stores, the broker, the telemetry, the gateway and the ingress
#   drill.sh build <version>                 2  the release's source, the eShop's images built against its published
#                                               Wow and loaded into the cluster, every service's generated values
#   drill.sh install <version>               2  every service from the release's chart and images, in waves
#   drill.sh e2e <scenarios>                 3  the end-to-end suite through the ingress (the cluster test)
#   drill.sh upgrade <from> <to> rolling|restart
#                                            4  the rolling upgrade, or a rolling restart at the same version, under load
#   drill.sh node-loss                       5  the partition owner killed, then a whole worker (a zone) stopped
#   drill.sh store-faults                    6  MongoDB's primary and a secondary killed, a broker killed, Elasticsearch
#                                               stopped for two minutes
#   drill.sh scale                           7  the ordering scaled 2 → 4 → 2
#   drill.sh drain                           8  a worker drained under the disruption budgets, then uncordoned
#   drill.sh alerts                          10 the alerts' history over the run, from Prometheus
#   drill.sh backup                          11 the ordering's database backed up, dropped and restored
#   drill.sh reinstall <version>             12 every service uninstalled, then installed again on the kept stores
#   drill.sh diagnostics                        every pod's description and log, the events, each JVM's threads
#   drill.sh teardown                           the cluster deleted
#   drill.sh run <version> [<previous>]         all of it, in order; with <previous>, installed first and upgraded from
#
# Steps 3, 5 (the owner's kill), 9 (ops) and the load under 4 to 8 are the cluster test's (examples/eshop/e2e,
# :eshop-e2e:clusterTest), driven from outside the cluster through the ingress; `e2e` and `under_load` call it. Every
# step writes to the run's evidence folder (DRILL_EVIDENCE), which holds the secrets too: it stays on the machine.
#
# The owner's machine only, in a window when nothing else uses its Docker (README.md, "What the machine needs").
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
# shellcheck source=versions.env
source "$here/versions.env"

cluster=wow-rc-drill
namespace=wow-rc-drill
gateway=${DRILL_GATEWAY:-http://127.0.0.1:30080}
evidence=${DRILL_EVIDENCE:-$root/build/rc-drill/run}
# The release's source, a worktree of its tag: the eShop's servers and every service's generated values come from it.
sources=${DRILL_SOURCES:-$root/build/rc-drill/sources}

# service | its Gradle server project | its module's directory, where generateWowHelmValues writes build/wow/helm/values.yaml
SERVICES=(
  "eshop-catalog|:eshop-catalog-server|examples/eshop/catalog/catalog-server"
  "eshop-basket|:eshop-basket-server|examples/eshop/basket/basket-server"
  "eshop-ordering|:eshop-ordering-server|examples/eshop/ordering/ordering-server"
  "eshop-inventory|:eshop-inventory-server|examples/eshop/inventory/inventory-server"
  "eshop-payment|:eshop-payment-server|examples/eshop/payment/payment-server"
  "ops|:ops-server|platform/ops/ops-server"
  "view-store|:view-store-server|platform/view-store/view-store-server"
  "analytics-catalog|:analytics-catalog-server|platform/analytics/catalog/server"
)
ESHOP=(eshop-catalog eshop-basket eshop-ordering eshop-inventory eshop-payment)
PLATFORM_IMAGES=(wow-ops-server wow-view-store-server wow-analytics-catalog-server wow-migrate-data)

# The waves, as the GA end-to-end run's: the platform first (ops records the ledgers), then the contexts others call
# or send to, then the ordering, and the basket, which calls the ordering, last. An upgrade goes in the same order.
WAVES=(
  "ops view-store analytics-catalog"
  "eshop-catalog eshop-inventory eshop-payment"
  "eshop-ordering"
  "eshop-basket"
)

# The registries a release publishes to (image-deploy.yml, chart-deploy.yml), as <images>|<charts>.
REGISTRIES=(
  "docker.io/ahoowang|oci://registry-1.docker.io/ahoowang"
  "ghcr.io/ahoo-wang|oci://ghcr.io/ahoo-wang"
  "registry.cn-shanghai.aliyuncs.com/ahoo|oci://registry.cn-shanghai.aliyuncs.com/ahoo"
)
CHART=oci://ghcr.io/ahoo-wang/wow-service

log() { echo "[rc-drill $(date -u +%H:%M:%S)] $*" >&2; }
fail() {
  log "FAILED: $*"
  return 1
}
k() { kubectl --context "kind-$cluster" -n "$namespace" "$@"; }

# Runs a step or an operation (a function of this script) in a process of its own, so that errexit holds inside it: a
# function called where its status is tested (cmd || ...) runs with errexit off, every command after a failing one
# included, and so does a subshell or a background job of it. The evidence folder goes with it.
checked() {
  DRILL_EVIDENCE="$evidence" DRILL_SOURCES="$sources" "$here/drill.sh" "$@"
}

# Times a step and keeps its output: evidence/steps/<name>.log, and one line per step in evidence/steps.tsv.
step() {
  local name=$1 started status=0
  shift
  mkdir -p "$evidence/steps"
  started=$(date -u +%s)
  log "step $name: $*"
  checked "$@" > >(tee "$evidence/steps/$name.log") 2>&1 || status=$?
  printf '%s\t%s\t%s\t%s\n' "$name" "$started" "$(date -u +%s)" "$status" >>"$evidence/steps.tsv"
  [ "$status" = 0 ] || fail "step $name ($status)"
}

entry() {
  local name=$1 entry
  for entry in "${SERVICES[@]}"; do
    [ "${entry%%|*}" = "$name" ] && echo "$entry" && return 0
  done
  fail "no service $name"
}

is_eshop() {
  local name=$1 s
  for s in "${ESHOP[@]}"; do [ "$s" = "$name" ] && return 0; done
  return 1
}

# region 0: artifacts

# A registry's manifest of an image, anonymously: the token each registry hands for a public pull, then the index.
manifest() {
  local registry=$1 repository=$2 tag=$3 token="" host auth
  local accept='application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json'
  case "$registry" in
    docker.io/*)
      host=registry-1.docker.io
      auth="https://auth.docker.io/token?service=registry.docker.io&scope=repository:${registry#docker.io/}/$repository:pull"
      ;;
    ghcr.io/*)
      host=ghcr.io
      auth="https://ghcr.io/token?scope=repository:${registry#ghcr.io/}/$repository:pull"
      ;;
    registry.cn-shanghai.aliyuncs.com/*)
      host=registry.cn-shanghai.aliyuncs.com
      auth="https://dockerauth.cn-hangzhou.aliyuncs.com/auth?service=registry.aliyuncs.com:cn-shanghai:26842&scope=repository:${registry#*/}/$repository:pull"
      ;;
  esac
  token=$(curl -fsS "$auth" | jq -r '.token // .access_token')
  curl -fsS -H "Authorization: Bearer $token" -H "Accept: $accept" -D "$evidence/artifacts/headers.tmp" \
    "https://$host/v2/${registry#*/}/$repository/manifests/$tag"
}

artifacts() {
  local version=$1 entry image registries charts digest digests platforms chart pom package ok=0
  mkdir -p "$evidence/artifacts"
  for image in "${PLATFORM_IMAGES[@]}"; do
    digests=()
    for entry in "${REGISTRIES[@]}"; do
      registries=${entry%%|*}
      if ! manifest "$registries" "$image" "$version" >"$evidence/artifacts/$image.json"; then
        log "missing: $registries/$image:$version"
        ok=1
        continue
      fi
      digest=$(grep -i '^docker-content-digest:' "$evidence/artifacts/headers.tmp" | tr -d '\r' | awk '{print $2}')
      platforms=$(jq -r '[.manifests[]? | select(.platform.os == "linux") | .platform.architecture] | sort | join(",")' \
        "$evidence/artifacts/$image.json")
      log "$registries/$image:$version $digest [$platforms]"
      [ "$platforms" = "amd64,arm64" ] || { log "not amd64 and arm64: $registries/$image"; ok=1; }
      digests+=("$digest")
    done
    # Bash 3.2 (macOS's) takes an empty array for an unset one: ${a[@]+...} expands it only when it has elements.
    [ "$(printf '%s\n' ${digests[@]+"${digests[@]}"} | sort -u | wc -l | tr -d ' ')" = 1 ] ||
      { log "the registries differ for $image: ${digests[*]-none}"; ok=1; }
  done
  mkdir -p "$evidence/artifacts/charts"
  for entry in "${REGISTRIES[@]}"; do
    charts=${entry#*|}
    chart="$evidence/artifacts/charts/${charts//[^a-z0-9]/_}"
    mkdir -p "$chart"
    if helm pull "$charts/wow-service" --version "$version" --untar --untardir "$chart" >/dev/null; then
      log "chart $charts/wow-service $version: $(grep -E '^(version|appVersion):' "$chart/wow-service/Chart.yaml" | tr '\n' ' ')"
      grep -qx "version: $version" "$chart/wow-service/Chart.yaml" &&
        grep -Eqx "appVersion: \"?$version\"?" "$chart/wow-service/Chart.yaml" ||
        { log "the chart's version is not $version"; ok=1; }
    else
      log "missing: $charts/wow-service $version"
      ok=1
    fi
  done
  for pom in wow-bom wow-api wow-runtime wow-spring-boot wow-mongo wow-elasticsearch wow-kafka; do
    if curl -fsS -o /dev/null "https://repo1.maven.org/maven2/me/ahoo/wow/$pom/$version/$pom-$version.pom"; then
      log "Maven Central me.ahoo.wow:$pom:$version"
    else
      log "missing: me.ahoo.wow:$pom:$version on Maven Central"
      ok=1
    fi
  done
  for package in wow-client wow-react wow-generator wow-view-engine wow-view-store; do
    if curl -fsS "https://registry.npmjs.org/@ahoo-wang%2f$package/$version" >/dev/null; then
      log "npm @ahoo-wang/$package@$version"
    else
      log "missing: @ahoo-wang/$package@$version on npm"
      ok=1
    fi
  done
  rm -f "$evidence/artifacts/headers.tmp"
  return "$ok"
}

# endregion
# region the upgrade's decision

# What changed between two releases that a running deployment cannot cross, for the session that decides step 4: the
# stored formats (the stores' schemas and documents), the cluster channel and the internal port's protocol, and the
# configuration (the keys a node reads, which the metadata lists). The list is the evidence; the decision, rolling or
# restart, is the drill's to state in its report.
compare() {
  local from=$1 to=$2 out
  out="$evidence/compare-$from-$to"
  mkdir -p "$out"
  git -C "$root" fetch -q origin "refs/tags/v$from:refs/tags/v$from" "refs/tags/v$to:refs/tags/v$to"
  git -C "$root" diff --stat "v$from" "v$to" -- \
    ':(glob)backends/*/src/main/**' ':(glob)core/spi/src/main/**' >"$out/stores.txt"
  git -C "$root" diff --stat "v$from" "v$to" -- \
    ':(glob)core/runtime/src/main/kotlin/me/ahoo/wow/runtime/cluster/**' ':(glob)edge/http/src/main/**/internal/**' \
    >"$out/channel.txt"
  git -C "$root" diff "v$from" "v$to" -- \
    'host/spring-boot/src/main/resources/META-INF/spring-configuration-metadata.json' \
    'host/spring-boot/src/main/resources/META-INF/additional-spring-configuration-metadata.json' >"$out/configuration.diff"
  git -C "$root" log --oneline "v$from..v$to" >"$out/commits.txt"
  log "compare $from..$to: $(wc -l <"$out/commits.txt" | tr -d ' ') commits; stores, channel and configuration in $out"
}

# endregion
# region 1: the cluster and the platform

cluster() {
  if kind get clusters | grep -qx "$cluster"; then
    log "cluster $cluster exists"
  else
    kind create cluster --config "$here/kind.yaml" --wait 0s
  fi
  curl -fsSLo "$evidence/calico.yaml" "https://raw.githubusercontent.com/projectcalico/calico/$CALICO_VERSION/manifests/calico.yaml"
  echo "$CALICO_SHA256  $evidence/calico.yaml" | shasum -a 256 -c -
  kubectl --context "kind-$cluster" apply -f "$evidence/calico.yaml" >/dev/null
  kubectl --context "kind-$cluster" -n kube-system rollout status daemonset/calico-node --timeout=600s
  kubectl --context "kind-$cluster" wait --for=condition=Ready nodes --all --timeout=600s
  kubectl --context "kind-$cluster" create namespace "$namespace" --dry-run=client -o yaml |
    kubectl --context "kind-$cluster" apply -f -
  # The cluster test runs kubectl in the context's namespace.
  kubectl config set-context "kind-$cluster" --namespace "$namespace" >/dev/null
  kubectl --context "kind-$cluster" get nodes -L topology.kubernetes.io/zone
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

# endregion
# region 2: the release's build and its install

# The release's source at its tag, in a worktree of its own, never this checkout.
checkout() {
  local version=$1 dir="$sources/$1"
  if [ ! -d "$dir/.git" ] && [ ! -f "$dir/.git" ]; then
    git -C "$root" fetch -q origin "refs/tags/v$version:refs/tags/v$version"
    git -C "$root" worktree add --detach "$dir" "v$version" >/dev/null
  fi
  echo "$dir"
}

# The eShop's five servers built from the release's source against the Wow its version published on Maven Central
# (eshop-build.sh), their images built with deploy/'s Dockerfiles and loaded into every node; and the values every
# service's build generates (generateWowHelmValues), the platform's three from the release's source as it is.
build() {
  local version=$1 src name entry project module tasks=()
  src=$(checkout "$version")
  [ -x "$here/eshop-build.sh" ] || fail "eshop-build.sh is missing: the eShop's build against the published Wow"
  "$here/eshop-build.sh" "$src" "$version"
  for name in "${ESHOP[@]}"; do
    entry=$(entry "$name")
    module=${entry##*|}
    docker build -q -f "$src/deploy/$name/Dockerfile" -t "wow-$name-server:$version" "$src/$module"
  done
  load "$version"
  for name in ops view-store analytics-catalog; do
    entry=$(entry "$name")
    project=$(cut -d'|' -f2 <<<"$entry")
    tasks+=("$project:generateWowHelmValues")
  done
  "$src/gradlew" -p "$src" --quiet "${tasks[@]}"
}

# The eShop's images of a version, into every node of the cluster (a cluster made again needs them again).
load() {
  local version=$1 name
  for name in "${ESHOP[@]}"; do
    kind load docker-image --name "$cluster" "wow-$name-server:$version"
  done
}

# One service: a release of the published chart with, in order, the values its build generated, deploy/'s, and the
# drill's (values/<service>.yaml); an eShop service takes the image the drill built for the version.
service() {
  local name=$1 version=$2 src entry module values=()
  src="$sources/$version"
  entry=$(entry "$name")
  module=${entry##*|}
  values+=(-f "$src/$module/build/wow/helm/values.yaml" -f "$src/deploy/$name/values.yaml")
  [ ! -f "$here/values/$name.yaml" ] || values+=(-f "$here/values/$name.yaml")
  is_eshop "$name" && values+=(--set "image.tag=$version")
  helm --kube-context "kind-$cluster" -n "$namespace" upgrade --install "$name" "$CHART" --version "$version" \
    "${values[@]}"
}

install() {
  local version=$1 wave name
  for wave in "${WAVES[@]}"; do
    for name in $wave; do service "$name" "$version" >/dev/null; done
    for name in $wave; do k rollout status "deployment/$name-service" --timeout=900s; done
  done
  k get pods -o wide
}

# endregion
# region 3 to 9: the end-to-end suite and the operations under load

# The cluster test through the ingress, with the run's JWT secret and the deployment's partitions (deploy/: 64), in
# the drill's namespace, its data under the run's prefix. <scenarios> selects what it runs (the cluster test's
# ga.scenarios); its report goes to evidence/e2e/<label>.
e2e() {
  local scenarios=$1 label=${2:-$1} extra=("${@:3}")
  mkdir -p "$evidence/e2e/$label"
  "$root/gradlew" -p "$root" :eshop-e2e:clusterTest --console=plain \
    "-Pga.gateway=$gateway" "-Pga.secret=$(cat "$evidence/secrets/JWT")" -Pga.backend=mongo -Pga.partitions=64 \
    "-Pga.namespace=$namespace" "-Pga.run=$(basename "$evidence")" "-Pga.scenarios=$scenarios" \
    "-Pga.report=$evidence/e2e/$label" ${extra[@]+"${extra[@]}"}
}

# An operation run while the cluster test's load runs: the load starts, the operation runs, the load is told to stop
# (its stop file), and the test judges what the load saw: every fault window within <bound>, and the verdict.
under_load() {
  local label=$1 bound=$2 stop
  shift 2
  stop="$evidence/e2e/$label.stop"
  rm -f "$stop"
  e2e load "$label" "-Pga.load.stop-file=$stop" "-Pga.load.bound=$bound" &
  local load=$!
  # The load's first answered order, before the operation.
  for _ in $(seq 1 120); do [ -f "$evidence/e2e/$label/load.started" ] && break; sleep 1; done
  [ -f "$evidence/e2e/$label/load.started" ] || { kill "$load"; fail "the load of $label did not start"; }
  local status=0
  checked "$@" || status=$?
  touch "$stop"
  wait "$load" || status=$?
  return "$status"
}

upgrade() {
  local from=$1 to=$2 mode=$3
  case "$mode" in
    rolling) under_load upgrade PT60S roll "$to" ;;
    restart) under_load restart PT60S roll_restart ;;
    *) fail "upgrade mode: rolling or restart" ;;
  esac
  log "step 4 was a $mode (from $from to $to): $evidence/compare-$from-$to holds why"
}

# The rolling upgrade itself: each wave's services upgraded to the release's chart and images, one service at a time,
# each until its rollout is done (maxSurge 1, maxUnavailable 0: the old pods end once the new are ready).
roll() {
  local version=$1 wave name
  for wave in "${WAVES[@]}"; do
    for name in $wave; do
      service "$name" "$version" >/dev/null
      k rollout status "deployment/$name-service" --timeout=900s
    done
  done
}

# The same version, rolled: a fresh install of the new release cannot take its rolling upgrade, so the rolling
# mechanics are exercised by a restart of every service, wave by wave.
roll_restart() {
  local wave name
  for wave in "${WAVES[@]}"; do
    for name in $wave; do
      k rollout restart "deployment/$name-service"
      k rollout status "deployment/$name-service" --timeout=900s
    done
  done
}

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

# endregion
# region 10 to 12: alerts, backup, reinstall

# Step 10: every alert that fired over the run (Prometheus' ALERTS series), with when, for the report to set against
# the steps' windows (steps.tsv).
alerts() {
  local forward start
  k port-forward service/prometheus 19090:9090 >/dev/null 2>&1 &
  forward=$!
  sleep 3
  start=$(head -1 "$evidence/steps.tsv" | cut -f2)
  curl -fsS "http://127.0.0.1:19090/api/v1/query_range" --data-urlencode 'query=ALERTS{alertstate="firing"}' \
    --data-urlencode "start=$start" --data-urlencode "end=$(date -u +%s)" --data-urlencode step=15s \
    >"$evidence/alerts.json" || { kill "$forward"; return 1; }
  curl -fsS "http://127.0.0.1:19090/api/v1/rules" >"$evidence/rules.json"
  kill "$forward" 2>/dev/null || true
  jq -r '.data.result[] | [.metric.alertname, (.metric.service_name // .metric.pod // ""),
    (.values | first | .[0] | todate), (.values | last | .[0] | todate)] | @tsv' "$evidence/alerts.json"
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

# endregion
# region evidence and the run

diagnostics() {
  local out="$evidence/diagnostics" pod container
  mkdir -p "$out/logs" "$out/threads"
  k get all,networkpolicy,poddisruptionbudget,configmap,pvc -o wide >"$out/resources.txt" 2>&1 || true
  helm --kube-context "kind-$cluster" -n "$namespace" list -a >"$out/releases.txt" 2>&1 || true
  k get events --sort-by=.lastTimestamp >"$out/events.txt" 2>&1 || true
  k describe pods >"$out/pods.txt" 2>&1 || true
  kubectl --context "kind-$cluster" get nodes -o wide -L topology.kubernetes.io/zone >"$out/nodes.txt" 2>&1 || true
  for pod in $(k get pods -o name 2>/dev/null); do
    pod=${pod#pod/}
    for container in $(k get pod "$pod" -o jsonpath='{.spec.containers[*].name}'); do
      k logs "$pod" -c "$container" >"$out/logs/$pod.$container.log" 2>&1 || true
      k logs "$pod" -c "$container" --previous >"$out/logs/$pod.$container.previous.log" 2>/dev/null ||
        rm -f "$out/logs/$pod.$container.previous.log"
    done
    k exec "$pod" -- jcmd 1 Thread.dump_to_file -overwrite -format=json /tmp/wow-threads.json >/dev/null 2>&1 &&
      k exec "$pod" -- cat /tmp/wow-threads.json >"$out/threads/$pod.json" 2>/dev/null ||
      rm -f "$out/threads/$pod.json"
  done
}

teardown() {
  kind delete cluster --name "$cluster"
}

run() {
  local version=$1 previous=${2:-} first mode
  first=${previous:-$version}
  mkdir -p "$evidence"
  : >"$evidence/steps.tsv"
  step 0-artifacts artifacts "$version"
  [ -z "$previous" ] || step 0-artifacts-previous artifacts "$previous"
  step 1-cluster cluster
  step 1-secrets secrets
  step 1-platform platform "$first"
  step 2-build build "$first"
  step 2-install install "$first"
  step 3-e2e e2e functional
  if [ -n "$previous" ]; then
    step 4-compare compare "$previous" "$version"
    # The decision is the drill's (README.md): DRILL_UPGRADE=rolling when nothing in the comparison stops a running
    # deployment from crossing it, restart otherwise; the run refuses to guess.
    mode=${DRILL_UPGRADE:?DRILL_UPGRADE=rolling|restart, from the comparison of $previous and $version}
    step 4-build build "$version"
    [ "$mode" != restart ] || step 4-fresh fresh "$version"
    step 4-upgrade upgrade "$previous" "$version" "$mode"
  else
    step 4-restart upgrade "$version" "$version" restart
  fi
  step 5-node-loss node-loss
  step 6-store-faults store-faults
  step 7-scale scale
  step 8-drain drain
  step 9-ops e2e ops
  step 10-alerts alerts
  step 11-backup backup
  step 12-reinstall reinstall "$version"
  step diagnostics diagnostics
  log "done: $evidence"
}

# A fresh install of a release on empty stores, when the upgrade cannot cross from the one before (step 4, restart): the
# cluster made again, with its stores' volumes, the run's secrets, the platform, the version's images and the release.
fresh() {
  local version=$1
  teardown
  cluster
  secrets
  platform "$version"
  load "$version"
  install "$version"
}

# endregion

command=${1:?usage: drill.sh artifacts|compare|cluster|secrets|platform|build|install|e2e|upgrade|node-loss|store-faults|scale|drain|alerts|backup|reinstall|diagnostics|teardown|run ...}
shift
mkdir -p "$evidence"
"$command" "$@"
