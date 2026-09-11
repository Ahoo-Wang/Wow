# 聚合查询指标函数扩展实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为共享的 `AggregationQuery` AST 增加 `DISTINCT_COUNT`、`STDDEV`、`VARIANCE`、`MEDIAN`/`PERCENTILE(p)` 五个指标函数，MongoDB 与 Elasticsearch 双后端实现，含 DSL、能力验证、TCK 契约与中英文档。

**Architecture:** `AggregationFunction` 枚举新增 `STDDEV`/`VARIANCE`（挂现有 `Numeric` 指标）；`AggregationMetric` 新增 `DistinctCount` 与 `Percentile` 子类型（沿用 Count/Any「形态即类型」模式）。两个编译器各自映射到原生算子（`$stdDevPop`/`$percentile`/`$push`+`$setUnion` 与 `extended_stats`/`percentiles`/`cardinality`），语义由 TCK 锚定。

**Tech Stack:** Kotlin 2.x、JUnit 5、MockK、Reactor、fluent-assert、MongoDB Java Driver、elasticsearch-java、Testcontainers。

**Spec:** [2026-09-11-aggregation-metric-functions-design.md](2026-09-11-aggregation-metric-functions-design.md)（语义规范表、精度口径、验证策略均以该文档为准）。

## Global Constraints

- 基线：`main` 分支，Wow `9.0.18`；不 bump 版本号。
- **不为存储端低版本做不支持或降级实现**：直接用原生算子，`PERCENTILE` 需要 MongoDB 7.0+ 仅在文档注明，运行时不做版本探测或门控。
- wire 兼容纯增量：不修改现有枚举值、字段名、JSON `type` 值与语义；source 兼容纯新增。
- 每个实现任务严格 TDD：先写失败测试、亲见 RED、再实现、确认 GREEN（用户 AGENTS.md 最高优先级原则）。
- 核心路径保持 Reactor 非阻塞，不引入阻塞调用。
- 测试断言用 fluent-assert 的 `.assert()` 扩展，不用 AssertJ。
- 不修改 CI workflow 文件；TCK 容器常量（`ContainerImages.MONGO`）属源码变更。
- 集成测试需要本地 Docker（Testcontainers 自动拉取 `mongo:7.0`）。
- 提交信息用 conventional commits，scope 用 `query`/`api`/`mongo`/`elasticsearch`/`tck`/`docs`。

---

### Task 1: wow-api — AggregationFunction 扩展与 AggregationMetric 新子类型

**Files:**
- Modify: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/AggregationQuery.kt`（198-247 行区域：`@JsonSubTypes`、`@Schema.oneOf`、`AggregationMetric`、`AggregationFunction`；260 行 `requireValidExpressions`）
- Test: `wow-api/src/test/kotlin/me/ahoo/wow/api/query/AggregationQueryTest.kt`

**Interfaces:**
- Produces（后续任务依赖的确切类型）:
  - `enum class AggregationFunction { SUM, AVG, MIN, MAX, STDDEV, VARIANCE }`
  - `data class AggregationMetric.DistinctCount(val expression: AggregationExpression, override val alias: String)`，JSON type `"DISTINCT_COUNT"`
  - `data class AggregationMetric.Percentile(val expression: AggregationExpression, val percentile: Double, override val alias: String)`，JSON type `"PERCENTILE"`，`percentile` 为有限 double 且 `0 < p < 100`

- [ ] **Step 1: 写失败测试**

在 `AggregationQueryTest.kt` 追加（沿用文件既有断言风格与 `configuredMapper`/`bareMapper`/`nestedExpression`）：

```kotlin
@Test
fun `distinct count and percentile metrics should round trip through JSON`() {
    val json = """
        {
          "metrics": [
            {"type": "DISTINCT_COUNT", "expression": {"field": "state.customerId"}, "alias": "customers"},
            {"type": "PERCENTILE", "expression": {"field": "state.amount"}, "percentile": 95.0, "alias": "p95"},
            {"type": "NUMERIC", "function": "STDDEV", "expression": {"field": "state.amount"}, "alias": "stddev"},
            {"type": "NUMERIC", "function": "VARIANCE", "expression": {"field": "state.amount"}, "alias": "variance"}
          ]
        }
    """.trimIndent()

    val query = configuredMapper.readValue(json, AggregationQuery::class.java)

    query.metrics.assert().containsExactly(
        AggregationMetric.DistinctCount(
            AggregationExpression.Field(QueryField("state.customerId")),
            "customers",
        ),
        AggregationMetric.Percentile(
            AggregationExpression.Field(QueryField("state.amount")),
            95.0,
            "p95",
        ),
        AggregationMetric.Numeric(
            AggregationFunction.STDDEV,
            AggregationExpression.Field(QueryField("state.amount")),
            "stddev",
        ),
        AggregationMetric.Numeric(
            AggregationFunction.VARIANCE,
            AggregationExpression.Field(QueryField("state.amount")),
            "variance",
        ),
    )
    configuredMapper.writeValueAsString(query).assert()
        .contains("\"type\":\"DISTINCT_COUNT\"")
        .contains("\"type\":\"PERCENTILE\"")
}

@Test
fun `distinct count and percentile should reject internal aliases`() {
    assertThrows<IllegalArgumentException> {
        AggregationMetric.DistinctCount(
            AggregationExpression.Field(QueryField("state.customerId")),
            "__wow_customers",
        )
    }
    assertThrows<IllegalArgumentException> {
        AggregationMetric.Percentile(
            AggregationExpression.Field(QueryField("state.amount")),
            95.0,
            "__wow_p95",
        )
    }
}

@Test
fun `percentile should require a finite ratio within exclusive bounds`() {
    listOf(0.0, 100.0, -1.0, 101.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { p ->
        assertThrows<IllegalArgumentException> {
            AggregationMetric.Percentile(
                AggregationExpression.Field(QueryField("state.amount")),
                p,
                "p95",
            )
        }
    }
}

@Test
fun `new metric expressions should count toward expression limits`() {
    assertThrows<IllegalArgumentException> {
        AggregationQuery(
            metrics = listOf(AggregationMetric.DistinctCount(nestedExpression(9), "customers")),
        )
    }
    assertThrows<IllegalArgumentException> {
        AggregationQuery(
            metrics = listOf(AggregationMetric.Percentile(nestedExpression(9), 50.0, "median")),
        )
    }
}

@Test
fun `unknown metric JSON subtype should fail`() {
    val json = """
        {
          "metrics": [{
            "type": "QUANTILE",
            "expression": {"field": "amount"},
            "alias": "q"
          }]
        }
    """.trimIndent()

    assertThrows<InvalidTypeIdException> {
        bareMapper.readValue(json, AggregationQuery::class.java)
    }
}
```

- [ ] **Step 2: 运行确认 RED**

```bash
./gradlew :wow-api:test --tests "me.ahoo.wow.api.query.AggregationQueryTest"
```

预期：编译失败（`AggregationMetric.DistinctCount`/`Percentile`、`AggregationFunction.STDDEV`/`VARIANCE` 不存在）——这是本任务预期的 RED 形态。

- [ ] **Step 3: 实现**

`AggregationQuery.kt`：

1. `@JsonSubTypes`（198-203 行）追加两个条目；`@Schema.oneOf`（204-211 行）追加两个类：

```kotlin
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = QueryProtocol.Polymorphic.TYPE)
@JsonSubTypes(
    JsonSubTypes.Type(AggregationMetric.Count::class, name = "COUNT"),
    JsonSubTypes.Type(AggregationMetric.Numeric::class, name = "NUMERIC"),
    JsonSubTypes.Type(AggregationMetric.Any::class, name = "ANY"),
    JsonSubTypes.Type(AggregationMetric.DistinctCount::class, name = "DISTINCT_COUNT"),
    JsonSubTypes.Type(AggregationMetric.Percentile::class, name = "PERCENTILE"),
)
@Schema(
    oneOf = [
        AggregationMetric.Count::class,
        AggregationMetric.Numeric::class,
        AggregationMetric.Any::class,
        AggregationMetric.DistinctCount::class,
        AggregationMetric.Percentile::class,
    ],
    discriminatorProperty = QueryProtocol.Polymorphic.TYPE,
)
```

2. `sealed interface AggregationMetric` 内、`Any` 之后追加：

```kotlin
data class DistinctCount(
    val expression: AggregationExpression,
    override val alias: String,
) : AggregationMetric {
    init {
        requireAggregationAlias(alias)
    }
}

data class Percentile(
    val expression: AggregationExpression,
    @get:Schema(minimum = "0", exclusiveMinimum = true, maximum = "100", exclusiveMaximum = true)
    val percentile: Double,
    override val alias: String,
) : AggregationMetric {
    init {
        requireAggregationAlias(alias)
        require(percentile.isFinite() && percentile > 0.0 && percentile < 100.0) {
            "percentile must be finite and within (0, 100)."
        }
    }
}
```

3. 枚举改为：

```kotlin
enum class AggregationFunction {
    SUM,
    AVG,
    MIN,
    MAX,
    STDDEV,
    VARIANCE,
}
```

4. `requireValidExpressions`（260 行）把 `filterIsInstance<AggregationMetric.Numeric>().forEach { ... }` 替换为遍历所有携带表达式的指标：

```kotlin
private fun List<AggregationMetric>.requireValidExpressions() {
    val pending = ArrayDeque<PendingExpression>()
    forEach { metric ->
        when (metric) {
            is AggregationMetric.Numeric -> pending.addLast(PendingExpression(metric.expression, 1))
            is AggregationMetric.DistinctCount -> pending.addLast(PendingExpression(metric.expression, 1))
            is AggregationMetric.Percentile -> pending.addLast(PendingExpression(metric.expression, 1))
            is AggregationMetric.Count, is AggregationMetric.Any -> Unit
        }
    }
    // ……其余不变
}
```

- [ ] **Step 4: 运行确认 GREEN（含 wow-api 全量）**

```bash
./gradlew :wow-api:test
```

预期：全绿。注意：此步之后 `wow-query`/`wow-mongo`/`wow-elasticsearch` 的生产代码中 `when (metric)` 分支不再穷尽，会在各自模块编译时报错——属预期，由 Task 3/4/5 修复；不要为通过其他模块编译而在本任务中加 `else ->` 分支。

- [ ] **Step 5: 提交**

```bash
git add wow-api/src/main/kotlin/me/ahoo/wow/api/query/AggregationQuery.kt wow-api/src/test/kotlin/me/ahoo/wow/api/query/AggregationQueryTest.kt
git commit -m "feat(api): add distinct count, stddev, variance and percentile aggregation metrics"
```

---

### Task 2: wow-query — Kotlin DSL 方法

**Files:**
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/AggregationQueryDsl.kt`（118 行 `max(expression, alias)` 之后）
- Test: `wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/AggregationQueryDslTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `AggregationMetric.DistinctCount`、`AggregationMetric.Percentile`、`AggregationFunction.STDDEV`/`VARIANCE`。
- Produces（Task 3/4/5/6 测试直接调用的 DSL）:

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

- [ ] **Step 1: 写失败测试**

`AggregationQueryDslTest.kt` 追加：

```kotlin
@Test
fun `aggregation DSL should map new metric functions`() {
    val query = aggregation {
        terms("status", "status")
        distinctCount("customerId", "customers")
        stddev("amount", "amtStddev")
        variance("amount", "amtVariance")
        percentile("amount", 95.0, "amtP95")
        median("amount", "amtMedian")
    }

    query.metrics.assert().containsExactly(
        AggregationMetric.DistinctCount(
            AggregationExpression.Field(QueryField("customerId")),
            "customers",
        ),
        AggregationMetric.Numeric(
            AggregationFunction.STDDEV,
            AggregationExpression.Field(QueryField("amount")),
            "amtStddev",
        ),
        AggregationMetric.Numeric(
            AggregationFunction.VARIANCE,
            AggregationExpression.Field(QueryField("amount")),
            "amtVariance",
        ),
        AggregationMetric.Percentile(
            AggregationExpression.Field(QueryField("amount")),
            95.0,
            "amtP95",
        ),
        AggregationMetric.Percentile(
            AggregationExpression.Field(QueryField("amount")),
            50.0,
            "amtMedian",
        ),
    )
}
```

- [ ] **Step 2: 运行确认 RED**

```bash
./gradlew :wow-query:test --tests "me.ahoo.wow.query.dsl.AggregationQueryDslTest"
```

预期：编译失败（DSL 方法不存在）。

- [ ] **Step 3: 实现**

`AggregationQueryDsl.kt` 在 `max(expression: AggregationExpression, alias: String)` 之后、`private fun numeric(...)` 之前追加：

```kotlin
fun distinctCount(field: String, alias: String) = distinctCount(field(field), alias)

fun distinctCount(expression: AggregationExpression, alias: String) {
    metrics += AggregationMetric.DistinctCount(expression, alias)
}

fun stddev(field: String, alias: String) = stddev(field(field), alias)

fun stddev(expression: AggregationExpression, alias: String) =
    numeric(AggregationFunction.STDDEV, expression, alias)

fun variance(field: String, alias: String) = variance(field(field), alias)

fun variance(expression: AggregationExpression, alias: String) =
    numeric(AggregationFunction.VARIANCE, expression, alias)

fun percentile(field: String, p: Double, alias: String) = percentile(field(field), p, alias)

fun percentile(expression: AggregationExpression, p: Double, alias: String) {
    metrics += AggregationMetric.Percentile(expression, p, alias)
}

fun median(field: String, alias: String) = median(field(field), alias)

fun median(expression: AggregationExpression, alias: String) = percentile(expression, 50.0, alias)
```

- [ ] **Step 4: 运行确认 GREEN**

```bash
./gradlew :wow-query:test --tests "me.ahoo.wow.query.dsl.AggregationQueryDslTest"
```

- [ ] **Step 5: 提交**

```bash
git add wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/AggregationQueryDsl.kt wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/AggregationQueryDslTest.kt
git commit -m "feat(query): add DSL for distinct count, stddev, variance, percentile and median"
```

---

### Task 3: wow-query — 能力验证

**Files:**
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaValidation.kt`（116-126 行 `field()`；262-311 行 `aggregate()`/`expression()`/`aggregationField()`）
- Test: `wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QuerySchemaValidationTest.kt`（fixtures 在 `QuerySchemaFixtures.kt`：`boundSchemaFixture`/`objectFixture`/`scalarFixture`）

**Interfaces:**
- Consumes: Task 1 AST；Task 2 DSL（测试用）。
- Produces: 验证规则——`DistinctCount` 的 `FIELD` 需 `AGGREGATE_TERMS` **或** `AGGREGATE_NUMERIC`；`Percentile` 与 `Numeric` 同走 `expression()`（`AGGREGATE_NUMERIC`）；二者均经 `aggregationField` 的 protected 检查。

- [ ] **Step 1: 写失败测试**

`QuerySchemaValidationTest.kt` 追加（imports 按文件现有条目补 `QueryCapability`，若缺）：

```kotlin
@Test
fun `distinct count accepts terms or numeric fields and rejects others while percentile follows numeric rules`() {
    val both = boundSchemaFixture(
        objectFixture(
            "customerId" to scalarFixture(),
            "amount" to scalarFixture(QueryValueType.DECIMAL),
        )
    )
    val query = aggregation {
        distinctCount("customerId", "customers")
        distinctCount("amount", "amounts")
        stddev("amount", "stddev")
        variance("amount", "variance")
        percentile("amount", 95.0, "p95")
        median("amount", "median")
    }
    validateQuery(query, both).assert().isSameAs(query)

    val presenceOnly = boundSchemaFixture(
        objectFixture("code" to scalarFixture()),
        fieldCapabilities = setOf(QueryCapability.PRESENCE),
    )
    assertThrows<QuerySchemaValidationException> {
        validateQuery(aggregation { distinctCount("code", "codes") }, presenceOnly)
    }
    assertThrows<QuerySchemaValidationException> {
        validateQuery(aggregation { percentile("code", 95.0, "p") }, presenceOnly)
    }
}
```

同时扩展现有 `masked values stay queryable but public cursor and aggregate admission reject them` 测试（419 行）：在其 `assertThrows<QuerySchemaValidationException>` 块中追加第二个被拒查询：

```kotlin
assertThrows<QuerySchemaValidationException> {
    validateQuery(
        aggregation {
            distinctCount("state.secret", "secrets")
            count("count")
        },
        schema
    )
}
```

- [ ] **Step 2: 运行确认 RED**

```bash
./gradlew :wow-query:test --tests "me.ahoo.wow.query.schema.QuerySchemaValidationTest"
```

预期：`aggregate()` 的 `when (metric)` 编译错误（非穷尽）或新测试行为失败。

- [ ] **Step 3: 实现**

1. `field()` 支持多能力集合。将 116-126 行私有 `field` 重构为：

```kotlin
private fun field(
    name: QueryField,
    capability: QueryCapability,
    parent: QueryField? = null,
): QueryFieldSchema = field(name, setOf(capability), capability.name, parent)

private fun field(
    name: QueryField,
    capabilities: Set<QueryCapability>,
    label: String,
    parent: QueryField?,
): QueryFieldSchema {
    val logical = absoluteLogicalField(name, parent)
    val field = schema.field(logical) ?: throw QuerySchemaValidationException("Unknown logical field [$logical].")
    requireSchema(capabilities.any { field.binding(it) != null }) { "Field [$logical] does not support [$label]." }
    requireSchema(
        field.elementAncestors != null && field.elementAncestors == schema.requiredElementAncestors(parent)
    ) {
        "Field [$logical] requires its declared element scope."
    }
    return field
}
```

2. `aggregate()` 的 metrics 分支（278-286 行）追加：

```kotlin
query.metrics.forEach { metric ->
    when (metric) {
        is AggregationMetric.Count -> Unit
        is AggregationMetric.Any -> requireSchema(
            aggregationField(metric.field, QueryCapability.AGGREGATE_TERMS, parent).value.cardinality == QueryCardinality.SINGLE,
        ) { "ANY requires a single value." }
        is AggregationMetric.Numeric -> expression(metric.expression, parent)
        is AggregationMetric.DistinctCount -> distinctCountExpression(metric.expression, parent)
        is AggregationMetric.Percentile -> expression(metric.expression, parent)
    }
}
```

3. `expression()`（289 行）之后追加：

```kotlin
private fun distinctCountExpression(expression: AggregationExpression, parent: QueryField?) {
    when (expression) {
        is AggregationExpression.Field -> aggregationField(
            expression.field,
            setOf(QueryCapability.AGGREGATE_TERMS, QueryCapability.AGGREGATE_NUMERIC),
            parent,
        )
        is AggregationExpression.Constant -> Unit
        is AggregationExpression.Binary -> {
            expression(expression.left, parent)
            expression(expression.right, parent)
        }
    }
}
```

4. `aggregationField` 增加集合重载（304 行版本不动，紧随其后追加）：

```kotlin
private fun aggregationField(
    name: QueryField,
    capabilities: Set<QueryCapability>,
    parent: QueryField?,
): QueryFieldSchema {
    val field = field(name, capabilities, capabilities.joinToString(" or ") { it.name }, parent)
    requireSchema(!isFieldProtected(schema, field.logicalField, field)) {
        "Protected field [${field.logicalField}] cannot be aggregated."
    }
    return field
}
```

- [ ] **Step 4: 运行确认 GREEN（含 wow-query 全量）**

```bash
./gradlew :wow-query:test
```

- [ ] **Step 5: 提交**

```bash
git add wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaValidation.kt wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QuerySchemaValidationTest.kt
git commit -m "feat(query): validate distinct count and percentile metric capabilities"
```

---

### Task 4: wow-mongo — 编译器与后端结果归一化

**Files:**
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/aggregation/MongoAggregationCompiler.kt`（99-167 行 `group`/`project`/`accumulate`；207-233 行 `toMongoInput`；476 行 `countAlias`）
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoQueryBackend.kt`（180-199 行 `toAggregationResult`/`emptySummary`）
- Test: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompilerTest.kt`（文件底部有顶层 `schema(vararg fields: Pair<QueryField, MongoTestField>)` 与 `field(logicalPath, capability, physicalPath, valueType, semanticType, additionalCapabilities)` 辅助函数可直接复用；编译器构造 `MongoAggregationCompiler(SnapshotFilterCompiler)`）

**Interfaces:**
- Consumes: Task 1 AST。
- Produces:
  - `AggregationFunction.STDDEV` → `$stdDevPop(input)`；`VARIANCE` → `{$pow: [{$stdDevPop: input}, 2]}`
  - `Percentile` → `{$percentile: {input, p: [p/100], method: "approximate"}}` + `__wow_value_count_<alias>` 守卫
  - `DistinctCount` → `$group` 阶段 `{$push: 参与值包装}`，`$project` 阶段 `{$size: {$setUnion: {$filter: {$reduce 扁平化后 null 过滤}}}}`
  - 后端归一化：`DistinctCount` → `toLong()`；`Percentile` → `toFiniteDouble()`；空 summary：`DistinctCount` → `0L`，其余新指标 → `null`

- [ ] **Step 1: 写失败测试**

`MongoAggregationCompilerTest.kt` 追加（新测试类或既有类内，沿用现有 `pipeline`/`toJson` 断言风格；注意 Kotlin 字符串中 `$` 需转义为 `\$`）：

```kotlin
@Test
fun `stddev and variance accumulate population statistics`() {
    val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
        aggregation {
            stddev("state.amount", "stddev")
            variance("state.amount", "variance")
        },
        schema(),
    ).map { it.toBsonDocument() }

    val group = pipeline.first { it.containsKey("\$group") }.getDocument("\$group")
    group.toJson().assert()
        .contains("stdDevPop")
        .contains("\$pow")
        .contains("\"stddev\"")
        .contains("\"variance\"")
}

@Test
fun `percentile accumulates approximate t-digest input with contribution guard`() {
    val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
        aggregation {
            percentile("state.amount", 95.0, "p95")
        },
        schema(),
    ).map { it.toBsonDocument() }

    val group = pipeline.first { it.containsKey("\$group") }.getDocument("\$group")
    group.toJson().assert()
        .contains("\$percentile")
        .contains("{\"p\":[0.95]}")
        .contains("\"method\":\"approximate\"")
        .contains("__wow_value_count_p95")
    val project = pipeline.first { it.containsKey("\$project") }.getDocument("\$project")
    project.toJson().assert().contains("p95")
}

@Test
fun `distinct count pushes wrapped participation and projects set size`() {
    val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
        aggregation {
            distinctCount("state.productId", "products")
        },
        schema(),
    ).map { it.toBsonDocument() }

    val group = pipeline.first { it.containsKey("\$group") }.getDocument("\$group")
    group.toJson().assert()
        .contains("\$push")
        .contains("\$isArray")
    val project = pipeline.first { it.containsKey("\$project") }.getDocument("\$project")
    project.toJson().assert()
        .contains("\$reduce")
        .contains("\$concatArrays")
        .contains("\$setUnion")
        .contains("\$size")
}
```

- [ ] **Step 2: 运行确认 RED**

```bash
./gradlew :wow-mongo:test --tests "me.ahoo.wow.mongo.query.snapshot.*"
```

预期：`MongoAggregationCompiler.kt` 与 `AbstractMongoQueryBackend.kt` 的 `when (metric)` 非穷尽导致编译失败，或测试断言失败。

- [ ] **Step 3: 实现**

`MongoAggregationCompiler.kt`：

1. `group()` 的 metrics 循环（107-129 行）改为：

```kotlin
val accumulators = buildList {
    query.metrics.forEach { metric ->
        when (metric) {
            is AggregationMetric.Count -> add(Accumulators.sum(metric.alias, 1))
            is AggregationMetric.Any -> {
                val field = metric.field.resolve(
                    parent,
                    physicalParent,
                    schema,
                    QueryCapability.AGGREGATE_TERMS,
                )
                add(Accumulators.max(metric.alias, "\$$field"))
            }
            is AggregationMetric.Numeric -> {
                val nullGuarded = metric.function == AggregationFunction.MIN ||
                    metric.function == AggregationFunction.MAX
                val (input, contributes) = numericParticipation(
                    metric.expression,
                    nullGuarded,
                    parent,
                    physicalParent,
                    schema,
                )
                add(metric.function.accumulate(metric.alias, input))
                add(
                    Accumulators.sum(
                        metric.countAlias,
                        Document("\$cond", listOf(contributes, 1, 0)),
                    ),
                )
            }
            is AggregationMetric.Percentile -> {
                val (input, contributes) = numericParticipation(
                    metric.expression,
                    nullGuarded = true,
                    parent,
                    physicalParent,
                    schema,
                )
                add(
                    BsonField(
                        metric.alias,
                        Document(
                            "\$percentile",
                            Document("input", input)
                                .append("p", listOf(metric.percentile / 100.0))
                                .append("method", "approximate"),
                        ),
                    ),
                )
                add(
                    Accumulators.sum(
                        metric.countAlias,
                        Document("\$cond", listOf(contributes, 1, 0)),
                    ),
                )
            }
            is AggregationMetric.DistinctCount -> {
                add(
                    Accumulators.push(
                        metric.alias,
                        distinctCountInput(metric.expression, parent, physicalParent, schema),
                    ),
                )
            }
        }
    }
}
```

2. `project()` 的 metrics 循环（139-157 行）追加分支：

```kotlin
is AggregationMetric.Percentile -> Projections.computed(
    metric.alias,
    Document(
        "\$cond",
        listOf(
            Document("\$eq", listOf("\$${metric.countAlias}", 0)),
            null,
            "\$${metric.alias}",
        ),
    ),
)
is AggregationMetric.DistinctCount -> Projections.computed(
    metric.alias,
    Document(
        "\$size",
        Document(
            "\$setUnion",
            Document(
                "input",
                Document(
                    "\$filter",
                    Document(
                        "input",
                        Document(
                            "\$reduce",
                            Document("input", "\$${metric.alias}")
                                .append("initialValue", emptyList<Any>())
                                .append(
                                    "in",
                                    Document("\$concatArrays", listOf("\$\$value", "\$\$this")),
                                ),
                        ),
                    ).append("cond", Document("\$ne", listOf("\$\$this", null))),
                ),
            ),
        ),
    ),
)
```

3. `accumulate`（162 行）追加：

```kotlin
private fun AggregationFunction.accumulate(field: String, input: Any): BsonField = when (this) {
    AggregationFunction.SUM -> Accumulators.sum(field, input)
    AggregationFunction.AVG -> Accumulators.avg(field, input)
    AggregationFunction.MIN -> Accumulators.min(field, input)
    AggregationFunction.MAX -> Accumulators.max(field, input)
    AggregationFunction.STDDEV -> BsonField(field, Document("\$stdDevPop", input))
    AggregationFunction.VARIANCE -> BsonField(
        field,
        Document("\$pow", listOf(Document("\$stdDevPop", input), 2)),
    )
}
```

4. `toMongoInput`（207 行）重命名并泛化为 `numericParticipation`（`nullGuarded` 取代原 MIN/MAX 特判）：

```kotlin
private fun numericParticipation(
    expression: AggregationExpression,
    nullGuarded: Boolean,
    parent: QueryField?,
    physicalParent: String?,
    schema: QueryModelSchema,
): Pair<Any, Any> {
    if (expression is AggregationExpression.Field) {
        val field = expression.field.resolve(
            parent,
            physicalParent,
            schema,
            QueryCapability.AGGREGATE_NUMERIC,
        )
        val value = numericInput("\$$field")
        val isNumber = Document("\$isNumber", value)
        val input = if (nullGuarded) Document("\$cond", listOf(isNumber, value, null)) else value
        return input to isNumber
    }
    val input = expression.toMongoExpression(parent, physicalParent, schema)
    return input to Document("\$ne", listOf(input, null))
}
```

5. 追加 `distinctCountInput`（放在 `numericParticipation` 之后）：

```kotlin
private fun distinctCountInput(
    expression: AggregationExpression,
    parent: QueryField?,
    physicalParent: String?,
    schema: QueryModelSchema,
): Any {
    val value: Any = if (expression is AggregationExpression.Field) {
        val logicalField = parent?.append(expression.field) ?: expression.field
        val capability = when {
            schema.field(logicalField)?.binding(QueryCapability.AGGREGATE_TERMS) != null ->
                QueryCapability.AGGREGATE_TERMS
            else -> QueryCapability.AGGREGATE_NUMERIC
        }
        "\$${expression.field.resolve(parent, physicalParent, schema, capability)}"
    } else {
        expression.toMongoExpression(parent, physicalParent, schema)
    }
    return Document(
        "\$cond",
        listOf(
            Document("\$isArray", value),
            value,
            Document(
                "\$cond",
                listOf(
                    Document("\$eq", listOf(value, null)),
                    emptyList<Any>(),
                    listOf(value),
                ),
            ),
        ),
    )
}
```

6. `countAlias`（476 行）接收者从 `AggregationMetric.Numeric` 放宽为 `AggregationMetric`：

```kotlin
private val AggregationMetric.countAlias: String
    get() = "__wow_value_count_$alias"
```

`AbstractMongoQueryBackend.kt`：

7. `toAggregationResult`（185-189 行）追加：

```kotlin
is AggregationMetric.Percentile -> get(metric.alias).toFiniteDouble(metric.alias)
is AggregationMetric.DistinctCount -> (get(metric.alias) as Number).toLong()
```

8. `emptySummary`（197 行）改为：

```kotlin
private fun AggregationQuery.emptySummary(): Document = metrics.associateTo(Document()) { metric ->
    metric.alias to when (metric) {
        is AggregationMetric.Count, is AggregationMetric.DistinctCount -> 0L
        else -> null
    }
}
```

- [ ] **Step 4: 运行确认 GREEN**

```bash
./gradlew :wow-mongo:test
```

- [ ] **Step 5: 提交**

```bash
git add wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/aggregation/MongoAggregationCompiler.kt wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoQueryBackend.kt wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompilerTest.kt
git commit -m "feat(mongo): compile distinct count, stddev, variance and percentile metrics"
```

---

### Task 5: wow-elasticsearch — 编译计划与分页器

**Files:**
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/aggregation/ElasticsearchAggregationCompiler.kt`（62-80 行 `ElasticsearchAggregationMetric`；253-284 行 `toPlan`）
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/aggregation/ElasticsearchAggregationPager.kt`（174-204 行 `metricAggregations`；255-286 行 `value`/`numericValue`）
- Test: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationCompilerTest.kt`（类内已有 `definition`/`schema`（`ElasticsearchQuerySchemaAdapter.bind`）与 `scalar`/`text` fixtures）

**Interfaces:**
- Consumes: Task 1 AST。
- Produces（内部计划类型，Pager 消费）:

```kotlin
data class DistinctCount(override val alias: String, val field: String) : ElasticsearchAggregationMetric
data class Percentile(
    override val alias: String,
    val field: String,
    val percentile: Double,
) : ElasticsearchAggregationMetric {
    val valueCountAlias: String get() = "__wow_value_count_$alias"
}
```

- Pager 映射：`DistinctCount` → `cardinality`；`Percentile` → `percentiles(p)` + `valueCount` 守卫；`STDDEV`/`VARIANCE` → `extended_stats` 取 `stdDeviationPopulation()`/`variancePopulation()`（若客户端版本无该访问器，回退 `stdDeviation()`/`variance()`，两者均为总体口径，编译期即可确认）。

- [ ] **Step 1: 写失败测试**

`ElasticsearchAggregationCompilerTest.kt`：先给类级 `definition` 与 `TypeMapping` 增加 `customerId` keyword 字段（`"customerId" to text` + `.properties("customerId") { it.text { it.fields("keyword") { it.keyword { it } } } }`，与 `name` 同款），再追加：

```kotlin
@Test
fun `plan should map distinct count and percentile metrics`() {
    val plan = ElasticsearchAggregationCompiler(SnapshotFilterCompiler).compile(
        aggregation {
            distinctCount("customerId", "customers")
            percentile("amount", 95.0, "p95")
            stddev("amount", "stddev")
            variance("amount", "variance")
        },
        schema,
    )

    plan.metrics.assert().containsExactly(
        ElasticsearchAggregationMetric.DistinctCount("customers", "customerId.keyword"),
        ElasticsearchAggregationMetric.Percentile("p95", "amount", 95.0),
        ElasticsearchAggregationMetric.Numeric("stddev", AggregationFunction.STDDEV, "amount"),
        ElasticsearchAggregationMetric.Numeric("variance", AggregationFunction.VARIANCE, "amount"),
    )
}
```

（`customerId.keyword`/`amount` 为该测试 schema 映射的实际物理名；若 RED 输出显示其他物理名，以映射定义为准修正断言——映射由同文件 `TypeMapping` 显式给出，无歧义。）

- [ ] **Step 2: 运行确认 RED**

```bash
./gradlew :wow-elasticsearch:test --tests "me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchAggregationCompilerTest"
```

预期：编译失败（计划类型不存在）。

- [ ] **Step 3: 实现**

1. `ElasticsearchAggregationMetric`（62-80 行）追加两个 data class（见 Interfaces）。

2. `toPlan`（253 行）追加分支：

```kotlin
is AggregationMetric.DistinctCount -> {
    val metricField = (expression as? AggregationExpression.Field)?.field?.let { field ->
        val logicalField = parent?.append(field) ?: field
        val capability = when {
            schema.field(logicalField)?.binding(QueryCapability.AGGREGATE_TERMS) != null ->
                QueryCapability.AGGREGATE_TERMS
            else -> QueryCapability.AGGREGATE_NUMERIC
        }
        field.resolve(parent, physicalParent, schema, capability)
    } ?: "__wow_expression_$index".also { runtimeFieldName ->
        runtimeMappings[runtimeFieldName] = RuntimeExpressionCompiler(
            parent,
            physicalParent,
            schema,
        ).compile(expression)
    }
    ElasticsearchAggregationMetric.DistinctCount(alias, metricField)
}
is AggregationMetric.Percentile -> {
    val metricExpression = expression
    val scalarField = (metricExpression as? AggregationExpression.Field)?.field?.takeIf { field ->
        val logicalField = parent?.append(field) ?: field
        schema.field(logicalField)?.value?.cardinality == QueryCardinality.SINGLE
    }
    val metricField = if (scalarField != null) {
        scalarField.resolve(parent, physicalParent, schema, QueryCapability.AGGREGATE_NUMERIC)
    } else {
        "__wow_expression_$index".also { runtimeFieldName ->
            runtimeMappings[runtimeFieldName] = RuntimeExpressionCompiler(
                parent,
                physicalParent,
                schema,
            ).compile(metricExpression)
        }
    }
    ElasticsearchAggregationMetric.Percentile(alias, metricField, percentile)
}
```

3. `ElasticsearchAggregationPager.metricAggregations()`（174 行）`Numeric` 分支的 `when (metric.function)` 追加两行，并新增两个分支：

```kotlin
is ElasticsearchAggregationMetric.Numeric -> {
    put(
        metric.alias,
        Aggregation.of { builder ->
            when (metric.function) {
                AggregationFunction.SUM -> builder.sum { it.field(metric.field) }
                AggregationFunction.AVG -> builder.avg { it.field(metric.field) }
                AggregationFunction.MIN -> builder.min { it.field(metric.field) }
                AggregationFunction.MAX -> builder.max { it.field(metric.field) }
                AggregationFunction.STDDEV, AggregationFunction.VARIANCE ->
                    builder.extendedStats { it.field(metric.field) }
            }
        },
    )
    put(
        metric.valueCountAlias,
        Aggregation.of { builder -> builder.valueCount { it.field(metric.field) } },
    )
}
is ElasticsearchAggregationMetric.DistinctCount -> put(
    metric.alias,
    Aggregation.of { builder -> builder.cardinality { it.field(metric.field) } },
)
is ElasticsearchAggregationMetric.Percentile -> {
    put(
        metric.alias,
        Aggregation.of { builder ->
            builder.percentiles { it.field(metric.field).percents(listOf(metric.percentile)) }
        },
    )
    put(
        metric.valueCountAlias,
        Aggregation.of { builder -> builder.valueCount { it.field(metric.field) } },
    )
}
```

4. `value()`（255 行）追加；`numericValue()`（274 行）的 `when (function)` 追加：

```kotlin
// value():
is ElasticsearchAggregationMetric.DistinctCount -> aggregations.getValue(alias).cardinality().value()
is ElasticsearchAggregationMetric.Percentile -> percentileValue(aggregations)

// numericValue() 内 when 追加：
AggregationFunction.STDDEV -> aggregations.getValue(alias).extendedStats().stdDeviationPopulation()
AggregationFunction.VARIANCE -> aggregations.getValue(alias).extendedStats().variancePopulation()

// 新增私有函数：
private fun ElasticsearchAggregationMetric.Percentile.percentileValue(
    aggregations: Map<String, Aggregate>,
): Double? {
    if (aggregations.getValue(valueCountAlias).valueCount().value() == 0.0) return null
    val percentile = aggregations.getValue(alias).percentiles()
        .percentiles()
        .firstOrNull { it.key().toDoubleOrNull() == percentile }
        ?: error("Aggregation metric [$alias] is missing percentile [$percentile].")
    val value = percentile.value()
    require(value.isFinite()) { "Aggregation metric [$alias] must be finite." }
    return value
}
```

（命名冲突说明：`Percentile` 计划类型的属性名 `percentile` 与函数内局部变量同名时，按需写 `this.percentile` 消歧。）

- [ ] **Step 4: 运行确认 GREEN**

```bash
./gradlew :wow-elasticsearch:test
```

- [ ] **Step 5: 提交**

```bash
git add wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/aggregation/ElasticsearchAggregationCompiler.kt wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/aggregation/ElasticsearchAggregationPager.kt wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationCompilerTest.kt
git commit -m "feat(elasticsearch): compile distinct count, stddev, variance and percentile metrics"
```

---

### Task 6: TCK — Mongo 7.0 基线与跨后端契约场景

**Files:**
- Modify: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/container/ContainerImages.kt:17`（`"mongo:6.0.6"` → `"mongo:7.0"`）
- Modify: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/SnapshotQueryBackendSpec.kt`（874 行取消测试之前插入新场景；fixture 复用 `aggregationStates()`/`MockStateAggregate`/`MockOrder`/`MockLine`）
- Modify: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/EventStreamQueryBackendSpec.kt`（469 行 `aggregateEmptySummary` 之后）

**Interfaces:**
- Consumes: Task 2 DSL；Task 4/5 后端实现。
- Produces: 跨后端语义契约（设计文档「验证设计」9 项场景）。

**fixture 事实（来自 `aggregationStates()`）**：全部 6 行 lines（两状态）：products `alpha,beta,gamma,alpha,beta,delta`，amounts `10,20,null,30,20,50`；仅 PAID（orders filter）5 行：amounts `[10,20,30,20,50]` 排序后 `[10,20,20,30,50]`；samples 数组 `[7.0]` 与 `[3.0,4.0]`。

- [ ] **Step 1: 升级容器基线并提交（独立小提交）**

```kotlin
object ContainerImages {
    const val MONGO = "mongo:7.0"
    ...
}
```

```bash
git add test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/container/ContainerImages.kt
git commit -m "feat(tck): bump MongoDB test container to 7.0 for native \$percentile"
```

- [ ] **Step 2: Snapshot 契约新场景（写测试）**

```kotlin
@Test
fun `aggregation should count distinct values excluding null`() {
    saveAggregationStates(*aggregationStates().toTypedArray())

    aggregation {
        filter { deletion(DeletionState.ACTIVE) }
        expand("state.orders")
        expand("lines")
        distinctCount("productId", "products")
        distinctCount("amount", "amounts")
    }.query(queryBackendBinding)
        .test()
        .assertNext {
            it.assertWireEquals(mapOf("products" to 4L, "amounts" to 4L))
        }.verifyComplete()
}

@Test
fun `aggregation should count distinct array elements`() {
    saveAggregationStates(*aggregationStates().toTypedArray())

    aggregation {
        filter { deletion(DeletionState.ACTIVE) }
        expand("state.orders")
        expand("lines")
        distinctCount("samples", "samples")
    }.query(queryBackendBinding)
        .test()
        .assertNext { it.assertWireEquals(mapOf("samples" to 3L)) }
        .verifyComplete()
}

@Test
fun `aggregation should calculate exact population stddev and variance`() {
    saveAggregationStates(
        MockStateAggregate(
            id = "stddev-a",
            orders = listOf(
                MockOrder(
                    status = "PAID",
                    lines = listOf(
                        MockLine(
                            productId = "s1",
                            quantity = 1,
                            amount = 10.0,
                            createdAt = Instant.parse("2026-01-01T10:00:00Z"),
                            discounts = emptyList(),
                        ),
                    ),
                ),
            ),
        ),
        MockStateAggregate(
            id = "stddev-b",
            orders = listOf(
                MockOrder(
                    status = "PAID",
                    lines = listOf(
                        MockLine(
                            productId = "s2",
                            quantity = 1,
                            amount = 20.0,
                            createdAt = Instant.parse("2026-01-01T10:00:00Z"),
                            discounts = emptyList(),
                        ),
                    ),
                ),
            ),
        ),
    )

    aggregation {
        filter { deletion(DeletionState.ACTIVE) }
        expand("state.orders") { "status" eq "PAID" }
        expand("lines")
        count("count")
        stddev("amount", "stddev")
        variance("amount", "variance")
    }.query(queryBackendBinding)
        .test()
        .assertNext {
            it.assertWireEquals(mapOf("count" to 2L, "stddev" to 5.0, "variance" to 25.0))
        }.verifyComplete()
}

@Test
fun `aggregation should calculate percentile metrics within rank bounds`() {
    saveAggregationStates(*aggregationStates().toTypedArray())

    aggregation {
        filter { deletion(DeletionState.ACTIVE) }
        expand("state.orders") { "status" eq "PAID" }
        expand("lines")
        median("amount", "median")
        percentile("amount", 95.0, "p95")
    }.query(queryBackendBinding)
        .test()
        .assertNext { row ->
            // PAID amounts 排序后 [10,20,20,30,50]：(n-1)*0.5=2 → [20,20]；(n-1)*0.95=3.8 → [30,50]
            assertPercentileWithinRankBounds(row.path("median").doubleValue(), listOf(10.0, 20.0, 20.0, 30.0, 50.0), 50.0)
            assertPercentileWithinRankBounds(row.path("p95").doubleValue(), listOf(10.0, 20.0, 20.0, 30.0, 50.0), 95.0)
        }.verifyComplete()
}

@Test
fun `aggregation should return null or zero when no value contributes`() {
    saveAggregationStates(*aggregationStates().toTypedArray())

    aggregation {
        filter { deletion(DeletionState.ACTIVE) }
        expand("state.orders") { "status" eq "CANCELLED" }
        expand("lines") { "productId" eq "gamma" }
        count("count")
        stddev("amount", "stddev")
        variance("amount", "variance")
        median("amount", "median")
        distinctCount("amount", "amounts")
    }.query(queryBackendBinding)
        .test()
        .assertNext {
            it.assertWireEquals(
                mapOf(
                    "count" to 1L,
                    "stddev" to null,
                    "variance" to null,
                    "median" to null,
                    "amounts" to 0L,
                ),
            )
        }.verifyComplete()
}

@Test
fun `aggregation should return zero distinct count in an empty summary`() {
    aggregation {
        filter {
            deletion(DeletionState.ACTIVE)
            aggregateId("missing")
        }
        count("count")
        distinctCount("version", "versions")
        percentile("version", 50.0, "median")
    }.query(queryBackendBinding)
        .test()
        .assertNext {
            it.assertWireEquals(mapOf("count" to 0L, "versions" to 0L, "median" to null))
        }.verifyComplete()
}

@Test
fun `aggregation should sort groups by distinct count`() {
    saveAggregationStates(*aggregationStates().toTypedArray())

    aggregation {
        filter { deletion(DeletionState.ACTIVE) }
        expand("state.orders") { "status" eq "PAID" }
        expand("lines")
        terms("productId", "product")
        distinctCount("amount", "amounts")
        stddev("amount", "amtStddev")
        sort { "amounts".desc() }
    }.query(queryBackendBinding)
        .collectList()
        .test()
        .assertNext { rows ->
            // 各组参与值：alpha {10,30}、beta {20,20}、delta {50}（单值组）
            // 总体方差/标准差：alpha 100.0/10.0、beta 0.0/0.0、delta 0.0/0.0（单值组 STDDEV=0）
            rows.map { row ->
                listOf(
                    row.path("product").textValue(),
                    row.path("amounts").longValue(),
                    row.path("amtStddev").doubleValue(),
                )
            }.assert().containsExactly(
                listOf("alpha", 2L, 10.0), // 与 beta/delta 并列时按 product ASC 稳定排序在前
                listOf("beta", 1L, 0.0),
                listOf("delta", 1L, 0.0),
            )
        }.verifyComplete()
}

@Test
fun `aggregation should apply arithmetic expressions to new metrics`() {
    saveAggregationStates(*aggregationStates().toTypedArray())

    aggregation {
        filter { deletion(DeletionState.ACTIVE) }
        expand("state.orders") { "status" eq "PAID" }
        expand("lines")
        distinctCount(field("amount") + constant(0.0), "amounts")
        median(field("amount") * constant(1.0), "medianAmount")
    }.query(queryBackendBinding)
        .test()
        .assertNext { row ->
            row.path("amounts").longValue().assert().isEqualTo(4L)
            assertPercentileWithinRankBounds(
                row.path("medianAmount").doubleValue(),
                listOf(10.0, 20.0, 20.0, 30.0, 50.0),
                50.0,
            )
        }.verifyComplete()
}
```

私有辅助（放在 `saveAggregationStates` 附近）：

```kotlin
private fun assertPercentileWithinRankBounds(value: Double, sortedValues: List<Double>, p: Double) {
    val rank = (sortedValues.size - 1) * p / 100.0
    val lower = sortedValues[rank.toInt()]
    val upper = sortedValues[ceil(rank).toInt()]
    value.assert().isGreaterThanOrEqualTo(lower)
    value.assert().isLessThanOrEqualTo(upper)
}
```

（`ceil` 来自 `kotlin.math.ceil`，按文件 import 惯例添加。）

- [ ] **Step 3: EventStream 契约场景**

`EventStreamQueryBackendSpec.kt` 在 `aggregateEmptySummary` 后追加：

```kotlin
@Test
fun aggregateDistinctEventNames() {
    val tenantId = generateGlobalId()
    eventStore.append(generateEventStream(namedAggregate.aggregateId(tenantId = tenantId))).block()

    aggregation {
        filter { tenantId(tenantId) }
        expand("body")
        count("count")
        distinctCount("name", "names")
    }.query(queryBackendBinding)
        .test()
        .assertNext { row ->
            row.path("count").longValue().assert().isEqualTo(10L)
            row.path("names").longValue().assert().isEqualTo(2L)
        }.verifyComplete()
}
```

（`aggregateEventsByName` 已验证该 fixture 产生 2 个事件名、10 条事件。）

- [ ] **Step 4: 跑双后端集成测试确认 GREEN（需 Docker）**

```bash
./gradlew :wow-mongo:integrationTest --stacktrace
./gradlew :wow-elasticsearch:integrationTest --stacktrace
```

预期：新增场景与全部存量场景通过。若 Mongo 侧 `$percentile`/`$stdDevPop` 行为与断言不符（如 null 参与语义差异），以设计文档语义规范为准修正**实现**（守卫条件），而不是放宽断言。

- [ ] **Step 5: 提交**

```bash
git add test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/SnapshotQueryBackendSpec.kt test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/EventStreamQueryBackendSpec.kt
git commit -m "test(tck): add cross-backend contracts for new aggregation metrics"
```

---

### Task 7: 文档更新（中英）

**Files:**
- Modify: `documentation/docs/zh/guide/query/aggregation-query.md`（55-66 行 Metric 表；67-82 行数值参与值与精度；83-88 行算术表达式）
- Modify: `documentation/docs/en/guide/query/aggregation-query.md`（英文镜像，逐节同步）
- Modify: `documentation/docs/zh/guide/query/snapshot-aggregation.md` 与 `documentation/docs/en/guide/query/snapshot-aggregation.md`
- Modify: `documentation/docs/zh/guide/query/event-stream-aggregation.md` 与 `documentation/docs/en/guide/query/event-stream-aggregation.md`

**Interfaces:**
- Consumes: 设计文档「语义规范」「文档更新」两节。

- [ ] **Step 1: zh `aggregation-query.md`**

1. Metric 表（55-66 行）替换为：

```markdown
| 类型 | 形状 |
| --- | --- |
| `COUNT` | 统计当前作用域的记录数 |
| `NUMERIC` | 对 Expression 使用 `SUM`、`AVG`、`MIN`、`MAX`、`STDDEV` 或 `VARIANCE` |
| `DISTINCT_COUNT` | 统计 Expression 非空参与值的去重个数，结果为整数；空集为 `0` |
| `PERCENTILE` | 对数值 Expression 计算 `PERCENTILE(p)`，`0 < p < 100`；DSL 的 `median` 等价 `p=50` |
| `ANY` | 选择一个字段值 |
```

2. 「数值参与值与精度」小节末尾追加一段：

```markdown
`STDDEV` 与 `VARIANCE` 为总体口径（population），与 `SUM`/`AVG` 使用相同的数值参与值规则，无有效贡献时为 `null`，单个贡献值的结果为 `0`。`PERCENTILE` 同样遵循该参与值规则，无有效贡献时为 `null`；MongoDB 与 Elasticsearch 均使用 t-digest 近似算法，结果落在排序后参与值的秩区间内（线性插值约定 `(n-1)·p`），不承诺逐位一致。`DISTINCT_COUNT` 的参与规则与 `NUMERIC` 不同：`FIELD` 引用的数组字段按元素逐个参与去重（不必先 Elements 展开），null/缺失不参与；`CONSTANT`/`BINARY` 表达式仍按每条记录至多一个值参与。Elasticsearch `cardinality` 在精度阈值内近似精确；MongoDB 按参与值集合精确计数。

**版本要求**：`PERCENTILE` 在 MongoDB 后端需要服务端 7.0+（`$percentile` 算子）；其余新指标无额外版本要求。旧版本服务端会返回其原生错误。
```

3. 「算术与时间表达式」中 `sum`、`avg`、`min`、`max` 的 DSL 列表补 `stddev`、`variance`、`percentile`、`median`、`distinctCount`。

- [ ] **Step 2: en 镜像同步**（同内容英文表述，保持两文件结构一致）

- [ ] **Step 3: `snapshot-aggregation.md` 追加场景（zh/en）**

```markdown
### 场景 N：去重客户数与 P95 金额

`kotlin
aggregation {
    filter { deletion(DeletionState.ACTIVE) }
    terms("state.status", "status")
    distinctCount("state.customerId", "customers")
    percentile("state.totalAmount", 95.0, "p95Amount")
    stddev("state.totalAmount", "amountStddev")
    sort { "customers".desc() }
}
`
```

（代码块用三反引号；`N` 取该文件现有场景编号的下一个。）

- [ ] **Step 4: `event-stream-aggregation.md` 追加场景（zh/en）**

```markdown
### 场景 N：事件名去重计数

`kotlin
aggregation {
    expand("body")
    terms("name", "eventName")
    count("count")
    distinctCount("aggregateId", "aggregates")
}
`
```

- [ ] **Step 5: 验证文档构建并提交**

```bash
cd documentation && pnpm docs:build
```

```bash
git add documentation/docs/zh/guide/query/ documentation/docs/en/guide/query/
git commit -m "docs(query): document new aggregation metric functions"
```

---

### Task 8: 全量回归与静态检查

**Files:** 无新增（验证任务）。

- [ ] **Step 1: 模块级 check**

```bash
./gradlew :wow-api:check :wow-query:check :wow-mongo:check :wow-elasticsearch:check
```

- [ ] **Step 2: 集成测试（Docker）**

```bash
./gradlew :wow-mongo:integrationTest :wow-elasticsearch:integrationTest --stacktrace
```

- [ ] **Step 3: 仓库级本地与契约测试**

```bash
./gradlew allLocalTest allContractTest
```

- [ ] **Step 4: Detekt**

```bash
./gradlew detekt
```

如有报告项，按仓库惯例 `./gradlew detekt --auto-correct` 或手动修正；不得为通过 Detekt 改动生产语义。

- [ ] **Step 5: 收尾确认**

`git log --oneline` 应显示本计划 8 个提交（1 容器基线 + 6 功能/文档 + 计划自身如有）；`git status` 干净（不含 `.mimosa/` 等本地产物）。把各层测试结果（通过数、失败数）如实汇报。

---

## 计划自审记录

1. **规格覆盖**：设计文档「API 契约」（Task 1）、「能力验证」（Task 3）、「MongoDB 实现」（Task 4）、「Elasticsearch 实现」（Task 5）、「Kotlin DSL」（Task 2）、「验证设计」9 项场景（Task 6：①字符串+null 排除+空集 0=场景1/5/6 ②Top-N 排序=场景7 ③精确断言=场景3 ④秩区间=场景4 ⑤空参与值=场景5 ⑥单值组 STDDEV=0=场景7 delta 组（单值 {50} → 0.0）⑦嵌套 elements=场景1/3/4/5/7/8 全部 ⑧算术表达式=场景8 ⑨masked 拒绝与非法参数=Task 1 测试 + Task 3 masked 扩展）、「文档更新」（Task 7）、「完成标准」回归（Task 8）。覆盖完整。
2. **占位符扫描**：无 TBD/TODO/占位代码块；每个代码步骤均为可直接落盘的最终版本。
3. **类型一致性**：`AggregationMetric.DistinctCount(expression, alias)`、`Percentile(expression, percentile, alias)`、`AggregationFunction.STDDEV/VARIANCE`、`countAlias` 接收者 `AggregationMetric`、ES 计划类型 `DistinctCount(alias, field)`/`Percentile(alias, field, percentile)` 在 Task 1/4/5/6 间一致。
