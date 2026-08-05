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
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory.toStateAggregate
import me.ahoo.wow.query.dsl.condition
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.dsl.pagedQuery
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.count
import me.ahoo.wow.query.snapshot.dynamicQuery
import me.ahoo.wow.query.snapshot.query
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.kotlin.test.test
import java.time.Clock

abstract class SnapshotQueryServiceSpec {
    lateinit var snapshotStore: SnapshotStore
    lateinit var snapshotQueryServiceFactory: SnapshotQueryServiceFactory
    lateinit var snapshotQueryService: SnapshotQueryService<MockStateAggregate>
    lateinit var snapshot: Snapshot<MockStateAggregate>
    lateinit var operatorQueryService: SnapshotQueryService<QueryOperatorState>
    lateinit var operatorSnapshots: List<Snapshot<QueryOperatorState>>

    @BeforeEach
    open fun setup() {
        snapshotStore = createSnapshotStore()
        snapshotQueryServiceFactory = createSnapshotQueryServiceFactory()
        snapshotQueryService = snapshotQueryServiceFactory.create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)
        operatorQueryService =
            snapshotQueryServiceFactory.create<QueryOperatorState>(QUERY_OPERATOR_AGGREGATE_METADATA)
        prepareOperatorSnapshotStorage()
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(generateGlobalId())
        val stateAggregate =
            ConstructorStateAggregateFactory.create(MOCK_AGGREGATE_METADATA.state, aggregateId)
        snapshot =
            SimpleSnapshot(stateAggregate, Clock.systemUTC().millis())
        operatorSnapshots = createOperatorSnapshots()
        Flux.concat(
            listOf(snapshotStore.save(snapshot)) + operatorSnapshots.map(snapshotStore::save)
        )
            .test()
            .verifyComplete()
    }

    protected open fun prepareOperatorSnapshotStorage() = Unit

    private fun createOperatorSnapshots(): List<Snapshot<QueryOperatorState>> =
        listOf(
            createOperatorSnapshot(
                name = "Prefix-Mid*?\\-Suffix",
                score = 10,
                labels = listOf("red", "blue"),
                active = true,
                items = listOf(
                    QueryOperatorItem("sku-a", 2),
                    QueryOperatorItem("sku-shared", 1),
                ),
            ),
            createOperatorSnapshot(
                name = "PREFIX-Other-Suffix",
                score = 20,
                labels = listOf("blue", "green"),
                active = false,
                items = listOf(
                    QueryOperatorItem("sku-a", 1),
                    QueryOperatorItem("sku-b", 3),
                ),
            ),
            createOperatorSnapshot(
                name = "Other",
                score = 30,
                labels = listOf("green"),
                active = true,
                items = listOf(QueryOperatorItem("sku-c", 4)),
            ),
        )

    private fun createOperatorSnapshot(
        name: String,
        score: Int,
        labels: List<String>,
        active: Boolean,
        items: List<QueryOperatorItem>
    ): Snapshot<QueryOperatorState> {
        val id = generateGlobalId()
        val state =
            QueryOperatorState(id).apply {
                this.name = name
                this.score = score
                this.labels = labels
                this.active = active
                this.items = items
            }
        val stateAggregate = QUERY_OPERATOR_AGGREGATE_METADATA.toStateAggregate(state, version = 1)
        return SimpleSnapshot(stateAggregate, Clock.systemUTC().millis())
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

    private fun assertMatches(
        condition: Condition,
        vararg expectedIndexes: Int
    ) {
        val scopedCondition =
            Condition.and(
                Condition.ids(operatorSnapshots.map { it.aggregateId.id }),
                condition,
            )
        val expectedIds = expectedIndexes.map { operatorSnapshots[it].aggregateId.id }
        listQuery {
            condition(scopedCondition)
            limit(operatorSnapshots.size)
        }.query(operatorQueryService)
            .map { it.aggregateId }
            .collectList()
            .test()
            .assertNext { actualIds ->
                actualIds.assert().containsExactlyInAnyOrder(*expectedIds.toTypedArray())
            }
            .verifyComplete()
    }

    @Test
    fun `common comparison operators follow MongoDB semantics`() {
        assertMatches(Condition.eq("state.score", 10), 0)
        assertMatches(Condition.ne("state.score", 10), 1, 2)
        assertMatches(Condition.gt("state.score", 10), 1, 2)
        assertMatches(Condition.gte("state.score", 20), 1, 2)
        assertMatches(Condition.lt("state.score", 30), 0, 1)
        assertMatches(Condition.lte("state.score", 20), 0, 1)
        assertMatches(Condition.between("state.score", 10, 20), 0, 1)
    }

    @Test
    fun `common collection operators follow MongoDB semantics`() {
        assertMatches(Condition.ids(emptyList()))
        assertMatches(Condition.isIn("state.score", listOf(10, 30)), 0, 2)
        assertMatches(Condition.notIn("state.score", listOf(10, 30)), 1)
        assertMatches(Condition.isIn("state.score", emptyList()))
        assertMatches(Condition.notIn("state.score", emptyList()), 0, 1, 2)
        assertMatches(Condition.all("state.labels", emptyList()))
        assertMatches(Condition.all("state.labels", listOf("red", "red", "blue")), 0)
    }

    @Test
    fun `common string operators are literal and honor ignoreCase`() {
        assertMatches(Condition.contains("state.name", "Mid*?\\"), 0)
        assertMatches(Condition.contains("state.name", "prefix", ignoreCase = true), 0, 1)
        assertMatches(Condition.startsWith("state.name", "prefix", ignoreCase = true), 0, 1)
        assertMatches(Condition.endsWith("state.name", "suffix", ignoreCase = true), 0, 1)
    }

    @Test
    fun `common boolean and logical operators follow MongoDB semantics`() {
        assertMatches(Condition.isTrue("state.active"), 0, 2)
        assertMatches(Condition.isFalse("state.active"), 1)
        assertMatches(Condition.exists("state.name"), 0, 1, 2)
        assertMatches(Condition.exists("state.name", exists = false))
        assertMatches(
            Condition.and(
                Condition.gte("state.score", 10),
                Condition.lte("state.score", 20),
            ),
            0,
            1,
        )
        assertMatches(
            Condition.or(
                Condition.eq("state.score", 10),
                Condition.eq("state.score", 30),
            ),
            0,
            2,
        )
        assertMatches(
            Condition.nor(
                Condition.eq("state.score", 10),
                Condition.eq("state.score", 30),
            ),
            1,
        )
    }

    @Test
    fun `elemMatch uses one array element and relative child fields`() {
        assertMatches(
            Condition.elemMatch(
                "state.items",
                Condition.and(
                    Condition.eq("sku", "sku-a"),
                    Condition.gte("quantity", 2),
                ),
            ),
            0,
        )
    }
}
