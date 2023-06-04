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
import reactor.util.context.Context
import reactor.util.context.ContextView

interface MessageBus<M : Message<*, *>, E : MessageExchange<*, M>> : AutoCloseable {
    override fun close() = Unit
    fun send(message: M): Mono<Void>
    fun receive(namedAggregates: Set<NamedAggregate>): Flux<E>
}

interface LocalMessageBus<M : Message<*, *>, E : MessageExchange<*, M>> : MessageBus<M, E>

interface DistributedMessageBus<M : Message<*, *>, E : MessageExchange<*, M>> : MessageBus<M, E>

const val RECEIVER_GROUP = "(ReceiverGroup)"

fun ContextView.getReceiverGroupOrDefault(default: String): String {
    return getOrDefault(RECEIVER_GROUP, default)!!
}

fun ContextView.getReceiverGroup(): String {
    return get(RECEIVER_GROUP)
}

fun Context.setReceiverGroup(receiverGroup: String): Context {
    return this.put(RECEIVER_GROUP, receiverGroup)
}

/**
 * Write Receiver Group.
 */
fun <T> Flux<T>.writeReceiverGroup(receiverGroup: String): Flux<T> {
    return contextWrite {
        it.setReceiverGroup(receiverGroup)
    }
}
