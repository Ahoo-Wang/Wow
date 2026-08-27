# Snapshot Elements Aggregation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add snapshot aggregation with ordered collection expansion, native MongoDB/Elasticsearch execution, the existing query policy chain, HTTP/OpenAPI, and Kotlin/client APIs.

**Architecture:** `AggregationQuery` is a small logical contract in `wow-api`; `wow-query` adds the DSL and routes it through the existing `SnapshotQueryHandler` chain. MongoDB and Elasticsearch each compile the same relative-path model into native aggregation plans, while invalid physical fields and mappings remain backend errors.

**Tech Stack:** Kotlin 2.4.10, JVM 17, Reactor, Jackson, MongoDB reactive streams, Elasticsearch Java client, Spring WebFlux, Victools JSON Schema, JUnit Jupiter 6, MockK, FluentAssert.

**Spec:** `docs/superpowers/specs/2026-08-24-snapshot-elements-aggregation-design.md`

## Global Constraints

- Keep `Aggregation*` naming and `POST .../snapshot/aggregation`; do not add `Analysis*` aliases.
- Add `aggregate()` to the existing `SnapshotQueryService` and `SnapshotQueryHandler` with source-compatible default errors; do not add capability interfaces or JVM ABI bridges.
- Fixed limits: result default `100`, result max `10_000`, Elements max `5`, groups max `32`, metrics max `64`, effective sort max `32`.
- The first Element path is absolute; later Element paths, every Element filter, and leaf group/metric fields are relative to their current scope.
- Do not use `TypeFieldPaths` for validation and do not create an aggregation field catalog.
- Do not validate arbitrary Jackson serializers or custom Elasticsearch mapping equivalence.
- Do not create type-specific field enums, rewrite `FilterExpression` schemas, add aggregation configuration, dependencies, modules, benchmarks, reports, or CI workflows.
- Reuse the existing snapshot filter chain, ABAC, Guard, ErrorHandler, `FilterDsl`, `SortDsl`, mapping resolver, response streaming, and PIT behavior.
- `MaskingSnapshotQueryFilter` skips aggregation without rejecting or rewriting results.
- Count returns `Long`; Sum/Avg/Min/Max return finite `Double` or `null` when no value contributes.
- Use FluentAssert `.assert()` in Kotlin tests and keep all execution reactive and cancellation-safe.
- Do not copy or cherry-pick the superseded aggregation branches; implement from the confirmed spec and current `main` only.

## File Structure

New focused production files:

- `wow-api/src/main/kotlin/me/ahoo/wow/api/query/AggregationQuery.kt` — public query, groups, metrics, expression and structural validation.
- `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/AggregationQueryDsl.kt` — direct Kotlin DSL.
- `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompiler.kt` — logical query to Mongo pipeline.
- `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchPointInTime.kt` — shared PIT lifecycle extracted from the existing pager.
- `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationCompiler.kt` — logical query to ES aggregation request parts.
- `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationPager.kt` — composite pagination and exact Top-N.
- `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/snapshot/SnapshotAggregationHandlerFunction.kt` — HTTP handler and route factory.
- `wow-apiclient/src/main/kotlin/me/ahoo/wow/apiclient/query/SnapshotAggregationQueryApi.kt` — HTTP interface.
- `wow-apiclient/src/main/kotlin/me/ahoo/wow/apiclient/query/ReactiveSnapshotAggregationQueryApi.kt` — reactive specialization.
- `wow-apiclient/src/main/kotlin/me/ahoo/wow/apiclient/query/SynchronousSnapshotAggregationQueryApi.kt` — synchronous specialization.

Existing files change only where the new operation crosses an established boundary: query service/handler/context, two backend services/converters, WebFlux route/guard, OpenAPI route/components, Spring proxy/route module, TCK fixtures, tests and documentation.

---

### Task 1: Public aggregation contract

**Files:**
- Create: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/AggregationQuery.kt`
- Modify: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/FilterExpression.kt`
- Create: `wow-api/src/test/kotlin/me/ahoo/wow/api/query/AggregationQueryTest.kt`

**Interfaces:**
- Consumes: `FilterExpression`, `FilterCapable`, `LogicalField`, `Sort`, `SortCapable`.
- Produces: `AggregationQuery`, `AggregationElement`, `AggregationGroup`, `AggregationMetric`, `AggregationExpression`, `AggregationFunction`, `AggregationDateUnit`, `AggregationQuery.effectiveSort()`.

- [ ] **Step 1: Write failing model and JSON tests**

Add tests that construct a query with relative Elements, deserialize a field expression without `type`, append an ABAC filter, and reject only local structural errors:

```kotlin
@Test
fun `field expression should be the default JSON subtype`() {
    val json = """
        {
          "metrics": [{
            "type": "NUMERIC",
            "function": "SUM",
            "expression": {"field": "amount"},
            "alias": "total"
          }]
        }
    """.trimIndent()

    val query = jsonMapper.readValue(json, AggregationQuery::class.java)
    val metric = query.metrics.single() as AggregationMetric.Numeric
    metric.expression.assert().isEqualTo(AggregationExpression.Field(LogicalField("amount")))
}

@Test
fun `elements should preserve ordered relative paths`() {
    val query = AggregationQuery(
        elements = listOf(
            AggregationElement(LogicalField("state.orders")),
            AggregationElement(LogicalField("lines")),
            AggregationElement(LogicalField("discounts")),
        ),
        metrics = listOf(AggregationMetric.Count("count")),
    )
    query.elements.map { it.path.value }.assert().containsExactly("state.orders", "lines", "discounts")
}

@Test
fun `invalid aliases and root element filters should fail`() {
    assertThrows<IllegalArgumentException> {
        AggregationQuery(
            elements = listOf(AggregationElement(LogicalField("state.orders"), TenantIdFilter("tenant"))),
            metrics = listOf(AggregationMetric.Count("count")),
        )
    }
    assertThrows<IllegalArgumentException> {
        AggregationQuery(
            groupBy = listOf(AggregationGroup.Terms(LogicalField("state.status"), "same")),
            metrics = listOf(AggregationMetric.Count("same")),
        )
    }
}
```

- [ ] **Step 2: Run the API test and verify red**

Run:

```bash
./gradlew :wow-api:test --tests 'me.ahoo.wow.api.query.AggregationQueryTest'
```

Expected: compilation fails because the aggregation types do not exist.

- [ ] **Step 3: Implement the minimum public model**

Change `FilterExpression.containsElementUnsupportedFilter()` from file-private to `internal` and reuse it from `AggregationElement`; do not duplicate the operator walk.

Implement the model with Jackson `NAME` polymorphism and `Field` as the default expression:

```kotlin
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    property = "type",
    defaultImpl = AggregationExpression.Field::class,
)
@JsonSubTypes(JsonSubTypes.Type(AggregationExpression.Field::class, name = "FIELD"))
interface AggregationExpression {
    data class Field(val field: LogicalField) : AggregationExpression
}

data class AggregationQuery(
    override val filter: FilterExpression = MatchAllFilter,
    val elements: List<AggregationElement> = emptyList(),
    val groupBy: List<AggregationGroup> = emptyList(),
    val metrics: List<AggregationMetric>,
    override val sort: List<Sort> = emptyList(),
    val limit: Int = DEFAULT_LIMIT,
) : FilterCapable<AggregationQuery>, SortCapable {
    override fun withFilter(newFilter: FilterExpression): AggregationQuery = copy(filter = newFilter)

    fun effectiveSort(): List<Sort> = buildList {
        addAll(sort)
        val sorted = sort.mapTo(hashSetOf(), Sort::field)
        groupBy.map(AggregationGroup::alias)
            .filterNot(sorted::contains)
            .forEach { add(Sort(it, Sort.Direction.ASC)) }
    }
}
```

Validate metrics non-empty, all fixed limits, finite positive Histogram interval, `ZoneId.of(timeZone)`, one-segment aliases, alias uniqueness, sort alias references, no sort without groups, and effective sort size. Do not inspect aggregate metadata, Java types or mappings.

- [ ] **Step 4: Run API checks**

Run:

```bash
./gradlew :wow-api:test --tests 'me.ahoo.wow.api.query.AggregationQueryTest' \
  --tests 'me.ahoo.wow.api.query.FilterExpressionTest'
```

Expected: all selected tests pass.

- [ ] **Step 5: Commit**

```bash
git add wow-api/src/main/kotlin/me/ahoo/wow/api/query/AggregationQuery.kt \
  wow-api/src/main/kotlin/me/ahoo/wow/api/query/FilterExpression.kt \
  wow-api/src/test/kotlin/me/ahoo/wow/api/query/AggregationQueryTest.kt
git commit -m "feat(query): define snapshot aggregation contract"
```

### Task 2: Kotlin DSL and execution extension

**Files:**
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/AggregationQueryDsl.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/Dsl.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/snapshot/QueryDsl.kt`
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/AggregationQueryDslTest.kt`

**Interfaces:**
- Consumes: all Task 1 aggregation types, existing `FilterDsl`, `SortDsl`, `SnapshotQueryService`.
- Produces: `aggregation {}`, direct `AggregationQueryDsl` methods, `AggregationQuery.query(SnapshotQueryService)`.

- [ ] **Step 1: Write a failing DSL shape test**

```kotlin
@Test
fun `aggregation DSL should preserve relative scopes and explicit aliases`() {
    val query = aggregation {
        filter { "state.status" eq "COMPLETED" }
        expand("state.orders") { "status" eq "PAID" }
        expand("lines") { "quantity" gt 0 }
        terms("productId", alias = "product")
        count(alias = "count")
        sum("amount", alias = "total")
        sort { "total".desc() }
        limit(20)
    }

    query.elements.map { it.path.value }.assert().containsExactly("state.orders", "lines")
    query.groupBy.assert().hasSize(1)
    query.metrics.assert().hasSize(2)
    query.limit.assert().isEqualTo(20)
}
```

- [ ] **Step 2: Run the DSL test and verify red**

```bash
./gradlew :wow-query:test --tests 'me.ahoo.wow.query.dsl.AggregationQueryDslTest'
```

Expected: compilation fails because `aggregation` and `AggregationQueryDsl` do not exist.

- [ ] **Step 3: Implement one direct DSL class**

Use mutable lists internally and existing builders for filter/sort:

```kotlin
@QueryDslMarker
class AggregationQueryDsl {
    private var filter: FilterExpression = MatchAllFilter
    private val elements = mutableListOf<AggregationElement>()
    private val groups = mutableListOf<AggregationGroup>()
    private val metrics = mutableListOf<AggregationMetric>()
    private var sort: List<Sort> = emptyList()
    private var limit: Int = AggregationQuery.DEFAULT_LIMIT

    fun expand(path: String) = elements.add(AggregationElement(LogicalField(path)))
    fun expand(path: String, block: FilterDsl.() -> Unit) = elements.add(
        AggregationElement(LogicalField(path), me.ahoo.wow.query.dsl.filter(block)),
    )

    fun sum(field: String, alias: String) = numeric(AggregationFunction.SUM, field, alias)
    fun avg(field: String, alias: String) = numeric(AggregationFunction.AVG, field, alias)
    fun min(field: String, alias: String) = numeric(AggregationFunction.MIN, field, alias)
    fun max(field: String, alias: String) = numeric(AggregationFunction.MAX, field, alias)
    private fun numeric(function: AggregationFunction, field: String, alias: String) {
        metrics += AggregationMetric.Numeric(function, AggregationExpression.Field(LogicalField(field)), alias)
    }

    fun terms(field: String, alias: String) {
        groups += AggregationGroup.Terms(LogicalField(field), alias)
    }

    fun count(alias: String) {
        metrics += AggregationMetric.Count(alias)
    }

    fun build(): AggregationQuery = AggregationQuery(filter, elements, groups, metrics, sort, limit)
}
```

Add the approved `filter`, `histogram`, `dateHistogram`, `sort` and `limit` setters with the exact signatures in the spec, plus top-level `aggregation(block)`, and:

```kotlin
fun AggregationQuery.query(queryService: SnapshotQueryService<*>): Flux<DynamicDocument> =
    queryService.aggregate(this)
```

Do not add property-reference overloads, alias inference, nested DSL classes or expression builders.

- [ ] **Step 4: Run DSL tests**

```bash
./gradlew :wow-query:test --tests 'me.ahoo.wow.query.dsl.AggregationQueryDslTest'
```

Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/AggregationQueryDsl.kt \
  wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/Dsl.kt \
  wow-query/src/main/kotlin/me/ahoo/wow/query/snapshot/QueryDsl.kt \
  wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/AggregationQueryDslTest.kt
git commit -m "feat(query): add snapshot aggregation DSL"
```

### Task 3: Existing snapshot service and filter chain

**Files:**
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/snapshot/SnapshotQueryService.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/filter/QueryType.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/filter/QueryContext.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/filter/QueryHandler.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/snapshot/filter/SnapshotQueryHandler.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/snapshot/filter/SnapshotQueryFilter.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/snapshot/filter/MaskingSnapshotQueryFilter.kt`
- Modify: `wow-spring/src/main/kotlin/me/ahoo/wow/spring/query/QueryServiceProxy.kt`
- Modify tests: `wow-query/src/test/kotlin/me/ahoo/wow/query/filter/QueryTypeTest.kt`
- Modify tests: `wow-query/src/test/kotlin/me/ahoo/wow/query/snapshot/filter/DefaultSnapshotQueryHandlerTest.kt`
- Modify tests: `wow-query/src/test/kotlin/me/ahoo/wow/query/snapshot/filter/AbacQueryFilterTest.kt`
- Modify tests: `wow-query/src/test/kotlin/me/ahoo/wow/query/snapshot/filter/MaskingSnapshotQueryFilterTest.kt`
- Modify tests: `wow-spring/src/test/kotlin/me/ahoo/wow/spring/query/QueryServiceProxyTest.kt`

**Interfaces:**
- Consumes: `AggregationQuery` and `DynamicDocument`.
- Produces: `SnapshotQueryService.aggregate`, `SnapshotQueryHandler.aggregate`, `QueryType.AGGREGATION`, `QueryContext.asAggregationQuery`.

- [ ] **Step 1: Write failing chain tests**

Add tests proving the default publisher is cold, ABAC rewrites only the root filter, masking is skipped, Tail invokes the service, and the Spring proxy enters the handler:

```kotlin
@Test
fun `aggregation should use the existing snapshot chain`() {
    val query = AggregationQuery(metrics = listOf(AggregationMetric.Count("count")))
    queryHandler.aggregate(MOCK_AGGREGATE_METADATA, query)
        .test()
        .expectNextMatches { it["count"] == 1L }
        .verifyComplete()
}

@Test
fun `masking should ignore aggregation`() {
    val context = DefaultQueryContext<AggregationQuery, Flux<DynamicDocument>>(
        QueryType.AGGREGATION,
        MOCK_AGGREGATE_METADATA,
    ).setQuery(AggregationQuery(metrics = listOf(AggregationMetric.Count("count"))))
    filter.filter(context, tail).test().verifyComplete()
    verify(exactly = 0) { masker.mask(any()) }
}
```

- [ ] **Step 2: Run query and Spring tests to verify red**

```bash
./gradlew :wow-query:test \
  --tests 'me.ahoo.wow.query.snapshot.filter.DefaultSnapshotQueryHandlerTest' \
  --tests 'me.ahoo.wow.query.snapshot.filter.AbacQueryFilterTest' \
  --tests 'me.ahoo.wow.query.snapshot.filter.MaskingSnapshotQueryFilterTest' \
  :wow-spring:test --tests 'me.ahoo.wow.spring.query.QueryServiceProxyTest'
```

Expected: compilation fails on the missing operation and query type.

- [ ] **Step 3: Thread aggregation through existing types**

Add source-compatible defaults directly on the two existing interfaces:

```kotlin
fun aggregate(query: AggregationQuery): Flux<DynamicDocument> =
    Flux.error(UnsupportedOperationException("Snapshot aggregation is not supported by [$name]."))

fun aggregate(namedAggregate: NamedAggregate, query: AggregationQuery): Flux<DynamicDocument> =
    Flux.error(UnsupportedOperationException("Snapshot aggregation is not supported."))
```

Put the first method on `SnapshotQueryService` and the second on `SnapshotQueryHandler`. Add `AGGREGATION(true)` to `QueryType`, `asAggregationQuery()` to `QueryContext`, and make `AbstractQueryHandler.flux` protected so `DefaultSnapshotQueryHandler.aggregate()` can call it.

Add one Tail branch:

```kotlin
QueryType.AGGREGATION -> context.asAggregationQuery().setResult(queryService::aggregate)
```

In `MaskingSnapshotQueryFilter.mask`, return before `isDynamic` handling when type is `AGGREGATION`. In `SnapshotQueryServiceProxy`, override `aggregate` and call the stored `SnapshotQueryHandler`; do not call the delegate directly or bypass policies.

- [ ] **Step 4: Run chain tests**

```bash
./gradlew :wow-query:test \
  --tests 'me.ahoo.wow.query.filter.QueryTypeTest' \
  --tests 'me.ahoo.wow.query.snapshot.filter.DefaultSnapshotQueryHandlerTest' \
  --tests 'me.ahoo.wow.query.snapshot.filter.AbacQueryFilterTest' \
  --tests 'me.ahoo.wow.query.snapshot.filter.MaskingSnapshotQueryFilterTest' \
  :wow-spring:test --tests 'me.ahoo.wow.spring.query.QueryServiceProxyTest'
```

Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add wow-query/src/main/kotlin/me/ahoo/wow/query \
  wow-query/src/test/kotlin/me/ahoo/wow/query \
  wow-spring/src/main/kotlin/me/ahoo/wow/spring/query/QueryServiceProxy.kt \
  wow-spring/src/test/kotlin/me/ahoo/wow/spring/query/QueryServiceProxyTest.kt
git commit -m "feat(query): route aggregation through snapshot policies"
```

### Task 4: Relative filter conversion in existing backend converters

**Files:**
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoFilterConverter.kt`
- Modify: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/SnapshotFilterConverterTest.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/AbstractElasticsearchFilterConverter.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchIndexMappingResolver.kt`
- Modify: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchFilterConverterTest.kt`
- Modify: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchIndexMappingResolverTest.kt`

**Interfaces:**
- Produces: `AbstractMongoFilterConverter.convert(filter, parent)`, `AbstractElasticsearchFilterConverter.convert(filter, parent)`, `ElasticsearchIndexMapping.resolve(filter, parent)`.
- Consumed by: both aggregation compilers.

- [ ] **Step 1: Write failing scoped conversion tests**

```kotlin
@Test
fun `Mongo filter should prefix relative fields`() {
    val bson = SnapshotFilterConverter.convert(filter { "quantity" gt 1 }, "state.orders.lines")
    bson.toBsonDocument().toJson().assert().contains("state.orders.lines.quantity")
}

@Test
fun `Elasticsearch filter should prefix relative fields`() {
    val query = SnapshotFilterConverter.convert(filter { "quantity" gt 1 }, "state.orders.lines")
    query.range().untyped().field().assert().isEqualTo("state.orders.lines.quantity")
}
```

- [ ] **Step 2: Run converter tests and verify red**

```bash
./gradlew :wow-mongo:test --tests 'me.ahoo.wow.mongo.query.SnapshotFilterConverterTest' \
  :wow-elasticsearch:test --tests 'me.ahoo.wow.elasticsearch.query.ElasticsearchFilterConverterTest' \
  --tests 'me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMappingResolverTest'
```

Expected: missing overload compilation errors.

- [ ] **Step 3: Expose the parent parameter already used internally**

For Elasticsearch, make the existing private parent-aware compile/resolve entry callable without changing its algorithm:

```kotlin
fun convert(filter: FilterExpression, parent: String? = null): Query =
    compile(filterNormalizer.normalize(filter), parent)

fun resolve(filter: FilterExpression, parent: String? = null): FilterExpression =
    resolveTyped(filter, parent)
```

For MongoDB, add `parent` to recursive compilation and resolve leaf paths before `fieldConverter`:

```kotlin
private fun LogicalField.path(parent: String?): String =
    if (parent == null || value == parent || value.startsWith("$parent.")) value else "$parent.$value"
```

Keep Mongo `$elemMatch` predicates relative inside the `$elemMatch` body; do not prefix those inner fields twice.

- [ ] **Step 4: Run converter tests**

Run the Step 2 command. Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoFilterConverter.kt \
  wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/SnapshotFilterConverterTest.kt \
  wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/AbstractElasticsearchFilterConverter.kt \
  wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchIndexMappingResolver.kt \
  wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchFilterConverterTest.kt \
  wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchIndexMappingResolverTest.kt
git commit -m "refactor(query): support scoped filter conversion"
```

### Task 5: MongoDB compiler and service execution

**Files:**
- Create: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompiler.kt`
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryService.kt`
- Create: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompilerTest.kt`

**Interfaces:**
- Consumes: `AggregationQuery`, scoped `SnapshotFilterConverter`.
- Produces: `MongoAggregationCompiler.compile(query): List<Bson>` and Mongo `SnapshotQueryService.aggregate`.

- [ ] **Step 1: Write failing pipeline tests**

Assert exact stage order and resolved paths for root and nested queries:

```kotlin
@Test
fun `compiler should unwind and filter every relative element`() {
    val query = aggregation {
        expand("state.orders") { "status" eq "PAID" }
        expand("lines") { "quantity" gt 0 }
        terms("productId", "product")
        count("count")
    }
    val stages = MongoAggregationCompiler(SnapshotFilterConverter).compile(query)
        .map { it.toBsonDocument().keys.first() }
    stages.assert().containsExactly(
        "\$match", "\$unwind", "\$match", "\$unwind", "\$match",
        "\$match", "\$group", "\$project", "\$sort", "\$limit",
    )
}

@Test
fun `summary compiler should retain contribution counts`() {
    val query = aggregation { sum("state.amount", "total") }
    MongoAggregationCompiler(SnapshotFilterConverter).compile(query)
        .joinToString { it.toBsonDocument().toJson() }
        .assert().contains("__wow_value_count_total")
}
```

- [ ] **Step 2: Run compiler tests and verify red**

```bash
./gradlew :wow-mongo:test --tests 'me.ahoo.wow.mongo.query.snapshot.MongoAggregationCompilerTest'
```

Expected: compilation fails because the compiler does not exist.

- [ ] **Step 3: Implement the minimal pipeline compiler**

Resolve paths by left-folding Elements:

```kotlin
var parent: String? = null
query.elements.forEach { element ->
    parent = if (parent == null) element.path.value else "$parent.${element.path.value}"
    stages += Aggregates.unwind("\$$parent")
    if (element.filter !== MatchAllFilter) {
        stages += Aggregates.match(converter.convert(element.filter, parent))
    }
}
```

Resolve group/metric fields against the final parent, exclude missing/null group keys, build one `$group`, one `$project`, stable `$sort`, and `$limit`. For each numeric metric, add one internal contribution count and project `null` when it is zero. Remove `_id` from output.

Compile group expressions exactly as follows: Terms uses the resolved field value, Histogram uses `floor(field / interval) * interval`, and DateHistogram uses `$dateTrunc` with the requested unit and time zone. Compile Count with `$sum: 1`; compile Sum/Avg/Min/Max with their matching Mongo accumulators.

In `MongoSnapshotQueryService.aggregate`, call `collection.aggregate(stages).toFlux()`, convert each `Document` to `DynamicDocument`, coerce Count to `Long`, numeric metrics to finite `Double`, and emit the one-row empty summary only when `groupBy` is empty.

- [ ] **Step 4: Run compiler and service unit checks**

```bash
./gradlew :wow-mongo:test --tests 'me.ahoo.wow.mongo.query.snapshot.MongoAggregationCompilerTest' \
  :wow-mongo:compileIntegrationTestKotlin
```

Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompiler.kt \
  wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryService.kt \
  wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompilerTest.kt
git commit -m "feat(query): execute snapshot aggregation in MongoDB"
```

### Task 6: Extract shared Elasticsearch PIT lifecycle

**Files:**
- Create: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchPointInTime.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchQueryPager.kt`
- Create: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchPointInTimeTest.kt`
- Modify: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchQueryPagerTest.kt`

**Interfaces:**
- Produces: internal `ElasticsearchPointInTime.use { session -> Publisher<T> }`, `Session.id`, `Session.update(id)` and `keepAliveValue`.
- Consumed by: existing hit pager and Task 7 aggregation pager.

- [ ] **Step 1: Add a failing PIT lifecycle test**

Create `ElasticsearchPointInTimeTest` and prove the helper closes the latest non-empty ID on cancellation:

```kotlin
@Test
fun `should close latest non-empty pit id on cancellation`() {
    val closeRequest = slot<ClosePointInTimeRequest>()
    val openResponse = mockk<OpenPointInTimeResponse> { every { id() } returns "pit-1" }
    val closeResponse = mockk<ClosePointInTimeResponse> { every { succeeded() } returns true }
    every { client.openPointInTime(any<OpenPointInTimeRequest>()) } returns Mono.just(openResponse)
    every { client.closePointInTime(capture(closeRequest)) } returns Mono.just(closeResponse)

    ElasticsearchPointInTime(client, "index", Duration.ofMinutes(1)).use { session ->
        session.update("pit-2")
        session.update("")
        Flux.never<String>()
    }.test().thenCancel().verify()

    closeRequest.captured.id().assert().isEqualTo("pit-2")
}
```

- [ ] **Step 2: Run the pager test before extraction**

```bash
./gradlew :wow-elasticsearch:test \
  --tests 'me.ahoo.wow.elasticsearch.query.ElasticsearchPointInTimeTest' \
  --tests 'me.ahoo.wow.elasticsearch.query.ElasticsearchQueryPagerTest'
```

Expected: compilation fails because `ElasticsearchPointInTime` does not exist.

- [ ] **Step 3: Extract only open/update/close behavior**

Implement a two-consumer internal helper around `Flux.usingWhen`:

```kotlin
internal class ElasticsearchPointInTime(
    private val client: ReactiveElasticsearchClient,
    private val indexName: String,
    keepAlive: Duration,
) {
    internal class Session(var id: String) {
        fun update(next: String?) {
            next?.takeIf(String::isNotBlank)?.let { id = it }
        }
    }

    fun <T : Any> use(block: (Session) -> Publisher<T>): Flux<T> = Flux.usingWhen(
        open(),
        { session -> Flux.from(block(session)) },
        ::close,
        { session, _ -> close(session) },
        ::close,
    )
}
```

Move the existing logging and non-fatal close behavior unchanged. Replace the corresponding private code in `ElasticsearchQueryPager`; do not alter pagination logic.

- [ ] **Step 4: Run the complete pager test**

Run the Step 2 command. Expected: pass, including completion, error and cancellation cleanup.

- [ ] **Step 5: Commit**

```bash
git add wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchPointInTime.kt \
  wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchQueryPager.kt \
  wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchPointInTimeTest.kt \
  wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchQueryPagerTest.kt
git commit -m "refactor(elasticsearch): share point in time lifecycle"
```

### Task 7: Elasticsearch compiler, composite pager and service

**Files:**
- Create: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationCompiler.kt`
- Create: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationPager.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchSnapshotQueryService.kt`
- Create: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationCompilerTest.kt`
- Create: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationPagerTest.kt`

**Interfaces:**
- Consumes: aggregation contract, mapping resolver, scoped filter converter and Task 6 PIT helper.
- Produces: `ElasticsearchAggregationPlan`, compiler, composite pager and ES `SnapshotQueryService.aggregate`.

- [ ] **Step 1: Write failing compiler and pager tests**

Cover nested path normalization, `.keyword` Terms resolution, composite after-key pagination, exact metric Top-N and latest PIT cleanup:

```kotlin
@Test
fun `compiler should nest relative elements in order`() {
    val mapping = mockk<ElasticsearchIndexMapping> {
        every { requireNested(any()) } answers { firstArg() }
        every { resolve(any<String>(), any()) } answers { firstArg() }
        every { resolve(any<FilterExpression>(), any()) } answers { firstArg() }
    }
    val compiler = ElasticsearchAggregationCompiler(SnapshotFilterConverter, mapping)
    val plan = compiler.compile(aggregation {
        expand("state.orders") { "status" eq "PAID" }
        expand("lines") { "quantity" gt 0 }
        terms("productId", "product")
        sum("amount", "total")
    })
    plan.assert().isNotNull()
    verifyOrder {
        mapping.requireNested("state.orders")
        mapping.requireNested("state.orders.lines")
    }
}

@Test
fun `metric sort should retain exact bounded top N`() {
    val rows = listOf(
        mapOf("product" to "a", "total" to 3.0).toDynamicDocument(),
        mapOf("product" to "b", "total" to 9.0).toDynamicDocument(),
        mapOf("product" to "c", "total" to 7.0).toDynamicDocument(),
    )
    selectTopRows(rows, listOf(Sort("total", Sort.Direction.DESC)), limit = 2)
        .map { it["total"] }.assert().containsExactly(9.0, 7.0)
}
```

- [ ] **Step 2: Run ES aggregation tests and verify red**

```bash
./gradlew :wow-elasticsearch:test \
  --tests 'me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchAggregationCompilerTest' \
  --tests 'me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchAggregationPagerTest'
```

Expected: compilation fails because compiler and pager do not exist.

- [ ] **Step 3: Implement compiler and plan**

Use constructor `ElasticsearchAggregationCompiler(filterConverter, mapping: ElasticsearchIndexMapping?)`. Its plan contains only data used by the pager: resolved root query, nested aggregation wrappers, composite sources, metric definitions, alias metadata, effective sort and limit. Resolve standard mappings as follows:

```kotlin
val termsField = mapping?.resolve(absoluteField, ElasticsearchFieldUsage.EXACT) ?: absoluteField
val nestedPath = mapping?.requireNested(absoluteElementPath) ?: absoluteElementPath
val filter = mapping?.resolve(element.filter, absoluteElementPath) ?: element.filter
val query = filterConverter.convert(filter, absoluteElementPath)
```

Do not add numeric/JVM type checks or inspect custom mapping features.

Compile Terms, Histogram and DateHistogram as Elasticsearch composite value sources in effective group-sort order. Compile Count from bucket `docCount`; compile Sum/Avg/Min/Max as matching metric sub-aggregations plus a value-count sub-aggregation for the null contract.

- [ ] **Step 4: Implement composite pagination and result normalization**

Use `size(0)` searches inside `ElasticsearchPointInTime.use`. Update the session from every response `pitId`, pass `afterKey` to the next composite request, and stop early only for group-only sort. Implement internal `selectTopRows(rows, sort, limit)` with `java.util.PriorityQueue` capped at `limit`; use the same comparator while streaming all metric-sorted buckets so ties follow the complete effective sort.

Normalize bucket keys and metrics into `DynamicDocument`; Count uses `docCount`, numeric metrics reject non-finite values and return `null` when the companion value-count is zero. For no groups, perform one aggregation request and emit one summary row.

Wire `ElasticsearchSnapshotQueryService.aggregate` to current-or-load mapping only for the standard `SnapshotFilterConverter`; custom converters use the supplied paths unchanged.

- [ ] **Step 5: Run ES unit and integration compilation**

```bash
./gradlew :wow-elasticsearch:test \
  --tests 'me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchAggregationCompilerTest' \
  --tests 'me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchAggregationPagerTest' \
  --tests 'me.ahoo.wow.elasticsearch.query.ElasticsearchQueryPagerTest' \
  :wow-elasticsearch:compileIntegrationTestKotlin
```

Expected: pass.

- [ ] **Step 6: Commit**

```bash
git add wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/snapshot \
  wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/snapshot
git commit -m "feat(query): execute snapshot aggregation in Elasticsearch"
```

### Task 8: Cross-backend TCK

**Files:**
- Modify: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/mock/MockAggregate.kt`
- Modify: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/SnapshotQueryServiceSpec.kt`
- Modify: `wow-mongo/src/integrationTest/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryServiceTest.kt`
- Modify: `wow-elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchSnapshotQueryServiceTest.kt`

**Interfaces:**
- Consumes: working Mongo and Elasticsearch aggregate implementations.
- Produces: one backend-neutral executable contract for standard Wow snapshot fields.

- [ ] **Step 1: Add nested TCK fixture state and failing contract tests**

Extend `MockStateAggregate` with defaulted immutable fixture fields so existing tests keep constructing it with only `id`:

```kotlin
data class MockOrder(val status: String, val lines: List<MockLine>)
data class MockLine(
    val productId: String,
    val quantity: Int,
    val amount: Double?,
    val createdAt: Instant,
    val discounts: List<MockDiscount>,
)
data class MockDiscount(val type: String, val amount: Double)
data class MockStateAggregate(
    val id: String,
    val orders: List<MockOrder> = emptyList(),
) : ReadOnlyStateAggregateAware<MockStateAggregate> {
    var data: String = ""
        private set
}
```

Create additional snapshots with `MOCK_AGGREGATE_METADATA.toStateAggregate(state, version = 1)` inside aggregation tests, not global setup, so existing count/list expectations remain unchanged.

Add TCK tests for root summary, two-level Elements with filters, all group/metric types, empty/null semantics, stable group sort, metric Top-N and cancellation.

- [ ] **Step 2: Run both integration suites and verify failures**

```bash
./gradlew :wow-mongo:integrationTest \
  --tests 'me.ahoo.wow.mongo.query.snapshot.MongoSnapshotQueryServiceTest' \
  :wow-elasticsearch:integrationTest \
  --tests 'me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchSnapshotQueryServiceTest'
```

Expected: Elasticsearch fixture setup fails until nested mappings are declared, or contract assertions expose backend result differences.

- [ ] **Step 3: Configure only the standard nested ES fixture mappings**

Before saving aggregation fixtures, create or update the test index mapping with `state.orders`, `state.orders.lines`, and `state.orders.lines.discounts` as nested object paths and their leaf fields as keyword/numeric/date. Do not add portability rejection tests for custom mappings.

Fix only real compiler/result differences shown by the shared TCK. Keep invalid-query backend errors out of the TCK because they are explicitly not portable.

- [ ] **Step 4: Run both integration suites**

Run the Step 2 command. Expected: both pass the same aggregation assertions.

- [ ] **Step 5: Commit**

```bash
git add test/wow-tck/src/main/kotlin/me/ahoo/wow/tck \
  wow-mongo/src/integrationTest/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryServiceTest.kt \
  wow-elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchSnapshotQueryServiceTest.kt
git commit -m "test(query): define snapshot aggregation TCK"
```

### Task 9: WebFlux route and existing Guard

**Files:**
- Create: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/snapshot/SnapshotAggregationHandlerFunction.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/query/QueryBodyExtractor.kt`
- Modify: `wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/query/HttpQueryGuardFilter.kt`
- Modify: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contract/BuiltInHttpRoutes.kt`
- Modify: `wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux/route/QueryRouteModule.kt`
- Create: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/snapshot/SnapshotAggregationHandlerFunctionTest.kt`
- Modify: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/query/QueryBodyExtractorTest.kt`
- Modify: `wow-webflux/src/test/kotlin/me/ahoo/wow/webflux/route/query/HttpQueryGuardFilterTest.kt`
- Modify: `wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxAutoConfigurationTest.kt`

**Interfaces:**
- Consumes: `SnapshotQueryHandler.aggregate`, existing rewrite filter, raw-request context and streaming response.
- Produces: route factory for the OpenAPI handler key and HTTP cost enforcement with existing options only.

- [ ] **Step 1: Write failing extractor, route and Guard tests**

```kotlin
@Test
fun `aggregation route should stream handler rows`() {
    val request = MockServerRequest.builder().body(
        AggregationQuery(metrics = listOf(AggregationMetric.Count("count"))).toMono(),
    )
    handler.handle(request).test()
        .consumeNextWith { it.statusCode().assert().isEqualTo(HttpStatus.OK) }
        .verifyComplete()
}

@Test
fun `Guard should reuse existing limits`() {
    val query = AggregationQuery(
        elements = listOf(AggregationElement(LogicalField("state.orders"))),
        metrics = listOf(AggregationMetric.Count("count")),
        limit = 101,
    )
    val context = DefaultQueryContext<AggregationQuery, Flux<DynamicDocument>>(
        QueryType.AGGREGATION,
        MOCK_AGGREGATE_METADATA,
    ).setQuery(query)
    guard(maxListSize = 100).filter(context, unexpectedBackend())
        .writeRawRequest(request).test().expectError(IllegalArgumentException::class.java).verify()
    guard(allowExpensiveOperators = false).filter(context, unexpectedBackend())
        .writeRawRequest(request).test().expectError(IllegalArgumentException::class.java).verify()
}
```

- [ ] **Step 2: Run WebFlux tests and verify red**

```bash
./gradlew :wow-webflux:test \
  --tests 'me.ahoo.wow.webflux.route.snapshot.SnapshotAggregationHandlerFunctionTest' \
  --tests 'me.ahoo.wow.webflux.route.query.QueryBodyExtractorTest' \
  --tests 'me.ahoo.wow.webflux.route.query.HttpQueryGuardFilterTest' \
  :wow-spring-boot-starter:test \
  --tests 'me.ahoo.wow.spring.boot.starter.webflux.WebFluxAutoConfigurationTest'
```

Expected: missing extractor/factory compilation failures.

- [ ] **Step 3: Implement route with existing machinery**

Add `AGGREGATION_QUERY_EXTRACTOR`. For this type only, allow both `filter` and legacy `condition` to be absent so the data-class `MatchAllFilter` default remains valid; still reject both being present. Decode through the same strict filter-value path and apply scalar-equality checks to the root filter and every Element filter. Rewrite the root filter with `RewriteRequestFilter`, call `SnapshotQueryHandler.aggregate`, write the raw request Reactor context, and return via existing `toServerResponse` so JSON arrays and SSE share established behavior.

Add `BuiltInHttpRouteHandlerKeys.Snapshot.AGGREGATION`, then add the factory once to `QueryRouteModule`; do not add a new route module or auto-configuration bean.

Extend Guard dispatch:

```kotlin
is AggregationQuery -> {
    validateResultSize(query.limit, "aggregation")
    validateFilters(listOf(query.filter) + query.elements.map(AggregationElement::filter), query.filter)
    require(allowExpensiveOperators || query.elements.isEmpty())
    val metricAliases = query.metrics.mapTo(hashSetOf(), AggregationMetric::alias)
    require(allowExpensiveOperators || query.sort.none { it.field in metricAliases })
}
```

Do not add properties or constructor parameters.

- [ ] **Step 4: Run WebFlux tests**

Run the Step 2 command. Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add wow-webflux/src/main/kotlin/me/ahoo/wow/webflux \
  wow-webflux/src/test/kotlin/me/ahoo/wow/webflux \
  wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contract/BuiltInHttpRoutes.kt \
  wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/webflux/route/QueryRouteModule.kt \
  wow-spring-boot-starter/src/test/kotlin/me/ahoo/wow/spring/boot/starter/webflux/WebFluxAutoConfigurationTest.kt
git commit -m "feat(webflux): expose snapshot aggregation route"
```

### Task 10: OpenAPI contract

**Files:**
- Modify: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/QueryComponent.kt`
- Modify: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contributor/QueryContractComponentSupport.kt`
- Modify: `wow-openapi/src/main/kotlin/me/ahoo/wow/openapi/contributor/aggregate/snapshot/SnapshotRouteContributor.kt`
- Modify: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/ExampleDomainOpenAPITest.kt`
- Modify snapshot: `wow-openapi/src/test/resources/openapi/example-domain-openapi.snapshot.json`
- Modify snapshot: `wow-openapi/src/test/resources/openapi/example-domain-contract.snapshot.json`

**Interfaces:**
- Consumes: generic `AggregationQuery` schema and existing aggregate `*AggregatedFields` component reference.
- Produces: one aggregation RequestBody, one dynamic-row array response and one aggregate route contract.

- [ ] **Step 1: Write failing OpenAPI assertions**

```kotlin
@Test
fun `snapshot aggregation should reuse aggregate field schema`() {
    val openApi = exampleDomainOpenAPI()
    val requestBody = openApi.components.requestBodies.getValue("example.cart.AggregationQuery")
    requestBody.extensions.getValue("x-wow-query-fields")
        .assert().isEqualTo(mapOf("\$ref" to "#/components/schemas/example.cart.CartAggregatedFields"))
    requestBody.content["application/json"]!!.schema.`$ref`
        .assert().isEqualTo("#/components/schemas/wow.api.query.AggregationQuery")
}
```

- [ ] **Step 2: Run OpenAPI test and verify red**

```bash
./gradlew :wow-openapi:test --tests 'me.ahoo.wow.openapi.ExampleDomainOpenAPITest'
```

Expected: aggregation route/request body is absent.

- [ ] **Step 3: Add one route/component path**

Add an `.AggregationQuery` suffix and `aggregatedAggregationQueryRequestBody(aggregateMetadata)` that calls the existing `aggregatedFieldsSchema` and uses `schema(AggregationQuery::class.java)`. Add the matching `HttpRequestBody`/`HttpResponse` reference helpers in `QueryContractComponentSupport`. The response is an array whose item is an object with free-form nullable properties; do not introduce alias-specific result schemas.

Add the route to each existing tenant/owner query variant with suffix `snapshot/aggregation`, streaming accept values, request timeout and too-many-requests responses.

- [ ] **Step 4: Update snapshots through the supported switch and run checks**

```bash
./gradlew :wow-openapi:test -Dwow.snapshot.update=true
./gradlew :wow-openapi:check
```

Expected: pass; aggregation references the single existing `*AggregatedFields` enum and does not create type-specific field schemas.

- [ ] **Step 5: Commit**

```bash
git add wow-openapi/src/main/kotlin/me/ahoo/wow/openapi \
  wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/ExampleDomainOpenAPITest.kt \
  wow-openapi/src/test/resources/openapi/example-domain-openapi.snapshot.json \
  wow-openapi/src/test/resources/openapi/example-domain-contract.snapshot.json
git commit -m "feat(openapi): publish snapshot aggregation contract"
```

### Task 11: ApiClient contract

**Files:**
- Create: `wow-apiclient/src/main/kotlin/me/ahoo/wow/apiclient/query/SnapshotAggregationQueryApi.kt`
- Create: `wow-apiclient/src/main/kotlin/me/ahoo/wow/apiclient/query/ReactiveSnapshotAggregationQueryApi.kt`
- Create: `wow-apiclient/src/main/kotlin/me/ahoo/wow/apiclient/query/SynchronousSnapshotAggregationQueryApi.kt`
- Create: `wow-apiclient/src/test/kotlin/me/ahoo/wow/apiclient/query/SnapshotAggregationQueryApiTest.kt`

**Interfaces:**
- Produces: independent synchronous/reactive aggregation clients; does not modify existing composite snapshot APIs.

- [ ] **Step 1: Write a failing interface contract test**

```kotlin
@Test
fun `aggregation API should use the snapshot aggregation resource`() {
    val method = SnapshotAggregationQueryApi::class.java.getMethod("aggregate", AggregationQuery::class.java)
    method.getAnnotation(PostExchange::class.java).value
        .assert().isEqualTo("snapshot/aggregation")
}
```

- [ ] **Step 2: Run ApiClient test and verify red**

```bash
./gradlew :wow-apiclient:test --tests 'me.ahoo.wow.apiclient.query.SnapshotAggregationQueryApiTest'
```

Expected: compilation fails because the interfaces do not exist.

- [ ] **Step 3: Implement three small interfaces and query extensions**

```kotlin
const val SNAPSHOT_AGGREGATION_RESOURCE_NAME = "$SNAPSHOT_RESOURCE_NAME/aggregation"

interface SnapshotAggregationQueryApi<R> : SnapshotQueryApi {
    @PostExchange(SNAPSHOT_AGGREGATION_RESOURCE_NAME)
    fun aggregate(@RequestBody query: AggregationQuery): R
}

interface ReactiveSnapshotAggregationQueryApi :
    SnapshotAggregationQueryApi<Flux<Map<String, Any?>>>

interface SynchronousSnapshotAggregationQueryApi :
    SnapshotAggregationQueryApi<List<Map<String, Any?>>>
```

Add `AggregationQuery.query(api)` extensions in the reactive and synchronous files. Do not add these interfaces to `ReactiveSnapshotQueryApi` or `SynchronousSnapshotQueryApi`.

- [ ] **Step 4: Run ApiClient test**

Run the Step 2 command. Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add wow-apiclient/src/main/kotlin/me/ahoo/wow/apiclient/query \
  wow-apiclient/src/test/kotlin/me/ahoo/wow/apiclient/query/SnapshotAggregationQueryApiTest.kt
git commit -m "feat(apiclient): add snapshot aggregation API"
```

### Task 12: Documentation and full verification

**Files:**
- Modify: `documentation/docs/en/guide/query.md`
- Modify: `documentation/docs/zh/guide/query.md`
- Modify: `documentation/docs/en/guide/extensions/mongo.md`
- Modify: `documentation/docs/zh/guide/extensions/mongo.md`
- Modify: `documentation/docs/en/guide/extensions/elasticsearch.md`
- Modify: `documentation/docs/zh/guide/extensions/elasticsearch.md`
- Modify: `documentation/docs/en/guide/extensions/webflux.md`
- Modify: `documentation/docs/zh/guide/extensions/webflux.md`
- Modify: `documentation/docs/en/guide/extensions/apiclient.md`
- Modify: `documentation/docs/zh/guide/extensions/apiclient.md`

**Interfaces:**
- Documents the exact shipped API and explicit non-goals; adds no runtime code.

- [ ] **Step 1: Add concise bilingual examples**

Document the approved DSL and equivalent JSON, including:

```kotlin
aggregation {
    expand("state.orders") { "status" eq "PAID" }
    expand("lines") { "quantity" gt 0 }
    terms("productId", "product")
    sum("amount", "total")
    sort { "total".desc() }
    limit(20)
}
```

State that later Element paths and filters are relative, custom serializer/mapping equivalence is not guaranteed, masker is ignored, metric sort is expensive, and Batch/arithmetic are not included.

- [ ] **Step 2: Run focused module checks**

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
```

Expected: build succeeds with zero test failures and no Detekt violations.

- [ ] **Step 3: Run both backend integration contracts**

```bash
./gradlew :wow-mongo:integrationTest \
  --tests 'me.ahoo.wow.mongo.query.snapshot.MongoSnapshotQueryServiceTest' \
  :wow-elasticsearch:integrationTest \
  --tests 'me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchSnapshotQueryServiceTest'
```

Expected: both pass the shared aggregation TCK.

- [ ] **Step 4: Build documentation**

```bash
cd documentation
pnpm docs:build
```

Expected: VitePress build exits zero.

- [ ] **Step 5: Verify the live OpenAPI contract**

Build and start the example distribution on an available localhost port, poll health, fetch `/v3/api-docs`, assert the aggregation route and references, then stop the process in a shell trap:

```bash
./gradlew :example-server:installDist
```

Required assertions against the fetched JSON:

```text
components.requestBodies.example.cart.AggregationQuery exists
its x-wow-query-fields.$ref points to example.cart.CartAggregatedFields
its application/json schema references wow.api.query.AggregationQuery
the snapshot/aggregation operation returns an array of dynamic objects
```

Keep the response and logs in a temporary directory and do not add a verifier script or task to the repository.

- [ ] **Step 6: Check the final diff for scope**

```bash
git diff --check
git status --short
git diff --stat origin/main...HEAD
rg -n "AggregationFieldCatalog|maxAggregationElements|maxAggregationMetrics|SnapshotAggregationQueryFilter" \
  wow-* test documentation || true
```

Expected: no whitespace errors, no unintended generated output, and none of the explicitly rejected abstractions/configuration names.

- [ ] **Step 7: Commit documentation**

```bash
git add documentation/docs/en/guide documentation/docs/zh/guide
git commit -m "docs: document snapshot elements aggregation"
```

- [ ] **Step 8: Request final review**

Invoke `superpowers:requesting-code-review` against the exact final commit. Address only verified correctness or contract findings; reject requests to restore the field catalog, custom mapping proof, dynamic schema rewriting or speculative compatibility infrastructure unless the user changes the approved scope.
