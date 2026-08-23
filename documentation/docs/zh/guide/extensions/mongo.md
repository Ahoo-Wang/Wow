---
title: Mongo
description: MongoDB 扩展，为生产环境提供 EventStore 和 SnapshotStore 实现。
---

# Mongo

_Mongo_ 扩展提供对 MongoDB 的支持，是推荐的用于生产环境的事件存储和快照存储实现。它实现了以下接口：

- `EventStore` - 事件存储
- `EventStreamQueryService` - 事件流查询服务
- `SnapshotStore` - 快照存储
- `SnapshotQueryService` - 快照查询服务
- `PrepareKey` - 基于 TTL 过期机制的分布式键预留

该模块设计为即插即用的后端。当 `wow.eventsourcing.store.storage` 设置为 `mongo` 时，框架将其默认的内存存储替换为 MongoDB 支持的实现，该实现可自动处理并发、幂等性和模式生命周期。

## 架构概述

```mermaid
graph TB
    subgraph App["应用层 (wow-core)"]
        direction LR
        AR["聚合根"]
        CM["命令网关"]
        QS["查询服务"]
    end

    subgraph MongoEvent["MongoDB - 事件流数据库"]
        ESColl[("{aggregateName}_event_stream<br>集合")]
    end

    subgraph MongoSnap["MongoDB - 快照数据库"]
        SSCol[("{aggregateName}_snapshot<br>集合")]
    end

    subgraph MongoPrep["MongoDB - PrepareKey 数据库"]
        PKCol[("prepare_{keyName}<br>集合")]
    end

    subgraph Impl["wow-mongo 实现"]
        direction LR
        MES["MongoEventStore"]
        MSR["MongoSnapshotStore"]
        MPK["MongoPrepareKey"]
        MESQ["MongoEventStreamQueryService"]
        MSQS["MongoSnapshotQueryService"]
    end

    AR -->|"appendStream()"| MES
    MES -->|"insertOne"| ESColl
    AR -->|"save(Snapshot)"| MSR
    MSR -->|"updateOne pipeline (upsert)"| SSCol
    CM -->|"prepare()"| MPK
    MPK -->|"replaceOne"| PKCol
    QS -->|"dynamicQuery()"| MESQ
    MESQ -->|"find()"| ESColl
    QS -->|"dynamicQuery()"| MSQS
    MSQS -->|"find()"| SSCol
```

每种聚合类型拥有自己的集合，按聚合名称分区。这种设计将热聚合彼此隔离，并支持按聚合进行分片和索引调优。

`MongoSnapshotStore.save()` 通过单次 aggregation-pipeline `updateOne` 配合
`$replaceWith` 与 `$cond` 完成保存。候选聚合版本大于或等于已存版本时，服务端
原子替换完整文档；版本较低时保留已存文档。缺失或非整数的已存版本被视为无效
元数据，并由候选快照修复。该 pipeline 使用的 MQL 表达式要求 MongoDB 5.2
或更高版本；集成测试验证的版本为 MongoDB 6.0.6。

该集合布局假设一个 MongoDB database 只服务一个 bounded context。Starter 启动时会在
`wow_database_metadata` 中原子认领当前 `wow.context-name`；同一 context 的实例可安全重复启动，
不同 context 误连到该 database 时会在创建 EventStore、SnapshotStore、查询 factory 或
PrepareKeyFactory 前失败，并提示配置独立数据库。即使 event 与 snapshot 使用其他后端，独立的
`prepare-database` 也会执行该检查；该检查不受 `auto-init-schema` 影响。对于尚无所有权标记的存量
database，首次启动会先从已有 `*_event_stream` 和 `*_snapshot` 集合中查找属于其他 context 的文档，
确认不存在历史混写后再写入标记。存量 `prepare_*` 文档没有 context
元数据，因此上线前必须审计 prepare database 映射；首个升级的 context 会认领尚未标记且仅含
prepare 数据的 database。aggregate 全量校验只在首次认领时执行；大型存量数据库应优先升级其真实
所有者服务，并预留扫描时间。

## 安装

::: code-group
```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-mongo")
implementation("org.springframework.boot:spring-boot-starter-data-mongodb-reactive")
```
```groovy [Gradle(Groovy)]
implementation 'me.ahoo.wow:wow-mongo'
implementation 'org.springframework.boot:spring-boot-starter-data-mongodb-reactive'
```
```xml [Maven]
<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-mongo</artifactId>
    <version>${wow.version}</version>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-mongodb-reactive</artifactId>
</dependency>
```
:::

## 核心组件

| 组件 | 实现的契约 | 关键文件 | 职责 |
|---|---|---|---|
| `MongoEventStore` | `AbstractEventStore` | [MongoEventStore.kt](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/MongoEventStore.kt) | 追加、加载和查询领域事件流 |
| `MongoSnapshotStore` | `SnapshotStore` | [MongoSnapshotStore.kt](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/MongoSnapshotStore.kt) | 保存、加载和版本检查聚合快照 |
| `MongoPrepareKey` | `PrepareKey<V>` | [MongoPrepareKey.kt](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/prepare/MongoPrepareKey.kt) | 基于 TTL 过期机制的分布式键预留 |
| `MongoEventStreamQueryService` | `EventStreamQueryService` | [MongoEventStreamQueryService.kt](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/event/MongoEventStreamQueryService.kt) | 原始事件流的动态查询 |
| `MongoSnapshotQueryService` | `SnapshotQueryService<S>` | [MongoSnapshotQueryService.kt](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryService.kt) | 将快照作为物化读模型进行动态查询 |
| `EventStreamSchemaInitializer` | （独立） | [EventStreamSchemaInitializer.kt](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/EventStreamSchemaInitializer.kt) | 创建事件流的集合 + 索引 |
| `SnapshotSchemaInitializer` | （独立） | [SnapshotSchemaInitializer.kt](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/SnapshotSchemaInitializer.kt) | 创建快照的集合 + 索引 |

## 事件追加时序

以下时序图展示了从聚合根产生事件到 MongoDB 文档持久化的完整路径，包括乐观并发控制和幂等性守卫。

```mermaid
sequenceDiagram
    autonumber
    participant AR as 聚合根
    participant ES as MongoEventStore
    participant Doc as Documents.toDocument()
    participant Coll as MongoCollection
    participant Err as ErrorMapping
    participant DB as MongoDB

    AR->>ES: appendStream(DomainEventStream)
    ES->>ES: eventStream.toEventStreamCollectionName()
    Note over ES: "{aggregateName}_event_stream"
    ES->>Doc: eventStream.toDocument()
    Doc->>Doc: toLinkedHashMap() - replaceIdToPrimaryKey() - append("size")

    ES->>Coll: insertOne(document) 或 unordered insertMany(batch)
    Coll->>DB: 插入文档，_id = eventStreamId
    DB-->>Coll: InsertOneResult 或 InsertManyResult

    alt 写入已确认
        Coll-->>ES: onNext(result)
        ES->>ES: check(wasAcknowledged())
        ES-->>AR: Mono.empty()（成功）
    else 重复版本 (aggregateId + version)
        DB-->>Coll: MongoWriteException 或 MongoBulkWriteException
        Coll->>Err: 映射对应的 duplicate-key write error
        Err->>Err: toWowError() - 匹配 "aggregateId_1_version_1"
        Err-->>ES: EventVersionConflictException
        ES-->>AR: EventVersionConflictException
    else 重复 requestId
        DB-->>Coll: MongoWriteException 或 MongoBulkWriteException
        Coll->>Err: 映射对应的 duplicate-key write error
        Err->>Err: toWowError() - 匹配 "requestId_1"
        Err-->>ES: DuplicateRequestIdException
        ES-->>AR: DuplicateRequestIdException
    end
```

关键设计洞察是 **MongoDB 唯一索引扮演双重角色**：`{aggregateId, version}` 复合唯一索引强制执行乐观并发控制（同一版本不能有两处写入），而 `{requestId}` 唯一索引提供命令幂等性（无重复处理）。在违反索引约束时，`ErrorMapping.toWowError()` 将原始的 MongoDB 单条或批量写入错误转换为 Wow 框架的类型异常，以便框架无论在何种存储后端都能统一处理。

## 配置

- 配置类： [MongoProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/mongo/MongoProperties.kt)
- 前缀： `wow.mongo.`

| 名称 | 数据类型 | 默认值 | 描述 |
|---|---|---|---|
| `enabled` | `Boolean` | `true` | 是否启用 |
| `auto-init-schema` | `Boolean` | `true` | 是否自动生成 *Schema* |
| `event-stream-database` | `String` | Spring Boot Mongo 模块配置的数据库名称 | 事件流数据库名称 |
| `snapshot-database` | `String` | Spring Boot Mongo 模块配置的数据库名称 | 快照数据库名称 |
| `prepare-database` | `String` | Spring Boot Mongo 模块配置的数据库名称 | `PrepareKey` 数据库名称 |
| `event-store-batch.enabled` | `Boolean` | `false` | 使用 unordered `insertMany` 批量写入并发 EventStore 追加请求 |
| `event-store-batch.max-size` | `Int` | `128` | 同一集合单批最多包含的事件流数量 |
| `event-store-batch.max-delay` | `Duration` | `1ms` | 收集不足一批请求的最长等待时间 |
| `event-store-batch.max-pending-appends` | `Int` | `4096` | 等待或正在写入的 append 最大接收数量；必须不小于 `max-size` |
| `event-store-batch.lane-count` | `Int` | `1` | 串行写入 lane 数量；同一聚合的 append 始终进入同一 lane |
| `snapshot-store-batch.enabled` | `Boolean` | `false` | 使用 unordered `bulkWrite` 批量写入并发 SnapshotStore 保存请求 |
| `snapshot-store-batch.max-size` | `Int` | `128` | 同一集合单批最多包含的快照数量 |
| `snapshot-store-batch.max-delay` | `Duration` | `1ms` | 收集不足一批快照的最长等待时间 |
| `snapshot-store-batch.max-pending-saves` | `Int` | `4096` | 等待或正在写入的 save 最大接收数量；必须不小于 `max-size` |
| `snapshot-store-batch.lane-count` | `Int` | `1` | 串行写入 lane 数量；同一聚合的 save 始终进入同一 lane |

**YAML 配置示例**

```yaml
spring:
  mongodb:
    uri: mongodb://localhost:27017/wow_db

wow:
  eventsourcing:
    store:
      storage: mongo
    snapshot:
      storage: mongo
  mongo:
    enabled: true
    auto-init-schema: true
    event-stream-database: wow_event_db
    snapshot-database: wow_snapshot_db
    prepare-database: wow_prepare_db
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

批处理默认关闭，因为不足一批的请求最多会增加 `max-delay` 的追加或保存延迟。启用后，两种存储
都会按 MongoDB collection 对时间窗内的请求分组。EventStore 使用 unordered `insertMany`；
SnapshotStore 会将同一存储聚合的请求合并为最高版本，并通过 unordered `bulkWrite` 使用与 direct
保存相同的原子版本保护。每个原始 `append` 或 `save` 仍会独立完成。当请求数达到对应 pending 上限
时，新请求会在提交 MongoDB 之前返回可恢复的过载错误，而不是继续堆积到无界内存队列。

带明确索引的 bulk write error 只会让对应请求失败；write concern error 或无法自洽的批量结果会
保守地让所有受影响请求失败，因为此时无法证明提交状态。批处理本身不具备原子性。直接构造启用
批处理的 `MongoEventStore` 或 `MongoSnapshotStore` 时，应关闭它（例如使用 Kotlin `use`），以
冲刷不足一批的窗口并释放工作线程；Spring 会通过正常的 Bean 生命周期关闭自动配置的实例。

## 集合模式

### 集合命名规则

集合名称根据聚合元数据使用确定性后缀派生，定义在 [AggregateSchemaInitializer.kt](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/AggregateSchemaInitializer.kt) 中：

| 数据类型 | 集合命名格式 | 示例 |
|---|---|---|
| 事件流 | `{aggregateName}_event_stream` | `order_event_stream` |
| 快照 | `{aggregateName}_snapshot` | `order_snapshot` |
| PrepareKey | `prepare_{name}` | `prepare_username_idx` |

事件流、快照与 PrepareKey 集合名称刻意保持兼容，不会加入 `contextName`。bounded context 的数据库
所有权由 `wow_database_metadata` 单独记录；不要删除或手工改写该集合。若确需将数据库交给另一个
context，应先迁移或清空原事件流、快照与 PrepareKey 数据，再删除所有权标记并启动新服务。

### 事件流集合 (`{aggregateName}_event_stream`)

每个聚合按聚合类型定义，使用事件流 ID 作为主键（`_id`）。`body` 字段存储序列化的领域事件数组。

```json
{
  "_id": "event-stream-id",
  "aggregateId": "order-001",
  "tenantId": "tenant-001",
  "requestId": "request-001",
  "commandId": "command-001",
  "version": 1,
  "header": {
    "upstream_id": "saga-001"
  },
  "body": [
    {
      "name": "OrderCreated",
      "revision": "1.0",
      "bodyType": "me.ahoo.wow.example.api.order.OrderCreated"
    }
  ],
  "size": 1,
  "createTime": 1699920000000
}
```

| 字段 | 类型 | 已索引 | 描述 |
|---|---|---|---|
| `_id` | String | 主键 | 事件流标识符 |
| `aggregateId` | String | 哈希 + 唯一（与 version 组合） | 聚合根标识符 |
| `tenantId` | String | 哈希 | 多租户分区键 |
| `requestId` | String | 唯一（复合） | 命令请求幂等性键 |
| `commandId` | String | -- | 发起命令标识符 |
| `version` | Integer | 唯一（与 aggregateId 组合） | 事件时的聚合版本 |
| `header` | Object | -- | 元数据（例如用于 Saga 追踪的 `upstream_id`） |
| `body` | Array | -- | 领域事件负载的有序列表 |
| `size` | Integer | -- | 此事件流中的事件数量 |
| `createTime` | Long | -- | 纪元时间戳（毫秒） |

### 快照集合 (`{aggregateName}_snapshot`)

快照使用聚合 ID 作为主键（`_id`），使其成为最新状态的自然查找键。`state` 字段包含序列化的聚合状态对象。

```json
{
  "_id": "order-001",
  "contextName": "order-service",
  "aggregateName": "order",
  "tenantId": "tenant-001",
  "version": 10,
  "eventId": "event-010",
  "firstOperator": "user-001",
  "operator": "user-002",
  "firstEventTime": 1699920000000,
  "eventTime": 1699930000000,
  "snapshotTime": 1699930000000,
  "deleted": false,
  "state": {
    "id": "order-001",
    "status": "PAID",
    "totalAmount": 100.00
  }
}
```

| 字段 | 类型 | 已索引 | 描述 |
|---|---|---|---|
| `_id` | String | 唯一 | 聚合标识符（主键） |
| `contextName` | String | -- | 限界上下文名称 |
| `aggregateName` | String | -- | 聚合类型名称 |
| `tenantId` | String | 哈希 | 多租户分区键 |
| `version` | Integer | -- | 快照时的聚合版本 |
| `eventId` | String | -- | 快照中包含的最后一个事件的 ID |
| `firstOperator` | String | -- | 创建聚合的初始操作者 |
| `operator` | String | -- | 最后修改聚合的操作者 |
| `firstEventTime` | Long | -- | 第一个事件的时间戳 |
| `eventTime` | Long | -- | 最后一个事件的时间戳 |
| `snapshotTime` | Long | -- | 快照创建时的时间戳 |
| `deleted` | Boolean | 哈希 | 软删除标志 |
| `state` | Object | -- | 序列化的聚合状态（类型化） |

### PrepareKey 集合 (`prepare_{keyName}`)

| 字段 | 类型 | 已索引 | 描述 |
|---|---|---|---|
| `_id` | String | 哈希 | 键值（唯一） |
| `value` | Object | -- | 预留值的负载 |
| `ttlAt` | Date | 升序（TTL） | 生存时间过期时间戳 |

关键的文档级转换是 **主键映射**：事件流内部将其 ID 存储为 `_id`，但 `DomainEventStream` 模型使用 `id`——`Documents.replaceIdToPrimaryKey()` 和 `replacePrimaryKeyToId()` 透明地处理双向映射。类似地，快照通过 `replaceAggregateIdToPrimaryKey()` 和 `replacePrimaryKeyToAggregateId()` 在 `_id` 和 `aggregateId` 之间进行映射。

## 模式初始化与索引

`wow.mongo.auto-init-schema` 标志（默认 `true`）控制在启动时是否自动创建集合和索引。三个职责单一的初始化器处理此过程：

### EventStreamSchemaInitializer

在初始化时，`EventStreamSchemaInitializer.initSchema()` 方法：

1. 通过 `database.ensureCollection(collectionName)` 确保集合存在
2. 在 `aggregateId` 上创建 **哈希索引** 以支持等值查询和哈希分片
3. 创建 **唯一复合索引** `{aggregateId: 1, version: 1}` 用于乐观并发控制
4. 根据 `enableRequestIdUniqueIndex` 标志（默认为 `false` 以兼容分片集群），创建全局 `requestId` 唯一索引或复合 `{aggregateId, requestId}` 唯一索引
5. 在 `tenantId` 和 `ownerId` 上创建哈希索引以支持多租户过滤

| 索引 | 字段 | 类型 | 用途 |
|---|---|---|---|
| `aggregateId_hashed` | `aggregateId` | 哈希 | 等值查询和哈希分片 |
| `aggregateId_1_version_1` | `aggregateId`, `version` | 唯一 | 乐观并发控制——防止版本冲突 |
| `aggregateId_1_requestId_1` | `aggregateId`, `requestId` | 唯一 | 请求幂等性（分片安全变体） |
| `requestId_1` | `requestId` | 唯一 | 请求幂等性（非分片变体） |
| `tenantId_hashed` | `tenantId` | 哈希 | 多租户过滤 |
| `ownerId_hashed` | `ownerId` | 哈希 | 基于所有者的过滤 |

`enableRequestIdUniqueIndex` 开关的存在是因为 MongoDB 分片集群无法跨分片强制执行唯一索引，除非分片键是唯一索引的一部分。当为 `false`（默认值）时，改用复合 `{aggregateId, requestId}` 索引，这与基于 `aggregateId` 的哈希分片兼容。

### SnapshotSchemaInitializer

`SnapshotSchemaInitializer.initSchema()` 创建：

| 索引 | 字段 | 类型 | 用途 |
|---|---|---|---|
| `tenantId_hashed` | `tenantId` | 哈希 | 多租户过滤 |
| `ownerId_hashed` | `ownerId` | 哈希 | 基于所有者的过滤 |
| `_id_hashed` | `_id` | 哈希 | 按 ID 快速查找聚合 |
| `deleted_hashed` | `deleted` | 哈希 | 软删除过滤 |

## 查询服务

`wow-mongo` 模块提供两个查询服务实现，将 `FilterExpression` 编译为 MongoDB 过滤器文档（`Bson`）。

### 过滤器编译管道

编译管道为：`FilterExpression` -> `AbstractMongoFilterConverter` -> `Bson`。

| Wow 操作符 | MongoDB 等价操作 |
|---|---|
| `EQ` | `Filters.eq()` |
| `GT` / `GTE` / `LT` / `LTE` | `Filters.gt()` / `gte()` / `lt()` / `lte()` |
| `CONTAINS` | `Filters.regex()`（已转义） |
| `SEARCH` | `Filters.text()` |
| `BETWEEN` | `Filters.and(Filters.gte(), Filters.lte())` |
| `IN` / `NOT_IN` | `Filters.in()` / `nin()` |
| `DELETION` | `Filters.eq("deleted", true/false)` 或 `Filters.empty()` |

转换器还通过 `FieldConverter` 应用 **字段名转换**。对于事件流，`MessageRecords.ID` 字段映射到 `_id`。对于快照，`MessageRecords.AGGREGATE_ID` 映射到 `_id`。这使得应用层查询模型在整个底层主键策略中保持一致。

### 快照查询

快照存储可直接用作读模型：

```kotlin
val query = listQuery {
    filter {
        "state.status" eq "PAID"
        "state.totalAmount" gt 50.00
    }
    sort { "snapshotTime".desc() }
    limit(10)
}

query.dynamicQuery(snapshotQueryService)
```

`MongoSnapshotQueryService` 使用 `MaterializedSnapshot<S>` 作为其类型化的结果包装器，其中 `S` 是从聚合元数据解析出的聚合状态类型。这支持直接对聚合状态字段进行类型安全的动态查询——例如，查询 `state.status` 或 `state.totalAmount` 而不需要单独的投影处理器。

## PrepareKey：分布式协调

`MongoPrepareKey` 实现了 Wow 的 `PrepareKey<V>` 接口，以 MongoDB 为协调后端进行分布式键预留。每个逻辑键变成一个 `prepare_{name}` 集合。

该实现使用三个 MongoDB 原语来实现协调：

| 操作 | MongoDB 方法 | 行为 |
|---|---|---|
| `prepare()` | `replaceOne`，过滤器 `{_id: key, ttlAt: {$lt: now}}` | CAS 风格的 upsert——仅当没有未过期的条目存在时才成功 |
| `rollback()` | `deleteOne`，过滤器 `{_id: key, ttlAt: {$gt: now}}` | 移除活动预留（仅当未过期时） |
| `reprepare()` | `updateOne`，使用 `$set` 更新 value + `ttlAt` | 原子性地扩展或替换预留 |

TTL 索引（`{ttlAt: 1}`，`expireAfter: 0 seconds`）确保 MongoDB 自动移除过期的条目，提供无需应用程序干预的清理机制。

## 错误映射

MongoDB 重复键错误通过 [ErrorMapping.toWowError()](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/ErrorMapping.kt) 转换为 Wow 框架异常：

```kotlin
fun WriteError.toWowError(eventStream: DomainEventStream, cause: MongoServerException): Throwable {
    if (ErrorCategory.fromErrorCode(code) != ErrorCategory.DUPLICATE_KEY) {
        return cause
    }
    if (message.contains(AggregateSchemaInitializer.AGGREGATE_ID_AND_VERSION_UNIQUE_INDEX_NAME)) {
        return EventVersionConflictException(eventStream = eventStream, cause = cause)
    }
    if (message.contains(AggregateSchemaInitializer.REQUEST_ID_UNIQUE_INDEX_NAME)) {
        return DuplicateRequestIdException(
            aggregateId = eventStream.aggregateId,
            requestId = eventStream.requestId,
            cause = cause
        )
    }
    return cause
}
```

映射依赖于嵌入在 MongoDB 错误消息中的索引名称：

- `EventVersionConflictException`——表示乐观并发冲突。框架会自动重试该命令。
- `DuplicateRequestIdException`——表示命令已被处理。框架将其视为幂等成功。

## 类层级

```mermaid
classDiagram
    direction TB

    class AbstractEventStore {
        <<abstract>>
        +appendStream(DomainEventStream) Mono~Void~
        +loadStream(AggregateId, Int, Int) Flux~DomainEventStream~
        +last(AggregateId) Mono~DomainEventStream~
    }

    class MongoEventStore {
        -database: MongoDatabase
        +appendStream(DomainEventStream) Mono~Void~
        +loadStream(...) Flux~DomainEventStream~
        +last(AggregateId) Mono~DomainEventStream~
        +scanAggregateId(...) Flux~AggregateId~
    }

    class SnapshotStore {
        <<interface>>
        +load(AggregateId) Mono~Snapshot~
        +save(Snapshot) Mono~Void~
    }

    class MongoSnapshotStore {
        -database: MongoDatabase
        +load(AggregateId) Mono~Snapshot~
        +save(Snapshot) Mono~Void~
    }

    class PrepareKey~V~ {
        <<interface>>
        +prepare(String, PreparedValue~V~) Mono~Boolean~
        +getValue(String) Mono~PreparedValue~V~~
        +rollback(String) Mono~Boolean~
        +reprepare(String, PreparedValue~V~) Mono~Boolean~
    }

    class MongoPrepareKey~V~ {
        -prepareCollection: MongoCollection
        +prepare(...) Mono~Boolean~
        +getValue(...) Mono~PreparedValue~V~~
        +rollback(...) Mono~Boolean~
        +reprepare(...) Mono~Boolean~
    }

    class AbstractMongoQueryService~R~ {
        <<abstract>>
        #collection: MongoCollection
        #converter: AbstractMongoFilterConverter
        +single(ISingleQuery) Mono~R~
        +list(IListQuery) Flux~R~
        +paged(IPagedQuery) Mono~PagedList~R~~
        +count(FilterExpression) Mono~Long~
    }

    class MongoEventStreamQueryService {
        -snapshotType: JavaType
        +toTypedResult(Document) DomainEventStream
    }

    class MongoSnapshotQueryService~S~ {
        +toTypedResult(Document) MaterializedSnapshot~S~
    }

    AbstractEventStore <|-- MongoEventStore
    SnapshotStore <|.. MongoSnapshotStore
    PrepareKey <|.. MongoPrepareKey
    AbstractMongoQueryService <|-- MongoEventStreamQueryService
    AbstractMongoQueryService <|-- MongoSnapshotQueryService
```

类层级揭示了两层抽象：**Wow 核心接口**（`AbstractEventStore`、`SnapshotStore`、`PrepareKey`、`QueryService`）以存储无关的方式定义了框架契约，而 **Mongo 特定实现** 将这些契约映射到 MongoDB 的响应式驱动原语（`insertOne`、aggregation-pipeline `updateOne`、`replaceOne`、`find`、`countDocuments`）。

## 索引优化建议

### 事件流索引

```javascript
// 推荐额外添加的索引
db.order_event_stream.createIndex(
  { "createTime": 1 },
  { name: "idx_create_time" }
)

db.order_event_stream.createIndex(
  { "body.name": 1, "createTime": 1 },
  { name: "idx_event_type_time" }
)
```

### 快照索引

```javascript
// 根据查询模式创建复合索引
db.order_snapshot.find({
  tenantId: "tenant-1",
  deleted: false,
  "state.status": "PAID"
}).sort({ snapshotTime: -1, _id: 1 })

db.order_snapshot.createIndex(
  {
    "tenantId": 1,
    "deleted": 1,
    "state.status": 1,
    "snapshotTime": -1,
    "_id": 1
  },
  { name: "tenant_deleted_status_time" }
)
```

对于 event-stream 与 snapshot 集合，Wow 的自动初始化只创建事件溯源与通用过滤所需的受管索引，不推测业务查询模式。业务复合索引由应用按以下流程逐个发布：

1. 从真实请求提取完整 filter、sort 与 projection，并在类生产数据上保存变更前的 `explain("executionStats")`。
2. 默认按 ESR 规则设计“等值字段 → 排序字段（包括查询 sort 中的唯一稳定字段）→ 范围字段”。范围条件选择性很高时，同时用 `explain` 比较 ERS 候选；只有记录基线并审批接受阻塞排序后才能选择 ERS。`aggregateId` 排序在 MongoDB 快照集合中映射为 `_id`。
3. 先在 staging 创建候选索引并复测。代表性查询必须返回数据；单节点及分片部署中每个 shard 的 winning plan 都不得包含 `COLLSCAN` 或阻塞 `SORT`。`totalKeysExamined / nReturned` 与 `totalDocsExamined / nReturned` 默认均不得超过 `5`；超过时必须记录基线和审批理由。同时核对结果数与有序 aggregate ID 列表不变。
4. 生产环境按单聚合、单索引发布，记录变更前后的查询 p95/p99、索引大小和写入延迟。
5. 仅查询计划回退时，可在支持隐藏索引的部署中先通过 `collMod` 隐藏新索引并复测。隐藏索引仍参与写入维护，不能回滚写放大；如索引大小或写入延迟回退，确认查询不再依赖后删除候选索引。

## 性能优化

### 连接池配置

```yaml
spring:
  mongodb:
    uri: mongodb://localhost:27017/wow_db?minPoolSize=10&maxPoolSize=100&maxIdleTimeMS=60000
```

| 参数 | 描述 | 推荐值 |
|---|---|---|
| `minPoolSize` | 最小连接数 | 10 |
| `maxPoolSize` | 最大连接数 | 100 |
| `maxIdleTimeMS` | 最大空闲时间 | 60000 |

### 写入关注配置

对于生产环境的事件溯源，`w=majority` 确保在命令返回之前大多数副本集成员确认事件。这可以防止故障转移期间的数据丢失，代价是略微增加写入延迟。

```yaml
spring:
  mongodb:
    uri: mongodb://localhost:27017/wow_db?w=majority&wtimeoutMS=5000
```

### 读取偏好安全性

Wow 的 Mongo 事件存储、快照存储、查询服务和幂等性检查默认共享 Spring 管理的 `MongoClient`。该共享客户端应保持主节点读取语义。在 `spring.mongodb.uri` 中设置 `readPreference=secondaryPreferred` 还会影响 `EventStore.load()` 与 `existsRequestId()`；复制延迟可能导致聚合重建或幂等性判断读取到旧数据。

下文的数据库名称分离不会创建独立客户端。若快照或查询流量必须从从节点读取，应提供单独配置的客户端与存储集成，通过存储 binding 显式路由，并在生产上线前验证一致性保证。

### 数据库分离

三个可配置的数据库（`event-stream-database`、`snapshot-database`、`prepare-database`）可在已配置的 MongoDB 部署内对工作负载进行逻辑分离：

- **事件流**：写入密集（仅追加），受益于快速存储
- **快照**：读取密集（物化视图），受益于专用索引和缓存
- **PrepareKey**：低容量、短生命周期文档，受益于 TTL 索引清理

当三者都为默认值 `null` 时，它们共享 Spring 配置的 MongoDB 数据库，这对开发和中度负载已经足够。不同的数据库名称可以支持差异化的 schema 管理、备份与保留策略以及运维归属；除非提供自定义集成，否则它们仍共享同一个 `MongoClient`。

## 分片策略

对于大规模数据，推荐使用 MongoDB 分片：

```javascript
// 哈希分片将写入均匀分布到各分片上
sh.shardCollection("wow_event_db.order_event_stream", { "aggregateId": "hashed" })
sh.shardCollection("wow_snapshot_db.order_snapshot", { "_id": "hashed" })
```

::: warning
使用分片集合时，保持 `EventStreamSchemaInitializer.enableRequestIdUniqueIndex = false`（默认值）。MongoDB 无法跨分片强制执行唯一索引，除非分片键是索引的一部分。复合 `{aggregateId, requestId}` 索引与分片兼容，因为 `aggregateId` 是分片键。
:::

## 故障排除

### 常见问题

#### 1. 版本冲突异常

```
me.ahoo.wow.eventsourcing.EventVersionConflictException
```

**原因**：对同一聚合根的并发写入

**解决方案**：
- 这是正常的乐观锁行为，框架会自动重试
- 如果频繁发生，请考虑优化业务流程以减少冲突

#### 2. 重复请求异常

```
me.ahoo.wow.eventsourcing.DuplicateRequestIdException
```

**原因**：相同的 `requestId` 被重复处理

**解决方案**：
- 这是幂等性保护，表明请求已被成功处理
- 检查客户端是否有重复提交

#### 3. 连接超时

```
com.mongodb.MongoTimeoutException
```

**解决方案**：
- 检查 MongoDB 服务是否正常运行
- 增大连接池大小
- 检查网络延迟

## 完整配置示例

```yaml
spring:
  mongodb:
    uri: mongodb://user:password@mongo1:27017,mongo2:27017,mongo3:27017/wow_db?replicaSet=rs0&w=majority&minPoolSize=10&maxPoolSize=100

wow:
  eventsourcing:
    store:
      storage: mongo
    snapshot:
      enabled: true
      strategy: all
      storage: mongo
  mongo:
    enabled: true
    auto-init-schema: true
    event-stream-database: wow_event_db
    snapshot-database: wow_snapshot_db
    prepare-database: wow_prepare_db
```

## 最佳实践

1. **数据库分离**：当事件流、快照和 PrepareKey 需要不同的 schema、备份、保留或归属策略时，使用不同的数据库名称
2. **启用快照**：对于拥有大量事件的聚合，启用快照可以显著提高加载性能
3. **使用副本集**：在生产环境中使用副本集以实现高可用性
4. **索引优化**：根据查询模式创建适当的复合索引
5. **使用分片扩展**：当数据量大时使用分片进行水平扩展

## 相关主题

| 主题 | 描述 |
|---|---|
| [MongoDB 配置参考](../../reference/config/infrastructure) | `wow.mongo.*` 属性的配置参考 |
| [事件溯源配置](../../reference/config/core) | 存储后端选择（`wow.eventsourcing.store.storage`） |
| [快照配置](../../reference/config/core) | 快照策略和存储后端选择 |
| [Redis 扩展](redis.md) | 替代的事件存储和快照后端 |
| [Spring Boot Starter](spring-boot-starter.md) | 自动配置和功能变体 |
