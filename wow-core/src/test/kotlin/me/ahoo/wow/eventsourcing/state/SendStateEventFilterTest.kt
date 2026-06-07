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

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.SimpleServerCommandExchange
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.command.CommandAggregate
import me.ahoo.wow.modeling.command.setCommandAggregate
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory.toStateAggregate
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class SendStateEventFilterTest {

    private val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("send-state")

    @Test
    fun `filter continues without sending when no event stream exists`() {
        val stateEventBus = RecordingStateEventBus()
        val chain = RecordingCommandFilterChain()
        val exchange = commandExchange()

        StepVerifier.create(SendStateEventFilter(stateEventBus).filter(exchange, chain))
            .verifyComplete()

        stateEventBus.sent.assert().isEmpty()
        chain.invocations.assert().isEqualTo(1)
    }

    @Test
    fun `filter continues without sending when command aggregate is missing`() {
        val stateEventBus = RecordingStateEventBus()
        val chain = RecordingCommandFilterChain()
        val exchange = commandExchange().setEventStream(eventStream())

        StepVerifier.create(SendStateEventFilter(stateEventBus).filter(exchange, chain))
            .verifyComplete()

        stateEventBus.sent.assert().isEmpty()
        chain.invocations.assert().isEqualTo(1)
    }

    @Test
    fun `filter continues without sending when state is uninitialized`() {
        val stateEventBus = RecordingStateEventBus()
        val chain = RecordingCommandFilterChain()
        val exchange = commandExchange()
            .setEventStream(eventStream())
            .setCommandAggregate(commandAggregate(version = 0))

        StepVerifier.create(SendStateEventFilter(stateEventBus).filter(exchange, chain))
            .verifyComplete()

        stateEventBus.sent.assert().isEmpty()
        chain.invocations.assert().isEqualTo(1)
    }

    @Test
    fun `filter sends copied state event before continuing when state is initialized`() {
        val calls = mutableListOf<String>()
        val stateEventBus = RecordingStateEventBus(calls)
        val chain = RecordingCommandFilterChain(calls)
        val eventStream = eventStream()
        eventStream.header.with("copy-source", "stream-header")
        eventStream.first().header.with("copy-source", "event-header")
        val exchange = commandExchange()
            .setEventStream(eventStream)
            .setCommandAggregate(commandAggregate(version = 1))

        StepVerifier.create(SendStateEventFilter(stateEventBus).filter(exchange, chain))
            .verifyComplete()

        val sent = stateEventBus.sent.single()
        eventStream.header.with("after-send", "mutated-stream-header")
        eventStream.first().header.with("after-send", "mutated-event-header")

        calls.assert().isEqualTo(listOf("send", "next"))
        sent.aggregateId.assert().isEqualTo(eventStream.aggregateId)
        sent.version.assert().isEqualTo(eventStream.version)
        sent.state.id.assert().isEqualTo("send-state")
        sent.header.assert().isNotSameAs(eventStream.header)
        sent.header["copy-source"].assert().isEqualTo("stream-header")
        sent.header["after-send"].assert().isNull()
        sent.first().header.assert().isNotSameAs(eventStream.first().header)
        sent.first().header["copy-source"].assert().isEqualTo("event-header")
        sent.first().header["after-send"].assert().isNull()
        chain.invocations.assert().isEqualTo(1)
    }

    private fun commandExchange(): SimpleServerCommandExchange<me.ahoo.wow.test.aggregate.GivenInitialization> =
        SimpleServerCommandExchange(GivenInitializationCommand(aggregateId))

    private fun eventStream() =
        MockAggregateCreated("created").toDomainEventStream(
            upstream = GivenInitializationCommand(aggregateId),
            aggregateVersion = 0,
        )

    private fun commandAggregate(version: Int): CommandAggregate<Any, MockStateAggregate> {
        val stateAggregate = MOCK_AGGREGATE_METADATA.state.toStateAggregate(
            aggregateId = aggregateId,
            state = MockStateAggregate("send-state"),
            version = version,
        )
        return mockk {
            every { state } returns stateAggregate
        }
    }

    private class RecordingStateEventBus(
        private val calls: MutableList<String> = mutableListOf()
    ) : StateEventBus {
        val sent = mutableListOf<StateEvent<MockStateAggregate>>()

        @Suppress("UNCHECKED_CAST")
        override fun send(message: StateEvent<*>): Mono<Void> =
            Mono.fromRunnable {
                calls += "send"
                sent += message as StateEvent<MockStateAggregate>
            }

        override fun receive(namedAggregates: Set<NamedAggregate>): Flux<StateEventExchange<*>> = Flux.empty()
    }

    private class RecordingCommandFilterChain(
        private val calls: MutableList<String> = mutableListOf()
    ) : FilterChain<ServerCommandExchange<*>> {
        var invocations = 0

        override fun filter(context: ServerCommandExchange<*>): Mono<Void> =
            Mono.fromRunnable {
                calls += "next"
                invocations++
            }
    }
}
