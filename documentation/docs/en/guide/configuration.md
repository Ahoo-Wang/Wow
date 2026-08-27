---
title: Configuring a Wow Application
description: Configure Wow by capability, runtime stage, backend ownership, and environment evidence; use reference pages for exact properties.
outline: deep
---

# Configuring a Wow Application

Configuring Wow is not a matter of copying one large YAML file. Decide which capabilities belong in the runtime, which bus owns each of the three message channels, who persists EventStore and SnapshotStore data, and who can recover every stage. This page provides the task flow; exact keys and defaults live only in [Configuration References](#configuration-references).

## Choose a Starting Point

| Capability to prove | Minimum selection | Completion evidence |
| --- | --- | --- |
| Domain command → event → state | Base Starter + `in_memory` | Aggregate specs and a single-process functional path before restart |
| Cross-instance command/event/state delivery | `kafka-support` or `redis-support` | Redelivery, lag, shutdown, and failure-injection results |
| Authoritative event history | Mongo/Redis/Elasticsearch EventStore | Version continuity, conflict, backup, and isolated-restore results |
| Current-state queries | `strategy: all` + Mongo/Elasticsearch SnapshotStore | `SNAPSHOT` read-after-write, query plans, and rebuild results |
| HTTP/OpenAPI | `webflux-support`; add `openapi-support` only for separate OpenAPI tooling | Runtime OpenAPI, authorization, and route tests |

Replace one boundary at a time. Keep the in-memory vertical slice, then add EventStore, then a bus, then query and operation entry points. A failure then maps to one Wow stage instead of several external systems at once.

## First Run: In-Memory Configuration

Request only the base Starter—no infrastructure capability—and override every core default that points to Kafka or MongoDB:

```yaml
spring:
  application:
    name: order-service

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

This proves only a single-process path. Events and snapshots disappear at process exit, with no cross-instance delivery, broker offsets, durable recovery, or general dynamic query support. If a development classpath still contains a capability, also set the corresponding `wow.kafka.enabled`, `wow.mongo.enabled`, `wow.redis.enabled`, or `wow.elasticsearch.enabled` to `false`—or remove the unused capability.

## Production Starting Point: Kafka + MongoDB

The following is a **candidate topology**, not a production-readiness claim. Use separate dependency declarations for the base variant and each capability. The feature already includes its backend module and Spring Data starter.

```kotlin
dependencies {
    implementation(platform("me.ahoo.wow:wow-bom:<aligned-version>"))
    implementation("me.ahoo.wow:wow-spring-boot-starter")

    implementation("me.ahoo.wow:wow-spring-boot-starter") {
        capabilities { requireCapability("me.ahoo.wow:kafka-support") }
    }
    implementation("me.ahoo.wow:wow-spring-boot-starter") {
        capabilities { requireCapability("me.ahoo.wow:mongo-support") }
    }
    implementation("me.ahoo.wow:wow-spring-boot-starter") {
        capabilities { requireCapability("me.ahoo.wow:webflux-support") }
    }
}
```

```yaml
spring:
  application:
    name: order-service
  mongodb:
    uri: ${MONGODB_URI}

wow:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS}
  command:
    bus:
      type: kafka
  event:
    bus:
      type: kafka
  eventsourcing:
    store:
      storage: mongo
    snapshot:
      enabled: true
      strategy: all
      storage: mongo
    state:
      bus:
        type: kafka
  prepare:
    storage: mongo
```

Accept it by runtime stage instead of substituting “the process started”:

| Stage/boundary | The application or platform owner must prove |
| --- | --- |
| Startup | Capability, property binding, Mongo schema/index, and Kafka client wiring succeeded |
| `SENT` | CommandBus send succeeds and topics, ACLs, and serialization work |
| `PROCESSED` | Aggregate load, business decision, EventStore append, and DomainEventBus send completed |
| `SNAPSHOT` | StateEventBus consumption and target SnapshotStore save/skip policy match expectations |
| `PROJECTED` / `EVENT_HANDLED` / `SAGA_HANDLED` | The exact target function completes and redelivery does not duplicate external effects |
| Shutdown | Ingress is removed and admitted work quiesces within `wow.shutdown-timeout` |
| Recovery | EventStore, snapshots, projections, broker offsets, and compensation state pass isolated restore and reconciliation |

## Backend Boundaries

### Bus

| Type | Capability | Does not provide |
| --- | --- | --- |
| `in_memory` | Fast single-instance validation | Durability or cross-instance delivery |
| `kafka` | Distributed Kafka bus | Automatic topic/ACL/retention/offset-backup governance |
| `redis` | Distributed Redis Streams bus and pending recovery | Recovery of trimmed Streams or EventStore backups |
| `no_op` | Explicitly disables a processing kind | Business execution or recoverable delivery |

Command, domain-event, and state-event channels can select different buses. Record the owner and recovery policy for each. Proving CommandBus does not prove projection or snapshot delivery.

### Storage

| Backend | EventStore | SnapshotStore | Dynamic query | Must prove before adoption |
| --- | --- | --- | --- | --- |
| MongoDB | yes | yes | events and snapshots | schema/indexes, write concern, backup, restore, query plans |
| Redis | yes | yes | no general implementation | canonical key layout, persistence, capacity, restart recovery |
| Elasticsearch | yes | yes | events and snapshots | templates, Bulk, PIT, cluster snapshots, rebuild |
| In-memory | yes | yes | not for production | explicit data-loss boundary |
| Delay | tests | tests | no | comes from `mock-support`; never use in production |

Use `wow.eventsourcing.storage-routing` for per-aggregate routing. The `event` and `snapshot` channels are independent. Rollback, backups, and query factories must cover each actual binding, not only the default store.

## Configuration and Secret Boundaries

| Owner | Examples | Recommended carrier |
| --- | --- | --- |
| Application contract | Bus/storage types, snapshot strategy, HTTP query guard | Versioned configuration reviewed with code |
| Environment topology | Broker/database endpoints, database/topic prefix | Deployment configuration or environment variables |
| Secret | Usernames, passwords, tokens, private keys | Secret manager |
| Operations baseline | Effective configuration digest, topics/indexes/templates, backup point | Redacted release evidence |

Reference external values with `${ENV_NAME}` and prove missing values stop the candidate at startup. Never store real URI credentials in examples, ConfigMaps, logs, or issues. A configuration change can alter delivery, storage, or recovery boundaries and must be reviewed with the application version.

## Environment Layers

### Development

1. Run aggregate specs and one complete command path with in-memory configuration.
2. Add only the capability under test and explicitly disable unused integrations that remain on the classpath.
3. Use isolated namespaces for external backends. Never reuse production topics, databases, consumer groups, or credentials.
4. Preserve the failing test, effective configuration, and backend-health evidence before replacing the next boundary.

### Production

A production candidate must map configuration to Wow stages: the bus owner controls delivery and offsets, the EventStore owner controls authoritative history, the Snapshot/Projection owner controls derived state, and the application owner controls handler idempotency, HTTP authorization, and shutdown. Evidence must come from a production-like environment and cover failure paths, backup/restore, redelivery/reconciliation, rolling shutdown, and capacity. Module checks or a YAML file alone do not prove production readiness.

See [Production Best Practices](./best-practices.md), [Backup, Restore, and Replay](./recovery.md), and [Troubleshooting](./troubleshooting.md).

## BI Script Configuration

Enable `wow.bi.script.*` only when actually generating or deploying ClickHouse scripts. `NO_OP` inspection supports offline generation but does not prove ClickHouse catalog reconciliation. `RESET` requires a controlled inspector and the destructive-operation gates.

```yaml
wow:
  bi:
    script:
      enabled: true
      database: ${BI_DATABASE}
      consumer-database: ${BI_CONSUMER_DATABASE}
      kafka-bootstrap-servers: ${BI_KAFKA_BOOTSTRAP_SERVERS}
      topic-prefix: ${BI_TOPIC_PREFIX}
      inspector:
        type: NO_OP # offline generation only
```

See [Observability Configuration](../reference/config/observability.md) and [BI Deployment and Recovery](./bi-operations.md) for exact BI properties and operations.

## Configuration References

- [Core](../reference/config/core.md): runtime, buses, EventStore, Snapshot, storage routing, query schema, and PrepareKey.
- [Infrastructure](../reference/config/infrastructure.md): Kafka, MongoDB, Redis, Elasticsearch, and WebFlux.
- [Observability](../reference/config/observability.md): OpenAPI, OpenTelemetry, metrics, and BI.
- [Event Compensation](../reference/config/compensation.md): compensation switches, scheduling, and notifications.

For upgrades, use configuration metadata and classes from the target release. Do not apply `main` properties or defaults to an older version.
