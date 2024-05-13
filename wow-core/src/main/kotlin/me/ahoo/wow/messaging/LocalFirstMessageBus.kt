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

import me.ahoo.wow.api.Copyable
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.configuration.MetadataSearcher.isLocal
import me.ahoo.wow.messaging.handler.ExchangeAck.filterThenAck
import me.ahoo.wow.messaging.handler.MessageExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

const val LOCAL_FIRST_HEADER = "local.first"

fun Header.withLocalFirst(localFirst: Boolean = true): Header {
    return with(LOCAL_FIRST_HEADER, localFirst.toString())
}

fun Header.isLocalFirst(): Boolean {
    return this[LOCAL_FIRST_HEADER].toBoolean()
}

fun <M : Message<out M, *>> M.withLocalFirst(localFirst: Boolean = true): M {
    this.header.withLocalFirst(localFirst)
    return this
}

fun <M : Message<*, *>> M.isLocalFirst(): Boolean {
    return header.isLocalFirst()
}

fun <M> M.shouldLocalFirst(): Boolean
    where M : Message<*, *>, M : NamedAggregate {
    return isLocal() && header[LOCAL_FIRST_HEADER] != false.toString()
}

fun <M> M.isLocalHandled(): Boolean where M : Message<*, *>, M : NamedAggregate {
    return isLocalFirst() && isLocal()
}

interface LocalFirstMessageBus<M, E : MessageExchange<*, M>> : MessageBus<M, E>
    where M : Message<*, *>, M : NamedAggregate, M : Copyable<*> {
    val distributedBus: DistributedMessageBus<M, E>
    val localBus: LocalMessageBus<M, E>

    @Suppress("ReturnCount")
    override fun send(message: M): Mono<Void> {
        if (message.shouldLocalFirst()) {
            message.withLocalFirst()
            val localSend = localBus.send(message)

            @Suppress("UNCHECKED_CAST")
            val distributedMessage = message.copy() as M
            val distributedSend = distributedBus.send(distributedMessage)
            return localSend.then(distributedSend)
        }
        return distributedBus.send(message)
    }

    override fun receive(namedAggregates: Set<NamedAggregate>): Flux<E> {
        val localTopics = namedAggregates.filter {
            it.isLocal()
        }.toSet()
        val localFlux = localBus.receive(localTopics)
        val distributedFlux = distributedBus.receive(namedAggregates)
            .filterThenAck {
                !it.message.isLocalHandled()
            }
        return Flux.merge(localFlux, distributedFlux)
    }
}
