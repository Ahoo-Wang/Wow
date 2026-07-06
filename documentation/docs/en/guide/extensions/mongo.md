---
title: Mongo
description: MongoDB extension providing EventStore and SnapshotStore for production environments.
---

# Mongo

The _Mongo_ extension provides support for MongoDB and is the recommended event store and snapshot storage implementation for production environments. It implements the following interfaces:

- `EventStore` - Event storage
- `EventStreamQueryService` - Event stream query service
- `SnapshotStore` - Snapshot store
- `SnapshotQueryService` - Snapshot query service
- `PrepareKey` - Distributed key reservation with TTL-based expiration

The module is designed as a drop-in backend. When `wow.eventsourcing.store.storage` is set to `mongo`, the framework replaces its default in-memory stores with MongoDB-backed implementations that handle concurrency, idempotency, and schema lifecycle automatically.

## Architecture Overview

```mermaid
graph TB
    subgraph App["Application Layer (wow-core)"]
        direction LR
        AR["Aggregate Root"]
        CM["Command Gateway"]
        QS["Query Services"]
    end

    subgraph MongoEvent["MongoDB - Event Stream Database"]
        ESColl[("{aggregateName}_event_stream<br>Collection")]
    end

    subgraph MongoSnap["MongoDB - Snapshot Database"]
        SSCol[("{aggregateName}_snapshot<br>Collection")]
    end

    subgraph MongoPrep["MongoDB - Prepare Database"]
        PKCol[("prepare_{keyName}<br>Collection")]
    end

    subgraph Impl["wow-mongo Implementations"]
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

Each aggregate type gets its own collection, partitioned by aggregate name. This design isolates hot aggregates from each other and enables per-aggregate sharding and index tuning.

## Installation

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

## Core Components

| Component | Contract Implemented | Key File | Responsibility |
|---|---|---|---|
| `MongoEventStore` | `AbstractEventStore` | [MongoEventStore.kt](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/MongoEventStore.kt) | Append, load, and query domain event streams |
| `MongoSnapshotStore` | `SnapshotStore` | [MongoSnapshotStore.kt](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/MongoSnapshotStore.kt) | Save, load, and version-check aggregate snapshots |
| `MongoPrepareKey` | `PrepareKey<V>` | [MongoPrepareKey.kt](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/prepare/MongoPrepareKey.kt) | Distributed key reservation with TTL-based expiration |
| `MongoEventStreamQueryService` | `EventStreamQueryService` | [MongoEventStreamQueryService.kt](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/event/MongoEventStreamQueryService.kt) | Dynamic querying of raw event streams |
| `MongoSnapshotQueryService` | `SnapshotQueryService<S>` | [MongoSnapshotQueryService.kt](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryService.kt) | Dynamic querying of snapshots as materialized read models |
| `EventStreamSchemaInitializer` | (standalone) | [EventStreamSchemaInitializer.kt](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/EventStreamSchemaInitializer.kt) | Creates collections + indexes for event streams |
| `SnapshotSchemaInitializer` | (standalone) | [SnapshotSchemaInitializer.kt](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/SnapshotSchemaInitializer.kt) | Creates collections + indexes for snapshots |

## Event Append Sequence

The following sequence shows the full path from an aggregate root producing events to the MongoDB document being persisted, including the optimistic concurrency and idempotency guards.

```mermaid
sequenceDiagram
    autonumber
    participant AR as AggregateRoot
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
    Coll->>DB: insert document with _id = eventStreamId
    DB-->>Coll: InsertOneResult

    alt Write acknowledged
        Coll-->>ES: onNext(result)
        ES->>ES: check(wasAcknowledged())
        ES-->>AR: Mono.empty() (success)
    else Duplicate version (aggregateId + version)
        DB-->>Coll: MongoWriteException (DUPLICATE_KEY, u_idx_aggregate_id_version)
        Coll->>Err: onErrorMap(MongoWriteException)
        Err->>Err: toWowError() - matches "aggregateId_1_version_1"
        Err-->>ES: EventVersionConflictException
        ES-->>AR: EventVersionConflictException
    else Duplicate requestId
        DB-->>Coll: MongoWriteException (DUPLICATE_KEY, u_idx_request_id)
        Coll->>Err: onErrorMap(MongoWriteException)
        Err->>Err: toWowError() - matches "requestId_1"
        Err-->>ES: DuplicateRequestIdException
        ES-->>AR: DuplicateRequestIdException
    end
```

The key design insight is that **MongoDB unique indexes serve dual roles**: the `{aggregateId, version}` compound unique index enforces optimistic concurrency (no two writes at the same version), while the `{requestId}` unique index provides command idempotency (no duplicate processing). On violation, `ErrorMapping.toWowError()` translates the raw `MongoWriteException` into the Wow framework's typed exceptions so the framework can handle them uniformly regardless of storage backend.

## Configuration

- Configuration class: [MongoProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/mongo/MongoProperties.kt)
- Prefix: `wow.mongo.`

| Name | Data Type | Default Value | Description |
|---|---|---|---|
| `enabled` | `Boolean` | `true` | Whether to enable |
| `auto-init-schema` | `Boolean` | `true` | Whether to auto-generate *Schema* |
| `event-stream-database` | `String` | Database name configured by Spring Boot Mongo module | Event stream database name |
| `snapshot-database` | `String` | Database name configured by Spring Boot Mongo module | Snapshot database name |
| `prepare-database` | `String` | Database name configured by Spring Boot Mongo module | `PrepareKey` database name |

**YAML Configuration Example**

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

## Collection Schema

### Collection Naming Rules

Collection names are derived from aggregate metadata using deterministic suffixes, defined in [AggregateSchemaInitializer.kt](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/AggregateSchemaInitializer.kt):

| Data Type | Collection Naming Format | Example |
|---|---|---|
| Event Stream | `{aggregateName}_event_stream` | `order_event_stream` |
| Snapshot | `{aggregateName}_snapshot` | `order_snapshot` |
| Prepare Key | `prepare_{name}` | `prepare_username_idx` |

### Event Stream Collection (`{aggregateName}_event_stream`)

Each aggregate is defined per-aggregate-type and uses the event stream ID as the primary key (`_id`). The `body` field stores an array of serialized domain events.

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

| Field | Type | Indexed | Description |
|---|---|---|---|
| `_id` | String | Primary | Event stream identifier |
| `aggregateId` | String | Hashed + Unique (with version) | Aggregate root identifier |
| `tenantId` | String | Hashed | Multi-tenancy partition key |
| `requestId` | String | Unique (composite) | Command request idempotency key |
| `commandId` | String | -- | Originating command identifier |
| `version` | Integer | Unique (with aggregateId) | Aggregate version at time of event |
| `header` | Object | -- | Metadata (e.g., `upstream_id` for saga tracking) |
| `body` | Array | -- | Ordered list of domain event payloads |
| `size` | Integer | -- | Number of events in this stream |
| `createTime` | Long | -- | Epoch milliseconds timestamp |

### Snapshot Collection (`{aggregateName}_snapshot`)

Snapshots use the aggregate ID as the primary key (`_id`), making it a natural lookup for the latest state. The `state` field contains the serialized aggregate state object.

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

| Field | Type | Indexed | Description |
|---|---|---|---|
| `_id` | String | Unique | Aggregate identifier (primary key) |
| `contextName` | String | -- | Bounded context name |
| `aggregateName` | String | -- | Aggregate type name |
| `tenantId` | String | Hashed | Multi-tenancy partition key |
| `version` | Integer | -- | Aggregate version at snapshot time |
| `eventId` | String | -- | ID of the last event included in snapshot |
| `firstOperator` | String | -- | Initial operator who created the aggregate |
| `operator` | String | -- | Last operator who modified the aggregate |
| `firstEventTime` | Long | -- | Timestamp of the first event |
| `eventTime` | Long | -- | Timestamp of the last event |
| `snapshotTime` | Long | -- | Timestamp when snapshot was created |
| `deleted` | Boolean | Hashed | Soft-delete flag |
| `state` | Object | -- | Serialized aggregate state (typed) |

### PrepareKey Collection (`prepare_{keyName}`)

| Field | Type | Indexed | Description |
|---|---|---|---|
| `_id` | String | Hashed | Key value (unique) |
| `value` | Object | -- | Prepared value payload |
| `ttlAt` | Date | Ascending (TTL) | Time-to-live expiration timestamp |

The key document-level transformation is the **primary key mapping**: event streams store their ID internally as `_id` but the `DomainEventStream` model uses `id` -- `Documents.replaceIdToPrimaryKey()` and `replacePrimaryKeyToId()` handle the bidirectional mapping transparently. Similarly, snapshots map between `_id` and `aggregateId` via `replaceAggregateIdToPrimaryKey()` and `replacePrimaryKeyToAggregateId()`.

## Schema Initialization and Indexes

The `wow.mongo.auto-init-schema` flag (default `true`) controls whether collections and indexes are created automatically on startup. Two initializers handle this:

### EventStreamSchemaInitializer

On initialization, the `EventStreamSchemaInitializer.initSchema()` method:

1. Ensures the collection exists via `database.ensureCollection(collectionName)`
2. Creates a **hashed index** on `aggregateId` for fast aggregate-scoped queries
3. Creates the **unique compound index** `{aggregateId: 1, version: 1}` for optimistic concurrency control
4. Creates either a global `requestId` unique index or a compound `{aggregateId, requestId}` unique index, depending on the `enableRequestIdUniqueIndex` flag (default `false` for sharded cluster compatibility)
5. Creates hashed indexes on `tenantId` and `ownerId` for multi-tenancy filtering

| Index | Fields | Type | Purpose |
|---|---|---|---|
| `aggregateId_hashed` | `aggregateId` | Hashed | Aggregate-scoped queries |
| `aggregateId_1_version_1` | `aggregateId`, `version` | Unique | Optimistic concurrency -- prevents version conflicts |
| `aggregateId_1_requestId_1` | `aggregateId`, `requestId` | Unique | Request idempotency (shard-safe variant) |
| `requestId_1` | `requestId` | Unique | Request idempotency (non-sharded variant) |
| `tenantId_hashed` | `tenantId` | Hashed | Multi-tenancy filtering |
| `ownerId_hashed` | `ownerId` | Hashed | Owner-based filtering |

The `enableRequestIdUniqueIndex` toggle exists because MongoDB sharded clusters cannot enforce unique indexes across shards unless the shard key is part of the unique index. When `false` (the default), the compound `{aggregateId, requestId}` index is used instead, which is compatible with hashed sharding on `aggregateId`.

### SnapshotSchemaInitializer

The `SnapshotSchemaInitializer.initSchema()` creates:

| Index | Fields | Type | Purpose |
|---|---|---|---|
| `tenantId_hashed` | `tenantId` | Hashed | Multi-tenancy filtering |
| `ownerId_hashed` | `ownerId` | Hashed | Owner-based filtering |
| `_id_hashed` | `_id` | Hashed | Fast aggregate lookup by ID |
| `deleted_hashed` | `deleted` | Hashed | Soft-delete filtering |

## Query Services

The `wow-mongo` module provides two query service implementations that translate Wow's abstract `Condition` objects into MongoDB filter documents (`Bson`).

### Condition Converter Pipeline

The conversion pipeline is: `Condition` -> `AbstractMongoConditionConverter` -> `Bson` (MongoDB filter).

| Wow Operator | MongoDB Equivalent |
|---|---|
| `eq` | `Filters.eq()` |
| `gt` / `gte` / `lt` / `lte` | `Filters.gt()` / `gte()` / `lt()` / `lte()` |
| `contains` | `Filters.regex()` (escaped) |
| `match` | `Filters.text()` |
| `between` | `Filters.and(Filters.gte(), Filters.lte())` |
| `isIn` / `notIn` | `Filters.in()` / `nin()` |
| `deleted` (soft-delete) | `Filters.eq("deleted", true/false)` or `Filters.empty()` |
| `raw` | `Document.parse()` or direct `Bson` |

The converter also applies **field name translation** via `FieldConverter`. For event streams, the `MessageRecords.ID` field is mapped to `_id`. For snapshots, `MessageRecords.AGGREGATE_ID` is mapped to `_id`. This keeps the application-layer query model consistent regardless of the underlying primary key strategy.

### Snapshot Queries

Snapshot storage can be used directly as a read model, supporting rich query conditions:

```kotlin
// Paginated snapshot query
val condition = Condition.all()
    .eq("state.status", "PAID")
    .gt("state.totalAmount", 50.00)
    .limit(10)
    .sort("snapshotTime".desc())

snapshotQueryService.dynamicQuery(condition)
```

The `MongoSnapshotQueryService` uses `MaterializedSnapshot<S>` as its typed result wrapper, where `S` is the aggregate's state type resolved from the aggregate metadata. This enables type-safe dynamic queries directly against aggregate state fields -- for example, querying `state.status` or `state.totalAmount` without a separate projection processor.

## PrepareKey: Distributed Coordination

`MongoPrepareKey` implements Wow's `PrepareKey<V>` interface for distributed key reservation with MongoDB as the coordination backend. Each logical key becomes a `prepare_{name}` collection.

The implementation uses three MongoDB primitives to achieve coordination:

| Operation | MongoDB Method | Behavior |
|---|---|---|
| `prepare()` | `replaceOne` with filter `{_id: key, ttlAt: {$lt: now}}` | CAS-style upsert -- only succeeds if no unexpired entry exists |
| `rollback()` | `deleteOne` with filter `{_id: key, ttlAt: {$gt: now}}` | Removes active reservation (only if not expired) |
| `reprepare()` | `updateOne` with `$set` on value + `ttlAt` | Extends or replaces a reservation atomically |

The TTL index (`{ttlAt: 1}` with `expireAfter: 0 seconds`) ensures MongoDB automatically removes expired entries, providing a cleanup mechanism that does not require application-level intervention.

## Error Mapping

MongoDB duplicate key errors are translated into Wow framework exceptions via [ErrorMapping.toWowError()](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/ErrorMapping.kt):

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

The mapping relies on the index name embedded in the MongoDB error message:

- `EventVersionConflictException` -- signals an optimistic concurrency conflict. The framework retries the command automatically.
- `DuplicateRequestIdException` -- signals that the command was already processed. The framework treats this as idempotent success.

## Class Hierarchy

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

The class hierarchy reveals two layers of abstraction: the **Wow core interfaces** (`AbstractEventStore`, `SnapshotStore`, `PrepareKey`, `QueryService`) define the framework contract in a storage-agnostic way, while the **Mongo-specific implementations** map those contracts onto MongoDB's reactive driver primitives (`insertOne`, `replaceOne`, `find`, `countDocuments`).

## Index Optimization Recommendations

### Event Stream Indexes

```javascript
// Recommended additional indexes
db.order_event_stream.createIndex(
  { "createTime": 1 },
  { name: "idx_create_time" }
)

db.order_event_stream.createIndex(
  { "body.name": 1, "createTime": 1 },
  { name: "idx_event_type_time" }
)
```

### Snapshot Indexes

```javascript
// Create compound indexes based on query patterns
db.order_snapshot.createIndex(
  { "state.status": 1, "snapshotTime": -1 },
  { name: "idx_status_time" }
)

db.order_snapshot.createIndex(
  { "tenantId": 1, "deleted": 1 },
  { name: "idx_tenant_deleted" }
)
```

## Performance Optimization

### Connection Pool Configuration

```yaml
spring:
  data:
    mongodb:
      uri: mongodb://localhost:27017/wow_db?minPoolSize=10&maxPoolSize=100&maxIdleTimeMS=60000
```

| Parameter | Description | Recommended Value |
|---|---|---|
| `minPoolSize` | Minimum connections | 10 |
| `maxPoolSize` | Maximum connections | 100 |
| `maxIdleTimeMS` | Maximum idle time | 60000 |

### Write Concern Configuration

For production event sourcing, `w=majority` ensures events are acknowledged by a majority of replica set members before the command returns. This prevents data loss during failover at the cost of slightly higher write latency.

```yaml
spring:
  data:
    mongodb:
      uri: mongodb://localhost:27017/wow_db?w=majority&wtimeoutMS=5000
```

### Read Preference Configuration

Setting `readPreference=secondaryPreferred` offloads snapshot read queries to secondary nodes, reducing load on the primary. Event stream writes always go to the primary.

```yaml
spring:
  data:
    mongodb:
      uri: mongodb://localhost:27017/wow_db?readPreference=secondaryPreferred
```

### Database Separation

The three configurable databases (`event-stream-database`, `snapshot-database`, `prepare-database`) enable **physical isolation** of workloads:

- **Event streams**: Write-heavy (append-only), benefits from fast storage
- **Snapshots**: Read-heavy (materialized views), benefits from caching and read replicas
- **Prepare keys**: Low volume, short-lived documents, benefits from TTL index cleanup

When all three default to `null`, they share the Spring-configured MongoDB database, which is sufficient for development and moderate loads. For production, separating them allows independent scaling, backup schedules, and read-preference tuning.

## Sharding Strategy

For large-scale data, MongoDB sharding is recommended:

```javascript
// Hashed sharding distributes writes evenly across shards
sh.shardCollection("wow_event_db.order_event_stream", { "aggregateId": "hashed" })
sh.shardCollection("wow_snapshot_db.order_snapshot", { "_id": "hashed" })
```

::: warning
When using sharded collections, keep `EventStreamSchemaInitializer.enableRequestIdUniqueIndex = false` (the default). MongoDB cannot enforce a unique index across shards unless the shard key is part of the index. The compound `{aggregateId, requestId}` index is shard-compatible because `aggregateId` is the shard key.
:::

## Troubleshooting

### Common Issues

#### 1. Version Conflict Exception

```
me.ahoo.wow.eventsourcing.EventVersionConflictException
```

**Cause**: Concurrent writes to the same aggregate root

**Solutions**:
- This is normal optimistic locking behavior, the framework will automatically retry
- If it occurs frequently, consider optimizing business processes to reduce conflicts

#### 2. Duplicate Request Exception

```
me.ahoo.wow.eventsourcing.DuplicateRequestIdException
```

**Cause**: The same `requestId` was processed repeatedly

**Solutions**:
- This is idempotency protection, indicating the request was already processed successfully
- Check if the client has duplicate submissions

#### 3. Connection Timeout

```
com.mongodb.MongoTimeoutException
```

**Solutions**:
- Check if MongoDB service is running normally
- Increase connection pool size
- Check network latency

## Complete Configuration Example

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

## Best Practices

1. **Database Separation**: Store event streams, snapshots, and prepare keys in different databases for independent scaling and management
2. **Enable Snapshots**: For aggregates with many events, enabling snapshots can significantly improve loading performance
3. **Use Replica Sets**: Use replica sets in production for high availability
4. **Index Optimization**: Create appropriate compound indexes based on query patterns
5. **Sharding for Scale**: Use sharding for horizontal scaling when data volume is large

## Related Topics

| Topic | Description |
|---|---|
| [MongoDB Configuration Reference](../../reference/config/mongo.md) | Configuration reference for `wow.mongo.*` properties |
| [Event Sourcing Configuration](../../reference/config/eventsourcing.md) | Storage backend selection (`wow.eventsourcing.store.storage`) |
| [Snapshot Configuration](../../reference/config/snapshot.md) | Snapshot strategies and storage backend selection |
| [Redis Extension](redis.md) | Alternative event store and snapshot backend |
| [Spring Boot Starter](spring-boot-starter.md) | Auto-configuration and feature variants |
