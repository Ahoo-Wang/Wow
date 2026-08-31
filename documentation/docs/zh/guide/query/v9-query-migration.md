---
title: V9 查询迁移
description: 将 V8 查询 JVM API 迁移到聚合级 Gateway 与 ObjectNode Backend。
---

# V9 查询迁移

## 迁移边界

除下述 `Condition` 迁移窗口外，V9 删除旧 JVM 类型，不提供 bridge、typealias 或 deprecation 过渡。该变更会破坏依赖旧类型的 JVM 源码与二进制；请重新编译下游代码，并按下表直接迁移。`QueryFieldSchemaMetadata.masked`、`QueryFieldDeclaration.maskRule`、`QueryFieldSchema.maskRule` 与 `LogicalQueryFieldSchema.maskRule` 是新增 Mask 字段的 Schema 构造合同，不保留 V8 JVM constructor overload。

V9.0.x 为查询条件提供明确的迁移窗口：保留已弃用的 `Condition`/`Operator` JVM 类型、`ConditionDsl`、旧查询构造器、count 客户端重载和既有反序列化，并统一适配为 `FilterExpression`。WebFlux list/paged/single 请求仍可提交 `condition`，count 请求仍可提交裸 `operator` 形状。上述兼容 API 计划在 9.1.0 删除；新代码应立即使用 `FilterExpression`/`FilterDsl`。规范 `filter`、OpenAPI 与出站 JSON 只使用 `op`。

### ConditionDsl 迁移

| V8.16.3 `ConditionDsl` | V9 `FilterDsl` | 迁移说明 |
| --- | --- | --- |
| `condition { ... }` | `filterExpression { ... }` | 旧空块表示 match-all；V9 空块非法，必须显式调用 `matchAll()` |
| `condition(existingCondition)` | `expression(existingFilter)` | 已弃用的 `existingCondition.toFilterExpression()` 适配器仅在 9.0.x 保留 |
| `all()` | `matchAll()` | V9 还提供 `matchNone()` |
| `and { ... }` / `or { ... }` / `nor { ... }` | 调用不变 | V9 逻辑块不能为空 |
| `id(value)`、`ids(values)`、`aggregateId(value)`、`aggregateIds(values)`、`tenantId(value)`、`ownerId(value)`、`spaceId(value)` | 调用不变 | `SpaceId` 原本就是 `String` typealias；V9 直接接收字符串值 |
| `deleted(state)` | `deletion(state)` | `DeletionState` 不变 |
| `field nested { ... }` | `field.path { ... }` | `path` 不是 infix；块内表达式使用作用域内的相对路径 |
| `field eq value`、`ne`、`gt`、`gte`、`lt`、`lte` | `String` 字段上的同名 infix 调用 | `KCallable` 重载已删除，改用逻辑字段字符串 |
| `field.contains(value, ignoreCase)` | `field.containsText(value, StringComparison.CASE_*)` | 显式选择 `CASE_SENSITIVE` 或 `CASE_INSENSITIVE` |
| `field startsWith value` / `field endsWith value` | `field.startsWithText(value)` / `field.endsWithText(value)` | V9 文本 helper 不是 infix；忽略大小写时传入 `StringComparison` |
| `field isIn values` / `field notIn values` | 同名 infix 调用 | V9 接收 `Iterable<*>` |
| `field between (lower to upper)` / `field between lower to upper` | `field.between(lower, upper)` | 中间态 `BetweenStart` 已删除 |
| `field all values` | `field containsAll values` | 这是集合 contains-all 条件，不是根级 match-all |
| `field match query` | `field search query` | 也可调用 `search(query, field)`；旧默认语义映射为 `SearchMode.TERMS` |
| `field elemMatch { ... }` | `field.elementMatch { ... }` | `elementMatch` 不是 infix；块不能为空，且不能包含 root filter |
| `field.isNull()`、`field.notNull()`、`field.isTrue()`、`field.isFalse()` | `field.isNull()`、`field.isNotNull()`、`field eq true`、`field eq false` | V9 equality 可直接接收 nullable value |
| `field.exists(true)` / `field.exists(false)` | `field.exists()` / `field.notExists()` | Boolean selector 改为显式操作 |
| `field.today(...)`、`tomorrow`、`thisWeek`、`nextWeek`、`lastWeek`、`thisMonth`、`lastMonth`、`recentDays`、`earlierDays` | 同名 dot call | date pattern 改为 `String?`；V9 还接收 `ZoneId` 与 `TimeUnit`，`beforeToday` 改为接收 `LocalTime` |

删除 property-reference wrapper，不要重建已移除的 `KCallable` 重载。改用 Query Schema 要求的稳定逻辑字段路径，例如 `"state.status"`，并在实际选中的 Backend 上验证每个迁移后的表达式。

数据查询的 HTTP 请求/结果 envelope、Backend wire tree、存储布局和既有数据不因这次 JVM 重构或静态注解 Mask 改变。Query Schema HTTP 元数据及其生成的 OpenAPI component 会变化：每个字段新增 `masked: Boolean`。无需迁移存储数据，Backend 与存储中的原值也不会被改写。把原 Mask 配置迁移到字段注解后，受管 Gateway 会恢复响应的保密语义。

## JVM 类型映射

| V8 源码 | V9 源码 |
| --- | --- |
| `QueryService<R>` | 已删除；职责拆分为 `QueryBackend` 与聚合绑定的 `QueryGateway<R>` |
| `QueryGateway<R>` / `AbstractQueryGateway<R>` | 名称保留，但改为聚合绑定合同 |
| `SnapshotQueryService<S>` | `SnapshotQueryGateway<S>` |
| `EventStreamQueryService` | `EventStreamQueryGateway` |
| `QueryServiceCacheSource` | `QueryGatewayCacheSource` |
| `SnapshotQueryServiceFactory` | `SnapshotQueryBackendFactory` |
| `EventStreamQueryServiceFactory` | `EventStreamQueryBackendFactory` |
| `AbstractSnapshotQueryServiceFactory` | `AbstractSnapshotQueryBackendFactory` |
| `AbstractEventStreamQueryServiceFactory` | `AbstractEventStreamQueryBackendFactory` |
| `RoutingSnapshotQueryServiceFactory` | `RoutingSnapshotQueryBackendFactory` |
| `RoutingEventStreamQueryServiceFactory` | `RoutingEventStreamQueryBackendFactory` |
| `AbstractMongoQueryService` | `AbstractMongoQueryBackend` |
| `MongoSnapshotQueryService` | `MongoSnapshotQueryBackend` |
| `MongoEventStreamQueryService` | `MongoEventStreamQueryBackend` |
| `MongoSnapshotQueryServiceFactory` | `MongoSnapshotQueryBackendFactory` |
| `MongoEventStreamQueryServiceFactory` | `MongoEventStreamQueryBackendFactory` |
| `AbstractElasticsearchQueryService` | `AbstractElasticsearchQueryBackend` |
| `ElasticsearchSnapshotQueryService` | `ElasticsearchSnapshotQueryBackend` |
| `ElasticsearchEventStreamQueryService` | `ElasticsearchEventStreamQueryBackend` |
| `ElasticsearchSnapshotQueryServiceFactory` | `ElasticsearchSnapshotQueryBackendFactory` |
| `ElasticsearchEventStreamQueryServiceFactory` | `ElasticsearchEventStreamQueryBackendFactory` |
| `SnapshotQueryServiceFactoryBinding` | `SnapshotQueryBackendFactoryBinding` |
| `EventStreamQueryServiceFactoryBinding` | `EventStreamQueryBackendFactoryBinding` |
| `NoOpSnapshotQueryService<S>` | `NoOpSnapshotQueryBackend` |
| `NoOpEventStreamQueryService` | `NoOpEventStreamQueryBackend` |
| `NoOpSnapshotQueryServiceFactory` | `NoOpSnapshotQueryBackendFactory` |
| `NoOpEventStreamQueryServiceFactory` | `NoOpEventStreamQueryBackendFactory` |
| `QueryServiceRegistrar` | `QueryGatewayRegistrar` |
| `SnapshotQueryServiceRegistrar` | `SnapshotQueryGatewayRegistrar` |
| `EventStreamQueryServiceRegistrar` | `EventStreamQueryGatewayRegistrar` |
| `QueryServiceProxy` / `SnapshotQueryServiceProxy` / `EventStreamQueryServiceProxy` | 已删除；直接注入聚合级 Gateway |
| `DynamicDocument` / `SimpleDynamicDocument` | `tools.jackson.databind.node.ObjectNode` |
| `DynamicDocumentMasker` | 已删除；在领域字段使用 `@Mask`、`@KeepMask` 或自定义 `@Masking` meta-annotation |
| `AggregateDynamicDocumentMasker` | 已删除；Snapshot 与 EventStream 由内建 `SchemaMaskQueryFilter` 按 Query Schema 统一 Mask |
| `StateDynamicDocumentMasker` | 已删除；在状态字段声明静态 Mask 注解 |
| `EventStreamDynamicDocumentMasker` | 已删除；在事件 payload 字段声明静态 Mask 注解 |
| `AggregateDataMasker` / `DefaultAggregateDataMasker` | 已删除；不保留运行时对象 Mask SPI |
| `DataMaskerRegistry` / `AbstractDataMaskerRegistry` | 已删除；规则由 Query Schema 从字段注解发现 |
| `StateDataMaskerRegistry` / `EventStreamMaskerRegistry` | 已删除；不再注册模型级 Masker |
| `DataMasker` / `DataMasking` / `tryMask` | 已删除；迁移为字段静态注解 |
| `MaskingDynamicDocumentQueryFilter` | 已删除；由框架内建、固定最外层的 `SchemaMaskQueryFilter` 统一替代 |
| `QueryType.DYNAMIC_SINGLE` | `QueryType.SINGLE` |
| `QueryType.DYNAMIC_LIST` | `QueryType.LIST` |
| `QueryType.DYNAMIC_PAGED` | `QueryType.PAGED` |
| `QueryType.isDynamic` | 已删除；typed 与节点路径共享操作类型 |
| `SnapshotRepository` | `SnapshotStore` |
| `NoOpSnapshotRepository` | `NoOpSnapshotStore` |
| `InMemorySnapshotRepository` | `InMemorySnapshotStore` |
| `DelaySnapshotRepository` | `DelaySnapshotStore` |
| `ElasticsearchSnapshotRepository` | `ElasticsearchSnapshotStore` |
| `TracingSnapshotRepository` | `TracingSnapshotStore` |
| `SnapshotRepositoryInstrumenter` | `SnapshotStoreInstrumenter` |
| `SnapshotRepositorySaveSpanNameExtractor` | `SnapshotStoreSaveSpanNameExtractor` |
| `SnapshotRepositoryLoadSpanNameExtractor` | `SnapshotStoreLoadSpanNameExtractor` |
| `SnapshotRepositorySpec` | `SnapshotStoreSpec` |
| `SnapshotStoreSpec.createSnapshotRepository()` / `CommandDispatcherSpec.createSnapshotRepository()` / `SnapshotQueryBackendSpec.createSnapshotRepository()` | `createSnapshotStore()` |
| Mongo `createAggregateIdIndex()`、`createAggregateIdAndVersionUniqueIndex()`、`createRequestIdUniqueIndex()`、`createAggregateIdAndRequestIdUniqueIndex()`、`createTenantIdIndex()`、`createOwnerIdIndex()` | 已删除；通过 `EventStreamSchemaInitializer` / `SnapshotSchemaInitializer` 的 `initSchema()` 或 `initAll()` 统一对账完整的受管索引集合。EventStream request id 唯一性中，`enableRequestIdUniqueIndex = true` 选择 request-id 索引，`false`（默认）选择 aggregate-id/request-id 复合索引 |
| Elasticsearch `UNLIMITED_SIZE` / `Int.searchSize()` | 已删除；直接传递 `ListQuery.limit`（`0` 仍表示不限），由 Backend 使用 PIT / `search_after` 分批读取 |
| `IndexTemplateInitializer.InitSubscriber` | 已删除；组合并等待 `ensureAllTemplates()`，或调用阻塞式 `initAll()`；初始化失败会继续传播 |
| `EventStoreSpec.TIMES` | `EventStoreSpec.DEFAULT_CONCURRENCY_TEST_ITERATIONS` |
| `EventStoreSpec.DEFAULT_PARALLELISM` | `EventStoreSpec.DEFAULT_CONCURRENCY_TEST_MAX_CONCURRENCY` |

typed 与节点返回共享 `SINGLE`、`LIST`、`PAGED`、`CURSOR` 操作类型。Backend 始终返回 `ObjectNode`，Gateway 在通用结果 Filter 完成后按需使用 Jackson 物化 typed 结果。

原 `QueryService<R>` 没有一对一替代类型：存储查询与 Schema 能力迁移到返回 `ObjectNode` 的 `QueryBackend`，受管入口、过滤链与 typed 物化留在聚合级 `QueryGateway<R>`。原 `QueryGateway` 每次调用接收 `NamedAggregate`；V9 在构造 Gateway 时只绑定 `NamedAggregate` 与 routed Backend，因此 `single`、`list`、`paged`、`cursor`、`count` 和 `aggregate` 调用不再传聚合参数。自定义 `AbstractQueryGateway` 子类必须按新构造合同提供 `namedAggregate`、`backend`、`targetType`、`filters`、`filterType` 与 `errorHandler`；没有自定义入口策略时直接使用 Snapshot/EventStream 默认 Gateway。

Filter 不再通过 `QueryType.isDynamic` 判断最终返回 typed 对象还是节点；两条路径在同一 ObjectNode FilterChain 中处理，区别仅发生在链完成后的可选 Jackson 物化。删除只为 typed/dynamic 分流的分支，不要发明新的结果类型判别器。

删除旧 Mask 类型、实现、Bean、Registry 与自定义 Filter，不建立 ObjectNode Mask 兼容层。把原规则声明到领域字段后，Snapshot、EventStream 的 typed、dynamic 与 aggregate-state load 会在同一条受管 Gateway 路径自动脱敏；框架内建 `SchemaMaskQueryFilter` 每次结果查询读取当前 Schema，同一实例复用 Masker，refresh 新实例重新编译，Schema 不可用时结果查询失败关闭且不订阅 Backend，count 不读取 Mask Schema。直接 Backend Factory 或不提供 `QueryModelSchemaProvider` 的自定义 Backend 仍是返回原始值的受信低层边界；`COMPATIBLE` unavailable fallback 只属于直接 `QueryModelSchemaProvider.resolve(...)` 请求解析。

## 静态 Mask 迁移

删除旧 Registry/Filter 后，把全量遮蔽迁移为 `@Mask`，保留前后字符的规则迁移为 `@KeepMask(prefix, suffix)`，领域专用规则迁移为带 `@Masking(strategy)` 的运行时字段注解。无需建立 ObjectNode 兼容层或新 Registry；完整 API、Unicode/空值语义、行为矩阵与失败关闭合同见[字段脱敏](./masking.md)。

## Spring Bean 映射

| V8 Bean | V9 Bean |
| --- | --- |
| `*.SnapshotQueryService` | `*.SnapshotQueryGateway` |
| `*.EventStreamQueryService` | `*.EventStreamQueryGateway` |
| `noOpSnapshotRepository` | `noOpSnapshotStore` |
| `inMemorySnapshotRepository` | `inMemorySnapshotStore` |
| `delaySnapshotRepository` | `delaySnapshotStore` |
| `mongoSnapshotRepository` | `mongoSnapshotStore` |
| `elasticsearchSnapshotRepository` | `elasticsearchSnapshotStore` |

新 Bean 的精确全名是 `{contextAlias.}{aggregateName}.SnapshotQueryGateway` 与 `{contextAlias.}{aggregateName}.EventStreamQueryGateway`；没有 context alias 时省略前缀。旧 QueryService 与 SnapshotRepository Bean alias 均不再注册。

## Binding 配置值

Factory 与公开 binding 字符串统一使用 Backend 概念和 `*-query-backend-factory` 后缀，例如 `mongo-snapshot-query-backend-factory` 与 `elasticsearch-event-stream-query-backend-factory`。迁移已有路由配置值，不保留旧 binding alias。

## 调用入口

业务代码注入聚合级 Gateway，让请求过滤、ABAC、通用结果处理与错误观察经过一条 around chain。只有受信低层诊断、Backend 合同测试与存储扩展直接调用 Backend Factory；该路径绕过 Gateway 治理。

Schema handler 也使用 routed Backend Factory，因此 Schema 与实际查询按 `NamedAggregate` 选择同一 Backend。通用 `QueryFilter` 不标注 `@FilterType`；只有模型专属过滤器才限定对应 Gateway 类型。

## ObjectNode 所有权

自定义 Backend 返回的 Publisher 每次订阅都必须创建由该订阅独占的可变 `ObjectNode`；`retry`、`repeat` 和并发订阅也必须分别得到新节点。不得跨订阅缓存或共享节点、发布缓存节点，或在节点发出后异步继续修改。

Backend 边界只允许标准 JSON tree。存储驱动的 `Map`/`Document`、BSON 值、`POJONode` 和任意 POJO 必须在 Backend 内规范化或被拒绝。

## 传输与错误语义

JSON 数组与 SSE 的流式行为保持不变。若流在输出部分元素后失败，已输出元素不会回滚；SSE 会尝试发送一个 `ErrorInfo` 错误事件。`RequestExceptionHandler` 失败，或该错误事件生成、渲染、序列化失败时，只要失败不同于原始错误且尚未记录，就附加为 suppressed error；原始终止错误始终继续传播，迁移不能把这种部分失败改写为空结果或成功完成。

## 最小迁移步骤

1. 按表替换 import、构造参数、Bean qualifier 与 Factory 实现。
2. 让自定义 Backend 的每次订阅返回独占、只含标准 JSON tree 的新 `ObjectNode`，把 typed 转换留给 Gateway。
3. 删除全部旧 Mask 实现、Bean、Registry 与 Filter；按[字段脱敏](./masking.md)把每条旧规则迁移为 `@Mask`、`@KeepMask` 或自定义 `@Masking(strategy)` 字段注解。
4. 检查 Schema 的 `masked` 元数据；分别验证 Snapshot/EventStream 的 typed、dynamic、state-only/aggregate-state load，以及 direct Backend 原始值边界。
5. 验证普通 filter/search/sort 和 count 保持可用，并确认 group、字段 metric、expression 引用 Mask 字段时失败关闭；再核对实际 MongoDB/Elasticsearch 路由、HTTP/OpenAPI 与存储原值。
