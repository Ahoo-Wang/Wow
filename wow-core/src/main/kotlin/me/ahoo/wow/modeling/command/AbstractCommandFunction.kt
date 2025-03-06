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
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.messaging.function.MessageFunction
import reactor.core.publisher.Mono

@JvmDefaultWithoutCompatibility
abstract class AbstractCommandFunction<C : Any>(
    val commandAggregate: CommandAggregate<C, *>,
    private val afterCommandFunction: AfterCommandFunction<C>
) : MessageFunction<C, ServerCommandExchange<*>, Mono<DomainEventStream>> {

    abstract fun invokeCommand(exchange: ServerCommandExchange<*>): Mono<*>

    override fun invoke(exchange: ServerCommandExchange<*>): Mono<DomainEventStream> {
        return afterCommandFunction.afterCommand(exchange) {
            invokeCommand(exchange)
        }.map {
            it.toDomainEventStream(
                upstream = exchange.message,
                aggregateVersion = commandAggregate.version,
                stateOwnerId = commandAggregate.state.ownerId
            )
        }
    }
}
