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
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * Deletes the consumers of a stream's group that have no pending entries and have been idle for at least the idle
 * timeout. The check and the delete run in one script, so a consumer that reads an entry in between is not deleted
 * with it pending. Best effort: a failure is logged and the receiver starts anyway.
 */
internal class RedisStreamConsumerReaper(
    private val redisTemplate: ReactiveStringRedisTemplate,
) {
    /** Emits how many consumers were deleted. */
    fun reap(topic: String, group: String, idleTimeout: Duration): Mono<Long> =
        redisTemplate.execute(REAP_SCRIPT, listOf(topic), listOf(group, idleTimeout.toMillis().toString()))
            .next()
            .defaultIfEmpty(0L)
            .doOnNext { reaped ->
                if (reaped > 0) {
                    log.info {
                        "Deleted [$reaped] Redis Stream consumer(s) of [$topic/$group] idle for at least " +
                            "[$idleTimeout] with no pending entries."
                    }
                }
            }
            .onErrorResume { failure ->
                log.warn(failure) {
                    "Failed to delete idle Redis Stream consumers of [$topic/$group]; they are kept."
                }
                Mono.just(0L)
            }

    private companion object {
        private val log = KotlinLogging.logger {}

        /** KEYS[1] stream; ARGV[1] group, ARGV[2] idle timeout in ms. XINFO CONSUMERS replies with field pairs. */
        private val REAP_SCRIPT = RedisScript.of(
            """
            local consumers = redis.call('XINFO', 'CONSUMERS', KEYS[1], ARGV[1])
            local idleTimeout = tonumber(ARGV[2])
            local reaped = 0
            for _, consumer in ipairs(consumers) do
                local fields = {}
                for i = 1, #consumer, 2 do
                    fields[consumer[i]] = consumer[i + 1]
                end
                if tonumber(fields['pending']) == 0 and tonumber(fields['idle']) >= idleTimeout then
                    redis.call('XGROUP', 'DELCONSUMER', KEYS[1], ARGV[1], fields['name'])
                    reaped = reaped + 1
                end
            end
            return reaped
            """.trimIndent(),
            Long::class.java,
        )
    }
}
