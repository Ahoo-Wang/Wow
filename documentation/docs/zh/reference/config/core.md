---
title: 核心配置
description: Wow 框架的基础配置选项，包括命令总线、事件总线、事件溯源、快照、状态事件和 Prepare Key。
---

# 核心配置

## WowProperties

- 配置类：[WowProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/WowProperties.kt)
- 前缀：`wow`

| 名称 | 数据类型 | 描述 | 默认值 |
|------|-----------|-------------|---------------|
| `enabled` | Boolean | 启用/禁用 Wow 框架 | `true` |
| `context-name` | String | 服务的限界上下文名称 | 回退为（必需的）`spring.application.name` |
| `shutdown-timeout` | Duration | 整个 Wow 运行时静默与停止的全局截止时间 | `60s` |
| `shutdown-quiet-period` | Duration | 关闭 Dispatcher 接收前必须连续空闲的时间；新活动会重新计时 | `1s` |

`shutdown-timeout` 必须大于零；`shutdown-quiet-period` 必须大于等于零，
并且严格小于 `shutdown-timeout`；二者都必须能表示为 64 位有符号纳秒值。

```yaml
wow:
  enabled: true
  context-name: order-service
  shutdown-timeout: 120s
  shutdown-quiet-period: 2s
```

## BusProperties

`BusProperties` 是 `CommandBus`、`EventBus` 和 `StateEventBus` 的公共配置。

- 配置类：[BusProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/BusProperties.kt)

| 名称 | 数据类型 | 描述 | 默认值 |
|------|-----------|-------------|---------------|
| `type` | BusType | 消息总线实现类型 | `kafka` |
| `local-first` | LocalFirstProperties | LocalFirst 模式配置 | |

### BusType

```kotlin
enum class BusType {
    KAFKA,      // Apache Kafka（生产环境推荐）
    REDIS,      // Redis Streams
    IN_MEMORY,  // 内存模式（用于测试）
    NO_OP;      // 无操作模式（用于特殊场景）
}
```

### LocalFirst 模式

LocalFirst 模式先尝试本地运行时准入，同时始终发送分布式副本。只有所有目标本地 Receiver 都确认准入后，副本才会标记为已在本地处理；否则仍可由分布式消费者处理。

```mermaid
flowchart TB
    subgraph Local["本地服务实例"]
        CG[命令网关]
        LocalBus[本地总线]
        Processor[处理器]
    end

    subgraph Distributed["分布式消息总线"]
        Kafka[Kafka]
    end

    Client --> CG
    CG -->|尝试本地准入| LocalBus
    CG -->|分布式副本| Kafka
    LocalBus --> Processor
    Kafka -->|未标记本地处理时| Processor
```

#### 行为与失败边界

1. **降低延迟**：已在本地准入的消息无需等待 Broker 往返即可处理。
2. **准入感知回退**：没有订阅者、处理入口已关闭或本地发送失败时，分布式副本仍可被处理。
3. **不会追溯重路由**：本地准入成功后的 Handler 失败遵循普通 Handler 的重试与确认策略，不会重新启用分布式副本。

来源：[LocalFirstMessageBus](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/messaging/LocalFirstMessageBus.kt#L142-L199)。

| 名称 | 数据类型 | 描述 | 默认值 |
|------|-----------|-------------|---------------|
| `local-first.enabled` | Boolean | 启用 LocalFirst 模式 | `true` |

## 命令总线

- 配置类：[CommandProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/command/CommandProperties.kt)
- 前缀：`wow.command.`

| 名称 | 数据类型 | 描述 | 默认值 |
|------|-----------|-------------|---------------|
| `bus` | `BusProperties` | 命令总线配置 | |
| `idempotency` | `IdempotencyProperties` | 命令幂等性 | |

```yaml
wow:
  command:
    bus:
      type: kafka
      local-first:
        enabled: true
    idempotency:
      enabled: true
      bloom-filter:
        expected-insertions: 1000000
        ttl: PT60S
        fpp: 0.00001
```

### IdempotencyProperties

- 配置类：[IdempotencyProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/command/CommandProperties.kt)

| 名称 | 数据类型 | 描述 | 默认值 |
|------|-----------|-------------|---------------|
| `enabled` | `boolean` | 是否启用 | `true` |
| `bloom-filter` | `BloomFilter` | 布隆过滤器 | |

#### BloomFilter

| 名称 | 数据类型 | 描述 | 默认值 |
|------|-----------|-------------|---------------|
| `ttl` | `Duration` | 存活时间 | `Duration.ofMinutes(1)` |
| `expected-insertions` | `Long` | 预期插入数量 | `1000_000` |
| `fpp` | `Double` | 误判率 | `0.00001` |

## 事件总线

- 配置类：[EventProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/event/EventProperties.kt)
- 前缀：`wow.event.`

| 名称 | 数据类型 | 描述 | 默认值 |
|------|-----------|-------------|---------------|
| `bus` | `BusProperties` | 事件总线配置 | |

```yaml
wow:
  event:
    bus:
      type: kafka
      local-first:
        enabled: true
```

## 事件溯源

### EventStoreProperties

- 配置类：[EventStoreProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/eventsourcing/store/EventStoreProperties.kt)
- 前缀：`wow.eventsourcing.store`

| 名称 | 数据类型 | 描述 | 默认值 |
|------|-----------|-------------|---------------|
| `storage` | `StorageType` | 事件存储后端 | `mongo` |

```yaml
wow:
  eventsourcing:
    store:
      storage: mongo
```

#### StorageType

`StorageType` 枚举由事件存储和快照存储共享使用。

```kotlin
enum class StorageType {
    MONGO,
    REDIS,
    ELASTICSEARCH,
    IN_MEMORY,
    DELAY
    ;
}
```

### SnapshotProperties

- 配置类：[SnapshotProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/eventsourcing/snapshot/SnapshotProperties.kt)
- 前缀：`wow.eventsourcing.snapshot`

| 名称 | 数据类型 | 描述 | 默认值 |
|------|-----------|-------------|---------------|
| `enabled` | `Boolean` | 是否启用快照 | `true` |
| `strategy` | `Strategy` | 快照策略 | `all` |
| `version-offset` | `Int` | 版本偏移阈值 | `5` |
| `storage` | `StorageType` | 快照存储后端 | `mongo` |

```yaml
wow:
  eventsourcing:
    snapshot:
      enabled: true
      strategy: all
      storage: mongo
```

#### Strategy

```kotlin
enum class Strategy {
    ALL,
    VERSION_OFFSET,
    ;
}
```

快照的 `storage` 属性复用共享的 [`StorageType`](#storagetype) 枚举。

### StorageRoutingProperties

将不同的聚合路由到单个服务内的不同存储后端。当配置了匹配的路由时，Wow 会安装一个
`RoutingEventStore` / `RoutingSnapshotStore`，按聚合并分派到绑定的存储，对于未列出的聚合则
回退到默认存储（`wow.eventsourcing.store.storage` / `wow.eventsourcing.snapshot.storage`）。

- 配置类：[StorageRoutingProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/eventsourcing/routing/StorageRoutingProperties.kt)
- 前缀：`wow.eventsourcing.storage-routing`

| 名称 | 数据类型 | 描述 | 默认值 |
|------|-----------|-------------|---------------|
| `aggregates` | `Map<String, AggregateStorageRouteProperties>` | 按聚合名称索引的逐聚合路由 | `{}`（空） |

每个聚合路由接受一个 `event` 和/或 `snapshot` 通道。**配置的通道必须设置 `storage` 或 `binding` 二者之一**——空通道（如 `event: {}`）会在启动时快速失败。只有完全省略通道才会回退到默认存储。

| 名称 | 数据类型 | 描述 | 默认值 |
|------|-----------|-------------|---------------|
| `storage` | `StorageType` | 该通道的存储后端（覆盖默认值） | _（`binding` 缺失时必填）_ |
| `binding` | `String` | 要使用的已绑定存储/查询服务 Bean 名称 | _（`storage` 缺失时必填）_ |

```yaml
wow:
  eventsourcing:
    storage-routing:
      aggregates:
        # 热点聚合：将事件和快照保留在 Redis 中以实现低延迟
        HotAggregate:
          event:
            storage: redis
          snapshot:
            storage: redis
        # 冷聚合：回退到默认的 MongoDB 存储
        # （无需路由条目 —— 应用默认值）
```

## 状态事件总线

- 配置类：[StateProperties](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/eventsourcing/state/StateProperties.kt)
- 前缀：`wow.eventsourcing.state`

| 名称 | 数据类型 | 描述 | 默认值 |
|------|-----------|-------------|---------------|
| `bus` | `BusProperties` | 状态事件总线配置 | |

```yaml
wow:
  eventsourcing:
    state:
      bus:
        type: kafka
        local-first:
          enabled: true
```

## Prepare Key

- 前缀：`wow.prepare`

| 名称 | 数据类型 | 描述 | 默认值 |
|------|-----------|-------------|---------------|
| `enabled` | Boolean | 启用 PrepareKey 功能 | `true` |
| `storage` | PrepareStorage | PrepareKey 存储后端 | `MONGO` |
| `base-packages` | List\<String\> | 扫描 PrepareKey 定义的基础包路径 | `[]` |

### PrepareStorage 值

| 值 | 描述 |
|-------|-------------|
| `MONGO` | MongoDB（推荐） |
| `REDIS` | Redis |

```yaml
wow:
  prepare:
    enabled: true
    storage: mongo
    base-packages:
      - com.example.domain
```

## 环境特定配置

### 开发环境

```yaml
wow:
  command:
    bus:
      type: in_memory
  event:
    bus:
      type: in_memory
  eventsourcing:
    store:
      storage: in_memory
    snapshot:
      storage: in_memory
```

### 生产环境

```yaml
wow:
  command:
    bus:
      type: kafka
      local-first:
        enabled: true
  event:
    bus:
      type: kafka
      local-first:
        enabled: true
  eventsourcing:
    store:
      storage: mongo
    snapshot:
      enabled: true
      strategy: all
      storage: mongo
```

## 完整配置示例

```yaml
spring:
  application:
    name: order-service

wow:
  enabled: true
  context-name: order-service
  shutdown-timeout: 120s
  shutdown-quiet-period: 2s

  command:
    bus:
      type: kafka
      local-first:
        enabled: true

  event:
    bus:
      type: kafka
      local-first:
        enabled: true

  eventsourcing:
    store:
      storage: mongo
    snapshot:
      enabled: true
      strategy: all
      storage: mongo
    state:
      bus:
        type: kafka
        local-first:
          enabled: true

  kafka:
    bootstrap-servers:
      - kafka-0:9092
      - kafka-1:9092
      - kafka-2:9092
    topic-prefix: 'wow.'

  mongo:
    enabled: true
    auto-init-schema: true

  openapi:
    enabled: true

  webflux:
    enabled: true
    global-error:
      enabled: true
```
