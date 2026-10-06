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

### 聚合策略移出 `@AggregateRoute`（9.3.0）

`@AggregateRoute(spaced, owner)` 已弃用，改为在聚合上声明 `@Spaced` 与 `@AggregateOwner(OwnerPolicy.…)`；同一策略或静态租户的声明不一致时会报错。见[从 `@AggregateRoute(spaced, owner)` 迁移](./domain/aggregate.md#从-aggregateroute-spaced-owner-迁移)。

### 移除兼容垫片（9.3.0）

9.3.0 删除了只为让旧字节码或 9.2 扩展代码继续链接而保留的声明。基于 9.2 及更早版本构建的库须先重新编译再升级；用到下表所列 API 的源码必须迁移。

| 已删除 | 改用 |
|---|---|
| 在总线中覆盖 `MessageBus.receive(subscription)` | 实现 `receiver(subscription)`，它现在是抽象方法 |
| `MessageBus.runtimeReceiver(subscription)` | `receiver(subscription.copy(runtimeOwned = true))` |
| 不带 `runtimeOwned` 的 `MessageSubscription` 构造函数与 `copy`（仅 JVM） | 带 `runtimeOwned` 的构造函数与 `copy`（默认 `false`） |
| 不带 `retentionOptions` 的 Redis 总线构造函数（仅 JVM） | 主构造函数（`retentionOptions` 默认 `RedisStreamRetentionOptions.DEFAULT`） |
| `BindingError`、`AggregationGroup.Terms`、`AggregationGroup.Histogram` 的 9.1 构造函数（仅 JVM） | 主构造函数 |
| `CoSecCommandBuilderExtractor`、`CoSecQueryRequestScope` | 由 `CoSecAutoConfiguration` 注册的 `CoSecIdentityHeaders.ALIASES` |
| `Flux<AggregateId>.toBatchResult(afterId)`、`ResendStateEventHandler.handle(afterId, limit)` | `toBatchResult(afterId, request, exceptionHandler)`、`resend(afterId, limit)` |
| 非 bean 的 `WebFluxAutoConfiguration.commandMessageExtractor`、`queryRequestScope`、`commandRouterFunction`、`pointReadAdmission` 重载，`CoSecAutoConfiguration.coSecCommandBuilderExtractor` / `coSecQueryRequestScope`，三参数的 `OpenAPIAutoConfiguration.routerSpecs` | 同名的 `@Bean` 方法 |

下列面向应用的调用在 9.3 中仍可编译，但已弃用，10.0.0 删除：

| 已弃用 | 改用 |
|---|---|
| 调用 `bus.receive(subscription)` | `receiver(subscription).openedMessages()` |
| `ServerRequest.getTenantId(aggregateMetadata)`、`getTenantIdOrDefault(aggregateMetadata)` | `identity(aggregateMetadata).tenantId()`（`?: TenantId.DEFAULT_TENANT_ID`） |
| `ServerRequest.getOwnerId()` | `identity(aggregateMetadata).ownerId()`（以聚合 ID 为所有者的聚合会退回 `{id}`） |
| `ServerRequest.getSpaceId()`、`getSpaceId(aggregateRouteMetadata)` | `identity(aggregateMetadata).spaceId()`（非空间化聚合为 `null`） |
| `ServerRequest.getAggregateId()` 及其两个 `AggregateRoute.Owner` 重载 | `identity(aggregateMetadata).aggregateId()`（按聚合的所有者策略） |
| `RecoverableExceptionRegistrar.register`、`unregister`、`getRecoverableType`（静态调用；Java 经 `.Companion` 调用） | `RecoverableExceptionRegistry.DEFAULT` 的同名方法，或 `RecoverableExceptionProvider` |

`identity(…)` 即 `me.ahoo.wow.webflux.route.identity.identity`；它返回的 `RequestIdentity` 按路由的规则读取每个身份字段（含请求头别名），与内置命令、查询处理器完全一致。

### 命令过滤器改为固定管道（9.3.0）

命令侧不再有过滤器链。`DefaultCommandHandler` 按固定顺序执行处理、确认、领域事件与状态事件发布以及 `PROCESSED` 报告（见[命令处理管道](./command/internals/pipeline.md#bus-到-dispatcher)）。`ExchangeFilter<ServerCommandExchange<*>>` Bean 不再被调用，按它原来的用途改到对应的扩展点：

| 已删除 | 改用 |
|---|---|
| 用于追踪、指标或日志的 `CommandFilter`，或带 `@FilterType(CommandDispatcher::class)` 的 `ExchangeFilter` | `CommandInstrumentation` Bean；`around(exchange, handling)` 包住每条命令的处理，必须原样返回结果 |
| 检查或拒绝命令的命令过滤器 | 命令上的 `CommandValidator` / Jakarta 校验（在网关处执行），或在命令函数中检查 |
| 响应已提交事件的命令过滤器 | 事件处理器、Saga 或投影 |
| `TraceAggregateFilter`（OpenTelemetry） | 由 starter 注册的 `TraceCommandInstrumentation`；span 名称与属性不变 |
| `AggregateProcessorFilter`、`SendDomainEventStreamFilter`、`SendStateEventFilter`、`ProcessedNotifierFilter`、`DefaultCommandHandler(chain, errorHandler)` | `DefaultCommandHandler(serviceProvider, aggregateProcessorFactory, domainEventBus, stateEventBus, commandWaitNotifier, instrumentations, errorHandler)` |
| `CommandHandler.handle(exchange)` | `CommandHandler.handle(exchange, aggregateMetadata)` |
| `ServerCommandExchange.setAggregateMetadata` / `getAggregateMetadata` / `setAggregateProcessor` / `getAggregateProcessor` | 处理器以参数接收 metadata |

上下文里仍有这样的 Bean（`ExchangeFilter<ServerCommandExchange<*>>`，或带 `@FilterType(CommandDispatcher::class)` 的 `ExchangeFilter`）时，starter 启动失败，错误信息给出 Bean 名称和上述替代方式，而不是静默忽略它。

### 命令网关与 request-ID 检查（9.3.0）

- 处理命令的节点在处理函数执行之前，会对照自己的 `EventStore` 再查一次 request ID。没有 `EventStore` 的节点所用的 `NoopRequestIdExistenceChecker` 现在回答“不存在”，不再回答“已存在”。因此只做网关的服务不再因布隆过滤器误判而拒绝命令，原样重发的命令改由处理节点拒绝；这样的网关也不再拒绝重发的 `@VoidCommand`，见[失败与幂等](./command/reliability.md#快速预检与权威确认)。
- 先升级处理节点，再升级只做网关的服务；处理节点只在布隆过滤器窗口内能不执行处理函数就拒绝重发，窗口之外由 `EventStore` 追加拒绝，与 9.2 相同。
- `DefaultCommandGateway.close()` 不再关闭传给它的 `CommandBus`。`close()` 之后网关无法再调度截止时间：`sendAndWait*` 以 `RejectedExecutionException` 失败。手工构建网关的代码自己关闭总线；Spring 会关闭总线 Bean。
- `sendAndWait` / `sendAndWaitStream` 发送的是 Header 带等待键的消息副本，调用方的消息不被修改。从传入的消息读回等待键的代码，改为从接收到的消息读取。

### 请求头传播与可恢复异常改为 Bean（9.3.0）

- `MessagePropagatorProvider` 已删除。改用注入的 `MessagePropagators`（Spring Bean，Spring 之外用 `MessagePropagators.DEFAULT`）；`import ...MessagePropagatorProvider.propagate` 改为 `import me.ahoo.wow.messaging.propagation.propagate`。`MessagePropagator` 现在也可以是 Bean；同一类的 Bean 优先于 ServiceLoader 的实现，排序按 Wow 的 `@Order`（不是 Spring 的）。
- `RecoverableExceptionRegistrar` 现在是 `RecoverableExceptionProvider` 注册时使用的接口，静态对象已删除。改用 `RecoverableExceptionRegistry.DEFAULT` 或 `recoverableExceptionRegistry` Bean（`register`、`unregister`、`getRecoverableType`）。`RecoverableExceptionProvider` 现在也可以是 Bean。
- `wow.messaging.propagation.request` 从 Spring 环境读取，只作用于运行时注入的 `MessagePropagators`；`MessagePropagators.DEFAULT` 忽略它，在 Spring 之外也不再读取 `-D` 系统属性。
- chain 等待的 tail 只传给 chain 所等待的那个 Saga 函数发出的命令；chain 计划必须等待随它发送的命令（`waitCommandId` 等于命令 ID），否则 `sendAndWait` 以 `IllegalArgumentException` 失败。见[命令等待运行时](./command/internals/wait-runtime.md)。

### BI 脚本路由需要 `wow-bi`（9.3.0）

`wow-webflux` 以及 Starter 的 `webflux-support` / `openapi-support` capability 不再引入 `wow-bi`（及 ClickHouse client）。提供 `POST /wow/bi/script` 的应用需自行添加 `wow-bi`，或请求 Starter 的 `bi-support` capability；它在 classpath 上时，该路由、它的 OpenAPI operation 与 schema、错误码以及 `wow.bi.script.*` 配置都不变。没有它时该路由不存在。BI 路由相关类已移到 Starter：

| 已删除 | 改用 |
|---|---|
| `me.ahoo.wow.webflux.route.global.GenerateBIScriptHandlerFunction` / `GenerateBIScriptHandlerFunctionFactory` | 由 `me.ahoo.wow.spring.boot.starter.bi.BiAutoConfiguration` 装配（内部类） |
| `me.ahoo.wow.spring.boot.starter.webflux.bi.BiDeploymentInspectorAutoConfiguration` | `me.ahoo.wow.spring.boot.starter.bi.BiAutoConfiguration` |
| `DefaultRouteContributors.all()` 中的 `GenerateBIScriptRouteContributor` | `wow-bi` 存在时由 Starter 注册的 `RouteContributor` bean；该路由只通过 Starter 提供 |

### 失败策略：接收重试与失败记录（9.3.0）

- 接收重试统一由核心的 `TransportFailurePolicy` 定义。Redis Streams 现在像 Kafka 一样重试失败的接收流（连续 3 次，退避从 `10s` 起，`wow.redis.message-bus.receiver.retry-*`），不再在第一次错误时停止运行时。`KafkaReceiverPolicy.retrySpec`、`DEFAULT_RETRY_ATTEMPTS`、`DEFAULT_RETRY_BACKOFF` 与 `defaultRetrySpec(...)` 已删除：构造 `TransportFailurePolicy(TransportFailurePolicy.receiveRetry(attempts, backoff))`，作为 `failurePolicy` 传给 `KafkaTransport` 或 Kafka/Redis 总线，或覆盖 `kafkaTransportFailurePolicy` / `redisTransportFailurePolicy` Bean。`wow.kafka.receiver.retry-*` 配置不变。
- Kafka 的 `RetriableException` 与 Redis 的连接失败、超时注册为 `RECOVERABLE`，`RetryableFilter` 与事件存储追加结果判定会重试它们。因此投影或 Saga 中的非幂等写（如 Redis `INCR`）在写入其实已成功的超时之后，可能在进程内再执行一次；投递本就是至少一次，这类写应保持幂等。
- Redis 在启动时不可用时，Redis 接收器的就绪改为在重试策略耗尽后才失败（默认约 70 秒），而不是立即失败。
- 进程内重试耗尽的失败，其补偿记录（`ExecutionFailed`）改为携带原因的错误码、消息、堆栈与 `recoverable`（9.2 记录的是 `IllegalState` "Retries exhausted: n/n" 与 `UNKNOWN`）。因此原因被声明为不可恢复（如 `@Retry(unrecoverable = …)`）时不再自动补偿。
- 删除未被使用的 `me.ahoo.wow.messaging.handler.retryStrategy(...)`；改用 Reactor 的 `Retry.backoff`。
- 补偿模块以 `FailureRecorder`（`CompensationFailureRecorder`）记录事件处理失败，不再使用 Filter：删除 `DomainEventCompensationFilter`、`StateEventCompensationFilter` 与 `EventCompensationFilter`，`domainEventCompensationFilter` / `stateEventCompensationFilter` Bean 由 `compensationFailureRecorder` 取代。它发送的命令与 9.2 逐字节一致。记录改为在 wait 通知器发出信号之后写入（见[失败记录](./event/dispatch.md#失败记录)）。
- 新增 `wow.event.ack-on-unrecorded-failure`（默认 `true`，行为不变）：设为 `false` 时，没有记录器记录的失败不确认，等待重投。
- `DefaultDomainEventHandler`、`DefaultProjectionHandler`、`DefaultStatelessSagaHandler` 与 `DefaultSnapshotHandler` 继承 `FailureRecordingHandler`，新增可选参数 `failureRecorder`（除 Snapshot 外还有 `ackOnUnrecordedFailure`）。
- 应用提供的 `FailureRecorder` Bean 现在会替代补偿模块的 `compensationFailureRecorder`，不再与它并存。
- 传输 SPI 删除两个内置实现从未使用的成员：`TransportMessage.headers`（Kafka 从未写入）与 `TransportRecord.nack()`（没有调用方）。自定义传输删除它们即可；未确认的记录仍由 Broker 重投。
- `KafkaProperties` 新增 `closeTimeout`（`wow.kafka.close-timeout`），`buildSenderOptions(defaultCloseTimeout)` 优先用它，否则用传入的默认值；Starter 传入 `wow.shutdown-timeout`。手动构造 `KafkaAutoConfiguration` 的代码还需传入 `WowProperties`。

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
