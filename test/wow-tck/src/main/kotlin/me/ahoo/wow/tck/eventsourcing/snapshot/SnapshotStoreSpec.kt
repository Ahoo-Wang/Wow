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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.Version
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.tck.metrics.meteredForTck
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateChanged
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.kotlin.test.test
import java.time.Clock
import kotlin.random.Random

abstract class SnapshotStoreSpec {

    protected val aggregateMetadata = MOCK_AGGREGATE_METADATA

    private val stateAggregateFactory: StateAggregateFactory = ConstructorStateAggregateFactory

    protected abstract fun createSnapshotStore(): SnapshotStore

    @Test
    fun name() {
        val snapshotStore = createSnapshotStore().meteredForTck()
        snapshotStore.name.assert().isNotBlank()
    }

    @Test
    fun load() {
        val snapshotStore = createSnapshotStore().meteredForTck()
        val stateAggregate =
            stateAggregateFactory.create(
                aggregateMetadata.state,
                aggregateMetadata.aggregateId(generateGlobalId()),
            )
        val command = GivenInitializationCommand(stateAggregate.aggregateId)
        stateAggregate.assert().isNotNull()

        val aggregateCreated = MockAggregateCreated(generateGlobalId())
        val changed = MockAggregateChanged(generateGlobalId())
        val eventStream = listOf(aggregateCreated, changed).toDomainEventStream(
            upstream = command,
            aggregateVersion = stateAggregate.version,
        )
        stateAggregate.onSourcing(eventStream)
        val snapshot: SimpleSnapshot<MockStateAggregate> =
            SimpleSnapshot(delegate = stateAggregate, snapshotTime = Clock.systemUTC().millis())

        snapshotStore.save(snapshot)
            .test()
            .verifyComplete()
        snapshotStore.getVersion(stateAggregate.aggregateId)
            .test()
            .expectNext(stateAggregate.version)
            .verifyComplete()
        snapshotStore.load<MockStateAggregate>(stateAggregate.aggregateId)
            .test()
            .consumeNextWith {
                it.aggregateId.assert().isEqualTo(stateAggregate.aggregateId)
                it.version.assert().isEqualTo(stateAggregate.version)
                it.state.data.assert().isEqualTo(stateAggregate.state.data)
            }
            .verifyComplete()
    }

    @Test
    fun getVersion() {
        val snapshotStore = createSnapshotStore().meteredForTck()
        val aggregateId = aggregateMetadata.aggregateId(generateGlobalId())
        snapshotStore.getVersion(aggregateId)
            .test()
            .expectNext(Version.UNINITIALIZED_VERSION)
            .verifyComplete()
    }

    @Test
    fun loadWhenNotFound() {
        val snapshotStore = createSnapshotStore().meteredForTck()

        val aggregateId = aggregateMetadata.aggregateId(generateGlobalId())
        snapshotStore.load<MockStateAggregate>(aggregateId)
            .test()
            .expectNextCount(0)
            .verifyComplete()
    }

    @Test
    fun save() {
        val snapshotStore = createSnapshotStore().meteredForTck()
        val aggregateId = aggregateMetadata.aggregateId(generateGlobalId())
        val stateAggregate = stateAggregateFactory.create(aggregateMetadata.state, aggregateId)
        val snapshot: Snapshot<MockStateAggregate> =
            SimpleSnapshot(stateAggregate, Clock.systemUTC().millis())
        snapshotStore.save(snapshot)
            .test()
            .verifyComplete()
    }

    @Test
    open fun saveTwice() {
        val snapshotStore = createSnapshotStore().meteredForTck()
        val stateAggregate =
            stateAggregateFactory.create(
                aggregateMetadata.state,
                aggregateMetadata.aggregateId(generateGlobalId()),
            )
        val command = GivenInitializationCommand(stateAggregate.aggregateId)
        stateAggregate.assert().isNotNull()
        val aggregateCreated = MockAggregateCreated(generateGlobalId())
        val changed = MockAggregateChanged(generateGlobalId())
        val eventStream = listOf(aggregateCreated, changed).toDomainEventStream(
            upstream = command,
            aggregateVersion = stateAggregate.version,
        )
        stateAggregate.onSourcing(eventStream)
        val snapshot: SimpleSnapshot<MockStateAggregate> =
            SimpleSnapshot(delegate = stateAggregate, snapshotTime = Clock.systemUTC().millis())

        snapshotStore.save(snapshot)
            .test()
            .verifyComplete()

        val eventStream2 = listOf(aggregateCreated, changed).toDomainEventStream(
            upstream = command,
            aggregateVersion = stateAggregate.version,
        )
        stateAggregate.onSourcing(eventStream2)

        snapshotStore.save(snapshot)
            .test()
            .verifyComplete()

        snapshotStore.load<MockStateAggregate>(stateAggregate.aggregateId)
            .test()
            .consumeNextWith {
                it.aggregateId.assert().isEqualTo(stateAggregate.aggregateId)
                it.version.assert().isEqualTo(stateAggregate.version)
                it.state.data.assert().isEqualTo(stateAggregate.state.data)
            }
            .verifyComplete()
    }

    @Test
    fun saveShouldNotReplaceANewerStoredSnapshot() {
        val snapshotStore = createSnapshotStore().meteredForTck()
        val aggregateId = aggregateMetadata.aggregateId(generateGlobalId())
        val command = GivenInitializationCommand(aggregateId)
        val aggregateCreated = MockAggregateCreated(generateGlobalId())
        val changed = MockAggregateChanged(generateGlobalId())
        val initialEventStream = listOf(aggregateCreated).toDomainEventStream(
            upstream = command,
            aggregateVersion = Version.UNINITIALIZED_VERSION,
        )

        val olderStateAggregate = stateAggregateFactory.create(aggregateMetadata.state, aggregateId)
        olderStateAggregate.onSourcing(initialEventStream)
        val olderSnapshot: Snapshot<MockStateAggregate> =
            SimpleSnapshot(delegate = olderStateAggregate, snapshotTime = Clock.systemUTC().millis())

        val newerStateAggregate = stateAggregateFactory.create(aggregateMetadata.state, aggregateId)
        newerStateAggregate.onSourcing(initialEventStream)
        newerStateAggregate.onSourcing(
            listOf(changed).toDomainEventStream(
                upstream = command,
                aggregateVersion = newerStateAggregate.version,
            )
        )
        val newerSnapshot: Snapshot<MockStateAggregate> =
            SimpleSnapshot(delegate = newerStateAggregate, snapshotTime = Clock.systemUTC().millis())

        snapshotStore.save(newerSnapshot)
            .then(Mono.defer { snapshotStore.save(olderSnapshot) })
            .test()
            .verifyComplete()

        snapshotStore.load<MockStateAggregate>(aggregateId)
            .test()
            .consumeNextWith {
                it.version.assert().isEqualTo(newerSnapshot.version)
                it.state.data.assert().isEqualTo(newerSnapshot.state.data)
            }
            .verifyComplete()
    }

    @Test
    fun saveShouldRetainTheHighestVersionUnderConcurrentWrites() {
        val snapshotStore = createSnapshotStore().meteredForTck()
        val aggregateId = aggregateMetadata.aggregateId(generateGlobalId())
        val snapshots = (1..8).map { expectedVersion ->
            val stateAggregate = stateAggregateFactory.create(aggregateMetadata.state, aggregateId)
            repeat(expectedVersion) { eventIndex ->
                val event = if (eventIndex == 0) {
                    MockAggregateCreated("version-$expectedVersion")
                } else {
                    MockAggregateChanged("version-$expectedVersion")
                }
                stateAggregate.onSourcing(
                    event.toDomainEventStream(
                        upstream = GivenInitializationCommand(aggregateId),
                        aggregateVersion = stateAggregate.version,
                    )
                )
            }
            SimpleSnapshot(delegate = stateAggregate, snapshotTime = expectedVersion.toLong())
        }
        val expected = snapshots.maxBy { it.version }
        val concurrentSaves = snapshots
            .shuffled(Random(0))
            .map { candidate ->
                Mono.defer { snapshotStore.save(candidate) }
                    .subscribeOn(Schedulers.parallel())
            }

        Flux.merge(concurrentSaves)
            .then(Mono.defer { snapshotStore.load<MockStateAggregate>(aggregateId) })
            .test()
            .consumeNextWith {
                it.version.assert().isEqualTo(expected.version)
                it.state.data.assert().isEqualTo(expected.state.data)
            }
            .verifyComplete()
    }

    @Test
    fun saveShouldReplaceTheStoredSnapshotForTheSameVersion() {
        val snapshotStore = createSnapshotStore().meteredForTck()
        val aggregateId = aggregateMetadata.aggregateId(generateGlobalId())
        val firstStateAggregate = stateAggregateFactory.create(aggregateMetadata.state, aggregateId)
        firstStateAggregate.onSourcing(
            listOf(MockAggregateCreated("first")).toDomainEventStream(
                upstream = GivenInitializationCommand(aggregateId),
                aggregateVersion = firstStateAggregate.version,
            )
        )
        val replacementStateAggregate = stateAggregateFactory.create(aggregateMetadata.state, aggregateId)
        replacementStateAggregate.onSourcing(
            listOf(MockAggregateCreated("replacement")).toDomainEventStream(
                upstream = GivenInitializationCommand(aggregateId),
                aggregateVersion = replacementStateAggregate.version,
            )
        )
        val firstSnapshot: Snapshot<MockStateAggregate> =
            SimpleSnapshot(delegate = firstStateAggregate, snapshotTime = 1)
        val replacementSnapshot: Snapshot<MockStateAggregate> =
            SimpleSnapshot(delegate = replacementStateAggregate, snapshotTime = 2)

        snapshotStore.save(firstSnapshot)
            .then(Mono.defer { snapshotStore.save(replacementSnapshot) })
            .test()
            .verifyComplete()

        snapshotStore.load<MockStateAggregate>(aggregateId)
            .test()
            .consumeNextWith {
                it.version.assert().isEqualTo(firstSnapshot.version)
                it.state.data.assert().isEqualTo(replacementSnapshot.state.data)
                it.snapshotTime.assert().isEqualTo(replacementSnapshot.snapshotTime)
            }
            .verifyComplete()
    }
}
