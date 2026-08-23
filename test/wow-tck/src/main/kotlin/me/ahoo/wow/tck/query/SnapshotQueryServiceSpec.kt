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
import me.ahoo.wow.api.query.AggregationElement
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.query.dsl.condition
import me.ahoo.wow.query.dsl.filter
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.dsl.pagedQuery
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.count
import me.ahoo.wow.query.snapshot.dynamicQuery
import me.ahoo.wow.query.snapshot.query
import me.ahoo.wow.serialization.toLinkedHashMap
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockOrder
import me.ahoo.wow.tck.mock.MockOrderLine
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

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
        prepareAggregationStorage()
        snapshot.state.orders = listOf(
            MockOrder(
                status = "PAID",
                amount = 30.0,
                lines = listOf(
                    MockOrderLine("A", 1, 10.0, false, Instant.parse("2024-01-01T00:00:00Z")),
                    MockOrderLine("B", 2, 40.0, true, Instant.parse("2024-01-02T00:00:00Z")),
                ),
            ),
            MockOrder(
                status = "NEW",
                amount = 30.0,
                lines = listOf(
                    MockOrderLine("A", 3, 30.0, false, Instant.parse("2024-02-01T00:00:00Z")),
                ),
            ),
            MockOrder(status = "PAID", amount = 0.0, lines = emptyList()),
            null,
        )
        val serializedOrders = (snapshot.toLinkedHashMap()["state"] as Map<*, *>)["orders"] as List<*>
        check(serializedOrders.size == 4) { "Aggregation TCK snapshot must serialize nested and null elements." }
        snapshotStore.save(snapshot)
            .test()
            .verifyComplete()
    }

    protected open fun prepareAggregationStorage() = Unit
    protected open val rootElementStatusField: String = "status"

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
        condition {
            id(snapshot.aggregateId.id)
        }.count(snapshotQueryService)
            .test()
            .expectNext(1L)
            .verifyComplete()
    }

    @Test
    fun aggregateGlobal() {
        snapshotQueryService.aggregate(
            AggregationQuery(
                metrics = listOf(
                    AggregationMetric.Count("count"),
                    AggregationMetric.Numeric(
                        AggregationFunction.SUM,
                        AggregationExpression.Field("version"),
                        "versionTotal",
                    ),
                    AggregationMetric.Numeric(
                        AggregationFunction.AVG,
                        AggregationExpression.Field("version"),
                        "versionAverage",
                    ),
                    AggregationMetric.Numeric(
                        AggregationFunction.MIN,
                        AggregationExpression.Field("version"),
                        "versionMinimum",
                    ),
                    AggregationMetric.Numeric(
                        AggregationFunction.MAX,
                        AggregationExpression.Field("version"),
                        "versionMaximum",
                    ),
                ),
            ),
        ).test()
            .assertNext { row ->
                row.getValue<Long>("count").assert().isOne()
                row.getValue<Double>("versionTotal").assert().isEqualTo(snapshot.version.toDouble())
                row.getValue<Double>("versionAverage").assert().isEqualTo(snapshot.version.toDouble())
                row.getValue<Double>("versionMinimum").assert().isEqualTo(snapshot.version.toDouble())
                row.getValue<Double>("versionMaximum").assert().isEqualTo(snapshot.version.toDouble())
            }
            .verifyComplete()
    }

    @Test
    fun aggregateGroupedAndEmpty() {
        snapshotQueryService.aggregate(
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms("aggregateId", "aggregateId")),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
        ).test()
            .assertNext { row ->
                row.getValue<String>("aggregateId").assert().isEqualTo(snapshot.aggregateId.id)
                row.getValue<Long>("count").assert().isOne()
            }
            .verifyComplete()

        snapshotQueryService.aggregate(
            AggregationQuery(
                filter = filter { "_id" eq "missing" },
                metrics = listOf(
                    AggregationMetric.Count("count"),
                    AggregationMetric.Numeric(
                        AggregationFunction.SUM,
                        AggregationExpression.Field("version"),
                        "sum",
                    ),
                    AggregationMetric.Numeric(
                        AggregationFunction.AVG,
                        AggregationExpression.Field("version"),
                        "average",
                    ),
                    AggregationMetric.Numeric(
                        AggregationFunction.MIN,
                        AggregationExpression.Field("version"),
                        "min",
                    ),
                    AggregationMetric.Numeric(
                        AggregationFunction.MAX,
                        AggregationExpression.Field("version"),
                        "max",
                    ),
                ),
            ),
        ).test()
            .assertNext { row ->
                row.getValue<Long>("count").assert().isZero()
                row.getValue<Double>("sum").assert().isEqualTo(0.0)
                row["average"].assert().isNull()
                row["min"].assert().isNull()
                row["max"].assert().isNull()
            }
            .verifyComplete()
    }

    @Test
    fun aggregateElementsAndNestedElements() {
        snapshotQueryService.aggregate(
            AggregationQuery(
                elements = listOf(
                    AggregationElement(
                        "state.orders",
                        filter { "state.orders.status" eq "PAID" },
                    ),
                ),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
        ).test()
            .assertNext { row -> row.getValue<Long>("count").assert().isEqualTo(2L) }
            .verifyComplete()

        snapshotQueryService.aggregate(
            AggregationQuery(
                elements = listOf(
                    AggregationElement(
                        "state.orders",
                        filter { "state.orders.status" eq "PAID" },
                    ),
                    AggregationElement(
                        "state.orders.lines",
                        filter { "state.orders.lines.cancelled" eq false },
                    ),
                ),
                groupBy = listOf(AggregationGroup.Terms("state.orders.lines.sku", "sku")),
                metrics = listOf(
                    AggregationMetric.Count("count"),
                    AggregationMetric.Numeric(
                        AggregationFunction.SUM,
                        AggregationExpression.Field("state.orders.lines.amount"),
                        "amount",
                    ),
                ),
            ),
        ).test()
            .assertNext { row ->
                row.getValue<String>("sku").assert().isEqualTo("A")
                row.getValue<Long>("count").assert().isOne()
                row.getValue<Double>("amount").assert().isEqualTo(10.0)
            }
            .verifyComplete()
    }

    @Test
    fun aggregateRejectsUndeclaredElementFields() {
        snapshotQueryService.aggregate(
            AggregationQuery(
                groupBy = listOf(AggregationGroup.Terms("state.orders.status", "status")),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
        ).test()
            .expectError(IllegalArgumentException::class.java)
            .verify()
    }

    @Test
    fun rootElemMatchShouldNotFilterExpandedRows() {
        snapshotQueryService.aggregate(
            AggregationQuery(
                filter = filter {
                    "state.orders".elementMatch { rootElementStatusField eq "PAID" }
                },
                elements = listOf(AggregationElement("state.orders")),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
        ).test()
            .assertNext { row -> row.getValue<Long>("count").assert().isEqualTo(3L) }
            .verifyComplete()
    }

    @Test
    fun aggregateElementsWithExactMetricTopN() {
        snapshotQueryService.aggregate(
            AggregationQuery(
                elements = listOf(
                    AggregationElement("state.orders"),
                    AggregationElement("state.orders.lines"),
                ),
                groupBy = listOf(AggregationGroup.Terms("state.orders.lines.sku", "sku")),
                metrics = listOf(
                    AggregationMetric.Count("count"),
                    AggregationMetric.Numeric(
                        AggregationFunction.SUM,
                        AggregationExpression.Field("state.orders.lines.amount"),
                        "amount",
                    ),
                ),
                sort = listOf(Sort("amount", Sort.Direction.DESC)),
                limit = 1,
            ),
        ).test()
            .assertNext { row ->
                row.getValue<String>("sku").assert().isEqualTo("A")
                row.getValue<Long>("count").assert().isEqualTo(2L)
                row.getValue<Double>("amount").assert().isEqualTo(40.0)
            }
            .verifyComplete()
    }

    @Test
    fun aggregateElementsShouldNormalizeGroupValues() {
        snapshotQueryService.aggregate(
            AggregationQuery(
                elements = listOf(
                    AggregationElement("state.orders"),
                    AggregationElement("state.orders.lines"),
                ),
                groupBy = listOf(
                    AggregationGroup.Histogram("state.orders.lines.amount", "amountBand", 20.0),
                    AggregationGroup.DateHistogram(
                        "state.orders.lines.createdAt",
                        "day",
                        AggregationDateUnit.DAY,
                    ),
                ),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
        ).collectList().test()
            .assertNext { rows ->
                rows.assert().hasSize(3)
                rows.all { it["amountBand"] is Double }.assert().isTrue()
                rows.all { it["day"] is Long }.assert().isTrue()
                rows.all { it["count"] is Long }.assert().isTrue()
            }
            .verifyComplete()

        snapshotQueryService.aggregate(
            AggregationQuery(
                elements = listOf(
                    AggregationElement("state.orders"),
                    AggregationElement("state.orders.lines"),
                ),
                groupBy = listOf(AggregationGroup.Terms("state.orders.lines.rank", "rank")),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
        ).collectList().test()
            .assertNext { rows ->
                rows.map { it["rank"] }.assert().containsExactly(1L, 2L, 3L)
            }
            .verifyComplete()
    }

    @Test
    fun aggregateElementsShouldPreserveBooleanTerms() {
        snapshotQueryService.aggregate(
            AggregationQuery(
                elements = listOf(
                    AggregationElement("state.orders"),
                    AggregationElement("state.orders.lines"),
                ),
                groupBy = listOf(AggregationGroup.Terms("state.orders.lines.cancelled", "cancelled")),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
        ).collectList().test()
            .assertNext { rows ->
                rows.map { it.getValue<Boolean>("cancelled") to it.getValue<Long>("count") }.assert()
                    .containsExactly(false to 2L, true to 1L)
            }
            .verifyComplete()
    }

    @Test
    fun aggregateElementsShouldFilterRelativeTime() {
        val today = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(43_200)
        snapshot.state.orders = listOf(
            MockOrder(
                status = "PAID",
                amount = 10.0,
                lines = listOf(MockOrderLine("TODAY", 1, 10.0, false, today)),
            ),
        )
        snapshotStore.save(snapshot).test().verifyComplete()

        snapshotQueryService.aggregate(
            AggregationQuery(
                elements = listOf(
                    AggregationElement("state.orders"),
                    AggregationElement(
                        "state.orders.lines",
                        filter {
                            "state.orders.lines.createdAt".today(ZoneOffset.UTC)
                            "state.orders.lines.createdAt" gte today.minusSeconds(3_600).toString()
                        },
                    ),
                ),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
        ).test()
            .assertNext { row -> row.getValue<Long>("count").assert().isOne() }
            .verifyComplete()
    }

    @Test
    fun aggregateElementsShouldCompareTemporalRangesChronologically() {
        snapshotQueryService.aggregate(
            AggregationQuery(
                elements = listOf(
                    AggregationElement("state.orders"),
                    AggregationElement(
                        "state.orders.lines",
                        filter {
                            "state.orders.lines.createdAt" gt "2024-01-01T01:00:00.000+02:00"
                            "state.orders.lines.createdAt" gte "2024-01-01T01:00:00.000+02:00"
                            "state.orders.lines.createdAt" lt "2024-03-01T00:00:00.000Z"
                            "state.orders.lines.createdAt" lte "2024-03-01T00:00:00.000Z"
                            "state.orders.lines.createdAt".between(
                                "2024-01-01T01:00:00.000+02:00",
                                "2024-03-01T00:00:00.000Z",
                            )
                        },
                    ),
                ),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
        ).test()
            .assertNext { row -> row.getValue<Long>("count").assert().isEqualTo(3L) }
            .verifyComplete()
    }

    @Test
    fun aggregateElementsShouldNormalizeTemporalExactMatches() {
        val field = "state.orders.lines.createdAt"
        val equivalent = "2023-12-31T19:00:00.000-05:00"
        listOf(
            filter { field eq equivalent } to 1L,
            filter { field ne equivalent } to 2L,
            filter { field isIn listOf(equivalent) } to 1L,
            filter { field notIn listOf(equivalent) } to 2L,
        ).forEach { (elementFilter, expected) ->
            snapshotQueryService.aggregate(
                AggregationQuery(
                    elements = listOf(
                        AggregationElement("state.orders"),
                        AggregationElement("state.orders.lines", elementFilter),
                    ),
                    metrics = listOf(AggregationMetric.Count("count")),
                ),
            ).test()
                .assertNext { row -> row.getValue<Long>("count").assert().isEqualTo(expected) }
                .verifyComplete()
        }

        snapshotQueryService.aggregate(
            AggregationQuery(
                elements = listOf(
                    AggregationElement("state.orders"),
                    AggregationElement("state.orders.lines", filter { field eq "not-a-date" }),
                ),
                metrics = listOf(AggregationMetric.Count("count")),
            ),
        ).test()
            .expectError()
            .verify()
    }

    @Test
    fun aggregateElementsShouldRejectExistencePredicates() {
        listOf(
            filter { "state.orders.status".exists() },
            filter { "state.orders.status".notExists() },
        ).forEach { elementFilter ->
            snapshotQueryService.aggregate(
                AggregationQuery(
                    elements = listOf(AggregationElement("state.orders", elementFilter)),
                    metrics = listOf(AggregationMetric.Count("count")),
                ),
            ).test()
                .expectError(IllegalArgumentException::class.java)
                .verify()
        }
    }
}
