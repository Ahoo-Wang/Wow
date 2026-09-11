# 聚合查询报表场景 Epic：指标级过滤、派生指标与后续阶段

日期：2026-09-12

状态：Phase 1-2 设计已获用户批准；Phase 3-6 为范围提纲（各阶段动手前细化）。

基线：`main` `6cf8be185`（含 PR #3233：DISTINCT_COUNT / STDDEV / VARIANCE / MEDIAN / PERCENTILE 已交付）。前置设计：[2026-09-11 聚合指标函数扩展](2026-09-11-aggregation-metric-functions-design.md)。

## 目标与范围

补齐报表场景的服务端聚合能力。原始需求 9 项，其中：

- **已交付（PR #3233）**：③ 去重计数（`DISTINCT_COUNT`）、⑧ 分位数/标准差（`PERCENTILE`/`MEDIAN`/`STDDEV`/`VARIANCE`）——移出本 Epic。
- **本 Epic 剩余 7 项**：① 指标级过滤（漏斗）、② 派生指标（比率）、⑤ HAVING、⑥ 空桶补齐、⑦ TERMS 截断/缺失桶、⑨ 批量聚合端点、④ 子聚合。

## 分期

| 阶段 | 能力 | PR | 状态 |
|---|---|---|---|
| Phase 1 | 指标级过滤（漏斗/状态对比） | 独立 PR | 设计已批准，待实施计划 |
| Phase 2 | 派生指标（客单价/毛利率/达成率） | 独立 PR | 设计已批准 |
| Phase 3 | HAVING（按聚合值筛选分组） | 独立 PR | 范围提纲 |
| Phase 4 | 分桶体验：空桶补齐 + TERMS 截断/缺失桶 | 独立 PR | 范围提纲 |
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

## Phase 3-6 范围提纲（动手前细化）

- **Phase 3 HAVING**：按聚合值过滤分组。引用语法直接复用 `MetricRef`（可引用派生）。Mongo：`$group` 后追加 `$match`（守卫字段须先落定，必要时三段式 project）。ES composite 无 bucket_selector：metric 排序路径在 Top-N 内存累积中过滤；分组排序路径按页超取直至满足 limit 或耗尽——`limit` 语义定义为**过滤后**行数。选择性与性能权衡文档化。
- **Phase 4 分桶体验**：空桶补齐（`DATE_HISTOGRAM` 专用开关，窗口默认=实际数据首末桶；Mongo `$densify`、ES pager 补零——补零行的指标值遵循各指标空语义）；TERMS 截断（与 metric 排序 Top-N 的关系澄清：默认仍按分组序分页，`size` 语义独立定义）；缺失桶（ES `missing_bucket`、Mongo `$ifNull` 哨兵值归入声明的哨兵键）。
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
