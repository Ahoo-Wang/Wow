---
title: 配置项
description: 每组 9.x `wow.*` 配置项在 V10 中的去向，哪些配置项有对应项，哪些随它们所配置的东西一起被移除。
---

# 配置项

V10 的配置项也在 `wow.*` 之下，但 9.x 的配置项一个都不沿用：留在原处的 9.x 配置项不绑定任何东西，服务启动时会忽略它。把 9.x 服务设置的每个配置项映射到 V10 的对应项，或者把它连同它所配置的东西一起记录为已移除。`wow-migrate` Skill 在改写源码时一并完成这件事。[配置项](../reference/configuration)是从代码生成的 V10 配置项完整列表；[配置服务](../operate/configuration)按任务解释它们。

## 服务与节点 {#the-service-and-its-node}

| 9.x | V10 |
| --- | --- |
| `wow.context-name` | 上下文是代码中的 `@BoundedContext`；服务提供其类路径上的上下文，用 `wow.contexts.include` 和 `exclude` 收窄。服务名是 `wow.service`，默认为 `spring.application.name` |
| `wow.shutdown-timeout`、`wow.shutdown-quiet-period` | `wow.cluster.drain-timeout`，以及 Spring Boot 自己的优雅停机（[Kubernetes](../operate/kubernetes)） |
| `wow.dispatch.*`（`workers`、`max-in-flight`、`throughput`、`spin`） | `wow.executor.*`：节点的 `max-in-flight`，一个聚合的 `lane-capacity` |
| `wow.command.bus.*`、`wow.event.bus.*`、`wow.eventsourcing.state.bus.*`、`local-first` | 已移除：没有总线。订阅读取日志；命令在拥有其聚合分区的节点上运行（`wow.cluster.*`） |
| `wow.command.idempotency.*`（布隆过滤器） | 已移除：带同一个 `Wow-Request-Id` 的重试，由日志本身以它首次的提交回答 |
| `wow.event.ack-on-unrecorded-failure` | 已移除：失败会让聚合停放在失败台账中（`wow.subscription.*`），不会越过它确认任何东西 |

## 存储 {#stores}

| 9.x | V10 |
| --- | --- |
| `wow.eventsourcing.store.storage`、`wow.eventsourcing.snapshot.storage` | `wow.backend`：PostgreSQL 或 MongoDB，默认由应用的 `DataSource` 或 `MongoClient` 选定；`wow.log.store` 和 `wow.snapshot.stores` 指明 `wow.stores` 中的存储 |
| `wow.eventsourcing.storage-routing.*` | `wow.aggregates.[<context>.<aggregate>]` 为聚合的日志和快照指明的存储，例如 `log.store` |
| `wow.eventsourcing.snapshot.strategy`、`version-offset` | `wow.snapshot.*`：快照由内置的 `snapshot` 订阅分批写出（`interval`、`batch`） |
| `wow.mongo.event-stream-database`、`snapshot-database`、`prepare-database` | `wow.mongo.database`，日志和每个存储共用一个数据库 |
| `wow.mongo.auto-init-schema` | `wow.ddl`（`auto`、`validate`、`none`）用于框架的表、集合、索引和查询索引，以及 Kafka 主题 |
| `wow.mongo.*-batch.*`、`wow.elasticsearch.*-batch.*` | 已移除：日志有自己的批量追加 |
| `wow.prepare.*` | 已移除：预留保存在后端中，在 decide 中获取 |
| `wow.redis.*` | 随 Redis 后端一起移除 |
| `wow.elasticsearch.*`（事件存储） | 作为事件存储已移除；在 V10 中 Elasticsearch 是查询后端 |

## Kafka {#kafka}

| 9.x | V10 |
| --- | --- |
| `wow.kafka.bootstrap-servers` | `wow.kafka.bootstrap-servers`，现在只用于发布的事件流和发往其他上下文的命令 |
| `wow.kafka.properties`、`producer`、`consumer`、`close-timeout` | V10 的 `wow.kafka.*`（[Kafka](../operate/kafka)） |
| `wow.kafka.decode-failure-strategy` | 已移除：订阅无法解码的记录会让它停放（`wow.subscription.undecodable` 对它们计数） |
| 按约定命名的 topic | `wow.<context>.commands` 和 `wow.<context>.<aggregate>.state`，按 `wow.kafka.ddl` 的设置创建 |

## HTTP 与查询 {#http-and-queries}

| 9.x | V10 |
| --- | --- |
| `wow.webflux.*`（`global-error`、`batch`、`query.default-list-size`、`state.*`） | 路由用 `wow.http.*`，它们的查询用 `wow.query.budgets.http.*`：`default-list-size`、`max-list-size`、`max-page-size`、`timeout` |
| `wow.webflux.command.request.appender.*` | `wow.http.request-facts`：命令保留的请求头 |
| `wow.query.budgets.http.*`（`max-list-size`、`max-page-size`、`max-filter-nodes`、`allow-expensive-operators`……） | `wow.query.budgets.http.*`，名字相同 |
| `wow.query.abac.*`、`wow.query.sensitivity.*` | `wow.query.abac.*`、`wow.query.sensitivity.*` |
| `wow.query.schema.*`、`legacy-declarations`、`require-explicit-entry` | 已移除：查询模型来自 Manifest |
| `wow.query.datasets`、`wow.query.clickhouse.*` | `wow.query.dataset.*` 和 `wow.clickhouse.*`（[发布数据集](../guide/datasets)） |
| `wow.openapi.enabled` | `wow.http.openapi.enabled`，springdoc 用 `wow.http.openapi.springdoc.*` |

## 安全、运维与分析 {#security-operations-and-analytics}

| 9.x | V10 |
| --- | --- |
| `wow.cosec.*`、`wow.cosec.abac.*` | `wow.cosec.*`：`tenant-level`、`cross-scope`、`service`、`authorizer`、`trust-injected-app`（[保护部署](../operate/security)）；查询 ABAC 是 `wow.query.abac.*` |
| `wow.compensation.*`，以及补偿服务自己的配置项 | 每个节点用 `wow.subscription.parked.*`，每个订阅用 `wow.subscriptions.[<processor.function>].parked.*`；运维路由、webhook 以及向运维服务发布用 `wow.ops.*`（[平台服务](./platform)） |
| `wow.bi.script.*` | 随 BI 同步一起移除：改用 ClickHouse Sink，`wow.clickhouse.sink.*`（[ClickHouse](../operate/clickhouse)） |
| 代码中每个聚合固定的租户 | `wow.aggregates.[<context>.<aggregate>].tenant` |

V10 新增、没有 9.x 对应项的：`wow.log.partitions`（日志有了条目之后就固定）、`wow.internal.*`（内部端口，默认 9090）、`wow.node.*`、`wow.wait.*`、`wow.tenant.default`、`wow.health.*`、`wow.timers.*` 和 `wow.node.standby`。
