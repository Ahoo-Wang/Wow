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
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
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
import org.hamcrest.CoreMatchers.sameInstance
import org.hamcrest.MatcherAssert.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test
import java.time.Clock

abstract class SnapshotQueryServiceSpec {
    lateinit var snapshotRepository: SnapshotRepository
    lateinit var snapshotQueryServiceFactory: SnapshotQueryServiceFactory
    lateinit var snapshotQueryService: SnapshotQueryService<MockStateAggregate>
    lateinit var snapshot: Snapshot<MockStateAggregate>

    @BeforeEach
    open fun setup() {
        snapshotRepository = createSnapshotRepository()
        snapshotQueryServiceFactory = createSnapshotQueryServiceFactory()
        snapshotQueryService = snapshotQueryServiceFactory.create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(generateGlobalId())
        val stateAggregate =
            ConstructorStateAggregateFactory.create(MOCK_AGGREGATE_METADATA.state, aggregateId)
        snapshot =
            SimpleSnapshot(stateAggregate, Clock.systemUTC().millis())
        snapshotRepository.save(snapshot)
            .test()
            .verifyComplete()
    }

    protected abstract fun createSnapshotRepository(): SnapshotRepository
    protected abstract fun createSnapshotQueryServiceFactory(): SnapshotQueryServiceFactory

    @Test
    fun createFromCache() {
        val queryService1 = snapshotQueryServiceFactory.create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)
        val queryService2 = snapshotQueryServiceFactory.create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)
        assertThat(queryService1, sameInstance(queryService2))
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
}
