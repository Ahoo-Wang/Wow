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

@JvmDefaultWithoutCompatibility
abstract class AbstractCommandFunction<C : Any>(
    val commandAggregate: CommandAggregate<C, *>,
    private val afterCommandFunctions: List<AfterCommandFunction<C>>
) : MessageFunction<C, ServerCommandExchange<*>, Mono<DomainEventStream>> {

    abstract fun invokeCommand(exchange: ServerCommandExchange<*>): Mono<*>

    private fun invokeCommandThenSetCommandInvokeResult(exchange: ServerCommandExchange<*>): Mono<Any> {
        @Suppress("UNCHECKED_CAST")
        return invokeCommand(exchange).doOnNext {
            exchange.setCommandInvokeResult(it)
        } as Mono<Any>
    }

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

    override fun invoke(exchange: ServerCommandExchange<*>): Mono<DomainEventStream> {
        return invokeWithAfter(exchange).map {
            it.toDomainEventStream(
                upstream = exchange.message,
                aggregateVersion = commandAggregate.version,
                stateOwnerId = commandAggregate.state.ownerId
            )
        }
    }
}
