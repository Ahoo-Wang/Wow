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

# The wow-service chart's checks (deploy/helm/wow-service), driven by ci.yml with Helm on the path (azure/setup-helm):
#
#   chart.sh tools       helm-unittest and kubeconform, each checked against its SHA-256
#   chart.sh check       helm lint --strict of every CI value set (ci/*.yaml), the unit tests (tests/), the schema's
#                        refusals, and every rendered set validated by kubeconform, the CRDs' schemas included
#   chart.sh umbrella    the chart twice in an umbrella chart (.github/helm/umbrella), with the umbrella's global values
#   chart.sh smoke       an install on the kind cluster helm/kind-action created, with the eShop catalog's image built
#                        from the commit (wow-eshop-catalog-server:local): the rollout, the SRV records of the internal
#                        port, the disruption budget and a rolling restart
#
# GitHub Actions only, as .github/ga-e2e/cluster.sh: it installs tools and creates objects on the machine it runs on.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
chart="$root/deploy/helm/wow-service"
bin="${RUNNER_TEMP:-/tmp}/helm-tools"

# The helm-unittest plugin: the release's archive for Linux on amd64, as its helm-unittest-checksum.sha lists it.
UNITTEST_VERSION=1.2.1
UNITTEST_SHA256=48e86ecc1467b009630e5954375e5d8494139f73b466df5fe8c04fa45b634939
KUBECONFORM_VERSION=0.8.0
KUBECONFORM_SHA256=9bc2bffbf71f261128533edaf912153948b7ff238f9a531ae6d34466ec287883
# The schemas the rendered objects are held to: Kubernetes' oldest version the chart supports (Chart.yaml's
# kubeVersion) and kind's node, and the CRDs' (ServiceMonitor, PodMonitor, ScaledObject) at a pinned commit.
KUBERNETES_VERSIONS="1.27.16 1.37.0"
CRDS_CATALOG=63669a570e231d4f1f8396d229a1de512bcf0a34

log() { echo "[chart] $*"; }

tools() {
  local archive="$bin/helm-unittest.tgz" plugin
  plugin="$(helm env HELM_PLUGINS)/unittest"
  mkdir -p "$bin" "$plugin"
  curl -fsSLo "$archive" \
    "https://github.com/helm-unittest/helm-unittest/releases/download/v$UNITTEST_VERSION/helm-unittest-linux-amd64-$UNITTEST_VERSION.tgz"
  echo "$UNITTEST_SHA256  $archive" | sha256sum --check --strict
  # The archive holds the plugin whole (its plugin.yaml and binary): unpacked into Helm's plugin directory, not through
  # `helm plugin install`, whose install hook would download the binary again, unchecked.
  tar -xzf "$archive" -C "$plugin"
  curl -fsSLo "$bin/kubeconform.tar.gz" \
    "https://github.com/yannh/kubeconform/releases/download/v$KUBECONFORM_VERSION/kubeconform-linux-amd64.tar.gz"
  echo "$KUBECONFORM_SHA256  $bin/kubeconform.tar.gz" | sha256sum --check --strict
  tar -xzf "$bin/kubeconform.tar.gz" -C "$bin" kubeconform
  helm version --short
  helm unittest --help >/dev/null
  "$bin/kubeconform" -v
}

# The rendered objects on stdin, held to the schemas of every version of KUBERNETES_VERSIONS.
validate() {
  local rendered version
  rendered=$(cat)
  for version in $KUBERNETES_VERSIONS; do
    "$bin/kubeconform" -strict -summary -kubernetes-version "$version" \
      -schema-location default \
      -schema-location "https://raw.githubusercontent.com/datreeio/CRDs-catalog/$CRDS_CATALOG/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json" \
      <<<"$rendered"
  done
}

# Fails unless rendering the chart with [arguments] fails with a message matching [pattern].
refused() {
  local pattern=$1 out
  shift
  if out=$(helm template refused "$chart" -f "$chart/ci/minimal-values.yaml" "$@" 2>&1); then
    log "::error::helm template $* rendered, but should have been refused ($pattern)"
    return 1
  fi
  grep -qE "$pattern" <<<"$out" || {
    log "::error::helm template $* was refused, but not for $pattern: $out"
    return 1
  }
  log "refused as expected: $*"
}

check() {
  local values
  for values in "$chart"/ci/*.yaml; do
    log "lint and render $(basename "$values")"
    helm lint --strict "$chart" -f "$values"
    helm template "$(basename "$values" -values.yaml)" "$chart" --namespace shop -f "$values" | validate
  done
  helm unittest --strict "$chart"
  # The schema refuses a key the chart does not know, at any depth, and a value of the wrong type.
  refused "replicaz" --set replicaz=3
  refused "pullPolicy" --set image.pullPolicy=Sometimes
  refused "shutdwn|additional" --set shutdwn.marginSeconds=5
  refused "maxSkew" --set topologySpread.maxSkew=zero
  refused "both true" --set autoscaling.hpa.enabled=true --set autoscaling.keda.enabled=true \
    --set autoscaling.keda.prometheus.serverAddress=http://prometheus:9090
}

umbrella() {
  local dir="$here/umbrella" rendered
  helm dependency build "$dir"
  helm lint --strict "$dir"
  rendered=$(helm template shop "$dir" --namespace shop)
  validate <<<"$rendered"
  # Each alias renders its service's objects, named after the service, never after the release or the alias.
  for name in eshop-ordering-service eshop-basket-service; do
    grep -q "^  name: $name$" <<<"$rendered" || {
      log "::error::the umbrella rendered no object named $name"
      return 1
    }
  done
}

smoke() {
  local name=eshop-catalog-service forward records
  kind load docker-image --name wow-chart wow-eshop-catalog-server:local
  helm install catalog "$chart" -f "$chart/ci/kind-values.yaml" --wait --timeout 10m
  kubectl get deployment,service,networkpolicy,poddisruptionbudget,configmap,serviceaccount -o wide
  # The headless Service lists both nodes as the peers find them: SRV records of the internal port, by the
  # namespace-qualified name the nodes' default template gives.
  kubectl -n kube-system port-forward service/kube-dns 5353:53 >/dev/null 2>&1 &
  forward=$!
  sleep 3
  records=$(dig +tcp -p 5353 @127.0.0.1 +short "_internal._tcp.$name-cluster.default.svc.cluster.local" SRV || true)
  kill "$forward" 2>/dev/null || true
  log "SRV records: $records"
  [ "$(grep -c " 9091 " <<<"$records")" = 2 ] || {
    log "::error::expected two SRV records of port 9091 for $name-cluster, got: $records"
    return 1
  }
  kubectl get poddisruptionbudget "$name" -o jsonpath='{.status.disruptionsAllowed}' | grep -qx 1
  # A rolling restart replaces the nodes one at a time and settles, every new node ready before an old one goes.
  kubectl rollout restart "deployment/$name"
  kubectl rollout status "deployment/$name" --timeout=10m
  helm uninstall catalog --wait
}

command=${1:?usage: chart.sh tools|check|umbrella|smoke}
shift
"$command" "$@"
