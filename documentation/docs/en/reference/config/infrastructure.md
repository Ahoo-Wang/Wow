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

```yaml
wow:
  mongo:
    enabled: true
    auto-init-schema: true
    event-stream-database: wow_events
    snapshot-database: wow_snapshots
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
```

## WebFlux

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.webflux.enabled` | Boolean | `true` | Enable WebFlux command endpoint auto-registration |
| `wow.webflux.global-error.enabled` | Boolean | `true` | Enable global error handling |
| `wow.webflux.batch.concurrency` | Integer | `1` | Concurrency for batch command requests |
| `wow.webflux.batch.prefetch` | Integer | `1` | Prefetch count for batch command requests |
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
    command:
      request:
        appender:
          agent:
            enabled: true
          ip:
            enabled: true
```
