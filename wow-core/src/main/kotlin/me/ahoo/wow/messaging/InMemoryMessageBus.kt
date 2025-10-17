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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.modeling.materialize
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Schedulers
import java.util.concurrent.ConcurrentHashMap

abstract class InMemoryMessageBus<M, E : MessageExchange<*, M>> : LocalMessageBus<M, E>
    where M : Message<*, *>, M : NamedAggregate {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    abstract val sinkSupplier: (NamedAggregate) -> Sinks.Many<M>
    private val sinks: MutableMap<NamedAggregate, Sinks.Many<M>> = ConcurrentHashMap()
    private fun computeSink(namedAggregate: NamedAggregate): Sinks.Many<M> {
        return sinks.computeIfAbsent(namedAggregate.materialize()) { sinkSupplier(it) }
    }

    private val sender = Schedulers.newSingle(this::class.java.simpleName)
    override fun subscriberCount(namedAggregate: NamedAggregate): Int {
        val sink = sinks[namedAggregate.materialize()] ?: return 0
        return sink.currentSubscriberCount()
    }

    override fun send(message: M): Mono<Void> {
        return Mono.fromRunnable<Void> {
            val sink = computeSink(message)
            log.debug {
                "Send to [${sink.currentSubscriberCount()}] \n $message."
            }
            message.withReadOnly()
            sink.tryEmitNext(message).orThrow()
        }.subscribeOn(sender)
    }

    abstract fun M.createExchange(): E
    override fun receive(namedAggregates: Set<NamedAggregate>): Flux<E> {
        val sources = namedAggregates.map {
            computeSink(it).asFlux()
        }

        return Flux.merge(sources).map {
            it.createExchange()
        }
    }
}
