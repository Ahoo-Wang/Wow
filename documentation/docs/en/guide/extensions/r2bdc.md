# R2DBC

The _R2DBC_ extension provides support for relational databases using reactive programming, implementing `EventStore` and `SnapshotRepository`.
It enables developers to directly utilize relational databases for event storage and snapshot storage.
It also provides support for both simple mode and sharding mode.

## Architecture Overview

```mermaid
flowchart TB
    subgraph Application["Application Layer"]
        AR[Aggregate Root]
        QS[Query Service]
    end
    
    subgraph R2DBC["R2DBC Layer"]
        CF[ConnectionFactory]
        TX[Transaction Manager]
    end
    
    subgraph Database["Relational Database"]
        subgraph Simple["Simple Mode"]
            ES1[(event_stream)]
            SS1[(snapshot)]
        end
        subgraph Sharding["Sharding Mode"]
            ES2[(event_stream_0)]
            ES3[(event_stream_1)]
            SS2[(snapshot_0)]
            SS3[(snapshot_1)]
        end
    end
    
    AR --> CF
    CF --> TX
    TX --> Simple
    TX --> Sharding
```

## Supported Databases

| Database | Driver | Connection URL Example |
|--------|------|--------------|
| MySQL | r2dbc-mysql | `r2dbc:mysql://localhost:3306/wow_db` |
| MariaDB | r2dbc-mariadb | `r2dbc:mariadb://localhost:3306/wow_db` |
| PostgreSQL | r2dbc-postgresql | `r2dbc:postgresql://localhost:5432/wow_db` |
| H2 | r2dbc-h2 | `r2dbc:h2:mem:///wow_db` |
| SQL Server | r2dbc-mssql | `r2dbc:mssql://localhost:1433/wow_db` |

## Installation

::: code-group
```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-r2dbc")
// Choose the appropriate database driver
implementation("org.mariadb:r2dbc-mariadb")
// or
implementation("io.r2dbc:r2dbc-postgresql")
```
```groovy [Gradle(Groovy)]
implementation 'me.ahoo.wow:wow-r2dbc'
implementation 'org.mariadb:r2dbc-mariadb'
```
```xml [Maven]
<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-r2dbc</artifactId>
    <version>${wow.version}</version>
</dependency>
<dependency>
    <groupId>org.mariadb</groupId>
    <artifactId>r2dbc-mariadb</artifactId>
</dependency>
```
:::

## Configuration

- Configuration class: [R2dbcProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/r2dbc/R2dbcProperties.kt)
- Prefix: `wow.r2dbc.`

| Name                      | Data Type  | Description | Default Value |
|-------------------------|------------|-------------|---------------|
| `enabled`               | `Boolean` | Whether to enable | `true` |

**YAML Configuration Example**

```yaml
wow:
  r2dbc:
    enabled: true
```

## DataSourceProperties

- Configuration class: [DataSourceProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/r2dbc/DataSourceProperties.kt)
- Prefix: `wow.r2dbc.datasource.`

| Name     | Data Type | Description | Default Value |
|--------|-----------|-------------|---------------|
| `type` | `Type`   | Mode: simple/sharding mode | `simple` |

### Type

```kotlin
enum class Type {
    SIMPLE,
    SHARDING
    ;
}
```

## Transaction Management

The R2DBC extension automatically handles transaction boundaries, ensuring atomicity of event appending and snapshot saving:

```mermaid
sequenceDiagram
    participant CP as CommandProcessor
    participant TX as TransactionManager
    participant ES as EventStore
    participant SR as SnapshotRepository
    
    CP->>TX: Begin Transaction
    TX->>ES: Append Events
    ES-->>TX: Confirm
    TX->>SR: Save Snapshot
    SR-->>TX: Confirm
    TX->>TX: Commit Transaction
    TX-->>CP: Complete
```

## Connection Pool Configuration

Use r2dbc-pool for connection pool management:

```yaml
spring:
  r2dbc:
    url: r2dbc:pool:mariadb://localhost:3306/wow_db?initialSize=8&maxSize=32&acquireRetry=3&maxLifeTime=PT30M&maxIdleTime=PT10M
```

| Parameter | Description | Recommended Value |
|------|------|--------|
| `initialSize` | Initial connections | 8 |
| `maxSize` | Maximum connections | 32 |
| `acquireRetry` | Connection acquire retries | 3 |
| `maxLifeTime` | Maximum connection lifetime | PT30M |
| `maxIdleTime` | Maximum idle time | PT10M |

## ShardingProperties

- Configuration class: [ShardingProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/r2dbc/ShardingProperties.kt)
- Prefix: `wow.r2dbc.datasource.sharding`

| Name             | Data Type                           | Description | Default Value |
|----------------|----------------------------------|-------------|---------------|
| `databases`    | `Map<String, Database>`          | Sharding databases |  |
| `event-stream` | `Map<String, ShardingRule>`      | Event stream sharding rules |  |
| `snapshot`     | `Map<String, ShardingRule>`      | Snapshot sharding rules |  |
| `algorithms`   | `Map<String, ShardingAlgorithm>` | Sharding algorithms |  |


### Database

| Name    | Data Type | Description | Default Value |
|-------|-----------|-------------|---------------|
| `url` | `String` | Database connection URL |  |


### ShardingRule

| Name                   | Data Type | Description | Default Value |
|----------------------|-----------|-------------|---------------|
| `database-algorithm` | `String` | Database sharding algorithm |  |
| `table-algorithm`    | `String` | Table sharding algorithm |  |

### ShardingAlgorithm

| Name     | Data Type         | Description | Default Value |
|--------|----------------|-------------|---------------|
| `type` | `String`       | Sharding algorithm type | `mod` |
| `mod`  | `ModAlgorithm` | Modulo sharding algorithm configuration |  |

#### ModAlgorithm

| Name                  | Data Type | Description | Default Value |
|---------------------|-----------|-------------|---------------|
| `logic-name-prefix` | `String` | Logic name prefix |  |
| `divisor`           | `Int`    | Divisor |  |

## Initialization SQL Scripts

### Naming Convention

- Aggregate event stream table: [aggregate_name]_event_stream
- Aggregate snapshot table: [aggregate_name]_snapshot

### MySQL/MariaDB

```sql
create table if not exists aggregate_name_event_stream
(
    id           char(15)        not null comment 'event stream id' primary key,
    aggregate_id char(15)        not null,
    tenant_id    char(15)        not null,
    request_id   char(15)        not null,
    command_id   char(15)        not null,
    version      int unsigned    not null,
    header       mediumtext      not null,
    body         longtext        not null,
    size         int unsigned    not null,
    create_time  bigint unsigned not null,
    constraint u_idx_aggregate_id_version
        unique (aggregate_id, version),
    constraint u_idx_request_id
        unique (request_id),
    key idx_tenant_id (tenant_id)
)
    collate = utf8mb4_bin;

create table if not exists aggregate_name_snapshot
(
    aggregate_id     char(15)        not null primary key,
    tenant_id        char(15)        not null,
    version          int unsigned    not null comment 'aggregate version',
    state_type       varchar(255)    not null comment 'aggregate state type',
    state            longtext        not null comment 'aggregate state',
    event_id         char(15)        not null default '',
    first_operator   char(15)        not null default '',
    operator         char(15)        not null default '',
    first_event_time bigint unsigned not null default 0,
    event_time       bigint unsigned not null default 0,
    snapshot_time    bigint unsigned not null,
    deleted          tinyint(1)      not null default 0,
    key idx_tenant_id (tenant_id)
)
    collate = utf8mb4_bin;
```

### PostgreSQL

```sql
create table if not exists aggregate_name_event_stream
(
    id           char(15)     not null primary key,
    aggregate_id char(15)     not null,
    tenant_id    char(15)     not null,
    request_id   char(15)     not null,
    command_id   char(15)     not null,
    version      integer      not null,
    header       text         not null,
    body         text         not null,
    size         integer      not null,
    create_time  bigint       not null,
    constraint u_idx_aggregate_id_version unique (aggregate_id, version),
    constraint u_idx_request_id unique (request_id)
);

create index idx_tenant_id on aggregate_name_event_stream (tenant_id);

create table if not exists aggregate_name_snapshot
(
    aggregate_id     char(15)     not null primary key,
    tenant_id        char(15)     not null,
    version          integer      not null,
    state_type       varchar(255) not null,
    state            text         not null,
    event_id         char(15)     not null default '',
    first_operator   char(15)     not null default '',
    operator         char(15)     not null default '',
    first_event_time bigint       not null default 0,
    event_time       bigint       not null default 0,
    snapshot_time    bigint       not null,
    deleted          boolean      not null default false
);

create index idx_snapshot_tenant_id on aggregate_name_snapshot (tenant_id);
```

## Simple Mode Configuration

```yaml
spring:
  r2dbc:
    url: r2dbc:pool:mariadb://root:root@localhost:3306/wow_db?initialSize=8&maxSize=8&acquireRetry=3&maxLifeTime=PT30M
wow:
  eventsourcing:
    store:
      storage: r2dbc
    snapshot:
      storage: r2dbc
```

## Sharding Mode Configuration

```yaml
wow:
  r2dbc:
    datasource:
      type: sharding
      sharding:
        databases:
          event_stream_0:
            url: r2dbc:pool:mariadb://root:root@localhost:3306/event_stream_0?initialSize=8&maxSize=8&acquireRetry=3&maxLifeTime=PT30M
          event_stream_1:
            url: r2dbc:pool:mariadb://root:root@localhost:3306/event_stream_1?initialSize=8&maxSize=8&acquireRetry=3&maxLifeTime=PT30M
          snapshot_0:
            url: r2dbc:pool:mariadb://root:root@localhost:3306/snapshot_0?initialSize=8&maxSize=8&acquireRetry=3&maxLifeTime=PT30M
          snapshot_1:
            url: r2dbc:pool:mariadb://root:root@localhost:3306/snapshot_1?initialSize=8&maxSize=8&acquireRetry=3&maxLifeTime=PT30M
        event-stream:
          order:
            database-algorithm: event_stream_db
            table-algorithm: order_event_stream_table
        snapshot:
          order:
            database-algorithm: snapshot_db
            table-algorithm: order_snapshot_table
        algorithms:
          event_stream_db:
            type: mod
            mod:
              logic-name-prefix: event_stream_
              divisor: 2
          snapshot_db:
            type: mod
            mod:
              logic-name-prefix: snapshot_
              divisor: 2
          order_event_stream_table:
            type: mod
            mod:
              logic-name-prefix: order_event_stream_
              divisor: 4
          order_snapshot_table:
            type: mod
            mod:
              logic-name-prefix: order_snapshot_
              divisor: 4
```

## Performance Optimization

### Batch Operations

The R2DBC extension supports batch event appending, reducing database round trips.

### Index Optimization

Ensure the following indexes are created to optimize query performance:

```sql
-- Event stream table indexes
create index idx_create_time on aggregate_name_event_stream (create_time);

-- Snapshot table indexes
create index idx_event_time on aggregate_name_snapshot (event_time);
create index idx_deleted on aggregate_name_snapshot (deleted);
```

## Troubleshooting

### Common Issues

#### 1. Connection Pool Exhaustion

**Solutions**:
- Increase `maxSize` configuration
- Check if connections are properly released
- Reduce transaction holding time

#### 2. Transaction Timeout

**Solutions**:
- Optimize query performance
- Increase transaction timeout
- Split large transactions

## Best Practices

1. **Choose the Right Database**: Choose MySQL, PostgreSQL, etc. based on business requirements
2. **Configure Connection Pool**: Configure appropriate connection pool size for concurrent load
3. **Use Sharding Mode**: Use sharding mode for horizontal scaling with large data volumes
4. **Create Indexes**: Create appropriate indexes based on query patterns
5. **Monitor Connection Pool**: Monitor connection pool usage to avoid resource exhaustion
