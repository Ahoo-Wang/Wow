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
      storage: mongo               # mongo, r2dbc, redis, elasticsearch, in_memory, delay
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

  redis:
    enabled: true

  r2dbc:
    enabled: true
    datasource:
      type: simple                 # simple or sharding

  elasticsearch:
    enabled: true

  # Features
  compensation:
    enabled: true
    host: https://your-dashboard.example.com
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

## Command Bus

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.command.bus.type` | BusType | `kafka` | Command bus implementation |
| `wow.command.bus.local-first.enabled` | Boolean | `true` | Enable LocalFirst mode |
| `wow.command.idempotency.enabled` | Boolean | `true` | Enable idempotency checking |
| `wow.command.idempotency.bloom-filter.expected-insertions` | Int | `1000000` | Bloom filter capacity |
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
| `wow.eventsourcing.snapshot.version-offset` | Int | `10` | Version offset for VERSION_OFFSET strategy |
| `wow.eventsourcing.snapshot.storage` | StorageType | `mongo` | Snapshot storage backend |

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

### Redis

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.redis.enabled` | Boolean | `true` | Enable Redis support |

### R2DBC

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.r2dbc.enabled` | Boolean | `true` | Enable R2DBC support |
| `wow.r2dbc.datasource.type` | Type | `simple` | `simple` or `sharding` |

### Elasticsearch

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.elasticsearch.enabled` | Boolean | `true` | Enable Elasticsearch support |

## Features

### Event Compensation

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.compensation.enabled` | Boolean | `true` | Enable event compensation |
| `wow.compensation.host` | String | | Dashboard URL for notification links |
| `wow.compensation.webhook.weixin.url` | String | | WeChat Work webhook URL |
| `wow.compensation.webhook.weixin.events` | List\<String\> | all events | Notification event types |

### OpenAPI

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.openapi.enabled` | Boolean | `true` | Enable OpenAPI support |

### WebFlux

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.webflux.enabled` | Boolean | `true` | Enable WebFlux support |
| `wow.webflux.global-error.enabled` | Boolean | `true` | Enable global error handling |

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
| `r2dbc` | R2DBC-compatible databases |
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
