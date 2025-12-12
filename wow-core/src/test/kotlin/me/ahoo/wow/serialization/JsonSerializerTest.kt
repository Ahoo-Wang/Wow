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

package me.ahoo.wow.serialization

import com.fasterxml.jackson.databind.node.ObjectNode
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.configuration.requiredNamedAggregate
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.toDomainEvent
import me.ahoo.wow.event.upgrader.MutableDomainEventRecord.Companion.toMutableDomainEventRecord
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.eventsourcing.state.StateEvent.Companion.toStateEvent
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.serialization.event.JsonDomainEvent
import me.ahoo.wow.serialization.event.StateJsonRecord
import me.ahoo.wow.serialization.event.toDomainEventRecord
import me.ahoo.wow.tck.event.MockDomainEventStreams
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockCreateAggregate
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Test
import java.time.Clock

internal class JsonSerializerTest {
    @Test
    fun aggregateId() {
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId()
        val output = aggregateId.toJsonString()
        val input = output.toObject<AggregateId>()
        input.assert().isEqualTo(aggregateId)
    }

    @Test
    fun command() {
        val command =
            MockCreateAggregate(generateGlobalId(), generateGlobalId())
                .toCommandMessage(tenantId = generateGlobalId(), ownerId = generateGlobalId())
        val output = command.toJsonString()
        val input = output.toObject<CommandMessage<*>>()
        input.assert().isEqualTo(command)
    }

    @Test
    fun eventStream() {
        val namedAggregate = requiredNamedAggregate<MockCreateAggregate>()
        val eventStream = MockDomainEventStreams.generateEventStream(
            aggregateId = namedAggregate.aggregateId(tenantId = generateGlobalId()),
            eventCount = 1,
            ownerId = generateGlobalId()
        )

        val output = eventStream.toJsonString()
        val input = output.toObject<DomainEventStream>()
        input.assert().isEqualTo(eventStream)
    }

    @Test
    fun domainEvent() {
        val namedAggregate = requiredNamedAggregate<MockAggregateCreated>()
        val domainEvent = MockDomainEventStreams.generateEventStream(
            aggregateId = namedAggregate.aggregateId(tenantId = generateGlobalId()),
            eventCount = 1,
            ownerId = generateGlobalId(),
            createdEventSupplier = { MockAggregateCreated(generateGlobalId()) },
        ).first()

        val output = domainEvent.toJsonString()
        val input = output.toObject<DomainEvent<*>>()
        input.assert().isEqualTo(domainEvent)
    }

    @Test
    fun asDomainEventWhenNotFoundClass() {
        val namedAggregate = requiredNamedAggregate<MockAggregateCreated>()
        val mockEvent = MockAggregateCreated(generateGlobalId())
            .toDomainEvent(
                aggregateId = namedAggregate.aggregateId(tenantId = generateGlobalId()),
                commandId = generateGlobalId(),
                ownerId = generateGlobalId()
            )
        val mockEventJson = mockEvent.toJsonString()
        val mutableDomainEventRecord =
            mockEventJson.toObjectNode().toDomainEventRecord().toMutableDomainEventRecord()
        mutableDomainEventRecord.bodyType = "NotFoundClass"
        val failedDomainEvent = mutableDomainEventRecord.actual.toJsonString().toObject<DomainEvent<Any>>()
        failedDomainEvent.assert().isInstanceOf(JsonDomainEvent::class.java)
        val failedJsonDomainEvent = failedDomainEvent as JsonDomainEvent
        failedJsonDomainEvent.id.assert().isEqualTo(mockEvent.id)
        failedJsonDomainEvent.aggregateId.assert().isEqualTo(mockEvent.aggregateId)
        failedJsonDomainEvent.ownerId.assert().isEqualTo(mockEvent.ownerId)
        failedJsonDomainEvent.bodyType.assert().isEqualTo(mutableDomainEventRecord.bodyType)
        failedJsonDomainEvent.revision.assert().isEqualTo(mutableDomainEventRecord.revision)
        val failedDomainEventJson = failedDomainEvent.toJsonString()
        val failedDomainEventRecord =
            failedDomainEventJson.toJsonNode<ObjectNode>().toDomainEventRecord().toMutableDomainEventRecord()
        failedDomainEventRecord.bodyType.assert().isEqualTo(failedJsonDomainEvent.bodyType)
        failedDomainEventRecord.bodyType = MockAggregateCreated::class.java.name
        val mockEvent2 = failedDomainEventRecord.toDomainEvent()
        mockEvent2.assert().isEqualTo(mockEvent)
    }

    @Test
    fun snapshot() {
        val aggregateMetadata = MOCK_AGGREGATE_METADATA
        val aggregateId = aggregateMetadata.aggregateId(
            generateGlobalId(),
            tenantId = generateGlobalId(),
        )
        val stateAggregate = ConstructorStateAggregateFactory.create(aggregateMetadata.state, aggregateId)
        val snapshot: Snapshot<MockStateAggregate> = SimpleSnapshot(stateAggregate, Clock.systemUTC().millis())
        val output = snapshot.toJsonString()
        val input = output.toObject<Snapshot<*>>()
        input.assert().isEqualTo(snapshot)
    }

    @Test
    fun stateEventStream() {
        val namedAggregate = requiredNamedAggregate<MockCreateAggregate>()
        val eventStream = MockDomainEventStreams.generateEventStream(
            aggregateId = namedAggregate.aggregateId(tenantId = generateGlobalId()),
            eventCount = 1,
            ownerId = generateGlobalId()
        )
        val stateRoot = MockStateAggregate(eventStream.aggregateId.id)
        val stateEvent = eventStream.toStateEvent(stateRoot)
        val output = stateEvent.toJsonString()
        val input = output.toObject<StateEvent<*>>()
        input.assert().isEqualTo(stateEvent)
    }

    @Test
    fun stateEventStreamIfNotFound() {
        val namedAggregate = "not.found".toNamedAggregate(generateGlobalId())
        val eventStream = MockDomainEventStreams.generateEventStream(
            aggregateId = namedAggregate.aggregateId(tenantId = generateGlobalId()),
            eventCount = 1,
        )
        val stateRoot = mapOf("id" to generateGlobalId())
        val stateEvent = eventStream.toStateEvent(stateRoot)
        val output = stateEvent.toJsonString()
        val input = output.toObject<StateEvent<*>>()
        input.state.assert().isInstanceOf(StateJsonRecord::class.java)
    }

    @Test
    fun anyToJsonNode() {
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId()
        val jsonNode = aggregateId.toJsonNode<ObjectNode>()
        val input = jsonNode.toObject<AggregateId>()
        input.assert().isEqualTo(aggregateId)
    }

    @Test
    fun stringToJsonNode() {
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId()
        val jsonNode = aggregateId.toJsonString().toJsonNode<ObjectNode>()
        val input = jsonNode.toObject<AggregateId>()
        input.assert().isEqualTo(aggregateId)
    }

    @Test
    fun deepCody() {
        val mutableData = MutableData(generateGlobalId())
        val deepCopied = mutableData.deepCody()
        mutableData.assert().isEqualTo(deepCopied)
        mutableData.id = generateGlobalId()
        deepCopied.id = generateGlobalId()
        mutableData.assert().isNotEqualTo(deepCopied)
    }
}

data class QueryById(val id: String)

data class MutableData(var id: String)
