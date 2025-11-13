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

import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregate

/**
 * Factory interface for creating command aggregate instances.
 *
 * Implementations of this interface are responsible for instantiating command aggregates
 * with the appropriate metadata and state aggregate.
 */
interface CommandAggregateFactory {
    /**
     * Creates a new command aggregate instance.
     *
     * @param C The type of the command aggregate root.
     * @param S The type of the state aggregate.
     * @param metadata The aggregate metadata containing configuration and function registries.
     * @param stateAggregate The state aggregate providing the current state.
     * @return A new command aggregate instance.
     */
    fun <C : Any, S : Any> create(
        metadata: AggregateMetadata<C, S>,
        stateAggregate: StateAggregate<S>
    ): CommandAggregate<C, S>
}
