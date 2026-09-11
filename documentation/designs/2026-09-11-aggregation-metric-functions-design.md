# 聚合查询指标函数扩展：DISTINCT_COUNT / STDDEV / VARIANCE / MEDIAN / PERCENTILE

日期：2026-09-11

状态：设计已评审通过，待实施。

基线：`main` 分支 `a2ad95eb7`（Wow `9.0.18`）。前置工作见[查询架构重设计](2026-09-08-query-architecture-redesign.md)与[聚合编译结果复用](2026-09-05-aggregation-compiler-refactoring-design.md)。

## 目标与范围

为 [AggregationQuery](../../wow-api/src/main/kotlin/me/ahoo/wow/api/query/AggregationQuery.kt) 增加常用分析指标函数：

- `DISTINCT_COUNT`：去重计数，表达式不限数值（字符串字段可用）
- `STDDEV` / `VARIANCE`：总体标准差与方差
- `MEDIAN` / `PERCENTILE(p)`：百分位数（`MEDIAN` 为 DSL 语法糖，等价 `PERCENTILE(50)`，不新增 wire 类型）

生产改动范围：

- [AggregationQuery.kt](../../wow-api/src/main/kotlin/me/ahoo/wow/api/query/AggregationQuery.kt)：`AggregationFunction` 新增枚举值；`AggregationMetric` 新增子类型
- [AggregationQueryDsl.kt](../../wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/AggregationQueryDsl.kt)：DSL 方法
- [QuerySchemaValidation.kt](../../wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaValidation.kt)：能力验证
- [MongoAggregationCompiler.kt](../../wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/aggregation/MongoAggregationCompiler.kt) 与 [AbstractMongoQueryBackend.kt](../../wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoQueryBackend.kt)
- [ElasticsearchAggregationPager.kt](../../wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/aggregation/ElasticsearchAggregationPager.kt) 与 [ElasticsearchAggregationCompiler.kt](../../wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/aggregation/ElasticsearchAggregationCompiler.kt)
- TCK 容器基线 [ContainerImages.kt](../../test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/container/ContainerImages.kt)：`mongo:6.0.6` → `mongo:7.0`（最新补丁版）
- 文档 `documentation/docs/{zh,en}/guide/query/`

不在范围内：HAVING、RANGE 分组、嵌套子聚合、FIRST/LAST、ES 聚合调参暴露（`precision_threshold`、tdigest `compression`）、EventStream 聚合 API client（独立缺口）、TS DSL `@ahoo-wow/fetcher-wow`（独立仓库，列跟进项）。

## 设计原则

1. **不为存储端低版本做不支持或降级实现**：直接使用各后端原生算子（如 MongoDB 7.0+ 的 `$percentile`），不做运行时版本探测、能力门控或旧版本兼容路径；版本要求仅在文档中注明。在低于要求版本的服务端上，由服务端原生错误呈现。
2. 纯增量 wire 变更：新增枚举值与多态子类型，不修改现有字段、类型或语义。
3. 跨后端语义由 TCK 锚定；确有算法差异的（t-digest、ES 基数近似）用容差断言，并在文档中说明口径。

## API 契约

### AST 建模

沿用「形态即类型」模式（同 `Count`、`Any` 的先例）：

```kotlin
enum class AggregationFunction {
    SUM, AVG, MIN, MAX,
    STDDEV,   // 新增：总体标准差
    VARIANCE, // 新增：总体方差
}

sealed interface AggregationMetric {
    // Count / Numeric / Any 不变，另新增：
    data class DistinctCount(
        val expression: AggregationExpression, // 任意标量（字符串/数值/布尔）
        override val alias: String,
    ) : AggregationMetric            // JSON type: "DISTINCT_COUNT"

    data class Percentile(
        val expression: AggregationExpression, // 数值
        val percentile: Double,                // 有限 double，0 < p < 100
        override val alias: String,
    ) : AggregationMetric            // JSON type: "PERCENTILE"
}
```

理由：`DISTINCT_COUNT` 接受非数值表达式，放进 `Numeric` 会与其名称、验证规则冲突；`PERCENTILE` 携带参数，可选字段方案会让 `Numeric` 的不变量变弱。子类型使 OpenAPI 多态文档与验证规则都最直接。

别名规则沿用 `requireAggregationAlias`（单段、无 `.`、无保留 `__wow` 前缀）；表达式深度/节点数校验（`MAX_EXPRESSION_DEPTH=8`、`MAX_EXPRESSION_NODES=256`）扩展到两个新子类型的表达式；`MAX_METRICS=64` 不变。

### 语义规范

| 指标 | 参与值 | 空参与值 | 结果类型 | 精度口径 |
|---|---|---|---|---|
| `DISTINCT_COUNT` | 非空标量值；`FIELD` 引用的数组字段按元素逐个参与（与 NUMERIC 指标「多值字段不参与」的规则不同，需在用户文档中显式说明）；`CONSTANT`/`BINARY` 表达式按每条记录单值参与 | `0` | 整数 | ES `cardinality` 阈值内近似精确；Mongo 参与值集合精确 |
| `STDDEV` | 有限 double，同 `AVG` 口径 | `null` | 数值 | 双后端精确（可精确断言） |
| `VARIANCE` | 同上 | `null` | 数值 | 双后端精确 |
| `PERCENTILE(p)` | 同上 | `null` | 数值 | 双后端 t-digest 近似，TCK 用秩区间断言 |

- `STDDEV`/`VARIANCE` 为总体口径（population），与 ES `extended_stats` 默认一致；单参与值结果为 `0`
- `DISTINCT_COUNT` 空集为 `0`（计数语义），不走 `__wow_value_count` 置 null 守卫
- 三个新指标均接受完整 `FIELD/CONSTANT/BINARY` 表达式；`BINARY` 参与值仍按有限 double 口径
- 按新指标别名排序：沿用现有 `effectiveSort` 与 ES Top-N 内存累积路径，无结构变更

## 能力验证（QuerySchemaValidation）

- `STDDEV` / `VARIANCE` / `Percentile`：表达式中的字段需具备 `AGGREGATE_NUMERIC` 能力，同 `SUM`/`AVG`
- `DistinctCount`：字段需具备 `AGGREGATE_TERMS` 或 `AGGREGATE_NUMERIC` 之一（字符串等词法字段走 `AGGREGATE_TERMS`）
- `Percentile.percentile`：有限 double 且 `0 < p < 100`
- masked/protected 字段拒绝，同现有指标规则

## MongoDB 实现

- `STDDEV` → `$stdDevPop(expr)`
- `VARIANCE` → `{ $pow: [{ $stdDevPop: expr }, 2] }`（Mongo 无方差累加器，平方标准差即总体方差）
- `PERCENTILE` → `{ $percentile: { input: expr, p: [p / 100], method: "approximate" } }`（需服务端 7.0+，见设计原则 1）
- `DISTINCT_COUNT` → 基于参与值集合的大小（`$addToSet` 一族），null/missing 不参与；数组字段的元素级参与需要专门的展开参与表达式，具体 BSON 形态在实施计划中确定并与 TCK 收敛
- 算术表达式输入沿用现有 `$let/$cond/$convert` 有限 double 守卫编译
- `__wow_value_count_<alias>` 隐藏计数器沿用：`STDDEV`/`VARIANCE`/`PERCENTILE` 无参与值时输出 `null`；`DISTINCT_COUNT` 不需要该守卫（空集语义为 `0`）
- EventStream 后端继承同一抽象编译器，自动获得

## Elasticsearch 实现

- `STDDEV` → `extended_stats` 聚合取 `std_deviation`；`VARIANCE` → `extended_stats` 取 `variance`。别名唯一，每个指标各自的子聚合即可，不做跨指标复用
- `PERCENTILE` → `percentiles` 聚合，按 `"95.0"` 形式的 key 取值
- `DISTINCT_COUNT` → `cardinality` 聚合（默认 `precision_threshold`，不暴露调节参数）
- 非标量/算术表达式输入复用现有 `RuntimeExpressionCompiler` runtime field 机制
- Pager 不变：composite 聚合分页（PIT + `after_key`）、metric Top-N 内存累积、ungrouped summary、effectiveSort 均按现有逻辑覆盖新指标

## Kotlin DSL

与现有 `sum/avg/min/max` 的重载风格一致（`String` 字段快捷重载 + 完整 `AggregationExpression` 重载，`alias` 恒为最后一个参数）：

```kotlin
fun distinctCount(field: String, alias: String)
fun distinctCount(expression: AggregationExpression, alias: String)
fun stddev(field: String, alias: String)
fun stddev(expression: AggregationExpression, alias: String)
fun variance(field: String, alias: String)
fun variance(expression: AggregationExpression, alias: String)
fun percentile(field: String, p: Double, alias: String)
fun percentile(expression: AggregationExpression, p: Double, alias: String)
fun median(field: String, alias: String)
fun median(expression: AggregationExpression, alias: String) // = percentile(expression, 50.0, alias)
```

使用示例：

```kotlin
aggregation {
    terms("status", "status")
    distinctCount("customerId", "customers")
    stddev("amount", "amt_std")
    variance("amount", "amt_var")
    percentile("amount", 95.0, "amt_p95")
    median("amount", "amt_median") // = percentile 50
    sort {
        "customers".desc()
    }
    limit(10)
}
```

## 验证设计

TCK（`SnapshotQueryBackendSpec` / `EventStreamQueryBackendSpec`）新增场景：

1. 字符串字段 `DISTINCT_COUNT`：含 null 排除与空集 `0` 语义
2. `DISTINCT_COUNT` × Top-N 排序组合
3. `STDDEV`/`VARIANCE` 已知数据集精确断言（数据集选取避免浮点表示歧义；`VARIANCE` 因 Mongo 经平方间接计算，必要时用极小相对容差）
4. `PERCENTILE` 跨后端秩区间断言：按线性插值秩约定（`(n−1)·p`），结果须落在排序后第 `floor((n−1)·p)` 与 `ceil((n−1)·p)` 个参与值之间（0 基下标，含端点），规避两个 t-digest 实现差异
5. 空参与值：`STDDEV`/`VARIANCE`/`PERCENTILE` → `null`；`DISTINCT_COUNT` → `0`
6. 单参与值组 `STDDEV` = `0`
7. 嵌套 `elements` 作用域内的新指标（作用域规则同现有指标）
8. 算术表达式输入（如 `percentile(field("amount") + constant(0), 50)`）
9. masked 字段拒绝与非法 `percentile` 参数（validation 层单测）

单元测试：wow-api AST 校验（别名/参数/表达式深度）；wow-query DSL 构造与验证；wow-mongo / wow-elasticsearch 编译器单测断言生成的 pipeline / agg JSON。集成测试经 Testcontainers 跑 TCK（容器基线 `mongo:7.0`）。

回归命令：`:wow-api:test`、`:wow-query:check`、`:wow-mongo:test`、`:wow-elasticsearch:test`、`:wow-mongo:integrationTest`、`:wow-elasticsearch:integrationTest`、`:wow-tck` 相关模块与 `allLocalTest` / `allContractTest`。

## 文档更新

- `aggregation-query.md`（中英）：指标函数表新增五行；近似性与精度说明（ES `cardinality` 阈值内近似精确、双后端 t-digest、`STDDEV`/`VARIANCE` 精确）；**版本要求：`PERCENTILE` 需 MongoDB 7.0+，其余函数无新增版本要求**
- `snapshot-aggregation.md` / `event-stream-aggregation.md`：各新增 1 个典型场景（去重客户数、P95 金额）
- OpenAPI：schema 由 wow-api 注解驱动自动生成，`wow-openapi` 无需手工改动；apiclient 查询体为透传 JSON，无需变更

## 兼容性

- wire：纯新增（新枚举值 + 新多态子类型），旧客户端与旧查询完全不受影响
- source：DSL 纯新增方法，无签名变更
- 运行时：唯一硬约束为 `PERCENTILE` 需 MongoDB 服务端 7.0+；按设计原则 1 不做版本探测或降级，仅文档注明
- TCK 容器基线升级到 `mongo:7.0` 影响所有 Mongo 集成测试环境，属预期变更

## 完成标准

1. 五个新函数在 MongoDB 与 Elasticsearch 两后端行为符合本文语义规范，TCK 新场景全部通过
2. 精度断言策略落地：`STDDEV`/`VARIANCE` 精确（或极小相对容差）、`PERCENTILE` 秩区间、`DISTINCT_COUNT` 小数据集精确
3. 能力验证、DSL、OpenAPI schema、中英文档同步完成，版本要求注明
4. 现有聚合查询行为零回归（现有 TCK 场景不变通过）
