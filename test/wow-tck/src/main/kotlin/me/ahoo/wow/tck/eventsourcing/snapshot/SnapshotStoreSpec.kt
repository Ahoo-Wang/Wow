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
import me.ahoo.wow.metrics.Metrics.metrizable
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateChanged
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test
import java.time.Clock

abstract class SnapshotStoreSpec {

    protected val aggregateMetadata = MOCK_AGGREGATE_METADATA

    private val stateAggregateFactory: StateAggregateFactory = ConstructorStateAggregateFactory

    protected open fun createSnapshotStore(): SnapshotStore = createSnapshotRepository()

    @Deprecated("Use createSnapshotStore().", ReplaceWith("createSnapshotStore()"))
    protected open fun createSnapshotRepository(): SnapshotStore {
        throw UnsupportedOperationException("Override createSnapshotStore().")
    }

    @Test
    fun name() {
        val snapshotStore = createSnapshotStore().metrizable()
        snapshotStore.name.assert().isNotBlank()
    }

    @Test
    fun load() {
        val snapshotStore = createSnapshotStore().metrizable()
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
        val snapshotStore = createSnapshotStore().metrizable()
        val aggregateId = aggregateMetadata.aggregateId(generateGlobalId())
        snapshotStore.getVersion(aggregateId)
            .test()
            .expectNext(Version.UNINITIALIZED_VERSION)
            .verifyComplete()
    }

    @Test
    fun loadWhenNotFound() {
        val snapshotStore = createSnapshotStore().metrizable()

        val aggregateId = aggregateMetadata.aggregateId(generateGlobalId())
        snapshotStore.load<MockStateAggregate>(aggregateId)
            .test()
            .expectNextCount(0)
            .verifyComplete()
    }

    @Test
    fun save() {
        val snapshotStore = createSnapshotStore().metrizable()
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
        val snapshotStore = createSnapshotStore().metrizable()
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
    open fun scanAggregateId() {
        val snapshotStore = createSnapshotStore().metrizable()
        val cursorId = generateGlobalId()
        val aggregateId = aggregateMetadata.aggregateId(generateGlobalId())
        val stateAggregate = stateAggregateFactory.create(aggregateMetadata.state, aggregateId)
        val snapshot: Snapshot<MockStateAggregate> =
            SimpleSnapshot(stateAggregate, Clock.systemUTC().millis())
        snapshotStore.save(snapshot)
            .test()
            .verifyComplete()

        snapshotStore.scanAggregateId(snapshot.aggregateId, afterId = cursorId, limit = 1)
            .test()
            .expectNextCount(1)
            .verifyComplete()
        snapshotStore.scanAggregateId(snapshot.aggregateId, afterId = snapshot.aggregateId.id, limit = 1)
            .test()
            .expectNextCount(0)
            .verifyComplete()
    }

    @Test
    open fun scanAggregateIdShouldFilterNamedAggregate() {
        val snapshotStore = createSnapshotStore().metrizable()
        val cursorId = generateGlobalId()
        val targetAggregateId = aggregateMetadata.aggregateId(generateGlobalId())
        val otherAggregateId = "other_aggregate"
            .toNamedAggregate(aggregateMetadata.contextName)
            .aggregateId(generateGlobalId())
        val targetStateAggregate = stateAggregateFactory.create(aggregateMetadata.state, targetAggregateId)
        val otherStateAggregate = stateAggregateFactory.create(aggregateMetadata.state, otherAggregateId)

        snapshotStore.save(SimpleSnapshot(targetStateAggregate, Clock.systemUTC().millis()))
            .test()
            .verifyComplete()
        snapshotStore.save(SimpleSnapshot(otherStateAggregate, Clock.systemUTC().millis()))
            .test()
            .verifyComplete()

        snapshotStore.scanAggregateId(aggregateMetadata, afterId = cursorId, limit = 10)
            .collectList()
            .test()
            .consumeNextWith {
                it.assert().containsExactly(targetAggregateId)
            }
            .verifyComplete()
    }

    @Test
    open fun scanAggregateIdShouldLimitResult() {
        val snapshotStore = createSnapshotStore().metrizable()
        val snapshots = (1..20).map {
            val aggregateId = aggregateMetadata.aggregateId(generateGlobalId())
            val stateAggregate = stateAggregateFactory.create(aggregateMetadata.state, aggregateId)
            SimpleSnapshot(stateAggregate, Clock.systemUTC().millis())
        }

        snapshots.forEach { snapshot ->
            snapshotStore.save(snapshot)
                .test()
                .verifyComplete()
        }

        snapshotStore.scanAggregateId(aggregateMetadata, afterId = "", limit = 3)
            .collectList()
            .test()
            .consumeNextWith {
                it.assert().hasSize(3)
            }
            .verifyComplete()
    }

    @Test
    open fun scanAggregateIdShouldReturnLexicographicalOrder() {
        val snapshotStore = createSnapshotStore().metrizable()
        val aggregateIds = listOf("003", "001", "004", "002").map {
            aggregateMetadata.aggregateId(it)
        }
        aggregateIds.forEach { aggregateId ->
            val stateAggregate = stateAggregateFactory.create(aggregateMetadata.state, aggregateId)
            snapshotStore.save(SimpleSnapshot(stateAggregate, Clock.systemUTC().millis()))
                .test()
                .verifyComplete()
        }

        snapshotStore.scanAggregateId(aggregateMetadata, afterId = "001", limit = 2)
            .collectList()
            .test()
            .consumeNextWith {
                it.assert().containsExactly(
                    aggregateMetadata.aggregateId("002"),
                    aggregateMetadata.aggregateId("003"),
                )
            }
            .verifyComplete()
    }
}
