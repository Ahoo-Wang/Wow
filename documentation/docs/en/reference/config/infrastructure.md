---
title: Infrastructure Configuration
description: Configuration options for infrastructure integrations including Kafka, MongoDB, Redis, Elasticsearch, and WebFlux.
---

# Infrastructure Configuration

## Kafka

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.kafka.enabled` | Boolean | `true` | Enable Kafka integration |
| `wow.kafka.bootstrap-servers` | List\<String\> | (required) | Kafka bootstrap server addresses |
| `wow.kafka.topic-prefix` | String | `wow.` | Topic name prefix |
| `wow.kafka.properties` | Map\<String, String\> | `{}` | Additional Kafka client properties |
| `wow.kafka.producer` | Map\<String, String\> | `{}` | Kafka producer-specific properties |
| `wow.kafka.consumer` | Map\<String, String\> | `{}` | Kafka consumer-specific properties |
| `wow.kafka.receiver.prefetch-batches` | Integer | `1` | Kafka poll batches prefetched by the reactive receiver |
| `wow.kafka.receiver.max-deferred-commits` | Integer | `1` | Out-of-order commits retained to preserve offset gaps |
| `wow.kafka.receiver.retry-attempts` | Long | `3` | Retry attempts per consecutive receiver failure burst |
| `wow.kafka.receiver.retry-backoff` | Duration | `10s` | Minimum receiver retry backoff |
| `wow.kafka.receiver.decode-failure-strategy` | Enum | `FAIL` | Invalid record policy: `FAIL` or `ACKNOWLEDGE` |

```yaml
wow:
  kafka:
    enabled: true
    bootstrap-servers:
      - localhost:9092
    topic-prefix: "wow."
    receiver:
      prefetch-batches: 1
      max-deferred-commits: 1
      retry-attempts: 3
      retry-backoff: 10s
      decode-failure-strategy: FAIL
    producer:
      acks: all
      retries: 3
    consumer:
      auto-offset-reset: earliest
      group-id: wow-consumer
```

## MongoDB

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.mongo.enabled` | Boolean | `true` | Enable MongoDB integration |
| `wow.mongo.auto-init-schema` | Boolean | `true` | Automatically initialize database schema on startup |
| `wow.mongo.event-stream-database` | String? | `null` | Separate database for event streams (defaults to main database) |
| `wow.mongo.snapshot-database` | String? | `null` | Separate database for snapshots (defaults to main database) |
| `wow.mongo.prepare-database` | String? | `null` | Separate database for PrepareKey storage (defaults to main database) |
| `wow.mongo.event-store-batch.enabled` | Boolean | `false` | Batch concurrent event-store appends with MongoDB `insertMany` |
| `wow.mongo.event-store-batch.max-size` | Int | `128` | Maximum event streams in one collection batch |
| `wow.mongo.event-store-batch.max-delay` | Duration | `1ms` | Maximum time to collect a partial batch |
| `wow.mongo.event-store-batch.max-pending-appends` | Int | `4096` | Maximum accepted appends waiting or being written; must be at least `max-size` |
| `wow.mongo.event-store-batch.lane-count` | Int | `1` | Number of serial write lanes; appends for the same aggregate stay on one lane |
| `wow.mongo.snapshot-store-batch.enabled` | Boolean | `false` | Batch concurrent SnapshotStore saves with MongoDB unordered `bulkWrite` |
| `wow.mongo.snapshot-store-batch.max-size` | Int | `128` | Maximum snapshots in one collection batch |
| `wow.mongo.snapshot-store-batch.max-delay` | Duration | `1ms` | Maximum time to collect a partial snapshot batch |
| `wow.mongo.snapshot-store-batch.max-pending-saves` | Int | `4096` | Maximum accepted saves waiting or being written; must be at least `max-size` |
| `wow.mongo.snapshot-store-batch.lane-count` | Int | `1` | Number of serial write lanes; saves for the same aggregate stay on one lane |

```yaml
wow:
  mongo:
    enabled: true
    auto-init-schema: true
    event-stream-database: wow_events
    snapshot-database: wow_snapshots
    event-store-batch:
      enabled: true
      max-size: 128
      max-delay: 1ms
      max-pending-appends: 4096
      lane-count: 1
    snapshot-store-batch:
      enabled: true
      max-size: 128
      max-delay: 1ms
      max-pending-saves: 4096
      lane-count: 1
```

## Redis

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.redis.enabled` | Boolean | `true` | Enable Redis integration |
| `wow.redis.message-bus.recovery.enabled` | Boolean | `true` | Recover abandoned Redis Stream pending messages |
| `wow.redis.message-bus.recovery.min-idle-time` | Duration | `5m` | Minimum idle time before a pending message is recoverable |
| `wow.redis.message-bus.recovery.interval` | Duration | `30s` | Interval between pending-message sweeps |
| `wow.redis.message-bus.recovery.batch-size` | Long | `100` | Maximum records per `XPENDING` page |

Redis connection is configured through Spring Boot's standard `spring.data.redis.*` properties.

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379

wow:
  redis:
    enabled: true
    message-bus:
      recovery:
        enabled: true
        min-idle-time: 5m
        interval: 30s
        batch-size: 100
```


## Elasticsearch

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.elasticsearch.enabled` | Boolean | `true` | Enable Elasticsearch integration |
| `wow.elasticsearch.auto-init-template` | Boolean | `true` | Initialize required index templates before startup completes |
| `wow.elasticsearch.query.batch-size` | Int | `10000` | PIT + `search_after` batch size; configure no higher than the target index's `index.max_result_window` |
| `wow.elasticsearch.query.keep-alive` | Duration | `1m` | PIT lifetime refreshed by each full-list search request; increase for slow subscribers |
| `wow.elasticsearch.event-store-batch.enabled` | Boolean | `false` | Enable transparent EventStore Bulk `create` batching |
| `wow.elasticsearch.event-store-batch.max-size` | Int | `128` | Maximum event streams per Bulk request |
| `wow.elasticsearch.event-store-batch.max-delay` | Duration | `1ms` | Maximum wait used to collect a partial event batch |
| `wow.elasticsearch.event-store-batch.max-pending-appends` | Int | `4096` | Maximum accepted appends waiting or being written; must be at least `max-size` |
| `wow.elasticsearch.event-store-batch.lane-count` | Int | `1` | Number of serial write lanes; appends for the same aggregate stay on one lane |
| `wow.elasticsearch.snapshot-store-batch.enabled` | Boolean | `false` | Enable transparent SnapshotStore Bulk `update` batching |
| `wow.elasticsearch.snapshot-store-batch.max-size` | Int | `128` | Maximum snapshots per Bulk request |
| `wow.elasticsearch.snapshot-store-batch.max-delay` | Duration | `1ms` | Maximum wait used to collect a partial snapshot batch |
| `wow.elasticsearch.snapshot-store-batch.max-pending-saves` | Int | `4096` | Maximum accepted saves waiting or being written; must be at least `max-size` |
| `wow.elasticsearch.snapshot-store-batch.lane-count` | Int | `1` | Number of serial write lanes; saves for the same aggregate stay on one lane |

Elasticsearch connection is configured through Spring Boot's standard `spring.elasticsearch.*` properties.
When automatic initialization is enabled, a failed, empty, or unacknowledged template request fails application startup.
Set `auto-init-template` to `false` only when templates are managed externally.

```yaml
spring:
  elasticsearch:
    uris: http://localhost:9200

wow:
  elasticsearch:
    enabled: true
    auto-init-template: true
    event-store-batch:
      enabled: true
      max-size: 128
      max-delay: 1ms
      max-pending-appends: 4096
      lane-count: 1
    snapshot-store-batch:
      enabled: true
      max-size: 128
      max-delay: 1ms
      max-pending-saves: 4096
      lane-count: 1
```

Batching is opt-in. EventStore batching uses Bulk `create`; SnapshotStore uses
an atomic `_source.version` guarded update in both direct and batch modes to
prevent stale snapshots, including legacy documents, from overwriting newer
snapshots.

## WebFlux

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.webflux.enabled` | Boolean | `true` | Enable WebFlux command endpoint auto-registration |
| `wow.webflux.global-error.enabled` | Boolean | `true` | Enable global error handling |
| `wow.webflux.batch.concurrency` | Integer | `1` | Concurrency for batch command requests |
| `wow.webflux.batch.prefetch` | Integer | `1` | Prefetch count for batch command requests |
| `wow.webflux.query.max-list-size` | Integer | `1000` | Maximum HTTP list-query limit; `0` disables the cap |
| `wow.webflux.query.max-page-size` | Integer | `100` | Maximum HTTP page size; `0` disables the cap |
| `wow.webflux.query.max-page-window` | Long | `10000` | Maximum HTTP page window; `0` disables the cap |
| `wow.webflux.query.max-condition-nodes` | Integer | `64` | Maximum HTTP query condition nodes; `0` disables the cap |
| `wow.webflux.query.max-condition-values` | Integer | `1000` | Maximum values in HTTP `IN`, `NOT_IN`, `ALL_IN`, `IDS`, or `AGGREGATE_IDS` conditions; `0` disables the cap |
| `wow.webflux.query.allowed-sort-fields` | Set<String> | `[]` | Indexed logical fields allowed for explicit HTTP sorting; an empty set rejects all explicit sorts |
| `wow.webflux.query.allow-raw` | Boolean | `false` | Allow native HTTP `RAW` queries |
| `wow.webflux.query.allow-expensive-operators` | Boolean | `false` | Allow HTTP `CONTAINS`/`ENDS_WITH` queries |
| `wow.webflux.query.idle-timeout` | Duration | `10s` | Timeout for the first JSON-array result or next SSE result; `0s` disables it |
| `wow.webflux.command.request.appender.agent.enabled` | Boolean | `true` | Append the client `User-Agent` to the command request context (set `false` to disable) |
| `wow.webflux.command.request.appender.ip.enabled` | Boolean | `true` | Append the client IP to the command request context (set `false` to disable) |

```yaml
wow:
  webflux:
    enabled: true
    global-error:
      enabled: true
    batch:
      concurrency: 1
      prefetch: 1
    query:
      max-list-size: 1000
      max-page-size: 100
      max-page-window: 10000
      max-condition-nodes: 64
      max-condition-values: 1000
      allowed-sort-fields: []
      allow-raw: false
      allow-expensive-operators: false
      idle-timeout: 10s
    command:
      request:
        appender:
          agent:
            enabled: true
          ip:
            enabled: true
```
