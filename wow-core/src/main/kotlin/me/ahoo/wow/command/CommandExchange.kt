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
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.command.wait.WaitStrategy
import me.ahoo.wow.event.DomainEventException.Companion.asException
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.modeling.command.AggregateProcessor
import java.util.concurrent.ConcurrentHashMap

/**
 * Command Exchange .
 *
 * @author ahoo wang
 */
interface CommandExchange<SOURCE : CommandExchange<SOURCE, C>, C : Any> :
    MessageExchange<SOURCE, CommandMessage<C>>

interface ClientCommandExchange<C : Any> : CommandExchange<ClientCommandExchange<C>, C> {
    val waitStrategy: WaitStrategy
}

data class SimpleClientCommandExchange<C : Any>(
    override val message: CommandMessage<C>,
    override val waitStrategy: WaitStrategy,
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap(),
) : ClientCommandExchange<C>

interface ServerCommandExchange<C : Any> : CommandExchange<ServerCommandExchange<C>, C> {
    var aggregateProcessor: AggregateProcessor<Any>?
    var eventStream: DomainEventStream?

    override fun getError(): Throwable? {
        super.getError()?.let { return it }
        val errorEvent = eventStream?.firstOrNull {
            it.body is ErrorInfo
        } ?: return null
        @Suppress("UNCHECKED_CAST")
        return (errorEvent as DomainEvent<ErrorInfo>).asException()
    }

    override fun <T : Any> extractDeclared(type: Class<T>): T? {
        val extracted = super.extractDeclared(type)
        if (extracted != null) {
            return extracted
        }
        if (type.isInstance(eventStream)) {
            return type.cast(eventStream)
        }
        val error = getError()
        if (type.isInstance(error)) {
            return type.cast(error)
        }

        return null
    }
}

data class SimpleServerCommandExchange<C : Any>(
    override val message: CommandMessage<C>,
    @Volatile
    override var aggregateProcessor: AggregateProcessor<Any>? = null,
    @Volatile
    override var eventStream: DomainEventStream? = null,
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap(),
) : ServerCommandExchange<C>
