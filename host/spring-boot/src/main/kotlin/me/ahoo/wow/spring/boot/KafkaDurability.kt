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

package me.ahoo.wow.spring.boot

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.wow.kafka.TopicDurability
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Whether the Kafka topics carry decision D143's promise, "accepted is durable" (review A3-15): a command topic must
 * keep at least two replicas and acknowledge a write only once two have it ([TopicDurability.survivesOneBroker]),
 * else an acknowledged command is lost with one broker. A deployment with a single broker runs a dev profile
 * ([KafkaProperties.Topics.devProfiles]), which turns the warnings off.
 */
internal class KafkaDurability(private val topics: KafkaProperties.Topics, profiles: Set<String>) {
    private val dev: Boolean = profiles.any { it in topics.devProfiles }

    /** The warnings about the configuration `auto` creates topics with: none in a dev profile. */
    fun configured(): List<String> {
        if (dev) {
            return emptyList()
        }
        val factor = topics.replicationFactor?.toInt()
        val minInsync = topics.minInsyncReplicas ?: topics.configs[MIN_INSYNC]?.toIntOrNull()
        val warnings = mutableListOf<String>()
        if (factor != null && factor < TopicDurability.DURABLE) {
            warnings += "wow.kafka.topics.replication-factor is $factor: topics created with fewer than 2 replicas " +
                "lose the commands they accepted with one broker (decision D143)."
        }
        if (minInsync != null && minInsync < TopicDurability.DURABLE) {
            warnings += "wow.kafka.topics.min-insync-replicas is $minInsync: an acknowledged command may be on one " +
                "replica only (decision D143)."
        }
        return warnings
    }

    /** The warnings about the command topics [durability] reads: each that does not survive one broker's loss. */
    fun warnings(durability: Map<String, TopicDurability>): List<String> {
        if (dev) {
            return emptyList()
        }
        return durability.filterValues { !it.survivesOneBroker }.map { (topic, it) ->
            "Command topic $topic has replication factor ${it.replicationFactor} and min.insync.replicas " +
                "${it.minInsyncReplicas}: a command accepted on it is lost with one broker (decision D143 needs both " +
                "at least ${TopicDurability.DURABLE})."
        }
    }

    /**
     * The warnings about what [read] reads, within [timeout]; none in a dev profile, and none when the broker cannot
     * be asked (logged): the check is advice, never a reason to stop.
     */
    @Suppress("TooGenericExceptionCaught") // Whatever stops the check, the node runs on.
    fun checkQuietly(
        timeout: Duration = CHECK_TIMEOUT,
        read: suspend () -> Map<String, TopicDurability>
    ): List<String> {
        if (dev) {
            return emptyList()
        }
        return try {
            warnings(runBlocking { withTimeout(timeout) { read() } })
        } catch (cancelled: CancellationException) {
            logger.info(cancelled) { "The durability of the command topics could not be read in $timeout." }
            emptyList()
        } catch (failure: Exception) {
            logger.info(failure) { "The durability of the command topics could not be read." }
            emptyList()
        }
    }

    private companion object {
        const val MIN_INSYNC = "min.insync.replicas"
        val CHECK_TIMEOUT: Duration = 10.seconds
        val logger = KotlinLogging.logger {}
    }
}
