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

package me.ahoo.wow.redis.bus

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.core.ReactiveStreamOperations
import reactor.core.publisher.Flux
import java.util.concurrent.atomic.AtomicLong

internal class RedisPendingMessageRecoverer(
    private val streamOps: ReactiveStreamOperations<String, String, String>,
    private val scanner: RedisPendingMessageScanner,
    private val leaseRegistry: RedisConsumerLeaseRegistry,
    private val options: RedisStreamRecoveryOptions,
    private val observer: RedisMessageBusObserver = RedisMessageBusObserver.NOOP,
) {
    fun recover(
        topic: String,
        consumer: Consumer,
    ): Flux<MapRecord<String, String, String>> {
        if (!options.enabled) {
            return Flux.empty()
        }
        return Flux.defer {
            recoverSweep(
                topic = topic,
                consumer = consumer,
                request = RedisPendingScanRequest(
                    cursor = null,
                    sweepEnd = null,
                    count = options.batchSize,
                ),
            ).onErrorResume { failure ->
                log.warn(failure) {
                    "Redis Stream pending-message recovery failed for topic [$topic] and consumer group " +
                        "[${consumer.group}]; live delivery remains active and recovery will retry after " +
                        "[${options.interval}]."
                }
                Flux.empty()
            }
        }.repeatWhen { completedSweeps ->
            completedSweeps.delayElements(options.interval)
        }
    }

    private fun recoverSweep(
        topic: String,
        consumer: Consumer,
        request: RedisPendingScanRequest,
    ): Flux<MapRecord<String, String, String>> {
        return scanner.scan(topic, consumer.group, request)
            .doOnError { failure ->
                observer.notifySafely(
                    RedisMessageBusObservation.PendingScanFailed(
                        topic = topic,
                        consumerGroup = consumer.group,
                        failureType = failure.javaClass.name,
                    ),
                )
            }
            .flatMapMany { page ->
                val nextPage = if (page.complete) {
                    Flux.empty()
                } else {
                    Flux.defer {
                        recoverSweep(topic, consumer, page.nextRequest())
                    }
                }
                recoverPage(topic, consumer, page).concatWith(nextPage)
            }
    }

    private fun recoverPage(
        topic: String,
        consumer: Consumer,
        page: RedisPendingMessagePage,
    ): Flux<MapRecord<String, String, String>> {
        val candidates = page.messages.asSequence()
            .filter { pending -> pending.consumerName != consumer.name }
            .filter { pending -> pending.elapsedTimeSinceLastDelivery >= options.minIdleTime }
            .toList()
        if (candidates.isEmpty()) {
            return Flux.empty()
        }
        return leaseRegistry.findActiveConsumers(
            topic = topic,
            group = consumer.group,
            consumerNames = candidates.mapTo(mutableSetOf()) { pending -> pending.consumerName },
        ).flatMapMany { activeConsumers ->
            val claimableMessages = candidates.filterNot { pending ->
                pending.consumerName in activeConsumers
            }
            if (claimableMessages.isEmpty()) {
                Flux.empty()
            } else {
                claim(topic, consumer, claimableMessages)
            }
        }
    }

    private fun RedisPendingMessagePage.nextRequest(): RedisPendingScanRequest {
        return RedisPendingScanRequest(
            cursor = requireNotNull(messages.lastOrNull()).id.value,
            sweepEnd = requireNotNull(sweepEnd),
            count = options.batchSize,
        )
    }

    @Suppress("SpreadOperator") // Spring Data Redis exposes claimed record identifiers as varargs.
    private fun claim(
        topic: String,
        consumer: Consumer,
        pendingMessages: List<RedisPendingMessage>,
    ): Flux<MapRecord<String, String, String>> {
        val recordIds = pendingMessages.map { pending -> pending.id }.toTypedArray()
        val minDeliveryCount = pendingMessages.minOf { pending -> pending.deliveryCount }
        val maxDeliveryCount = pendingMessages.maxOf { pending -> pending.deliveryCount }
        log.debug {
            "Attempting to recover [${recordIds.size}] pending message(s) from Redis Stream [$topic] " +
                "for consumer group [${consumer.group}] as consumer [${consumer.name}]; observed delivery count " +
                "[$minDeliveryCount..$maxDeliveryCount] at XPENDING scan time."
        }
        val claimedCount = AtomicLong()
        // The response is bounded by recordIds/options.batchSize. Materialize the complete XCLAIM
        // response so downstream cancellation cannot hide the final claim outcome.
        return streamOps.claim(
            topic,
            consumer.group,
            consumer.name,
            options.minIdleTime,
            *recordIds,
        ).doOnNext {
            claimedCount.incrementAndGet()
        }.collectList()
            .doOnSuccess {
                log.info {
                    "Redis Stream pending-message recovery completed for topic [$topic] and consumer group " +
                        "[${consumer.group}]: requested [${recordIds.size}], claimed [${claimedCount.get()}], " +
                        "observed delivery count [$minDeliveryCount..$maxDeliveryCount] at XPENDING scan time."
                }
                observer.notifySafely(
                    RedisMessageBusObservation.PendingClaimCompleted(
                        topic = topic,
                        consumerGroup = consumer.group,
                        requestedMessages = recordIds.size.toLong(),
                        claimedMessages = claimedCount.get(),
                    ),
                )
            }
            .doOnError { failure ->
                observer.notifySafely(
                    RedisMessageBusObservation.PendingClaimFailed(
                        topic = topic,
                        consumerGroup = consumer.group,
                        requestedMessages = recordIds.size.toLong(),
                        claimedMessages = claimedCount.get(),
                        failureType = failure.javaClass.name,
                    ),
                )
            }
            .flatMapMany { claimedRecords -> Flux.fromIterable(claimedRecords) }
    }

    companion object {
        private val log = KotlinLogging.logger {}
    }
}
