# R2DBC

_R2DBC_ 扩展提供了使用响应式编程的方式对关系型数据库的支持，实现了 `EventStore` 和 `SnapshotRepository` 。
使开发者能够直接利用关系型数据库进行事件存储和快照存储。
同时提供了简单模式，跟分片模式的支持。

## 安装

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

## 配置

- 配置类：[R2dbcProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/r2dbc/R2dbcProperties.kt)
- 前缀：`wow.r2dbc.`

| 名称                      | 数据类型      | 说明                 | 默认值                          |
|-------------------------|-----------|--------------------|------------------------------|
| `enabled`               | `Boolean` | 是否启用               | `true`                       |

**YAML 配置样例**

```yaml
wow:
  r2dbc:
    enabled: true
```

## DataSourceProperties

- 配置类：[DataSourceProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/r2dbc/DataSourceProperties.kt)
- 前缀：`wow.r2dbc.datasource.`

| 名称     | 数据类型   | 说明         | 默认值      |
|--------|--------|------------|----------|
| `type` | `Type` | 模式：简单/分片模式 | `simple` |

### Type

```kotlin
enum class Type {
    SIMPLE,
    SHARDING
    ;
}
```

## ShardingProperties

- 配置类：[ShardingProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/r2dbc/ShardingProperties.kt)
- 前缀：`wow.r2dbc.datasource.sharding`

| 名称             | 数据类型                             | 说明      | 默认值 |
|----------------|----------------------------------|---------|-----|
| `databases`    | `Map<String, Database>`          | 分片数据库   |     |
| `event-stream` | `Map<String, ShardingRule>`      | 事件流分片规则 |     |
| `snapshot`     | `Map<String, ShardingRule>`      | 快照分片规则  |     |
| `algorithms`   | `Map<String, ShardingAlgorithm>` | 分片算法    |     |


### Database

| 名称    | 数据类型     | 说明      | 默认值 |
|-------|----------|---------|-----|
| `url` | `String` | 数据库连接地址 |     |


### ShardingRule

| 名称                   | 数据类型     | 说明      | 默认值 |
|----------------------|----------|---------|-----|
| `database-algorithm` | `String` | 数据库分片算法 |     |
| `table-algorithm`    | `String` | 表分片算法   |     |

### ShardingAlgorithm

| 名称     | 数据类型           | 说明       | 默认值   |
|--------|----------------|----------|-------|
| `type` | `String`       | 分片算法类型   | `mod` |
| `mod`  | `ModAlgorithm` | 取模分片算法配置 |       |

#### ModAlgorithm

| 名称                  | 数据类型     | 说明    | 默认值 |
|---------------------|----------|-------|-----|
| `logic-name-prefix` | `String` | 逻辑名前缀 |     |
| `divisor`           | `Int`    | 除数    |     |

## 初始化SQL脚本

### 命名约定

- 聚合事件流表: [聚合名称]_event_stream
- 聚合快照表: [聚合名称]_snapshot

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

## 简单模式配置

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

## 分片模式配置

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
