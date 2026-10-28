/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.query.memory

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.AggregationDatePart
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationGroupScope
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.DateDiffUnit
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EndsWithFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.ExpressionFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.IsEmptyStringFilter
import me.ahoo.wow.api.query.IsNotEmptyStringFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.LessThanOrEqualFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.NotExistsFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.TodayFilter
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.CursorPosition
import me.ahoo.wow.query.backend.GroupWindow
import me.ahoo.wow.query.backend.PageWindow
import me.ahoo.wow.query.backend.QueryBackendStorages
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.dataset.QueryDatasets
import me.ahoo.wow.query.error.QueryExecutionException
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.query.schema.QueryValueKind
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.Temporal
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.math.BigInteger
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * The reference backend's own semantics, beyond what the backend specs hold every backend to: each filter operator on
 * values of every shape, the sort order across kinds, the page windows, projections through objects and arrays, and
 * every group, metric and expression of an aggregation. It runs on one in-memory table whose rows hold their fields at
 * the root.
 */
class InMemoryReferenceSemanticsTest {
    private val storage = InMemoryDatasetStorage()
    private val schemaAndBackend by lazy { runBlocking { target() } }
    private val schema: QueryModelSchema get() = schemaAndBackend.first
    private val backend: InMemoryQueryBackend get() = schemaAndBackend.second

    private suspend fun target(): Pair<QueryModelSchema, InMemoryQueryBackend> {
        val dataset = dataset(storage.put(TABLE, ROWS.map { WowJson.mapper.readTree(it) as ObjectNode }))
        val datasets = QueryDatasets(listOf(dataset))
        val catalog = QuerySchemaCatalog(
            storages = QueryBackendStorages(datasetBindings = storage, datasets = datasets),
            compiler = QueryModelCompiler.of(datasets = datasets),
            datasets = datasets,
        )
        return catalog.schema(dataset.name, QueryModel.DATASET) to
            checkNotNull(storage.binding(dataset)).backend as InMemoryQueryBackend
    }

    /** The table's dataset: a flat record of every shape the backend reads. */
    private fun dataset(binding: ObjectNode): QueryDataset =
        QueryDataset.of(AggregateType("memory", TABLE)) {
            tenant("tenant_id")
            identity("id")
            storage(binding)
            field("tenant_id") { types(QueryValueType.STRING) }
            field("id") { types(QueryValueType.STRING) }
            field("name") {
                types(QueryValueType.STRING)
                nullable(true)
            }
            field("note") {
                types(QueryValueType.STRING)
                nullable(true)
            }
            field("price") {
                types(QueryValueType.DECIMAL)
                nullable(true)
            }
            field("qty") {
                types(QueryValueType.INTEGER)
                nullable(true)
            }
            field("flag") {
                types(QueryValueType.BOOLEAN)
                nullable(true)
            }
            field("day") {
                types(QueryValueType.STRING)
                semantic(Temporal.Date)
                nullable(true)
            }
            field("at") {
                types(QueryValueType.INTEGER)
                temporalEpoch(TimeUnit.SECONDS)
                nullable(true)
            }
            field("tags") {
                kind(QueryValueKind.ARRAY)
                items {
                    types(QueryValueType.STRING)
                    nullable(false)
                }
                nullable(true)
            }
            field("lines") {
                kind(QueryValueKind.ARRAY)
                items {
                    kind(QueryValueKind.OBJECT)
                    property("sku") { types(QueryValueType.STRING) }
                    property("price") { types(QueryValueType.DECIMAL) }
                }
                nullable(true)
            }
        }

    private fun ids(filter: FilterExpression, sort: List<Sort> = emptyList()): List<String> = runBlocking {
        backend.stream(QueryAdmission.Trusted.list(ListQuery(filter, sort = sort), schema)).toList()
            .map { it.path("id").stringValue() }
    }

    private fun matches(filter: FilterExpression): Set<String> = ids(filter).toSet()

    private fun aggregate(query: AggregationQuery, window: GroupWindow = GroupWindow.All): List<ObjectNode> =
        runBlocking { backend.aggregate(QueryAdmission.Trusted.aggregate(query, schema), window).toList() }

    /** Filters evaluated directly, past admission: what admission would refuse still reaches a backend's table. */
    private fun direct(filter: FilterExpression, row: Int = 0): Boolean {
        val admitted = QueryAdmission.Trusted.list(ListQuery(MatchAllFilter), schema)
        return InMemoryFilters(admitted).matches(filter, RecordScope(storage.rows(TABLE)[row]))
    }

    // Filters

    @Test
    fun `logical filters and the system fields`() {
        matches(OrFilter(listOf(EqualFilter(NAME, "Alpha"), EqualFilter(NAME, "Beta")))).assert()
            .containsExactlyInAnyOrder("r1", "r2")
        matches(NorFilter(listOf(EqualFilter(NAME, "Alpha"), EqualFilter(NAME, "Beta")))).assert()
            .containsExactlyInAnyOrder("r3", "r4", "r5")
        matches(MatchNoneFilter).assert().isEmpty()
        matches(IdFilter("r3")).assert().containsExactly("r3")
        matches(IdsFilter(listOf("r1", "r5"))).assert().containsExactlyInAnyOrder("r1", "r5")
        matches(TenantIdFilter("t2")).assert().containsExactly("r5")
    }

    @Test
    fun `equality and membership by value, on scalars and arrays`() {
        matches(EqualFilter(PRICE, 10)).assert().containsExactly("r1")
        matches(EqualFilter(TAGS, "b")).assert().containsExactlyInAnyOrder("r1", "r2")
        matches(NotEqualFilter(TAGS, "b")).assert().containsExactlyInAnyOrder("r3", "r4", "r5")
        matches(InFilter(QTY, listOf(1, 3))).assert().containsExactlyInAnyOrder("r1", "r3")
        matches(NotInFilter(QTY, listOf(1, 3))).assert().containsExactlyInAnyOrder("r2", "r4", "r5")
        matches(ContainsAllFilter(TAGS, listOf("a", "b"))).assert().containsExactly("r1")
        matches(EqualFilter(FLAG, true)).assert().containsExactlyInAnyOrder("r1", "r3")
    }

    @Test
    fun `ranges compare numbers by value and dates as instants`() {
        matches(GreaterThanFilter(PRICE, 20)).assert().containsExactlyInAnyOrder("r3", "r5")
        matches(GreaterThanOrEqualFilter(PRICE, 20)).assert().containsExactlyInAnyOrder("r2", "r3", "r5")
        matches(LessThanFilter(PRICE, 20)).assert().containsExactly("r1")
        matches(LessThanOrEqualFilter(QTY, 2)).assert().containsExactlyInAnyOrder("r1", "r2", "r5")
        matches(BetweenFilter(DAY, "2026-01-06", "2026-01-07T23:59:59Z")).assert()
            .containsExactlyInAnyOrder("r2", "r3")
        matches(GreaterThanFilter(DAY, "2026-01-07T00:00:00+08:00")).assert()
            .containsExactlyInAnyOrder("r3", "r4")
        // A stored day that is no instant, or a bound that is none, orders against nothing.
        matches(GreaterThanFilter(DAY, "not a day")).assert().isEmpty()
    }

    @Test
    fun `text predicates, case sensitive and not`() {
        matches(ContainsFilter(NAME, "lph")).assert().containsExactly("r1")
        matches(ContainsFilter(NAME, "LPH", StringComparison.CASE_INSENSITIVE)).assert().containsExactly("r1")
        matches(StartsWithFilter(NAME, "be", StringComparison.CASE_INSENSITIVE)).assert().containsExactly("r2")
        matches(StartsWithFilter(NAME, "be")).assert().isEmpty()
        matches(EndsWithFilter(NAME, "ma")).assert().containsExactly("r3")
        matches(ContainsFilter(TAGS, "c")).assert().containsExactly("r3")
    }

    @Test
    fun `presence and emptiness`() {
        matches(IsNullFilter(NOTE)).assert().containsExactlyInAnyOrder("r3", "r4", "r5")
        matches(IsNotNullFilter(NOTE)).assert().containsExactlyInAnyOrder("r1", "r2")
        matches(ExistsFilter(NOTE)).assert().containsExactlyInAnyOrder("r1", "r2", "r3")
        matches(NotExistsFilter(NOTE)).assert().containsExactlyInAnyOrder("r4", "r5")
        matches(IsEmptyFilter(TAGS)).assert().containsExactly("r4")
        matches(IsEmptyStringFilter(NOTE)).assert().containsExactly("r2")
        matches(IsNotEmptyStringFilter(NOTE)).assert().containsExactly("r1")
    }

    @Test
    fun `an element match reads each element's fields together`() {
        val line = ElementMatchFilter(
            LINES,
            AndFilter(listOf(EqualFilter(SKU, "A"), GreaterThanFilter(ELEMENT_PRICE, 6))),
        )
        matches(line).assert().containsExactly("r2")
    }

    @Test
    fun `an expression filter compares its value by each operator`() {
        val total = AggregationExpression.Binary(
            AggregationExpressionOperator.MULTIPLY,
            AggregationExpression.Field(PRICE),
            AggregationExpression.Field(QTY),
        )
        matches(ExpressionFilter(total, ComparisonOperator.EQ, 10.0)).assert().containsExactly("r1")
        matches(ExpressionFilter(total, ComparisonOperator.NE, 10.0)).assert()
            .containsExactlyInAnyOrder("r2", "r3", "r5")
        matches(ExpressionFilter(total, ComparisonOperator.GT, 40.0)).assert().containsExactly("r3")
        matches(ExpressionFilter(total, ComparisonOperator.GTE, 40.0)).assert().containsExactlyInAnyOrder("r2", "r3")
        matches(ExpressionFilter(total, ComparisonOperator.LT, 40.0)).assert().containsExactlyInAnyOrder("r1", "r5")
        matches(ExpressionFilter(total, ComparisonOperator.LTE, 10.0)).assert().containsExactlyInAnyOrder("r1", "r5")
        val sum = AggregationExpression.Binary(
            AggregationExpressionOperator.ADD,
            AggregationExpression.Field(PRICE),
            AggregationExpression.Constant(1.0),
        )
        matches(ExpressionFilter(sum, ComparisonOperator.EQ, 11.0)).assert().containsExactly("r1")
        val less = AggregationExpression.Binary(
            AggregationExpressionOperator.SUBTRACT,
            AggregationExpression.Field(PRICE),
            AggregationExpression.Field(QTY),
        )
        matches(ExpressionFilter(less, ComparisonOperator.EQ, 9.0)).assert().containsExactly("r1")
        // A division by zero has no value, so it matches nothing.
        val ratio = AggregationExpression.Binary(
            AggregationExpressionOperator.DIVIDE,
            AggregationExpression.Field(PRICE),
            AggregationExpression.Field(QTY),
        )
        matches(
            ExpressionFilter(ratio, ComparisonOperator.EQ, 10.0)
        ).assert().containsExactlyInAnyOrder("r1", "r2", "r3")
        matches(ExpressionFilter(ratio, ComparisonOperator.GTE, 0.0)).assert().doesNotContain("r5")
        val span = AggregationExpression.DateDiff(DAY, AT, DateDiffUnit.HOUR)
        matches(ExpressionFilter(span, ComparisonOperator.EQ, 1.0)).assert().containsExactly("r1")
    }

    @Test
    fun `what admission keeps from a backend fails as an execution error`() {
        assertThrows<QueryExecutionException> { direct(SearchFilter("alpha")) }
        assertThrows<QueryExecutionException> { direct(TodayFilter(DAY)) }
    }

    // Sort and pages

    @Test
    fun `sorts by every key in turn, a missing value lowest`() {
        ids(MatchAllFilter, listOf(Sort(FLAG, Sort.Direction.DESC), Sort(PRICE, Sort.Direction.ASC))).assert()
            .containsExactly("r1", "r3", "r2", "r5", "r4")
        ids(MatchAllFilter, listOf(Sort(TAGS, Sort.Direction.DESC))).assert().first().isEqualTo("r3")
        ids(MatchAllFilter, listOf(Sort(NOTE, Sort.Direction.ASC), Sort(ID, Sort.Direction.DESC))).assert()
            .containsExactly("r5", "r4", "r3", "r2", "r1")
    }

    @Test
    fun `offset pages count their total, bounded or not`() = runBlocking {
        val query = QueryAdmission.Trusted.list(
            ListQuery(MatchAllFilter, sort = listOf(Sort(ID, Sort.Direction.ASC))),
            schema
        )
        val page = backend.page(query, PageWindow.Offset(offset = 1, limit = 2, withTotal = true))
        page.rows.map { it.path("id").stringValue() }.assert().containsExactly("r2", "r3")
        page.total.assert().isEqualTo(5L)
        backend.page(query, PageWindow.Offset(0, 2, withTotal = true, totalLimit = 3)).total.assert().isEqualTo(4L)
        backend.page(query, PageWindow.Offset(0, 2, withTotal = false)).total.assert().isNull()
        backend.count(QueryAdmission.Trusted.count(CountQuery(MatchAllFilter, 2), schema)).assert().isEqualTo(3L)
        backend.count(QueryAdmission.Trusted.count(CountQuery(MatchAllFilter), schema)).assert().isEqualTo(5L)
    }

    @Test
    fun `keyset pages continue after a position of every kind`() = runBlocking {
        val sort = listOf(Sort(PRICE, Sort.Direction.DESC), Sort(ID, Sort.Direction.ASC))
        val query = QueryAdmission.Trusted.list(ListQuery(MatchAllFilter, sort = sort), schema)
        val first = backend.page(query, PageWindow.Keyset(null, 2))
        first.rows.map { it.path("id").stringValue() }.assert().containsExactly("r3", "r5")
        val second = backend.page(query, PageWindow.Keyset(first.positions!!.last(), 10))
        second.rows.map { it.path("id").stringValue() }.assert().containsExactly("r2", "r1", "r4")
        second.positions!!.last().values.assert().containsExactly(null, "r4")
        val after = backend.page(query, PageWindow.Keyset(CursorPosition(listOf(20, "r2")), 10))
        after.rows.map { it.path("id").stringValue() }.assert().containsExactly("r1", "r4")
        val byFlag = QueryAdmission.Trusted.list(
            ListQuery(MatchAllFilter, sort = listOf(Sort(FLAG, Sort.Direction.ASC), Sort(ID, Sort.Direction.ASC))),
            schema
        )
        backend.page(byFlag, PageWindow.Keyset(CursorPosition(listOf(false, "r2")), 10)).rows
            .map { it.path("id").stringValue() }.assert().containsExactly("r1", "r3")
        backend.page(byFlag, PageWindow.Keyset(null, 1)).positions!!.single().values.assert()
            .containsExactly(null, "r4")
    }

    @Test
    fun `projections include and exclude through objects and arrays`() = runBlocking {
        val include = QueryAdmission.Trusted.list(
            ListQuery(
                IdFilter("r1"),
                projection = Projection(include = listOf(ID, NAME, LINE_SKU, QueryField("missing.path"))),
            ),
            schema,
        )
        val included = backend.stream(include).toList().single()
        included.path("name").stringValue().assert().isEqualTo("Alpha")
        included.path("lines").toList().map { it.path("sku").stringValue() }.assert().containsExactly("A", "B")
        included.path("lines").first().has("price").assert().isFalse()
        included.has("price").assert().isFalse()
        val exclude = QueryAdmission.Trusted.list(
            ListQuery(IdFilter("r1"), projection = Projection(exclude = listOf(PRICE, LINE_PRICE, QueryField("x.y")))),
            schema,
        )
        val excluded = backend.stream(exclude).toList().single()
        excluded.has("price").assert().isFalse()
        excluded.path("lines").first().has("price").assert().isFalse()
        excluded.path("lines").first().path("sku").stringValue().assert().isEqualTo("A")
        val both = QueryAdmission.Trusted.list(
            ListQuery(IdFilter("r1"), projection = Projection(include = listOf(LINES), exclude = listOf(LINE_PRICE))),
            schema,
        )
        backend.stream(both).toList().single().path("lines").first().has("price").assert().isFalse()
        backend.stream(QueryAdmission.Trusted.list(ListQuery(MatchAllFilter, limit = 2), schema)).toList()
            .size.assert().isEqualTo(2)
    }

    // Aggregation

    @Test
    fun `terms, histograms and their keys of missing values`() {
        val rows = aggregate(
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(NOTE, alias = "note", missingKey = "(none)")),
                metrics = listOf(AggregationMetric.Count("n")),
                sort = listOf(Sort(QueryField("note"), Sort.Direction.ASC)),
            ),
        )
        rows.associate { it.path("note").stringValue() to it.path("n").longValue() }.assert()
            .containsEntry("(none)", 3L).containsEntry("", 1L).containsEntry("x", 1L)
        val nullKeys = aggregate(
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(NOTE, alias = "note")),
                metrics = listOf(AggregationMetric.Count("n")),
            ),
        )
        nullKeys.count { it.path("note").isNull }.assert().isEqualTo(1)
        val byExpression = aggregate(
            AggregationQuery(
                groupBy = listOf(
                    AggregationGroup.Terms(
                        alias = "double",
                        expression = AggregationExpression.Binary(
                            AggregationExpressionOperator.MULTIPLY,
                            AggregationExpression.Field(QTY),
                            AggregationExpression.Constant(2.0),
                        ),
                    ),
                ),
                metrics = listOf(AggregationMetric.Count("n")),
                sort = listOf(Sort(QueryField("double"), Sort.Direction.DESC)),
            ),
            GroupWindow.First(2),
        )
        byExpression.map { it.path("double").doubleValue() }.assert().containsExactly(8.0, 6.0)
        val histogram = aggregate(
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Histogram(PRICE, alias = "band", interval = 20.0)),
                metrics = listOf(AggregationMetric.Count("n")),
                sort = listOf(Sort(QueryField("band"), Sort.Direction.ASC)),
            ),
        )
        histogram.map { it.path("band").let { band -> if (band.isNull) null else band.doubleValue() } }.assert()
            .containsExactly(null, 0.0, 20.0, 40.0)
        val expressionHistogram = aggregate(
            AggregationQuery(
                groupBy = listOf(
                    AggregationGroup.Histogram(
                        alias = "band",
                        interval = 2.0,
                        expression = AggregationExpression.Field(QTY),
                    ),
                ),
                metrics = listOf(AggregationMetric.Count("n")),
            ),
        )
        expressionHistogram.size.assert().isEqualTo(3)
    }

    @Test
    fun `date histograms bucket by every unit, and date parts by every part`() {
        val counts = AggregationDateUnit.entries.associateWith { unit ->
            aggregate(
                AggregationQuery(
                    groupBy = listOf(AggregationGroup.DateHistogram(AT, alias = "bucket", unit = unit)),
                    metrics = listOf(AggregationMetric.Count("n")),
                ),
            ).size
        }
        counts.getValue(AggregationDateUnit.YEAR).assert().isEqualTo(1)
        counts.getValue(AggregationDateUnit.QUARTER).assert().isEqualTo(1)
        counts.getValue(AggregationDateUnit.MONTH).assert().isEqualTo(1)
        counts.getValue(AggregationDateUnit.WEEK).assert().isEqualTo(1)
        counts.getValue(AggregationDateUnit.DAY).assert().isEqualTo(3)
        counts.getValue(AggregationDateUnit.SECOND).assert().isEqualTo(4)
        val parts = AggregationDatePart.entries.associateWith { part ->
            aggregate(
                AggregationQuery(
                    groupBy = listOf(
                        AggregationGroup.DatePart(DAY, alias = "part", part = part, timeZone = "Asia/Shanghai"),
                    ),
                    metrics = listOf(AggregationMetric.Count("n")),
                    sort = listOf(Sort(QueryField("part"), Sort.Direction.ASC)),
                ),
            ).map { it.path("part").intValue() }
        }
        parts.getValue(AggregationDatePart.DAY_OF_WEEK).assert().containsExactly(1, 2, 3, 4)
        parts.getValue(AggregationDatePart.DAY_OF_MONTH).assert().containsExactly(5, 6, 7, 8)
        parts.getValue(AggregationDatePart.MONTH_OF_YEAR).assert().containsExactly(1)
        parts.getValue(AggregationDatePart.HOUR_OF_DAY).assert().isNotEmpty()
    }

    @Test
    fun `every metric over the groups, filtered metrics included`() {
        val price = AggregationExpression.Field(PRICE)
        val rows = aggregate(
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms(TENANT, alias = "tenant")),
                metrics = AggregationFunction.entries.map { AggregationMetric.Numeric(it, price, it.name) } + listOf(
                    AggregationMetric.Count("flagged", filter = EqualFilter(FLAG, true)),
                    AggregationMetric.Any(NAME, "anyName"),
                    AggregationMetric.Any(NOTE, "anyNote", filter = NotExistsFilter(NOTE)),
                    AggregationMetric.DistinctCount(AggregationExpression.Field(TAGS), "tagCount"),
                    AggregationMetric.DistinctCount(
                        AggregationExpression.Binary(AggregationExpressionOperator.DIVIDE, price, price),
                        "ones",
                    ),
                    AggregationMetric.Percentile(price, 50.0, "median"),
                    AggregationMetric.Percentile(price, 50.0, "none", filter = MatchNoneFilter),
                    AggregationMetric.Numeric(AggregationFunction.SUM, price, "nothing", filter = MatchNoneFilter),
                    AggregationMetric.First(NAME, "first", orderBy = PRICE),
                    AggregationMetric.Last(NAME, "last", orderBy = PRICE),
                    AggregationMetric.First(NAME, "noFirst", orderBy = PRICE, filter = MatchNoneFilter),
                    derived("perFlag", AggregationExpressionOperator.DIVIDE, ref("SUM"), ref("flagged")),
                    derived("plus", AggregationExpressionOperator.ADD, ref("MIN"), DerivedExpression.Constant(1.0)),
                    derived("minus", AggregationExpressionOperator.SUBTRACT, ref("MAX"), ref("MIN")),
                    derived("times", AggregationExpressionOperator.MULTIPLY, ref("AVG"), DerivedExpression.Constant(2.0)),
                    derived("byZero", AggregationExpressionOperator.DIVIDE, ref("SUM"), DerivedExpression.Constant(0.0)),
                    derived("ofNull", AggregationExpressionOperator.ADD, ref("nothing"), DerivedExpression.Constant(1.0)),
                ),
                sort = listOf(Sort(QueryField("tenant"), Sort.Direction.ASC)),
            ),
        )
        val t1 = rows.first()
        t1.path("tenant").stringValue().assert().isEqualTo("t1")
        t1.path("SUM").doubleValue().assert().isEqualTo(60.0)
        t1.path("AVG").doubleValue().assert().isEqualTo(20.0)
        t1.path("MIN").doubleValue().assert().isEqualTo(10.0)
        t1.path("MAX").doubleValue().assert().isEqualTo(30.0)
        t1.path("VARIANCE").doubleValue().assert().isCloseTo(66.666, org.assertj.core.data.Offset.offset(0.01))
        t1.path("STDDEV").doubleValue().assert().isCloseTo(8.165, org.assertj.core.data.Offset.offset(0.01))
        t1.path("flagged").longValue().assert().isEqualTo(2L)
        t1.path("anyName").stringValue().assert().isEqualTo("Alpha")
        t1.path("anyNote").isNull.assert().isTrue()
        t1.path("tagCount").longValue().assert().isEqualTo(3L)
        t1.path("ones").longValue().assert().isEqualTo(1L)
        t1.path("median").doubleValue().assert().isEqualTo(20.0)
        t1.path("none").isNull.assert().isTrue()
        t1.path("nothing").isNull.assert().isTrue()
        t1.path("first").stringValue().assert().isEqualTo("Alpha")
        t1.path("last").stringValue().assert().isEqualTo("Gamma")
        t1.path("noFirst").isNull.assert().isTrue()
        t1.path("perFlag").doubleValue().assert().isEqualTo(30.0)
        t1.path("plus").doubleValue().assert().isEqualTo(11.0)
        t1.path("minus").doubleValue().assert().isEqualTo(20.0)
        t1.path("times").doubleValue().assert().isEqualTo(40.0)
        t1.path("byZero").isNull.assert().isTrue()
        t1.path("ofNull").isNull.assert().isTrue()
    }

    @Test
    fun `elements expand into groups, a root group keyed by its record`() {
        val rows = aggregate(
            AggregationQuery(
                elements = listOf(AggregationElement(LINES, GreaterThanFilter(ELEMENT_PRICE, 1))),
                groupBy = listOf(
                    AggregationGroup.Terms(SKU, alias = "sku"),
                    AggregationGroup.Terms(NAME, alias = "name", scope = AggregationGroupScope.ROOT),
                ),
                metrics = listOf(
                    AggregationMetric.Count("n"),
                    AggregationMetric.Numeric(
                        AggregationFunction.SUM,
                        AggregationExpression.Field(ELEMENT_PRICE),
                        "sum"
                    ),
                ),
                sort = listOf(
                    Sort(QueryField("name"), Sort.Direction.DESC),
                    Sort(QueryField("sku"), Sort.Direction.ASC),
                ),
            ),
        )
        rows.map { it.path("sku").stringValue() + "/" + it.path("name").stringValue() }.assert()
            .containsExactly("A/Beta", "A/Alpha", "B/Alpha")
        aggregate(
            AggregationQuery(filter = MatchNoneFilter, metrics = listOf(AggregationMetric.Count("n"))),
        ).assert().isEmpty()
    }

    // Values

    @Test
    fun `values read through objects, arrays and their indexes`() {
        val record = WowJson.mapper.readTree("""{"a":[{"b":1},{"b":[2,3]},5],"c":{"d":null}}""")
        record.valuesAt(QueryField("a.b")).flattened().map { it.intValue() }.assert().containsExactly(1, 2, 3)
        record.valuesAt(QueryField("a.1.b")).single().size().assert().isEqualTo(2)
        record.valuesAt(QueryField("a.9")).assert().isEmpty()
        record.valuesAt(QueryField("c.d.e")).assert().isEmpty()
        record.valuesAt(QueryField("c.d")).single().isNull.assert().isTrue()
        null.operandNode().isNull.assert().isTrue()
        val node: JsonNode = nodes.stringNode("x")
        node.operandNode().assert().isSameAs(node)
        listOf(1, 2).operandNode().isArray.assert().isTrue()
    }

    @Test
    fun `values equal and order by value across widths and kinds`() {
        nodes.numberNode(1).sameValue(nodes.numberNode(1.0)).assert().isTrue()
        nodes.numberNode(BigInteger.TEN).sameValue(nodes.numberNode(10L)).assert().isTrue()
        array(1, 2).sameValue(array(1, 2)).assert().isTrue()
        array(1, 2).sameValue(array(1)).assert().isFalse()
        array(1, 2).sameValue(array(1, 3)).assert().isFalse()
        array(1, 2).matches(nodes.numberNode(2)).assert().isTrue()
        array(1, 2).matches(array(1)).assert().isFalse()
        compareScalars(nodes.booleanNode(false), nodes.booleanNode(true)).assert().isEqualTo(-1)
        compareScalars(nodes.stringNode("a"), nodes.numberNode(1)).assert().isNull()
        orderOf(null, nodes.nullNode()).assert().isEqualTo(0)
        orderOf(nodes.numberNode(1), nodes.stringNode("a")).assert().isNegative()
        orderOf(nodes.booleanNode(true), nodes.stringNode("a")).assert().isPositive()
        orderOf(array(1), nodes.booleanNode(true)).assert().isPositive()
        orderOf(array(1), array(2)).assert().isNegative()
        listOf(array(3, 1, 2), nodes.nullNode()).sortKey(ascending = true)!!.intValue().assert().isEqualTo(1)
        listOf(array(3, 1, 2)).sortKey(ascending = false)!!.intValue().assert().isEqualTo(3)
        listOf<JsonNode>(nodes.nullNode()).sortKey(ascending = true).assert().isNull()
    }

    @Test
    fun `sort keys round trip as cursor positions`() {
        val keys: List<JsonNode?> = listOf(
            null,
            nodes.nullNode(),
            nodes.numberNode(7L),
            nodes.numberNode(BigInteger.TWO.pow(70)),
            nodes.numberNode(1.5),
            nodes.stringNode("s"),
            nodes.booleanNode(true),
            array(1),
        )
        keys.map { it.positionValue() }.assert().containsExactly(
            null,
            null,
            7L,
            BigInteger.TWO.pow(70).toDouble(),
            1.5,
            "s",
            true,
            "[1]",
        )
        listOf<Any?>(null, 7L, 7, 1.5, "s", true, listOf(1)).map { it.positionNode() }.map { it?.toString() }.assert()
            .containsExactly(null, "7", "7", "1.5", "\"s\"", "true", "[1]")
    }

    @Test
    fun `numbers and instants read from single values and singleton arrays`() {
        nodes.numberNode(2).numberOrNull().assert().isEqualTo(2.0)
        array(3).numberOrNull().assert().isEqualTo(3.0)
        array(3, 4).numberOrNull().assert().isNull()
        nodes.stringNode("3").numberOrNull().assert().isNull()
        nodes.numberNode(2L).epochMillis(Temporal.Epoch(TimeUnit.SECONDS)).assert().isEqualTo(2000L)
        nodes.numberNode(2L).epochMillis(null).assert().isEqualTo(2L)
        nodes.numberNode(2L).epochMillis(Temporal.Date).assert().isEqualTo(2L)
        array(5).epochMillis(null).assert().isEqualTo(5L)
        nodes.booleanNode(true).epochMillis(null).assert().isNull()
        nodes.stringNode("2026-01-05").epochMillis(Temporal.Date).assert().isEqualTo(JAN_5)
        parseInstant("2026-01-05T00:00:00Z")!!.toEpochMilli().assert().isEqualTo(JAN_5)
        parseInstant("2026-01-05T08:00:00+08:00")!!.toEpochMilli().assert().isEqualTo(JAN_5)
        parseInstant("2026-01-04T19:00:00-05:00")!!.toEpochMilli().assert().isEqualTo(JAN_5)
        parseInstant("2026-01-05T00:00:00")!!.toEpochMilli().assert().isEqualTo(JAN_5)
        parseInstant("2026-13-45").assert().isNull()
    }

    private fun array(vararg values: Int): JsonNode = nodes.arrayNode().also { array -> values.forEach(array::add) }

    private fun ref(metric: String): DerivedExpression = DerivedExpression.MetricRef(metric)

    private fun derived(
        alias: String,
        operator: AggregationExpressionOperator,
        left: DerivedExpression,
        right: DerivedExpression,
    ) = AggregationMetric.Derived(alias, DerivedExpression.Binary(operator, left, right))

    private companion object {
        const val TABLE = "items"
        val JAN_5: Long = Instant.parse("2026-01-05T00:00:00Z").toEpochMilli()
        val ID = QueryField("id")
        val TENANT = QueryField("tenant_id")
        val NAME = QueryField("name")
        val NOTE = QueryField("note")
        val PRICE = QueryField("price")
        val QTY = QueryField("qty")
        val FLAG = QueryField("flag")
        val DAY = QueryField("day")
        val AT = QueryField("at")
        val TAGS = QueryField("tags")
        val LINES = QueryField("lines")
        val LINE_SKU = QueryField("lines.sku")
        val LINE_PRICE = QueryField("lines.price")
        val SKU = QueryField("sku")
        val ELEMENT_PRICE = QueryField("price")

        /*
         * Five rows over two tenants. `day` stores dates in each ISO form; `at` stores epoch seconds an hour after
         * r1's day. r4 holds no price, no tags but an empty list, and no note; r5 is of the other tenant.
         */
        val ROWS = listOf(
            """{"id":"r1","tenant_id":"t1","name":"Alpha","note":"x","price":10,"qty":1,"flag":true,""" +
                """"day":"2026-01-05","at":${JAN_5 / 1000 + 3600},"tags":["a","b"],""" +
                """"lines":[{"sku":"A","price":5.0},{"sku":"B","price":5.0}]}""",
            """{"id":"r2","tenant_id":"t1","name":"Beta","note":"","price":20.0,"qty":2,"flag":false,""" +
                """"day":"2026-01-06T10:00:00Z","at":${JAN_5 / 1000 + 86_400},"tags":["b"],""" +
                """"lines":[{"sku":"A","price":20}]}""",
            """{"id":"r3","tenant_id":"t1","name":"Gamma","note":null,"price":30,"qty":3,"flag":true,""" +
                """"day":"2026-01-07T08:00:00+08:00","at":${JAN_5 / 1000 + 86_400 + 1},"tags":["c"],"lines":[]}""",
            """{"id":"r4","tenant_id":"t1","name":"Delta","qty":4,"day":"2026-01-08T09:30:00",""" +
                """"at":${JAN_5 / 1000 + 2 * 86_400},"tags":[]}""",
            """{"id":"r5","tenant_id":"t2","name":"Epsilon","price":50,"qty":0,"flag":false,"day":"bad"}""",
        )
    }
}
