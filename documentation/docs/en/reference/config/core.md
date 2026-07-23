---
title: Core Configuration
description: Fundamental configuration options for the Wow framework, including command bus, event bus, event sourcing, snapshots, state events, and prepare keys.
---

# Core Configuration

## WowProperties

- Configuration class: [WowProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/WowProperties.kt)
- Prefix: `wow`

| Name | Data Type | Description | Default Value |
|------|-----------|-------------|---------------|
| `enabled` | Boolean | Enable/disable the Wow framework | `true` |
| `context-name` | String | Bounded context name for the service | Falls back to (required) `spring.application.name` |
| `shutdown-timeout` | Duration | Graceful shutdown timeout | `60s` |

```yaml
wow:
  enabled: true
  context-name: order-service
  shutdown-timeout: 120s
```

## BusProperties

`BusProperties` is the shared configuration for `CommandBus`, `EventBus`, and `StateEventBus`.

- Configuration class: [BusProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/BusProperties.kt)

| Name | Data Type | Description | Default Value |
|------|-----------|-------------|---------------|
| `type` | BusType | Message bus implementation type | `kafka` |
| `local-first` | LocalFirstProperties | LocalFirst mode configuration | |

### BusType

```kotlin
enum class BusType {
    KAFKA,      // Apache Kafka (recommended for production)
    REDIS,      // Redis Streams
    IN_MEMORY,  // In-memory (for testing)
    NO_OP;      // No-op (for special cases)
}
```

### LocalFirst Mode

LocalFirst mode optimizes command and event processing by prioritizing local message consumption over distributed message bus:

```mermaid
flowchart TB
    subgraph Local["Local Service Instance"]
        CG[CommandGateway]
        LocalBus[Local Bus]
        Processor[Processor]
    end

    subgraph Distributed["Distributed Message Bus"]
        Kafka[Kafka]
    end

    Client --> CG
    CG --> LocalBus
    CG --> Kafka
    LocalBus --> Processor
    Kafka --> Processor
```

#### Benefits

1. **Reduced Latency**: Local message processing avoids network round trips
2. **Better Resource Utilization**: Maximizes local processing before distributed
3. **Fault Tolerance**: Failed local messages are retried via distributed bus

| Name | Data Type | Description | Default Value |
|------|-----------|-------------|---------------|
| `local-first.enabled` | Boolean | Enable LocalFirst mode | `true` |

## Command Bus

- Configuration class: [CommandProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/command/CommandProperties.kt)
- Prefix: `wow.command.`

| Name | Data Type | Description | Default Value |
|------|-----------|-------------|---------------|
| `bus` | `BusProperties` | Command bus configuration | |
| `idempotency` | `IdempotencyProperties` | Command idempotency | |

```yaml
wow:
  command:
    bus:
      type: kafka
      local-first:
        enabled: true
    idempotency:
      enabled: true
      bloom-filter:
        expected-insertions: 1000000
        ttl: PT60S
        fpp: 0.00001
```

### IdempotencyProperties

- Configuration class: [IdempotencyProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/command/CommandProperties.kt)

| Name | Data Type | Description | Default Value |
|------|-----------|-------------|---------------|
| `enabled` | `boolean` | Whether to enable | `true` |
| `bloom-filter` | `BloomFilter` | BloomFilter | |

#### BloomFilter

| Name | Data Type | Description | Default Value |
|------|-----------|-------------|---------------|
| `ttl` | `Duration` | Time to live | `Duration.ofMinutes(1)` |
| `expected-insertions` | `Long` | Expected number of insertions | `1000_000` |
| `fpp` | `Double` | False positive probability | `0.00001` |

## Event Bus

- Configuration class: [EventProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/event/EventProperties.kt)
- Prefix: `wow.event.`

| Name | Data Type | Description | Default Value |
|------|-----------|-------------|---------------|
| `bus` | `BusProperties` | Event bus configuration | |

```yaml
wow:
  event:
    bus:
      type: kafka
      local-first:
        enabled: true
```

## Event Sourcing

### EventStoreProperties

- Configuration class: [EventStoreProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/eventsourcing/store/EventStoreProperties.kt)
- Prefix: `wow.eventsourcing.store`

| Name | Data Type | Description | Default Value |
|------|-----------|-------------|---------------|
| `storage` | `StorageType` | Event store storage backend | `mongo` |

```yaml
wow:
  eventsourcing:
    store:
      storage: mongo
```

#### StorageType

The `StorageType` enum is shared across the event store and snapshot store.

```kotlin
enum class StorageType {
    MONGO,
    REDIS,
    ELASTICSEARCH,
    IN_MEMORY,
    DELAY
    ;
}
```

### SnapshotProperties

- Configuration class: [SnapshotProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/eventsourcing/snapshot/SnapshotProperties.kt)
- Prefix: `wow.eventsourcing.snapshot`

| Name | Data Type | Description | Default Value |
|------|-----------|-------------|---------------|
| `enabled` | `Boolean` | Whether to enable snapshots | `true` |
| `strategy` | `Strategy` | Snapshot strategy | `all` |
| `version-offset` | `Int` | Version offset threshold | `5` |
| `storage` | `StorageType` | Snapshot storage backend | `mongo` |

```yaml
wow:
  eventsourcing:
    snapshot:
      enabled: true
      strategy: version_offset
      version-offset: 10
      storage: mongo
```

#### Strategy

```kotlin
enum class Strategy {
    ALL,
    VERSION_OFFSET,
    ;
}
```

The snapshot `storage` property reuses the shared [`StorageType`](#storagetype) enum.

### SnapshotCheckpointProperties

Persist the latest snapshot version as an immutable checkpoint (`VersionIntervalCheckpointStrategy`)
at a fixed aggregate-version interval. The checkpoint is available via `VersionedSnapshotStore.loadAtOrBefore`,
which an application can use to resume from a recent version without replaying the entire
history — but the flag only adds the checkpoint writes; it does not change projection or
rebuild behavior on its own.

- Configuration class: [SnapshotCheckpointProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/eventsourcing/snapshot/SnapshotProperties.kt)
- Prefix: `wow.eventsourcing.snapshot.checkpoint`

| Name | Data Type | Description | Default Value |
|------|-----------|-------------|---------------|
| `enabled` | `Boolean` | Persist the snapshot version checkpoint | `false` |
| `version-interval` | `Int` | How often (in versions) to persist the checkpoint; must be positive | `100` |

```yaml
wow:
  eventsourcing:
    snapshot:
      checkpoint:
        enabled: true
        version-interval: 100
```

### StorageRoutingProperties

Route different aggregates to different storage backends within a single service. When a
matching route is configured, Wow installs a `RoutingEventStore` / `RoutingSnapshotStore`
that dispatches per aggregate to the bound store, falling back to the default storage
(`wow.eventsourcing.store.storage` / `wow.eventsourcing.snapshot.storage`) for unlisted
aggregates.

- Configuration class: [StorageRoutingProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/eventsourcing/routing/StorageRoutingProperties.kt)
- Prefix: `wow.eventsourcing.storage-routing`

| Name | Data Type | Description | Default Value |
|------|-----------|-------------|---------------|
| `aggregates` | `Map<String, AggregateStorageRouteProperties>` | Per-aggregate route keyed by aggregate name | `{}` (empty) |

Each aggregate route accepts an `event` and/or `snapshot` channel. **A configured channel
must set exactly one** of `storage` or `binding` — an empty channel (e.g. `event: {}`) fails
fast at startup. Omitting a channel entirely is what falls back to the default store.

| Name | Data Type | Description | Default Value |
|------|-----------|-------------|---------------|
| `storage` | `StorageType` | Storage backend for this channel (overrides the default) | _(required if `binding` absent)_ |
| `binding` | `String` | Name of the bound store/query-service bean to use | _(required if `storage` absent)_ |

```yaml
wow:
  eventsourcing:
    storage-routing:
      aggregates:
        # Hot aggregate: keep events and snapshots in Redis for low latency
        HotAggregate:
          event:
            storage: redis
          snapshot:
            storage: redis
        # Cold aggregate: fall back to the default MongoDB store
        # (no route entry needed — default applies)
```

## State Event Bus

- Configuration class: [StateProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/eventsourcing/state/StateProperties.kt)
- Prefix: `wow.eventsourcing.state`

| Name | Data Type | Description | Default Value |
|------|-----------|-------------|---------------|
| `bus` | `BusProperties` | State event bus configuration | |

```yaml
wow:
  eventsourcing:
    state:
      bus:
        type: kafka
        local-first:
          enabled: true
```

## Prepare Key

- Prefix: `wow.prepare`

| Name | Data Type | Description | Default Value |
|------|-----------|-------------|---------------|
| `enabled` | Boolean | Enable PrepareKey functionality | `true` |
| `storage` | PrepareStorage | Storage backend for PrepareKey | `MONGO` |
| `base-packages` | List\<String\> | Base packages to scan for PrepareKey definitions | `[]` |

### PrepareStorage Values

| Value | Description |
|-------|-------------|
| `MONGO` | MongoDB (recommended) |
| `REDIS` | Redis |

```yaml
wow:
  prepare:
    enabled: true
    storage: mongo
    base-packages:
      - com.example.domain
```

## Environment-Specific Configuration

### Development Environment

```yaml
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
```

### Production Environment

```yaml
wow:
  command:
    bus:
      type: kafka
      local-first:
        enabled: true
  event:
    bus:
      type: kafka
      local-first:
        enabled: true
  eventsourcing:
    store:
      storage: mongo
    snapshot:
      enabled: true
      strategy: version_offset
      version-offset: 10
      storage: mongo
```

## Complete Configuration Example

```yaml
spring:
  application:
    name: order-service

wow:
  enabled: true
  context-name: order-service
  shutdown-timeout: 120s

  command:
    bus:
      type: kafka
      local-first:
        enabled: true

  event:
    bus:
      type: kafka
      local-first:
        enabled: true

  eventsourcing:
    store:
      storage: mongo
    snapshot:
      enabled: true
      strategy: version_offset
      version-offset: 10
      storage: mongo
    state:
      bus:
        type: kafka
        local-first:
          enabled: true

  kafka:
    bootstrap-servers:
      - kafka-0:9092
      - kafka-1:9092
      - kafka-2:9092
    topic-prefix: 'wow.'

  mongo:
    enabled: true
    auto-init-schema: true

  openapi:
    enabled: true

  webflux:
    enabled: true
    global-error:
      enabled: true
```
