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

import com.google.errorprone.annotations.ThreadSafe
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

/**
 * Abstract base class for in-memory message bus implementations.
 *
 * This class provides a local message bus that uses Reactor Sinks for message distribution
 * within a single JVM instance. Messages are sent to subscribers via sinks and can be
 * received by subscribing to the appropriate named aggregates.
 *
 * @param M The type of message, must implement both Message and NamedAggregate
 * @param E The type of message exchange
 */
@ThreadSafe
abstract class InMemoryMessageBus<M, E : MessageExchange<*, M>> : LocalMessageBus<M, E>
    where M : Message<*, *>, M : NamedAggregate {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val sender = Schedulers.newSingle(this::class.java.simpleName)

    /**
     * Supplier function that creates a sink for a given named aggregate.
     *
     * Implementations should provide this to create appropriate sinks for message distribution.
     */
    abstract val sinkSupplier: (NamedAggregate) -> Sinks.Many<M>

    /**
     * Map of sinks keyed by materialized named aggregates.
     */
    private val sinks: MutableMap<NamedAggregate, Sinks.Many<M>> = ConcurrentHashMap()

    /**
     * Computes or retrieves the sink for the given named aggregate.
     *
     * @param namedAggregate The named aggregate for which to get the sink
     * @return The sink for the aggregate
     */
    private fun computeSink(namedAggregate: NamedAggregate): Sinks.Many<M> =
        sinks.computeIfAbsent(namedAggregate.materialize()) { sinkSupplier(it) }

    /**
     * Returns the number of subscribers for the specified named aggregate.
     *
     * @param namedAggregate The named aggregate to check
     * @return The number of current subscribers, or 0 if no sink exists
     */
    override fun subscriberCount(namedAggregate: NamedAggregate): Int {
        val sink = sinks[namedAggregate.materialize()] ?: return 0
        return sink.currentSubscriberCount()
    }

    /**
     * Sends a message through the in-memory bus.
     *
     * The message is made read-only before sending and emitted to all subscribers
     * of the message's aggregate. If there are no subscribers, the message is silently dropped.
     *
     * @param message The message to send
     * @return A Mono that completes when the message has been sent
     */
    override fun send(message: M): Mono<Void> {
        return Mono.fromRunnable<Void> {
            val sink = computeSink(message)
            message.withReadOnly()
            val emitResult = sink.tryEmitNext(message)
            if (emitResult == Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
                log.debug {
                    "Send [$message], but no subscribers."
                }
                return@fromRunnable
            }
            emitResult.orThrow()
        }.subscribeOn(sender)
    }

    /**
     * Creates a message exchange from this message.
     *
     * @receiver The message to create an exchange for
     * @return The created message exchange
     */
    abstract fun M.createExchange(): E

    /**
     * Receives messages for the specified named aggregates.
     *
     * Creates a flux that merges messages from all the sinks corresponding to the
     * named aggregates and converts them to message exchanges.
     *
     * @param namedAggregates The set of named aggregates to receive messages for
     * @return A flux of message exchanges
     */
    override fun receive(namedAggregates: Set<NamedAggregate>): Flux<E> {
        val sources = namedAggregates.map {
            computeSink(it).asFlux()
        }

        return Flux.merge(sources).map {
            it.createExchange()
        }
    }

    override fun close() {
        sinks.forEach { (aggregate, many) ->
            val emitResult = many.tryEmitComplete()
            log.debug {
                "Close [${aggregate.aggregateName}] sink - [$emitResult]."
            }
        }
        sinks.clear()
    }
}
