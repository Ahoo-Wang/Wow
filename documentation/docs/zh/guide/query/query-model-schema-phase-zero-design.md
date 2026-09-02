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

0 阶段允许 `wow-api` source/binary breaking change，但保持 Query JSON wire contract。

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

- Filter、RelativeTime、Aggregation 与 Schema Metadata 的字段类型统一为 `QueryField`；
- 不保留 `LogicalField` typealias、兼容类或 `value` 属性；
- `QueryModel`、`QueryCapability`、`QueryValueType` 仍是标识符，继续使用 `value`；
- `Projection` 保持 `List<String>`，因为允许 `state.*` 等通配符；
- `Sort.field` 在 0 阶段保持 `String`，Resolver 内部按需转换为 `QueryField`；
- JSON 仍将 QueryField 序列化为字符串。

`absoluteTo` 与 `relativeTo` 是唯一字段层级组合入口。Resolver、Provider fallback 和 Backend Compiler 不再自行拼接 `"$parent.$field"`。

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
- 只校验已精确声明字段的内建值类型；
- 暴露 Mask 状态；
- 保存 Adapter 根据完整字段上下文计算出的不可变 `rewriteMode`。

`matchesValueTypes` 不宣称执行完整字段验证：它不新增 enum、nullable、required 或 cardinality 规则。`supports()` 不存在，调用方使用 `binding(capability) != null`。

Dynamic 派生必须根据派生后的 source、Binding、projection 与 semantic 状态重新得到准确 Mode，不能盲目继承父字段 Mode。

Metadata 投影仍由 `QueryModelSchema` 负责，因为逻辑字段身份来自 Model 的字段索引，不属于独立字段值。

## QueryModelSchema

```kotlin
data class QueryModelSchema(
    val model: QueryModel,
    val capabilities: Set<QueryCapability>,
    val fields: Map<QueryField, QueryFieldSchema>,
) {
    private val maskedFields: Map<QueryField, QueryFieldSchema> =
        fields.filterValues(QueryFieldSchema::masked)

    private val hasConditionalModelRewrite: Boolean =
        model == QueryModel.EVENT_STREAM && maskedFields.isNotEmpty()

    val rewriteMode: QueryRewriteMode = when {
        hasConditionalModelRewrite ||
            fields.values.any { it.rewriteMode != QueryRewriteMode.NONE } ->
            QueryRewriteMode.INFER

        else ->
            QueryRewriteMode.NONE
    }

    private val resolver = QuerySchemaResolver(this)

    fun supports(capability: QueryCapability): Boolean =
        capability in capabilities

    fun field(field: QueryField): QueryFieldSchema?

    fun resolve(query: ISingleQuery): QuerySchemaResolution<ISingleQuery>
    fun resolve(query: IListQuery): QuerySchemaResolution<IListQuery>
    fun resolve(query: IPagedQuery): QuerySchemaResolution<IPagedQuery>
    fun resolve(query: ICursorQuery): QuerySchemaResolution<ICursorQuery>
    fun resolve(filter: FilterExpression): QuerySchemaResolution<FilterExpression>
    fun resolve(query: AggregationQuery): QuerySchemaResolution<AggregationQuery>

    fun toMetadata(): QueryModelSchemaMetadata
}
```

当前内置 Model 没有“所有查询必然改写”的模型级规则，因此 Model 构造只产生 `NONE` 或 `INFER`。`REQUIRED` 已在 Field 作用域存在真实语义；若未来出现实际的模型级无条件规则，再直接在属性初始化器中增加具体条件，不预建一次性 `resolveRewriteMode()` 函数。

### Field 查找

`field(QueryField)` 按以下顺序执行：

1. 精确声明字段；
2. 最近的 dynamic ancestor；
3. 由 ancestor 调用 `resolveDynamic(source, relative)`；
4. 未知且没有 dynamic ancestor 时返回 `null`。

精确字段始终覆盖 dynamic ancestor。Dynamic 查找不增加无界缓存。

### Query 解析

`QueryModelSchema` 是六类 Query 的唯一业务入口。`QuerySchemaResolver` 降为内部算法实现：

| 输入 | 输出行为 |
|---|---|
| `ISingleQuery` | Filter、Projection、Sort 完成准入并按需改写 |
| `IListQuery` | Filter、Projection、Sort 完成准入并按需改写 |
| `IPagedQuery` | Filter、Projection、Sort 完成准入并按需改写 |
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
- `REQUIRED`：使用 Binding 的 `resolvedField`。

### Query 作用域

以下无字段输入直接 O(1) 返回：

- `MatchAllFilter`；
- `MatchNoneFilter`；
- 空 Sort；
- 不需要 EventStream Mask 内部字段的 `Projection.ALL`。

不增加 `hasNoFieldReferences()` 预扫描。Composite Filter 只遍历一次；叶节点未变化时保留原引用，全部子节点未变化时保留原 Composite，Query 的 Filter、Projection、Sort 全部未变化时保留原 Query。

MongoDB 常规查询和 Elasticsearch identity mapping 的目标合同是：

```kotlin
resolution.value === inputQuery
```

只有 alias、keyword multi-field、nested、temporal 配置或模型级规则等真实变化才分配新节点。

## 不可变合同

- Schema、Field Schema、Binding 构造完成后不再修改；
- Provider refresh 发布新 Model 实例，不修改旧实例；
- 不为每次 Provider 发布、Context 或查询订阅深复制集合与 JsonNode；
- 自定义 Source、Adapter 与 Provider 遵守同一所有权合同；
- `copy()` 构造新模型并重新计算派生状态。

只有存在已证明的不受信可变输入边界时，才在该边界局部快照。

## 兼容性

- `LogicalField → QueryField` 是 Kotlin/Java source 与 binary breaking change；
- 不提供 typealias、废弃桥接类或重载兼容层；
- Filter、RelativeTime、Aggregation、Schema Metadata 与下游 DSL 同步迁移；
- Query JSON 中字段仍为字符串，wire contract 不变；
- Projection 通配符和现有 HTTP/OpenAPI JSON 形态保持不变。

## 0 阶段范围

包含：

- `wow-api` 的 QueryField 及所有字段引用；
- `wow-query` 的 QueryFieldSchema、QueryModelSchema 与内部 Resolver；
- MongoDB、Elasticsearch Schema Adapter 与 Compiler；
- Schema Source、Merger、Mask、Metadata 的类型迁移；
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

1. `LogicalField` 从生产、测试、文档与 Benchmark 源码中完全删除；
2. QueryField JSON round-trip 与改造前 wire 字符串一致；
3. QueryField 的 absolute、relative、append 行为由单元测试覆盖；
4. 六类 Query 只能通过 `QueryModelSchema.resolve`；
5. QuerySchemaResolver 不再是外部业务入口；
6. Dynamic Field 使用最近 ancestor，正确派生 resolved、physical、projection 与 Rewrite Mode；
7. MongoDB、Elasticsearch Filter、Projection、Sort、Cursor、Aggregation 行为与改造前一致；
8. Mask、Element scope、Temporal、Value type、Cursor compatibility 合同保持不变；
9. MongoDB 常规 Query 与 Elasticsearch identity mapping 验证零改写对象复用；
10. Resolver JMH 覆盖 `NONE`、`INFER`、`REQUIRED` 和 dynamic path，并检查吞吐与 `gc.alloc.rate.norm`；
11. `:wow-api:check`、`:wow-query:check`、MongoDB 与 Elasticsearch 相关检查通过；
12. 文档构建通过。
