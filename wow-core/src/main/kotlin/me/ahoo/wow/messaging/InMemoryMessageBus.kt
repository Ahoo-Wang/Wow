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
import me.ahoo.wow.modeling.materialize
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

val DEFAULT_BUSY_LOOPING_DURATION: Duration = Duration.ofSeconds(1)
private val LOG = LoggerFactory.getLogger(InMemoryMessageBus::class.java)

abstract class InMemoryMessageBus<M, E : MessageExchange<*, M>> : LocalMessageBus<M, E>
    where M : Message<*, *>, M : NamedAggregate {
    private val busyLoopingDuration: Duration = DEFAULT_BUSY_LOOPING_DURATION
    abstract val sinkSupplier: (NamedAggregate) -> Sinks.Many<M>
    private val sinks: MutableMap<NamedAggregate, Sinks.Many<M>> = ConcurrentHashMap()
    private fun computeSink(namedAggregate: NamedAggregate): Sinks.Many<M> {
        return sinks.computeIfAbsent(namedAggregate.materialize()) { sinkSupplier(it) }
    }

    override fun send(message: M): Mono<Void> {
        return Mono.fromRunnable {
            if (LOG.isDebugEnabled) {
                LOG.debug("Send {}.", message)
            }
            message.withReadOnly()
            val sink = computeSink(message)
            sink.emitNext(
                message,
                Sinks.EmitFailureHandler.busyLooping(busyLoopingDuration),
            )
        }
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
