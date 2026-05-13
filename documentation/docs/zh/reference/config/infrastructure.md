---
title: 基础设施配置
description: 基础设施集成的配置选项，包括 Kafka、MongoDB、Redis、R2DBC、Elasticsearch 和 WebFlux。
---

# 基础设施配置

## Kafka

| 属性 | 类型 | 默认值 | 描述 |
|----------|------|---------|-------------|
| `wow.kafka.enabled` | Boolean | `true` | 启用 Kafka 集成 |
| `wow.kafka.bootstrap-servers` | List\<String\> | （必填） | Kafka bootstrap server 地址 |
| `wow.kafka.topic-prefix` | String | `wow.` | Topic 名称前缀 |
| `wow.kafka.properties` | Map\<String, String\> | `{}` | 额外的 Kafka 客户端属性 |
| `wow.kafka.producer` | Map\<String, String\> | `{}` | Kafka 生产者专属属性 |
| `wow.kafka.consumer` | Map\<String, String\> | `{}` | Kafka 消费者专属属性 |

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

| 属性 | 类型 | 默认值 | 描述 |
|----------|------|---------|-------------|
| `wow.mongo.enabled` | Boolean | `true` | 启用 MongoDB 集成 |
| `wow.mongo.auto-init-schema` | Boolean | `true` | 启动时自动初始化数据库 Schema |
| `wow.mongo.event-stream-database` | String? | `null` | 事件流使用的独立数据库（默认使用主数据库） |
| `wow.mongo.snapshot-database` | String? | `null` | 快照使用的独立数据库（默认使用主数据库） |
| `wow.mongo.prepare-database` | String? | `null` | PrepareKey 存储使用的独立数据库（默认使用主数据库） |

```yaml
wow:
  mongo:
    enabled: true
    auto-init-schema: true
    event-stream-database: wow_events
    snapshot-database: wow_snapshots
```

## Redis

| 属性 | 类型 | 默认值 | 描述 |
|----------|------|---------|-------------|
| `wow.redis.enabled` | Boolean | `true` | 启用 Redis 集成 |

Redis 连接通过 Spring Boot 标准的 `spring.data.redis.*` 属性进行配置。

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

### 基础属性

| 属性 | 类型 | 默认值 | 描述 |
|----------|------|---------|-------------|
| `wow.r2dbc.enabled` | Boolean | `true` | 启用 R2DBC 集成 |

### DataSource 属性

| 属性 | 类型 | 默认值 | 描述 |
|----------|------|---------|-------------|
| `wow.r2dbc.datasource.type` | Type | `SIMPLE` | DataSource 类型：`SIMPLE` 或 `SHARDING` |

### 分片属性

当 `wow.r2dbc.datasource.type` 为 `SHARDING` 时：

| 属性 | 类型 | 默认值 | 描述 |
|----------|------|---------|-------------|
| `wow.r2dbc.datasource.sharding.databases` | Map\<String, Database\> | `{}` | 分片数据库定义 |
| `wow.r2dbc.datasource.sharding.event-stream` | Map\<String, ShardingRule\> | `{}` | 事件流分片规则 |
| `wow.r2dbc.datasource.sharding.snapshot` | Map\<String, ShardingRule\> | `{}` | 快照分片规则 |
| `wow.r2dbc.datasource.sharding.algorithms` | Map\<String, ShardingAlgorithm\> | `{}` | 分片算法定义 |

```yaml
wow:
  r2dbc:
    enabled: true
    datasource:
      type: simple
```

### 分片示例

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

| 属性 | 类型 | 默认值 | 描述 |
|----------|------|---------|-------------|
| `wow.elasticsearch.enabled` | Boolean | `true` | 启用 Elasticsearch 集成 |
| `wow.elasticsearch.auto-init-template` | Boolean | `true` | 启动时自动初始化索引模板 |

Elasticsearch 连接通过 Spring Boot 标准的 `spring.elasticsearch.*` 属性进行配置。

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

| 属性 | 类型 | 默认值 | 描述 |
|----------|------|---------|-------------|
| `wow.webflux.enabled` | Boolean | `true` | 启用 WebFlux 命令端点自动注册 |
| `wow.webflux.global-error.enabled` | Boolean | `true` | 启用全局错误处理 |

```yaml
wow:
  webflux:
    enabled: true
    global-error:
      enabled: true
```
