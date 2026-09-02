---
title: 基础设施配置
description: Kafka、MongoDB、Redis、Elasticsearch 与 WebFlux 集成的精确属性、默认值和所有权边界。
outline: deep
---

# 基础设施配置

本页只描述 Wow 自己拥有的配置。连接池、TLS、认证、超时等后端客户端属性仍由 Spring Boot 或原生客户端拥有；请对照应用实际使用的 Spring Boot 版本，而不是把外部属性复制进 Wow 参考页。

::: warning capability 与属性是两个门禁
`wow.*.enabled=true` 不会把实现加入 classpath。应用必须先请求对应的 Starter capability；反过来，capability 在 classpath 上时这些集成默认启用。未使用的 capability 不要提前引入。
:::

## Kafka

配置类：`KafkaProperties`、`KafkaReceiverProperties`；所需 capability：`kafka-support`。

| 属性 | 类型 | 默认值 | 含义 |
| --- | --- | --- | --- |
| `wow.kafka.enabled` | Boolean | `true` | 启用 Kafka 自动配置 |
| `wow.kafka.bootstrap-servers` | List\<String\> | 无，必填 | 发送端和接收端 bootstrap 地址 |
| `wow.kafka.topic-prefix` | String | `wow.` | Command/DomainEvent/StateEvent topic 前缀 |
| `wow.kafka.properties` | Map\<String, String\> | `{}` | 生产者与消费者公共 Kafka 属性 |
| `wow.kafka.producer` | Map\<String, String\> | `{}` | 生产者覆盖；优先级高于公共属性 |
| `wow.kafka.consumer` | Map\<String, String\> | `{}` | 消费者覆盖；优先级高于公共属性 |
| `wow.kafka.receiver.prefetch-batches` | Int | `1` | Reactor Kafka 预取的 poll 批次数 |
| `wow.kafka.receiver.max-deferred-commits` | Int | `1` | 为乱序提交保留的最大 deferred commit 数 |
| `wow.kafka.receiver.retry-attempts` | Long | `3` | 一次连续 receive failure burst 的最大尝试次数 |
| `wow.kafka.receiver.retry-backoff` | Duration | `10s` | 接收重试的最小退避 |
| `wow.kafka.receiver.decode-failure-strategy` | Enum | `fail` | `fail` 或 `acknowledge` |

```yaml
wow:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS}
    producer:
      acks: all
    receiver:
      decode-failure-strategy: fail
```

`fail` 让无效记录终止当前接收并进入 receiver retry；`acknowledge` 确认并跳过无效记录。后者会放弃该记录，只有在已有隔离、审计和人工恢复路径时才应使用。Wow 创建 Bus 客户端，但 topic/partition、ACL、retention、consumer lag、备份和位点恢复仍由应用平台负责。

## MongoDB

配置类：`MongoProperties`、`MongoEventStoreBatchProperties`、`MongoSnapshotStoreBatchProperties`；所需 capability：`mongo-support`。

| 属性 | 类型 | 默认值 | 含义 |
| --- | --- | --- | --- |
| `wow.mongo.enabled` | Boolean | `true` | 启用 Mongo 自动配置 |
| `wow.mongo.auto-init-schema` | Boolean | `true` | 启动时初始化 Wow collection/index schema |
| `wow.mongo.event-stream-database` | String? | `null` | EventStore 数据库；空值使用 Spring 主数据库 |
| `wow.mongo.snapshot-database` | String? | `null` | SnapshotStore/查询数据库；空值使用 Spring 主数据库 |
| `wow.mongo.prepare-database` | String? | `null` | PrepareKey 数据库；空值使用 Spring 主数据库 |

连接由 Spring Boot 的 `spring.mongodb.*` 属性拥有：

```yaml
spring:
  mongodb:
    uri: ${MONGODB_URI}
```

两个批处理器默认关闭；启用后才按 collection 聚合并发写入。

| 精确属性 | 默认值 |
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

`max-size` 必须大于 `1`，`max-delay` 必须为正数，pending 上限不得小于 `max-size`，`lane-count` 必须大于零。同一聚合始终进入同一 lane；增加 lane 只能在有吞吐证据时进行。`auto-init-schema=true` 负责 Wow schema 初始化，不替代数据库备份、分片设计或业务查询索引验证。

## Redis

配置类：`RedisProperties`、`RedisStreamRecoveryProperties`；所需 capability：`redis-support`。

| 属性 | 类型 | 默认值 | 含义 |
| --- | --- | --- | --- |
| `wow.redis.enabled` | Boolean | `true` | 启用 Redis 自动配置 |
| `wow.redis.message-bus.recovery.enabled` | Boolean | `true` | 恢复 Redis Streams consumer group 中遗留的 pending 消息 |
| `wow.redis.message-bus.recovery.min-idle-time` | Duration | `5m` | pending 消息可被 claim 前的最小 idle 时间 |
| `wow.redis.message-bus.recovery.interval` | Duration | `30s` | recovery sweep 间隔 |
| `wow.redis.message-bus.recovery.batch-size` | Long | `100` | 每页 pending 记录数 |

`min-idle-time`、`interval` 至少为 `1ms`，`batch-size` 必须大于零。连接由 Spring Boot 的 `spring.data.redis.*` 属性拥有。

```yaml
spring:
  data:
    redis:
      url: ${REDIS_URL}
```

pending recovery 只处理 Wow Redis Streams Bus 的 consumer-group pending entries；它不是 Redis EventStore/SnapshotStore 备份，也不会恢复已经被裁剪或删除的 Stream 记录。平台仍需负责持久化模式、容量、Stream trimming、consumer group 与备份恢复。

## Elasticsearch

配置类：`ElasticsearchProperties`、`ElasticsearchQueryProperties` 及两个 batch properties；所需 capability：`elasticsearch-support`。

| 属性 | 类型 | 默认值 | 含义 |
| --- | --- | --- | --- |
| `wow.elasticsearch.enabled` | Boolean | `true` | 启用 Elasticsearch 自动配置 |
| `wow.elasticsearch.auto-init-template` | Boolean | `true` | 创建/确认 Wow event 与 snapshot index template |
| `wow.elasticsearch.compatibility-version` | Int? | `null` | 配置后为 REST client 添加兼容媒体类型 header |
| `wow.elasticsearch.query.batch-size` | Int | `10000` | PIT + `search_after` 的单批大小 |
| `wow.elasticsearch.query.keep-alive` | Duration | `1m` | 每次全量查询请求刷新的 PIT keep-alive |

`query.batch-size` 必须在 `1..10000`，且不能高于目标索引的 `index.max_result_window`；`keep-alive` 必须至少 `1ms`。连接由 `spring.elasticsearch.*` 拥有：

```yaml
spring:
  elasticsearch:
    uris: ${ELASTICSEARCH_URIS}
```

`compatibility-version` 没有默认值。只有部署拓扑确实要求 Elasticsearch REST compatibility header 时才设置，并由应用验证该值与服务端兼容；文档不固定某个服务端主版本。

| 精确属性 | 默认值 |
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

批处理校验与 MongoDB 相同。EventStore batch 使用 Bulk `create`；SnapshotStore 的 direct/batch 两条路径都以 `_source.version` 做原子保护更新，避免旧快照覆盖新版本。`auto-init-template=true` 时，模板请求失败、空响应或未确认会让启动失败；仅在外部平台明确拥有模板时关闭它，并保留模板版本与验证证据。

## WebFlux

配置类：`WebFluxProperties`；所需 capability：`webflux-support`。

| 属性 | 类型 | 默认值 | 含义 |
| --- | --- | --- | --- |
| `wow.webflux.enabled` | Boolean | `true` | 启用内置 Wow HTTP route 装配 |
| `wow.webflux.global-error.enabled` | Boolean | `true` | 注册 Wow 全局 `WebExceptionHandler` |
| `wow.webflux.batch.concurrency` | Int | `128` | 批量快照重建与 StateEvent 重发任务并发度 |
| `wow.webflux.batch.prefetch` | Int | `4` | 批量任务 prefetch |
| `wow.webflux.query.max-list-size` | Int | `1000` | list/aggregation limit；`0` 关闭上限并允许 limit `0` |
| `wow.webflux.query.max-page-size` | Int | `100` | page size 上限；`0` 关闭 |
| `wow.webflux.query.max-page-window` | Long | `10000` | `page.index * page.size` 上限；`0` 关闭 |
| `wow.webflux.query.max-filter-nodes` | Int | `128` | FilterExpression 节点数上限；`0` 关闭 |
| `wow.webflux.query.max-filter-values` | Int | `1000` | 集合型过滤条件的值数量上限；`0` 关闭 |
| `wow.webflux.query.allow-expensive-operators` | Boolean | `true` | 允许 expensive filters、Elements、metric 排序/算术及 match-all count/paged |
| `wow.webflux.query.idle-timeout` | Duration | `10s` | 等待下一结果或完成的最长空闲时间；`0s` 关闭 |
| `wow.webflux.command.request.appender.agent.enabled` | Boolean | `true` | 把 `User-Agent` 写入命令上下文 |
| `wow.webflux.command.request.appender.ip.enabled` | Boolean | `true` | 把解析出的远端 IP 写入命令上下文 |

所有数值型查询上限必须非负；普通 page size 仍至少为 `1`，page offset 仍不得超过 `Int.MAX_VALUE`。`allow-expensive-operators=true` 是兼容性默认值，不是容量证明；收紧前需验证现有请求和升级路径。

批量并发度按请求生效，并由快照重建与 StateEvent 重发共享；多个并发请求会叠加下游负载，应按应用与存储容量下调。

`webflux-support` 注册命令、事件、快照查询以及重建/补偿等内置 route，但不自动提供业务认证、授权或管理面隔离。应用必须从运行时 OpenAPI 取得实际路径，并为修改性运维 route 配置鉴权、审计、限流和受控网络入口。
