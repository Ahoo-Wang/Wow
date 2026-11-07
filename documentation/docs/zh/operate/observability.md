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
| `node` | 节点没有在运行：启动中、排空中或已停止；或者它遇到了[致命错误](#fatal-errors)，此后一直如此 | |
| `stores` | 某个日志，或写入需要的某个存储（存放日志的存储、某个聚合类型的主快照存储），持续失败了 `wow.health.stores.down-after`（15 秒），期间没有成功过 | 其中某个在 `wow.health.stores.window`（1 分钟）内失败过 |
| `secondaryStores` | 从不：写入路径之外的每个存储实例（额外的快照存储、查询或搜索存储）按与 `stores` 相同的规则变为 `DOWN`，但它的故障只降级它所服务的读取，不影响节点，所以就绪检查不包含它 | 它在 `wow.health.stores.window`（1 分钟）内失败过 |
| `ownership` | 它的租约存储不可用，或者在 `wow.health.ownership.handover-grace`（1 分钟）之后，节点仍没有拥有某个租约组中应得的任何分区 | 它在这么长时间内拥有的分区集合不是它应得的那份，或者租约存储最近失败过 |
| `topics` | 它读取的另一个上下文的某个主题仍在加入中（除非 `wow.health.topics.required: false`） | 在 `wow.health.topics.required: false` 时仍在加入中 |
| `subscriptions` | 从不 | 某个订阅落后了 `lag-degraded-at`（10,000）个条目，有 `parked-degraded-at`（100）个停放条目或比上一次采样时多，或暂停了某个分区 |
| `executor` | | 执行器的在途量达到其上限的 `saturation`（90%） |
| `liveness` | 节点卡住超过 `wow.health.liveness.stall-timeout`（3 分钟）：它的调度器没有运转，后端驱动调用的线程什么也不运行，分区的一次协调没有结束，或者在每个日志都有响应的情况下在途的命令没有完成。或者节点遇到了[致命错误](#fatal-errors)：此后直到进程结束。只有这一项会让存活探针失败 | |

降级的组件不会改变分组的状态：它的作用是让人看到并据此报警。存活探针从不因存储故障而失败，节点排空期间也不会失败，所以一次故障不会让所有节点同时重启。探针不能等待的东西（日志的头部、订阅的落后量、停放的条目），节点每隔 `wow.node.sample-interval`（10 秒）采样一次，所以探针能立即得到回答。同一次采样同时供健康与 gauge 使用，每个订阅按它的组和名字区分：每个上下文的 `snapshot` 订阅各自独立，有自己的停放计数，最多计到 `wow.node.max-parked`。

响应除了状态之外还显示什么，由 Actuator 自己的设置决定（`management.endpoint.health.show-details`，默认 `never`）。详情包含计数、状态和异常类名，从不包含消息或地址。探针的写法见 [Kubernetes](./kubernetes#probes)。

### 致命错误 {#fatal-errors}

节点自身工作遇到的 JVM 致命错误（`OutOfMemoryError`、`InternalError`、`UnknownError`）不是这项工作的失败：无论它出现在订阅的处理函数、定时器的发送、命令的执行，还是节点的某个循环里（租约的协调与续约、成员资格、某个上下文的追加队列、台账的发布器）。出现之后，JVM 的状态已不可信，因为堆是整个进程共用的，重试也可能再次遇到同样的错误，所以它既不重试，也不停放到失败台账。节点从此是致命的：

- `liveness` 与 `node` 一直为 `DOWN`，直到进程结束，不论节点处于什么状态：进程内没有任何东西能让它们恢复。它们的详情在 `fatal` 下给出第一个致命错误：遇到它的节点部分（`component`：`subscription`、`timer`、`executor`、`ownership`、`membership`、`append-queue`、`ledger`、`node`），它在运行什么（`name`：订阅及其组、上下文、租约组），`partition`，错误的类（`error`），以及距今多久（`fatalMillis`）；`liveness` 另给出遇到了几个（`fatalErrors`）。
- 第一个以 ERROR 记录一次，带调用栈；每一个都计入 `wow.node.fatal.errors`。
- 节点退出所有租约组，如同优雅关闭的第一步：离开成员并交出它的分区，让其他节点从检查点接手（停止的 lane 持有的条目在那里再处理一次，一如既往至少一次）。它的其他 lane 照常处理，直到交接把它们排空。交接本身也需要内存：超过 `wow.cluster.reconcile-timeout`（1 分钟）仍未完成、或者失败的交接，会让节点不再续约任何租约，租约过期后（`wow.cluster.lease-ttl`）由其他节点接手。网关不关闭，仍收到的命令转给分区的新属主。所以即使没有任何东西依据 liveness 行动，工作也会转移；进程结束时，宿主的停止会收尾。

`StackOverflowError` 不是致命错误。它只让出事的那一个线程展开栈，JVM 不受损，而且对同一输入一定会重现：栈溢出的处理函数与抛出 `NotImplementedError` 的一样失败，先重试，再停放。如果它是致命的，一个这样的条目会让接手其分区的每个节点依次退出。

用 `-XX:+ExitOnOutOfMemoryError` 运行 JVM，让进程中任何地方（包括请求中）的 `OutOfMemoryError` 立即结束进程，由编排器重新启动它。chart 的默认 `javaOpts` 与 Wow 构建的镜像都设置了它（[Kubernetes](./kubernetes#memory)）；对没有这样运行的 JVM，节点自身的规则是兜底。

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

采样型 gauge（停放条目和订阅的位置）与健康来自同一次采样，每隔 `wow.node.sample-interval`（10 秒）一次。

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
| `wow.node.fatal.errors` 大于 0 | 某个节点内存耗尽，或遇到了 JVM 的其他致命错误，已交出它的分区，等待重启 | 它的 ERROR 日志；堆的大小（[Kubernetes](./kubernetes#memory)） |
| `error.type=wow.command.busy` 的 `wow.command.refusals` | 节点在负载下拒绝命令 | 节点数、存储的延迟 |
