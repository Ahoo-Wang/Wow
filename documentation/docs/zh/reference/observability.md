---
title: 指标、Span 与健康
description: Wow 节点记录的每个指标及其类型和标签，每个 Span 及其属性，以及节点健康状态的各个组件。
---

# 指标、Span 与健康

Wow 节点向监控报告的每个名字。类路径上有 `wow-observability` 时，指标进入应用的 Micrometer 注册表，Span 进入它的 `OpenTelemetry`；健康状态通过 Spring Boot Actuator 报告。[健康、指标与追踪](../operate/observability)说明怎样接入它们，以及对什么设置告警。一个测试让本页的每个名字与报告它的代码保持一致。9.x 的名字与这些名字的对照见[指标与 Span](../migrate/metrics)。

## 指标 {#metrics}

标签写作 `name=value`；没有写值的标签，取其名字所说明的值。每个指标恰好带它那一行列出的标签；取值由框架命名的标签会列出全部取值，均为小写。标签与 Span 的属性同名（`wow.*`，有语义约定时用约定的名字：`error.type`、`messaging.destination.name`），取值有界：指标从不带聚合 ID、请求 ID 这类标识。分区标签 `wow.partition` 只出现在订阅位置的三个 Gauge 上。

### 命令 {#commands}

| 指标 | 类型 | 标签 | 度量 |
| --- | --- | --- | --- |
| `wow.command.duration` | Timer | `wow.context`, `wow.aggregate.name`, `wow.outcome` | 命令的执行，从接手它到得出结果：`committed`、`rejected`、`duplicate`（此前已提交）、`unchanged`、`contended`、`unknown`、`ok`（未指明结果）、`error`、`cancelled` |
| `wow.command.refusals` | Counter | `wow.context`, `wow.aggregate.name`, `error.type` | 在内核接手之前就结束的命令，按 problem 错误码 |
| `wow.command.rejections` | Counter | `wow.context`, `wow.aggregate.name`, `error.type` | 被聚合的决策拒绝的命令（带或不带事件），按拒绝的错误码 |
| `wow.command.forward.failures` | Counter | `wow.context`, `wow.outcome` | 转发给分区所有者却没有得到结论的命令：`local`（在本地运行，从未到达所有者）、`busy`（所有者在途的转发已达上限；在本地运行）、`unknown`（可能已到达） |
| `wow.wait.timeouts` | Counter | `wow.context`, `wow.aggregate.name`, `wow.stage` | 超时的等待，按它到达的最后阶段：`accepted`、`committed`、`handled`、`unknown` |
| `wow.wait.signal.drops` | Counter | `wow.reason` | 发往其他节点、未发送就被丢弃的等待信号：`queue-full`、`closed`、`expired`、`refused`、`failed` |
| `wow.executor.in_flight` | Gauge | `wow.context` | 上下文执行器中在途的命令 |
| `wow.executor.lanes` | Gauge | `wow.context` | 在执行器中有命令的聚合 |
| `wow.log.append_queue.depth` | Gauge | `wow.context` | 在日志追加队列中等待的提交 |
| `wow.log.tail.reads` | Counter | `wow.context`, `wow.reason` | 因为内存无法接续而读取存储的 tail：`start`、`gap`、`overflow`、`idle` |
| `wow.state_cache.lookups` | Counter | `wow.context`, `wow.outcome` | 状态缓存的查找：`hit`、`miss`（一次快照读取和一次 tail 读取） |
| `wow.state_cache.evictions` | Counter | `wow.context` | 从状态缓存逐出的状态 |
| `wow.store.fallbacks` | Counter | `wow.context`, `wow.capability`, `wow.reason` | 存储放弃某项可选能力、改用所有存储都支持的方式完成工作的次数。`wow.capability`：`snapshot-tail-read`（存储在一次往返中读出快照及其后的日志尾部）、`snapshot-rebuild`（快照存储的原地重建）。`wow.reason`：`failed`（该能力失败；第一次失败记为 warning 日志）、`unsupported`（存储没有这项能力） |
| `wow.reservation.losses` | Counter | `wow.context` | 待定预留过期后被另一个所有者占用、已提交事实丢失的唯一键：来自追加后内核的确认，或 `reservation` 订阅的确认 |

### 订阅 {#subscriptions}

| 指标 | 类型 | 标签 | 度量 |
| --- | --- | --- | --- |
| `wow.subscription.lag` | Timer | `wow.group`, `wow.subscription` | 从提交到订阅处理它 |
| `wow.subscription.position` | Gauge | `wow.group`, `wow.subscription`, `wow.partition` | 订阅已处理到的日志位置 |
| `wow.subscription.backlog` | Gauge | `wow.group`, `wow.subscription`, `wow.partition` | 分区中该位置之后的条目 |
| `wow.subscription.progress` | Gauge | `wow.group`, `wow.subscription`, `wow.partition` | 该位置与日志头的比值，0 到 1：重建的进度 |
| `wow.subscription.parked.aggregates` | Gauge | `wow.group`, `wow.subscription` | 订阅当前停放的聚合，最多计到 `wow.observability.max-parked`（10,000） |
| `wow.subscription.parked.notices` | Counter | `wow.group`, `wow.subscription`, `wow.kind` | 被停放的条目（`wow.kind=parked`），以及自动重试已用尽的条目（`wow.kind=exhausted`） |
| `wow.subscription.paused.partitions` | Gauge | `wow.group`, `wow.subscription` | 该节点上订阅因系统性失败暂停的分区数，全部运行时为 0 |
| `wow.subscription.paused.notices` | Counter | `wow.group`, `wow.subscription`, `wow.kind` | 暂停（`wow.kind=paused`）和恢复（`wow.kind=resumed`） |
| `wow.subscription.throttled.partitions` | Gauge | `wow.group`, `wow.subscription` | 该节点上订阅因背压被限速的分区数，全速运行时为 0 |
| `wow.subscription.throttled.notices` | Counter | `wow.group`, `wow.subscription`, `wow.kind` | 限速（`wow.kind=throttled`）和恢复全速（`wow.kind=unthrottled`） |
| `wow.subscription.backpressure.refusals` | Counter | `wow.group`, `wow.subscription`, `error.type` | 因背压被拒绝的处理尝试，按 problem 错误码 |
| `wow.subscription.undecodable.messages` | Counter | `messaging.destination.name`, `wow.outcome` | 某个 topic 中当前版本无法解码的消息，被停放（`wow.outcome=parked`）或跳过（`wow.outcome=skipped`） |
| `wow.timer.delay` | Timer | `wow.context` | 定时器在到期时间之后多晚触发 |
| `wow.subscription.offset.gaps` | Counter | `messaging.destination.name` | 读取 topic 时要从分区已不再保留的 offset 开始的次数：消息因保留期而丢失 |

### 查询 {#queries}

| 指标 | 类型 | 标签 | 度量 |
| --- | --- | --- | --- |
| `wow.query.duration` | Timer | `wow.context`, `wow.aggregate.name`, `wow.query.type`, `wow.outcome` | 对聚合类型的快照或事件的一次查询，从准入到结束。`wow.query.type`：`single`、`list`、`paged`、`cursor`、`count`、`aggregation`、`state`、`trace`。`wow.outcome`：`ok`、`cached`（计数或总数来自缓存）、`error`、`cancelled` |
| `wow.query.dataset.duration` | Timer | `wow.context`, `wow.dataset`, `wow.query.type`, `wow.outcome` | 对数据集的一次查询，从准入到结束；类型与结果同 `wow.query.duration`：`single`、`list`、`paged`、`cursor`、`count`、`aggregation`、`state`、`trace`；`ok`、`cached`、`error`、`cancelled` |
| `wow.query.events.pinned` | Counter | `wow.context`, `wow.aggregate.name`, `wow.read` | 限定到其聚合的事件查询，按批读取（`wow.read=batch`）或按点读取（`wow.read=point`） |
| `wow.query.limit.current` | Gauge | `wow.store` | 存储当前的并发查询上限 |
| `wow.query.pressure` | Gauge | `wow.store` | 自适应上限处于压力之下期间为 1 |
| `wow.query.limit.decreases` | Counter | `wow.store` | 自适应上限下调的次数 |

Gauge 每隔 `wow.observability.sample-interval`（15 s）采样一次；对象已不存在的 Gauge，例如已经移到另一个节点的订阅，会被移除。

### 分析目录服务器 {#analytics-catalog-server}

分析目录服务器在提供数据集时报告它自己的两个 Gauge，单位为秒。

| 指标 | 类型 | 标签 | 度量 |
| --- | --- | --- | --- |
| `wow.analytics.catalog.datasets.sync.age` | Gauge | | 距节点上次读取数据集发布的时间；首次读取之前为 NaN |
| `wow.analytics.catalog.datasets.publication.lag` | Gauge | | 节点应用的最新一次发布从发布到提供所用的时间 |

## Span {#spans}

tracer 名为 `me.ahoo.wow`，版本为 Wow 的版本。从到达的东西开始的阶段，即请求的 trace 或消息的传播字段，是它们所指明的 Span 的子 Span；其他阶段是当前 Span 的子 Span。准入的父 Span 是当前 Span（若有），即请求的 server span。每个 Span 都以它的 `wow.outcome` 结束，失败会记录在它上面，失败的完整类名为 `error.type`。订阅由它的组和名字共同确定，即 `wow.group` 与 `wow.subscription`：每个上下文都有自己的 `snapshot`。

| Span | 类型 | 属性 |
| --- | --- | --- |
| `admit <context>.<aggregate>` | INTERNAL | `wow.context`, `wow.aggregate.name`, `wow.command.name` |
| `execute <context>.<aggregate>` | INTERNAL | `wow.context`, `wow.aggregate.name`, `wow.aggregate.id`, `wow.tenant.id`, `wow.command.name`, `wow.command.id`, `wow.request.id` |
| `decide <context>.<aggregate>` | INTERNAL | `wow.context`, `wow.aggregate.name`, `wow.aggregate.id`, `wow.tenant.id` |
| `append <context>.<aggregate>` | INTERNAL | `wow.context`, `wow.aggregate.name`, `wow.aggregate.id`, `wow.aggregate.version` |
| `handle <subscription>` | INTERNAL | `wow.group`, `wow.subscription`, `wow.context`, `wow.aggregate.id`, `wow.aggregate.version` |
| `process <topic>` | CONSUMER | `wow.group`, `wow.subscription`, `wow.context`, `wow.aggregate.id`, `wow.aggregate.version`, `messaging.system`, `messaging.destination.name`, `messaging.operation.name`, `messaging.operation.type` |
| `send <topic>` | PRODUCER | `messaging.system`, `messaging.destination.name`, `messaging.operation.name`, `messaging.operation.type`, `messaging.batch.message_count` |
| `fire <command type>` | INTERNAL | `wow.context`, `wow.timer.id`, `wow.aggregate.id` |
| `query <context>.<aggregate>` | INTERNAL | `wow.context`, `wow.aggregate.name`（数据集查询为 `wow.dataset`）, `wow.query.type` |

`wow.command.name` 是命令的全名 `<context>.<aggregate>.<Name>`，在准入 Span 和执行 Span 上都有，所以用一个值就能找到两者。

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
