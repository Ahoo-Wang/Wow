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
  is_local "$version" && fail "nothing is published for $version: build builds its images and packages its chart"
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
    [ -n "$charts" ] || continue
    # A folder per version too: the run checks the previous release's chart into the same evidence after the new one's.
    chart="$evidence/artifacts/charts/${charts//[^a-z0-9]/_}/$version"
    # Pulled again into an empty folder: helm refuses to untar over a chart a run before pulled there.
    rm -rf "$chart"
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
  local from=$1 to=$2 out a b
  out="$evidence/compare-$(label "$from")-$(label "$to")"
  mkdir -p "$out"
  fetch "$from"
  fetch "$to"
  a=$(ref "$from")
  b=$(ref "$to")
  git -C "$root" diff --stat "$a" "$b" -- \
    ':(glob)backends/*/src/main/**' ':(glob)core/spi/src/main/**' >"$out/stores.txt"
  git -C "$root" diff --stat "$a" "$b" -- \
    ':(glob)core/runtime/src/main/kotlin/me/ahoo/wow/runtime/cluster/**' ':(glob)edge/http/src/main/**/internal/**' \
    >"$out/channel.txt"
  git -C "$root" diff "$a" "$b" -- \
    'host/spring-boot/src/main/resources/META-INF/spring-configuration-metadata.json' \
    'host/spring-boot/src/main/resources/META-INF/additional-spring-configuration-metadata.json' >"$out/configuration.diff"
  # What a deployment of the two runs on: the chart, deploy/'s values and images, the drill's platform of the GA run.
  git -C "$root" diff "$a" "$b" -- deploy .github/ga-e2e/gateway .github/ga-e2e/backends >"$out/deployment.diff"
  git -C "$root" log --oneline "$a..$b" >"$out/commits.txt"
  log "compare $from..$to: $(wc -l <"$out/commits.txt" | tr -d ' ') commits; stores, channel, configuration and deployment in $out"
}

# A version's source in this repository: a release's tag, or a commit (fetched by its SHA when it is not here yet).
fetch() {
  local version=$1
  if is_local "$version"; then
    git -C "$root" cat-file -e "$version^{commit}" 2>/dev/null || git -C "$root" fetch -q origin "$version"
  else
    git -C "$root" fetch -q origin "refs/tags/v$version:refs/tags/v$version"
  fi
}

# The version's source, in a worktree of its own, never this checkout: a release's at its tag, a commit's at it.
checkout() {
  local version=$1 dir
  dir="$sources/$(label "$version")"
  if [ ! -d "$dir/.git" ] && [ ! -f "$dir/.git" ]; then
    fetch "$version"
    git -C "$root" worktree add --detach "$dir" "$(ref "$version")" >/dev/null
  fi
  echo "$dir"
}

# The chart a version installs, unpacked: a release's as step 0 pulled and checked it from the chart's registry
# (artifacts), a commit's as build packaged it from its source (local_chart).
chart_dir() {
  local version=$1 registry=${CHART%/wow-service}
  if is_local "$version"; then
    echo "$evidence/artifacts/charts/local/$(label "$version")/wow-service"
  else
    echo "$evidence/artifacts/charts/${registry//[^a-z0-9]/_}/$version/wow-service"
  fi
}

# A commit's chart, deploy/helm/wow-service of its source packaged with its label as the version and the appVersion,
# as chart-deploy.yml packages a release's with the release's version; unpacked beside the package, as a release's.
local_chart() {
  local version=$1 src=$2 name dir
  name=$(label "$version")
  dir="$evidence/artifacts/charts/local/$name"
  rm -rf "$dir"
  mkdir -p "$dir"
  helm package "$src/deploy/helm/wow-service" --version "$name" --app-version "$name" -d "$dir" >/dev/null
  tar -xzf "$dir/wow-service-$name.tgz" -C "$dir"
  log "chart wow-service $name packaged from ${src#"$root"/} at $version"
}

# The eShop's five servers built from the release's source against the Wow its version published on Maven Central
# (eshop-build.sh), their images built with deploy/'s Dockerfiles and loaded into every node; and the values every
# service's build generates (generateWowHelmValues), the platform's three from the release's source as it is.
build() {
  local version=$1 src name entry project module tag tasks=()
  src=$(checkout "$version")
  tag=$(label "$version")
  [ -x "$here/eshop-build.sh" ] || fail "eshop-build.sh is missing: the eShop's build against the published Wow"
  "$here/eshop-build.sh" "$src" "$version"
  for name in "${ESHOP[@]}"; do
    entry=$(entry "$name")
    module=${entry##*|}
    docker build -q -f "$src/deploy/$name/Dockerfile" -t "wow-$name-server:$tag" "$src/$module"
  done
  for name in ops view-store analytics-catalog; do
    entry=$(entry "$name")
    project=$(cut -d'|' -f2 <<<"$entry")
    tasks+=("$project:generateWowHelmValues")
  done
  "$src/gradlew" -p "$src" --quiet "${tasks[@]}"
  if is_local "$version"; then
    platform_images "$version" "$src"
    local_chart "$version" "$src"
  fi
  load "$version"
}

# A commit's images of the platform's three, as image-deploy.yml builds a release's: the console a server serves from
# its distribution's ui/, the server's installDist, then the image from its folder, for this machine's platform alone.
platform_images() {
  local version=$1 src=$2 tag name entry project module
  tag=$(label "$version")
  (cd "$src" && pnpm install --frozen-lockfile --silent &&
    pnpm --filter "wow-ops-console..." --filter "wow-analytics-catalog-console..." build >/dev/null)
  for name in ops view-store analytics-catalog; do
    entry=$(entry "$name")
    project=$(cut -d'|' -f2 <<<"$entry")
    module=${entry##*|}
    "$src/gradlew" -p "$src" --quiet "$project:installDist"
    docker build -q -t "wow-$name-server:$tag" "$src/$module"
  done
}

# A version's images the drill built, into every node of the cluster (a cluster made again needs them again): the
# eShop's five, and for a commit the platform's three too.
load() {
  local version=$1 tag name
  tag=$(label "$version")
  for name in "${ESHOP[@]}"; do
    kind load docker-image --name "$cluster" "wow-$name-server:$tag"
  done
  if is_local "$version"; then
    for name in ops view-store analytics-catalog; do
      kind load docker-image --name "$cluster" "wow-$name-server:$tag"
    done
  fi
}

# One service: a release of the published chart with, in order, the values its build generated, deploy/'s, and the
# drill's (values/<service>.yaml); an eShop service takes the image the drill built for the version.
service() {
  local name=$1 version=$2 args=()
  shift 2
  lines args service_args "$name" "$version"
  # Any further argument is helm's, after the drill's values (--set replicas=0, say).
  helm --kube-context "kind-$cluster" -n "$namespace" upgrade --install "$name" "${args[@]}" "$@"
}

# The lines a command prints, into the array named [into], one element each (Bash 3.2 has no mapfile). The command runs
# in an assignment, so its failure stops the caller as errexit would.
lines() {
  local into=$1 out line
  shift
  out=$("$@")
  eval "$into=()"
  while IFS= read -r line; do eval "$into+=(\"\$line\")"; done <<<"$out"
}

# A service's chart and values, in order, as `helm upgrade --install <name>` and `helm template <name>` take them, one
# per line: the values its build generated, deploy/'s, the workaround where the chart needs it, the drill's
# (values/<service>.yaml), and the image a version the drill built runs.
service_args() {
  local name=$1 version=$2 src entry module tag chart values=()
  src="$sources/$(label "$version")"
  tag=$(label "$version")
  entry=$(entry "$name")
  module=${entry##*|}
  values+=(-f "$src/$module/build/wow/helm/values.yaml" -f "$src/deploy/$name/values.yaml")
  # The drill's workaround for the OpenTelemetry agent's init container (values/pod-security-workaround.yaml), only for a
  # chart that names no user: a chart setting podSecurityContext.runAsUser is proved by its own default. Read from the
  # chart installed: a release's as step 0 checked it (artifacts), a commit's as build packaged it.
  local chart_values
  chart_values="$(chart_dir "$version")/values.yaml"
  [ -f "$chart_values" ] || fail "no chart of $version at $chart_values: run \`artifacts $version\` (a release) or \`build $version\` (a commit) first"
  if awk '/^podSecurityContext:/ { inside = 1; next } /^[^[:space:]#]/ { inside = 0 }
    inside && /^[[:space:]]+runAsUser:/ { found = 1 } END { exit !found }' "$chart_values"; then
    log "$name $version: the chart sets podSecurityContext.runAsUser; no workaround"
  else
    log "$name $version: the chart sets no podSecurityContext.runAsUser; the install takes values/pod-security-workaround.yaml"
    values+=(-f "$here/values/pod-security-workaround.yaml")
  fi
  [ ! -f "$here/values/$name.yaml" ] || values+=(-f "$here/values/$name.yaml")
  if is_eshop "$name"; then
    values+=(--set "image.tag=$tag")
  elif is_local "$version"; then
    # The platform's three of a commit run the images build built and loaded, not a registry's.
    values+=(--set "image.repository=wow-$name-server" --set "image.tag=$tag")
  fi
  if is_local "$version"; then
    chart="$evidence/artifacts/charts/local/$tag/wow-service-$tag.tgz"
    printf '%s\n' "$chart"
  else
    printf '%s\n' "$CHART" --version "$version"
  fi
  printf '%s\n' "${values[@]}"
}

# Every service's release as the drill installs it, rendered (helm template, the schema's checks included) into
# evidence/render/<version>/<service>.yaml, and the platform's base (kubectl kustomize): what can be proven of an install
# without a cluster. The values must have been generated (build).
render() {
  local version=$1 out name entry
  out="$evidence/render/$(label "$version")"
  mkdir -p "$out"
  local args=()
  for entry in "${SERVICES[@]}"; do
    name=${entry%%|*}
    lines args service_args "$name" "$version"
    helm template "$name" "${args[@]}" -n "$namespace" >"$out/$name.yaml" ||
      fail "the release of $name at $version does not render"
    log "$name $version: $(grep -c '^kind:' "$out/$name.yaml") objects rendered"
  done
  "$kubectl" kustomize "$here/platform" >"$out/platform.yaml"
  log "platform: $(grep -c '^kind:' "$out/platform.yaml") objects rendered"
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
  log "step 4 was a $mode (from $from to $to): $evidence/compare-$(label "$from")-$(label "$to") holds why"
}

# The upgrade by the documented quiesce steps, not under load (decision D480 ⑰): Operate › Upgrades, "Published streams
# on Kafka", for every service at once, since every eShop service publishes and consumes, and the CoSec order of "From
# a 10.0.0 pre-release". Then the services in their waves, and the gateway last:
#   1. the commands stopped: the gateway scaled to none, so no caller reaches a service (the services' own schedules
#      and timers still fire: step 2 waits for what they send too);
#   2. every subscription caught up with nothing parked, read from the metrics the page names (caught_up);
#   3. every state topic with fewer partitions than the log's given that many (topics);
#   4. CoSec's stored patterns checked with the new version's cosec-migrate while the old gateway still runs
#      (patterns);
#   5. the catalog's Elasticsearch indices that the old version created deleted, as Operate › Elasticsearch says of an
#      index an earlier pre-release created, with the catalog's nodes stopped by its release (search_indices);
#   6. the services upgraded, wave by wave, each until its rollout is done; then the gateway, on the new version's
#      image, every subscription caught up again, and the catalog's snapshot copy rebuilt (the cluster test's
#      search-rebuild).
quiesce() {
  local from=$1 to=$2 wave name
  log "quiesce $from -> $to: 1. the commands stopped, the gateway scaled to none"
  k scale deployment/cosec-gateway --replicas=0
  k wait --for=delete pod -l app.kubernetes.io/name=cosec-gateway --timeout=300s
  log "quiesce: 2. every subscription caught up, nothing parked"
  caught_up 900
  log "quiesce: 3. the state topics given the log's partitions"
  topics
  log "quiesce: 4. CoSec's stored patterns checked with $to's cosec-migrate"
  patterns "$to"
  log "quiesce: 5. the catalog's Elasticsearch indices of $from deleted, its nodes stopped"
  # Through its release, which owns the replicas (the chart is applied server-side): a kubectl scale would take them over,
  # and the upgrade that follows would conflict with it.
  service eshop-catalog "$from" --set replicas=0 >/dev/null
  k wait --for=delete pod -l app.kubernetes.io/name=eshop-catalog-service --timeout=600s
  search_indices
  log "quiesce: 6. the services upgraded to $to, wave by wave, then the gateway"
  for wave in "${WAVES[@]}"; do
    for name in $wave; do
      # The catalog, stopped for step 5, starts again at the new version with its values' nodes.
      service "$name" "$to" >/dev/null
      k rollout status "deployment/$name-service" --timeout=900s
    done
  done
  gateway "$to"
  caught_up 900
  # Operate › Elasticsearch has each copy rebuilt from the log after the delete: the snapshot copy by the snapshot
  # rebuild, sent through the gateway (the cluster test's search-rebuild). The events index has no rebuild a deployment
  # can ask for; the functional suite that follows lists every product and its events from Elasticsearch.
  e2e search-rebuild search-rebuild
  search_layout
  log "quiesce done: $from -> $to; the commands may come again"
}

# Every subscription of every service caught up, as Operate › Upgrades' step 2 reads it: for each partition
# wow.subscription.backlog 0 and wow.subscription.progress 1, and wow.subscription.parked.aggregates 0, over the last
# minute, from Prometheus. Waits at most [bound] seconds; each read is logged.
caught_up() {
  local bound=$1 forward deadline backlog progress parked series status=1
  k port-forward service/prometheus 19090:9090 >/dev/null 2>&1 &
  forward=$!
  sleep 3
  deadline=$(($(date +%s) + bound))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    series=$(prom 'count({__name__=~"wow_subscription_backlog(_ratio)?"})')
    backlog=$(prom 'max(max_over_time({__name__=~"wow_subscription_backlog(_ratio)?"}[1m]))')
    progress=$(prom 'min(min_over_time({__name__=~"wow_subscription_progress(_ratio)?"}[1m]))')
    parked=$(prom 'max(max_over_time({__name__=~"wow_subscription_parked_aggregates(_ratio)?"}[1m]))')
    log "caught up? partitions $series, backlog at most $backlog, progress at least $progress, parked at most $parked"
    if [ "${series:-0}" -gt 0 ] && [ "$backlog" = 0 ] && [ "$progress" = 1 ] && [ "$parked" = 0 ]; then
      status=0
      break
    fi
    sleep 15
  done
  kill "$forward" 2>/dev/null || true
  [ "$status" = 0 ] || fail "the subscriptions did not catch up within ${bound}s"
  # Each subscription's last read, for the evidence: the services' own view of the same.
  log "caught up: every partition's backlog 0 and progress 1, nothing parked"
}

# One value of a Prometheus instant query, through caught_up's port-forward; empty when it answers none.
prom() {
  curl -fsS "http://127.0.0.1:19090/api/v1/query" --data-urlencode "query=$1" |
    jq -r '.data.result[0].value[1] // empty'
}

# Operate › Upgrades' step 3: every state topic with fewer partitions than the log's (WOW_LOG_PARTITIONS, 64 in deploy/)
# given that many, nothing being published; the receipts topics take any count. Each topic's count is logged before.
topics() {
  local partitions=64 topic count
  for topic in $(k exec kafka-0 -- /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list | grep -E '^wow\..*\.state$'); do
    count=$(k exec kafka-0 -- /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --describe --topic "$topic" |
      sed -n 's/.*PartitionCount: *\([0-9]*\).*/\1/p' | head -1)
    if [ "$count" -lt "$partitions" ]; then
      log "topic $topic: $count partitions, altered to $partitions"
      k exec kafka-0 -- /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --alter --topic "$topic" \
        --partitions "$partitions"
    else
      log "topic $topic: $count partitions"
    fi
  done
}

# CoSec's own step before an upgrade onto 8.0.0-beta.9 ("Check the stored patterns first"): the new version's
# cosec-migrate check-patterns over the gateway's Redis, which only reads. Its report goes to evidence/patterns.txt; a
# pattern it names stops the upgrade.
patterns() {
  local version=$1 src forward status=0
  src=$(checkout "$version")
  k port-forward service/redis 16379:6379 >/dev/null 2>&1 &
  forward=$!
  for _ in $(seq 1 30); do
    if (exec 3<>/dev/tcp/127.0.0.1/16379) 2>/dev/null; then break; fi
    sleep 1
  done
  "$src/gradlew" -p "$src" --quiet :eshop-e2e:cosecMigrate --args="check-patterns --to-redis redis://127.0.0.1:16379" \
    >"$evidence/patterns.txt" 2>&1 || status=$?
  kill "$forward" 2>/dev/null || true
  cat "$evidence/patterns.txt" >&2
  [ "$status" = 0 ] || fail "cosec-migrate check-patterns ($status): $evidence/patterns.txt"
}

# The catalog's indices on Elasticsearch that are not an alias's (an earlier pre-release created each under the name
# the store now reads through an alias), deleted: Operate › Elasticsearch, "An index an earlier pre-release created",
# says the store refuses one and to delete and rebuild it. The catalog's nodes are stopped first. What each held is
# listed before, for the evidence.
search_indices() {
  local indices aliased index
  search_layout
  indices=$(es 'http://localhost:9200/_cat/indices/eshop*?h=index' | sort)
  aliased=$(es 'http://localhost:9200/_cat/aliases/eshop*?h=index' | sort -u)
  for index in $indices; do
    if grep -qx "$index" <<<"$aliased"; then
      log "index $index is behind an alias: kept"
    else
      log "index $index is no alias's: deleted"
      es -X DELETE "http://localhost:9200/$index" >/dev/null
    fi
  done
}

# curl on Elasticsearch as its elastic user, from inside its first node, whose environment holds the password.
es() {
  # shellcheck disable=SC2016 # expanded by the shell in the pod
  k exec elasticsearch-0 -c elasticsearch -- sh -c 'curl -fsS -u "elastic:$ELASTIC_PASSWORD" "$@"' _ "$@"
}

# The catalog's indices, their documents and their aliases, for the evidence; each index's documents as a search counts
# them (_count, not _cat's last refresh); and the checkpoints of the catalog's Elasticsearch sinks, where the events
# index would be filled from (#7097: an events index cannot be filled again from the log).
search_layout() {
  local index
  es 'http://localhost:9200/_cat/indices/eshop*?v&s=index&h=index,docs.count,store.size' >&2
  es 'http://localhost:9200/_cat/aliases/eshop*?v&s=alias&h=alias,index,is_write_index' >&2
  for index in eshop_wow_snapshot__eshop_catalog__product eshop_wow_events__eshop_catalog__product; do
    log "$index: $(es "http://localhost:9200/$index/_count" 2>&1)"
  done
  mongo 'db.getSiblingDB("eshop_catalog").wow_checkpoint.find().toArray()
    .filter(c => JSON.stringify(c).includes("elasticsearch-sink") || JSON.stringify(c).includes("wow.snapshot:search"))
    .forEach(c => print(EJSON.stringify(c)))' >&2 || log "the catalog's sink checkpoints could not be read"
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
