# R2DBC

The _R2DBC_ extension provides support for relational databases using reactive programming, implementing `EventStore` and `SnapshotRepository`.
It enables developers to directly utilize relational databases for event storage and snapshot storage.
It also provides support for both simple mode and sharding mode.

## Installation

::: code-group
```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-r2dbc")
```
```groovy [Gradle(Groovy)]
implementation 'me.ahoo.wow:wow-r2dbc'
```
```xml [Maven]
<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-r2dbc</artifactId>
    <version>${wow.version}</version>
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
