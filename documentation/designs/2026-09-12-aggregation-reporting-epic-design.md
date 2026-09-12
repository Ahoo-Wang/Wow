# 聚合查询报表场景 Epic：指标级过滤、派生指标与后续阶段

日期：2026-09-12

状态：Phase 1-3 已交付（#3234/#3240/#3241）；Phase 4 设计已细化待批准；Phase 5-6 为范围提纲（各阶段动手前细化）。

基线：`main` `6cf8be185`（含 PR #3233：DISTINCT_COUNT / STDDEV / VARIANCE / MEDIAN / PERCENTILE 已交付）。前置设计：[2026-09-11 聚合指标函数扩展](2026-09-11-aggregation-metric-functions-design.md)。

## 目标与范围

补齐报表场景的服务端聚合能力。原始需求 9 项，其中：

- **已交付（PR #3233）**：③ 去重计数（`DISTINCT_COUNT`）、⑧ 分位数/标准差（`PERCENTILE`/`MEDIAN`/`STDDEV`/`VARIANCE`）——移出本 Epic。
- **本 Epic 剩余 7 项**：① 指标级过滤（漏斗）、② 派生指标（比率）、⑤ HAVING、⑥ 空桶补齐、⑦ TERMS 截断/缺失桶、⑨ 批量聚合端点、④ 子聚合。

## 分期

| 阶段 | 能力 | PR | 状态 |
|---|---|---|---|
| Phase 1 | 指标级过滤（漏斗/状态对比） | 独立 PR | 已交付（#3234） |
| Phase 2 | 派生指标（客单价/毛利率/达成率） | 独立 PR | 已交付（#3240） |
| Phase 3 | HAVING（按聚合值筛选分组） | 独立 PR | 已交付（#3241） |
| Phase 4 | 分桶体验：空桶补齐 + 缺失桶 + 截断语义澄清 | 独立 PR | 设计已细化，待批准 |
| Phase 5 | 批量聚合端点（仪表盘一次加载） | 独立 PR | 范围提纲 |
| Phase 6 | 子聚合（层级钻取） | 独立 Epic | 范围提纲（结构风险最高） |

每个阶段独立可交付，不依赖未合并的后续阶段；各阶段动手前完成细化设计 → 实施计划 → PR。

## 贯穿约束（承接 #3233 的设计原则）

1. **不为存储端低版本做不支持或降级实现**：直接用原生算子，版本要求仅文档注明，运行时不做版本探测/门控。
2. wire 兼容纯增量：不改既有字段/类型/JSON `type` 值与语义；无新能力的 JSON 与现状字节兼容。
3. 跨后端语义由 TCK 锚定；确有差异的（近似、内存策略）以容差断言并文档化口径。
4. 严格 TDD；测试用 fluent-assert；sealed `when` 全链路穷尽、无 `else`。
5. HTTP 门控（HttpQueryGuard）：新增查询能力须评估 `allowExpensiveOperators=false` 与 `maxFilterValues` 的适用性。

## Phase 1：指标级过滤（漏斗）

### AST

`AggregationMetric` 全部五个子类型（`Count`/`Numeric`/`Any`/`DistinctCount`/`Percentile`）各追加末位字段：

```kotlin
val filter: FilterExpression = MatchAllFilter
```

`@get:JsonInclude(NON_DEFAULT)` 省略默认值——无过滤的 JSON 与现状字节兼容，DSL 位置构造不受影响（`filter` 恒为最后参数）。`Derived`（Phase 2）同样携带该字段。

### 语义

- filter 作用于**当前作用域的记录**（根文档或最内层 element），不匹配的记录对该指标**零贡献**，不影响其他指标、分组与排序。
- `COUNT + filter` = 条件计数，空匹配结果为 `0`（计数语义）。
- `Numeric`/`DistinctCount`/`Percentile`/`Any` + filter：仅匹配记录的参与值/值参与；空匹配沿用各指标既有空语义（`null`；`DistinctCount` 仍为 `0`——它本就以 0 为空集语义）。

### 验证（QuerySchemaValidation）

- metric filter 复用 element-filter 的作用域校验（element 内不得含根级过滤器）；字段能力校验同常。
- HttpQueryGuard：metric filter 不属昂贵算子；其过滤值数并入既有 `maxFilterValues` 计数。

### 双后端

- **Mongo**：参与值表达式外包 `$cond[filter, 参与, null/零贡献]`——`COUNT` → `$sum($cond(filter,1,0))`；`value_count` 守卫条件变为 `filter ∧ 参与`；`DistinctCount` 的 `$addToSet` 输入包 `$cond[filter, value, null]`（null 由投影过滤）；`Any` 用 `$max($cond(filter, field, null))`（`$max` 忽略 null）。
- **Elasticsearch**：带 filter 的指标包一层 `filter` 子聚合（条件命中文档为其 `doc_count`），指标与 `valueCount` 守卫收在内层；`COUNT + filter` 读该 filter agg 的 `doc_count`；`value()` 导航为该类指标增加一层；分页/Top-N/`effectiveSort` 结构不变。

### DSL

```kotlin
aggregation {
    dateHistogram("createdAt", AggregationDateUnit.DAY, "day")
    count("created")
    count("paid") { "status" eq "PAID" }
    sum("amount", "paidAmount") { "status" eq "PAID" }
    distinctCount("customerId", "paidCustomers") { "status" eq "PAID" }
    percentile("amount", 95.0, "paidP95") { "status" eq "PAID" }
}
```

（带 `filter` 尾 lambda 的重载；无过滤重载签名不变。）

### TCK 契约场景

① 漏斗三环同图（created/paid/shipped 条件计数）② 条件 SUM/去重/百分位 ③ `COUNT+filter` 空匹配 = 0、数值类 = null ④ metric filter 与 element filter 叠加 ⑤ 按 filter 指标别名排序（Top-N）⑥ element 作用域内 metric filter 作用域正确性。

## Phase 2：派生指标

### AST

新子类型：

```kotlin
data class Derived(
    override val alias: String,
    val expression: DerivedExpression,
) : AggregationMetric

sealed interface DerivedExpression {
    data class MetricRef(val metric: String) : DerivedExpression
    data class Constant(val value: Double) : DerivedExpression
    data class Binary(
        val operator: AggregationExpressionOperator,  // 复用 ADD/SUBTRACT/MULTIPLY/DIVIDE
        val left: DerivedExpression,
        val right: DerivedExpression,
    ) : DerivedExpression
}
```

不复用 `AggregationExpression`（记录级表达式），两者语义域不同，混用会误导 OpenAPI 与验证。深度/节点上限沿用 `MAX_EXPRESSION_DEPTH`/`MAX_EXPRESSION_NODES`。`Derived` **不携带 filter 字段**：filter 是记录级概念，派生在聚合之后计算，二者属不同语义层，不叠加。

### 引用规则（构造期校验，AggregationQuery.init）

- `MetricRef` 只能引用**声明序在前**的指标（无前向引用 → 结构上无环）；被引用别名必须存在、不得为分组别名、不得带 `__wow` 前缀。
- 派生可引用非派生指标（含 `COUNT`，计数值作数值参与）与先声明的派生。

### 语义

- **null 传播**：任一操作数 `null` → 结果 `null`（被引用指标空语义随之传播）。
- **除零 → null**、结果须有限 double——与既有 BINARY 口径一致。
- 与 Phase 1 叠加：`filteredSum / filteredCount` = 已支付客单价等。

### 双后端

- **Mongo**：`$project` 不能引用同阶段输出 → 派生在**追加的第二个 `$project` 阶段**按声明序计算（读第一阶段已守卫的别名输出；`$cond` 实现 null 传播与除零守卫，模式同 `finiteDouble`）。
- **Elasticsearch**：`bucket_script` 管道聚合置于 composite 桶内，`buckets_path` 引用兄弟聚合（`_count`、指标 value、percentiles 的 `alias[95.0]` 路径、先声明 bucket_script 的别名）；`gap_policy=skip` 产生 null，与 null 传播一致。
- 按派生别名排序复用 Top-N 路径；ungrouped summary 同步支持。

### DSL

```kotlin
aggregation {
    terms("state.status", "status")
    count("paid") { "status" eq "PAID" }
    sum("amount", "paidAmount") { "status" eq "PAID" }
    derived("paidAov") { ref("paidAmount") / ref("paid") }          // 已支付客单价
    sum("targetAmount", "target")
    derived("attainment") { ref("paidAmount") / ref("target") }     // 达成率
    derived("margin") { (ref("paidAmount") - ref("target")) / ref("paidAmount") }
    sort { "paidAov".desc() }
}
```

（`ref(alias)` 引用先声明指标；`+ - * /` 运算符重载沿用 `AggregationQueryDsl` 既有模式；尾 lambda 为派生表达式块。）

### TCK 契约场景

① 客单价 `SUM/COUNT` ② 达成率 `SUM/SUM` ③ 毛利率（复合表达式）④ 除零 → null ⑤ null 传播（引用空语义指标）⑥ 派生链（引用先声明派生）⑦ 按派生别名排序 ⑧ filter × derived 叠加 ⑨ 前向引用/未知别名/分组别名引用构造期拒绝。

## Phase 3：HAVING（按聚合值筛选分组）

### AST

`AggregationQuery` 追加可选字段（默认 `null`、序列化省略——无 having 的 JSON 字节不变）：

```kotlin
val having: HavingExpression? = null

@MissingTypeImpl 不适用（新类型，wire 形态恒显式携带 type）
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = QueryProtocol.Polymorphic.TYPE)
@JsonSubTypes(
    JsonSubTypes.Type(HavingExpression.Condition::class, name = "CONDITION"),
    JsonSubTypes.Type(HavingExpression.Between::class, name = "BETWEEN"),
    JsonSubTypes.Type(HavingExpression.In::class, name = "IN"),
    JsonSubTypes.Type(HavingExpression.IsNull::class, name = "IS_NULL"),
    JsonSubTypes.Type(HavingExpression.And::class, name = "AND"),
    JsonSubTypes.Type(HavingExpression.Or::class, name = "OR"),
)
sealed interface HavingExpression {
    data class Condition(
        val metric: String,              // 引用声明序中任意位置的指标别名（含派生）
        val operator: ComparisonOperator,
        val value: Double,               // 单值比较
    ) : HavingExpression

    data class Between(
        val metric: String,
        val lower: Double,
        val upper: Double,               // 双闭区间，与 BetweenFilter 口径一致
    ) : HavingExpression

    data class In(
        val metric: String,
        val values: List<Double>,        // 非空、有限
    ) : HavingExpression

    data class IsNull(
        val metric: String,
        val negated: Boolean = false,    // false = IS NULL，true = IS NOT NULL
    ) : HavingExpression

    data class And(val operands: List<HavingExpression>) : HavingExpression
    data class Or(val operands: List<HavingExpression>) : HavingExpression
}

enum class ComparisonOperator { EQ, NE, GT, GTE, LT, LTE }
```

不复用 `FilterExpression`（字段级语义、能力校验、作用域解析对聚合别名无意义）；不复用 `DerivedExpression.MetricRef` 类型本身，但**引用规则同源**：裸别名 String，构造期校验。

### 引用与构造期校验（AggregationQuery.init）

- `having != null` 时 `require(groupBy.isNotEmpty())`——HAVING 是分组级概念，无分组摘要行过滤无意义（客户端可从单行自行判断）。
- `Condition`/`Between`/`In`/`IsNull` 的 `metric` 必须是**已声明的指标别名**：不得为分组别名、不得为 `ANY` 指标（值域非数值，比较无意义）、不得带 `__wow` 前缀（别名规则已禁）。与派生引用不同：**不要求声明序在前**——having 在全部指标落定后求值，前向引用天然合法。
- 数值边界：所有 Double 值必须有限；`Between` 要求 `lower <= upper`；`In.values` 非空且去重后 ≤ `MAX_FILTER_VALUES` 量级（并入 HttpQueryGuard 值计数，见下）。
- 嵌套深度沿用 `MAX_EXPRESSION_DEPTH`。

### 语义（SQL HAVING 口径）

- **null 判假**：引用值为 `null`（指标空语义：Numeric/Percentile/Any 空匹配、派生 null 传播）时，`EQ/NE/GT/GTE/LT/LTE/Between/In` 一律不成立（SQL 三值逻辑的工程化简化：UNKNOWN → false）。`IsNull` 恰好捕获这些行。
- 数值统一按 IEEE double 比较（`COUNT`/`DISTINCT_COUNT` 的 long 在 2^53 内无损，文档注明）。
- `limit` 语义定义为**过滤后**行数；`sort` 作用于过滤后的行序（与 SQL 一致）。跨后端一致由 TCK 锚定。

### 双后端

- **Mongo**：派生 `$project` 阶段链之后、`$sort` 之前追加单个 `$match`（此时全部指标别名已是守卫后终值——无需提纲中预警的"三段式 project"：派生阶段已重包含全部别名）。比较算子直接使用 match 操作符文档，**null 判假需显式守卫**：BSON 全序中 `null < 数值`，`{alias: {$gt: 0}}` 会命中 null——除 `IsNull` 外每个条件必须并上 `{alias: {$ne: null}}`；`EQ/NE` 同理（`{alias: {$eq: v, $ne: null}}`/`{$ne: v, $ne: null}`）。`IsNull` → `{alias: null}`（投影后字段恒存在，null 即语义 null）；`IsNotNull` → `{$ne: null}`。`And/Or` → `$and`/`$or`。
- **Elasticsearch**：composite 无 bucket_selector——**客户端求值**。`toRow` 产出行后按 having 过滤：
  - **metric 排序路径**（Top-N）：行在进入 `BoundedTopRows` 前过滤——分页行为不变（本就全量扫描取 Top-N），仅留存行减少。
  - **分组排序路径**：按页超取直至**存活行数 ≥ limit** 或桶耗尽。两处既有机制须调整：`pageSize` 的 `min(pageCapacity, limit - fetched)` 封顶对 having 放开（恒取 `pageCapacity`，否则会在凑齐存活行前截断）；`shouldStop` 的 `rows.isEmpty()` 短路对 having 放开（整页被滤空 ≠ 桶耗尽，仅 `afterKey` 为空可停）——`fetched` 改计**存活行数**。
  - 求值用与 Mongo 相同的 null 判假口径（行内 `alias` 缺失或 JSON null → 条件不成立）。
- 无 having 查询的双后端行为逐字节不变（门控发射/求值）。

### DSL

```kotlin
aggregation {
    terms("state.status", "status")
    count("paid") { "status" eq "PAID" }
    sum("amount", "paidAmount") { "status" eq "PAID" }
    derived("attainment") { ref("paidAmount") / constant(6000.0) }
    having {
        ("attainment" gte 0.8) and ("paid" gt 10)
    }
    sort { "attainment".desc() }
    limit(20)
}
```

（`HavingDsl` 上下文：`String` 接收者的 `eq/ne/gt/gte/lt/lte(Double)`、`between(l, u)`、`isIn(List<Double>)`、`isNull()/isNotNull()` 返回 `HavingExpression`；`and`/`or` 中缀组合。`@QueryDslMarker` 独立上下文，避免与 FilterDsl 助手名冲突的同签名问题——FilterDsl 的 `eq` 接收者是字段路径 String 且产 FilterExpression，两者返回类型不同可共存于不同上下文。）

### HttpQueryGuard

- having 的字面值并入 `maxFilterValues` 计数（`In` 计 N 值、`Between` 计 2、单值计 1；复用既有计数 walker 的口径）。
- 比较不属于昂贵算子——不进 `allowExpensiveOperators` 判定。

### TCK 契约场景

① 计数阈值（`"paid" gt N` 留部分分组）② 派生阈值（`attainment gte 0.8`）③ null 判假（对含 null 指标的分组 `gt 0` 不命中、`isNull` 恰好命中）④ 过滤后 limit（3 个存活分组 + `limit(2)` + 排序 → 恰好前 2 行，双后端一致）⑤ 全滤空（`gt 极大值` → 空 Flux，非空行/摘要）⑥ AND/OR 组合 ⑦ `Between`/`isIn` ⑧ 构造期拒绝（无 groupBy、未知别名、分组别名、ANY 指标、非有限值、空 In）——AST 层单测，不进 TCK。

### 性能与文档口径（文档化，不做门控）

- Mongo：`$match` 位于分组之后，聚合值无索引可用——选择性收益有限，但避免向客户端传输被滤行。
- ES 分组排序路径：最坏全桶扫描（PIT 分页直至耗尽）；metric 排序路径行为不变。选择性差的 having 在大数据量下是成本放大器，文档给出建议（优先 metric 排序 + having）。

## Phase 4：分桶体验（空桶补齐 + 缺失桶 + 截断语义澄清）

### 范围决策（先决取舍）

1. **空桶补齐限定单维时间序列**：`dense` 仅当 `DateHistogram` 是唯一 groupBy 时合法；多维组合补齐（date × terms 笛卡尔补全）显式排除，留待 Phase 6 子聚合设计统一考虑。理由：ES composite 的跨页流式补齐只有在单维下才能与既有分页/having/limit 契约无冲突地组合（多维时非日期维的取值全集要枚举完才知窗口，破坏流式语义）。
2. **TERMS 截断不新增 `size`/`shard_size` 字段**：截断统一由既有 `sort`+`limit` 表达（分组序分页收满 limit 即止；metric 排序 = 全局 Top-N）。另立 `size` 会形成两套互相竞争的截断语义，且服务端预截断会破坏 metric 排序 Top-N 的全局正确性。本项交付为用户文档**专节澄清**，无代码变更。
3. **缺失桶限定字符串维度**：`missingKey` 仅允许声明在标量值类型为 STRING（`QueryValueKind.SCALAR` + `QueryValueType.STRING`）且 `cardinality == SINGLE` 的 Terms 字段上。哨兵是普通字符串键，双端都以字符串参与字典序——排序位置跨端可证明一致；数值/布尔字段的哨兵在 BSON 类型序与 Lucene 排序之间无法证明一致。`DateHistogram`/`Histogram` 不提供缺失桶（无时间/数值可定位的记录无处可归）。

### AST

```kotlin
data class Terms(
    override val field: QueryField,
    override val alias: String,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val missingKey: String? = null,
) : AggregationGroup

data class DateHistogram(
    override val field: QueryField,
    override val alias: String,
    val unit: AggregationDateUnit,
    val timeZone: String = "UTC",
    @get:JsonInclude(JsonInclude.Include.NON_FALSE)
    val dense: Boolean = false,
) : AggregationGroup
```

- 均为**尾参 + 默认值**：既有位置参数调用（含 JMH Java 基准）源码兼容；默认值序列化省略，无新能力的查询 JSON 字节不变。
- 不新增多态类型 → wow-schema 无 skipSubtypeLookup 变更；OpenAPI 快照纯增量。

### 构造期与 schema 校验

- AST `init`：`missingKey` 非空白；`dense == true` 要求 DateHistogram 是唯一 groupBy（"dense requires DATE_HISTOGRAM to be the only groupBy."）。`dense` 与 `missingKey` 因此互斥（后者必然引入第二个分组）。
- `QuerySchemaValidation.aggregate`：声明 `missingKey` 的 Terms 字段必须为 SCALAR/STRING 值类型且 `QueryCardinality.SINGLE`（口径与 ANY 指标一致），错误信息注明原因。

### 空桶补齐语义（时间序列补零口径）

- **窗口 = [首个实际桶键, 末个实际桶键]**，仅补内部间隙，首尾之外不补；空结果或单桶不产生补齐行。该窗口定义使 ES 无需 min/max 预查询即可流式补齐，并与 Mongo `$densify bounds:"full"`（= 输入数据的最小/最大值）逐字一致。
- **补齐行指标遵循各指标空语义**（与 Phase 2 emptySummary 同源）：Count/DistinctCount → 0；Numeric/Percentile/Any → null；Derived → 对上述值求值（null 传播、除零 → null）。
- **补齐行是普通行**：参与排序（按日期键就地插入网格位置，含 DESC 方向）；参与 having（null 失败一切比较，`count eq 0`/`count lte 0` 恰好命中补齐行）；计入 limit。
- metric 排序组合：补齐行同样进入 Top-N 竞争（count=0 的补齐行在 count DESC 下沉底，仅当 limit 超过实际桶数才会出现在结果中）。
- 成本口径：补齐桶数 = 窗口 × 粒度，超大窗口 + 秒级粒度在两端都是反模式（Mongo 受 `internalQueryMaxAllowedDensifyDocs`（默认 500,000）约束；ES 为客户端流式生成）。文档注明，不做门控。

### 双后端

**共享网格算术（新增于 wow-query，两端复用）**

- `DenseDateGrid(unit, timeZone)`：编译期以 `java.time` 构造 anchor——声明时区内对齐到单位网格的本地零点（WEEK 取周一本地零点；anchor 容许早于 1970 以兼容负索引）；提供 `indexOf(epochMillis)`（本地日历步数）与 `keyOf(index)`（反演回毫秒键）。
- 跨端网格一致性由构造保证：Mongo 的 `$dateTrunc/$dateDiff/$dateAdd(timezone)` 与 `java.time` 同为本地日历语义，TCK 契约钉死等值。
- 派生空值求值器 `DerivedExpression.evaluateOver` 与指标空值映射自 `AbstractMongoQueryBackend` 提升至共享模块供 ES 补齐行复用。

**Mongo（全程服务端管道内）**

- `dense`：$group 键从 `{$toLong: $dateTrunc}` 改为**桶索引** `{$dateDiff: [anchor, $dateTrunc(...), unit, timezone]}`；$group 后 `$set` 把 `_id.alias` 提升到顶层 → `$densify {field: <alias>, range: {step: 1, bounds: "full"}}`（**纯数值 densify**）→ `$project` 应用指标空语义（count 族 `$ifNull` 0、数值族显式 null、DistinctCount 空集）并把 alias 反演为显示键 `{$toLong: {$dateAdd: [anchor, "$<alias>", unit, timezone]}}` → 既有派生 $project/having $match/$sort/$limit 阶段原序复用（补齐行自然参与）。
- 设计依据（已查证官方手册）：`$densify` **不支持 timezone**，对 date 值的 month/quarter/year 步进是 UTC 日历算术，与 `$dateTrunc(timezone)` 生成的本地网格不对齐（固定 +8 偏移下月网格亦漂移）——索引空间 densify 规避该缺陷，且保留 having/limit 的服务端截断优势（不退化为全量拉取客户端补齐）。
- `missingKey`：Terms 守卫 `exists && ne null` 除去，分组键改 `{$ifNull: ["$<path>", missingKey]}`；字段缺失与显式 null 均归入哨兵键。

**Elasticsearch**

- `dense`：composite 不产生空桶，**pager 跨页流式补齐**——在页与页合并后的键序列上，于相邻实际桶之间按网格生成补齐行（含跨页间隙）；窗口随最后一个实际桶自然闭合，无预查询。补齐行参与 having 过滤与 Top-N 累积；停止条件的 `fetched` 计数含补齐行以维持 limit 语义。
- `missingKey`：为该源声明 keyword 运行时字段 `__wow_missing_<index>`（单值透传；缺失/null → 哨兵字符串），terms 源指向运行时字段——哨兵以普通字符串参与排序，与 Mongo 字典序一致。不采用 composite `missing_bucket`：其 null 键固定排在升序首位，与 Mongo `$ifNull` 字典序位置不一致。

### DSL

```kotlin
fun terms(field: String, alias: String, missingKey: String? = null)
fun dateHistogram(
    field: String,
    unit: AggregationDateUnit,
    alias: String,
    timeZone: ZoneId = ZoneOffset.UTC,
    dense: Boolean = false,
)
```

尾参默认值，既有 DSL 调用不变。

### HttpQueryGuard

`dense`/`missingKey` 是静态声明，不引入过滤节点或字面值——**无需变更**。

### TCK 契约场景

① DAY 序列内部间隙补零（count=0、sum=null、派生对补齐行求值）② 窗口首尾不补（数据从第 2 天起 → 无第 1 天行）③ 单桶不补 ④ 补齐行 × having（`count gt 0` 滤除补齐行；`count eq 0` 恰好命中）⑤ DESC 排序下补齐行网格落位 ⑥ limit 计入补齐行（limit < dense 行数 → 按网格序截断）⑦ WEEK/MONTH 网格 × 非 UTC 时区（Asia/Shanghai）跨端一致 ⑧ missingKey：缺失字段文档归入哨兵键、排序位置字典序、双端一致 ⑨ 校验拒绝（dense 非唯一 groupBy、missingKey 空白/非 STRING 字段/多值字段）——AST/schema 层单测。

### 文档与性能口径

- 中英文档新增「空桶补齐」「缺失桶」两节与「截断语义澄清」小节（sort+limit 即截断，无独立 size）。
- 存储版本要求仅文档注明，不做运行时门控/降级：MongoDB ≥ 5.1（`$densify`/`$dateDiff`，`dense` 新增）；Elasticsearch runtime fields（≥ 7.11）为既有 epoch 日期直方图路径已在用，`missingKey` 复用，无新增门槛。

## Phase 5-6 范围提纲（动手前细化）
- **Phase 5 批量聚合端点**：`POST .../aggregation:batch`（N 个 AggregationQuery）；ES `msearch`、Mongo `$facet`（内存受 100MB/阶段约束，超限回退服务端并发归并——细化时定案）；响应逐项对应；部分失败语义（逐项错误对象 vs 整体失败）细化时定案；HttpQueryGuard 按 N× 单查询限制收紧。
- **Phase 6 子聚合**：ES composite 不可嵌套——需评估嵌套 terms 聚合/多级 composite 模拟/内存归并的结构方案及其对分页与 metric 排序契约的冲击；结构风险最高，独立 Epic 设计。

## 测试与文档策略（每阶段相同）

- wow-api AST 构造/JSON 往返单测 → wow-query DSL/验证单测 → 双编译器单测（结构化 Document/计划断言）→ TCK 跨后端契约（Docker 集成）→ OpenAPI 快照对齐 → 中英文档（场景各 1+）→ 全量回归 + detekt。
- 版本要求（如出现新算子）仅文档注明。

## 完成标准（每阶段）

1. 新能力在双后端行为符合本设计语义，TCK 新场景全部通过且零回归。
2. wire 纯增量（无新能力的 JSON 字节不变）；OpenAPI 快照纯增量对齐。
3. DSL/验证/文档（中英）同步；HttpQueryGuard 适用性评估落地。
4. 独立 PR、独立可交付；不依赖后续阶段。
