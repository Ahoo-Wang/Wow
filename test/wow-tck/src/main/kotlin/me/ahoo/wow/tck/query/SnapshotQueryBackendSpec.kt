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
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.LogicalField
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
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory.toStateAggregate
import me.ahoo.wow.query.dsl.aggregation
import me.ahoo.wow.query.dsl.condition
import me.ahoo.wow.query.dsl.filterExpression
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.dsl.pagedQuery
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.query.schema.toMetadata
import me.ahoo.wow.query.snapshot.SnapshotQueryBackend
import me.ahoo.wow.query.snapshot.SnapshotQueryBackendFactory
import me.ahoo.wow.query.snapshot.requiredQueryModelSchemaProvider
import me.ahoo.wow.schema.query.JsonQuerySchemaSource
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.convert
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockDiscount
import me.ahoo.wow.tck.mock.MockLine
import me.ahoo.wow.tck.mock.MockOrder
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.concurrent.TimeUnit

@Suppress("LargeClass")
abstract class SnapshotQueryBackendSpec {
    protected val querySchemaSources: List<QuerySchemaSource> = listOf(JsonQuerySchemaSource())
    lateinit var snapshotStore: SnapshotStore
    lateinit var snapshotQueryBackendFactory: SnapshotQueryBackendFactory
    lateinit var snapshotQueryBackend: SnapshotQueryBackend
    lateinit var snapshot: Snapshot<MockStateAggregate>

    @BeforeEach
    open fun setup() {
        snapshotStore = createSnapshotStore()
        snapshotQueryBackendFactory = createSnapshotQueryBackendFactory()
        snapshotQueryBackend = snapshotQueryBackendFactory.create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)
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
    protected abstract fun createSnapshotQueryBackendFactory(): SnapshotQueryBackendFactory

    @Test
    fun createFromCache() {
        val queryService1 = snapshotQueryBackendFactory.create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)
        val queryService2 = snapshotQueryBackendFactory.create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)
        queryService1.assert().isSameAs(queryService2)
    }

    @Test
    fun name() {
        snapshotQueryBackendFactory.create<MockStateAggregate>(MOCK_AGGREGATE_METADATA).name.assert().isNotBlank()
    }

    @Test
    fun `single should expose canonical snapshot json`() {
        singleQuery {
            condition {
                id(snapshot.aggregateId.id)
            }
        }.query(snapshotQueryBackend)
            .test()
            .assertNext { node ->
                node.path("aggregateId").textValue().assert().isEqualTo(snapshot.aggregateId.id)
                node.path("state").isObject.assert().isTrue()
            }
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
        }.dynamicQuery(snapshotQueryBackend)
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
        }.query(snapshotQueryBackend)
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
        }.dynamicQuery(snapshotQueryBackend)
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
        }.query(snapshotQueryBackend)
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
        }.dynamicQuery(snapshotQueryBackend)
            .test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun count() {
        filterExpression {
            id(snapshot.aggregateId.id)
        }.count(snapshotQueryBackend)
            .test()
            .expectNext(1L)
            .verifyComplete()
    }

    @Test
    fun `schema should expose system state and backend-neutral capabilities`() {
        snapshotQueryBackend.requiredQueryModelSchemaProvider().schema()
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
        val provider = snapshotQueryBackend.requiredQueryModelSchemaProvider()
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
        }.query(snapshotQueryBackend)
            .test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun `schema should aggregate annotated epoch fields as time`() {
        aggregation {
            dateHistogram("state.createdAt", AggregationDateUnit.DAY, "day")
            count("count")
        }.query(snapshotQueryBackend)
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
        }.query(snapshotQueryBackend)
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
        }.query(snapshotQueryBackend)
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
        }.query(snapshotQueryBackend)
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
        }.query(snapshotQueryBackend)
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
        }.query(snapshotQueryBackend)
            .collectList()
            .test()
            .assertNext { rows ->
                rows.map(ObjectNode::toMap).assert().containsExactly(
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
        }.query(snapshotQueryBackend)
            .collectList()
            .test()
            .assertNext { rows ->
                rows.map(ObjectNode::toMap).assert().containsExactly(
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
        }.query(snapshotQueryBackend)
            .collectList()
            .test()
            .assertNext { rows ->
                rows.map(ObjectNode::toMap).assert().containsExactly(
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
        }.query(snapshotQueryBackend)
            .collectList()
            .test()
            .assertNext { rows ->
                rows.map(ObjectNode::toMap).assert().containsExactly(
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
        }.query(snapshotQueryBackend)
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
        }.query(snapshotQueryBackend)
            .test()
            .assertNext { row ->
                val result = row.toMap()
                result["productId"].assert().isEqualTo("alpha")
                setOf("Alpha", "Alpha 2026").contains(result["productName"]).assert().isTrue()
                result["count"].assert().isEqualTo(3L)
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
        }.query(snapshotQueryBackend)
            .test()
            .assertNext { row ->
                val result = row.toMap()
                result.containsKey("productName").assert().isTrue()
                result["productName"].assert().isNull()
                result["count"].assert().isEqualTo(1L)
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
        }.query(snapshotQueryBackend)
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
        }.query(snapshotQueryBackend)
            .collectList()
            .test()
            .assertNext { rows ->
                rows.map { row ->
                    row.toMap().let { listOf(it["product"], it["quantityBucket"], it["count"]) }
                }.assert().containsExactly(
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
        }.query(snapshotQueryBackend)
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
        }.query(snapshotQueryBackend)
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

private fun ISingleQuery.query(backend: SnapshotQueryBackend): Mono<ObjectNode> = backend.single(this)
private fun ISingleQuery.dynamicQuery(backend: SnapshotQueryBackend): Mono<ObjectNode> = backend.single(this)
private fun IListQuery.query(backend: SnapshotQueryBackend): Flux<ObjectNode> = backend.list(this)
private fun IListQuery.dynamicQuery(backend: SnapshotQueryBackend): Flux<ObjectNode> = backend.list(this)
private fun IPagedQuery.query(backend: SnapshotQueryBackend) = backend.paged(this)
private fun IPagedQuery.dynamicQuery(backend: SnapshotQueryBackend) = backend.paged(this)
private fun FilterExpression.count(backend: SnapshotQueryBackend): Mono<Long> = backend.count(this)
private fun AggregationQuery.query(backend: SnapshotQueryBackend): Flux<ObjectNode> = backend.aggregate(this)
private fun ObjectNode.toMap(): Map<String, Any?> = convert()
