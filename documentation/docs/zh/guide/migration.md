---
title: 迁移指南
description: 选择 Wow 迁移路径，并严格区分源码、运行时、存储、数据与生产证据。
---

# 迁移指南

“迁移”不是单一兼容结论。任何变更前都要分别记录五个范围：

| 范围 | 要回答的问题 | 典型证据 |
|---|---|---|
| 源码 | 应用能否针对固定目标 API 编译？ | compiler、单测、生成元数据 diff |
| 运行时 | 目标生命周期/配置能否启动、ready、处理工作并正确停机？ | 集成测试、readiness、优雅停机 trace/log |
| 存储 | 目标能否读写精确的 event、snapshot、Redis/Mongo 与 BI 布局？ | tag-to-tag 契约 diff、离线 inventory、格式测试 |
| 数据 | 数量、版本、request ID、索引、回放状态与读模型是否对账？ | manifest、checksum、代表性/全量对账 |
| 切换 | 已审批生产 revision 是否真实运行、可观测且有演练过的回滚？ | deployment digest/revision、真实流量、告警与回滚证据 |

本地 build 绿色只能关闭源码门禁，不能关闭其他四项。

## 选择迁移路径

| 当前系统 | 主路径 | 原因 |
|---|---|---|
| CRUD/事务脚本/直接写表，没有 Wow 历史 | [传统架构迁移](./migration/traditional-architecture.md) | 建立 command、aggregate、event、导入与流量所有权 |
| 精确 Wow v6 tag | [Wow v6 迁移到 v8](./migration/v6-to-v8.md) | 比较固定平台/API/存储契约，并在需要时执行数据硬切换 |
| Wow v8 上有自定义 dispatcher/message-bus/Spring 生命周期 owner | [运行时编排迁移](./migration/runtime-orchestration.md) | 把生命周期源码迁移到统一 `WowRuntime`；它不自动等于数据迁移 |
| Wow 9.2.x | [从 9.2 升级到 9.3.0](#从-9-2-升级到-9-3-0) | 重新编译，迁移已删除与已弃用的 API，先升级处理节点再升级只做网关的服务；REST、存储与线上格式不变 |
| Wow v8.16.x 使用旧查询 API 或 `SnapshotRepository` | [V9 查询迁移](./query/v9-query-migration.md) | 迁移 Gateway/Backend、Filter、Mask、SnapshotStore 与 Spring Bean 名 |
| TypeScript 客户端使用 `fetcher-wow`、`fetcher-generator` 或 `fetcher-react` 的 Wow Hook | [从 Fetcher 包迁移](./typescript/migration.md) | 换用 `wow-client`、`wow-generator` 和 `wow-react`，并重新生成客户端代码 |

不要把首次采用 Wow 与 v6→v8 升级混成一次无法区分的发布。每个变更窗口都应选择一个 bounded context
及精确 source/target version。

## 文档边界

| 页面 | 负责 | 不负责 |
|---|---|---|
| 传统架构迁移 | 领域边界、历史导入、shadow 追平、读写切换 | Wow 版本/平台升级假设 |
| v6→v8 | 固定 Gradle/平台矩阵、源码破坏、存储格式、数据切换 | 重设计全部领域 |
| 运行时编排 | `RuntimeComponent`、message receiver admission、Spring 生命周期所有权、停机 | 除非其他章节明确要求，否则不转换 event/snapshot 格式 |
| V9 查询迁移 | 查询 Gateway/Backend、Filter/Mask、SnapshotStore 命名和 Condition 迁移窗口 | 部署或生产切换证明 |
| 运行时生命周期 | 迁移后的稳定语义 | 迁移步骤本身 |

[Release Notes](https://github.com/Ahoo-Wang/Wow/releases) 描述版本变更；选定 tag 的源码、测试与 build 文件
才是精确契约。`main` 只能作为当前目标的证据。

## 共同完成门禁

只有当前门禁具备可复现证据后才能推进：

1. **范围**：固定 bounded context、source tag、target tag、dataset/store、负责人和明确排除项。
2. **基线**：源码测试绿色；盘点 event/snapshot/key/collection/read model；创建并验证可恢复 backup。
3. **演练**：在生产形态的隔离副本上运行同一迁移工具与 manifest。
4. **验证**：编译、启动、处理、回放、对账并优雅停止目标版本；覆盖失败路径。
5. **切换**：关闭 admission、排空旧 writer、只迁移一次、先启动一个目标实例，再转移受控流量。
6. **观察**：验证 metric/trace、backend version、projection/BI lag、告警与业务不变量。
7. **关闭**：回滚窗口结束后才移除旧 writer、旧数据与临时 bridge。

回滚计划必须区分“目标版本第一次生产写入之前”和“之后”。新存储格式已经写入时，只恢复旧 binary
不是回滚。

## 从 9.2 升级到 9.3.0

9.3.0 重做了写侧、传输与 API 分层。REST 路由与请求体、存储格式、消息 JSON、Kafka 主题与消费组都不变，所以滚动升级期间 9.2.x 与 9.3.0 节点可以共用一个集群。Kotlin API 有变化：9.3.0 **不保留二进制兼容垫片**（只为让 9.2 字节码继续链接而保留的声明），被替换的面向应用的 API 保留**一个 `@Deprecated` 周期**，10.0.0 删除，清单见[兼容性债务](https://github.com/Ahoo-Wang/Wow/blob/main/docs/compat-debt.md)。只有后端、传输或框架实现者才会接触的扩展点 SPI 直接修改，不经弃用。9.3.0 发布说明（9.3.0 发布后见 [Releases 页面](https://github.com/Ahoo-Wang/Wow/releases)）逐条列出每项变化及其 PR。

下面各节按应用遇到的可能性排序：前四节每个应用都要看，有 REST 客户端的应用还要看第五节，后面的主要涉及自定义扩展。

### 升级之前

1. **还在更早的 9.2.x 上的，先升级到 9.2.4**：混部门禁（`MixedVersionClusterTest`）让已发布的 9.2.4 镜像与 9.3 构建共用一个 MongoDB 和一个 Kafka、加入相同的消费组，双向验证命令、快照、Saga 与本地优先副本。
2. **所有应用和库都基于 9.3.0 重新编译。** 基于 9.2 构建的库可能无法链接（见[重新编译与弃用的调用](#重新编译与弃用的调用)）。
3. **先升级处理节点，再升级只做网关的服务。** 处理节点承载聚合并有 `EventStore`；只做网关的服务只发送命令。9.3 的网关服务会放行请求 ID 布隆过滤器的命中，而 9.2 的处理节点在处理函数之前不查请求 ID，所以顺序反过来时，重发的命令会再执行一次处理函数，直到追加时被拒绝。
4. **混部窗口尽量短。** 两个版本同时运行时，同一个请求的结果可能取决于由哪个节点处理：

| 情形 | 9.2 节点 | 9.3 节点 |
|---|---|---|
| 请求体或请求头与路由确定的租户或所有者矛盾 | 接受（以请求体为准） | `400 IllegalArgument` |
| SSE 流或批量结果中的意外异常 | `BadRequest`，带异常消息 | `InternalServerError`，"Unexpected server error" |
| 用原请求 ID 重发的创建命令，超出请求 ID 窗口 | `DuplicateAggregateId` | `DuplicateRequestId` |
| 追加失败的命令的 `aggregateVersion`（处理节点） | N+1（未存储） | N（已提交） |
| 失败后重试的 Saga 创建命令 | 每次尝试都创建新聚合（随机 ID） | 重试以 `DuplicateRequestId` 被拒绝；一次 9.2 尝试加一次 9.3 尝试仍可能创建两个聚合 |

### 重新编译与弃用的调用

下列声明只为让旧字节码或 9.2 扩展代码继续链接而保留，现已删除。用到它们的源码必须迁移：

| 已删除 | 改用 |
|---|---|
| 在总线中覆盖 `MessageBus.receive(subscription)` | 实现 `receiver(subscription)`，它现在是抽象方法 |
| `MessageBus.runtimeReceiver(subscription)` | `receiver(subscription.copy(runtimeOwned = true))`；总线按 `subscription.runtimeOwned` 分支 |
| 不带 `runtimeOwned` 的 `MessageSubscription` 构造函数与 `copy`（仅 JVM） | 带 `runtimeOwned` 的构造函数与 `copy`（默认 `false`） |
| 不带 `retentionOptions` 的 Redis 总线构造函数（仅 JVM） | 主构造函数（`retentionOptions` 与 `failurePolicy` 有默认值） |
| `BindingError`、`AggregationGroup.Terms`、`AggregationGroup.Histogram` 的 9.1 构造函数（仅 JVM） | 主构造函数 |
| `CoSecCommandBuilderExtractor`、`CoSecQueryRequestScope` | 由 `CoSecAutoConfiguration` 注册为 `IdentityHeaderAliases` Bean 的 `CoSecIdentityHeaders.ALIASES` |
| `Flux<AggregateId>.toBatchResult(afterId)`、`ResendStateEventHandler.handle(afterId, limit)` | `toBatchResult(afterId, request, exceptionHandler)`、`resend(afterId, limit)` |
| 非 Bean 的 `WebFluxAutoConfiguration.commandMessageExtractor`、`queryRequestScope`、`commandRouterFunction`、`pointReadAdmission` 重载，`CoSecAutoConfiguration.coSecCommandBuilderExtractor` / `coSecQueryRequestScope`，三参数的 `OpenAPIAutoConfiguration.routerSpecs` | 同名的 `@Bean` 方法 |

下列面向应用的调用在 9.3 中仍可编译，但已弃用，10.0.0 删除：

| 已弃用 | 改用 |
|---|---|
| `@AggregateRoute(spaced = …, owner = …)`、`AggregateRoute.Owner` | `@Spaced`、`@AggregateOwner(OwnerPolicy.…)`（见下一节） |
| `AggregateRouteMetadata.owner` 及其接收 `AggregateRoute.Owner` 的主构造函数 | `ownerPolicy` 与接收 `OwnerPolicy` 的构造函数 |
| 调用 `bus.receive(subscription)` | `receiver(subscription).openedMessages()` |
| `ServerRequest.getTenantId(aggregateMetadata)`、`getTenantIdOrDefault(aggregateMetadata)` | `identity(aggregateMetadata).tenantId()`（`?: TenantId.DEFAULT_TENANT_ID`） |
| `ServerRequest.getOwnerId()` | `identity(aggregateMetadata).ownerId()`（以聚合 ID 为所有者的聚合会退回 `{id}`） |
| `ServerRequest.getSpaceId()`、`getSpaceId(aggregateRouteMetadata)` | `identity(aggregateMetadata).spaceId()`（非空间化聚合为 `null`） |
| `ServerRequest.getAggregateId()` 及其两个 `AggregateRoute.Owner` 重载 | `identity(aggregateMetadata).aggregateId()`（按聚合的所有者策略） |
| `RecoverableExceptionRegistrar.register`、`unregister`、`getRecoverableType`（静态调用；Java 经 `.Companion` 调用，9.2 的 `INSTANCE` 已不存在） | `RecoverableExceptionRegistry.DEFAULT` 的同名方法，或 `RecoverableExceptionProvider` |
| `Throwable.toResponseEntity()`、`ErrorInfo.toServerResponse()` | `WebFluxErrorStrategy.toServerResponse`，或 `RequestExceptionHandler` Bean |

`identity(…)` 即 `me.ahoo.wow.webflux.route.identity.identity`；它返回的 `RequestIdentity` 按路由的规则读取每个身份字段（含请求头别名），与内置命令、查询处理器完全一致。弃用的读取函数都委托给它，所以它们同样拒绝路由声明的空白身份路径变量（400），并执行[客户端可见的请求变化](#客户端可见的请求变化)中的冲突检查。

### 聚合策略移出 `@AggregateRoute`

`@AggregateRoute(spaced, owner)` 已弃用，改为在聚合上声明 `@Spaced` 与 `@AggregateOwner(OwnerPolicy.…)`；没有新注解时仍读取旧属性，所以每个聚合的实际策略不变。见[从 `@AggregateRoute(spaced, owner)` 迁移](./domain/aggregate.md#从-aggregateroute-spaced-owner-迁移)。

不一致的声明不再由其中一处静默生效：

- 同一个类上 `@Spaced` / `@AggregateOwner` 与 `@AggregateRoute(spaced, owner)` 的值不同，或 `@StaticTenantId` 与 `@BoundedContext.Aggregate`、手写 `wow-metadata.json` 中的 `tenantId` 不同，会在启动时（`IllegalStateException`）和编译时（KSP）失败，并给出两边的值。在 api 包声明租户、在 domain 包声明 `@StaticTenantId` 的拆分方式同样会被检查。
- classpath 上两个 `wow-metadata.json` 不一致（同一聚合的租户或类型不同，同一上下文的别名不同）时启动失败，并给出两个资源的 URL；9.2 记录错误日志并丢弃第二个资源，结果取决于 classpath 顺序。无法解析的资源仍然记录日志并跳过。

### 聚合测试运行生产内核

聚合测试 DSL（`AggregateSpec`、`aggregateVerifier`）让每条命令经过生产环境的管道与内核。API 不变，但依赖旧 DSL 自身行为的断言会变：没有历史的非创建命令以 `NotFoundResourceException` 失败；已有 given 事件后的创建命令在追加时以 `DuplicateAggregateIdException` 失败；不返回任何结果的命令函数通过，没有事件流；given 事件会被存储，每一步都重新加载状态；失败命令的 `domainEventStream == null`。同一 given 阶段的并列 `whenCommand` 仍互相独立，与 9.2 相同。可恢复的失败立即报告，不重试。全部差异见[测试套件的表格](./test-suite.md#与生产相同的管道)。

### 客户端可见的请求变化

- **拒绝自相矛盾的身份。** 命令体中的 `@TenantId` / `@OwnerId`，或请求头 `Command-Tenant-Id` / `Command-Owner-Id`，与路由确定的租户或所有者（静态租户、`{tenantId}`、`{ownerId}`，或以聚合 ID 为所有者的聚合的 `{id}`）不同时，返回 `400 IllegalArgument`；对静态租户发送的租户请求头仍被忽略。在每个请求上都发送同一个全局 `Command-Owner-Id` 或 `Command-Tenant-Id` 的客户端或网关，须在路径已给出该值时去掉这个请求头。命令体中空白的 `@OwnerId` 只在以聚合 ID 为所有者、所有者来自 `{id}` 的聚合上视为没有值；对 `{ownerId}`，以及空白的 `@TenantId` 对静态租户或 `{tenantId}`，都算矛盾。见[请求身份](./open-api.md#请求身份)，那里还说明了从 `{id}` 取得所有者与百分号编码 ID 的情况。
- 事件加载、补偿、重新生成快照与追踪路由上空白的 `{id}` 与其他路由一样返回 400；空白的 `CoSec-Space-Id` / `CoSec-Request-Id` 视为不存在。
- SSE 错误事件或批量结果中的意外异常与 JSON 路由一样，是 `InternalServerError` 加 "Unexpected server error"，不再是带异常消息的 `BadRequest`。见[错误处理](./extensions/webflux.md#错误处理)。
- 追加失败的命令，其 `CommandResult` 与等待信号报告已提交的 `aggregateVersion` N，不再是未存储的 N+1。版本冲突之后，`@OnError` 看到的状态可能比 N 新（N+k），而结果仍报告 N。
- 用创建聚合时的请求 ID 重发的创建命令，在任何情况下都报告 `DuplicateRequestId`，包括超出请求 ID 窗口时（9.2 的缺陷：它在那里报告 `DuplicateAggregateId`，但这个请求是重放）。HTTP 状态仍是 400。使用其他请求 ID 的创建仍是 `DuplicateAggregateId`。

### 命令过滤器改为固定管道

命令侧不再有过滤器链。`DefaultCommandHandler` 按固定顺序执行处理、确认、领域事件与状态事件发布以及 `PROCESSED` 报告（见[命令处理管道](./command/internals/pipeline.md#bus-到-dispatcher)）。`ExchangeFilter<ServerCommandExchange<*>>` Bean 不再被调用；上下文里仍有这样的 Bean（或带 `@FilterType(CommandDispatcher::class)` 的 `ExchangeFilter`）时，starter 启动失败，错误信息给出 Bean 名称和下列替代方式：

| 已删除 | 改用 |
|---|---|
| 用于追踪、指标或日志的 `CommandFilter`，或带 `@FilterType(CommandDispatcher::class)` 的 `ExchangeFilter` | `CommandInstrumentation` Bean；`around(exchange, handling)` 包住每条命令的处理，必须原样返回结果 |
| 检查或拒绝命令的命令过滤器 | 命令上的 `CommandValidator` / Jakarta 校验（在网关处执行），或在命令函数中检查 |
| 响应已提交事件的命令过滤器 | 事件处理器、Saga 或投影 |
| `TraceAggregateFilter`（OpenTelemetry） | 由 starter 注册的 `TraceCommandInstrumentation`；span 名称与属性不变 |
| `AggregateProcessorFilter`、`SendDomainEventStreamFilter`、`SendStateEventFilter`、`ProcessedNotifierFilter`、`DefaultCommandHandler(chain, errorHandler)` | `DefaultCommandHandler(serviceProvider, aggregateProcessorFactory, domainEventBus, stateEventBus, commandWaitNotifier, instrumentations, requestIdChecker, errorHandler)`（`@InternalWowApi`） |
| `CommandHandler.handle(exchange)` | `CommandHandler.handle(exchange, aggregateMetadata)` |
| `ServerCommandExchange.setAggregateMetadata` / `getAggregateMetadata` / `setAggregateProcessor` / `getAggregateProcessor` | 处理器以参数接收 metadata |
| Starter Bean `aggregateProcessorFilter`、`sendDomainEventStreamFilter`、`commandFilterChain`、`sendStateEventFilter`、`processedNotifierFilter`、`traceAggregateFilter` | `commandHandler`、`traceCommandInstrumentation` |

### 命令内核与失败的命令

命令仍与 9.2 一样先决定、再应用事件、再追加，现在三者是一个原子单元（见[决定、应用、再追加](./command/internals/pipeline.md#决定、应用、再追加)）：

- 9.2.3 已经是先应用事件再追加；变化在失败路径上。溯源函数抛出异常或追加失败时，命令失败，不存储也不发布任何内容，不发送 `StateEvent`；应用了一半的实例被丢弃、绝不复用，版本停在 N。
- `@OnError` 在已提交的状态上执行：失败的尝试应用了未存储的事件时，先重新加载聚合，创建命令则由工厂新建聚合（9.2 给它的是已应用这些事件的状态）。重新加载也失败时跳过 `@OnError`，记录 ERROR 日志，返回原始错误，并把加载错误作为 suppressed 附上。
- 追加失败时，`CommandResult` / 等待信号报告已提交的 `aggregateVersion` N。版本冲突之后，`@OnError` 重新加载的状态可能更新（N+k）。
- `VersionAware.version` 在事件流的全部溯源函数执行完之后才设置：读取 `this.version` 的溯源函数看到的是之前的版本。

每个聚合类型在启动时编译一次：

- 处理函数以父类或接口类型作参数声明时，命令现在由最近的这类处理函数处理，不再作为未定义命令失败。after-command 与 `@OnError` 函数仍按命令自身的类型匹配。重复的处理函数，以及解析不到值的非空注入参数，会记录 WARN。
- 返回 `Flow` 的函数在返回 flow 之前抛出的异常不再包成 `InvocationTargetException`，原样传出；命令、事件、Saga 与投影函数都是如此。
- 删除 `StateAggregateMetadata.toMessageFunctionRegistry(stateRoot)`；通过 `StateAggregate.onSourcing(eventStream)` 溯源状态。
- 删除各响应式访问器类（`SimpleMonoFunctionAccessor`、`SyncMonoFunctionAccessor`、`FluxMonoFunctionAccessor`、`PublisherMonoFunctionAccessor`、`FlowMonoFunctionAccessor`、`SuspendMonoFunctionAccessor`、`BlockingMonoFunctionAccessor`、`AbstractMonoFunctionAccessor`）；改用 `KFunction.toMonoFunctionAccessor()` 或 `MonoMethodAccessorFactory.create(function)`。`toBlockable` 移到 `BlockableKt` 门面类（影响 Java 调用方）。

### 命令网关与 request-ID 检查

- 处理命令的节点在处理函数执行之前，用自己的布隆过滤器和 `EventStore` 再查一次请求 ID。没有 `EventStore` 的节点所用的 `NoopRequestIdExistenceChecker` 现在回答“不存在”，不再回答“已存在”。因此只做网关的服务不再因布隆过滤器误判而拒绝命令，原样重发的命令改由处理节点拒绝（`DuplicateRequestId`），处理函数不会再次执行。在只做网关的服务上只等待 `SENT` 的调用方不再看到重复；要看到它，请等待 `PROCESSED`。这样的网关也不再拒绝重发的 `@VoidCommand`。见[失败与幂等](./command/reliability.md#快速预检与权威确认)。
- 处理节点只在布隆过滤器窗口内能不执行处理函数就拒绝重发；窗口之外由 `EventStore` 追加拒绝，与 9.2 相同。`wow.command.idempotency.enabled=false` 同时关闭这两处检查。
- 在 Spring 之外，只有给 `DefaultCommandHandler` 传入 `RequestIdChecker` 时，处理节点才会做这项检查；没有传入时，`EventStore` 追加是该节点唯一的重复检查，与 9.2 相同。
- `DefaultCommandGateway.close()` 不再关闭传给它的 `CommandBus`。`close()` 之后网关无法再调度截止时间：`sendAndWait*` 以 `RejectedExecutionException` 失败。手工构建网关的代码自己关闭总线；Spring 会关闭总线 Bean。
- `sendAndWait` / `sendAndWaitStream` 发送的是 Header 带等待键的消息副本，调用方的消息不被修改。从传入的消息读回等待键的代码，改为从接收到的消息读取。

### Saga、等待与排序

- Saga 命令的 ID 从事件推导。没有指定聚合的命令体或 `CommandBuilder` 得到由事件、Saga 函数、序号与目标类型推导的聚合 ID，格式是目标 ID 生成器的格式（仅限基于时间的 CosId 或 Snowflake 生成器；号段与自定义生成器仍是随机 ID，返回的 `CommandMessage` 保留自己的聚合 ID）。返回的 `CommandMessage` 没有自己的请求 ID 时得到 `<事件 ID>-<序号>-<producer 哈希>`；命令体与 builder 仍是 `<事件 ID>-<序号>`，与 9.2 相同。因此重试的 Saga 创建命令指向第一次尝试创建的聚合，以 `DuplicateRequestId` 被拒绝；Saga 跳过这次发送，不会为它写补偿记录。见[requestId 与上下文传播](./event/saga.md#requestid-与上下文传播)。
- chain 等待的 tail 只传给 chain 所等待的那个 Saga 函数发出的命令，所以链式等待的 SSE 流（以链式目标调用 `sendAndWaitStream`）不再携带对同一事件作出反应的其他 Saga 的尾部信号。chain 计划必须等待随它发送的命令（`waitCommandId` 等于命令 ID），否则 `sendAndWait` 以 `IllegalArgumentException` 失败。见[命令等待运行时](./command/internals/wait-runtime.md)。
- `@Order` 按 `before`/`after` 约束做拓扑排序（就绪的元素中 `value` 小的在前，其次按声明顺序），适用于所有有序列表：过滤器链、after-command 函数、事件升级器、ID 生成器、错误信息转换器、查询准入与消息传播器。即使 9.2 的顺序已满足所有约束，结果也可能与 9.2 不同（`X @Order(100, before = [Y])`、`Y @Order(0)`、`Z @Order(50)`：9.2 为 `[X, Y, Z]`，9.3 为 `[Z, X, Y]`）；约束中出现环时启动失败，抛出指明该环的 `IllegalStateException`。

### BI 脚本路由需要 `wow-bi`

`wow-webflux` 以及 Starter 的 `webflux-support` / `openapi-support` capability 不再引入 `wow-bi`（及 ClickHouse client）。提供 `POST /wow/bi/script` 的应用需自行添加 `wow-bi`，或请求 Starter 的 `bi-support` capability；它在 classpath 上时，该路由、它的 OpenAPI operation 与 schema、错误码以及 `wow.bi.script.*` 配置都不变。没有它时该路由不存在。BI 路由相关类已移到 Starter：

| 已删除 | 改用 |
|---|---|
| `me.ahoo.wow.webflux.route.global.GenerateBIScriptHandlerFunction` / `GenerateBIScriptHandlerFunctionFactory` | 由 `me.ahoo.wow.spring.boot.starter.bi.BiAutoConfiguration` 装配（内部类） |
| `me.ahoo.wow.spring.boot.starter.webflux.bi.BiDeploymentInspectorAutoConfiguration` | `me.ahoo.wow.spring.boot.starter.bi.BiAutoConfiguration` |
| `DefaultRouteContributors.all()` 中的 `GenerateBIScriptRouteContributor` | `wow-bi` 存在时由 Starter 注册的 `RouteContributor` Bean；该路由只通过 Starter 提供 |

`BiScriptOptions` 与 `BiScriptProperties` 新增 `omitSensitiveFields`（`wow.bi.script.omit-sensitive-fields`，默认 `false`），它们的 JVM 构造函数与 `copy` 签名随之改变；打开后展开列不包含 `@Sensitive` 状态属性。默认生成的脚本不变。

### 本地优先投递

- 本地优先的发送在消息交给本地接收者时即完成，不再等待本地分发器准入，也不再等待它的分布式副本。副本异步发送，同一聚合内保持发送顺序，`local_first` 标记的含义与 9.2 相同。对已交出的发送，副本发送失败会记日志并计数，不再让这次发送失败；被拒绝的发送（没有可路由的接收者、路由已关闭、交出出错）仍等待自己的副本，并随它失败。见[LocalFirst 双副本准入](./command/internals/transport.md#localfirst-双副本准入)。
- 在本地交出结果到达之前取消的发送方，其副本仍只发送一次，并带上真实的本地决定：不重复、不丢失。交出结果在 `handOffTimeout`（30 秒）内没有到达时按拒绝处理，副本不带标记发出，其他成员可能再处理一次该消息。
- 领域事件与状态事件的本地 sink 不再有上限：本地消费者变慢时积压增长（指标 `wow.local_first.backlog`，达到 `wow.{command,event,eventsourcing.state}.bus.local-first.backlog-high-water-mark` 时记 WARN，默认 10000），而不是绕过本地投递。
- 排队的副本在 `wow.shutdown-timeout` 内发出，超时后取消。已交出但尚未处理的消息在进程崩溃时会丢失；消息必须在崩溃后保留的场景请关闭本地优先。不保证跨主题或跨总线的顺序。
- `LocalMessageBus.sendIfSubscribed(message): Mono<Boolean>` 改为 `handOff(message): Mono<LocalHandoff>`；`LocalFirstMessageBus` 新增抽象属性 `distributedCopies: LocalFirstDistributedCopies`，三个内置的本地优先总线以构造参数接收它。Starter 为每个总线注册一个 `localFirst{Command,DomainEvent,StateEvent}BusDistributedCopies` Bean，作为运行时组件。在运行时之外创建的 `LocalFirstDistributedCopies` 不注册为运行时组件时，停机时不会等待它。

### 分发：每个运行时一个 KeyedExecutor

- 运行时的所有分发器（命令、领域事件、状态事件、投影、无状态 Saga、快照）共享一个 [`KeyedExecutor`](./advanced/keyed-executor.md)，不再为每种聚合类型与分发器各建一个 `Schedulers.newParallel(cores)` 线程池：`wow.dispatch.workers` 个线程（默认等于可用处理器数），每个聚合 ID 一个邮箱，每个接收器最多 `wow.dispatch.max-in-flight`（默认 `256`）条未完成消息，一个聚合每轮最多执行 `wow.dispatch.throughput`（默认 `16`）条消息。按聚合的顺序不变。
- 删除且无需替代调用：`AggregateSchedulerSupplier`、`DefaultAggregateSchedulerSupplier`、`MessageParallelism`（`DEFAULT_PARALLELISM`、`toGroupKey`）、`ParallelismCapable`、系统属性 `wow.parallelism`（设置了它的运行时启动时记一条 WARN），以及 `CommandDispatcher`、`AggregateCommandDispatcher`、`DomainEventDispatcher`、`ProjectionDispatcher`、`StatelessSagaDispatcher`、`SnapshotDispatcher`、`AggregateSnapshotDispatcher`、`CompositeEventDispatcher` 的构造参数 `parallelism`、`scheduler`、`schedulerSupplier`。删掉这些参数与自定义的 `AggregateSchedulerSupplier` Bean；用 `wow.dispatch.workers` 设定线程数，不用 Spring 时用 `WowRuntime(keyedExecutor = KeyedExecutor(workers = …))`。自定义的 `AggregateDispatcher` 改为实现 `T.mailboxKey(): Any`，取代 `parallelism`、`scheduler` 与 `T.toGroupKey()`。
- 处理函数运行在 `wow-dispatch-N` 线程上，不再是 `<Dispatcher>-<aggregate>-N`：请更新匹配旧线程名的日志格式、线程名断言与线程池指标。工作线程与 9.2 一样是 Reactor 非阻塞线程，处理函数中的 `block()` 会立即失败；阻塞的函数请标注 `@Blocking`。
- 由分发器调用的 `suspend` 与 `Flow` 消息函数在分发工作线程上恢复，不再是 `Dispatchers.Default`。
- 等待中的处理函数（I/O、版本冲突后的重试退避）不占线程，只推迟自己的聚合；9.2 中它会推迟哈希到同一组的所有聚合。但未完成窗口由接收器的所有聚合 ID 共享：一个聚合积压约 241 条未完成消息（默认配置）就会让接收器停下，Kafka 上会暂停它的主题。见 [Keyed Executor](./advanced/keyed-executor.md#模型)。
- 强制停止（`wow.shutdown-timeout` 到期）在返回前丢弃仍在邮箱中排队的消息，与 9.2 释放调度器一致；这些消息不会被确认，Kafka 与 Redis Streams 会重新投递。见[运行时生命周期](./advanced/runtime-lifecycle.md)。
- 每个分发器按**限界上下文**各开一个接收器（Kafka 上是一个订阅该上下文全部聚合主题的消费者），不再按聚合类型各开一个。消费组 ID 与主题不变；每个组的实例与再均衡参与者从“聚合类型数”降为“限界上下文数”。未完成窗口与 Kafka 的 `max-deferred-commits` 由上下文内的聚合类型共享，所以一个聚合的积压会让该分发器在这个上下文的全部主题暂停；处理函数的致命错误也会让该分发器整个上下文的接收器失败，而不只是一种聚合类型的。自定义 `MainDispatcher` 改为实现 `newAggregateDispatcher(namedAggregates: Set<NamedAggregate>, …)`（每个上下文调用一次），自定义 `AggregateDispatcher` 提供 `namedAggregates`；`AggregateCommandDispatcher` 改为接收 `List<AggregateMetadata<*, *>>`，不再是泛型。子分发器的默认名称变为 `<context>-…Dispatcher`（日志中的组件名；指标标签不变）。
- **滚动升级。** 9.2.x 按主题的消费者与 9.3.0 按上下文的消费者可以加入同一个消费组：Kafka 的分配器只在订阅了某主题的成员之间分配该主题，分区带着已提交的偏移量移交。混合版本门禁在命令与 Saga 驱动的订单持续流转时先重启 9.3 成员、再重启 9.2.4 成员，并检查没有命令丢失或被重复应用。所有成员的 `partition.assignment.strategy` 应保持一致。见 [Kafka 消费组](./extensions/kafka.md#消费者组)。
- 修复（影响 9.2.x）：处理函数在自身完成时以响应式方式发送命令，不再让同组的其他聚合饿死直至命令超时。
- 性能：9.3.0 的基准门禁比较了这一变更前后的 main。CI（4 核，每侧 8 个交替 fork）中没有变慢的行：本地优先与内存命令发送 7 行更快、5 行在噪声内（[run 37557218922](https://github.com/Ahoo-Wang/Wow/actions/runs/37557218922)），聚合处理在噪声内（[run 37550851420](https://github.com/Ahoo-Wang/Wow/actions/runs/37550851420)）。在 128 个聚合各等待 5 ms I/O 时，冷命令在 CI 中快 79–85 倍（单发送线程 348 → 29,447 ops/s，三线程 991 → 78,597；[run 37562193882](https://github.com/Ahoo-Wang/Wow/actions/runs/37562193882)），本地（14 核）快 14–16 倍（单发送线程 4.9k → 71k ops/s，三线程 11.7k → 186k），可持续的命令发送速率最多高 66 %。完整表格见 [#3969](https://github.com/Ahoo-Wang/Wow/pull/3969)。

### 失败策略：接收重试与失败记录

- 接收重试统一由核心的 `TransportFailurePolicy` 定义。Redis Streams 现在像 Kafka 一样重试失败的接收流（连续 3 次，退避从 `10s` 起，`wow.redis.message-bus.receiver.retry-*`），不再在第一次错误时停止运行时。Redis 在启动时不可用时，Redis 接收器的就绪改为在重试策略耗尽后才失败（默认约 70 秒），而不是立即失败。
- `KafkaReceiverPolicy.retrySpec`、`DEFAULT_RETRY_ATTEMPTS`、`DEFAULT_RETRY_BACKOFF` 与 `defaultRetrySpec(...)` 已删除：构造 `TransportFailurePolicy(TransportFailurePolicy.receiveRetry(attempts, backoff))`，作为 `failurePolicy` 传给 `KafkaTransport` 或 Kafka/Redis 总线，或覆盖 `kafkaTransportFailurePolicy` / `redisTransportFailurePolicy` Bean。`wow.kafka.receiver.retry-*` 配置不变。
- Kafka 的 `RetriableException` 与 Redis 的连接失败、超时注册为 `RECOVERABLE`，`RetryableFilter` 与事件存储追加结果判定会重试它们。因此投影或 Saga 中的非幂等写（如 Redis `INCR`）在写入其实已成功的超时之后，可能在进程内再执行一次；投递本就是至少一次，这类写应保持幂等。
- 进程内重试耗尽的失败，其补偿记录（`ExecutionFailed`）改为携带原因的错误码、消息、堆栈与 `recoverable`（9.2 记录的是 `IllegalState` "Retries exhausted: n/n" 与 `UNKNOWN`）。因此原因被声明为不可恢复（如 `@Retry(unrecoverable = …)`）时不再自动补偿。
- 补偿模块以 `FailureRecorder`（`CompensationFailureRecorder`）记录事件处理失败，不再使用 Filter：删除 `DomainEventCompensationFilter`、`StateEventCompensationFilter` 与 `EventCompensationFilter`，`domainEventCompensationFilter` / `stateEventCompensationFilter` Bean 由 `compensationFailureRecorder` 取代；应用提供的 `FailureRecorder` Bean 会替代它。它发送的命令与 9.2 逐字节一致。记录改为在 wait 通知器发出信号之后写入，所以请按事件 ID 轮询 `ExecutionFailed`，不要期望它与信号同时出现（见[失败记录](./event/dispatch.md#失败记录)）。
- 新增 `wow.event.ack-on-unrecorded-failure`（默认 `true`，行为不变）：设为 `false` 时，没有记录器记录的失败不确认，等待重投。
- `DefaultDomainEventHandler`、`DefaultProjectionHandler`、`DefaultStatelessSagaHandler` 与 `DefaultSnapshotHandler` 继承 `FailureRecordingHandler`，新增可选参数 `failureRecorder`（除 Snapshot 外还有 `ackOnUnrecordedFailure`）。
- 删除未被使用的 `me.ahoo.wow.messaging.handler.retryStrategy(...)`；改用 Reactor 的 `Retry.backoff`。

### Redis Streams：保留与空闲 consumer

- Redis 接收器启动时，删除所在消费组中没有待处理消息、且空闲超过 `wow.redis.message-bus.retention.consumer-idle-timeout`（默认 `30m`）的 consumer；检查与删除是原子的，不会丢消息。`XINFO CONSUMERS` 不再随每次部署增长。设置 `wow.redis.message-bus.retention.reap-idle-consumers: false` 可保留所有 consumer。
- 新增 Stream 裁剪，默认关闭：`max-length`（`MAXLEN`）或 `max-age`（`MINID`，至少 1 分钟），除非设置 `approximate: false`，否则为近似裁剪（`~`）。需要 Redis 7.0 或更高版本；落后的消费组会丢失在它读到之前被裁掉的条目。见[Stream 保留与空闲 consumer](./extensions/redis.md#stream-保留与空闲-consumer)。
- `RedisMessageBusAutoConfiguration` 的三个 Redis 总线 Bean 改为接收新的 `RedisStreamRetentionProperties`。

### 停机：写入器与发送器加入运行时

- Mongo 与 Elasticsearch 的批量写入器以及 Kafka 发送器，在分发器之后、运行时同一个 `wow.shutdown-timeout` 之内停止，不再在之后由 Spring 关闭（每个批量写入器最多 30 秒，依次进行）。请让 `wow.shutdown-timeout` 足够完成一次正常的刷写。见[存储与传输资源](./advanced/runtime-lifecycle.md#存储与传输资源)。
- 默认 Starter 运行时的 `WowRuntime.components` 现在以一个 `RuntimeResources` 组件开头；自定义运行时也应把它放在最前。断言完整组件列表的测试需要把它过滤掉。
- Kafka producer 的关闭时间受 `wow.kafka.close-timeout` 限制，默认为 `wow.shutdown-timeout`。`KafkaProperties` 新增末尾参数 `closeTimeout`，`buildSenderOptions(defaultCloseTimeout)` 优先用它，否则用传入的默认值；手动构造 `KafkaAutoConfiguration` 的代码还需传入 `WowProperties`。
- 优雅停机先暂停持久入口：Kafka 与 Redis Streams 接收器不再请求新记录，持续到达的流量不再让运行时越过静默期、拖到 `wow.shutdown-timeout` 强制停止。尚未拉取的记录对 Kafka 保持未提交、对 Redis 保持未读或 pending，留给消费组；进程内发送与 9.2 一样，在全局准入关闭前都会被接纳。中间件会保留未投递记录的自定义 `Transport` 应设置 `TransportReceiver.durable = true`；`MessageReceiver` 新增末尾参数 `durableIntakeSuspension`（需重新编译）。见[持续流量下先停持久入口](./advanced/runtime-lifecycle.md#持续流量下先停持久入口)。

### 请求头传播与可恢复异常改为 Bean

- `MessagePropagatorProvider` 已删除。改用注入的 `MessagePropagators`（Spring Bean，Spring 之外用 `MessagePropagators.DEFAULT`）；`import ...MessagePropagatorProvider.propagate` 改为 `import me.ahoo.wow.messaging.propagation.propagate`。`toCommandMessage(…)`、`CommandBuilder.toCommandMessage()`、`toDomainEventStream(…)` 以及 `SimpleCommandMessageFactory`、`SimpleCommandAggregateFactory`、`StatelessSagaFunction`、`StatelessSagaFunctionRegistrar` 的构造函数新增末尾参数 `MessagePropagators`（有默认值）。`MessagePropagator` 现在也可以是 Bean；同一类的 Bean 优先于 ServiceLoader 的实现，排序按 Wow 的 `@Order`（不是 Spring 的）。
- `RecoverableExceptionRegistrar` 现在是 `RecoverableExceptionProvider` 注册时使用的接口，9.2 的静态调用作为已弃用的伴生对象函数保留（见[重新编译与弃用的调用](#重新编译与弃用的调用)）。改用 `RecoverableExceptionRegistry.DEFAULT` 或 `recoverableExceptionRegistry` Bean。`RecoverableExceptionProvider` 现在也可以是 Bean。
- `wow.messaging.propagation.request` 从 Spring 环境读取，可以写在 `application.yaml` 中；它只作用于运行时注入的 `MessagePropagators`。`MessagePropagators.DEFAULT` 忽略它，在 Spring 之外也不再读取 `-D` 系统属性。

### API 分层：`@WowSpi` 与 `@InternalWowApi`

两个标注区分应用使用的 API 与实现者使用的 API：`@WowSpi`（opt-in，警告级）标注供后端、传输与命令聚合实现者使用的 SPI；`@InternalWowApi`（opt-in，错误级）标注由其他 Wow 模块装配的内部类型。实现 SPI 的地方加上 `@OptIn(WowSpi::class)` 或编译参数 `-opt-in=me.ahoo.wow.api.annotation.WowSpi`。

| 模块 | 改为 internal 或 `@InternalWowApi` | 改用 |
|---|---|---|
| wow-core | `SimpleCommandAggregate`、`CommandState`、`CommandAggregate.commandState`、`RetryableAggregateProcessor`、`CommandFunction` 及其实现、`AfterCommandFunction`、`AggregateProcessorFactory`、`RetryableAggregateProcessorFactory`、`SimpleStateAggregate`、`FunctionAccessorMetadata`、`MessageFunctionAccessor` 及其实现、`MessageFunctionRegistrar`、`FunctionMetadataParser`、各事件分发器基类、`CompositeEventDispatcher`、`EventHandler`、exchange 属性键以及除 `getEventStream()` / `getAggregateVersion()` 之外的 `ServerCommandExchange` 属性访问器、`commandSentSignal`、各 `toResult` 工厂 | `@OnCommand` / `@AfterCommand` 函数、`MessageFunction`、`StateAggregateFactory`、`ServerCommandExchange.getEventStream()` / `getAggregateVersion()`、`CommandGateway` 的结果 |
| wow-query | `QueryOperation`、`QueryAdmission.admit(QueryOperation, …)`、`QueryEntryPolicy.requireScope`、`UnavailableQueryModelSchemaProvider`、`FilterNormalizer`、`DefaultQueryModelSchemaProvider`、`QueryFieldSchema`、`QueryModelSchema.field`、`QueryFieldCapabilities` | 网关或 `QueryAdmission.Trusted.*`；`QueryModelCompiler` / `QuerySchemaCatalog`；`QueryModelSchema.describe(...)` |

`CommandAggregate`、`AggregateProcessor`、`CommandAggregateFactory`、`SimpleCommandAggregateFactory`、查询后端 SPI（见[API 分层](./query/query-backend.md#api-分层)）与传输 SPI 都标注为 `@WowSpi`。需要当前状态的命令函数改用 `ReadOnlyStateAggregate<S>` 参数，不要用 `CommandAggregate`。运行 TCK 的第三方查询后端现在会执行 `schema refresh with unchanged storage keeps the published object`：没有变化的刷新保留已发布的 schema。

### 传输 SPI：删除 Kafka 与 Redis 总线内部类型

所有分布式总线现在都是建在 `Transport` 上的核心 `TransportMessageBus`（见[传输 SPI](./command/internals/transport.md#传输-spi)）。各后端的总线基类、交换类型与解码处理器属于扩展点 SPI，直接删除，不经弃用；主题、键、JSON 与消费组都不变。

| 已删除 | 改用 |
|---|---|
| `AbstractKafkaBus`、`AbstractRedisMessageBus` | 核心 `TransportCommandBus`、`TransportDomainEventBus`、`TransportStateEventBus` 加上 `KafkaTransport` / `RedisStreamTransport`；`Kafka*Bus` / `Redis*Bus` 的选项构造函数除下面的解码处理器和末尾有默认值的 `failurePolicy` 外，参数不变 |
| `KafkaServerCommandExchange`、`KafkaEventStreamExchange`、`KafkaStateEventExchange`、`RedisServerCommandExchange`、`RedisEventStreamExchange`、`RedisStateEventExchange` | `TransportServerCommandExchange`、`TransportEventStreamExchange`、`TransportStateEventExchange` |
| `KafkaRecordDecodeFailureHandler`、`KafkaRecordDecodeFailure`、`KafkaRecordDecodeException` | `TransportDecodeFailureHandler`、`TransportDecodeFailure`、`TransportDecodeException` |
| `FailKafkaRecordDecodeFailureHandler`、`AcknowledgeKafkaRecordDecodeFailureHandler` | `TransportDecodeFailureHandler.FAIL`、`TransportDecodeFailureHandler.ACKNOWLEDGE` |
| `Kafka*Bus(…, recordDecodeFailureHandler = …)` | `decodeFailureHandler: TransportDecodeFailureHandler` |
| `MainDispatcher.receiveMessage`（及命令、事件、快照分发器中的覆盖） | 实现 `createMessageReceiver(subscription)`，例如 `bus.receiver(subscription.copy(runtimeOwned = true))` |
| Starter 中返回 `KafkaRecordDecodeFailureHandler` 的 `kafkaRecordDecodeFailureHandler()` | 同名 Bean 方法，返回 `TransportDecodeFailureHandler`；应用中旧类型的 Bean 改为 `TransportDecodeFailureHandler` Bean（`wow.kafka.receiver.decode-failure-strategy` 不变） |

Kafka 接收的消息现在是只读的，与 Redis 和内存总线一致。Redis 把消息属于另一个 Stream 的条目视为无法解码（`RedisRecordDecodeFailureReason.TOPIC_MISMATCH`），并让所有无法解码的条目保持待处理状态。

### 其他 API 变化

- **WebFlux 路由：** 每条物化路由的处理函数都包在 `RouteIdentityHandlerFunction`（`@InternalWowApi`）里；按具体类型检查路由 `HandlerFunction` 的代码需要先拆开它。
- **错误处理：** 自定义的 `WebFluxErrorStrategy` 通过 `toErrorInfo` 作用于 SSE 错误事件和批量结果，请与 `toServerResponse` 一起覆盖。自定义的 `RequestExceptionHandler` 通过 `handleInBody` 报告这些错误；只实现了 `handle` 的不再为它们记日志。
- **OpenAPI：** 除非先执行过 `RouterSpecs.buildDocumentation()` 或 `mergeOpenAPIFromCatalog`，`RouterSpecs.toRouteCatalog()` 的契约不再带生成的 schema；`OpenAPIAutoConfiguration.routerSpecs(…)` 改为接收 `ObjectProvider<RouteContributor>` 与 `OpenAPIProperties`。OpenAPI 文档与路由不变。
- **API 客户端：** `ReactiveRestCommandGateway` 与 `SyncRestCommandGateway` 新增抽象方法 `send(sendUri, headers, command)`，接收 `CommandRequest.toRequestHeaders()` 的结果。手写实现这两个接口的类要补上它；CoApi 生成的代理不受影响。
- **删除的 JSON 记录辅助类型：** `FlatEventStreamRecord`、`DelegatingMessageRecord`、`DelegatingNamedBoundedContextMessageRecord`、`toMessageRecord()` 与 `toBoundedContextMessageRecord()`；改为直接实现记录接口。
- **TCK：** `CommandGatewaySpec` 新增可覆盖的 `requestIdExistenceChecker`，因为没有事件存储的网关不再只凭预检就拒绝。

### 构建：`wow-metadata` 与可复现的 KSP 输出

`wow-compiler`（KSP 处理器）改为依赖新的 `wow-metadata` 模块，不再依赖 `wow-core`。元数据模型（`me.ahoo.wow.configuration.*`、`me.ahoo.wow.naming.*`）以不变的包名移到那里，`wow-core` 依赖它，`wow-bom` 对齐它的版本，所以依赖 `wow-core` 的代码看到的类不变。原来只经由 `wow-compiler` 拿到 `wow-core` 的构建需要自己声明它。生成代码上的 `@Generated` 不再带日期，重复构建产生逐字节一致的源码。

## 旧链接导航

旧单页主题已拆分到三份专项指南。以下标题与显式 alias 保留既有 deep link。

### 版本升级指南

<span id="升级步骤"></span>
<span id="依赖版本更新"></span>
<span id="破坏性变更检查"></span>

参见 [v6 → v8：通用升级步骤](./migration/v6-to-v8.md#通用升级步骤)。

### 从传统架构迁移

<span id="迁移策略"></span>
<span id="渐进式迁移"></span>
<span id="迁移步骤"></span>

参见 [传统架构迁移：迁移总览](./migration/traditional-architecture.md#迁移总览)。

### 数据迁移

<span id="历史数据导入"></span>

参见 [用单写者完成历史导入与增量追平](./migration/traditional-architecture.md#_2-用单写者完成历史导入与增量追平)。

### 代码迁移

<span id="从-crud-到命令模式"></span>
<span id="从直接查询到查询快照"></span>

参见 [先迁移边界，不先迁移表](./migration/traditional-architecture.md#_1-先迁移边界-不先迁移表)
和 [对账后分别切换读与写](./migration/traditional-architecture.md#_3-对账后分别切换读与写)。

### 兼容性说明

<span id="数据格式兼容性"></span>
<span id="事件升级"></span>
<span id="消息格式兼容性"></span>

参见 [领域模型继续演进](./migration/traditional-architecture.md#_4-领域模型继续演进)
和 [v6 → v8：破坏性变更检查](./migration/v6-to-v8.md#破坏性变更检查)。

### 已知问题

<span id="版本特定问题"></span>
<span id="常见迁移问题"></span>

参见 [Release Notes](https://github.com/Ahoo-Wang/Wow/releases) 和
[故障排查](./troubleshooting.md)。应用 workaround 前，应先在精确固定 tag 上复现失败。

### 迁移检查清单

使用 [传统架构迁移检查清单](./migration/traditional-architecture.md#完成检查清单)
或 [v6 → v8 验证清单](./migration/v6-to-v8.md#验证清单)，再补充环境特定的生产准入证据。

### 回滚计划

使用所选专项指南的回滚步骤，并遵守[共同完成门禁](#共同完成门禁)中首次写入前后的区分。

### 统一运行时编排

参见 [运行时编排迁移](./migration/runtime-orchestration.md)。

### 移除版本化快照检查点

参见 [v6 → v8：移除版本化快照检查点](./migration/v6-to-v8.md#移除版本化快照检查点)。

### SnapshotStore 原子保存

参见 [v6 → v8：SnapshotStore 原子保存](./migration/v6-to-v8.md#snapshotstore-原子保存)。

### Redis EventStore Canonical v2 布局（v8.9.0 引入）

参见 [v6 → v8：Redis EventStore Canonical v2 布局](./migration/v6-to-v8.md#redis-eventstore-canonical-v2-布局-v8-9-0-引入)。

### Mongo 所有权保护

参见 [v6 → v8：Mongo 所有权保护](./migration/v6-to-v8.md#mongo-所有权保护)。

## 相关页面

| 页面 | 关系 |
|---|---|
| [传统架构迁移](./migration/traditional-architecture.md) | 首次采用与流量所有权 |
| [Wow v6 迁移到 v8](./migration/v6-to-v8.md) | 既有 Wow 平台/存储升级 |
| [运行时编排迁移](./migration/runtime-orchestration.md) | 统一生命周期源码迁移 |
| [V9 查询迁移](./query/v9-query-migration.md) | V8.16.x 到 V9 的查询与 SnapshotStore 源码迁移 |
| [从 Fetcher 包迁移](./typescript/migration.md) | TypeScript 包改名与重新生成客户端 |
| [运行时生命周期](./advanced/runtime-lifecycle.md) | 迁移后的稳定运行模型 |
| [故障排查](./troubleshooting.md) | 门禁失败时的证据化诊断 |

<!-- Sources: current migration subpages, v6/v8 tags, WowRuntime, SnapshotStore, Redis/Mongo guards -->
