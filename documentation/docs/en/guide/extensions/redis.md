---
title: Redis
description: Use Redis Streams, Lua, and native data structures for Wow buses and stores.
---

# Redis

`wow-redis` provides Redis implementations of all three distributed message buses, `EventStore`, `SnapshotStore`, and `PrepareKeyFactory`. Use it when the team already operates Redis and accepts its Streams, persistence, and memory-capacity boundaries. It is not a transparent Kafka or database replacement.

## Architecture Overview

Message buses use Redis Streams consumer groups; EventStore uses sorted sets, request sets, and Lua; SnapshotStore uses strings plus version-guarded Lua; PrepareKey uses hashes and Lua. Wow defines key layouts and maps script results. Redis owns script atomicity, cluster slots, persistence, replication, eviction, and failover.

## Installation

Direct dependencies:

```kotlin
implementation("me.ahoo.wow:wow-redis")
implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
```

Starter capability:

```kotlin
implementation("me.ahoo.wow:wow-spring-boot-starter") {
    capabilities { requireCapability("me.ahoo.wow:redis-support") }
}
```

## Configuration

This explicitly selects Redis for buses, event/snapshot storage, and Prepare:

```yaml
spring:
  data:
    redis:
      url: redis://localhost:6379

wow:
  command:
    bus:
      type: redis
  event:
    bus:
      type: redis
  eventsourcing:
    store:
      storage: redis
    snapshot:
      storage: redis
    state:
      bus:
        type: redis
  prepare:
    storage: redis
```

`wow.redis.enabled=true`. Pending recovery defaults to enabled with `min-idle-time=5m`, `interval=30s`, and `batch-size=100`. Both durations must be at least `1ms`, and batch size must be positive. `spring.data.redis.*` owns connection, cluster, Sentinel, TLS, and pool settings.

## Command Bus

`RedisCommandBus` is created only for `wow.command.bus.type=redis`. A successful send means Redis stored the Stream record, not that a command processor handled it.

### Stream Naming Rules

The default command Stream is `${contextAlias}.${aggregateName}:command`; domain and state events use `:event` and `:state`. Names come from `NamedAggregate.toStringWithAlias()` and are data-migration concerns, not arbitrary labels.

### Consumer Groups

The subscription receiver group becomes the Redis consumer group. `BUSYGROUP` during concurrent group creation is normal; permission, wrong-type, and connectivity errors still fail.

### Pending-message recovery

Recovery periodically scans entries idle beyond the threshold and claims them after confirming the original consumer is inactive. It recovers only pending entries still present in the Stream, never trimmed, deleted, or unpersisted data.

## Event Bus

Domain and state events share the Streams pipeline and explicit acknowledgment semantics. Failed processing can redeliver, so handlers must be idempotent.

### Domain Event Stream

`RedisDomainEventBus` is created for `wow.event.bus.type=redis` and serves domain-event subscribers such as projections and Sagas.

### State Event Stream

`RedisStateEventBus` is created for `wow.eventsourcing.state.bus.type=redis` and serves snapshots and state subscribers. Module presence does not mean this bus is selected.

## Event Store

`RedisEventStore` uses canonical v2 keys. Hash tags keep one aggregate's event, request index, and ID bucket in the same cluster slot for Lua operations.

### Data Structure

Events are stored by version in a sorted set. A 128-bucket aggregate-ID index supports stable scans, and every aggregate has its own request-ID set. Component encoding prevents `:`, `{}`, or Unicode in user IDs from corrupting key structure.

### Request Idempotency

`event_stream_append.lua` atomically checks event count, first-version aggregate ID, and request ID before writing event and indexes. Results map to version conflict, duplicate aggregate ID, duplicate request, or success. Do not duplicate these checks non-atomically in application code.

### Aggregate ID Scanning

Scanning merges 128 buckets ordered by a canonical sortable index-member encoding. It is a framework maintenance/query contract, not a wrapper around Redis `SCAN`.

## Snapshot Storage

`snapshot_save.lua` calls `SET` only when the candidate version is not older. An older snapshot is a no-op. A missing or non-numeric stored `version` raises an error to prevent silent overwrite of corrupt data.

### Upgrade boundary

Starter inspects aggregates actually routed to Redis EventStore for the legacy shared request index and legacy bucket layout. Detection fails closed. The runtime reads and writes canonical v2 only and does not migrate keys online. Stop old writers, migrate or rebuild offline, then start the new runtime.

## Prepare Key

`wow.prepare.storage=redis` is required for `RedisPrepareKeyFactory`. Lua atomically implements prepare, reprepare, and rollback with a single explicit hash tag. Callers own contention handling and business compensation.

## Connection Pool Configuration

Use native Spring Data Redis/Lettuce settings. Wow does not own pool size, command timeout, topology refresh, or duplicate driver validation.

## Cluster Configuration

Canonical layout keeps keys for one Lua operation in one slot, but does not configure cluster, resharding, or replicas. Verify scripts, failover, and slot migration on the actual cluster.

## Sentinel Configuration

Spring Boot Redis properties own Sentinel master, nodes, authentication, and TLS. The module consumes the resulting `ReactiveStringRedisTemplate`.

## Performance Optimization

Observe command latency, Stream lag, pending count, memory, and script duration first. Do not enable trimming or change eviction merely because the module is present.

### Batch Operations

The bus uses native Stream batch reads. EventStore and SnapshotStore use Lua for single-aggregate atomicity. There is no configurable Mongo/Elasticsearch-style write batcher.

### Memory Optimization

If event history is authoritative, eviction or unaudited trimming can cause unrecoverable loss. Plan capacity and retention from event volume, snapshots, and replay objectives.

### Recommended Configuration

There is no universal Redis server template. Select persistence, replication, `maxmemory-policy`, backup, and recovery targets based on whether Redis is an authoritative store, and record tested evidence.

# Redis server configuration recommendations

When Redis carries EventStore, prevent ordinary cache eviction from deleting v2 event/snapshot keys. When it carries only buses, retain Streams beyond the maximum recovery window.

## Troubleshooting

Current implementation and tests cover invalid recovery bounds, corrupt snapshot versions, legacy EventStore layouts, Lua version/request conflicts, and malformed pending/consumer metadata.

### Common Issues

Separate connectivity/topology, key layout, Lua result, Stream group, and capacity failures.

#### 1. Connection Timeout

Check Spring Redis URL, DNS, authentication, TLS, Sentinel/cluster topology, and network policy. Retain the original Lettuce exception.

#### 2. Out of Memory

Stop writes and determine whether eviction occurred. Do not delete event/request/index keys to recover temporary space; expand capacity or follow a verified migration/retention procedure.

#### 3. Stream Consumption Delay

Inspect lag, pending entries, idle consumers, recovery observers, and handler latency. Recovery can claim visible pending entries only; it cannot recreate trimmed records.

## Monitoring Metrics

Monitor Redis latency, memory, eviction, persistence, replication, Stream length/lag/pending, Lua errors, and Wow handler failures. A successful module check is not proof of target Redis health.

## Complete Configuration Example

```yaml
spring:
  data:
    redis:
      url: ${REDIS_URL}

wow:
  redis:
    enabled: true
    message-bus:
      recovery:
        enabled: true
        min-idle-time: 5m
        interval: 30s
        batch-size: 100
  eventsourcing:
    store:
      storage: redis
    snapshot:
      storage: redis
```

## Best Practices

- Select every bus/store explicitly; do not infer runtime wiring from a capability.
- Treat canonical v2 adoption as a data migration, not an online compatibility bridge.
- Keep handlers idempotent and rehearse pending claim, failover, and recovery.
- Disable unaudited eviction/trimming for authoritative data and verify backup restoration.

Focused check:

```bash
./gradlew :wow-redis:check
```

Next, read [Infrastructure configuration](../../reference/config/infrastructure.md) and [Migration](../migration.md).
