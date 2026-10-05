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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.redis.connection.RedisStreamCommands.MaxLenTrimStrategy
import org.springframework.data.redis.connection.RedisStreamCommands.MinIdTrimStrategy
import org.springframework.data.redis.connection.RedisStreamCommands.TrimOperator
import org.springframework.data.redis.connection.stream.RecordId
import java.time.Duration

class RedisStreamRetentionOptionsTest {
    @Test
    fun `the default trims nothing and reaps consumers idle for thirty minutes`() {
        val options = RedisStreamRetentionOptions.DEFAULT

        options.trims.assert().isFalse()
        options.addOptions(1_000).hasTrimOptions().assert().isFalse()
        options.consumerIdleTimeout.assert().isEqualTo(Duration.ofMinutes(30))
    }

    @Test
    fun `max length trims approximately by default`() {
        val trim = RedisStreamRetentionOptions(maxLength = 10).addOptions(1_000).trimOptions!!

        trim.trimOperator.assert().isEqualTo(TrimOperator.APPROXIMATE)
        (trim.trimStrategy as MaxLenTrimStrategy).threshold().assert().isEqualTo(10L)
    }

    @Test
    fun `max age trims entries older than now minus the age`() {
        val trim = RedisStreamRetentionOptions(maxAge = Duration.ofSeconds(1), approximate = false)
            .addOptions(5_000)
            .trimOptions!!

        trim.trimOperator.assert().isEqualTo(TrimOperator.EXACT)
        (trim.trimStrategy as MinIdTrimStrategy).threshold().assert().isEqualTo(RecordId.of(4_000, 0))
    }

    @Test
    fun `invalid options are rejected`() {
        assertThrows<IllegalArgumentException> { RedisStreamRetentionOptions(maxLength = 0) }
        assertThrows<IllegalArgumentException> { RedisStreamRetentionOptions(maxAge = Duration.ZERO) }
        assertThrows<IllegalArgumentException> {
            RedisStreamRetentionOptions(maxLength = 10, maxAge = Duration.ofMinutes(1))
        }
        assertThrows<IllegalArgumentException> { RedisStreamRetentionOptions(consumerIdleTimeout = Duration.ZERO) }
    }
}
