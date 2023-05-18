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

class SimpleCommandAggregateFactory(
    private val eventStore: EventStore,
) : CommandAggregateFactory {
    override fun <C : Any, S : Any> create(
        metadata: AggregateMetadata<C, S>,
        stateAggregate: StateAggregate<S>,
    ): CommandAggregate<C, S> {
        var commandRoot: Any = stateAggregate.stateRoot
        if (metadata.isAggregationPattern) {
            commandRoot = metadata.command.constructorAccessor.invoke(arrayOf(stateAggregate.stateRoot))
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
