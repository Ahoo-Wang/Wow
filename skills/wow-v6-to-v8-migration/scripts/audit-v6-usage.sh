#!/usr/bin/env bash

set -euo pipefail

scan_root="${1:-.}"

if ! command -v rg >/dev/null 2>&1; then
  echo "ERROR: rg is required" >&2
  exit 127
fi

if [[ ! -d "$scan_root" ]]; then
  echo "ERROR: target is not a directory: $scan_root" >&2
  exit 2
fi

common_globs=(
  --hidden
  --glob '!**/.git/**'
  --glob '!**/.idea/**'
  --glob '!**/.vscode/**'
  --glob '!**/build/**'
  --glob '!**/.gradle/**'
  --glob '!**/node_modules/**'
  --glob '!**/dist/**'
  --glob '!**/target/**'
  --glob '!**/*lock*.json'
  --glob '!**/*lock*.yaml'
  --glob '!**/*lock*.yml'
)

match_section() {
  local title="$1"
  local pattern="$2"
  shift 2

  local output
  local rg_status
  if output="$(rg -n --color never "$@" "${common_globs[@]}" -- "$pattern" "$scan_root")"; then
    :
  else
    rg_status=$?
    if [[ "$rg_status" -eq 1 ]]; then
      return 0
    fi
    printf 'ERROR: ripgrep failed while scanning section [%s] (exit %s).\n' "$title" "$rg_status" >&2
    return "$rg_status"
  fi
  if [[ -n "$output" ]]; then
    printf '\n## %s\n%s\n' "$title" "$output"
  fi
}

printf '# Wow v6 to v8 static exposure audit\n'
printf 'root: %s\n' "$(cd "$scan_root" && pwd)"
printf 'note: matches are review leads, not proof of incompatibility or completeness.\n'

match_section \
  'Declared platform and Wow versions' \
  '^[[:space:]]*(wow|kotlin|ksp|spring-boot)[[:space:]]*=|me\.ahoo\.wow|wow-(bom|dependencies|spring-boot-starter)|wow[._-]?version|org\.springframework\.boot|springBoot|spring-boot|kotlin\("jvm"\)|org\.jetbrains\.kotlin\.jvm|\bksp\b|<java\.version>|<kotlin\.version>|jvmToolchain|distributionUrl' \
  --glob '*.gradle' --glob '*.gradle.kts' --glob 'libs.versions.toml' \
  --glob 'gradle.properties' --glob 'gradle-wrapper.properties' --glob 'pom.xml'

match_section \
  'Jackson 2 or Jackson 3 direct usage' \
  'com\.fasterxml\.jackson\.(core|databind|module|datatype)|tools\.jackson|ObjectMapper|JsonNode|JsonSerializer|JsonDeserializer|JacksonAutoConfiguration' \
  --glob '*.kt' --glob '*.java' --glob '*.gradle' --glob '*.gradle.kts' --glob 'pom.xml'

match_section \
  'Spring Boot configuration and auto-configuration exposure' \
  '^[[:space:]]*(wow|spring|r2dbc):[[:space:]]*(#.*)?$|spring\.(jackson|data\.mongodb|mongodb|data\.elasticsearch|elasticsearch|autoconfigure\.exclude)|org\.springframework\.boot\.autoconfigure\.(mongo|data\.mongo|elasticsearch|jackson)|JacksonAutoConfiguration|Mongo[A-Za-z]*AutoConfiguration|Elasticsearch[A-Za-z]*AutoConfiguration' \
  --glob '*.kt' --glob '*.java' --glob '*.yml' --glob '*.yaml' --glob '*.properties'

match_section \
  'Removed or changed command wait APIs' \
  '\bClientCommandExchange\b|\bWaitStrategy\b|\bWaitStrategyRegistrar\b|\bExtractedWaitStrategy\b|\bWaitingFors\b|\bWaitingFor[A-Za-z]*\b|sendAndWait|CommandWaitNotifier' \
  --glob '*.kt' --glob '*.java'

# The backticks below are literal ripgrep tokens, not shell command substitutions.
# shellcheck disable=SC2016
match_section \
  'Legacy test DSL usage' \
  '\.\s*`when`\s*\(|\.when\s*[({]|inject\s*\(\s*[^[:space:]{]' \
  --glob '*Test.kt' --glob '*Tests.kt' --glob '*Spec.kt' --glob '*IT.kt' \
  --glob '*Test.java' --glob '*Tests.java' --glob '*Spec.java' --glob '*IT.java'

match_section \
  'Custom messaging or lifecycle ownership' \
  '\b[A-Za-z]*DispatcherLauncher\b|MessageSubscription|MessageBus<|override\s+fun\s+receive\s*\([^)]*(Set<NamedAggregate>|namedAggregates)|\.receive\s*\(\s*(setOf(?:\s*<[^>]+>)?\s*\(|Set\.of\s*\(|namedAggregates\b)|getReceiverGroup|setReceiverGroup|writeReceiverGroup|SmartLifecycle|DisposableBean|GracefullyStoppable|WowRuntime|WowRuntimeLifecycle|RuntimeComponent|me\.ahoo\.wow\.infra\.lifecycle\.Lifecycle|@PostConstruct|@PreDestroy|destroyMethod' \
  --glob '*.kt' --glob '*.java'

match_section \
  'Removed R2DBC and sharding support' \
  'wow-r2dbc|r2dbc-support|wow\.r2dbc|me\.ahoo\.wow\.r2dbc|me\.ahoo\.wow\.sharding' \
  --glob '*.kt' --glob '*.java' --glob '*.gradle' --glob '*.gradle.kts' \
  --glob 'libs.versions.toml' --glob 'pom.xml' --glob '*.yml' --glob '*.yaml' --glob '*.properties'

match_section \
  'Changed OpenAPI and WebFlux extension APIs' \
  '\bRouteSpec\b|\bRouteSpecFactory\b|\bGlobalRouteSpecFactory\b|\bAggregateRouteSpecFactory\b|\bRouteHandlerFunctionFactory\b' \
  --glob '*.kt' --glob '*.java'

match_section \
  'Snapshot checkpoint or custom store exposure' \
  'VersionedSnapshotStore|VersionIntervalCheckpointStrategy|CompositeSnapshotStrategy|SnapshotCheckpointProperties|wow\.eventsourcing\.snapshot\.checkpoint|SnapshotStore|SnapshotRepository|EventStore' \
  --glob '*.kt' --glob '*.java' --glob '*.yml' --glob '*.yaml' --glob '*.properties'

# v6 exposed the misspelled public STEAM constant; v8 replaced it with internal STREAM.
match_section \
  'Removed Redis persistence internals' \
  'AggregateKeyConverter|RedisWrappedKey|RedisSnapshotRepository|EventStreamKeyConverter|DefaultSnapshotKeyConverter|PrepareKeyConverter|SCRIPT_EVENT_STEAM_APPEND|SCRIPT_EVENT_STREAM_APPEND|redisSnapshotRepository' \
  --glob '*.kt' --glob '*.java' --glob '*.yml' --glob '*.yaml' --glob '*.properties'

match_section \
  'Redis and Mongo configuration' \
  'wow\..*(event-store|snapshot-store|prepare|redis|mongo)|spring\.(data\.)?(redis|mongodb)|^[[:space:]]*storage:[[:space:]]*(redis|mongo)\b|^[[:space:]]*(redis|mongodb):[[:space:]]*(#.*)?$|Redis(EventStore|SnapshotStore|PrepareKey)|Mongo(EventStore|SnapshotStore)|MongoDatabaseContext' \
  --glob '*.yml' --glob '*.yaml' --glob '*.properties' --glob '*.kt' --glob '*.java'

match_section \
  'Generated metadata and API contracts' \
  'wow-metadata\.json|openapi|json-schema|\bksp\b' \
  --glob '*.gradle' --glob '*.gradle.kts' --glob 'pom.xml' \
  --glob '*.json' --glob '*.yaml' --glob '*.yml' --glob '*.kt' --glob '*.java'

printf '\nAudit complete. Confirm resolved dependencies, runtime behavior, storage inventory, and target-tag contracts separately.\n'
