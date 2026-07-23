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
import me.ahoo.wow.infra.sink.CloseSettlementAware
import me.ahoo.wow.infra.sink.prepareConcurrentSink
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.modeling.materialize
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.util.concurrent.CompletableFuture
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
    private val lifecycleLock = Any()

    @Volatile
    private var closing = false

    /**
     * Computes or retrieves the sink for the given named aggregate.
     *
     * @param namedAggregate The named aggregate for which to get the sink
     * @return The sink for the aggregate, or null while the bus is closing
     */
    private fun computeSink(namedAggregate: NamedAggregate): Sinks.Many<M>? {
        if (closing) {
            return null
        }
        val materialized = namedAggregate.materialize()
        sinks[materialized]?.let {
            return it
        }
        return synchronized(lifecycleLock) {
            if (closing) {
                null
            } else {
                sinks[materialized] ?: sinkSupplier(materialized).prepareConcurrentSink().also {
                    sinks[materialized] = it
                }
            }
        }
    }

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
        return Mono.fromRunnable {
            val sink = computeSink(message)
            message.withReadOnly()
            if (sink == null) {
                log.debug {
                    "Send [$message], but the message bus is closing."
                }
                return@fromRunnable
            }
            val emitResult = sink.tryEmitNext(message)
            if (emitResult == Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
                log.debug {
                    "Send [$message], but no subscribers."
                }
                return@fromRunnable
            }
            emitResult.orThrow()
        }
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
     * @param subscription The message subscription
     * @return A flux of message exchanges
     */
    override fun receive(subscription: MessageSubscription): Flux<E> {
        val sources = subscription.namedAggregates.mapNotNull {
            computeSink(it)?.asFlux()
        }

        return Flux.merge(sources).map {
            it.createExchange()
        }
    }

    override fun close() {
        val detachedSinks = synchronized(lifecycleLock) {
            if (closing) {
                return
            }
            closing = true
            sinks.entries.map { entry -> entry.key to entry.value }
        }
        val settledSinks = mutableListOf<Pair<NamedAggregate, Sinks.Many<M>>>()
        val pendingSettlements = mutableListOf<PendingCloseSettlement<M>>()
        var closeFailure: Throwable? = null
        detachedSinks.forEach { (aggregate, many) ->
            val attempt = tryCloseSink(aggregate, many)
            attempt.settledSink?.let(settledSinks::add)
            attempt.pendingSettlement?.let(pendingSettlements::add)
            attempt.failure?.let { closeFailure = mergeCloseFailure(closeFailure, it) }
        }
        if (pendingSettlements.isEmpty()) {
            finishClose(settledSinks)
        } else {
            CompletableFuture.allOf(*pendingSettlements.map { it.settled }.toTypedArray())
                .whenComplete { _, error ->
                    if (error != null) {
                        log.warn(error) {
                            "Failed to settle one or more in-memory sink close signals."
                        }
                    }
                    /*
                     * Exceptional settlement still means the MPSC close state machine
                     * reached its final linearization point. That sink is terminal and
                     * cannot be retried, so it must not remain cached and block a fresh
                     * subscription for the same aggregate.
                     */
                    val asynchronouslySettled = pendingSettlements.map {
                        it.aggregate to it.many
                    }
                    finishClose(settledSinks + asynchronouslySettled)
                }
        }
        closeFailure?.let { throw it }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun tryCloseSink(
        aggregate: NamedAggregate,
        many: Sinks.Many<M>,
    ): CloseAttempt<M> =
        try {
            val emitResult = many.tryEmitComplete()
            when {
                emitResult == Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER -> {
                    /*
                     * Some unicast sinks cannot retain a terminal before their
                     * first subscriber. Nobody can observe that completion, so
                     * detach the sink instead of waiting for a settlement that
                     * cannot occur.
                     */
                    log.debug {
                        "Close [${aggregate.aggregateName}] sink - [$emitResult]."
                    }
                    CloseAttempt(settledSink = aggregate to many)
                }

                emitResult.isSuccess ||
                    emitResult == Sinks.EmitResult.FAIL_TERMINATED ||
                    emitResult == Sinks.EmitResult.FAIL_CANCELLED -> {
                    log.debug {
                        "Close [${aggregate.aggregateName}] sink - [$emitResult]."
                    }
                    val settlement = many.closeSettlement()
                    if (settlement == null) {
                        CloseAttempt(settledSink = aggregate to many)
                    } else {
                        CloseAttempt(
                            pendingSettlement = PendingCloseSettlement(aggregate, many, settlement),
                        )
                    }
                }

                else -> CloseAttempt(
                    failure = Sinks.EmissionException(
                        emitResult,
                        "In-memory [${aggregate.aggregateName}] sink rejected close with [$emitResult].",
                    ),
                )
            }
        } catch (error: Throwable) {
            log.warn(error) {
                "Failed to close [${aggregate.aggregateName}] sink."
            }
            CloseAttempt(
                pendingSettlement = many.closeSettlement()?.let {
                    PendingCloseSettlement(aggregate, many, it)
                },
                failure = error,
            )
        }

    private fun mergeCloseFailure(current: Throwable?, next: Throwable): Throwable {
        if (current == null) {
            return next
        }
        if (current !== next) {
            current.addSuppressed(next)
        }
        return current
    }

    private fun Sinks.Many<M>.closeSettlement(): CompletableFuture<Unit>? =
        (this as? CloseSettlementAware)?.closeSettled

    private fun finishClose(detachedSinks: List<Pair<NamedAggregate, Sinks.Many<M>>>) {
        synchronized(lifecycleLock) {
            detachedSinks.forEach { (aggregate, many) ->
                sinks.remove(aggregate, many)
            }
            closing = false
        }
    }

    private data class PendingCloseSettlement<M : Any>(
        val aggregate: NamedAggregate,
        val many: Sinks.Many<M>,
        val settled: CompletableFuture<Unit>,
    )

    private data class CloseAttempt<M : Any>(
        val settledSink: Pair<NamedAggregate, Sinks.Many<M>>? = null,
        val pendingSettlement: PendingCloseSettlement<M>? = null,
        val failure: Throwable? = null,
    )
}
