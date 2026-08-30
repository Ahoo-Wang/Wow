---
title: Mongo
description: Use MongoDB for event streams, snapshots, queries, and PrepareKey.
---

# Mongo

`wow-mongo` provides MongoDB implementations of `EventStore`, `SnapshotStore`, their query Backends, and `PrepareKeyFactory`. Use it when a service already operates MongoDB and needs durable event history plus snapshot queries. Use `in_memory` for process-local tests.

The module does not take over storage merely by being on the classpath. Starter also needs the Mongo capability, a reactive `MongoClient`, `wow.mongo.enabled=true`, and Mongo selected independently for event, snapshot, or prepare storage.

## Architecture Overview

Wow owns document shape, collection names, index initialization, error mapping, and storage bindings. MongoDB owns write atomicity, unique indexes, read/write concern, replication, sharding, and recovery. One physical database belongs to one bounded context by default, recorded durably in `wow_database_metadata` during startup.

`MongoEventStore` writes `{aggregateName}_event_stream`, `MongoSnapshotStore` writes `{aggregateName}_snapshot`, and `MongoPrepareKey` uses `prepare_{keyName}`. Collection names omit the context, so different contexts must not share a database.

## Installation

For direct Spring wiring, add the module and reactive Mongo starter:

```kotlin
implementation("me.ahoo.wow:wow-mongo")
implementation("org.springframework.boot:spring-boot-starter-data-mongodb-reactive")
```

With Wow Starter, request the capability; it carries both dependencies:

```kotlin
implementation("me.ahoo.wow:wow-spring-boot-starter") {
    capabilities {
        requireCapability("me.ahoo.wow:mongo-support")
    }
}
```

## Core Components

| Component | Runtime responsibility |
|---|---|
| `MongoEventStore` | Append and load event streams by version/time; scan aggregate IDs |
| `MongoSnapshotStore` | Load and atomically save versioned snapshots |
| Query Backend factories | Create Mongo event-stream and snapshot query implementations |
| `MongoPrepareKeyFactory` | Create distributed reservation keys with TTL semantics |
| Schema initializers | Create collections and reconcile indexes from loaded aggregate metadata |
| `MongoDatabaseContextGuard` | Prevent contexts from sharing a database whose collections omit context names |

## Event Append Sequence

`append` serializes one `DomainEventStream` to one document and executes `insertOne`. When batching is explicitly enabled, independent appends are grouped into unordered `insertMany` operations. Wow maps duplicate-key failures only when they reference known index names.

A successful append means MongoDB acknowledged the write under the configured write concern. It does not mean event consumers or snapshots have completed.

## Configuration

Minimum configuration for Mongo EventStore and SnapshotStore:

```yaml
spring:
  application:
    name: order-service
  mongodb:
    uri: mongodb://localhost:27017/order_service

wow:
  eventsourcing:
    store:
      storage: mongo
    snapshot:
      storage: mongo
```

Unset `wow.mongo.event-stream-database`, `snapshot-database`, and `prepare-database` fall back to Spring's Mongo database. If neither the URI nor Spring properties provide a database, auto-configuration fails with the corresponding `must not be null` message. Defaults are `wow.mongo.enabled=true` and `auto-init-schema=true`. Event/snapshot batching is disabled; when enabled, defaults are `max-size=128`, `max-delay=1ms`, `max-pending-*=4096`, and `lane-count=1`.

## Collection Schema

The module generates the physical layout. Applications should not create a parallel collection model or duplicate MongoDB's schema and uniqueness checks before every write.

### Collection Naming Rules

Event streams use `{aggregateName}_event_stream`, snapshots use `{aggregateName}_snapshot`, and reservations use `prepare_{keyName}`. Only the database provides bounded-context isolation.

### Event Stream Collection (`{aggregateName}_event_stream`)

Each document represents one event-stream batch. Default indexes include a hashed aggregate ID, unique `{aggregateId, version}`, unique `{aggregateId, requestId}`, and query indexes. MongoDB atomically enforces concurrency and request idempotency through those unique indexes.

### Snapshot Collection (`{aggregateName}_snapshot`)

The document key is the aggregate ID. Save uses an aggregation update pipeline with a version guard: replace when the candidate is not older, and keep the stored document for an older candidate. A missing or non-integer stored version is repaired by the candidate. The pipeline calls driver MQL `MqlValue.isIntegerOr`, whose `@mongodb.server.release` declares MongoDB 5.2 as the runtime floor. Deploy on 5.2+ and retain real-backend integration tests against the target server version.

### PrepareKey Collection (`prepare_{keyName}`)

`MongoPrepareKey` uses MongoDB updates and a TTL index for prepare, reprepare, and rollback. It is a coordination primitive, not a general distributed transaction or lock service.

## Schema Initialization and Indexes

With `wow.mongo.auto-init-schema=true`, Starter scans loaded Wow aggregate metadata before constructing stores, creates collections, reconciles expected indexes, and removes layouts the module explicitly identifies as conflicting. When disabled, database operations must establish the same contract before traffic starts.

### EventStreamSchemaInitializer

Event index names participate in error mapping. Renaming them manually can stop duplicate-key failures from mapping to `EventVersionConflictException` or `DuplicateRequestIdException`.

### SnapshotSchemaInitializer

The initializer creates only module-declared snapshot collections and indexes. It does not configure replica sets, sharding, read/write concern, backup, or retention.

## Query Backends

Mongo query Backends compile Wow filters, projections, sorting, paging, and aggregations into MongoDB operations. Runtime `QuerySchema` defines supported public capabilities; arbitrary MQL support is not automatically a Wow API contract.

### Filter Compilation Pipeline

Public fields pass through logical schema validation and field conversion before a Mongo filter is emitted. Unsupported or conflicting backend mappings follow `wow.query.schema.validation-mode`. Applications do not need a second validator that guesses Mongo field types.

### Snapshot Queries

`MongoSnapshotQueryBackendFactory` creates a Backend for each aggregate and binds its snapshot collection. Shared query rewriting adds tenant, owner, and space scope; the Mongo adapter executes the resulting filter.

### Snapshot Aggregation

The aggregation compiler translates the Wow aggregation AST into a Mongo pipeline. Public query semantics and Mongo expressions jointly determine results. Use a real backend integration test; string snapshots do not prove pipeline execution.

## PrepareKey: Distributed Coordination

Prepare storage defaults to Mongo. If it selects Redis, the Mongo capability does not create `MongoPrepareKeyFactory`. MongoDB atomic updates determine expiration, contention, and rollback results; callers must handle a reservation-not-acquired result as a normal branch.

## Error Mapping

The current implementation verifies these mappings:

- unique `{aggregateId, version}` conflict → `EventVersionConflictException`;
- request-ID unique-index conflict → `DuplicateRequestIdException`;
- known network, primary-transition, and timeout write errors → recoverable Mongo exceptions;
- unacknowledged event/snapshot writes → `IllegalStateException`;
- a different context connecting to an owned database → startup failure.

Other MongoDB failures remain backend exceptions and are not promised as unified Wow errors.

## Class Hierarchy

Application code depends on `EventStore`, `SnapshotStore`, and query/prepare contracts. Mongo classes are adapters. With Starter, let storage bindings and routing select the adapter instead of using internal saver/appender classes as application APIs.

## Index Optimization Recommendations

Keep required unique indexes, then add indexes from real `explain` and slow-query evidence. Verify that initializer reconciliation does not identify a custom index as a conflicting legacy layout.

### Event Stream Indexes

Version and request-ID indexes are correctness constraints and must not be removed for write throughput. Add query indexes only for observed event-query paths.

### Snapshot Indexes

Index state fields used by filters, sorting, or aggregation. Do not index fields for Wow query capabilities that are not exposed. Arrays, nested fields, and collation follow native MongoDB rules.

## Performance Optimization

Measure direct append/save first. Enable event or snapshot batching only when evidence shows per-request write overhead is the bottleneck. Batching adds queueing, shutdown draining, and partial-bulk-error boundaries.

### Connection Pool Configuration

Spring Boot Mongo/driver configuration owns the pool; `wow.mongo.*` does not duplicate it. Tune from target concurrency, wait queues, and server connection limits.

### Write Concern Configuration

Mongo client/URI configuration owns write concern. Wow checks `wasAcknowledged()` but does not choose a durability level for the application.

### Read Preference Safety

Event replay and read-after-write on secondaries can observe replication lag. The application chooses read preference and verifies its consistency requirement; the adapter does not silently strengthen or weaken it.

### Database Separation

Event, snapshot, and prepare stores may use separate databases; each database runs the context ownership guard independently. Separation can help capacity and permission governance, but it is not a mandatory abstraction.

## Sharding Strategy

Shard keys, zones, and balancing belong to MongoDB operations. The module emits fixed unique-index combinations. Before selecting a shard key, verify MongoDB's native shard-key/unique-index constraints on the actual cluster.

## Troubleshooting

Read the startup exception, Mongo server error code, and index name before deciding whether the problem is configuration, ownership, concurrency, or connectivity.

### Common Issues

These are reproducible boundaries covered by current source and tests.

#### 1. Version Conflict Exception

The database already has the same aggregate version. Do not catch and overwrite blindly; trace the command race, aggregate version, and retry policy.

#### 2. Duplicate Request Exception

The aggregate already records the same request ID. Before treating it as an idempotent success, reconcile the original request result with the API response contract.

#### 3. Connection Timeout

Check URI, DNS, authentication, TLS, replica-set discovery, and network policy. Wow does not add a duplicate connectivity validator ahead of the driver; the driver exception is the diagnostic evidence.

## Complete Configuration Example

```yaml
spring:
  mongodb:
    uri: mongodb://mongo-0:27017/order_service?replicaSet=rs0

wow:
  mongo:
    auto-init-schema: true
    event-stream-database: order_event
    snapshot-database: order_snapshot
    prepare-database: order_prepare
    event-store-batch:
      enabled: false
    snapshot-store-batch:
      enabled: false
  eventsourcing:
    store:
      storage: mongo
    snapshot:
      storage: mongo
```

All example databases must serve only the same bounded context.

## Best Practices

- Use a database per bounded context and audit ownership before upgrades.
- Preserve the required unique indexes and snapshot-version semantics.
- Put schema change, backup, recovery, sharding, and read/write concern in MongoDB operations.
- Verify concurrency, idempotency, query pipelines, and upgrades against real MongoDB.

Focused check:

```bash
./gradlew :wow-mongo:check
```

The repository TCK currently uses `mongo:6.0.6`. A module check does not prove your server version, topology, data volume, or index migration.

## Related Topics

Next, read [Event Sourcing](../domain/event-sourcing.md) for the authoritative-history boundary, then [Infrastructure configuration](../../reference/config/infrastructure.md) for connection, index, and recovery gates.
