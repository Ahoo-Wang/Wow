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
package me.ahoo.wow.modeling.command

import me.ahoo.wow.api.Version
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedTypedAggregate
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.modeling.state.StateAggregate
import reactor.core.publisher.Mono

/**
 * Represents a command aggregate that processes commands and manages state transitions.
 *
 * A command aggregate subscribes to command messages, validates business rules using the current state
 * from the state aggregate, and publishes domain events. It coordinates between command processing
 * and state management.
 *
 * Key responsibilities:
 * 1. Subscribe to command messages
 * 2. Validate business rules using state aggregate's current state
 * 3. Publish domain events
 *
 * @param C The type of the command aggregate root.
 * @param S The type of the state aggregate.
 * @property state The associated state aggregate containing the current state.
 * @property commandRoot The command aggregate root instance.
 * @property commandState The current state of command processing.
 */
interface CommandAggregate<C : Any, S : Any> :
    NamedTypedAggregate<C>,
    AggregateProcessor<C>,
    Version {
    override val aggregateId: AggregateId
        get() = state.aggregateId
    override val version: Int
        get() = state.version

    val state: StateAggregate<S>
    val commandRoot: C
    val commandState: CommandState
}

/**
 * Represents the state of command processing in a command aggregate.
 *
 * This enum defines the lifecycle states of command processing: from initial storage,
 * through event sourcing, to final storage, and eventual expiration.
 *
 * - STORED: Initial state, supports sourcing events
 * - SOURCED: After sourcing, supports storing events
 * - EXPIRED: Final state, no operations supported
 */
enum class CommandState {
    STORED {
        override fun onSourcing(
            stateAggregate: StateAggregate<*>,
            eventStream: DomainEventStream
        ): CommandState {
            stateAggregate.onSourcing(eventStream)
            return SOURCED
        }
    },
    SOURCED {
        override fun onStore(eventStore: EventStore, eventStream: DomainEventStream): Mono<CommandState> {
            return eventStore.append(eventStream)
                .checkpoint(
                    "Append DomainEventStream[${eventStream.id}] CommandId:[${eventStream.commandId}] [CommandState]"
                )
                .thenReturn(STORED)
        }
    },
    EXPIRED
    ;

    /**
     * Applies event sourcing to the state aggregate with the given event stream.
     *
     * @param stateAggregate The state aggregate to source events into.
     * @param eventStream The domain event stream to source.
     * @return The next command state.
     * @throws UnsupportedOperationException if the current state doesn't support sourcing.
     */
    open fun onSourcing(
        stateAggregate: StateAggregate<*>,
        eventStream: DomainEventStream
    ): CommandState =
        throw UnsupportedOperationException(
            "Failed to Sourcing eventStream[${eventStream.id}]: Current State[$this] does not support this operation.",
        )

    /**
     * Stores the event stream in the event store.
     *
     * @param eventStore The event store to append to.
     * @param eventStream The domain event stream to store.
     * @return A Mono that completes with the next command state.
     * @throws UnsupportedOperationException if the current state doesn't support storing.
     */
    open fun onStore(
        eventStore: EventStore,
        eventStream: DomainEventStream
    ): Mono<CommandState> =
        throw UnsupportedOperationException(
            "Failed to Store eventStream[${eventStream.id}]: Current State[$this] does not support this operation.",
        )
}
