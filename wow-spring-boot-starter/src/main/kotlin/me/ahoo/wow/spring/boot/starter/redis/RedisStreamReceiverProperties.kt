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

import me.ahoo.wow.messaging.transport.TransportFailurePolicy
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Receive-side policy of the Redis Streams buses: consecutive receive failures are retried [retryAttempts] times
 * with an exponential backoff from [retryBackoff] (the same defaults as `wow.kafka.receiver.retry-*`).
 */
@ConfigurationProperties(prefix = RedisStreamReceiverProperties.PREFIX)
class RedisStreamReceiverProperties(
    var retryAttempts: Long = TransportFailurePolicy.DEFAULT_RECEIVE_RETRY_ATTEMPTS,
    var retryBackoff: Duration = TransportFailurePolicy.DEFAULT_RECEIVE_RETRY_BACKOFF,
) {
    fun toFailurePolicy(): TransportFailurePolicy =
        TransportFailurePolicy(
            TransportFailurePolicy.receiveRetry(maxAttempts = retryAttempts, minBackoff = retryBackoff)
        )

    companion object {
        const val PREFIX = "${RedisProperties.PREFIX}.message-bus.receiver"
    }
}
