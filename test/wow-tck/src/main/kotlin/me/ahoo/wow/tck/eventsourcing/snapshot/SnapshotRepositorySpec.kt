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
package me.ahoo.wow.tck.eventsourcing.snapshot

import me.ahoo.wow.api.Version
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.metrics.Metrics.metrizable
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateChanged
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test
import java.time.Clock

abstract class SnapshotRepositorySpec {

    protected val aggregateMetadata = MOCK_AGGREGATE_METADATA

    private val stateAggregateFactory: StateAggregateFactory = ConstructorStateAggregateFactory

    protected abstract fun createSnapshotRepository(): SnapshotRepository

    @Test
    fun load() {
        val snapshotRepository = createSnapshotRepository().metrizable()
        val stateAggregate =
            stateAggregateFactory.create(
                aggregateMetadata.state,
                aggregateMetadata.aggregateId(GlobalIdGenerator.generateAsString()),
            ).block()!!
        val command = GivenInitializationCommand(stateAggregate.aggregateId)
        assertThat(stateAggregate, notNullValue())

        val aggregateCreated = MockAggregateCreated(GlobalIdGenerator.generateAsString())
        val changed = MockAggregateChanged(GlobalIdGenerator.generateAsString())
        val eventStream = listOf(aggregateCreated, changed).toDomainEventStream(
            upstream = command,
            aggregateVersion = stateAggregate.version,
        )
        stateAggregate.onSourcing(eventStream)
        val snapshot: SimpleSnapshot<MockStateAggregate> =
            SimpleSnapshot(delegate = stateAggregate, snapshotTime = Clock.systemUTC().millis())

        snapshotRepository.save(snapshot)
            .test()
            .verifyComplete()
        snapshotRepository.getVersion(stateAggregate.aggregateId)
            .test()
            .expectNext(stateAggregate.version)
            .verifyComplete()
        snapshotRepository.load<MockStateAggregate>(stateAggregate.aggregateId)
            .test()
            .consumeNextWith {
                assertThat(
                    it.aggregateId,
                    equalTo(stateAggregate.aggregateId),
                )
                assertThat(
                    it.version,
                    equalTo(stateAggregate.version),
                )
                assertThat(
                    it.state.data,
                    equalTo(stateAggregate.state.data),
                )
            }
            .verifyComplete()
    }

    @Test
    fun getVersion() {
        val snapshotRepository = createSnapshotRepository().metrizable()
        val aggregateId = aggregateMetadata.aggregateId(GlobalIdGenerator.generateAsString())
        snapshotRepository.getVersion(aggregateId)
            .test()
            .expectNext(Version.UNINITIALIZED_VERSION)
            .verifyComplete()
    }

    @Test
    fun loadWhenNotFound() {
        val snapshotRepository = createSnapshotRepository().metrizable()

        val aggregateId = aggregateMetadata.aggregateId(GlobalIdGenerator.generateAsString())
        snapshotRepository.load<MockStateAggregate>(aggregateId)
            .test()
            .expectNextCount(0)
            .verifyComplete()
    }

    @Test
    fun save() {
        val snapshotRepository = createSnapshotRepository().metrizable()
        val aggregateId = aggregateMetadata.aggregateId(GlobalIdGenerator.generateAsString())
        val stateAggregate = stateAggregateFactory.create(aggregateMetadata.state, aggregateId).block()!!
        val snapshot: Snapshot<MockStateAggregate> =
            SimpleSnapshot(stateAggregate, Clock.systemUTC().millis())
        snapshotRepository.save(snapshot)
            .test()
            .verifyComplete()
    }

    @Test
    open fun saveTwice() {
        val snapshotRepository = createSnapshotRepository().metrizable()
        val stateAggregate =
            stateAggregateFactory.create(
                aggregateMetadata.state,
                aggregateMetadata.aggregateId(GlobalIdGenerator.generateAsString()),
            ).block()!!
        val command = GivenInitializationCommand(stateAggregate.aggregateId)
        assertThat(stateAggregate, notNullValue())

        val aggregateCreated = MockAggregateCreated(GlobalIdGenerator.generateAsString())
        val changed = MockAggregateChanged(GlobalIdGenerator.generateAsString())
        val eventStream = listOf(aggregateCreated, changed).toDomainEventStream(
            upstream = command,
            aggregateVersion = stateAggregate.version,
        )
        stateAggregate.onSourcing(eventStream)
        val snapshot: SimpleSnapshot<MockStateAggregate> =
            SimpleSnapshot(delegate = stateAggregate, snapshotTime = Clock.systemUTC().millis())

        snapshotRepository.save(snapshot)
            .test()
            .verifyComplete()

        val eventStream2 = listOf(aggregateCreated, changed).toDomainEventStream(
            upstream = command,
            aggregateVersion = stateAggregate.version,
        )
        stateAggregate.onSourcing(eventStream2)

        snapshotRepository.save(snapshot)
            .test()
            .verifyComplete()

        snapshotRepository.load<MockStateAggregate>(stateAggregate.aggregateId)
            .test()
            .consumeNextWith {
                assertThat(
                    it.aggregateId,
                    equalTo(stateAggregate.aggregateId),
                )
                assertThat(
                    it.version,
                    equalTo(stateAggregate.version),
                )
                assertThat(
                    it.state.data,
                    equalTo(stateAggregate.state.data),
                )
            }
            .verifyComplete()
    }

    @Test
    open fun scanAggregateId() {
        val snapshotRepository = createSnapshotRepository().metrizable()
        val cursorId = generateGlobalId()
        val aggregateId = aggregateMetadata.aggregateId(generateGlobalId())
        val stateAggregate = stateAggregateFactory.create(aggregateMetadata.state, aggregateId).block()!!
        val snapshot: Snapshot<MockStateAggregate> =
            SimpleSnapshot(stateAggregate, Clock.systemUTC().millis())
        snapshotRepository.save(snapshot)
            .test()
            .verifyComplete()

        snapshotRepository.scanAggregateId(snapshot.aggregateId, cursorId = cursorId, limit = 1)
            .test()
            .expectNextCount(1)
            .verifyComplete()
        snapshotRepository.scanAggregateId(snapshot.aggregateId, cursorId = snapshot.aggregateId.id, limit = 1)
            .test()
            .expectNextCount(0)
            .verifyComplete()
    }
}
