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

<!-- 下文每个「从 x.y 升级」章节对应一行：新增章节时同时加上。 -->
| 当前系统 | 主路径 | 原因 |
|---|---|---|
| CRUD/事务脚本/直接写表，没有 Wow 历史 | [传统架构迁移](./migration/traditional-architecture.md) | 建立 command、aggregate、event、导入与流量所有权 |
| 精确 Wow v6 tag | [Wow v6 迁移到 v8](./migration/v6-to-v8.md) | 已从本站下线：按 v8.16.3 标签中保存的指南升级到 v8.16，再[从 8.16 升级到 9.0.0](#从-8-16-升级到-9-0-0) |
| Wow v8 上有自定义 dispatcher/message-bus/Spring 生命周期 owner | [运行时编排迁移](./migration/runtime-orchestration.md) | 把生命周期源码迁移到统一 `WowRuntime`；它不自动等于数据迁移 |
| Wow 9.8.x | [从 9.8 升级到 9.9.0](#从-9-8-升级到-9-9-0) | 重新编译：数据集、聚合窗口与根分组在线上都是新增；自行构建查询后端或 Catalog、通过基类读取查询模型 profile、或从 Java 调用变更过的构造函数的代码需要修改 |
| Wow 9.7.x | [从 9.7 升级到 9.8.0](#从-9-7-升级到-9-8-0) | 重新编译：类从 `wow-core` 的 `messaging` 包移入各自的领域包，`infra.batch` 改为 `me.ahoo.wow.batch`，指标装饰器移到 `me.ahoo.wow.metrics.decorator`；自己构建 `QuerySchemaCatalog` 或继承 `QueryGatewayRegistrar` 的集成需要修改 |
| Wow 9.6.x | [从 9.6 升级到 9.7.0](#从-9-6-升级到-9-7-0) | 只有用到 `wow-webflux` 内部实现或已删除的弃用辅助方法的代码需要修改；路由、REST、配置与线上格式不变，9.6.x 与 9.7.0 节点可以共处一个集群 |
| Wow 9.5.x | [从 9.5 升级到 9.6.0](#从-9-5-升级到-9-6-0) | `wow-openapi` 路由合同改为纯数据，OpenAPI 文档不变：重新编译，修改自定义 `RouteContributor` 与 `mergeOpenAPIFromCatalog` 的调用 |
| Wow 9.4.x | [从 9.4 升级到 9.5.0](#从-9-4-升级到-9-5-0) | `wow-schema` 重构，生成的 Schema 不变：重新编译；`OpenAPISchemaBuilder` 的 import 改为 `me.ahoo.wow.openapi.schema`（位于 `wow-openapi`），删掉对已删除 `SchemaGeneratorBuilder` 成员的调用；改掉对已转为内部的 Provider 与检查的引用 |
| Wow 9.3.x | [从 9.3 升级到 9.4.0](#从-9-3-升级到-9-4-0) | 只有 `wow-bi` 变化：每个 BI 部署用确认后的 `RESET` 重建一次，直接调用已删除 `wow-bi` API 的 Kotlin 代码改用 `BiScriptService` 或 `generate(prepare(…))`；其余部分的 REST、存储与线上格式不变 |
| Wow 9.2.x | [从 9.2 升级到 9.3.0](#从-9-2-升级到-9-3-0) | 重新编译，迁移已删除与已弃用的 API，先升级处理节点再升级只做网关的服务；REST、存储与线上格式不变 |
| Wow 9.1.x | [从 9.1 升级到 9.2.0](#从-9-1-升级到-9-2-0) | 在不变的网关与 REST 合同之下重建查询引擎：自定义查询后端与网关、改名的 Bean、9.1 声明文件需要修改；四项经批准的服务端行为例外需要在升级之前处理 |
| Wow 9.0.x | [从 9.0 升级到 9.1.0](#从-9-0-升级到-9-1-0) | 新增聚合能力：重新编译调用聚合构造函数的代码 |
| Wow v8.16.x | [从 8.16 升级到 9.0.0](#从-8-16-升级到-9-0-0)，再依次完成各个 9.x 章节 | 完整的 V8 → V9 路径：查询迁到 `QueryGateway` 与 `QueryBackend`（[V9 查询迁移](./query/v9-query-migration.md)），并删除 v8 的存储别名 |
| TypeScript 客户端使用 `fetcher-wow`、`fetcher-generator` 或 `fetcher-react` 的 Wow Hook | [从 Fetcher 包迁移](./typescript/migration.md) | 换用 `wow-client`、`wow-generator` 和 `wow-react`，并重新生成客户端代码 |

不要把首次采用 Wow 与 v6→v8 升级混成一次无法区分的发布。每个变更窗口都应选择一个 bounded context
及精确 source/target version。

## 文档边界

| 页面 | 负责 | 不负责 |
|---|---|---|
| 传统架构迁移 | 领域边界、历史导入、shadow 追平、读写切换 | Wow 版本/平台升级假设 |
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

## 从 9.8 升级到 9.9.0

9.9.0 给查询核心加入数据集：声明式的表状记录集（例如 ClickHouse 宽表），像聚合一样查询、限定范围、脱敏与审计，经 `/dataset/{context}/{dataset}` 提供。它还加入了在全部分组上计算的聚合窗口，以及展开元素时读取根记录的分组（`scope: ROOT`）。线上的一切变化都是新增。配置、请求头键、指标名与标签、既有的 REST 路由与线格式都不变，9.8.x 与 9.9.0 节点可以共处一个集群：9.8 节点的描述符不提供窗口与根分组，读取描述符的客户端（wow-client、视图引擎）只在提供的地方发送它们。下表的签名变化都属于 SPI 或内部实现（见 `docs/compat-debt.md` 的范围说明），没有弃用周期。只使用 starter 与公开入口 API 的应用无需修改源码，重新编译即可。

| 变化 | 影响谁 | 怎么做 |
|---|---|---|
| `QueryModelSchema(…, profile)`、`QuerySchemaCatalog(…, datasets)`、`QueryBackendStorages(…, datasetFactory, datasets)`、`SimpleQueryBackendProvider(…, dataset)` 与 `QueryModelCompiler.of(…, datasets)` 末尾多一个带默认值的参数 | 自行构建查询后端、Schema 提供方或 Catalog 的代码 | 重新编译 |
| `QueryModelProfile` 上的 `identityField`、`payloadField`、`eventTimeField` 与 `systemField()` 可为空；`requiredScope` 与 `systemField()` 改为抽象；`QueryModelProfile.of` 返回 `BuiltInQueryModelProfile`。快照与事件流的 profile 仍返回非空值 | 通过基类读取 profile 字段的代码 | 处理 `null`，或通过 `BuiltInQueryModelProfile` 读取 |
| `QueryModelSchema.profile` 扩展属性改为成员 | 导入 `me.ahoo.wow.query.schema.profile` 的代码 | 删除该导入 |
| `AnalysisDescriptor` 与 `AggregationSupport` 末尾多 `windows`、`rootGroups` 参数；`AggregationGroup.Terms`、`.Histogram`、`.DateHistogram`、`.DatePart` 及 `AggregationQueryDsl` 中对应的函数末尾多一个 `scope` | 传入全部参数的 Java 调用方；针对 9.8 编译的二进制 | 重新编译；Java 调用方给 `scope` 传 `null` |
| `QueryAutoConfiguration.querySchemaCatalog(…)` 多接收数据集与数据集后端工厂；`QueryProperties` 多 `datasets` | 直接调用它们的代码 | 按新签名重新编译 |
| `SnapshotQueryBackendSpec`（`wow-tck`）多两个根分组用例，由 `elementRootGroups`（默认 `true`）开启 | 运行该规范、但不能按根字段给元素分组的查询后端 | 覆盖 `elementRootGroups = false`，并声明 `AggregationSupport.rootGroups = SupportMode.NONE` |
| TypeScript：`RecordLayout` 多 `'kanban'`；`DERIVED_KINDS`、`DerivedKind` 与 `DerivedGap` 多了成员；`ANALYSIS_AUTO_RUN_MEMBERS` 多 `'top'`；wow-client 的 `DerivedExpressionType` 多了窗口节点；`AnalysisScope` 多 `rootAggregations` | 对这些联合类型做穷举 switch、或手工构造 `AnalysisScope` 的代码 | 处理新成员；用 `analysisScope()` 构造作用域 |

行为变化（不需要修改代码，但按旧文本匹配的调用方可能察觉）：

- 元素作用域的拒绝说明怎样够到字段：`Field [x] requires its declared element scope: reach it through ELEMENT_MATCH in a filter, or expand it with the aggregation's elements.`
- 命令等待超时会说明等的是什么，例如 `Waiting for stage [PROJECTED] timed out after 10000ms.`，不再是 Reactor 的内部文本。
- 缺少 ABAC 标签的拒绝改为 `Query requires the principal's ABAC tags.`，不再是 `Snapshot query requires…`；错误码不变。

## 从 9.7 升级到 9.8.0

9.8.0 把 `wow-core` 通用 `messaging` 包里的类移到各自所属的领域包，把存储批处理包移出 `infra`，并把指标装饰器移出指标端口包，使 `messaging` 不再导入命令、事件与事件溯源包，`infra` 不再导入指标包，指标端口不再导入任何领域包。`wow-query` 中，`QuerySchemaCatalog` 改为通过 `QueryStorageLocator` 读取存储，而不再依赖各读模型的后端工厂，使 `schema` 包不再导入读模型包。`wow-spring` 的查询网关注册收敛为一个模板。这些都属于内部实现（见 `docs/compat-debt.md` 的范围说明），没有弃用周期。配置、请求头键、指标名与标签、REST 与线格式都不变，9.7.x 与 9.8.0 节点可以共处一个集群。只使用 starter 与公开入口 API 的应用无需改动；导入了下表中类的代码按表修改后重新编译。

| 变化 | 影响谁 | 怎么做 |
|---|---|---|
| `TransportCommandBus` 与 `TransportServerCommandExchange` 从 `me.ahoo.wow.messaging.transport` 移到 `me.ahoo.wow.command.transport`；`TransportDomainEventBus` 与 `TransportEventStreamExchange` 移到 `me.ahoo.wow.event.transport`；`TransportStateEventBus` 与 `TransportStateEventExchange` 移到 `me.ahoo.wow.eventsourcing.state.transport`。`Transport`、`TransportMessageBus` 等 Transport SPI 仍在 `me.ahoo.wow.messaging.transport` | 自定义传输与总线 | 修改导入 |
| `EventCompensator` 与 `EventCompensateSupporter` 从 `me.ahoo.wow.messaging.compensation` 移到 `me.ahoo.wow.event.compensation`；`CompensationMatcher` 与 `CompensationTarget` 不动 | 自行补偿事件的代码 | 修改导入 |
| `CommandOperatorMessagePropagator` 与 `WaitPlanMessagePropagator` 从 `me.ahoo.wow.messaging.propagation` 移到 `me.ahoo.wow.command.propagation`；`MessagePropagator`、`MessagePropagators` 与其他传播器不动 | 手工组装 `MessagePropagators` 列表、或在 `META-INF/services` 文件中列出这些类的代码 | 修改导入与服务文件条目 |
| `me.ahoo.wow.infra.batch`（`BatchOptions`、`BatchCoordinator`、`BatchWriter`、`BatchItemResult` 与批处理异常）移到 `me.ahoo.wow.batch` | 用 `BatchOptions` 构建 MongoDB 或 Elasticsearch 存储、或直接使用批处理协调器的代码 | 修改导入 |
| `QuerySchemaCatalog(snapshots, eventStreams, compiler, aggregates, meterRegistry)` 改为 `QuerySchemaCatalog(storages, compiler, aggregates, meterRegistry)`：`storages` 是 `QueryStorageLocator`，返回某个聚合模型的 `QueryStorageAdapter` 或 `null` | 自行构建 Catalog 的代码（测试、工具、自定义的 `QuerySchemaCatalog` Bean）；starter 构建的 Catalog 不受影响 | 用 `QueryBackendStorages(snapshots, eventStreams)` 代替两个工厂参数 |
| `MetricDecoratorFactory` 与 `metered(...)` 扩展函数从 `me.ahoo.wow.metrics` 移到 `me.ahoo.wow.metrics.decorator`；`WowMetrics`、`MetricDescriptor` 等指标端口不动 | 不经 starter、自行为组件装饰指标的集成 | 改为导入 `me.ahoo.wow.metrics.decorator.MetricDecoratorFactory` 与 `me.ahoo.wow.metrics.decorator.metered` |
| `QueryGatewayRegistrar` 改为 `sealed`，删除 `registerQueryGateway(Map.Entry, BeanDefinitionRegistry)`、无参构造函数与 `appContext` 访问器 | 继承 `QueryGatewayRegistrar` 注册自定义查询网关的集成 | 不再继承；以 `snapshotQueryGatewayBeanName()` / `eventStreamQueryGatewayBeanName()` 为名注册自己的网关 bean，注册器会保留已存在的同名 bean |
| `PrepareKeyFactoryBean.setApplicationContext(ApplicationContext)` 改为 `setBeanFactory(BeanFactory)` | 直接调用该方法的代码；经 `PrepareKeyAutoRegistrar` 注册的 bean 不受影响 | 改为调用 `setBeanFactory` |

行为变化（不需要修改代码，但可能影响依赖旧行为的调用方或运维配置）：

- `SpringServiceProvider.getService(name)` 遇到未知名称时返回 `null`，不再抛出异常，与 `ServiceProvider` 的查找契约一致。依赖该异常的调用方改为检查 `null`，或改用 `getRequiredService`。
- 后端返回负数的 count 时，查询失败并返回 `500`（`Backend count must be greater than or equal to 0.`）；此前负数会原样返回给调用方。
- 主体缺少 ABAC 标签而被拒绝的查询（`403 IllegalAccessQueryScope`）改为记录 DEBUG 日志，不再记录带堆栈的 ERROR 日志；按 ERROR 日志告警的运维规则不会再因这类请求触发。
- `RetryableFilter` 现在总是包住 Projection 与 Stateless Saga 的函数 Filter，不再取决于 Filter 的注册顺序。使用 starter 的应用此前已是如此；自行组装 Filter 链、且函数 Filter 先注册的应用，可恢复异常会开始被即时重试。

## 从 9.6 升级到 9.7.0

9.7.0 整理 `wow-webflux` 的内部实现，并删除属于内部实现（不是公开入口 API）的弃用辅助函数，不必等到 v10（见 `docs/compat-debt.md` 的范围说明）。路由、REST、配置与线格式不变，9.6.x 与 9.7.0 节点可以共处一个集群。通过 starter 组装路由、且没有使用被删除辅助函数的应用无需改动；其余代码按下表修改。

| 变化 | 影响谁 | 怎么做 |
|---|---|---|
| 删除只绑定 handlerKey 的查询工厂：`Count`/`List`/`Paged`/`CursorQuery`/`Single` 的 `…SnapshotHandlerFunctionFactory` 及其 `…SnapshotState…` 变体、`Count`/`List`/`Paged`/`CursorQueryEventStreamHandlerFunctionFactory`、`SnapshotSchemaHandlerFunctionFactory` 与 `EventStreamSchemaHandlerFunctionFactory`。`*QueryHandlerFunctionFactory` 改为 final | 自行组装查询路由的代码 | 改用 `CountQueryHandlerFunctionFactory`、`ListQueryHandlerFunctionFactory` 等，传入 `BuiltInHttpRouteHandlerKeys.Snapshot.*` 或 `Event.*`；`*_STATE` 路由再传 `rewriteResult = { it.toStateDocument() }`（分页用 `toStateDocumentPagedList()`，游标用 `toStateDocumentCursorPage()`）。Schema 路由改用 `QuerySchemaHandlerFunctionFactory(handlerKey, …)` |
| 删除 `AbstractLoadAggregateHandlerFunction`、`LoadVersionedAggregateHandlerFunction(Factory)` 与 `LoadTimeBasedAggregateHandlerFunction(Factory)` | 自行组装状态加载路由的代码 | 改用 `LoadAggregateHandlerFunction(Factory)`，传 `route = StateLoadRoute.VERSIONED` 或 `StateLoadRoute.TIME_BASED` |
| `DefaultCommandMessageExtractor` 与 `RouterFunctionBuilder` 去掉次构造函数；`EMPTY_OK` 改为私有 | 针对它们编译的代码 | 重新编译：同样的参数经主构造函数传入 |
| tracing 请求带 `limit` 且读取事件流末尾失败时，在响应开始前以错误本身的状态码返回；原先事件流请求会先回 `200` 再发错误事件 | tracing 路由的 SSE 客户端 | 无需改动 |
| 删除 `ServerRequest` 的身份读取函数：`getTenantId`、`getTenantIdOrDefault`、`getOwnerId`、`getSpaceId`（两个重载）与 `getAggregateId`（三个重载） | 从请求读取身份的自定义处理器 | 改用 `identity(aggregateMetadata)` 或 `identity(aggregateRouteMetadata)`（`me.ahoo.wow.webflux.route.identity`）：`tenantId()`（原 `getTenantIdOrDefault` 加 `?: TenantId.DEFAULT_TENANT_ID`）、`ownerId()`、`aggregateId()`、`spaceId()`、`requestId()`。接收 `AggregateRoute.Owner` 的重载没有替代：按聚合自身的所有者策略 |
| 删除 `Throwable.toResponseEntity()` 与 `ErrorInfo.toServerResponse()` | 自行把错误映射为响应的代码 | 改用 `WebFluxErrorStrategy.toServerResponse`，或 `RequestExceptionHandler` Bean |
| 删除 `MessageBus.receive(subscription)`，`wow-tck` 的 `DefaultMethodContract.COMPAT_ADAPTERS` 随之删除 | 把总线当普通流读取的代码 | `receiver(subscription).openedMessages()` |
| 删除静态调用 `RecoverableExceptionRegistrar.register`、`unregister`、`getRecoverableType` | 仍用 9.2 静态调用分类异常的代码 | `RecoverableExceptionRegistry.DEFAULT` 的同名方法，或 `RecoverableExceptionProvider` |
| 删除 `wow-openapi` 的 `CommandComponent.Header`、`CommonComponent.Header` 与 `me.ahoo.wow.openapi.BatchResult` 类型别名 | 从 `wow-openapi` 读取请求头名或 `BatchResult` 的代码 | `wow-rest-contract` 的 `me.ahoo.wow.rest.CommandHeaders`、`WowHeaders` 与 `BatchResult`；请求头的值不变 |

## 从 9.5 升级到 9.6.0

`wow-openapi` 在 9.6.0 中把路由合同改为纯数据（设计：`documentation/designs/2026-10-09-wow-openapi-refactor-design.md`（在 `main` 分支））。路由、route id 与生成的 OpenAPI 文档不变；REST、OpenAPI 与线格式不变，9.5.x 与 9.6.0 节点可以共处一个集群。针对 9.5 编译的代码需要重新编译；实现 `RouteContributor` 或构造路由合同的代码需要按下表修改源码。

| 变化 | 影响谁 | 怎么做 |
|---|---|---|
| 修复：`RouterSpecs.mergeOpenAPI` 可以并发调用：文档只渲染一次（启动时由 `buildDocumentation()` 完成），每次调用合并其副本，不生成 Schema、不阻塞 | 从多个线程渲染文档的服务，例如多个 Springdoc 分组 | 无需操作 |
| 修复（9.6.1）：所有宿主的 OpenAPI 文档都不再列出视图存储关闭的路由（`DELETE …/view_preferences/{id}`、`PUT …/view/{id}/recover`、事件查询等）。此前，宿主的组件扫描覆盖 `me.ahoo.wow.viewstore.starter` 时，视图存储的 customizer 先于 Wow 的执行，这些路由仍留在文档中。Wow 的 customizer 现在在合并路由后立即应用 `OpenApiDocumentFilter` Bean；springdoc customizer 的执行顺序不变。REST 不变：这些路由一直被拒绝 | 组件扫描覆盖该 starter 包的视图存储宿主 | 无需操作；从这类宿主的文档生成的客户端需重新生成，被拒绝路由的方法随之消失。若要不论 customizer 顺序都修改文档中 Wow 的路由，声明 `OpenApiDocumentFilter` Bean |
| 渲染错误（例如同一 key 的两个不同组件）在存在 Springdoc 时会让应用启动失败（文档由 `buildDocumentation()` 渲染），而不是在第一次请求 `/v3/api-docs` 时出错；在事件循环线程上首次调用 `mergeOpenAPI` 会失败并提示调用 `buildDocumentation()` | 提供 OpenAPI 文档的应用；自行调用 `mergeOpenAPI` 的库代码 | 修正报错的路由合同；在请求线程上合并之前，启动时先调用 `buildDocumentation()` |
| 修复：上下文内联 Schema 时（`OpenAPIComponentContext.default(inline = true)`），组件被内联，而不是输出指向从未登记的组件的 `$ref` | 使用内联上下文渲染的文档 | 无需操作 |
| `RouteContributor.contributeGlobal(currentContext)` 与 `contributeAggregate(currentContext, aggregateRouteMetadata)` 去掉 `componentContext` 参数；删除 `id`、`order`、`category` 以及 `RouteContributors`、`RouteCategory` | 自定义贡献者 | 删除该参数与三个属性（目录自己排序路由）。构造合同时不再登记组件或生成 Schema，而是引用它们：Schema 用 `HttpSchema.TypeRef(type, typeArguments)`，组件用 `HttpComponent.parameter(key) { … }`（或 `header`、`requestBody`、`response`） |
| `HttpParameter`、`HttpHeader`、`HttpRequestBody`、`HttpResponse` 的 `componentRef: String?` 改为 `component: HttpComponent<…>?`；删除 `HttpRouteContract.pathSummary`、`pathDescription` | 构造或读取合同的代码 | 传入由工厂函数创建的 `HttpComponent`，渲染器以其 key 登记。同一类组件的 key 必须唯一：同类同 key 的两个组件实例，除非构建出的组件相等（在 Schema 生成之后比较），否则渲染失败。不能再为 path item 设置与第一个路由不同的 summary 或 description：path item 取第一个路由的 summary 与 description |
| `HttpSchema.TypeRef(mainTargetType, typeParameters: List<Type>)` 改为 `TypeRef(type, typeArguments: List<TypeRef>)`；删除 `HttpSchema.ComponentRef`、`Long`、`Boolean`、`Formatted`、`Unspecified` | 构造合同 Schema 的代码 | 类型参数包成 `TypeRef`；按类型用 `TypeRef` 引用 Schema 组件；静态 Schema 用 `Raw` |
| 删除 `RouterSpecs.mergeOpenAPIFromCatalog` | 调用方 | 改用 `mergeOpenAPI` |
| 删除 `CommonComponent`、`QueryComponent`、`CommandComponent`、`EventComponent` 中生成组件的函数（`errorCodeHeader()`、`badRequestResponse()` 等），删除 `BatchComponent`（其 `PathVariable` 常量移到 `me.ahoo.wow.rest.RouteVariables`，见下表）；弃用别名 `CommonComponent.Header`、`CommandComponent.Header` 保留 | 调用这些函数的代码 | 9.6.1 起使用 `WowComponents`：合同中用 `WowComponents.errorCodeHeader`、`badRequestResponse`、`notFoundResponse` 等；组件构建函数中（`errorCodeHeader()` 原先返回放进 `ApiResponse` 的 swagger `Header`）用 `context.ref(WowComponents.errorCodeHeaderComponent)`。或定义自己的 `HttpComponent` |
| 删除 `me.ahoo.wow.openapi.QueryComponent`、`me.ahoo.wow.openapi.aggregate.event.EventComponent` 与 `CommonComponent.Response`：它们只是内置路由的组件名 | 读取这些常量的代码 | 直接写出取值：查询请求体为 `wow.SingleQuery`、`wow.CountQuery`、`wow.ListQuery`、`wow.PagedQuery`、`wow.CursorQuery`、`wow.AggregationQuery`（聚合专属的为 `{context}.{aggregate}` 加 `.SingleQuery` 等，字段枚举为 `{context}.{aggregate}.{Aggregate}AggregatedFields`）；查询字段扩展为 `x-wow-query-fields`；`COMPENSATION_TARGET_KEY` 为 `wow.CompensationTarget`；`UNSUPPORTED_MEDIA_TYPE_ERROR_CODE` 为 `UnsupportedMediaType` |
| 删除 `ApiResponseBuilder.listContent(context, type, …)` | 调用它的代码 | 用 `content(mediaType, schema)` 分别加入 JSON 数组与 `text/event-stream` 媒体类型 |
| `OpenAPIComponentContext.componentSchema` 不再有默认实现 | 自定义 `OpenAPIComponentContext` 实现 | 实现 `componentSchema` |
| 改为 internal：`Https`、`PathBuilder`、`RouteIdSpec`、`Tags`、`OpenAPIExtensions`、`RouteCatalogBuilder`、`OpenApiRenderer`、`DefaultOpenAPIComponentContext`、`AggregateRouteMetadataParser`、`CommandRouteMetadataParser`、`BoundedContextSchemaNameConverter` 的 companion（`resolveName`），以及内置贡献者 `StateRouteContributor`、`CommandFacadeRouteContributor`、`CommandWaitRouteContributor`、`GenerateGlobalIdRouteContributor`、`GetWowMetadataRouteContributor` | 使用它们的代码 | `Https` 常量：直接写字符串（`"GET"`、`"200"`、`"application/json"`）；route id、路径与 tag：直接写在合同里；文档：`RouterSpecs.mergeOpenAPI`；目录：`RouterSpecs.toRouteCatalog()` 或 `RouteCatalog(routes)`；上下文：`OpenAPIComponentContext.default(…)`；路由元数据：`aggregateRouteMetadata()` 与 `commandRouteMetadata()`；内置路由：`DefaultRouteContributors.all()`。info 扩展 `x-wow-version`、`x-wow-context-name`、`x-wow-context-alias` 不变 |
| 标为 `@InternalWowApi`（供 Spring Boot Starter 使用，需要 opt-in，不属于 API）：`CommandRouteContributor`、`SnapshotRouteContributor`、`EventRouteContributor`、`GenerateBIScriptRouteContributor` | 引用它们的代码 | 内置路由用公开的 `DefaultRouteContributors.all()`，用 `RouteContributor`（Spring 中为 Bean）添加路由；否则可以 `@OptIn(InternalWowApi::class)`，自担风险 |
| `HttpComponent` 构建函数的 `context`（`HttpComponentContext`）只生成 Schema（`inline`、`schema`、`arraySchema`、`resolveType`、`componentSchema`）并引用其他组件（`ref`），不再继承 `OpenAPIComponentContext`；`RouterSpecs.componentContext` 改为 private（构造参数保留） | 通过 `context.parameter(…)` 等登记组件的构建函数；读取 `RouterSpecs.componentContext` 的代码 | 用 `context.ref(component)` 引用其他组件；自行保留传给 `RouterSpecs` 的上下文 |
| 文档中的 `Schema` 实例由每次 `mergeOpenAPI` 共享；原始 `/v3/api-docs` 中 `components` 各 Map 的 key 顺序有变化（JSON 等价） | 原地修改 Schema 的 `OpenApiCustomizer`；比较原始文档文本的工具 | 修改 Schema 前先复制；按 JSON 比较文档 |

REST 线格式词汇从 `wow-openapi` 移到新模块 `wow-rest-contract`（包 `me.ahoo.wow.rest`），`wow-openapi` 以 `api` 依赖引入它：

| 9.5 | 9.6.0 |
|---|---|
| `me.ahoo.wow.openapi.RouteSuffixes` | `me.ahoo.wow.rest.RouteSuffixes` |
| `me.ahoo.wow.openapi.contract.BuiltInHttpRoutePaths.Global` | `me.ahoo.wow.rest.RoutePaths` |
| `me.ahoo.wow.openapi.BatchComponent.PathVariable`（`BatchComponent` 已删除） | `me.ahoo.wow.rest.RouteVariables` |
| `me.ahoo.wow.openapi.contract.bi.*` | `me.ahoo.wow.rest.bi.*` |
| `CommandComponent.Header`（弃用别名，9.7.0 删除） | `me.ahoo.wow.rest.CommandHeaders` |
| `CommonComponent.Header`（弃用别名，9.7.0 删除） | `me.ahoo.wow.rest.WowHeaders` |
| `me.ahoo.wow.openapi.BatchResult`（弃用 `typealias`，9.7.0 删除；类本身已迁移，需要重新编译） | `me.ahoo.wow.rest.BatchResult` |

没有弃用别名的声明需要修改 import。OpenAPI Schema 名仍是 `wow.openapi.*`；`GET /wow/metadata` 的 `wow.openapi` 上下文多列出作用域 `me.ahoo.wow.rest`。

`wow-apiclient` 改为依赖 `wow-rest-contract` 与 `wow-query`，不再依赖 `wow-openapi`。它不再带来 `wow-openapi`、`wow-schema`、swagger-core 与 swagger-annotations、victools jsonschema 模块（编译 classpath），也不再带来 `wow-models`（运行时 classpath）。用到其中任何一个的客户端需自行声明：`io.swagger.core.v3:swagger-core-jakarta`、`me.ahoo.wow:wow-openapi` 或 `me.ahoo.wow:wow-models`。用 Springdoc 提供自身文档、依赖 `wow-openapi` 以服务形式注册的 `BoundedContextSchemaNameConverter` 模型转换器的 BFF 也是如此：加入 `wow-openapi` 以保留 Wow 的 Schema 名。

## 从 9.4 升级到 9.5.0

对 `wow-schema` 与 `wow-openapi` 而言，9.5.0 是一次内部重构（设计见 `documentation/designs/2026-10-09-wow-schema-refactor-design.md`（在 `main` 分支））。生成的 JSON Schema、OpenAPI 文档和查询模型与 9.4.0 逐字节相同，REST、存储与线上格式不变，9.4.x 与 9.5.0 节点可以共用一个集群。基于 9.4 编译的代码需要重新编译；直接使用 Schema builder 的代码可能需要按下表修改源码：为了保持模块整洁，这部分没有保留源码兼容。9.5.0 发布说明（[Releases 页面](https://github.com/Ahoo-Wang/Wow/releases)）逐条列出每项变化及其 PR。

| 变化 | 影响谁 | 怎么做 |
|---|---|---|
| 修复：并发生成 Schema 时，Kotlin getter-only（计算）属性不再随机缺失 | 有时缺少计算属性的查询模型和 OpenAPI 文档 | 无需操作，字段每次都会出现 |
| `OpenAPISchemaBuilder`、`InlineSchemaCapable` 从 `wow-schema`（`me.ahoo.wow.schema.openapi`）迁入 `wow-openapi`（`me.ahoo.wow.openapi.schema`） | 使用它们的代码 | import 改为 `me.ahoo.wow.openapi.schema`；classpath 上只有 `wow-schema` 的要添加 `me.ahoo.wow:wow-openapi`（启用 `openapi-support` 的应用已经有） |
| Schema 的实现类改为内部：各 definition Provider、Kotlin 检查与 getter Provider、`@Summary`/`@Description` 解析器、`WowSchemaLoader`、`JsonSchema`、`Types`、`SchemaMerger`、`JavaTypeResolver` | 注册或继承过它们的代码，特别是 `TypedCustomDefinitionProvider`、`MessageDefinitionProvider`、`AbstractStateAggregate` | 通过 `SchemaGeneratorBuilder` 注册 `WowModule`、`KotlinModule` 或 `JodaMoneyModule`，不要逐个注册 Provider 或检查；自定义类型实现 victools 的 `CustomDefinitionProviderV2`，在 `customizer { … }` 里添加 |
| `OpenAPISchemaBuilder` 不再替换传入 builder 的 `schemaNamingModule` | 之后还读取该 builder 命名模块的代码 | 无需操作，builder 保留你的设置 |
| 删除：`SchemaGeneratorBuilder.openapi31` 与 `openapi31(Boolean)`（从未生效）、`typeContext`、`requiredTypeContent` | 调用它们的代码会编译失败 | 删掉 `openapi31` 调用；需要 type context 时用 `buildConfig()` 加 `TypeContextFactory.createDefaultTypeContext(config)` |
| Schema 生成器和 `OpenAPISchemaBuilder` 都不是线程安全的 | 跨线程共享同一个实例的代码 | 每个线程各建一个；`SchemaGeneratorBuilder.copy()` 让每个实例拥有自己的设置 |

## 从 9.3 升级到 9.4.0

9.4.0 只改了 `wow-bi`（ClickHouse BI 脚本生成器）。其余部分的 API、REST 行为、配置、存储与线上格式都与 9.3 相同，9.3.x 与 9.4.0 节点可以共用一个集群；BI 部署重建之后只从 9.4.0 节点生成 BI 脚本，9.3 节点会拒绝 layout 8。`wow-bi` 没有兼容性负担：它的 ClickHouse 布局是 v9 存储冻结的唯一例外，变化时不提供迁移路径，也不经弃用周期。9.4.0 发布说明（[Releases 页面](https://github.com/Ahoo-Wang/Wow/releases)）逐条列出每项变化及其 PR。

| 变化 | 影响谁 | 怎么做 |
|---|---|---|
| BI layout 8：anchor 的持久对象清单取代 ownership registry | 每个既有 BI 部署 | 用 9.4.0 执行一次确认后的 `RESET`，再执行一次 `DEPLOY`，然后手工删除旧的 `__wow_bi_registry_<deploymentId>` 表，见 [BI 部署与恢复：升级](./bi-operations#升级)。9.3 及更早版本拒绝 layout 8 的部署，回滚只能恢复 ClickHouse 备份 |
| `DEPLOY` 只渲染变化 | 审阅与执行 BI 脚本的人 | 幂等 `DEPLOY` 只有两条 `CREATE DATABASE IF NOT EXISTS` 与 anchor；漂移只替换漂移的 view，或该 stream 的 consumer 链 |
| 清单中的 store 或 queue 缺失时 `DEPLOY` 拒绝（400） | 丢了 BI 表的部署 | 备份后执行确认后的 `RESET` |
| Kotlin API：新增 `BiScriptService`；观测模型改为 internal；删除 `BiScriptGenerator.generate(namedAggregates, …)` | 直接调用 `wow-bi` 或实现 `BiDeploymentInspector` 的代码 | 调用 `BiScriptService` 或 `generate(prepare(…), …)`；自定义 inspector 可以包装内置实现，但不能再构造 `BiDeploymentInspection.Available` |

`POST /wow/bi/script` 路由、请求选项与 `wow.bi.script.*` 配置不变；使用 `kafkaOffsetStorage = KEEPER` 时，执行会修复 consumer 的 `DEPLOY` 之前先读 [BI 部署与恢复](./bi-operations#操作决策) 里关于 consumer 漂移的规则。

## 从 9.2 升级到 9.3.0

9.3.0 重做了写侧、传输与 API 分层。REST 路由与请求体、存储格式、消息 JSON、Kafka 主题与消费组都不变，所以滚动升级期间 9.2.x 与 9.3.0 节点可以共用一个集群。Kotlin API 有变化：9.3.0 **不保留二进制兼容垫片**（只为让 9.2 字节码继续链接而保留的声明），被替换的面向应用的 API 保留**一个 `@Deprecated` 周期**，10.0.0 删除，清单见[兼容性债务](https://github.com/Ahoo-Wang/Wow/blob/main/docs/compat-debt.md)。只有后端、传输或框架实现者才会接触的扩展点 SPI 直接修改，不经弃用。9.3.0 发布说明（见 [Releases 页面](https://github.com/Ahoo-Wang/Wow/releases)）逐条列出每项变化及其 PR。

下面各节按应用遇到的可能性排序：前四节每个应用都要看，有 REST 客户端的应用还要看第五节，后面的主要涉及自定义扩展。

### 升级之前

1. **还在更早的 9.2.x 上的，先升级到 9.2.4**：9.3.0 发布前，混部门禁（`MixedVersionClusterTest`）让已发布的 9.2.4 镜像与 9.3.0 的代码共用一个 MongoDB 和一个 Kafka、加入相同的消费组，双向验证了命令、快照、Saga 与本地优先副本。门禁现在以 9.3.0 为上一版本。
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

上表中 `bus.receive`、`RecoverableExceptionRegistrar` 的静态调用、`ServerRequest` 的身份读取函数与两个错误辅助函数已提前在 9.7.0 删除（见[从 9.6 升级到 9.7.0](#从-9-6-升级到-9-7-0)）。

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
- **滚动升级。** 9.2.x 按主题的消费者与 9.3.0 按上下文的消费者可以加入同一个消费组：Kafka 的分配器只在订阅了某主题的成员之间分配该主题，分区带着已提交的偏移量移交。发布前，混合版本门禁在命令与 Saga 驱动的订单持续流转时先重启 9.3 成员、再重启 9.2.4 成员，检查了没有命令丢失或被重复应用。所有成员的 `partition.assignment.strategy` 应保持一致。见 [Kafka 消费组](./extensions/kafka.md#消费者组)。
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

## 从 9.1 升级到 9.2.0

9.2.0 在不变的 `QueryGateway` 与 REST 合同之下重建了查询引擎，新增查询能力描述，并让 TypeScript 包随 Wow 一起发布。现有聚合的 REST、存储与线上格式不变，只有[服务端行为](#server-behaviour-9-2)下列出的四项经批准的例外，所以滚动升级期间 9.1.x 与 9.2.x 节点可以共处一个集群，差异见各项说明；请尽量缩短这段混部时间。TypeScript 应用按[从 Fetcher 包迁移](./typescript/migration.md)切换。

### 升级之前 {#before-9-2}

1. 在每个依靠空间请求头隔离数据的聚合上声明 `spaced = true`（自 9.3.0 起为 `@Spaced`）；Saga 由非空间聚合的事件创建空间聚合时，显式设置空间。
2. Elasticsearch：对 Wow 自带定义的索引（视图存储与补偿的 `execution_failed` 快照）授予 `view_index_metadata` 与 `create_index`，在 `auto-init-template=true`（默认）时还要授予集群权限 `manage_index_templates`；把小数映射为整数的索引重建一次（[重建索引](./extensions/elasticsearch.md#reindex-existing-index)）。
3. 把 9.1 的查询 Schema 声明文件转换为 9.2 格式（[迁移 9.1 的声明文件](./query/query-model-schema.md#declaration-91)）。
4. 按新名字覆盖改名的 Spring Bean（见下表）。
5. 解析 `/schema` 响应体的客户端改用能力描述；按错误文案匹配查询错误的客户端改为按错误码匹配。

### Kotlin 与 SPI 变化 {#kotlin-9-2}

| 变化 | 受影响的代码 | 怎么做 |
|---|---|---|
| `QueryBackend` 接收 `AdmittedQuery`，实现四个原语（`stream`、`page`、`count`、`aggregate`）和一个游标位置编解码器；存储通过 `QueryBackendProvider` 注册（取代 `SnapshotQueryBackendFactoryBinding` 与 `EventStreamQueryBackendFactoryBinding`），`QueryStorageAdapter` 取代 `QuerySchemaBackendAdapter` | 自定义存储，以及继承内置 Backend 或传入自定义过滤编译器的代码 | 实现四个原语，并以存储名注册 `QueryBackendProvider`；见[查询后端](./query/query-backend.md) |
| `QueryCapability` 改为枚举；`AggregationExpression` 改为 sealed；`AggregationGroup.field` 可空；`QueryRequestScope.resolve` 返回 `QueryScope`；`QueryContext` 新增两个必填参数；`HttpQueryGuard` 只接受三个构造参数且不再带预算（有界操作经 `guard.of(gateway)`）；`BindingError`、`Terms`、`Histogram` 的 `copy(...)` 改变了 JVM 签名 | 构造或继承这些类型的代码 | 重新编译；把 `QueryCapability(...)` 换成枚举常量 |
| 按 9.1 编译的自定义 `QueryGateway` 拿到的是 `QueryEntryPolicy.DEFAULT` 而不是配置的 `wow.query.*` 预算，它的 `describe`（`GET …/schema` 背后）以 503 `QuerySchemaUnavailable` 失败 | 自定义网关 | 重新编译，传入配置的策略并实现 `describe`，或者继承 `AbstractQueryGateway` |
| 标注 `@InternalWowApi` 的类型出现在公开签名里：`QueryModelProfile.systemField`，以及 TCK 的 `SnapshotQueryBackendSpec` 与 `FilterSemantics`（现位于 `wow-tck` 的 `me.ahoo.wow.tck.query`） | 第三方后端与 TCK 使用者 | 加上 `@OptIn(InternalWowApi::class)` 或对应的编译参数 |
| Spring Bean 改名：`noOp*QueryBackendFactory` → `unavailable*QueryBackendFactory`；`jsonQuerySchemaSource` → `inferredQuerySchemaSource`；`mongo*QueryBackendFactoryBinding` / `elasticsearch*QueryBackendFactoryBinding` → `mongo*QueryBackendProvider` / `elasticsearch*QueryBackendProvider`；`querySchemaCatalog` 移到 `QueryAutoConfiguration` | 按 Bean 名覆盖其中之一的宿主：覆盖会被静默忽略 | 按新名字覆盖 |
| 查询 Schema 声明只从 `META-INF/wow/query-schema/{ctx}.{agg}.{model}.json` 或 `config/wow/query-schema/…` 读取，且须为 9.2 格式。位于 9.1 位置 `wow-query-schema/{ctx}/{agg}/{model}.json` 的文件会被跳过并给出警告，该模型使用推断的 Schema（`wow.query.schema.legacy-declarations=fail` 则直接失败）；新路径上的 9.1 格式文件会让该模型的 Schema 失败 | 有声明文件的应用 | 转换文件（[迁移 9.1 的声明文件](./query/query-model-schema.md#declaration-91)）；滚动升级期间把 9.1 文件留在旧位置，并给每个 9.2 节点在 `config/wow/query-schema/` 放上 9.2 文件 |
| 新的启动检查：同一方法下模板只有变量名不同的两条路由在启动时失败并同时报出两条（其中一条原本就无法到达），被包含的路由先于包含它的路由匹配；`wow.query.require-authenticated-scope=true` 要求 `wow.webflux.state.point-read-admission=true` | 有这类路由或配置的服务 | 删掉无法到达的那条路由；两个属性一起设置 |
| 查询错误文案用框架自己的话说明错在哪里（`limit must be between 1 and 10000.`）；状态码与错误码不变 | 按文案匹配的客户端 | 改为按错误码匹配 |
| 删除或改为内部的辅助方法：`FilterNormalizer.normalize(...)`（改经 `QueryAdmission.Trusted` 准入）、`ICursorQuery.withUniqueSort`、`selectTopRows`、`HttpQueryGuard.of(QueryBudget)`、`FilterExpression.walkFilterNodes()` / `filterNodeCount()`、`FieldArity`、残余算子（`DenseFill`、`BoundedTopRows`、`matchesHaving` 等）、`ResolvedField.definition` / `value` / `bindings`、`AdmittedQuery.schema`、`requireScalarMetricFilterFields`、`EventStore.appendResolvingOutcome`、`QueryModelSchemaMetadata`、`QueryValueSchemaMetadata` 与 `toMetadata()` | 调用它们的代码 | 改用网关或 `QueryAdmission`；内部算子没有公开的替代 |

已弃用、在 v10 之前仍然有效（见 `docs/compat-debt.md`）：9.1 的脱敏注解（`@Mask`、`@KeepMask`、`@Masking`）与旧的 `QueryTemporal`，分别按 `@Sensitive` 与新的 `@QueryTemporal` 读取；`wow.webflux.query.max-*` 键，在对应的 `wow.query.http.*` 键未设置时读取并给出警告；9.1 补偿控制台的链接，会重定向。

### 服务端行为 {#server-behaviour-9-2}

每一项都是对 v9 冻结的经批准的例外，或 9.2 节点需要的部署步骤。

| 变化 | 升级之前 | 9.1/9.2 混部集群 |
|---|---|---|
| 非空间聚合不再从请求、命令构造器或 Saga 取空间：它的命令带默认空间 `""`，它的查询不再按 `Wow-Space-Id`（或 `CoSec-Space-Id`）过滤。空间也不再经过非空间聚合传到 Saga 创建的空间聚合（[详情](./data-access.md#从-9-1-升级)） | 上面第 1 步 | 9.1 节点按请求头的空间写入并过滤，9.2 节点不会，所以同一查询可能因应答节点不同而返回不同的行；网关、聚合节点与查询节点一起升级。回滚之后，9.2 在空间 `""` 写下的记录，对发送空间请求头的客户端会被拒绝或隐藏 |
| `GET …/snapshot/schema` 与 `GET …/event/schema` 返回能力描述（`QueryModelDescriptor`），而不是 9.1 的 Schema 元数据；`POST …/schema/refresh` 作为弃用别名保留到 10.0.0 | 上面第 5 步 | 同一路由按应答节点以两种形状之一应答；9.2 的视图引擎会回退到宿主声明的定义 |
| 游标 token 带有版本以及聚合、读模型与排序的指纹；9.1 签发的、或属于别的模型或排序的 token 以 `Invalid cursor.`（400）拒绝 | 无需操作：客户端重新开始一次游标 | 一个版本签发的 token 会被另一个版本拒绝 |
| Elasticsearch：`META-INF/wow/elasticsearch/` 下的索引定义在启动时创建（需要第 2 步的权限）；整数字段上的小数只提供存在性过滤（过滤与排序返回 400 `QuerySchemaValidation`）；`exists`、`isNull`、`notExists`、`isEmpty` 也读取 `_ignored` | 上面第 2 步 | 9.1 节点仍对这类小数过滤和排序，存在性判断也不看 `_ignored` |

### 其他行为变化 {#behaviour-9-2}

可见但不破坏兼容；除非条目另有说明，无需迁移：

- **Elasticsearch。** 查询尚不存在的索引时返回空，而不是 503（根据模板模拟 Schema 需要 `manage_index_templates`）；索引映射与其定义不一致时，启动日志给出一条警告并列出路径；新索引把 `state` 下的动态字符串映射为带 `.keyword`（上限 8191）的字段、浮点值映射为 `double`——两个版本都开着 `auto-init-template=true` 时，最后启动的节点决定模板，所以升级期间在 9.1 节点上设为 `false`，或在升级后重启一个 9.2 节点；`ignore_above` 不小于 8191 的 keyword 变为可查询；超出 `index.max_result_window` 的分页在准入时以 400 拒绝；去重计数每个桶在 40000 以内是精确的。
- **查询错误与准入。** 服务端故障以 500 `InternalServerError` 应答而不是 400，且不再带出驱动的文字；超过 32 个排序字段或重复同一字段的排序会被拒绝；`wow.query.http.allow-expensive-operators=false` 时，HTTP 也拒绝 `DATE_PART` 分组、补齐与 `FIRST`/`LAST`；MongoDB 上错误的日期操作数返回 400 而不是 5xx。
- **运维。** 示例、补偿与视图存储镜像只在授权时显示健康详情；5xx 响应以 `ERROR` 级别连同堆栈记录，客户端的 4xx 只记一行。
- **视图存储。** 如果部署视图存储（9.2 新增），部署之前先在网关把 `…/tenant/(platform)/owner/(system)/**` 限制为管理员；服务端没有控制谁能写系统视图的设置。

## 从 9.0 升级到 9.1.0

9.1.0 在 MongoDB 与 Elasticsearch 上新增聚合能力：`DISTINCT_COUNT`、`STDDEV`、`VARIANCE`、`PERCENTILE`、指标级过滤、派生指标、`HAVING`、补齐的日期直方图与 `TERMS.missingKey`。现有聚合 JSON 不变。

- 重新编译调用 `AggregationGroup`、`AggregationMetric`、`AggregationQuery` 构造函数的 Kotlin 与 Java 代码：它们新增了带默认值的末尾参数，JVM 签名随之改变。
- 补齐的日期直方图需要 MongoDB 5.1 或更高版本。

## 从 8.16 升级到 9.0.0

9.0.0 用 `QueryGateway` → `QueryBackend` 架构取代 `QueryService`，并删除 v8 保留的存储别名。事件与快照的数据布局不变。

1. 按 [V9 查询迁移](./query/v9-query-migration.md) 迁移查询、过滤、脱敏与自定义后端。
2. 替换已删除的存储别名：

| 9.0.0 删除的 | 改用 |
|---|---|
| `SnapshotRepository`、`NoOpSnapshotRepository`、`InMemorySnapshotRepository`、`ElasticsearchSnapshotRepository`、`TracingSnapshotRepository` | `SnapshotStore` 及其实现（`NoOpSnapshotStore` 等） |
| `*SnapshotRepository` Spring Bean 别名（`mongoSnapshotRepository`、`inMemorySnapshotRepository` 等） | `*SnapshotStore` Bean |
| 存储路由 Bean `<storage>-snapshot-query-service-factory` 与 `<storage>-event-stream-query-service-factory` | `<storage>-snapshot-query-backend-factory` 与 `<storage>-event-stream-query-backend-factory`（9.2 又由 `QueryBackendProvider` 取代） |
| `AggregateSchemaInitializer` 上已弃用的 MongoDB 建索引辅助方法（`createAggregateIdIndex`、`createRequestIdUniqueIndex` 等） | 自己的集合需要的索引自行创建 |
| Elasticsearch 的 `UNLIMITED_SIZE` 与 `Int.searchSize()` | 无需替代：列表查询使用 PIT 与 `search_after` |

然后按版本从旧到新，依次完成上面各节，直到目标版本。

## 旧链接导航

旧单页主题已拆分到三份专项指南。以下标题与显式 alias 保留既有 deep link。

### 版本升级指南

<span id="升级步骤"></span>
<span id="依赖版本更新"></span>
<span id="破坏性变更检查"></span>

参见 [v6 → v8：通用升级步骤](https://github.com/Ahoo-Wang/Wow/blob/v8.16.3/documentation/docs/zh/guide/migration/v6-to-v8.md)。

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
和 [v6 → v8：破坏性变更检查](https://github.com/Ahoo-Wang/Wow/blob/v8.16.3/documentation/docs/zh/guide/migration/v6-to-v8.md)。

### 已知问题

<span id="版本特定问题"></span>
<span id="常见迁移问题"></span>

参见 [Release Notes](https://github.com/Ahoo-Wang/Wow/releases) 和
[故障排查](./troubleshooting.md)。应用 workaround 前，应先在精确固定 tag 上复现失败。

### 迁移检查清单

使用 [传统架构迁移检查清单](./migration/traditional-architecture.md#完成检查清单)
或 [v6 → v8 验证清单](https://github.com/Ahoo-Wang/Wow/blob/v8.16.3/documentation/docs/zh/guide/migration/v6-to-v8.md)，再补充环境特定的生产准入证据。

### 回滚计划

使用所选专项指南的回滚步骤，并遵守[共同完成门禁](#共同完成门禁)中首次写入前后的区分。

### 统一运行时编排

参见 [运行时编排迁移](./migration/runtime-orchestration.md)。

### 移除版本化快照检查点

参见 [v6 → v8：移除版本化快照检查点](https://github.com/Ahoo-Wang/Wow/blob/v8.16.3/documentation/docs/zh/guide/migration/v6-to-v8.md)。

### SnapshotStore 原子保存

参见 [v6 → v8：SnapshotStore 原子保存](https://github.com/Ahoo-Wang/Wow/blob/v8.16.3/documentation/docs/zh/guide/migration/v6-to-v8.md)。

### Redis EventStore Canonical v2 布局（v8.9.0 引入）

参见 [v6 → v8：Redis EventStore Canonical v2 布局](https://github.com/Ahoo-Wang/Wow/blob/v8.16.3/documentation/docs/zh/guide/migration/v6-to-v8.md)。

### Mongo 所有权保护

参见 [v6 → v8：Mongo 所有权保护](https://github.com/Ahoo-Wang/Wow/blob/v8.16.3/documentation/docs/zh/guide/migration/v6-to-v8.md)。

## 相关页面

| 页面 | 关系 |
|---|---|
| [传统架构迁移](./migration/traditional-architecture.md) | 首次采用与流量所有权 |
| [Wow v6 迁移到 v8](./migration/v6-to-v8.md) | v6 → v8 指南的保存位置（v8.16.3 标签） |
| [运行时编排迁移](./migration/runtime-orchestration.md) | 统一生命周期源码迁移 |
| [V9 查询迁移](./query/v9-query-migration.md) | V8.16.x 到 V9 的查询与 SnapshotStore 源码迁移 |
| [从 Fetcher 包迁移](./typescript/migration.md) | TypeScript 包改名与重新生成客户端 |
| [运行时生命周期](./advanced/runtime-lifecycle.md) | 迁移后的稳定运行模型 |
| [故障排查](./troubleshooting.md) | 门禁失败时的证据化诊断 |

<!-- Sources: current migration subpages, v6/v8 tags, WowRuntime, SnapshotStore, Redis/Mongo guards -->
