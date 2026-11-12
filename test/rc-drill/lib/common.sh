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
# drill.sh's settings, its helpers and its evidence keeping, sourced first by drill.sh: what every part uses.

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

# The kubectl the drill runs, resolved once: DRILL_KUBECTL, else the first on the path. A shell started before the path
# changed may find another first (OrbStack's own, older), so a run can name it (README.md, "Tools").
kubectl=${DRILL_KUBECTL:-$(command -v kubectl || true)}
k() { "$kubectl" --context "kind-$cluster" -n "$namespace" "$@"; }

# The JDK everything the drill launches runs on, 25 (DRILL_JAVA_HOME, else macOS's java_home for it): every Gradle it
# runs runs on it, and the cluster test's JVM is started on it (-Pwow.test.java). The build still compiles with its 21.
run_jdk=${DRILL_JAVA_HOME:-$(/usr/libexec/java_home -v "$RUN_JDK" 2>/dev/null || true)}
[ -z "$run_jdk" ] || export JAVA_HOME="$run_jdk" PATH="$run_jdk/bin:$PATH"

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

# Step 0's first check, before any cluster exists: each tool's path and version into evidence/tools.txt, and a
# refusal of any that is not the version README.md names (helm, kind, kubectl within one minor of the nodes', both
# JDKs: the build's 21 and the 25 that runs). Docker is recorded, not checked: it is OrbStack's.
tools() {
  local out="$evidence/tools.txt" version minor ok=0
  : >"$out"
  record() { printf '%-8s %-40s %s\n' "$1" "$2" "$3" | tee -a "$out" >&2; }
  version=$(helm version --short 2>/dev/null | cut -d+ -f1)
  record helm "$(command -v helm || echo missing)" "$version"
  [ "$version" = "$HELM_VERSION" ] || { log "helm is ${version:-missing}, not $HELM_VERSION"; ok=1; }
  version=$(kind version 2>/dev/null | awk '{print $2}')
  record kind "$(command -v kind || echo missing)" "$version"
  [ "$version" = "$KIND_VERSION" ] || { log "kind is ${version:-missing}, not $KIND_VERSION"; ok=1; }
  version=$("$kubectl" version --client -o json 2>/dev/null | jq -r '.clientVersion.gitVersion // empty')
  record kubectl "${kubectl:-missing}" "$version"
  minor=$(cut -d. -f2 <<<"$version")
  case " $KUBECTL_MINORS " in
    *" $minor "*) ;;
    *) log "kubectl is ${version:-missing}, not 1.${KUBECTL_MINORS// /, 1.}: set DRILL_KUBECTL"; ok=1 ;;
  esac
  local jdk home
  for jdk in "$BUILD_JDK" "$RUN_JDK"; do
    home=$(/usr/libexec/java_home -v "$jdk" 2>/dev/null || true)
    [ "$jdk" != "$RUN_JDK" ] || home=${run_jdk:-$home}
    version=$([ -n "$home" ] && "$home/bin/java" -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')
    record "jdk$jdk" "${home:-missing}" "${version:-} ($([ "$jdk" = "$RUN_JDK" ] && echo runs || echo builds))"
    [ "$version" = "$jdk" ] || { log "no JDK $jdk: the $([ "$jdk" = "$RUN_JDK" ] && echo one that runs || echo build\'s toolchain)"; ok=1; }
  done
  record docker "$(command -v docker || echo missing)" "$(docker version --format '{{.Server.Version}}' 2>/dev/null)"
  return "$ok"
}

diagnostics() {
  local out="$evidence/diagnostics" pod container
  mkdir -p "$out/logs" "$out/threads"
  k get all,networkpolicy,poddisruptionbudget,configmap,pvc -o wide >"$out/resources.txt" 2>&1 || true
  helm --kube-context "kind-$cluster" -n "$namespace" list -a >"$out/releases.txt" 2>&1 || true
  k get events --sort-by=.lastTimestamp >"$out/events.txt" 2>&1 || true
  k describe pods >"$out/pods.txt" 2>&1 || true
  "$kubectl" --context "kind-$cluster" get nodes -o wide -L topology.kubernetes.io/zone >"$out/nodes.txt" 2>&1 || true
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
