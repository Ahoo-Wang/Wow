---
title: Redis
description: 使用 Redis Streams、Lua 与原生数据结构承载 Wow 总线和存储。
---

# Redis

`wow-redis` 提供 Redis 实现的三种分布式消息总线、`EventStore`、`SnapshotStore` 与 `PrepareKeyFactory`。在团队已运维 Redis，并接受其 Streams、持久化和内存容量边界时使用；它不是 Kafka 或数据库的透明替代品。

## 架构概述

消息总线使用 Redis Streams consumer group；事件存储使用 sorted set、request set 和 Lua；快照使用 string 与版本保护 Lua；PrepareKey 使用 hash 与 Lua。Wow 定义 key 布局和脚本结果映射，Redis 负责脚本原子性、cluster slot、持久化、复制、淘汰与故障切换。

## 安装

直接依赖：

```kotlin
implementation("me.ahoo.wow:wow-redis")
implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
```

Starter capability：

```kotlin
implementation("me.ahoo.wow:wow-spring-boot-starter") {
    capabilities { requireCapability("me.ahoo.wow:redis-support") }
}
```

## 配置

下面显式选择 Redis 作为总线、事件/快照和 Prepare 存储：

```yaml
spring:
  data:
    redis:
      url: redis://localhost:6379

wow:
  command:
    bus:
      type: redis
  event:
    bus:
      type: redis
  eventsourcing:
    store:
      storage: redis
    snapshot:
      storage: redis
    state:
      bus:
        type: redis
  prepare:
    storage: redis
```

`wow.redis.enabled=true`。pending recovery 默认启用：`min-idle-time=5m`、`interval=30s`、`batch-size=100`；两个 duration 至少 `1ms`，batch size 必须大于 0。连接、cluster、sentinel、TLS 和 pool 由 `spring.data.redis.*` 管理。

## 命令总线

`RedisCommandBus` 只在 `wow.command.bus.type=redis` 时装配。发送成功表示 Stream record 已写入 Redis，不表示命令已处理。

自 9.3.0 起，三个 Redis 总线就是建在 `RedisStreamTransport` 之上的核心传输总线（见[传输 SPI](../command/internals/transport.md#传输-spi)），构造器与 Stream 格式不变。`msg` 字段缺失或无法解码、或消息属于别的 stream 的条目，会报告给 `RedisMessageBusObserver` 并保持待处理（`RedisRecordDecodeFailureHandler`）。

### Stream 命名规则

默认命令 Stream 为 `${contextAlias}.${aggregateName}:command`；领域事件和状态事件分别使用 `:event`、`:state`。命名来自 `NamedAggregate.toStringWithAlias()`，不是可随意改动的数据迁移细节。

### 消费者组

subscription 的 receiver group 成为 Redis consumer group。group 创建时的 `BUSYGROUP` 被视为并发创建后的正常状态；权限、键类型或连接错误仍会失败。

### 接收重试

自 9.3.0 起，接收流失败后按核心的 `TransportFailurePolicy` 重新订阅，默认值与 Kafka 相同：连续重试 3 次，退避从 `10s` 起（`wow.redis.message-bus.receiver.retry-attempts` / `retry-backoff`，Bean `redisTransportFailurePolicy`）。新订阅以新的 consumer 加入消费组，旧 consumer 遗留的 pending 条目由恢复机制认领。重试耗尽后错误才到达 dispatcher，并让运行时停止。9.3.0 之前，任何 Redis 接收错误（包括一次故障切换的抖动）都会立即让运行时停止。连接丢失与命令超时（`RedisConnectionFailureException`、`QueryTimeoutException` 及其 Lettuce 原因）也注册为 `RECOVERABLE`（`RedisRecoverableExceptionProvider`），`RetryableFilter` 与事件存储追加结果判定会重试它们。

### Pending 消息恢复

recovery 周期扫描 idle 超过阈值的 pending entry，并在确认原 consumer 不活跃后 claim。它只处理 ack 前因进程终止/取消、transport 或 decode 等路径遗留且仍在 Stream 中的 PEL entry，不恢复被 trim、删除或未持久化的数据。

优雅停机（自 9.3.0 起）先停止读取：已经交出的 entry 照常处理并确认，已经读到、但没有交出的 entry 保持 pending，交由这里的恢复处理。见[运行时生命周期](../advanced/runtime-lifecycle.md#持续流量下先停持久入口)。

### Stream 保留与空闲 consumer

默认不裁剪 Stream：条目一直保留到手动删除，落后的消费者组或从头重放的新组仍能读到它。要限制内存，可设置以下之一：
- `wow.redis.message-bus.retention.max-length`：每次发送时把 Stream 裁剪到约这么多条（`XADD … MAXLEN ~`）。
- `wow.redis.message-bus.retention.max-age`：每次发送时裁剪早于这个时长的条目（`XADD … MINID ~`，按发送方时钟计算）。

裁剪对所有消费者组生效。落后的组尚未读到的条目会对它丢失，recovery 也补不回来，所以上限要按最慢的组的最坏延迟来定。

开启裁剪前：
- **需要 Redis 7.0 及以上。** 裁剪可能删掉某个 consumer 仍在待处理的条目。Redis 7.0 在 recovery 认领（`XCLAIM`）这类条目时会清掉它们的引用；在 Redis 6.2 上，认领已被裁剪的待处理条目可能在每轮 recovery 中反复报错。
- **`max-age` 要远大于时钟偏差。** `MINID` 按发送节点的时钟计算，而不是 Redis 的时钟。时钟偏快的发送方会裁掉按 Redis 时钟尚未达到 `max-age` 的条目。`max-age` 至少为 `1m`，并应远大于节点间的时钟偏差。

每次订阅启动都以新的 consumer 名加入组，重启会留下旧 consumer。接收器启动时，会删除本组中没有待处理条目、且空闲达到 `consumer-idle-timeout`（默认 `30m`）的 consumer。检查和删除在同一个脚本里执行，不会丢失待处理条目。活跃的 consumer 每隔几秒就会轮询，不会空闲这么久；即使被删，下一次读取也会重新创建它。设置 `reap-idle-consumers=false` 可保留所有 consumer。

## 事件总线

领域事件与状态事件复用相同 Streams 管线和显式 acknowledge 语义。`RECOVERABLE` 失败先由进程内 `RetryableFilter` 重试；耗尽后，已启用的补偿模块记录它以便后续补偿，默认 `LogResumeErrorHandler` 记录并恢复，而 `AbstractAggregateEventDispatcher.finallyAck` 在成功或错误后都会确认原 exchange。因此普通业务 handler 失败不依赖 Redis PEL recovery 重投（除非 `wow.event.ack-on-unrecorded-failure=false` 或补偿记录失败，见[失败记录](../event/dispatch.md#失败记录)）；只有确认前终止/取消、transport、decode 等未确认路径才可能由 Redis redelivery/recovery 重新交付。处理器仍应保持幂等以覆盖这些未确认路径和显式补偿。

### 领域事件 Stream

`RedisDomainEventBus` 在 `wow.event.bus.type=redis` 时创建，服务投影、Saga 等领域事件订阅者。

### 状态事件 Stream

`RedisStateEventBus` 在 `wow.eventsourcing.state.bus.type=redis` 时创建，服务快照与状态订阅者。模块存在不等于该 bus 已选中。

## 事件存储

`RedisEventStore` 使用 canonical v2 keys。每个聚合实例的事件、request index 和所在 ID bucket 通过 hash tag 保持 Lua 所需键在同一 cluster slot。

### 数据结构

事件按 version 写入 sorted set；128 个 bucket 的 aggregate-ID index 支持稳定扫描；每个 aggregate 有独立 request-ID set。组件名经编码，避免用户 ID 中的 `:`、`{}` 或 Unicode 破坏 key 结构。

### 请求幂等性

`event_stream_append.lua` 在一个原子脚本内检查已有事件数、首版本聚合 ID、request ID，再写事件和索引。结果映射为版本冲突、重复聚合 ID、重复请求或成功；不要在应用层复制非原子的预检查。

### 聚合 ID 扫描

扫描按 canonical index member 的可排序编码跨 128 buckets 合并。它是框架维护/查询能力，不是 Redis `SCAN` 的包装。

## 快照存储

`snapshot_save.lua` 只在候选版本不低于存量版本时 `SET`；较旧快照 no-op。缺失或非数字的存量 `version` 直接报错，防止静默覆盖损坏数据。

### 升级边界

Starter 启动时对实际路由到 Redis EventStore 的聚合检测旧 shared request index 与旧 bucket layout；发现后 fail closed。当前运行时只读写 canonical v2，不在线迁移旧 key。停旧 writer，离线迁移或重建，再启动新版本。

## 预分配 Key

`wow.prepare.storage=redis` 才创建 `RedisPrepareKeyFactory`。Lua 原子执行 prepare/reprepare/rollback，key 使用单一显式 hash tag；调用方负责处理竞争失败与业务补偿。

## 连接池配置

使用 Spring Data Redis/Lettuce 原生属性。Wow 不拥有 pool size、command timeout 或 topology refresh，也不重复驱动的连接校验。

## 集群配置

canonical key layout 保证单个 Lua 操作的 keys 同 slot，但不会配置 cluster、resharding 或 replica。上线前用实际 cluster 验证脚本、failover 和 slot 迁移。

## 哨兵配置

Sentinel master、nodes、认证与 TLS 全由 Spring Boot Redis 配置。模块只消费已创建的 `ReactiveStringRedisTemplate`。

## 性能优化

先观察 command latency、Stream lag、pending 数、内存与脚本耗时；不要凭模块存在就启用 trim 或改变淘汰策略。

### 批量操作

总线使用 Streams 原生批量读取；EventStore/SnapshotStore 以 Lua 保持单聚合原子性。模块没有可配置的 Mongo/Elasticsearch 式写入 batcher。

### 内存优化

事件历史是权威数据时，任意 eviction 或未审计 trim 都可能造成不可恢复的数据丢失。容量与保留应按事件量、快照和重放目标规划。

### 建议配置

没有通用 Redis server 模板。根据是否把 Redis 当权威存储，选择持久化、复制、`maxmemory-policy`、备份和恢复目标，并记录实测证据。

# Redis 服务端配置建议

若 Redis 承载 EventStore，至少确保不会因普通缓存淘汰策略删除 v2 event/snapshot keys；若只承载消息总线，也要保证 Stream retention 覆盖最大恢复窗口。

## 故障排查

当前实现与测试验证的主要失败包括：非法 recovery bounds、损坏 snapshot version、旧 EventStore layout、Lua 返回版本/请求冲突，以及 pending/consumer 元数据解析失败。

### 常见问题

先区分连接/拓扑、key layout、Lua 结果、Stream group 与容量问题。

#### 1. 连接超时

检查 Spring Redis URL、DNS、认证、TLS、Sentinel/cluster 拓扑和网络；保留 Lettuce 原始异常。

#### 2. 内存不足

停止继续写入并确认是否发生 eviction。不要通过删除 event/request/index key 临时恢复容量；应扩容或按已验证的数据迁移/保留策略处理。

#### 3. Stream 消费延迟

检查 lag、pending、idle consumer、recovery observer 与处理器耗时。先确认 entry 是否在 ack 前因进程/transport/decode 路径遗留；普通 handler 失败通常已由 retry/compensation/error handler 处理并经 `finallyAck` 确认。recovery 只能 claim 可见 pending entry，不能补回已 trim 的记录。

## 监控指标

监控 Redis latency、内存、eviction、persistence、replication、Stream length/lag/pending、Lua error 和 Wow handler failure。成功的 module check 不是目标 Redis 健康证明。

## 完整配置示例

```yaml
spring:
  data:
    redis:
      url: ${REDIS_URL}

wow:
  redis:
    enabled: true
    message-bus:
      recovery:
        enabled: true
        min-idle-time: 5m
        interval: 30s
        batch-size: 100
  eventsourcing:
    store:
      storage: redis
    snapshot:
      storage: redis
```

## 最佳实践

- 显式选择每个 bus/store，不从 capability 推断运行时；
- 把 canonical v2 升级当数据迁移，不做在线兼容桥；
- 保持事件处理幂等，演练 pending claim、故障切换和恢复；
- 权威数据禁用未审计 eviction/trim，并验证备份可恢复。

聚焦检查：

```bash
./gradlew :wow-redis:check
```

下一步阅读[基础设施配置](../../reference/config/infrastructure.md)和[迁移](../migration.md)。
