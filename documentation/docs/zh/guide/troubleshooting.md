---
title: 故障排查
description: 用最后完成的 Wow stage、配置装配条件和后端证据定位命令、存储、快照、投影与 Saga 故障。
outline: deep
---

# 故障排查

先回答：**最后完成了哪个 stage？下一个 stage 由哪个 Bus、Store 或 Handler 拥有？** 不要先扩大超时或切换后端。

::: warning 超时不是失败证明
调用方超时只表示截止时间前没有收到目标信号。命令可能尚未处理、仍在处理，或已经完成但通知未到达。在查询权威状态前，不要换新 `requestId` 重试。
:::

## 先收集最小证据包

在修改代码或配置前保存同一时间窗口的：

- 应用构建标识、JDK、Wow BOM/依赖锁定和 runtime classpath 中的 capability；
- 完整异常链与 Spring condition evaluation report；
- `requestId`、`commandId`、`contextName`、`aggregateName`、`aggregateId`、已知版本；
- 请求的 `CommandStage`、函数目标与最后收到的 `CommandResult.stage`；
- 脱敏后的 `wow.*`、相关 `spring.*` 生效配置和 storage route；
- Broker offset/lag/pending、EventStore/SnapshotStore 健康、trace 与指标。

只在受控复现窗口临时开启：

```yaml
logging:
  level:
    me.ahoo.wow: DEBUG
```

Debug 日志可能包含业务 ID、Header 和错误上下文；公开 Issue 前必须脱敏，生产中不得长期启用。

## 症状到阶段的速查表

| 最后证据 | 下一 owner | 先检查 |
| --- | --- | --- |
| 应用未启动 | capability / 自动配置 | 依赖变体、`*.enabled`、必填连接、缺失/重复 Bean |
| 没有 `SENT` | CommandBus | 发送错误、topic/Stream、ACL、序列化、网络 |
| 只有 `SENT` | Command Dispatcher / Aggregate | 元数据、Handler、聚合加载、业务异常 |
| `PROCESSED` 缺失 | EventStore / DomainEventBus | append、版本冲突、request-id、Broker send |
| 只有 `PROCESSED` | StateEvent/Snapshot 或目标函数 | StateEvent lag、SnapshotStore、函数标识、consumer lag |
| `SNAPSHOT` 已到但查询旧 | Snapshot strategy / query binding | `version_offset` 是否跳过、查询是否路由到同一后端 |
| Projection/副作用重复 | Handler | 幂等键、ACK/offset、重投、补偿记录 |
| Redis pending 增长 | Redis Bus recovery | group、idle time、claim 失败、Stream trimming |
| Kafka receiver 重复失败 | decode/receiver policy | 首个坏记录、`decode-failure-strategy`、退避与 offset |
| 关停超时 | WowRuntime component owner | 入口是否摘除、活动任务、不可取消 I/O、批处理 drain |

## 命令超时

### 1. 先确认等待的阶段

| Stage | 未到达时沿链路检查 |
| --- | --- |
| `SENT` | Gateway → 选中的 CommandBus send |
| `PROCESSED` | Command Dispatcher → 聚合加载/处理 → EventStore append → DomainEventBus send |
| `SNAPSHOT` | StateEventBus → Snapshot Dispatcher → SnapshotStrategy/Store |
| `PROJECTED` | 目标 Projection function、last-projection 信号、读模型写入 |
| `EVENT_HANDLED` | 目标 Event Handler、外部依赖、重试/补偿 |
| `SAGA_HANDLED` | 目标 Saga 与派生命令 send；不要继续推断下游聚合已完成 |

函数型 stage 同时核对 `contextName`、`processorName`、`functionName`。另一个函数成功不能满足错误目标。

### 2. 用同一组标识追踪链路

HTTP 客户端通过 `Command-Request-Id` 传入稳定 `requestId`。有响应时 `CommandResult` 提供服务端 `commandId`；没有响应时，用 request-id 在日志/span 找到 command-id，再沿 AggregateId 和 stage 关联。不要依赖固定英文日志句子，标识和 stage 更稳定。

### 3. 不要用超时掩盖根因

- 没有 `SENT`：增大 `PROJECTED` 等待时间无效。
- 总在 `PROCESSED` 后停止：检查目标消费链路，不要重跑聚合命令。
- 只有热点聚合超时：比较事件重放数、版本冲突与后端延迟。
- 调用方超时后需要重试：先查询权威状态，并复用原 `requestId`。

`WaitPlan.withTimeout` 是调用方本地截止时间，不传播到命令 Header。完整语义见[完成语义](./command/completion.md#超时取消与未知结果)。

## 聚合、幂等与并发异常

### `DuplicateRequestIdException`

EventStore 已确认同一 aggregate 上存在该 `requestId`。如果是同一逻辑命令的重试，这是幂等结果；如果不是，修复 request-id 生成/作用域。换新 ID 会绕过该保护。

### `DuplicateAggregateIdException`

创建命令尝试初始化已经存在的聚合。核对 ID 分配、`isCreate` 语义与调用方重试；不要把它当作普通网络失败无限重试。

### `EventVersionConflictException`

append 的 expected version 与 EventStore head 不同。Wow 只对 recoverable 错误做有限退避重试。持续冲突应检查热点聚合、陈旧 `aggregateVersion` 或违反单聚合顺序的自定义 Bus/Store，而不是增加无限重试。

## 元数据或处理函数未注册

1. 确认领域模块应用 KSP，并在 `ksp(...)` 中使用 `wow-compiler`。
2. 确认服务 runtime classpath 包含该领域模块，不只是 API 模块。
3. 对目标模块 clean/build，并检查产物中的 `META-INF/wow-metadata.json`。
4. 核对 `spring.application.name` / `wow.context-name`、aggregate 名和函数元数据。
5. 若 HTTP route 缺失，再核对 `webflux-support`；不要添加一个重复 Controller 掩盖元数据问题。

## 投影延迟或重复副作用

### 先区分积压与单次处理慢

- lag/pending 持续增长：定位分区/consumer group、持续失败和下游容量。
- lag 稳定但单次慢：分开测量反序列化、业务函数与外部 I/O。
- 处理完成但等待不结束：核对函数目标与 `isLastProjection`，不是先扩容。

只有不可避免的阻塞 API 才用 `@Blocking`/受限 Scheduler 隔离；它不会改善慢查询或无界队列。

### 处理器必须可重试

使用业务唯一键、event ID 或目标 version 做幂等。重试外部调用前先查询其当前状态。自动重试耗尽后保留补偿记录与原错误，按目标函数补偿；不要通过 ACK 丢弃来“消除”积压。

## 聚合加载慢或快照异常

1. 记录 EventStore head、Snapshot version、本次重放 stream 数和 sourcing 耗时。
2. 分开测量 SnapshotStore load、EventStore load 与 sourcing function。
3. 若 Snapshot 与完整重放不一致，停止依赖该快照的读路径，先用聚合规格定位非确定 sourcing。
4. 若 `SNAPSHOT` 已完成但没有写入，确认策略是否为 `version_offset` 且阈值未达到。
5. 若查询返回旧数据，核对 storage route 的 SnapshotStore 与 SnapshotQueryServiceFactory 是否指向同一 binding。

## 连接与自动配置

### 先隔离框架与外部后端

在最小复现中用纯内存配置定位领域/元数据问题，并显式关闭可能仍在 classpath 的集成：

```yaml
wow:
  kafka.enabled: false
  mongo.enabled: false
  redis.enabled: false
  elasticsearch.enabled: false
  prepare.enabled: false
  command.bus.type: in_memory
  event.bus.type: in_memory
  eventsourcing.store.storage: in_memory
  eventsourcing.snapshot.storage: in_memory
  eventsourcing.state.bus.type: in_memory
```

内存路径通过只能把故障缩小到外部 Adapter；它不是生产降级方案，也不能证明真实后端语义。

### Bean 装配失败

按顺序检查：

1. 对应 capability 是否在 `runtimeClasspath`；
2. `wow.*.enabled`、Bus/Storage 类型与 Spring Boot 连接属性是否一致；
3. condition report 中第一个未满足的 `@Conditional*`；
4. storage route 的 `storage`/`binding` 是否恰好一个，store 与 query factory binding 是否都存在；
5. 自定义 Bean 是否导致多个候选或替换了自动配置。

属性和默认值见[核心配置](../reference/config/core.md)与[基础设施配置](../reference/config/infrastructure.md)。

## 性能与告警

阈值来自应用 SLO 和目标硬件基线，不存在通用的“命令一秒”。至少按 stage 分解延迟，并关联：聚合重放事件数、version conflict、EventStore/SnapshotStore 延迟、Broker lag/pending、Handler retry/compensation 和 shutdown drain。没有 production-like 数据量的测量时，结论应标记为 `MISSING EVIDENCE`。

框架 JMH 只能建立框架基线，不能替代应用查询计划与端到端负载；见[框架测试与基准](./test-runtime.md#基准分三种用途)。

## 提交可诊断的 Issue

在 [GitHub Issues](https://github.com/Ahoo-Wang/Wow/issues) 搜索完整异常类名和 `errorCode`。新 Issue 提供最小失败测试、完整异常链、最后 stage、脱敏配置、相关 capability 与后端健康/lag 证据。删除密码、Token、证书、真实 URI 凭据和敏感业务 payload。
