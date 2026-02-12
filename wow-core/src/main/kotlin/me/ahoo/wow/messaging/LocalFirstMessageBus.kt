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
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.configuration.MetadataSearcher.isLocal
import me.ahoo.wow.messaging.handler.ExchangeAck.filterThenAck
import me.ahoo.wow.messaging.handler.MessageExchange
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
 * doesn't explicitly disable local-first (set to false).
 *
 * @return true if local-first routing should be used, false otherwise
 */
fun <M> M.shouldLocalFirst(): Boolean
    where M : Message<*, *>, M : NamedAggregate =
    isLocal() && header[LOCAL_FIRST_HEADER] != false.toString()

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

    /**
     * The simple name of the local bus class for logging.
     */
    private val localBusName: String
        get() = localBus.javaClass.simpleName

    /**
     * Sends a message using local-first routing strategy.
     *
     * If local-first routing is enabled and there are local subscribers, the message
     * is first sent locally. Regardless of local success/failure, a copy is also sent
     * to the distributed bus. If local-first is disabled or no local subscribers exist,
     * only the distributed bus is used.
     *
     * @param message The message to send
     * @return A Mono that completes when sending is done
     */
    @Suppress("ReturnCount")
    override fun send(message: M): Mono<Void> {
        if (!message.shouldLocalFirst() || localBus.subscriberCount(message) == 0) {
            return distributedBus.send(message)
        }

        message.withLocalFirst()
        val localSend = localBus.send(message)
        return localSend.materialize().flatMap {
            @Suppress("UNCHECKED_CAST")
            val distributedMessage = message.copy() as M
            if (it.hasError()) {
                val error = it.throwable!!
                log.error(error) {
                    "[$localBusName] Failed to send local message[${message.id}], LocalFirst mode temporarily disabled."
                }
                distributedMessage.withLocalFirst(false)
            }
            distributedBus.send(distributedMessage)
        }
    }

    /**
     * Receives messages from both local and distributed buses.
     *
     * Local messages are received for local aggregates, while distributed messages
     * are filtered to exclude those already handled locally.
     *
     * @param namedAggregates The set of named aggregates to receive messages for
     * @return A merged flux of message exchanges from local and distributed sources
     */
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
