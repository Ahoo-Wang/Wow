---
title: 指标与 Span
description: 9.x 的每个指标、标签、Span 和 Span 属性，以及 V10 中回答同一个问题的是什么，以便在切换之前重建看板与告警。
---

# 指标与 Span

V10 改了 9.x 的每个指标和 Span 的名字，并且不保留别名。基于 9.x 名字建的看板或告警，在 V10 节点上什么也查不到：请在[切换](./cutover)之前按这些表重建。V10 的全部名字见[指标、Span 与健康](../reference/observability)。

9.x 用一个通用的 Timer `wow.operation` 度量每个组件，靠 `component` 和 `operation` 标签区分。V10 按度量的对象给指标命名，形如 `wow.<域>.<名词>`，采用 OpenTelemetry 的形式；标签就是 Span 的属性：`wow.context`、`wow.aggregate.name`、`wow.subscription`、`wow.outcome`，problem 错误码用 `error.type`。模块是 `wow-observability`（9.x 为 `wow-opentelemetry`），配置在 `wow.observability.*` 之下。

## 指标 {#metrics}

| 9.x 指标 | 9.x 标签 | V10 |
| --- | --- | --- |
| `wow.operation`，`component=command_handler`，`operation=handle` | `context`、`aggregate`、`outcome`、`exception` | `wow.command.duration`（`wow.context`、`wow.aggregate.name`、`wow.outcome`）；在内核接手前被拒绝的命令：`wow.command.refusals`（`error.type`）；被聚合拒绝的命令：`wow.command.rejections`（`error.type`，拒绝的错误码） |
| `wow.operation`，`component=event_store`，`operation=append` | `context`、`aggregate`、`outcome` | 包含在 `wow.command.duration` 中；等待中的追加：`wow.log.append_queue.depth` |
| `wow.operation`，`component=event_store`，`operation=last` 或 `single` | `context`、`aggregate` | `wow.state_cache.lookups`（`wow.outcome=miss` 时读取存储）、`wow.log.tail.reads` |
| `wow.operation`，`component=snapshot_store`，`operation=save` 或 `load` | `context`、`aggregate` | 保存：`snapshot` 订阅的 `wow.subscription.lag` 和 `wow.subscription.backlog`；加载：`wow.state_cache.lookups` |
| `wow.operation`，`component=snapshot_strategy` | `context`、`aggregate` | 没有对应：快照是一个订阅，和其他订阅一样度量 |
| `wow.operation`，`component=domain_event_handler`、`projection_handler`、`stateless_saga_handler` 或 `snapshot_handler`，`operation=handle` | `processor`、`subscriber`、`outcome`、`exception` | `wow.subscription.lag`（`wow.subscription`）；失败：`wow.subscription.parked.notices` 和 `wow.subscription.parked.aggregates` |
| `wow.operation`，`operation=send` 或 `send_if_subscribed`（总线） | `component`、`message` | 命令：`wow.command.duration`、`wow.command.forward.failures`；事件：没有指标，见 `send <topic>` Span |
| `wow.operation`，`operation=receive`（总线） | `component`、`subscriber` | `wow.subscription.lag`、`wow.subscription.backlog`、`wow.subscription.position` |
| `wow.operation.items` | 同 `wow.operation` | 没有对应；排队的追加：`wow.log.append_queue.depth` |
| `wow.stream.active`、`wow.stream.messages`、`wow.stream.terminations` | `component`、`subscriber` | 没有对应：订阅的进度是 `wow.subscription.position`、`wow.subscription.backlog`、`wow.subscription.progress`；暂停是 `wow.subscription.paused.partitions` |
| `wow.processing.outcomes` | `component`、`outcome`、`exception` | `wow.subscription.parked.notices`、`wow.command.refusals`、`wow.command.rejections` |
| `wow.batch.write`、`wow.batch.write.items`、`wow.batch.queue.wait` | `coordinator`、`lane`、`window` | 没有对应：追加队列属于内部实现；看 `wow.log.append_queue.depth` 和 `wow.command.duration` |
| `wow.batch.admission.rejected` | `coordinator`、`reason` | `error.type=wow.command.busy` 的 `wow.command.refusals` |
| `wow.batch.close`、`wow.batch.coordinator.failed` | `coordinator` | 没有对应：看节点的健康，`/actuator/health/wow` |
| `wow.query` | `context`、`aggregate`、`outcome` | `wow.query.duration`（`wow.context`、`wow.aggregate.name`、`wow.query.type`、`wow.outcome`） |
| `wow.query.rows` | `context`、`aggregate` | 没有对应 |
| `wow.query.schema.refresh`、`wow.query.schema.version.changes` | | 没有对应 |

V10 还度量了 9.x 没有度量的东西：等待（`wow.wait.timeouts`）、执行器（`wow.executor.in_flight`、`wow.executor.lanes`）、状态缓存的逐出、存储的降级、丢失的预留、背压、无法解码的消息、定时器和查询上限。完整列表见[指标、Span 与健康](../reference/observability#metrics)。

## Span {#spans}

9.x 每个组件有一个插桩作用域（`me.ahoo.wow-aggregate`、`me.ahoo.wow-eventStore`……）。V10 只有一个，`me.ahoo.wow`，Span 命名为 `<操作> <目标>`。

| 9.x Span | V10 Span |
| --- | --- |
| `<aggregate>.<Command>`（处理一条命令） | `admit <context>.<aggregate>`（SERVER），其下有 `execute <context>.<aggregate>` 和 `decide <context>.<aggregate>` |
| `<aggregate>.<Command>.command send` | 在准入之内；经由主题时：`send <topic>` |
| `<aggregate>.<Event>.event.append` | `append <context>.<aggregate>` |
| `<aggregate>.event.load`、`<aggregate>.event.existsRequestId` | 没有 Span：属于 `execute` |
| `<aggregate>.<Event>.event send`、`<aggregate>.<Event>.state_event send` | `send <topic>` |
| `<function>`（事件处理器、投影或 Saga） | `handle <subscription>`；来自主题时：`process <topic>` |
| `<aggregate>.snapshot` | `handle snapshot` |
| `<aggregate>.snapshot.save`、`.snapshot.load`、`.snapshot.version` | 没有 Span |
| `<aggregate>.<Command>.waiting` | 没有 Span：等待的结果结束准入 Span（`wow.outcome`） |
| （无） | `fire <command type>`（定时器）、`query <context>.<aggregate>` |

## Span 属性 {#span-attributes}

| 9.x 属性 | V10 属性 |
| --- | --- |
| `wow.aggregate.context_name` | `wow.context` |
| `wow.aggregate.name` | `wow.aggregate.name` |
| `wow.aggregate.id` | `wow.aggregate.id` |
| `wow.aggregate.tenant_id` | `wow.tenant.id` |
| `wow.message.id` | 命令的 Span 上为 `wow.command.id` |
| `wow.message.request_id` | `wow.request.id` |
| `wow.message.trace_id` | 无：Span 自身的 trace |
