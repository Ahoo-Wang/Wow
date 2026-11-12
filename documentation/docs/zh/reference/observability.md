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
| `wow.state_cache.invalidations` | Counter | `wow.context` | 因落后于日志而从状态缓存丢弃的状态：分区属主读到了该聚合更新的流，是另一个节点在不持该分区租约时提交的 |
| `wow.shared_state.rebuilds` | Counter | `wow.context`, `wow.aggregate.name`, `wow.kind`, `wow.outcome` | 订阅从快照与日志重建共享状态的次数（上下文的视图已不再持有它）：重放超过日志的一页（512 个流）、经过重放闸门（日志存储的查询上限）时为 `gated`，否则为 `ungated`；`ran` 表示已运行，`refused` 表示被闸门拒绝，订阅按背压重试 |
| `wow.shared_state.rebuild.wait` | Timer | `wow.context`, `wow.aggregate.name`, `wow.outcome` | `gated` 的重建等待重放闸门许可的时长，按之后是 `ran` 还是 `refused` 区分 |
| `wow.store.fallbacks` | Counter | `wow.context`, `wow.capability`, `wow.reason` | 存储放弃某项可选能力、改用所有存储都支持的方式完成工作的次数。`wow.capability`：`snapshot-tail-read`（存储在一次往返中读出快照及其后的日志尾部）、`snapshot-rebuild`（快照存储的原地重建）、`snapshot-load`（快照存储加载快照，此时改为从日志重放状态）。`wow.reason`：`failed`（该能力失败；第一次失败记为 warning 日志）、`unsupported`（存储没有这项能力）、`timed-out`（快照加载或一次往返的读取在 `wow.snapshot.load-timeout` 内没有应答而被放弃：存储宕机，或者在存储仍有应答时持续出现，说明这个超时对它的负载太紧） |
| `wow.cluster.fence.notices` | Counter | `wow.group`, `wow.kind` | 本节点隔离的租约组（`wow.kind=fenced`）：它的调和在集群的调和超时内没有完成任何一步，于是退出成员表并放弃租约；以及恢复的租约组（`wow.kind=recovered`）。隔离会先在这里出现，然后存活检查才会重启节点：为它设置告警。见[部署模型](../operate/topology#a-node-stuck-in-its-reconcile) |
| `wow.reservation.losses` | Counter | `wow.context` | 待定预留过期后被另一个所有者占用、已提交事实丢失的唯一键：来自追加后内核的确认，或内置预留订阅的确认 |
| `wow.node.fatal.errors` | Counter | `wow.kind`, `error.type` | 节点自身工作遇到的 JVM 致命错误（`OutOfMemoryError`、`InternalError`），按遇到它的节点部分：`subscription`、`timer`、`executor`、`ownership`、`membership`、`append-queue`、`ledger`、`node`；以及错误的类。第一个使节点致命：其 liveness 与 readiness 一直为 down，直到进程结束（[致命错误](../operate/observability#fatal-errors)） |

### 订阅 {#subscriptions}

| 指标 | 类型 | 标签 | 度量 |
| --- | --- | --- | --- |
| `wow.subscription.lag` | Timer | `wow.group`, `wow.subscription` | 从提交到订阅处理它 |
| `wow.subscription.position` | Gauge | `wow.group`, `wow.subscription`, `wow.partition` | 订阅已处理到的日志位置 |
| `wow.subscription.backlog` | Gauge | `wow.group`, `wow.subscription`, `wow.partition` | 分区中该位置之后的条目 |
| `wow.subscription.progress` | Gauge | `wow.group`, `wow.subscription`, `wow.partition` | 该位置与日志头的比值，0 到 1：重建的进度 |
| `wow.subscription.parked.aggregates` | Gauge | `wow.group`, `wow.subscription` | 订阅当前停放的聚合，最多计到 `wow.node.max-parked`（10,000） |
| `wow.subscription.parked.notices` | Counter | `wow.group`, `wow.subscription`, `wow.kind` | 被停放的条目（`wow.kind=parked`），以及自动重试已用尽的条目（`wow.kind=exhausted`） |
| `wow.subscription.paused.partitions` | Gauge | `wow.group`, `wow.subscription` | 该节点上订阅因系统性失败暂停的分区数，全部运行时为 0 |
| `wow.subscription.paused.notices` | Counter | `wow.group`, `wow.subscription`, `wow.kind` | 暂停（`wow.kind=paused`）和恢复（`wow.kind=resumed`） |
| `wow.subscription.throttled.partitions` | Gauge | `wow.group`, `wow.subscription` | 该节点上订阅因背压被限速的分区数，全速运行时为 0 |
| `wow.subscription.throttled.notices` | Counter | `wow.group`, `wow.subscription`, `wow.kind` | 限速（`wow.kind=throttled`）和恢复全速（`wow.kind=unthrottled`） |
| `wow.subscription.backpressure.refusals` | Counter | `wow.group`, `wow.subscription`, `error.type` | 因背压被拒绝的处理尝试，按 problem 错误码 |
| `wow.subscription.undecodable.messages` | Counter | `messaging.destination.name`, `wow.outcome` | 某个 topic 中当前版本无法解码的消息，被停放（`wow.outcome=parked`）或跳过（`wow.outcome=skipped`） |
| `wow.timer.delay` | Timer | `wow.context` | 定时器在到期时间之后多晚触发 |
| `wow.subscription.offset.gaps` | Counter | `messaging.destination.name` | 读取 topic 时要从分区已不再保留的 offset 开始的次数：消息因保留期而丢失 |

### 访问属性 {#access-attributes}

| 指标 | 类型 | 标签 | 度量 |
| --- | --- | --- | --- |
| `wow.access.stamped` | Counter | `wow.context`, `wow.aggregate.name`, `wow.reason` | 盖上聚合当前访问属性的流：`change`（一次提交改变了它们，它之前的流随之盖章）、`walk`（该类型的访问遍历）、`catch-up`（`wow.access` 在遍历的日志头之后读到不带它们的流） |
| `wow.access.stamp.duration` | Timer | `wow.context`, `wow.aggregate.name`, `wow.reason` | 为一个聚合的历史盖章所用的时间：`change`、`walk`、`catch-up` |
| `wow.access.walked` | Counter | `wow.context`, `wow.aggregate.name` | 该类型的访问遍历在本节点盖过章的聚合 |
| `wow.maintenance.skipped` | Counter | `wow.context`, `wow.aggregate.name`, `wow.kind` | 类型历史上的后台工作读不了、为运维停放的聚合，每次停放计一次，按工作区分：`access-walk` 或 `snapshot-rewrite`（[重建与重放](../operate/replay#access-attributes)）。重试或跳过不会让它减少；仍在停放的由停放计量计数 |
| `wow.access.walk.pending` | Gauge | `wow.context`, `wow.aggregate.name` | 按日志的记录，该类型的访问遍历完成之前为 1，之后为 0：在此之前，访问策略下的事件查询不包含遍历尚未到达的流 |
| `wow.access.walk.walked` | Gauge | `wow.context`, `wow.aggregate.name` | 按日志的记录，该类型的访问遍历已盖过章的聚合数：每个节点报的值相同，重启也不丢 |

### 查询 {#queries}

| 指标 | 类型 | 标签 | 度量 |
| --- | --- | --- | --- |
| `wow.query.duration` | Timer | `wow.context`, `wow.aggregate.name`, `wow.query.type`, `wow.outcome` | 对聚合类型的快照或事件的一次查询，从准入到结束。`wow.query.type`：`single`、`list`、`paged`、`cursor`、`count`、`aggregation`、`state`、`trace`。`wow.outcome`：`ok`、`cached`（计数或总数来自缓存）、`error`、`cancelled` |
| `wow.query.dataset.duration` | Timer | `wow.context`, `wow.dataset`, `wow.query.type`, `wow.outcome` | 对数据集的一次查询，从准入到结束；类型与结果同 `wow.query.duration`：`single`、`list`、`paged`、`cursor`、`count`、`aggregation`、`state`、`trace`；`ok`、`cached`、`error`、`cancelled` |
| `wow.query.limit.current` | Gauge | `wow.store` | 存储当前的并发查询上限 |
| `wow.query.pressure` | Gauge | `wow.store` | 自适应上限处于压力之下期间为 1 |
| `wow.query.limit.decreases` | Counter | `wow.store` | 自适应上限下调的次数 |

节点的 Gauge，即执行器、追加队列和订阅的 Gauge，每隔 `wow.node.sample-interval`（10 s）采样一次；对象已不存在的 Gauge，例如已经移到另一个节点的订阅，会被移除。查询上限的 Gauge，`wow.query.limit.current` 和 `wow.query.pressure`，是实时读取的。

### 分析目录服务器 {#analytics-catalog-server}

分析目录服务器在提供数据集时报告它自己的两个 Gauge，单位为秒。

| 指标 | 类型 | 标签 | 度量 |
| --- | --- | --- | --- |
| `wow.analytics.catalog.datasets.sync.age` | Gauge | | 距节点上次读取数据集发布的时间；首次读取之前为 NaN |
| `wow.analytics.catalog.datasets.publication.lag` | Gauge | | 节点应用的最新一次发布从发布到提供所用的时间 |

## Span {#spans}

tracer 名为 `me.ahoo.wow`，版本为 Wow 的版本。从到达的东西开始的阶段，即请求的 trace 或消息的传播字段，是它们所指明的 Span 的子 Span；其他阶段是当前 Span 的子 Span。准入的父 Span 是当前 Span（若有），即请求的 server span。每个 Span 都以它的 `wow.outcome` 结束，失败会记录在它上面，失败的完整类名为 `error.type`。订阅由它的组和名字共同确定，即 `wow.group` 与 `wow.subscription`：每个上下文都有自己的 `wow.snapshot`。

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
| `secondaryStores` | `wow.health.stores.*`：`down-after`、`window`（不计入就绪） |
| `ownership` | `wow.health.ownership.*`：`handover-grace` |
| `topics` | `wow.health.topics.*`：`required` |
| `subscriptions` | `wow.health.subscriptions.*`：`lag-degraded-at`、`parked-degraded-at` |
| `executor` | `wow.health.executor.*`：`saturation` |
| `liveness` | `wow.health.liveness.*`：`stall-timeout` |
| `queries` | 始终为 `UP`：供读取的每个存储的查询上限及其压力 |

`DEGRADED` 从不改变分组的状态。[健康](../operate/observability#health)说明每个组件何时为 down 或 degraded，以及哪些会让就绪或存活检查失败。处于待机的节点把它的租约分组和 topic 报告为 `standby`，而不是 down。
