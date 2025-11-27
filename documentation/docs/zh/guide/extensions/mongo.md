# Mongo

_Mongo_ 扩展提供了对 MongoDB 的支持，是生产环境推荐的事件存储和快照存储实现。它实现了以下接口：

- `EventStore` - 事件存储
- `EventStreamQueryService` - 事件流查询服务
- `SnapshotRepository` - 快照仓储
- `SnapshotQueryService` - 快照查询服务
- `PrepareKey` - 预分配 Key

## 架构概述

```mermaid
flowchart TB
    subgraph Application["应用层"]
        AR[聚合根]
        QS[查询服务]
    end
    
    subgraph Mongo["MongoDB"]
        subgraph EventDB["事件数据库"]
            ES["event_stream 集合"]
        end
        subgraph SnapshotDB["快照数据库"]
            SS["snapshot 集合"]
        end
        subgraph PrepareDB["预分配数据库"]
            PK["prepare_key 集合"]
        end
    end
    
    AR -->|追加事件| ES
    AR -->|保存快照| SS
    AR -->|预分配 Key| PK
    QS -->|查询快照| SS
    QS -->|查询事件| ES
```

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

## 配置

- 配置类：[MongoProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/mongo/MongoProperties.kt)
- 前缀：`wow.mongo.`

| 名称                      | 数据类型      | 说明                 | 默认值                          |
|-------------------------|-----------|--------------------|------------------------------|
| `enabled`               | `Boolean` | 是否启用               | `true`                       |
| `auto-init-schema`      | `Boolean` | 是否自动生成 *Schema*    | `true`                       |
| `event-stream-database` | `String`  | 事件流数据库名称           | Spring Boot Mongo 模块配置的数据库名称 |
| `snapshot-database`     | `String`  | 快照数据库名称            | Spring Boot Mongo 模块配置的数据库名称 |
| `prepare-database`      | `String`  | `PrepareKey` 数据库名称 | Spring Boot Mongo 模块配置的数据库名称 |

**YAML 配置样例**

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

## 集合命名规则

MongoDB 扩展使用以下规则生成集合名称：

| 数据类型 | 集合命名格式 | 示例 |
|---------|------------|------|
| 事件流 | `{aggregateName}_event_stream` | `order_event_stream` |
| 快照 | `{aggregateName}_snapshot` | `order_snapshot` |
| 预分配 Key | `{keyName}` | `username_idx` |

## 事件存储 (EventStore)

### 事件流文档结构

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

### 自动创建的索引

| 索引名称 | 字段 | 类型 | 说明 |
|---------|------|------|------|
| `u_idx_aggregate_id_version` | `aggregateId`, `version` | 唯一索引 | 防止版本冲突 |
| `u_idx_request_id` | `requestId` | 唯一索引 | 请求幂等性 |
| `idx_aggregate_id` | `aggregateId` | 普通索引 | 加速聚合查询 |

## 快照存储 (SnapshotRepository)

### 快照文档结构

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

### 快照查询

快照存储可直接用作读模型，支持丰富的查询条件：

```kotlin
// 分页查询快照
val condition = Condition.all()
    .eq("state.status", "PAID")
    .gt("state.totalAmount", 50.00)
    .limit(10)
    .sort("snapshotTime".desc())

snapshotQueryService.dynamicQuery(condition)
```

## 索引优化建议

### 事件流索引

```javascript
// 推荐的额外索引
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

| 参数 | 说明 | 推荐值 |
|------|------|--------|
| `minPoolSize` | 最小连接数 | 10 |
| `maxPoolSize` | 最大连接数 | 100 |
| `maxIdleTimeMS` | 连接最大空闲时间 | 60000 |

### 写关注配置

```yaml
spring:
  data:
    mongodb:
      uri: mongodb://localhost:27017/wow_db?w=majority&wtimeoutMS=5000
```

### 读偏好配置

```yaml
spring:
  data:
    mongodb:
      uri: mongodb://localhost:27017/wow_db?readPreference=secondaryPreferred
```

## 分片策略

对于大规模数据，建议使用 MongoDB 分片：

```javascript
// 事件流集合分片
sh.shardCollection("wow_event_db.order_event_stream", { "aggregateId": "hashed" })

// 快照集合分片
sh.shardCollection("wow_snapshot_db.order_snapshot", { "_id": "hashed" })
```

## 故障排查

### 常见问题

#### 1. 版本冲突异常

```
me.ahoo.wow.eventsourcing.EventVersionConflictException
```

**原因**：并发写入同一聚合根

**解决方案**：
- 这是正常的乐观锁行为，框架会自动重试
- 如果频繁发生，考虑优化业务流程减少冲突

#### 2. 重复请求异常

```
me.ahoo.wow.eventsourcing.DuplicateRequestIdException
```

**原因**：相同的 `requestId` 被重复处理

**解决方案**：
- 这是幂等性保护，表明请求已成功处理
- 检查客户端是否存在重复提交

#### 3. 连接超时

```
com.mongodb.MongoTimeoutException
```

**解决方案**：
- 检查 MongoDB 服务是否正常
- 增加连接池大小
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

1. **数据库分离**：将事件流、快照和预分配 Key 存储在不同的数据库中，便于独立扩展和管理
2. **启用快照**：对于事件数量较多的聚合，启用快照可以显著提升加载性能
3. **使用副本集**：生产环境使用副本集保证高可用
4. **索引优化**：根据查询模式创建适当的复合索引
5. **分片扩展**：数据量较大时使用分片进行水平扩展