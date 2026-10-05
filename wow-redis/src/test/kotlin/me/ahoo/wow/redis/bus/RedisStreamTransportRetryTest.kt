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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.messaging.transport.TransportFailurePolicy
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.core.ReactiveStreamOperations
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import reactor.core.Exceptions
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import reactor.util.retry.Retry
import java.util.concurrent.atomic.AtomicInteger

class RedisStreamTransportRetryTest {

    @Test
    fun `a failed receive stream is retried and fails readiness only once the policy is exhausted`() {
        val attempts = AtomicInteger()
        val streamOps = mockk<ReactiveStreamOperations<String, String, String>> {
            every { createGroup("topic", ReadOffset.latest(), "group") } returns Mono.defer {
                attempts.incrementAndGet()
                Mono.error(IllegalStateException("redis down"))
            }
        }
        val redisTemplate = mockk<ReactiveStringRedisTemplate> {
            every { opsForStream<String, String>() } returns streamOps
            every { connectionFactory } returns mockk<ReactiveRedisConnectionFactory>(relaxed = true)
        }
        val transport = RedisStreamTransport(
            redisTemplate = redisTemplate,
            recoveryOptions = RedisStreamRecoveryOptions.DISABLED,
            retentionOptions = RedisStreamRetentionOptions(consumerIdleTimeout = null),
            failurePolicy = TransportFailurePolicy(Retry.max(2)),
        )
        val receiver = transport.open("group", setOf("topic"))

        receiver.records.test()
            .expectErrorSatisfies {
                Exceptions.isRetryExhausted(it).assert().isTrue()
                it.cause!!.message.assert().isEqualTo("redis down")
            }
            .verify()

        attempts.get().assert().isEqualTo(3)
        receiver.readiness.test().expectError().verify()
    }
}
