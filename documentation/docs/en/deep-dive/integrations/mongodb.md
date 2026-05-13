---
title: MongoDB Integration
description: Deep-dive into the Wow MongoDB integration covering event store, snapshot repository, schema initialization, query services, and PrepareKey support.
outline: deep
---

# MongoDB Integration

The **Wow MongoDB integration** (`wow-mongo`) provides a production-grade reactive persistence layer for the Wow CQRS + Event Sourcing framework. It implements every critical storage contract: event streams, snapshots, query services, and distributed PrepareKey coordination -- all backed by MongoDB's reactive driver.

The module is designed as a drop-in backend. When `wow.eventsourcing.store.storage` is set to `mongo`, the framework replaces its default in-memory stores with MongoDB-backed implementations that handle concurrency, idempotency, and schema lifecycle automatically.

## Architecture Overview

The integration spans three MongoDB databases (configurable independently) and maps directly to the Wow core abstractions:

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
        MSR["MongoSnapshotRepository"]
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

<!-- Sources:
- wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/MongoEventStore.kt:32-105
- wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/MongoSnapshotRepository.kt:34-111
- wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/prepare/MongoPrepareKey.kt:46-163
- wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/event/MongoEventStreamQueryService.kt:31-46
- wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryService.kt:35-57
-->

Each aggregate type gets its own collection, partitioned by aggregate name. This design isolates hot aggregates from each other and enables per-aggregate sharding and index tuning.

## Core Components at a Glance

| Component | Contract Implemented | Key File | Responsibility |
|---|---|---|---|
| `MongoEventStore` | `AbstractEventStore` | [MongoEventStore.kt:32](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/MongoEventStore.kt#L32) | Append, load, and query domain event streams |
| `MongoSnapshotRepository` | `SnapshotRepository` | [MongoSnapshotRepository.kt:34](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/MongoSnapshotRepository.kt#L34) | Save, load, and version-check aggregate snapshots |
| `MongoPrepareKey` | `PrepareKey<V>` | [MongoPrepareKey.kt:46](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/prepare/MongoPrepareKey.kt#L46) | Distributed key reservation with TTL-based expiration |
| `MongoEventStreamQueryService` | `EventStreamQueryService` | [MongoEventStreamQueryService.kt:31](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/event/MongoEventStreamQueryService.kt#L31) | Dynamic querying of raw event streams |
| `MongoSnapshotQueryService` | `SnapshotQueryService<S>` | [MongoSnapshotQueryService.kt:35](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryService.kt#L35) | Dynamic querying of snapshots as materialized read models |
| `EventStreamSchemaInitializer` | (standalone) | [EventStreamSchemaInitializer.kt:29](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/EventStreamSchemaInitializer.kt#L29) | Creates collections + indexes for event streams |
| `SnapshotSchemaInitializer` | (standalone) | [SnapshotSchemaInitializer.kt:29](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/SnapshotSchemaInitializer.kt#L29) | Creates collections + indexes for snapshots |

## Event Append Sequence

The following sequence shows the full path from an aggregate root producing events to the MongoDB document being persisted, including the optimistic concurrency and idempotency guards.

```mermaid
sequenceDiagram
    autonumber
    participant AR as AggregateRoot
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
    Coll->>DB: insert document with _id = eventStreamId
    DB-->>Coll: InsertOneResult

    alt Write acknowledged
        Coll-->>ES: onNext(result)
        ES->>ES: check(wasAcknowledged())
        ES-->>AR: Mono.empty() (success)
    else Duplicate version (aggregateId + version)
        DB-->>Coll: MongoWriteException (DUPLICATE_KEY, u_idx_aggregate_id_version)
        Coll->>Err: onErrorMap(MongoWriteException)
        Err->>Err: toWowError() -> matches "aggregateId_1_version_1"
        Err-->>ES: EventVersionConflictException
        ES-->>AR: EventVersionConflictException
    else Duplicate requestId
        DB-->>Coll: MongoWriteException (DUPLICATE_KEY, u_idx_request_id)
        Coll->>Err: onErrorMap(MongoWriteException)
        Err->>Err: toWowError() -> matches "requestId_1"
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

The key design insight is that **MongoDB unique indexes serve dual roles**: the `{aggregateId, version}` compound unique index enforces optimistic concurrency (no two writes at the same version), while the `{requestId}` unique index provides command idempotency (no duplicate processing). On violation, `ErrorMapping.toWowError()` translates the raw `MongoWriteException` into the Wow framework's typed exceptions (`EventVersionConflictException` or `DuplicateRequestIdException`) so the framework can handle them uniformly regardless of storage backend.

## Class Hierarchy

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

The class hierarchy reveals two layers of abstraction: the **Wow core interfaces** (`AbstractEventStore`, `SnapshotRepository`, `PrepareKey`, `QueryService`) define the framework contract in a storage-agnostic way, while the **Mongo-specific implementations** map those contracts onto MongoDB's reactive driver primitives (`insertOne`, `replaceOne`, `find`, `countDocuments`).

## Collection Schema

### Event Stream Collection (`{aggregateName}_event_stream`)

Each aggregate is defined per-aggregate-type and uses the event stream ID as the primary key (`_id`). The `body` field stores an array of serialized domain events.

| Field | Type | Indexed | Description |
|---|---|---|---|
| `_id` | String | Primary | Event stream identifier (e.g., `"event-stream-id"`) |
| `aggregateId` | String | Hashed + Unique (with version) | Aggregate root identifier |
| `tenantId` | String | Hashed | Multi-tenancy partition key |
| `requestId` | String | Unique (composite) | Command request idempotency key |
| `commandId` | String | -- | Originating command identifier |
| `version` | Integer | Unique (with aggregateId) | Aggregate version at time of event |
| `header` | Object | -- | Metadata (e.g., `upstream_id` for saga tracking) |
| `body` | Array | -- | Ordered list of domain event payloads |
| `size` | Integer | -- | Number of events in this stream |
| `createTime` | Long | -- | Epoch milliseconds timestamp |

<!-- Source: wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/Documents.kt:58-63 (toDocument contains all fields from DomainEventStream including aggregateId, version, header, body, size, createTime) -->

### Snapshot Collection (`{aggregateName}_snapshot`)

Snapshots use the aggregate ID as the primary key (`_id`), making it a natural lookup for the latest state. The `state` field contains the serialized aggregate state object.

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

<!-- Source: wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/Documents.kt:73-77 (Snapshot toDocument uses toLinkedHashMap which includes all snapshot fields) -->

The key document-level transformation is the **primary key mapping**: event streams store their ID internally as `_id` but the `DomainEventStream` model uses `id` -- `Documents.replaceIdToPrimaryKey()` and `replacePrimaryKeyToId()` handle the bidirectional mapping transparently. Similarly, snapshots map between `_id` and `aggregateId` via `replaceAggregateIdToPrimaryKey()` and `replacePrimaryKeyToAggregateId()`.

### PrepareKey Collection (`prepare_{keyName}`)

| Field | Type | Indexed | Description |
|---|---|---|---|
| `_id` | String | Hashed | Key value (unique) |
| `value` | Object | -- | Prepared value payload |
| `ttlAt` | Date | Ascending (TTL) | Time-to-live expiration timestamp |

<!-- Source: wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/prepare/MongoPrepareKey.kt:55-66 (prepareCollection init block, TTL index) -->

### Collection Naming Rules

Collection names are derived from aggregate metadata using deterministic suffixes. This convention is defined in [AggregateSchemaInitializer.kt:33-37](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/AggregateSchemaInitializer.kt#L33-L37):

| Data Type | Naming Pattern | Example |
|---|---|---|
| Event Stream | `{aggregateName}_event_stream` | `order_event_stream` |
| Snapshot | `{aggregateName}_snapshot` | `order_snapshot` |
| Prepare Key | `prepare_{name}` | `prepare_username_idx` |

## Schema Initialization & Indexes

The `wow.mongo.auto-init-schema` flag (default `true`) controls whether collections and indexes are created automatically on startup. Two initializers handle this:

### EventStreamSchemaInitializer

On initialization, the [EventStreamSchemaInitializer.initSchema()](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/EventStreamSchemaInitializer.kt#L51-L69) method:

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

<!-- Source: EventStreamSchemaInitializer.kt:51-69 (initSchema method creating all indexes) -->

The `enableRequestIdUniqueIndex` toggle exists because [MongoDB sharded clusters cannot enforce unique indexes across shards unless the shard key is part of the unique index](https://www.mongodb.com/docs/manual/core/sharding-shard-key/#unique-indexes). When `false` (the default), the compound `{aggregateId, requestId}` index is used instead, which is compatible with hashed sharding on `aggregateId`.

### SnapshotSchemaInitializer

The [SnapshotSchemaInitializer.initSchema()](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/SnapshotSchemaInitializer.kt#L40-L55) creates:

| Index | Fields | Type | Purpose |
|---|---|---|---|
| `tenantId_hashed` | `tenantId` | Hashed | Multi-tenancy filtering |
| `ownerId_hashed` | `ownerId` | Hashed | Owner-based filtering |
| `_id_hashed` | `_id` | Hashed | Fast aggregate lookup by ID |
| `deleted_hashed` | `deleted` | Hashed | Soft-delete filtering |

<!-- Source: SnapshotSchemaInitializer.kt:40-55 (initSchema method creating all snapshot indexes) -->

## Query Services

The `wow-mongo` module provides two query service implementations that translate Wow's abstract `Condition` objects into MongoDB filter documents (`Bson`):

### Condition Converter Pipeline

The conversion pipeline is: `Condition` -> `AbstractMongoConditionConverter` -> `Bson` (MongoDB filter).

[AbstractMongoConditionConverter](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoConditionConverter.kt#L28) implements the full condition grammar defined by Wow's [Condition API](../reference/cqrs.md):

| Wow Operator | MongoDB Equivalent | Source |
|---|---|---|
| `eq` | `Filters.eq()` | [L85](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoConditionConverter.kt#L85) |
| `gt` / `gte` / `lt` / `lte` | `Filters.gt()` / `gte()` / `lt()` / `lte()` | [L89-L95](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoConditionConverter.kt#L89-L95) |
| `contains` | `Filters.regex()` (escaped) | [L119-L120](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoConditionConverter.kt#L119-L120) |
| `match` | `Filters.text()` | [L122](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoConditionConverter.kt#L122) |
| `between` | `Filters.and(Filters.gte(), Filters.lte())` | [L134-L145](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoConditionConverter.kt#L134-L145) |
| `isIn` / `notIn` | `Filters.in()` / `nin()` | [L130-L132](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoConditionConverter.kt#L130-L132) |
| `deleted` (soft-delete) | `Filters.eq("deleted", true/false)` or `Filters.empty()` | [L166-L179](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoConditionConverter.kt#L166-L179) |
| `raw` | `Document.parse()` or direct `Bson` | [L181-L199](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoConditionConverter.kt#L181-L199) |

The converter also applies **field name translation** via `FieldConverter`. For event streams, the `MessageRecords.ID` field is mapped to `_id` ([EventStreamFieldConverter.kt:20-27](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/event/EventStreamFieldConverter.kt#L20-L27)). For snapshots, `MessageRecords.AGGREGATE_ID` is mapped to `_id` ([SnapshotFieldConverter.kt:7-15](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/SnapshotFieldConverter.kt#L7-L15)). This keeps the application-layer query model consistent regardless of the underlying primary key strategy.

### Pagination Support

[AbstractMongoQueryService.paged()](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoQueryService.kt#L83-L105) implements efficient pagination by executing two queries in parallel via `Mono.zip()`:

1. `collection.countDocuments(filter)` -- total count
2. `collection.find(filter).projection().sort().skip().limit()` -- data page

Both are non-blocking reactive operations that leverage MongoDB's cursor-based pagination natively.

### Snapshot as Read Model

Snapshots double as read models -- the `state` field can be queried directly via `MongoSnapshotQueryService`. The service uses `MaterializedSnapshot<S>` as its typed result wrapper, where `S` is the aggregate's state type resolved from the aggregate metadata at construction time:

```kotlin
val snapshotType = JsonSerializer.typeFactory
    .constructParametricType(
        MaterializedSnapshot::class.java,
        namedAggregate.requiredAggregateType<Any>()
            .aggregateMetadata<Any, S>().state.aggregateType
    )
```

<!-- Source: wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryService.kt:44-48 -->

This enables type-safe dynamic queries directly against aggregate state fields -- for example, querying `state.status` or `state.totalAmount` without a separate projection processor.

## PrepareKey: Distributed Coordination

[MongoPrepareKey](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/prepare/MongoPrepareKey.kt#L46-L163) implements Wow's `PrepareKey<V>` interface for distributed key reservation with MongoDB as the coordination backend. Each logical key becomes a `prepare_{name}` collection.

The implementation uses three MongoDB primitives to achieve coordination:

| Operation | MongoDB Method | Behavior |
|---|---|---|
| `prepare()` | `replaceOne` with filter `{_id: key, ttlAt: {$lt: now}}` | CAS-style upsert -- only succeeds if no unexpired entry exists |
| `rollback()` | `deleteOne` with filter `{_id: key, ttlAt: {$gt: now}}` | Removes active reservation (only if not expired) |
| `reprepare()` | `updateOne` with `$set` on value + `ttlAt` | Extends or replaces a reservation atomically |

<!-- Source: wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/prepare/MongoPrepareKey.kt:69-163 -->

The TTL index (`{ttlAt: 1}` with `expireAfter: 0 seconds`) ensures MongoDB automatically removes expired entries, providing a cleanup mechanism that does not require application-level intervention.

## Error Mapping

MongoDB duplicate key errors are translated into Wow framework exceptions via [ErrorMapping.toWowError()](https://github.com/Ahoo-Wang/Wow/blob/main/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/ErrorMapping.kt#L23-L41):

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

The mapping relies on the index name embedded in the MongoDB error message. This is a pragmatic approach that avoids a separate metadata lookup: the index name (`aggregateId_1_version_1` or `requestId_1`) uniquely identifies which constraint was violated.

- `EventVersionConflictException` -- signals an optimistic concurrency conflict. The framework retries the command automatically.
- `DuplicateRequestIdException` -- signals that the command was already processed. The framework treats this as idempotent success.

For `MongoPrepareKey`, duplicate key errors during `prepare()` are caught separately and mapped to `Mono.just(false)`, indicating that the key could not be acquired (another process holds it).

## Configuration

### Configuration Properties

The `MongoProperties` class binds to the `wow.mongo` prefix and is defined in [MongoProperties.kt:21-32](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/mongo/MongoProperties.kt#L21-L32).

| Property | Type | Default | Description | Source |
|---|---|---|---|---|
| `wow.mongo.enabled` | `Boolean` | `true` | Master switch for MongoDB integration | [MongoProperties.kt:23](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/mongo/MongoProperties.kt#L23) |
| `wow.mongo.auto-init-schema` | `Boolean` | `true` | Auto-create collections and indexes on startup | [MongoProperties.kt:24](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/mongo/MongoProperties.kt#L24) |
| `wow.mongo.event-stream-database` | `String?` | `null` | Database for event stream collections (defaults to Spring Mongo database) | [MongoProperties.kt:25](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/mongo/MongoProperties.kt#L25) |
| `wow.mongo.snapshot-database` | `String?` | `null` | Database for snapshot collections (defaults to Spring Mongo database) | [MongoProperties.kt:26](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/mongo/MongoProperties.kt#L26) |
| `wow.mongo.prepare-database` | `String?` | `null` | Database for PrepareKey collections (defaults to Spring Mongo database) | [MongoProperties.kt:27](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/mongo/MongoProperties.kt#L27) |

### Core Event Sourcing Switches

In addition to the MongoDB-specific properties, two properties in `wow.eventsourcing` control which storage backend is used:

```yaml
wow:
  eventsourcing:
    store:
      storage: mongo        # Selects MongoEventStore
    snapshot:
      storage: mongo        # Selects MongoSnapshotRepository
```

These switches cause the framework to instantiate `MongoEventStore` instead of the default in-memory store, and `MongoSnapshotRepository` for snapshot persistence.

### Complete Configuration Example

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

## Performance Considerations

### Database Separation

The three configurable databases (`event-stream-database`, `snapshot-database`, `prepare-database`) enable **physical isolation** of workloads:

- **Event streams**: Write-heavy (append-only), benefits from fast storage
- **Snapshots**: Read-heavy (materialized views), benefits from caching and read replicas
- **Prepare keys**: Low volume, short-lived documents, benefits from TTL index cleanup

When all three default to `null`, they share the Spring-configured MongoDB database, which is sufficient for development and moderate loads. For production, separating them allows independent scaling, backup schedules, and read-preference tuning.

### Connection Pool Tuning

MongoDB connection pool sizing is configured through Spring Boot's `spring.data.mongodb.uri` query parameters:

| Parameter | Recommended | Rationale |
|---|---|---|
| `minPoolSize` | `10` | Ensures warm connections during traffic spikes |
| `maxPoolSize` | `100` | Upper bound to prevent connection exhaustion |
| `maxIdleTimeMS` | `60000` | Reclaims idle connections after 60 seconds |

### Write Concern

For production event sourcing, `w=majority` ensures events are acknowledged by a majority of replica set members before the command returns. This prevents data loss during failover at the cost of slightly higher write latency.

### Read Preference

Setting `readPreference=secondaryPreferred` offloads snapshot read queries to secondary nodes, reducing load on the primary. Event stream writes always go to the primary.

### Sharding Strategy

For large-scale deployments, shard event stream and snapshot collections by aggregate ID:

```javascript
// Hashed sharding distributes writes evenly across shards
sh.shardCollection("wow_event_db.order_event_stream", { "aggregateId": "hashed" })
sh.shardCollection("wow_snapshot_db.order_snapshot", { "_id": "hashed" })
```

**Important**: When using sharded collections, keep `EventStreamSchemaInitializer.enableRequestIdUniqueIndex = false` (the default). MongoDB cannot enforce a unique index across shards unless the shard key is part of the index. The compound `{aggregateId, requestId}` index is shard-compatible because `aggregateId` is the shard key.

## Related Pages

| Page | Relationship |
|---|---|
| [Mongo Configuration](../reference/config/mongo.md) | Configuration reference for `wow.mongo.*` properties |
| [Event Sourcing Configuration](../reference/config/eventsourcing.md) | Storage backend selection (`wow.eventsourcing.store.storage`) |
| [Snapshot Configuration](../reference/config/snapshot.md) | Snapshot strategies and storage backend selection |
| [CQRS Reference](../reference/cqrs.md) | Wow's `Condition` API for dynamic queries |
| [Redis Integration](./redis.md) | Alternative event store and snapshot backend |
| [R2DBC Integration](./r2dbc.md) | SQL-based event store alternative |
