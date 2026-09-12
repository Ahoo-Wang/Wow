# Phase 3 实施计划：HAVING（按聚合值筛选分组）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 `AggregationQuery` 增加可选 `having` 表达式——按聚合值（含派生）筛选分组，SQL HAVING 口径（null 判假、limit=过滤后行数），MongoDB `$match` 后置与 Elasticsearch 客户端求值双实现，含 DSL、构造期校验、HttpQueryGuard 值计数、TCK 契约与中英文档。

**Architecture:** 纯增量可选字段（`having: HavingExpression? = null`，**末位**参数——位置构造兼容，`NON_NULL` 序列化省略）。新 sealed 树 `HavingExpression`（Condition/Between/In/IsNull/And/Or）不复用 `FilterExpression`。Mongo 在派生 `$project` 链后、`$sort` 前追加单个 `$match`（比较条件须并 `$ne: null`——BSON 全序 `null < 数值` 会 otherwise 命中 null）。ES 无 bucket_selector：行级客户端求值；分组排序路径按页超取直至存活行 ≥ limit（`pageSize` 封顶与 `shouldStop` 空页短路对 having 放开）；metric 排序路径在进 Top-N 前滤行。

**Tech Stack:** Kotlin 2.x、JUnit 5、fluent-assert、Jackson 3、MongoDB Java Driver、elasticsearch-java 9.x、Testcontainers（mongo:7.0 / ES 9.x）。

**Spec:** [2026-09-12-aggregation-reporting-epic-design.md](2026-09-12-aggregation-reporting-epic-design.md)「Phase 3：HAVING」节（AST、语义、双后端、TCK 场景以该文档为准）。

## Global Constraints

- 基线：`main` `0fca5744b`（含 #3240 派生指标）。
- wire 兼容纯增量：`having` 为 `AggregationQuery` **末位**构造参数（既有 6 参位置构造不变）、默认 `null`、`@get:JsonInclude(NON_NULL)` 省略——无 having 的 JSON 字节不变。
- 语义锚点（SQL HAVING 口径）：引用值为 null（指标空语义/派生 null 传播）→ 一切比较不成立；`IsNull` 恰好捕获；`limit` = **过滤后**行数；`sort` 作用于过滤后行序；数值统一 IEEE double 比较。
- Mongo null 陷阱：BSON 全序 `null < 数值`——除 `IsNull` 外每个比较条件必须并 `{alias: {$ne: null}}`。
- ES 两处既有机制调整（仅 having 存在时）：`pageSize` 的 `min(pageCapacity, limit - fetched)` 封顶放开为恒 `pageCapacity`；`shouldStop` 的 `rows.isEmpty()` 短路放开（整页滤空 ≠ 桶耗尽，仅 `afterKey` 为空可停）；`fetched` 改计存活行数。无 having 行为逐字节不变。
- 构造期校验（`AggregationQuery.init`）：having 须有 `groupBy`；`metric` 引用须为已声明指标别名（禁分组别名、禁 `ANY` 指标、禁 `__wow` 前缀）；不限声明序（having 在全部指标后求值）；值有限；`Between` `lower <= upper`；`In` 非空；`And/Or` operands 非空；深度 ≤ `MAX_EXPRESSION_DEPTH`。
- HttpQueryGuard：having 并入既有口径——节点数 ≤ `maxFilterNodes`、逐节点值数 ≤ `maxFilterValues`（In 计 N、Between 计 2、Condition 计 1、IsNull 计 0）；比较不属昂贵算子。
- 严格 TDD：先失败测试、亲见 RED、再实现、GREEN；fluent-assert `.assert()`。
- 不为存储端低版本降级；本阶段无新存储版本要求（`$match`/分页机制均远早于基线）。
- 提交 conventional commits（scope：`api`/`query`/`mongo`/`elasticsearch`/`webflux`/`tck`/`openapi`/`docs`）。
- wow-schema 前置事实：`HavingExpression` 经 And/Or 自引用是递归多态——OpenAPI 任务须把 `HavingExpression` 加入 `FilterExpressionDefinitionProvider.skipSubtypeLookup`（#3240 的 `DerivedExpression` 先例），否则 victools 无限展开栈溢出。

---

### Task 1: wow-api — HavingExpression 与构造期校验

**Files:**
- Modify: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/AggregationQuery.kt`
- Test: `wow-api/src/test/kotlin/me/ahoo/wow/api/query/AggregationQueryTest.kt`

**Interfaces:**
- Consumes: 既有 `MAX_EXPRESSION_DEPTH`、`requireAggregationAlias` 风格、`DerivedExpression`（同文件既有递归多态先例）。
- Produces（后续任务依赖）：

```kotlin
// AggregationQuery 追加末位参数：
@get:JsonInclude(JsonInclude.Include.NON_NULL)
val having: HavingExpression? = null,

@get:Schema(...) oneOf + discriminator 同 DerivedExpression 形态
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
    data class Condition(val metric: String, val operator: ComparisonOperator, val value: Double) : HavingExpression
    data class Between(val metric: String, val lower: Double, val upper: Double) : HavingExpression
    data class In(val metric: String, val values: List<Double>) : HavingExpression
    data class IsNull(val metric: String, val negated: Boolean = false) : HavingExpression
    data class And(val operands: List<HavingExpression>) : HavingExpression
    data class Or(val operands: List<HavingExpression>) : HavingExpression
}

enum class ComparisonOperator { EQ, NE, GT, GTE, LT, LTE }
```

- 构造期校验（init 内、既有校验之后，新私有 `requireValidHaving(having, groupBy, metrics)`）：
  - `having != null` → `require(groupBy.isNotEmpty()) { "having requires at least one groupBy." }`
  - 指标别名集 `metricAliases = metrics.map { alias }`、`anyAliases = metrics.filterIsInstance<AggregationMetric.Any>().map { alias }.toSet()`。
  - 每个叶子（Condition/Between/In/IsNull）：`require(metric in metricAliases)`——消息 `"having condition [$metric] must reference a declared metric alias."`（分组别名不在集合中自动覆盖）；`require(metric !in anyAliases)`——消息 `"having condition [$metric] cannot reference ANY metric."`。
  - 数值：全有限（`Condition.value`/`Between.lower/upper`/`In.values` 各自 `isFinite`）；`Between` `lower <= upper`；`In.values` 非空。
  - `And/Or`：`operands.isNotEmpty()`；子节点入队，深度 ≤ `MAX_EXPRESSION_DEPTH`（模式同 `requireValidDerivedMetrics` 的队列走查）。

- [ ] **Step 1: 写失败测试**（追加到 AggregationQueryTest，按文件既有 idiom 适配）

```kotlin
@Test
fun `having should round trip and omit when absent`() {
    val json = """
        {
          "groupBy": [{"type": "TERMS", "field": "status", "alias": "status"}],
          "metrics": [{"type": "COUNT", "alias": "paid"}],
          "having": {"type": "AND", "operands": [
            {"type": "CONDITION", "metric": "paid", "operator": "GT", "value": 10.0},
            {"type": "IS_NULL", "metric": "paid", "negated": true}
          ]}
        }
    """.trimIndent()
    val query = configuredMapper.readValue(json, AggregationQuery::class.java)
    val having = requireNotNull(query.having) as HavingExpression.And
    (having.operands.single { it is HavingExpression.Condition } as HavingExpression.Condition).let {
        it.metric.assert().isEqualTo("paid")
        it.operator.assert().isEqualTo(ComparisonOperator.GT)
        it.value.assert().isEqualTo(10.0)
    }
    val wire = configuredMapper.writeValueAsString(query)
    wire.assert().contains("\"type\":\"AND\"").contains("\"operator\":\"GT\"")
    // 无 having 的查询不出现该字段
    val plain = configuredMapper.writeValueAsString(
        AggregationQuery(groupBy = listOf(AggregationGroup.Terms(QueryField("status"), "status")), metrics = listOf(AggregationMetric.Count("paid"))),
    )
    plain.assert().doesNotContain("\"having\"")
}

@Test
fun `having references and bounds are validated at construction`() {
    fun query(having: HavingExpression?, groupBy: List<AggregationGroup> = listOf(AggregationGroup.Terms(QueryField("status"), "status"))) =
        AggregationQuery(
            groupBy = groupBy,
            metrics = listOf(AggregationMetric.Count("paid")),
            having = having,
        )
    val gt = HavingExpression.Condition("paid", ComparisonOperator.GT, 1.0)
    // 合法：引用指标（含派生，不限声明序）
    query(HavingExpression.And(listOf(gt, HavingExpression.IsNull("paid"))))
    query(HavingExpression.Condition("paid", ComparisonOperator.GTE, 0.0))
    // 无 groupBy 拒绝
    assertThrows<IllegalArgumentException> { query(gt, groupBy = emptyList()) }.message.assert().contains("groupBy")
    // 未知/分组别名拒绝
    assertThrows<IllegalArgumentException> { query(HavingExpression.Condition("unknown", ComparisonOperator.GT, 1.0)) }.message.assert().contains("declared metric alias")
    assertThrows<IllegalArgumentException> { query(HavingExpression.Condition("status", ComparisonOperator.GT, 1.0)) }.message.assert().contains("declared metric alias")
    // ANY 指标拒绝
    assertThrows<IllegalArgumentException> {
        AggregationQuery(
            groupBy = listOf(AggregationGroup.Terms(QueryField("status"), "status")),
            metrics = listOf(AggregationMetric.Any(QueryField("state"), "sample"), AggregationMetric.Count("paid")),
            having = HavingExpression.Condition("sample", ComparisonOperator.GT, 1.0),
        )
    }.message.assert().contains("ANY")
    // 数值边界
    assertThrows<IllegalArgumentException> { query(HavingExpression.Condition("paid", ComparisonOperator.GT, Double.POSITIVE_INFINITY)) }
    assertThrows<IllegalArgumentException> { query(HavingExpression.Between("paid", 5.0, 1.0)) }
    assertThrows<IllegalArgumentException> { query(HavingExpression.In("paid", emptyList())) }
    assertThrows<IllegalArgumentException> { query(HavingExpression.And(emptyList())) }
}

@Test
fun `having may reference derived metrics regardless of declaration order`() {
    val query = AggregationQuery(
        groupBy = listOf(AggregationGroup.Terms(QueryField("status"), "status")),
        metrics = listOf(
            AggregationMetric.Count("total"),
            AggregationMetric.Derived("half", DerivedExpression.Binary(AggregationExpressionOperator.DIVIDE, DerivedExpression.MetricRef("total"), DerivedExpression.Constant(2.0))),
        ),
        having = HavingExpression.Condition("half", ComparisonOperator.GTE, 0.5), // 引用派生 ✓
    )
    (query.having as HavingExpression.Condition).metric.assert().isEqualTo("half")
}
```

- [ ] **Step 2: RED**：`./gradlew :wow-api:test --tests "me.ahoo.wow.api.query.AggregationQueryTest"`——编译失败：`HavingExpression` 不存在（预期点）。

- [ ] **Step 3: 实现**（按 Interfaces；`HavingExpression`/`ComparisonOperator` 放在 `DerivedExpression` 之后；`requireValidHaving` 放在 `requireValidDerivedMetrics` 之后同文件私有）。

- [ ] **Step 4: GREEN**：`./gradlew :wow-api:test` 全绿（下游模块因新增可选字段不破坏编译——`having` 是新增独立类型，无 sealed `when` 冲击；本任务后其余模块仍可编译，执行者顺跑 `:wow-query:compileKotlin` 确认无涟漪）。

- [ ] **Step 5: 提交** `feat(api): add aggregation having expression`

---

### Task 2: wow-query DSL + wow-webflux Guard 值计数

**Files:**
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/AggregationQueryDsl.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/query/HttpQueryGuard.kt`（aggregation 分支）
- Test: `wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/AggregationQueryDslTest.kt`、`wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/query/HttpQueryGuardTest.kt`

**Interfaces:**
- Consumes: Task 1 `HavingExpression`/`ComparisonOperator`。
- Produces:

```kotlin
// AggregationQueryDsl 内：
private var having: HavingExpression? = null

fun having(init: HavingDsl.() -> HavingExpression) {
    having = HavingDsl().init()
}

@QueryDslMarker
class HavingDsl {
    infix fun String.eq(value: Double): HavingExpression = condition(ComparisonOperator.EQ, value)
    infix fun String.ne(value: Double): HavingExpression = condition(ComparisonOperator.NE, value)
    infix fun String.gt(value: Double): HavingExpression = condition(ComparisonOperator.GT, value)
    infix fun String.gte(value: Double): HavingExpression = condition(ComparisonOperator.GTE, value)
    infix fun String.lt(value: Double): HavingExpression = condition(ComparisonOperator.LT, value)
    infix fun String.lte(value: Double): HavingExpression = condition(ComparisonOperator.LTE, value)
    fun String.between(lower: Double, upper: Double): HavingExpression = HavingExpression.Between(this, lower, upper)
    fun String.isIn(values: List<Double>): HavingExpression = HavingExpression.In(this, values)
    fun String.isNull(): HavingExpression = HavingExpression.IsNull(this)
    fun String.isNotNull(): HavingExpression = HavingExpression.IsNull(this, negated = true)

    infix fun HavingExpression.and(other: HavingExpression): HavingExpression = HavingExpression.And(listOf(this, other))
    infix fun HavingExpression.or(other: HavingExpression): HavingExpression = HavingExpression.Or(listOf(this, other))

    private fun String.condition(operator: ComparisonOperator, value: Double) = HavingExpression.Condition(this, operator, value)
}
// buildQuery 处把 having 传入 AggregationQuery 末位参数（按该文件既有 build 收口写法）
```

（独立 `@QueryDslMarker` 上下文，`eq(Double)` 与 FilterDsl 的 `eq(Any?)` 不同类不同签名，无冲突。）

- HttpQueryGuard（aggregation 分支内、既有校验之后）：

```kotlin
query.having?.let { validateHaving(it) }

private fun validateHaving(having: HavingExpression) {
    val pending = ArrayDeque<HavingExpression>()
    pending.add(having)
    var nodes = 0
    while (pending.isNotEmpty()) {
        val current = pending.removeLast()
        nodes++
        require(maxFilterNodes == 0 || nodes <= maxFilterNodes) {
            "HTTP having nodes[$nodes] must not exceed $maxFilterNodes."
        }
        val valueCount = when (current) {
            is HavingExpression.Condition -> 1
            is HavingExpression.Between -> 2
            is HavingExpression.In -> current.values.size
            is HavingExpression.IsNull -> 0
            is HavingExpression.And -> current.operands.size.also { pending.addAll(current.operands); 0 }
            is HavingExpression.Or -> current.operands.size.also { pending.addAll(current.operands); 0 }
        }
        if (valueCount > 0) {
            require(maxFilterValues == 0 || valueCount <= maxFilterValues) {
                "HTTP having values[$valueCount] must not exceed $maxFilterValues."
            }
        }
    }
}
```

（口径对齐既有 `validateFilters`：逐节点值数 ≤ 上限、节点数 ≤ 上限；无昂贵算子判定。`And/Or` 分支写法按 Kotlin 语法展开为显式 `also` 之外的常规语句，避免歧义。）

- [ ] **Step 1: 写失败测试**

```kotlin
// AggregationQueryDslTest
@Test
fun `aggregation DSL should build having expressions`() {
    val query = aggregation {
        terms("state.status", "status")
        count("paid")
        sum("amount", "paidAmount") { "state.status" eq "PAID" }
        derived("attainment") { ref("paidAmount") / constant(6000.0) }
        having {
            ("attainment" gte 0.8) and ("paid" gt 10)
        }
        sort { "attainment".desc() }
    }
    val having = requireNotNull(query.having) as HavingExpression.And
    (having.operands[0] as HavingExpression.Condition).let {
        it.metric.assert().isEqualTo("attainment")
        it.operator.assert().isEqualTo(ComparisonOperator.GTE)
    }
    (having.operands[1] as HavingExpression.Condition).value.assert().isEqualTo(10.0)
}

@Test
fun `aggregation DSL having supports full operator set`() {
    val query = aggregation {
        terms("state.status", "status")
        count("c")
        having {
            ("c" between 1.0..2.0.let { 1.0 to 2.0 }.let { it.first } ?: 1.0) // 以 between(1.0, 2.0) 实际写法替换
        }
    }
    // 展开为：having { "c".between(1.0, 2.0) or ("c".isIn(listOf(3.0, 4.0))) or "c".isNull() or "c".isNotNull() }
}
```

（第二个测试写成四类叶子的组合断言：`"c".between(1.0, 2.0)`、`"c".isIn(listOf(3.0, 4.0))`、`"c".isNull()`、`"c".isNotNull()` 经 `or` 链接——执行者按 DSL 实际接收者写法展开，勿照抄上面的草稿伪码。）

```kotlin
// HttpQueryGuardTest（沿用既有 guard 测试助手实况）
@Test
fun `having values count toward filter value limits`() {
    val guard = guard(maxFilterValues = 2)
    assertThrows<IllegalArgumentException> {
        guard.validateAggregation(
            aggregation {
                terms("state.status", "status")
                count("c")
                having { "c".isIn(listOf(1.0, 2.0, 3.0)) } // 3 > 2
            },
        )
    }
}

@Test
fun `having nodes count toward filter node limits`() {
    val guard = guard(maxFilterNodes = 2)
    assertThrows<IllegalArgumentException> {
        guard.validateAggregation(
            aggregation {
                terms("state.status", "status")
                count("c")
                having { ("c" gt 1.0) and (("c" lt 2.0) and ("c" ne 3.0)) } // 3 条件节点 > 2
            },
        )
    }
}
```

- [ ] **Step 2: RED**：`./gradlew :wow-query:test --tests "*AggregationQueryDsl*"` 与 `:wow-webflux:test --tests "*HttpQueryGuard*"`——编译失败（`having` 不存在）。

- [ ] **Step 3: 实现**（按 Interfaces 两处）。

- [ ] **Step 4: GREEN**：`./gradlew :wow-query:test :wow-webflux:test`

- [ ] **Step 5: 提交** `feat(query): add having DSL and HTTP guard limits`

---

### Task 3: wow-mongo — 派生链后 $match

**Files:**
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/aggregation/MongoAggregationCompiler.kt`（compile() 阶段发射 + `toHavingDocument`）
- Test: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompilerTest.kt`

**Interfaces:**
- Consumes: Task 1 AST；既有派生阶段发射（`if (query.metrics.any { is Derived })` 块）。
- Produces:

```kotlin
// compile()：派生阶段之后、sort 之前：
query.having?.let { add(Aggregates.match(it.toHavingDocument())) }

// 类内私有：
private fun HavingExpression.toHavingDocument(): Bson = when (this) {
    is HavingExpression.And -> Filters.and(operands.map { it.toHavingDocument() })
    is HavingExpression.Or -> Filters.or(operands.map { it.toHavingDocument() })
    is HavingExpression.IsNull -> if (negated) {
        Filters.ne(metric, null)
    } else {
        Document(metric, null)
    }
    is HavingExpression.Condition -> Document(
        metric,
        Document(operator.matchOperator, value).append("\$ne", null),
    )
    is HavingExpression.Between -> Document(
        metric,
        Document("\$gte", lower).append("\$lte", upper).append("\$ne", null),
    )
    is HavingExpression.In -> Document(
        metric,
        Document("\$in", values).append("\$ne", null),
    )
}

private val ComparisonOperator.matchOperator: String
    get() = when (this) {
        ComparisonOperator.EQ -> "\$eq"
        ComparisonOperator.NE -> "\$ne"
        ComparisonOperator.GT -> "\$gt"
        ComparisonOperator.GTE -> "\$gte"
        ComparisonOperator.LT -> "\$lt"
        ComparisonOperator.LTE -> "\$lte"
    }
```

（KDoc：BSON 全序 `null < 数值`，`{alias: {$gt: v}}` 会命中 null——与 null 判假口径冲突，故除 `IsNull` 外一律并 `$ne: null`。`Filters.ne(metric, null)` 产出 `{$ne: null}` 文档形态；`Document(metric, null)` 为 IS NULL——投影后别名恒存在，null 即语义 null。）

- [ ] **Step 1: 写失败测试**（结构化 BsonDocument 断言，沿用本类风格；分组字段与 schema 组合以既有分组测试实况为准——参照 Phase 2 派生测试中 `terms` 组 + `statusFilterSchema`/`schema()` 的可行组合，若 `state.productId` 在默认 `schema()` 无分组能力则换用该参照组合）

```kotlin
@Test
fun `having compiles into a post-derivation match with null guards`() {
    val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
        aggregation {
            terms("state.productName", "product")
            count("lines")
            sum("amount", "total")
            derived("avgAmount") { ref("total") / ref("lines") }
            having { ("avgAmount" gte 10.0) and ("total" gt 0.0) }
            sort { "avgAmount".desc() }
        },
        schema(),
    ).map { it.toBsonDocument() }

    val matchIndex = pipeline.indexOfLast { it.containsKey("\$match") }
    val projectIndex = pipeline.indexOfLast { it.containsKey("\$project") }
    val sortIndex = pipeline.indexOfFirst { it.containsKey("\$sort") }
    projectIndex.assert().isLessThan(matchIndex) // 派生 $project 链之后
    matchIndex.assert().isLessThan(sortIndex)    // $sort 之前

    val having = pipeline[matchIndex].getDocument("\$match")
    val and = having.getDocument("\$and")
    and.getArray().size().assert().isEqualTo(2)
    and.getArray()[0].asDocument().getDocument("avgAmount").let {
        it.getDouble("\$gte").assert().isEqualTo(10.0)
        it.containsKey("\$ne").assert().isTrue() // null 守卫
    }
    and.getArray()[1].asDocument().getDocument("total").getDouble("\$gt").assert().isEqualTo(0.0)
}

@Test
fun `having null checks and in-lists compile without numeric guards`() {
    val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
        aggregation {
            terms("state.productName", "product")
            count("lines")
            sum("amount", "total")
            having { ("total" isNull()) or ("total".isIn(listOf(40.0, 50.0))) }
        },
        schema(),
    ).map { it.toBsonDocument() }
    val or = pipeline.last { it.containsKey("\$match") }.getDocument("\$match").getDocument("\$or")
    or.getArray()[0].asDocument().get("total").assert().isEqualTo(null) // IS NULL：无 $ne 守卫
    val inDoc = or.getArray()[1].asDocument().getDocument("total")
    (inDoc.getArray("\$in") as List<*>).assert().hasSize(2)
    inDoc.containsKey("\$ne").assert().isTrue()
}

@Test
fun `queries without having keep their pipeline shape`() {
    val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
        aggregation { terms("state.productName", "product"); count("lines") },
        schema(),
    )
    pipeline.filter { it.toBsonDocument().containsKey("\$match") }.assert().hasSize(1) // 仅根过滤 $match
}
```

- [ ] **Step 2: RED**：`./gradlew :wow-mongo:test --tests "*MongoAggregationCompiler*"`——编译失败（`toHavingDocument` 不存在 / having DSL 不可用）。

- [ ] **Step 3: 实现**（按 Interfaces；无 having 管线逐阶段不变——`?.let` 门控）。

- [ ] **Step 4: GREEN**：`./gradlew :wow-mongo:test`

- [ ] **Step 5: 提交** `feat(mongo): filter grouped aggregation rows with having matches`

---

### Task 4: wow-elasticsearch — 客户端求值与按页超取

**Files:**
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/aggregation/ElasticsearchAggregationCompiler.kt`（计划类型透传 `having`）
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/aggregation/ElasticsearchAggregationPager.kt`（行谓词 + searchPage 滤行 + pageSize/shouldStop 调整）
- Test: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationCompilerTest.kt`、`wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationPagerTest.kt`

**Interfaces:**
- Consumes: Task 1 AST；既有 `AggregationPage(rows, afterKey, fetched)`、`shouldStop`、`pageSize`、`BoundedTopRows`。
- Produces:

```kotlin
// 计划类型追加字段（构造末位，默认 null）：
internal data class ElasticsearchAggregationPlan(
    ...
    val having: HavingExpression? = null,
)
// compile() 返回处：having = query.having

// Pager——行级求值（null 判假口径）：
private fun ObjectNode.matchesHaving(having: HavingExpression): Boolean = when (having) {
    is HavingExpression.And -> having.operands.all { matchesHaving(it) }
    is HavingExpression.Or -> having.operands.any { matchesHaving(it) }
    is HavingExpression.IsNull -> {
        val value = get(having.metric)
        if (having.negated) !(value == null || value.isNull) else value == null || value.isNull
    }
    is HavingExpression.Condition -> {
        val value = get(having.metric)
        value != null && !value.isNull && compare(value.asDouble(), having.operator, having.value)
    }
    is HavingExpression.Between -> {
        val value = get(having.metric)
        value != null && !value.isNull && value.asDouble() >= having.lower && value.asDouble() <= having.upper
    }
    is HavingExpression.In -> {
        val value = get(having.metric)
        value != null && !value.isNull && having.values.any { it == value.asDouble() }
    }
}

private fun compare(left: Double, operator: ComparisonOperator, right: Double): Boolean = when (operator) {
    ComparisonOperator.EQ -> left == right
    ComparisonOperator.NE -> left != right
    ComparisonOperator.GT -> left > right
    ComparisonOperator.GTE -> left >= right
    ComparisonOperator.LT -> left < right
    ComparisonOperator.LTE -> left <= right
}

// searchPage：行生产处滤行（rows 即存活行）：
val rows = composite.buckets().array()
    .asSequence()
    .map { it.toRow(plan) }
    .filter { row -> plan.having == null || row.matchesHaving(plan.having) }
    .toList()
// fetched = fetched + rows.size（自然改计存活行）

// pageSize：having 存在时放开 limit 封顶：
return if (metricSorted || having != null) pageCapacity else min(pageCapacity, limit - fetched)

// shouldStop：having 存在时移除空页短路：
fun shouldStop(plan: ElasticsearchAggregationPlan): Boolean {
    if (afterKey.isEmpty()) return true
    if (plan.having == null && rows.isEmpty()) return true
    return !plan.metricSorted && fetched >= plan.limit
}
```

（metric 排序路径无需专门改动——`searchPage` 滤行后 `BoundedTopRows` 自然只收存活行，分页本就全量扫描。无 having 时三处条件短路为既有行为。）

- [ ] **Step 1: 写失败测试**

```kotlin
// ElasticsearchAggregationCompilerTest
@Test
fun `plan should carry the having expression`() {
    val plan = compiler.compile(
        aggregation {
            terms("state.product", "product")
            count("c")
            having { "c" gte 2.0 }
        },
        schema,
    )
    (plan.having as HavingExpression.Condition).value.assert().isEqualTo(2.0)
}
```

```kotlin
// ElasticsearchAggregationPagerTest——三个场景（mockk 既有助手：stubPointInTime/groupResponse/metricBucket/compileAggregation）
@Test
fun `group sort with having should keep paging past fully filtered pages`() {
    val requests = mutableListOf<SearchRequest>()
    stubPointInTime()
    // 第一页 2 桶全被滤除、第二页 1 桶存活（limit=1 已满足即停）
    every { client.search(capture(requests), Map::class.java) } returnsMany listOf(
        groupResponse("pit-2", listOf(metricBucket("a", 1.0), metricBucket("b", 1.0)), "b"),
        groupResponse("pit-3", listOf(metricBucket("c", 9.0))),
    )
    val plan = compileAggregation(
        aggregation {
            terms("state.product", "product")
            sum("state.total", "total")
            having { "total" gte 5.0 }
            sort { "product".asc() } // 分组排序路径（非 metric 排序）
            limit(1)
        },
    )
    pager(batchSize = 2).execute(plan)
        .map { it.path("product").textValue() }
        .test()
        .assertNext { it.assert().isEqualTo("c") }
        .verifyComplete()
    requests.assert().hasSize(2) // 全滤空的第一页未终止分页
}

@Test
fun `group sort with having should request uncapped page sizes`() {
    val requests = mutableListOf<SearchRequest>()
    stubPointInTime()
    every { client.search(capture(requests), Map::class.java) } returnsMany listOf(
        groupResponse("pit-2", listOf(metricBucket("a", 9.0)), "a"),
        groupResponse("pit-3", emptyList()),
    )
    val plan = compileAggregation(
        aggregation {
            terms("state.product", "product")
            sum("state.total", "total")
            having { "total" gte 5.0 }
            sort { "product".asc() }
            limit(1)
        },
    )
    pager(batchSize = 10).execute(plan).test().verifyComplete()
    // having 存在：pageSize 恒为 pageCapacity（10/bucketWidth），不受 limit-fetched 封顶
    requests[0].aggregations().values.single().composite().sources().size() // 以实际断言形态核对 size
    requests[0].aggregations().values.single().composite().size().assert().isEqualTo(10)
}

@Test
fun `metric sort with having should filter rows before top N`() {
    val requests = mutableListOf<SearchRequest>()
    stubPointInTime()
    every { client.search(capture(requests), Map::class.java) } returnsMany listOf(
        groupResponse("pit-2", listOf(metricBucket("a", 1.0), metricBucket("b", 9.0), metricBucket("c", 5.0)), "c"),
        groupResponse("pit-3", emptyList()),
    )
    val plan = compileAggregation(
        aggregation {
            terms("state.product", "product")
            sum("state.total", "total")
            having { "total" gte 5.0 }
            sort { "total".desc() } // metric 排序路径
            limit(2)
        },
    )
    pager(batchSize = 3).execute(plan)
        .map { it.path("product").textValue() }
        .test()
        .expectNext("b", "c") // a 被滤除，Top-N 只在存活行中取
        .verifyComplete()
}
```

（断言细节按既有测试实况适配：`metricBucket` 助手的产品名/total 字段名、`composite().size()` 访问器名。）

- [ ] **Step 2: RED**：`./gradlew :wow-elasticsearch:test --tests "*Aggregation*"`——编译失败（`plan.having` 不存在）。

- [ ] **Step 3: 实现**（按 Interfaces；`matchesHaving`/`compare` 为 Pager 私有）。

- [ ] **Step 4: GREEN**：`./gradlew :wow-elasticsearch:test`

- [ ] **Step 5: 提交** `feat(elasticsearch): evaluate having client-side with page over-fetching`

---

### Task 5: TCK — 跨后端契约场景

**Files:**
- Modify: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/SnapshotQueryBackendSpec.kt`（派生场景块之后追加）
- Modify: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/EventStreamQueryBackendSpec.kt`（一个代表场景）

**fixture 事实**（展开 `state.orders`+`lines`、terms `productId` 分组）：

| 分组 | 行数（count） | sum(amount) |
|---|---|---|
| alpha | 2 | 40.0 |
| beta | 2 | 40.0 |
| delta | 1 | 50.0 |
| gamma | 1 | null |

派生 `share = total / constant(50.0)`：alpha 0.8、beta 0.8、delta 1.0、gamma null。

- [ ] **Step 1: Snapshot 场景（设计 ①-⑦）**

```kotlin
@Test
fun `aggregation having should filter groups by count thresholds`() { // ①
    saveAggregationStates(*aggregationStates().toTypedArray())
    aggregation {
        filter { deletion(DeletionState.ACTIVE) }
        expand("state.orders")
        expand("lines")
        terms("productId", "product")
        count("lines")
        having { "lines" gte 2.0 }
    }.query(queryBackendBinding)
        .collectList()
        .test()
        .assertNext { rows -> rows.map { it.path("product").textValue() }.assert().containsExactly("alpha", "beta") }
        .verifyComplete()
}

@Test
fun `aggregation having should filter groups by derived thresholds`() { // ②
    saveAggregationStates(*aggregationStates().toTypedArray())
    aggregation {
        filter { deletion(DeletionState.ACTIVE) }
        expand("state.orders")
        expand("lines")
        terms("productId", "product")
        sum("amount", "total")
        derived("share") { ref("total") / constant(50.0) }
        having { "share" gte 0.8 } // 0.8/0.8/1.0 命中，gamma null 判假；无显式 sort → 分组别名升序
    }.query(queryBackendBinding)
        .collectList()
        .test()
        .assertNext { rows -> rows.map { it.path("product").textValue() }.assert().containsExactly("alpha", "beta", "delta") }
        .verifyComplete()
}

@Test
fun `aggregation having should treat null as failing comparisons`() { // ③
    saveAggregationStates(*aggregationStates().toTypedArray())
    aggregation {
        filter { deletion(DeletionState.ACTIVE) }
        expand("state.orders")
        expand("lines")
        terms("productId", "product")
        sum("amount", "total")
        having { "total" isNotNull() }
    }.query(queryBackendBinding)
        .collectList()
        .test()
        .assertNext { rows -> rows.map { it.path("product").textValue() }.assert().containsExactly("alpha", "beta", "delta") }
        .verifyComplete()
}

@Test
fun `aggregation having should apply limit after filtering`() { // ④
    saveAggregationStates(*aggregationStates().toTypedArray())
    aggregation {
        filter { deletion(DeletionState.ACTIVE) }
        expand("state.orders")
        expand("lines")
        terms("productId", "product")
        sum("amount", "total")
        having { "total" gte 40.0 } // alpha/beta/delta 存活
        sort { "total".desc() }
        limit(2) // 过滤后行数：delta(50) > alpha(40)=beta(40)，tie 按分组别名序 alpha<beta
    }.query(queryBackendBinding)
        .collectList()
        .test()
        .assertNext { rows -> rows.map { it.path("product").textValue() }.assert().containsExactly("delta", "alpha") }
        .verifyComplete()
}

@Test
fun `aggregation having matching nothing should return an empty flux`() { // ⑤
    saveAggregationStates(*aggregationStates().toTypedArray())
    aggregation {
        filter { deletion(DeletionState.ACTIVE) }
        expand("state.orders")
        expand("lines")
        terms("productId", "product")
        count("lines")
        having { "lines" gt 100.0 }
    }.query(queryBackendBinding)
        .collectList()
        .test()
        .assertNext { it.assert().isEmpty() }
        .verifyComplete()
}

@Test
fun `aggregation having should compose with and and or`() { // ⑥
    saveAggregationStates(*aggregationStates().toTypedArray())
    aggregation {
        filter { deletion(DeletionState.ACTIVE) }
        expand("state.orders")
        expand("lines")
        terms("productId", "product")
        count("lines")
        sum("amount", "total")
        having { ("total" gte 40.0) and (("lines" gt 1.0) or ("total" gte 50.0)) }
        // alpha: 40>=40 ✓ (2>1 ✓) → 命中；beta 同；delta: 50>=50 ✓ → 命中；gamma: null 判假 → 排除
    }.query(queryBackendBinding)
        .collectList()
        .test()
        .assertNext { rows -> rows.map { it.path("product").textValue() }.assert().containsExactly("alpha", "beta", "delta") }
        .verifyComplete()
}

@Test
fun `aggregation having should support between and in`() { // ⑦
    saveAggregationStates(*aggregationStates().toTypedArray())
    aggregation {
        filter { deletion(DeletionState.ACTIVE) }
        expand("state.orders")
        expand("lines")
        terms("productId", "product")
        sum("amount", "total")
        having { ("total".between(40.0, 45.0)) or ("total".isIn(listOf(50.0))) }
        // between: alpha/beta；in: delta
    }.query(queryBackendBinding)
        .collectList()
        .test()
        .assertNext { rows -> rows.map { it.path("product").textValue() }.assert().containsExactly("alpha", "beta", "delta") }
        .verifyComplete()
}
```

（顺序断言依据：默认排序=分组别名序 + 无 sort 时 effectiveSort 追加分组别名——两后端一致；④ 的 tie 断言 alpha 在 beta 前依赖 effectiveSort 的追加序，与 Phase 1/2 排序口径一致。）

- [ ] **Step 2: EventStream 场景**（沿用既有事件 fixture；阈值按既有 grouped 场景断言值复推——执行者读既有分组事件聚合计数场景后取阈值，使恰好留部分分组）

```kotlin
@Test
fun aggregateHavingEventCounts() {
    // fixture 同既有分组事件聚合场景；having 过滤计数阈值，期望=该场景断言值中满足阈值的子集
}
```

- [ ] **Step 3: 双后端集成确认 GREEN**（Docker）

```bash
./gradlew :wow-mongo:integrationTest :wow-elasticsearch:integrationTest --stacktrace
```

若后端行为与断言不符：以设计语义修正实现（null 判假、过滤后 limit），不放宽断言；期望值算术错误按 fixture 复推修正并记录。

- [ ] **Step 4: 提交** `test(tck): add cross-backend contracts for aggregation having`

---

### Task 6: OpenAPI 快照与中英文档

**Files:**
- Modify: `wow-schema/src/main/kotlin/me/ahoo/wow/schema/contributor/FilterExpressionDefinitionProvider.kt`（`skipSubtypeLookup` 追加 `HavingExpression`——递归多态注册，`DerivedExpression` 先例；其测试同步断言 `$ref` 递归）
- Modify: OpenAPI 快照再生成（`-Dwow.snapshot.update=true`；差异须为纯增量：AggregationQuery 增加 `having` 属性引用 + 新 `HavingExpression`/`ComparisonOperator` schemas）
- Modify: `documentation/docs/zh/guide/query/aggregation-query.md` + en 镜像（HAVING 节：语义/引用规则/DSL/双后端实现说明与性能口径）
- Modify: `documentation/docs/zh/guide/query/snapshot-aggregation.md` + en 镜像（达成率阈值场景）

- [ ] **Step 1**: 先加 `skipSubtypeLookup` 注册与失败测试（RED：栈溢出）→ 修复 GREEN（TDD，#3240 先例的反向应用——这次前置）。
- [ ] **Step 2**: `./gradlew :wow-openapi:test -Dwow.snapshot.update=true` 再生成；`./gradlew :wow-openapi:test` 干净复跑绿（差异纯增量核对）。
- [ ] **Step 3**: 文档：zh HAVING 节——SQL 口径（null 判假、过滤后 limit）、引用规则（任意已声明指标含派生、禁 ANY/分组别名）、DSL 示例、Mongo `$match` 后置与 ES 客户端求值/按页超取说明、性能建议（优先 metric 排序 + having；选择性差的 having 在 ES 分组排序路径最坏全桶扫描）、无新版本要求；en 镜像；snapshot-aggregation 达成率阈值场景。
- [ ] **Step 4**: `cd documentation && pnpm docs:build`
- [ ] **Step 5**: 提交 `feat(openapi): align having schemas` 与 `docs(query): document aggregation having`（可两提交）

---

### Task 7: 全量回归与静态检查

- [ ] **Step 1**: `./gradlew :wow-api:check :wow-query:check :wow-mongo:check :wow-elasticsearch:check :wow-webflux:check :wow-openapi:check :wow-schema:check`
- [ ] **Step 2**: `./gradlew :wow-mongo:integrationTest :wow-elasticsearch:integrationTest --stacktrace`
- [ ] **Step 3**: `./gradlew allLocalTest allContractTest`
- [ ] **Step 4**: `./gradlew detekt`
- [ ] **Step 5**: `git log --oneline`、`git status` 收尾核对；如实汇报各层结果。

---

## 计划自审记录

1. **规格覆盖**：AST 与构造期校验（Task 1）、DSL（Task 2）、Guard 值/节点计数（Task 2）、Mongo `$match` 后置 + null 守卫（Task 3）、ES 客户端求值 + 两处机制调整（Task 4）、TCK ①-⑦ + EventStream（Task 5；⑧ 构造期拒绝落 Task 1 单测——AST 层与后端无关）、wow-schema 递归注册（Task 6，前置化）、文档与快照（Task 6）、回归（Task 7）。设计节「性能与文档口径」落 Task 6 Step 3 文档要求。覆盖完整。
2. **自审修正记录**：①原稿 having 参数放在 sort 之前——会破坏既有 6 参位置构造（JMH 基准即受害者，#3234 教训），改为**末位**参数 + `NON_NULL` 省略；②原稿 Guard 用共享总值口径——实查 `validateFilters` 是**逐节点** `valueCount() ≤ maxFilterValues`，having walker 对齐该口径；③`HavingExpression` 递归（And/Or 自引用）→ `skipSubtypeLookup` 注册从"快照时发现"前置为 Task 6 Step 1 的 TDD 步骤；④Task 2 第二个 DSL 测试初稿含伪码——标注为组合断言意图说明，指示执行者按实际 DSL 写法展开（leaf 覆盖 between/isIn/isNull/isNotNull）。
3. **类型一致性**：`HavingExpression` 六子类型 + `ComparisonOperator` 六值（Task 1）↔ DSL 助手（Task 2）↔ `toHavingDocument`/`matchOperator`（Task 3）↔ `plan.having`/`matchesHaving`/`compare`（Task 4）↔ TCK 断言别名一致。`having` 不触碰任何 sealed `when` 派发点（独立可选字段）——Task 1 后全模块可编译（与 Phase 2 的穷尽性冲击不同）。
4. **风险注记**：①ES `shouldStop` 空页短路移除是行为敏感点——已限定 `plan.having == null` 时保持既有短路，无 having 逐字节不变，并由 Pager 既有回归测试守护；②Task 5 ④ 的 tie 断言（alpha 前 beta）依赖 effectiveSort 追加分组别名的既有口径，集成双后端确认；③Mongo `Document(metric, null)` 的 IS NULL 依赖"投影后别名恒存在"——派生阶段与第一 project 均包含全部指标别名（#3240 修复轮保证），分组键存在性由分组 match 阶段保证；④EventStream 场景阈值执行时按既有分组事件聚合断言值复推。
