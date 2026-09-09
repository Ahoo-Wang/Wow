---
title: V9 查询迁移
description: 从历史查询实现迁移到当前聚合级 Gateway、递归 Schema 与原生 Backend。
---

# V9 查询迁移

## 迁移边界

本页保留 V8→V9 的 Condition/DSL 迁移说明，并将扩展示例更新为当前实现。业务 `QueryGateway` 方法合同保留；旧 Backend、Schema 构造器、过滤链与验证模式不属于实现兼容合同，下游扩展需修改源码并重新编译。

旧 `Condition` 兼容栈按既定约定保留至 10.0.0，本轮内部架构清理不提前删除它。弃用提示用于安排迁移，不表示该兼容栈是本轮未完成的清理项。

查询路径使用 `QueryField.path`；Projection/Sort 中仍序列化为字符串，但不允许公共 `state.*` 通配路径。未知逻辑字段不会作为原生字段透传。

数值迁移保留 singleton 数组指标：直接 FIELD 与算术叶子统一忽略 null 后只接受一个数值，重复项仍计为多个。标量原生聚合精度与有限 Double 算术不承诺任意精度代数恒等；数值过滤遵循原生存储精度。完整说明见[聚合查询](./aggregation-query.md#numeric-contributions)和[过滤条件](./filter-expression.md)。

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

## 历史类型与当前替代

下表左列为已经移除的历史类型，不是当前可调用 API：

| 历史类型或做法 | 当前实现 |
| --- | --- |
| QueryService / SnapshotQueryService / EventStreamQueryService | 应用注入聚合级 QueryGateway；存储实现使用 QueryBackend |
| ResolvedQuery | Backend 每个方法显式接收 `(query, schema)` |
| QueryFilterChain / around filter | `QueryFilter.prepare(QueryContext<Q>): Mono<Q>`，只准备请求 |
| RewriteRequestFilter / HttpQueryGuardFilter | Handler 的 QueryRequestScope / HttpQueryGuard |
| AbacQueryFilter | 实现 QueryPolicy 的独立 AbacQueryPolicy |
| SchemaMaskQueryFilter / 自定义结果 Mask Filter | Gateway 固定 Mask 步骤；领域静态 Mask 声明 |
| validation-mode / QuerySchemaValidationMode | 已移除；最终逻辑请求严格校验 |
| flat fields metadata / dynamicChildren | `QueryModelSchemaMetadata.root` 递归 properties/items/additionalProperties/alternatives |

旧 `wow.query.schema.validation-mode` 配置的任何值（包括 `strict`）都会在启动时明确失败并要求删除；camelCase 写法同样拒绝，不会静默忽略。

## 自定义 QueryBackend 迁移

六个方法显式接收逻辑 Query 与 Schema。例如：

```kotlin
fun single(query: ISingleQuery, schema: QueryModelSchema): Mono<ObjectNode>
fun list(query: IListQuery, schema: QueryModelSchema): Flux<ObjectNode>
fun paged(query: IPagedQuery, schema: QueryModelSchema): Mono<PagedList<ObjectNode>>
fun cursor(query: ICursorQuery, schema: QueryModelSchema): Mono<CursorPage<ObjectNode>>
fun count(query: FilterExpression, schema: QueryModelSchema): Mono<Long>
fun aggregate(query: AggregationQuery, schema: QueryModelSchema): Flux<ObjectNode>
```

Backend 从传入 Schema 取 native binding，检查原生参数和物理作用域并执行；不再读取 Provider、执行公共 whole-query validator、授权、Mask 或 typed 物化。Factory 返回 `QueryBackendBinding` 配对 Backend 与 Provider。每次订阅产生独占的标准 JSON ObjectNode。

## 请求扩展与调用入口

`QueryContext<Q>` 只含 query、namedAggregate、schema。把旧请求处理搬到 prepare；把身份约束放到 Reactor `withQueryScope` 或 `QueryPolicy`（包括仅适用 Snapshot 的 `AbacQueryPolicy`）；Observer 只观察终止，不修改结果。Gateway 固定在 prepare 后合并 scope/policy，然后默认条件、公共校验、Backend、Mask、typed 物化。

业务继续使用 SnapshotQueryGateway / EventStreamQueryGateway 的 typed、dynamic、分页、游标、count 和 aggregate 方法。直接 Backend 是受信低层边界，调用者显式提供 Schema 和所有治理责任。游标唯一排序由 Gateway 追加，Backend 不追加。

## 静态 Mask 迁移

规则迁移到 `@Mask`、`@KeepMask` 或自定义 `@Masking`。不要恢复历史 registry 或结果 Filter。当前 metadata 只公开递归值节点的masked标记和公开能力；不公开策略与native路径。完整值域、联合分支和别名边界见[字段脱敏](./masking.md)。

## 最小迁移步骤

1. 保留合法逻辑字段路径，删除对未声明字段透传和物理别名查询的依赖。
2. 修改 Backend 签名与 Factory binding；按真实值树声明数组、Map 与联合分支。
3. 分离 prepare、scope、QueryPolicy 与 Observer，使用默认 Gateway 的固定流程。
4. 验证普通查询、集合/元素作用域、cursor、聚合、metadata与Mask失败场景，再验证实际存储行为。

当前扩展合同见[查询网关](./query-gateway.md)、[查询后端](./query-backend.md)和[查询模型 Schema](./query-model-schema.md)。

`DefaultSnapshotQueryGateway` 与 `DefaultEventStreamQueryGateway` 的 `policies` 参数及各自 Spring 注册器均使用 `QueryPolicy`，由 `AbstractQueryGateway` 统一执行；不再通过子类覆写 `policyFilter`。现有 `AbacQueryPolicy` 已实现该接口，并仅对 Snapshot 读取标签。数据生命周期、业务查询条件等其他策略直接实现 `evaluate(ContextView, QueryContext<*>): Mono<FilterExpression>`。使用模型专属字段的策略应先检查 `context.schema.model`，在不适用模型上返回 `MatchAllFilter`。固定策略阶段、捕获身份、AND 合并与空 Publisher 拒绝规则保持。
