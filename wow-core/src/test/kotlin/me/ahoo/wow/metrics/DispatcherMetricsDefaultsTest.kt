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

package me.ahoo.wow.metrics

import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.EventStreamExchange
import me.ahoo.wow.event.dispatcher.AggregateEventDispatcher
import me.ahoo.wow.event.dispatcher.AggregateStateEventDispatcher
import me.ahoo.wow.event.dispatcher.EventHandler
import me.ahoo.wow.event.dispatcher.StateEventDispatcher
import me.ahoo.wow.eventsourcing.snapshot.dispatcher.AggregateSnapshotDispatcher
import me.ahoo.wow.eventsourcing.snapshot.dispatcher.SnapshotHandler
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.eventsourcing.state.StateEventExchange
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.function.MessageFunctionRegistrar
import me.ahoo.wow.modeling.command.dispatcher.AggregateCommandDispatcher
import me.ahoo.wow.modeling.command.dispatcher.CommandHandler
import me.ahoo.wow.saga.stateless.StatelessSagaDispatcher
import me.ahoo.wow.saga.stateless.StatelessSagaFunctionRegistrar
import me.ahoo.wow.saga.stateless.StatelessSagaHandler
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class DispatcherMetricsDefaultsTest {
    private val namedAggregate = mockk<NamedAggregate>()
    private val functionRegistrar =
        mockk<MessageFunctionRegistrar<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>>>()
    private val eventHandler = mockk<EventHandler>()

    @Test
    fun `aggregate dispatchers should default to disabled metrics`() {
        val aggregateEventDispatcher = AggregateEventDispatcher(
            name = "aggregate-event",
            namedAggregates = setOf(namedAggregate),
            messageFlux = Flux.empty<EventStreamExchange>(),
            functionRegistrar = functionRegistrar,
            eventHandler = eventHandler,
        )
        val aggregateStateEventDispatcher = AggregateStateEventDispatcher(
            name = "aggregate-state-event",
            namedAggregates = setOf(namedAggregate),
            messageFlux = Flux.empty<StateEventExchange<*>>(),
            functionRegistrar = functionRegistrar,
            eventHandler = eventHandler,
        )
        val aggregateCommandDispatcher = AggregateCommandDispatcher(
            name = "aggregate-command",
            aggregateMetadata = listOf(MOCK_AGGREGATE_METADATA),
            messageFlux = Flux.empty(),
            commandHandler = mockk<CommandHandler>(),
        )
        val aggregateSnapshotDispatcher = AggregateSnapshotDispatcher(
            name = "aggregate-snapshot",
            namedAggregates = setOf(namedAggregate),
            messageFlux = Flux.empty(),
            snapshotHandler = mockk<SnapshotHandler>(),
        )

        aggregateEventDispatcher.name.assert().isEqualTo("aggregate-event")
        aggregateStateEventDispatcher.name.assert().isEqualTo("aggregate-state-event")
        aggregateCommandDispatcher.name.assert().isEqualTo("aggregate-command")
        aggregateSnapshotDispatcher.name.assert().isEqualTo("aggregate-snapshot")
    }

    @Test
    fun `composite dispatchers should default to disabled metrics`() {
        val stateEventDispatcher = StateEventDispatcher(
            name = "state-event",
            messageBus = mockk<StateEventBus>(),
            functionRegistrar = functionRegistrar,
            eventHandler = eventHandler,
        )
        val statelessSagaDispatcher = StatelessSagaDispatcher(
            name = "stateless-saga",
            domainEventBus = mockk<DomainEventBus>(),
            stateEventBus = mockk<StateEventBus>(),
            functionRegistrar = mockk<StatelessSagaFunctionRegistrar>(),
            eventHandler = mockk<StatelessSagaHandler>(),
        )

        stateEventDispatcher.name.assert().isEqualTo("state-event")
        statelessSagaDispatcher.name.assert().isEqualTo("stateless-saga")
    }
}
