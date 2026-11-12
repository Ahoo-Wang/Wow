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

# The eShop's five servers built from a release's source against the Wow that release published on Maven Central, as
# a user's service is built: drill.sh build calls it with the source (a worktree of the release's tag) and its version.
#
#   eshop-build.sh <source> <version>
#
# The release's own build, with published-wow.init.gradle.kts: the eShop's me.ahoo.wow dependencies resolve to the
# published modules, everything else is the release's. It runs each server's installDist, which deploy/'s Dockerfiles
# copy, and generateWowHelmValues, which writes build/wow/helm/values.yaml. Then it proves where Wow came from: every
# wow-*-<version>.jar in each distribution must have the SHA-1 Maven Central publishes for it. The root build's
# repositories put mavenLocal() first, so a local copy that differs from Central's fails here.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
src=$(cd "${1:?usage: eshop-build.sh <source> <version>}" && pwd)
version=${2:?usage: eshop-build.sh <source> <version>}
central=https://repo1.maven.org/maven2/me/ahoo/wow

log() { echo "[eshop-build] $*" >&2; }

servers=(eshop-catalog-server eshop-basket-server eshop-ordering-server eshop-inventory-server eshop-payment-server)
tasks=()
for server in "${servers[@]}"; do
  tasks+=(":$server:installDist" ":$server:generateWowHelmValues")
done
log "building ${servers[*]} of $src against Wow $version from Maven Central"
"$src/gradlew" -p "$src" --console=plain -I "$here/published-wow.init.gradle.kts" "-Prc-drill.wow-version=$version" \
  "${tasks[@]}"

checked=0
for server in "${servers[@]}"; do
  # A server's module is examples/eshop/<context>/<context>-server; its distribution is named after the project.
  lib=$(find "$src/examples/eshop" -type d -path "*/build/install/$server/lib" | head -1)
  [ -n "$lib" ] || { log "no distribution of $server"; exit 1; }
  values="${lib%/build/install/*}/build/wow/helm/values.yaml"
  [ -f "$values" ] || { log "no generated values for $server: $values"; exit 1; }
  jars=0
  for jar in "$lib"/wow-*-"$version".jar; do
    [ -e "$jar" ] || continue
    artifact=$(basename "$jar" "-$version.jar")
    expected=$(curl -fsS "$central/$artifact/$version/$artifact-$version.jar.sha1" | awk '{print $1}')
    actual=$(shasum -a 1 "$jar" | awk '{print $1}')
    if [ "$expected" != "$actual" ]; then
      log "$server: $artifact-$version.jar is not Maven Central's ($actual, Central has $expected)"
      exit 1
    fi
    jars=$((jars + 1))
  done
  [ "$jars" -gt 0 ] || { log "$server has no wow-*-$version.jar: Wow did not come from the release"; exit 1; }
  # A jar of Wow's built from the source carries no version of the release's, or another one: none may be there.
  if find "$lib" -name 'wow-*.jar' ! -name "wow-*-$version.jar" | grep -q .; then
    log "$server: Wow jars of another version: $(find "$lib" -name 'wow-*.jar' ! -name "wow-*-$version.jar" | xargs -n1 basename)"
    exit 1
  fi
  log "$server: $jars Wow jars, each Maven Central's"
  checked=$((checked + jars))
done
log "done: $checked Wow jars across ${#servers[@]} servers, all from Maven Central's $version"
