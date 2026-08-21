---
title: Configuring a Wow Application
description: Configure Wow by development stage, production backend, environment, and secret boundary; use reference pages for exact properties.
outline: deep
---

# Configuring a Wow Application

This page helps application developers make configuration decisions. Exact properties, types, and defaults live only in [Configuration References](#configuration-references), avoiding multiple manually maintained sources of truth.

## Choose a Starting Point

| Scenario | Bus | EventStore / SnapshotStore | Next step |
| --- | --- | --- | --- |
| Initial adoption and domain tests | `in_memory` | `in_memory` | prove command → event → state first |
| Common production baseline | Kafka | MongoDB | verify persistence, restart, offsets, and recovery |
| Existing Redis platform | Redis Streams | Redis | evaluate query capability, capacity, and canonical v2 layout |
| Search/complex snapshot queries | Kafka/Redis | Elasticsearch or MongoDB | establish indexes, query plans, and rebuild procedures |

Do not introduce Kafka, MongoDB, Redis, Elasticsearch, compensation, and telemetry together on the first run. Complete an in-memory vertical slice, then replace one boundary at a time while retaining test evidence.

## First Run: In-Memory Configuration

```yaml
spring:
  application:
    name: order-service

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

This configuration is only for single-process validation: data disappears on restart and it provides no cross-instance delivery, durable recovery, or general dynamic query support. A manual machine ID is also unsafe for multiple instances.

## Production Starting Point: Kafka + MongoDB

Request the matching Starter capabilities before configuring a backend. Configuration keys alone do not add runtime dependencies:

```kotlin
implementation("me.ahoo.wow:wow-spring-boot-starter") {
    capabilities { requireCapability("me.ahoo.wow:kafka-support") }
}
implementation("me.ahoo.wow:wow-spring-boot-starter") {
    capabilities { requireCapability("me.ahoo.wow:mongo-support") }
}
implementation("org.springframework.boot:spring-boot-starter-data-mongodb-reactive")
```

```yaml
spring:
  application:
    name: order-service
  mongodb:
    uri: ${MONGODB_URI}

wow:
  command:
    bus:
      type: kafka
  event:
    bus:
      type: kafka
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS}
  eventsourcing:
    store:
      storage: mongo
    snapshot:
      storage: mongo
      strategy: all
    state:
      bus:
        type: kafka
```

This is only a production starting point. Before release, verify authentication/TLS, topics and consumer groups, indexes, capacity, backup/restore, shutdown, alerts, and rolling upgrades.

## Backend Boundaries

### Bus

| Type | Good fit | Main boundary |
| --- | --- | --- |
| `in_memory` | single-process development and tests | no persistence or cross-instance delivery |
| `kafka` | multiple instances and durable messaging | topics, partitions, offsets, redelivery, and capacity |
| `redis` | services already using Redis Streams | pending recovery, consumer groups, and capacity |
| `no_op` | explicit cases that do not process a message kind | messages do not produce real business processing |

### Storage

| Backend | EventStore | SnapshotStore | Dynamic query | Verify before adoption |
| --- | --- | --- | --- | --- |
| MongoDB | yes | yes | event stream and snapshot | indexes, sharding, write concern, backup, recovery |
| Redis | yes | yes | no general dynamic snapshot query | canonical v2, capacity, persistence, pending recovery |
| Elasticsearch | yes | yes | event stream and snapshot | templates/ILM, bulk, PIT, rebuild, recovery |
| In-memory | yes | yes | tests only | process exit loses all data |

Use `wow.eventsourcing.storage-routing` when one aggregate needs a dedicated backend; do not select storage manually in business code. See [Spring Boot Starter](./extensions/spring-boot-starter.md#bean-wiring-and-overrides) for binding rules.

## Configuration and Secret Boundaries

Split configuration into three groups:

| Type | Examples | Location |
| --- | --- | --- |
| Versioned policy | bus/storage type, snapshot strategy, timeouts | repository `application.yaml` |
| Environment value | broker addresses, database names, OTLP endpoint | deployment environment/configuration |
| Secret | database password, token, webhook, certificate private key | secret manager |

- never put real credentials in documentation, examples, or ConfigMaps;
- reference environment values with `${ENV_NAME}` and fail startup when required values are missing;
- do not keep `me.ahoo.wow: DEBUG` enabled in production;
- retain a redacted effective-configuration summary for recovery and audit;
- review configuration changes with the application version instead of treating YAML-only changes as risk-free.

## Environment Layers

### Development

- prefer in-memory adapters or isolated local backends;
- use a single-instance manual machine ID;
- keep Swagger, detailed logs, and fast domain tests;
- make data-loss expectations explicit and never copy local settings to production.

### Production

- use a distributor that guarantees unique machine IDs;
- configure durable bus, EventStore, and SnapshotStore implementations;
- protect command, query, and Actuator endpoints with authentication and authorization;
- verify idempotency indexes, partitioning/sharding, consumer offsets, and graceful shutdown;
- complete [Application Testing](./application-testing.md) and [Backup, Restore, and Replay](./recovery.md) gates.

## BI Script Configuration

The BI script service uses `wow.bi.script.*`; enable it only when generating or deploying ClickHouse scripts:

```yaml
wow:
  bi:
    script:
      enabled: true
      database: wow
      consumer-database: wow_consumer
      timezone: UTC
      kafka-bootstrap-servers: ${BI_KAFKA_BOOTSTRAP_SERVERS:${KAFKA_BOOTSTRAP_SERVERS}}
      topic-prefix: ${BI_TOPIC_PREFIX:wow.}
      inspector:
        type: NO_OP # offline generation only; deployment/reset needs a controlled ClickHouse inspector
```

Explicit `wow.bi.script.kafka-bootstrap-servers` and `topic-prefix` values override `wow.kafka.*`. A `NO_OP` inspector supports offline generation and does not prove catalog reconciliation. Before `RESET`, use a real inspector and complete the destructive gates in [BI Deployment and Recovery](./bi-operations.md).

## Configuration References

Use configuration classes and these pages for exact properties:

- [Core](../reference/config/core.md): Wow, buses, event sourcing, snapshots, storage routing, and PrepareKey;
- [Infrastructure](../reference/config/infrastructure.md): Kafka, MongoDB, Redis, Elasticsearch, and WebFlux;
- [Observability](../reference/config/observability.md): OpenAPI, OpenTelemetry, metrics, and BI;
- [Event Compensation](../reference/config/compensation.md): compensation switch, scheduler, and notifications.

When upgrading Wow, use the target tag's configuration classes and release notes; do not apply `main` defaults to an older release.
