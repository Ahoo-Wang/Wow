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
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.core.ReactiveStreamOperations
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import reactor.core.publisher.Mono
import java.time.Duration

internal data class RedisPendingMessage(
    val id: RecordId,
    val consumerName: String,
    val elapsedTimeSinceLastDelivery: Duration,
    val deliveryCount: Long,
) {
    init {
        require(deliveryCount > 0) {
            "Redis pending-message delivery count must be positive."
        }
    }
}

internal data class RedisPendingMessagePage(
    val sweepEnd: String?,
    val messages: List<RedisPendingMessage>,
    val complete: Boolean,
)

internal data class RedisPendingScanRequest(
    val cursor: String?,
    val sweepEnd: String?,
    val count: Long,
)

internal fun interface RedisPendingMessageScanner {
    fun scan(
        topic: String,
        group: String,
        request: RedisPendingScanRequest,
    ): Mono<RedisPendingMessagePage>
}

/**
 * Uses a small Lua wrapper because the bounded reactive XPENDING path encodes range bounds
 * as ByteBuffer values while Lettuce expects String stream IDs.
 */
@Suppress("UNCHECKED_CAST")
internal class DefaultRedisPendingMessageScanner(
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val streamOps: ReactiveStreamOperations<String, String, String>,
    private val observer: RedisMessageBusObserver = RedisMessageBusObserver.NOOP,
) : RedisPendingMessageScanner {
    override fun scan(
        topic: String,
        group: String,
        request: RedisPendingScanRequest,
    ): Mono<RedisPendingMessagePage> {
        val sweepEndPublisher = request.sweepEnd?.let(Mono<String>::just) ?: pendingSweepEnd(topic, group)
        return sweepEndPublisher.flatMap { sweepEnd ->
            redisTemplate.execute(
                PENDING_SCRIPT,
                listOf(topic),
                listOf(
                    group,
                    request.cursor?.let { "($it" } ?: "-",
                    sweepEnd,
                    request.count.toString(),
                ),
            ).next()
                .defaultIfEmpty(emptyList())
                .map { flattened -> flattened.toPage(sweepEnd, request.count) }
        }.switchIfEmpty(
            Mono.just(RedisPendingMessagePage(sweepEnd = null, messages = emptyList(), complete = true)),
        )
    }

    private fun pendingSweepEnd(topic: String, group: String): Mono<String> {
        return streamOps.pending(topic, group)
            .doOnNext { summary ->
                val hasPendingMessages = summary.totalPendingMessages > 0
                val observation = RedisMessageBusObservation.PendingSweepStarted(
                    topic = topic,
                    consumerGroup = group,
                    totalPendingMessages = summary.totalPendingMessages,
                    minMessageId = if (hasPendingMessages) summary.minMessageId() else null,
                    maxMessageId = if (hasPendingMessages) summary.maxMessageId() else null,
                )
                observer.notifySafely(observation)
                if (hasPendingMessages) {
                    log.debug {
                        "Redis Stream pending-message sweep started for topic [$topic] and consumer group [$group]: " +
                            "total [${summary.totalPendingMessages}], range " +
                            "[${observation.minMessageId}..${observation.maxMessageId}]."
                    }
                }
            }
            .filter { summary -> summary.totalPendingMessages > 0 }
            .map { summary -> requireNotNull(summary.maxMessageId()) }
    }

    private fun List<Any>.toPage(sweepEnd: String, count: Long): RedisPendingMessagePage {
        require(size % FIELDS_PER_MESSAGE == 0) {
            "Unexpected XPENDING response size: $size."
        }
        val messages = chunked(FIELDS_PER_MESSAGE).map { fields ->
            RedisPendingMessage(
                id = RecordId.of(fields[0].toString()),
                consumerName = fields[1].toString(),
                elapsedTimeSinceLastDelivery = Duration.ofMillis(fields[2].asLong()),
                deliveryCount = fields[3].asLong(),
            )
        }
        val complete = messages.size.toLong() < count || messages.lastOrNull()?.id?.value == sweepEnd
        return RedisPendingMessagePage(
            sweepEnd = sweepEnd,
            messages = messages,
            complete = complete,
        )
    }

    private fun Any.asLong(): Long {
        return when (this) {
            is Number -> toLong()
            else -> toString().toLong()
        }
    }

    companion object {
        private const val FIELDS_PER_MESSAGE = 4
        private val log = KotlinLogging.logger {}

        private val PENDING_SCRIPT: RedisScript<List<Any>> = RedisScript.of(
            """
            local rows = redis.call('XPENDING', KEYS[1], ARGV[1], ARGV[2], ARGV[3], ARGV[4])
            local flattened = {}
            for _, row in ipairs(rows) do
                flattened[#flattened + 1] = row[1]
                flattened[#flattened + 1] = row[2]
                flattened[#flattened + 1] = row[3]
                flattened[#flattened + 1] = row[4]
            end
            return flattened
            """.trimIndent(),
            List::class.java as Class<List<Any>>,
        )
    }
}
