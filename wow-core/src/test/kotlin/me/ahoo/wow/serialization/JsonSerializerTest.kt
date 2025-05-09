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
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import java.time.Clock

internal class JsonSerializerTest {
    @Test
    fun aggregateId() {
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId()
        val output = aggregateId.toJsonString()
        assertThat(output, notNullValue())
        val input = output.toObject<AggregateId>()
        assertThat(input, equalTo(aggregateId))
    }

    @Test
    fun command() {
        val command =
            MockCreateAggregate(generateGlobalId(), generateGlobalId())
                .toCommandMessage(tenantId = generateGlobalId(), ownerId = generateGlobalId())
        val output = command.toJsonString()
        assertThat(output, notNullValue())
        val input = output.toObject<CommandMessage<*>>()
        assertThat(input, equalTo(command))
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
        assertThat(output, notNullValue())
        val input = output.toObject<DomainEventStream>()
        assertThat(input, equalTo(eventStream))
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
        assertThat(output, notNullValue())
        val input = output.toObject<DomainEvent<*>>()
        assertThat(input, equalTo(domainEvent))
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
        assertThat(failedDomainEvent, instanceOf(JsonDomainEvent::class.java))
        val failedJsonDomainEvent = failedDomainEvent as JsonDomainEvent
        assertThat(failedJsonDomainEvent.id, equalTo(mockEvent.id))
        assertThat(failedJsonDomainEvent.aggregateId, equalTo(mockEvent.aggregateId))
        assertThat(failedJsonDomainEvent.ownerId, equalTo(mockEvent.ownerId))
        assertThat(failedJsonDomainEvent.bodyType, equalTo(mutableDomainEventRecord.bodyType))
        assertThat(failedJsonDomainEvent.revision, equalTo(mutableDomainEventRecord.revision))
        val failedDomainEventJson = failedDomainEvent.toJsonString()
        val failedDomainEventRecord =
            failedDomainEventJson.toJsonNode<ObjectNode>().toDomainEventRecord().toMutableDomainEventRecord()
        assertThat(failedDomainEventRecord.bodyType, equalTo(failedJsonDomainEvent.bodyType))
        failedDomainEventRecord.bodyType = MockAggregateCreated::class.java.name
        val mockEvent2 = failedDomainEventRecord.toDomainEvent()
        assertThat(mockEvent2, equalTo(mockEvent))
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
        assertThat(output, notNullValue())
        val input = output.toObject<Snapshot<*>>()
        assertThat(input, equalTo(snapshot))
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
        assertThat(output, notNullValue())
        val input = output.toObject<StateEvent<*>>()
        assertThat(input, equalTo(stateEvent))
    }

    @Test
    fun stateEventStreamIfNotFound() {
        val namedAggregate = "not.found".toNamedAggregate(generateGlobalId())
        val eventStream = MockDomainEventStreams.generateEventStream(
            aggregateId = namedAggregate.aggregateId(tenantId = generateGlobalId()),
            eventCount = 1,
        )
        val stateRoot = mapOf<String, String>("id" to generateGlobalId())
        val stateEvent = eventStream.toStateEvent(stateRoot)
        val output = stateEvent.toJsonString()
        assertThat(output, notNullValue())
        val input = output.toObject<StateEvent<*>>()
        assertThat(input.state, instanceOf(StateJsonRecord::class.java))
    }

    @Test
    fun deepCody() {
        val mutableData = MutableData(generateGlobalId())
        val deepCopied = mutableData.deepCody()
        assertThat(mutableData, equalTo(deepCopied))
        mutableData.id = generateGlobalId()
        deepCopied.id = generateGlobalId()
        assertThat(mutableData, not(deepCopied))
    }
}

data class QueryById(val id: String)

data class MutableData(var id: String)
