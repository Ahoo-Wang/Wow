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

package me.ahoo.wow.messaging.handler

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.messaging.rejectLocalDelivery
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

private const val ACKNOWLEDGEMENT_WITHHELD_KEY = "__ACKNOWLEDGEMENT_WITHHELD__"

/**
 * Leaves this exchange unacknowledged when its processing ends, so a bus that redelivers unacknowledged messages
 * delivers it again. For a processing failure that was neither handled nor durably recorded, for example when
 * recording an event-processing failure for compensation fails.
 *
 * Set on a per-function event exchange, it applies to the event stream exchange that function belongs to; the
 * other functions of that stream still run. [withheldBy] names who withheld it (by default the exchange's
 * function) for the log line.
 *
 * Whether and when the message is delivered again is the bus's behaviour:
 * - Redis Streams: the entry stays pending and is re-claimed and redelivered.
 * - Kafka: one Wow receiver is one consumer for all the aggregate topics of its dispatcher. Commits stop at this
 *   offset; after `maxDeferredCommits` further acknowledgements the whole receiver (every subscribed aggregate and
 *   partition) stops polling until a restart or rebalance redelivers from this offset, and every later rebalance
 *   of that consumer waits the full `maxDelayRebalance` (60 s by default) for it.
 * - In-memory buses and locally handled (local-first) messages: not redelivered.
 */
@InternalWowApi
fun MessageExchange<*, *>.withholdAcknowledgement(
    withheldBy: String = getFunction()?.let { "${it.processorName}.${it.name}" } ?: "unknown",
) {
    attributes.merge(ACKNOWLEDGEMENT_WITHHELD_KEY, withheldBy) { previous, added ->
        if (previous.toString().split(", ").contains(added.toString())) previous else "$previous, $added"
    }
}

/**
 * Whether [withholdAcknowledgement] was called on this exchange.
 */
@InternalWowApi
fun MessageExchange<*, *>.isAcknowledgementWithheld(): Boolean =
    attributes.containsKey(ACKNOWLEDGEMENT_WITHHELD_KEY)

/**
 * Who withheld the acknowledgement of this exchange ([withholdAcknowledgement]), or `null`.
 */
@InternalWowApi
fun MessageExchange<*, *>.acknowledgementWithheldBy(): String? =
    attributes[ACKNOWLEDGEMENT_WITHHELD_KEY]?.toString()

/**
 * Utilities for acknowledging message exchanges.
 *
 * Provides extension functions to ensure messages are acknowledged
 * regardless of processing success or failure.
 */
object ExchangeAck {
    private val log = KotlinLogging.logger {}

    private fun MessageExchange<*, *>.acknowledgeDefer(): Mono<Void> =
        Mono.defer {
            if (isAcknowledgementWithheld()) {
                log.error {
                    "Leave message[${message.id}] unacknowledged: its acknowledgement was withheld by " +
                        "[${acknowledgementWithheldBy()}]."
                }
                Mono.empty()
            } else {
                acknowledge()
            }
        }

    /**
     * Ensures the exchange is acknowledged after Mono completion, even on error.
     *
     * If the Mono fails, acknowledges first, then re-throws the error.
     * If successful, acknowledges after completion.
     * An exchange whose acknowledgement is withheld ([withholdAcknowledgement]) is left unacknowledged.
     *
     * @param exchange The exchange to acknowledge
     * @return A Mono that acknowledges the exchange
     */
    fun Mono<*>.finallyAck(exchange: MessageExchange<*, *>): Mono<Void> =
        onErrorResume {
            exchange.acknowledgeDefer()
                .then(Mono.error(it))
        }.then(exchange.acknowledgeDefer())

    /**
     * Ensures the exchange is acknowledged after Flux completion, even on error.
     *
     * If the Flux fails, acknowledges first, then re-throws the error.
     * If successful, acknowledges after completion.
     * An exchange whose acknowledgement is withheld ([withholdAcknowledgement]) is left unacknowledged.
     *
     * @param exchange The exchange to acknowledge
     * @return A Mono that acknowledges the exchange
     */
    fun Flux<*>.finallyAck(exchange: MessageExchange<*, *>): Mono<Void> =
        onErrorResume {
            exchange.acknowledgeDefer()
                .then(Mono.error(it))
        }.then(exchange.acknowledgeDefer())

    /**
     * Filters the flux and acknowledges exchanges that don't match the predicate.
     *
     * For each exchange, if it matches the predicate, it passes through.
     * If it doesn't match, it's acknowledged and filtered out.
     *
     * @param T The message exchange type.
     * @param predicate The predicate to test exchanges against
     * @return A flux containing only exchanges that match the predicate
     */
    inline fun <T : MessageExchange<*, *>> Flux<T>.filterThenAck(crossinline predicate: (T) -> Boolean): Flux<T> =
        filterWhen {
            val matched = predicate(it)
            if (matched) {
                Mono.just(true)
            } else {
                it.acknowledge()
                    .thenReturn(false)
                    .doFinally { _ -> it.rejectLocalDelivery() }
            }
        }
}
