---
title: Configuration
description: Comprehensive configuration options through Spring Boot configuration properties mechanism.
---

# Configuration

The Wow framework provides comprehensive configuration options through Spring Boot's configuration properties mechanism. This guide covers all available configuration options and how to configure them effectively.

## Configuration Structure

Wow configuration is organized under the `wow` prefix in your `application.yaml` or `application.yml` file:

```yaml
wow:
  enabled: true                    # Enable/disable Wow framework
  context-name: my-service         # Bounded context name
  shutdown-timeout: 60s           # Graceful shutdown timeout

  # Command Bus Configuration
  command:
    bus:
      type: kafka                  # kafka, redis, in_memory, no_op
      local-first:
        enabled: true              # Process local messages first

  # Event Bus Configuration
  event:
    bus:
      type: kafka
      local-first:
        enabled: true

  # State Event Bus Configuration
  eventsourcing:
    state:
      bus:
        type: kafka
        local-first:
          enabled: true
    store:
      storage: mongo               # Event store type: mongo, redis, elasticsearch, in_memory, delay
    snapshot:
      enabled: true
      strategy: all                # all, version_offset
      storage: mongo
      version-offset: 10

  # Infrastructure-specific configurations
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

  redis:
    enabled: true


  elasticsearch:
    enabled: true

  compensation:
    enabled: true
    webhook:
      weixin:
        url: <webhook-url>
        events:
          - execution_failed_created
          - execution_failed_applied
          - execution_success_applied

  openapi:
    enabled: true

  webflux:
    enabled: true
    global-error:
      enabled: true
```

## Core Configuration

### WowProperties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.enabled` | Boolean | `true` | Enable/disable the Wow framework |
| `wow.context-name` | String | `${spring.application.name}` | Bounded context name for the service |
| `wow.shutdown-timeout` | Duration | `60s` | Graceful shutdown timeout duration |

```yaml
wow:
  enabled: true
  context-name: order-service
  shutdown-timeout: 120s
```

## Command Bus Configuration

### CommandProperties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.command.bus.type` | BusType | `kafka` | Command bus implementation type |
| `wow.command.bus.local-first.enabled` | Boolean | `true` | Enable LocalFirst mode |

```yaml
wow:
  command:
    bus:
      type: kafka
      local-first:
        enabled: true
```

## Event Bus Configuration

### EventProperties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.event.bus.type` | BusType | `kafka` | Event bus implementation type |
| `wow.event.bus.local-first.enabled` | Boolean | `true` | Enable LocalFirst mode |

```yaml
wow:
  event:
    bus:
      type: kafka
      local-first:
        enabled: true
```

## State Event Bus Configuration

### StateProperties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.eventsourcing.state.bus.type` | BusType | `kafka` | State event bus type |
| `wow.eventsourcing.state.bus.local-first.enabled` | Boolean | `true` | Enable LocalFirst mode |

```yaml
wow:
  eventsourcing:
    state:
      bus:
        type: kafka
        local-first:
          enabled: true
```

## Event Sourcing Configuration

### Event Store Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.eventsourcing.store.storage` | StorageType | `mongo` | Event store backend |

```yaml
wow:
  eventsourcing:
    store:
      storage: mongo    # mongo, redis, elasticsearch, in_memory, delay
```

### Snapshot Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.eventsourcing.snapshot.enabled` | Boolean | `true` | Enable snapshot functionality |
| `wow.eventsourcing.snapshot.strategy` | Strategy | `all` | Snapshot strategy |
| `wow.eventsourcing.snapshot.version-offset` | Int | `10` | Version offset for VERSION_OFFSET strategy |
| `wow.eventsourcing.snapshot.storage` | StorageType | `mongo` | Snapshot storage backend |

```yaml
wow:
  eventsourcing:
    snapshot:
      enabled: true
      strategy: version_offset      # all, version_offset
      version-offset: 10
      storage: mongo
```

### Aggregate Storage Routing

`wow.eventsourcing.storage-routing` is optional. When an aggregate or channel is not configured, Wow keeps using the corresponding global default from `wow.eventsourcing.store.storage` or `wow.eventsourcing.snapshot.storage`.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.eventsourcing.storage-routing.aggregates.*.event.storage` | StorageType | | EventStore backend for one aggregate |
| `wow.eventsourcing.storage-routing.aggregates.*.event.binding` | String | | Named EventStore binding for one aggregate |
| `wow.eventsourcing.storage-routing.aggregates.*.snapshot.storage` | StorageType | | SnapshotStore backend for one aggregate |
| `wow.eventsourcing.storage-routing.aggregates.*.snapshot.binding` | String | | Named SnapshotStore binding for one aggregate |

```yaml
wow:
  context-name: order-service
  eventsourcing:
    store:
      storage: mongo
    snapshot:
      enabled: true
      storage: mongo
    storage-routing:
      aggregates:
        order:
          event:
            storage: redis
        cart:
          snapshot:
            storage: redis
        audit:
          event:
            binding: archive-event-store
          snapshot:
            binding: archive-snapshot-store
```

- `order` resolves to `order-service.order` by using the current `wow.context-name`.
- Full aggregate keys such as `order-service.order` are also accepted. Quote the key in YAML when needed.
- `event` routes only affect the aggregate `EventStore`; `snapshot` routes only affect the aggregate `SnapshotStore`.
- `event.binding` and `snapshot.binding` point to named custom bindings registered by application code or infrastructure auto-configuration.
- `storage` and `binding` are mutually exclusive inside the same `event` or `snapshot` channel.
- Changing a route to another backend does not migrate existing event streams or snapshots.
- The snapshot abstraction is now named `SnapshotStore`. Deprecated `SnapshotRepository` Kotlin compatibility aliases remain transitional and should not be used in new code.

## Infrastructure Configuration

### Kafka Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.kafka.enabled` | Boolean | `true` | Enable Kafka support |
| `wow.kafka.bootstrap-servers` | List\<String\> | | Kafka broker addresses |
| `wow.kafka.topic-prefix` | String | `wow.` | Topic name prefix |

```yaml
wow:
  kafka:
    enabled: true
    bootstrap-servers:
      - kafka-0:9092
      - kafka-1:9092
      - kafka-2:9092
    topic-prefix: 'wow.'
```

### MongoDB Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.mongo.enabled` | Boolean | `true` | Enable MongoDB support |
| `wow.mongo.auto-init-schema` | Boolean | `true` | Auto-create collections |
| `wow.mongo.event-stream-database` | String | Spring MongoDB database | Event stream database |
| `wow.mongo.snapshot-database` | String | Spring MongoDB database | Snapshot database |
| `wow.mongo.prepare-database` | String | Spring MongoDB database | Prepare key database |

```yaml
wow:
  mongo:
    enabled: true
    auto-init-schema: true
    event-stream-database: wow_event_db
    snapshot-database: wow_snapshot_db
    prepare-database: wow_prepare_db
```

### Redis Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.redis.enabled` | Boolean | `true` | Enable Redis support |

```yaml
wow:
  redis:
    enabled: true
```


### Elasticsearch Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.elasticsearch.enabled` | Boolean | `true` | Enable Elasticsearch support |

```yaml
wow:
  elasticsearch:
    enabled: true
```

## Feature Configuration

### Event Compensation Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.compensation.enabled` | Boolean | `true` | Enable event compensation |
| `wow.compensation.webhook.weixin.url` | String | | WeChat Work webhook URL |
| `wow.compensation.webhook.weixin.events` | List\<String\> | See description | Notification events |

```yaml
wow:
  compensation:
    enabled: true
    webhook:
      weixin:
        url: https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx
        events:
          - execution_failed_created
          - execution_failed_applied
          - execution_success_applied
```

### OpenAPI Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.openapi.enabled` | Boolean | `true` | Enable OpenAPI support |

```yaml
wow:
  openapi:
    enabled: true
```

### WebFlux Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.webflux.enabled` | Boolean | `true` | Enable WebFlux support |
| `wow.webflux.global-error.enabled` | Boolean | `true` | Enable global error handling |

```yaml
wow:
  webflux:
    enabled: true
    global-error:
      enabled: true
```

### BI Script Configuration

These properties configure the ClickHouse SQL returned by `GET /wow/bi/script`:

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.bi.script.database` | String | `bi_db` | Database for distributed/local state and command tables and expansion views |
| `wow.bi.script.consumer-database` | String | `bi_db_consumer` | Database for Kafka queue tables and consumer materialized views |
| `wow.bi.script.cluster` | String | `{cluster}` | ClickHouse cluster name used by `ON CLUSTER` and `Distributed` |
| `wow.bi.script.installation` | String | `{installation}` | Installation segment in the replicated table path |
| `wow.bi.script.shard` | String | `{shard}` | Shard segment in the replicated table path |
| `wow.bi.script.replica` | String | `{replica}` | Replica name passed to replicated table engines |
| `wow.bi.script.timezone` | String | `Asia/Shanghai` | ClickHouse timezone for generated date-time columns and conversions |
| `wow.bi.script.kafka-bootstrap-servers` | String | Inherit `wow.kafka.bootstrap-servers`; otherwise `localhost:9093` | BI Kafka broker override; multiple inherited brokers are joined with commas |
| `wow.bi.script.topic-prefix` | String | Inherit `wow.kafka.topic-prefix`; otherwise `wow.` | BI topic prefix override |
| `wow.bi.script.max-expansion-depth` | Int | `5` | Maximum complex-property expansion depth; must be at least `1` |
| `wow.bi.script.unsupported-type-strategy` | Enum | `RAW_JSON` | `RAW_JSON` emits a scoped JSON convenience projection and a diagnostic; the exact lexical value is recovered from `__state` at the recovery `__path`; `FAIL` stops generation |

```yaml
wow:
  bi:
    script:
      database: bi_db
      consumer-database: bi_db_consumer
      cluster: '{cluster}'
      installation: '{installation}'
      shard: '{shard}'
      replica: '{replica}'
      timezone: Asia/Shanghai
      kafka-bootstrap-servers: kafka-0:9092,kafka-1:9092
      topic-prefix: 'wow.'
      max-expansion-depth: 5
      unsupported-type-strategy: RAW_JSON
```

Kafka and topic settings use this precedence:

1. Explicit `wow.bi.script.kafka-bootstrap-servers` / `wow.bi.script.topic-prefix`, even when the value equals the default;
2. The corresponding `wow.kafka.bootstrap-servers` / `wow.kafka.topic-prefix`, with multiple brokers joined by commas;
3. The `BiScriptOptions` domain defaults `localhost:9093` / `wow.`.

Every other nullable binding falls back directly to its `BiScriptOptions` default when absent. The Starter performs validation when constructing the domain options: blank required strings, control characters, and `max-expansion-depth < 1` all fail application startup.

See [Business Intelligence](./bi) for structured result diagnostics, current expansion semantics, and lossless mappings.

## Bus Type

The framework supports multiple bus implementations:

| Type | Description |
|------|-------------|
| `kafka` | Apache Kafka message bus (recommended for production) |
| `redis` | Redis Streams message bus |
| `in_memory` | In-memory message bus (for testing) |
| `no_op` | No-op message bus (for special cases) |

## Storage Type

For event stores and snapshots:

| Type | Description |
|------|-------------|
| `mongo` | MongoDB (recommended for event store) |
| `redis` | Redis for high-performance scenarios |
| `elasticsearch` | Elasticsearch for full-text search |

## Complete Example

```yaml
spring:
  application:
    name: order-service

  data:
    mongodb:
      uri: mongodb://localhost:27017/wow_db
    redis:
      host: localhost
      port: 6379


  elasticsearch:
    uris:
      - http://localhost:9200

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
    state:
      bus:
        type: kafka
        local-first:
          enabled: true
    store:
      storage: mongo
    snapshot:
      enabled: true
      strategy: version_offset
      version-offset: 10
      storage: mongo
  kafka:
    bootstrap-servers:
      - localhost:9092
    topic-prefix: 'wow.'
  mongo:
    enabled: true
    auto-init-schema: true
  elasticsearch:
    enabled: true
  compensation:
    enabled: true
  openapi:
    enabled: true
  webflux:
    enabled: true
    global-error:
      enabled: true

management:
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true
  endpoints:
    web:
      exposure:
        include:
          - health
          - wow
          - cosid

springdoc:
  show-actuator: true
```

## Configuration in Different Environments

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

## Configuration References

For detailed configuration of specific modules, see:
- [Kafka Extension](./extensions/kafka)
- [MongoDB Extension](./extensions/mongo)
- [Redis Extension](./extensions/redis)
- [Elasticsearch Extension](./extensions/elasticsearch)
- [Event Compensation](./event-compensation)
- [Command Configuration](./reference/config/command)
- [Event Configuration](./reference/config/event)
- [Event Sourcing Configuration](./reference/config/eventsourcing)
