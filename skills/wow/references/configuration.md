# Wow Configuration Reference

All configuration is under the `wow` prefix in `application.yaml`.

## Core Configuration

```yaml
wow:
  enabled: true                    # Enable/disable Wow framework
  context-name: my-service         # Bounded context name (default: spring.application.name)
  shutdown-timeout: 60s            # Graceful shutdown timeout
```

## Complete Configuration Template

```yaml
wow:
  enabled: true
  context-name: order-service
  shutdown-timeout: 120s

  # Command Bus
  command:
    bus:
      type: kafka                  # kafka, redis, in_memory, no_op
      local-first:
        enabled: true              # Process local messages first
    idempotency:
      enabled: true
      bloom-filter:
        expected-insertions: 1000000
        ttl: PT60S
        fpp: 0.00001

  # Event Bus
  event:
    bus:
      type: kafka
      local-first:
        enabled: true

  # State Event Bus
  eventsourcing:
    state:
      bus:
        type: kafka
        local-first:
          enabled: true
    store:
      storage: mongo               # mongo, redis, elasticsearch, in_memory, delay
    snapshot:
      enabled: true
      strategy: all                # all, version_offset
      storage: mongo
      version-offset: 10

  # Infrastructure
  kafka:
    bootstrap-servers:
      - localhost:9092
    topic-prefix: 'wow.'

  mongo:
    enabled: true
    auto-init-schema: true
    event-stream-database: wow_event_db
    snapshot-database: wow_snapshot_db
    prepare-database: wow_prepare_db

  prepare:
    enabled: true
    storage: mongo                  # mongo or redis
    base-packages:
      - me.example.prepare

  redis:
    enabled: true


  elasticsearch:
    enabled: true
    auto-init-template: true

  openapi:
    enabled: true

  webflux:
    enabled: true
    global-error:
      enabled: true
```

## Command Bus

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.command.bus.type` | BusType | `kafka` | Command bus implementation |
| `wow.command.bus.local-first.enabled` | Boolean | `true` | Enable LocalFirst mode |
| `wow.command.idempotency.enabled` | Boolean | `true` | Enable idempotency checking |
| `wow.command.idempotency.bloom-filter.expected-insertions` | Long | `1000000` | Bloom filter capacity |
| `wow.command.idempotency.bloom-filter.ttl` | Duration | `PT60S` | Bloom filter TTL |
| `wow.command.idempotency.bloom-filter.fpp` | Double | `0.00001` | False positive probability |

## Event Bus

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.event.bus.type` | BusType | `kafka` | Event bus implementation |
| `wow.event.bus.local-first.enabled` | Boolean | `true` | Enable LocalFirst mode |

## State Event Bus

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.eventsourcing.state.bus.type` | BusType | `kafka` | State event bus type |
| `wow.eventsourcing.state.bus.local-first.enabled` | Boolean | `true` | Enable LocalFirst mode |

## Dispatcher Tuning

`stripe-count` controls aggregate-ID ordering domains; it is not a thread count.
`scheduler-pool-size` controls the dedicated Reactor Scheduler workers created for each
materialized named aggregate type; it is not a role-wide thread cap.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.command.dispatcher.stripe-count` | Int | `64 × CPU` | Command ordering stripes |
| `wow.command.dispatcher.scheduler-pool-size` | Int | `CPU` | Workers per command aggregate type |
| `wow.event.dispatcher.stripe-count` | Int | `64 × CPU` | Domain event ordering stripes |
| `wow.event.dispatcher.scheduler-pool-size` | Int | `CPU` | Workers per event aggregate type |
| `wow.projection.dispatcher.stripe-count` | Int | `64 × CPU` | Projection ordering stripes |
| `wow.projection.dispatcher.scheduler-pool-size` | Int | `CPU` | Workers per projection aggregate type |
| `wow.saga.stateless.dispatcher.stripe-count` | Int | `64 × CPU` | Stateless saga ordering stripes |
| `wow.saga.stateless.dispatcher.scheduler-pool-size` | Int | `CPU` | Workers per stateless saga aggregate type |

Every value must be greater than `0`. When a role-specific property is absent,
`stripe-count` falls back to `-Dwow.parallelism` and `scheduler-pool-size` falls back to
`-Dreactor.schedulers.defaultPoolSize`. Keep the defaults for unknown workloads; lower values
must be validated against CPU-heavy handlers, hash collisions, head-of-line blocking, tail
latency, and the number of active aggregate types.

## Event Sourcing

### Event Store

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.eventsourcing.store.storage` | StorageType | `mongo` | Event store backend |

### Snapshot

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.eventsourcing.snapshot.enabled` | Boolean | `true` | Enable snapshot functionality |
| `wow.eventsourcing.snapshot.strategy` | Strategy | `all` | Snapshot strategy: `all` or `version_offset` |
| `wow.eventsourcing.snapshot.version-offset` | Int | `5` | Version offset for VERSION_OFFSET strategy (`DEFAULT_VERSION_OFFSET`) |
| `wow.eventsourcing.snapshot.storage` | StorageType | `mongo` | Snapshot storage backend |

### Snapshot Checkpoint

Persist the latest snapshot version as an immutable checkpoint at a fixed version interval. The checkpoint is written via `VersionIntervalCheckpointStrategy`; it does not change projection or rebuild behavior on its own.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.eventsourcing.snapshot.checkpoint.enabled` | Boolean | `false` | Persist the snapshot version checkpoint |
| `wow.eventsourcing.snapshot.checkpoint.version-interval` | Int | `100` | How often (in versions) to persist the checkpoint; must be positive |

### Storage Routing

Route different aggregates to different storage backends within a single service. When a matching route is configured, Wow installs a `RoutingEventStore` / `RoutingSnapshotStore`; unlisted aggregates fall back to the default storage. A configured channel **must set exactly one** of `storage` or `binding` — an empty channel (e.g. `event: {}`) fails fast at startup.

```yaml
wow:
  eventsourcing:
    storage-routing:
      aggregates:
        HotAggregate:
          event:
            storage: redis
          snapshot:
            storage: redis
```

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.eventsourcing.storage-routing.aggregates.<name>.event.storage` | StorageType | _(required if `binding` absent)_ | Event store backend for this aggregate |
| `wow.eventsourcing.storage-routing.aggregates.<name>.event.binding` | String | _(required if `storage` absent)_ | Bound event store bean name |
| `wow.eventsourcing.storage-routing.aggregates.<name>.snapshot.storage` | StorageType | _(required if `binding` absent)_ | Snapshot store backend for this aggregate |
| `wow.eventsourcing.storage-routing.aggregates.<name>.snapshot.binding` | String | _(required if `storage` absent)_ | Bound snapshot store bean name |

## Infrastructure

### Kafka

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.kafka.enabled` | Boolean | `true` | Enable Kafka support |
| `wow.kafka.bootstrap-servers` | List\<String\> | | Kafka broker addresses |
| `wow.kafka.topic-prefix` | String | `wow.` | Topic name prefix |

### MongoDB

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.mongo.enabled` | Boolean | `true` | Enable MongoDB support |
| `wow.mongo.auto-init-schema` | Boolean | `true` | Auto-create collections |
| `wow.mongo.event-stream-database` | String | Spring MongoDB database | Event stream database |
| `wow.mongo.snapshot-database` | String | Spring MongoDB database | Snapshot database |
| `wow.mongo.prepare-database` | String | Spring MongoDB database | Prepare key database |

### Prepare

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.prepare.enabled` | Boolean | `true` | Enable PrepareKey support |
| `wow.prepare.storage` | PrepareStorage | `mongo` | PrepareKey backend: `mongo` or `redis` |
| `wow.prepare.base-packages` | List\<String\> | empty | Extra packages scanned for `@PreparableKey` interfaces; the registrar also scans application base packages |

### Redis

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.redis.enabled` | Boolean | `true` | Enable Redis support |


### Elasticsearch

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.elasticsearch.enabled` | Boolean | `true` | Enable Elasticsearch support |
| `wow.elasticsearch.auto-init-template` | Boolean | `true` | Auto-create Elasticsearch templates |

## Features

### OpenAPI

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.openapi.enabled` | Boolean | `true` | Enable OpenAPI support |

### WebFlux

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.webflux.enabled` | Boolean | `true` | Enable WebFlux support |
| `wow.webflux.global-error.enabled` | Boolean | `true` | Enable global error handling |
| `wow.webflux.batch.concurrency` | Int | `1` | Concurrency for batch command requests |
| `wow.webflux.batch.prefetch` | Int | `1` | Prefetch count for batch command requests |
| `wow.webflux.command.request.appender.agent.enabled` | Boolean | `true` | Append client `User-Agent` to command request context (set `false` to disable) |
| `wow.webflux.command.request.appender.ip.enabled` | Boolean | `true` | Append client IP to command request context (set `false` to disable) |

### Observability Toggles

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.opentelemetry.enabled` | Boolean | `true` | Enable OpenTelemetry tracing instrumentation (`matchIfMissing`; requires `wow-opentelemetry` on classpath) |
| `wow.metrics.enabled` | Boolean | `true` | Enable Micrometer/Prometheus metrics collection (`matchIfMissing`) |

## Bus Types

| Type | Description |
|------|-------------|
| `kafka` | Apache Kafka (recommended for production) |
| `redis` | Redis Streams |
| `in_memory` | In-memory (for testing) |
| `no_op` | No-op (for special cases) |

## Storage Types

| Type | Description |
|------|-------------|
| `mongo` | MongoDB (recommended for event store) |
| `redis` | Redis for high-performance scenarios |
| `elasticsearch` | Elasticsearch for full-text search |
| `in_memory` | In-memory (for testing) |
| `delay` | Delay (for testing) |

## Environment-Specific Configurations

### Development

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
      strategy: all
```

### Production

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
  kafka:
    bootstrap-servers:
      - kafka-0:9092
      - kafka-1:9092
      - kafka-2:9092
  mongo:
    enabled: true
    auto-init-schema: true
```
