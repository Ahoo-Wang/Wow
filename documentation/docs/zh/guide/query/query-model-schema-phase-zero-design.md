---
title: QueryModelSchema 0 阶段演进设计
description: 固化 QueryField、富 QueryFieldSchema、富 QueryModelSchema 与分层快速路径。
---

# QueryModelSchema 0 阶段演进设计

> 状态：设计已确认，尚未实现。本文描述 0 阶段目标，不代表当前运行时行为。

## 目标

0 阶段先建立稳定的 Schema 领域模型，再进入 `ResolvedQuery → QueryBackend` 的阶段一：

1. 把 `LogicalField` 破坏性修正为中性的 `QueryField`；
2. 让 `QueryFieldSchema` 成为单字段富模型；
3. 让 `QueryModelSchema` 成为唯一 Query Schema 业务入口；
4. 在 Field 与 Model 两个作用域显式表达 `rewriteMode`；
5. 为 MongoDB、Elasticsearch 和后续 Backend 保留准确的零改写快速路径。

0 阶段允许 `wow-api` source/binary breaking change。合法 QueryField 仍使用字符串 JSON 形态；`state.*` 等 Backend pattern 不再被公共 Query 接受，属于明确的 wire 语义 breaking change。

Query Runtime 只承担公共 Query 的准入、逻辑语义校验与确有必要的节点改写，不提前编译 QueryBackend 或存储语法。Projection 是返回节点选择意图，Runtime 只校验并透传；Backend 使用同一个 QueryModelSchema 编译本地 Projection。避免把存储差异推入 Runtime 后再用条件分支持续修补。

## Schema 模型层级

```text
QuerySchemaDeclaration
    ↓ merge
LogicalQuerySchema
    ↓ Backend Adapter binds storage facts
QueryModelSchema
    └─ QueryFieldSchema
         └─ QueryFieldBinding
```

- `QuerySchemaDeclaration` 是可合并的部分声明，区分 `Unset` 与显式 `Set`；
- `LogicalQuerySchema` 是完成来源合并后的后端无关逻辑模型；
- `QueryModelSchema` 是发布给查询运行时的富模型；
- `QueryFieldSchema` 是字段能力、Binding、动态派生和值类型语义的富模型；
- `QueryFieldBinding` 描述 resolved query 字段与真实存储字段。

不新增 Schema Service、Resolver 接口、Capability Registry 或并行 Schema 模型。

## QueryField

`LogicalField` 当前既承载请求逻辑字段，也承载解析后的 Backend 字段，名称与真实语义不一致。0 阶段统一为中性字段路径值对象：

```kotlin
data class QueryField(
    @get:JsonValue
    val path: String,
) {
    init {
        require(QUERY_FIELD_PATTERN.matches(path)) {
            "Query field is invalid: [$path]."
        }
    }

    fun append(relative: QueryField): QueryField =
        QueryField("$path.${relative.path}")

    fun absoluteTo(parent: QueryField?): QueryField =
        if (parent == null || this == parent || path.startsWith("${parent.path}.")) {
            this
        } else {
            parent.append(this)
        }

    fun relativeTo(parent: QueryField): QueryField? =
        path.removePrefix("${parent.path}.")
            .takeIf { it != path && it.isNotEmpty() }
            ?.let(::QueryField)

    override fun toString(): String = path

    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun from(path: String): QueryField = QueryField(path)
    }
}
```

约束：

- Filter、RelativeTime、Projection、Sort、Aggregation 与 Schema Metadata 的字段类型统一为 `QueryField`；
- 不保留 `LogicalField` typealias、兼容类或 `value` 属性；
- `QueryModel`、`QueryCapability`、`QueryValueType` 仍是标识符，继续使用 `value`；
- JSON 仍将 QueryField 序列化为字符串。

`absoluteTo` 与 `relativeTo` 是唯一字段层级组合入口。Resolver、Provider fallback 和 Backend Compiler 不再自行拼接 `"$parent.$field"`。

## Projection

```kotlin
data class Projection(
    val include: List<QueryField> = emptyList(),
    val exclude: List<QueryField> = emptyList(),
) {
    companion object {
        val ALL: Projection = Projection()
    }
}
```

Projection 只表达 Backend 无关的 QueryField 节点：

- include 与 exclude 都为空表示 `ALL`；
- 每个 QueryField 选择或排除该路径节点及其全部后代；
- 标量节点自然退化为精确字段；对象节点表示整棵子树；
- `state` 表示整个状态节点及其子树，`state.name` 表示 name 节点；若 name 是对象，同样包含其子树；
- 公共 Query 不接受 `state.*` 等 Backend pattern，也不增加 Pattern 类型或 Backend escape hatch。

`QueryModelSchema` 只校验 Projection Field 是否可接受、是否允许投影以及 Mask 安全性，返回原 Projection 实例，不把逻辑字段改写为 `projectionField`：

```kotlin
resolution.value.projection === input.projection
```

`QueryFieldSchema.projectionField` 只供 Backend Projection Compiler 使用，表示存储侧可投影节点的根路径，不表示公共 Query 字段，也不表示原生 Projection 表达式。

Backend Projection Converter 再把解析后的节点编译为本地语法：

- MongoDB：FieldConverter 转换路径后投影该节点，MongoDB 节点投影自然覆盖其子树；
- Elasticsearch：每个节点编译为 `path` 与 `path.*`，同时覆盖标量节点和对象子树；
- `*` 只允许出现在 Elasticsearch 本地请求中，不能回写 `Projection`、`QueryFieldSchema` 或解析后的公共 Query；
- Backend 编译可以创建本地 Projection 对象，但不能修改传入的公共 Projection。

0 阶段不改变 QueryBackend 公共签名：Backend 在单次执行链中只获取一次 QueryModelSchema，准入校验与本地 Projection 编译共享该实例，不能在两步之间重新获取 Schema。阶段一改为直接使用 `ResolvedQuery.schema`，Projection 透传合同保持不变。

MongoDB 与 Elasticsearch 必须分别以标量和对象节点覆盖 include/exclude 集成测试，证明相同公共 Projection 语义。

### EventStream Projection 约束

EventStream Projection 只使用公共 Projection 中的逻辑 QueryField 层级关系：

```kotlin
private fun QueryField.selects(target: QueryField): Boolean =
    this == target || target.relativeTo(this) != null

private fun QueryField.intersects(target: QueryField): Boolean =
    selects(target) || target.selects(this)
```

逻辑 payload field 为 `body.body`，逻辑事件类型字段为 `body.bodyType`。只要 Projection 选择 payload 或其任意子节点，就必须同时选择且不能排除 bodyType：

```kotlin
val payloadSelected = include.isEmpty() || include.any { it.intersects(EVENT_BODY_PAYLOAD_FIELD) }
val payloadExcluded = exclude.any { it.selects(EVENT_BODY_PAYLOAD_FIELD) }
val bodyTypeSelected = include.isEmpty() || include.any { it.selects(EVENT_BODY_TYPE_FIELD) }
val bodyTypeExcluded = exclude.any { it.selects(EVENT_BODY_TYPE_FIELD) }

val accepted = !payloadSelected || payloadExcluded || (bodyTypeSelected && !bodyTypeExcluded)
```

- `Projection.ALL`、`include = [body]` 已经包含 bodyType；
- `include = [body.body]` 或 payload 子字段时，必须显式增加 `body.bodyType`；
- payload 仍被选择时，`exclude = [body.bodyType]` 不兼容；
- 整个 payload 被排除时不要求 bodyType；
- Runtime 只执行该公共合同校验，不修改 Projection；
- Backend 按原 Projection 编译，不补充内部 bodyType；Delivery 不再删除内部 bodyType；
- 删除 wildcard 正则、内部 bodyType 标记、补字段与 `hasUnrestorableInternalEventBodyTypeExclusion` 分支；
- 覆盖 `body`、`body.body`、`body.bodyType` 与 payload 子字段的 include/exclude 组合，并验证公共 Projection 始终保持原引用。

## Sort

```kotlin
data class Sort(
    val field: QueryField,
    val direction: Direction,
)
```

- `state.*` 没有有效、确定的排序语义，不再作为 `COMPATIBLE` 原样透传；
- `_score`、`_doc`、`_shard_doc` 都满足 QueryField 路径合同；Cursor 对不稳定字段的既有禁用规则保持不变；
- 普通 Sort 与 Cursor Sort 统一通过 Schema 解析，不再保留字符串解析失败的 Backend escape hatch；
- Sort DSL、`withUniqueSort` 与 Backend Sort Converter 同步使用 QueryField，原生 Compiler 只在最终边界读取 `field.path`。

Aggregation group/metric alias 保持 `String` 和单段 alias 约束，不扩大为路径模型。Aggregation Sort 在边界显式转换：

```kotlin
val sortFields = sort.map { it.field.path }
require(sortFields.all(aliases::contains))

Sort(QueryField(alias), Sort.Direction.ASC)
```

Aggregation Sort 只按 group/metric alias 校验，不进入字段 Schema 解析。

## QueryRewriteMode

```kotlin
enum class QueryRewriteMode {
    NONE,
    INFER,
    REQUIRED,
}
```

- `NONE`：当前作用域确定不会改写；
- `INFER`：是否改写取决于 Query 类型、capability、dynamic path 或 temporal 参数；
- `REQUIRED`：进入当前作用域后确定必须改写。

Mode 只描述 Query 是否需要重建，不代替 capability、Mask、Cardinality、Cursor 或值类型准入校验。

## QueryFieldBinding

```kotlin
data class QueryFieldBinding(
    val resolvedField: QueryField,
    val physicalField: QueryField,
    val storageType: QueryStorageType?,
)
```

- `resolvedField` 放入解析后的公共 Query，供当前 Backend Converter 消费；
- `physicalField` 是真实存储字段，供聚合与 Backend 原生编译使用；
- `storageType` 保存 Backend 已证明的存储类型。

MongoDB Converter 已经执行字段转换，因此 Adapter 通常保留请求字段：

```kotlin
QueryFieldBinding(
    resolvedField = queryField,
    physicalField = QueryField(fieldConverter.convert(queryField.path)),
    storageType = storageType,
)
```

Elasticsearch Converter 直接消费 mapping 路径，因此 Adapter 输出选择后的字段：

```kotlin
QueryFieldBinding(
    resolvedField = mappedField,
    physicalField = mappedField,
    storageType = storageType,
)
```

Backend 原生脚本或表达式不是字段，不能放入 `physicalField`。所有内置与自定义 Adapter 输出的可查询物理字段都必须满足 `QueryField` 路径合同。

Binding 保存绝对字段。解析 `ElementMatchFilter` 子节点时，Resolver 分别跟踪 logical、resolved 与 physical parent，并通过 `QueryField.relativeTo` 得到当前作用域的相对字段：

```kotlin
val resolved = if (resolvedParent == null) {
    binding.resolvedField
} else {
    binding.resolvedField.relativeTo(resolvedParent)
}
val physical = if (physicalParent == null) {
    binding.physicalField
} else {
    binding.physicalField.relativeTo(physicalParent)
}
```

任一相对路径无法建立时，该 capability 不兼容。Resolver 不能把绝对 `resolvedField` 直接写入 Element predicate；MongoDB 在 `$elemMatch` 内不再执行 FieldConverter，Elasticsearch 也需要在 nested parent 下编译子字段。

logical、resolved 与 physical parent 始终保存当前 Element 的绝对字段。相对字段只写入当前 Query 节点，不能作为下一层 parent。进入下一层 Element 时分别更新为当前绝对 logical field、`binding.resolvedField` 与 `binding.physicalField`，从而保证多层 Element 可以继续正确调用 `relativeTo`。

## QueryFieldSchema

```kotlin
data class QueryFieldSchema(
    val title: String?,
    val description: String?,
    val enumValues: List<JsonNode>?,
    val valueTypes: Set<QueryValueType>,
    val nullable: Boolean,
    val required: Boolean,
    val cardinality: QueryCardinality,
    val semanticType: QuerySemanticType?,
    val dynamicChildren: Boolean,
    val bindings: Map<QueryCapability, QueryFieldBinding>,
    val projectionField: QueryField? =
        bindings[QueryCapability.PRESENCE]?.resolvedField,
    val rewriteMode: QueryRewriteMode,
    @get:JsonIgnore internal val maskRule: MaskRule? = null,
) {
    val capabilities: Set<QueryCapability>
        get() = bindings.keys

    val masked: Boolean
        get() = maskRule != null

    fun binding(capability: QueryCapability): QueryFieldBinding? =
        bindings[capability]

    internal fun resolveDynamic(
        source: QueryField,
        relative: QueryField,
    ): QueryFieldSchema

    internal fun matchesValueTypes(values: Iterable<JsonNode>): Boolean
}
```

职责：

- 根据 capability 返回 Binding；
- 派生 dynamic child 的 resolved field、physical field 与 projection field；
- dynamic child 不继承 `ELEMENT_SCOPE`；
- 比较值是否匹配自身声明的内建值类型；
- 暴露 Mask 状态；
- 保存 Adapter 根据完整字段上下文计算出的不可变 `rewriteMode`。

`matchesValueTypes` 不判断当前 Schema 来自精确声明还是 dynamic 派生，也不宣称执行完整字段验证：它不新增 enum、nullable、required 或 cardinality 规则。精确声明身份由 `QueryModelSchema` 判断。`supports()` 不存在，调用方使用 `binding(capability) != null`。

Dynamic 派生必须完整重算 Binding、projectionField 与 rewriteMode：projectionField 供 Backend 编译；rewriteMode 根据派生后的 source、Binding 与 semantic 状态计算，不能盲目继承父字段 Mode。Dynamic 只是 Schema 派生机制，不天然意味着公共 Query 需要改写。

Field Mode 使用统一判定合同：

- `NONE`：该字段及其可派生 dynamic child 的所有可接受用法都保留输入 Query 节点，并且不存在会改变节点的 Element 相对路径或 temporal 规则；
- `REQUIRED`：该字段所有可接受用法都确定重建输入 Query 节点；
- `INFER`：其余情况，包括不同 capability 的 Binding 结果不同、dynamic 派生可能改变 resolvedField、Element 相对路径和条件式 temporal 改写。

判断字段路径是否改写时，只比较输入 QueryField 与公共 Query 使用的 `resolvedField`，不能因为 `physicalField` 不同就判定需要改写。最终 Field Mode 还必须合并 dynamic、Element 相对路径和 temporal 等 Query 节点级规则。MongoDB Adapter、Elasticsearch Adapter 与 `resolveDynamic` 必须遵守同一合同，并分别覆盖测试。

Projection 不参与 rewriteMode：Runtime 不使用 projectionField 重建公共 Query，projectionField 的存储映射由 Backend Projection Compiler 消费。

`resolveDynamic` 不使用只替换 `bindings` 的 data class `copy()`。Kotlin `copy(bindings = ...)` 会保留旧的 `projectionField` 与 `rewriteMode`；派生 child 必须显式构造完整的 bindings、projectionField 与 rewriteMode。

Metadata 投影仍由 `QueryModelSchema` 负责，因为逻辑字段身份来自 Model 的字段索引，不属于独立字段值。

## QueryModelSchema

```kotlin
data class QueryModelSchema(
    val model: QueryModel,
    val capabilities: Set<QueryCapability>,
    val fields: Map<QueryField, QueryFieldSchema>,
) {
    @get:JsonIgnore
    internal val maskedFields: Map<QueryField, QueryFieldSchema> =
        fields.filterValues(QueryFieldSchema::masked)

    @get:JsonIgnore
    internal val hasMaskedFields: Boolean = maskedFields.isNotEmpty()

    val rewriteMode: QueryRewriteMode = when {
        fields.values.any { it.rewriteMode != QueryRewriteMode.NONE } ->
            QueryRewriteMode.INFER

        else ->
            QueryRewriteMode.NONE
    }

    private val resolver = QuerySchemaResolver(this)

    fun supports(capability: QueryCapability): Boolean =
        capability in capabilities

    fun field(field: QueryField): QueryFieldSchema?

    internal fun matchesValueTypes(
        field: QueryField,
        values: Iterable<JsonNode>,
    ): Boolean = fields[field]?.matchesValueTypes(values) ?: true

    fun resolve(query: ISingleQuery): QuerySchemaResolution<ISingleQuery>
    fun resolve(query: IListQuery): QuerySchemaResolution<IListQuery>
    fun resolve(query: IPagedQuery): QuerySchemaResolution<IPagedQuery>
    fun resolve(query: ICursorQuery): QuerySchemaResolution<ICursorQuery>
    fun resolve(filter: FilterExpression): QuerySchemaResolution<FilterExpression>
    fun resolve(query: AggregationQuery): QuerySchemaResolution<AggregationQuery>

    fun toMetadata(): QueryModelSchemaMetadata
}
```

当前内置 Model 没有“所有查询必然改写”的模型级规则，因此 Model 构造只产生 `NONE` 或 `INFER`。EventStream Projection 的 bodyType 约束只影响 compatibility，不重建公共 Query，因此不参与 Model rewriteMode。`REQUIRED` 已在 Field 作用域存在真实语义；若未来出现实际的模型级无条件规则，再直接在属性初始化器中增加具体条件，不预建一次性 `resolveRewriteMode()` 函数。

### Field 查找

`field(QueryField)` 按以下顺序执行：

1. 精确声明字段；
2. 最近的 dynamic ancestor；
3. 由 ancestor 调用 `resolveDynamic(source, relative)`；
4. 未知且没有 dynamic ancestor 时返回 `null`。

精确字段始终覆盖 dynamic ancestor。Dynamic 查找不增加无界缓存。

值类型准入不调用 `field()`：`QueryModelSchema.matchesValueTypes` 只对 `fields[field]` 的精确命中委托 `QueryFieldSchema.matchesValueTypes`，dynamic 派生与未知字段返回 `true`。因此 Field Schema 负责值类型比较规则，Model Schema 负责字段声明身份，不增加 Field Match 包装类型。

### Query 解析

`QueryModelSchema` 是六类 Query 的唯一业务入口。`QuerySchemaResolver` 降为内部算法实现：

| 输入 | 输出行为 |
|---|---|
| `ISingleQuery` | Filter、Sort 完成准入并按需改写；Projection 完成准入并透传 |
| `IListQuery` | Filter、Sort 完成准入并按需改写；Projection 完成准入并透传 |
| `IPagedQuery` | Filter、Sort 完成准入并按需改写；Projection 完成准入并透传 |
| `ICursorQuery` | 同上，并验证稳定 Cursor 字段 |
| `FilterExpression` | Filter 完成准入并按需改写 |
| `AggregationQuery` | Filter 与 Element Filter 按需改写；聚合结构完成准入并保留公共结构 |

调用方在 Model 外应用验证模式：

```kotlin
val query = schema.resolve(input).requireAccepted(validationMode)
```

`QueryModelSchemaProvider.resolve(...)` 在 0 阶段改为委托 `QueryModelSchema.resolve(...)`，暂时保留现有 Schema 获取、Unavailable fallback 与 Reactor Context 桥接。阶段一由 Gateway 持有 Schema 后删除这些执行链机制。

## 聚合边界

聚合 `groupBy`、metric、expression 不能伪装成已经完全物理化。Model 负责：

- Element scope；
- Group、Metric、Expression capability；
- Cardinality；
- Mask；
- compatibility 与准入。

Backend Compiler 使用同一个 `QueryModelSchema` 的 `field()` 与 `QueryFieldSchema.binding()` 取得 physical field、storage type 与 temporal semantic，编译 MongoDB 或 Elasticsearch 原生表达式。Compiler 不访问 Provider，不重新计算 compatibility，也不自行解释 `fields` 与 `bindings` Map。

0 阶段不增加 resolved aggregation IR。

## 分层快速路径

### Model 作用域

- `NONE`：Resolver 不执行字段改写判断，不分配新的 Query 节点；
- `INFER`：Resolver 按实际引用字段的 Field Mode 判断；
- `REQUIRED`：仅在存在真实模型级无条件改写规则时使用。

Model `NONE` 只跳过改写，不能跳过 capability、Mask、Cursor 等准入校验。

### Field 作用域

- `NONE`：保留原 `QueryField`；
- `INFER`：根据当前 capability、dynamic path 或 temporal 输入判断；
- `REQUIRED`：顶层使用 Binding 的 `resolvedField`，Element predicate 使用相对当前 resolved parent 的字段。

### Query 作用域

以下无字段输入直接 O(1) 返回：

- `MatchAllFilter`；
- `MatchNoneFilter`；
- 空 Sort；
- 不需要 EventStream Mask 内部字段的 `Projection.ALL`。

不增加 `hasNoFieldReferences()` 预扫描。Composite Filter 只遍历一次；叶节点未变化时保留原引用，全部子节点未变化时保留原 Composite。Projection 始终保留原引用；Filter 与 Sort 都未变化时保留原 Query。

MongoDB 常规查询和 Elasticsearch identity mapping 的目标合同是：

```kotlin
resolution.value === inputQuery
```

只有 Filter、Sort 中的 alias、keyword multi-field、nested、temporal 配置或模型级规则等真实变化才分配新 Query 节点。Projection 的 Backend 映射只分配本地执行对象。

## 不可变合同

- Schema、Field Schema、Binding 构造完成后不再修改；
- Provider refresh 发布新 Model 实例，不修改旧实例；
- 不为每次 Provider 发布、Context 或查询订阅深复制集合与 JsonNode；
- 自定义 Source、Adapter 与 Provider 遵守同一所有权合同；
- Dynamic child 使用完整构造结果，不能通过部分 `copy()` 继承旧的派生状态。

只有存在已证明的不受信可变输入边界时，才在该边界局部快照。

## 兼容性

- `LogicalField → QueryField`、`Sort.field: String → QueryField` 与 `Projection: List<String> → List<QueryField>` 都是 Kotlin/Java source 与 binary breaking change；
- 不提供 typealias、废弃桥接类或重载兼容层；
- Filter、RelativeTime、Projection、Sort、Aggregation、Schema Metadata 与下游 DSL 同步迁移；
- 合法 QueryField 在 Filter、Projection、Sort 与 Aggregation JSON 中仍序列化为字符串，正常字段的 payload 形态不变；
- `state.*` 等 pattern 在 Projection 与 Sort 中变为非法输入，这是明确的 wire 语义 breaking change；
- EventStream Projection 选择 `body.body` 或其子节点却未同时选择 `body.bodyType` 时变为不兼容，这是明确的查询语义 breaking change；
- OpenAPI component identity 从 `wow.api.query.LogicalField` 破坏性更新为 `wow.api.query.QueryField`，Projection items 与 Sort field 同步引用 QueryField component；
- 不保留旧 OpenAPI 名称，不增加兼容类、Pattern 类型、Backend escape hatch 或其他推测性抽象。

## 0 阶段范围

包含：

- `wow-api` 的 QueryField 及所有字段引用；
- `wow-query` 的 QueryFieldSchema、QueryModelSchema 与内部 Resolver；
- MongoDB、Elasticsearch Schema Adapter、Projection/Sort Converter 与 Compiler；
- Schema Source、Merger、EventStream Projection/Mask、Metadata、OpenAPI Definition Provider 与快照的类型迁移；
- JVM、Java、文档、Benchmark 与 Backend 集成测试更新。

不包含：

- `ResolvedQuery`；
- `QueryBackend` 方法签名；
- `QueryContext.schema`；
- Provider 生命周期与 Factory 所有权；
- Gateway、Filter、Spring 或 HTTP 架构重构；
- Backend 查询计划缓存；
- Dynamic Field 无界缓存；
- `ObjectNode` 边界变更。

## 验收标准

1. `LogicalField` 类型声明、import 与构造调用从生产、测试和 Benchmark 源码中完全删除；迁移与设计文档可以保留旧名称说明；
2. 合法 QueryField 在 Filter、Projection、Sort 与 Aggregation 中的 JSON round-trip 与改造前字符串一致；
3. Projection 与 Sort 拒绝 `state.*` 等 pattern，删除对应 Resolver 与 Event Mask wildcard 测试；
4. OpenAPI 使用 `wow.api.query.QueryField` component/ref，Projection items 与 Sort field 引用该 component，HTTP payload 中合法字段仍为字符串；
5. QueryField 的 absolute、relative、append 行为由单元测试覆盖；
6. Projection 的每个 QueryField 表示节点及其子树，MongoDB 与 Elasticsearch 分别通过标量/对象 include/exclude 集成测试；
7. Elasticsearch Projection pattern 只在本地 Compiler 输出中出现，不进入公共 Query；
8. EventStream Projection 只使用逻辑 QueryField 的 selects/intersects 关系；选择 payload 时必须包含且不得排除 bodyType，删除 wildcard 正则、内部字段标记、补字段与删除字段分支，并覆盖 body、payload、bodyType 的 include/exclude 组合；公共 Projection 保持原引用；
9. Sort、Sort DSL、`withUniqueSort` 与 Backend Converter 使用 QueryField；Aggregation alias 保持 String，并通过 `sort.field.path` 校验；
10. 六类 Query 只能通过 `QueryModelSchema.resolve`，QuerySchemaResolver 不再是外部业务入口；
11. Dynamic Field 使用最近 ancestor，正确派生 resolved、physical、projection 与 Rewrite Mode；
12. Element predicate 的 resolved 与 physical 字段相对各自 parent，MongoDB、Elasticsearch 嵌套查询行为与改造前一致；
13. MongoDB、Elasticsearch Filter、Cursor、Aggregation 既有行为保持不变；Projection 与 Sort 仅发生本文明确列出的 pattern breaking change 和 EventStream bodyType 查询语义 breaking change；
14. Mask、Element scope、Temporal、Value type、Cursor compatibility 合同保持不变；精确字段执行内建值类型校验，dynamic 派生字段不继承该准入；
15. Projection 与 EventStream bodyType compatibility 约束不参与 Field/Model rewriteMode；MongoDB 常规 Query 与 Elasticsearch identity mapping 验证零改写对象复用；
16. Resolver JMH 覆盖 `NONE`、`INFER`、`REQUIRED`、identity dynamic path 与 rewrite dynamic path，并检查吞吐与 `gc.alloc.rate.norm`；
17. `:wow-api:check`、`:wow-query:check`、MongoDB 与 Elasticsearch 相关检查通过；
18. 文档构建通过。
