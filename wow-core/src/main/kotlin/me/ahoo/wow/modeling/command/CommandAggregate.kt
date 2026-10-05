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
import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedTypedAggregate
import me.ahoo.wow.modeling.state.StateAggregate

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
 * The [state] contains the current aggregate state and [commandRoot] is the command aggregate root.
 */
@WowSpi
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
}
