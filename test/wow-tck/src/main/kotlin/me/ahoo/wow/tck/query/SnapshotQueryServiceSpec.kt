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
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.count
import me.ahoo.wow.query.snapshot.dynamicQuery
import me.ahoo.wow.query.snapshot.query
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

abstract class SnapshotQueryServiceSpec {
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
    fun count() {
        filterExpression {
            id(snapshot.aggregateId.id)
        }.count(snapshotQueryService)
            .test()
            .expectNext(1L)
            .verifyComplete()
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
            sum(field("productId") * constant(1.0), "text")
            sum(field("missing") * constant(1.0), "missing")
            sum(constant(Double.MAX_VALUE) * constant(2.0), "overflow")
        }.query(snapshotQueryService)
            .test()
            .assertNext {
                it.toMap().assert().isEqualTo(
                    mapOf(
                        "safeDivision" to 5.0,
                        "zeroDivision" to null,
                        "text" to null,
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
            count("count")
            sum("version", "total")
        }.query(snapshotQueryService)
            .test()
            .assertNext {
                it.toMap().assert().isEqualTo(mapOf("count" to 0L, "total" to null))
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

    private companion object {
        const val AGGREGATION_SNAPSHOT_TIME = 1_767_225_600_000L
    }
}
