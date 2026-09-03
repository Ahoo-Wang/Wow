---
title: V9 查询迁移
description: 将 V8 查询 JVM API 迁移到聚合级 Gateway 与 ObjectNode Backend。
---

# V9 查询迁移

## 迁移边界

除下述 `Condition` 迁移窗口外，V9 删除旧 JVM 类型，不提供 bridge、typealias 或 deprecation 过渡。该变更会破坏依赖旧类型的 JVM 源码与二进制；请重新编译下游代码，并按下表直接迁移。`QueryFieldSchemaMetadata.masked`、`QueryFieldDeclaration.maskRule`、`QueryFieldSchema.maskRule` 与 `LogicalQueryFieldSchema.maskRule` 是新增 Mask 字段的 Schema 构造合同，不保留 V8 JVM constructor overload。

查询字段值对象也发生破坏性重命名：`LogicalField` 改为 `QueryField`，字段字符串从 `value` 属性改由 `path` 读取；不提供兼容类或 typealias。`Projection.include/exclude` 现在接收 `List<QueryField>`，`Sort.field` 接收 `QueryField`。合法字段的 JSON 仍是字符串，但公共 Projection 与 Sort 不再接受 `state.*` 等后端 pattern；EventStream payload 投影还必须同时选择 `body.bodyType`。

V9.x 为查询条件提供明确的迁移窗口：保留已弃用的 `Condition`/`Operator` JVM 类型、`ConditionDsl`、旧查询构造器、count 客户端重载和既有反序列化，并统一适配为 `FilterExpression`。WebFlux list/paged/single 请求仍可提交 `condition`，count 请求仍可提交裸 `operator` 形状。上述兼容 API 计划在 10.0.0 删除；新代码应立即使用 `FilterExpression`/`FilterDsl`。规范 `filter`、OpenAPI 与出站 JSON 只使用 `op`。

### ConditionDsl 迁移

| V8.16.3 `ConditionDsl` | V9 `FilterDsl` | 迁移说明 |
| --- | --- | --- |
| 独立的 `condition { ... }` | `filterExpression { ... }` | 旧空块表示 match-all；V9 空块非法，必须显式调用 `matchAll()` |
| `listQuery` / `pagedQuery` / `singleQuery` / `cursorQuery` 的 `{ condition { ... } }` | 同一 query builder 中改用 `filter { ... }` | 在 query builder 内调用 `filterExpression { ... }` 只会创建并丢弃一个独立值 |
| Condition block 内的 `condition(existingCondition)` | `expression(existingFilter)` | 已弃用的 `existingCondition.toFilterExpression()` 适配器仅在 V9.x 保留；query builder 应改用 `filter(existingFilter)` |
| `all()` | `matchAll()` | V9 还提供 `matchNone()` |
| `and { ... }` / `or { ... }` / `nor { ... }` | 至少生成一个条件时调用不变 | 条件是动态生成的时，把 guard 移到整个逻辑块外；无条件时省略该块，插入 `matchAll()` 会改变 `or`/`nor` 语义 |
| `id(value)`、`ids(values)`、`aggregateId(value)`、`aggregateIds(values)`、`tenantId(value)`、`ownerId(value)`、`spaceId(value)` | 调用不变 | `ids` 或 `aggregateIds` 为空时改用 `matchNone()`；`SpaceId` 原本就是 `String` typealias，V9 直接接收字符串值 |
| `deleted(state)` | `deletion(state)` | `DeletionState` 不变 |
| `field nested { ... }` | 仅在需要 AND 分组时使用 `field.path { ... }` | V8 会把 nested 子条件展平到外围逻辑块；V9 `path` 会把多个子条件组成隐式 AND，未生成子条件时必须省略整个调用 |
| `field eq value`、`ne`、`gt`、`gte`、`lt`、`lte` | scalar value 使用同名 infix 调用 | `KCallable` 重载已删除；结构化 JVM equality 与 range operand 使用下述显式 expression |
| `field.contains(value, ignoreCase)` | `field.containsText(value, StringComparison.CASE_*)` | 显式选择 `CASE_SENSITIVE` 或 `CASE_INSENSITIVE` |
| `field startsWith value` / `field endsWith value` | `field.startsWithText(value)` / `field.endsWithText(value)` | V9 文本 helper 不是 infix；忽略大小写时传入 `StringComparison` |
| `field isIn values` / `field notIn values` | 同名 infix 调用 | V9 只接受非空 `Iterable<*>`；空 `isIn` 映射为 `matchNone()`，空 `notIn` 映射为 `matchAll()` |
| `field between (lower to upper)` / `field between lower to upper` | `field.between(lower, upper)` | 中间态 `BetweenStart` 已删除 |
| `field all values` | `field containsAll values` | 这是集合 contains-all 条件；空集合映射为 `matchNone()` |
| `field match query` | `field search query` | 也可调用 `search(query, field)`；旧 field 为空时使用 `search(query)` 保留全局搜索，默认模式为 `SearchMode.TERMS` |
| `field elemMatch { ... }` | `field.elementMatch { ... }` | `elementMatch` 不是 infix 且不能包含 root filter；旧空块改为 `field.elementMatch { matchAll() }` |
| `field.isNull()`、`field.notNull()`、`field.isTrue()`、`field.isFalse()` | `field.isNull()`、`field.isNotNull()`、`field eq true`、`field eq false` | V9 equality 可直接接收 nullable value |
| `field.exists(true)` / `field.exists(false)` | `field.exists()` / `field.notExists()` | Boolean selector 改为显式操作 |
| `field beforeToday time` | `field.beforeToday(localTime, ...)` | V9 helper 不是 infix 且必须传 `LocalTime`；还可传 `ZoneId`、`String?` date pattern 与 `TimeUnit` |
| `field recentDays days` / `Property::field recentDays days` | `field.recentDays(days, ...)` | V9 helper 不是 infix，且没有 `KCallable` 重载 |
| `field.today(pattern)`、`tomorrow`、week/month helper | `field.today(datePattern = pattern)` 及对应的 named-argument 调用 | V9 在 `datePattern` 前新增 `ZoneId?`；不能保留旧 pattern 位置参数 |
| `field.recentDays(days, pattern)` / `field.earlierDays(days, pattern)` | `field.recentDays(days, datePattern = pattern)` / `field.earlierDays(days, datePattern = pattern)` | V9 还接收 `ZoneId` 与 `TimeUnit` |

删除 property-reference wrapper，不要重建已移除的 `KCallable` 重载。改用 Query Schema 要求的稳定逻辑字段路径，例如 `"state.status"`，并在实际选中的 Backend 上验证每个迁移后的表达式。

`ConditionDsl.nested` 会把子条件展平到外围逻辑块。根级、`and` 内或只有一个子条件时可以直接改为 `path`；在 `or` 或 `nor` 内，应把带完整前缀的子条件作为同级 operand 保留。例如，把 `or { "state" nested { "a" eq 1; "b" eq 2 } }` 改为 `or { "state.a" eq 1; "state.b" eq 2 }`，不能改成一个 `"state".path { ... }` operand。

如果旧 `nested` 块内的条件全部按运行时分支生成，应在整个 `path` 调用外使用同一 guard，并在没有条件时省略它。V8 空 `nested` 块是 no-op，V9 空 `path` 块则非法。

逻辑块按条件动态填充时，把相同 guard 移到整个 block invocation 外，让空块像 V8 一样被省略。例如：`if (includeName || includeStatus) { or { if (includeName) "name" eq name; if (includeStatus) "status" eq status } }`。不要在空 `or` 或 `nor` 中插入 `matchAll()`。

V9 集合过滤器会在构造时拒绝空值。请在 DSL 内用普通 Kotlin 分支保留 V8 语义：`if (ids.isEmpty()) matchNone() else ids(ids)`、`if (values.isEmpty()) matchNone() else "field" isIn values`，以及 `if (excluded.isEmpty()) matchAll() else "field" notIn excluded`。

`FilterDsl` 会把任意 Kotlin object 或 map 序列化为 JSON object，而规范 `EQ`/`NE` 会拒绝它。scalar 与 scalar array equality 继续使用 DSL。若要保留 V8 进程内 POJO/map equality，请显式构造 `EqualFilter` 或 `NotEqualFilter`，传入 `QueryField(field)` 与 `JsonNodeFactory.instance.pojoNode(value)`。`POJONode` 与 scalar-array equality 仅用于 JVM 构造和旧 `Condition` 兼容；规范 V9 REST filter equality 只接受 JSON scalar。

V8 的 `gt`、`gte`、`lt`、`lte` 或 `between` 任一 bound 为结构化对象时，也需要采用同一 JVM-only 方式。显式构造对应的 `GreaterThanFilter`、`GreaterThanOrEqualFilter`、`LessThanFilter`、`LessThanOrEqualFilter` 或 `BetweenFilter`，并用 `JsonNodeFactory.instance.pojoNode(value)` 包装每个 POJO/map operand。规范 REST range operand 仍只能是非 null JSON scalar。

`isIn`、`notIn` 与集合 `all` 中的结构化元素也遵循同一边界：`FilterDsl` 会把它们转换为被拒绝的 JSON object。进程内 native-value collection 应显式构造 `InFilter`、`NotInFilter` 或 `ContainsAllFilter`，并用 `JsonNodeFactory.instance.pojoNode(value)` 映射每个结构化元素，例如 `InFilter(QueryField(field), values.map(JsonNodeFactory.instance::pojoNode))`；同时保留上文的空 list 分支。`POJONode` 集合元素仅限 JVM；规范 REST collection 只包含非 null JSON scalar。

V8 传入 `DateTimeFormatter` 而不是 pattern string 时，直接构造对应 relative-time filter，并使用 named `dateFormatter` 属性，例如 `TodayFilter(QueryField(field), dateFormatter = formatter)` 或 `RecentDaysFilter(QueryField(field), days, dateFormatter = formatter)`。`BeforeTodayFilter` 还需要 `time = localTime.toString()`。`dateFormatter` 只用于 JVM 且不会进入 wire；规范 REST 使用 `datePattern`。

### Condition JVM 直接迁移

`Condition`、`ICondition`、`Operator` 与通用 `ConditionOptions` map 只在 V9.x 兼容窗口保留。请迁移到封闭的 `FilterExpression` 类型层级；下游不能新增 `FilterExpression` subtype。自定义 `ICondition` 若只表达内建 operator，应转换为对应内建 expression；真正自定义的查询语义应迁移到 request `QueryFilter` 或实际选中的 Backend，不要扩展规范 wire AST。

`FilterOperator` 是具体 expression 暴露的 metadata，不是通用 constructor selector。删除根据 operator/options tuple 构造或解释一个通用 condition 的代码，改为读取 typed property：`DeletionFilter.deletionState`、文本 filter 的 `stringComparison`、relative-time 的 `zoneId`/`datePattern`/`dateFormatter`/`timeUnit`，以及各具体 expression 的 `value`、`values`、`operands`、`predicate`、`query` 或 `fields`。

| V8 JVM surface | V9 规范 JVM surface |
| --- | --- |
| `Condition(...)` / 带 `field`、`operator`、`value`、`children`、`options` 的自定义 `ICondition` | 按下表构造具体 `FilterExpression`；不再有通用 condition constructor 或自定义 expression subtype |
| `Operator` | `FilterOperator`；主要重命名为 `ALL → MATCH_ALL`、`DELETED → DELETION`、`ALL_IN → CONTAINS_ALL`、`ELEM_MATCH → ELEMENT_MATCH`、`NULL → IS_NULL`、`NOT_NULL → IS_NOT_NULL`、`MATCH → SEARCH`；`TRUE`/`FALSE` 改为 Boolean `EQ` |
| `ConditionOptions`、option key 常量、`ignoreCaseOptions`、`datePatternOptions` | typed property：`stringComparison`、`zoneId`、`datePattern`、`dateFormatter`、`timeUnit` |
| `valueAs`、`deletionState`、`ignoreCase`、`zoneId`、`datePattern` getter | 按具体 expression 类型分支并读取其 typed property |
| `Condition.ALL` / `all()` | `MatchAllFilter` |
| `Condition.ACTIVE` / `active()` / `deleted(false)` | `DeletionFilter(DeletionState.ACTIVE)` |
| `deleted(true)` / `deleted(state)` | `DeletionFilter(DeletionState.DELETED)` / `DeletionFilter(state)` |
| `and`、`or`、`nor` | 使用非空 operand list 的 `AndFilter`、`OrFilter`、`NorFilter` |
| `id`、`ids`、`aggregateId`、`aggregateIds`、`tenantId`、`ownerId`、`spaceId` | `IdFilter`、`IdsFilter`、`AggregateIdFilter`、`AggregateIdsFilter`、`TenantIdFilter`、`OwnerIdFilter`、`SpaceIdFilter`；保留上文记录的空 list 分支 |
| `eq`、`ne` | `EqualFilter`、`NotEqualFilter`；使用 scalar `JsonNode`、scalar array，或上文 JVM-only `POJONode` 迁移 |
| `gt`、`gte`、`lt`、`lte` | `GreaterThanFilter`、`GreaterThanOrEqualFilter`、`LessThanFilter`、`LessThanOrEqualFilter`；结构化 operand 使用上文 JVM-only `POJONode` 迁移 |
| `contains`、`startsWith`、`endsWith` | 带显式 `StringComparison` 的 `ContainsFilter`、`StartsWithFilter`、`EndsWithFilter` |
| `isIn`、`notIn`、`between`、集合 `all` | `InFilter`、`NotInFilter`、`BetweenFilter`、`ContainsAllFilter`；结构化 bound 使用上文 JVM-only `POJONode` 迁移 |
| `match(field, query)` | 非空 field：`SearchFilter(query, setOf(QueryField(field)), SearchMode.TERMS)`；空 field：`SearchFilter(query)` 或 `filterExpression { search(query) }` |
| `elemMatch(field, condition)` | `ElementMatchFilter(QueryField(field), predicate)`；多个 child 用非空 `AndFilter` 组合，旧 DSL 空块产生的 `Condition.ALL` 映射为 `MatchAllFilter` |
| `isNull`、`notNull`、`isTrue`、`isFalse`、`exists(true)`、`exists(false)` | `IsNullFilter(QueryField(field))`、`IsNotNullFilter(QueryField(field))`、`filterExpression { field eq true }`、`filterExpression { field eq false }`、`ExistsFilter(QueryField(field))`、`NotExistsFilter(QueryField(field))` |
| `today`、`beforeToday`、`tomorrow`、week/month、`recentDays`、`earlierDays` | 对应 `TodayFilter`、`BeforeTodayFilter`、`TomorrowFilter`、`ThisWeekFilter`、`NextWeekFilter`、`LastWeekFilter`、`ThisMonthFilter`、`LastMonthFilter`、`RecentDaysFilter`、`EarlierDaysFilter`；使用 typed constructor property 与上文 formatter 边界 |
| `condition.toFilterExpression()` | 仅用于 V9.x 过渡；10.0.0 前把保存或公开的 `Condition` 值改为具体 expression |

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

原 `QueryService<R>` 没有一对一替代类型：存储查询迁移到返回 `ObjectNode` 的 `QueryBackend`，受管入口、过滤链与 typed 物化留在聚合级 `QueryGateway<R>`。原 `QueryGateway` 每次调用接收 `NamedAggregate`；V9 在构造 Gateway 时绑定 `NamedAggregate` 与 routed `QueryBackendBinding`，因此 `single`、`list`、`paged`、`cursor`、`count` 和 `aggregate` 调用不再传聚合参数。自定义 `AbstractQueryGateway` 子类必须按新构造合同提供 `namedAggregate`、`binding`、`validationMode`、`targetType`、`filters`、`filterType` 与 `errorHandler`；没有自定义入口策略时直接使用 Snapshot/EventStream 默认 Gateway。

### 自定义 QueryBackend 迁移

自定义 Backend 必须一次性迁移全部六个执行签名；V9 不提供接收原始 Query 的兼容重载：

```kotlin
fun single(query: ResolvedQuery<ISingleQuery>): Mono<ObjectNode>
fun list(query: ResolvedQuery<IListQuery>): Flux<ObjectNode>
fun paged(query: ResolvedQuery<IPagedQuery>): Mono<PagedList<ObjectNode>>
fun cursor(query: ResolvedQuery<ICursorQuery>): Mono<CursorPage<ObjectNode>>
fun count(query: ResolvedQuery<FilterExpression>): Mono<Long>
fun aggregate(query: ResolvedQuery<AggregationQuery>): Flux<ObjectNode>
```

用 `query.query` 编译已准入的查询，用非空的 `query.schema` 编译 Projection 和 Aggregation。删除执行路径中的 Provider 读取、Schema resolve、验证模式分支和 Cursor 唯一字段追加；`QueryModelSchema` 已按模型追加并验证唯一排序。受管 Gateway 每次订阅在创建 Context 前获取一次 Schema，Filter 链从开始即可读取同一实例；Schema 获取失败时 Filter 和 Backend 都不会执行。自定义 Factory 在 `QueryBackendBinding` 中显式配对 Backend 与 `QueryModelSchemaProvider`；自定义 Backend 从不实现或委托 Provider 能力。

Filter 不再通过 `QueryType.isDynamic` 判断最终返回 typed 对象还是节点；两条路径在同一 ObjectNode FilterChain 中处理，区别仅发生在链完成后的可选 Jackson 物化。删除只为 typed/dynamic 分流的分支，不要发明新的结果类型判别器。

删除旧 Mask 类型、实现、Bean、Registry 与自定义 Filter，不建立 ObjectNode Mask 兼容层。把原规则声明到领域字段后，Snapshot、EventStream 的 typed、dynamic 与 aggregate-state load 会在同一条受管 Gateway 路径自动脱敏；框架内建 `SchemaMaskQueryFilter` 读取 `QueryContext.schema`，同一实例复用 Masker，refresh 后的新订阅读取新实例并重新编译。Schema 不可用时所有受管 Gateway 调用在 Context、Filter 与订阅 Backend 前失败关闭；count 不执行结果脱敏，但仍需要 Schema 完成请求准入。受信原始边界是 `factory.create(namedAggregate).backend`；调用方必须显式取得 Schema、完成解析与准入，再构造 `ResolvedQuery`，不存在 Provider 级 unavailable fallback。

#### 已删除与收窄的公共 API

除六个执行签名与 Gateway 构造合同外，同一发布列车还删除或收窄了以下公共 API；所有直接使用都必须迁移：

| 已删除或收窄的 API | 迁移方式 |
| --- | --- |
| `ResolvedAggregationQuery` | 改用 `ResolvedQuery<AggregationQuery>`。 |
| 全部六个 `QueryModelSchemaProvider.resolve(query, mode)` 扩展及 Provider 级 `COMPATIBLE` unavailable fallback | 自行调用 `QueryModelSchema.resolve(...)` 并 `requireAccepted(mode)`；所有路径在 Schema 不可用时统一以 `QuerySchemaUnavailableException` 失败关闭。 |
| `ProjectionConverter.convert(projection, schema: QueryModelSchema?)` | `schema` 参数改为非空；自定义 converter 始终基于非空 Schema 编译。 |
| `QueryContext` / `DefaultQueryContext` | `QueryContext` 新增无默认值的 `schema: QueryModelSchema` 成员；`DefaultQueryContext` 第三个构造参数必填。 |
| `me.ahoo.wow.query.filter.Contexts.getRawRequest/writeRawRequest` | 改用 `me.ahoo.wow.webflux.route.getRawRequest/writeRawRequest`；该 API 归属 WebFlux，且只接受和返回 `ServerRequest`，不再支持任意值或调用方强转。 |
| MongoDB 与 Elasticsearch Backend 构造函数的 `schemaProvider` 参数，以及 Backend 实现或委托 `QueryModelSchemaProvider` | 改为在 Factory 的 `QueryBackendBinding` 中配对 Provider；Backend 不再承载 Provider 能力。 |
| `requiredQueryModelSchemaProvider()` 扩展 | 从 binding 读取 `factory.create(namedAggregate).schemaProvider`。 |
| `MongoCollections.findDocument` 四参数公有重载 | 仅存重载要求非空 `QueryModelSchema`。 |
| MongoDB 与 Elasticsearch Backend Factory 构造函数的 `validationMode` 参数 | 验证模式归 Gateway 所有；默认 Bean 来自 `wow.query.schema.validation-mode`。 |

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

Factory 与公开 binding 字符串统一使用 Backend 概念和 `*-query-backend-factory` 后缀。`SnapshotQueryBackendFactory.create` 与 `EventStreamQueryBackendFactory.create` 现在返回 `QueryBackendBinding`，且 Snapshot Factory 删除未使用泛型；这两项都是源码与二进制 breaking change。迁移已有路由配置值，例如 `mongo-snapshot-query-backend-factory` 与 `elasticsearch-event-stream-query-backend-factory`，不保留旧 binding alias。Query JSON、Schema HTTP 路径和响应及 Gateway 公共方法没有 wire change。

## 调用入口

业务代码注入聚合级 Gateway，让请求过滤、ABAC、通用结果处理与错误观察经过一条 around chain。只有受信低层诊断、Backend 合同测试与存储扩展直接调用 Backend Factory；该路径绕过 Gateway 治理。

Schema handler 解包同一个 routed binding 的 `factory.create(namedAggregate).schemaProvider`，因此 Schema 与实际查询按 `NamedAggregate` 选择同一 Backend 和 Provider。通用 `QueryFilter` 不标注 `@FilterType`；只有模型专属过滤器才限定对应 Gateway 类型。

## ObjectNode 所有权

自定义 Backend 返回的 Publisher 每次订阅都必须创建由该订阅独占的可变 `ObjectNode`；`retry`、`repeat` 和并发订阅也必须分别得到新节点。不得跨订阅缓存或共享节点、发布缓存节点，或在节点发出后异步继续修改。

Backend 边界只允许标准 JSON tree。存储驱动的 `Map`/`Document`、BSON 值、`POJONode` 和任意 POJO 必须在 Backend 内规范化或被拒绝。

## 传输与错误语义

JSON 数组与 SSE 的流式行为保持不变。若流在输出部分元素后失败，已输出元素不会回滚；SSE 会尝试发送一个 `ErrorInfo` 错误事件。`RequestExceptionHandler` 失败，或该错误事件生成、渲染、序列化失败时，只要失败不同于原始错误且尚未记录，就附加为 suppressed error；原始终止错误始终继续传播，迁移不能把这种部分失败改写为空结果或成功完成。

未配置 Backend 时，受管 Gateway 调用在 Filter 执行与 Backend 订阅之前，以 `QuerySchemaUnavailableException`（错误码 `QuerySchemaUnavailable`，HTTP 503）失败关闭；内建 Provider 与 schema source 在 Schema 不可用或不可读时同样抛出该异常。若自定义 `QueryModelSchemaProvider` 或 `QuerySchemaSource` 以其它异常失败，Gateway 会原样传播该错误，按其自身错误码映射传输状态，而不是 503。早期版本对该场景抛通用 `INTERNAL_SERVER_ERROR` `WowException`（HTTP 500）；依赖错误码或 HTTP 状态码做匹配的客户端必须更新。直接调用低层 Backend 仍会得到各 Backend 自身的错误，例如 unavailable Backend 的 `INTERNAL_SERVER_ERROR` `WowException`。

## 最小迁移步骤

1. 按表与"已删除与收窄的公共 API"清单替换 import、构造参数、Bean qualifier 与 Factory 实现。
2. 把自定义 Backend 的六个方法全部改为接收 `ResolvedQuery`，删除原始 Query 重载、执行期 Schema resolve、验证模式与 Cursor 唯一字段追加，并用 `query.schema` 编译 Projection 和 Aggregation。
3. 让自定义 Backend 的每次订阅返回独占、只含标准 JSON tree 的新 `ObjectNode`，把 typed 转换留给 Gateway。
4. 删除全部旧 Mask 实现、Bean、Registry 与 Filter；按[字段脱敏](./masking.md)把每条旧规则迁移为 `@Mask`、`@KeepMask` 或自定义 `@Masking(strategy)` 字段注解。
5. 检查 Schema 的 `masked` 元数据；分别验证 Snapshot/EventStream 的 typed、dynamic、state-only/aggregate-state load，以及 direct Backend 原始值边界。
6. 验证普通 filter/search/sort 和 count 保持可用，并确认 group、字段 metric、expression 引用 Mask 字段时失败关闭；再核对实际 MongoDB/Elasticsearch 路由、HTTP/OpenAPI 与存储原值。
