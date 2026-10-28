---
title: 健康、指标与追踪
description: 节点在 Actuator 就绪和存活分组中的健康状况、wow-observability 记录的指标和 span，以及应当对什么报警。
outline: deep
---

# 健康、指标与追踪

节点通过 Spring Boot Actuator 的健康分组告诉平台是否该给它发流量、是否该重启它。有了 `wow-observability`，它还为工作的每个阶段记录一个 span，放在发起这项工作的请求、条目或消息的追踪中，并记录提交、订阅、定时器和查询的指标。本页说明两者，并列出应当对什么报警。

## 健康 {#health}

类路径上有 Spring Boot Actuator 的健康支持时，宿主会提供节点的健康状况：

| 端点 | 报告 |
| --- | --- |
| `/actuator/health/readiness` | 节点是否应当接收流量：Actuator 自己的就绪状态加上节点的 |
| `/actuator/health/liveness` | 节点是否存活：Actuator 自己的存活状态加上节点的 |
| `/actuator/health/wow` | 节点的每个组件，各为 `UP`、`DEGRADED` 或 `DOWN` |

组件：

| 组件 | 何时 `DOWN`（未就绪） | 何时 `DEGRADED` |
| --- | --- | --- |
| `node` | 节点没有在运行：启动中、排空中或已停止 | |
| `stores` | 某个日志持续失败了 `wow.health.stores.down-after`（15 秒），期间没有成功过 | 某个日志在 `wow.health.stores.window`（1 分钟）内失败过 |
| `ownership` | 它的租约存储不可用，或者在 `wow.health.ownership.handover-grace`（1 分钟）之后，节点仍没有拥有某个租约组中应得的任何分区 | 它在这么长时间内拥有的分区集合不是它应得的那份，或者租约存储最近失败过 |
| `topics` | 它读取的另一个上下文的某个主题仍在加入中（除非 `wow.health.topics.required: false`） | |
| `subscriptions` | 从不 | 某个订阅落后了 `lag-degraded-at`（10,000）个条目，或有 `parked-degraded-at`（100）个停放条目 |
| `executor` | | 执行器的在途量达到其上限的 `saturation`（90%） |
| `liveness` | 节点卡住了：在每个日志都有响应的情况下，它的调度器在 `wow.health.liveness.stall-timeout`（3 分钟）内没有运转，或者在途的命令没有完成。只有这一项会让存活探针失败 | |

降级的组件不会改变分组的状态：它的作用是让人看到并据此报警。存活探针从不因存储故障而失败，节点排空期间也不会失败，所以一次故障不会让所有节点同时重启。探针不能等待的东西（日志的头部、订阅的落后量、停放的条目），节点每隔 `wow.node.sample-interval`（10 秒）采样一次，所以探针能立即得到回答。

响应除了状态之外还显示什么，由 Actuator 自己的设置决定（`management.endpoint.health.show-details`，默认 `never`）。详情包含计数、状态和异常类名，从不包含消息或地址。探针的写法见 [Kubernetes](./kubernetes#probes)。

## 指标与追踪 {#metrics-and-traces}

把 `wow-observability` 放到类路径上。span 通过应用的 `OpenTelemetry` Bean 输出，例如 Spring Boot 带 SDK 及其导出器的那个，否则使用全局的，例如 Java agent 的。指标输出到应用的 Micrometer `MeterRegistry`，例如带 Prometheus 或 OTLP registry 的 Actuator，否则使用 Micrometer 的全局 registry。`wow.observability.enabled: false` 同时关闭两者。

**Span。** 命令和订阅的每个阶段都是一个 span，位于它的请求、日志条目或 Kafka 消息所携带的追踪中：

| 阶段 | Span |
| --- | --- |
| 准入 | `admit <context>.<aggregate>`，一个 internal span，是请求的 server span 的子 Span |
| 执行、决定、追加 | `execute`、`decide`、`append <context>.<aggregate>` |
| 订阅处理日志的一个条目 | `handle <subscription>` |
| 订阅处理主题的一条消息 | `process <topic>`，一个 consumer span |
| 定时器触发 | `fire <command type>` |
| 查询 | 每个查询一个 span |
| Kafka 发送 | 每次发送一个 producer span |

span 以属性的形式带着上下文、聚合、租户、命令和订阅（`wow.context`、`wow.aggregate.name`、`wow.tenant.id`、`wow.command.name`、`wow.subscription` 等），所以一条追踪可以跨服务跟随一条命令经过它的 Saga。

**指标。** 主要的指标，标签与 Span 的属性同名（`wow.context`、`wow.aggregate.name`、`wow.subscription`……）：

| 指标 | 类型 | 度量 |
| --- | --- | --- |
| `wow.command.duration` | Timer | 一条命令的执行，从接收到得出结果，按结果分 |
| `wow.command.refusals` | Counter | 在内核接收之前就结束的命令：拒绝、繁忙的车道或节点、不可用的存储 |
| `wow.command.rejections` | Counter | 被聚合拒绝的命令（带或不带事件），按拒绝的错误码：是业务结果，不是失败 |
| `wow.subscription.lag` | Timer | 从一次提交到订阅处理它 |
| `wow.subscription.backlog` | Gauge | 分区中位于订阅位置之后的条目数 |
| `wow.subscription.progress` | Gauge | 订阅的位置相对于日志头部的比例，0 到 1：重建的进度 |
| `wow.subscription.parked.aggregates` | Gauge | 订阅停放的聚合数 |
| `wow.subscription.paused.partitions` | Gauge | 订阅因系统性故障暂停的分区数 |
| `wow.subscription.throttled.partitions` | Gauge | 订阅因背压限流的分区数 |
| `wow.subscription.undecodable.messages` | Counter | 这个版本无法解码的主题消息 |
| `wow.reservation.losses` | Counter | 待定预留过期后被另一个所有者占用、已提交事实丢失的唯一键 |
| `wow.timer.delay` | Timer | 定时器在到期时间之后延迟多久触发 |
| `wow.query.duration` | Timer | 一个查询，从准入到结束 |
| `wow.query.limit.current` | Gauge | 存储当前的并发查询上限 |
| `wow.subscription.offset.gaps` | Counter | 另一个上下文主题上的偏移量缺口（[Kafka](./kafka#reading-other-contexts-streams)） |

`wow.node.sample-interval`（15 秒）是读取采样型 gauge（停放条目和订阅的位置）的频率。

**拒绝日志。** 被聚合拒绝的命令，以及被准入拒绝的命令，都以同一种形态记录在 logger `wow.command.rejection` 之下：拒绝是业务结果，记为 INFO；准入拒绝是准入的日常工作，记为 DEBUG。消息读起来是一句话，字段则是 SLF4J 的 key-value，与 Span 的属性同名：`wow.context`、`wow.aggregate.name`、`wow.command.name`、`error.type`（错误码）、`wow.request.id`，拒绝还带 `wow.with_events`。JSON 编码器（例如 Logback 的或 Elastic Common Schema 的）会把它们写成可检索的字段。调整这个 logger 的级别即可屏蔽拒绝日志，或设为 `DEBUG` 一并查看准入拒绝。

## 应当对什么报警 {#what-to-alert-on}

| 信号 | 含义 | 查看 |
| --- | --- | --- |
| 某个节点的就绪状态持续几分钟为 down | 某个存储不可用，或者某个节点失去了它的分区 | `/actuator/health/wow` |
| `wow.subscription.parked.aggregates` 大于 0 | 有聚合在等待修复和运维人员处理 | [ops 服务与控制台](./ops-console) |
| `wow.subscription.paused.partitions` 长时间大于 0 | 下游的存储或服务不可用 | 订阅的日志、存储 |
| `wow.subscription.backlog` 持续增长 | 某个订阅落后了 | 它的落后量、处理函数的延迟、节点的执行器 |
| `wow.timer.delay` 持续增长 | 定时器触发得晚了 | 节点的负载、定时器的存储 |
| `wow.subscription.offset.gaps` 大于 0 | 某个订阅跳过了数据，或停在已过保留期的数据上 | 主题的保留期 |
| `wow.reservation.losses` 大于 0 | 有唯一键被两个聚合同时依赖 | 指明该键及其持有者的错误日志；预留的存活时间 |
| `error.type=wow.command.busy` 的 `wow.command.refusals` | 节点在负载下拒绝命令 | 节点数、存储的延迟 |
