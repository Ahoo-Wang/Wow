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
import me.ahoo.wow.api.messaging.FunctionKind
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.command.asCommandMessage
import me.ahoo.wow.configuration.requiredNamedAggregate
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.asDomainEvent
import me.ahoo.wow.event.error.ErrorDetails
import me.ahoo.wow.event.error.EventFunctionError
import me.ahoo.wow.event.error.EventId
import me.ahoo.wow.event.upgrader.MutableDomainEventRecord.Companion.asMutableDomainEventRecord
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.eventsourcing.state.StateEvent.Companion.asStateEvent
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.messaging.processor.ProcessorInfoData
import me.ahoo.wow.modeling.asAggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.serialization.event.BodyTypeNotFoundDomainEvent
import me.ahoo.wow.serialization.event.asDomainEventRecord
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
        val aggregateId = MOCK_AGGREGATE_METADATA.asAggregateId()
        val output = aggregateId.asJsonString()
        assertThat(output, notNullValue())
        val input = output.asObject<AggregateId>()
        assertThat(input, equalTo(aggregateId))
    }

    @Test
    fun eventFunctionError() {
        val aggregateId = MOCK_AGGREGATE_METADATA.asAggregateId()
        val eventFunctionError =
            EventFunctionError(
                id = GlobalIdGenerator.generateAsString(),
                eventId = EventId(GlobalIdGenerator.generateAsString(), aggregateId),
                functionKind = FunctionKind.EVENT,
                processor = ProcessorInfoData("", ""),
                error = ErrorDetails("", "", ""),
                createTime = System.currentTimeMillis()
            )
        val output = eventFunctionError.asJsonString()
        assertThat(output, notNullValue())
        val input = output.asObject<EventFunctionError>()
        assertThat(input, equalTo(eventFunctionError))
    }

    @Test
    fun command() {
        val command =
            MockCreateAggregate(GlobalIdGenerator.generateAsString(), GlobalIdGenerator.generateAsString())
                .asCommandMessage(tenantId = GlobalIdGenerator.generateAsString())
        val output = command.asJsonString()
        assertThat(output, notNullValue())
        val input = output.asObject<CommandMessage<*>>()
        assertThat(input, equalTo(command))
    }

    @Test
    fun eventStream() {
        val namedAggregate = requiredNamedAggregate<MockCreateAggregate>()
        val eventStream = MockDomainEventStreams.generateEventStream(
            aggregateId = namedAggregate.asAggregateId(tenantId = GlobalIdGenerator.generateAsString()),
            eventCount = 1,
        )

        val output = eventStream.asJsonString()
        assertThat(output, notNullValue())
        val input = output.asObject<DomainEventStream>()
        assertThat(input, equalTo(eventStream))
    }

    @Test
    fun domainEvent() {
        val namedAggregate = requiredNamedAggregate<MockAggregateCreated>()
        val domainEvent = MockDomainEventStreams.generateEventStream(
            aggregateId = namedAggregate.asAggregateId(tenantId = GlobalIdGenerator.generateAsString()),
            eventCount = 1,
            createdEventSupplier = { MockAggregateCreated(GlobalIdGenerator.generateAsString()) },
        ).first()

        val output = domainEvent.asJsonString()
        assertThat(output, notNullValue())
        val input = output.asObject<DomainEvent<*>>()
        assertThat(input, equalTo(domainEvent))
    }

    @Test
    fun asDomainEventWhenNotFoundClass() {
        val namedAggregate = requiredNamedAggregate<MockAggregateCreated>()
        val mockEvent = MockAggregateCreated(GlobalIdGenerator.generateAsString())
            .asDomainEvent(
                aggregateId = namedAggregate.asAggregateId(tenantId = GlobalIdGenerator.generateAsString()),
                commandId = GlobalIdGenerator.generateAsString(),
            )
        val mockEventJson = mockEvent.asJsonString()
        val mutableDomainEventRecord =
            mockEventJson.asJsonNode<ObjectNode>().asDomainEventRecord().asMutableDomainEventRecord()
        mutableDomainEventRecord.bodyType = "NotFoundClass"
        val failedDomainEvent = mutableDomainEventRecord.actual.asJsonString().asObject<DomainEvent<Any>>()
        assertThat(mockEvent.id, equalTo(failedDomainEvent.id))
        assertThat(failedDomainEvent.aggregateId, equalTo(failedDomainEvent.aggregateId))
        assertThat(failedDomainEvent.revision, equalTo(failedDomainEvent.revision))
        assertThat(failedDomainEvent, instanceOf(BodyTypeNotFoundDomainEvent::class.java))

        val failedDomainEventJson = failedDomainEvent.asJsonString()
        val failedDomainEventRecord =
            failedDomainEventJson.asJsonNode<ObjectNode>().asDomainEventRecord().asMutableDomainEventRecord()
        failedDomainEventRecord.bodyType = MockAggregateCreated::class.java.name
        val mockEvent2 = failedDomainEventRecord.asDomainEvent()
        assertThat(mockEvent2, equalTo(mockEvent))
    }

    @Test
    fun snapshot() {
        val aggregateMetadata = MOCK_AGGREGATE_METADATA
        val aggregateId =
            aggregateMetadata.asAggregateId(
                GlobalIdGenerator.generateAsString(),
                tenantId = GlobalIdGenerator.generateAsString(),
            )
        val stateAggregate = ConstructorStateAggregateFactory.create(aggregateMetadata.state, aggregateId).block()!!
        val snapshot: Snapshot<MockStateAggregate> =
            SimpleSnapshot(stateAggregate, Clock.systemUTC().millis())
        val output = snapshot.asJsonString()
        assertThat(output, notNullValue())
        val input = output.asObject<Snapshot<*>>()
        assertThat(input, equalTo(snapshot))
    }

    @Test
    fun stateEventStream() {
        val namedAggregate = requiredNamedAggregate<MockCreateAggregate>()
        val eventStream = MockDomainEventStreams.generateEventStream(
            aggregateId = namedAggregate.asAggregateId(tenantId = GlobalIdGenerator.generateAsString()),
            eventCount = 1,
        )
        val stateRoot = MockStateAggregate(eventStream.aggregateId.id)
        val stateEvent = eventStream.asStateEvent(stateRoot)
        val output = stateEvent.asJsonString()
        assertThat(output, notNullValue())
        val input = output.asObject<StateEvent<*>>()
        assertThat(input, equalTo(stateEvent))
    }
}

data class QueryById(val id: String)
