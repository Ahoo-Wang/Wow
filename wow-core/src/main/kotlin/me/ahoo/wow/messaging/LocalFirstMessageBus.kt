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
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.configuration.MetadataSearcher.isLocal
import me.ahoo.wow.messaging.handler.MessageExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

const val LOCAL_FIRST_HEADER = "local.first"
const val LOCAL_FIRST_HEADER_VALUE = "true"

@Suppress("UNCHECKED_CAST")
fun <M : Message<*, *>> M.withLoadFirst(): M {
    return withHeader(LOCAL_FIRST_HEADER, LOCAL_FIRST_HEADER_VALUE) as M
}

fun <M : Message<*, *>> M.isLoadFirst(): Boolean {
    return header[LOCAL_FIRST_HEADER] == LOCAL_FIRST_HEADER_VALUE
}

fun <M> M.islocalHandled(): Boolean where M : Message<*, *>, M : NamedAggregate {
    return isLoadFirst() && isLocal()
}

interface LocalFirstMessageBus<M, E : MessageExchange<*, M>> : MessageBus<M, E>
    where M : Message<*, *>, M : NamedAggregate, M : Copyable<M> {
    val distributedBus: DistributedMessageBus<M, E>
    val localBus: LocalMessageBus<M, E>

    @Suppress("ReturnCount")
    override fun send(message: M): Mono<Void> {
        if (message.isLocal()) {
            message.withLoadFirst()
            val localSend = localBus.send(message)
            val distributedMessage = message.copy()
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
        val distributedFlux =
            distributedBus.receive(namedAggregates).filterWhen {
                val islocalHandled = it.message.islocalHandled()
                if (islocalHandled) {
                    return@filterWhen it.acknowledge().thenReturn(false)
                }
                Mono.just(true)
            }
        return Flux.merge(localFlux, distributedFlux)
    }
}
