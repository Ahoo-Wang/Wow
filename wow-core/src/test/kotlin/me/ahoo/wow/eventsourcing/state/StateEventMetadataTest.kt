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

package me.ahoo.wow.eventsourcing.state

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.event.OwnerTransferred
import me.ahoo.wow.api.event.SpaceTransferred
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.eventsourcing.EventSourcingStateAggregateRepository
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.snapshot.InMemorySnapshotStore
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.state.StateEvent.Companion.toStateEvent
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject
import me.ahoo.wow.tck.event.MockDomainEventStreams.generateEventStream
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockChangeAggregate
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Test
import java.time.Duration

class StateEventMetadataTest {
    @Test
    fun `state events preserve transferred ownership through copying serialization and snapshots`() {
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("transferred-state-event")
        val store = InMemoryEventStore()
        val snapshots = InMemorySnapshotStore()
        val state = ConstructorStateAggregateFactory.create(MOCK_AGGREGATE_METADATA.state, aggregateId)
        val first = generateEventStream(
            aggregateId,
            aggregateVersion = 0,
            eventCount = 1,
            ownerId = "old-owner",
            spaceId = "old-space",
            createdEventSupplier = { MockAggregateCreated("v1") }
        )
        state.onSourcing(first)
        store.append(first).block(Duration.ofSeconds(5))
        val command = MockChangeAggregate(aggregateId.id, "transfer").toCommandMessage(
            ownerId = "old-owner",
            spaceId = "old-space",
        )
        val second = listOf(TransferredOwner("new-owner"), TransferredSpace("new-space"))
            .toDomainEventStream(command, aggregateVersion = 1)
        state.onSourcing(second)
        state.ownerId.assert().isEqualTo("new-owner")
        state.spaceId.assert().isEqualTo("new-space")
        store.append(second).block(Duration.ofSeconds(5))
        val stateEvent = second.toStateEvent(state)
        for (event in listOf(
            stateEvent,
            stateEvent.copy(),
            stateEvent.toJsonString().toObject<StateEvent<MockStateAggregate>>()
        )) {
            assertAll(
                { event.ownerId.assert().isEqualTo("new-owner") },
                { event.spaceId.assert().isEqualTo("new-space") },
            )
            snapshots.save(SimpleSnapshot(event)).block(Duration.ofSeconds(5))
        }
        val restored = EventSourcingStateAggregateRepository(ConstructorStateAggregateFactory, snapshots, store)
            .load(aggregateId, MOCK_AGGREGATE_METADATA.state).block(Duration.ofSeconds(5))!!
        assertAll(
            { restored.ownerId.assert().isEqualTo("new-owner") },
            { restored.spaceId.assert().isEqualTo("new-space") },
        )
    }
}

private data class TransferredOwner(override val toOwnerId: String) : OwnerTransferred
private data class TransferredSpace(override val toSpaceId: String) : SpaceTransferred
