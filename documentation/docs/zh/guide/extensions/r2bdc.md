# R2DBC

_R2DBC_ 扩展提供了使用响应式编程的方式对关系型数据库的支持，实现了 `EventStore` 和 `SnapshotRepository` 。
使开发者能够直接利用关系型数据库进行事件存储和快照存储。
同时提供了简单模式，跟分片模式的支持。

## 架构概述

```mermaid
flowchart TB
    subgraph Application["应用层"]
        AR[聚合根]
        QS[查询服务]
    end
    
    subgraph R2DBC["R2DBC 层"]
        CF[ConnectionFactory]
        TX[事务管理]
    end
    
    subgraph Database["关系数据库"]
        subgraph Simple["简单模式"]
            ES1[(event_stream)]
            SS1[(snapshot)]
        end
        subgraph Sharding["分片模式"]
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

## 支持的数据库

| 数据库 | 驱动 | 连接 URL 示例 |
|--------|------|--------------|
| MySQL | r2dbc-mysql | `r2dbc:mysql://localhost:3306/wow_db` |
| MariaDB | r2dbc-mariadb | `r2dbc:mariadb://localhost:3306/wow_db` |
| PostgreSQL | r2dbc-postgresql | `r2dbc:postgresql://localhost:5432/wow_db` |
| H2 | r2dbc-h2 | `r2dbc:h2:mem:///wow_db` |
| SQL Server | r2dbc-mssql | `r2dbc:mssql://localhost:1433/wow_db` |

## 安装

::: code-group
```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-r2dbc")
// 选择对应的数据库驱动
implementation("org.mariadb:r2dbc-mariadb")
// 或
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

## 事务管理

R2DBC 扩展自动处理事务边界，确保事件追加和快照保存的原子性：

```mermaid
sequenceDiagram
    participant CP as CommandProcessor
    participant TX as TransactionManager
    participant ES as EventStore
    participant SR as SnapshotRepository
    
    CP->>TX: 开始事务
    TX->>ES: 追加事件
    ES-->>TX: 确认
    TX->>SR: 保存快照
    SR-->>TX: 确认
    TX->>TX: 提交事务
    TX-->>CP: 完成
```

## 连接池配置

使用 r2dbc-pool 进行连接池管理：

```yaml
spring:
  r2dbc:
    url: r2dbc:pool:mariadb://localhost:3306/wow_db?initialSize=8&maxSize=32&acquireRetry=3&maxLifeTime=PT30M&maxIdleTime=PT10M
```

| 参数 | 说明 | 推荐值 |
|------|------|--------|
| `initialSize` | 初始连接数 | 8 |
| `maxSize` | 最大连接数 | 32 |
| `acquireRetry` | 获取连接重试次数 | 3 |
| `maxLifeTime` | 连接最大生命周期 | PT30M |
| `maxIdleTime` | 连接最大空闲时间 | PT10M |

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

## 性能优化

### 批量操作

R2DBC 扩展支持批量事件追加，减少数据库往返次数。

### 索引优化

确保创建以下索引以优化查询性能：

```sql
-- 事件流表索引
create index idx_create_time on aggregate_name_event_stream (create_time);

-- 快照表索引
create index idx_event_time on aggregate_name_snapshot (event_time);
create index idx_deleted on aggregate_name_snapshot (deleted);
```

## 故障排查

### 常见问题

#### 1. 连接池耗尽

**解决方案**：
- 增加 `maxSize` 配置
- 检查连接是否正确释放
- 减少事务持有时间

#### 2. 事务超时

**解决方案**：
- 优化查询性能
- 增加事务超时时间
- 拆分大事务

## 最佳实践

1. **选择合适的数据库**：根据业务需求选择 MySQL、PostgreSQL 等
2. **配置连接池**：合理配置连接池大小以应对并发负载
3. **使用分片模式**：数据量较大时使用分片模式进行水平扩展
4. **创建索引**：根据查询模式创建适当的索引
5. **监控连接池**：监控连接池使用情况，避免资源耗尽
