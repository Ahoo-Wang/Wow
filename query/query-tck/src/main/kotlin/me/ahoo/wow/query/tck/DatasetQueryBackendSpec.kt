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

package me.ahoo.wow.query.tck

import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CountResult
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.DerivedExpression
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanOrEqualFilter
import me.ahoo.wow.api.query.HavingExpression
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.RunningFunction
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.TotalMode
import me.ahoo.wow.query.QueryAdmission
import me.ahoo.wow.query.QueryBackendStorages
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.ScopeQueryPolicy
import me.ahoo.wow.query.aggregate
import me.ahoo.wow.query.chunks
import me.ahoo.wow.query.countBounded
import me.ahoo.wow.query.cursor
import me.ahoo.wow.query.dataset.DatasetQueryBackend
import me.ahoo.wow.query.dataset.DatasetQueryBackendFactory
import me.ahoo.wow.query.dataset.DatasetQueryGateways
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.dataset.QueryDatasets
import me.ahoo.wow.query.list
import me.ahoo.wow.query.paged
import me.ahoo.wow.query.schema.AggregationSupport
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.query.schema.QueryValueKind
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.Temporal
import me.ahoo.wow.query.single
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A column of a TCK dataset table, in storage-neutral terms; each backend maps it to a native type. */
data class DatasetColumn(val name: String, val type: Type, val nullable: Boolean = false) {
    /**
     * A date column stores instants and is declared with the `TEMPORAL_DATE` semantic (a string at the query
     * interface). A backend whose arrays cannot be null stores a null string array as an empty array.
     */
    enum class Type { STRING, INTEGER, DECIMAL, DATE, STRING_ARRAY }
}

/**
 * A table the spec loads: its columns and rows, each value a `String`, `Long`, `BigDecimal`, `Instant`, a `List` of
 * strings or `null` as its column's type says. A row without a column's key leaves the value missing; a table stores
 * it as its storage stores a missing value (NULL, or an empty array).
 */
data class DatasetTable(val name: String, val columns: List<DatasetColumn>, val rows: List<Map<String, Any?>>)

/**
 * The dataset backend contract (decision D126; the 9.x `DatasetQueryBackendSpec` of wow-tck): every dataset query
 * backend passes it over a flat table: the filter semantics matrix, records (sort, projection, offset and keyset
 * pages, counts), aggregation, the dataset's tenant column, and the gateway's scope over it. A backend's test supplies
 * its factory ([createDatasetQueryBackendFactory]) and loads each table in [createTable].
 *
 * Queries run through trusted admission against the schema the Catalog compiles from the dataset's declaration and
 * the storage's facts, as a gateway would but without governance, except the gateway test.
 */
abstract class DatasetQueryBackendSpec {
    private lateinit var factory: DatasetQueryBackendFactory
    private lateinit var orders: Target

    protected abstract fun createDatasetQueryBackendFactory(): DatasetQueryBackendFactory

    /** Creates [table] in the storage, replacing any earlier one, loads its rows and returns the dataset's binding. */
    protected abstract suspend fun createTable(table: DatasetTable): ObjectNode

    /**
     * Semantic cases ([FilterSemantics]) this backend is known to diverge on, such as a table that cannot tell a
     * missing value from a null; each one is skipped and reported, never silently passed.
     */
    protected open val semanticDivergences: Set<String> = emptySet()

    /** A dataset's backend with the schema its Catalog compiled. */
    protected class Target(val dataset: QueryDataset, val backend: DatasetQueryBackend, val schema: QueryModelSchema) {
        suspend fun list(query: ListQuery): List<ObjectNode> =
            backend.list(QueryAdmission.Trusted.list(query, schema)).toList()

        suspend fun count(filter: FilterExpression): Long =
            backend.countBounded(QueryAdmission.Trusted.count(CountQuery(filter), schema)).count

        /** [query]'s groups; with [residual], the core computes every window the storage would. */
        suspend fun aggregate(query: AggregationQuery, residual: Boolean = false): List<ObjectNode> =
            backend.aggregate(
                QueryAdmission.Trusted.aggregate(query, if (residual) schema.withResidualWindows() else schema),
            ).toList()
    }

    @BeforeEach
    open fun setup() {
        factory = createDatasetQueryBackendFactory()
        orders = blocking { target(ORDERS) }
    }

    private fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }

    /** The dataset of [table], its backend, and the schema a Catalog over both compiles. */
    protected suspend fun target(table: DatasetTable): Target {
        val dataset = dataset(table)
        val datasets = QueryDatasets(listOf(dataset))
        val catalog = catalog(datasets)
        return Target(dataset, factory.create(dataset).backend, catalog.schema(dataset.name, QueryModel.DATASET))
    }

    private fun catalog(datasets: QueryDatasets) = QuerySchemaCatalog(
        storages = QueryBackendStorages(datasetFactory = factory, datasets = datasets),
        compiler = QueryModelCompiler.of(datasets = datasets),
        datasets = datasets,
    )

    @TestFactory
    fun `filter semantics matrix`(): List<DynamicTest> {
        val semantic = blocking { target(SEMANTIC) }
        return FilterSemantics.CASES.map { case ->
            DynamicTest.dynamicTest(case.id) {
                Assumptions.assumeFalse(case.id in semanticDivergences) {
                    "Known divergence from the canonical semantics: ${case.id}."
                }
                val probes = checkNotNull(SemanticProbe.ALL[case.shape])
                val byId = probes.associateBy(QueryBackendSpec::probeId)
                val filter = AndFilter(
                    listOf(
                        IdsFilter(probes.map(QueryBackendSpec::probeId)),
                        case.filter(QueryField(SEMANTIC_FIELDS.getValue(case.shape))),
                    ),
                )
                blocking { semantic.list(ListQuery(filter, limit = MAX_LIST)) }
                    .map { checkNotNull(byId[it.path(ID).stringValue()]) { "Unexpected record [$it]." }.name }
                    .toSet().assert().describedAs(case.id).isEqualTo(case.matches)
            }
        }
    }

    @Test
    fun `records sort, project and limit`() = blocking {
        val rows = orders.list(
            ListQuery(
                EqualFilter(TENANT_COLUMN, "t1"),
                projection = Projection(include = listOf(QueryField(ID), AMOUNT)),
                sort = listOf(Sort(AMOUNT, Sort.Direction.DESC)),
                limit = 3,
            ),
        )
        rows.map { it.path(ID).stringValue() }.assert().containsExactly("o3", "o2", "o1")
        rows.forEach { it.propertyNames().assert().containsExactlyInAnyOrder(ID, "amount") }
        rows.first().get("amount").decimalValue().assert().isEqualByComparingTo(BigDecimal("30.00"))
        Unit
    }

    @Test
    fun `the tenant filter reads the dataset's tenant column`() = blocking {
        orders.count(TenantIdFilter("t2")).assert().isEqualTo(1L)
        orders.count(TenantIdFilter("t1")).assert().isEqualTo(5L)
        Unit
    }

    @Test
    fun `a single record is the first in sort order, or none`() = blocking {
        val first = orders.backend.single(
            QueryAdmission.Trusted.single(
                SingleQuery(EqualFilter(TENANT_COLUMN, "t1"), sort = listOf(Sort(AMOUNT, Sort.Direction.ASC))),
                orders.schema,
            ),
        )
        first?.path(ID)?.stringValue().assert().isEqualTo("o4")
        orders.backend.single(QueryAdmission.Trusted.single(SingleQuery(EqualFilter(CITY, "Nowhere")), orders.schema))
            .assert().isNull()
        Unit
    }

    @Test
    fun `offset pages carry the total`() = blocking {
        val query = PagedQuery(
            MatchAllFilter,
            sort = listOf(Sort(QueryField(ID), Sort.Direction.ASC)),
            pagination = Pagination(2, 2),
            total = TotalMode.EXACT,
        )
        val page = orders.backend.paged(QueryAdmission.Trusted.paged(query, orders.schema))
        page.total.assert().isEqualTo(6L)
        page.list.map { it.path(ID).stringValue() }.assert().containsExactly("o3", "o4")
        Unit
    }

    @Test
    fun `counts are bounded by their limit`() = blocking {
        val count = orders.backend.countBounded(
            QueryAdmission.Trusted.count(CountQuery(MatchAllFilter, 3), orders.schema)
        )
        count.assert().isEqualTo(CountResult(3, exact = false))
        Unit
    }

    @Test
    fun `keyset pages walk every record once in sort order`() = blocking {
        Assumptions.assumeTrue(orders.schema.storage.paging.keyset != me.ahoo.wow.query.schema.SupportMode.NONE)
        walk(Sort(AMOUNT, Sort.Direction.DESC)).assert().containsExactly("o6", "o3", "o2", "o1", "o5", "o4")
        walk(Sort(CREATED_AT, Sort.Direction.ASC)).assert().containsExactly("o1", "o6", "o2", "o3", "o4", "o5")
        walk(Sort(CITY, Sort.Direction.ASC)).assert().containsExactly("o3", "o4", "o1", "o2", "o6", "o5")
        Unit
    }

    @Test
    fun `a stream reads keyset chunks with positions and resumes strictly after one (decision D253)`() = blocking {
        Assumptions.assumeTrue(orders.schema.storage.paging.keyset != me.ahoo.wow.query.schema.SupportMode.NONE)
        val sort = listOf(Sort(AMOUNT, Sort.Direction.DESC))
        fun chunks(after: String? = null) = orders.backend.chunks(
            QueryAdmission.Trusted.cursor(
                CursorQuery(MatchAllFilter, sort = sort, size = 2, cursor = after),
                orders.schema
            ),
        )
        val all = chunks().toList()
        all.map { it.record.path(ID).stringValue() }.assert().isEqualTo(walk(sort.single()))
        all.indices.forEach { index ->
            chunks(
                all[index].position
            ).toList().map { it.position }.assert().isEqualTo(all.drop(index + 1).map { it.position })
        }
        Unit
    }

    @Test
    fun `groups count, sum, average, bound, distinct-count and pick the first and last records`() = blocking {
        val rows = orders.aggregate(
            AggregationQuery(
                filter = EqualFilter(TENANT_COLUMN, "t1"),
                groupBy = listOf(AggregationGroup.Terms(CITY, "city")),
                metrics = listOf(
                    AggregationMetric.Count("orders"),
                    numeric(AggregationFunction.SUM, AMOUNT, "gmv"),
                    numeric(AggregationFunction.AVG, QueryField("quantity"), "quantity"),
                    numeric(AggregationFunction.MIN, AMOUNT, "smallest"),
                    numeric(AggregationFunction.MAX, AMOUNT, "largest"),
                    AggregationMetric.DistinctCount(AggregationExpression.Field(QueryField(ID)), "ids"),
                    AggregationMetric.First(QueryField(ID), "first", orderBy = CREATED_AT),
                    AggregationMetric.Last(QueryField(ID), "last", orderBy = CREATED_AT),
                ),
                sort = listOf(Sort(QueryField("orders"), Sort.Direction.DESC)),
            ),
        )
        rows.map { it.path("city").stringValue() }.assert().containsExactly("Beijing", "Hangzhou", "Shanghai")
        val beijing = rows.first()
        beijing.get("orders").asLong().assert().isEqualTo(2)
        beijing.get("gmv").decimalValue().assert().isEqualByComparingTo(BigDecimal("35.00"))
        beijing.get("quantity").asDouble().assert().isEqualTo(2.0)
        beijing.get("smallest").decimalValue().assert().isEqualByComparingTo(BigDecimal("5.00"))
        beijing.get("largest").decimalValue().assert().isEqualByComparingTo(BigDecimal("30.00"))
        beijing.get("ids").asLong().assert().isEqualTo(2)
        beijing.get("first").stringValue().assert().isEqualTo("o3")
        beijing.get("last").stringValue().assert().isEqualTo("o4")
        rows[1].get("gmv").decimalValue().assert().isEqualByComparingTo(BigDecimal("30.75"))
        Unit
    }

    @Test
    fun `having filters groups before the metric sort and limit`() = blocking {
        val rows = orders.aggregate(
            AggregationQuery(
                filter = EqualFilter(TENANT_COLUMN, "t1"),
                groupBy = listOf(AggregationGroup.Terms(CITY, "city")),
                metrics = listOf(numeric(AggregationFunction.SUM, AMOUNT, "gmv")),
                having = HavingExpression.Condition("gmv", ComparisonOperator.GT, 10.0),
                sort = listOf(Sort(QueryField("gmv"), Sort.Direction.ASC)),
                limit = 1,
            ),
        )
        rows.map { it.path("city").stringValue() }.assert().containsExactly("Hangzhou")
        Unit
    }

    @Test
    fun `date histograms key buckets by the local start of the unit`() = blocking {
        val zone = ZoneId.of("Asia/Shanghai")
        val rows = orders.aggregate(
            AggregationQuery(
                filter = EqualFilter(TENANT_COLUMN, "t1"),
                groupBy = listOf(AggregationGroup.DateHistogram(CREATED_AT, "day", AggregationDateUnit.DAY, zone.id)),
                metrics = listOf(AggregationMetric.Count("orders")),
            ),
        )
        fun day(month: Int, day: Int) = LocalDate.of(2025, month, day).atStartOfDay(zone).toInstant().toEpochMilli()
        rows.map { it.get("day").asLong() to it.get("orders").asLong() }.assert().containsExactly(
            day(1, 1) to 1L,
            day(1, 2) to 2L,
            day(2, 1) to 1L,
            day(2, 15) to 1L,
        )
        Unit
    }

    @Test
    fun `windows read every group before HAVING, alike in the storage and in the core`() = blocking {
        val query = AggregationQuery(
            filter = EqualFilter(TENANT_COLUMN, "t1"),
            groupBy = listOf(AggregationGroup.Terms(CITY, "city")),
            metrics = listOf(
                numeric(AggregationFunction.SUM, AMOUNT, "gmv"),
                AggregationMetric.Count("orders"),
                AggregationMetric.Derived(
                    "share",
                    DerivedExpression.Binary(
                        AggregationExpressionOperator.DIVIDE,
                        DerivedExpression.MetricRef("gmv"),
                        DerivedExpression.Total("gmv"),
                    ),
                ),
                AggregationMetric.Derived("rank", DerivedExpression.Rank("gmv")),
                AggregationMetric.Derived(
                    "cumulative",
                    DerivedExpression.Running("orders", Sort(QueryField("gmv"), Sort.Direction.DESC)),
                ),
            ),
            having = HavingExpression.Condition("rank", ComparisonOperator.LTE, 2.0),
            sort = listOf(Sort(QueryField("gmv"), Sort.Direction.DESC)),
        )
        listOf(false, true).forEach { residual ->
            val rows = orders.aggregate(query, residual)
            rows.map { it.path("city").stringValue() }.assert().containsExactly("Beijing", "Hangzhou")
            rows.map { it.get("share").asDouble() }.assert().containsExactly(35.0 / 73.5, 30.75 / 73.5)
            rows.map { it.get("rank").asDouble() }.assert().containsExactly(1.0, 2.0)
            rows.map { it.get("cumulative").asDouble() }.assert().containsExactly(2.0, 4.0)
        }
    }

    @Test
    fun `running windows follow their order within each partition, alike in the storage and in the core`() = blocking {
        val month = Sort(QueryField("month"), Sort.Direction.ASC)
        val query = AggregationQuery(
            filter = EqualFilter(TENANT_COLUMN, "t1"),
            groupBy = listOf(
                AggregationGroup.Terms(CITY, "city"),
                AggregationGroup.DateHistogram(CREATED_AT, "month", AggregationDateUnit.MONTH),
            ),
            metrics = listOf(
                numeric(AggregationFunction.SUM, AMOUNT, "gmv"),
                AggregationMetric.Derived("cumulative", DerivedExpression.Running("gmv", month, listOf("city"))),
                AggregationMetric.Derived(
                    "moving",
                    DerivedExpression.Running("gmv", month, frame = 2, function = RunningFunction.AVG),
                ),
            ),
        )
        listOf(false, true).forEach { residual ->
            val rows = orders.aggregate(query, residual)
            rows.map { it.path("city").stringValue() }.assert()
                .containsExactly("Beijing", "Beijing", "Hangzhou", "Shanghai")
            rows.map { it.get("cumulative").asDouble() }.assert().containsExactly(30.0, 35.0, 30.75, 7.75)
            // Along the month, ties by city: Beijing Jan, Hangzhou Jan, Beijing Feb, Shanghai Feb.
            rows.map { it.get("moving").asDouble() }.assert()
                .containsExactly(30.0, (30.75 + 5.0) / 2, (30.0 + 30.75) / 2, (5.0 + 7.75) / 2)
        }
    }

    @Test
    fun `previous reads the earlier period over the shifted range`() = blocking {
        val rows = orders.aggregate(
            AggregationQuery(
                filter = AndFilter(
                    listOf(
                        EqualFilter(TENANT_COLUMN, "t1"),
                        GreaterThanOrEqualFilter(CREATED_AT, "2025-02-01T00:00:00Z"),
                    ),
                ),
                groupBy = listOf(
                    AggregationGroup.Terms(CITY, "city"),
                    AggregationGroup.DateHistogram(CREATED_AT, "month", AggregationDateUnit.MONTH),
                ),
                metrics = listOf(
                    numeric(AggregationFunction.SUM, AMOUNT, "gmv"),
                    AggregationMetric.Derived("previous", DerivedExpression.Previous("gmv", "month")),
                ),
            ),
        )
        rows.map { it.path("city").stringValue() }.assert().containsExactly("Beijing", "Shanghai")
        rows.map { it.get("previous").takeUnless { node -> node.isNull }?.asDouble() }.assert()
            .containsExactly(30.0, null)
        Unit
    }

    @Test
    fun `value metrics skip missing values and an empty input keeps the empty summary`() = blocking {
        val discount = QueryField("discount")
        val discounts = orders.aggregate(
            AggregationQuery(
                filter = EqualFilter(TENANT_COLUMN, "t1"),
                metrics = listOf(
                    numeric(AggregationFunction.AVG, discount, "discount"),
                    AggregationMetric.Count("discounted", IsNotNullFilter(discount)),
                ),
            ),
        ).single()
        (discounts.get("discount").asDouble() - 0.35 / 3).assert().isBetween(-EPSILON, EPSILON)
        discounts.get("discounted").asLong().assert().isEqualTo(3)
        val empty = orders.aggregate(
            AggregationQuery(
                filter = EqualFilter(CITY, "Nowhere"),
                metrics = listOf(
                    AggregationMetric.Count("orders"),
                    numeric(AggregationFunction.SUM, AMOUNT, "gmv"),
                    AggregationMetric.DistinctCount(AggregationExpression.Field(QueryField(ID)), "ids"),
                ),
            ),
        ).single()
        empty.get("orders").asLong().assert().isEqualTo(0)
        empty.get("gmv").isNull.assert().isTrue()
        empty.get("ids").asLong().assert().isEqualTo(0)
        Unit
    }

    @Test
    fun `the gateway restricts every query to the caller's tenant column`() = blocking {
        val datasets = QueryDatasets(listOf(orders.dataset))
        val gateways = DatasetQueryGateways.of(
            datasets,
            catalog(datasets),
            factory,
            policies = listOf(ScopeQueryPolicy())
        )
        val gateway = checkNotNull(gateways[orders.dataset.name])
        val t2 = QueryCaller(Caller(tenantId = "t2"), entry = QueryEntry.HTTP)
        gateway.count(CountQuery(MatchAllFilter), t2).assert().isEqualTo(CountResult(1, exact = true))
        gateway.list(ListQuery(MatchAllFilter, limit = 10), t2).toList().map { it.path(ID).stringValue() }
            .assert().containsExactly("o6")
        gateway.single(SingleQuery(IdsFilter(listOf("o1"))), t2).assert().isNull()
        gateway.count(
            CountQuery(MatchAllFilter),
            QueryCaller.IN_PROCESS
        ).assert().isEqualTo(CountResult(6, exact = true))
        Unit
    }

    private suspend fun walk(sort: Sort): List<String> {
        val ids = mutableListOf<String>()
        var cursor: String? = null
        do {
            val query = CursorQuery(MatchAllFilter, sort = listOf(sort), size = 2, cursor = cursor)
            val page = orders.backend.cursor(QueryAdmission.Trusted.cursor(query, orders.schema))
            ids += page.list.map { it.path(ID).stringValue() }
            cursor = page.nextCursor
        } while (cursor != null && ids.size <= MAX_LIST)
        return ids
    }

    private suspend fun dataset(table: DatasetTable): QueryDataset {
        val storage = createTable(table)
        return QueryDataset.of(AggregateType(CONTEXT, table.name)) {
            tenant(TENANT_COLUMN.path)
            identity(ID)
            storage(storage)
            table.columns.forEach { column ->
                field(column.name) {
                    when (column.type) {
                        DatasetColumn.Type.STRING -> types(QueryValueType.STRING)
                        DatasetColumn.Type.INTEGER -> types(QueryValueType.INTEGER)
                        DatasetColumn.Type.DECIMAL -> types(QueryValueType.DECIMAL)
                        DatasetColumn.Type.DATE -> {
                            types(QueryValueType.STRING)
                            semantic(Temporal.Date)
                        }
                        DatasetColumn.Type.STRING_ARRAY -> {
                            kind(QueryValueKind.ARRAY)
                            items {
                                types(QueryValueType.STRING)
                                nullable(false)
                            }
                        }
                    }
                    nullable(column.nullable)
                }
            }
        }
    }

    private fun numeric(function: AggregationFunction, field: QueryField, alias: String) =
        AggregationMetric.Numeric(function, AggregationExpression.Field(field), alias)

    companion object {
        const val CONTEXT: String = "tck"
        const val ID: String = "id"
        private const val MAX_LIST = 100
        private const val EPSILON = 1e-9
        private val TENANT_COLUMN = QueryField("tenant_id")
        private val CITY = QueryField("city")
        private val AMOUNT = QueryField("amount")
        private val CREATED_AT = QueryField("created_at")

        /** The column each semantic shape's probes are stored in. */
        val SEMANTIC_FIELDS: Map<SemanticShape, String> = mapOf(
            SemanticShape.STRING to "string_value",
            SemanticShape.NUMBER to "number_value",
            SemanticShape.STRING_ARRAY to "array_value",
        )

        /**
         * One row per probe of each shape, keyed by [QueryBackendSpec.probeId]: a missing probe leaves its column out
         * of the row, a null probe stores `null`.
         */
        val SEMANTIC = DatasetTable(
            name = "semantic",
            columns = listOf(
                DatasetColumn(ID, DatasetColumn.Type.STRING),
                DatasetColumn(TENANT_COLUMN.path, DatasetColumn.Type.STRING),
                DatasetColumn("string_value", DatasetColumn.Type.STRING, nullable = true),
                DatasetColumn("number_value", DatasetColumn.Type.INTEGER, nullable = true),
                DatasetColumn("array_value", DatasetColumn.Type.STRING_ARRAY, nullable = true),
            ),
            rows = SEMANTIC_FIELDS.flatMap { (shape, column) ->
                checkNotNull(SemanticProbe.ALL[shape]).map { probe ->
                    val row = mapOf(ID to QueryBackendSpec.probeId(probe), TENANT_COLUMN.path to "t1")
                    val json = probe.json ?: return@map row
                    row + (column to probeValue(json))
                }
            },
        )

        private fun probeValue(json: String): Any? {
            val node = me.ahoo.wow.spi.json.WowJson.mapper.readTree(json)
            return when {
                node.isNull -> null
                node.isArray -> node.toList().map { it.stringValue() }
                node.isNumber -> node.longValue()
                else -> node.stringValue()
            }
        }

        val ORDERS = DatasetTable(
            name = "orders",
            columns = listOf(
                DatasetColumn(ID, DatasetColumn.Type.STRING),
                DatasetColumn(TENANT_COLUMN.path, DatasetColumn.Type.STRING),
                DatasetColumn("city", DatasetColumn.Type.STRING),
                DatasetColumn("amount", DatasetColumn.Type.DECIMAL),
                DatasetColumn("quantity", DatasetColumn.Type.INTEGER),
                DatasetColumn("discount", DatasetColumn.Type.DECIMAL, nullable = true),
                DatasetColumn("created_at", DatasetColumn.Type.DATE),
            ),
            rows = listOf(
                order("o1", "t1", "Hangzhou", "10.50", 1, null, "2025-01-01T01:00:00Z"),
                order("o2", "t1", "Hangzhou", "20.25", 2, "0.10", "2025-01-01T23:30:00Z"),
                order("o3", "t1", "Beijing", "30.00", 3, "0.20", "2025-01-02T10:00:00Z"),
                order("o4", "t1", "Beijing", "5.00", 1, null, "2025-02-01T00:00:00Z"),
                order("o5", "t1", "Shanghai", "7.75", 4, "0.05", "2025-02-15T12:00:00Z"),
                order("o6", "t2", "Hangzhou", "99.99", 9, null, "2025-01-01T05:00:00Z"),
            ),
        )

        @Suppress("LongParameterList")
        private fun order(
            id: String,
            tenant: String,
            city: String,
            amount: String,
            quantity: Long,
            discount: String?,
            createdAt: String,
        ): Map<String, Any?> = mapOf(
            ID to id,
            TENANT_COLUMN.path to tenant,
            "city" to city,
            "amount" to BigDecimal(amount),
            "quantity" to quantity,
            "discount" to discount?.let(::BigDecimal),
            "created_at" to Instant.parse(createdAt),
        )
    }
}

/** This schema with every window computed by the core, so a spec can hold the storage's native windows to it. */
internal fun QueryModelSchema.withResidualWindows() = QueryModelSchema(
    model,
    capabilities,
    definition,
    bindings,
    fullProjectionAvailable,
    approximateMetrics,
    storage.copy(aggregation = storage.aggregation.copy(windows = AggregationSupport.RESIDUAL_WINDOWS)),
    provisional,
    profile,
)
