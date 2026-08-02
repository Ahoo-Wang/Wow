#!/usr/bin/env bash

set -euo pipefail

usage() {
  printf 'Usage: %s [--include-dotenv] [target-repository]\n' "${0##*/}"
}

include_dotenv=false
case "${1:-}" in
  --include-dotenv)
    include_dotenv=true
    shift
    ;;
  -h|--help)
    usage
    exit 0
    ;;
  -*)
    printf 'ERROR: unknown option: %s\n' "$1" >&2
    usage >&2
    exit 2
    ;;
esac

if [[ "$#" -gt 1 ]]; then
  printf 'ERROR: expected at most one target repository.\n' >&2
  usage >&2
  exit 2
fi

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
  --glob '!**/target/**'
  --glob '!**/package-lock.json'
  --glob '!**/npm-shrinkwrap.json'
  --glob '!**/pnpm-lock.yaml'
)

if [[ "$include_dotenv" == false ]]; then
  common_globs+=(--glob '!**/.env*' --glob '!**/*.env')
fi

redact_sensitive_values() {
  awk '
    {
      prefix = ""
      content = $0
      if (match($0, /^[^:]+:[0-9]+:/)) {
        prefix = substr($0, 1, RLENGTH)
        content = substr($0, RLENGTH + 1)
      }
      lower = tolower(content)
      if (match(lower, /(^|[^[:alnum:]])(password|passphrase|secret|client[_-]?secret|token|credential|api[_-]?key|private[_-]?key|access[_-]?key|authorization|bearer|username|uri|url)/)) {
        sensitive_end = RSTART + RLENGTH
        suffix = substr(content, sensitive_end)
        if (match(suffix, /[=:]/)) {
          delimiter_end = sensitive_end + RSTART - 1
          print prefix substr(content, 1, delimiter_end) "<redacted>"
          next
        }
      }
      print $0
    }
  '
}

match_section() {
  local title="$1"
  local pattern="$2"
  shift 2

  local output
  local rg_status
  if output="$(rg -n --only-matching --color never "$@" "${common_globs[@]}" -- "$pattern" "$scan_root")"; then
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
    output="$(printf '%s\n' "$output" | redact_sensitive_values)"
    printf '\n## %s\n%s\n' "$title" "$output"
  fi
}

printf '# Wow v6 to v8 static exposure audit\n'
printf 'root: %s\n' "$(cd "$scan_root" && pwd)"
printf 'note: matches are review leads, not proof of incompatibility or completeness.\n'

match_section \
  'Declared platform and Wow versions' \
  '^[[:space:]]*(wow|kotlin|ksp|spring-boot)[[:space:]]*=[[:space:]]*"?[0-9][[:alnum:].+_-]*"?|me\.ahoo\.wow:[[:alnum:]_.-]+:[0-9][[:alnum:].+_-]*|wow[._-]?[Vv]ersion[[:space:]]*[=:][[:space:]]*"?[0-9][[:alnum:].+_-]*"?|<(java|kotlin)\.version>[0-9][[:alnum:].+_-]*</(java|kotlin)\.version>|(org\.springframework\.boot|org\.jetbrains\.kotlin\.jvm)[" )]*version[[:space:]]*"?[0-9][[:alnum:].+_-]*"?|kotlin\("jvm"\)[[:space:]]*version[[:space:]]*"?[0-9][[:alnum:].+_-]*"?|me\.ahoo\.wow|wow-(bom|dependencies|spring-boot-starter)|wow[._-]?[Vv]ersion|org\.springframework\.boot|springBoot|spring-boot|kotlin\("jvm"\)|org\.jetbrains\.kotlin\.jvm|\bksp\b|<java\.version>|<kotlin\.version>|jvmToolchain|distributionUrl' \
  --glob '*.gradle' --glob '*.gradle.kts' --glob '*.versions.toml' \
  --glob 'gradle.properties' --glob 'gradle-wrapper.properties' --glob 'pom.xml'

match_section \
  'Jackson 2 or Jackson 3 direct usage' \
  'com\.fasterxml\.jackson\.(core|databind|module|datatype|dataformat)|tools\.jackson|ObjectMapper|JsonNode|JsonSerializer|JsonDeserializer|JacksonAutoConfiguration' \
  --glob '*.kt' --glob '*.java' --glob '*.gradle' --glob '*.gradle.kts' --glob 'pom.xml'

match_section \
  'Spring Boot configuration and auto-configuration exposure' \
  '^[[:space:]]*(wow|spring|r2dbc):|spring\.(jackson|data\.mongodb|mongodb|data\.elasticsearch|elasticsearch|autoconfigure\.exclude)|org\.springframework\.boot\.autoconfigure\.|JacksonAutoConfiguration|Mongo[A-Za-z]*AutoConfiguration|Elasticsearch[A-Za-z]*AutoConfiguration' \
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
  '\b([A-Za-z]*DispatcherLauncher|MainDispatcher|AggregateDispatcher|AggregateSchedulerSupplier|AUTO_REGISTRAR_PHASE)\b|MessageSubscription|MessageBus<|override\s+fun\s+receive\s*\([^)]*(Set<NamedAggregate>|namedAggregates)|\.receive\s*\(\s*(setOf(\s*<[^>]+>)?\s*\(|Set\.of\s*\(|namedAggregates\b)|\b([A-Za-z_][A-Za-z0-9_]*)?[Bb]us\.receive\s*\(\s*[A-Za-z_][A-Za-z0-9_]*\s*\)|getReceiverGroup|setReceiverGroup|writeReceiverGroup|\bLifecycle\b|SmartLifecycle|DisposableBean|GracefullyStoppable|WowRuntime|WowRuntimeLifecycle|RuntimeComponent|me\.ahoo\.wow\.infra\.lifecycle\.Lifecycle|@PostConstruct|@PreDestroy|destroyMethod' \
  --glob '*.kt' --glob '*.java'

match_section \
  'Removed R2DBC and sharding support' \
  'wow-r2dbc|r2dbc-support|wow\.r2dbc|me\.ahoo\.wow\.r2dbc|me\.ahoo\.wow\.sharding' \
  --glob '*.kt' --glob '*.java' --glob '*.gradle' --glob '*.gradle.kts' \
  --glob '*.versions.toml' --glob 'pom.xml' --glob '*.yml' --glob '*.yaml' --glob '*.properties'

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

storage_globs=(
  --glob '*.yml'
  --glob '*.yaml'
  --glob '*.properties'
  --glob '*.kt'
  --glob '*.java'
)
if [[ "$include_dotenv" == true ]]; then
  storage_globs+=(--glob '.env*' --glob '*.env')
fi

match_section \
  'Redis and Mongo configuration' \
  'wow\.[[:alnum:]_.-]*(event-store|snapshot-store|prepare|redis|mongo)[[:alnum:]_.-]*|spring\.(data\.)?(redis|mongodb)|WOW_[A-Z0-9_]*(STORAGE|REDIS|MONGO)[A-Z0-9_]*|SPRING_(DATA_)?(REDIS|MONGODB)(_[A-Z0-9_]+)?|^[[:space:]]*storage[[:space:]]*[=:][[:space:]]*([Rr][Ee][Dd][Ii][Ss]|[Mm][Oo][Nn][Gg][Oo]([Dd][Bb])?)\b|^[[:space:]]*(redis|mongodb):|Redis(EventStore|SnapshotStore|PrepareKey)|Mongo(EventStore|SnapshotStore)|MongoDatabaseContext' \
  "${storage_globs[@]}"

match_section \
  'Generated metadata and API contracts' \
  'wow-metadata\.json|openapi|json-schema|\bksp\b' \
  --glob '*.gradle' --glob '*.gradle.kts' --glob 'pom.xml' \
  --glob '*.json' --glob '*.yaml' --glob '*.yml' --glob '*.kt' --glob '*.java'

printf '\nAudit complete. Confirm resolved dependencies, runtime behavior, storage inventory, and target-tag contracts separately.\n'
