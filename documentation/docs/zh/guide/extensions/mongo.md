---
title: Mongo
description: 使用 MongoDB 承载事件流、快照、查询和 PrepareKey。
---

# Mongo

`wow-mongo` 提供 `EventStore`、`SnapshotStore`、对应查询服务和 `PrepareKeyFactory` 的 MongoDB 实现。适合已经运行 MongoDB、需要持久化事件历史与快照查询的服务；若只需进程内测试，使用 `in_memory` 更简单。

模块不会仅因存在于 classpath 就接管存储。Starter 还要看到 Mongo capability、Reactive `MongoClient`、`wow.mongo.enabled=true`，以及 event/snapshot/prepare 各自选择 Mongo。

## 架构概述

Wow 负责文档形状、集合命名、索引初始化、错误映射和存储 binding；MongoDB 负责写入原子性、唯一索引、读写关注、复制、分片和故障恢复。一个物理 database 默认只归属一个 bounded context，启动时由 `wow_database_metadata` 持久化认领。

`MongoEventStore` 写入 `{aggregateName}_event_stream`，`MongoSnapshotStore` 写入 `{aggregateName}_snapshot`，`MongoPrepareKey` 使用 `prepare_{keyName}`。集合名没有 context 前缀，因此不要让不同 context 共用 database。

## 安装

直接装配模块与 Spring Reactive Mongo：

```kotlin
implementation("me.ahoo.wow:wow-mongo")
implementation("org.springframework.boot:spring-boot-starter-data-mongodb-reactive")
```

使用 Starter 时只请求 capability，它已带入上述依赖：

```kotlin
implementation("me.ahoo.wow:wow-spring-boot-starter") {
    capabilities {
        requireCapability("me.ahoo.wow:mongo-support")
    }
}
```

## 核心组件

| 组件 | 运行时职责 |
|---|---|
| `MongoEventStore` | 追加、按版本/时间加载事件流，扫描聚合 ID |
| `MongoSnapshotStore` | 加载并按版本原子保存快照 |
| 查询 service factory | 为事件流和快照建立 Mongo 查询实现 |
| `MongoPrepareKeyFactory` | 创建 TTL 语义的分布式预占键 |
| schema initializer | 按已加载聚合元数据创建集合并协调索引 |
| `MongoDatabaseContextGuard` | 阻止不同 bounded context 共用无 context 前缀的 database |

## 事件追加时序

`append` 把一个 `DomainEventStream` 序列化为一个文档，再执行 `insertOne`；批处理显式启用时，多个独立 append 聚合为 unordered `insertMany`。MongoDB 返回 duplicate-key 时，Wow 只对已知索引名映射框架异常。

写入成功只表示 MongoDB 已按当前 write concern 确认，不表示事件消费者或快照已经完成。

## 配置

同时使用 Mongo EventStore 与 SnapshotStore 的最小配置：

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

`wow.mongo.event-stream-database`、`snapshot-database`、`prepare-database` 未设置时回退到 Spring Mongo database。若 URI 与 Spring 属性都没有 database，自动配置会以对应 `must not be null` 错误失败。`wow.mongo.enabled=true`、`auto-init-schema=true`；event/snapshot batch 默认关闭，启用后的默认窗口为 `max-size=128`、`max-delay=1ms`、`max-pending-*=4096`、`lane-count=1`。

## 集合模式

物理布局由模块生成，应用不应复制一套平行 collection 或在写入前重做 MongoDB 已负责的 schema/唯一性校验。

### 集合命名规则

事件流为 `{aggregateName}_event_stream`，快照为 `{aggregateName}_snapshot`，预占键为 `prepare_{keyName}`。名称只含 aggregate/key name；database 才是 bounded-context 隔离边界。

### 事件流集合 (`{aggregateName}_event_stream`)

每个文档代表一个事件流批次。默认索引包含聚合 ID hash、`{aggregateId, version}` 唯一约束、`{aggregateId, requestId}` 唯一约束，以及查询所需索引。唯一索引由 MongoDB 原子执行并发控制与请求幂等性。

### 快照集合 (`{aggregateName}_snapshot`)

文档主键是聚合 ID。保存使用带版本判断的 aggregation update pipeline：候选版本不低于现有版本时替换，较旧候选保持 no-op；缺失或非整数的既有版本由候选修复。该 pipeline 调用 driver MQL `MqlValue.isIntegerOr`，其 `@mongodb.server.release` 标注的运行下限是 MongoDB 5.2；部署必须使用 5.2+，并继续以目标版本的真实后端集成测试为准。

### PrepareKey 集合 (`prepare_{keyName}`)

`MongoPrepareKey` 使用 MongoDB 文档更新与 TTL 索引表达预占、续租和回滚。它是协调原语，不是通用分布式事务或业务锁服务。

## 模式初始化与索引

`wow.mongo.auto-init-schema=true` 时，Starter 在创建存储前扫描当前 Wow 聚合元数据，确保集合和期望索引存在，并移除模块明确判定为冲突的旧索引。关闭后，数据库团队必须在流量进入前完成同一 schema 契约。

### EventStreamSchemaInitializer

事件索引的名字是错误映射契约的一部分。手工改名可能使 duplicate-key 不再映射为 `EventVersionConflictException` 或 `DuplicateRequestIdException`。

### SnapshotSchemaInitializer

初始化器只建立模块声明的快照集合/索引；它不会替你设置副本集、分片、读写关注、备份或保留策略。

## 查询服务

Mongo 查询服务把 Wow 的 filter、projection、sort、分页与 aggregation 编译为 MongoDB 查询。支持的能力以运行时 `QuerySchema` 为准，不要把任意 MQL 能力推断为 Wow 公共查询合同。

### 过滤器编译管道

公共字段先经逻辑 schema 验证和字段转换，再生成 Mongo filter。后端不支持或映射冲突的字段按 `wow.query.schema.validation-mode` 处理；应用无需增加一套猜测 Mongo 类型的预校验。

### 快照查询

`MongoSnapshotQueryServiceFactory` 按聚合创建查询服务并绑定 snapshot collection。tenant、owner、space 等公共作用域由共享查询层重写，Mongo 扩展只执行最终 filter。

### 快照聚合

aggregation 编译器把 Wow aggregation AST 转为 Mongo pipeline。结果语义由公共查询合同与 Mongo 表达式共同决定；运行真实后端集成测试，不要只用字符串快照证明 pipeline 可执行。

## PrepareKey：分布式协调

Prepare 存储默认选择 Mongo。若改为 Redis，Mongo capability 即使存在也不会创建 `MongoPrepareKeyFactory`。过期、竞争和回滚结果由 MongoDB 原子更新决定，调用方仍需处理未获得预占的正常分支。

## 错误映射

当前实现验证以下映射：

- `{aggregateId, version}` 唯一索引冲突 → `EventVersionConflictException`；
- request ID 唯一索引冲突 → `DuplicateRequestIdException`；
- 已知网络、primary 切换和超时写错误 → recoverable Mongo 异常；
- 未确认的 event/snapshot 写入 → `IllegalStateException`；
- 不同 context 连接同一已认领 database → 启动失败。

其他 MongoDB 异常保持后端异常，不应被文档承诺为统一 Wow 错误。

## 类层级

公共调用方依赖 `EventStore`、`SnapshotStore` 和 query/prepare 契约；Mongo 具体类属于 adapter。通过 Starter 时优先让 storage binding 与 routing 选择 adapter，不要直接把内部 saver/appender 当作应用 API。

## 索引优化建议

保留模块所需唯一索引，再根据真实 `explain` 与慢查询证据添加索引。任何修改都必须验证 schema initializer 的 reconciliation 不会把它识别为冲突布局。

### 事件流索引

版本与 request-ID 唯一索引是正确性约束，不能为了写吞吐移除。额外查询索引必须基于实际事件查询路径，而不是复制所有字段。

### 快照索引

为被 filter/sort/aggregation 使用的状态字段建立索引；不要为未开放的 Wow 查询能力建索引。数组、嵌套字段和 collation 均服从 MongoDB 原生规则。

## 性能优化

先测 direct append/save。只有积压与吞吐证据表明单写请求开销是瓶颈时，才启用 event/snapshot batch；batch 增加排队、关闭排空和部分 bulk error 处理边界。

### 连接池配置

连接池由 Spring Boot Mongo/driver 配置拥有，`wow.mongo.*` 不复制这些键。依据目标环境并发、等待队列和 server 连接限制调整。

### 写入关注配置

write concern 由 Mongo client/URI 配置。Wow 检查 `wasAcknowledged()`，但不替应用选择 durability 等级。

### 读取偏好安全性

事件重放和刚写后的读取若使用 secondary，可能观察复制延迟。读偏好由应用选择，并应在一致性测试中验证；扩展不会私自提升或降低一致性。

### 数据库分离

event、snapshot、prepare 可配置不同 database；每个 database 都会独立执行 context ownership guard。分离有助于容量与权限治理，但不是默认必需抽象。

## 分片策略

分片键、zone 和 balancing 属于 MongoDB 运维。模块会生成固定唯一索引组合；选分片键前必须确认 MongoDB 对 unique index 与 shard key 的原生限制，并用实际集群验证。

## 故障排除

先读启动异常、Mongo server error code 与索引名，再决定是配置、所有权、并发还是连接问题。

### 常见问题

以下是当前源码和测试覆盖的可重复边界。

#### 1. 版本冲突异常

它表示数据库已存在相同聚合版本。不要捕获后盲目覆盖；回到命令并发、聚合版本和重试策略定位冲突来源。

#### 2. 重复请求异常

它表示同一聚合已记录相同 request ID。将它作为幂等结果处理前，必须核对原请求结果和业务响应合同。

#### 3. 连接超时

检查 URI、DNS、认证、TLS、副本集发现和网络策略。Wow 不在驱动前添加一套连接校验；原始驱动异常是诊断依据。

## 完整配置示例

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

示例 database 必须都只服务同一 bounded context。

## 最佳实践

- 为每个 bounded context 使用独立 database，并在升级前审计存量所有权；
- 保留模块所需唯一索引和快照版本语义；
- 将 schema 变更、备份、恢复、分片和读写关注交给 MongoDB 运维流程；
- 用真实 MongoDB 集成测试验证并发、幂等、query pipeline 和升级路径。

聚焦检查：

```bash
./gradlew :wow-mongo:check
```

仓库 TCK 当前使用 `mongo:6.0.6`；模块 check 通过不证明你的版本、拓扑、数据量或索引迁移已经通过。

## 相关主题

下一步阅读[事件溯源](../domain/event-sourcing.md)确认权威历史边界，并阅读[基础设施配置](../../reference/config/infrastructure.md)完成连接、索引与恢复门禁。
