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
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.serialization.event.BodyTypeNotFoundDomainEvent
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
            MockCreateAggregate(GlobalIdGenerator.generateAsString(), GlobalIdGenerator.generateAsString())
                .toCommandMessage(tenantId = GlobalIdGenerator.generateAsString())
        val output = command.toJsonString()
        assertThat(output, notNullValue())
        val input = output.toObject<CommandMessage<*>>()
        assertThat(input, equalTo(command))
    }

    @Test
    fun eventStream() {
        val namedAggregate = requiredNamedAggregate<MockCreateAggregate>()
        val eventStream = MockDomainEventStreams.generateEventStream(
            aggregateId = namedAggregate.aggregateId(tenantId = GlobalIdGenerator.generateAsString()),
            eventCount = 1,
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
            aggregateId = namedAggregate.aggregateId(tenantId = GlobalIdGenerator.generateAsString()),
            eventCount = 1,
            createdEventSupplier = { MockAggregateCreated(GlobalIdGenerator.generateAsString()) },
        ).first()

        val output = domainEvent.toJsonString()
        assertThat(output, notNullValue())
        val input = output.toObject<DomainEvent<*>>()
        assertThat(input, equalTo(domainEvent))
    }

    @Test
    fun asDomainEventWhenNotFoundClass() {
        val namedAggregate = requiredNamedAggregate<MockAggregateCreated>()
        val mockEvent = MockAggregateCreated(GlobalIdGenerator.generateAsString())
            .toDomainEvent(
                aggregateId = namedAggregate.aggregateId(tenantId = GlobalIdGenerator.generateAsString()),
                commandId = GlobalIdGenerator.generateAsString(),
            )
        val mockEventJson = mockEvent.toJsonString()
        val mutableDomainEventRecord =
            mockEventJson.toJsonNode<ObjectNode>().toDomainEventRecord().toMutableDomainEventRecord()
        mutableDomainEventRecord.bodyType = "NotFoundClass"
        val failedDomainEvent = mutableDomainEventRecord.actual.toJsonString().toObject<DomainEvent<Any>>()
        assertThat(mockEvent.id, equalTo(failedDomainEvent.id))
        assertThat(failedDomainEvent.aggregateId, equalTo(failedDomainEvent.aggregateId))
        assertThat(failedDomainEvent.revision, equalTo(failedDomainEvent.revision))
        assertThat(failedDomainEvent, instanceOf(BodyTypeNotFoundDomainEvent::class.java))

        val failedDomainEventJson = failedDomainEvent.toJsonString()
        val failedDomainEventRecord =
            failedDomainEventJson.toJsonNode<ObjectNode>().toDomainEventRecord().toMutableDomainEventRecord()
        failedDomainEventRecord.bodyType = MockAggregateCreated::class.java.name
        val mockEvent2 = failedDomainEventRecord.toDomainEvent()
        assertThat(mockEvent2, equalTo(mockEvent))
    }

    @Test
    fun snapshot() {
        val aggregateMetadata = MOCK_AGGREGATE_METADATA
        val aggregateId =
            aggregateMetadata.aggregateId(
                GlobalIdGenerator.generateAsString(),
                tenantId = GlobalIdGenerator.generateAsString(),
            )
        val stateAggregate = ConstructorStateAggregateFactory.create(aggregateMetadata.state, aggregateId).block()!!
        val snapshot: Snapshot<MockStateAggregate> =
            SimpleSnapshot(stateAggregate, Clock.systemUTC().millis())
        val output = snapshot.toJsonString()
        assertThat(output, notNullValue())
        val input = output.toObject<Snapshot<*>>()
        assertThat(input, equalTo(snapshot))
    }

    @Test
    fun stateEventStream() {
        val namedAggregate = requiredNamedAggregate<MockCreateAggregate>()
        val eventStream = MockDomainEventStreams.generateEventStream(
            aggregateId = namedAggregate.aggregateId(tenantId = GlobalIdGenerator.generateAsString()),
            eventCount = 1,
        )
        val stateRoot = MockStateAggregate(eventStream.aggregateId.id)
        val stateEvent = eventStream.toStateEvent(stateRoot)
        val output = stateEvent.toJsonString()
        assertThat(output, notNullValue())
        val input = output.toObject<StateEvent<*>>()
        assertThat(input, equalTo(stateEvent))
    }
}

data class QueryById(val id: String)
