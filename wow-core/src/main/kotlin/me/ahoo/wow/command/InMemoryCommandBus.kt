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
package me.ahoo.wow.command

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.messaging.InMemoryMessageBus
import reactor.core.publisher.Sinks
import reactor.core.publisher.Sinks.Many

/**
 * In-memory implementation of CommandBus for local command processing.
 * This bus uses unicast sinks to ensure each command has exactly one consumer,
 * making it suitable for single-instance or testing scenarios.
 *
 * @param sinkSupplier Function that creates a unicast sink for each named aggregate.
 *                     Defaults to unicast with backpressure buffer.
 * @author ahoo wang
 */
class InMemoryCommandBus(
    /**
     * Supplier for creating sinks for command distribution.
     * Uses unicast mode to ensure each command reaches exactly one consumer.
     *
     * @see Sinks.UnicastSpec
     */
    override val sinkSupplier: (NamedAggregate) -> Many<CommandMessage<*>> = {
        Sinks.many().unicast().onBackpressureBuffer()
    }
) : InMemoryMessageBus<CommandMessage<*>, ServerCommandExchange<*>>(),
    LocalCommandBus {
    /**
     * Creates a server command exchange for the given command message.
     * This exchange handles the command processing lifecycle.
     *
     * @return A new ServerCommandExchange instance for this command.
     */
    override fun CommandMessage<*>.createExchange(): ServerCommandExchange<*> = SimpleServerCommandExchange(this)
}
