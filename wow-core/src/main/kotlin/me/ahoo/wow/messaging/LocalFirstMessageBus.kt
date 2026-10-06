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
import me.ahoo.wow.api.Copyable
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.modeling.AggregateIdCapable
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.configuration.MetadataSearcher.isLocal
import me.ahoo.wow.messaging.handler.ExchangeAck.filterThenAck
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.modeling.materialize
import reactor.core.Exceptions
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Header key used to indicate local-first message routing.
 */
const val LOCAL_FIRST_HEADER = "local_first"

/**
 * Adds the local-first flag to the header.
 *
 * @param localFirst Whether to enable local-first routing (default: true)
 * @return A new header with the local-first flag set
 */
fun Header.withLocalFirst(localFirst: Boolean = true): Header = with(LOCAL_FIRST_HEADER, localFirst.toString())

/**
 * Checks if the header has the local-first flag set.
 *
 * @return true if local-first routing is enabled, false otherwise
 */
fun Header.isLocalFirst(): Boolean = this[LOCAL_FIRST_HEADER].toBoolean()

/**
 * Sets the local-first flag on this message.
 *
 * @param M The message type.
 * @param localFirst Whether to enable local-first routing (default: true)
 * @return This message with the local-first flag set
 */
fun <M : Message<out M, *>> M.withLocalFirst(localFirst: Boolean = true): M {
    this.header.withLocalFirst(localFirst)
    return this
}

/**
 * Checks if this message has local-first routing enabled.
 *
 * @return true if local-first routing is enabled, false otherwise
 */
fun <M : Message<*, *>> M.isLocalFirst(): Boolean = header.isLocalFirst()

/**
 * Determines if this message should use local-first routing.
 *
 * Local-first routing is used when the aggregate is local and the header
 * doesn't explicitly disable local-first (set to `false`, compared case-insensitively
 * so values like `FALSE`/`False` also disable routing).
 *
 * @return true if local-first routing should be used, false otherwise
 */
fun <M> M.shouldLocalFirst(): Boolean
    where M : Message<*, *>, M : NamedAggregate =
    isLocal() && !header[LOCAL_FIRST_HEADER].equals(false.toString(), ignoreCase = true)

/**
 * Checks if this message has been handled locally.
 *
 * A message is considered locally handled if it has the local-first flag
 * and the aggregate is local.
 *
 * @return true if the message was handled locally, false otherwise
 */
fun <M> M.isLocalHandled(): Boolean where M : Message<*, *>, M : NamedAggregate = isLocalFirst() && isLocal()

private val log = KotlinLogging.logger {}

/**
 * A message bus that prioritizes local message handling before distributed routing.
 *
 * This bus first attempts to send messages locally within the JVM, and only sends
 * to the distributed bus if local sending fails or there are no local subscribers.
 * It also merges local and distributed message streams for receiving.
 *
 * @param M The message type, must implement Message, NamedAggregate, and Copyable
 * @param E The message exchange type
 */
interface LocalFirstMessageBus<M, E : MessageExchange<*, M>> :
    MessageBus<M, E>
    where M : Message<*, *>, M : NamedAggregate, M : Copyable<*> {
    /**
     * The distributed message bus for fallback routing.
     */
    val distributedBus: DistributedMessageBus<M, E>

    /**
     * The local message bus for in-JVM routing.
     */
    val localBus: LocalMessageBus<M, E>

    @Suppress("TooGenericExceptionCaught")
    override fun close() {
        var closeError: Exception? = null
        try {
            localBus.close()
        } catch (error: Exception) {
            closeError = error
        }
        try {
            distributedBus.close()
        } catch (error: Exception) {
            closeError?.addSuppressed(error) ?: run {
                closeError = error
            }
        }
        closeError?.let {
            throw it
        }
    }

    /**
     * The simple name of the local bus class for logging.
     */
    private val localBusName: String
        get() = localBus.javaClass.simpleName

    /**
     * The distributed copies, sent in send order per aggregate.
     */
    val distributedCopies: LocalFirstDistributedCopies

    /**
     * Sends a message using local-first routing strategy.
     *
     * When local-first applies, the message is handed to the local receivers ([LocalMessageBus.handOff]) and its
     * distributed copy is queued in [distributedCopies], which sends an aggregate's copies in send order. When the
     * send completes:
     * - **handed off** (the message entered the local sink of every routed, processing-open receiver): at once. The
     *   copy goes out in its turn once the receivers decided: marked locally handled (`local_first=true`) when every
     *   receiver admitted it, eligible for distributed processing (`local_first=false`) when the delivery was rejected
     *   first (a receiver closed). Its failure is logged and counted by the distributed bus's metrics; it never reaches
     *   the sender.
     * - **refused** (no routable receiver, a closed route) or **local error**: the copy, eligible for distributed
     *   processing, is the delivery; the send completes when it was sent in its turn, or fails with it.
     *
     * A sender never waits for a local receiver's demand, so handlers that send (a command handler publishing events,
     * a saga sending commands) cannot block one another: closing a route rejects its pending admissions, so a refused
     * copy never waits behind one that waits for demand. A message handed off but not yet processed is lost if the
     * process crashes: local-first trades that durability for latency.
     *
     * @param message The message to send
     * @return A Mono that completes as described above
     */
    override fun send(message: M): Mono<Void> {
        if (!message.shouldLocalFirst()) {
            return distributedBus.send(message)
        }

        // A local delivery attempt owns a fresh immutable message identity.
        return Mono.deferContextual { context ->
            @Suppress("UNCHECKED_CAST")
            val localMessage = message.copy() as M
            localMessage.withLocalFirst()
            @Suppress("UNCHECKED_CAST")
            val distributedMessage = message.copy() as M
            // Queued before the hand-off, so the copies of one aggregate keep the send order.
            val copy = distributedCopies.enqueue(
                key = (message as? AggregateIdCapable)?.aggregateId ?: message.materialize(),
                namedAggregate = message,
                context = context,
                description = { "message[${message.id}] (via $localBusName)" },
            ) { admitted ->
                distributedMessage.withLocalFirst(admitted)
                distributedBus.send(distributedMessage)
            }
            localBus.handOff(localMessage)
                .onErrorResume { error ->
                    log.error(error) {
                        "[$localBusName] Failed to hand off local message[${message.id}], " +
                            "LocalFirst mode temporarily disabled."
                    }
                    Mono.just(LocalHandoff.REFUSED)
                }
                .doOnNext(copy::decide)
                // A sender cancelled before it learnt the result: the copy is not suppressed (at most a duplicate).
                .doFinally { copy.decide(LocalHandoff.REFUSED) }
                .flatMap { handoff ->
                    if (handoff.accepted) Mono.empty() else copy.sent
                }
        }
    }

    /**
     * Receives messages from both local and distributed buses.
     *
     * Local messages are received for local aggregates, while distributed messages are filtered to exclude those
     * already handled locally. Both receivers get the same [MessageSubscription.runtimeOwned], so a runtime
     * dispatcher's local receiver takes part in local-first delivery receipts.
     *
     * @param subscription The message subscription
     * @return One receiver over the local and distributed sources
     */
    override fun receiver(subscription: MessageSubscription): MessageReceiver<E> {
        val localTopics = subscription.namedAggregates.filter {
            it.isLocal()
        }.toSet()
        val localReceiver = localBus.receiver(subscription.copy(namedAggregates = localTopics))
        val distributedReceiver = distributedBus.receiver(subscription)
        return MessageReceiver(
            messages = Flux.merge(
                localReceiver.messages,
                distributedReceiver.messages.filterThenAck {
                    !it.message.isLocalHandled()
                },
            ),
            readiness = Mono.`when`(
                localReceiver.readiness,
                distributedReceiver.readiness,
            ),
            processingAdmission = {
                localReceiver.openProcessing()
                distributedReceiver.openProcessing()
            },
            processingQuiescence = {
                closeReceivers(localReceiver, distributedReceiver)
            },
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun closeReceivers(
        localReceiver: MessageReceiver<E>,
        distributedReceiver: MessageReceiver<E>,
    ) {
        var failure: Throwable? = null
        try {
            localReceiver.closeProcessing()
        } catch (error: Throwable) {
            Exceptions.throwIfFatal(error)
            failure = error
        }
        try {
            distributedReceiver.closeProcessing()
        } catch (error: Throwable) {
            Exceptions.throwIfFatal(error)
            if (failure == null) {
                failure = error
            } else if (failure !== error) {
                failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}
