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
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.core.ReactiveStreamOperations
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.ReactiveValueOperations
import org.springframework.data.redis.core.script.RedisScript
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration

class RedisConsumerLeaseRegistryTest {
    private val redisTemplate = mockk<ReactiveStringRedisTemplate>()
    private val streamOps = mockk<ReactiveStreamOperations<String, String, String>>()

    init {
        every { redisTemplate.opsForStream<String, String>() } returns streamOps
    }

    @Test
    fun `should return immediately for an empty consumer set`() {
        val registry = DefaultRedisConsumerLeaseRegistry(redisTemplate, recoveryOptions())

        StepVerifier.create(registry.findActiveConsumers("topic", "group", emptySet()))
            .expectNext(emptySet())
            .verifyComplete()
    }

    @Test
    fun `should fail closed when consumer liveness lookup fails`() {
        every { redisTemplate.hasKey(any()) } returns Mono.error(IllegalStateException("Redis unavailable"))
        every { streamOps.consumers("topic", "group") } returns Flux.empty()
        val registry = DefaultRedisConsumerLeaseRegistry(redisTemplate, recoveryOptions())

        StepVerifier.create(
            registry.findActiveConsumers("topic", "group", setOf("consumer-1", "consumer-2")),
        )
            .expectNext(setOf("consumer-1", "consumer-2"))
            .verifyComplete()
    }

    @Test
    fun `should continue live delivery when lease acquisition fails`() {
        val valueOps = mockk<ReactiveValueOperations<String, String>>()
        every { redisTemplate.opsForValue() } returns valueOps
        every {
            valueOps.set(any(), any(), any<Duration>())
        } returns Mono.just(false)
        val registry = DefaultRedisConsumerLeaseRegistry(redisTemplate, recoveryOptions())

        StepVerifier.create(
            registry.withLease(
                topic = "topic",
                consumer = Consumer.from("group", "consumer"),
                source = Flux.just("message"),
            ),
        )
            .expectNext("message")
            .verifyComplete()
    }

    @Test
    fun `should continue live delivery when lease renewal fails`() {
        val valueOps = mockk<ReactiveValueOperations<String, String>>()
        every { redisTemplate.opsForValue() } returns valueOps
        every {
            valueOps.set(any(), any(), any<Duration>())
        } returns Mono.just(true)
        every {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                any<List<String>>(),
                any<List<*>>(),
            )
        } returns Flux.just(0L)
        val registry = DefaultRedisConsumerLeaseRegistry(redisTemplate, recoveryOptions())

        StepVerifier.withVirtualTime {
            registry.withLease(
                topic = "topic",
                consumer = Consumer.from("group", "consumer"),
                source = Flux.never<String>(),
            ).takeUntilOther(Mono.delay(Duration.ofSeconds(2)))
        }
            .expectSubscription()
            .expectNoEvent(Duration.ofSeconds(2))
            .thenAwait(Duration.ofMillis(1))
            .verifyComplete()
    }

    private fun recoveryOptions(): RedisStreamRecoveryOptions {
        return RedisStreamRecoveryOptions(
            minIdleTime = Duration.ofSeconds(1),
            interval = Duration.ofSeconds(1),
        )
    }
}
