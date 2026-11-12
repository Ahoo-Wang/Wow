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
#   drill.sh tools                           0  the tools' paths and versions, refused unless README.md's
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
#
# The steps are in lib/, by what they are: common.sh (the settings, the helpers, the evidence), platform.sh (the cluster
# and the platform), release.sh (the artifacts, the build, the install and the upgrade), faults.sh (the faults and the
# operations under load) and verdict.sh (the end-to-end suite and the alerts). This file sources them and dispatches.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
# shellcheck source=versions.env
source "$here/versions.env"
for part in common platform release faults verdict; do
  # shellcheck source=/dev/null
  source "$here/lib/$part.sh"
done

run() {
  local version=$1 previous=${2:-} first mode
  first=${previous:-$version}
  mkdir -p "$evidence"
  : >"$evidence/steps.tsv"
  step 0-tools tools
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

command=${1:?usage: drill.sh tools|artifacts|compare|cluster|secrets|platform|build|install|e2e|upgrade|node-loss|store-faults|scale|drain|alerts|backup|reinstall|diagnostics|teardown|run ...}
shift
mkdir -p "$evidence"
"$command" "$@"
