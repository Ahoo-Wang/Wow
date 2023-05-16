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

import me.ahoo.wow.event.asDomainEventStream
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.metrics.Metrics.metrizable
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.asAggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.tck.modeling.AggregateChanged
import me.ahoo.wow.tck.modeling.AggregateCreated
import me.ahoo.wow.tck.modeling.MockAggregate
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test
import java.time.Clock

abstract class SnapshotRepositorySpec {

    protected val aggregateMetadata =
        aggregateMetadata<MockAggregate, MockAggregate>()

    private val stateAggregateFactory: StateAggregateFactory = ConstructorStateAggregateFactory

    protected abstract fun createSnapshotRepository(): SnapshotRepository

    @Test
    fun load() {
        val snapshotRepository = createSnapshotRepository().metrizable()
        val stateAggregate =
            stateAggregateFactory.create(
                aggregateMetadata.state,
                aggregateMetadata.asAggregateId(GlobalIdGenerator.generateAsString()),
            ).block()!!
        val command = GivenInitializationCommand(stateAggregate.aggregateId)
        assertThat(stateAggregate, notNullValue())

        val aggregateCreated = AggregateCreated(GlobalIdGenerator.generateAsString())
        val changed = AggregateChanged(GlobalIdGenerator.generateAsString())
        val eventStream = listOf(aggregateCreated, changed).asDomainEventStream(
            command = command,
            aggregateVersion = stateAggregate.version,
        )
        stateAggregate.onSourcing(eventStream)
        val snapshot: SimpleSnapshot<MockAggregate> =
            SimpleSnapshot(delegate = stateAggregate, snapshotTime = Clock.systemUTC().millis())

        snapshotRepository.save(snapshot)
            .test()
            .verifyComplete()

        snapshotRepository.load<MockAggregate>(stateAggregate.aggregateId)
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
                    it.stateRoot.state(),
                    equalTo(stateAggregate.stateRoot.state()),
                )
            }
            .verifyComplete()
    }

    @Test
    fun loadWhenNotFound() {
        val snapshotRepository = createSnapshotRepository().metrizable()

        val aggregateId = aggregateMetadata.asAggregateId(GlobalIdGenerator.generateAsString())
        snapshotRepository.load<MockAggregate>(aggregateId)
            .test()
            .expectNextCount(0)
            .verifyComplete()
    }

    @Test
    fun save() {
        val snapshotRepository = createSnapshotRepository().metrizable()
        val aggregateId = aggregateMetadata.asAggregateId(GlobalIdGenerator.generateAsString())
        val stateAggregate = stateAggregateFactory.create(aggregateMetadata.state, aggregateId).block()!!
        val snapshot: Snapshot<MockAggregate> =
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
                aggregateMetadata.asAggregateId(GlobalIdGenerator.generateAsString()),
            ).block()!!
        val command = GivenInitializationCommand(stateAggregate.aggregateId)
        assertThat(stateAggregate, notNullValue())

        val aggregateCreated = AggregateCreated(GlobalIdGenerator.generateAsString())
        val changed = AggregateChanged(GlobalIdGenerator.generateAsString())
        val eventStream = listOf(aggregateCreated, changed).asDomainEventStream(
            command = command,
            aggregateVersion = stateAggregate.version,
        )
        stateAggregate.onSourcing(eventStream)
        val snapshot: SimpleSnapshot<MockAggregate> =
            SimpleSnapshot(delegate = stateAggregate, snapshotTime = Clock.systemUTC().millis())

        snapshotRepository.save(snapshot)
            .test()
            .verifyComplete()

        val eventStream2 = listOf(aggregateCreated, changed).asDomainEventStream(
            command = command,
            aggregateVersion = stateAggregate.version,
        )
        stateAggregate.onSourcing(eventStream2)

        snapshotRepository.save(snapshot)
            .test()
            .verifyComplete()

        snapshotRepository.load<MockAggregate>(stateAggregate.aggregateId)
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
                    it.stateRoot.state(),
                    equalTo(stateAggregate.stateRoot.state()),
                )
            }
            .verifyComplete()
    }

    @Test
    open fun scrollAggregateId() {
        val snapshotRepository = createSnapshotRepository().metrizable()
        val aggregateId = aggregateMetadata.asAggregateId(GlobalIdGenerator.generateAsString())
        val stateAggregate = stateAggregateFactory.create(aggregateMetadata.state, aggregateId).block()!!
        val snapshot: Snapshot<MockAggregate> =
            SimpleSnapshot(stateAggregate, Clock.systemUTC().millis())
        snapshotRepository.save(snapshot)
            .test()
            .verifyComplete()

        snapshotRepository.scrollAggregateId(aggregateId, limit = 1)
            .test()
            .expectNextCount(1)
            .verifyComplete()
    }
}
