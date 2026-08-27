# 聚合 ANY 代表值指标实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 `AggregationQuery` 增加不改变分组键的 `ANY` 代表值指标，使按 `productId` 分组时可直接返回组内任意非空 `productName`。

**Architecture:** 在公开 `AggregationMetric` sealed 层次中增加 `Any(field, alias)`，DSL 只增加 `any(field, alias)`。字段复用 `AGGREGATE_TERMS` 和现有 Elements 相对路径；MongoDB 编译为 `$max`，Elasticsearch 编译为 `terms(size = 1)`，结果继续进入现有动态行、排序与 HTTP guard 流程。

**Tech Stack:** Kotlin 2.4、JUnit Jupiter 6、FluentAssert、Reactor Test、MongoDB Java Driver、Elasticsearch Java Client 9.4、Spring Data、Jackson、springdoc/OpenAPI、VitePress。

**Spec:** `docs/superpowers/specs/2026-08-27-aggregation-any-metric-design.md`

## Global Constraints

- `ANY` 返回组内任意非 `null` 标量；全部为空时返回 `null`，不得承诺执行间或后端间稳定。
- 输入字段必须具备 `AGGREGATE_TERMS`，已知 `QueryCardinality.MANY` 按现有 validation mode 判定为不兼容。
- 不支持表达式、对象、多值字段、`FIRST`、`LAST`、`LATEST`、`ARG_MAX` 或自定义选择排序。
- 不增加兼容别名、`selections` 平行通道、Catalog、Scanner、Lookup、Join、脚本、配置、依赖、模块或 CI 改动。
- 不修改 `compensation/dashboard/src/generated/`；OpenAPI 只更新模型与既有快照。
- MongoDB 与 Elasticsearch 必须执行真实集成测试；编译器字符串断言不能替代后端证据。
- 所有 Kotlin 测试沿用 FluentAssert `.assert()`，不引入 AssertJ。

---

## File Structure

不创建新的生产源码文件，按现有职责修改：

- `wow-api/src/main/kotlin/me/ahoo/wow/api/query/AggregationQuery.kt`：公开 `AggregationMetric.Any` 与 JSON subtype。
- `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/AggregationQueryDsl.kt`：Kotlin DSL 入口。
- `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaResolver.kt`：ANY 字段 capability 与 cardinality 兼容性。
- `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompiler.kt`：`$max` accumulator 与投影。
- `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryService.kt`：ANY 结果采用 Terms key 规范化。
- `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationCompiler.kt`：显式 Count/Numeric/Any 内部计划和物理字段解析。
- `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationPager.kt`：生成 `terms(size=1)`，读取 string/long/double/unmapped bucket。
- `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/mock/MockAggregate.kt`：为共享聚合数据增加单值 `productName`。
- `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/SnapshotQueryServiceSpec.kt`：MongoDB 与 Elasticsearch 共用真实合同。
- `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/query/HttpQueryGuardFilterTest.kt`：ANY alias 排序沿用昂贵查询门禁。
- `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/ExampleDomainOpenAPITest.kt` 与两个 JSON snapshot：固定联合类型合同。
- `documentation/docs/zh/guide/query.md`、`documentation/docs/en/guide/query.md`：同步 DSL、JSON、空值与不稳定语义。

---

### Task 1: 公共 ANY 模型与 Kotlin DSL

**Files:**
- Modify: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/AggregationQuery.kt:192-215`
- Test: `wow-api/src/test/kotlin/me/ahoo/wow/api/query/AggregationQueryTest.kt:20-275`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/AggregationQueryDsl.kt:71-122`
- Test: `wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/AggregationQueryDslTest.kt:25-104`

**Interfaces:**
- Consumes: 现有 `LogicalField`、`AggregationMetric.alias`、`requireAggregationAlias(alias)` 与 `metrics` 集合。
- Produces: `AggregationMetric.Any(val field: LogicalField, override val alias: String)`、JSON subtype `ANY`、`AggregationQueryDsl.any(field: String, alias: String)`。

- [ ] **Step 1: 写入 API JSON 与 alias RED 测试**

在 `AggregationQueryTest` 增加：

```kotlin
@Test
fun `any metric should round trip through JSON`() {
    val json = """
        {
          "metrics": [{
            "type": "ANY",
            "field": "state.productName",
            "alias": "productName"
          }]
        }
    """.trimIndent()

    val query = configuredMapper.readValue(json, AggregationQuery::class.java)

    query.metrics.assert().containsExactly(
        AggregationMetric.Any(LogicalField("state.productName"), "productName"),
    )
    configuredMapper.writeValueAsString(query).assert().contains("\"type\":\"ANY\"")
}

@Test
fun `any metric should reject internal aliases`() {
    assertThrows<IllegalArgumentException> {
        AggregationMetric.Any(LogicalField("state.productName"), "__wow_productName")
    }
}
```

- [ ] **Step 2: 运行 API RED 测试**

Run:

```bash
./gradlew :wow-api:test --tests "me.ahoo.wow.api.query.AggregationQueryTest" --stacktrace
```

Expected: Kotlin 编译失败，包含 `Unresolved reference 'Any'`。

- [ ] **Step 3: 写入 DSL RED 测试**

在 `AggregationQueryDslTest` 增加：

```kotlin
@Test
fun `aggregation DSL should add an any metric without another group`() {
    val query = aggregation {
        terms("productId", "productId")
        any("productName", "productName")
        count("count")
    }

    query.groupBy.assert().containsExactly(
        AggregationGroup.Terms(LogicalField("productId"), "productId"),
    )
    query.metrics.assert().containsExactly(
        AggregationMetric.Any(LogicalField("productName"), "productName"),
        AggregationMetric.Count("count"),
    )
}
```

- [ ] **Step 4: 运行 DSL RED 测试**

Run:

```bash
./gradlew :wow-query:test --tests "me.ahoo.wow.query.dsl.AggregationQueryDslTest" --stacktrace
```

Expected: Kotlin 编译失败，包含 `Unresolved reference 'any'` 和 `Unresolved reference 'Any'`。

- [ ] **Step 5: 实现最小公开模型与 DSL**

在 `AggregationMetric` subtype 注册中加入：

```kotlin
JsonSubTypes.Type(AggregationMetric.Any::class, name = "ANY"),
```

在 sealed interface 上显式固定 OpenAPI union，并在 `AggregationMetric` 内加入：

```kotlin
@Schema(
    oneOf = [
        AggregationMetric.Count::class,
        AggregationMetric.Numeric::class,
        AggregationMetric.Any::class,
    ],
    discriminatorProperty = "type",
)
sealed interface AggregationMetric {
    val alias: String

    data class Any(
        val field: LogicalField,
        override val alias: String,
    ) : AggregationMetric {
        init {
            requireAggregationAlias(alias)
        }
    }
}
```

在 `AggregationQueryDsl` 的 `count` 后加入：

```kotlin
fun any(field: String, alias: String) {
    metrics += AggregationMetric.Any(LogicalField(field), alias)
}
```

不改变 `AggregationFunction`，不增加表达式重载。

- [ ] **Step 6: 运行公共模型与 DSL GREEN 测试**

Run:

```bash
./gradlew :wow-api:test :wow-query:test \
  --tests "*AggregationQueryTest" \
  --tests "*AggregationQueryDslTest" \
  --stacktrace
```

Expected: 两个测试类全部 PASS。其他后端模块尚未重编译，不在本步骤声称全仓可编译。

- [ ] **Step 7: 提交公共合同**

```bash
git add \
  wow-api/src/main/kotlin/me/ahoo/wow/api/query/AggregationQuery.kt \
  wow-api/src/test/kotlin/me/ahoo/wow/api/query/AggregationQueryTest.kt \
  wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/AggregationQueryDsl.kt \
  wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/AggregationQueryDslTest.kt
git commit -m "feat(query): add aggregation any metric contract"
```

---

### Task 2: ANY 字段 Schema 解析

**Files:**
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaResolver.kt:146-180`
- Test: `wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QuerySchemaResolverTest.kt:1000-1210`

**Interfaces:**
- Consumes: Task 1 的 `AggregationMetric.Any`；现有 `resolveAggregationField`、`FieldResolution.fieldSchema`、`QueryCapability.AGGREGATE_TERMS`。
- Produces: SINGLE 精确字段为 `EXACT`，缺少 capability 或已知 MANY 为 `INCOMPATIBLE`，Elements 字段仍按最内层相对路径解析。

- [ ] **Step 1: 写入 capability、相对路径与 cardinality RED 测试**

在 `QuerySchemaResolverTest` 增加：

```kotlin
@Test
fun `aggregation any should require a single terms-capable field in the innermost element`() {
    val productName = LogicalField("state.orders.lines.productName")
    val baseFields = linkedMapOf(
        LogicalField("state.orders") to fieldSchema(
            QueryCapability.ELEMENT_SCOPE to "document.orders",
            cardinality = QueryCardinality.MANY,
            valueTypes = setOf(QueryValueType.OBJECT),
        ),
        LogicalField("state.orders.lines") to fieldSchema(
            QueryCapability.ELEMENT_SCOPE to "document.orders.lines",
            cardinality = QueryCardinality.MANY,
            valueTypes = setOf(QueryValueType.OBJECT),
        ),
        productName to fieldSchema(
            QueryCapability.AGGREGATE_TERMS to "document.orders.lines.productName.keyword",
            valueTypes = setOf(QueryValueType.STRING),
        ),
    )
    val query = AggregationQuery(
        elements = listOf(
            AggregationElement(LogicalField("state.orders")),
            AggregationElement(LogicalField("lines")),
        ),
        metrics = listOf(AggregationMetric.Any(LogicalField("productName"), "productName")),
    )

    QuerySchemaResolver(schema(baseFields)).resolve(query).compatibility.assert()
        .isEqualTo(QueryCompatibilityLevel.EXACT)

    val manyField = baseFields.getValue(productName).copy(cardinality = QueryCardinality.MANY)
    QuerySchemaResolver(schema(baseFields + (productName to manyField)))
        .resolve(query).compatibility.assert().isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)

    val withoutTerms = baseFields.getValue(productName).copy(bindings = emptyMap())
    QuerySchemaResolver(schema(baseFields + (productName to withoutTerms)))
        .resolve(query).compatibility.assert().isEqualTo(QueryCompatibilityLevel.INCOMPATIBLE)
}
```

- [ ] **Step 2: 运行 Schema RED 测试**

Run:

```bash
./gradlew :wow-query:test \
  --tests "me.ahoo.wow.query.schema.QuerySchemaResolverTest.aggregation any should require a single terms-capable field in the innermost element" \
  --stacktrace
```

Expected: MANY 与 missing-capability 分支错误地保持 `EXACT`，测试 FAIL。

- [ ] **Step 3: 在现有 aggregation resolve 流程加入 ANY 检查**

在 Numeric 表达式检查前加入：

```kotlin
query.metrics.filterIsInstance<AggregationMetric.Any>().forEach { metric ->
    val resolved = resolveAggregationField(
        metric.field,
        QueryCapability.AGGREGATE_TERMS,
        logicalParent,
        physicalParent,
    )
    levels += resolved.compatibility
    if (resolved.fieldSchema?.cardinality == QueryCardinality.MANY) {
        levels += QueryCompatibilityLevel.INCOMPATIBLE
    }
}
```

补充 `QueryCardinality` import。不要新增 `AGGREGATE_ANY` capability，也不要改写 query 中的逻辑字段。

- [ ] **Step 4: 运行 Schema GREEN 与回归测试**

Run:

```bash
./gradlew :wow-query:test --tests "me.ahoo.wow.query.schema.QuerySchemaResolverTest" --stacktrace
```

Expected: `QuerySchemaResolverTest` 全部 PASS。

- [ ] **Step 5: 提交 Schema 解析**

```bash
git add \
  wow-query/src/main/kotlin/me/ahoo/wow/query/schema/QuerySchemaResolver.kt \
  wow-query/src/test/kotlin/me/ahoo/wow/query/schema/QuerySchemaResolverTest.kt
git commit -m "feat(query): validate aggregation any fields"
```

---

### Task 3: MongoDB ANY 编译、结果与共享真实合同

**Files:**
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompiler.kt:92-141`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryService.kt:122-147`
- Test: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompilerTest.kt:35-360`
- Modify: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/mock/MockAggregate.kt:70-77`
- Test: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/SnapshotQueryServiceSpec.kt:283-665`

**Interfaces:**
- Consumes: Task 1 的 `AggregationMetric.Any`，Task 2 的 `AGGREGATE_TERMS`/SINGLE 约束。
- Produces: Mongo `$max` accumulator、ANY 投影、Terms-key 结果规范化，以及由 Mongo/Elasticsearch 两个 integrationTest 类继承的共享合同。

- [ ] **Step 1: 给共享 TCK 数据增加显示名称**

在 `MockLine` 末尾增加默认字段：

```kotlin
val productName: String? = null,
```

在 `aggregationStateA()` 的 `alpha` line 设置：

```kotlin
productName = "Alpha",
```

在 `aggregationStateB()` 的 `alpha` line 设置：

```kotlin
productName = "Alpha 2026",
```

保留 `gamma` 的缺省 `null`，用于全空场景。

- [ ] **Step 2: 写入共享 Mongo RED 合同**

在 `SnapshotQueryServiceSpec` 增加：

```kotlin
@Test
fun `aggregation should select any non-null value without splitting the group`() {
    saveAggregationStates(*aggregationStates().toTypedArray())

    aggregation {
        expand("state.orders")
        expand("lines") { "productId" eq "alpha" }
        terms("productId", "productId")
        any("productName", "productName")
        count("count")
    }.query(snapshotQueryService)
        .test()
        .assertNext { row ->
            row["productId"].assert().isEqualTo("alpha")
            setOf("Alpha", "Alpha 2026").contains(row["productName"]).assert().isTrue()
            row["count"].assert().isEqualTo(2L)
        }.verifyComplete()
}

@Test
fun `aggregation any should return null when every value is absent`() {
    saveAggregationStates(*aggregationStates().toTypedArray())

    aggregation {
        expand("state.orders")
        expand("lines") { "productId" eq "gamma" }
        any("productName", "productName")
        count("count")
    }.query(snapshotQueryService)
        .test()
        .assertNext { row ->
            row.containsKey("productName").assert().isTrue()
            row["productName"].assert().isNull()
            row["count"].assert().isEqualTo(1L)
        }.verifyComplete()
}
```

同时扩展现有 `aggregation should return one empty summary row`：

```kotlin
aggregation {
    filter { aggregateId("missing") }
    any("state.data", "anyData")
    count("count")
    sum("version", "total")
}.query(snapshotQueryService)
    .test()
    .assertNext {
        it.toMap().assert().isEqualTo(
            mapOf("anyData" to null, "count" to 0L, "total" to null),
        )
    }.verifyComplete()
```

- [ ] **Step 3: 写入 Mongo compiler RED 测试**

在 `MongoAggregationCompilerTest` 增加：

```kotlin
@Test
fun `any metric should compile a resolved max accumulator and projection`() {
    val schema = schema(
        field(
            "state.productName",
            QueryCapability.AGGREGATE_TERMS,
            "document.productName",
        ),
    )
    val pipeline = compiler.compile(
        aggregation {
            any("state.productName", "productName")
            count("count")
        },
        schema,
    ).map { it.toBsonDocument() }

    val group = pipeline.single { it.containsKey("\$group") }.getDocument("\$group")
    group.getDocument("productName").assert()
        .isEqualTo(BsonDocument("\$max", BsonString("\$document.productName")))
    pipeline.single { it.containsKey("\$project") }.toJson().assert().contains("productName")
}
```

复用该测试文件现有 `compiler`、`schema`、`field` helper；按现有 imports 使用 `BsonDocument`/`BsonString`，不要比较整条 pipeline 字符串。

- [ ] **Step 4: 运行 Mongo RED 测试**

Run:

```bash
./gradlew :wow-mongo:test \
  --tests "*MongoAggregationCompilerTest.any metric should compile a resolved max accumulator and projection" \
  --stacktrace
```

Expected: `when` 不穷尽或 ANY 未产生 accumulator，测试 FAIL。

- [ ] **Step 5: 实现 Mongo `$max` 与投影**

在 `MongoAggregationCompiler.group()` 的 metric `when` 中加入：

```kotlin
is AggregationMetric.Any -> add(
    Accumulators.max(
        metric.alias,
        "\$${metric.field.resolve(parent, schema, QueryCapability.AGGREGATE_TERMS)}",
    ),
)
```

在 `project()` 的 metric `when` 中加入：

```kotlin
is AggregationMetric.Any -> Projections.include(metric.alias)
```

- [ ] **Step 6: 实现 Mongo ANY 结果规范化**

在 `MongoSnapshotQueryService` 增加：

```kotlin
private fun Any?.toTermsValue(alias: String): Any? =
    if (this is Decimal128) toFiniteDouble(alias) else this
```

把 group key 转换改为调用 `toTermsValue`，并在 metric `when` 中加入：

```kotlin
is AggregationMetric.Any -> get(metric.alias).toTermsValue(metric.alias)
```

保留现有 empty-summary 表达式；它已经让非 Count 指标返回 `null`。

- [ ] **Step 7: 运行 Mongo 单元与真实集成 GREEN 测试**

Run:

```bash
./gradlew :wow-mongo:test --tests "*MongoAggregationCompilerTest" --stacktrace
./gradlew :wow-mongo:integrationTest \
  --tests "*MongoSnapshotQueryServiceTest.aggregation should select any non-null value without splitting the group" \
  --tests "*MongoSnapshotQueryServiceTest.aggregation any should return null when every value is absent" \
  --tests "*MongoSnapshotQueryServiceTest.aggregation should return one empty summary row" \
  --stacktrace
```

Expected: compiler 测试与三个真实 MongoDB 合同均 PASS。

- [ ] **Step 8: 提交 Mongo 与共享合同**

```bash
git add \
  wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompiler.kt \
  wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryService.kt \
  wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompilerTest.kt \
  test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/mock/MockAggregate.kt \
  test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/SnapshotQueryServiceSpec.kt
git commit -m "feat(mongo): execute aggregation any metrics"
```

---

### Task 4: Elasticsearch ANY 计划、请求与结果读取

**Files:**
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationCompiler.kt:36-69,226-247`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationPager.kt:130-230`
- Test: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationCompilerTest.kt:35-360`
- Test: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationPagerTest.kt:30-410`

**Interfaces:**
- Consumes: 共享 TCK 的 `productName` 数据与 `AggregationMetric.Any`。
- Produces: sealed internal `ElasticsearchAggregationMetric.Count/Numeric/Any`，`terms(field, size=1)` 请求，string/long/double/unmapped 结果读取。

- [ ] **Step 1: 写入 compiler RED 测试**

在 `ElasticsearchAggregationCompilerTest` 增加：

```kotlin
@Test
fun `any metric should resolve a terms-capable field without a runtime mapping`() {
    val schema = schema(
        field(
            "state.productName",
            QueryCapability.AGGREGATE_TERMS,
            "document.productName.keyword",
            "keyword",
        ),
    )
    val plan = ElasticsearchAggregationCompiler(SnapshotFilterConverter).compile(
        aggregation { any("state.productName", "productName") },
        schema,
    )

    val metric = plan.metrics.single() as ElasticsearchAggregationMetric.Any
    metric.alias.assert().isEqualTo("productName")
    metric.field.assert().isEqualTo("document.productName.keyword")
    plan.runtimeMappings.assert().isEmpty()
}
```

- [ ] **Step 2: 写入 pager RED 测试与 response helper**

在 `ElasticsearchAggregationPagerTest` import `DoubleTermsBucket`、`LongTermsBucket`、`StringTermsBucket`，增加 helper：

```kotlin
private fun stringTerms(value: String?): Aggregate = Aggregate.of { aggregate ->
    aggregate.sterms { terms ->
        terms.buckets(
            Buckets.of<StringTermsBucket> { buckets ->
                buckets.array(
                    value?.let {
                        listOf(StringTermsBucket.of { bucket -> bucket.key(it).docCount(1) })
                    }.orEmpty(),
                )
            },
        )
    }
}

private fun booleanTerms(value: Boolean): Aggregate = Aggregate.of { aggregate ->
    aggregate.sterms { terms ->
        terms.buckets(
            Buckets.of<StringTermsBucket> { buckets ->
                buckets.array(listOf(StringTermsBucket.of { it.key(value).docCount(1) }))
            },
        )
    }
}

private fun longTerms(value: Long): Aggregate = Aggregate.of { aggregate ->
    aggregate.lterms { terms ->
        terms.buckets(
            Buckets.of<LongTermsBucket> { buckets ->
                buckets.array(listOf(LongTermsBucket.of { it.key(value).docCount(1) }))
            },
        )
    }
}

private fun doubleTerms(value: Double): Aggregate = Aggregate.of { aggregate ->
    aggregate.dterms { terms ->
        terms.buckets(
            Buckets.of<DoubleTermsBucket> { buckets ->
                buckets.array(listOf(DoubleTermsBucket.of { it.key(value).docCount(1) }))
            },
        )
    }
}

private fun anyBucket(product: String, productName: Aggregate): CompositeBucket = CompositeBucket.of {
    it.key("product", product)
        .docCount(1)
        .aggregations("productName", productName)
}
```

增加测试：

```kotlin
@Test
fun `any metric should request one terms bucket and read its scalar key`() {
    val requests = mutableListOf<SearchRequest>()
    stubPointInTime()
    every { client.search(capture(requests), Map::class.java) } returns Mono.just(
        groupResponse("pit-2", listOf(anyBucket("alpha", stringTerms("Alpha")))),
    )
    val plan = compiler().compile(
        aggregation {
            terms("state.productId", "product")
            any("state.productName", "productName")
        },
    )

    pager().execute(plan).test()
        .assertNext { row -> row["productName"].assert().isEqualTo("Alpha") }
        .verifyComplete()

    requests.single().aggregations().values.single().composite()
        .aggregations().getValue("productName").terms().apply {
            field().assert().isEqualTo("state.productName")
            size().assert().isEqualTo(1)
        }
}
```

在同一测试类增加以下原生类型测试：

```kotlin
@Test
fun `any metric should normalize boolean long double and empty terms buckets`() {
    stubPointInTime()
    every { client.search(any<SearchRequest>(), Map::class.java) } returns Mono.just(
        groupResponse(
            "pit-2",
            listOf(
                anyBucket("a", booleanTerms(true)),
                anyBucket("b", longTerms(7L)),
                anyBucket("c", doubleTerms(7.5)),
                anyBucket("d", stringTerms(null)),
            ),
        ),
    )
    val plan = compiler().compile(
        aggregation {
            terms("state.productId", "product")
            any("state.value", "productName")
        },
    )

    pager().execute(plan).map { it["productName"] }.collectList().test()
        .assertNext { values -> values.assert().containsExactly(true, 7L, 7.5, null) }
        .verifyComplete()
}
```

- [ ] **Step 3: 运行 Elasticsearch RED 测试**

Run:

```bash
./gradlew :wow-elasticsearch:test \
  --tests "*ElasticsearchAggregationCompilerTest.any metric should resolve a terms-capable field without a runtime mapping" \
  --tests "*ElasticsearchAggregationPagerTest.any metric should request one terms bucket and read its scalar key" \
  --stacktrace
```

Expected: internal `Any` plan 不存在，且 production `when` 不穷尽，编译或断言 FAIL。

- [ ] **Step 4: 把 nullable-function data class 改为显式内部 sealed 计划**

在 `ElasticsearchAggregationCompiler.kt` 替换现有内部 metric data class：

```kotlin
internal sealed interface ElasticsearchAggregationMetric {
    val alias: String

    data class Count(override val alias: String) : ElasticsearchAggregationMetric

    data class Numeric(
        override val alias: String,
        val function: AggregationFunction,
        val field: String,
    ) : ElasticsearchAggregationMetric {
        val valueCountAlias: String
            get() = "__wow_value_count_$alias"
    }

    data class Any(
        override val alias: String,
        val field: String,
    ) : ElasticsearchAggregationMetric
}
```

将 `AggregationMetric.toPlan()` 改为：

```kotlin
is AggregationMetric.Count -> ElasticsearchAggregationMetric.Count(alias)
is AggregationMetric.Any -> ElasticsearchAggregationMetric.Any(
    alias,
    field.resolve(parent, schema, QueryCapability.AGGREGATE_TERMS),
)
is AggregationMetric.Numeric -> {
    val metricField = when (val expression = expression) {
        is AggregationExpression.Field -> expression.field.resolve(
            parent,
            schema,
            QueryCapability.AGGREGATE_NUMERIC,
        )
        else -> "__wow_expression_$index".also { runtimeFieldName ->
            runtimeMappings[runtimeFieldName] = RuntimeExpressionCompiler(parent, schema).compile(expression)
        }
    }
    ElasticsearchAggregationMetric.Numeric(alias, function, metricField)
}
```

- [ ] **Step 5: 为三种内部计划生成聚合请求**

把 `metricAggregations()` 改为对 sealed 计划穷尽处理：

```kotlin
private fun ElasticsearchAggregationPlan.metricAggregations(): Map<String, Aggregation> = buildMap {
    metrics.forEach { metric ->
        when (metric) {
            is ElasticsearchAggregationMetric.Count -> Unit
            is ElasticsearchAggregationMetric.Any -> put(
                metric.alias,
                Aggregation.of { builder ->
                    builder.terms { terms -> terms.field(metric.field).size(1) }
                },
            )
            is ElasticsearchAggregationMetric.Numeric -> {
                put(
                    metric.alias,
                    Aggregation.of { builder ->
                        when (metric.function) {
                            AggregationFunction.SUM -> builder.sum { it.field(metric.field) }
                            AggregationFunction.AVG -> builder.avg { it.field(metric.field) }
                            AggregationFunction.MIN -> builder.min { it.field(metric.field) }
                            AggregationFunction.MAX -> builder.max { it.field(metric.field) }
                        }
                    },
                )
                put(
                    metric.valueCountAlias,
                    Aggregation.of { builder -> builder.valueCount { it.field(metric.field) } },
                )
            }
        }
    }
}
```

- [ ] **Step 6: 读取 ANY bucket key**

把 metric value 读取改为 sealed `when`，保留 Numeric 的 finite 检查：

```kotlin
private fun ElasticsearchAggregationMetric.value(
    docCount: Long,
    aggregations: Map<String, Aggregate>,
): Any? = when (this) {
    is ElasticsearchAggregationMetric.Count -> docCount
    is ElasticsearchAggregationMetric.Any -> aggregations.getValue(alias).anyValue(alias)
    is ElasticsearchAggregationMetric.Numeric -> numericValue(aggregations)
}

private fun Aggregate.anyValue(alias: String): Any? = when {
    isSterms -> sterms().buckets().array().firstOrNull()?.key()?.nativeValue()
    isLterms -> lterms().buckets().array().firstOrNull()?.key()
    isDterms -> dterms().buckets().array().firstOrNull()?.key()
    isUmterms -> null
    else -> error("Aggregation ANY metric [$alias] returned unsupported Elasticsearch aggregate [${_kind()}].")
}
```

将现有 Numeric 读取主体移动到一个 helper，保持唯一实现：

```kotlin
private fun ElasticsearchAggregationMetric.Numeric.numericValue(
    aggregations: Map<String, Aggregate>,
): Double? {
    if (aggregations.getValue(valueCountAlias).valueCount().value() == 0.0) return null
    val value = when (function) {
        AggregationFunction.SUM -> aggregations.getValue(alias).sum().value()
        AggregationFunction.AVG -> aggregations.getValue(alias).avg().value()
        AggregationFunction.MIN -> aggregations.getValue(alias).min().value()
        AggregationFunction.MAX -> aggregations.getValue(alias).max().value()
    }
    require(value != null && value.isFinite()) { "Aggregation metric [$alias] must be finite." }
    return value
}
```

- [ ] **Step 7: 运行 Elasticsearch 单元与真实集成 GREEN 测试**

Run:

```bash
./gradlew :wow-elasticsearch:test \
  --tests "*ElasticsearchAggregationCompilerTest" \
  --tests "*ElasticsearchAggregationPagerTest" \
  --stacktrace
./gradlew :wow-elasticsearch:integrationTest \
  --tests "*ElasticsearchSnapshotQueryServiceTest.aggregation should select any non-null value without splitting the group" \
  --tests "*ElasticsearchSnapshotQueryServiceTest.aggregation any should return null when every value is absent" \
  --tests "*ElasticsearchSnapshotQueryServiceTest.aggregation should return one empty summary row" \
  --stacktrace
```

Expected: 单元测试和三个真实 Elasticsearch 共享合同全部 PASS；请求没有 runtime mapping 或 script。

- [ ] **Step 8: 提交 Elasticsearch 实现**

```bash
git add \
  wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationCompiler.kt \
  wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationPager.kt \
  wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationCompilerTest.kt \
  wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationPagerTest.kt
git commit -m "feat(elasticsearch): execute aggregation any metrics"
```

---

### Task 5: HTTP Guard、OpenAPI 合同与中英文文档

**Files:**
- Test: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/query/HttpQueryGuardFilterTest.kt:130-205`
- Test: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/ExampleDomainOpenAPITest.kt:99-170`
- Modify generated snapshot: `wow-openapi/src/test/resources/openapi/example-domain-openapi.snapshot.json`
- Modify generated snapshot: `wow-openapi/src/test/resources/openapi/example-domain-contract.snapshot.json`
- Modify: `documentation/docs/zh/guide/query.md:163-254,390-430`
- Modify: `documentation/docs/en/guide/query.md:163-254,392-432`

**Interfaces:**
- Consumes: 完整 `AggregationMetric.Any`、现有 metric-alias guard、springdoc subtype discovery。
- Produces: 明确的 guard 回归证据、OpenAPI `ANY` union/discriminator、双语使用示例。

- [ ] **Step 1: 固定 ANY alias 排序的现有门禁**

在 `HttpQueryGuardFilterTest` 增加：

```kotlin
@Test
fun `aggregation Guard should treat any alias sorting as expensive but allow plain any`() {
    val query = AggregationQuery(
        groupBy = listOf(AggregationGroup.Terms(LogicalField("state.productId"), "productId")),
        metrics = listOf(AggregationMetric.Any(LogicalField("state.productName"), "productName")),
        sort = listOf(Sort("productName", Sort.Direction.ASC)),
    )

    guard(allowExpensiveOperators = false)
        .filter(aggregationContext(query), unexpectedBackend())
        .writeRawRequest(request).test()
        .expectError(IllegalArgumentException::class.java)
        .verify()

    guard(allowExpensiveOperators = false, idleTimeout = Duration.ZERO)
        .filter(
            aggregationContext(query.copy(sort = emptyList())),
            FilterChain { context ->
                context.asAggregationQuery().setResult(Flux.empty())
                Mono.empty()
            },
        ).writeRawRequest(request).test().verifyComplete()
}
```

Run:

```bash
./gradlew :wow-webflux:test \
  --tests "*HttpQueryGuardFilterTest.aggregation Guard should treat any alias sorting as expensive but allow plain any" \
  --stacktrace
```

Expected: PASS，无需修改 `HttpQueryGuardFilter` 生产代码。若失败，修复只能复用 `query.metrics.map(AggregationMetric::alias)`，不得新增 ANY 专属开关。

- [ ] **Step 2: 写入 OpenAPI ANY 联合类型断言**

在 `snapshot aggregation should use generic query body and expose dynamic rows` 测试中加入：

```kotlin
val metricSchema = openAPI.components.schemas.getValue("wow.api.query.AggregationMetric")
querySchema.properties.getValue("metrics").items.`$ref`.assert()
    .isEqualTo("#/components/schemas/wow.api.query.AggregationMetric")
metricSchema.oneOf.map { it.`$ref` }.assert().containsExactlyInAnyOrder(
    "#/components/schemas/wow.api.query.AggregationMetric.Count",
    "#/components/schemas/wow.api.query.AggregationMetric.Numeric",
    "#/components/schemas/wow.api.query.AggregationMetric.Any",
)
metricSchema.discriminator.propertyName.assert().isEqualTo("type")
val anySchema = openAPI.components.schemas.getValue("wow.api.query.AggregationMetric.Any")
anySchema.required.assert().containsExactlyInAnyOrder("field", "alias", "type")
anySchema.properties.getValue("field").`$ref`.assert().isEqualTo(logicalFieldRef)
```

- [ ] **Step 3: 运行 OpenAPI RED 并受控更新快照**

Run:

```bash
./gradlew :wow-openapi:test --tests "*ExampleDomainOpenAPITest*" --stacktrace
```

Expected: snapshot mismatch，FAIL 并显示新增 `AggregationMetric.Any`。

Update:

```bash
./gradlew :wow-openapi:test \
  -Dwow.snapshot.update=true \
  --tests "*ExampleDomainOpenAPITest*" \
  --stacktrace
```

Review:

```bash
git diff -- \
  wow-openapi/src/test/resources/openapi/example-domain-openapi.snapshot.json \
  wow-openapi/src/test/resources/openapi/example-domain-contract.snapshot.json
```

Expected diff: 只新增 `AggregationMetric.Any`、`ANY` discriminator mapping/ref 及对应必填字段；不得出现路由、endpoint 或无关 Schema 漂移。

- [ ] **Step 4: 更新中文聚合说明与案例**

在指标说明中把指标集合更新为包含 `ANY`，并加入：

```kotlin
val query = aggregation {
    expand("state.items")
    terms("productId", "productId")
    any("productName", "productName")
    count("count")
}
```

对应 JSON：

```json
{
  "elements": [{"path": "state.items"}],
  "groupBy": [
    {"type": "TERMS", "field": "productId", "alias": "productId"}
  ],
  "metrics": [
    {"type": "ANY", "field": "productName", "alias": "productName"},
    {"type": "COUNT", "alias": "count"}
  ]
}
```

紧随示例说明：`ANY` 不参与分组；忽略 `null`/缺失；全部为空返回 `null`；MongoDB、Elasticsearch 或重复执行可能选择不同非空名称；需要确定名称时应修复冗余数据或使用明确业务查询，不要依赖 `ANY`。

- [ ] **Step 5: 镜像更新英文文档**

使用同一 DSL/JSON，英文语义固定为：

```text
ANY does not add another group key. It returns one non-null scalar from the current group, or null when no value contributes. The selected value is intentionally unstable across executions and backends.
```

中文和英文示例字段、alias、null 语义必须逐项一致。

- [ ] **Step 6: 运行合同、守卫与文档 GREEN 验证**

Run:

```bash
./gradlew :wow-webflux:check :wow-openapi:check --stacktrace
cd documentation && pnpm docs:build
```

Expected: Gradle 两模块 PASS，VitePress build 成功且无死链。

- [ ] **Step 7: 提交合同与文档**

```bash
git add \
  wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/query/HttpQueryGuardFilterTest.kt \
  wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/ExampleDomainOpenAPITest.kt \
  wow-openapi/src/test/resources/openapi/example-domain-openapi.snapshot.json \
  wow-openapi/src/test/resources/openapi/example-domain-contract.snapshot.json \
  documentation/docs/zh/guide/query.md \
  documentation/docs/en/guide/query.md
git commit -m "docs(query): document aggregation any metrics"
```

---

### Task 6: 全量相关验证与完成审计

**Files:**
- Verify only: all files modified in Tasks 1-5

**Interfaces:**
- Consumes: Tasks 1-5 的完整实现。
- Produces: 可复核的 module check、双后端 integration、Detekt、文档构建和干净补丁证据。

- [ ] **Step 1: 运行所有相关 module checks**

```bash
./gradlew \
  :wow-api:check \
  :wow-query:check \
  :wow-mongo:check \
  :wow-elasticsearch:check \
  :wow-webflux:check \
  :wow-openapi:check \
  --stacktrace
```

Expected: 全部任务 BUILD SUCCESSFUL；不得把缺失依赖或容器失败误报为源码通过。

- [ ] **Step 2: 运行完整 MongoDB 与 Elasticsearch integration suites**

```bash
./gradlew \
  :wow-mongo:integrationTest \
  :wow-elasticsearch:integrationTest \
  --stacktrace
```

Expected: 两个完整 integrationTest suite PASS，包括共享 `SnapshotQueryServiceSpec` 的两个 ANY 场景。

- [ ] **Step 3: 运行 Detekt 与文档构建**

```bash
./gradlew detekt --stacktrace
cd documentation && pnpm docs:build
```

Expected: Detekt 与 VitePress 均成功。

- [ ] **Step 4: 审计范围与补丁卫生**

```bash
git diff --check
git status --short
git diff --stat HEAD~5..HEAD
rg -n "AggregationMetric\.Any|fun any\(" \
  wow-api wow-query wow-mongo wow-elasticsearch wow-webflux wow-openapi test documentation
```

Expected:

- `git diff --check` 无输出。
- 工作树不包含构建产物、`node_modules`、`.gradle`、生成 dashboard 客户端或未解释文件。
- 生产引用只出现在已批准的 API、DSL、Schema、MongoDB、Elasticsearch 边界。
- 文档与测试引用覆盖中英文、OpenAPI 和双后端 TCK。

- [ ] **Step 5: 确认验证任务不产生额外提交**

Task 6 只验证，不编辑文件。任一命令失败时返回拥有该文件的 Task 1-5，在该任务中完成 RED/GREEN 修复、重跑其最窄测试并使用该任务的提交范围；全部通过时不创建空提交，记录 Steps 1-4 的命令输出作为完成证据。
