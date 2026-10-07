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

import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A single message source with separate readiness and processing admission.
 *
 * `messages` must be subscribed before `readiness`. The readiness signal is
 * hot and replayable: it completes only after the subscribed source can retain
 * messages without loss, or fails when that setup cannot complete. Once every
 * runtime component is ready, `openProcessing` explicitly opens transport
 * consumption. `closeProcessing` revokes that logical admission before
 * physical cancellation, without inferring lifecycle state from reactive
 * demand or subscription count. `suspendDurableIntake` stops pulling new
 * records from a durable transport, which keeps them for redelivery, while the
 * records already pulled keep flowing; a source that is not durable ignores it.
 */
class MessageReceiver<E : Any>(
    messages: Flux<E>,
    val readiness: Mono<Void> = Mono.empty(),
    private val processingAdmission: () -> Unit = {},
    private val processingQuiescence: () -> Unit = {},
    private val durableIntakeSuspension: () -> Unit = {},
) {
    private val subscribed = AtomicBoolean()
    private val processingMonitor = Any()

    private enum class ProcessingState {
        PENDING,
        OPEN,
        CLOSED,
    }

    private var processingState = ProcessingState.PENDING

    val messages: Flux<E> =
        Flux.defer {
            if (subscribed.compareAndSet(false, true)) {
                messages
            } else {
                Flux.error(
                    IllegalStateException("MessageReceiver supports exactly one messages subscriber."),
                )
            }
        }

    /**
     * [messages] with processing opened on subscription: the stream a consumer that is ready at once reads, so a
     * transport that gates consumption on [openProcessing] streams immediately. Like [messages], it supports exactly
     * one subscriber.
     */
    fun openedMessages(): Flux<E> = messages.doOnSubscribe { openProcessing() }

    fun openProcessing() {
        synchronized(processingMonitor) {
            if (processingState != ProcessingState.PENDING) {
                return
            }
            processingState = ProcessingState.OPEN
            processingAdmission()
        }
    }

    /**
     * Revokes processing admission synchronously without waiting for physical
     * source cancellation. The callback must be prompt and idempotent.
     */
    fun closeProcessing() {
        synchronized(processingMonitor) {
            if (processingState == ProcessingState.CLOSED) {
                return
            }
            processingState = ProcessingState.CLOSED
            processingQuiescence()
        }
    }

    /**
     * Stops pulling new records from a durable transport without revoking processing admission or cancelling the
     * source: the records already pulled are still delivered and acknowledged, the rest stay with the broker
     * (uncommitted on Kafka, unread or pending on Redis Streams) for another member. Prompt, non-blocking and
     * idempotent; a source that is not durable, such as an in-memory bus, ignores it.
     */
    fun suspendDurableIntake() {
        durableIntakeSuspension()
    }

    fun <R : Any> mapMessages(transform: (Flux<E>) -> Flux<R>): MessageReceiver<R> =
        MessageReceiver(
            messages = transform(messages),
            readiness = readiness,
            processingAdmission = ::openProcessing,
            processingQuiescence = ::closeProcessing,
            durableIntakeSuspension = ::suspendDurableIntake,
        )
}
