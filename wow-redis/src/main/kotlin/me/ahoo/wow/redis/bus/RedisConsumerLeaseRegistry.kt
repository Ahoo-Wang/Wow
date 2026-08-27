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
import me.ahoo.wow.id.GlobalIdGenerator
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.core.ReactiveStreamOperations
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

internal interface RedisConsumerLeaseRegistry {
    fun <T : Any> withLease(
        topic: String,
        consumer: Consumer,
        source: Flux<T>,
    ): Flux<T>

    fun findActiveConsumers(
        topic: String,
        group: String,
        consumerNames: Set<String>,
    ): Mono<Set<String>>
}

internal class DefaultRedisConsumerLeaseRegistry(
    private val redisTemplate: ReactiveStringRedisTemplate,
    options: RedisStreamRecoveryOptions,
) : RedisConsumerLeaseRegistry {
    private val streamOps: ReactiveStreamOperations<String, String, String> = redisTemplate.opsForStream()
    private val minIdleTime = options.minIdleTime
    private val heartbeatInterval = options.interval
    private val leaseDuration = heartbeatInterval.multipliedBy(3)

    override fun <T : Any> withLease(
        topic: String,
        consumer: Consumer,
        source: Flux<T>,
    ): Flux<T> {
        return acquire(topic, consumer)
            .map<LeaseState>(LeaseState::Acquired)
            .onErrorResume { failure ->
                log.warn(failure) {
                    "Failed to acquire a Redis consumer lease for [$topic/${consumer.group}/${consumer.name}]. " +
                        "Live delivery will continue with Redis consumer activity as the recovery safety net."
                }
                Mono.just(LeaseState.Unavailable)
            }
            .flatMapMany { leaseState ->
                when (leaseState) {
                    is LeaseState.Acquired -> Flux.usingWhen(
                        Mono.just(leaseState.lease),
                        { lease -> source.withHeartbeat(lease) },
                        ::release,
                        { lease, _ -> release(lease) },
                        ::release,
                    )

                    LeaseState.Unavailable -> source
                }
            }
    }

    override fun findActiveConsumers(
        topic: String,
        group: String,
        consumerNames: Set<String>,
    ): Mono<Set<String>> {
        if (consumerNames.isEmpty()) {
            return Mono.just(emptySet())
        }
        val leasedConsumers = Flux.fromIterable(consumerNames)
            .flatMap(
                { consumerName ->
                    redisTemplate.hasKey(leaseKey(topic, group, consumerName))
                        .filter { active -> active }
                        .map { consumerName }
                },
                MAX_LOOKUP_CONCURRENCY,
            )
            .collectList()
            .map { activeConsumers -> activeConsumers.toSet() }
        val recentlyActiveConsumers = streamOps.consumers(topic, group)
            .filter { consumer ->
                consumer.consumerName() in consumerNames && consumer.idleTime() < minIdleTime
            }
            .map { consumer -> consumer.consumerName() }
            .collectList()
            .map { activeConsumers -> activeConsumers.toSet() }
        return Mono.zip(leasedConsumers, recentlyActiveConsumers)
            .map { active -> active.t1 + active.t2 }
            .onErrorResume { failure ->
                log.warn(failure) {
                    "Failed to resolve active Redis Stream consumers for [$topic/$group]. " +
                        "Skipping recovery for [${consumerNames.size}] candidate consumer(s)."
                }
                Mono.just(consumerNames)
            }
    }

    private fun acquire(topic: String, consumer: Consumer): Mono<Lease> {
        val lease = Lease(
            key = leaseKey(topic, consumer.group, consumer.name),
            token = GlobalIdGenerator.generateAsString(),
        )
        return redisTemplate.opsForValue()
            .set(lease.key, lease.token, leaseDuration)
            .flatMap { acquired ->
                if (acquired) {
                    Mono.just(lease)
                } else {
                    Mono.error(IllegalStateException("Failed to acquire Redis consumer lease [${lease.key}]."))
                }
            }
    }

    private fun heartbeat(lease: Lease): Mono<Void> {
        return Flux.interval(heartbeatInterval)
            .concatMap { renew(lease) }
            .then()
            .onErrorResume { failure ->
                log.warn(failure) {
                    "Failed to renew Redis consumer lease [${lease.key}]. Live delivery will continue with " +
                        "Redis consumer activity as the recovery safety net."
                }
                Mono.empty()
            }
    }

    private fun <T : Any> Flux<T>.withHeartbeat(lease: Lease): Flux<T> {
        return publish { shared ->
            Flux.merge(
                shared,
                heartbeat(lease)
                    .takeUntilOther(shared.ignoreElements())
                    .thenMany(Flux.empty()),
            )
        }
    }

    private fun renew(lease: Lease): Mono<Void> {
        return redisTemplate.execute(
            RENEW_SCRIPT,
            listOf(lease.key),
            listOf(lease.token, leaseDuration.toMillis().toString()),
        ).next()
            .flatMap { renewed ->
                if (renewed == SCRIPT_SUCCEEDED) {
                    Mono.empty()
                } else {
                    Mono.error(IllegalStateException("Redis consumer lease [${lease.key}] is no longer owned."))
                }
            }
    }

    private fun release(lease: Lease): Mono<Void> {
        return redisTemplate.execute(
            RELEASE_SCRIPT,
            listOf(lease.key),
            listOf(lease.token),
        ).then()
            .onErrorResume { failure ->
                log.warn(failure) {
                    "Failed to release Redis consumer lease [${lease.key}]; it will expire after [$leaseDuration]."
                }
                Mono.empty()
            }
    }

    private fun leaseKey(topic: String, group: String, consumerName: String): String {
        val identity = "$topic\u0000$group\u0000$consumerName"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(StandardCharsets.UTF_8))
        return "$LEASE_KEY_PREFIX:${HexFormat.of().formatHex(digest)}"
    }

    private data class Lease(
        val key: String,
        val token: String,
    )

    private sealed interface LeaseState {
        data class Acquired(val lease: Lease) : LeaseState
        data object Unavailable : LeaseState
    }

    companion object {
        private const val LEASE_KEY_PREFIX = "wow:redis:message-bus:consumer-lease"
        private const val MAX_LOOKUP_CONCURRENCY = 16
        private const val SCRIPT_SUCCEEDED = 1L
        private val log = KotlinLogging.logger {}

        private val RENEW_SCRIPT = RedisScript.of(
            """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            return 0
            """.trimIndent(),
            Long::class.java,
        )

        private val RELEASE_SCRIPT = RedisScript.of(
            """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """.trimIndent(),
            Long::class.java,
        )
    }
}
