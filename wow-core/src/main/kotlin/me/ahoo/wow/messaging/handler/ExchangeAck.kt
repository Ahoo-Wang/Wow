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
 * Set on a per-function event exchange, it applies to the event stream exchange that function belongs to.
 * Whether the message is delivered again is the bus's behaviour: Redis Streams re-claims pending entries, Kafka
 * stops committing at the offset until the partition is reassigned, and in-memory and locally handled
 * messages are not redelivered.
 */
@InternalWowApi
fun MessageExchange<*, *>.withholdAcknowledgement() {
    attributes[ACKNOWLEDGEMENT_WITHHELD_KEY] = true
}

/**
 * Whether [withholdAcknowledgement] was called on this exchange.
 */
@InternalWowApi
fun MessageExchange<*, *>.isAcknowledgementWithheld(): Boolean =
    attributes[ACKNOWLEDGEMENT_WITHHELD_KEY] == true

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
                log.warn {
                    "Leave message[${message.id}] unacknowledged: its acknowledgement was withheld."
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
