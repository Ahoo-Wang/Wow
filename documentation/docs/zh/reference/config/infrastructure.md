---
title: Infrastructure Configuration
description: Configuration options for infrastructure integrations including Kafka, MongoDB, Redis, R2DBC, Elasticsearch, and WebFlux.
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

```yaml
wow:
  kafka:
    enabled: true
    bootstrap-servers:
      - localhost:9092
    topic-prefix: "wow."
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
```

## R2DBC

### Basic Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.r2dbc.enabled` | Boolean | `true` | Enable R2DBC integration |

### DataSource Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.r2dbc.datasource.type` | Type | `SIMPLE` | DataSource type: `SIMPLE` or `SHARDING` |

### Sharding Properties

When `wow.r2dbc.datasource.type` is `SHARDING`:

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.r2dbc.datasource.sharding.databases` | Map\<String, Database\> | `{}` | Sharded database definitions |
| `wow.r2dbc.datasource.sharding.event-stream` | Map\<String, ShardingRule\> | `{}` | Event stream sharding rules |
| `wow.r2dbc.datasource.sharding.snapshot` | Map\<String, ShardingRule\> | `{}` | Snapshot sharding rules |
| `wow.r2dbc.datasource.sharding.algorithms` | Map\<String, ShardingAlgorithm\> | `{}` | Sharding algorithm definitions |

```yaml
wow:
  r2dbc:
    enabled: true
    datasource:
      type: simple
```

### Sharding Example

```yaml
wow:
  r2dbc:
    enabled: true
    datasource:
      type: sharding
      sharding:
            databases:
              ds0:
                url: r2dbc:mysql://localhost:3306/wow_ds0
              ds1:
                url: r2dbc:mysql://localhost:3306/wow_ds1
            algorithms:
              order_mod:
                type: mod
                mod:
                  logic-name-prefix: order_event_stream
                  divisor: 2
            event-stream:
              order:
                database-algorithm: order_mod
                table-algorithm: order_mod
```

## Elasticsearch

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `wow.elasticsearch.enabled` | Boolean | `true` | Enable Elasticsearch integration |
| `wow.elasticsearch.auto-init-template` | Boolean | `true` | Automatically initialize index templates on startup |

Elasticsearch connection is configured through Spring Boot's standard `spring.elasticsearch.*` properties.

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

```yaml
wow:
  webflux:
    enabled: true
    global-error:
      enabled: true
```
