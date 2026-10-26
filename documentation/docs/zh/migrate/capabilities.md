---
title: V10 中的 9.x 能力
description: Wow 9.x 每项面向用户的能力在 V10 中的去向：保留、由别的机制替代、放弃，或计划在之后的 10.x 版本提供。
outline: deep
---

# V10 中的 9.x 能力

迁移之前，用这一页查清你的服务用到的每项 9.x 能力会怎样。每一行说明这项能力的去向：

- **保留**：V10 有它。
- **替代**：V10 用另一种方式提供它。说明一栏写明怎样提供；`wow-migrate` Skill 会改写用到它的代码。
- **放弃**：V10 有意不提供它。说明一栏写明该怎么做。
- **10.x**：10.0 里没有，计划在之后的 10.x 版本提供。

## 命令 {#commands}

| 9.x 能力 | V10 中 | 说明 |
| --- | --- | --- |
| 命令网关：发送、等待、执行 | 保留 | 基于协程；结果以命令结果的 flow 到达。 |
| Reactor 形态的命令网关 | 放弃 | 在协程里调用网关，或者在 Java 里通过客户端的 `CompletableFuture` 门面调用。处理函数仍可以返回 `Mono` 或 `Flux`。 |
| 等待阶段 `SENT`、`PROCESSED`、`SNAPSHOT`、`PROJECTED`、`EVENT_HANDLED`、`SAGA_HANDLED` | 替代 | 三个阶段 `ACCEPTED`、`COMMITTED` 和 `HANDLED(subscription)`，默认 `snapshot`。见[等待结果](../concepts/waits)。 |
| 等待链尾部请求头（`Command-Wait-Tail-*`） | 替代 | 多跳的 `Wow-Wait` 计划。 |
| 跨上下文等待 | 保留 | 信号直接发往发起节点。 |
| 用服务端推送事件返回结果 | 保留 | 每到达一跳一个事件。 |
| 请求 ID 幂等 | 保留 | 请求 ID 在每个租户和聚合类型下唯一；用于另一种命令类型时被拒绝。 |
| 布隆过滤器幂等检查 | 替代 | 每个缓存的聚合保存它最近的请求（从日志推出）；其余由存储回答。 |
| 命令改写器（`CommandBuilderRewriter`） | 替代 | 准入时的命令转换器；它不能改变命令的身份。 |
| 挂在另一个聚合上的命令（`@AggregateRoot(commands)`） | 替代 | `@MountedCommand`；不指明聚合的命令用 `@Unaddressed`。 |
| 命令校验（Jakarta Bean Validation） | 替代 | 在准入时校验：命令类上的 Jakarta 约束，对每条命令（HTTP、进程内、Saga、其他上下文）检查，回答 `wow.command.invalid`（400）。需要状态的规则是拒绝。 |
| `@VoidCommand` | 放弃 | 发送命令，等待不超过 `ACCEPTED`。 |
| 自定义路由（`@CommandRoute`、`@AggregateRoute`） | 放弃 | 路由从 Manifest 推出：`POST /{context}/{aggregate}/{id}/{command}`，创建命令不带 `{id}`。 |
| `Command-*` 请求头 | 替代 | `Wow-Wait`、`Wow-Wait-Timeout`、`Wow-Request-Id`、`If-Match`、`Wow-Scope`、`Wow-Space`。 |
| 请求头别名 | 放弃 | 在你的网关里改写请求头。 |
| 从请求头复制的请求事实 | 保留 | 一份配置的请求头列表，加上已认证的应用、远程地址和 User-Agent。 |
| 命令的操作人 | 保留 | 每次提交都会记录。 |
| 全局和按聚合的 ID 生成、CosId | 保留 | 决定函数也能通过决定作用域使用。 |
| 命令批处理 | 替代 | 分区属主批量提交。 |
| 额外的命令结果（`CommandResultAccessor`） | 替代 | 决定函数通过决定作用域返回额外结果；它们不存储，所以重复请求拿不到。 |
| 跨聚合的唯一键（`PrepareKey`、`@PreparableKey`） | 替代 | 通过决定作用域使用预留键，存在日志旁边；数据工具导入 9.x 已确认的键。 |
| 查找 `PrepareKey`、它的值、它的存活时间 | 10.x | 计划提供只读的预留查找。存活时间放弃：定时一条释放该键的命令。 |
| 期望版本 | 保留 | `If-Match`；不匹配时返回 412。 |
| 命令总线：内存、本地优先、Kafka | 替代 | 默认经集群通道分区路由；需要持久摄入时按聚合类型经 Kafka 路由。 |
| Redis 命令总线 | 放弃 | — |
| 单一命令端点（`/wow/command/send`） | 替代 | 每条命令一个路由。 |
| 命令过滤器与埋点 | 替代 | 一个观测端口，由 OpenTelemetry 模块实现。 |
| 错误码到 HTTP 状态的映射 | 保留 | 用 `@ErrorCode` 声明，或按错误码配置。 |
| 领域异常转换为 `ErrorInfo` | 替代 | 决定结果返回一个带已声明错误码的拒绝。 |

## 领域模型 {#domain-model}

| 9.x 能力 | V10 中 | 说明 |
| --- | --- | --- |
| 核心注解 | 保留 | 同一个包里一套更小、重新设计的注解。 |
| 事件修订号与升级器（`EventUpgrader`） | 替代 | `@Revision`、`@Upgrade` 和 `@Alias`。 |
| 丢弃的事件（`DroppedEvent`） | 替代 | 退役事件，列在聚合上。 |
| 固定租户（`@StaticTenantId`） | 替代 | 在配置里为聚合类型指定固定租户。 |
| 固定聚合 ID（`@StaticAggregateId`） | 放弃 | — |
| 所有者和空间策略（`@AggregateOwner`、`@Spaced`） | 替代 | 按聚合类型声明归属（所有者或租户）；作用域来自调用方。见[身份、租户与访问](../concepts/identity)。 |
| 命令字段上的身份注解（`@AggregateId` 等） | 放弃 | 聚合来自路由或消息；命令体只承载业务数据。 |
| 从状态推出标签（`StateAggregateTagsExtractor`） | 替代 | 状态上的 `@Tags`。 |
| 打标签 | 保留 | 生命周期命令 `ApplyTags`。 |
| 生命周期命令：删除、恢复、转移所有者或空间 | 保留 | — |
| `@EntityObject`、`@ValueObject`、`@Order` | 放弃 | — |
| `@OnError` | 放弃 | 从决定函数返回拒绝；把补偿放进 Saga。 |
| 用 Java 写的领域模型 | 保留 | 编译器读取 Java 源码。面向 Java 的测试 API 计划在 10.x 提供。 |
| 注入命令函数的领域服务 | 保留 | 通过生成的模型工厂绑定一次。 |
| 错误事件（`IgnoreSourcing`） | 替代 | 一个拒绝，或者一个什么都不改的演化函数。 |
| 状态类独立的聚合 | 保留 | — |
| `@AfterCommand`、`@Blocking`、`@CreateAggregate`、`@AllowCreate` | 保留 | — |
| 快照系统字段 `eventId`、`snapshotTime`、`eventTime` | 替代 | `firstEventTime`、`lastEventTime`、`operator`、`firstOperator` 以及其他快照字段。 |

## 事件与存储 {#events-and-storage}

| 9.x 能力 | V10 中 | 说明 |
| --- | --- | --- |
| MongoDB 事件存储 | 保留 | 一等的日志后端；另一个是 PostgreSQL。 |
| Redis 事件存储与快照存储 | 放弃 | 数据工具只读取 9.x 的 MongoDB 存储：先在 9.x 中把 Redis 数据迁到 MongoDB。 |
| Elasticsearch 事件存储 | 放弃 | Elasticsearch 接收快照和事件流的副本用于检索，从不保存日志。迁移之前先把 9.x 数据迁到 MongoDB。 |
| MongoDB 与 Elasticsearch 快照存储 | 保留 | 还有 PostgreSQL。 |
| 快照策略（每 n 个版本） | 替代 | 每个版本都写快照，按聚合批量写入。 |
| 为聚合关闭快照 | 保留 | V10 新增。 |
| 按聚合的存储（`storage-routing`） | 保留 | 命名的存储，按聚合为日志和快照选择。 |
| 重新生成快照，单个或批量 | 保留 | 一条系统命令；状态修订号提高时自动重建。 |
| 历史状态：按版本、按时间、轨迹 | 保留 | `GET …/state?version=`、`?at=` 和 `…/state/trace`。 |
| 读取一个聚合的事件流 | 保留 | `events` 查询模型。 |
| 恢复或跳过失败的处理 | 替代 | 停放与失败台账，通过 ops 服务操作。 |
| 定向重放一个版本区间；批量重发状态事件 | 10.x | 在此之前，恢复停放的聚合，或重建整个订阅。 |
| 领域事件总线与状态事件总线 | 替代 | 日志就是总线；其他上下文读取 Kafka 上发布的事件流。见[日志就是总线](../concepts/event-log)。 |
| 创建集合与索引 | 保留 | 由存储的 DDL 设置管理。 |

## 处理器与订阅 {#processors-and-subscriptions}

| 9.x 能力 | V10 中 | 说明 |
| --- | --- | --- |
| Saga、投影、事件处理器 | 替代 | `@Saga`、`@Projection` 和 `@Reactor`；每个处理函数是一个独立的订阅。 |
| `@OnStateEvent`、`@OnMessage` | 替代 | `@OnEvent`，接收状态事件，或其中一种事件类型。 |
| 处理另一个上下文的事件 | 保留 | 通过它发布的事件流。 |
| 把另一个上下文的事件解码成共享的类 | 保留 | 把对方上下文的 api jar 放在类路径上。 |
| `@Retry`：次数、退避、超时 | 替代 | V10 的 `@Retry`，可按订阅配置，之后是停放聚合的长周期重试。 |
| 可恢复与不可恢复的异常类 | 保留 | 在 `@Retry` 上声明，另有可插拔的失败分类器。 |
| 订阅从哪里开始 | 保留 | V10 新增：在处理函数上声明或通过配置指定。 |
| 处理器过滤器、拦截器、`@Order` | 放弃 | — |
| 跳过无法解码的 Kafka 消息 | 10.x | V10 会停放它们。 |
| 在处理函数里读取其他聚合的状态或查询 | 替代 | 类型化的查询 API，处理函数可以使用。 |

## 查询 {#queries}

| 9.x 能力 | V10 中 | 说明 |
| --- | --- | --- |
| 单条、列表、分页、游标、计数和聚合查询 | 保留 | — |
| 过滤表达式、相对时间、元数据过滤、聚合窗口 | 保留 | — |
| `Condition` 协议 | 放弃 | 使用过滤表达式；Skill 会改写条件。 |
| Kotlin 查询 DSL 与生成的 `<State>Properties` | 保留 | — |
| 查询字段注解（`@Sensitive`、`@QueryAlias`、`@QueryReference` 等） | 保留 | — |
| 在查询和状态读取中脱敏敏感字段 | 保留 | — |
| ABAC 查询策略、CoSec 的 `abac.` 属性 | 保留 | 默认关闭。 |
| 查询策略 SPI 与作用域 | 保留 | — |
| MongoDB 与 Elasticsearch 上的快照查询 | 保留 | 还有 PostgreSQL。 |
| 事件流查询与跨聚合的聚合 | 保留 | 快照、事件和数据集共用一个查询引擎。 |
| HTTP 查询路由（`snapshot/list` 等） | 替代 | `POST /{context}/{aggregate}/{model}/query`，查询种类写在请求体里。 |
| 流式返回列表 | 保留 | 可从停下处续读的服务端推送事件。 |
| 查询 schema 路由与刷新 | 替代 | 对查询路由发 `GET`，描述模型能做什么；schema 来自 Manifest。 |
| 数据集：目录、网关、路由 | 保留 | — |
| 数据集注册（dataset-registry） | 替代 | 分析目录，一个平台服务。 |
| ClickHouse 数据集后端 | 保留 | — |
| 查询预算、有上限的计数、游标 | 保留 | 另有每个存储一个查询舱壁。 |

## 消息、BI 与外部存储 {#messaging-bi-and-external-stores}

| 9.x 能力 | V10 中 | 说明 |
| --- | --- | --- |
| Kafka 传输 | 替代 | 发布事件流的 Sink、对其他上下文事件流的订阅、命令主题与回执。 |
| Kafka 主题前缀与主题管理 | 保留 | — |
| 分开的生产者与消费者设置 | 10.x | 在此之前只有一个 `wow.kafka.properties`。 |
| BI 同步脚本（`/wow/bi/script`） | 替代 | ClickHouse Sink 直接写数仓，并为数据集提供展平的视图。 |
| BI 表中状态的嵌套展开 | 10.x | — |
| 9.x 主题的外部消费者 | 放弃 | 切换时把每个消费者改到 V10 发布的事件流。没有 9.x 格式的 Sink。 |

## 租户与安全 {#tenancy-and-security}

| 9.x 能力 | V10 中 | 说明 |
| --- | --- | --- |
| CoSec 集成 | 保留 | — |
| 租户与所有者的路由前缀 | 放弃 | 身份来自调用方；被允许在别处行事的调用方在 `Wow-Scope` 里指明作用域。 |
| 把 CoSec 主体传播到消息 | 替代 | 操作人和会传播的请求事实，随 Saga 发出的每条命令一起传递。 |
| 跨租户的上下文 | 保留 | — |

## 契约、OpenAPI 与 HTTP {#contract-openapi-and-http}

| 9.x 能力 | V10 中 | 说明 |
| --- | --- | --- |
| `wow-metadata.json` 与 `/wow/metadata` | 替代 | Manifest 与 `GET /wow/manifest`。见[一份契约](../concepts/contract)。 |
| JSON Schema 生成 | 替代 | 在构建期依据 Jackson 自己对类的看法生成 schema。 |
| 注册自定义 Jackson 模块 | 放弃 | Wow 的 JSON 注册一组固定的模块，所以线上形式不依赖宿主。 |
| OpenAPI 文档 | 保留 | 从 Manifest 推出；也有对应的 Gradle 任务。 |
| springdoc 集成 | 保留 | — |
| 错误体（`ErrorInfo`） | 替代 | 带错误码的 `application/problem+json`（RFC 9457）。 |

## 可观测性 {#observability}

| 9.x 能力 | V10 中 | 说明 |
| --- | --- | --- |
| OpenTelemetry 追踪 | 保留 | — |
| 指标 | 保留 | — |
| 健康指示器 | 保留 | V10 新增：Actuator 的就绪与存活分组。 |

## 测试 {#testing}

| 9.x 能力 | V10 中 | 说明 |
| --- | --- | --- |
| 聚合测试 | 保留 | V10 的测试 DSL，制品 `wow-test`。 |
| `fork`、`givenState`、按类型的 `expectError` | 保留 | — |
| Saga、投影与反应器测试 | 保留 | — |
| Java 测试 API | 10.x | 在此之前测试 DSL 只支持 Kotlin。 |
| `TestValidator` | 放弃 | — |
| TCK 与 Testcontainers 测试夹具 | 保留 | — |
| `wow-mock` | 放弃 | — |

## 平台服务 {#platform-services}

| 9.x 能力 | V10 中 | 说明 |
| --- | --- | --- |
| 补偿服务 | 替代 | 基于各服务失败台账的 ops 服务和控制台。数据工具导入未解决的执行。 |
| 长周期自动重试 | 保留 | — |
| 恢复、跳过、标记为可恢复、应用重试规格 | 保留 | — |
| 修改执行的函数；强制准备 | 放弃 | — |
| 企业微信 webhook | 保留 | 另有通用 webhook。 |
| 通知中的控制台链接与模板 | 10.x | — |
| 控制台里的堆栈信息 | 放弃 | — |
| 控制台里的每日趋势分析 | 10.x | — |
| 控制台里可编辑的看板 | 放弃 | — |
| 视图存储 | 保留 | — |
| 数据集注册与分析服务 | 替代 | 分析目录。 |

## 扩展模块 {#extension-modules}

| 9.x 能力 | V10 中 | 说明 |
| --- | --- | --- |
| `wow-models` | 保留 | — |
| `wow-cocache` | 放弃 | — |
| `wow-apiclient` | 替代 | 类型化的命令与查询客户端：对服务自己的上下文在进程内运行，对其他上下文经 HTTP。见[上下文之间](../concepts/cross-context#typed-clients)。 |
| CosId | 保留 | — |
| Spring 集成与 starter 功能 | 替代 | 一个 Spring Boot 宿主模块，按类路径装配各个后端。 |

## 配置与运维 {#configuration-and-operations}

| 9.x 能力 | V10 中 | 说明 |
| --- | --- | --- |
| `wow.*` 配置 | 替代 | 新的键。未知的 `wow.*` 键会让服务拒绝启动，并指明它。 |
| 优雅关闭 | 保留 | — |
| 没有持久存储时快速失败 | 保留 | — |
| Maven 构建 | 放弃 | V10 的编译期绑定需要 Gradle。 |
| TypeScript 客户端生成器 | 保留 | 它读取 Manifest。 |

## TypeScript {#typescript}

| 9.x 能力 | V10 中 | 说明 |
| --- | --- | --- |
| 命令客户端与经 SSE 等待 | 保留 | — |
| 快照、事件、数据集与描述客户端 | 保留 | — |
| `Condition` 与本地化的操作符 | 放弃 | — |
| 按所有者路径读取状态 | 放弃 | 身份来自调用方。 |
| React 查询 hooks（`wow-react`） | 保留 | — |
| 视图引擎与视图存储客户端 | 保留 | — |

## 数据迁移 {#data-migration}

| 9.x 来源 | V10 中 | 说明 |
| --- | --- | --- |
| MongoDB 事件存储：导入、追尾、比对、快照 | 保留 | 见[迁移数据](./data)。 |
| Redis 事件存储 | 放弃 | 先在 9.x 中迁到 MongoDB。 |
| Elasticsearch 事件存储 | 放弃 | 先在 9.x 中迁到 MongoDB。 |
| `PrepareKey` 的键 | 保留 | 作为已确认的预留导入。 |
| 未解决的补偿执行 | 保留 | 作为停放的聚合导入。 |
