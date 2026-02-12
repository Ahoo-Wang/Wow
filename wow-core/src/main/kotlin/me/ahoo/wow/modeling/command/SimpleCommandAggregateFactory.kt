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

import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregate

/**
 * Factory for creating SimpleCommandAggregate instances.
 *
 * This factory handles the creation of command aggregates, taking into account whether
 * the aggregate follows an aggregation pattern (separate command and state aggregates).
 *
 * @param eventStore The event store to be used by created command aggregates.
 */
class SimpleCommandAggregateFactory(
    private val eventStore: EventStore
) : CommandAggregateFactory {
    /**
     * Creates a new SimpleCommandAggregate instance.
     *
     * For aggregation patterns, the command root is constructed using the command metadata's constructor.
     * For non-aggregation patterns, the state aggregate's state is used directly as the command root.
     *
     * @param C The type of the command aggregate root.
     * @param S The type of the state aggregate.
     * @param metadata The aggregate metadata containing configuration.
     * @param stateAggregate The state aggregate to associate with the command aggregate.
     * @return A new SimpleCommandAggregate instance.
     */
    override fun <C : Any, S : Any> create(
        metadata: AggregateMetadata<C, S>,
        stateAggregate: StateAggregate<S>
    ): CommandAggregate<C, S> {
        var commandRoot: Any = stateAggregate.state
        if (metadata.isAggregationPattern) {
            commandRoot = metadata.command.constructorAccessor.invoke(arrayOf<Any>(stateAggregate.state))
        }
        @Suppress("UNCHECKED_CAST")
        return SimpleCommandAggregate(
            state = stateAggregate,
            commandRoot = commandRoot as C,
            eventStore = eventStore,
            metadata = metadata.command,
        )
    }
}
