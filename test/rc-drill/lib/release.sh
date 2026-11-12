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
# drill.sh's release: the artifacts a release published (step 0), the comparison of two (step 4's decision), their
# build, install and upgrade (steps 2 and 4), sourced by drill.sh: its settings and helpers are in scope.

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
