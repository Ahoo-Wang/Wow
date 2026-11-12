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
# drill.sh's verdict: the end-to-end suite through the ingress, the load it judges an operation under (steps 3 to 9),
# and the alerts' history (step 10), sourced by drill.sh: its settings and helpers are in scope.

# The cluster test through the ingress, with the run's JWT secret and the deployment's partitions (deploy/: 64), in
# the drill's namespace, its data under the run's prefix. <scenarios> selects what it runs (the cluster test's
# ga.scenarios); its report goes to evidence/e2e/<label>.
e2e() {
  local scenarios=$1 label=${2:-$1} extra=("${@:3}")
  mkdir -p "$evidence/e2e/$label"
  # The test's own kubectl is the one first on its path: the drill's, whatever the shell's path says.
  PATH="$(dirname "$kubectl"):$PATH" "$root/gradlew" -p "$root" :eshop-e2e:clusterTest --console=plain \
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
