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
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.SimpleDynamicDocument.Companion.toDynamicDocument
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.metadata.StateAggregateMetadata
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory.toStateAggregate
import me.ahoo.wow.modeling.state.SimpleStateAggregate
import me.ahoo.wow.query.dsl.aggregation
import me.ahoo.wow.query.dsl.condition
import me.ahoo.wow.query.dsl.filterExpression
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.dsl.pagedQuery
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.query.schema.toMetadata
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.count
import me.ahoo.wow.query.snapshot.dynamicQuery
import me.ahoo.wow.query.snapshot.query
import me.ahoo.wow.query.snapshot.requiredQueryModelSchemaProvider
import me.ahoo.wow.schema.query.JsonQuerySchemaSource
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockDiscount
import me.ahoo.wow.tck.mock.MockLine
import me.ahoo.wow.tck.mock.MockOrder
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.concurrent.TimeUnit

@Suppress("LargeClass")
abstract class SnapshotQueryServiceSpec {
    protected val querySchemaSources: List<QuerySchemaSource> = listOf(JsonQuerySchemaSource())
    lateinit var snapshotStore: SnapshotStore
    lateinit var snapshotQueryServiceFactory: SnapshotQueryServiceFactory
    lateinit var snapshotQueryService: SnapshotQueryService<MockStateAggregate>
    lateinit var snapshot: Snapshot<MockStateAggregate>

    @BeforeEach
    open fun setup() {
        snapshotStore = createSnapshotStore()
        snapshotQueryServiceFactory = createSnapshotQueryServiceFactory()
        snapshotQueryService = snapshotQueryServiceFactory.create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(generateGlobalId())
        val stateAggregate =
            ConstructorStateAggregateFactory.create(MOCK_AGGREGATE_METADATA.state, aggregateId)
        snapshot =
            SimpleSnapshot(stateAggregate, Clock.systemUTC().millis())
        snapshotStore.save(snapshot)
            .test()
            .verifyComplete()
    }

    protected open fun createSnapshotStore(): SnapshotStore = createSnapshotRepository()

    @Deprecated("Use createSnapshotStore().", ReplaceWith("createSnapshotStore()"))
    protected open fun createSnapshotRepository(): SnapshotStore {
        throw UnsupportedOperationException("Override createSnapshotStore().")
    }
    protected abstract fun createSnapshotQueryServiceFactory(): SnapshotQueryServiceFactory

    @Test
    fun createFromCache() {
        val queryService1 = snapshotQueryServiceFactory.create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)
        val queryService2 = snapshotQueryServiceFactory.create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)
        queryService1.assert().isSameAs(queryService2)
    }

    @Test
    fun name() {
        snapshotQueryServiceFactory.create<MockStateAggregate>(MOCK_AGGREGATE_METADATA).name.assert().isNotBlank()
    }

    @Test
    fun single() {
        singleQuery {
            condition {
                id(snapshot.aggregateId.id)
            }
        }.query(snapshotQueryService)
            .test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun dynamicSingle() {
        singleQuery {
            condition {
                id(snapshot.aggregateId.id)
            }
            projection {
                include("contextName")
            }
            sort {
                "version".asc()
            }
        }.dynamicQuery(snapshotQueryService)
            .test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun list() {
        listQuery {
            condition {
                id(snapshot.aggregateId.id)
            }
            limit(10)
        }.query(snapshotQueryService)
            .test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun dynamicList() {
        listQuery {
            condition {
                id(snapshot.aggregateId.id)
            }
            projection {
                exclude("firstEventTime")
            }
            limit(10)
        }.dynamicQuery(snapshotQueryService)
            .test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun paged() {
        pagedQuery {
            condition {
                id(snapshot.aggregateId.id)
            }
        }.query(snapshotQueryService)
            .test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun dynamicPaged() {
        pagedQuery {
            condition {
                id(snapshot.aggregateId.id)
            }
        }.dynamicQuery(snapshotQueryService)
            .test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun `cursor should traverse first middle and last pages with a stable tie breaker`() {
        val ids = (1..5).map { "cursor-snapshot-$it" }
        ids.forEach { saveCursorSnapshot(MockStateAggregate(id = it), version = 1) }

        val query = CursorQuery(
            filter = AggregateIdsFilter(ids),
            sort = listOf(Sort("version", Sort.Direction.ASC)),
            size = 2,
        )
        val first = query.query(snapshotQueryService).block()!!
        val middle = query.copy(cursor = first.nextCursor).query(snapshotQueryService).block()!!
        val last = query.copy(cursor = middle.nextCursor).query(snapshotQueryService).block()!!

        first.list.map { it.aggregateId }.assert().containsExactly(ids[0], ids[1])
        middle.list.map { it.aggregateId }.assert().containsExactly(ids[2], ids[3])
        last.list.map { it.aggregateId }.assert().containsExactly(ids[4])
        first.nextCursor.assert().isNotNull()
        middle.nextCursor.assert().isNotNull()
        last.nextCursor.assert().isNull()
    }

    @Test
    fun `cursor should use the unique field as the default sort`() {
        val ids = listOf("cursor-default-c", "cursor-default-a", "cursor-default-b")
        ids.forEach { saveCursorSnapshot(MockStateAggregate(id = it), version = 1) }

        val query = CursorQuery(AggregateIdsFilter(ids), size = 2)
        val first = query.query(snapshotQueryService).block()!!
        val second = query.copy(cursor = first.nextCursor).query(snapshotQueryService).block()!!

        (first.list + second.list).map { it.aggregateId }.assert().containsExactly(*ids.sorted().toTypedArray())
        second.nextCursor.assert().isNull()
    }

    @Test
    fun `cursor should preserve mixed sort directions across pages`() {
        val states = listOf(
            MockStateAggregate(id = "cursor-mixed-a", createdAt = 30),
            MockStateAggregate(id = "cursor-mixed-b", createdAt = 10),
            MockStateAggregate(id = "cursor-mixed-c", createdAt = 20),
        )
        saveCursorSnapshot(states[0], version = 1)
        saveCursorSnapshot(states[1], version = 1)
        saveCursorSnapshot(states[2], version = 2)

        val query = CursorQuery(
            filter = AggregateIdsFilter(states.map(MockStateAggregate::id)),
            sort = listOf(
                Sort("version", Sort.Direction.ASC),
                Sort("state.createdAt", Sort.Direction.DESC),
            ),
            size = 2,
        )
        val first = query.query(snapshotQueryService).block()!!
        val second = query.copy(cursor = first.nextCursor).query(snapshotQueryService).block()!!

        (first.list + second.list).map { it.aggregateId }.assert().containsExactly(
            "cursor-mixed-a",
            "cursor-mixed-b",
            "cursor-mixed-c",
        )
    }

    @Test
    fun `cursor should continue after null and missing sort values`() {
        val ids = listOf("cursor-null-a", "cursor-null-b", "cursor-null-c")
        saveCursorDynamicSnapshot(mapOf("id" to ids[0]))
        saveCursorDynamicSnapshot(mapOf("id" to ids[1], "cursorOrder" to null))
        saveCursorDynamicSnapshot(mapOf("id" to ids[2], "cursorOrder" to 1))

        val query = CursorQuery(
            filter = AggregateIdsFilter(ids),
            sort = listOf(Sort("state.cursorOrder", Sort.Direction.ASC)),
            size = 2,
        )
        val first = query.dynamicQuery(snapshotQueryService).block()!!
        val second = query.copy(cursor = first.nextCursor).dynamicQuery(snapshotQueryService).block()!!
        val documents = first.list + second.list
        val byId = documents.associateBy { it["aggregateId"] }

        documents.map { it["aggregateId"] }.assert().containsExactly(*ids.toTypedArray())
        byId.getValue(ids[0]).getNestedDocument("state").containsKey("cursorOrder").assert().isFalse()
        val explicitNullState = byId.getValue(ids[1]).getNestedDocument("state")
        explicitNullState.containsKey("cursorOrder").assert().isTrue()
        explicitNullState["cursorOrder"].assert().isNull()
        byId.getValue(ids[2]).getNestedDocument("state")["cursorOrder"].assert().isEqualTo(1)
        second.nextCursor.assert().isNull()
    }

    @Test
    fun `dynamic cursor should exclude projected sort fields without breaking continuation`() {
        val ids = listOf("cursor-projection-a", "cursor-projection-b", "cursor-projection-c")
        ids.forEach { saveCursorSnapshot(MockStateAggregate(id = it), version = 1) }
        val query = CursorQuery(
            filter = AggregateIdsFilter(ids),
            projection = Projection(exclude = listOf("version")),
            sort = listOf(Sort("version", Sort.Direction.ASC)),
            size = 2,
        )

        val first = query.dynamicQuery(snapshotQueryService).block()!!
        val second = query.copy(cursor = first.nextCursor).dynamicQuery(snapshotQueryService).block()!!

        (first.list + second.list).map { it.containsKey("version") }.assert().containsExactly(false, false, false)
        (first.list + second.list).map { it["aggregateId"] }.assert().containsExactly(*ids.toTypedArray())
    }

    @Test
    fun count() {
        filterExpression {
            id(snapshot.aggregateId.id)
        }.count(snapshotQueryService)
            .test()
            .expectNext(1L)
            .verifyComplete()
    }

    @Test
    fun `schema should expose system state and backend-neutral capabilities`() {
        snapshotQueryService.requiredQueryModelSchemaProvider().schema()
            .test()
            .assertNext { schema ->
                schema.model.assert().isEqualTo(QueryModel.SNAPSHOT)
                schema.fields.keys.assert().contains(
                    LogicalField("aggregateId"),
                    LogicalField("eventTime"),
                    LogicalField("state"),
                    LogicalField("state.data"),
                    LogicalField("state.createdAt"),
                    LogicalField("state.orders"),
                    LogicalField("state.decimalValue"),
                )
                schema.fields.getValue(LogicalField("state.data")).bindings.keys.assert().contains(
                    QueryCapability.EXACT_MATCH,
                    QueryCapability.SORT,
                )
                val createdAt = schema.fields.getValue(LogicalField("state.createdAt"))
                createdAt.valueTypes.assert().isEqualTo(setOf(QueryValueType.INTEGER))
                createdAt.semanticType.assert().isEqualTo(Temporal.Epoch(TimeUnit.MILLISECONDS))
                createdAt.bindings.keys.assert().contains(
                    QueryCapability.RANGE,
                    QueryCapability.SORT,
                    QueryCapability.AGGREGATE_TEMPORAL,
                )
                val orders = schema.fields.getValue(LogicalField("state.orders"))
                orders.cardinality.assert().isEqualTo(QueryCardinality.MANY)
                orders.valueTypes.assert().isEqualTo(setOf(QueryValueType.OBJECT))
                orders.bindings.keys.assert().contains(QueryCapability.ELEMENT_SCOPE)
                schema.fields.getValue(LogicalField("state.decimalValue")).bindings.keys.assert().contains(
                    QueryCapability.AGGREGATE_TERMS,
                    QueryCapability.AGGREGATE_NUMERIC,
                )

                val metadata = schema.toMetadata()
                metadata.fields.map { it.field.value }.assert().isEqualTo(
                    metadata.fields.map { it.field.value }.sorted(),
                )
                JsonSerializer.writeValueAsString(metadata).assert()
                    .doesNotContain("physicalPath", "storageType")
            }.verifyComplete()
    }

    @Test
    fun `schema refresh should replace and publish one new object`() {
        val provider = snapshotQueryService.requiredQueryModelSchemaProvider()
        provider.schema()
            .flatMap { initial ->
                provider.refresh().flatMap { refreshed ->
                    provider.schema().map { cached -> Triple(initial, refreshed, cached) }
                }
            }.test()
            .assertNext { (initial, refreshed, cached) ->
                refreshed.assert().isNotSameAs(initial)
                cached.assert().isSameAs(refreshed)
            }.verifyComplete()
    }

    @Test
    fun `schema should execute exact range and sort bindings`() {
        listQuery {
            filter {
                "state.data" eq ""
                "state.createdAt" gte 0L
            }
            sort { "state.createdAt".asc() }
            limit(10)
        }.query(snapshotQueryService)
            .test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun `schema should aggregate annotated epoch fields as time`() {
        aggregation {
            dateHistogram("state.createdAt", AggregationDateUnit.DAY, "day")
            count("count")
        }.query(snapshotQueryService)
            .test()
            .assertNext {
                it.toMap().assert().isEqualTo(mapOf("day" to 0L, "count" to 1L))
            }.verifyComplete()
    }

    @Test
    fun `aggregation should summarize standard root fields with every metric`() {
        saveAggregationStates(*aggregationStates().toTypedArray())

        aggregation {
            filter { aggregateIds("aggregation-a", "aggregation-b") }
            count("count")
            sum("version", "total")
            avg("version", "average")
            min("version", "minimum")
            max("version", "maximum")
        }.query(snapshotQueryService)
            .test()
            .assertNext {
                it.toMap().assert().isEqualTo(
                    mapOf(
                        "count" to 2L,
                        "total" to 3.0,
                        "average" to 1.5,
                        "minimum" to 1.0,
                        "maximum" to 2.0,
                    ),
                )
            }.verifyComplete()
    }

    @Test
    fun `aggregation should calculate arithmetic metrics in the innermost element scope`() {
        saveAggregationStates(*aggregationStates().toTypedArray())

        aggregation {
            expand("state.orders")
            expand("lines")
            count("count")
            val net = field("amount") * field("quantity") - constant(10.0)
            sum(net, "total")
            avg(net, "average")
            min(net, "minimum")
            max(net, "maximum")
        }.query(snapshotQueryService)
            .test()
            .assertNext {
                it.toMap().assert().isEqualTo(
                    mapOf(
                        "count" to 6L,
                        "total" to 410.0,
                        "average" to 82.0,
                        "minimum" to 0.0,
                        "maximum" to 240.0,
                    ),
                )
            }.verifyComplete()
    }

    @Test
    fun `aggregation should ignore invalid arithmetic values`() {
        saveAggregationStates(*aggregationStates().toTypedArray())

        aggregation {
            expand("state.orders")
            expand("lines") { "productId" isIn listOf("alpha", "beta") }
            sum(field("amount") / (field("quantity") - constant(2.0)), "safeDivision")
            sum(field("quantity") / (field("quantity") - field("quantity")), "zeroDivision")
            sum(field("missing") * constant(1.0), "missing")
            sum(constant(Double.MAX_VALUE) * constant(2.0), "overflow")
        }.query(snapshotQueryService)
            .test()
            .assertNext {
                it.toMap().assert().isEqualTo(
                    mapOf(
                        "safeDivision" to 5.0,
                        "zeroDivision" to null,
                        "missing" to null,
                        "overflow" to null,
                    ),
                )
            }.verifyComplete()
    }

    @Test
    fun `aggregation should accept singleton and ignore multi-valued numeric arrays`() {
        saveAggregationStates(*aggregationStates().toTypedArray())

        aggregation {
            expand("state.orders")
            expand("lines")
            sum(field("samples") * constant(1.0), "total")
        }.query(snapshotQueryService)
            .test()
            .assertNext { it.toMap().assert().isEqualTo(mapOf("total" to 7.0)) }
            .verifyComplete()
    }

    @Test
    fun `aggregation should apply two element filters and every group type`() {
        saveAggregationStates(*aggregationStates().toTypedArray())

        aggregation {
            expand("state.orders") { "status" eq "PAID" }
            expand("lines") { "quantity" gte 2 }
            terms("productId", "product")
            histogram("quantity", 2.0, "quantityBucket")
            dateHistogram("createdAt", me.ahoo.wow.api.query.AggregationDateUnit.DAY, "day")
            count("count")
        }.query(snapshotQueryService)
            .collectList()
            .test()
            .assertNext { rows ->
                rows.map(Map<String, Any?>::toMap).assert().containsExactly(
                    mapOf(
                        "product" to "alpha",
                        "quantityBucket" to 4.0,
                        "day" to 1_767_398_400_000L,
                        "count" to 1L,
                    ),
                    mapOf(
                        "product" to "beta",
                        "quantityBucket" to 2.0,
                        "day" to 1_767_312_000_000L,
                        "count" to 2L,
                    ),
                    mapOf(
                        "product" to "delta",
                        "quantityBucket" to 4.0,
                        "day" to 1_769_990_400_000L,
                        "count" to 1L,
                    ),
                )
            }.verifyComplete()
    }

    @Test
    fun `aggregation should start UTC weeks on Monday`() {
        saveAggregationStates(*aggregationStates().toTypedArray())

        aggregation {
            expand("state.orders")
            expand("lines") { "productId" isIn listOf("gamma", "delta") }
            dateHistogram("createdAt", AggregationDateUnit.WEEK, "week")
            count("count")
        }.query(snapshotQueryService)
            .collectList()
            .test()
            .assertNext { rows ->
                rows.map(Map<String, Any?>::toMap).assert().containsExactly(
                    mapOf("week" to 1_769_385_600_000L, "count" to 1L),
                    mapOf("week" to 1_769_990_400_000L, "count" to 1L),
                )
            }.verifyComplete()
    }

    @Test
    fun `aggregation should support second date histograms`() {
        saveAggregationStates(*aggregationStates().toTypedArray())

        aggregation {
            expand("state.orders")
            expand("lines") { "productId" eq "beta" }
            dateHistogram("createdAt", AggregationDateUnit.SECOND, "second")
            count("count")
        }.query(snapshotQueryService)
            .collectList()
            .test()
            .assertNext { rows ->
                rows.map(Map<String, Any?>::toMap).assert().containsExactly(
                    mapOf("second" to Instant.parse("2026-01-02T10:00:00Z").toEpochMilli(), "count" to 1L),
                    mapOf("second" to Instant.parse("2026-01-02T18:00:00Z").toEpochMilli(), "count" to 1L),
                )
            }.verifyComplete()
    }

    @Test
    fun `aggregation should return backend neutral decimal Terms keys`() {
        saveAggregationStates(
            MockStateAggregate(id = "decimal-a", decimalValue = BigDecimal("1.25")),
            MockStateAggregate(id = "decimal-b", decimalValue = BigDecimal("2.50")),
        )

        aggregation {
            filter { aggregateIds("decimal-a", "decimal-b") }
            terms("state.decimalValue", "decimal")
            count("count")
        }.query(snapshotQueryService)
            .collectList()
            .test()
            .assertNext { rows ->
                rows.map(Map<String, Any?>::toMap).assert().containsExactly(
                    mapOf("decimal" to 1.25, "count" to 1L),
                    mapOf("decimal" to 2.5, "count" to 1L),
                )
            }.verifyComplete()
    }

    @Test
    fun `aggregation should return one empty summary row`() {
        aggregation {
            filter { aggregateId("missing") }
            any("state.data", "anyData")
            count("count")
            sum("version", "total")
        }.query(snapshotQueryService)
            .test()
            .assertNext {
                it.toMap().assert().isEqualTo(mapOf("anyData" to null, "count" to 0L, "total" to null))
            }.verifyComplete()
    }

    @Test
    fun `aggregation should select any non-null value without splitting the group`() {
        saveAggregationStates(*(aggregationStates() + aggregationAnyNullState()).toTypedArray())

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
                row["count"].assert().isEqualTo(3L)
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

    @Test
    fun `aggregation should return null when no numeric value contributes`() {
        saveAggregationStates(*aggregationStates().toTypedArray())

        aggregation {
            expand("state.orders") { "status" eq "CANCELLED" }
            expand("lines") { "productId" eq "gamma" }
            count("count")
            sum("amount", "total")
            avg("amount", "average")
            min("amount", "minimum")
            max("amount", "maximum")
        }.query(snapshotQueryService)
            .test()
            .assertNext {
                it.toMap().assert().isEqualTo(
                    mapOf(
                        "count" to 1L,
                        "total" to null,
                        "average" to null,
                        "minimum" to null,
                        "maximum" to null,
                    ),
                )
            }.verifyComplete()
    }

    @Test
    fun `aggregation should append group aliases for stable sorting`() {
        saveAggregationStates(*aggregationStates().toTypedArray())

        aggregation {
            expand("state.orders") { "status" eq "PAID" }
            expand("lines")
            terms("productId", "product")
            histogram("quantity", 2.0, "quantityBucket")
            count("count")
            sort { "product".asc() }
        }.query(snapshotQueryService)
            .collectList()
            .test()
            .assertNext { rows ->
                rows.map { listOf(it["product"], it["quantityBucket"], it["count"]) }.assert().containsExactly(
                    listOf("alpha", 0.0, 1L),
                    listOf("alpha", 4.0, 1L),
                    listOf("beta", 2.0, 2L),
                    listOf("delta", 4.0, 1L),
                )
            }.verifyComplete()
    }

    @Test
    fun `aggregation should select metric Top-N across nested discounts`() {
        saveAggregationStates(*aggregationStates().toTypedArray())

        aggregation {
            expand("state.orders") { "status" eq "PAID" }
            expand("lines") { "quantity" gte 2 }
            expand("discounts") { "amount" gt 0 }
            terms("type", "type")
            sum("amount", "total")
            sort { "total".desc() }
            limit(1)
        }.query(snapshotQueryService)
            .test()
            .assertNext {
                it.toMap().assert().isEqualTo(mapOf("type" to "PROMO", "total" to 8.0))
            }.verifyComplete()
    }

    @Test
    fun `aggregation should support cancellation after a real result`() {
        saveAggregationStates(*aggregationStates().toTypedArray())

        aggregation {
            expand("state.orders")
            expand("lines")
            terms("productId", "product")
            count("count")
        }.query(snapshotQueryService)
            .test()
            .expectNextCount(1)
            .thenCancel()
            .verify()
    }

    private fun saveAggregationStates(vararg states: MockStateAggregate) {
        states.forEachIndexed { index, state ->
            snapshotStore.save(
                SimpleSnapshot(
                    MOCK_AGGREGATE_METADATA.toStateAggregate(state, version = index + 1),
                    AGGREGATION_SNAPSHOT_TIME,
                ),
            ).test().verifyComplete()
        }
    }

    private fun saveCursorSnapshot(
        state: MockStateAggregate,
        version: Int,
        tags: Map<String, List<String>> = emptyMap(),
    ) {
        snapshotStore.save(
            SimpleSnapshot(
                MOCK_AGGREGATE_METADATA.toStateAggregate(state, version = version, tags = tags),
                AGGREGATION_SNAPSHOT_TIME,
            ),
        ).test().verifyComplete()
    }

    @Suppress("UNCHECKED_CAST")
    private fun saveCursorDynamicSnapshot(state: Map<String, Any?>) {
        val id = state.getValue("id") as String
        val aggregate = SimpleStateAggregate(
            aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(id),
            metadata = MOCK_AGGREGATE_METADATA.state as StateAggregateMetadata<me.ahoo.wow.api.query.DynamicDocument>,
            state = state.toMutableMap().toDynamicDocument(),
            version = 1,
        )
        snapshotStore.save(SimpleSnapshot(aggregate, AGGREGATION_SNAPSHOT_TIME)).test().verifyComplete()
    }

    private fun aggregationStates(): List<MockStateAggregate> = listOf(aggregationStateA(), aggregationStateB())

    private fun aggregationStateA(): MockStateAggregate =
        MockStateAggregate(
            id = "aggregation-a",
            orders = listOf(
                MockOrder(
                    status = "PAID",
                    lines = listOf(
                        MockLine(
                            productId = "alpha",
                            quantity = 1,
                            amount = 10.0,
                            createdAt = Instant.parse("2026-01-01T10:00:00Z"),
                            discounts = listOf(
                                MockDiscount("LOYALTY", 1.0),
                                MockDiscount("PROMO", 2.0),
                            ),
                            samples = listOf(7.0),
                            productName = "Alpha",
                        ),
                        MockLine(
                            productId = "beta",
                            quantity = 2,
                            amount = 20.0,
                            createdAt = Instant.parse("2026-01-02T10:00:00Z"),
                            discounts = listOf(MockDiscount("PROMO", 3.0)),
                            samples = listOf(3.0, 4.0),
                        ),
                    ),
                ),
                MockOrder(
                    status = "CANCELLED",
                    lines = listOf(
                        MockLine(
                            productId = "gamma",
                            quantity = 3,
                            amount = null,
                            createdAt = Instant.parse("2026-02-01T10:00:00Z"),
                            discounts = listOf(MockDiscount("LOYALTY", 4.0)),
                        ),
                    ),
                ),
            ),
        )

    private fun aggregationStateB(): MockStateAggregate =
        MockStateAggregate(
            id = "aggregation-b",
            orders = listOf(
                MockOrder(
                    status = "PAID",
                    lines = listOf(
                        MockLine(
                            productId = "alpha",
                            quantity = 4,
                            amount = 30.0,
                            createdAt = Instant.parse("2026-01-03T10:00:00Z"),
                            discounts = listOf(MockDiscount("PROMO", 5.0)),
                            productName = "Alpha 2026",
                        ),
                        MockLine(
                            productId = "beta",
                            quantity = 2,
                            amount = 20.0,
                            createdAt = Instant.parse("2026-01-02T18:00:00Z"),
                            discounts = listOf(MockDiscount("LOYALTY", 6.0)),
                        ),
                        MockLine(
                            productId = "delta",
                            quantity = 5,
                            amount = 50.0,
                            createdAt = Instant.parse("2026-02-02T10:00:00Z"),
                            discounts = emptyList(),
                        ),
                    ),
                ),
            ),
        )

    private fun aggregationAnyNullState(): MockStateAggregate =
        MockStateAggregate(
            id = "aggregation-any-null",
            orders = listOf(
                MockOrder(
                    status = "PAID",
                    lines = listOf(
                        MockLine(
                            productId = "alpha",
                            quantity = 1,
                            amount = 40.0,
                            createdAt = Instant.parse("2026-01-04T10:00:00Z"),
                            discounts = emptyList(),
                            productName = null,
                        ),
                    ),
                ),
            ),
        )

    private companion object {
        const val AGGREGATION_SNAPSHOT_TIME = 1_767_225_600_000L
    }
}
