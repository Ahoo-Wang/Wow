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

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.stream.PendingMessagesSummary
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.core.ReactiveStreamOperations
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration

class RedisPendingMessageScannerTest {
    @Test
    fun `should reject a non-positive pending delivery count`() {
        assertThrows<IllegalArgumentException> {
            RedisPendingMessage(
                id = RecordId.of("1-0"),
                consumerName = "consumer",
                elapsedTimeSinceLastDelivery = Duration.ZERO,
                deliveryCount = 0,
            )
        }
    }

    @Test
    fun `should observe an empty pending snapshot`() {
        val topic = "topic"
        val group = "group"
        val streamOps = mockk<ReactiveStreamOperations<String, String, String>>()
        every { streamOps.pending(topic, group) } returns Mono.just(
            PendingMessagesSummary(group, 0, Range.unbounded(), emptyMap()),
        )
        val observations = mutableListOf<RedisMessageBusObservation>()
        val scanner = DefaultRedisPendingMessageScanner(
            redisTemplate = mockk<ReactiveStringRedisTemplate>(),
            streamOps = streamOps,
            observer = RedisMessageBusObserver(observations::add),
        )

        StepVerifier.create(
            scanner.scan(topic, group, RedisPendingScanRequest(cursor = null, sweepEnd = null, count = 10)),
        )
            .assertNext { page ->
                page.complete.assert().isTrue()
                page.sweepEnd.assert().isNull()
                page.messages.assert().isEmpty()
            }
            .verifyComplete()

        observations.assert().containsExactly(
            RedisMessageBusObservation.PendingSweepStarted(
                topic = topic,
                consumerGroup = group,
                totalPendingMessages = 0,
                minMessageId = null,
                maxMessageId = null,
            ),
        )
    }

    @Test
    @Suppress("LongMethod") // Fixed-snapshot pagination and cursor assertions form one scenario.
    fun `should scan a fixed pending snapshot with an exclusive cursor`() {
        val topic = "topic"
        val group = "group"
        val streamOps = mockk<ReactiveStreamOperations<String, String, String>>()
        every { streamOps.pending(topic, group) } returns Mono.just(
            PendingMessagesSummary(
                group,
                3,
                Range.closed("1-0", "3-0"),
                mapOf("old" to 3),
            ),
        )
        val redisTemplate = mockk<ReactiveStringRedisTemplate>()
        val arguments = mutableListOf<List<*>>()
        every {
            redisTemplate.execute(
                any<RedisScript<List<Any>>>(),
                listOf(topic),
                capture(arguments),
            )
        } returnsMany listOf(
            Flux.just(
                listOf(
                    "1-0",
                    "old",
                    "1500",
                    "2",
                    "2-0",
                    "old",
                    2000L,
                    3L,
                ),
            ),
            Flux.just(listOf("3-0", "old", 2500L, 4L)),
        )
        val observations = mutableListOf<RedisMessageBusObservation>()
        val scanner = DefaultRedisPendingMessageScanner(
            redisTemplate = redisTemplate,
            streamOps = streamOps,
            observer = RedisMessageBusObserver(observations::add),
        )

        val firstPage = scanner.scan(
            topic,
            group,
            RedisPendingScanRequest(cursor = null, sweepEnd = null, count = 2),
        ).block()!!
        firstPage.complete.assert().isFalse()
        firstPage.sweepEnd.assert().isEqualTo("3-0")
        firstPage.messages.map { pending -> pending.id.value }
            .assert()
            .containsExactly("1-0", "2-0")
        firstPage.messages.map(RedisPendingMessage::deliveryCount)
            .assert()
            .containsExactly(2, 3)

        val secondPage = scanner.scan(
            topic,
            group,
            RedisPendingScanRequest(cursor = "2-0", sweepEnd = "3-0", count = 2),
        ).block()!!
        secondPage.complete.assert().isTrue()
        secondPage.messages.single().elapsedTimeSinceLastDelivery
            .assert()
            .isEqualTo(Duration.ofMillis(2500))

        arguments.assert().containsExactly(
            listOf(group, "-", "3-0", "2"),
            listOf(group, "(2-0", "3-0", "2"),
        )
        observations.single().assert().isEqualTo(
            RedisMessageBusObservation.PendingSweepStarted(
                topic = topic,
                consumerGroup = group,
                totalPendingMessages = 3,
                minMessageId = "1-0",
                maxMessageId = "3-0",
            ),
        )
        verify(exactly = 1) { streamOps.pending(topic, group) }
    }

    @Test
    fun `should reject a malformed pending response`() {
        val redisTemplate = mockk<ReactiveStringRedisTemplate>()
        every {
            redisTemplate.execute(
                any<RedisScript<List<Any>>>(),
                listOf("topic"),
                any<List<*>>(),
            )
        } returns Flux.just(listOf("1-0"))
        val scanner = DefaultRedisPendingMessageScanner(
            redisTemplate = redisTemplate,
            streamOps = mockk(),
        )

        StepVerifier.create(
            scanner.scan(
                "topic",
                "group",
                RedisPendingScanRequest(cursor = null, sweepEnd = "1-0", count = 1),
            ),
        )
            .expectError(IllegalArgumentException::class.java)
            .verify()
    }
}
