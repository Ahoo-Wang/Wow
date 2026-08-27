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

/**
 * Receives synchronous Redis message-bus recovery and decode observations.
 *
 * Implementations must be non-blocking. Callback failures are isolated from message delivery.
 * Metrics adapters should use the observation type, outcome, or [RedisRecordDecodeFailureReason]
 * as low-cardinality dimensions instead of topic, consumer-group, record, or message-type values.
 */
fun interface RedisMessageBusObserver {
    fun onObservation(observation: RedisMessageBusObservation)

    companion object {
        @JvmField
        val NOOP: RedisMessageBusObserver = RedisMessageBusObserver { }
    }
}

/**
 * Message payloads and [Throwable] instances are deliberately excluded so observers cannot retain
 * sensitive payloads or failure object graphs.
 */
sealed interface RedisMessageBusObservation {
    val topic: String
    val consumerGroup: String

    data class PendingSweepStarted(
        override val topic: String,
        override val consumerGroup: String,
        val totalPendingMessages: Long,
        val minMessageId: String?,
        val maxMessageId: String?,
    ) : RedisMessageBusObservation

    data class PendingScanFailed(
        override val topic: String,
        override val consumerGroup: String,
        val failureType: String,
    ) : RedisMessageBusObservation

    data class PendingClaimCompleted(
        override val topic: String,
        override val consumerGroup: String,
        val requestedMessages: Long,
        val claimedMessages: Long,
    ) : RedisMessageBusObservation

    data class PendingClaimFailed(
        override val topic: String,
        override val consumerGroup: String,
        val requestedMessages: Long,
        val claimedMessages: Long,
        val failureType: String,
    ) : RedisMessageBusObservation

    data class RecordDecodeFailed(
        override val topic: String,
        override val consumerGroup: String,
        val recordId: String,
        val messageType: String,
        val reason: RedisRecordDecodeFailureReason,
        val failureType: String?,
    ) : RedisMessageBusObservation
}

enum class RedisRecordDecodeFailureReason {
    MISSING_MESSAGE_FIELD,
    DESERIALIZATION_FAILED,
}

/**
 * Dispatches each observation to an immutable observer snapshot in iteration order.
 */
class CompositeRedisMessageBusObserver(
    observers: Iterable<RedisMessageBusObserver>,
) : RedisMessageBusObserver {
    private val observers = observers.toList()

    override fun onObservation(observation: RedisMessageBusObservation) {
        observers.forEach { observer -> observer.notifySafely(observation) }
    }
}

@Suppress("TooGenericExceptionCaught") // Observer failures must remain outside the delivery chain.
internal fun RedisMessageBusObserver.notifySafely(observation: RedisMessageBusObservation) {
    try {
        onObservation(observation)
    } catch (failure: Exception) {
        observerLog.warn {
            "Redis message-bus observer [${javaClass.name}] failed while processing " +
                "observation [${observation.javaClass.name}] with failure type [${failure.javaClass.name}]."
        }
    }
}

private val observerLog = KotlinLogging.logger {}
