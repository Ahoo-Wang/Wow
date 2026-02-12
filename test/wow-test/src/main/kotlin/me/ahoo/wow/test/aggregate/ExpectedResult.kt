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

package me.ahoo.wow.test.aggregate

import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.modeling.state.StateAggregate

/**
 * Encapsulates the complete result of an aggregate command execution for testing purposes.
 *
 * This data class holds all the information needed to validate the outcome of a command
 * execution, including the command exchange, resulting aggregate state, generated events,
 * and any errors that occurred.
 *
 * @param S the type of the aggregate state
 * @property exchange the server command exchange containing command execution details
 * @property stateAggregate the resulting state of the aggregate after command execution
 * @property domainEventStream the stream of domain events produced by the command (null if error occurred)
 * @property error any Throwable that occurred during command execution (null if successful)
 * @property hasError convenience property indicating whether an error occurred
 */
data class ExpectedResult<S : Any>(
    val exchange: ServerCommandExchange<*>,
    val stateAggregate: StateAggregate<S>,
    val domainEventStream: DomainEventStream? = null,
    val error: Throwable? = null
) {
    val hasError = error != null
}
