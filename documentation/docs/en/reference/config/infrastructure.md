---
title: Infrastructure Configuration
description: Exact properties, defaults, and ownership boundaries for Kafka, MongoDB, Redis, Elasticsearch, and WebFlux integrations.
outline: deep
---

# Infrastructure Configuration

This page describes only configuration owned by Wow. Backend client settings such as pools, TLS, authentication, and timeouts remain owned by Spring Boot or the native client. Check the Spring Boot version used by the application instead of copying external properties into the Wow reference.

::: warning Capabilities and properties are separate gates
`wow.*.enabled=true` does not add an implementation to the classpath. The application must request the matching Starter capability first. Conversely, these integrations are enabled by default when their capability is present. Do not add unused capabilities in advance.
:::

## Kafka

Configuration classes: `KafkaProperties`, `KafkaReceiverProperties`; required capability: `kafka-support`.

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `wow.kafka.enabled` | Boolean | `true` | Enables Kafka auto-configuration |
| `wow.kafka.bootstrap-servers` | List\<String\> | none; required | Bootstrap addresses for senders and receivers |
| `wow.kafka.topic-prefix` | String | `wow.` | Command/DomainEvent/StateEvent topic prefix |
| `wow.kafka.properties` | Map\<String, String\> | `{}` | Common Kafka producer and consumer properties |
| `wow.kafka.producer` | Map\<String, String\> | `{}` | Producer overrides; higher precedence than common properties |
| `wow.kafka.consumer` | Map\<String, String\> | `{}` | Consumer overrides; higher precedence than common properties |
| `wow.kafka.receiver.prefetch-batches` | Int | `1` | Reactor Kafka poll batches to prefetch |
| `wow.kafka.receiver.max-deferred-commits` | Int | `1` | Maximum deferred commits retained for out-of-order completion |
| `wow.kafka.receiver.retry-attempts` | Long | `3` | Maximum attempts for one consecutive receive-failure burst |
| `wow.kafka.receiver.retry-backoff` | Duration | `10s` | Minimum receiver retry backoff |
| `wow.kafka.receiver.decode-failure-strategy` | Enum | `fail` | `fail` or `acknowledge` |

```yaml
wow:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS}
    producer:
      acks: all
    receiver:
      decode-failure-strategy: fail
```

`fail` terminates the current receive path and enters receiver retry for an invalid record. `acknowledge` acknowledges and skips it. The latter abandons that record and is appropriate only with an isolation, audit, and operator-recovery path. Wow creates bus clients; the application platform still owns topics/partitions, ACLs, retention, consumer lag, backups, and offset recovery.

## MongoDB

Configuration classes: `MongoProperties`, `MongoEventStoreBatchProperties`, `MongoSnapshotStoreBatchProperties`; required capability: `mongo-support`.

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `wow.mongo.enabled` | Boolean | `true` | Enables Mongo auto-configuration |
| `wow.mongo.auto-init-schema` | Boolean | `true` | Initializes Wow collection/index schema at startup |
| `wow.mongo.event-stream-database` | String? | `null` | EventStore database; absent uses the primary Spring database |
| `wow.mongo.snapshot-database` | String? | `null` | SnapshotStore/query database; absent uses the primary Spring database |
| `wow.mongo.prepare-database` | String? | `null` | PrepareKey database; absent uses the primary Spring database |

Spring Boot owns the connection through `spring.mongodb.*`:

```yaml
spring:
  mongodb:
    uri: ${MONGODB_URI}
```

Both batchers are disabled by default. When enabled, they collect concurrent writes per collection.

| Exact property | Default |
| --- | --- |
| `wow.mongo.event-store-batch.enabled` | `false` |
| `wow.mongo.event-store-batch.max-size` | `128` |
| `wow.mongo.event-store-batch.max-delay` | `1ms` |
| `wow.mongo.event-store-batch.max-pending-appends` | `4096` |
| `wow.mongo.event-store-batch.lane-count` | `1` |
| `wow.mongo.snapshot-store-batch.enabled` | `false` |
| `wow.mongo.snapshot-store-batch.max-size` | `128` |
| `wow.mongo.snapshot-store-batch.max-delay` | `1ms` |
| `wow.mongo.snapshot-store-batch.max-pending-saves` | `4096` |
| `wow.mongo.snapshot-store-batch.lane-count` | `1` |

`max-size` must exceed `1`, `max-delay` must be positive, a pending limit must be at least `max-size`, and `lane-count` must be positive. Writes for one aggregate stay on one lane. Increase lanes only with throughput evidence. `auto-init-schema=true` owns Wow schema initialization; it does not replace database backup, sharding design, or business-query index verification.

## Redis

Configuration classes: `RedisProperties`, `RedisStreamRecoveryProperties`; required capability: `redis-support`.

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `wow.redis.enabled` | Boolean | `true` | Enables Redis auto-configuration |
| `wow.redis.message-bus.recovery.enabled` | Boolean | `true` | Recovers abandoned pending messages in Redis Streams consumer groups |
| `wow.redis.message-bus.recovery.min-idle-time` | Duration | `5m` | Minimum idle time before a pending entry can be claimed |
| `wow.redis.message-bus.recovery.interval` | Duration | `30s` | Interval between recovery sweeps |
| `wow.redis.message-bus.recovery.batch-size` | Long | `100` | Pending records per page |

`min-idle-time` and `interval` must be at least `1ms`; `batch-size` must be positive. Spring Boot owns the connection through `spring.data.redis.*`.

```yaml
spring:
  data:
    redis:
      url: ${REDIS_URL}
```

Pending recovery applies only to consumer-group pending entries for Wow Redis Streams buses. It is not a Redis EventStore/SnapshotStore backup and cannot restore Stream records that were trimmed or deleted. The platform still owns persistence mode, capacity, Stream trimming, consumer groups, and backup/restore.

## Elasticsearch

Configuration classes: `ElasticsearchProperties`, `ElasticsearchQueryProperties`, and the two batch-property classes; required capability: `elasticsearch-support`.

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `wow.elasticsearch.enabled` | Boolean | `true` | Enables Elasticsearch auto-configuration |
| `wow.elasticsearch.auto-init-template` | Boolean | `true` | Creates/confirms Wow event and snapshot index templates |
| `wow.elasticsearch.compatibility-version` | Int? | `null` | Adds REST compatibility media-type headers when configured |
| `wow.elasticsearch.query.batch-size` | Int | `10000` | Batch size for PIT + `search_after` |
| `wow.elasticsearch.query.keep-alive` | Duration | `1m` | PIT keep-alive refreshed by every full-query request |

`query.batch-size` must be in `1..10000` and no greater than the target index's `index.max_result_window`; `keep-alive` must be at least `1ms`. Spring Boot owns the connection through `spring.elasticsearch.*`:

```yaml
spring:
  elasticsearch:
    uris: ${ELASTICSEARCH_URIS}
```

`compatibility-version` has no default. Set it only when the deployed topology requires Elasticsearch REST compatibility headers, and verify the value against that server. This documentation does not pin a server major version.

| Exact property | Default |
| --- | --- |
| `wow.elasticsearch.event-store-batch.enabled` | `false` |
| `wow.elasticsearch.event-store-batch.max-size` | `128` |
| `wow.elasticsearch.event-store-batch.max-delay` | `1ms` |
| `wow.elasticsearch.event-store-batch.max-pending-appends` | `4096` |
| `wow.elasticsearch.event-store-batch.lane-count` | `1` |
| `wow.elasticsearch.snapshot-store-batch.enabled` | `false` |
| `wow.elasticsearch.snapshot-store-batch.max-size` | `128` |
| `wow.elasticsearch.snapshot-store-batch.max-delay` | `1ms` |
| `wow.elasticsearch.snapshot-store-batch.max-pending-saves` | `4096` |
| `wow.elasticsearch.snapshot-store-batch.lane-count` | `1` |

Batch validation matches MongoDB. EventStore batching uses Bulk `create`. Both direct and batch SnapshotStore paths use an atomic `_source.version` guarded update so an older snapshot cannot overwrite a newer one. With `auto-init-template=true`, a failed, empty, or unacknowledged template request fails startup. Disable it only when an external platform explicitly owns templates, and retain template version and validation evidence.

## Cursor Encryption

Configuration class: `QueryProperties`. Built-in MongoDB and Elasticsearch CursorQuery services share this configuration.

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `wow.query.cursor.encryption-key` | String? | `null` | Base64URL-encoded 32-byte AES-256-GCM key |

```yaml
wow:
  query:
    cursor:
      encryption-key: ${WOW_QUERY_CURSOR_ENCRYPTION_KEY:}
```

The example references an environment variable only; never put a real key in a repository, image, document, or log. A nonblank value fails configuration deterministically unless it is valid Base64URL and decodes to exactly 32 bytes. An absent or blank value does not prevent startup and does not change existing queries such as `PageQuery`; every built-in CursorQuery, including its first page, fails explicitly with `UnsupportedOperationException`. Rotating the single key invalidates every outstanding cursor. There is no key ID, key ring, or historical-key support. Direct MongoDB or Elasticsearch JVM factory users can inject the same `CursorTokenCodec` through the final optional constructor argument; the source-compatible default keeps cursor unsupported.

## WebFlux

Configuration class: `WebFluxProperties`; required capability: `webflux-support`.

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `wow.webflux.enabled` | Boolean | `true` | Enables built-in Wow HTTP route wiring |
| `wow.webflux.global-error.enabled` | Boolean | `true` | Registers Wow's global `WebExceptionHandler` |
| `wow.webflux.batch.concurrency` | Int | `1` | Concurrency for batch operation/command tasks |
| `wow.webflux.batch.prefetch` | Int | `1` | Batch-task prefetch |
| `wow.webflux.query.max-list-size` | Int | `1000` | List/aggregation limit; `0` removes the cap and permits limit `0` |
| `wow.webflux.query.max-page-size` | Int | `100` | Size cap for paged and cursor queries; `0` disables it |
| `wow.webflux.query.max-page-window` | Long | `10000` | `page.index * page.size` cap; `0` disables it |
| `wow.webflux.query.max-filter-nodes` | Int | `128` | FilterExpression node cap; `0` disables it |
| `wow.webflux.query.max-filter-values` | Int | `1000` | Value-count cap for collection filters; `0` disables it |
| `wow.webflux.query.allow-expensive-operators` | Boolean | `true` | Allows expensive filters, Elements, metric sorting/arithmetic, and match-all count/paged requests |
| `wow.webflux.query.idle-timeout` | Duration | `10s` | Maximum idle wait for the next result or completion; `0s` disables it |
| `wow.webflux.command.request.appender.agent.enabled` | Boolean | `true` | Adds `User-Agent` to command context |
| `wow.webflux.command.request.appender.ip.enabled` | Boolean | `true` | Adds the resolved remote IP to command context |

All numeric query caps must be non-negative. Paged and cursor sizes must still be at least `1`, and a paged offset cannot exceed `Int.MAX_VALUE`. CursorQuery cannot compensate for a missing index or an expensive filter: MongoDB needs a compound index matching the fixed filter and complete sort order; Elasticsearch continues with `search_after`, not a PIT, so it provides no cross-request snapshot consistency. `allow-expensive-operators=true` is the compatibility default, not capacity evidence. Test existing requests and the upgrade path before tightening it.

`webflux-support` wires built-in command, event, snapshot-query, rebuild, and compensation routes. It does not provide business authentication, authorization, or management-plane isolation. Read actual paths from the runtime OpenAPI and protect modifying operation routes with authorization, audit, rate limits, and a controlled network entry point.
