---
title: Core Configuration
description: Exact reference for the Wow runtime, buses, event sourcing, snapshots, storage routing, query schema, and PrepareKey configuration.
outline: deep
---

# Core Configuration

This page records property names, types, defaults, and wiring boundaries. Use [Configuring a Wow Application](../../guide/configuration.md) to choose values for an environment. Defaults below come from `wow-spring-boot-starter` configuration classes; binding defaults and runtime fallbacks are stated separately.

## WowProperties

Configuration class: `WowProperties`; prefix: `wow`.

| Property | Type | Default | Runtime meaning |
| --- | --- | --- | --- |
| `wow.enabled` | Boolean | `true` | Master switch; Wow auto-configuration does not apply when `false` |
| `wow.context-name` | String? | `null` | Reads the required `spring.application.name` when absent |
| `wow.shutdown-timeout` | Duration | `60s` | Shared deadline for quiescing and reverse-order shutdown of the complete `WowRuntime` |
| `wow.shutdown-quiet-period` | Duration | `1s` | Continuous idle interval required before intake closes; new activity restarts it |

`shutdown-timeout` must be positive. `shutdown-quiet-period` must be non-negative and shorter than `shutdown-timeout`. Both must fit exactly in a signed 64-bit nanosecond value.

```yaml
spring:
  application:
    name: order-service

wow:
  shutdown-timeout: 60s
  shutdown-quiet-period: 1s
```

## BusProperties

`BusProperties` is reused by command, domain-event, and state-event channels.

| Relative property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `bus.type` | [`BusType`](#bustype) | `kafka` | Selects the bus implementation for this channel |
| `bus.local-first.enabled` | Boolean | `true` | Composes LocalFirst when a distributed bus exists |

The `kafka` default is only a property default. The runtime still needs the `kafka-support` capability and `wow.kafka.bootstrap-servers`; it is not evidence that Kafka is usable.

### BusType

| Value | Implementation required | Boundary |
| --- | --- | --- |
| `kafka` | `wow-kafka` / `kafka-support` | Distributed Kafka bus |
| `redis` | `wow-redis` / `redis-support` | Redis Streams bus; this value must be explicit |
| `in_memory` | Base Starter | Single process and non-durable |
| `no_op` | Base Starter | Accepts calls without real message processing; only for an explicit disabled path |

### LocalFirst Mode

LocalFirst always sends a distributed copy while attempting local admission. The distributed copy is marked locally handled only after every targeted local receiver accepts admission. It remains eligible for distributed processing when no subscriber exists, intake is closed, or local send fails.

#### Behavior and failure boundary

- LocalFirst removes a broker round trip from the fast path; it is not an exactly-once guarantee.
- A handler failure after successful local admission follows that handler's retry and acknowledgement policy. It does not retroactively re-enable the distributed copy.
- `no_op` and `in_memory` have no distributed bus to compose; LocalFirst settings do not add cross-instance behavior.

## Command Bus

Configuration class: `CommandProperties`; prefix: `wow.command`.

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `wow.command.bus.type` | `BusType` | `kafka` | Command bus |
| `wow.command.bus.local-first.enabled` | Boolean | `true` | Command LocalFirst |
| `wow.command.idempotency.enabled` | Boolean | `true` | Enables command idempotency precheck |
| `wow.command.idempotency.bloom-filter.ttl` | Duration | `1m` | Bloom-filter lifetime |
| `wow.command.idempotency.bloom-filter.expected-insertions` | Long | `1000000` | Expected insertions |
| `wow.command.idempotency.bloom-filter.fpp` | Double | `0.00001` | Target false-positive probability |

### IdempotencyProperties

The Bloom filter is an in-process precheck, not the source of truth. When the precheck rejects, `DefaultCommandGateway` still confirms the duplicate through the EventStore request-id check. Disabling it installs a no-op precheck; it does not turn client retries into exactly-once delivery.

#### BloomFilter

```yaml
wow:
  command:
    idempotency:
      enabled: true
      bloom-filter:
        ttl: 1m
        expected-insertions: 1000000
        fpp: 0.00001
```

## Event Bus

Configuration class: `EventProperties`; prefix: `wow.event`.

| Property | Type | Default |
| --- | --- | --- |
| `wow.event.bus.type` | `BusType` | `kafka` |
| `wow.event.bus.local-first.enabled` | Boolean | `true` |

Domain events are sent to this bus after the EventStore append succeeds. Its acknowledgement covers only the selected adapter's send boundary; it does not prove projection, processor, or saga completion.

## Event Sourcing

### EventStoreProperties

Configuration class: `EventStoreProperties`; prefix: `wow.eventsourcing.store`.

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `wow.eventsourcing.store.storage` | [`StorageType`](#storagetype) | `mongo` | Default EventStore binding |

#### StorageType

| Value | Implementation source | Notes |
| --- | --- | --- |
| `mongo` | `mongo-support` | MongoDB EventStore / SnapshotStore / query backends |
| `redis` | `redis-support` | Redis EventStore / SnapshotStore; no general dynamic query implementation |
| `elasticsearch` | `elasticsearch-support` | Elasticsearch EventStore / SnapshotStore / query backends |
| `in_memory` | Base Starter | Lost at process exit |
| `delay` | `mock-support` | Delayed test implementation, not a production backend |

Selecting a value does not add the implementation module. Auto-configuration fails when the matching capability or application-provided binding is missing.

### SnapshotProperties

Configuration class: `SnapshotProperties`; prefix: `wow.eventsourcing.snapshot`.

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `wow.eventsourcing.snapshot.enabled` | Boolean | `true` | Enables Snapshot Dispatcher and SnapshotStore |
| `wow.eventsourcing.snapshot.strategy` | [`Strategy`](#strategy) | `all` | Decides when to save snapshots |
| `wow.eventsourcing.snapshot.version-offset` | Int | `5` | Version interval for `version_offset` |
| `wow.eventsourcing.snapshot.storage` | `StorageType` | `mongo` | Default SnapshotStore binding |

Disabling snapshots installs `NoOpSnapshotStore`; configuring a snapshot storage route at the same time fails startup.

#### Strategy

| Value | Behavior |
| --- | --- |
| `all` | Save a snapshot for every processed StateEvent |
| `version_offset` | Save when the stored version trails by at least `version-offset` |

The `SNAPSHOT` stage means Snapshot Dispatcher processing completed. Under `version_offset`, it may complete without a new snapshot write.

### StorageRoutingProperties

Configuration class: `StorageRoutingProperties`; prefix: `wow.eventsourcing.storage-routing`.

| Property | Type | Default |
| --- | --- | --- |
| `wow.eventsourcing.storage-routing.aggregates` | `Map<String, AggregateStorageRouteProperties>` | `{}` |

A map key is either an `aggregate` in the current context or a qualified `context.aggregate`. Each route may contain `event` and `snapshot` channels. An omitted channel falls back to the default store.

| Exact property pattern | Type | Rule |
| --- | --- | --- |
| `wow.eventsourcing.storage-routing.aggregates.<route>.event.storage` | `StorageType?` | Selects an EventStore type binding |
| `wow.eventsourcing.storage-routing.aggregates.<route>.event.binding` | String? | Selects a named EventStore binding |
| `wow.eventsourcing.storage-routing.aggregates.<route>.snapshot.storage` | `StorageType?` | Selects a SnapshotStore type binding |
| `wow.eventsourcing.storage-routing.aggregates.<route>.snapshot.binding` | String? | Selects a named SnapshotStore binding |

A configured channel must set exactly one of `storage` and `binding`. An empty channel, both values, an unknown aggregate, a missing store binding, or a missing corresponding query-backend factory binding fails fast.

```yaml
wow:
  eventsourcing:
    storage-routing:
      aggregates:
        order-service.Audit:
          event:
            binding: archiveEventStore
          snapshot:
            storage: mongo
```

## State Event Bus

Configuration class: `StateProperties`; prefix: `wow.eventsourcing.state`.

| Property | Type | Default |
| --- | --- | --- |
| `wow.eventsourcing.state.bus.type` | `BusType` | `kafka` |
| `wow.eventsourcing.state.bus.local-first.enabled` | Boolean | `true` |

StateEvents drive snapshot processing. With a distributed implementation, include this channel's lag and failures when diagnosing the `SNAPSHOT` stage.

## Prepare Key

Configuration class: `PrepareProperties`; prefix: `wow.prepare`.

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `wow.prepare.enabled` | Boolean | `true` | Enables PrepareKey |
| `wow.prepare.storage` | `PrepareStorage` | `mongo` | PrepareKey storage |
| `wow.prepare.base-packages` | List\<String\> | `[]` | Packages scanned for PrepareKey definitions |

### PrepareStorage Values

| Value | Required capability |
| --- | --- |
| `mongo` | `mongo-support` |
| `redis` | `redis-support` |

A purely in-memory application has no PrepareStorage implementation and must set `wow.prepare.enabled: false`.

## QueryProperties

Configuration class: `QueryProperties`; prefix: `wow.query`.

| Property | Type | Default | Values |
| --- | --- | --- | --- |
| `wow.query.schema.validation-mode` | `QuerySchemaValidationMode` | `compatible` | `compatible`, `strict` |

`compatible` accepts exact and compatible schema resolutions; `strict` accepts only exact ones. This controls query-schema resolution, not HTTP query-cost limits; see [Infrastructure Configuration](./infrastructure.md#webflux) for those limits.

## Environment-Specific Configuration

Reference pages do not define a development or production template. For each environment, explicitly choose all three buses, EventStore, SnapshotStore, PrepareStorage, and capabilities. See [Configuring a Wow Application](../../guide/configuration.md#environment-layers) for the task flow.

### Development Environment

A fully in-memory mode must set all three buses and both stores to `in_memory`, and disable PrepareKey. Otherwise untouched core defaults still select Kafka and MongoDB.

### Production Environment

There is no hidden production-safe default. Record the implementation, owner, durability, recovery method, and verified evidence for every channel; property defaults alone are insufficient.

## Complete Configuration Example

This example is complete only for **core selections**. Connections, credentials, and infrastructure tuning belong outside this page.

```yaml
spring:
  application:
    name: order-service

wow:
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
  query:
    schema:
      validation-mode: compatible
```
