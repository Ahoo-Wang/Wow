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

package me.ahoo.wow.spring.boot.starter.redis

import me.ahoo.wow.redis.bus.RedisStreamRetentionOptions
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * What the Redis stream bus keeps ([RedisStreamRetentionOptions]). Trimming is off unless [maxLength] or [maxAge]
 * is set; idle consumers with nothing pending are deleted unless [reapIdleConsumers] is `false`.
 */
@ConfigurationProperties(prefix = RedisStreamRetentionProperties.PREFIX)
class RedisStreamRetentionProperties(
    /** Trim each stream to about this many entries on every send (`MAXLEN ~`). */
    var maxLength: Long? = null,
    /**
     * Trim entries older than this on every send (`MINID ~`, from the sender's clock): at least one minute, and far
     * larger than the clock skew between nodes. Trimming needs Redis 7.0 or later.
     */
    var maxAge: Duration? = null,
    /** Trim whole macro nodes (`~`), which is much cheaper than exact trimming. */
    var approximate: Boolean = true,
    var reapIdleConsumers: Boolean = true,
    var consumerIdleTimeout: Duration = RedisStreamRetentionOptions.DEFAULT_CONSUMER_IDLE_TIMEOUT,
) {
    fun toOptions(): RedisStreamRetentionOptions {
        return RedisStreamRetentionOptions(
            maxLength = maxLength,
            maxAge = maxAge,
            approximate = approximate,
            consumerIdleTimeout = consumerIdleTimeout.takeIf { reapIdleConsumers },
        )
    }

    companion object {
        const val PREFIX = "${RedisProperties.PREFIX}.message-bus.retention"
    }
}
