---
title: 故障排查
description: 按命令、聚合、事件存储、投影与 Saga 阶段定位 Wow 故障，并收集可复现证据。
outline: deep
---

# 故障排查

排障的目标不是先扩大超时，而是回答两个问题：**消息最后到达了哪个阶段？下一个阶段为什么没有完成？**

::: warning 超时不等于命令失败
调用方超时只表示没有在截止时间前观测到目标信号。命令可能尚未处理、正在处理，或已经完成但结果信号未到达。在确认状态前，不要用新 `requestId` 盲目重试。
:::

## 先收集最小证据包

在修改配置或代码前，保留以下信息：

- Wow 版本、JDK 版本与启用的 `wow-*` 模块。
- 完整异常链，不只是最后一行。
- `commandId`、`requestId`、`contextName`、`aggregateName`、`aggregateId` 和已知的聚合版本。
- 调用方请求的 `CommandStage` 与最后观测到的 `CommandResult.stage`。
- 生效的 `wow.*`、`spring.*` 后端配置（密码、Token 和 URI 凭据必须脱敏）。
- 同一时间窗口内的日志、span、指标、消费积压和存储健康状态。

临时提高 Wow 日志级别时，优先在可复现环境中开启，并限制时间窗口：

```yaml
logging:
  level:
    me.ahoo.wow: DEBUG
```

Debug 日志可能包含业务标识和消息上下文；不要在生产环境长期开启，也不要将未脱敏日志附到公开 Issue。

## 症状到阶段的速查表

| 症状 | 先确认 | 最常见的下一步 |
| --- | --- | --- |
| 命令没有任何结果 | 是否到达 `SENT` | 检查 CommandBus 连接、路由和发送错误 |
| 只到 `SENT` | 聚合元数据和处理函数是否注册 | 检查 KSP 输出、限界上下文和聚合加载 |
| 只到 `PROCESSED` | 实际等待的是否为 `SNAPSHOT`、`PROJECTED` 或 `SAGA_HANDLED` | 进入对应处理器，核对目标函数和消费积压 |
| HTTP 写入成功，立即查询不到 | 写入等待阶段是否覆盖该投影 | 使用精确 `PROJECTED` 目标，不要固定 `sleep` |
| 同一事件的副作用重复执行 | 处理器是否幂等 | 先修正副作用幂等，再分析重投或 ACK |
| 聚合加载慢 | 加载了多少事件，是否命中快照 | 检查快照策略与 EventStore 查询延迟 |
| 启动时缺少 Bean 或聚合 | 依赖、capability 和自动配置条件是否成立 | 打开 Spring condition report，核对配置键 |
| 关停卡住 | 哪个运行时组件仍有活动任务 | 按[运行时生命周期](./advanced/runtime-lifecycle.md)核对拥有权和超时 |

## 命令超时

### 1. 先确认等待的阶段

| 阶段 | 表示 | 未到达时检查 |
| --- | --- | --- |
| `SENT` | 命令已被命令总线接受 | CommandBus 实现、网络、序列化和路由 |
| `PROCESSED` | 聚合决策与事件追加已完成 | 处理函数注册、聚合加载、业务异常和 EventStore |
| `SNAPSHOT` | 快照处理已完成 | 状态事件总线、快照处理器和 SnapshotStore |
| `PROJECTED` | 特定投影函数已完成 | 目标函数是否匹配、投影异常、消费积压和读库 |
| `EVENT_HANDLED` | 特定事件处理函数已完成 | 处理器过滤、重试、补偿和外部依赖 |
| `SAGA_HANDLED` | 特定 Saga 函数已处理源事件 | Saga 匹配、处理异常和派生命令发送；该阶段不代表下游命令已完成 |

等待函数型阶段时，还要核对 `contextName`、`processorName` 和 `functionName`。目标函数写错时，其他投影或 Saga 完成也不会满足等待计划。

### 2. 用同一组标识追踪链路

使用客户端在 `Command-Request-Id` 中提供的 `requestId` 关联 HTTP 请求。`CommandResult` 还会返回服务端生成的 `commandId`；如果没有返回结果，则通过 `requestId` 关联服务端日志或 span，从中找回 `commandId`。不要依赖固定英文日志文本；日志消息可随实现演进，标识与阶段更稳定。

### 3. 不要用超时掩盖根因

- `SENT` 都未到达：延长 `PROJECTED` 超时没有意义。
- 总在 `PROCESSED` 后超时：优先检查目标投影/Saga 和消费积压。
- 只有少数请求超时：对比聚合热点、事件数量、后端延迟和重试。

详细等待契约见[命令网关](./command-gateway.md#等待计划)。

## 聚合、幂等与并发异常

### `DuplicateRequestIdException`

表示同一聚合上的 `requestId` 重复。先判断这是客户端对同一逻辑请求的重试，还是错误复用了标识。对同一逻辑请求，重试时应保留原 `requestId`；换新标识会绕过幂等保护。

### `DuplicateAggregateIdException`

创建命令尝试初始化已存在的聚合。核对 ID 生成、创建语义和客户端重试，不要把它当作普通版本冲突无限重试。

### `EventVersionConflictException`

表示事件流的预期版本与存储中的当前版本不同。Wow 会对可恢复异常执行有限重试，但持续冲突通常意味着聚合是写入热点或业务边界需要调整。记录冲突频率和聚合标识，不要把无限重试当作解决方案。

## 元数据或处理函数未注册

典型表现是运行时无法找到 context、aggregate 或处理函数。

1. 确认领域模块应用 KSP 并引入 `wow-compiler`。
2. 确认聚合类和处理函数符合[建模约定](./modeling.md#约定)。
3. 清理并重新编译相关模块，然后检查生成的 `META-INF/wow-metadata.json`。
4. 确认宿主服务实际依赖了该领域模块。

```kotlin
plugins {
    id("com.google.devtools.ksp")
}

dependencies {
    ksp("me.ahoo.wow:wow-compiler")
}
```

## 投影延迟或重复副作用

### 先区分积压与单次处理慢

- **积压增长**：检查消费并发度、分区、实例数、持续失败和下游容量。
- **单次处理慢**：从 span 或处理时间分布定位外部 I/O、大对象序列化或读库写入。
- **只有等待超时**：核对 `PROJECTED` 目标函数；处理器可能已完成，但不匹配等待目标。

优先使用响应式客户端。只有处理函数确实调用不可避免的阻塞 API 时，才使用 `@Blocking` 显式隔离；它不会修复慢查询或下游容量不足。

### 处理器必须可重试

不要把分布式消息投递当作“恰好一次”。以业务唯一键、事件 ID 或目标版本建立幂等写入，并在重试前确认外部副作用的当前状态。对无法自动恢复的失败，进入[事件补偿](./event-compensation.md)流程。

## 聚合加载慢或快照异常

1. 记录聚合当前版本、快照版本和本次重放的事件数。
2. 分开测量 SnapshotStore 加载、EventStore 加载和溯源函数执行时间。
3. 如果快照与完整事件重放结果不同，先停止依赖该快照的读取路径，用聚合规格测试检查每个 `onSourcing` 函数。
4. 调整快照策略前，先用实际事件分布验证收益；不要只因单个热聚合就全局改策略。

配置和语义见[快照](./snapshot.md)。

## 连接与自动配置

### 先隔离框架与外部后端

在本地或最小复现中，可暂时使用内存实现确认领域模型与路由是否正常：

```yaml
wow:
  kafka:
    enabled: false
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
    state:
      bus:
        type: in_memory
```

如果内存路径通过而外部后端失败，再进入 [Kafka](./extensions/kafka.md)、[MongoDB](./extensions/mongo.md)、[Redis](./extensions/redis.md) 或 [Elasticsearch](./extensions/elasticsearch.md) 页面。该方法只用于定位，不是生产降级方案。

### Bean 装配失败

1. 检查对应模块或 starter capability 是否在运行时 classpath。
2. 打开 Spring Boot condition evaluation report，查看具体哪个 `@Conditional*` 未满足。
3. 核对配置前缀和默认值，使用[配置参考](../reference/config/core.md)而不是猜测配置键。
4. 检查是否同时提供了多个候选实现，或用自定义 Bean 覆盖了自动配置。

## 性能与告警

没有通用的“命令 1 秒”或“投影 5 秒”告警阈值。阈值应来自应用 SLO、当前代码与硬件上的基线，并区分 p50、p95、p99 和最大值。

建议至少观测：

- 按 `CommandStage` 分解的端到端延迟。
- 聚合加载时间、重放事件数和版本冲突率。
- EventStore/SnapshotStore 读写延迟与错误率。
- 命令、事件、投影和 Saga 的消费积压与失败率。
- 重试、补偿和不可恢复任务数量。

对当前版本建立可复现性能基线时，使用[测试运行体系](./test-runtime.md#基准-smoke)中的 JMH 任务。

## 提交可诊断的 Issue

如果仍无法定位，在 [GitHub Issues](https://github.com/Ahoo-Wang/Wow/issues) 搜索完整异常类名和 `errorCode`。新 Issue 至少应包含：

- Wow/JDK 版本与依赖模块。
- 期望阶段、实际最后阶段和完整异常链。
- 脱敏后的有关配置。
- 最小可复现示例或可执行失败测试。
- 如涉及外部后端，提供后端版本、拓扑和健康/积压证据。

::: tip
一个能稳定失败的最小测试，比一整段脱离上下文的 Debug 日志更容易解决问题。
:::
