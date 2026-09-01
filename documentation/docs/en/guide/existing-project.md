---
title: Add Wow to an Existing Spring Boot Project
description: Add one Wow aggregate to an existing Gradle, Kotlin, and Spring Boot service with explicit build, metadata, route, runtime, failure, and rollback gates.
outline: deep
---

# Add Wow to an Existing Spring Boot Project

Adopt Wow as one reversible vertical slice first:

```text
KSP metadata → Spring auto-configuration → generated HTTP route
→ command aggregate → event store / snapshot → versioned state read
```

Do not migrate every write path or add production infrastructure in the same change. The first milestone is one aggregate running on explicit in-memory adapters with its old write path still available for rollback.

## Version Baseline

The current Wow `9.0.4` source declares:

| Component | Version |
| --- | --- |
| JDK | 17+ |
| Wow | `9.0.4` |
| Spring Boot | `4.1.1` |
| Kotlin | `2.4.10` |
| KSP | `2.3.11` |
| CosId | `3.2.1` |
| Springdoc | `3.1.0` |

Treat these as a compatibility train, not independent suggestions. For another Wow release, inspect that tag's `gradle/libs.versions.toml`, release notes, and persisted-event requirements before changing the application.

## 1. Define the Adoption Boundary

Choose one use case with a clear aggregate ID and business invariant. Before editing, record:

- the existing HTTP/write entry point and current source of truth;
- the Wow command route that will be introduced;
- whether the first run is local-only or receives real traffic;
- the state comparison or reconciliation needed before cutover;
- the last point where removing Wow dependencies is a sufficient rollback.

For a new route with no production traffic, rollback is simple. After commands have appended events, removing the runtime is not data rollback; see [Rollback Boundary](#rollback-boundary).

## 2. Select Dependencies and Capabilities

Every module containing Wow annotations applies KSP and depends on `wow-compiler`. In a multi-module service, a minimal responsibility split is:

| Module | Responsibilities | Required Wow pieces |
| --- | --- | --- |
| `api` | bounded context, commands, events, `@CommandRoute` | `wow-api`, KSP, `wow-compiler` |
| `domain` | `@AggregateRoot`, `@OnCommand`, `@OnSourcing`, domain specs | `wow-core` or `wow-spring`, KSP, `wow-compiler`, `wow-test` |
| `server` | Spring Boot host and runtime adapters | base Starter plus selected capabilities |

Align the platform and compiler in the annotated modules:

```kotlin
dependencies {
    implementation(platform("me.ahoo.wow:wow-bom:9.0.4"))
    ksp(platform("me.ahoo.wow:wow-bom:9.0.4"))

    implementation("me.ahoo.wow:wow-api") // api module
    ksp("me.ahoo.wow:wow-compiler")

    testImplementation("me.ahoo.wow:wow-test") // domain module
}
```

Request the base Starter and the `webflux-support` feature in the server module:

```kotlin
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    implementation(platform("me.ahoo.wow:wow-bom:9.0.4"))

    implementation("me.ahoo.wow:wow-spring-boot-starter")
    implementation("me.ahoo.wow:wow-spring-boot-starter") {
        capabilities {
            requireCapability("me.ahoo.wow:webflux-support")
        }
    }

    implementation("me.ahoo.cosid:cosid-spring-boot-starter:3.2.1")
}
```

The base Starter supplies core Spring wiring. `webflux-support` selects `wow-webflux`, which materializes command and state routes from `RouterSpecs`. It is a Gradle Feature Capability, not a property flag.

Add only a backend required by the target runtime:

| Requirement | Capability |
| --- | --- |
| Local single-process proof | none; use in-memory configuration |
| Distributed command/event/state buses | `kafka-support` |
| MongoDB event/snapshot storage and queries | `mongo-support` |
| Redis event/snapshot storage or bus | `redis-support` |
| Elasticsearch storage and queries | `elasticsearch-support` |
| Tracing instrumentation | `opentelemetry-support` |
| CoSec authorization | `cosec-support` |

See [Spring Boot Starter](./extensions/spring-boot-starter.md) for the complete capability table. Do not request Kafka or storage capabilities merely because they are likely future choices.

::: warning Gradle + KSP boundary
The repository's verified automatic metadata pipeline is Gradle + KSP. Maven can declare runtime dependencies, but this site does not claim an equivalent verified Maven generation path. If generated handlers are required, prove `META-INF/wow-metadata.json` before release.
:::

## 3. Generate and Inspect Metadata

Define the bounded context, commands/events, aggregate, state, and one `AggregateSpec` by following [Aggregate and Invariants](./domain/aggregate.md). Keep business invariants in the aggregate, not duplicated in a controller.

Run KSP and tests in the actual annotated modules:

```shell
./gradlew clean :api:kspKotlin :domain:kspKotlin :domain:test
test -s api/build/generated/ksp/main/resources/META-INF/wow-metadata.json
test -s domain/build/generated/ksp/main/resources/META-INF/wow-metadata.json
```

Adjust module paths to the application. A module with only configuration and no Wow annotations does not need a fabricated metadata file.

At runtime, `MetadataSearcher` merges every classpath resource named `META-INF/wow-metadata.json`. The server must depend on the annotated modules so their resources are packaged on its runtime classpath. Never hand-write metadata or commit `build/` output.

## 4. Use Explicit First-Run Adapters

Wow's event store and snapshot defaults are MongoDB, while bus selection can activate external adapters when present. For a local single-process proof, select all in-memory implementations and disable `PrepareKey`, which requires MongoDB or Redis:

```yaml
spring:
  application:
    name: demo-service

cosid:
  machine:
    enabled: true
    distributor:
      type: manual
      manual:
        machine-id: 1
  generator:
    enabled: true

wow:
  prepare:
    enabled: false
  command:
    bus:
      type: in_memory
  event:
    bus:
      type: in_memory
  eventsourcing:
    store:
      storage: in_memory
    snapshot:
      storage: in_memory
      strategy: all
    state:
      bus:
        type: in_memory
```

This is not a production configuration: data disappears on exit, delivery is single-process, and manual machine ID `1` is safe only for one instance. Persist this local profile without secrets and activate it explicitly.

## 5. Prove Runtime Route Wiring

Start the actual server task, for example:

```shell
./gradlew :server:bootRun
```

Route wiring succeeds through this chain:

1. KSP resources are present on the runtime classpath;
2. `MetadataSearcher` merges bounded-context, aggregate, command, and handler metadata;
3. OpenAPI auto-configuration creates `RouterSpecs`;
4. WebFlux auto-configuration registers command/state route modules;
5. `RouterFunctionBuilder` materializes the route catalog as Spring `RouterFunction`s.

Verify the application logs show each annotated module's `META-INF/wow-metadata.json` being loaded. Then inspect `/v3/api-docs` or the application's route catalog and confirm the command and versioned-state paths. If Swagger UI is wanted, add it as an application choice with the matching Springdoc baseline; it is not required for the runtime route itself.

When that UI is configured, its conventional local entry is [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html).

## 6. Prove Command → Event → State

Use the generated route for the aggregate being adopted; do not add a controller as a test shortcut.

1. Send a create command with a fixed aggregate ID and a unique request ID.
2. Request at least `SNAPSHOT` for this adoption proof.
3. Require HTTP success plus `succeeded: true`, `errorCode: Ok`, the expected stage, ID, and version.
4. Read `/tenant/{tenantId}/{aggregateName}/{id}/state/{version}` at the returned version.
5. Compare the sourced state with the expected domain result and the `AggregateSpec` assertion.

For a concrete verified request and response, use [Getting Started](./getting-started.md#send-the-first-real-command). Replace its demo route and payload with the application's generated contract rather than copying Demo into production code.

## Failure Checkpoints

| Observation | Check first | Do not do |
| --- | --- | --- |
| KSP task succeeds but metadata file is absent | KSP and `wow-compiler` are applied in the module containing annotations | Hand-write `wow-metadata.json` |
| Metadata exists but no HTTP route appears | Annotated module is on the server runtime classpath; `webflux-support` is selected | Add a duplicate controller |
| Startup requests Kafka or MongoDB | Local profile loaded; no unwanted backend capability; all in-memory keys are set | Start unrelated infrastructure to mask wrong configuration |
| First command reports an uninitialized ID generator | CosId starter, generator, and one-instance machine ID are active | Generate production IDs with an ad hoc random fallback |
| Command returns duplicate request | Reuse of the same `requestId` | Disable idempotency to make the request pass |
| State route is `404` | Full context/aggregate/tenant/ID, command result, and returned version | Assume HTTP `200` on the command proved state persistence |

If routes are missing, continue with [Troubleshooting: Missing Metadata or Handler Registration](./troubleshooting.md#missing-metadata-or-handler-registration).

## Rollback Boundary

Before real traffic, rollback is code/config removal: keep the existing write path unchanged, disable the new Wow route or profile, and remove the new dependencies if the slice fails its gates.

After a Wow command has appended a domain event, rollback changes meaning:

- do not delete the event to imitate a database rollback;
- do not send the same business write through old and new paths without an explicit dual-write and reconciliation design;
- keep the old path read-only or isolated while comparing state;
- define how accepted Wow events are replayed, reconciled, or compensated before shifting traffic back;
- cut over only after backup/restore, idempotency, monitoring, and recovery are verified on the chosen durable adapters.

The migration unit should remain one aggregate/use case until its production evidence is complete.

## Completion Gate

Adoption is ready for a separate cutover decision only when:

- domain tests pass and metadata files exist in every annotated module;
- the server loads those resources and exposes the generated routes;
- a real command reaches the declared wait stage;
- versioned sourced state matches the expected event history;
- failure, idempotency, storage, and rollback procedures have environment evidence.

## Next Steps

- Select durable adapters: [Spring Boot Starter](./extensions/spring-boot-starter.md)
- Define completion semantics: [Completion Semantics](./command/completion.md)
- Build release gates: [Testing Wow Applications](./application-testing.md)
- Establish persisted-event policy: [Event Evolution](./domain/event-evolution.md)
- Plan module boundaries: [Module Dependencies](./advanced/module-dependencies.md)
