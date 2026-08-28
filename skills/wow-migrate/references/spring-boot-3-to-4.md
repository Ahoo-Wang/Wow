# Spring Boot 3 to 4 for Wow Applications

Use this reference only when a downstream Wow application crosses from Spring Boot 3 to 4. Verify every version, module, property, package, default, and removed feature against the pinned source application, target Wow tag/BOM/template, and target Boot migration guide or configuration changelog.

## Pin the platform train

Record the source and target for Java, Gradle, Spring Boot and Framework, Kotlin/KSP, Jackson, Reactor, Spring Data, Kafka, storage clients, Wow BOM/modules, and every third-party starter. Prefer upgrading to the latest supported Boot 3.5.x baseline before Boot 4 when the authorized migration can be staged; otherwise record the direct-jump evidence gap.

Use the target Wow tag and official project template to select one compatible train. Do not independently choose the newest version of each component. Boot 4 requires Java 17+, Kotlin 2.2+, Spring Framework 7, and Jakarta EE 11, but the pinned Wow target may require newer versions.

Use the [`wow-project-template` version catalog](https://github.com/Ahoo-Wang/wow-project-template/blob/main/gradle/libs.versions.toml) as the preferred baseline for the target Wow ecosystem train: Boot, Wow, CosId, CoAPI, CoCache, CoSec, CoSky, Simba, Kotlin/KSP, test libraries, and build plugins. Resolve `main` to an immutable commit before recording versions. When the requested Wow target differs from the current template, select a template commit aligned with that target or prove every substituted version; never silently replace the requested target with the floating template.

The template is a selection baseline, not runtime proof. Compare its catalog with the target Wow BOM/published metadata and the application's resolved graph.

## Resolve dependencies and modules

| Surface | Required decision |
|---|---|
| Wow | Keep the base `wow-spring-boot-starter`; add only the capability-qualified starter declarations the application actually uses. |
| Boot modules | Map every imported or referenced Boot auto-configuration class to its target Boot 4 module and package. |
| Application APIs | Add a direct Boot starter/module only when application source uses its API or the selected Wow capability does not provide it. |
| Tests | Replace broad Boot 3 test infrastructure with the target technology test starters only when the pinned target requires them. |
| Third-party starters | Prove a Boot 4/Spring Framework 7/Jakarta-compatible release; do not assume Boot-managed dependencies repair an incompatible starter. |
| KSP/compiler | Keep `wow-compiler` on the processor path and generated metadata in runtime artifacts; do not add the compiler as a runtime repair. |

Prove both dependency selection and class presence:

```bash
./gradlew :app:dependencies --configuration compileClasspath
./gradlew :app:dependencies --configuration runtimeClasspath
./gradlew :app:dependencyInsight --configuration runtimeClasspath --dependency <module>
jar tf <resolved-artifact.jar> | rg '<required/class/path>'
```

Boot 4's `spring-boot-starter-classic` and `spring-boot-starter-test-classic` may be used only as an explicitly temporary diagnostic bridge. Record what they restore, replace them with the required focused modules, and remove them before claiming the dependency migration complete.

## Migrate configuration by ownership

Inventory key names and precedence across packaged YAML/properties, profiles, `spring.config.import`, environment variables, JVM arguments, tests, remote configuration, ConfigMaps, Helm values, and secret references. Do not read or print secret values. A local file update does not prove a remote property source or deployed environment changed.

Use the target Boot configuration changelog and property metadata. `spring-boot-properties-migrator` may be added temporarily to diagnose Boot-owned keys, but it is not a permanent compatibility layer and does not prove late or remote property sources are correct; remove it after explicit configuration migration.

MongoDB illustrates why a global prefix replacement is unsafe:

| Boot 3 input | Boot 4 target | Boundary |
|---|---|---|
| `spring.data.mongodb.uri`, `database`, host, credentials, replica set, SSL | corresponding `spring.mongodb.*` key | Mongo client/connection |
| `spring.data.mongodb.auto-index-creation`, `field-naming-strategy`, `gridfs.*`, `repositories.type`, `representation.big-decimal` | remains under `spring.data.mongodb.*` | Spring Data MongoDB |
| management keys using `mongo` | target metadata uses `mongodb` | Health and metrics |
| `wow.mongo.*` | remains Wow-owned unless the pinned Wow target says otherwise | Wow storage/query behavior |

Apply the same ownership test to Redis, Elasticsearch, Kafka, WebFlux, Actuator, observability, security, and application-specific properties. Verify `@ConfigurationProperties`, `@ConditionalOnProperty`, auto-configuration exclusions, Bean names/qualifiers, and custom overrides against target source; do not infer a rename from a similar prefix.

## Preserve Jackson and wire contracts

Boot 4 defaults to Jackson 3. Most classes and dependency coordinates move from `com.fasterxml.jackson` to `tools.jackson`; Jackson annotations retain the compatibility namespace. Inspect custom serializers/deserializers, mix-ins, modules, `ObjectMapper`/`JsonMapper` construction, Kotlin module registration, and Wow's `WowModule` rather than applying a global import rewrite.

Migrate feature keys by their Jackson 3 owner:

| Boot 3 | Boot 4 |
|---|---|
| `spring.jackson.mapper.accept-case-insensitive-enums` | unchanged under `spring.jackson.mapper.accept-case-insensitive-enums` |
| `spring.jackson.serialization.write-durations-as-timestamps` | `spring.jackson.datatype.datetime.write-durations-as-timestamps` |
| `spring.jackson.serialization.write-dates-as-timestamps` | `spring.jackson.datatype.datetime.write-dates-as-timestamps` |

```yaml
spring:
  jackson:
    mapper:
      accept-case-insensitive-enums: true
    datatype:
      datetime:
        write-durations-as-timestamps: true
        write-dates-as-timestamps: true
```

Do not infer equivalent JSON only because the new keys bind. The Boot 4 defaults for both timestamp features are disabled; preserving an explicit `true` changes or retains a wire decision that must be checked against source-version payloads and consumers.

Use the target serializer to read representative source-version commands, events, event streams, snapshots, state events, query bodies, and error payloads. Compare reconstructed domain state and regenerate OpenAPI, JSON Schema, and clients from the target source. Compilation does not prove stored or transported JSON compatibility.

Treat temporary Jackson 2 support or old-default switches as migration bridges only when the pinned Boot target supports them and the user accepts an exit plan. Do not silently run two mapper stacks without proving which mapper owns HTTP, messaging, and persistence.

## Check selected Wow capabilities

| Capability | Boot 3 to 4 evidence |
|---|---|
| `mongo-support` | Mongo Boot module/starter, property binding, selected database, client Bean, collection/index initialization, snapshot and query path. |
| `redis-support` | Redis module/starter, client factory, property binding, bus/store selection, stream recovery and health. |
| `elasticsearch-support` | Boot/client generation, customizers, mapping/template access, query Schema binding and refresh behavior. |
| `kafka-support` | Kafka/Spring Kafka train, client and retry customizers, receiver options, topic/consumer startup and shutdown. |
| `webflux-support` / `openapi-support` | Reactor Netty/WebFlux modules, error handling, request binding, generated routes/OpenAPI and client regeneration. |
| `opentelemetry-support` | Boot observability modules, Micrometer/OpenTelemetry bridge, exporter ownership, metrics/traces and graceful shutdown. |
| `cosec-support` and other starters | Exact Boot 4/Jakarta-compatible train, auto-configuration conditions, security context and authorization behavior. |

An unused capability is `NOT APPLICABLE`; do not add it merely to satisfy this matrix.

## Adapt source and auto-configuration

Audit imports under `org.springframework.boot.*`, custom `@AutoConfiguration`, `spring.factories`, auto-configuration imports, `EnvironmentPostProcessor`, configuration metadata, conditions, exclusions, and Bean overrides. Boot 4 module packages may differ even when a similarly named type still exists.

For application-owned starters, prefer a Boot 4 target artifact over speculative dual Boot 3/4 compatibility in one artifact. Add a compatibility bridge only when both runtimes are an explicit supported product contract with separate verification.

## Verification gates

1. Save source and target dependency reports plus focused `dependencyInsight`; prove final `runtimeClasspath` ownership.
2. Compile production, generated, and test source; run focused domain, auto-configuration, serialization, and capability tests.
3. Start the unmodified target configuration when safe, then separate any temporary isolated retry. Capture the first property-binding, condition, missing-class, Bean conflict, or external dependency failure.
4. Prove effective non-secret configuration from every required source, including remote configuration and deployed manifests; a local migrator result is insufficient.
5. Exercise the application's actual HTTP or messaging surface, health/readiness, OpenAPI, one Wow command/query path as authorized, and graceful shutdown.
6. Validate external integrations and stored data only with exact targets and separate authorization; otherwise mark `MISSING EVIDENCE`.

Report **platform/source adaptation**, **configuration adaptation**, **wire compatibility**, **local runtime**, **deployability**, and **production readiness** independently. A Boot 4 startup or smoke test proves none of the later gates by itself.

Authoritative starting points: the pinned Wow tag/BOM/template, the [Spring Boot 4 migration guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide), and its target-version configuration changelog.
