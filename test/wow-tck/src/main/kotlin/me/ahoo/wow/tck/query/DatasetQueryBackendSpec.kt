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

package me.ahoo.wow.tck.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.RunningFunction
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.QueryAdmission
import me.ahoo.wow.query.aggregate
import me.ahoo.wow.query.cursor
import me.ahoo.wow.query.dataset.DatasetQueryBackend
import me.ahoo.wow.query.dataset.DatasetQueryBackendFactory
import me.ahoo.wow.query.dsl.aggregation
import me.ahoo.wow.query.dsl.cursorQuery
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.dsl.pagedQuery
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.list
import me.ahoo.wow.query.paged
import me.ahoo.wow.query.schema.AggregationSupport
import me.ahoo.wow.query.schema.QueryDataset
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.single
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A column of a TCK dataset table, in storage-neutral terms; each backend maps it to a native type. */
data class DatasetColumn(val name: String, val type: Type, val nullable: Boolean = false) {
    /**
     * A date column stores instants natively and is declared with the `TEMPORAL_DATE` semantic. A string-array column
     * is never nullable: a record without values holds an empty array.
     */
    enum class Type { STRING, INTEGER, DECIMAL, DATE, STRING_ARRAY }
}

/**
 * A table the spec loads: its columns and rows, each value a `String`, `Long`, `BigDecimal`, `Instant`, a `List` of
 * strings or `null` as its column's type says.
 */
data class DatasetTable(val name: String, val columns: List<DatasetColumn>, val rows: List<Map<String, Any?>>)

/**
 * The semantics every dataset backend implements over a flat table: the filter semantics matrix, records (sort,
 * projection, offset and keyset pages, counts), aggregation, and the dataset's scope column. Queries run through
 * trusted admission against the schema the Catalog compiles from the dataset's declaration and the storage's facts.
 */
abstract class DatasetQueryBackendSpec {
    private lateinit var factory: DatasetQueryBackendFactory
    private lateinit var orders: QueryTarget<DatasetQueryBackend>

    protected abstract fun createDatasetQueryBackendFactory(): DatasetQueryBackendFactory

    /** Creates [table] in the storage, replacing any earlier one, loads its rows and returns the dataset's binding. */
    protected abstract fun createTable(table: DatasetTable): ObjectNode

    /**
     * Semantic cases ([FilterSemantics]) this backend is known to diverge on; each one is skipped and reported,
     * never silently passed.
     */
    protected open val semanticDivergences: Set<String> = emptySet()

    @BeforeEach
    open fun setup() {
        factory = createDatasetQueryBackendFactory()
        orders = factory.target(dataset(ORDERS))
    }

    @TestFactory
    fun `filter semantics matrix`(): List<DynamicTest> {
        val semantic = factory.target(dataset(SEMANTIC))
        return FilterSemanticsMatrix.tests(
            divergences = semanticDivergences,
            shapes = SEMANTIC_FIELDS.keys,
            field = { QueryField(SEMANTIC_FIELDS.getValue(it)) },
            ids = ::IdsFilter,
            idOf = { it.path(ID).asString() },
            list = { semantic.list(it) },
        )
    }

    @Test
    fun `records sort, project and limit`() {
        val rows = orders.list(
            listQuery {
                filter { "tenant_id" eq "t1" }
                projection { include(ID, "amount") }
                sort { "amount".desc() }
                limit(3)
            },
        ).collectList().block()!!
        rows.map { it.path(ID).asString() }.assert().containsExactly("o3", "o2", "o1")
        rows.forEach { it.propertyNames().assert().containsExactlyInAnyOrder(ID, "amount") }
        rows.first().get("amount").decimalValue().assert().isEqualByComparingTo(BigDecimal("30.00"))
    }

    @Test
    fun `the tenant filter reads the dataset's tenant column`() {
        orders.count(TenantIdFilter("t2")).block().assert().isEqualTo(1L)
        orders.count(TenantIdFilter("t1")).block().assert().isEqualTo(5L)
    }

    @Test
    fun `a single record is the first in sort order, or none`() {
        orders.single(
            singleQuery {
                filter { "tenant_id" eq "t1" }
                sort { "amount".asc() }
            },
        ).block()!!.path(ID).asString().assert().isEqualTo("o4")
        orders.single(singleQuery { filter { "city" eq "Nowhere" } }).block().assert().isNull()
    }

    @Test
    fun `offset pages carry the total`() {
        val page = orders.paged(
            pagedQuery {
                sort { ID.asc() }
                pagination {
                    index(2)
                    size(2)
                }
            },
        ).block()!!
        page.total.assert().isEqualTo(6L)
        page.list.map { it.path(ID).asString() }.assert().containsExactly("o3", "o4")
    }

    @Test
    fun `keyset pages walk every record once in sort order`() {
        walk { sort { "amount".desc() } }.assert().containsExactly("o6", "o3", "o2", "o1", "o5", "o4")
        walk { sort { "created_at".asc() } }.assert().containsExactly("o1", "o6", "o2", "o3", "o4", "o5")
        walk { sort { "city".asc() } }.assert().containsExactly("o3", "o4", "o1", "o2", "o6", "o5")
    }

    @Test
    fun `groups count, sum, average, bound, distinct-count and pick the first and last records`() {
        val rows = orders.aggregate(
            aggregation {
                filter { "tenant_id" eq "t1" }
                terms("city", "city")
                count("orders")
                sum("amount", "gmv")
                avg("quantity", "quantity")
                min("amount", "smallest")
                max("amount", "largest")
                distinctCount(ID, "ids")
                first(ID, "first", orderBy = "created_at")
                last(ID, "last", orderBy = "created_at")
                sort { "orders".desc() }
            },
        ).collectList().block()!!
        rows.map { it.path("city").asString() }.assert().containsExactly("Beijing", "Hangzhou", "Shanghai")
        val beijing = rows.first()
        beijing.get("orders").asLong().assert().isEqualTo(2)
        beijing.get("gmv").decimalValue().assert().isEqualByComparingTo(BigDecimal("35.00"))
        beijing.get("quantity").asDouble().assert().isEqualTo(2.0)
        beijing.get("smallest").decimalValue().assert().isEqualByComparingTo(BigDecimal("5.00"))
        beijing.get("largest").decimalValue().assert().isEqualByComparingTo(BigDecimal("30.00"))
        beijing.get("ids").asLong().assert().isEqualTo(2)
        beijing.get("first").asString().assert().isEqualTo("o3")
        beijing.get("last").asString().assert().isEqualTo("o4")
        rows[1].get("gmv").decimalValue().assert().isEqualByComparingTo(BigDecimal("30.75"))
    }

    @Test
    fun `having filters groups before the metric sort and limit`() {
        val rows = orders.aggregate(
            aggregation {
                filter { "tenant_id" eq "t1" }
                terms("city", "city")
                sum("amount", "gmv")
                having { "gmv" gt 10.0 }
                sort { "gmv".asc() }
                limit(1)
            },
        ).collectList().block()!!
        rows.map { it.path("city").asString() }.assert().containsExactly("Hangzhou")
    }

    @Test
    fun `date histograms key buckets by the local start of the unit`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val rows = orders.aggregate(
            aggregation {
                filter { "tenant_id" eq "t1" }
                dateHistogram("created_at", AggregationDateUnit.DAY, "day", zone)
                count("orders")
            },
        ).collectList().block()!!
        fun day(month: Int, day: Int) = LocalDate.of(2025, month, day).atStartOfDay(zone).toInstant().toEpochMilli()
        rows.map { it.get("day").asLong() to it.get("orders").asLong() }.assert().containsExactly(
            day(1, 1) to 1L,
            day(1, 2) to 2L,
            day(2, 1) to 1L,
            day(2, 15) to 1L,
        )
    }

    @Test
    fun `windows read every group before HAVING, alike in the storage and in the core`() {
        val query = aggregation {
            filter { "tenant_id" eq "t1" }
            terms("city", "city")
            sum("amount", "gmv")
            count("orders")
            derived("share") { ref("gmv") / total("gmv") }
            derived("rank") { rank("gmv") }
            derived("cumulative") { running("orders", Sort(QueryField("gmv"), Sort.Direction.DESC)) }
            having { "rank" lte 2.0 }
            sort { "gmv".desc() }
        }
        listOf(false, true).forEach { residual ->
            val rows = orders.aggregate(query, residual).collectList().block()!!
            rows.map { it.path("city").asString() }.assert().containsExactly("Beijing", "Hangzhou")
            rows.map { it.get("share").asDouble() }.assert().containsExactly(35.0 / 73.5, 30.75 / 73.5)
            rows.map { it.get("rank").asDouble() }.assert().containsExactly(1.0, 2.0)
            rows.map { it.get("cumulative").asDouble() }.assert().containsExactly(2.0, 4.0)
        }
    }

    @Test
    fun `running windows follow their order within each partition, alike in the storage and in the core`() {
        val month = Sort(QueryField("month"), Sort.Direction.ASC)
        val query = aggregation {
            filter { "tenant_id" eq "t1" }
            terms("city", "city")
            dateHistogram("created_at", AggregationDateUnit.MONTH, "month")
            sum("amount", "gmv")
            derived("cumulative") { running("gmv", month, listOf("city")) }
            derived("moving") { running("gmv", month, frame = 2, function = RunningFunction.AVG) }
        }
        listOf(false, true).forEach { residual ->
            val rows = orders.aggregate(query, residual).collectList().block()!!
            val cities = rows.map { it.path("city").asString() }
            cities.assert().containsExactly("Beijing", "Beijing", "Hangzhou", "Shanghai")
            rows.map { it.get("cumulative").asDouble() }.assert().containsExactly(30.0, 35.0, 30.75, 7.75)
            // Along the month, ties by city: Beijing Jan, Hangzhou Jan, Beijing Feb, Shanghai Feb.
            rows.map { it.get("moving").asDouble() }.assert()
                .containsExactly(30.0, (30.75 + 5.0) / 2, (30.0 + 30.75) / 2, (5.0 + 7.75) / 2)
        }
    }

    @Test
    fun `previous reads the earlier period over the shifted range`() {
        val rows = orders.aggregate(
            aggregation {
                filter {
                    "tenant_id" eq "t1"
                    "created_at" gte "2025-02-01T00:00:00Z"
                }
                terms("city", "city")
                dateHistogram("created_at", AggregationDateUnit.MONTH, "month")
                sum("amount", "gmv")
                derived("previous") { previous("gmv", "month") }
            },
        ).collectList().block()!!
        rows.map { it.path("city").asString() }.assert().containsExactly("Beijing", "Shanghai")
        rows.map { it.get("previous").takeUnless { node -> node.isNull }?.asDouble() }.assert()
            .containsExactly(30.0, null)
    }

    @Test
    fun `value metrics skip missing values and an empty input keeps the empty summary`() {
        val discounts = orders.aggregate(
            aggregation {
                filter { "tenant_id" eq "t1" }
                avg("discount", "discount")
                count("discounted") { "discount".isNotNull() }
            },
        ).single().block()!!
        discounts.get("discount").asDouble().assert().isCloseTo(0.35 / 3, org.assertj.core.data.Offset.offset(1e-9))
        discounts.get("discounted").asLong().assert().isEqualTo(3)
        val empty = orders.aggregate(
            aggregation {
                filter { "city" eq "Nowhere" }
                count("orders")
                sum("amount", "gmv")
                distinctCount(ID, "ids")
            },
        ).single().block()!!
        empty.get("orders").asLong().assert().isEqualTo(0)
        empty.get("gmv").isNull.assert().isTrue()
        empty.get("ids").asLong().assert().isEqualTo(0)
    }

    private fun walk(block: me.ahoo.wow.query.dsl.CursorQueryDsl.() -> Unit): List<String> {
        val ids = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = orders.cursor(
                cursorQuery {
                    block()
                    size(2)
                    cursor(cursor)
                },
            ).block()!!
            ids += page.list.map { it.path(ID).asString() }
            cursor = page.nextCursor
        } while (cursor != null)
        return ids
    }

    private fun dataset(table: DatasetTable): QueryDataset {
        val storage = createTable(table)
        return QueryDataset.of(MaterializedNamedAggregate("tck", table.name)) {
            tenant("tenant_id")
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

    companion object {
        const val ID = "id"

        private val SEMANTIC_FIELDS = mapOf(
            SemanticShape.STRING to "string_value",
            SemanticShape.NUMBER to "number_value",
            SemanticShape.STRING_ARRAY to "array_value",
        )

        /**
         * One row per probe of each shape; a missing and a null probe both store NULL, or an empty array in the array
         * column, which every row fills.
         */
        val SEMANTIC = DatasetTable(
            name = "semantic",
            columns = listOf(
                DatasetColumn(ID, DatasetColumn.Type.STRING),
                DatasetColumn("tenant_id", DatasetColumn.Type.STRING),
                DatasetColumn("string_value", DatasetColumn.Type.STRING, nullable = true),
                DatasetColumn("number_value", DatasetColumn.Type.INTEGER, nullable = true),
                DatasetColumn("array_value", DatasetColumn.Type.STRING_ARRAY),
            ),
            rows = SEMANTIC_FIELDS.flatMap { (shape, column) ->
                SemanticProbe.ALL.getValue(shape).map { probe ->
                    val value = probe.value?.takeUnless { it.isNull }?.let {
                        when {
                            it.isArray -> it.values().map { element -> element.stringValue() }
                            it.isNumber -> it.longValue()
                            else -> it.stringValue()
                        }
                    }
                    mapOf(
                        ID to FilterSemanticsMatrix.probeId(probe),
                        "tenant_id" to "t1",
                        "array_value" to emptyList<String>(),
                    ) + (column to (value ?: emptyList<String>().takeIf { shape == SemanticShape.STRING_ARRAY }))
                }
            },
        )

        val ORDERS = DatasetTable(
            name = "orders",
            columns = listOf(
                DatasetColumn(ID, DatasetColumn.Type.STRING),
                DatasetColumn("tenant_id", DatasetColumn.Type.STRING),
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
            "tenant_id" to tenant,
            "city" to city,
            "amount" to BigDecimal(amount),
            "quantity" to quantity,
            "discount" to discount?.let(::BigDecimal),
            "created_at" to Instant.parse(createdAt),
        )
    }
}

private fun QueryTarget<DatasetQueryBackend>.list(query: IListQuery): Flux<ObjectNode> =
    Mono.defer { schemaProvider.schema() }.flatMapMany { backend.list(QueryAdmission.Trusted.list(query, it)) }

private fun QueryTarget<DatasetQueryBackend>.single(query: ISingleQuery): Mono<ObjectNode> =
    Mono.defer { schemaProvider.schema() }.flatMap { backend.single(QueryAdmission.Trusted.single(query, it)) }

private fun QueryTarget<DatasetQueryBackend>.paged(query: IPagedQuery) =
    Mono.defer { schemaProvider.schema() }.flatMap { backend.paged(QueryAdmission.Trusted.paged(query, it)) }

private fun QueryTarget<DatasetQueryBackend>.cursor(query: ICursorQuery) =
    Mono.defer { schemaProvider.schema() }.flatMap { backend.cursor(QueryAdmission.Trusted.cursor(query, it)) }

private fun QueryTarget<DatasetQueryBackend>.count(filter: FilterExpression): Mono<Long> =
    Mono.defer { schemaProvider.schema() }.flatMap { backend.count(QueryAdmission.Trusted.count(filter, it)) }

/** [query]'s groups; with [residual], the core computes every window the storage would. */
private fun QueryTarget<DatasetQueryBackend>.aggregate(
    query: AggregationQuery,
    residual: Boolean = false,
): Flux<ObjectNode> =
    Mono.defer { schemaProvider.schema() }.flatMapMany { schema ->
        backend.aggregate(
            QueryAdmission.Trusted.aggregate(query, if (residual) schema.withResidualWindows() else schema)
        )
    }

private fun QueryModelSchema.withResidualWindows() = QueryModelSchema(
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
