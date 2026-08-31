---
title: 核心配置
description: Wow 核心运行时、消息总线、事件溯源、快照、存储路由、查询 Schema 与 PrepareKey 的精确配置参考。
outline: deep
---

# 核心配置

本页只记录配置键、类型、默认值和装配边界。如何为某个环境选择这些值，请看[配置 Wow 应用](../../guide/configuration.md)。表中的默认值来自 `wow-spring-boot-starter` 配置类；“运行时回退”与“绑定默认值”分开说明。

## WowProperties

配置类：`WowProperties`；前缀：`wow`。

| 属性 | 类型 | 默认值 | 运行时含义 |
| --- | --- | --- | --- |
| `wow.enabled` | Boolean | `true` | 总开关；设为 `false` 时 Wow 自动配置不生效 |
| `wow.context-name` | String? | `null` | 未配置时读取必需的 `spring.application.name` |
| `wow.shutdown-timeout` | Duration | `60s` | 整个 `WowRuntime` 静默和逆序停止共享的截止时间 |
| `wow.shutdown-quiet-period` | Duration | `1s` | 停止接收前必须连续保持无活动的时间；新活动会重新计时 |

`shutdown-timeout` 必须大于零；`shutdown-quiet-period` 必须非负且小于 `shutdown-timeout`。二者还必须能够精确表示为有符号 64 位纳秒值。

```yaml
spring:
  application:
    name: order-service

wow:
  shutdown-timeout: 60s
  shutdown-quiet-period: 1s
```

## BusProperties

`BusProperties` 由命令、领域事件和状态事件三个通道复用。

| 相对属性 | 类型 | 默认值 | 含义 |
| --- | --- | --- | --- |
| `bus.type` | [`BusType`](#bustype) | `kafka` | 为当前通道选择总线实现 |
| `bus.local-first.enabled` | Boolean | `true` | 分布式总线存在时启用 LocalFirst 组合 |

“默认 `kafka`”只表示属性默认值。运行时仍必须包含 `kafka-support` capability，并提供 `wow.kafka.bootstrap-servers`；否则不能据此声称 Kafka 已可用。

### BusType

| 值 | 所需实现 | 边界 |
| --- | --- | --- |
| `kafka` | `wow-kafka` / `kafka-support` | 分布式 Kafka 总线 |
| `redis` | `wow-redis` / `redis-support` | Redis Streams 总线；必须显式配置该值 |
| `in_memory` | 基础 Starter | 单进程、非持久化 |
| `no_op` | 基础 Starter | 接受调用但不提供真实消息处理；仅用于明确的禁用场景 |

### LocalFirst 模式

LocalFirst 同时发送分布式副本并尝试本地准入。所有目标本地 Receiver 都确认准入后，分布式副本才标记为本地已处理；没有订阅者、入口关闭或本地发送失败时，分布式副本仍可处理。

#### 行为与失败边界

- LocalFirst 优化的是 Broker 往返延迟，不是 exactly-once 保证。
- 本地准入成功后的 Handler 失败按 Handler 自身的重试/确认策略处理；不会追溯性地重新启用分布式副本。
- `no_op` 和 `in_memory` 没有可组合的分布式 Bus，不能把 LocalFirst 配置当作跨实例能力。

## 命令总线

配置类：`CommandProperties`；前缀：`wow.command`。

| 属性 | 类型 | 默认值 | 含义 |
| --- | --- | --- | --- |
| `wow.command.bus.type` | `BusType` | `kafka` | 命令总线 |
| `wow.command.bus.local-first.enabled` | Boolean | `true` | 命令 LocalFirst |
| `wow.command.idempotency.enabled` | Boolean | `true` | 启用命令幂等预检 |
| `wow.command.idempotency.bloom-filter.ttl` | Duration | `1m` | Bloom Filter 的存活时间 |
| `wow.command.idempotency.bloom-filter.expected-insertions` | Long | `1000000` | 预期插入量 |
| `wow.command.idempotency.bloom-filter.fpp` | Double | `0.00001` | 目标误判率 |

### IdempotencyProperties

Bloom Filter 是进程内的快速预检，不是业务真相来源。预检拒绝后，`DefaultCommandGateway` 仍会通过 EventStore 的 request-id 检查确认是否真的重复。关闭此配置会安装 no-op 预检，但不会把客户端重试自动变成 exactly-once。

#### BloomFilter

```yaml
wow:
  command:
    idempotency:
      enabled: true
      bloom-filter:
        ttl: 1m
        expected-insertions: 1000000
        fpp: 0.00001
```

## 事件总线

配置类：`EventProperties`；前缀：`wow.event`。

| 属性 | 类型 | 默认值 |
| --- | --- | --- |
| `wow.event.bus.type` | `BusType` | `kafka` |
| `wow.event.bus.local-first.enabled` | Boolean | `true` |

领域事件在 EventStore append 成功后发送到该总线。该总线的 ACK 只覆盖所选 Adapter 的发送边界，不证明投影、事件处理器或 Saga 已完成。

## 事件溯源

### EventStoreProperties

配置类：`EventStoreProperties`；前缀：`wow.eventsourcing.store`。

| 属性 | 类型 | 默认值 | 含义 |
| --- | --- | --- | --- |
| `wow.eventsourcing.store.storage` | [`StorageType`](#storagetype) | `mongo` | 默认 EventStore binding |

#### StorageType

| 值 | 实现来源 | 说明 |
| --- | --- | --- |
| `mongo` | `mongo-support` | MongoDB EventStore / SnapshotStore / 查询服务 |
| `redis` | `redis-support` | Redis EventStore / SnapshotStore；无通用动态查询实现 |
| `elasticsearch` | `elasticsearch-support` | Elasticsearch EventStore / SnapshotStore / 查询服务 |
| `in_memory` | 基础 Starter | 进程退出即丢失 |
| `delay` | `mock-support` | 延迟测试实现，不是生产后端 |

选择值不会引入实现模块。缺少对应 capability 或应用自定义 binding 时，自动配置会因找不到目标存储而失败。

### SnapshotProperties

配置类：`SnapshotProperties`；前缀：`wow.eventsourcing.snapshot`。

| 属性 | 类型 | 默认值 | 含义 |
| --- | --- | --- | --- |
| `wow.eventsourcing.snapshot.enabled` | Boolean | `true` | 启用 Snapshot Dispatcher 与 SnapshotStore |
| `wow.eventsourcing.snapshot.strategy` | [`Strategy`](#strategy) | `all` | 何时保存快照 |
| `wow.eventsourcing.snapshot.version-offset` | Int | `5` | `version_offset` 策略的版本间隔 |
| `wow.eventsourcing.snapshot.storage` | `StorageType` | `mongo` | 默认 SnapshotStore binding |

关闭快照时基础配置提供 `NoOpSnapshotStore`；同时配置 snapshot storage route 会启动失败。

#### Strategy

| 值 | 行为 |
| --- | --- |
| `all` | 每个已处理的 StateEvent 都保存快照 |
| `version_offset` | 与已保存版本至少相差 `version-offset` 时保存 |

`SNAPSHOT` 阶段表示 Snapshot Dispatcher 的本次处理完成。使用 `version_offset` 时，该阶段可能在没有新写快照的情况下完成。

### StorageRoutingProperties

配置类：`StorageRoutingProperties`；前缀：`wow.eventsourcing.storage-routing`。

| 属性 | 类型 | 默认值 |
| --- | --- | --- |
| `wow.eventsourcing.storage-routing.aggregates` | `Map<String, AggregateStorageRouteProperties>` | `{}` |

Map key 必须是当前上下文中的 `aggregate`，或完整的 `context.aggregate`。每个 route 可含 `event`、`snapshot` 两个通道；完全省略的通道回退到默认存储。

| 精确属性模式 | 类型 | 规则 |
| --- | --- | --- |
| `wow.eventsourcing.storage-routing.aggregates.<route>.event.storage` | `StorageType?` | 选择 EventStore 类型 binding |
| `wow.eventsourcing.storage-routing.aggregates.<route>.event.binding` | String? | 选择具名 EventStore binding |
| `wow.eventsourcing.storage-routing.aggregates.<route>.snapshot.storage` | `StorageType?` | 选择 SnapshotStore 类型 binding |
| `wow.eventsourcing.storage-routing.aggregates.<route>.snapshot.binding` | String? | 选择具名 SnapshotStore binding |

一个已配置通道必须在 `storage` 与 `binding` 中恰好设置一个。空通道、二者同时设置、未知聚合、缺失 store binding，或缺失对应 query-backend factory binding，都会快速失败。

```yaml
wow:
  eventsourcing:
    storage-routing:
      aggregates:
        order-service.Audit:
          event:
            binding: archiveEventStore
          snapshot:
            storage: mongo
```

## 状态事件总线

配置类：`StateProperties`；前缀：`wow.eventsourcing.state`。

| 属性 | 类型 | 默认值 |
| --- | --- | --- |
| `wow.eventsourcing.state.bus.type` | `BusType` | `kafka` |
| `wow.eventsourcing.state.bus.local-first.enabled` | Boolean | `true` |

StateEvent 驱动快照处理。选择分布式实现时，应把该通道的积压与失败纳入 `SNAPSHOT` 阶段诊断。

## Prepare Key

配置类：`PrepareProperties`；前缀：`wow.prepare`。

| 属性 | 类型 | 默认值 | 含义 |
| --- | --- | --- | --- |
| `wow.prepare.enabled` | Boolean | `true` | 启用 PrepareKey |
| `wow.prepare.storage` | `PrepareStorage` | `mongo` | PrepareKey 存储 |
| `wow.prepare.base-packages` | List\<String\> | `[]` | 扫描 PrepareKey 定义的包 |

### PrepareStorage 值

| 值 | 所需能力 |
| --- | --- |
| `mongo` | `mongo-support` |
| `redis` | `redis-support` |

纯内存应用没有 PrepareStorage 实现，应显式设置 `wow.prepare.enabled: false`。

## QueryProperties

配置类：`QueryProperties`；前缀：`wow.query`。

| 属性 | 类型 | 默认值 | 取值 |
| --- | --- | --- | --- |
| `wow.query.schema.validation-mode` | `QuerySchemaValidationMode` | `compatible` | `compatible`、`strict` |

`compatible` 接受 exact 与 compatible 的 Schema 解析结果；`strict` 只接受 exact。该属性控制 Query Schema 解析，不替代 HTTP 查询成本限制；后者见[基础设施配置](./infrastructure.md#webflux)。

## 环境特定配置

参考页不定义“开发”或“生产”配置模板。选择环境时必须显式决定三种 Bus、EventStore、SnapshotStore、PrepareStorage 和所需 capability；任务步骤见[配置 Wow 应用](../../guide/configuration.md#环境分层)。

### 开发环境

纯内存模式至少需要把三个 Bus 与两个 Store 设为 `in_memory`，并关闭 PrepareKey。否则未覆盖的核心默认值仍指向 Kafka/Mongo。

### 生产环境

生产值没有隐藏的安全默认值。对每个通道记录实现、所有者、持久性、恢复方式和已验证证据；不能只依赖本页的属性默认值。

## 完整配置示例

以下示例只展示完整的**核心选择**；连接、凭据和基础设施调优不属于本页。

```yaml
spring:
  application:
    name: order-service

wow:
  command:
    bus:
      type: kafka
  event:
    bus:
      type: kafka
  eventsourcing:
    store:
      storage: mongo
    snapshot:
      enabled: true
      strategy: all
      storage: mongo
    state:
      bus:
        type: kafka
  prepare:
    storage: mongo
  query:
    schema:
      validation-mode: compatible
```
