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

import me.ahoo.wow.redis.bus.RedisStreamRecoveryOptions
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import java.time.Duration

@ConfigurationProperties(prefix = RedisStreamRecoveryProperties.PREFIX)
class RedisStreamRecoveryProperties(
    @DefaultValue("true") var enabled: Boolean = true,
    @DefaultValue("5m") var minIdleTime: Duration = Duration.ofMinutes(5),
    @DefaultValue("30s") var interval: Duration = Duration.ofSeconds(30),
    @DefaultValue("100") var batchSize: Long = 100,
) {
    fun toOptions(): RedisStreamRecoveryOptions {
        return RedisStreamRecoveryOptions(
            enabled = enabled,
            minIdleTime = minIdleTime,
            interval = interval,
            batchSize = batchSize,
        )
    }

    companion object {
        const val PREFIX = "${RedisProperties.PREFIX}.message-bus.recovery"
    }
}
