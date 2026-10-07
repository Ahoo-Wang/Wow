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
import reactor.core.Scannable
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
     * Whether [send] skips a sink without subscribers instead of emitting into it. A multicast sink with an unbounded
     * buffer keeps everything emitted before its first subscriber, so a bus with such sinks sets this to drop a
     * message nobody in this process receives. (9.2's bounded buffer kept up to 256 events per aggregate until the
     * first subscriber, then refused more with `FAIL_OVERFLOW`.)
     */
    protected open val skipsSinksWithoutSubscribers: Boolean = false

    /**
     * Map of sinks keyed by materialized named aggregates.
     */
    private val sinks: MutableMap<NamedAggregate, Sinks.Many<M>> = ConcurrentHashMap()
    private val routingStates: MutableMap<NamedAggregate, LocalDeliveryRoute<M>> = ConcurrentHashMap()
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
     * @return The number of current subscribers, or 0 if no sink exists or the bus is closing
     */
    override fun subscriberCount(namedAggregate: NamedAggregate): Int {
        if (closing) {
            return 0
        }
        val materialized = namedAggregate.materialize()
        val sink = sinks[materialized] ?: return 0
        if (closing) {
            return 0
        }
        val unavailable = routingStates[materialized]?.unavailableSubscriptions() ?: 0
        return (sink.currentSubscriberCount() - unavailable).coerceAtLeast(0)
    }

    /**
     * Hands [message] to the routed runtime receivers of its aggregate: accepted once the sink took it, refused when
     * no receiver is routable or open, or the sink did not take it (full, closed, no subscriber). The admission
     * completes when every routed receiver admitted the message, or `false` when the delivery was rejected first.
     */
    @Suppress("TooGenericExceptionCaught")
    override fun handOff(message: M): Mono<LocalHandoff> =
        Mono.defer {
            val materialized = message.materialize()
            val messageWritable = !message.header.isReadOnly
            if (closing) {
                return@defer Mono.just(LocalHandoff.REFUSED)
            }
            // The aggregate's route decides under its own monitor; close() closes every route before it detaches
            // the sinks, so no delivery is created on a sink being closed.
            val route = routingStates.computeIfAbsent(materialized) { LocalDeliveryRoute() }
            val (sink, pendingDelivery) = route.tryCreateDelivery(
                message = message,
                messageWritable = messageWritable,
                sinkLookup = { sinks[materialized] },
                physicalSubscribers = { it.currentSubscriberCount() },
            ) ?: return@defer Mono.just(LocalHandoff.REFUSED)
            val emitResult = try {
                message.withReadOnly()
                sink.tryEmitNext(message)
            } catch (error: Throwable) {
                route.reject(pendingDelivery)
                throw error
            }
            if (!emitResult.isSuccess) {
                // Zero subscribers, a full buffer or a terminated sink: the distributed copy takes over.
                log.debug { "Local hand-off of [${message.id}] refused by the sink: [$emitResult]." }
                route.reject(pendingDelivery)
                return@defer Mono.just(LocalHandoff.REFUSED)
            }
            if (pendingDelivery.receipt.confirmed) {
                // Every routed receiver admitted it synchronously while it was emitted, on the sender's thread:
                // the admission is already known, so the copy queue may send the distributed copy within this send.
                route.remove(pendingDelivery)
                return@defer Mono.just(LocalHandoff.accepted(ADMITTED_ON_HAND_OFF))
            }
            Mono.just(
                LocalHandoff.accepted(
                    pendingDelivery.receipt.signal()
                        .doFinally {
                            route.remove(pendingDelivery)
                        },
                ),
            )
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
            if (skipsSinksWithoutSubscribers && sink.currentSubscriberCount() == 0) {
                log.debug {
                    "Send [$message], but no subscribers."
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
     * Merges the messages of the sinks of the subscription's named aggregates and converts them to message
     * exchanges. A [runtime-owned][MessageSubscription.runtimeOwned] receiver also takes part in local-first delivery
     * receipts: [handOff] suppresses the distributed copy only while every such receiver has opened
     * processing, and each delivered exchange carries a ticket the receiver confirms or rejects. Any other receiver
     * only observes the messages.
     *
     * @param subscription The message subscription
     * @return The message receiver
     */
    override fun receiver(subscription: MessageSubscription): MessageReceiver<E> =
        if (subscription.runtimeOwned) {
            runtimeOwnedReceiver(subscription)
        } else {
            MessageReceiver(receiveMessages(subscription))
        }

    private fun receiveMessages(
        subscription: MessageSubscription,
        routingSubscription: RoutingSubscription? = null,
    ): Flux<E> {
        val sources = subscription.namedAggregates
            .map { it.materialize() }
            .toSet()
            .mapNotNull { namedAggregate ->
                computeSink(namedAggregate)?.asFlux()?.let { source ->
                    if (routingSubscription == null) {
                        source
                    } else {
                        source
                            .doOnSubscribe {
                                routingSubscription.connect(namedAggregate)
                            }
                            .doFinally {
                                routingSubscription.disconnect(namedAggregate)
                            }
                    }
                }
            }

        return Flux.merge(sources).map { message ->
            message.createExchange().also { exchange ->
                if (routingSubscription != null) {
                    routingStates[message.materialize()]
                        ?.ticket(message, routingSubscription.target)
                        ?.let(exchange::attachLocalDeliveryTicket)
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun runtimeOwnedReceiver(subscription: MessageSubscription): MessageReceiver<E> {
        val routingSubscription = RoutingSubscription(
            subscription.namedAggregates.map { it.materialize() }.toSet(),
        )
        val messages = Flux.defer {
            routingSubscription.begin()
            try {
                receiveMessages(subscription, routingSubscription)
                    .doFinally {
                        routingSubscription.terminate()
                    }
            } catch (error: Throwable) {
                routingSubscription.terminate()
                Flux.error(error)
            }
        }
        return MessageReceiver(
            messages = messages,
            processingAdmission = routingSubscription::open,
            processingQuiescence = routingSubscription::close,
        )
    }

    private inner class RoutingSubscription(
        private val namedAggregates: Set<NamedAggregate>,
    ) {
        val target = LocalDeliveryRouteTarget()
        private val monitor = Any()
        private var subscribed = false
        private var processingOpen = false
        private var terminated = false
        private val connectedAggregates = mutableSetOf<NamedAggregate>()

        fun begin() {
            synchronized(monitor) {
                check(!subscribed) {
                    "In-memory routing subscription supports exactly one subscriber."
                }
                subscribed = true
            }
        }

        fun connect(namedAggregate: NamedAggregate) {
            val rejected = synchronized(monitor) {
                check(namedAggregate in namedAggregates) {
                    "Unexpected local routing aggregate: ${namedAggregate.aggregateName}."
                }
                if (terminated) {
                    return@synchronized emptyList()
                }
                check(connectedAggregates.add(namedAggregate)) {
                    "Local routing aggregate is already connected: ${namedAggregate.aggregateName}."
                }
                routingStates.computeIfAbsent(namedAggregate) { LocalDeliveryRoute() }
                    .addSubscription(target, processingOpen)
            }
            rejected.rejectAll()
        }

        fun disconnect(namedAggregate: NamedAggregate) {
            val rejected = synchronized(monitor) {
                if (!connectedAggregates.remove(namedAggregate)) {
                    return@synchronized emptyList()
                }
                checkNotNull(routingStates[namedAggregate]) {
                    "Missing local routing state for ${namedAggregate.aggregateName}."
                }.removeSubscription(target)
            }
            rejected.rejectAll()
        }

        fun open() {
            val rejected = synchronized(monitor) {
                if (terminated || processingOpen) {
                    return@synchronized emptyList()
                }
                processingOpen = true
                connectedAggregates.flatMap {
                    checkNotNull(routingStates[it]) {
                        "Missing local routing state for ${it.aggregateName}."
                    }.openSubscription(target)
                }
            }
            rejected.rejectAll()
        }

        fun close() {
            val rejected = synchronized(monitor) {
                if (terminated || !processingOpen) {
                    return@synchronized emptyList()
                }
                processingOpen = false
                connectedAggregates.flatMap {
                    checkNotNull(routingStates[it]) {
                        "Missing local routing state for ${it.aggregateName}."
                    }.closeSubscription(target)
                }
            }
            rejected.rejectAll()
        }

        fun terminate() {
            val rejected = synchronized(monitor) {
                if (terminated) {
                    return@synchronized emptyList()
                }
                terminated = true
                connectedAggregates.toList().flatMap {
                    checkNotNull(routingStates[it]) {
                        "Missing local routing state for ${it.aggregateName}."
                    }.removeSubscription(target)
                }.also {
                    connectedAggregates.clear()
                }
            }
            rejected.rejectAll()
        }
    }

    override fun close() {
        val (detachedSinks, rejectedDeliveries) = synchronized(lifecycleLock) {
            if (closing) {
                return
            }
            closing = true
            sinks.entries.map { entry -> entry.key to entry.value } to
                routingStates.values.flatMap { it.close() }
        }
        rejectedDeliveries.rejectAll()
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
            val settlement = many.closeSettlement()
            CloseAttempt(
                settledSink = if (settlement == null && many.isTerminatedOrCancelled()) {
                    aggregate to many
                } else {
                    null
                },
                pendingSettlement = settlement?.let {
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

    private fun Sinks.Many<M>.isTerminatedOrCancelled(): Boolean =
        scan(Scannable.Attr.TERMINATED) == true ||
            scan(Scannable.Attr.CANCELLED) == true

    private fun finishClose(detachedSinks: List<Pair<NamedAggregate, Sinks.Many<M>>>) {
        synchronized(lifecycleLock) {
            detachedSinks.forEach { (aggregate, many) ->
                sinks.remove(aggregate, many)
            }
            // The closed sinks are gone, so a reopened route only ever finds a new one.
            routingStates.values.forEach { it.reopen() }
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
