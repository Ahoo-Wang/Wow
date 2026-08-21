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
| `wow.mongo.event-store-batch.enabled` | Boolean | `false` | 使用 MongoDB `insertMany` 批量写入并发 EventStore 追加请求 |
| `wow.mongo.event-store-batch.max-size` | Int | `128` | 同一集合单批最多包含的事件流数量 |
| `wow.mongo.event-store-batch.max-delay` | Duration | `1ms` | 收集不足一批请求的最长等待时间 |
| `wow.mongo.event-store-batch.max-pending-appends` | Int | `4096` | 等待或正在写入的 append 最大接收数量；必须不小于 `max-size` |
| `wow.mongo.event-store-batch.lane-count` | Int | `1` | 串行写入 lane 数量；同一聚合的 append 始终进入同一 lane |
| `wow.mongo.snapshot-store-batch.enabled` | Boolean | `false` | 使用 MongoDB unordered `bulkWrite` 批量写入并发 SnapshotStore 保存请求 |
| `wow.mongo.snapshot-store-batch.max-size` | Int | `128` | 同一集合单批最多包含的快照数量 |
| `wow.mongo.snapshot-store-batch.max-delay` | Duration | `1ms` | 收集不足一批快照的最长等待时间 |
| `wow.mongo.snapshot-store-batch.max-pending-saves` | Int | `4096` | 等待或正在写入的 save 最大接收数量；必须不小于 `max-size` |
| `wow.mongo.snapshot-store-batch.lane-count` | Int | `1` | 串行写入 lane 数量；同一聚合的 save 始终进入同一 lane |

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
| `wow.elasticsearch.query.batch-size` | Int | `10000` | PIT + `search_after` 批大小；不得高于目标索引的 `index.max_result_window` |
| `wow.elasticsearch.query.keep-alive` | Duration | `1m` | 每次全量列表请求都会刷新的 PIT 存活时间；慢速订阅场景应增大 |
| `wow.elasticsearch.event-store-batch.enabled` | Boolean | `false` | 启用透明的 EventStore Bulk `create` 批处理 |
| `wow.elasticsearch.event-store-batch.max-size` | Int | `128` | 单个 Bulk 请求最多包含的事件流数量 |
| `wow.elasticsearch.event-store-batch.max-delay` | Duration | `1ms` | 收集不足一批事件的最长等待时间 |
| `wow.elasticsearch.event-store-batch.max-pending-appends` | Int | `4096` | 等待或正在写入的 append 最大接收数量；必须不小于 `max-size` |
| `wow.elasticsearch.event-store-batch.lane-count` | Int | `1` | 串行写入 lane 数量；同一聚合的 append 始终进入同一 lane |
| `wow.elasticsearch.snapshot-store-batch.enabled` | Boolean | `false` | 启用透明的 SnapshotStore Bulk `update` 批处理 |
| `wow.elasticsearch.snapshot-store-batch.max-size` | Int | `128` | 单个 Bulk 请求最多包含的快照数量 |
| `wow.elasticsearch.snapshot-store-batch.max-delay` | Duration | `1ms` | 收集不足一批快照的最长等待时间 |
| `wow.elasticsearch.snapshot-store-batch.max-pending-saves` | Int | `4096` | 等待或正在写入的 save 最大接收数量；必须不小于 `max-size` |
| `wow.elasticsearch.snapshot-store-batch.lane-count` | Int | `1` | 串行写入 lane 数量；同一聚合的 save 始终进入同一 lane |

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

批处理默认关闭。EventStore 批处理使用 Bulk `create`；SnapshotStore 在
direct 和 batch 模式下都使用基于 `_source.version` 的原子保护更新，防止旧快照
（包括 legacy 文档）覆盖新快照。

## WebFlux

| 属性 | 类型 | 默认值 | 描述 |
|----------|------|---------|-------------|
| `wow.webflux.enabled` | Boolean | `true` | 启用 WebFlux 命令端点自动注册 |
| `wow.webflux.global-error.enabled` | Boolean | `true` | 启用全局错误处理 |
| `wow.webflux.batch.concurrency` | Integer | `1` | 批量命令请求的并发数 |
| `wow.webflux.batch.prefetch` | Integer | `1` | 批量命令请求的预取数 |
| `wow.webflux.query.max-list-size` | Integer | `1000` | HTTP 列表查询最大 limit；`0` 关闭上限 |
| `wow.webflux.query.max-page-size` | Integer | `100` | HTTP 查询最大页大小；`0` 关闭上限 |
| `wow.webflux.query.max-page-window` | Long | `10000` | HTTP 查询最大分页窗口；`0` 关闭上限 |
| `wow.webflux.query.max-condition-nodes` | Integer | `64` | HTTP 查询条件节点上限；`0` 关闭上限 |
| `wow.webflux.query.max-condition-values` | Integer | `1000` | HTTP `IN`、`NOT_IN`、`ALL_IN`、`IDS`、`AGGREGATE_IDS` 条件值数量上限；`0` 关闭上限 |
| `wow.webflux.query.allowed-sort-fields` | Set<String> | `[]` | HTTP 显式排序允许的已索引逻辑字段；空集拒绝所有显式排序，`["*"]` 关闭限制 |
| `wow.webflux.query.allowed-condition-fields` | Set<String> | `[]` | HTTP 条件允许的额外已索引逻辑字段；空集仅保留内置字段，`["*"]` 关闭限制 |
| `wow.webflux.query.allow-raw` | Boolean | `false` | 允许 HTTP `RAW` 查询 |
| `wow.webflux.query.allow-expensive-operators` | Boolean | `false` | 允许 HTTP 负向/存在性/高成本字符串操作符及无过滤 count/paged 查询 |
| `wow.webflux.query.idle-timeout` | Duration | `10s` | 等待下一条结果或完成的最长时间；普通 JSON 数组在提交前缓冲，SSE 保持流式；`0s` 关闭 |
| `wow.webflux.command.request.appender.agent.enabled` | Boolean | `true` | 将客户端 `User-Agent` 追加到命令请求上下文（设为 `false` 可禁用） |
| `wow.webflux.command.request.appender.ip.enabled` | Boolean | `true` | 将客户端 IP 追加到命令请求上下文（设为 `false` 可禁用） |

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
      allowed-condition-fields: []
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
