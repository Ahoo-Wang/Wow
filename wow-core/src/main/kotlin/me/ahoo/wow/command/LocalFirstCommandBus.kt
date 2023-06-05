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
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.metrics.Metrics.metrizable
import me.ahoo.wow.modeling.materialize
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

const val COMMAND_LOAD_FIRST = "command_load_first"
fun CommandMessage<*>.withLoadFirst(): CommandMessage<*> {
    return withHeader(mapOf(COMMAND_LOAD_FIRST to "true"))
}

fun CommandMessage<*>.isLoadFirst(): Boolean {
    return header[COMMAND_LOAD_FIRST]?.toBoolean() ?: false
}

class LocalFirstCommandBus(
    private val distributedCommandBus: DistributedCommandBus,
    private val doubleSend: Boolean = false,
    private val localCommandBus: LocalCommandBus = InMemoryCommandBus().metrizable(),
    private val localAggregates: Set<NamedAggregate> = MetadataSearcher.namedAggregateType.keys.toSet()
) : CommandBus {

    @Suppress("ReturnCount")
    override fun send(message: CommandMessage<*>): Mono<Void> {
        if (localAggregates.contains(message.materialize())) {
            message.withLoadFirst()
            val localSend = localCommandBus.send(message)
            if (!doubleSend) {
                return localSend
            }
            val distributedMessage = message.copy()
            val distributedSend = distributedCommandBus.send(distributedMessage)
            return localSend.then(distributedSend)
        }
        return distributedCommandBus.send(message)
    }

    override fun receive(namedAggregates: Set<NamedAggregate>): Flux<ServerCommandExchange<*>> {
        val localFlux = localCommandBus.receive(namedAggregates)
        val distributedFlux =
            distributedCommandBus.receive(namedAggregates).filterWhen {
                val isLoadFirst = it.message.isLoadFirst()
                if (isLoadFirst) {
                    return@filterWhen it.acknowledge().thenReturn(false)
                }
                Mono.just(true)
            }
        return Flux.merge(localFlux, distributedFlux)
    }
}
