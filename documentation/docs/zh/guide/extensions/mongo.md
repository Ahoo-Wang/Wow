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
    MSR -->|"replaceOne (upsert)"| SSCol
    CM -->|"prepare()"| MPK
    MPK -->|"replaceOne"| PKCol
    QS -->|"dynamicQuery()"| MESQ
    MESQ -->|"find()"| ESColl
    QS -->|"dynamicQuery()"| MSQS
    MSQS -->|"find()"| SSCol
```

每种聚合类型拥有自己的集合，按聚合名称分区。这种设计将热聚合彼此隔离，并支持按聚合进行分片和索引调优。

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

    ES->>Coll: insertOne(document)
    Coll->>DB: 插入文档，_id = eventStreamId
    DB-->>Coll: InsertOneResult

    alt 写入已确认
        Coll-->>ES: onNext(result)
        ES->>ES: check(wasAcknowledged())
        ES-->>AR: Mono.empty()（成功）
    else 重复版本 (aggregateId + version)
        DB-->>Coll: MongoWriteException (DUPLICATE_KEY, u_idx_aggregate_id_version)
        Coll->>Err: onErrorMap(MongoWriteException)
        Err->>Err: toWowError() - 匹配 "aggregateId_1_version_1"
        Err-->>ES: EventVersionConflictException
        ES-->>AR: EventVersionConflictException
    else 重复 requestId
        DB-->>Coll: MongoWriteException (DUPLICATE_KEY, u_idx_request_id)
        Coll->>Err: onErrorMap(MongoWriteException)
        Err->>Err: toWowError() - 匹配 "requestId_1"
        Err-->>ES: DuplicateRequestIdException
        ES-->>AR: DuplicateRequestIdException
    end
```

关键设计洞察是 **MongoDB 唯一索引扮演双重角色**：`{aggregateId, version}` 复合唯一索引强制执行乐观并发控制（同一版本不能有两处写入），而 `{requestId}` 唯一索引提供命令幂等性（无重复处理）。在违反索引约束时，`ErrorMapping.toWowError()` 将原始的 `MongoWriteException` 转换为 Wow 框架的类型异常，以便框架无论在何种存储后端都能统一处理。

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

**YAML 配置示例**

```yaml
spring:
  data:
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
```

## 集合模式

### 集合命名规则

集合名称根据聚合元数据使用确定性后缀派生，定义在 [AggregateSchemaInitializer.kt](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/AggregateSchemaInitializer.kt) 中：

| 数据类型 | 集合命名格式 | 示例 |
|---|---|---|
| 事件流 | `{aggregateName}_event_stream` | `order_event_stream` |
| 快照 | `{aggregateName}_snapshot` | `order_snapshot` |
| PrepareKey | `prepare_{name}` | `prepare_username_idx` |

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

`wow.mongo.auto-init-schema` 标志（默认 `true`）控制在启动时是否自动创建集合和索引。两个初始化器处理此过程：

### EventStreamSchemaInitializer

在初始化时，`EventStreamSchemaInitializer.initSchema()` 方法：

1. 通过 `database.ensureCollection(collectionName)` 确保集合存在
2. 在 `aggregateId` 上创建 **哈希索引** 以支持快速的聚合范围查询
3. 创建 **唯一复合索引** `{aggregateId: 1, version: 1}` 用于乐观并发控制
4. 根据 `enableRequestIdUniqueIndex` 标志（默认为 `false` 以兼容分片集群），创建全局 `requestId` 唯一索引或复合 `{aggregateId, requestId}` 唯一索引
5. 在 `tenantId` 和 `ownerId` 上创建哈希索引以支持多租户过滤

| 索引 | 字段 | 类型 | 用途 |
|---|---|---|---|
| `aggregateId_hashed` | `aggregateId` | 哈希 | 聚合范围查询 |
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

`wow-mongo` 模块提供两个查询服务实现，将 Wow 的抽象 `Condition` 对象转换为 MongoDB 过滤器文档（`Bson`）。

### 条件转换管道

转换管道为：`Condition` -> `AbstractMongoConditionConverter` -> `Bson`（MongoDB 过滤器）。

| Wow 操作符 | MongoDB 等价操作 |
|---|---|
| `eq` | `Filters.eq()` |
| `gt` / `gte` / `lt` / `lte` | `Filters.gt()` / `gte()` / `lt()` / `lte()` |
| `contains` | `Filters.regex()`（已转义） |
| `match` | `Filters.text()` |
| `between` | `Filters.and(Filters.gte(), Filters.lte())` |
| `isIn` / `notIn` | `Filters.in()` / `nin()` |
| `deleted`（软删除） | `Filters.eq("deleted", true/false)` 或 `Filters.empty()` |
| `raw` | `Document.parse()` 或直接 `Bson` |

转换器还通过 `FieldConverter` 应用 **字段名转换**。对于事件流，`MessageRecords.ID` 字段映射到 `_id`。对于快照，`MessageRecords.AGGREGATE_ID` 映射到 `_id`。这使得应用层查询模型在整个底层主键策略中保持一致。

### 快照查询

快照存储可直接用作读模型，支持丰富的查询条件：

```kotlin
// 分页快照查询
val condition = Condition.all()
    .eq("state.status", "PAID")
    .gt("state.totalAmount", 50.00)
    .limit(10)
    .sort("snapshotTime".desc())

snapshotQueryService.dynamicQuery(condition)
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
    }

    class SnapshotStore {
        <<interface>>
        +load(AggregateId) Mono~Snapshot~
        +save(Snapshot) Mono~Void~
        +scanAggregateId(NamedAggregate, String, Int) Flux~AggregateId~
    }

    class MongoSnapshotStore {
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

    AbstractEventStore <|-- MongoEventStore
    SnapshotStore <|.. MongoSnapshotStore
    PrepareKey <|.. MongoPrepareKey
    AbstractMongoQueryService <|-- MongoEventStreamQueryService
    AbstractMongoQueryService <|-- MongoSnapshotQueryService
```

类层级揭示了两层抽象：**Wow 核心接口**（`AbstractEventStore`、`SnapshotStore`、`PrepareKey`、`QueryService`）以存储无关的方式定义了框架契约，而 **Mongo 特定实现** 将这些契约映射到 MongoDB 的响应式驱动原语（`insertOne`、`replaceOne`、`find`、`countDocuments`）。

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
db.order_snapshot.createIndex(
  { "state.status": 1, "snapshotTime": -1 },
  { name: "idx_status_time" }
)

db.order_snapshot.createIndex(
  { "tenantId": 1, "deleted": 1 },
  { name: "idx_tenant_deleted" }
)
```

## 性能优化

### 连接池配置

```yaml
spring:
  data:
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
  data:
    mongodb:
      uri: mongodb://localhost:27017/wow_db?w=majority&wtimeoutMS=5000
```

### 读取偏好配置

设置 `readPreference=secondaryPreferred` 将快照读取查询卸载到从节点，减少主节点的负载。事件流写入始终发往主节点。

```yaml
spring:
  data:
    mongodb:
      uri: mongodb://localhost:27017/wow_db?readPreference=secondaryPreferred
```

### 数据库分离

三个可配置的数据库（`event-stream-database`、`snapshot-database`、`prepare-database`）实现了工作负载的 **物理隔离**：

- **事件流**：写入密集（仅追加），受益于快速存储
- **快照**：读取密集（物化视图），受益于缓存和读取副本
- **PrepareKey**：低容量、短生命周期文档，受益于 TTL 索引清理

当三者都为默认值 `null` 时，它们共享 Spring 配置的 MongoDB 数据库，这对开发和中度负载已经足够。对于生产环境，将它们分开可以实现独立的扩展、备份计划和读取偏好调优。

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
  data:
    mongodb:
      uri: mongodb://user:password@mongo1:27017,mongo2:27017,mongo3:27017/wow_db?replicaSet=rs0&w=majority&readPreference=secondaryPreferred&minPoolSize=10&maxPoolSize=100

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

1. **数据库分离**：将事件流、快照和 PrepareKey 存储在不同的数据库中，以实现独立扩展和管理
2. **启用快照**：对于拥有大量事件的聚合，启用快照可以显著提高加载性能
3. **使用副本集**：在生产环境中使用副本集以实现高可用性
4. **索引优化**：根据查询模式创建适当的复合索引
5. **使用分片扩展**：当数据量大时使用分片进行水平扩展

## 相关主题

| 主题 | 描述 |
|---|---|
| [MongoDB 配置参考](../../reference/config/mongo.md) | `wow.mongo.*` 属性的配置参考 |
| [事件溯源配置](../../reference/config/eventsourcing.md) | 存储后端选择（`wow.eventsourcing.store.storage`） |
| [快照配置](../../reference/config/snapshot.md) | 快照策略和存储后端选择 |
| [Redis 扩展](redis.md) | 替代的事件存储和快照后端 |
| [R2DBC 扩展](r2dbc.md) | 基于 SQL 的事件存储替代方案 |
| [Spring Boot Starter](spring-boot-starter.md) | 自动配置和功能变体 |
