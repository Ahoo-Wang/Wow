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
import me.ahoo.wow.query.dsl.aggregationQuery
import me.ahoo.wow.query.dsl.condition
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.dsl.pagedQuery
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.aggregate
import me.ahoo.wow.query.snapshot.count
import me.ahoo.wow.query.snapshot.dynamicQuery
import me.ahoo.wow.query.snapshot.query
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test
import java.time.Instant
import java.time.temporal.ChronoUnit

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
            SimpleSnapshot(stateAggregate, FIXED_SNAPSHOT_TIME)
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
        condition {
            id(snapshot.aggregateId.id)
        }.count(snapshotQueryService)
            .test()
            .expectNext(1L)
            .verifyComplete()
    }

    @Test
    fun aggregateGlobalMetrics() {
        val second = saveSnapshot(FIXED_SNAPSHOT_TIME + 1_000)

        aggregationQuery {
            count("snapshotCount")
            sum("snapshotTime", "totalSnapshotTime")
            avg("snapshotTime", "averageSnapshotTime")
            min("snapshotTime", "minimumSnapshotTime")
            max("snapshotTime", "maximumSnapshotTime")
        }.aggregate(snapshotQueryService)
            .test()
            .consumeNextWith { result ->
                result["snapshotCount"].assert().isEqualTo(2L)
                result["totalSnapshotTime"].assert()
                    .isEqualTo(FIXED_SNAPSHOT_TIME.toDouble() + second.snapshotTime.toDouble())
                result["averageSnapshotTime"].assert()
                    .isEqualTo((FIXED_SNAPSHOT_TIME.toDouble() + second.snapshotTime.toDouble()) / 2)
                result["minimumSnapshotTime"].assert().isEqualTo(FIXED_SNAPSHOT_TIME.toDouble())
                result["maximumSnapshotTime"].assert().isEqualTo(second.snapshotTime.toDouble())
            }
            .verifyComplete()
    }

    @Test
    fun aggregateIntegralTerms() {
        aggregationQuery {
            groupBy("version", "version")
            count("snapshotCount")
        }.aggregate(snapshotQueryService)
            .test()
            .consumeNextWith { result ->
                result["version"].assert().isEqualTo(snapshot.version.toLong())
                result["snapshotCount"].assert().isEqualTo(1L)
            }
            .verifyComplete()
    }

    @Test
    fun aggregateMultiDimensionalBuckets() {
        saveSnapshot(FIXED_SNAPSHOT_TIME + 1_000)
        saveSnapshot(FIXED_SNAPSHOT_TIME + HOUR_MILLIS)

        aggregationQuery {
            groupBy("contextName", "context")
            histogram("snapshotTime", "hour", HOUR_MILLIS.toDouble())
            count("snapshotCount")
            sort {
                "snapshotCount".desc()
            }
            limit(10)
        }.aggregate(snapshotQueryService)
            .collectList()
            .test()
            .consumeNextWith { result ->
                result.assert().hasSize(2)
                result[0]["snapshotCount"].assert().isEqualTo(2L)
                result[0]["hour"].assert().isEqualTo(hourBucket(FIXED_SNAPSHOT_TIME))
                result[1]["snapshotCount"].assert().isEqualTo(1L)
                result[1]["hour"].assert().isEqualTo(hourBucket(FIXED_SNAPSHOT_TIME + HOUR_MILLIS))
            }
            .verifyComplete()
    }

    @Test
    fun aggregateDateHistogram() {
        saveSnapshot(FIXED_SNAPSHOT_TIME + 1_000)

        aggregationQuery {
            groupBy("contextName", "context")
            dateHistogram("snapshotTime", "day", AggregationDateUnit.DAY)
            count("snapshotCount")
        }.aggregate(snapshotQueryService)
            .test()
            .consumeNextWith { result ->
                result["context"].assert().isEqualTo(MOCK_AGGREGATE_METADATA.contextName)
                result["day"].assert().isEqualTo(
                    Instant.ofEpochMilli(FIXED_SNAPSHOT_TIME).truncatedTo(ChronoUnit.DAYS).toEpochMilli()
                )
                result["snapshotCount"].assert().isEqualTo(2L)
            }
            .verifyComplete()
    }

    @Test
    fun aggregateEmptyGlobalMetrics() {
        aggregationQuery {
            condition {
                id("missing")
            }
            count("snapshotCount")
            sum("snapshotTime", "totalSnapshotTime")
            avg("snapshotTime", "averageSnapshotTime")
        }.aggregate(snapshotQueryService)
            .test()
            .consumeNextWith { result ->
                result["snapshotCount"].assert().isEqualTo(0L)
                result["totalSnapshotTime"].assert().isEqualTo(0.0)
                result["averageSnapshotTime"].assert().isNull()
            }
            .verifyComplete()
    }

    private fun saveSnapshot(snapshotTime: Long): Snapshot<MockStateAggregate> {
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(generateGlobalId())
        val stateAggregate = ConstructorStateAggregateFactory.create(MOCK_AGGREGATE_METADATA.state, aggregateId)
        val additionalSnapshot = SimpleSnapshot(stateAggregate, snapshotTime)
        snapshotStore.save(additionalSnapshot).block()
        return additionalSnapshot
    }

    private companion object {
        const val FIXED_SNAPSHOT_TIME = 1_700_000_000_000L
        const val HOUR_MILLIS = 3_600_000L

        fun hourBucket(value: Long): Double =
            (value / HOUR_MILLIS * HOUR_MILLIS).toDouble()
    }
}
