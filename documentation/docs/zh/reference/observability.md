---
title: 指标、Span 与健康
description: Wow 节点记录的每个指标及其类型和标签，每个 Span 及其属性，以及节点健康状态的各个组件。
---

# 指标、Span 与健康

Wow 节点向监控报告的每个名字。类路径上有 `wow-observability` 时，指标进入应用的 Micrometer 注册表，Span 进入它的 `OpenTelemetry`；健康状态通过 Spring Boot Actuator 报告。[健康、指标与追踪](../operate/observability)说明怎样接入它们，以及对什么设置告警。一个测试让本页的每个名字与报告它的代码保持一致。

## 指标 {#metrics}

标签写作 `name=value`；没有写值的标签，取其名字所说明的值。

### 命令 {#commands}

| 指标 | 类型 | 标签 | 度量 |
| --- | --- | --- | --- |
| `wow.command.commit` | Timer | `context`, `aggregate`, `outcome` | 命令的执行，从接手它到得出结果 |
| `wow.command.refused` | Counter | `context`, `aggregate`, `code` | 在内核接手之前就结束的命令，按 problem 错误码 |
| `wow.command.forward.failed` | Counter | `context`, `outcome` | 转发给分区所有者却没有得到结论的命令：`local`（在本地运行，从未到达所有者）、`busy`（所有者在途的转发已达上限；在本地运行）、`unknown`（可能已到达） |
| `wow.wait.timeout` | Counter | `context`, `aggregate`, `stage` | 超时的等待，按所等待的阶段 |
| `wow.wait.signal.dropped` | Counter | `reason` | 发往其他节点、未发送就被丢弃的等待信号 |
| `wow.executor.in-flight` | Gauge | `context` | 上下文执行器中在途的命令 |
| `wow.executor.lanes` | Gauge | `context` | 在执行器中有命令的聚合 |
| `wow.log.append-queue.depth` | Gauge | `context` | 在日志追加队列中等待的提交 |
| `wow.log.tail.reads` | Counter | `context`, `reason` | 因为内存无法接续而读取存储的 tail：`start`、`gap`、`overflow`、`idle` |
| `wow.state-cache` | Counter | `context`, `outcome` | 状态缓存的查找和逐出：`hit`、`miss`（一次快照读取和一次 tail 读取）、`evicted` |
| `wow.store.fallbacks` | Counter | `context`, `capability`, `reason` | 存储放弃某项可选能力、改用所有存储都支持的方式完成工作的次数。`capability`：`snapshot-tail-read`（日志在一次往返中读出快照及其后的尾部）、`snapshot-rebuild`（快照存储的原地重建）。`reason`：`failed`（该能力失败；第一次失败记为 warning 日志）、`unavailable`（该能力不适用于这种情况，例如快照存储与日志不在同一个数据库）、`unsupported`（存储没有这项能力） |
| `wow.reservation.lost` | Counter | `context` | 待定预留过期后被另一个所有者占用、已提交事实丢失的唯一键（决策 D50）：来自追加后内核的确认，或 `reservation` 订阅的确认 |

### 订阅 {#subscriptions}

| 指标 | 类型 | 标签 | 度量 |
| --- | --- | --- | --- |
| `wow.subscription.lag` | Timer | `context`, `subscription` | 从提交到订阅处理它 |
| `wow.subscription.position` | Gauge | `group`, `subscription`, `partition` | 订阅已处理到的日志位置 |
| `wow.subscription.backlog` | Gauge | `group`, `subscription`, `partition` | 分区中该位置之后的条目 |
| `wow.subscription.progress` | Gauge | `group`, `subscription`, `partition` | 该位置与日志头的比值，0 到 1：重建的进度 |
| `wow.subscription.parked` | Gauge | `subscription` | 订阅当前停放的聚合 |
| `wow.subscription.parked.notices` | Counter | `subscription`, `kind` | 被停放的条目（`kind=parked`），以及自动重试已用尽的条目（`kind=exhausted`） |
| `wow.subscription.paused` | Gauge | `group`, `subscription`, `partition` | 分区因系统性失败暂停期间为 1 |
| `wow.subscription.paused.notices` | Counter | `group`, `subscription`, `partition`, `kind` | 暂停（`kind=paused`）和恢复（`kind=resumed`） |
| `wow.subscription.throttled` | Gauge | `group`, `subscription`, `partition` | 分区因背压被限速期间为 1 |
| `wow.subscription.throttled.notices` | Counter | `group`, `subscription`, `partition`, `kind` | 限速（`kind=throttled`）和恢复全速（`kind=unthrottled`） |
| `wow.subscription.backpressure` | Counter | `group`, `subscription`, `code` | 因背压被拒绝的处理尝试，按 problem 错误码 |
| `wow.subscription.undecodable` | Counter | `topic`, `outcome` | 某个 topic 中当前版本无法解码的消息，被停放（`outcome=parked`）或跳过（`outcome=skipped`） |
| `wow.timer.delay` | Timer | `context` | 定时器在到期时间之后多晚触发 |
| `wow.kafka.offset.gaps` | Counter | | 读取其他上下文的 topic 时遇到的 offset 空缺 |

### 查询 {#queries}

| 指标 | 类型 | 标签 | 度量 |
| --- | --- | --- | --- |
| `wow.query` | Timer | `context`, `aggregate`, `query.type`, `outcome` | 一次查询，从准入到结束 |
| `wow.query.events.pinned` | Counter | `context`, `aggregate`, `read` | 限定到其聚合的事件查询，按批读取（`read=batch`）或按点读取（`read=point`） |
| `wow.query.limit` | Gauge | `store` | 存储当前的并发查询上限 |
| `wow.query.pressure` | Gauge | `store` | 自适应上限处于压力之下期间为 1 |
| `wow.query.limit.decreases` | Counter | `store` | 自适应上限下调的次数 |

Gauge 每隔 `wow.observability.sample-interval`（15 s）采样一次；对象已不存在的 Gauge，例如已经移到另一个节点的订阅，会被移除。

## Span {#spans}

tracer 名为 `me.ahoo.wow`。从到达的东西开始的阶段，即请求的 trace 或消息的传播字段，是它们所指明的 Span 的子 Span；其他阶段是当前 Span 的子 Span。Span 以它的 `wow.outcome` 结束，失败会记录在它上面。

| Span | 类型 | 属性 |
| --- | --- | --- |
| `admit <context>.<aggregate>` | SERVER | `wow.context`, `wow.aggregate`, `wow.command` |
| `execute <context>.<aggregate>` | INTERNAL | `wow.context`, `wow.aggregate`, `wow.aggregate.id`, `wow.tenant.id`, `wow.command`, `wow.command.id`, `wow.request.id` |
| `decide <context>.<aggregate>` | INTERNAL | `wow.context`, `wow.aggregate`, `wow.aggregate.id`, `wow.tenant.id` |
| `append <context>.<aggregate>` | INTERNAL | `wow.context`, `wow.aggregate`, `wow.aggregate.id`, `wow.version` |
| `handle <subscription>` | INTERNAL | `wow.subscription`, `wow.context`, `wow.aggregate.id`, `wow.version` |
| `<topic> process` | CONSUMER | `wow.subscription`, `wow.context`, `wow.aggregate.id`, `wow.version`, `messaging.destination.name`, `messaging.operation.type` |
| `<topic> publish` | PRODUCER | `messaging.destination.name`, `messaging.operation.type`, `messaging.batch.message_count` |
| `fire <command type>` | INTERNAL | `wow.context`, `wow.timer.id`, `wow.aggregate.id` |
| `query <context>.<aggregate>` | INTERNAL | `wow.context`, `wow.aggregate`, `wow.query.type` |

`wow.command` 是命令的全名 `<context>.<aggregate>.<Name>`，在准入 Span 和执行 Span 上都有，所以用一个值就能找到两者。

## 健康 {#health}

类路径上有 Actuator 的健康支持时，宿主贡献三个健康指示器：`wow`，即节点的各组件；`wowReadiness`；以及 `wowLiveness`。除非应用自己设置了探针分组，宿主会把后两者加入 Actuator 的探针分组：

| 端点 | 报告 |
| --- | --- |
| `/actuator/health/readiness` | `readinessState` 和 `wowReadiness` |
| `/actuator/health/liveness` | `livenessState` 和 `wowLiveness` |
| `/actuator/health/wow` | 每个组件，各为 `UP`、`DEGRADED` 或 `DOWN` |

| 组件 | 设置 |
| --- | --- |
| `node` | |
| `stores` | `wow.health.stores.*`：`down-after`、`window` |
| `ownership` | `wow.health.ownership.*`：`handover-grace` |
| `topics` | `wow.health.topics.*`：`required` |
| `subscriptions` | `wow.health.subscriptions.*`：`lag-degraded-at`、`parked-degraded-at` |
| `executor` | `wow.health.executor.*`：`saturation` |
| `liveness` | `wow.health.liveness.*`：`stall-timeout` |
| `queries` | 始终为 `UP`：供读取的每个存储的查询上限及其压力 |

`DEGRADED` 从不改变分组的状态。[健康](../operate/observability#health)说明每个组件何时为 down 或 degraded，以及哪些会让就绪或存活检查失败。处于待机的节点把它的租约分组和 topic 报告为 `standby`，而不是 down。
