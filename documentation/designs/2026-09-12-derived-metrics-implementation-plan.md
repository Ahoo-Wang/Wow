# Phase 2 实施计划：派生指标（比率）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 `AggregationMetric.Derived` 子类型——在聚合结果之上按声明序计算比率/复合指标（客单价、达成率、毛利率），MongoDB 与 Elasticsearch 双后端实现，含 DSL、构造期引用校验、TCK 契约与中英文档。

**Architecture:** 纯增量 sealed 子类型（wire 新增 `DERIVED` 判别类型，既有形状零变化）。派生表达式 `DerivedExpression`（`MetricRef`/`Constant`/`Binary`）只引用**声明序在前**的指标——结构上无环、无需拓扑排序，按声明序线性求值。语义：null 传播 + 除零→null + 结果必须有限 double。Mongo 在 `$group` 投影后追加**第二个 `$project`** 计算派生（复用既有 `$let`/`$cond`/`finiteDouble` 守卫模式）；ES 用 `bucket_script` 管道聚合（`gap_policy` 默认 skip 即 null 传播），引用路径由编译器从计划类型预生成。

**Tech Stack:** Kotlin 2.x、JUnit 5、fluent-assert、Jackson 3、MongoDB Java Driver、elasticsearch-java 9.x（`bucket_script`）、Testcontainers。

**Spec:** [2026-09-12-aggregation-reporting-epic-design.md](2026-09-12-aggregation-reporting-epic-design.md)「Phase 2」节。语义、引用规则、TCK 场景以该文档为准；本计划补充实现细节决策。

## Global Constraints

- 基线：`main`（含 #3233/#3234/#3235）。
- wire 兼容纯增量：仅新增 `DERIVED` 子类型与 `DerivedExpression` 三态；既有子类型 JSON 形状与 DSL 签名零变化。
- 只考虑源码级与 RESTful API 兼容，不考虑二进制兼容（用户既定裁决）。
- 不为存储端低版本做降级/门控。本阶段无新存储版本要求：Mongo 第二 `$project` 与 ES `bucket_script` 均为远早于基线（Mongo 7.0 / ES 9.x）的能力。
- 每个实现任务严格 TDD：先写失败测试、亲见 RED（失败在预期点）、再实现、GREEN。
- 测试断言用 fluent-assert `.assert()`；核心路径 Reactor 非阻塞。
- 提交信息 conventional commits（scope：`api`/`query`/`mongo`/`elasticsearch`/`webflux`/`tck`/`openapi`/`docs`）。
- 语义锚点（与 Phase 1 空语义衔接）：`MetricRef` 引用空语义指标（Numeric/Percentile 空匹配→null）→ 派生 null；除零→null；任一操作数 null→null；结果必须有限（`±Infinity`/`NaN`→null）。
- `Derived` **不携带 filter**（设计裁决：filter 是记录级概念，派生在聚合之后计算）。`Derived` 也不得被 `MetricRef` 引用 `Any` 指标（非数值，`$add`/painless 算术会运行期报错——构造期 fail-closed，见 Task 1）。

---

### Task 1: wow-api — Derived 子类型与构造期校验

**Files:**
- Modify: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/AggregationQuery.kt`
- Test: `wow-api/src/test/kotlin/me/ahoo/wow/api/query/AggregationQueryTest.kt`

**Interfaces:**
- Consumes: 既有 `requireAggregationAlias`、`MAX_EXPRESSION_DEPTH`/`MAX_EXPRESSION_NODES`、`MatchAllFilterValueFilter`。
- Produces（后续任务全部依赖）：

```kotlin
// AggregationMetric 的 JsonSubTypes 追加 JsonSubTypes.Type(AggregationMetric.Derived::class, name = "DERIVED")
// @Schema oneOf 数组同步追加 AggregationMetric.Derived::class

data class Derived(
    override val alias: String,
    val expression: DerivedExpression,
) : AggregationMetric {
    @get:JsonInclude(JsonInclude.Include.CUSTOM, valueFilter = MatchAllFilterValueFilter::class)
    override val filter: FilterExpression get() = MatchAllFilter
    init {
        requireAggregationAlias(alias)
    }
}

@MissingTypeImpl 不适用（新类型无既有 wire 形态，所有形态显式携带 type）
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = QueryProtocol.Polymorphic.TYPE)
@JsonSubTypes(
    JsonSubTypes.Type(DerivedExpression.MetricRef::class, name = "METRIC_REF"),
    JsonSubTypes.Type(DerivedExpression.Constant::class, name = "CONSTANT"),
    JsonSubTypes.Type(DerivedExpression.Binary::class, name = "BINARY"),
)
@Schema(oneOf = [DerivedExpression.MetricRef::class, DerivedExpression.Constant::class, DerivedExpression.Binary::class], discriminatorProperty = QueryProtocol.Polymorphic.TYPE)
sealed interface DerivedExpression {
    data class MetricRef(val metric: String) : DerivedExpression
    data class Constant(val value: Double) : DerivedExpression {
        init {
            require(value.isFinite()) { "derived constant must be finite." }
        }
    }
    data class Binary(
        val operator: AggregationExpressionOperator,  // 复用 ADD/SUBTRACT/MULTIPLY/DIVIDE
        val left: DerivedExpression,
        val right: DerivedExpression,
    ) : DerivedExpression
}
```

- 构造期校验：`AggregationQuery.init` 在 `metrics.requireValidExpressions()` 之后追加 `metrics.requireValidDerivedMetrics()`（新私有函数，同文件）：
  - 按声明序遍历 metrics，维护 `declared = LinkedHashMap<String, Boolean /*isAny*/>`（alias → 是否 `Any`）。
  - `Derived`：对 `expression` 做深度（≤`MAX_EXPRESSION_DEPTH`，根深度 1）/节点数（≤`MAX_EXPRESSION_NODES`）迭代校验（模式同 `requireValidExpressions` 的 `PendingExpression` 队列）。
  - 每个 `MetricRef.metric`：`require(metric in declared)`——覆盖未知别名、前向引用、分组别名（分组别名与指标别名经既有唯一性检查互斥，不会出现在 declared 中）；错误消息：`"derived metric [$alias] must reference a metric declared before it, but was [$ref]."`。`require(!declared.getValue(ref) /*isAny*/)`——错误消息：`"derived metric [$alias] cannot reference ANY metric [$ref]."`。
  - `DerivedExpression.Constant` 自校验 finite（init）；`Binary` 操作数入队。
  - `requireValidExpressions()`（320-355 行）的 metric `when` 追加 `is AggregationMetric.Derived -> Unit`（派生树由新函数校验）。

**注意**：`Derived.filter` 为 getter-only 覆写（非构造参数）——序列化经 `MatchAllFilterValueFilter` 省略（恒等于 `MatchAllFilter`）、反序列化无 creator 参数天然忽略；接口其余消费方（`HttpQueryGuard.validateFilters` 的 `filter { it !== MatchAllFilter }`、`metricFilter()` 短路）自动跳过。

- [ ] **Step 1: 写失败测试**（追加到 AggregationQueryTest）

```kotlin
@Test
fun `derived metrics should round trip with expression subtypes`() {
    val json = """
        {
          "metrics": [
            {"type": "COUNT", "alias": "paid"},
            {"type": "SUM", "alias": "x"},
            {"type": "DERIVED", "alias": "aov",
             "expression": {"type": "BINARY", "operator": "DIVIDE",
               "left": {"type": "METRIC_REF", "metric": "paid"},
               "right": {"type": "CONSTANT", "value": 2.0}}}
          ]
        }
    """.trimIndent()
    val query = configuredMapper.readValue(json, AggregationQuery::class.java)
    val derived = query.metrics.filterIsInstance<AggregationMetric.Derived>().single()
    derived.filter.assert().isEqualTo(MatchAllFilter)
    val wire = configuredMapper.writeValueAsString(query)
    wire.assert().contains("\"type\":\"DERIVED\"")
    // Derived 段不得出现 "filter"（getter-only 默认经 valueFilter 省略）
    wire.substring(wire.indexOf("DERIVED"), wire.length).assert().doesNotContain("\"filter\"")
}

@Test
fun `derived references must be declared earlier`() {
    fun derivedOf(vararg metrics: AggregationMetric) = AggregationQuery(metrics = metrics.toList())
    val paid = AggregationMetric.Count("paid")
    val aov = AggregationMetric.Derived(
        "aov",
        DerivedExpression.Binary(ADD, DerivedExpression.MetricRef("paid"), DerivedExpression.Constant(1.0)),
    )
    // 前向引用：aov 在前引用其后声明的 paid
    assertThrows<IllegalArgumentException> { derivedOf(aov, paid) }.message.assert()
        .contains("must reference a metric declared before it")
    assertThrows<IllegalArgumentException> {
        derivedOf(paid, AggregationMetric.Derived("x", DerivedExpression.MetricRef("unknown")))
    }.message.assert().contains("unknown")
    // 单独 MetricRef 引用先声明指标——合法，不抛
    derivedOf(paid, AggregationMetric.Derived("x", DerivedExpression.MetricRef("paid")))
}

@Test
fun `derived references to any metrics and group aliases are rejected`() {
    val any = AggregationMetric.Any(QueryField("status"), "sample")
    assertThrows<IllegalArgumentException> {
        AggregationQuery(
            groupBy = listOf(AggregationGroup.Terms(QueryField("status"), "status")),
            metrics = listOf(any, AggregationMetric.Derived("x", DerivedExpression.MetricRef("sample"))),
        )
    }.message.assert().contains("cannot reference ANY metric")
    assertThrows<IllegalArgumentException> { // 引用分组别名 = 未知指标
        AggregationQuery(
            groupBy = listOf(AggregationGroup.Terms(QueryField("status"), "status")),
            metrics = listOf(
                AggregationMetric.Count("total"),
                AggregationMetric.Derived("x", DerivedExpression.MetricRef("status")),
            ),
        )
    }
}

@Test
fun `derived expressions are depth and node bounded`() {
    fun nest(depth: Int): DerivedExpression =
        (1 until depth).fold(DerivedExpression.Constant(1.0) as DerivedExpression) { acc, _ ->
            DerivedExpression.Binary(ADD, acc, DerivedExpression.Constant(1.0))
        }
    assertThrows<IllegalArgumentException> {
        AggregationQuery(metrics = listOf(AggregationMetric.Count("c"), AggregationMetric.Derived("d", nest(9))))
    }.message.assert().contains("depth")
    assertThrows<IllegalArgumentException> {
        DerivedExpression.Constant(Double.POSITIVE_INFINITY)
    }
}
```

（`DIVIDE`/`ADD`/`MetricRef` 为 `DerivedExpression`/`AggregationExpressionOperator` 嵌套类型的静态导入简写，按测试文件既有 import 风格展开；`configuredMapper` 为该测试类既有 mapper。）

- [ ] **Step 2: RED**：`./gradlew :wow-api:test --tests "me.ahoo.wow.api.query.AggregationQueryTest"`——编译失败：`Derived`/`DerivedExpression` 不存在（失败点即预期点）。

- [ ] **Step 3: 实现**（按 Interfaces；`DerivedExpression` 与注解块放在 `AggregationMetric` 之后、`AggregationFunction` 之前；`requireValidDerivedMetrics` 放在 `requireValidExpressions` 之后同文件私有）。

- [ ] **Step 4: GREEN**：`./gradlew :wow-api:test` 全绿（其余模块因 sealed 穷尽性**预期编译失败**——不属本任务范围，Task 2-4 逐模块补臂；执行者在此步只跑 `:wow-api:test`）。

- [ ] **Step 5: 提交** `feat(api): add derived aggregation metric type`

---

### Task 2: wow-query DSL + 验证臂 + wow-webflux Guard 臂

**Files:**
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/AggregationQueryDsl.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaValidation.kt:301`（aggregate() metric when）
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/query/HttpQueryGuard.kt:219`（hasArithmeticExpression）
- Test: `wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/AggregationQueryDslTest.kt`、`wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QuerySchemaValidationTest.kt`、`wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/query/HttpQueryGuardTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `AggregationMetric.Derived`/`DerivedExpression`。
- Produces:

```kotlin
// AggregationQueryDsl 内（@QueryDslMarker 同类）：
fun derived(alias: String, expression: DerivedExpression) {
    metrics += AggregationMetric.Derived(alias, expression)
}

fun derived(alias: String, init: DerivedExpressionDsl.() -> DerivedExpression) =
    derived(alias, DerivedExpressionDsl().init())

@QueryDslMarker
class DerivedExpressionDsl {
    fun ref(metric: String): DerivedExpression = DerivedExpression.MetricRef(metric)
    fun constant(value: Double): DerivedExpression = DerivedExpression.Constant(value)

    operator fun DerivedExpression.plus(other: DerivedExpression): DerivedExpression = binary(ADD, other)
    operator fun DerivedExpression.minus(other: DerivedExpression): DerivedExpression = binary(SUBTRACT, other)
    operator fun DerivedExpression.times(other: DerivedExpression): DerivedExpression = binary(MULTIPLY, other)
    operator fun DerivedExpression.div(other: DerivedExpression): DerivedExpression = binary(DIVIDE, other)

    private fun DerivedExpression.binary(operator: AggregationExpressionOperator, other: DerivedExpression) =
        DerivedExpression.Binary(operator, this, other)
}
```

（独立上下文类而非挂到 `AggregationQueryDsl`：`constant(Double)` 在同类中会与既有 `AggregationExpression` 版本签名冲突。`derived` 两个重载参数类型不同，无歧义。）

- `QuerySchemaValidation.aggregate()` 追加臂：`is AggregationMetric.Derived -> Unit`（无 schema 字段；引用合法性已由 AST 构造期保证）。
- `HttpQueryGuard.hasArithmeticExpression()` 追加臂：`is AggregationMetric.Derived -> true`（派生即算术——与既有"expensive operators 未允许时禁用指标算术表达式"口径一致）。

- [ ] **Step 1: 写失败测试**

```kotlin
// AggregationQueryDslTest
@Test
fun `aggregation DSL should build derived metrics from refs and operators`() {
    val query = aggregation {
        count("paid")
        sum("amount", "paidAmount") { "status" eq "PAID" }
        derived("aov") { ref("paidAmount") / ref("paid") }
        derived("target") { constant(120.0) }
        derived("attainment") { ref("paidAmount") / ref("target") }
    }
    val derived = query.metrics.filterIsInstance<AggregationMetric.Derived>()
    derived.assert().hasSize(3)
    val aov = derived[0].expression as DerivedExpression.Binary
    aov.operator.assert().isEqualTo(AggregationExpressionOperator.DIVIDE)
    (aov.left as DerivedExpression.MetricRef).metric.assert().isEqualTo("paidAmount")
    (derived[1].expression as DerivedExpression.Constant).value.assert().isEqualTo(120.0)
    // attainment 引用先声明的派生 target —— 构造不抛即链合法
}

// QuerySchemaValidationTest
@Test
fun `derived metrics need no schema capability`() {
    val schema = boundSchemaFixture(objectFixture("status" to scalarFixture()))
    validateQuery(
        aggregation {
            count("total")
            derived("half") { ref("total") / constant(2.0) }
        },
        schema,
    ).assert().isNotNull()
}

// HttpQueryGuardTest（沿用既有 guard 测试构造助手）
@Test
fun `derived metrics count as arithmetic expressions`() {
    val guard = guard(allowExpensiveOperators = false)
    assertThrows<IllegalArgumentException> {
        guard.validateAggregation(
            aggregation {
                count("total")
                derived("half") { ref("total") / constant(2.0) }
            },
        )
    }
}
```

（`guard(...)` 参数按既有测试助手实况调整；`validateAggregation` 为 guard 的既有聚合入口，名称以实况为准。）

- [ ] **Step 2: RED**：`./gradlew :wow-query:test --tests "*AggregationQueryDsl*" --tests "*QuerySchemaValidation*"` 与 `:wow-webflux:test --tests "*HttpQueryGuard*"`——编译失败（`derived` 不存在 / when 非穷尽）。

- [ ] **Step 3: 实现**（按 Interfaces 三处）。

- [ ] **Step 4: GREEN**：`./gradlew :wow-query:test :wow-webflux:test`

- [ ] **Step 5: 提交** `feat(query): add derived metric DSL and guard arms`（可与 webflux 部分合并一提交；若分拆则 `feat(webflux): ...`）

---

### Task 3: wow-mongo — 第二 $project 派生阶段与结果映射

**Files:**
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/aggregation/MongoAggregationCompiler.kt`（`compile` 138 行后、`group()`/`project()` when 臂、新增 `derivedProject` 与 `DerivedExpression` 编译）
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoQueryBackend.kt:185,201`（toAggregationResult/emptySummary 两处 when）
- Test: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompilerTest.kt`

**Interfaces:**
- Consumes: Task 1 AST；既有 `finiteDouble`、`toMongoExpression` 的 Binary `$let`/`$cond` 守卫模式、`mongoOperator`。
- Produces:

```kotlin
// compile()：project 之后、sort 之前（sort 需读到派生别名）
add(project(query))
if (query.metrics.any { it is AggregationMetric.Derived }) {
    add(derivedProject(query))
}

// group() 与 project() 的 metric when 各追加：is AggregationMetric.Derived -> Unit
// （派生无 accumulator、无第一阶段投影——值由第二阶段补）

private fun derivedProject(query: AggregationQuery): Bson {
    val projections = buildList {
        add(Projections.excludeId())
        query.groupBy.forEach { add(Projections.include(it.alias)) }
        query.metrics.forEach { metric ->
            when (metric) {
                is AggregationMetric.Derived -> add(Projections.computed(metric.alias, metric.expression.toDerivedDocument()))
                else -> add(Projections.include(metric.alias))
            }
        }
    }
    return Aggregates.project(Projections.fields(projections))
}

// 声明序即求值序：$project 计算字段按序可见于同文档后续字段（文档内顺序求值），
// 引用先声明派生读到的即是本阶段已计算的同名输出。
private fun DerivedExpression.toDerivedDocument(): Any = when (this) {
    is DerivedExpression.MetricRef -> "\$$metric"
    is DerivedExpression.Constant -> value
    is DerivedExpression.Binary -> {
        val conditions = mutableListOf<Any>(
            Document("\$ne", listOf("\$\$left", null)),
            Document("\$ne", listOf("\$\$right", null)),
        )
        if (operator == AggregationExpressionOperator.DIVIDE) {
            conditions += Document("\$ne", listOf("\$\$right", 0.0))
        }
        finiteDouble(
            Document(
                "\$let",
                Document("vars", Document("left", left.toDerivedDocument()).append("right", right.toDerivedDocument()))
                    .append(
                        "in",
                        Document(
                            "\$cond",
                            listOf(
                                Document("\$and", conditions),
                                Document(operator.mongoOperator, listOf("\$\$left", "\$\$right")),
                                null,
                            ),
                        ),
                    ),
            ),
        )
    }
}
```

（null 传播/除零/有限性与既有记录级 Binary 完全同构——被引用指标经第一阶段 project 已是守卫后的终值：`COUNT` 恒数、Numeric/Percentile 空贡献已改写 null。）

- `AbstractMongoQueryBackend.toAggregationResult` 追加：`is AggregationMetric.Derived -> get(metric.alias).toFiniteDouble(metric.alias)`；`emptySummary` 追加：`is AggregationMetric.Derived -> null`（null 传播：空聚合引用指标为空语义）。

- [ ] **Step 1: 写失败测试**（结构化 Document 断言，沿用本类既有风格）

```kotlin
@Test
fun `derived metrics compile into a second project stage`() {
    val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
        aggregation {
            count("paid")
            sum("state.amount", "paidAmount") { "state.status" eq "PAID" }
            derived("aov") { ref("paidAmount") / ref("paid") }
        },
        schema(),
    ).map { it.toBsonDocument() }

    val projects = pipeline.filter { it.containsKey("\$project") }
    projects.assert().hasSize(2)
    val derived = projects[1].getDocument("\$project")
    derived.containsKey("_id").assert().isTrue() // excludeId
    val aov = derived.getDocument("aov").getDocument("\$let")
    aov.getDocument("vars").getString("left").value.assert().isEqualTo("\$paidAmount")
    // DIVIDE 守卫：$and 含 $ne right 0.0
    val cond = aov.getDocument("in").getArray("\$cond")
    cond.get(0).asDocument().getArray("\$and").assert().hasSize(3)
    // 既有指标别名与分组别名在第二阶段保留
    derived.containsKey("paid").assert().isTrue()
    derived.containsKey("paidAmount").assert().isTrue()
    // 排序阶段位于派生之后（sort 引用派生别名）
    pipeline.indexOfLast { it.containsKey("\$project") }
        .assert().isLessThan(pipeline.indexOfFirst { it.containsKey("\$sort") })
}

@Test
fun `derived without derived metrics keeps a single project stage`() {
    val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
        aggregation { count("total") },
        schema(),
    ).map { it.toBsonDocument() }
    pipeline.filter { it.containsKey("\$project") }.assert().hasSize(1)
}

@Test
fun `derived constants and chains compile leaves in order`() {
    val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
        aggregation {
            count("total")
            derived("half") { ref("total") / constant(2.0) }
            derived("quarter") { ref("half") / constant(2.0) }
        },
        schema(),
    ).map { it.toBsonDocument() }
    val derived = pipeline.last { it.containsKey("\$project") }.getDocument("\$project")
    derived.containsKey("half").assert().isTrue()
    derived.containsKey("quarter").assert().isTrue()
    derived.keys.toList().indexOf("half").assert().isLessThan(derived.keys.toList().indexOf("quarter"))
}
```

（`schema()` 默认字段集含 `state.status`/`state.amount` ✓；第二个测试中 `it as Bson` 的实际类型按 compile 返回 `List<Bson>` 直接用 `it.toBsonDocument()` 即可——以本类既有断言风格为准。）

- [ ] **Step 2: RED**：`./gradlew :wow-mongo:test --tests "*MongoAggregationCompiler*"`——编译失败（when 非穷尽：`group`/`project`/`toAggregationResult`/`emptySummary`）。

- [ ] **Step 3: 实现**（按 Interfaces；无派生查询的 pipeline **逐阶段不变**——`any { is Derived }` 守卫保证）。

- [ ] **Step 4: GREEN**：`./gradlew :wow-mongo:test`

- [ ] **Step 5: 提交** `feat(mongo): compute derived metrics in a second project stage`

---

### Task 4: wow-elasticsearch — bucket_script 计划与提取

**Files:**
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/aggregation/ElasticsearchAggregationCompiler.kt`（计划类型 + `compile()` prior 映射 + `toPlan` 臂 + bucketsPath/script 生成）
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/aggregation/ElasticsearchAggregationPager.kt`（`metricAggregations`/`value()` 臂）
- Test: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationCompilerTest.kt`

**Interfaces:**
- Consumes: Task 1 AST；既有计划类型、`metricFilterAggregationName`、`valueCountAlias`。
- Produces:

```kotlin
// 计划类型追加：
data class Derived(
    override val alias: String,
    val bucketsPath: Map<String, String>,
    val script: Script,
) : ElasticsearchAggregationMetric {
    override val filter: Query? = null
}

// compile()：metrics 构建改为携带先声明计划映射（声明序天然由遍历序保证）：
val metricPlans = mutableListOf<ElasticsearchAggregationMetric>()
val priorByAlias = linkedMapOf<String, ElasticsearchAggregationMetric>()
query.metrics.forEachIndexed { index, metric ->
    val plan = metric.toPlan(logicalParent, physicalParent, index, schema, runtimeMappings, now, priorByAlias)
    metricPlans += plan
    priorByAlias[metric.alias] = plan
}

// toPlan 追加参数 prior: Map<String, ElasticsearchAggregationMetric>，追加臂：
is AggregationMetric.Derived -> toDerivedPlan(metric.expression, priorByAlias)

private fun toDerivedPlan(
    expression: DerivedExpression,
    prior: Map<String, ElasticsearchAggregationMetric>,
): ElasticsearchAggregationMetric.Derived {
    val bucketsPath = linkedMapOf<String, String>()
    val source = "def value = ${expression.toScript(prior, bucketsPath)}; " +
        "value == null || !Double.isFinite(value) ? null : value"
    return ElasticsearchAggregationMetric.Derived(
        alias,
        bucketsPath,
        Script.of { it.source { s -> s.scriptString(source) } },
    )
}

// 叶子/节点序列化；MetricRef 顺带登记 bucketsPath 条目（vN=值路径，Numeric/Percentile 额外 cN=计数路径）
private fun DerivedExpression.toScript(
    prior: Map<String, ElasticsearchAggregationMetric>,
    bucketsPath: MutableMap<String, String>,
): String = when (this) {
    is DerivedExpression.MetricRef -> {
        val target = prior.getValue(metric)
        val index = bucketsPath.size
        val (valuePath, countPath) = target.referencePaths()
        bucketsPath["v$index"] = valuePath
        if (countPath == null) {
            "params.v$index"
        } else {
            bucketsPath["c$index"] = countPath
            "(params.c$index == 0.0 ? null : params.v$index)" // 空语义守卫：计数为 0 → null
        }
    }

    is DerivedExpression.Constant -> value.toString()

    is DerivedExpression.Binary -> when (operator) {
        AggregationExpressionOperator.ADD ->
            "(${left.toScript(prior, bucketsPath)} + ${right.toScript(prior, bucketsPath)})"
        AggregationExpressionOperator.SUBTRACT ->
            "(${left.toScript(prior, bucketsPath)} - ${right.toScript(prior, bucketsPath)})"
        AggregationExpressionOperator.MULTIPLY ->
            "(${left.toScript(prior, bucketsPath)} * ${right.toScript(prior, bucketsPath)})"
        AggregationExpressionOperator.DIVIDE -> {
            val rightScript = right.toScript(prior, bucketsPath)
            val leftScript = left.toScript(prior, bucketsPath)
            "($rightScript == 0.0 ? null : $leftScript / $rightScript)" // painless 除零抛异常 → 显式守卫
        }
    }
}

// 逐被引用指标解析 (valuePath, countPath?)；countPath 非空 = 脚本需要空语义守卫。
// filtered 包装名基于被引用指标自身的 alias：metricFilterAggregationName(target.alias)。
private fun ElasticsearchAggregationMetric.referencePaths(): Pair<String, String?> {
    val scope = if (filter == null) "" else "${metricFilterAggregationName(alias)}."
    return when (this) {
        is ElasticsearchAggregationMetric.Count ->
            // 未过滤：桶自身 doc_count；过滤：名为 alias 的 filter 聚合的 doc_count
            (if (filter == null) "_count" else "$alias._count") to null

        is ElasticsearchAggregationMetric.Numeric -> {
            val value = when (function) {
                AggregationFunction.SUM, AggregationFunction.AVG,
                AggregationFunction.MIN, AggregationFunction.MAX,
                -> "$alias.value"

                AggregationFunction.STDDEV -> "$alias.std_deviation_population"
                AggregationFunction.VARIANCE -> "$alias.variance_population"
            }
            "$scope$value" to "$scope${valueCountAlias}.value"
        }

        is ElasticsearchAggregationMetric.Percentile -> "$scope$alias[$percentile]" to "$scope${valueCountAlias}.value"
        is ElasticsearchAggregationMetric.DistinctCount -> "$scope$alias.value" to null // cardinality 恒非 null（可为 0）
        is ElasticsearchAggregationMetric.Derived -> "$alias.value" to null // 先声明 bucket_script 输出；null → gap → skip
    }
}
```

（`metricFilterAggregationName`/`valueCountAlias` 为 Pager 同包文件级既有声明——若声明于 Pager 文件为 private，则将 `referencePaths` 移至 Pager 文件或提升这两者为 internal 同包共享；执行者按现状选择最小改动。gap_policy 不显式设置：`bucket_script` 默认即 skip（缺路径/null 值 → 不执行 → null），与 null 传播一致。）

- Pager `metricAggregations()` 追加臂：

```kotlin
is ElasticsearchAggregationMetric.Derived -> put(
    metric.alias,
    Aggregation.of { builder ->
        builder.bucketScript { bs -> bs.bucketsPath(metric.bucketsPath).script(metric.script) }
    },
)
```

（`bucket_script` 引用兄弟聚合按名字解析，与 map 内声明序无关；引用先声明派生 → `${alias}.value` ✓。不增加 `bucketWidth` 预算——bucket_script 不产生桶。）

- Pager `value()` 追加臂：`is ElasticsearchAggregationMetric.Derived -> aggregations.getValue(alias).simpleValue().value()`（`Double?`；ungrouped summary 与分组行同一路径——`summary()` 走 `metricAggregations()` 同一聚合集 ✓；按派生别名排序复用既有 metric-sorted Top-N 路径，行值经 `toRow` 自动携带 ✓）。

- [ ] **Step 1: 写失败测试**

```kotlin
@Test
fun `derived metrics plan bucket scripts with guarded paths`() {
    val plan = ElasticsearchAggregationCompiler(SnapshotFilterCompiler).compile(
        aggregation {
            count("paid") { "status" eq "PAID" }
            sum("amount", "paidAmount") { "status" eq "PAID" }
            sum("amount", "totalAmount")
            derived("aov") { ref("paidAmount") / ref("paid") }
            derived("attainment") { ref("paidAmount") / ref("totalAmount") }
        },
        schema,
    )
    val derived = plan.metrics.filterIsInstance<ElasticsearchAggregationMetric.Derived>()
    derived.assert().hasSize(2)
    val aov = derived[0]
    // 过滤 Numeric：value 走 wrapper、count 走 wrapper 内 valueCount；过滤 Count：alias._count
    aov.bucketsPath.assert().containsKey("v0").containsKey("c0").containsKey("v1")
    aov.bucketsPath["v0"].assert().isEqualTo("__wow_metric_filter_paidAmount.paidAmount.value")
    aov.bucketsPath["c0"].assert().isEqualTo("__wow_metric_filter_paidAmount.__wow_value_count_paidAmount.value")
    aov.bucketsPath["v1"].assert().isEqualTo("paid._count")
    aov.script.source().scriptString().assert()
        .contains("(params.c0 == 0.0 ? null : params.v0)").contains("params.v1 == 0.0 ? null")
    val attainment = derived[1]
    attainment.bucketsPath["v0"].assert().isEqualTo("__wow_metric_filter_paidAmount.paidAmount.value")
    attainment.bucketsPath["v2"].assert().isEqualTo("totalAmount.value") // 未过滤 Numeric 无 wrapper
    attainment.bucketsPath["c2"].assert().isEqualTo("__wow_value_count_totalAmount.value")
    attainment.script.source().scriptString().assert().contains("Double.isFinite")
}

@Test
fun `derived chains reference prior derived aliases`() {
    val plan = ElasticsearchAggregationCompiler(SnapshotFilterCompiler).compile(
        aggregation {
            count("total")
            derived("half") { ref("total") / constant(2.0) }
            derived("quarter") { ref("half") / constant(2.0) }
        },
        schema,
    )
    val quarter = plan.metrics.filterIsInstance<ElasticsearchAggregationMetric.Derived>()[1]
    quarter.bucketsPath.values.single().assert().isEqualTo("half.value")
    quarter.script.source().scriptString().assert().contains("/ 2.0")
}
```

（`script.source().scriptString()` 访问器名按 elasticsearch-java 9.x 实际 API 调整（如 `source().toString()` / `_get()`）；断言意图不变。`schema` 为本测试类既有绑定 schema。）

- [ ] **Step 2: RED**：`./gradlew :wow-elasticsearch:test --tests "*ElasticsearchAggregationCompiler*"`——编译失败（when 非穷尽 + 计划类型无 Derived）。

- [ ] **Step 3: 实现**（按 Interfaces；bucketsPath 键 `vN`/`cN` 按引用出现顺序编号，脚本与路径共用同一编号）。

- [ ] **Step 4: GREEN**：`./gradlew :wow-elasticsearch:test`

- [ ] **Step 5: 提交** `feat(elasticsearch): derive metrics with bucket scripts`

---

### Task 5: TCK — 跨后端契约场景

**Files:**
- Modify: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/SnapshotQueryBackendSpec.kt`（1215 行区域之后追加）
- Modify: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/EventStreamQueryBackendSpec.kt`

**fixture 事实**（`aggregationStates()`，展开 `state.orders`+`lines` 后 ACTIVE 共 6 行）：

| 行 | quantity | amount |
|---|---|---|
| stateA alpha | 1 | 10 |
| stateA beta | 2 | 20 |
| stateA gamma（CANCELLED 单） | 3 | null |
| stateB alpha | 4 | 30 |
| stateB beta | 2 | 20 |
| stateB delta | 5 | 50 |

派生期望值推导：`big`(qty≥2)=5 行、`bigAmount`=120.0、`total`=全部 6 行、`totalAmount`=130.0（gamma null 不参与）。

构造期拒绝类（前向引用/未知别名/分组别名/ANY 引用/深度超限）不进 TCK——AST 层规则与后端无关，已由 Task 1 单测锁定（设计场景 ⑨ 的落点说明，PR 描述中注明）。

- [ ] **Step 1: Snapshot 场景（设计 ①-⑧）**

```kotlin
@Test
fun `aggregation should derive ratios from filtered metrics`() { // ① 客单价 ⑧ filter×derived
    saveAggregationStates(*aggregationStates().toTypedArray())
    aggregation {
        filter { deletion(DeletionState.ACTIVE) }
        expand("state.orders")
        expand("lines")
        count("big") { "quantity" gte 2 }                // 5
        sum("amount", "bigAmount") { "quantity" gte 2 }  // 120.0
        derived("aov") { ref("bigAmount") / ref("big") } // 24.0
    }.query(queryBackendBinding)
        .test()
        .assertNext { it.assertWireEquals(mapOf("big" to 5L, "bigAmount" to 120.0, "aov" to 24.0)) }
        .verifyComplete()
}

@Test
fun `aggregation should derive ratios between sums and constants`() { // ② 达成率 ③ 复合表达式
    saveAggregationStates(*aggregationStates().toTypedArray())
    aggregation {
        filter { deletion(DeletionState.ACTIVE) }
        expand("state.orders")
        expand("lines")
        sum("amount", "totalAmount")                                     // 130.0
        sum("amount", "bigAmount") { "quantity" gte 2 }                  // 120.0
        derived("margin") { (ref("totalAmount") - ref("bigAmount")) / constant(10.0) } // 1.0
        derived("target") { constant(120.0) }
        derived("attainment") { ref("bigAmount") / ref("target") }       // 1.0
    }.query(queryBackendBinding)
        .test()
        .assertNext {
            it.assertWireEquals(
                mapOf("totalAmount" to 130.0, "bigAmount" to 120.0, "margin" to 1.0, "target" to 120.0, "attainment" to 1.0),
            )
        }.verifyComplete()
}

@Test
fun `aggregation should derive null on division by zero`() { // ④
    saveAggregationStates(*aggregationStates().toTypedArray())
    aggregation {
        filter { deletion(DeletionState.ACTIVE) }
        expand("state.orders")
        expand("lines")
        count("none") { "quantity" gt 100 }                              // 0
        sum("amount", "bigAmount") { "quantity" gte 2 }                  // 120.0
        derived("ratio") { ref("bigAmount") / ref("none") }              // null
    }.query(queryBackendBinding)
        .test()
        .assertNext { it.assertWireEquals(mapOf("none" to 0L, "bigAmount" to 120.0, "ratio" to null)) }
        .verifyComplete()
}

@Test
fun `aggregation should propagate null from empty metrics into derived`() { // ⑤
    saveAggregationStates(*aggregationStates().toTypedArray())
    aggregation {
        filter { deletion(DeletionState.ACTIVE) }
        expand("state.orders")
        expand("lines")
        sum("amount", "noneAmount") { "quantity" gt 100 }                // null
        derived("scaled") { ref("noneAmount") * constant(2.0) }          // null
    }.query(queryBackendBinding)
        .test()
        .assertNext { it.assertWireEquals(mapOf("noneAmount" to null, "scaled" to null)) }
        .verifyComplete()
}

@Test
fun `aggregation should derive from previously derived metrics`() { // ⑥ 派生链
    saveAggregationStates(*aggregationStates().toTypedArray())
    aggregation {
        filter { deletion(DeletionState.ACTIVE) }
        expand("state.orders")
        expand("lines")
        count("big") { "quantity" gte 2 }                                // 5
        sum("amount", "bigAmount") { "quantity" gte 2 }                  // 120.0
        derived("aov") { ref("bigAmount") / ref("big") }                 // 24.0
        derived("aovDoubled") { ref("aov") * constant(2.0) }             // 48.0
    }.query(queryBackendBinding)
        .test()
        .assertNext {
            it.assertWireEquals(mapOf("big" to 5L, "bigAmount" to 120.0, "aov" to 24.0, "aovDoubled" to 48.0))
        }.verifyComplete()
}

@Test
fun `aggregation should sort groups by a derived metric`() { // ⑦（数据形态对齐 Phase 1 过滤排序场景）
    saveAggregationStates(*aggregationStates().toTypedArray())
    aggregation {
        filter { deletion(DeletionState.ACTIVE) }
        expand("state.orders")
        expand("lines")
        terms("productId", "product")
        sum("amount", "bigAmount") { "quantity" gte 2 } // delta=50, beta=40, alpha=30, gamma=null
        derived("share") { ref("bigAmount") / constant(50.0) } // 1.0 / 0.8 / 0.6 / null
        sort { "share".desc() }
    }.query(queryBackendBinding)
        .collectList()
        .test()
        .assertNext { rows ->
            rows.map { it.path("product").textValue() }.assert()
                .containsExactly("delta", "beta", "alpha", "gamma") // null 置尾，与 Phase 1 排序口径一致
        }.verifyComplete()
}
```

- [ ] **Step 2: EventStream 场景**

```kotlin
@Test
fun aggregateDerivedEventRatios() {
    // 沿用本类既有事件流 fixture（Phase 1 aggregateFilteredEventCounts 同源）
    aggregation {
        filter { tenantId(tenantId) }
        expand("body")
        count("all")
        count("first") { "revision" eq 1L }
        derived("firstShare") { ref("first") / ref("all") }
    }.query(queryBackendBinding)
        .test()
        .assertNext {
            it.path("firstShare").doubleValue().assert().isEqualTo(/* all/first 按既有场景期望复推 */)
        }.verifyComplete()
}
```

（期望值按该类既有 aggregateFilteredEventCounts 的 all/first 实测值复推——若为 10/1 则 0.1；`revision` 构造沿用既有场景写法。）

- [ ] **Step 3: 双后端集成确认 GREEN**（Docker）

```bash
./gradlew :wow-mongo:integrationTest :wow-elasticsearch:integrationTest --stacktrace
```

若后端行为与断言不符：以设计语义修正实现（如 ES `alias._count` 路径在目标版本的实际写法、`buckets_path` 对 extendedStats/percentiles 的键名），不放宽断言；期望值算术错误按 fixture 复推修正并记录。

- [ ] **Step 4: 提交** `test(tck): add cross-backend contracts for derived metrics`

---

### Task 6: OpenAPI 快照与中英文档

**Files:**
- Modify: OpenAPI 快照再生成（`-Dwow.snapshot.update=true` 机制，差异须为纯增量：`AggregationMetric` oneOf 新增 `DERIVED`、`DerivedExpression` 新 schema）
- Modify: `documentation/docs/zh/guide/query/aggregation-query.md`、`documentation/docs/en/guide/query/aggregation-query.md`（Derived 节：语义、引用规则、null 传播/除零、DSL 示例）
- Modify: `documentation/docs/zh/guide/query/snapshot-aggregation.md` + en 镜像（达成率场景：DSL 示例即 Task 2 形态）

- [ ] **Step 1**: `./gradlew :wow-openapi:test -Dwow.snapshot.update=true` 再生成；`./gradlew :wow-openapi:test` 干净复跑绿（差异纯增量核对）。
- [ ] **Step 2**: 文档：zh `aggregation-query.md` Metric 节后追加「派生指标」小节——声明序引用规则（无前向引用/不可引用分组别名与 ANY）、null 传播与除零→null、filter×derived 叠加示例、ES `bucket_script`/Mongo 第二 `$project` 实现说明与版本要求（无新增要求）；en 镜像；`snapshot-aggregation.md` zh/en 各加达成率场景。
- [ ] **Step 3**: `cd documentation && pnpm docs:build`
- [ ] **Step 4**: 提交 `feat(openapi): align derived metric schemas` 与 `docs(query): document derived aggregation metrics`（可两提交）

---

### Task 7: 全量回归与静态检查

- [ ] **Step 1**: `./gradlew :wow-api:check :wow-query:check :wow-mongo:check :wow-elasticsearch:check :wow-webflux:check :wow-openapi:check`
- [ ] **Step 2**: `./gradlew :wow-mongo:integrationTest :wow-elasticsearch:integrationTest --stacktrace`
- [ ] **Step 3**: `./gradlew allLocalTest allContractTest`
- [ ] **Step 4**: `./gradlew detekt`
- [ ] **Step 5**: `git log --oneline`、`git status` 收尾核对；如实汇报各层结果。

---

## 计划自审记录

1. **规格覆盖**：AST 与构造期校验（Task 1）、DSL（Task 2）、验证/Guard（Task 2）、Mongo 第二 `$project`（Task 3）、ES `bucket_script` 与 gap_policy=skip（Task 4）、null 传播/除零/有限结果（Tasks 3/4 守卫 + Task 5 ④⑤）、按派生排序与 ungrouped summary（Tasks 3/4 路径 + Task 5 ⑦）、TCK ①-⑧（Task 5；⑨ 落 Task 1 单测——AST 层规则与后端无关）、文档与快照（Task 6）、回归（Task 7）。覆盖完整。
2. **自审修正记录**：①原稿把派生运算符挂在 `AggregationQueryDsl` 上——`constant(Double)` 与既有 `AggregationExpression` 版本签名冲突，改为独立 `DerivedExpressionDsl` 上下文；②ES 引用空语义指标：`sum` 空集返回 0 而非 gap，直接引用会得到 0 而非 null——bucketsPath 为 Numeric/Percentile 引用同时收集 valueCount 路径并在脚本中 `(count == 0 ? null : value)` 守卫，与 Mongo 第一阶段 project 的 countAlias 改写对齐；③ES 脚本除零在 painless 中抛异常（非 null）——DIVIDE 节点显式 `right == 0.0 ? null` 守卫，整体包 `Double.isFinite` 守卫对齐 `finiteDouble`；④过滤 Count 引用路径 `alias._count`（filter 聚合 doc_count）与未过滤 `_count` 区分。
3. **类型一致性**：`AggregationMetric.Derived(alias, expression)`（Task 1）↔ DSL `derived`/`ref`/`constant`（Task 2）↔ Mongo `toDerivedDocument`（Task 3）↔ ES 计划 `Derived(alias, bucketsPath, script)`（Task 4）↔ TCK 断言别名一致；6 处 sealed `when` 派发点（wow-api `requireValidExpressions`、wow-query 验证、webflux guard、Mongo `group`/`project`/backend 两处、ES `toPlan`/pager 两处）全部点名补臂，编译器穷尽性兜底。
4. **风险注记**：①`$project` 计算字段文档内顺序求值——派生链依赖该语义（MongoDB 文档化行为：文档内按出现顺序求值，后续字段可引用先前计算字段）；若实测目标版本不满足，回退为每个派生单独一个 `$project` 阶段（计划允许，语义不变）；②ES `buckets_path` 对 extendedStats 键名（`std_deviation_population`）与 `alias._count` 写法以 Task 5 集成为准（本地无容器单测不覆盖），不符则改路径生成而非放宽断言；③`bucket_script` 脚本长度上限（`script.max_size_in_bytes`，默认 65535）远大于本阶段表达式上限产出的脚本，不构成约束；④裸 `MetricRef`（`derived("x") { ref("total") }`）合法——`Derived.expression` 即叶子，两后端直读 `"$total"` / `params.v0`，无需 Binary 包裹（Task 1 测试已含）。
