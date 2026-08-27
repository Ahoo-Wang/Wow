---
title: Spring Boot Starter
description: Conditionally wire Wow core, runtime lifecycle, and optional infrastructure in Spring Boot.
---

# Spring-Boot-Starter

`wow-spring-boot-starter` is the Spring Boot wiring entry point: it binds `wow.*`, registers core beans, collects `RuntimeComponent`s, and lets one `WowRuntimeLifecycle` own startup and shutdown. It does not choose the application's broker, storage, security, or exporter.

## Installation

```kotlin
implementation("me.ahoo.wow:wow-spring-boot-starter")
```

The base dependency provides core/spring/schema/compensation and Spring WebFlux/Jackson baselines. Kafka, Mongo, Redis, Elasticsearch, OpenTelemetry, OpenAPI, and CoSec remain optional capabilities. Starter alone with default `kafka`/`mongo` selections does not prove those implementations are present.

## Auto-Configuration Mechanism

Spring Boot loads Wow entries from `AutoConfiguration.imports`. `@ConditionalOnClass`, `@ConditionalOnProperty`, storage/bus selections, and `@ConditionalOnMissingBean` then decide beans. Actual beans, routes, and runtime components after startup are the wiring evidence.

### Gradle Feature Variants

| Capability | Main direct capability |
|---|---|
| `mongo-support` | `wow-mongo` + reactive Mongo starter |
| `redis-support` | `wow-redis` + reactive Redis starter |
| `mock-support` | `wow-mock` |
| `kafka-support` | `wow-kafka` |
| `webflux-support` | `wow-bi` API + `wow-webflux` |
| `elasticsearch-support` | `wow-elasticsearch` + Spring Data Elasticsearch |
| `opentelemetry-support` | `wow-opentelemetry` |
| `openapi-support` | `wow-bi` API + `wow-openapi` + springdoc common |
| `cosec-support` | `wow-cosec` |

Request the full coordinate, for example `requireCapability("me.ahoo.wow:mongo-support")`. Maven does not resolve Gradle feature variants, so it needs explicit module dependencies.

## Auto-Configuration Classes

Core configuration is split across serialization, command, event, event sourcing, query, projection, Saga, metrics, and runtime lifecycle. Extension configurations join under classpath and selection conditions. Do not infer defaults from class names; inspect class and bean conditions.

## Complete Configuration Properties

Use [Core configuration](../../reference/config/core.md) and extension pages for the full key/default catalog. This page keeps only minimum startup boundaries to avoid copying a drifting table.

### Core Configuration (wow.*)

Defaults are `wow.enabled=true`, `wow.shutdown-timeout=60s`, and `wow.shutdown-quiet-period=1s`. When `wow.context-name` is absent, `spring.application.name` is required; if both are absent, current bounded-context creation fails.

### Command Configuration (wow.command.*)

The bus defaults to `kafka`, local-first to enabled, and idempotency to enabled. Without Kafka capability, explicitly select `in_memory`, `redis`, or an application implementation.

### Event Configuration (wow.event.*)

The domain-event bus defaults to `kafka`, with local-first enabled. Its selection is independent from the state-event bus; changing one key is not enough.

### Event Sourcing Configuration (wow.eventsourcing.*)

EventStore and SnapshotStore default to `mongo`, while the state bus defaults to `kafka`. Storage routing builds a primary router from named bindings. An ordinary same-type bean does not automatically replace an enabled storage capability.

## Bean Wiring and Overrides

Replace only extension points whose source declares `@ConditionalOnMissingBean`. `WowRuntime` must be the only singleton named `wowRuntime` in the current context and have one exclusive `WowRuntimeLifecycle`. A competing Spring `Lifecycle`, `DisposableBean`, destroy method, or `AutoCloseable.close` owner fails fast to prevent double shutdown.

Starter preserves backend-native semantics: Kafka offsets, Mongo unique indexes, Redis Lua, and Elasticsearch mappings remain owned by their adapters/backends instead of duplicate core validation.

## Multi-Module Project Configuration

Separating API, domain, and server keeps dependencies directed; only the server requests runtime capabilities.

### Project Structure

```text
order-api -> order-domain -> order-server
```

### API Module Configuration

```kotlin
api("me.ahoo.wow:wow-api")
```

### Domain Module Configuration

```kotlin
implementation("me.ahoo.wow:wow-core")
ksp("me.ahoo.wow:wow-compiler")
testImplementation("me.ahoo.wow:wow-test")
```

### Server Module Configuration

A local candidate without external infrastructure can request `mock-support` and `webflux-support`:

```kotlin
implementation("me.ahoo.wow:wow-spring-boot-starter") {
    capabilities { requireCapability("me.ahoo.wow:mock-support") }
}
implementation("me.ahoo.wow:wow-spring-boot-starter") {
    capabilities { requireCapability("me.ahoo.wow:webflux-support") }
}
```

```yaml
spring:
  application:
    name: order-service
wow:
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
    state:
      bus:
        type: in_memory
```

`mock-support` is for local/test use and is not production persistence evidence.

## Metadata Loading

KSP generates `META-INF/wow-metadata.json`; `MetadataSearcher` lazily merges all resources with that name from the classpath. Missing generated resources leave aggregates, processors, routes, or schemas incomplete. Starter does not reconstruct the full contract through reflection.

## Processor Registration

Aggregates, Sagas, and projections come from compiler metadata plus Spring bean discovery. An annotation does not form complete runtime registration when the server does not depend on the module, KSP did not run, or generated resources were not packaged.

### Aggregate Processor

Aggregate command handling uses aggregate metadata and state factories. Verify with a real command and state read, not bean count alone.

### Saga Processor

A Saga also needs an event-bus subscription and a started runtime component. Class presence does not prove message consumption.

### Projection Processor

A projection likewise needs event subscription, processor discovery, and its target read model. Startup logs do not prove projection catch-up.

## Complete Configuration Example

Build production configuration from the selected capability pages instead of copying an “everything” example. At minimum, explicitly declare context, all three buses, event/snapshot storage, and the corresponding Spring backend connections.

## Best Practices

- Request only used capabilities and select buses/stores explicitly.
- Verify wiring through auto-configuration conditions, actual beans, OpenAPI, and runtime state.
- Preserve single ownership of `WowRuntimeLifecycle`.
- Leave backend checks to adapters/backends and public-contract validation at boundaries.

Verified failures include missing context name, a missing selected backend bean, duplicate runtime/lifecycle ownership, non-singleton runtime components, and invalid extension properties. Focused check:

```bash
./gradlew :wow-spring-boot-starter:check
```

Next, read [Existing project](../existing-project.md) and [Module dependencies](../advanced/module-dependencies.md).
