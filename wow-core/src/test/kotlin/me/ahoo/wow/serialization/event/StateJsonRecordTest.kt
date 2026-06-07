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

package me.ahoo.wow.serialization.event

import me.ahoo.test.asserts.assert
import me.ahoo.wow.event.SimpleDomainEvent
import me.ahoo.wow.event.SimpleDomainEventStream
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.eventsourcing.state.StateEvent.Companion.toStateEvent
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject
import me.ahoo.wow.serialization.toObjectNode
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Test

internal class StateJsonRecordTest {

    @Test
    fun `state json record should decode its object node to requested state type`() {
        val state = StateValue("order-1", 2)
        val record = StateJsonRecord(state.toJsonString().toObjectNode())

        record.state<StateValue>().assert().isEqualTo(state)
    }

    @Test
    fun `state event deserializer should keep StateJsonRecord when aggregate metadata is missing`() {
        val stream = SimpleDomainEventStream(
            id = "stream-1",
            requestId = "request-1",
            header = DefaultHeader.empty(),
            body = listOf(
                SimpleDomainEvent(
                    id = "event-1",
                    body = StateChanged("order-1"),
                    aggregateId = MaterializedNamedAggregate("missing-context", "MissingAggregate")
                        .aggregateId("order-1", "tenant-1"),
                    version = 1,
                    commandId = "command-1",
                    createTime = 2000,
                ),
            ),
        )
        val stateEvent = stream.toStateEvent(StateValue("order-1", 2))

        val decoded = stateEvent.toJsonString().toObject<StateEvent<*>>()

        decoded.state.assert().isInstanceOf(StateJsonRecord::class.java)
        (decoded.state as StateJsonRecord).state<StateValue>().assert().isEqualTo(StateValue("order-1", 2))
    }

    @Test
    fun `state event deserializer should decode state root when aggregate metadata is found`() {
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("aggregate-1", tenantId = "tenant-1")
        val stream = SimpleDomainEventStream(
            id = "stream-1",
            requestId = "request-1",
            header = DefaultHeader.empty(),
            body = listOf(
                SimpleDomainEvent(
                    id = "event-1",
                    body = MockAggregateCreated("created"),
                    aggregateId = aggregateId,
                    version = 1,
                    commandId = "command-1",
                    createTime = 2000,
                ),
            ),
        )
        val stateEvent = stream.toStateEvent(MockStateAggregate("aggregate-1"))

        val decoded = stateEvent.toJsonString().toObject<StateEvent<*>>()

        decoded.state.assert().isInstanceOf(MockStateAggregate::class.java)
        decoded.assert().isEqualTo(stateEvent)
    }

    private data class StateValue(val id: String, val quantity: Int)
    private data class StateChanged(val id: String)
}
