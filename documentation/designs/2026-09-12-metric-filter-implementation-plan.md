# Phase 1 实施计划：指标级过滤（漏斗）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 `AggregationMetric` 全部子类型增加记录级 `filter` 字段（漏斗/状态对比：同一分组内按不同条件分别计数/聚合），MongoDB 与 Elasticsearch 双后端实现，含 DSL、验证、HttpQueryGuard、TCK 契约与中英文档。

**Architecture:** 纯增量字段（默认 `MatchAllFilter`、`NON_DEFAULT` 序列化省略）——无过滤的 JSON 与现状字节兼容；不新增 sealed 子类型，全链路 `when` 不受穷尽性冲击，各模块任务独立。语义：当前作用域（根或最内层 element）内不匹配的记录对该指标零贡献。

**Tech Stack:** Kotlin 2.x、JUnit 5、fluent-assert、Reactor、MongoDB Java Driver、elasticsearch-java 9.x、Testcontainers（mongo:7.0）。

**Spec:** [2026-09-12-aggregation-reporting-epic-design.md](2026-09-12-aggregation-reporting-epic-design.md)「Phase 1」节（语义、验证、双后端、TCK 场景以该文档为准）。

## Global Constraints

- 基线：`main` `429970431`；不 bump 版本号。
- wire 兼容纯增量：`filter` 为各子类型**末位**字段（位置构造兼容）、默认 `MatchAllFilter`、`@get:JsonInclude(NON_DEFAULT)` 省略——无过滤 JSON 字节不变。
- 不为存储端低版本做降级/门控；本阶段无新版本要求。
- 每个实现任务严格 TDD：先写失败测试、亲见 RED（失败在预期点）、再实现、GREEN。
- 测试断言用 fluent-assert `.assert()`；核心路径 Reactor 非阻塞。
- 不修改 CI workflow；OpenAPI 快照再生成属纯增量对齐（沿用 `-Dwow.snapshot.update=true` 机制）。
- 提交信息 conventional commits（scope：`api`/`query`/`mongo`/`elasticsearch`/`webflux`/`tck`/`openapi`/`docs`）。
- 设计语义锚点：`COUNT+filter` 空匹配 → `0`；数值/`Any`/`Percentile` → `null`；`DistinctCount` → `0`（既有空集语义）。metric filter 不属昂贵算子；其值数并入 `maxFilterValues`。

---

### Task 1: wow-api — 五个子类型的 filter 字段

**Files:**
- Modify: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/AggregationQuery.kt`（216-267 行区域的五个 data class）
- Test: `wow-api/src/test/kotlin/me/ahoo/wow/api/query/AggregationQueryTest.kt`

**Interfaces:**
- Produces（后续任务依赖）：每个子类型追加 `val filter: FilterExpression = MatchAllFilter`（**末位**，`Any` 的在 `alias` 之后、其余在现有参数之后）；JSON 属性名 `filter`，默认省略。`Count(alias, filter)` 与既有 `Count(alias)` 位置构造并存。

- [ ] **Step 1: 写失败测试**（追加到 AggregationQueryTest）

```kotlin
@Test
fun `metric filters should round trip and omit defaults`() {
    val json = """
        {
          "metrics": [
            {"type": "COUNT", "alias": "total"},
            {"type": "COUNT", "filter": {"op": "EQ", "field": "status", "value": "PAID"}, "alias": "paid"},
            {"type": "NUMERIC", "function": "SUM", "expression": {"field": "amount"},
             "filter": {"op": "EQ", "field": "status", "value": "PAID"}, "alias": "paidAmount"}
          ]
        }
    """.trimIndent()

    val query = configuredMapper.readValue(json, AggregationQuery::class.java)
    query.metrics.filterIsInstance<AggregationMetric.Count>().assert().hasSize(2)
    query.metrics[1].filter.assert().isInstanceOf(EqualFilter::class.java)
    query.metrics.filterIsInstance<AggregationMetric.Numeric>().single().filter.assert()
        .isInstanceOf(EqualFilter::class.java)
    query.metrics[0].filter.assert().isEqualTo(MatchAllFilter)
    val wire = configuredMapper.writeValueAsString(query)
    wire.assert()
        .contains("\"type\":\"COUNT\",\"alias\":\"total\"")
        .contains("\"filter\":{\"op\":\"EQ\"")
}
```

注意：**不在**子类型 init 中校验 filter 内容——根级过滤器（如 `TenantIdFilter`）在根作用域的 metric filter 上是合法的，合法性取决于查询上下文，属 `QuerySchemaValidation` 职责（Task 3 经由 `filter(metric.filter, parent)` 的既有作用域校验强制执行）。

- [ ] **Step 2: 运行确认 RED**

```bash
./gradlew :wow-api:test --tests "me.ahoo.wow.api.query.AggregationQueryTest"
```

预期：编译失败——`Count` 无第二参数、`filter` 属性不存在（失败点即预期点）。

- [ ] **Step 3: 实现**

五个子类型追加末位字段（`@get:JsonInclude(JsonInclude.Include.NON_DEFAULT)`，import 已在文件头）。示例（其余同款，无 init 校验——见 Step 1 注意）：

```kotlin
data class Count(
    override val alias: String,
    @get:JsonInclude(JsonInclude.Include.NON_DEFAULT)
    val filter: FilterExpression = MatchAllFilter,
) : AggregationMetric {
    init {
        requireAggregationAlias(alias)
    }
}
```

- [ ] **Step 4: GREEN**：`./gradlew :wow-api:test` 全绿（本任务后其他模块不受影响——纯增量字段，模块仍编译）。

- [ ] **Step 5: 提交** `feat(api): add record-level filter to aggregation metrics`

---

### Task 2: wow-query — DSL 过滤重载

**Files:**
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/AggregationQueryDsl.kt`
- Test: `wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/AggregationQueryDslTest.kt`

**Interfaces:**
- Consumes: Task 1 字段。
- Produces: 既有方法各加一个尾 lambda 过滤重载（`init: FilterDsl.() -> Unit`，内部 `me.ahoo.wow.query.dsl.filter(init)` 转换）：`count(alias, init)`、`sum/avg/min/max/stddev/variance(field, alias, init)` 与 `(expression, alias, init)`、`percentile/median(field, p, alias, init)` 与 `(expression, p, alias, init)`、`distinctCount(field, alias, init)` 与 `(expression, alias, init)`、`any(field, alias, init)`。无过滤重载签名不变。

- [ ] **Step 1: 写失败测试**

```kotlin
@Test
fun `aggregation DSL should apply metric filters`() {
    val query = aggregation {
        terms("status", "status")
        count("paid") { "status" eq "PAID" }
        sum("amount", "paidAmount") { "status" eq "PAID" }
        distinctCount(field("customerId"), "customers") { "amount" gt 0 }
        percentile("amount", 95.0, "p95") { "status" eq "PAID" }
    }

    query.metrics.filterIsInstance<AggregationMetric.Count>().single().filter.assert()
        .isInstanceOf(EqualFilter::class.java)
    query.metrics.filterIsInstance<AggregationMetric.Numeric>().single().filter.assert()
        .isInstanceOf(EqualFilter::class.java)
    query.metrics.filterIsInstance<AggregationMetric.DistinctCount>().single().filter.assert()
        .isInstanceOf(GreaterThanFilter::class.java)
    query.metrics.filterIsInstance<AggregationMetric.Percentile>().single().filter.assert()
        .isInstanceOf(EqualFilter::class.java)
}
```

- [ ] **Step 2: RED**：`./gradlew :wow-query:test --tests "me.ahoo.wow.query.dsl.AggregationQueryDslTest"`——编译失败：重载不存在。

- [ ] **Step 3: 实现**：每个方法追加重载，形如：

```kotlin
fun count(alias: String, init: FilterDsl.() -> Unit) {
    metrics += AggregationMetric.Count(alias, me.ahoo.wow.query.dsl.filter(init))
}

fun sum(field: String, alias: String, init: FilterDsl.() -> Unit) = sum(field(field), alias, init)

fun sum(expression: AggregationExpression, alias: String, init: FilterDsl.() -> Unit) {
    metrics += AggregationMetric.Numeric(AggregationFunction.SUM, expression, me.ahoo.wow.query.dsl.filter(init), alias)
}
```

（全部 21 个重载同款展开：count×1、sum/avg/min/max/stddev/variance×2、percentile×2、median×2、distinctCount×2、any×2；字段重载委托表达式重载。）

- [ ] **Step 4: GREEN**：`./gradlew :wow-query:test`
- [ ] **Step 5: 提交** `feat(query): add metric filter DSL overloads`

---

### Task 3: wow-query — 验证与 HttpQueryGuard

**Files:**
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaValidation.kt`（`aggregate()` metrics 循环）
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/query/HttpQueryGuard.kt`（110 行 `validateFilters` 列表）
- Test: `wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QuerySchemaValidationTest.kt`、`wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/query/HttpQueryGuardTest.kt`

**Interfaces:**
- Produces: `aggregate()` 在 metrics 循环前统一执行 `if (metric.filter !== MatchAllFilter) filter(metric.filter, parent)`（作用域=当前 parent，与 element.filter 同一校验路径）；guard 将非默认 metric filter 并入 `validateFilters` 列表（节点数与值数上限随之生效）。

- [ ] **Step 1: 写失败测试**

QuerySchemaValidationTest 追加：

```kotlin
@Test
fun `metric filters follow the current scope for root and element contexts`() {
    val schema = boundSchemaFixture(
        objectFixture(
            "tenantId" to scalarFixture(),
            "orders" to arrayFixture(
                objectFixture("status" to scalarFixture(), "amount" to scalarFixture(QueryValueType.DECIMAL))
            )
        )
    )
    // 根作用域：metric filter 可用根级过滤器（tenantId 在根声明）
    validateQuery(
        aggregation {
            count("tenantOrders") { tenantId("tenant") }
        },
        schema,
    ).assert().isNotNull()
    // element 作用域：根级字段经 filter() 的 element-scope 校验拒绝
    assertThrows<QuerySchemaValidationException> {
        validateQuery(
            aggregation {
                expand("orders")
                count("counted") { tenantId("tenant") }
            },
            schema,
        )
    }
    // element 作用域：本作用域字段合法；未知字段拒绝
    validateQuery(
        aggregation {
            expand("orders")
            count("paid") { "status" eq "PAID" }
        },
        schema,
    ).assert().isNotNull()
    assertThrows<QuerySchemaValidationException> {
        validateQuery(
            aggregation {
                expand("orders")
                sum("missing", "total") { "status" eq "PAID" }
            },
            schema,
        )
    }
}
```

（若 `tenantId` 的 FilterDsl 扩展在测试中不可用，以 `TenantIdFilter("tenant")` 直接构造；`boundSchemaFixture` 需给 `tenantId` 字段 EXACT_MATCH 能力——fixtures 默认全能力集已含 ✓。）

HttpQueryGuardTest 追加（沿用既有 guard 测试构造助手）：

```kotlin
@Test
fun `aggregation metric filters count toward filter value limits`() {
    val guard = guard(maxFilterValues = 2)
    assertThrows<IllegalArgumentException> {
        guard.validateAggregation(
            aggregation {
                count("a") { "status" isIn listOf("PAID", "SHIPPED", "CANCELLED") } // 3 values > 2
            }
        )
    }
}
```

（guard 构造参数名按既有测试助手实况调整；断言消息含 `must not exceed` 可选加强。）

- [ ] **Step 2: RED**

```bash
./gradlew :wow-query:test --tests "me.ahoo.wow.query.schema.QuerySchemaValidationTest"
./gradlew :wow-webflux:test --tests "*HttpQueryGuard*"
```

预期失败点：guard 值计数断言（metric filter 未计数 → 3 值不超限 → 不抛）；validation 的 element-scope 拒绝段（filter 未被校验 → 通过 → assertThrows 失败）。根作用域通过段与未知字段段在实现前后均应通过（后者走 sum 字段校验，与 filter 无关）——执行者须核对每段实际失败点并在报告记录。

- [ ] **Step 3: 实现**

QuerySchemaValidation.aggregate()（metrics.forEach 之前）：

```kotlin
query.metrics.forEach { metric ->
    if (metric.filter !== MatchAllFilter) {
        filter(metric.filter, parent)
    }
}
query.metrics.forEach { metric -> /* 既有 when 分支不动 */ }
```

（或并入既有循环开头，二选一，保持文件风格。）

HttpQueryGuard 110 行：

```kotlin
validateFilters(
    listOf(query.filter) +
        query.elements.map(AggregationElement::filter) +
        query.metrics.map(AggregationMetric::filter).filter { it !== MatchAllFilter } +
        scopeFilters,
    rejectMatchAll = false,
)
```

（`AggregationMetric::filter` 属性引用；metric filter 不属昂贵算子——不加 expensive 判定。）

- [ ] **Step 4: GREEN**：`./gradlew :wow-query:test :wow-webflux:test`
- [ ] **Step 5: 提交** `feat(query): validate aggregation metric filters and count them in HTTP guards`

---

### Task 4: wow-mongo — 编译器守卫

**Files:**
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/aggregation/MongoAggregationCompiler.kt`（`compile`/`group` 签名穿 `now`；`group` 五分支 + `numericParticipation`）
- Test: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompilerTest.kt`

**Interfaces:**
- Consumes: Task 1 字段；既有 `filterCompiler.compileScoped(filter, schema, logicalParent, physicalParent, now)`。
- Produces:
  - `group(query, id, parent, physicalParent, schema, now)`（`now` 由 `compile` 透传，供 metric filter 相对时间）。
  - `metricFilter(metric, parent, physicalParent, schema, now): Bson?`（`MatchAllFilter → null`，否则 `compileScoped` 结果）。
  - `Count+filter` → `Accumulators.sum(alias, {$cond: [filterDoc, 1, 0]})`。
  - `Numeric/Percentile`：`input` 外包 `{$cond: [filterDoc, input, null]}`；`countAlias` 守卫变 `{$cond: [{$and: [filterDoc, contributes]}, 1, 0]}`。
  - `DistinctCount`：`$addToSet` 输入外包 `{$cond: [filterDoc, value, null]}`（null 由投影过滤）。
  - `Any+filter` → `Accumulators.max(alias, {$cond: [filterDoc, $field, null]})`。
  - `project`/`toAggregationResult`/`emptySummary` 不变（空语义由上述守卫自然产生：COUNT 空 → 0、数值类 → null）。

- [ ] **Step 1: 写失败测试**（结构化 Document 断言，沿用 Task 6 既有风格）

```kotlin
@Test
fun `filtered count accumulates conditional ones`() {
    val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
        aggregation { count("paid") { "status" eq "PAID" } },
        schema(),
    ).map { it.toBsonDocument() }

    val group = pipeline.first { it.containsKey("\$group") }.getDocument("\$group")
    val accumulator = group.getDocument("paid").getDocument("\$sum").getArray("\$cond")
    accumulator.get(1).asInt32().value.assert().isEqualTo(1)
    accumulator.get(0).asDocument().containsKey("\$eq").assert().isTrue()
}

@Test
fun `filtered numeric metrics guard contributions with the filter`() {
    val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
        aggregation {
            sum("state.amount", "paidAmount") { "state.status" eq "PAID" }
            percentile("state.amount", 50.0, "paidP50") { "state.status" eq "PAID" }
        },
        schema(),
    ).map { it.toBsonDocument() }

    val group = pipeline.first { it.containsKey("\$group") }.getDocument("\$group")
    group.getDocument("paidAmount").getDocument("\$sum").getDocument("\$cond")
        .containsKey("\$eq").assert().isTrue()
    val countGuard = group.getDocument("__wow_value_count_paidAmount").getDocument("\$sum")
        .getArray("\$cond").get(0).asDocument()
    countGuard.getDocument("\$and").getArray(0).asDocument().containsKey("\$eq").assert().isTrue()
    group.getDocument("paidP50").getDocument("\$percentile").getDocument("input")
        .getDocument("\$cond").containsKey("\$eq").assert().isTrue()
}

@Test
fun `filtered distinct count and any null non matching records`() {
    val pipeline = MongoAggregationCompiler(SnapshotFilterCompiler).compile(
        aggregation {
            distinctCount("state.productId", "paidProducts") { "state.status" eq "PAID" }
            any("state.status", "anyStatus") { "deleted" eq false }
        },
        schema(),
    ).map { it.toBsonDocument() }

    val group = pipeline.first { it.containsKey("\$group") }.getDocument("\$group")
    group.getDocument("paidProducts").getDocument("\$addToSet").getDocument("\$cond")
        .containsKey("\$eq").assert().isTrue()
    group.getDocument("anyStatus").getDocument("\$max").getDocument("\$cond")
        .containsKey("\$eq").assert().isTrue()
}
```

（`schema()` 的默认字段集含 `state.status` TERMS/`state.amount` NUMERIC/`state.productId` TERMS/`deleted` ✓；作用域内相对路径 `"status"` 在根作用域解析需绝对 `"state.status"`——测试用绝对路径。）

- [ ] **Step 2: RED**：`./gradlew :wow-mongo:test --tests "me.ahoo.wow.mongo.query.snapshot.*"`——断言失败（当前 accumulator 无 `$cond`）。

- [ ] **Step 3: 实现**（按 Interfaces 的六个产出点；`compile` 内 `group(query, groupId, logicalParent, physicalParent, schema)` 调用补 `now` 实参；既有无过滤路径输出**逐字节不变**——filter 为 null 时不包装）

- [ ] **Step 4: GREEN**：`./gradlew :wow-mongo:test`
- [ ] **Step 5: 提交** `feat(mongo): guard metric contributions with record-level filters`

---

### Task 5: wow-elasticsearch — filter 子聚合

**Files:**
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/aggregation/ElasticsearchAggregationCompiler.kt`（计划类型 + `toPlan` 五分支）
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/aggregation/ElasticsearchAggregationPager.kt`（`metricAggregations`/`value()`）
- Test: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationCompilerTest.kt`

**Interfaces:**
- Produces:
  - 计划类型追加 `filter: Query?`（默认 null）：`Count(alias, filter)`、`Numeric(alias, function, field, filter)`、`Any(alias, field, filter)`、`DistinctCount(alias, field, filter)`、`Percentile(alias, field, percentile, filter)`；`toPlan` 各分支编译 `metric.filter !== MatchAllFilter → filterCompiler.compileScoped(filter, schema, logicalParent, physicalParent, now)`。
  - Pager：`Count+filter` → 名为 alias 的 `filter` 聚合（值 = 其 `doc_count`）；其余带 filter 指标 → 名为 `__wow_metric_filter_<alias>` 的 filter 聚合包裹原指标聚合与 valueCount；`value()` 对带 filter 指标先导航一层再走既有提取；无 filter 路径输出不变。

- [ ] **Step 1: 写失败测试**

```kotlin
@Test
fun `plan should compile metric filters into scoped queries`() {
    val plan = ElasticsearchAggregationCompiler(SnapshotFilterCompiler).compile(
        aggregation {
            count("paid") { "status" eq "PAID" }
            sum("amount", "paidAmount") { "status" eq "PAID" }
        },
        schema,
    )

    val count = plan.metrics.filterIsInstance<ElasticsearchAggregationMetric.Count>().single()
    count.filter.assert().isNotNull()
    plan.metrics.filterIsInstance<ElasticsearchAggregationMetric.Numeric>().single().filter.assert().isNotNull()
}
```

（`schema` 为该测试类既有绑定 schema，`status` 为 keyword ✓。Pager 的 filter 包装与 value() 导航由 Task 6 集成契约覆盖——单元层断言计划携带 Query 即可，Pager 私有函数不可直测。）

- [ ] **Step 2: RED**：编译失败（计划类型无 filter 参数）。
- [ ] **Step 3: 实现**（按 Interfaces；`metricAggregations` 中带 filter 的非 COUNT 指标：

```kotlin
val metricAggregation = Aggregation.of { builder -> /* 既有 when(metric) 主体 */ }
if (metric.filter == null) {
    put(metric.alias, metricAggregation)
} else {
    put(
        "__wow_metric_filter_${metric.alias}",
        Aggregation.of { builder -> builder.filter(metric.filter).aggregations(metric.alias, metricAggregation) },
    )
}
```

COUNT 分支：`filter == null → Unit`（现状）；否则 `put(alias, filter agg)`。`value()`：COUNT+filter → `aggregations.getValue(alias).filter().docCount()`；其余带 filter 指标先把 `aggregations` 换为 `aggregations.getValue("__wow_metric_filter_$alias").filter().aggregations()` 再走既有提取——以局部函数/提前导航实现，避免分支复制。）

- [ ] **Step 4: GREEN**：`./gradlew :wow-elasticsearch:test`
- [ ] **Step 5: 提交** `feat(elasticsearch): wrap filtered metrics in filter aggregations`

---

### Task 6: TCK — 跨后端契约场景

**Files:**
- Modify: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/SnapshotQueryBackendSpec.kt`（874 行区域，`aggregation should support cancellation...` 之前）
- Modify: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/EventStreamQueryBackendSpec.kt`

**fixture 事实**（`aggregationStates()`）：stateA 订单 [PAID(lines: alpha@10, beta@20), CANCELLED(gamma@null)]；stateB [PAID(alpha@30, beta@20, delta@50)]。展开 `state.orders`+`lines` 后共 6 行：PAID 5 行（amounts 10/20/30/20/50）、CANCELLED 1 行。

- [ ] **Step 1: Snapshot 场景**（设计文档 6 组；取材原则：metric filter 只能引用当前作用域自有字段——`status` 属 orders 层，lines 层条件用 `quantity`/`productName`）

```kotlin
@Test
fun `aggregation should count a funnel of metric filtered counts`() {
    saveAggregationStates(*aggregationStates().toTypedArray())
    aggregation {
        filter { deletion(DeletionState.ACTIVE) }
        expand("state.orders")
        count("all")                            // stateA 两单 + stateB 一单 = 3
        count("paid") { "status" eq "PAID" }    // 2
    }.query(queryBackendBinding)
        .test()
        .assertNext { it.assertWireEquals(mapOf("all" to 3L, "paid" to 2L)) }
        .verifyComplete()
}

@Test
fun `aggregation should filter numeric and distinct metrics by record filters`() {
    saveAggregationStates(*aggregationStates().toTypedArray())
    aggregation {
        filter { deletion(DeletionState.ACTIVE) }
        expand("state.orders")
        expand("lines")
        count("big") { "quantity" gte 2 }                               // beta,gamma,alpha,beta,delta = 5
        sum("amount", "bigAmount") { "quantity" gte 2 }                 // 20+null+30+20+50 = 120.0
        distinctCount("productId", "bigProducts") { "quantity" gte 2 }  // {alpha,beta,delta,gamma} = 4
    }.query(queryBackendBinding)
        .test()
        .assertNext {
            it.assertWireEquals(mapOf("big" to 5L, "bigAmount" to 120.0, "bigProducts" to 4L))
        }.verifyComplete()
}

@Test
fun `aggregation should return zero or null when a metric filter matches nothing`() {
    saveAggregationStates(*aggregationStates().toTypedArray())
    aggregation {
        filter { deletion(DeletionState.ACTIVE) }
        expand("state.orders")
        expand("lines")
        count("none") { "quantity" gt 100 }                            // 0L
        sum("amount", "noneAmount") { "quantity" gt 100 }              // null
        distinctCount("productId", "noneProducts") { "quantity" gt 100 } // 0L
        percentile("amount", 95.0, "noneP95") { "quantity" gt 100 }    // null
    }.query(queryBackendBinding)
        .test()
        .assertNext {
            it.assertWireEquals(
                mapOf("none" to 0L, "noneAmount" to null, "noneProducts" to 0L, "noneP95" to null),
            )
        }.verifyComplete()
}

@Test
fun `aggregation should combine metric filters with element filters`() {
    saveAggregationStates(*aggregationStates().toTypedArray())
    aggregation {
        filter { deletion(DeletionState.ACTIVE) }
        expand("state.orders") { "status" eq "PAID" }
        expand("lines")
        count("all")                          // PAID 5 行
        count("big") { "quantity" gte 2 }     // beta(2),alpha(4),beta(2),delta(5) = 4
    }.query(queryBackendBinding)
        .test()
        .assertNext { it.assertWireEquals(mapOf("all" to 5L, "big" to 4L)) }
        .verifyComplete()
}

@Test
fun `aggregation should sort groups by a filtered metric`() {
    saveAggregationStates(*aggregationStates().toTypedArray())
    aggregation {
        filter { deletion(DeletionState.ACTIVE) }
        expand("state.orders")
        expand("lines")
        terms("productId", "product")
        sum("amount", "bigAmount") { "quantity" gte 2 } // alpha=30(仅qty4行), beta=40, delta=50, gamma=null(amt空)
        sort { "bigAmount".desc() }
    }.query(queryBackendBinding)
        .collectList()
        .test()
        .assertNext { rows ->
            rows.map { it.path("product").textValue() }.assert().containsExactly("delta", "beta", "alpha")
        }.verifyComplete()
}

@Test
fun `aggregation should scope metric filters to the innermost element`() {
    saveAggregationStates(*aggregationStates().toTypedArray())
    aggregation {
        filter { deletion(DeletionState.ACTIVE) }
        expand("state.orders")
        expand("lines")
        count("byName") { "productName" eq "Alpha" } // 仅 stateA 的 alpha 行 = 1
    }.query(queryBackendBinding)
        .test()
        .assertNext { it.path("byName").longValue().assert().isEqualTo(1L) }
        .verifyComplete()
}
```

（`productName` 在 lines 作用域已声明（既有 ANY 场景使用）；`productName eq "Alpha"` 精确匹配仅 stateA alpha 行——stateB alpha 行为 "Alpha 2026" 不等。）

- [ ] **Step 2: EventStream 场景**

```kotlin
@Test
fun aggregateFilteredEventCounts() {
    val tenantId = generateGlobalId()
    eventStore.append(generateEventStream(namedAggregate.aggregateId(tenantId = tenantId))).block()
    aggregation {
        filter { tenantId(tenantId) }
        expand("body")
        count("all")
        count("first") { "revision" eq 1L } // 按 body.revision 条件计数；期望值执行时按 fixture 实况断言（事件流 revision 语义）
    }.query(queryBackendBinding)
        .test()
        .assertNext { row ->
            row.path("all").longValue().assert().isEqualTo(10L)
            row.path("first").longValue().assert().isEqualTo(1L)
        }.verifyComplete()
}
```

（`revision` 的 JSON 值类型与比较语义执行时核对；若 revision 为数值需 `eq 1L` 的数值构造按 FilterDsl 惯例。）

- [ ] **Step 3: 双后端集成确认 GREEN**（Docker）

```bash
./gradlew :wow-mongo:integrationTest :wow-elasticsearch:integrationTest --stacktrace
```

若后端行为与断言不符：以设计语义修正实现（守卫条件），不放宽断言；期望值算术错误则按 fixture 复推修正并记录。

- [ ] **Step 4: 提交** `test(tck): add cross-backend contracts for metric filters`

---

### Task 7: OpenAPI 快照与中英文档

**Files:**
- Modify: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/ExampleDomainOpenAPITest.kt`（如有 schema 形状断言需同步）+ 快照再生成
- Modify: `documentation/docs/{zh,en}/guide/query/aggregation-query.md`（Metric 节 filter 语义）与 `snapshot-aggregation.md`（漏斗场景）

- [ ] **Step 1**: `./gradlew :wow-openapi:test -Dwow.snapshot.update=true` 再生成；`./gradlew :wow-openapi:test` 干净复跑绿（快照差异须为纯增量：各 metric schema 增加 `filter` 属性引用）。
- [ ] **Step 2**: zh Metric 表后追加段：指标级 `filter`（记录级零贡献语义、`COUNT+filter` 空匹配 0/数值类 null、element 作用域约束、值数计入 HTTP 限制）；en 镜像。`snapshot-aggregation.md` zh/en 各加漏斗场景（DSL 示例即 Task 2 形态）。
- [ ] **Step 3**: `cd documentation && pnpm docs:build`。
- [ ] **Step 4**: 提交 `feat(openapi): align metric filter schemas` 与 `docs(query): document aggregation metric filters`（可两提交）。

---

### Task 8: 全量回归与静态检查

- [ ] **Step 1**: `./gradlew :wow-api:check :wow-query:check :wow-mongo:check :wow-elasticsearch:check :wow-webflux:check`
- [ ] **Step 2**: `./gradlew :wow-mongo:integrationTest :wow-elasticsearch:integrationTest --stacktrace`
- [ ] **Step 3**: `./gradlew allLocalTest allContractTest`
- [ ] **Step 4**: `./gradlew detekt`
- [ ] **Step 5**: `git log --oneline`、`git status` 收尾核对；如实汇报各层结果。

---

## 计划自审记录

1. **规格覆盖**：设计「Phase 1」的 AST（Task 1）、语义（Tasks 4/5/6 断言锚定）、验证（Task 3）、双后端（Tasks 4/5）、DSL（Task 2）、HttpQueryGuard（Task 3）、TCK 六组场景（Task 6：漏斗/条件数值与去重/空匹配/叠加 element/按 filter 指标排序/最内层作用域 + EventStream 代表）、文档与快照（Task 7）、回归（Task 8）。覆盖完整。
2. **自审修正记录**（写入时发现并已修正）：①原稿在 AST init 校验 filter 内容——错误：根级过滤器在根作用域 metric filter 上合法，合法性属查询上下文，移交 Task 3 验证层（`filter(metric.filter, parent)` 既有作用域路径）；②原稿测试取材 `"status"` 于 lines 层属跨层引用（相对解析仅一级，不合法）——漏斗移至 orders 层、lines 层条件改用自有字段 `quantity`/`productName`，期望值全部按 fixture 重推（含 gamma 含入 distinct=4、排序 delta>beta>alpha 无并列）。
3. **类型一致性**：`filter: FilterExpression = MatchAllFilter` 末位字段（Task 1）与 DSL 重载（Task 2）、`metricFilter`/`compileScoped`（Task 4）、计划类型 `filter: Query?`（Task 5）签名一致；无新 sealed 子类型，无跨任务编译耦合（各任务独立 GREEN）。
4. **风险注记**：ES `Count+filter` 复用 alias 作 filter 聚合名——与既有「COUNT 无子聚合」的 `value()` 路径以 `filter == null` 区分，零歧义；Mongo `now` 穿参为唯一签名变更（内部 private，无兼容影响）；Task 6 EventStream 的 `revision` 值类型按 FilterDsl 数值惯例核对后落定。
