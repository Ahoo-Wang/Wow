---
title: 基础设施配置
description: 基础设施集成的配置选项，包括 Kafka、MongoDB、Redis、Elasticsearch 和 WebFlux。
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
| `wow.kafka.receiver.prefetch-batches` | Integer | `1` | 响应式接收端预取的 Kafka 轮询批次数 |
| `wow.kafka.receiver.max-deferred-commits` | Integer | `1` | 为保留偏移量间隙而缓存的乱序提交数 |
| `wow.kafka.receiver.retry-attempts` | Long | `3` | 每次连续接收失败的重试次数 |
| `wow.kafka.receiver.retry-backoff` | Duration | `10s` | 接收端重试的最小退避时间 |
| `wow.kafka.receiver.decode-failure-strategy` | Enum | `FAIL` | 无效记录策略：`FAIL` 或 `ACKNOWLEDGE` |

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
| `wow.redis.message-bus.recovery.enabled` | Boolean | `true` | 恢复被遗留的 Redis Stream pending 消息 |
| `wow.redis.message-bus.recovery.min-idle-time` | Duration | `5m` | pending 消息可恢复前的最小空闲时间 |
| `wow.redis.message-bus.recovery.interval` | Duration | `30s` | pending 消息扫描间隔 |
| `wow.redis.message-bus.recovery.batch-size` | Long | `100` | 每页 `XPENDING` 最大记录数 |

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
    message-bus:
      recovery:
        enabled: true
        min-idle-time: 5m
        interval: 30s
        batch-size: 100
```


## Elasticsearch

| 属性 | 类型 | 默认值 | 描述 |
|----------|------|---------|-------------|
| `wow.elasticsearch.enabled` | Boolean | `true` | 启用 Elasticsearch 集成 |
| `wow.elasticsearch.auto-init-template` | Boolean | `true` | 在应用启动完成前初始化所需索引模板 |

Elasticsearch 连接通过 Spring Boot 标准的 `spring.elasticsearch.*` 属性进行配置。
开启自动初始化时，模板请求失败、无响应结果或未确认都会导致应用启动失败。
仅当索引模板由外部系统管理时，才应将 `auto-init-template` 设为 `false`。

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
