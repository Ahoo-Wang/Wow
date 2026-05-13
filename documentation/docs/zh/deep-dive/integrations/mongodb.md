---
title: MongoDB 集成
description: 深入探究 Wow 的 MongoDB 集成，涵盖事件存储、快照仓库、Schema 初始化、查询服务和 PrepareKey 支持。
outline: deep
---

# MongoDB 集成

**Wow MongoDB 集成**（`wow-mongo`）为 Wow CQRS + 事件溯源框架提供了一个生产级的响应式持久化层。它实现了每一个关键的存储契约：事件流、快照、查询服务以及分布式 PrepareKey 协调 —— 全部由 MongoDB 响应式驱动器支撑。

该模块被设计为一个即插即用的后端。当 `wow.eventsourcing.store.storage` 被设置为 `mongo` 时，框架将其默认的内存存储替换为经过 MongoDB 支持的实现，这些实现自动处理并发、幂等性和 Schema 生命周期。

## 架构概览

该集成横跨三个 MongoDB 数据库（可独立配置），并直接映射到 Wow 核心抽象：

```mermaid
graph TB
    subgraph App["应用层（wow-core）"]
        direction LR
        AR["聚合根"]
        CM["命令网关"]
        QS["查询服务"]
    end

    subgraph MongoEvent["MongoDB - 事件流数据库"]
        ESColl[("{aggregateName}_event_stream<br>Collection")]
    end

    subgraph MongoSnap["MongoDB - 快照数据库"]
        SSCol[("{aggregateName}_snapshot<br>Collection")]
    end

    subgraph MongoPrep["MongoDB - Prepare 数据库"]
        PKCol[("prepare_{keyName}<br>Collection")]
    end

    subgraph Impl["wow-mongo 实现"]
        direction LR
        MES["MongoEventStore"]
        MSR["MongoSnapshotRepository"]
        MPK["MongoPrepareKey"]
        MESQ["MongoEventStreamQueryService"]
        MSQS["MongoSnapshotQueryService"]
    end

    AR -->|"appendStream()"| MES
    MES -->|"insertOne"| ESColl
    AR -->|"save(Snapshot)"| MSR
    MSR -->|"replaceOne（upsert）"| SSCol
    CM -->|"prepare()"| MPK
    MPK -->|"replaceOne"| PKCol
    QS -->|"dynamicQuery()"| MESQ
    MESQ -->|"find()"| ESColl
    QS -->|"dynamicQuery()"| MSQS
    MSQS -->|"find()"| SSCol

```

<!-- Sources:
- wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/MongoEventStore.kt:32-105
- wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/MongoSnapshotRepository.kt:34-111
- wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/prepare/MongoPrepareKey.kt:46-163
- wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/event/MongoEventStreamQueryService.kt:31-46
- wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryService.kt:35-57
-->

每种聚合类型拥有自己的集合，按聚合名称分区。这种设计将热聚合彼此隔离，并支持按聚合进行分片和索引调优。

## 核心组件概览

| 组件 | 实现的契约 | 关键文件 | 职责 |
|---|---|---|---|
| `MongoEventStore` | `AbstractEventStore` | [MongoEventStore.kt:32](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/MongoEventStore.kt#L32) | 追加、加载和查询领域事件流 |
| `MongoSnapshotRepository` | `SnapshotRepository` | [MongoSnapshotRepository.kt:34](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/MongoSnapshotRepository.kt#L34) | 保存、加载和版本检查聚合快照 |
| `MongoPrepareKey` | `PrepareKey<V>` | [MongoPrepareKey.kt:46](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/prepare/MongoPrepareKey.kt#L46) | 带 TTL 过期功能的分布式键预留 |
| `MongoEventStreamQueryService` | `EventStreamQueryService` | [MongoEventStreamQueryService.kt:31](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/event/MongoEventStreamQueryService.kt#L31) | 原始事件流的动态查询 |
| `MongoSnapshotQueryService` | `SnapshotQueryService<S>` | [MongoSnapshotQueryService.kt:35](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryService.kt#L35) | 将快照作为物化读模型进行动态查询 |
| `EventStreamSchemaInitializer` | （独立） | [EventStreamSchemaInitializer.kt:29](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/EventStreamSchemaInitializer.kt#L29) | 为事件流创建集合和索引 |
| `SnapshotSchemaInitializer` | （独立） | [SnapshotSchemaInitializer.kt:29](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/SnapshotSchemaInitializer.kt#L29) | 为快照创建集合和索引 |

## 事件追加序列

以下序列展示了从聚合根产出事件到 MongoDB 文档持久化的完整路径，包括乐观并发和幂等性守卫。

```mermaid
sequenceDiagram
    autonumber
    participant AR as 聚合根
    participant ES as MongoEventStore
    participant Doc as Documents.toDocument()
    participant Coll as MongoCollection<Document>
    participant Err as ErrorMapping
    participant DB as MongoDB

    AR->>ES: appendStream(DomainEventStream)
    ES->>ES: eventStream.toEventStreamCollectionName()
    Note over ES: "{aggregateName}_event_stream"
    ES->>Doc: eventStream.toDocument()
    Doc->>Doc: toLinkedHashMap() -> replaceIdToPrimaryKey() -> append("size")

    ES->>Coll: insertOne(document)
    Coll->>DB: 插入文档 _id = eventStreamId
    DB-->>Coll: InsertOneResult

    alt 写入已确认
        Coll-->>ES: onNext(result)
        ES->>ES: check(wasAcknowledged())
        ES-->>AR: Mono.empty()（成功）
    else 重复版本（aggregateId + version）
        DB-->>Coll: MongoWriteException（DUPLICATE_KEY, u_idx_aggregate_id_version）
        Coll->>Err: onErrorMap(MongoWriteException)
        Err->>Err: toWowError() -> 匹配 "aggregateId_1_version_1"
        Err-->>ES: EventVersionConflictException
        ES-->>AR: EventVersionConflictException
    else 重复 requestId
        DB-->>Coll: MongoWriteException（DUPLICATE_KEY, u_idx_request_id）
        Coll->>Err: onErrorMap(MongoWriteException)
        Err->>Err: toWowError() -> 匹配 "requestId_1"
        Err-->>ES: DuplicateRequestIdException
        ES-->>AR: DuplicateRequestIdException
    end
```

<!-- Sources:
- wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/MongoEventStore.kt:34-46 (appendStream)
- wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/Documents.kt:58-63 (DomainEventStream.toDocument)
- wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/AggregateSchemaInitializer.kt:35-37 (toEventStreamCollectionName)
- wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/ErrorMapping.kt:23-41 (toWowError)
-->

关键设计洞察在于 **MongoDB 唯一索引扮演双重角色**：`{aggregateId, version}` 复合唯一索引强制执行乐观并发控制（同一版本不能有两次写入），而 `{requestId}` 唯一索引提供命令幂等性（不允许重复处理）。在违反时，`ErrorMapping.toWowError()` 将原始的 `MongoWriteException` 转换为 Wow 框架的类型化异常（`EventVersionConflictException` 或 `DuplicateRequestIdException`），以便框架无论在何种存储后端都能统一处理它们。

## 类层次结构

```mermaid
classDiagram
%%{init: {'theme': 'dark', 'themeVariables': {'primaryColor': '#2d333b', 'primaryTextColor': '#e6edf3', 'primaryBorderColor': '#6d5dfc', 'lineColor': '#8b949e'}}}%%
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
    }

    class SnapshotRepository {
        <<interface>>
        +load(AggregateId) Mono~Snapshot~
        +save(Snapshot) Mono~Void~
        +scanAggregateId(NamedAggregate, String, Int) Flux~AggregateId~
    }

    class MongoSnapshotRepository {
        -database: MongoDatabase
        +load(AggregateId) Mono~Snapshot~
        +save(Snapshot) Mono~Void~
        +scanAggregateId(...) Flux~AggregateId~
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
        #converter: ConditionConverter
        +single(ISingleQuery) Mono~R~
        +list(IListQuery) Flux~R~
        +paged(IPagedQuery) Mono~PagedList~R~~
        +count(Condition) Mono~Long~
    }

    class MongoEventStreamQueryService {
        -snapshotType: JavaType
        +toTypedResult(Document) DomainEventStream
    }

    class MongoSnapshotQueryService~S~ {
        +toTypedResult(Document) MaterializedSnapshot~S~
    }

    class AbstractMongoConditionConverter {
        <<abstract>>
        +eq(Condition) Bson
        +gt(Condition) Bson
        +contains(Condition) Bson
        +between(Condition) Bson
        +deleted(Condition) Bson
    }

    class EventStreamConditionConverter {
        +aggregateId(Condition) Bson
        +aggregateIds(Condition) Bson
    }

    class SnapshotConditionConverter {
        +aggregateId(Condition) Bson
        +aggregateIds(Condition) Bson
    }

    AbstractEventStore <|-- MongoEventStore
    SnapshotRepository <|.. MongoSnapshotRepository
    PrepareKey <|.. MongoPrepareKey
    AbstractMongoQueryService <|-- MongoEventStreamQueryService
    AbstractMongoQueryService <|-- MongoSnapshotQueryService
    AbstractMongoConditionConverter <|-- EventStreamConditionConverter
    AbstractMongoConditionConverter <|-- SnapshotConditionConverter

```

<!-- Sources:
- wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/MongoEventStore.kt:32 (MongoEventStore extends AbstractEventStore)
- wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/MongoSnapshotRepository.kt:34 (MongoSnapshotRepository implements SnapshotRepository)
- wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/prepare/MongoPrepareKey.kt:46 (MongoPrepareKey implements PrepareKey)
- wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoQueryService.kt:34 (AbstractMongoQueryService implements QueryService)
- wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoConditionConverter.kt:28 (AbstractMongoConditionConverter)
- wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/event/EventStreamConditionConverter.kt:23 (EventStreamConditionConverter)
- wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/SnapshotConditionConverter.kt:21 (SnapshotConditionConverter)
-->

类层次结构揭示了两层抽象：**Wow 核心接口**（`AbstractEventStore`、`SnapshotRepository`、`PrepareKey`、`QueryService`）以存储无关的方式定义了框架契约，而**MongoDB 特定的实现**将这些契约映射为 MongoDB 响应式驱动器原语（`insertOne`、`replaceOne`、`find`、`countDocuments`）。

## 集合 Schema

### 事件流集合（`{aggregateName}_event_stream`）

每个聚合按聚合类型定义，并使用事件流 ID 作为主键（`_id`）。`body` 字段存储一个序列化领域事件的数组。

| 字段 | 类型 | 已索引 | 描述 |
|---|---|---|---|
| `_id` | String | 主键 | 事件流标识符（例如，`"event-stream-id"`） |
| `aggregateId` | String | Hashed + Unique（与 version） | 聚合根标识符 |
| `tenantId` | String | Hashed | 多租户分区键 |
| `requestId` | String | Unique（复合） | 命令请求幂等性键 |
| `commandId` | String | -- | 发起命令标识符 |
| `version` | Integer | Unique（与 aggregateId） | 事件发生时的聚合版本 |
| `header` | Object | -- | 元数据（例如，`upstream_id` 用于 saga 追踪） |
| `body` | Array | -- | 领域事件负载的有序列表 |
| `size` | Integer | -- | 此事件流中的事件数量 |
| `createTime` | Long | -- | 纪元毫秒时间戳 |

<!-- Source: wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/Documents.kt:58-63 (toDocument contains all fields from DomainEventStream including aggregateId, version, header, body, size, createTime) -->

### 快照集合（`{aggregateName}_snapshot`）

快照使用聚合 ID 作为主键（`_id`），使其成为最新状态的自然查找。`state` 字段包含序列化的聚合状态对象。

| 字段 | 类型 | 已索引 | 描述 |
|---|---|---|---|
| `_id` | String | Unique | 聚合标识符（主键） |
| `contextName` | String | -- | 限界上下文名称 |
| `aggregateName` | String | -- | 聚合类型名称 |
| `tenantId` | String | Hashed | 多租户分区键 |
| `version` | Integer | -- | 快照时刻的聚合版本 |
| `eventId` | String | -- | 快照中包含的最后事件的 ID |
| `firstOperator` | String | -- | 创建聚合的初始操作者 |
| `operator` | String | -- | 最后修改聚合的操作者 |
| `firstEventTime` | Long | -- | 第一个事件的时间戳 |
| `eventTime` | Long | -- | 最后事件的时间戳 |
| `snapshotTime` | Long | -- | 快照创建时刻的时间戳 |
| `deleted` | Boolean | Hashed | 软删除标志 |
| `state` | Object | -- | 序列化的聚合状态（已类型化） |

<!-- Source: wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/Documents.kt:73-77 (Snapshot toDocument uses toLinkedHashMap which includes all snapshot fields) -->

关键的文档级转换是**主键映射**：事件流内部将其 ID 存储为 `_id`，但 `DomainEventStream` 模型使用 `id` —— `Documents.replaceIdToPrimaryKey()` 和 `replacePrimaryKeyToId()` 透明地处理双向映射。类似地，快照通过 `replaceAggregateIdToPrimaryKey()` 和 `replacePrimaryKeyToAggregateId()` 在 `_id` 和 `aggregateId` 之间映射。

### PrepareKey 集合（`prepare_{keyName}`）

| 字段 | 类型 | 已索引 | 描述 |
|---|---|---|---|
| `_id` | String | Hashed | 键值（唯一） |
| `value` | Object | -- | 已预备的值负载 |
| `ttlAt` | Date | Ascending（TTL） | 生存时间过期时间戳 |

<!-- Source: wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/prepare/MongoPrepareKey.kt:55-66 (prepareCollection init block, TTL index) -->

### 集合命名规则

集合名称通过确定性的后缀从聚合元数据派生。此约定定义在 [AggregateSchemaInitializer.kt:33-37](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/AggregateSchemaInitializer.kt#L33-L37) 中：

| 数据类型 | 命名模式 | 示例 |
|---|---|---|
| Event Stream | `{aggregateName}_event_stream` | `order_event_stream` |
| Snapshot | `{aggregateName}_snapshot` | `order_snapshot` |
| Prepare Key | `prepare_{name}` | `prepare_username_idx` |

## Schema 初始化与索引

`wow.mongo.auto-init-schema` 标志（默认 `true`）控制启动时是否自动创建集合和索引。两个初始化器处理此过程：

### EventStreamSchemaInitializer

在初始化时，[EventStreamSchemaInitializer.initSchema()](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/EventStreamSchemaInitializer.kt#L51-L69) 方法：

1. 通过 `database.ensureCollection(collectionName)` 确保集合存在
2. 在 `aggregateId` 上创建**哈希索引**以支持快速的聚合范围查询
3. 创建**唯一复合索引** `{aggregateId: 1, version: 1}` 用于乐观并发控制
4. 根据 `enableRequestIdUniqueIndex` 标志（默认 `false`，用于分片集群兼容），创建全局 `requestId` 唯一索引或复合 `{aggregateId, requestId}` 唯一索引
5. 在 `tenantId` 和 `ownerId` 上创建哈希索引用于多租户过滤

| 索引 | 字段 | 类型 | 用途 |
|---|---|---|---|
| `aggregateId_hashed` | `aggregateId` | Hashed | 聚合范围查询 |
| `aggregateId_1_version_1` | `aggregateId`, `version` | Unique | 乐观并发 —— 防止版本冲突 |
| `aggregateId_1_requestId_1` | `aggregateId`, `requestId` | Unique | 请求幂等性（分片安全变体） |
| `requestId_1` | `requestId` | Unique | 请求幂等性（非分片变体） |
| `tenantId_hashed` | `tenantId` | Hashed | 多租户过滤 |
| `ownerId_hashed` | `ownerId` | Hashed | 基于所有者的过滤 |

<!-- Source: EventStreamSchemaInitializer.kt:51-69 (initSchema method creating all indexes) -->

`enableRequestIdUniqueIndex` 开关存在的原因是 [MongoDB 分片集群无法跨分片强制唯一索引，除非分片键是唯一索引的一部分](https://www.mongodb.com/docs/manual/core/sharding-shard-key/#unique-indexes)。当设为 `false`（默认）时，改用复合的 `{aggregateId, requestId}` 索引，这与基于 `aggregateId` 的哈希分片兼容。

### SnapshotSchemaInitializer

[SnapshotSchemaInitializer.initSchema()](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/SnapshotSchemaInitializer.kt#L40-L55) 创建：

| 索引 | 字段 | 类型 | 用途 |
|---|---|---|---|
| `tenantId_hashed` | `tenantId` | Hashed | 多租户过滤 |
| `ownerId_hashed` | `ownerId` | Hashed | 基于所有者的过滤 |
| `_id_hashed` | `_id` | Hashed | 按 ID 快速查找聚合 |
| `deleted_hashed` | `deleted` | Hashed | 软删除过滤 |

<!-- Source: SnapshotSchemaInitializer.kt:40-55 (initSchema method creating all snapshot indexes) -->

## 查询服务

`wow-mongo` 模块提供两个查询服务实现，将 Wow 的抽象 `Condition` 对象转换为 MongoDB 过滤器文档（`Bson`）：

### 条件转换器管道

转换管道为：`Condition` -> `AbstractMongoConditionConverter` -> `Bson`（MongoDB 过滤器）。

[AbstractMongoConditionConverter](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoConditionConverter.kt#L28) 实现了 Wow [条件 API](../reference/cqrs.md) 定义的完整条件语法：

| Wow 操作符 | MongoDB 等价物 | 来源 |
|---|---|---|
| `eq` | `Filters.eq()` | [L85](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoConditionConverter.kt#L85) |
| `gt` / `gte` / `lt` / `lte` | `Filters.gt()` / `gte()` / `lt()` / `lte()` | [L89-L95](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoConditionConverter.kt#L89-L95) |
| `contains` | `Filters.regex()`（已转义） | [L119-L120](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoConditionConverter.kt#L119-L120) |
| `match` | `Filters.text()` | [L122](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoConditionConverter.kt#L122) |
| `between` | `Filters.and(Filters.gte(), Filters.lte())` | [L134-L145](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoConditionConverter.kt#L134-L145) |
| `isIn` / `notIn` | `Filters.in()` / `nin()` | [L130-L132](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoConditionConverter.kt#L130-L132) |
| `deleted`（软删除） | `Filters.eq("deleted", true/false)` 或 `Filters.empty()` | [L166-L179](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoConditionConverter.kt#L166-L179) |
| `raw` | `Document.parse()` 或直接 `Bson` | [L181-L199](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoConditionConverter.kt#L181-L199) |

转换器还通过 `FieldConverter` 应用**字段名翻译**。对于事件流，`MessageRecords.ID` 字段映射到 `_id`（[EventStreamFieldConverter.kt:20-27](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/event/EventStreamFieldConverter.kt#L20-L27)）。对于快照，`MessageRecords.AGGREGATE_ID` 映射到 `_id`（[SnapshotFieldConverter.kt:7-15](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/SnapshotFieldConverter.kt#L7-L15)）。这保持了应用层的查询模型一致性，无论底层主键策略如何。

### 分页支持

[AbstractMongoQueryService.paged()](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoQueryService.kt#L83-L105) 通过 `Mono.zip()` 并行执行两个查询，实现高效分页：

1. `collection.countDocuments(filter)` —— 总数
2. `collection.find(filter).projection().sort().skip().limit()` —— 数据页

两者都是利用 MongoDB 原生游标分页的非阻塞响应式操作。

### 快照作为读模型

快照同时充当读模型 —— `state` 字段可以通过 `MongoSnapshotQueryService` 直接查询。该服务使用 `MaterializedSnapshot<S>` 作为其类型化结果包装器，其中 `S` 是聚合的状态类型，在构造时从聚合元数据解析得出：

```kotlin
val snapshotType = JsonSerializer.typeFactory
    .constructParametricType(
        MaterializedSnapshot::class.java,
        namedAggregate.requiredAggregateType<Any>()
            .aggregateMetadata<Any, S>().state.aggregateType
    )
```

<!-- Source: wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryService.kt:44-48 -->

这使得可以直接针对聚合状态字段进行类型安全的动态查询 —— 例如，查询 `state.status` 或 `state.totalAmount`，而无需单独的投影处理器。

## PrepareKey：分布式协调

[MongoPrepareKey](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/prepare/MongoPrepareKey.kt#L46-L163) 实现了 Wow 的 `PrepareKey<V>` 接口，以 MongoDB 为协调后端进行分布式键预留。每个逻辑键成为一个 `prepare_{name}` 集合。

该实现使用三个 MongoDB 原语来实现协调：

| 操作 | MongoDB 方法 | 行为 |
|---|---|---|
| `prepare()` | `replaceOne` 带过滤 `{_id: key, ttlAt: {$lt: now}}` | CAS 风格的 upsert —— 仅在没有未过期条目时成功 |
| `rollback()` | `deleteOne` 带过滤 `{_id: key, ttlAt: {$gt: now}}` | 移除活跃预留（仅在未过期时） |
| `reprepare()` | `updateOne` 带 `$set` 更新 value + `ttlAt` | 原子性地延长或替换预留 |

<!-- Source: wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/prepare/MongoPrepareKey.kt:69-163 -->

TTL 索引（`{ttlAt: 1}` 附带 `expireAfter: 0 seconds`）确保 MongoDB 自动移除过期条目，提供不需要应用级干预的清理机制。

## 错误映射

MongoDB 重复键错误通过 [ErrorMapping.toWowError()](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/ErrorMapping.kt#L23-L41) 转换为 Wow 框架异常：

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

<!-- Source: wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/ErrorMapping.kt:23-41 -->

该映射依赖嵌入在 MongoDB 错误信息中的索引名称。这是一种实用的方法，避免了单独的元数据查找：索引名称（`aggregateId_1_version_1` 或 `requestId_1`）唯一标识了哪个约束被违反。

- `EventVersionConflictException` —— 表示乐观并发冲突。框架自动重试该命令。
- `DuplicateRequestIdException` —— 表示该命令已被处理。框架将此视为幂等成功。

对于 `MongoPrepareKey`，`prepare()` 期间的重复键错误被单独捕获，并映射到 `Mono.just(false)`，表示无法获取键（另一个进程持有该键）。

## 配置

### 配置属性

`MongoProperties` 类绑定到 `wow.mongo` 前缀，定义在 [MongoProperties.kt:21-32](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/mongo/MongoProperties.kt#L21-L32)。

| 属性 | 类型 | 默认值 | 描述 | 来源 |
|---|---|---|---|---|
| `wow.mongo.enabled` | `Boolean` | `true` | MongoDB 集成的主开关 | [MongoProperties.kt:23](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/mongo/MongoProperties.kt#L23) |
| `wow.mongo.auto-init-schema` | `Boolean` | `true` | 启动时自动创建集合和索引 | [MongoProperties.kt:24](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/mongo/MongoProperties.kt#L24) |
| `wow.mongo.event-stream-database` | `String?` | `null` | 事件流集合的数据库（默认使用 Spring Mongo 数据库） | [MongoProperties.kt:25](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/mongo/MongoProperties.kt#L25) |
| `wow.mongo.snapshot-database` | `String?` | `null` | 快照集合的数据库（默认使用 Spring Mongo 数据库） | [MongoProperties.kt:26](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/mongo/MongoProperties.kt#L26) |
| `wow.mongo.prepare-database` | `String?` | `null` | PrepareKey 集合的数据库（默认使用 Spring Mongo 数据库） | [MongoProperties.kt:27](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/mongo/MongoProperties.kt#L27) |

### 核心事件溯源开关

除了 MongoDB 专有属性外，`wow.eventsourcing` 中的两个属性控制使用哪个存储后端：

```yaml
wow:
  eventsourcing:
    store:
      storage: mongo        # 选择 MongoEventStore
    snapshot:
      storage: mongo        # 选择 MongoSnapshotRepository
```

这些开关使框架实例化 `MongoEventStore`（而非默认的内存存储），以及用于快照持久化的 `MongoSnapshotRepository`。

### 完整配置示例

```yaml
spring:
  data:
    mongodb:
      uri: mongodb://user:password@mongo1:27017,mongo2:27017,mongo3:27017/wow_db
        ?replicaSet=rs0
        &w=majority
        &readPreference=secondaryPreferred
        &minPoolSize=10
        &maxPoolSize=100

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

## 性能考量

### 数据库分离

三个可配置的数据库（`event-stream-database`、`snapshot-database`、`prepare-database`）使工作负载的**物理隔离**成为可能：

- **事件流**：写密集型（仅追加），受益于快速存储
- **快照**：读取密集型（物化视图），受益于缓存和读取副本
- **Prepare Keys**：低量、短生命周期文档，受益于 TTL 索引清理

当三者都默认为 `null` 时，它们共享 Spring 配置的 MongoDB 数据库，这对于开发和中低负载是足够的。对于生产环境，将它们分离可以实现独立的扩缩容、备份计划和读取偏好调优。

### 连接池调优

MongoDB 连接池大小通过 Spring Boot 的 `spring.data.mongodb.uri` 查询参数配置：

| 参数 | 推荐值 | 原因 |
|---|---|---|
| `minPoolSize` | `10` | 确保流量高峰期间有热连接 |
| `maxPoolSize` | `100` | 上限以防止连接耗尽 |
| `maxIdleTimeMS` | `60000` | 60 秒后回收空闲连接 |

### 写入关注

对于生产环境的事件溯源，`w=majority` 确保事件在命令返回之前被多数副本集成员确认。这可以防止故障切换期间的数据丢失，代价是略高的写入延迟。

### 读取偏好

设置 `readPreference=secondaryPreferred` 将快照读取查询卸载到次要节点，减少主节点的负载。事件流写入始终发送到主节点。

### 分片策略

对于大规模部署，按聚合 ID 对事件流和快照集合进行分片：

```javascript
// 哈希分片将写入均匀分布到各个分片
sh.shardCollection("wow_event_db.order_event_stream", { "aggregateId": "hashed" })
sh.shardCollection("wow_snapshot_db.order_snapshot", { "_id": "hashed" })
```

**重要提示**：在使用分片集合时，保持 `EventStreamSchemaInitializer.enableRequestIdUniqueIndex = false`（默认）。除非分片键是索引的一部分，否则 MongoDB 无法跨分片强制执行唯一索引。复合的 `{aggregateId, requestId}` 索引与分片兼容，因为 `aggregateId` 是分片键。

## 相关页面

| 页面 | 关系 |
|---|---|
| [Mongo 配置](../reference/config/mongo.md) | `wow.mongo.*` 属性的配置参考 |
| [事件溯源配置](../reference/config/eventsourcing.md) | 存储后端选择（`wow.eventsourcing.store.storage`） |
| [快照配置](../reference/config/snapshot.md) | 快照策略与存储后端选择 |
| [CQRS 参考](../reference/cqrs.md) | Wow 的动态查询 `Condition` API |
| [Redis 集成](./redis.md) | 替代的事件存储和快照后端 |
| [R2DBC 集成](./r2dbc.md) | 基于 SQL 的事件存储替代方案 |
