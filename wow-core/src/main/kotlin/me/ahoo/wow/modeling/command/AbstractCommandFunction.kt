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

import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.flatEvent
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.modeling.command.after.AfterCommandFunction
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Abstract base class for command functions that handle command processing with after-command functions.
 *
 * This class provides the framework for executing commands and their associated after-command functions,
 * combining their results into a domain event stream.
 *
 * @param C The type of the command aggregate.
 * @property commandAggregate The command aggregate instance this function belongs to.
 * @property afterCommandFunctions List of after-command functions to execute after the main command.
 *
 * @constructor Creates a new AbstractCommandFunction with the specified command aggregate and after functions.
 */
@JvmDefaultWithoutCompatibility
abstract class AbstractCommandFunction<C : Any>(
    val commandAggregate: CommandAggregate<C, *>,
    private val afterCommandFunctions: List<AfterCommandFunction<C>>
) : MessageFunction<C, ServerCommandExchange<*>, Mono<DomainEventStream>> {
    /**
     * Invokes the main command logic.
     *
     * Subclasses must implement this method to provide the core command processing.
     *
     * @param exchange The server command exchange containing the command and context.
     * @return A Mono containing the result of the command execution.
     */
    abstract fun invokeCommand(exchange: ServerCommandExchange<*>): Mono<*>

    /**
     * Invokes the command and sets the result in the exchange.
     *
     * @param exchange The server command exchange.
     * @return A Mono containing the command result.
     */
    private fun invokeCommandThenSetCommandInvokeResult(exchange: ServerCommandExchange<*>): Mono<Any> {
        @Suppress("UNCHECKED_CAST")
        return invokeCommand(exchange).doOnNext {
            exchange.setCommandInvokeResult(it)
        } as Mono<Any>
    }

    /**
     * Invokes the command with after-command functions if present.
     *
     * @param exchange The server command exchange.
     * @return A Mono containing the combined results.
     */
    private fun invokeWithAfter(exchange: ServerCommandExchange<*>): Mono<*> {
        if (afterCommandFunctions.isEmpty()) {
            return invokeCommandThenSetCommandInvokeResult(exchange)
        }
        val afterFunctionResult = Flux.fromIterable(afterCommandFunctions)
            .flatMap {
                it.invoke(exchange)
            }.flatMapIterable { event ->
                event.flatEvent()
            }

        return invokeCommandThenSetCommandInvokeResult(exchange)
            .flatMapIterable {
                it.flatEvent()
            }
            .concatWith(afterFunctionResult).collectList()
    }

    /**
     * Invokes the command function and returns a domain event stream.
     *
     * This method orchestrates the command execution, including after-command functions,
     * and converts the results into a domain event stream.
     *
     * @param exchange The server command exchange containing the command.
     * @return A Mono containing the domain event stream.
     */
    override fun invoke(exchange: ServerCommandExchange<*>): Mono<DomainEventStream> {
        exchange.setFunction(this)
        return invokeWithAfter(exchange).map {
            it.toDomainEventStream(
                upstream = exchange.message,
                aggregateVersion = commandAggregate.version,
                stateOwnerId = commandAggregate.state.ownerId,
                stateSpaceId = commandAggregate.state.spaceId
            ).also { eventStream ->
                exchange.setEventStream(eventStream)
            }
        }
    }
}
