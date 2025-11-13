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

package me.ahoo.wow.saga.stateless

import me.ahoo.wow.api.command.CommandMessage

/**
 * Represents a stream of command messages generated in response to a domain event in a stateless saga.
 * This interface provides access to the commands that should be sent as part of the saga's reaction to an event.
 */
interface CommandStream : Iterable<CommandMessage<*>> {
    /**
     * The ID of the domain event that triggered this command stream.
     */
    val domainEventId: String

    /**
     * The number of command messages in this stream.
     */
    val size: Int
}

/**
 * Default implementation of [CommandStream] that holds a list of command messages.
 *
 * @property domainEventId The ID of the domain event that triggered this command stream.
 * @property commands The list of command messages to be sent.
 */
data class DefaultCommandStream(
    override val domainEventId: String,
    private val commands: List<CommandMessage<*>>
) : CommandStream,
    Iterable<CommandMessage<*>> by commands {
    override val size: Int
        get() = commands.size
}
