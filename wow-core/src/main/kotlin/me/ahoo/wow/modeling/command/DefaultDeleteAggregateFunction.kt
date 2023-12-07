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

import me.ahoo.wow.api.command.DefaultDeleteAggregate
import me.ahoo.wow.api.event.DefaultAggregateDeleted
import me.ahoo.wow.api.messaging.FunctionKind
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.asDomainEventStream
import me.ahoo.wow.messaging.function.MessageFunction
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

class DefaultDeleteAggregateFunction<C : Any>(
    private val commandAggregate: CommandAggregate<C, *>
) : MessageFunction<C, ServerCommandExchange<*>, Mono<DomainEventStream>> {
    override val contextName: String = commandAggregate.contextName
    override val supportedType: Class<*> = DefaultDeleteAggregate::class.java
    override val processor: C = commandAggregate.commandRoot
    override val name: String = "${processor.javaClass.simpleName}.${supportedType.simpleName}"
    override val functionKind: FunctionKind = FunctionKind.COMMAND

    override fun invoke(
        exchange: ServerCommandExchange<*>
    ): Mono<DomainEventStream> {
        return DefaultAggregateDeleted
            .asDomainEventStream(exchange.message, commandAggregate.version)
            .toMono()
    }
}
