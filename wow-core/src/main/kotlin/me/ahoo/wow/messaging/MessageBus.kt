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

package me.ahoo.wow.messaging

import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.messaging.handler.MessageExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface MessageBus<M : Message<*, *>, E : MessageExchange<*, M>> : AutoCloseable {
    override fun close() = Unit
    fun send(message: M): Mono<Void>
    fun receive(namedAggregates: Set<NamedAggregate>): Flux<E>
}

interface LocalMessageBus<M : Message<*, *>, E : MessageExchange<*, M>> : MessageBus<M, E> {
    fun subscriberCount(namedAggregate: NamedAggregate): Int
}

interface DistributedMessageBus<M : Message<*, *>, E : MessageExchange<*, M>> : MessageBus<M, E>
