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
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import java.time.Duration

class RedisStreamRecoveryOptionsTest {
    @Test
    fun `should enable pending-message recovery by default`() {
        RedisStreamRecoveryOptions.DEFAULT.assert().isEqualTo(RedisStreamRecoveryOptions())
        RedisStreamRecoveryOptions.DEFAULT.enabled.assert().isTrue()
        RedisStreamRecoveryOptions.DISABLED.enabled.assert().isFalse()
    }

    @Test
    fun `should enable recovery for directly constructed message buses`() {
        val redisTemplate = mockk<ReactiveStringRedisTemplate> {
            every { opsForStream<String, String>() } returns mockk()
        }
        val commandBus = RedisCommandBus(redisTemplate)
        val recoveryOptionsField = AbstractRedisMessageBus::class.java
            .getDeclaredField("recoveryOptions")
            .apply { isAccessible = true }

        val recoveryOptions = recoveryOptionsField.get(commandBus) as RedisStreamRecoveryOptions

        recoveryOptions.enabled.assert().isTrue()
        RedisDomainEventBus(redisTemplate, RedisStreamRecoveryOptions.DISABLED)
            .assert()
            .isInstanceOf(RedisDomainEventBus::class.java)
        RedisStateEventBus(redisTemplate, RedisStreamRecoveryOptions.DISABLED)
            .assert()
            .isInstanceOf(RedisStateEventBus::class.java)
    }

    @Test
    fun `should reject unsupported recovery bounds`() {
        runCatching {
            RedisStreamRecoveryOptions(minIdleTime = Duration.ofNanos(1))
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        runCatching {
            RedisStreamRecoveryOptions(interval = Duration.ZERO)
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
        runCatching {
            RedisStreamRecoveryOptions(batchSize = 0)
        }.exceptionOrNull().assert().isInstanceOf(IllegalArgumentException::class.java)
    }
}
