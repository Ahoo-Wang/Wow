# 快照集合聚合设计

## 背景

Wow 当前支持快照的单条、列表、分页与计数查询，但不能把状态中的对象集合展开后进行分组和指标计算。例如，订单快照中的 `state.items` 只能作为整体字段查询，不能按商品统计数量或金额。

此前的实现尝试同时证明任意 Jackson 序列化与任意 Elasticsearch mapping 在 MongoDB、Elasticsearch 上完全等价，导致字段扫描、Schema 重写和 mapping 防御持续扩大。本设计只实现标准 Wow 查询路径的可执行聚合，不建立通用可移植性证明器。

## 目标

- 在现有 `SnapshotQueryService` 增加表格型聚合查询。
- 支持根快照以及一条父子对象集合展开链。
- 支持每层 Elements 独立过滤。
- 支持 Terms、Histogram、DateHistogram 分组。
- 支持 Count、Sum、Avg、Min、Max 指标。
- MongoDB 与 Elasticsearch 使用各自原生聚合能力。
- 复用现有查询 filter chain、ABAC、HTTP Guard、ErrorHandler 和 OpenAPI 组件。
- 保持实现精炼，只验证请求自身即可确定的结构。

## 非目标

- 不实现 Batch、Facet 或多表返回。
- 不实现算术表达式；只保留已确认的表达式扩展边界。
- 不支持兄弟集合展开形成笛卡尔积。
- 不提供 `reverse_nested`、`$lookup`、窗口函数、原始 Mongo pipeline 或原始 Elasticsearch script。
- 不使用 `TypeFieldPaths` 判断字段是否存在、是否为对象集合或字段类型。
- 不保证自定义 Jackson serializer/converter 的跨后端等价性。
- 不保证自定义 Elasticsearch mapping（包括 runtime、`copy_to`、`null_value` 和类型强制转换）的跨后端等价性。
- 不生成 Elements、Numeric、Temporal 等专用字段枚举。
- 不动态复制或改写 `FilterExpression` Schema。
- 不增加 aggregation 专用配置项、JVM ABI 桥接、JMH benchmark、报告文件或 CI 工作流。

## 公共 API

### AggregationQuery

```kotlin
data class AggregationQuery(
    override val filter: FilterExpression = MatchAllFilter,
    val elements: List<AggregationElement> = emptyList(),
    val groupBy: List<AggregationGroup> = emptyList(),
    val metrics: List<AggregationMetric>,
    override val sort: List<Sort> = emptyList(),
    val limit: Int = DEFAULT_LIMIT,
) : FilterCapable<AggregationQuery>, SortCapable
```

首期使用以下固定上限，不增加对应配置项：

- `DEFAULT_LIMIT = 100`
- `MAX_LIMIT = 10_000`
- `MAX_ELEMENTS = 5`
- `MAX_GROUPS = 32`
- `MAX_METRICS = 64`
- `MAX_SORT_FIELDS = 32`

`withFilter` 返回复制了根 filter 的查询，使现有 ABAC 与路由 filter 重写可以直接复用。

### Elements

```kotlin
data class AggregationElement(
    val path: LogicalField,
    val filter: FilterExpression = MatchAllFilter,
)
```

`elements` 是有序父子展开链。例如：

```text
state.orders
state.orders.lines
state.orders.lines.discounts
```

每层可以声明自己的 filter。数组中的后一个路径必须严格以 `前一个路径 + "."` 开头，因此重复路径和兄弟集合都会被拒绝。

公共 HTTP 模型始终使用绝对路径。Kotlin DSL 在嵌套 `expand {}` 中允许相对路径，但构建结果必须规范化为绝对路径。

### 分组

```kotlin
sealed interface AggregationGroup {
    val field: LogicalField
    val alias: String

    data class Terms(...)
    data class Histogram(..., val interval: Double)
    data class DateHistogram(..., val unit: AggregationDateUnit, val timeZone: String = "UTC")
}
```

Histogram interval 必须是有限正数。DateHistogram 时区直接使用 JDK `ZoneId.of()` 验证，不维护时区资源列表或专用 Schema provider。

### 表达式与指标

```kotlin
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(JsonSubTypes.Type(AggregationExpression.Field::class, name = "FIELD"))
interface AggregationExpression {
    data class Field(val field: LogicalField) : AggregationExpression
}

sealed interface AggregationMetric {
    val alias: String

    data class Count(override val alias: String) : AggregationMetric

    data class Numeric(
        val function: AggregationFunction,
        val expression: AggregationExpression,
        override val alias: String,
    ) : AggregationMetric
}
```

首期只注册和支持 `FIELD`。表达式接口不使用 `sealed`，避免以后增加 `ADD`、`MULTIPLY` 或 `COALESCE` 时破坏调用方的穷举 `when`。当前编译器遇到非 `Field` 实现时返回 unsupported error。

DSL 提供 `sum("state.amount")` 等快捷方法，调用者不需要手工构造 `AggregationExpression.Field`。

### 结构校验

公共层只校验请求本身可确定的事实：

- 数量和 limit 不超过固定上限。
- metrics 不为空。
- Elements 构成严格父子前缀链。
- element filter 不包含 ID、租户、所有者、空间、删除、搜索等根级操作符。
- element filter 中的字段属于当前 element 路径。
- 存在 Elements 时，group 与 metric 字段属于最内层 element 路径。
- alias 是非空的单个逻辑路径段、全局唯一，且不使用框架内部保留前缀。
- sort 字段唯一且只引用 group/metric alias。
- 无 groupBy 时禁止 sort。
- interval、时区和其他本地值满足基本格式要求。

公共层明确不校验字段存在性、对象集合类型、数值/时间类型或物理 mapping。

## 返回合同

`aggregate` 返回 `Flux<DynamicDocument>`。每个文档是一行，key 为 group/metric alias。

- 无 `groupBy` 时返回一行汇总结果。
- 有 `groupBy` 时返回不超过 `limit` 的分组行。
- Count 返回 `Long`。
- Sum、Avg、Min、Max 返回有限 `Double`；没有贡献值时返回 `null`。
- 完全空的数据集仍返回一行汇总：Count 为 `0`，数值指标为 `null`。
- 不向公共结果暴露 MongoDB `Decimal128` 或 Elasticsearch 专用数值类型。
- 精确金额由调用方使用缩放整数建模。

sort 只引用输出 alias。未显式排序的 group alias 按声明顺序追加升序，作为稳定决胜字段。group alias 排序是普通操作；metric alias 排序由现有 `allowExpensiveOperators` 控制。

## 服务与过滤链

`SnapshotQueryService` 直接增加：

```kotlin
fun aggregate(query: AggregationQuery): Flux<DynamicDocument> =
    Flux.error(UnsupportedOperationException("Snapshot aggregation is not supported by [$name]."))
```

默认实现只保证现有实现重新编译时保持源码兼容，不保留旧 JVM method descriptor，也不增加新的 capability interface。

`SnapshotQueryHandler` 增加对应入口，`QueryType` 增加 `AGGREGATION`。聚合复用现有 `QueryContext` 和 `SnapshotQueryFilter` chain：

`SnapshotQueryHandler.aggregate` 同样提供返回 `UnsupportedOperationException` 的默认实现，保证现有自定义 handler 重新编译时源码兼容；`DefaultSnapshotQueryHandler` 覆盖它并进入现有 filter chain。

```text
SnapshotQueryHandler.aggregate
→ QueryType.AGGREGATION
→ SnapshotQueryFilter chain
→ TailSnapshotQueryFilter
→ SnapshotQueryService.aggregate
```

- ABAC、租户及路由 filter 继续追加到根 filter。
- `MaskingSnapshotQueryFilter` 对 `AGGREGATION` 直接跳过：不拒绝、不检查 masker、不处理结果。
- 不新增 `SnapshotAggregationQueryFilter`、专用 context 或第二套 handler chain。

## MongoDB 编译

MongoDB 使用独立的内部 `MongoAggregationCompiler`，复用现有 `SnapshotFilterConverter`：

```text
根 $match
→ 对每个 element 执行 $unwind
→ 对该层 element filter 执行 $match
→ 排除缺失或 null 的分组键
→ $group
→ $project
→ $sort
→ $limit
```

指标直接使用 MongoDB accumulator。编译器增加最小的贡献值计数，以便把没有贡献值的 Sum、Avg、Min、Max 统一为 `null`，而不是错误地返回 `0`。

编译器不读取 Java 类型或 Mongo schema。不存在、不可展开或类型错误的路径由 MongoDB 执行结果或错误决定。

## Elasticsearch 编译

Elasticsearch 使用独立的内部 `ElasticsearchAggregationCompiler`：

```text
根 query
→ 按 elements 顺序构造 nested/filter aggregation
→ 最内层 composite sources 与 metric sub-aggregations
→ composite 分页
→ 精确排序与 limit
```

标准 `SnapshotFilterConverter` 下复用现有 `ElasticsearchIndexMappingResolver`：

- Elements 调用 `requireNested`。
- Terms 使用现有 exact field resolution，以解析 `.keyword` 等标准 multi-field。
- element filter 使用现有 filter mapping resolution。
- Histogram、DateHistogram 和 numeric metric 只解析出可执行字段；类型不匹配由 Elasticsearch 返回错误。

自定义 filter converter 或 mapping 不获得额外可移植性处理；使用者负责提供可执行物理路径。

group alias 排序使用 composite source 原生顺序，只读取达到 limit 所需的页面。metric alias 排序必须完整遍历 composite buckets，再在客户端维护有界 Top-N；不使用语义不等价的 `terms` 或 `bucket_sort` 近似。

聚合分页复用现有 `ElasticsearchQueryPager` 的 PIT 生命周期。实现时只提取 open、更新最新 PIT ID、close 三项内部逻辑，普通查询和聚合分页共同使用。完成、错误和取消都会关闭最新 PIT ID。

## HTTP Guard、路由与客户端

新增单一路由：

```text
POST /{context}/{aggregate}/snapshot/aggregation
```

JSON 响应为数组，SSE 响应为 `DynamicDocument` 流。复用现有 body extractor、ErrorHandler 与 idle timeout。

`HttpQueryGuardFilter` 只增加以下分支：

- 根 filter 与所有 element filter 一起计入现有 filter 节点和值数量。
- 聚合 limit 复用 `maxListSize`。
- Elements 与 metric alias 排序复用 `allowExpensiveOperators`。

不增加 `maxAggregationElements`、`maxAggregationMetrics` 或其他 aggregation 配置。

OpenAPI 直接生成通用 `AggregationQuery` Schema。聚合专属 RequestBody 复用 main 已有的 `*AggregatedFields` `$ref`，但不生成 Elements 或类型专用字段枚举，也不修改 `FilterExpression` Schema。

ApiClient 增加独立的 `SnapshotAggregationQueryApi`、`ReactiveSnapshotAggregationQueryApi` 和 `SynchronousSnapshotAggregationQueryApi`，不修改现有 composite query API。

## 错误策略

- 请求自身结构错误使用 `IllegalArgumentException`，由现有 ErrorHandler 转换。
- MongoDB/Elasticsearch 不支持路径、字段类型或 mapping 时保留后端错误。
- 后端返回 NaN 或正负无穷时整体失败，不把非有限数值写入公共结果。
- 不把错误降级为空结果，不尝试统一两个后端的错误文本。
- Flux 任一编译、分页或执行阶段失败即整体失败。
- PIT close 失败只记录日志，不覆盖原始查询结果或错误。

对于无效字段、自定义 serializer/converter 或自定义 mapping，MongoDB 与 Elasticsearch 可以在不同阶段产生不同错误或结果；该行为明确不属于跨后端合同。

## 测试策略

测试只覆盖公共合同和真实执行路径：

- `wow-api`：模型构造、JSON 多态、alias/sort/Elements 结构校验。
- `wow-query`：DSL 相对路径规范化、现有 filter chain、ABAC 与 masker 跳过。
- `wow-mongo`：pipeline 编译和实际集成测试。
- `wow-elasticsearch`：nested/composite 编译、分页、精确 metric Top-N，以及 PIT 完成/错误/取消清理。
- 双后端 TCK：根快照聚合、单层/多层 Elements、每层 filter、三种分组、五种指标、空集、null、稳定排序和 limit。
- `wow-webflux`：HTTP 路由、Guard、JSON/SSE 响应。
- `wow-openapi`：Schema 快照和一次真实 `example-server /v3/api-docs` 请求。
- `wow-apiclient`：同步/响应式请求与返回类型。
- 中英文查询文档：公共模型、Elements 作用域、HTTP/DSL 示例与明确非目标。

不建立 Jackson annotation 与 Elasticsearch mapping 的组合测试矩阵，不设置 patch coverage 百分比目标。

## 验证

实现阶段使用 TDD，从最窄模块测试开始。完成前至少运行：

```bash
./gradlew detekt \
  :wow-api:check \
  :wow-query:check \
  :wow-mongo:check \
  :wow-elasticsearch:check \
  :wow-webflux:check \
  :wow-openapi:check \
  :wow-apiclient:check \
  :wow-spring:check \
  :wow-spring-boot-starter:check

./gradlew \
  :wow-mongo:integrationTest \
  :wow-elasticsearch:integrationTest

cd documentation && pnpm docs:build
```

另外启动一次 `example-server`，通过实际 HTTP 请求验证 `/v3/api-docs` 中的 aggregation route、RequestBody Schema、`x-wow-query-fields` 引用和响应 Schema。临时响应与日志不提交仓库。

## 完成条件

- 公共模型、DSL、服务入口、HTTP 路由和 ApiClient 使用统一 `Aggregation*` 词汇。
- MongoDB 与 Elasticsearch 对标准 Wow 数据路径通过同一套 TCK。
- Elements 多层过滤、稳定排序、精确 metric Top-N 与 PIT 清理均有可运行验证。
- 现有查询 filter chain、ABAC、Guard 和 ErrorHandler 被复用，没有第二套策略链。
- 没有引入字段目录、mapping 可移植性证明器、动态 FilterExpression Schema 重写或 aggregation 专用配置。
- 相关模块检查、双后端集成测试、文档构建和真实 OpenAPI HTTP 验证通过。
