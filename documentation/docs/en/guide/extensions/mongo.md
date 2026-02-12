# Mongo

The _Mongo_ extension provides support for MongoDB and is the recommended event store and snapshot storage implementation for production environments. It implements the following interfaces:

- `EventStore` - Event storage
- `EventStreamQueryService` - Event stream query service
- `SnapshotRepository` - Snapshot repository
- `SnapshotQueryService` - Snapshot query service
- `PrepareKey` - Prepare key

## Architecture Overview

```mermaid
flowchart TB
    subgraph Application["Application Layer"]
        AR[Aggregate Root]
        QS[Query Service]
    end
    
    subgraph Mongo["MongoDB"]
        subgraph EventDB["Event Database"]
            ES["event_stream collection"]
        end
        subgraph SnapshotDB["Snapshot Database"]
            SS["snapshot collection"]
        end
        subgraph PrepareDB["Prepare Database"]
            PK["prepare_key collection"]
        end
    end
    
    AR -->|Append Events| ES
    AR -->|Save Snapshot| SS
    AR -->|Prepare Key| PK
    QS -->|Query Snapshot| SS
    QS -->|Query Events| ES
```

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

## Configuration

- Configuration class: [MongoProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/mongo/MongoProperties.kt)
- Prefix: `wow.mongo.`

| Name                      | Data Type  | Description | Default Value |
|-------------------------|------------|-------------|---------------|
| `enabled`               | `Boolean` | Whether to enable | `true` |
| `auto-init-schema`      | `Boolean` | Whether to auto-generate *Schema* | `true` |
| `event-stream-database` | `String`  | Event stream database name | Database name configured by Spring Boot Mongo module |
| `snapshot-database`     | `String`  | Snapshot database name | Database name configured by Spring Boot Mongo module |
| `prepare-database`      | `String`  | `PrepareKey` database name | Database name configured by Spring Boot Mongo module |

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

## Collection Naming Rules

The MongoDB extension uses the following rules to generate collection names:

| Data Type | Collection Naming Format | Example |
|---------|------------|------|
| Event Stream | `{aggregateName}_event_stream` | `order_event_stream` |
| Snapshot | `{aggregateName}_snapshot` | `order_snapshot` |
| Prepare Key | `{keyName}` | `username_idx` |

## Event Store (EventStore)

### Event Stream Document Structure

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

### Auto-Created Indexes

| Index Name | Fields | Type | Description |
|---------|------|------|------|
| `u_idx_aggregate_id_version` | `aggregateId`, `version` | Unique Index | Prevent version conflicts |
| `u_idx_request_id` | `requestId` | Unique Index | Request idempotency |
| `idx_aggregate_id` | `aggregateId` | Normal Index | Accelerate aggregate queries |

## Snapshot Storage (SnapshotRepository)

### Snapshot Document Structure

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
|------|------|--------|
| `minPoolSize` | Minimum connections | 10 |
| `maxPoolSize` | Maximum connections | 100 |
| `maxIdleTimeMS` | Maximum idle time | 60000 |

### Write Concern Configuration

```yaml
spring:
  data:
    mongodb:
      uri: mongodb://localhost:27017/wow_db?w=majority&wtimeoutMS=5000
```

### Read Preference Configuration

```yaml
spring:
  data:
    mongodb:
      uri: mongodb://localhost:27017/wow_db?readPreference=secondaryPreferred
```

## Sharding Strategy

For large-scale data, MongoDB sharding is recommended:

```javascript
// Event stream collection sharding
sh.shardCollection("wow_event_db.order_event_stream", { "aggregateId": "hashed" })

// Snapshot collection sharding
sh.shardCollection("wow_snapshot_db.order_snapshot", { "_id": "hashed" })
```

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